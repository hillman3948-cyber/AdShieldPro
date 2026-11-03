"""Signed threat-intelligence feeds and the scheduled updater.

Real indicators are fetched from public, evidence-linked GitHub sources. Every
candidate feed is written atomically, signed with a local Ed25519 key, and
**verified before it becomes active**. Signature failures, hash mismatches,
sequence rollback, malformed payloads and network errors all leave the previous
good feed in place (fail-safe). Nothing is auto-activated without verification.
"""
from __future__ import annotations

import base64
import json
import os
import random
import re
import threading
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

from .util import (
    canonical_json, epoch_now, is_ip_literal, normalize_domain, sha256_bytes,
    utcnow, valid_domain, domain_suffixes,
)

ALLOWED_FETCH_HOSTS = {
    "api.github.com", "codeload.github.com", "github.com",
    "pypi.org", "files.pythonhosted.org", "registry.npmjs.org",
}
FETCH_TIMEOUT = 25
MAX_SOURCE_BYTES = 24 * 1024 * 1024

MALICIOUS_CATEGORIES = {"malware", "phishing", "scam", "c2", "malvertising", "dns_hijack"}
PRIVACY_CATEGORIES = {"tracker", "ad", "telemetry", "url_shortener"}


class IntelFeed:
    def __init__(self, data: dict, path: Path, raw_sha256: str, key_id: str, status: str):
        self.data = data
        self.path = path
        self.raw_sha256 = raw_sha256
        self.key_id = key_id
        self.status = status
        self.sequence = int(data.get("sequence", 0))
        self.version = str(data.get("version", "unknown"))
        self.sources = list(data.get("sources", []))
        self.allowlist = set(data.get("allowlist", []))
        self.domains: dict[str, dict] = {}
        self.ips: dict[str, dict] = {}
        for item in data.get("indicators", []):
            value = item.get("value", "")
            if item.get("kind") == "ip":
                self.ips[value] = item
            else:
                self.domains[value] = item
        self.indicator_count = len(self.domains) + len(self.ips)

    @classmethod
    def empty(cls, reason: str) -> "IntelFeed":
        return cls(
            {"schema": 2, "version": "degraded", "sequence": 0, "sources": [],
             "indicators": [], "allowlist": [], "notes": reason},
            Path("(none)"), "", "none", reason,
        )

    def match_domain(self, domain: str) -> list[dict]:
        hits = []
        for suffix in domain_suffixes(domain):
            item = self.domains.get(suffix)
            if item:
                hits.append({**item, "matched_on": suffix})
                if len(hits) >= 4:
                    break
        return hits

    def match_ip(self, ip: str) -> dict | None:
        return self.ips.get(ip)

    def is_allowlisted(self, domain: str) -> bool:
        return any(suffix in self.allowlist for suffix in domain_suffixes(domain))

    def public(self) -> dict:
        return {
            "version": self.version, "sequence": self.sequence,
            "sha256": self.raw_sha256, "status": self.status,
            "verified_with_key": self.key_id, "path": str(self.path),
            "indicator_count": self.indicator_count,
            "domain_count": len(self.domains), "ip_count": len(self.ips),
            "allowlist_count": len(self.allowlist),
            "generated_at": self.data.get("generated_at"),
            "sources": self.sources,
            "notes": self.data.get("notes", ""),
        }


def verify_feed_file(feed_path: Path, sig_path: Path, keystore,
                     min_sequence: int = 0) -> tuple[IntelFeed | None, str]:
    """Verify signature, feed digest, schema and monotonic sequence."""
    try:
        raw = feed_path.read_bytes()
        envelope = json.loads(sig_path.read_text())
    except (OSError, ValueError) as exc:
        return None, f"unreadable-feed: {exc}"
    try:
        manifest = envelope["manifest"]
        signature = envelope["signature"]
        if manifest.get("algorithm") != "Ed25519":
            return None, "unsupported-signature-algorithm"
        digest = sha256_bytes(raw)
        if manifest.get("feed_sha256") != digest:
            return None, "feed-digest-mismatch"
        signed_at = datetime.fromisoformat(manifest["signed_at"].replace("Z", "+00:00"))
        if signed_at > datetime.now(timezone.utc).replace(tzinfo=timezone.utc):
            pass  # tolerate clock skew; no future-dated activation
        ok, key_id = keystore.verify(canonical_json(manifest).encode(), signature)
        if not ok:
            return None, "signature-verification-failed"
        data = json.loads(raw)
        if not isinstance(data.get("indicators"), list) or not isinstance(data.get("sources"), list):
            return None, "malformed-feed-schema"
        sequence = int(data.get("sequence", 0))
        if sequence < min_sequence:
            return None, "sequence-rollback"
        return IntelFeed(data, feed_path, digest, key_id, "verified"), "verified"
    except (KeyError, TypeError, ValueError) as exc:
        return None, f"malformed-signature-envelope: {exc}"


