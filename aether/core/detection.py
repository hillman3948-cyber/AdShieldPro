"""AETHER detection engine — explainable, local, deterministic analysis.

This is NOT a language model and makes no claim of general AI reasoning. It is a
versioned, measurable rule engine that combines verified third-party
intelligence with explicit heuristics. Every finding carries its rule id,
weight, source, timestamp, confidence and human-readable evidence. Confirmed
intelligence is kept strictly separate from "suspicious" heuristic findings, so
unknown domains are never silently treated as confirmed malware.
"""
from __future__ import annotations

import copy
import re
from typing import Any

from .intel import MALICIOUS_CATEGORIES, PRIVACY_CATEGORIES
from .util import (
    bounded_levenshtein, clamp, confusable_skeleton, consonant_ratio,
    digit_ratio, domain_suffixes, has_non_ascii, is_bogon_ip, is_ip_literal,
    mixed_script, normalize_domain, parse_url, PHISHING_KEYWORDS, registrable_domain,
    sanitize_text, shannon_entropy, SUSPICIOUS_TLDS, utcnow,
)

DEFAULT_CONFIG: dict[str, Any] = {
    "version": "rules-1.0.0",
    "description": "Baseline: confirmed-intelligence blocking, conservative heuristics, "
                   "known-good allowlist suppression.",
    "weights": {
        "intel_confirmed": 95, "intel_ip_reputation": 90, "intel_malvertising": 88,
        "typosquat": 68, "homograph": 72, "brand_in_subdomain": 62,
        "dns_bogon_answer": 58, "redirect_cross_registrable": 48,
        "encoded_host": 40, "ip_literal": 30, "dga_label": 45,
        "url_userinfo": 55, "suspicious_tld": 26, "phishing_keywords": 20,
        "privacy_intel": 38, "url_shortener": 22, "nonstandard_port": 20,
        "excessive_labels": 18,
        "dns_ttl_anomaly": 18, "beaconing": 55, "http_downgrade": 30,
        "long_redirect_chain": 22, "dns_nxdomain": 8, "percent_encoded_host": 35,
    },
    "thresholds": {
        "suspicious_risk": 55,
        "min_signals_for_suspicious": 2,
        "dga_entropy": 3.55,
        "dga_label_length": 12,
        "typosquat_max_distance": 1,
        "max_registrable_labels": 4,
        "redirect_chain_max": 4,
    },
    "enabled_rules": [],  # empty means "all known rules enabled"
}

SEVERITY_ORDER = {"none": 0, "low": 1, "medium": 2, "high": 3, "critical": 4}

BRANDS = (
    "paypal", "google", "gmail", "microsoft", "outlook", "apple", "icloud",
    "amazon", "facebook", "instagram", "whatsapp", "netflix", "spotify",
    "github", "dropbox", "bankofamerica", "wellsfargo", "chase", "gcash",
    "bpi", "maya", "coinbase", "binance", "steam", "discord", "adobe",
)

HEX_IP_RE = re.compile(r"^0x[0-9a-f]{6,8}$|^\d{8,10}$")


def _signal(rule: str, weight: float, detail: str, *, source: str,
            confidence: float, observed_at: str, category: str = "",
            evidence: str = "", kind: str = "heuristic") -> dict:
    return {
        "id": rule, "weight": round(float(weight), 2), "detail": detail,
        "source": source, "observed_at": observed_at,
        "confidence": round(clamp(confidence), 2), "category": category,
        "evidence": evidence, "kind": kind,
    }


