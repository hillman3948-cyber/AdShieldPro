#!/usr/bin/env python3
"""Build the signed, bundled threat-intel feed, the labeled evaluation corpus
and the Android on-device baseline.

Usage (from repo root):
    .venv/bin/python aether/tools/build_feed.py [--offline]

--offline uses only SHA-pinned files already present in aether/data/cache.
The private signing key is written to aether/data/keys (git-ignored); the
matching public verification key is committed to aether/intel/trusted_keys.
"""
from __future__ import annotations

import argparse
import json
import random
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parent
sys.path.insert(0, str(ROOT))

from core.crypto import KeyStore  # noqa: E402
from core.intel import (  # noqa: E402
    IntelManager, sign_feed, verify_feed_file, MALICIOUS_CATEGORIES,
)
from core.util import utcnow  # noqa: E402


def synthetic_lookalikes(benign: list[str], count: int = 60) -> list[str]:
    rng = random.Random("aether-synthetic-fixtures")
    swaps = {"o": "0", "l": "1", "e": "3", "i": "1", "a": "4", "rn": "m", "m": "rn"}
    out = set()
    hosts = [b for b in benign if "." in b and len(b) < 30]
    attempts = 0
    while len(out) < count and attempts < count * 40:
        attempts += 1
        base = rng.choice(hosts)
        parts = base.split(".")
        label = parts[0]
        for old, new in swaps.items():
            if old in label:
                label = label.replace(old, new, 1)
                break
        else:
            label = label + rng.choice(["-verify", "-secure", "-login", "update"])
        candidate = ".".join([label] + parts[1:])
        if candidate != base and candidate not in benign:
            out.add(candidate)
    return sorted(out)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--offline", action="store_true",
                        help="fail instead of contacting GitHub (use existing cache)")
    args = parser.parse_args()

    keys = KeyStore(ROOT)
    public_target = ROOT / "intel" / "trusted_keys" / f"build-{keys.key_id()}.pem"
    if not public_target.exists():
        public_target.write_bytes(keys.public_pem())
        print(f"[keys] committed public verification key -> {public_target.relative_to(REPO)}")
    print(f"[keys] private signing key (git-ignored) -> {keys.signing_key_path.relative_to(REPO)}")

    manager = IntelManager(ROOT, keys, offline=args.offline)
    feed_obj, holdout = manager.build_feed(sequence=1, with_holdout=True)
    active_sources = [s for s in feed_obj["sources"] if s.get("status") == "active"]
    if not active_sources:
        raise SystemExit("No source data available. Run once with network access, then use --offline.")
    print(f"[fetch] {len(active_sources)} active sources, "
          f"{len(feed_obj['indicators'])} indicators, errors={len(feed_obj['build_errors'])}")

    sign_feed(keys, feed_obj, manager.bundled_path, manager.bundled_sig)
    verified, status = verify_feed_file(manager.bundled_path, manager.bundled_sig, keys)
    if verified is None:
        raise SystemExit(f"Bundled feed failed self-verification: {status}")
    print(f"[sign] bundled feed verified with key {verified.key_id}: "
          f"{verified.indicator_count} indicators, sha256={verified.raw_sha256[:16]}...")

    # ---- labeled evaluation corpus -------------------------------------
    rng = random.Random("aether-corpus-v1")
    domain_source_ids = {s["id"] for s in manager.sources_config["sources"]
                         if s.get("kind") == "domain"}
    malicious_active = [i["value"] for i in feed_obj["indicators"]
                        if i.get("category") in MALICIOUS_CATEGORIES
                        and i.get("kind") == "domain"]
    corpus = []
    for value in rng.sample(malicious_active, min(240, len(malicious_active))):
        corpus.append({"domain": value, "label": "malicious", "split": "validation",
                       "origin": "active-feed", "synthetic": False})
    # IP reputation entries are evaluated by dedicated IP tests, not domain fixtures.
    for sid, values in holdout.items():
        if sid not in domain_source_ids:
            continue
        for value in rng.sample(values, min(100, len(values))):
            corpus.append({"domain": value, "label": "malicious", "split": "holdout",
                           "origin": "held-out-" + sid, "synthetic": False})
    benign = sorted(set(manager.curated["allowlist"] + manager.curated["compatibility_domains"]))
    for value in benign:
        corpus.append({"domain": value, "label": "benign", "split": "validation",
                       "origin": "curated-allowlist", "synthetic": False})
    known_feed = {i["value"] for i in feed_obj["indicators"]}
    for value in synthetic_lookalikes(benign):
        if value not in known_feed:
            corpus.append({"domain": value, "label": "malicious", "split": "validation",
                           "origin": "synthetic-typosquat-fixture", "synthetic": True})
    corpus_file = ROOT / "corpus" / "labeled_domains.json"
    corpus_file.write_text(json.dumps({
        "schema": 1,
        "created_at": utcnow(),
        "description": "Labeled validation corpus. 'active-feed' and 'curated-allowlist' "
                       "items reflect live indicators; 'held-out-*' items are real IOCs "
                       "excluded from the bundled feed to measure generalization; "
                       "'synthetic-typosquat-fixture' items are clearly labelled test "
                       "fixtures, NOT live user telemetry. Verified user feedback labels "
                       "from the local database are added at evaluation time.",
        "items": corpus,
    }, indent=1) + "\n")
    (ROOT / "corpus" / "compatibility_domains.json").write_text(
        json.dumps({"description": "Popular services that must never be auto-blocked "
                                   "by a candidate detection model.",
                    "domains": manager.curated["compatibility_domains"]}, indent=1) + "\n"
    )
    counts: dict[str, int] = {}
    for item in corpus:
        key = f"{item['split']}:{item['label']}"
        counts[key] = counts.get(key, 0) + 1
    print(f"[corpus] {len(corpus)} labeled items -> {counts}")

    # ---- Android offline baseline ---------------------------------------
    asset = REPO / "app" / "src" / "main" / "assets" / "aether_threat_baseline.txt"
    chosen = [i for i in feed_obj["indicators"] if i.get("category") in MALICIOUS_CATEGORIES]
    chosen = rng.sample(chosen, min(1200, len(chosen)))
    lines = [
        "# AETHER AI on-device threat baseline (generated by aether/tools/build_feed.py)",
        f"# generated_at={utcnow()}  feed_version={feed_obj['version']}  records={len(chosen)}",
        "# Format: category|domain|source_id|observed_at|confidence",
        "# Indicators are real third-party intelligence, not simulated data.",
    ]
    for i in chosen:
        lines.append("|".join([i["category"], i["value"], i["source"],
                               i["observed_at"], str(i["confidence"])]))
    asset.parent.mkdir(parents=True, exist_ok=True)
    asset.write_text("\n".join(lines) + "\n")
    print(f"[android] threat baseline asset -> {asset.relative_to(REPO)} ({len(chosen)} IOCs)")

    privacy_asset = REPO / "app" / "src" / "main" / "assets" / "aether_privacy_baseline.txt"
    privacy_lines = [
        "# AETHER AI on-device privacy baseline (trackers/ads/telemetry, NOT malware)",
        f"# generated_at={utcnow()}  feed_version={feed_obj['version']}",
        "# Format: category|domain|source_id|observed_at|confidence",
    ]
    for i in feed_obj["indicators"]:
        if i.get("category") in ("tracker", "ad", "telemetry", "url_shortener"):
            privacy_lines.append("|".join([i["category"], i["value"], i["source"],
                                          i["observed_at"], str(i["confidence"])]))
    privacy_asset.write_text("\n".join(privacy_lines) + "\n")
    print(f"[android] privacy baseline asset -> {privacy_asset.relative_to(REPO)}")


if __name__ == "__main__":
    main()
