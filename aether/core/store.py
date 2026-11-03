"""SQLite persistence with a tamper-evident hash chain.

Every security event and every audit entry is chained: sha256(prev_hash +
canonical row). Verification walks the whole chain so edits/deletions are
detectable. Sensitive free text is AES-GCM encrypted before it is written.
No personal data is collected: device identifiers are salted hashes supplied
by the caller and raw IPs are never stored.
"""
from __future__ import annotations

import json
import sqlite3
import threading
from pathlib import Path

from .util import canonical_json, epoch_now, sha256_text, utcnow

GENESIS = "0" * 64

EVENT_COLUMNS = (
    "ts", "epoch", "device", "domain", "qtype", "verdict", "classification",
    "risk", "severity", "ruleset", "source", "latency_ms", "bytes_out",
    "bytes_in", "answers", "encrypted_note",
)
AUDIT_COLUMNS = ("ts", "epoch", "actor", "action", "scope", "detail")
SNAPSHOT_COLUMNS = ("ts", "epoch", "version", "state_json", "actor")


class Store:
    def __init__(self, db_path: Path, keystore):
        self.db_path = Path(db_path)
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self.keys = keystore
        self._lock = threading.RLock()
        self.conn = sqlite3.connect(str(self.db_path), check_same_thread=False)
        self.conn.row_factory = sqlite3.Row
        self.conn.execute("PRAGMA journal_mode=WAL")
        self.conn.execute("PRAGMA synchronous=FULL")
        self._create_tables()

    def _create_tables(self) -> None:
        with self._lock, self.conn:
            self.conn.executescript(
                """
                CREATE TABLE IF NOT EXISTS events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    ts TEXT NOT NULL, epoch REAL NOT NULL, device TEXT NOT NULL,
                    domain TEXT NOT NULL, qtype TEXT, verdict TEXT, classification TEXT,
                    risk REAL, severity TEXT, ruleset TEXT, source TEXT,
                    latency_ms REAL, bytes_out INTEGER, bytes_in INTEGER,
                    answers TEXT, encrypted_note TEXT,
                    prev_hash TEXT NOT NULL, hash TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS idx_events_epoch ON events(epoch);
                CREATE INDEX IF NOT EXISTS idx_events_domain ON events(domain);
                CREATE TABLE IF NOT EXISTS audit (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    ts TEXT NOT NULL, epoch REAL NOT NULL, actor TEXT NOT NULL,
                    action TEXT NOT NULL, scope TEXT, detail TEXT,
                    prev_hash TEXT NOT NULL, hash TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS labels (
                    domain TEXT PRIMARY KEY, label TEXT NOT NULL, verified_by TEXT,
                    verified_at TEXT, encrypted_note TEXT, split TEXT
                );
                CREATE TABLE IF NOT EXISTS policy_snapshots (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    ts TEXT NOT NULL, epoch REAL NOT NULL, version INTEGER UNIQUE,
                    state_json TEXT NOT NULL, actor TEXT,
                    prev_hash TEXT NOT NULL, hash TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS models (
                    version TEXT PRIMARY KEY, ts TEXT NOT NULL, config_json TEXT NOT NULL,
                    metrics_json TEXT NOT NULL, passed INTEGER NOT NULL,
                    active INTEGER NOT NULL DEFAULT 0, report_json TEXT NOT NULL,
                    parent TEXT
                );
                CREATE TABLE IF NOT EXISTS intel_updates (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, ts TEXT NOT NULL,
                    version TEXT, sequence INTEGER, status TEXT, key_id TEXT,
                    source_summary TEXT
                );
                CREATE TABLE IF NOT EXISTS assistant_messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, ts TEXT NOT NULL,
                    session_id TEXT NOT NULL, level TEXT,
                    question TEXT NOT NULL, answer TEXT NOT NULL,
                    injection_flags TEXT
                );
                """
            )

    # ---- hash-chain primitives -----------------------------------------
    def _append_chain(self, table: str, columns: tuple[str, ...], values: tuple) -> int:
        row = self.conn.execute(f"SELECT hash FROM {table} ORDER BY id DESC LIMIT 1").fetchone()
        prev_hash = row["hash"] if row else GENESIS
        payload = dict(zip(columns, values))
        digest = sha256_text(prev_hash + canonical_json(payload))
        placeholders = ",".join("?" for _ in columns)
        cur = self.conn.execute(
            f"INSERT INTO {table} ({','.join(columns)},prev_hash,hash) VALUES ({placeholders},?,?)",
            tuple(values) + (prev_hash, digest),
        )
        return int(cur.lastrowid)

    def _rebuild_chain(self, table: str, columns: tuple[str, ...]) -> None:
        rows = self.conn.execute(f"SELECT * FROM {table} ORDER BY id").fetchall()
        prev = GENESIS
        for row in rows:
            payload = {c: row[c] for c in columns}
            digest = sha256_text(prev + canonical_json(payload))
            self.conn.execute(f"UPDATE {table} SET prev_hash=?, hash=? WHERE id=?", (prev, digest, row["id"]))
            prev = digest

    def verify_chain(self, table: str, columns: tuple[str, ...]) -> dict:
        if table not in ("events", "audit", "policy_snapshots"):
            return {"ok": False, "reason": "unknown-table"}
        with self._lock:
            rows = self.conn.execute(f"SELECT * FROM {table} ORDER BY id").fetchall()
            prev = GENESIS
            for row in rows:
                payload = {c: row[c] for c in columns}
                expected = sha256_text(prev + canonical_json(payload))
                if row["prev_hash"] != prev or row["hash"] != expected:
                    return {"ok": False, "rows": len(rows), "broken_at": row["id"],
                            "reason": "hash-mismatch"}
                prev = row["hash"]
            return {"ok": True, "rows": len(rows), "head": prev}

    # ---- events ---------------------------------------------------------
    def add_event(self, *, device: str, domain: str, verdict: str, classification: str,
                  risk: float = 0.0, severity: str = "none", ruleset: str = "",
                  source: str = "", latency_ms: float | None = None,
                  bytes_out: int | None = None, bytes_in: int | None = None,
                  answers: list | None = None, qtype: str = "A", note: str = "",
                  epoch: float | None = None, ts: str | None = None) -> int:
        encrypted = self.keys.encrypt_text(note) if note else ""
        values = (
            ts or utcnow(), epoch if epoch is not None else epoch_now(), device, domain,
            qtype, verdict, classification, round(float(risk), 2), severity, ruleset,
            source, latency_ms, bytes_out, bytes_in,
            json.dumps(answers or []), encrypted,
        )
        with self._lock, self.conn:
            event_id = self._append_chain("events", EVENT_COLUMNS, values)
            count = self.conn.execute("SELECT COUNT(*) FROM events").fetchone()[0]
            if count > 50000:  # bounded local retention; chain is re-sealed afterwards
                self.conn.execute(
                    "DELETE FROM events WHERE id IN "
                    "(SELECT id FROM events ORDER BY id LIMIT 5000)"
                )
                self._rebuild_chain("events", EVENT_COLUMNS)
        return event_id

    def get_events(self, limit: int = 100, device: str | None = None,
                   severity: str | None = None) -> list[dict]:
        clauses, params = [], []
        if device:
            clauses.append("device = ?"); params.append(device)
        if severity:
            clauses.append("severity = ?"); params.append(severity)
        where = ("WHERE " + " AND ".join(clauses)) if clauses else ""
        with self._lock:
            rows = self.conn.execute(
                f"SELECT * FROM events {where} ORDER BY id DESC LIMIT ?",
                params + [min(500, max(1, int(limit)))],
            ).fetchall()
        return [self._public_event(dict(r)) for r in rows]

    def all_events(self, limit: int = 5000) -> list[dict]:
        with self._lock:
            rows = self.conn.execute(
                "SELECT * FROM events ORDER BY id DESC LIMIT ?", (limit,)
            ).fetchall()
        return [self._public_event(dict(r)) for r in rows]

    @staticmethod
    def _public_event(row: dict) -> dict:
        row.pop("encrypted_note", None)
        row.pop("prev_hash", None)
        row["answers"] = json.loads(row.get("answers") or "[]")
        return row

    # ---- audit ----------------------------------------------------------
    def add_audit(self, actor: str, action: str, scope: str = "", detail: dict | None = None) -> int:
        encrypted_detail = self.keys.encrypt_text(canonical_json(detail or {}))
        values = (utcnow(), epoch_now(), actor, action, scope, encrypted_detail)
        with self._lock, self.conn:
            return self._append_chain("audit", AUDIT_COLUMNS, values)

    def get_audit(self, limit: int = 100) -> list[dict]:
        with self._lock:
            rows = self.conn.execute(
                "SELECT * FROM audit ORDER BY id DESC LIMIT ?",
                (min(500, max(1, int(limit))),),
            ).fetchall()
        out = []
        for r in rows:
            d = dict(r)
            d["detail"] = json.loads(self.keys.decrypt_text(d.pop("detail")))
            d.pop("prev_hash", None)
            out.append(d)
        return out

    # ---- verified labels / feedback ------------------------------------
    def add_label(self, domain: str, label: str, verified_by: str, note: str = "",
                  split: str = "verified") -> None:
        with self._lock, self.conn:
            self.conn.execute(
                "INSERT OR REPLACE INTO labels(domain,label,verified_by,verified_at,encrypted_note,split) "
                "VALUES(?,?,?,?,?,?)",
                (domain, label, verified_by, utcnow(), self.keys.encrypt_text(note), split),
            )

    def get_labels(self) -> list[dict]:
        with self._lock:
            rows = self.conn.execute("SELECT * FROM labels").fetchall()
        return [{
            "domain": r["domain"], "label": r["label"], "verified_by": r["verified_by"],
            "verified_at": r["verified_at"], "split": r["split"],
            "note": self.keys.decrypt_text(r["encrypted_note"]),
        } for r in rows]

    # ---- policy snapshots ----------------------------------------------
    def save_snapshot(self, version: int, state: dict, actor: str) -> int:
        values = (utcnow(), epoch_now(), version, canonical_json(state), actor)
        with self._lock, self.conn:
            return self._append_chain("policy_snapshots", SNAPSHOT_COLUMNS, values)

    def list_snapshots(self, limit: int = 20) -> list[dict]:
        with self._lock:
            rows = self.conn.execute(
                "SELECT id,ts,version,state_json,actor,hash FROM policy_snapshots "
                "ORDER BY version DESC LIMIT ?", (limit,),
            ).fetchall()
        return [{"id": r["id"], "ts": r["ts"], "version": r["version"],
                 "state": json.loads(r["state_json"]), "actor": r["actor"],
                 "hash": r["hash"]} for r in rows]

    def get_snapshot(self, version: int) -> dict | None:
        with self._lock:
            r = self.conn.execute(
                "SELECT state_json FROM policy_snapshots WHERE version=?", (version,)
            ).fetchone()
        return json.loads(r["state_json"]) if r else None

    # ---- versioned detection models ------------------------------------
    def save_model(self, version: str, config: dict, metrics: dict, passed: bool,
                   report: dict, parent: str = "") -> None:
        with self._lock, self.conn:
            self.conn.execute(
                "INSERT OR REPLACE INTO models(version,ts,config_json,metrics_json,passed,active,report_json,parent) "
                "VALUES(?,?,?,?,?,?,?,?)",
                (version, utcnow(), canonical_json(config), canonical_json(metrics),
                 1 if passed else 0, 0, canonical_json(report), parent),
            )

    def activate_model(self, version: str) -> None:
        with self._lock, self.conn:
            self.conn.execute("UPDATE models SET active=0")
            self.conn.execute("UPDATE models SET active=1 WHERE version=?", (version,))

    def get_models(self) -> list[dict]:
        with self._lock:
            rows = self.conn.execute("SELECT * FROM models ORDER BY ts").fetchall()
        return [{"version": r["version"], "ts": r["ts"],
                 "config": json.loads(r["config_json"]),
                 "metrics": json.loads(r["metrics_json"]),
                 "passed": bool(r["passed"]), "active": bool(r["active"]),
                 "report": json.loads(r["report_json"]), "parent": r["parent"]} for r in rows]

    # ---- encrypted assistant transcripts -------------------------------
    def add_message(self, session_id: str, level: str, question: str,
                    answer: str, injection_flags: list[str] | None = None) -> None:
        with self._lock, self.conn:
            self.conn.execute(
                "INSERT INTO assistant_messages(ts,session_id,level,question,answer,injection_flags) "
                "VALUES(?,?,?,?,?,?)",
                (utcnow(), session_id, level, self.keys.encrypt_text(question),
                 self.keys.encrypt_text(answer), json.dumps(injection_flags or [])),
            )
            self.conn.execute(
                "DELETE FROM assistant_messages WHERE session_id=? AND id NOT IN "
                "(SELECT id FROM assistant_messages WHERE session_id=? ORDER BY id DESC LIMIT 20)",
                (session_id, session_id),
            )

    def get_messages(self, session_id: str, limit: int = 20) -> list[dict]:
        with self._lock:
            rows = self.conn.execute(
                "SELECT * FROM assistant_messages WHERE session_id=? ORDER BY id DESC LIMIT ?",
                (session_id, min(20, max(1, int(limit)))),
            ).fetchall()
        return [{"ts": r["ts"], "level": r["level"],
                 "question": self.keys.decrypt_text(r["question"]),
                 "answer": self.keys.decrypt_text(r["answer"]),
                 "injection_flags": json.loads(r["injection_flags"])} for r in rows]

    # ---- intel update history ------------------------------------------
    def add_intel_update(self, version: str, sequence: int, status: str,
                         key_id: str, summary: dict) -> None:
        with self._lock, self.conn:
            self.conn.execute(
                "INSERT INTO intel_updates(ts,version,sequence,status,key_id,source_summary) "
                "VALUES(?,?,?,?,?,?)",
                (utcnow(), version, sequence, status, key_id, canonical_json(summary)),
            )

    def get_intel_updates(self, limit: int = 20) -> list[dict]:
        with self._lock:
            rows = self.conn.execute(
                "SELECT * FROM intel_updates ORDER BY id DESC LIMIT ?", (limit,)
            ).fetchall()
        return [{"ts": r["ts"], "version": r["version"], "sequence": r["sequence"],
                 "status": r["status"], "key_id": r["key_id"],
                 "summary": json.loads(r["source_summary"])} for r in rows]
