"""Configurable protection policy, user-approved allowlisting and safe rollback.

Design rule: only *confirmed* intelligence is auto-blocked by default. Suspicious
heuristic findings are flagged unless the user has explicitly enabled the more
aggressive option. Every change is versioned, chained in the audit log, and can
be rolled back in one step if it breaks application compatibility.
"""
from __future__ import annotations

import copy
import json
from typing import Any

from .util import epoch_now, iso_from_epoch, normalize_domain, sanitize_text, utcnow

APPROVAL_PHRASE = "I APPROVE THIS TEMPORARY ALLOWLIST"

DEFAULT_STATE: dict[str, Any] = {
    "protection_mode": "standard",          # standard | strict | privacy | custom
    "privacy_level": "balanced",            # minimal | balanced | strict
    "auto_block_confirmed": True,
    "auto_block_suspicious": False,
    "suspicious_risk_threshold": 80,
    "temporary_allowlist": {},              # domain -> {expires_at, approved_by, reason, created_at}
    "user_rules": {},                       # domain -> "allow" | "block"
    "explanation_level": "beginner",        # beginner | intermediate | professional
    "fail_safe_dns": "allow-on-engine-error",
    "updated_at": None,
    "updated_by": "system",
}

ALLOWED_MODES = {"standard", "strict", "privacy", "custom"}
ALLOWED_PRIVACY = {"minimal", "balanced", "strict"}
ALLOWED_LEVELS = {"beginner", "intermediate", "professional"}


