"""Privacy intelligence over *user-supplied* resource lists.

A DNS-only filter sees hostnames, not HTTP payloads. This module therefore makes
no claim about what data a site actually collects: it classifies known
third-party tracking infrastructure and flags tracking parameters in URLs that
a browser extension or the user explicitly provides.
"""
from __future__ import annotations

from collections import defaultdict
from urllib.parse import parse_qsl

from .intel import PRIVACY_CATEGORIES
from .util import normalize_domain, parse_url, registrable_domain, sanitize_text, utcnow

TRACKING_PARAMS = {
    "fbclid", "gclid", "dclid", "msclkid", "ttclid", "twclid", "igshid",
    "mc_cid", "mc_eid", "utm_source", "utm_medium", "utm_campaign",
    "utm_term", "utm_content", "ref_src", "click_id", "aff_id",
}

LEVEL_DESCRIPTIONS = {
    "minimal": "Allow known tracking endpoints. Only confirmed threats are blocked.",
    "balanced": "Report trackers, standard tracker blocking via DNS, no aggressive telemetry blocking.",
    "strict": "Block known ads, analytics, telemetry and fingerprinting endpoints at DNS level.",
}


class PrivacyIntelligence:
    def __init__(self, intel):
        self.intel = intel

    def analyze_resources(self, page_url: str, resources: list, level: str = "balanced") -> dict:
        page = parse_url(sanitize_text(page_url, 2048))
        page_host = page["host"]
        page_reg = registrable_domain(page_host) if page_host else ""
        feed = self.intel.current()
        third_party: list[dict] = []
        trackers: list[dict] = []
        categories: dict[str, int] = defaultdict(int)
        params_found: set[str] = set()
        checked = 0
        for raw in (resources or [])[:300]:
            url = parse_url(sanitize_text(str(raw), 2048))
            if not url["host"]:
                continue
            checked += 1
            host_reg = registrable_domain(url["host"])
            is_third_party = bool(page_reg) and host_reg != page_reg
            matches = feed.match_domain(url["host"])
            matched_categories = sorted({m["category"] for m in matches
                                         if m.get("category") in PRIVACY_CATEGORIES})
            for name, _value in parse_qsl(url["query"], keep_blank_values=True):
                if name.lower() in TRACKING_PARAMS:
                    params_found.add(name.lower())
            entry = {
                "host": url["host"], "third_party": is_third_party,
                "categories": matched_categories,
                "evidence": [{"source": m["source"], "observed_at": m.get("observed_at"),
                              "confidence": m.get("confidence"), "value": m["value"],
                              "evidence": m.get("evidence", "")} for m in matches[:3]],
            }
            if is_third_party:
                third_party.append(entry)
            if matched_categories:
                trackers.append(entry)
                for category in matched_categories:
                    categories[category] += 1
        score = min(100, len(trackers) * 12 + len(params_found) * 6
                    + (10 if len(third_party) > 8 else 0))
        recommendations = []
        if trackers:
            recommendations.append({
                "priority": "medium",
                "action": f"Set privacy level to 'strict' to block these {len(trackers)} "
                          "known endpoints at DNS level.",
                "why": "Tracker destinations are in the signed privacy baseline.",
            })
        if params_found:
            recommendations.append({
                "priority": "medium",
                "action": "Strip tracking parameters when sharing links: " + ", ".join(sorted(params_found)[:6]),
                "why": "These parameters identify the click/source across sites.",
            })
        if not resources:
            recommendations.append({
                "priority": "info",
                "action": "Provide a real resource list from your own browser's network panel "
                          "or the AETHER browser extension to get an analysis.",
                "why": "AETHER does not fabricate traffic data.",
            })
        return {
            "page_host": page_host, "page_registrable_domain": page_reg,
            "privacy_level": level, "privacy_level_description": LEVEL_DESCRIPTIONS.get(level, ""),
            "resources_checked": checked,
            "third_party_count": len(third_party),
            "tracker_count": len(trackers),
            "category_counts": dict(categories),
            "tracking_parameters": sorted(params_found),
            "trackers": trackers[:30],
            "third_party_hosts": sorted({e["host"] for e in third_party})[:30],
            "privacy_risk_score": score,
            "recommendations": recommendations,
            "analyzed_at": utcnow(),
            "limitations": [
                "Only hostnames and URLs supplied to this API are inspected; no traffic is intercepted.",
                "A DNS filter cannot see HTTPS bodies, cookies or the fields a site collects.",
                "Not every third-party endpoint is a tracker; classifications come from evidence-linked lists.",
                "Android app-level ad blocking inside other apps is not possible without root or a VPN; "
                "see docs/aether/CROSS_PLATFORM.md.",
            ],
            "intel_feed_version": feed.version,
        }