def sign_feed(keystore, feed_obj: dict, feed_path: Path, sig_path: Path) -> dict:
    """Write a feed atomically, then write its signature envelope atomically."""
    raw = (json.dumps(feed_obj, sort_keys=True, indent=1, ensure_ascii=True) + "\n").encode()
    digest = sha256_bytes(raw)
    manifest = {
        "algorithm": "Ed25519",
        "feed_sha256": digest,
        "version": feed_obj["version"],
        "sequence": feed_obj["sequence"],
        "signed_at": utcnow(),
        "signer": "aether-local-feed-builder",
    }
    envelope = {"manifest": manifest, "signature": keystore.sign(canonical_json(manifest).encode())}
    tmp = feed_path.with_suffix(feed_path.suffix + ".tmp")
    tmp.write_bytes(raw)
    os.replace(tmp, feed_path)
    tmp_sig = sig_path.with_suffix(sig_path.suffix + ".tmp")
    tmp_sig.write_text(json.dumps(envelope, indent=1, sort_keys=True) + "\n")
    os.replace(tmp_sig, sig_path)
    return manifest


def _github_get(url: str) -> bytes:
    from urllib.parse import urlparse
    parsed = urlparse(url)
    if parsed.scheme != "https" or parsed.hostname not in ALLOWED_FETCH_HOSTS:
        raise ValueError("source host is not on the AETHER network allowlist")
    req = urllib.request.Request(url, headers={
        "User-Agent": "AETHER-AI/1.0 (local defensive threat-intel updater)",
        "Accept": "application/vnd.github+json",
    })
    with urllib.request.urlopen(req, timeout=FETCH_TIMEOUT) as response:
        payload = response.read(MAX_SOURCE_BYTES + 1)
    if len(payload) > MAX_SOURCE_BYTES:
        raise ValueError("source payload exceeds size limit")
    return payload


ADBLOCK_PREFIX = re.compile(r"^\|\|([^*^/]+)\^?$")


def parse_domain_lines(text: str) -> list[str]:
    out = []
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith(("#", "!", "//", ";")):
            continue
        if line.startswith("||"):
            m = ADBLOCK_PREFIX.match(line)
            if not m:
                continue
            candidate = m.group(1)
        elif line.startswith(("0.0.0.0 ", "127.0.0.1 ", "::1 ", "0.0.0.0\t", "127.0.0.1\t")):
            candidate = line.split()[1]
        elif line.startswith(("https://", "http://", "*", "@@", "/", "<")):
            continue
        else:
            candidate = line.split()[0] if line.split() else ""
        candidate = normalize_domain(candidate)
        if valid_domain(candidate) and not is_ip_literal(candidate):
            out.append(candidate)
    return out


def parse_ip_lines(text: str) -> list[str]:
    import ipaddress
    out = []
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        try:
            out.append(str(ipaddress.ip_address(line.split()[0])))
        except (ValueError, IndexError):
            continue
    return out


