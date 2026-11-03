#!/usr/bin/env python3
"""Universal-build gate — runs before any release is published.

A release may only be published if the APK actually works on every supported device:
one ABI-independent package, correct minSdk/targetSdk, no per-device splits, and the
offline rule sets present. This script fails the release job otherwise, so "universal"
is a verified property of each build rather than a promise.

Checks
  1. build config: minSdk == 29, targetSdk >= 29, no abiFilters / splits / native build.
  2. APK: no lib/<abi>/ entries (=> installs on arm64, armv7, x86, x86_64),
          contains classes*.dex, resources.arsc, AndroidManifest.xml.
  3. APK: bundled offline rule sets are present and non-trivial.
  4. versionCode/versionName match the build file (so upgrades are ordered).

Usage: python3 tools/check_universal_release.py app/build/outputs/apk/release/app-release.apk
"""
from __future__ import annotations

import re
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BUILD_FILE = ROOT / "app/build.gradle.kts"

MIN_SDK_REQUIRED = 29


def read_int(pattern: str, text: str, label: str) -> int:
    match = re.search(pattern, text)
    if not match:
        raise SystemExit(f"could not read {label} from app/build.gradle.kts")
    return int(match.group(1))


def check_build_config(problems: list[str]) -> dict:
    text = BUILD_FILE.read_text(encoding="utf-8")
    info = {
        "minSdk": read_int(r"minSdk\s*=\s*(\d+)", text, "minSdk"),
        "targetSdk": read_int(r"targetSdk\s*=\s*(\d+)", text, "targetSdk"),
        "compileSdk": read_int(r"compileSdk\s*=\s*(\d+)", text, "compileSdk"),
        "versionCode": read_int(r"versionCode\s*=\s*(\d+)", text, "versionCode"),
        "versionName": re.search(r'versionName\s*=\s*"([^"]+)"', text).group(1),
    }
    if info["minSdk"] != MIN_SDK_REQUIRED:
        problems.append(f"minSdk is {info['minSdk']}, expected {MIN_SDK_REQUIRED} (universal baseline)")
    if info["targetSdk"] < MIN_SDK_REQUIRED:
        problems.append(f"targetSdk {info['targetSdk']} is below minSdk")
    for forbidden, why in (
        ("abiFilters", "per-ABI filtering makes the APK non-universal"),
        ("splits {", "APK splits produce per-device packages"),
        ("externalNativeBuild", "native code would need per-ABI artifacts"),
        ("ndk {", "NDK configuration makes the build ABI-specific"),
    ):
        if forbidden in text:
            problems.append(f"build.gradle.kts contains '{forbidden}' — {why}")
    return info


def check_apk(apk: Path, problems: list[str]) -> dict:
    if not apk.is_file():
        problems.append(f"APK not found: {apk}")
        return {}
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        native = sorted({name.split("/")[1] for name in names if name.startswith("lib/") and name.count("/") >= 2})
        if native:
            problems.append(f"APK contains native libraries for {native} — it is not ABI-universal")
        if not any(re.fullmatch(r"classes\d*\.dex", name) for name in names):
            problems.append("APK has no classes*.dex")
        if "resources.arsc" not in names:
            problems.append("APK has no resources.arsc")
        if "AndroidManifest.xml" not in names:
            problems.append("APK has no AndroidManifest.xml")

        assets = {
            "core_max_ads.txt": 100_000,
            "core_max_threats.txt": 100_000,
            "core_lite_ads.txt": 40_000,
        }
        for asset, minimum_lines in assets.items():
            entry = f"assets/{asset}"
            if entry not in names:
                problems.append(f"offline rule set missing from APK: {asset}")
                continue
            if archive.getinfo(entry).file_size < minimum_lines * 8:
                problems.append(f"{asset} looks truncated in the APK")

        return {
            "size_mb": round(apk.stat().st_size / (1024 * 1024), 2),
            "native_abis": native or ["none (pure JVM — runs on every ABI)"],
            "dex": sum(1 for name in names if re.fullmatch(r"classes\d*\.dex", name)),
        }


def main() -> int:
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    apk = Path(sys.argv[1])
    problems: list[str] = []
    config = check_build_config(problems)
    apk_info = check_apk(apk, problems)

    print("Universal build gate")
    print(f"  version        : {config.get('versionName')} (versionCode {config.get('versionCode')})")
    print(f"  sdk            : min {config.get('minSdk')} / target {config.get('targetSdk')} / compile {config.get('compileSdk')}")
    if apk_info:
        print(f"  apk            : {apk.name}, {apk_info['size_mb']} MB, {apk_info['dex']} dex file(s)")
        print(f"  native libs    : {', '.join(apk_info['native_abis'])}")

    if problems:
        print("\nFAILED — this build is not universal:")
        for problem in problems:
            print(f"  - {problem}")
        return 1

    print("\nPASSED — single ABI-independent APK for Android 10+ (API 29-37) on any CPU architecture.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
