"""AETHER AI HTTP service: authenticated JSON APIs + same-origin dashboard.

Security properties: per-session tokens, CSRF double-submit for browser writes,
scope-based permissions, rate limiting, strict input limits, security headers,
encrypted sensitive fields, tamper-evident audit log, and fail-open analysis.
"""
from __future__ import annotations

import json
import os
import re
import resource
import secrets
import threading
import time
import traceback
from collections import defaultdict, deque
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

from .assistant import SecurityAssistant
from .crypto import KeyStore
from .detection import DetectionEngine
from .education import EducationCenter
from .guardian import NetworkGuardian, real_dns_lookup
from .improvement import ImprovementEngine
from .intel import IntelManager
from .policy import PolicyEngine
from .privacy import PrivacyIntelligence
from .store import Store, EVENT_COLUMNS, AUDIT_COLUMNS, SNAPSHOT_COLUMNS
from .util import canonical_json, epoch_now, hmac_equal, normalize_domain, sanitize_text, utcnow

MAX_BODY = 64 * 1024
RATE_LIMIT = 240  # requests per minute per identity
DEFAULT_SCOPES = {"read", "analyze", "feedback", "approve", "policy"}
AGENT_SCOPES = {"read", "analyze", "feedback", "approve", "policy", "admin", "agent"}

MODULES = [
    ("M1", "AETHER Security Brain", "Explainable rule engine with evidence, confidence and 3 explanation levels"),
    ("M2", "Universal Cyber Defense", "Signed intel for phishing, malware, scam, C2, trackers and malvertising"),
    ("M3", "Autonomous Protection", "Policy engine, approved expiring allowlist, versioned rollback, fail-safe DNS"),
    ("M4", "AI Security Assistant", "Tool-using assistant with prompt-injection resistance and 3 levels"),
    ("M5", "AI Threat Intelligence", "Ed25519-signed, timestamped, scheduled GitHub-sourced feeds"),
    ("M6", "AI Network Guardian", "Real DNS measurement, event history and anomaly detection"),
    ("M7", "AI Privacy Intelligence", "Tracker classification and user-selectable privacy levels"),
    ("M8", "AI Security Education", "Lessons, graded quizzes and permission-gated defensive labs"),
    ("M9", "Global Security Dashboard", "Live dashboard built only from measured local data"),
    ("M10", "Security, Privacy & Reliability", "AES-GCM at rest, auth, scopes, audit chain, minimal collection"),
    ("M11", "Cross-Platform Intelligence", "Android VPN + desktop agent + browser evidence (documented limits)"),
    ("M12", "Continuous Improvement", "Measured accuracy, validation gate, versioned models, rollback"),
]


class Session:
    def __init__(self, token: str, csrf: str, scopes: set[str], source: str):
        self.token = token
        self.csrf = csrf
        self.scopes = set(scopes)
        self.source = source
        self.created = epoch_now()