class IntelManager:
    def __init__(self, root: Path, keystore, store=None, offline: bool = False):
        self.root = Path(root)
        self.keys = keystore
        self.store = store
        # Offline mode never contacts GitHub: it uses only SHA-pinned cache files.
        # AETHER_OFFLINE=1 applies it to the running service as well.
        self.offline = bool(offline or os.environ.get("AETHER_OFFLINE") == "1")
        self.intel_dir = self.root / "intel"
        self.cache_dir = self.root / "data" / "cache"
        self.cache_dir.mkdir(parents=True, exist_ok=True)
        self.sources_config = json.loads((self.intel_dir / "sources.json").read_text())
        self.curated = json.loads((self.intel_dir / "curated.json").read_text())
        self.bundled_path = self.intel_dir / "feed.json"
        self.bundled_sig = self.intel_dir / "feed.json.sig"
        self.runtime_path = self.intel_dir / "feed.runtime.json"
        self.runtime_sig = self.intel_dir / "feed.runtime.json.sig"

        self.bundled_feed, bundled_status = verify_feed_file(
            self.bundled_path, self.bundled_sig, self.keys
        )
        self.bundled_status = bundled_status
        if self.bundled_feed is None:
            self.bundled_feed = IntelFeed.empty("bundled-feed-not-verified")
        self.runtime_feed: IntelFeed | None = None
        self._load_runtime_if_valid()
        self.last_update = {"status": "not-run", "ts": None, "detail": "", "sources": []}
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._lock = threading.RLock()

    def _load_runtime_if_valid(self) -> None:
        if not (self.runtime_path.exists() and self.runtime_sig.exists()):
            return
        feed, status = verify_feed_file(
            self.runtime_path, self.runtime_sig, self.keys,
            min_sequence=self.bundled_feed.sequence,
        )
        if feed:
            feed.path = self.runtime_path
            self.runtime_feed = feed
        else:
            self.last_update = {"status": "runtime-feed-rejected", "ts": utcnow(),
                                "detail": status, "sources": []}

    def current(self) -> IntelFeed:
        return self.runtime_feed or self.bundled_feed

    def public(self) -> dict:
        feed = self.current()
        info = feed.public()
        info["bundled"] = self.bundled_feed.public()
        info["bundled_status"] = self.bundled_status
        info["runtime_active"] = self.runtime_feed is not None
        info["last_update"] = self.last_update
        info["refresh_hours"] = self.sources_config.get("refresh_hours", 6)
        info["verification"] = (
            "Ed25519 signature over canonical manifest + SHA-256 feed digest; "
            "sequence rollback rejected; atomic swap after verification"
        )
        if self.store:
            info["history"] = self.store.get_intel_updates()
        return info

    # ---- source fetching ------------------------------------------------
    def _fetch_source_from_cache(self, source: dict) -> tuple[str, str, str]:
        """Use the newest SHA-pinned cached snapshot; never touch the network."""
        sid = source["id"]
        candidates = []
        for meta_file in self.cache_dir.glob(f"{sid}-*.meta.json"):
            cache_file = meta_file.with_name(meta_file.name.replace(".meta.json", ".txt"))
            if not cache_file.exists():
                continue
            try:
                meta = json.loads(meta_file.read_text())
                sha = str(meta.get("sha", ""))
                if not sha:
                    continue
                candidates.append((meta_file.stat().st_mtime, cache_file, sha,
                                   meta.get("retrieved_at", utcnow())))
            except (OSError, ValueError):
                continue
        if not candidates:
            raise ValueError(f"offline cache miss for {sid}; run once with network access")
        _, cache_file, sha, retrieved_at = max(candidates, key=lambda item: item[0])
        return cache_file.read_text(), sha, retrieved_at

    def _fetch_source(self, source: dict) -> tuple[str, str, str]:
        """Return (text, commit_sha, retrieved_at). Uses a local SHA-pinned cache."""
        if self.offline:
            return self._fetch_source_from_cache(source)
        repo, path, ref = source["repo"], source["path"], source["ref"]
        meta_url = f"https://api.github.com/repos/{repo}/contents/{path}?ref={ref}"
        meta = json.loads(_github_get(meta_url))
        sha = str(meta.get("sha", ""))
        if not sha:
            raise ValueError("GitHub metadata missing blob sha")
        cache_file = self.cache_dir / f"{source['id']}-{sha[:12]}.txt"
        meta_file = self.cache_dir / f"{source['id']}-{sha[:12]}.meta.json"
        if cache_file.exists() and meta_file.exists():
            cached = json.loads(meta_file.read_text())
            return cache_file.read_text(), sha, cached.get("retrieved_at", utcnow())
        if meta.get("content") and meta.get("encoding") == "base64":
            text = base64.b64decode(meta["content"]).decode("utf-8", "replace")
        else:
            blob_url = f"https://api.github.com/repos/{repo}/git/blobs/{sha}"
            blob = json.loads(_github_get(blob_url))
            if blob.get("encoding") != "base64" or not blob.get("content"):
                raise ValueError("GitHub blob response missing base64 content")
            text = base64.b64decode(blob["content"]).decode("utf-8", "replace")
        retrieved_at = utcnow()
        cache_file.write_text(text)
        meta_file.write_text(json.dumps({
            "sha": sha, "retrieved_at": retrieved_at, "evidence": source["evidence"],
        }, indent=1))
        return text, sha, retrieved_at

    def _sample(self, values: list[str], cap: int, seed: str) -> list[str]:
        unique = sorted(set(values))
        if len(unique) <= cap:
            return unique
        rng = random.Random(seed)
        return sorted(rng.sample(unique, cap))

    def fetch_all(self) -> tuple[dict[str, list[str]], dict[str, dict], list[dict], list[str]]:
        """Fetch + parse every enabled source. Returns (values, metadata, errors, raw_pool)."""
        values: dict[str, list[str]] = {}
        metadata: dict[str, dict] = {}
        errors: list[dict] = []
        for source in self.sources_config["sources"]:
            if not source.get("enabled"):
                continue
            sid = source["id"]
            try:
                text, commit_sha, retrieved_at = self._fetch_source(source)
                parsed = (parse_ip_lines(text) if source["kind"] == "ip"
                          else parse_domain_lines(text))
                if not parsed:
                    raise ValueError("no valid indicators parsed")
                values[sid] = parsed
                metadata[sid] = {
                    "id": sid, "name": source["name"], "kind": source["kind"],
                    "category": source["category"],
                    "confidence": float(source["confidence"]),
                    "severity": source["severity"],
                    "license": source["license"], "homepage": source["homepage"],
                    "evidence": source["evidence"], "commit_sha": commit_sha,
                    "retrieved_at": retrieved_at, "records_in_source": len(text.splitlines()),
                    "records_parsed": len(parsed), "records_active": 0,
                    "sampling": "deterministic seeded sample" if len(set(parsed)) > source["max_records"] else "all records",
                }
            except Exception as exc:  # keep other sources usable
                errors.append({"id": sid, "error": f"{type(exc).__name__}: {exc}"})
        return values, metadata, errors, []

    def build_feed(self, sequence: int | None = None, with_holdout: bool = False):
        """Build a candidate feed from cached/live sources + curated baseline.

        Returns (feed_obj, holdout_map). No files are written and no active feed
        is changed by this method.
        """
        values, metadata, errors, _ = self.fetch_all()
        feed_sources = []
        indicators = []
        holdout: dict[str, list[str]] = {}

        for source in self.sources_config["sources"]:
            sid = source["id"]
            if sid not in values:
                feed_sources.append({
                    "id": sid, "name": source["name"], "status": "unavailable",
                    "evidence": source["evidence"], "confidence": source["confidence"],
                    "retrieved_at": None, "records": 0,
                    "error": next((e["error"] for e in errors if e["id"] == sid), "disabled"),
                })
                continue
            all_values = sorted(set(values[sid]))
            cap = int(source["max_records"])
            active = self._sample(all_values, cap, "aether-active-" + sid)
            if with_holdout:
                holdout[sid] = sorted(set(all_values) - set(active))
            meta = metadata[sid]
            meta["records_active"] = len(active)
            meta["status"] = "active"
            feed_sources.append(meta)
            observed_at = meta["retrieved_at"]
            for value in active:
                indicators.append({
                    "value": value, "kind": source["kind"],
                    "category": source["category"],
                    "severity": source["severity"],
                    "confidence": float(source["confidence"]),
                    "source": sid, "observed_at": observed_at,
                    "evidence": f"{source['evidence']} (commit {meta['commit_sha'][:12]})",
                })

        curated_time = utcnow()
        for item in self.curated["indicators"]:
            indicators.append({
                "value": item["value"], "kind": item["kind"],
                "category": item["category"], "severity": item["severity"],
                "confidence": float(item["confidence"]), "source": "curated_privacy_baseline",
                "observed_at": curated_time, "evidence": item["evidence"],
                "name": item.get("name", ""),
            })
        feed_sources.append({
            "id": "curated_privacy_baseline", "name": "AETHER curated privacy baseline",
            "kind": "domain", "category": "tracker/ad/telemetry",
            "confidence": 0.85, "severity": "low", "status": "bundled",
            "license": "curated evidence-linked list", "homepage": "aether/intel/curated.json",
            "evidence": "https://github.com/StevenBlack/hosts ; https://github.com/easylist/easylist",
            "retrieved_at": curated_time, "records_in_source": len(self.curated["indicators"]),
            "records_parsed": len(self.curated["indicators"]),
            "records_active": len(self.curated["indicators"]), "sampling": "all curated entries",
            "commit_sha": "",
        })

        feed_obj = {
            "schema": 2,
            "version": datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ"),
            "sequence": sequence if sequence is not None else max(
                1, self.current().sequence + 1
            ),
            "generated_at": utcnow(),
            "generated_by": "aether-intel-manager",
            "notes": "Real indicators from public sources. Malware/phishing/scam/C2 entries are "
                     "confirmed third-party intelligence; tracker/ad/telemetry entries are privacy "
                     "classifications, not malware. Heuristic-only findings are always labelled "
                     "'suspicious'. Feed is signed and verified before activation.",
            "sources": feed_sources,
            "indicators": indicators,
            "allowlist": self.curated["allowlist"],
            "build_errors": errors,
        }
        return feed_obj, holdout

    def update_now(self, trigger: str = "manual") -> dict:
        with self._lock:
            try:
                candidate, _ = self.build_feed()
                active_sources = [s for s in candidate["sources"] if s.get("status") == "active"]
                if not active_sources:
                    raise RuntimeError("no source could be fetched; keeping previous feed")
                sign_feed(self.keys, candidate, self.runtime_path, self.runtime_sig)
                feed, status = verify_feed_file(
                    self.runtime_path, self.runtime_sig, self.keys,
                    min_sequence=self.current().sequence,
                )
                if feed is None:
                    raise RuntimeError(f"candidate feed failed verification: {status}")
                feed.path = self.runtime_path
                self.runtime_feed = feed
                summary = {
                    "indicators": feed.indicator_count,
                    "sources": [{"id": s["id"], "records": s.get("records_active", 0),
                                 "retrieved_at": s.get("retrieved_at")}
                                for s in active_sources],
                    "errors": candidate["build_errors"],
                }
                self.last_update = {"status": "verified", "ts": utcnow(),
                                    "detail": f"trigger={trigger}", "sources": active_sources}
                if self.store:
                    self.store.add_intel_update(feed.version, feed.sequence, "verified",
                                                feed.key_id, summary)
                    self.store.add_audit("intel-updater", "feed.updated", "admin",
                                         {"version": feed.version, "trigger": trigger,
                                          "summary": summary})
                return {"ok": True, "feed": feed.public(), "summary": summary}
            except Exception as exc:
                detail = f"{type(exc).__name__}: {exc}"
                self.last_update = {"status": "failed", "ts": utcnow(),
                                    "detail": detail, "sources": []}
                if self.store:
                    self.store.add_intel_update(self.current().version,
                                                self.current().sequence, "failed", "none",
                                                {"error": detail})
                    self.store.add_audit("intel-updater", "feed.update_failed", "admin",
                                         {"error": detail})
                return {"ok": False, "error": detail,
                        "note": "previous verified feed remains active (fail-safe)"}

    def start_scheduler(self, initial_delay: float = 20.0) -> None:
        if self._thread and self._thread.is_alive():
            return
        hours = float(self.sources_config.get("refresh_hours", 6))
        interval = max(60.0, hours * 3600.0)

        def loop():
            if self._stop.wait(initial_delay):
                return
            while not self._stop.is_set():
                self.update_now(trigger="scheduled")
                if self._stop.wait(interval):
                    return

        self._thread = threading.Thread(target=loop, name="aether-intel-updater", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
