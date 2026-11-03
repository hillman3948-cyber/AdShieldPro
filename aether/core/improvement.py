"""Controlled intelligence improvement: versioned rule sets, measured accuracy,
validation gates, compatibility checks and rollback.

AI-generated or user-submitted rules are NEVER activated directly. A candidate
must be evaluated on a labeled corpus (including real held-out IOCs and verified
user feedback), pass recall / false-positive / compatibility gates, and only
then can an admin activate it. Every version is stored with its exact metrics.
"""
from __future__ import annotations

import copy
import json
from pathlib import Path

from .detection import DEFAULT_CONFIG, DetectionEngine
from .util import canonical_json, normalize_domain, utcnow

DEFAULT_GATES = {
    "min_validation_recall": 0.65,
    "max_validation_false_positive_rate": 0.12,
    "require_compatibility_pass": True,
    "max_precision_drop_vs_active": 0.20,
    "note": "Held-out real IOCs are reported but not gated; they measure generalization.",
}


class ImprovementEngine:
    def __init__(self, root: Path, store, intel_manager, detection: DetectionEngine):
        self.root = Path(root)
        self.store = store
        self.intel = intel_manager
        self.detection = detection
        self.corpus = json.loads((self.root / "corpus" / "labeled_domains.json").read_text())
        self.compatibility = json.loads(
            (self.root / "corpus" / "compatibility_domains.json").read_text()
        )["domains"]
        self.gates = copy.deepcopy(DEFAULT_GATES)
        models = [m for m in store.get_models() if m["active"]]
        if not models:
            self._register_baseline()

    def _register_baseline(self) -> None:
        baseline = copy.deepcopy(DEFAULT_CONFIG)
        report = self.evaluate(baseline)
        self.store.save_model(baseline["version"], baseline, report["metrics"],
                              report["passed"], report, parent="")
        self.store.activate_model(baseline["version"])
        self.store.add_audit("system", "models.baseline_registered", "admin",
                             {"version": baseline["version"],
                              "metrics": report["metrics"], "passed": report["passed"]})

    @staticmethod
    def _metrics_from_counts(tp: int, fp: int, tn: int, fn: int) -> dict:
        precision = tp / (tp + fp) if tp + fp else 0.0
        recall = tp / (tp + fn) if tp + fn else 0.0
        fpr = fp / (fp + tn) if fp + tn else 0.0
        f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
        return {"tp": tp, "fp": fp, "tn": tn, "fn": fn,
                "precision": round(precision, 4), "recall": round(recall, 4),
                "false_positive_rate": round(fpr, 4), "f1": round(f1, 4),
                "support": tp + fn + fp + tn}

    def _verified_items(self) -> list[dict]:
        out = []
        for label in self.store.get_labels():
            domain = normalize_domain(label["domain"])
            if not domain or "." not in domain:
                continue
            truth = "benign" if label["label"] in ("benign", "false_positive") else "malicious"
            out.append({"domain": domain, "label": truth, "split": "verified",
                        "origin": "user-feedback:" + label["label"], "synthetic": False})
        return out

    def _compatibility(self, engine: DetectionEngine) -> dict:
        failures = []
        for domain in self.compatibility:
            analysis = engine.safe_analyze(domain)
            if analysis.get("confirmed") or analysis.get("suspicious"):
                failures.append({"domain": domain,
                                 "classification": analysis.get("classification"),
                                 "risk": analysis.get("risk")})
        return {"checked": len(self.compatibility), "passed": not failures,
                "failures": failures[:10],
                "note": "Popular services must never be auto-blocked by a candidate ruleset."}

    def evaluate(self, config: dict) -> dict:
        """Run a candidate config against labeled data; no activation."""
        engine = DetectionEngine(self.intel, config)
        items = list(self.corpus["items"]) + self._verified_items()
        counts = {"validation": [0, 0, 0, 0], "holdout": [0, 0, 0, 0], "verified": [0, 0, 0, 0]}
        misclassified = []
        for item in items:
            analysis = engine.safe_analyze(item["domain"])
            predicted = bool(analysis.get("confirmed") or analysis.get("suspicious"))
            truth = item["label"] == "malicious"
            group = counts.setdefault(item["split"], [0, 0, 0, 0])
            if predicted and truth:
                group[0] += 1
            elif predicted and not truth:
                group[1] += 1
                if len(misclassified) < 12:
                    misclassified.append({"domain": item["domain"], "type": "false_positive",
                                          "origin": item["origin"],
                                          "risk": analysis.get("risk"),
                                          "rules": analysis.get("transparency", {}).get("rules_fired", [])[:4]})
            elif not predicted and truth:
                group[2] += 1
                if len(misclassified) < 12:
                    misclassified.append({"domain": item["domain"], "type": "miss",
                                          "origin": item["origin"], "risk": analysis.get("risk")})
            else:
                group[3] += 1
        # counts lists are [tp, fp, fn, tn]; _metrics_from_counts takes (tp, fp, tn, fn).
        metrics = {name: self._metrics_from_counts(v[0], v[1], v[3], v[2])
                   for name, v in counts.items()}
        compatibility = self._compatibility(engine)
        active = next((m for m in self.store.get_models() if m["active"]), None)
        active_precision = active["metrics"]["validation"]["precision"] if active else 0.0
        validation = metrics["validation"]
        reasons = []
        if validation["recall"] < self.gates["min_validation_recall"]:
            reasons.append(f"validation recall {validation['recall']} < {self.gates['min_validation_recall']}")
        if validation["false_positive_rate"] > self.gates["max_validation_false_positive_rate"]:
            reasons.append(f"validation false-positive rate {validation['false_positive_rate']} > "
                           f"{self.gates['max_validation_false_positive_rate']}")
        if active and validation["precision"] < active_precision - self.gates["max_precision_drop_vs_active"]:
            reasons.append(f"precision drop vs active model exceeds "
                           f"{self.gates['max_precision_drop_vs_active']}")
        if self.gates["require_compatibility_pass"] and not compatibility["passed"]:
            reasons.append(f"compatibility failures: {len(compatibility['failures'])} popular domains flagged")
        passed = not reasons
        return {
            "passed": passed, "reasons": reasons, "metrics": metrics,
            "compatibility": compatibility, "gates": copy.deepcopy(self.gates),
            "examples": misclassified,
            "corpus": {"file": "aether/corpus/labeled_domains.json",
                       "items": len(self.corpus["items"]),
                       "verified_feedback_items": len(self._verified_items()),
                       "description": self.corpus.get("description", "")},
            "candidate_version": config.get("version", "unnamed"),
            "evaluated_at": utcnow(),
            "honesty_note": "Metrics are measured on a labeled corpus, not guaranteed live-world "
                            "accuracy. Held-out real IOCs are excluded from the active feed.",
        }

    def submit_candidate(self, config: dict, actor: str) -> dict:
        if not isinstance(config, dict):
            raise ValueError("config must be an object")
        candidate = copy.deepcopy(DEFAULT_CONFIG)
        candidate.update({k: v for k, v in config.items()
                          if k in ("version", "description", "weights", "thresholds", "enabled_rules")})
        if "weights" in config:
            merged = copy.deepcopy(DEFAULT_CONFIG["weights"])
            merged.update(config["weights"])
            candidate["weights"] = merged
        if "thresholds" in config:
            merged = copy.deepcopy(DEFAULT_CONFIG["thresholds"])
            merged.update(config["thresholds"])
            candidate["thresholds"] = merged
        suffix = canonical_json(candidate)[:10]
        version = str(candidate.get("version") or f"candidate-{utcnow().replace(':','')}-{suffix[:6]}")
        existing = {m["version"] for m in self.store.get_models()}
        if version in existing:
            version = f"{version}-rev-{suffix[:6]}"
        candidate["version"] = version
        DetectionEngine._validate_config(candidate)  # schema validation before evaluation
        report = self.evaluate(candidate)
        self.store.save_model(version, candidate, report["metrics"], report["passed"], report,
                              parent=self.detection.version)
        self.store.add_audit(actor, "models.candidate_evaluated", "admin",
                             {"version": version, "passed": report["passed"],
                              "metrics": report["metrics"]["validation"]})
        return {**report, "version": version,
                "activation": "requires admin scope AND a passed validation report"}

    def activate(self, version: str, actor: str) -> dict:
        model = next((m for m in self.store.get_models() if m["version"] == version), None)
        if model is None:
            raise ValueError("unknown model version")
        if not model["passed"]:
            raise PermissionError("this model failed validation and cannot be activated")
        if not model["report"]["compatibility"]["passed"]:
            raise PermissionError("this model failed the compatibility check")
        previous = self.detection.version
        self.detection.set_config(model["config"])
        self.store.activate_model(version)
        self.store.add_audit(actor, "models.activated", "admin",
                             {"version": version, "previous": previous,
                              "metrics": model["metrics"]["validation"]})
        return {"ok": True, "active_version": version, "previous_version": previous,
                "ruleset_version": self.detection.version,
                "note": "Roll back with POST /api/v1/admin/models/rollback if compatibility problems appear."}

    def rollback(self, version: str, actor: str) -> dict:
        model = next((m for m in self.store.get_models() if m["version"] == version), None)
        if model is None:
            raise ValueError("unknown model version")
        previous = self.detection.version
        self.detection.set_config(model["config"])
        self.store.activate_model(version)
        self.store.add_audit(actor, "models.rolled_back", "admin",
                             {"version": version, "previous": previous})
        return {"ok": True, "active_version": version, "previous_version": previous}

    def status(self) -> dict:
        models = self.store.get_models()
        active = next((m for m in models if m["active"]), None)
        return {
            "active": active, "versions": [
                {"version": m["version"], "ts": m["ts"], "passed": m["passed"],
                 "active": m["active"], "parent": m["parent"],
                 "validation": m["metrics"].get("validation", {})} for m in models],
            "gates": self.gates,
            "verified_events": self.verified_event_summary(),
            "corpus_items": len(self.corpus["items"]),
            "active_ruleset_version": self.detection.version,
        }

    def verified_event_summary(self) -> dict:
        labels = {normalize_domain(l["domain"]): l["label"] for l in self.store.get_labels()}
        events = self.store.all_events(limit=5000)
        blocked = [e for e in events if e["verdict"] == "block"]
        fp = [e for e in blocked if labels.get(e["domain"]) in ("benign", "false_positive")]
        confirmed = [e for e in blocked if labels.get(e["domain"]) in ("malicious", "confirmed")]
        return {
            "verified_labels": len(labels),
            "false_positive_labels": sum(1 for v in labels.values() if v in ("benign", "false_positive")),
            "blocked_events": len(blocked),
            "blocked_events_labeled_false_positive": len(fp),
            "blocked_events_labeled_confirmed": len(confirmed),
            "measured_false_positive_rate_on_verified_events":
                round(len(fp) / (len(fp) + len(confirmed)), 4) if (fp or confirmed) else None,
            "note": "Only events with a verified user label count toward the measured rate; "
                    "unlabeled events are not assumed to be true or false positives.",
        }