class AetherApp:
    def __init__(self, root: Path, *, auto_update: bool = True):
        self.root = Path(root)
        self.started_at = epoch_now()
        self.keys = KeyStore(self.root)
        self.store = Store(self.root / "data" / "aether.sqlite3", self.keys)
        self.intel = IntelManager(self.root, self.keys, self.store)
        self.detection = DetectionEngine(self.intel)
        self.policy = PolicyEngine(self.store)
        self.guardian = NetworkGuardian(self.store, self.detection, self.policy, self.intel)
        self.privacy_engine = PrivacyIntelligence(self.intel)
        self.education = EducationCenter(self.store)
        self.improvement = ImprovementEngine(self.root, self.store, self.intel, self.detection)
        self.assistant = SecurityAssistant(self.detection, self.policy, self.guardian,
                                           self.intel, self.education, self.store)
        self.sessions: dict[str, Session] = {}
        self.rate: dict[str, deque] = defaultdict(deque)
        self._lock = threading.RLock()
        self.auto_update = auto_update and os.environ.get("AETHER_AUTO_UPDATE", "1") != "0"
        self.store.add_audit("system", "service.started", "system",
                             {"version": "aether-1.0.0", "auto_update": self.auto_update})
        if self.auto_update:
            self.intel.start_scheduler(initial_delay=float(os.environ.get("AETHER_UPDATE_DELAY", "25")))

    # ---- auth -----------------------------------------------------------
    def _session_from_request(self, handler) -> Session | None:
        auth = handler.headers.get("Authorization", "")
        if auth.startswith("Bearer "):
            token = auth[7:].strip()
            if hmac_equal(token, self.keys.api_token):
                with self._lock:
                    session = self.sessions.get("token-agent")
                    if session is None:
                        session = Session(token, "", AGENT_SCOPES, "operator-token")
                        self.sessions["token-agent"] = session
                    return session
            return None
        cookie = SimpleCookie()
        try:
            cookie.load(handler.headers.get("Cookie", ""))
        except Exception:
            return None
        morsel = cookie.get("aether_session")
        if not morsel:
            return None
        with self._lock:
            return self.sessions.get(morsel.value)

    def _new_browser_session(self) -> Session:
        with self._lock:
            session = Session(secrets.token_urlsafe(32), secrets.token_urlsafe(32),
                              DEFAULT_SCOPES, "browser")
            self.sessions[session.token] = session
            return session

    def _rate_ok(self, identity: str) -> bool:
        now = time.time()
        with self._lock:
            bucket = self.rate[identity]
            while bucket and now - bucket[0] > 60:
                bucket.popleft()
            if len(bucket) >= RATE_LIMIT:
                return False
            bucket.append(now)
            return True

    def stop(self) -> None:
        self.intel.stop()

    # ---- shared helpers -------------------------------------------------
    def _modules(self) -> list[dict]:
        feed = self.intel.current()
        stats = self.guardian.stats()
        models = self.improvement.status()
        return [
            {"id": mid, "name": name, "description": desc, "status": "active",
             "detail": (
                 f"{len(feed.domains)} domain + {len(feed.ips)} IP indicators" if mid == "M2" else
                 f"ruleset {self.detection.version}, {len(self.policy.get_state()['user_rules'])} user rules" if mid == "M3" else
                 f"{stats['total_events']} real events" if mid == "M6" else
                 f"active model {models['active']['version'] if models['active'] else 'none'}" if mid == "M12" else
                 "operational"
             )} for mid, name, desc in MODULES
        ]

    def _recommendations(self, stats: dict, intel: dict, models: dict) -> list[dict]:
        recs = []
        state = self.policy.get_state()
        if not stats["total_events"]:
            recs.append({"priority": "info", "action": "Generate real telemetry with Investigate → Real DNS lookup or connect the desktop agent.",
                         "evidence": "0 events in local database", "source": "local event store"})
        if not state["auto_block_confirmed"]:
            recs.append({"priority": "high", "action": "Enable automatic blocking of confirmed threats.",
                         "evidence": "auto_block_confirmed=false", "source": "policy state"})
        if state["privacy_level"] == "minimal":
            recs.append({"priority": "medium", "action": "Consider the balanced privacy level to see tracker findings.",
                         "evidence": "privacy_level=minimal", "source": "policy state"})
        for anomaly in stats["anomalies"][:3]:
            recs.append({"priority": "high", "action": f"Review {anomaly['type']} on {anomaly.get('domain', anomaly.get('device','device'))}.",
                         "evidence": anomaly["evidence"], "source": anomaly["source"]})
        last = intel.get("last_update", {})
        if last.get("status") == "failed":
            recs.append({"priority": "medium", "action": "Threat-intel update failed; the previous verified feed is still active.",
                         "evidence": last.get("detail", ""), "source": "intel updater"})
        if intel.get("bundled_status") != "verified" and not intel.get("runtime_active"):
            recs.append({"priority": "critical", "action": "Rebuild the signed feed: no verified intelligence is loaded.",
                         "evidence": intel.get("bundled_status", ""), "source": "feed verifier"})
        if models.get("active") and not models["active"]["passed"]:
            recs.append({"priority": "high", "action": "Roll back the active detection model: it failed validation.",
                         "evidence": "model.passed=false", "source": "model registry"})
        audit = self.store.verify_chain("audit", AUDIT_COLUMNS)
        if not audit["ok"]:
            recs.append({"priority": "critical", "action": "Audit chain verification failed — investigate local tampering.",
                         "evidence": f"broken at row {audit.get('broken_at')}", "source": "audit verifier"})
        return recs

    def status(self) -> dict:
        feed = self.intel.current()
        rusage = resource.getrusage(resource.RUSAGE_SELF)
        return {
            "service": "AETHER AI", "version": "1.0.0", "started_at": utcnow(),
            "uptime_seconds": round(epoch_now() - self.started_at, 1),
            "protection": "active" if feed.status == "verified" else "degraded",
            "intel_verified": feed.status == "verified",
            "intel_indicators": feed.indicator_count,
            "ruleset_version": self.detection.version,
            "policy_version": self.policy.version,
            "modules": self._modules(),
            "authentication": {"method": "HttpOnly session cookie + CSRF, or Bearer operator token",
                               "scopes_available": sorted(AGENT_SCOPES)},
            "audit_chain_ok": self.store.verify_chain("audit", AUDIT_COLUMNS)["ok"],
            "resources": {"max_rss_kb": rusage.ru_maxrss,
                          "cpu_user_seconds": round(rusage.ru_utime, 3),
                          "cpu_system_seconds": round(rusage.ru_stime, 3),
                          "note": "Measured with getrusage for this process; no estimates."},
            "limits": ["DNS-visible names only; no HTTPS payload inspection",
                       "No universal application-level ad blocking",
                       "No offensive actions; no file execution",
                       "Fail-open DNS on analysis engine error"],
        }

    def dashboard(self) -> dict:
        stats = self.guardian.stats()
        intel = self.intel.public()
        models = self.improvement.status()
        audit = {
            "audit": self.store.verify_chain("audit", AUDIT_COLUMNS),
            "events": self.store.verify_chain("events", EVENT_COLUMNS),
            "policy_snapshots": self.store.verify_chain("policy_snapshots", SNAPSHOT_COLUMNS),
        }
        return {
            "generated_at": utcnow(),
            "status": {k: v for k, v in self.status().items() if k != "modules"},
            "modules": self._modules(),
            "stats": stats,
            "policy": self.policy.get_state(),
            "intel": intel,
            "models": {k: v for k, v in models.items() if k != "versions"} | {"versions": models["versions"]},
            "audit_integrity": audit,
            "recent_events": self.store.get_events(limit=12),
            "recent_audit": self.store.get_audit(limit=8),
            "recommendations": self._recommendations(stats, intel, models),
        }

    # ---- analysis -------------------------------------------------------
    def analyze(self, body: dict, actor: str) -> dict:
        target = sanitize_text(body.get("target") or body.get("domain") or "", 2048)
        if not target:
            raise ValueError("target is required")
        chain = body.get("redirect_chain") if isinstance(body.get("redirect_chain"), list) else []
        answers = body.get("answers") if isinstance(body.get("answers"), list) else []
        domain = normalize_domain(target)
        analysis = self.detection.safe_analyze(
            target, redirect_chain=chain[:12], answers=answers[:32],
            beaconing=self.guardian.beacon_context(domain),
        )
        decision = self.policy.decide(analysis, actor=actor)
        resolution = None
        if body.get("resolve") and decision["action"] != "block":
            resolution = real_dns_lookup(domain)
            # Re-evaluate using real observed answers (bogon / IP reputation).
            analysis = self.detection.safe_analyze(
                target, redirect_chain=chain[:12], answers=resolution["answers"],
                resolution=resolution, beaconing=self.guardian.beacon_context(domain))
            decision = self.policy.decide(analysis, actor=actor)
        elif body.get("resolve"):
            resolution = {"skipped": True,
                          "reason": "destination blocked by confirmed intelligence; no lookup performed"}
        event = self.guardian.record(
            device=self._device(body.get("device_id")), domain=domain or target,
            analysis=analysis, decision=decision, resolution=resolution or {},
            qtype=sanitize_text(str(body.get("qtype", "A")), 8),
            note=sanitize_text(str(body.get("note", "")), 300),
        )
        if decision["action"] in ("block", "flag") or decision.get("user_override"):
            self.store.add_audit(actor, "decision." + decision["action"], "analyze",
                                 {"domain": domain, "classification": analysis["classification"],
                                  "risk": analysis["risk"], "reason": decision["reason_code"],
                                  "ruleset": self.detection.version})
        if analysis.get("classification") == "engine_error":
            self.store.add_audit(actor, "detection.engine_error", "system",
                                 {"target": target, "decision": "allow (fail-safe)"})
        return {"analysis": analysis, "decision": decision, "resolution": resolution, "event": event}

    def dns_query(self, body: dict, actor: str) -> dict:
        domain = normalize_domain(sanitize_text(body.get("domain", ""), 1024))
        if not domain:
            raise ValueError("valid domain is required")
        resolution = real_dns_lookup(domain)
        analysis = self.detection.safe_analyze(
            domain, answers=resolution["answers"], resolution=resolution,
            beaconing=self.guardian.beacon_context(domain))
        decision = self.policy.decide(analysis, actor=actor)
        event = self.guardian.record(
            device=self._device(body.get("device_id")), domain=domain, analysis=analysis,
            decision=decision, resolution=resolution,
            note=sanitize_text(str(body.get("note", "")), 300))
        if decision["action"] in ("block", "flag"):
            self.store.add_audit(actor, "dns.decision." + decision["action"], "analyze",
                                 {"domain": domain, "reason": decision["reason_code"]})
        return {"analysis": analysis, "decision": decision, "resolution": resolution, "event": event}

    def ingest_events(self, body: dict, actor: str) -> dict:
        events = body.get("events")
        if not isinstance(events, list) or not events:
            raise ValueError("events must be a non-empty list of real query records")
        accepted = []
        for raw in events[:200]:
            if not isinstance(raw, dict):
                continue
            domain = normalize_domain(sanitize_text(str(raw.get("domain", "")), 1024))
            if not domain:
                continue
            epoch = raw.get("epoch")
            try:
                epoch = float(epoch) if epoch is not None else None
            except (TypeError, ValueError):
                epoch = None
            resolution = {"latency_ms": raw.get("latency_ms"),
                          "bytes_out": raw.get("bytes_out"), "bytes_in": raw.get("bytes_in"),
                          "answers": raw.get("answers") if isinstance(raw.get("answers"), list) else []}
            analysis = self.detection.safe_analyze(
                domain, answers=resolution["answers"],
                beaconing=self.guardian.beacon_context(domain))
            decision = self.policy.decide(analysis, actor=actor)
            self.store.add_event(
                device=self._device(raw.get("device_id")), domain=domain,
                verdict=decision["action"], classification=analysis["classification"],
                risk=analysis["risk"], severity=analysis["severity"],
                ruleset=self.detection.version, source="agent",
                latency_ms=resolution["latency_ms"], bytes_out=resolution["bytes_out"],
                bytes_in=resolution["bytes_in"], answers=resolution["answers"],
                qtype=sanitize_text(str(raw.get("qtype", "A")), 8), epoch=epoch,
                note=sanitize_text(str(raw.get("note", "")), 300))
            accepted.append({"domain": domain, "decision": decision["action"]})
        self.store.add_audit(actor, "dns.agent_events_ingested", "agent",
                             {"count": len(accepted)})
        return {"ok": True, "accepted": len(accepted), "events": accepted}

    @staticmethod
    def _device(value) -> str:
        """Minimal data collection: accept an opaque pseudonymous id only."""
        text = sanitize_text(str(value or "local-device"), 64)
        return re.sub(r"[^A-Za-z0-9_.:-]", "-", text)[:64] or "local-device"

    # ---- dispatch -------------------------------------------------------
    def handle(self, handler, method: str, path: str, query: dict, body: dict,
               session: Session) -> tuple[int, dict | str, dict | None]:
        actor = session.source
        p = path.rstrip("/") or "/"

        def need(scope: str) -> None:
            if scope not in session.scopes:
                raise PermissionError(f"missing required scope: {scope}")

        # ---- read-only ---------------------------------------------------
        if p == "/api/v1/config" and method == "GET":
            return 200, {
                "service": "AETHER AI", "version": "1.0.0",
                "default_scopes": sorted(session.scopes),
                "admin_unlock_available": True,
                "features": ["signed-intel", "real-dns", "explainable-detection", "audit-chain",
                             "policy-rollback", "graded-lessons", "model-validation"],
                "limitations": ["DNS names only; no HTTPS inspection", "no offensive actions",
                                "no guaranteed prevention", "platform restrictions documented"],
                "docs": ["docs/aether/MODULES.md", "docs/aether/API.md",
                         "docs/aether/CROSS_PLATFORM.md", "docs/aether/SECURITY.md"],
            }, None
        if p == "/api/v1/session" and method == "GET":
            return 200, {"authenticated": True, "scopes": sorted(session.scopes),
                         "csrf": session.csrf, "source": session.source}, None
        if p == "/api/v1/status" and method == "GET":
            need("read"); return 200, self.status(), None
        if p == "/api/v1/dashboard" and method == "GET":
            need("read"); return 200, self.dashboard(), None
        if p == "/api/v1/events" and method == "GET":
            need("read")
            return 200, {"events": self.store.get_events(
                limit=int(query.get("limit", ["100"])[0]),
                device=query.get("device", [None])[0],
                severity=query.get("severity", [None])[0])}, None
        if p == "/api/v1/network/stats" and method == "GET":
            need("read"); return 200, self.guardian.stats(), None
        if p == "/api/v1/network/anomalies" and method == "GET":
            need("read"); return 200, {"anomalies": self.guardian.anomalies()}, None
        if p == "/api/v1/policy" and method == "GET":
            need("read"); return 200, self.policy.get_state(), None
        if p == "/api/v1/policy/snapshots" and method == "GET":
            need("read"); return 200, {"snapshots": self.store.list_snapshots()}, None
        if p == "/api/v1/intel" and method == "GET":
            need("read"); return 200, self.intel.public(), None
        if p == "/api/v1/audit" and method == "GET":
            need("read"); return 200, {"entries": self.store.get_audit(
                limit=int(query.get("limit", ["100"])[0]))}, None
        if p == "/api/v1/audit/verify" and method == "GET":
            need("read")
            return 200, {"audit": self.store.verify_chain("audit", AUDIT_COLUMNS),
                         "events": self.store.verify_chain("events", EVENT_COLUMNS),
                         "policy_snapshots": self.store.verify_chain("policy_snapshots", SNAPSHOT_COLUMNS)}, None
        if p == "/api/v1/models" and method == "GET":
            need("read"); return 200, self.improvement.status(), None
        if p == "/api/v1/education/lessons" and method == "GET":
            need("read"); return 200, {"lessons": self.education.lessons(),
                                      "labs": self.education.labs()}, None
        if re.fullmatch(r"/api/v1/education/lessons/[a-z_]+", p) and method == "GET":
            need("read")
            lesson = self.education.lesson(p.rsplit("/", 1)[1], query.get("level", ["beginner"])[0])
            return (200, lesson, None) if lesson else (404, {"error": "unknown lesson"}, None)
        if p == "/api/v1/education/labs" and method == "GET":
            need("read"); return 200, {"labs": self.education.labs()}, None
        if p == "/api/v1/assistant/history" and method == "GET":
            need("read")
            return 200, {"messages": self.assistant.history(query.get("session_id", [""])[0])}, None
        if p == "/api/v1/analyze" and method == "GET":
            need("analyze")
            return 200, self.analyze({"target": query.get("target", [""])[0]}, actor), None

        # ---- mutations ---------------------------------------------------
        if p == "/api/v1/session/authorize" and method == "POST":
            if hmac_equal(sanitize_text(str(body.get("admin_token", "")), 256), self.keys.admin_token):
                session.scopes.add("admin")
                self.store.add_audit("browser-session", "session.elevated", "admin",
                                     {"source": session.source})
                return 200, {"ok": True, "scopes": sorted(session.scopes)}, None
            return 403, {"error": "invalid admin token"}, None
        if p == "/api/v1/analyze" and method == "POST":
            need("analyze"); return 200, self.analyze(body, actor), None
        if p == "/api/v1/dns/query" and method == "POST":
            need("analyze"); return 200, self.dns_query(body, actor), None
        if p == "/api/v1/dns/events" and method == "POST":
            need("agent"); return 200, self.ingest_events(body, actor), None
        if p == "/api/v1/privacy/analyze" and method == "POST":
            need("analyze")
            return 200, self.privacy_engine.analyze_resources(
                str(body.get("page_url", "")),
                body.get("resources") if isinstance(body.get("resources"), list) else [],
                self.policy.get_state()["privacy_level"]), None
        if p == "/api/v1/assistant" and method == "POST":
            need("analyze")
            return 200, self.assistant.respond(
                str(body.get("message", "")), str(body.get("level", "beginner")),
                body.get("session_id"), actor), None
        if p == "/api/v1/feedback" and method == "POST":
            need("feedback")
            label = str(body.get("label", ""))
            domain = normalize_domain(str(body.get("domain", "")))
            verified_by = sanitize_text(str(body.get("verified_by", actor)), 120)
            note = sanitize_text(str(body.get("note", "")), 500)
            if label == "false_positive":
                return 200, self.policy.report_false_positive(domain, note, verified_by, actor), None
            if label in ("confirmed", "malicious", "benign"):
                self.store.add_label(domain, label, verified_by, note)
                self.store.add_audit(actor, "feedback.label", "feedback",
                                     {"domain": domain, "label": label})
                return 200, {"ok": True, "domain": domain, "label": label,
                             "effect": "verified label added; included in the next model evaluation"}, None
            return 400, {"error": "label must be false_positive, confirmed, malicious or benign"}, None
        if p == "/api/v1/policy" and method == "POST":
            need("policy"); return 200, self.policy.update_state(body, actor), None
        if p == "/api/v1/policy/user-rule" and method == "POST":
            need("policy")
            return 200, self.policy.set_user_rule(str(body.get("domain", "")),
                                                  str(body.get("action", "")), actor), None
        if p == "/api/v1/policy/allowlist" and method == "POST":
            need("approve")
            return 200, self.policy.request_temp_allow(
                str(body.get("domain", "")), body.get("minutes", 30),
                str(body.get("reason", "")), str(body.get("approved_by", "")),
                str(body.get("approval_phrase", "")), actor), None
        if p == "/api/v1/policy/allowlist" and method == "DELETE":
            need("approve")
            return 200, self.policy.revoke_temp_allow(query.get("domain", [""])[0], actor), None
        if p == "/api/v1/policy/rollback" and method == "POST":
            need("admin")
            return 200, self.policy.rollback(body.get("version"),
                                             actor, str(body.get("reason", ""))), None
        if p == "/api/v1/education/quiz" and method == "POST":
            need("read")
            return 200, self.education.grade_quiz(str(body.get("lesson_id", "")),
                                                  body.get("answers", [])), None
        if p == "/api/v1/education/lab" and method == "POST":
            need("read")
            return 200, self.education.lab(str(body.get("lab_id", "")),
                                           str(body.get("permission_phrase", "")), actor), None
        if p == "/api/v1/intel/update" and method == "POST":
            need("admin"); return 200, self.intel.update_now(trigger="manual-api"), None
        if p == "/api/v1/models/evaluate" and method == "POST":
            need("admin")
            return 200, self.improvement.submit_candidate(body.get("config", body), actor), None
        if p == "/api/v1/models/activate" and method == "POST":
            need("admin")
            return 200, self.improvement.activate(str(body.get("version", "")), actor), None
        if p == "/api/v1/models/rollback" and method == "POST":
            need("admin")
            return 200, self.improvement.rollback(str(body.get("version", "")), actor), None

        return 404, {"error": "not found", "path": p}, None


class AetherHTTPServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address, app: AetherApp):
        self.app = app
        super().__init__(address, AetherHandler)


class AetherHandler(BaseHTTPRequestHandler):
    server_version = "AETHER/1.0"
    sys_version = ""

    @property
    def app(self) -> AetherApp:
        return self.server.app

    def log_message(self, fmt, *args):
        # Do not log cookies, tokens or query values.
        print(f"[aether] {self.client_address[0]} {self.command} {self.path.split('?')[0]}")

    def _headers(self, extra: dict | None = None, content_type: str = "application/json") -> dict:
        headers = {
            "Content-Type": content_type + "; charset=utf-8",
            "Cache-Control": "no-store",
            "X-Content-Type-Options": "nosniff",
            "Referrer-Policy": "no-referrer",
            "Content-Security-Policy": "default-src 'self'; script-src 'self'; style-src 'self'; "
                                       "img-src 'self' data:; connect-src 'self'; object-src 'none'; "
                                       "base-uri 'self'; form-action 'self'",
        }
        # X-Frame-Options is intentionally omitted: the Arena live preview
        # embeds this dashboard in an iframe on the proxied preview host.

        if extra:
            headers.update(extra)
        return headers

    def _send(self, status: int, payload, extra: dict | None = None,
              content_type: str = "application/json") -> None:
        if isinstance(payload, (dict, list)):
            data = json.dumps(payload, ensure_ascii=True, default=str).encode()
        elif isinstance(payload, str):
            data = payload.encode()
        else:
            data = bytes(payload)
        self.send_response(status)
        for key, value in self._headers(extra, content_type).items():
            self.send_header(key, value)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(data)

    def _static(self, path: str) -> None:
        files = {"/": "index.html", "/index.html": "index.html", "/app.js": "app.js",
                 "/styles.css": "styles.css", "/favicon.svg": "favicon.svg"}
        name = files.get(path)
        if name is None:
            return self._send(404, {"error": "not found"})
        file_path = self.app.root / "web" / name
        if not file_path.is_file():
            return self._send(404, {"error": "missing asset"})
        content_type = {"index.html": "text/html", "app.js": "application/javascript",
                        "styles.css": "text/css", "favicon.svg": "image/svg+xml"}[name]
        extra = {}
        if name == "index.html":
            session = self._get_or_create_session()
            if session is None:
                return self._send(401, {"error": "session unavailable"})
            text = file_path.read_text().replace("__AETHER_CSRF__", session.csrf)
            extra["Set-Cookie"] = (
                f"aether_session={session.token}; HttpOnly; SameSite=Lax; Path=/; Max-Age=43200"
            )
            return self._send(200, text, extra, content_type)
        return self._send(200, file_path.read_bytes(), {}, content_type)

    def _get_or_create_session(self) -> Session | None:
        existing = self.app._session_from_request(self)
        if existing and existing.source == "browser":
            return existing
        return self.app._new_browser_session()

    def do_HEAD(self): self.do_GET()
    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path
        if not path.startswith("/api/"):
            return self._static(path)
        query = parse_qs(parsed.query, keep_blank_values=True)
        return self._api("GET", path, query)

    def do_POST(self): return self._mutating("POST")
    def do_DELETE(self): return self._mutating("DELETE")

    def _read_body(self) -> dict:
        length = int(self.headers.get("Content-Length") or 0)
        if length > MAX_BODY:
            raise ValueError("request body too large")
        raw = self.rfile.read(length) if length else b"{}"
        if not raw:
            return {}
        content_type = self.headers.get("Content-Type", "")
        if "application/json" not in content_type:
            raise ValueError("Content-Type must be application/json")
        parsed = json.loads(raw)
        if not isinstance(parsed, dict):
            raise ValueError("JSON body must be an object")
        return parsed

    def _mutating(self, method: str):
        parsed = urlparse(self.path)
        return self._api(method, parsed.path, parse_qs(parsed.query, keep_blank_values=True))

    def _api(self, method: str, path: str, query: dict) -> None:
        session = self.app._session_from_request(self)
        if session is None:
            return self._send(401, {"error": "authentication required",
                                   "hint": "Open the dashboard first, or use the operator Bearer token"})
        identity = session.token if session.source == "browser" else "operator-token"
        if not self.app._rate_ok(identity):
            return self._send(429, {"error": "rate limit exceeded"})
        try:
            body = self._read_body() if method in ("POST", "DELETE") else {}
        except ValueError as exc:
            return self._send(400, {"error": str(exc)})
        except json.JSONDecodeError:
            return self._send(400, {"error": "invalid JSON"})
        if method in ("POST", "DELETE") and session.source == "browser":
            if not hmac_equal(self.headers.get("X-CSRF-Token", ""), session.csrf):
                return self._send(403, {"error": "CSRF token missing or invalid"})
        try:
            status, payload, extra = self.app.handle(self, method, path, query, body, session)
            self._send(status, payload, extra)
        except PermissionError as exc:
            self._send(403, {"error": str(exc), "scopes": sorted(session.scopes)})
        except ValueError as exc:
            self._send(400, {"error": str(exc)})
        except Exception as exc:  # never leak stack traces to clients
            print(f"[aether] internal error: {type(exc).__name__}: {exc}")
            traceback.print_exc()
            self._send(500, {"error": "internal error", "type": type(exc).__name__})