class PolicyEngine:
    def __init__(self, store):
        self.store = store
        snapshots = store.list_snapshots(limit=1)
        if snapshots:
            self.state = copy.deepcopy(snapshots[0]["state"])
            self.version = int(snapshots[0]["version"])
            self._verified_allow: set[str] = set()
        else:
            self.state = copy.deepcopy(DEFAULT_STATE)
            self.state["updated_at"] = utcnow()
            self.version = 1
            store.save_snapshot(self.version, self.state, "system")
            store.add_audit("system", "policy.initialized", "admin",
                            {"version": self.version, "state": self.state})
        self._refresh_verified_labels()

    def _refresh_verified_labels(self) -> None:
        self._verified_allow = {
            item["domain"] for item in self.store.get_labels()
            if item["label"] in ("benign", "false_positive")
        }

    def get_state(self) -> dict:
        state = copy.deepcopy(self.state)
        now = epoch_now()
        for domain, entry in state["temporary_allowlist"].items():
            entry["active"] = float(entry["expires_at"]) > now
        state["version"] = self.version
        state["approval_phrase_required"] = APPROVAL_PHRASE
        state["policy_precedence"] = [
            "approved unexpired temporary allowlist", "user allow/block rules",
            "verified false-positive labels", "confirmed intelligence",
            "privacy level", "suspicious heuristic threshold", "allow",
        ]
        return state

    def _commit(self, actor: str, action: str, detail: dict) -> int:
        self.version += 1
        self.state["updated_at"] = utcnow()
        self.state["updated_by"] = actor
        self.store.save_snapshot(self.version, self.state, actor)
        self.store.add_audit(actor, action, "policy",
                             {"version": self.version, **detail})
        return self.version

    def update_state(self, patch: dict, actor: str) -> dict:
        if not isinstance(patch, dict):
            raise ValueError("patch must be an object")
        allowed = {
            "protection_mode": lambda v: v in ALLOWED_MODES,
            "privacy_level": lambda v: v in ALLOWED_PRIVACY,
            "explanation_level": lambda v: v in ALLOWED_LEVELS,
            "auto_block_confirmed": lambda v: isinstance(v, bool),
            "auto_block_suspicious": lambda v: isinstance(v, bool),
            "suspicious_risk_threshold": lambda v: isinstance(v, (int, float)) and 50 <= v <= 100,
        }
        changes = {}
        for key, value in patch.items():
            if key not in allowed:
                raise ValueError(f"unsupported policy field: {key}")
            if not allowed[key](value):
                raise ValueError(f"invalid value for {key}")
            changes[key] = value
        if not changes:
            raise ValueError("no supported fields supplied")
        self.state.update(changes)
        version = self._commit(actor, "policy.updated", {"changes": changes})
        return {"ok": True, "version": version, "state": self.get_state()}

    def set_user_rule(self, domain: str, action: str, actor: str) -> dict:
        domain = normalize_domain(domain)
        if not domain:
            raise ValueError("invalid domain")
        if action not in ("allow", "block", "remove"):
            raise ValueError("action must be allow, block or remove")
        if action == "remove":
            self.state["user_rules"].pop(domain, None)
        else:
            self.state["user_rules"][domain] = action
        version = self._commit(actor, "policy.user_rule", {"domain": domain, "action": action})
        return {"ok": True, "version": version, "state": self.get_state()}

    def request_temp_allow(self, domain: str, minutes: int, reason: str,
                           approved_by: str, approval_phrase: str, actor: str) -> dict:
        """Explicit user approval is mandatory. Entries always expire (max 24h)."""
        domain = normalize_domain(domain)
        if not domain:
            raise ValueError("invalid domain")
        if approval_phrase != APPROVAL_PHRASE:
            raise PermissionError("explicit approval phrase required")
        if not sanitize_text(reason).strip():
            raise ValueError("a reason is required for the audit log")
        if not sanitize_text(approved_by).strip():
            raise ValueError("approved_by is required")
        minutes = int(minutes)
        if not 1 <= minutes <= 1440:
            raise ValueError("minutes must be between 1 and 1440 (24 hours)")
        created = epoch_now()
        entry = {
            "expires_at": created + minutes * 60,
            "created_at": created,
            "approved_by": sanitize_text(approved_by, 120),
            "reason": sanitize_text(reason, 500),
            "minutes": minutes,
        }
        self.state["temporary_allowlist"][domain] = entry
        version = self._commit(actor, "policy.temp_allow.approved",
                               {"domain": domain, "minutes": minutes, "approved_by": approved_by})
        return {"ok": True, "version": version, "domain": domain,
                "entry": {**entry, "expires_at_iso": iso_from_epoch(entry["expires_at"])}}

    def revoke_temp_allow(self, domain: str, actor: str) -> dict:
        domain = normalize_domain(domain)
        removed = self.state["temporary_allowlist"].pop(domain, None)
        if not removed:
            raise ValueError("no active temporary allowlist entry for that domain")
        version = self._commit(actor, "policy.temp_allow.revoked", {"domain": domain})
        return {"ok": True, "version": version, "removed": domain}

    def report_false_positive(self, domain: str, note: str, verified_by: str,
                              actor: str) -> dict:
        """Creates a verified label. A false positive suppresses auto-blocking but
        does NOT silently add a permanent allow rule or remove intelligence."""
        domain = normalize_domain(domain)
        if not domain:
            raise ValueError("invalid domain")
        self.store.add_label(domain, "false_positive", verified_by, note, split="verified")
        self._refresh_verified_labels()
        self.store.add_audit(actor, "feedback.false_positive", "feedback",
                             {"domain": domain, "note_chars": len(note)})
        return {"ok": True, "domain": domain,
                "effect": "auto-block suppressed for this verified label; "
                          "use an approved temporary allowlist entry for an explicit override"}

    def rollback(self, version: int, actor: str, reason: str = "") -> dict:
        snapshot = self.store.get_snapshot(int(version))
        if snapshot is None:
            raise ValueError("unknown policy snapshot version")
        self.state = copy.deepcopy(snapshot)
        new_version = self._commit(actor, "policy.rollback",
                                   {"restored_version": int(version),
                                    "reason": sanitize_text(reason, 300)})
        return {"ok": True, "restored_from": int(version), "version": new_version,
                "state": self.get_state()}

    # ------------------------------------------------------------------
    def decide(self, analysis: dict, actor: str = "system") -> dict:
        domain = normalize_domain(analysis.get("normalized_domain", ""))
        state = self.state
        now = epoch_now()
        base = {
            "domain": domain, "policy_version": self.version,
            "ruleset_version": analysis.get("transparency", {}).get("ruleset_version", ""),
            "decided_at": utcnow(),
        }

        # Temporary allowlist with explicit approval and expiry.
        entry = state["temporary_allowlist"].get(domain)
        if entry and float(entry["expires_at"]) > now:
            self.store.add_audit(actor, "decision.temp_allow", "read",
                                 {"domain": domain, "approved_by": entry["approved_by"]})
            return {**base, "action": "allow", "reason_code": "approved_temporary_allowlist",
                    "user_override": True,
                    "explain": "User-approved, expiring allowlist entry overrides automatic blocking.",
                    "warning": "This destination has a security finding; the override expires automatically."}
        if entry and float(entry["expires_at"]) <= now:
            self.state["temporary_allowlist"].pop(domain, None)

        rule = state["user_rules"].get(domain)
        if rule == "block":
            return {**base, "action": "block", "reason_code": "user_block_rule",
                    "explain": "You created an explicit block rule for this domain."}
        if rule == "allow":
            return {**base, "action": "allow", "reason_code": "user_allow_rule",
                    "explain": "You created an explicit allow rule for this domain."}

        if domain in self._verified_allow:
            return {**base, "action": "flag", "reason_code": "verified_false_positive",
                    "explain": "A verified false-positive label suppresses automatic blocking; "
                               "the finding stays visible for review.",
                    "false_positive": True}

        if analysis.get("classification") == "engine_error":
            return {**base, "action": "allow", "reason_code": "fail_safe_engine_error",
                    "explain": "Analysis engine error: DNS is allowed so connectivity is preserved."}

        if analysis.get("confirmed") and state["auto_block_confirmed"]:
            return {**base, "action": "block", "reason_code": "confirmed_intelligence",
                    "explain": "A signed, verified threat-intelligence indicator matches this destination."}

        if analysis.get("classification") == "privacy_risk":
            level = state["privacy_level"]
            if level == "strict":
                return {**base, "action": "block", "reason_code": "privacy_level_strict",
                        "explain": "Strict privacy level blocks known tracker/ad endpoints."}
            if level == "balanced":
                return {**base, "action": "flag", "reason_code": "privacy_level_balanced",
                        "explain": "Balanced privacy level reports trackers without blocking them."}
            return {**base, "action": "allow", "reason_code": "privacy_level_minimal",
                    "explain": "Minimal privacy level allows tracking endpoints."}

        if analysis.get("suspicious"):
            if (state["auto_block_suspicious"]
                    and analysis.get("risk", 0) >= state["suspicious_risk_threshold"]):
                return {**base, "action": "block", "reason_code": "user_enabled_suspicious_block",
                        "explain": "You explicitly enabled blocking of high-risk heuristic findings."}
            return {**base, "action": "flag", "reason_code": "suspicious_not_confirmed",
                    "explain": "Suspicious heuristics are flagged, not blocked by default, to avoid false positives."}

        return {**base, "action": "allow", "reason_code": "no_confirmed_threat",
                "explain": "No confirmed threat or strong suspicious signal was found."}