class DetectionEngine:
    def __init__(self, intel_manager, config: dict | None = None):
        self.intel = intel_manager
        self.config = copy.deepcopy(config or DEFAULT_CONFIG)
        self._validate_config(self.config)

    @staticmethod
    def _validate_config(config: dict) -> None:
        if not isinstance(config.get("weights"), dict) or not isinstance(config.get("thresholds"), dict):
            raise ValueError("model config needs weights and thresholds objects")
        for key, value in config["weights"].items():
            if not isinstance(key, str) or not isinstance(value, (int, float)) or not 0 <= value <= 100:
                raise ValueError(f"invalid weight for {key}")
        for key, value in config["thresholds"].items():
            if not isinstance(key, str) or not isinstance(value, (int, float)) or value < 0:
                raise ValueError(f"invalid threshold for {key}")
        enabled = config.get("enabled_rules", [])
        if not isinstance(enabled, list) or any(not isinstance(x, str) for x in enabled):
            raise ValueError("enabled_rules must be a list of rule ids")

    @property
    def version(self) -> str:
        return str(self.config.get("version", "rules-custom"))

    def set_config(self, config: dict) -> None:
        self._validate_config(config)
        self.config = copy.deepcopy(config)

    def get_config(self) -> dict:
        return copy.deepcopy(self.config)

    def _enabled(self, rule: str) -> bool:
        enabled = self.config.get("enabled_rules", [])
        return not enabled or rule in enabled

    def _weight(self, rule: str) -> float:
        return float(self.config["weights"].get(rule, 0))

    def _add(self, signals: list, rule: str, detail: str, *, source: str = "local-heuristic",
             confidence: float = 0.6, observed_at: str | None = None, category: str = "",
             evidence: str = "", kind: str = "heuristic") -> None:
        if self._enabled(rule) and self._weight(rule) > 0:
            signals.append(_signal(
                rule, self._weight(rule), detail, source=source,
                confidence=confidence, observed_at=observed_at or utcnow(),
                category=category, evidence=evidence, kind=kind,
            ))

    # ------------------------------------------------------------------
    def analyze(self, target: str, *, redirect_chain: list | None = None,
                answers: list | None = None, resolution: dict | None = None,
                beaconing: dict | None = None) -> dict:
        """Analyze a domain, URL or IP. No network access is performed here."""
        feed = self.intel.current()
        target = sanitize_text(target or "", 2048)
        url = parse_url(target)
        domain = url["host"] or normalize_domain(target)
        is_url = "://" in target or "/" in target or "?" in target
        now = utcnow()
        signals: list[dict] = []
        intel_matches: list[dict] = []
        allowlisted = feed.is_allowlisted(domain) if domain else False

        # 1. Verified third-party intelligence ---------------------------
        for match in feed.match_domain(domain):
            category = match.get("category", "")
            intel_matches.append({
                "value": match["value"], "matched_on": match["matched_on"],
                "category": category, "confidence": match.get("confidence", 0.8),
                "severity": match.get("severity", "medium"),
                "source": match.get("source", "unknown"),
                "source_name": self._source_name(feed, match.get("source", "")),
                "observed_at": match.get("observed_at"),
                "evidence": match.get("evidence", ""),
                "kind": match.get("kind", "domain"),
            })
            if category in MALICIOUS_CATEGORIES:
                rule = "intel_confirmed" if category != "malvertising" else "intel_malvertising"
                self._add(signals, rule,
                          f"Domain '{domain}' is listed as {category} by "
                          f"{self._source_name(feed, match.get('source',''))}.",
                          source=f"intel:{match.get('source','')}",
                          confidence=float(match.get("confidence", 0.9)),
                          observed_at=match.get("observed_at"), category=category,
                          evidence=match.get("evidence", ""), kind="verified-intel")
            elif category in PRIVACY_CATEGORIES:
                self._add(signals, "url_shortener" if category == "url_shortener" else "privacy_intel",
                          f"Known {category} endpoint '{match['value']}' "
                          f"({match.get('name') or 'curated baseline'}).",
                          source=f"intel:{match.get('source','')}",
                          confidence=float(match.get("confidence", 0.8)),
                          observed_at=match.get("observed_at"), category=category,
                          evidence=match.get("evidence", ""), kind="verified-intel")

        if is_ip_literal(domain):
            ip_match = feed.match_ip(domain)
            if ip_match:
                intel_matches.append({
                    "value": domain, "matched_on": domain,
                    "category": ip_match.get("category", "phishing"),
                    "confidence": ip_match.get("confidence", 0.9),
                    "severity": ip_match.get("severity", "high"),
                    "source": ip_match.get("source", ""),
                    "source_name": self._source_name(feed, ip_match.get("source", "")),
                    "observed_at": ip_match.get("observed_at"),
                    "evidence": ip_match.get("evidence", ""), "kind": "ip",
                })
                self._add(signals, "intel_ip_reputation",
                          f"IP {domain} appears in active phishing IP reputation data.",
                          source=f"intel:{ip_match.get('source','')}",
                          confidence=float(ip_match.get("confidence", 0.9)),
                          observed_at=ip_match.get("observed_at"), category="phishing",
                          evidence=ip_match.get("evidence", ""), kind="verified-intel")
            else:
                self._add(signals, "ip_literal",
                          "The host is a raw IP address instead of a domain name; "
                          "phishing pages often avoid normal hostnames.",
                          confidence=0.55)

        if HEX_IP_RE.match(domain):
            self._add(signals, "encoded_host",
                      "Host looks like an obfuscated numeric/hex IP address.", confidence=0.6)

        # 2. URL structure heuristics ------------------------------------
        if url["parse_error"]:
            self._add(signals, "percent_encoded_host",
                      "The address could not be parsed cleanly.", confidence=0.5)
        if url["userinfo"]:
            self._add(signals, "url_userinfo",
                      "The URL contains '@' userinfo, a classic trick to disguise the real host.",
                      confidence=0.75)
        if url["percent_encoded_host"]:
            self._add(signals, "percent_encoded_host",
                      "The hostname contains percent-encoded characters.", confidence=0.65)
        if url["port"] is not None and url["port"] not in (80, 443, 8080, 8443):
            self._add(signals, "nonstandard_port",
                      f"Non-standard port {url['port']} is used for a web address.", confidence=0.55)
        if url["scheme"] == "http":
            self._add(signals, "http_downgrade",
                      "The address uses plaintext HTTP instead of HTTPS.", confidence=0.5)
        elif url["scheme"] and url["scheme"] not in ("http", "https"):
            self._add(signals, "encoded_host",
                      f"Non-web URL scheme '{url['scheme']}' was supplied.", confidence=0.6)

        labels = domain.split(".") if domain else []
        tld = labels[-1] if labels else ""
        if tld in SUSPICIOUS_TLDS:
            self._add(signals, "suspicious_tld",
                      f"Top-level domain '.{tld}' is frequently abused for phishing/scams "
                      "(this alone is not proof of malice).", confidence=0.5)
        if len(labels) > self.config["thresholds"]["max_registrable_labels"] + 1:
            self._add(signals, "excessive_labels",
                      f"{len(labels)} hostname labels: unusually deep subdomain chain.",
                      confidence=0.5)

        haystack = (domain + " " + url["decoded_path"]).lower()
        keyword_hits = [kw for kw in PHISHING_KEYWORDS if kw in haystack]
        if keyword_hits:
            self._add(signals, "phishing_keywords",
                      "Credential-related words in the address: " + ", ".join(keyword_hits[:5]) + ".",
                      confidence=0.5)

        reg = registrable_domain(domain) if domain else ""
        # Brand appearing in a subdomain but not in the registrable domain.
        for brand in BRANDS:
            if brand in domain and reg and brand not in reg.split(".")[0] and not allowlisted:
                self._add(signals, "brand_in_subdomain",
                          f"Brand '{brand}' appears in a subdomain while the actual site is "
                          f"'{reg}' — a common phishing layout.", confidence=0.7)
                break

        # 3. Lookalike / homograph detection ------------------------------
        if domain and not is_ip_literal(domain) and not allowlisted:
            for good in feed.allowlist:
                if "." not in good or good == domain:
                    continue
                skeleton_domain = confusable_skeleton(domain.split(".")[0])
                skeleton_good = confusable_skeleton(good.split(".")[0])
                distance = bounded_levenshtein(
                    skeleton_domain, skeleton_good,
                    int(self.config["thresholds"]["typosquat_max_distance"]),
                )
                if distance <= int(self.config["thresholds"]["typosquat_max_distance"]):
                    self._add(signals, "typosquat",
                              f"'{domain}' is one character away from the known service "
                              f"'{good}' after confusable-character normalization.",
                              confidence=0.72)
                    break
        punycode = "xn--" in domain
        if punycode or has_non_ascii(target):
            if has_non_ascii(target) and mixed_script(target):
                self._add(signals, "homograph",
                          "The hostname mixes Unicode scripts, which can imitate Latin letters.",
                          confidence=0.7)
            elif punycode:
                self._add(signals, "homograph",
                          "Punycode (IDN) hostname: check that every character is what it appears to be.",
                          confidence=0.55)

        # 4. DGA / tunneling-like label ---------------------------------
        if labels:
            longest = max(labels, key=len)
            if (len(longest) >= self.config["thresholds"]["dga_label_length"]
                    and shannon_entropy(longest) >= self.config["thresholds"]["dga_entropy"]
                    and consonant_ratio(longest) > 0.62):
                self._add(signals, "dga_label",
                          f"Hostname label '{longest}' has high entropy "
                          f"({shannon_entropy(longest):.2f} bits/char) and looks machine-generated.",
                          confidence=0.62)

        # 5. DNS observations (only from real, supplied resolution data) --
        normalized_answers = []
        for answer in (answers or []):
            if isinstance(answer, dict):
                normalized_answers.append(answer)
            else:
                normalized_answers.append({"ip": str(answer)})
        for answer in normalized_answers:
            ip = str(answer.get("ip", ""))
            if feed.match_ip(ip):
                self._add(signals, "intel_ip_reputation",
                          f"Resolved address {ip} is present in phishing IP reputation data.",
                          source="intel:ip", confidence=0.9, category="phishing",
                          evidence="signed feed IP indicator", kind="verified-intel")
            if is_bogon_ip(ip) and not is_ip_literal(domain):
                self._add(signals, "dns_bogon_answer",
                          f"'{domain}' resolved to non-public address {ip}, a possible "
                          "DNS hijack / sinkhole indicator.", confidence=0.7,
                          category="dns_hijack")
            ttl = answer.get("ttl")
            if isinstance(ttl, (int, float)) and ttl == 0:
                self._add(signals, "dns_ttl_anomaly",
                          "DNS answer has TTL 0, which can indicate fast-flux infrastructure.",
                          confidence=0.5)
        if resolution and resolution.get("rcode") == "NXDOMAIN":
            self._add(signals, "dns_nxdomain",
                      "No DNS answer from the local resolver (NXDOMAIN).", confidence=0.5)
        if beaconing and beaconing.get("is_beacon"):
            self._add(signals, "beaconing",
                      f"Real local event history shows regular repeated connections to this "
                      f"domain ({beaconing.get('samples', 0)} events, low timing variance) — "
                      "a possible command-and-control heartbeat.",
                      confidence=0.7, category="c2", kind="observed-traffic")

        # 6. Redirect chain (only on user/browser-supplied chain) ---------
        chain_findings = self._analyze_redirect_chain(redirect_chain or [], feed, signals)

        # ---- aggregate ---------------------------------------------------
        positives = [s for s in signals if s["weight"] > 0]
        positives.sort(key=lambda s: -s["weight"])
        max_weight = positives[0]["weight"] if positives else 0.0
        bonus = 0.3 * sum(min(40.0, s["weight"]) for s in positives[1:5])
        risk = clamp((max_weight + bonus) / 100.0) * 100.0
        if allowlisted and not any(s["kind"] == "verified-intel" for s in positives):
            risk = max(0.0, risk - 30.0)

        confirmed = [s for s in positives if s["kind"] == "verified-intel"
                     and s["category"] in MALICIOUS_CATEGORIES]
        privacy_only = [s for s in positives
                        if s["category"] in PRIVACY_CATEGORIES or s["id"] == "privacy_intel"]
        heuristic_count = len([s for s in positives if s["kind"] != "verified-intel"])

        suspicious_threshold = float(self.config["thresholds"]["suspicious_risk"])
        min_signals = int(self.config["thresholds"]["min_signals_for_suspicious"])
        if confirmed:
            classification = "confirmed_" + confirmed[0]["category"]
            severity = "critical" if confirmed[0]["category"] in ("c2", "malware") else "high"
        elif risk >= suspicious_threshold and (
                heuristic_count >= min_signals or max_weight >= 60):
            # A single strong, well-explained signal (e.g. a one-character
            # typosquat of a known service) is enough to *flag* — never to
            # auto-block — so users get a warning without a false-positive block.
            classification = "suspicious"
            severity = "high" if risk >= 80 else "medium"
        elif privacy_only and not positives_without_privacy(positives):
            classification = "privacy_risk"
            severity = "low"
        elif positives and not allowlisted:
            classification = "unknown"
            severity = "low"
        else:
            classification = "benign" if allowlisted else "unknown"
            severity = "none"

        confidences = [s["confidence"] for s in positives]
        combined = 1.0
        for c in confidences:
            combined *= (1.0 - float(c))
        confidence = round(clamp(1.0 - combined, 0.0, 0.98), 2) if positives else 0.35
        if classification == "suspicious":
            confidence = min(confidence, 0.8)

        analysis = {
            "input": target,
            "normalized_domain": domain,
            "registrable_domain": reg,
            "is_url": is_url,
            "url": {k: v for k, v in url.items() if k != "raw"},
            "classification": classification,
            "verdict": "confirmed_threat" if confirmed else (
                "suspicious" if classification == "suspicious" else "no_confirmed_threat"),
            "risk": round(risk, 1),
            "severity": severity,
            "confidence": confidence,
            "confirmed": bool(confirmed),
            "suspicious": classification == "suspicious",
            "auto_block_eligible": bool(confirmed),
            "known_good_suppression": bool(allowlisted and not confirmed),
            "signals": positives,
            "intel_matches": intel_matches,
            "redirect_analysis": chain_findings,
            "answers_checked": normalized_answers,
            "recommendations": self._recommendations(classification, domain, signals),
            "explanation": self._explanations(classification, domain, positives, intel_matches, risk),
            "transparency": {
                "engine": "AETHER explainable rule engine (no opaque model)",
                "ruleset_version": self.version,
                "rules_fired": [s["id"] for s in positives],
                "weights_used": {s["id"]: s["weight"] for s in positives},
                "thresholds": copy.deepcopy(self.config["thresholds"]),
                "intel_feed_version": feed.version,
                "intel_feed_sequence": feed.sequence,
                "intel_feed_verified": feed.status == "verified",
                "analyzed_at": now,
                "distinction": "verified-intel = confirmed third-party indicator; "
                               "heuristic = suspicious signal only, never proof",
            },
        }
        return analysis

    def safe_analyze(self, *args, **kwargs) -> dict:
        """Fail-safe: an analysis failure must never break DNS resolution."""
        try:
            return self.analyze(*args, **kwargs)
        except Exception as exc:
            return {
                "input": sanitize_text(args[0] if args else kwargs.get("target", "")),
                "normalized_domain": "", "classification": "engine_error",
                "verdict": "no_confirmed_threat", "risk": 0.0, "severity": "none",
                "confidence": 0.0, "confirmed": False, "suspicious": False,
                "auto_block_eligible": False, "signals": [], "intel_matches": [],
                "redirect_analysis": [], "answers_checked": [],
                "recommendations": [{"priority": "info", "action": "DNS resolution was allowed (fail-safe).",
                                     "why": "The analysis engine reported an error."}],
                "explanation": {"beginner": "We could not analyze this name, so we did not block it.",
                                "intermediate": "Analysis error; fail-open DNS applied to preserve connectivity.",
                                "professional": f"engine exception: {type(exc).__name__}; fail-open to avoid "
                                                "breaking DNS. Error is recorded in the audit log."},
                "transparency": {"engine_error": type(exc).__name__,
                                 "ruleset_version": self.version, "rules_fired": [],
                                 "fail_safe": "allow-on-error"},
            }

    # ------------------------------------------------------------------
    @staticmethod
    def _source_name(feed, source_id: str) -> str:
        for source in feed.sources:
            if source.get("id") == source_id:
                return source.get("name", source_id)
        return source_id or "unknown source"

    def _analyze_redirect_chain(self, chain: list, feed, signals: list) -> list[dict]:
        if not isinstance(chain, list) or not chain:
            return []
        hops = [parse_url(str(hop)) for hop in chain[:12]]
        findings = []
        for idx, hop in enumerate(hops):
            reg = registrable_domain(hop["host"]) if hop["host"] else ""
            findings.append({"hop": idx + 1, "host": hop["host"], "scheme": hop["scheme"],
                             "registrable_domain": reg,
                             "is_shortener": any(
                                 item.get("category") == "url_shortener"
                                 for item in feed.match_domain(hop["host"]))})
        if len(chain) > int(self.config["thresholds"]["redirect_chain_max"]):
            self._add(signals, "long_redirect_chain",
                      f"{len(chain)} redirect hops — long chains are common in malvertising.",
                      confidence=0.55)
        regs = [f["registrable_domain"] for f in findings if f["registrable_domain"]]
        if len(set(regs)) > 2:
            self._add(signals, "redirect_cross_registrable",
                      "The redirect chain crosses several unrelated sites.", confidence=0.6)
        if any(f["scheme"] == "http" for f in findings[1:]):
            self._add(signals, "http_downgrade",
                      "A later redirect hop downgrades to plaintext HTTP.", confidence=0.55)
        if any(f["is_shortener"] for f in findings[:-1]):
            self._add(signals, "url_shortener",
                      "The chain passes through a URL shortener, hiding the final destination.",
                      confidence=0.55)
        if regs and regs[0] and regs[-1] and regs[0] != regs[-1]:
            for kw in ("login", "verify", "account", "secure"):
                if kw in str(chain[0]).lower() and regs[0] not in str(chain[-1]).lower():
                    self._add(signals, "brand_in_subdomain",
                              "Brand-looking first hop ends at an unrelated registrable domain.",
                              confidence=0.6)
                    break
        return findings

    @staticmethod
    def _recommendations(classification: str, domain: str, signals: list) -> list[dict]:
        recs = []
        if classification.startswith("confirmed_"):
            recs.append({"priority": "critical", "action": "Do not open this destination or enter credentials.",
                         "why": "A signed, timestamped threat-intelligence source lists this indicator."})
            recs.append({"priority": "high", "action": "If it was reached from a message, delete the message and warn the sender's contacts.",
                         "why": "Confirmed phishing/malware infrastructure is commonly distributed by messages."})
            recs.append({"priority": "medium", "action": "If you believe this is a false positive, submit feedback for review.",
                         "why": "User feedback creates verified labels used to measure detection accuracy."})
        elif classification == "suspicious":
            recs.append({"priority": "high", "action": "Verify the address through the official app or by typing the known domain yourself.",
                         "why": "Heuristics found suspicious traits, but there is no confirmed intelligence match."})
            recs.append({"priority": "medium", "action": "Check the certificate, the exact hostname and the page asking for credentials.",
                         "why": "Lookalike domains rely on users not checking the full hostname."})
        elif classification == "privacy_risk":
            recs.append({"priority": "medium", "action": "Choose your privacy level; stricter levels block tracking endpoints.",
                         "why": "This is a privacy classification, not a malware verdict."})
        else:
            recs.append({"priority": "info", "action": "No action required. Keep protection enabled and intelligence updated.",
                         "why": "No confirmed threat or strong suspicious signal was found."})
        if any(s["id"] == "dns_bogon_answer" for s in signals):
            recs.append({"priority": "high", "action": "Check your router/DNS settings and compare answers with a trusted resolver.",
                         "why": "A non-public DNS answer for a public domain can indicate DNS hijacking."})
        return recs

    @staticmethod
    def _explanations(classification: str, domain: str, signals: list,
                      intel_matches: list, risk: float) -> dict:
        top = ", ".join(s["id"] for s in signals[:4]) or "no signals"
        evidence = "; ".join(
            f"{m['source_name']} ({m['observed_at']}, confidence {m['confidence']})"
            for m in intel_matches[:2]
        ) or "no third-party intelligence match"
        if classification.startswith("confirmed_"):
            category = classification.replace("confirmed_", "")
            return {
                "beginner": f"This address is on a trusted security list for {category}. "
                            "AETHER will block it automatically. Do not visit it or type passwords there.",
                "intermediate": f"The host '{domain}' matches a verified {category} indicator. "
                                f"Evidence: {evidence}. The decision is based on a signed threat-intel feed, "
                                "not on guesswork. You can request a temporary, expiring allowlist entry if an app breaks.",
                "professional": f"Confirmed {category}: exact/suffix match against signed feed. "
                                f"Rules fired: {top}. Risk={risk:.1f}. Evidence chain: {evidence}. "
                                "Review the signal list and audit entry for full provenance; heuristics are advisory only.",
            }
        if classification == "suspicious":
            return {
                "beginner": "This address looks unusual and could be a trick, but we do not have proof it is dangerous. "
                            "Double-check the exact address before signing in anywhere.",
                "intermediate": f"'{domain}' triggered {len(signals)} heuristic signals ({top}) with risk {risk:.1f}. "
                                "No verified intelligence match exists, so AETHER flags rather than blocks by default.",
                "professional": f"Suspicious (not confirmed). Rules fired: {top}. Risk={risk:.1f}. "
                                f"Intel evidence: {evidence}. Heuristics are deterministic and versioned; "
                                "individual false positives can be reported and become verified labels.",
            }
        if classification == "privacy_risk":
            return {
                "beginner": "This is a tracking or advertising address. It is about privacy, not viruses.",
                "intermediate": f"'{domain}' is a known third-party tracker/ad endpoint. Your privacy level decides whether it is blocked.",
                "professional": f"Privacy classification only. Rules fired: {top}. "
                                "Tracker blocking applies to DNS names the client can observe; no HTTPS payload inspection occurs.",
            }
        return {
            "beginner": "We did not find evidence that this address is dangerous. Keep your protection on.",
            "intermediate": f"No confirmed intelligence match for '{domain}' and no strong suspicious pattern "
                            f"(risk {risk:.1f}). Absence of a match is not a guarantee of safety.",
            "professional": f"No confirmed threat. Rules fired: {top}. Risk={risk:.1f}. "
                            "Detection is limited to DNS-visible names, signed intel and user-supplied evidence; "
                            "unknown or newly registered malicious domains may not yet appear in feeds.",
        }


def positives_without_privacy(positives: list[dict]) -> bool:
    return any(s["category"] not in PRIVACY_CATEGORIES and s["id"] != "privacy_intel"
               for s in positives)