def serve(host: str = "0.0.0.0", port: int = 8000, root: Path | None = None,
          auto_update: bool = True) -> tuple[AetherHTTPServer, threading.Thread]:
    root = root or Path(__file__).resolve().parents[1]
    app = AetherApp(root, auto_update=auto_update)
    server = AetherHTTPServer((host, port), app)
    thread = threading.Thread(target=server.serve_forever, name="aether-http", daemon=True)
    thread.start()
    return server, thread


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    host = os.environ.get("AETHER_HOST", "0.0.0.0")
    port = int(os.environ.get("AETHER_PORT", "8000"))
    app = AetherApp(root)
    server = AetherHTTPServer((host, port), app)
    print("=" * 68)
    print("  AETHER AI — Universal Cybersecurity Intelligence & Autonomous Defense")
    print(f"  Dashboard: http://{host}:{port}/  (live preview uses the proxied host)")
    print(f"  Operator API token: {app.keys.api_token}")
    print(f"  Admin token (unlock admin actions in the UI): {app.keys.admin_token}")
    print(f"  Signed intel feed: {app.intel.current().version} "
          f"({app.intel.current().indicator_count} indicators, {app.intel.current().status})")
    print(f"  Ruleset: {app.detection.version}   Policy version: {app.policy.version}")
    print("=" * 68)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        app.stop()
        server.server_close()


if __name__ == "__main__":
    main()
