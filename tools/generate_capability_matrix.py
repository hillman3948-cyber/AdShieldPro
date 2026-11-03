#!/usr/bin/env python3
"""Generate docs/CAPABILITY_MATRIX.md from the app's CapabilityRegistry.

The registry (app/src/main/java/com/adshield/pro/engine/platform/CapabilityRegistry.kt) is the
single source of truth shown in the app's Status screen. This script renders the same data as a
markdown table for the repository docs, so the docs can never drift from the app.

It parses the Kotlin `cap(...)` calls literally and fails loudly if it meets a construct it does
not understand — a silent partial parse would produce misleading documentation.

Usage: python3 tools/generate_capability_matrix.py [--check]
  --check  exit non-zero if docs/CAPABILITY_MATRIX.md is out of date (used in CI)
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REGISTRY = ROOT / "app/src/main/java/com/adshield/pro/engine/platform/CapabilityRegistry.kt"
OUTPUT = ROOT / "docs/CAPABILITY_MATRIX.md"

STATUS_LABEL = {
    "IMPLEMENTED": "IMPLEMENTED",
    "PARTIAL": "PARTIAL",
    "PLANNED": "PLANNED",
    "UNAVAILABLE": "UNAVAILABLE",
}

AREA_LABEL = {
    "DNS_PROTECTION": "DNS & network protection",
    "FILTERING": "Filtering & rule engine",
    "PRIVACY_BROWSER": "Privacy browser",
    "THREAT_SCANNING": "Threat scanning",
    "AI_INTELLIGENCE": "On-device intelligence",
    "BATTERY": "Battery & power",
    "DEVICE_MAINTENANCE": "Device maintenance",
    "UPDATES": "Updates & supply chain",
    "PLATFORM": "Platform & operations",
}


def split_top_level(text: str) -> list[str]:
    """Split a Kotlin argument list on commas that are not inside brackets or strings."""
    args: list[str] = []
    depth = 0
    in_string = False
    escape = False
    current: list[str] = []
    for char in text:
        if in_string:
            current.append(char)
            if escape:
                escape = False
            elif char == "\\":
                escape = True
            elif char == '"':
                in_string = False
            continue
        if char == '"':
            in_string = True
            current.append(char)
        elif char in "([{":
            depth += 1
            current.append(char)
        elif char in ")]}":
            depth -= 1
            current.append(char)
        elif char == "," and depth == 0:
            args.append("".join(current).strip())
            current = []
        else:
            current.append(char)
    if "".join(current).strip():
        args.append("".join(current).strip())
    return args


def unquote(value: str) -> str:
    value = value.strip()
    if value.startswith('"') and value.endswith('"'):
        return value[1:-1].replace('\\"', '"')
    return value


def list_items(value: str) -> list[str]:
    value = value.strip()
    if value.startswith("listOf(") and value.endswith(")"):
        inner = value[len("listOf("):-1]
        return [unquote(item) for item in split_top_level(inner) if item.strip()]
    if value in ("emptyList()", "listOf()"):
        return []
    raise SystemExit(f"unrecognised list expression in registry: {value[:80]}")


def parse(source: str) -> list[dict]:
    entries: list[dict] = []
    marker = "\n        cap("
    index = 0
    while True:
        start = source.find(marker, index)
        if start == -1:
            break
        cursor = start + len(marker)
        depth = 1
        in_string = False
        escape = False
        while cursor < len(source) and depth > 0:
            char = source[cursor]
            if in_string:
                if escape:
                    escape = False
                elif char == "\\":
                    escape = True
                elif char == '"':
                    in_string = False
            elif char == '"':
                in_string = True
            elif char in "([{":
                depth += 1
            elif char in ")]}":
                depth -= 1
            cursor += 1
        body = source[start + len(marker): cursor - 1]
        index = cursor
        args = split_top_level(body)
        if len(args) != 9:
            raise SystemExit(f"cap() with {len(args)} args, expected 9: {body[:120]}")
        area = args[2].strip().removeprefix("CapabilityArea.")
        status = args[3].strip().removeprefix("CapabilityStatus.")
        if area not in AREA_LABEL:
            raise SystemExit(f"unknown capability area: {area}")
        if status not in STATUS_LABEL:
            raise SystemExit(f"unknown capability status: {status}")
        entries.append(
            {
                "id": unquote(args[0]),
                "name": unquote(args[1]),
                "area": area,
                "status": status,
                "code": unquote(args[4]),
                "permissions": list_items(args[5]),
                "data_sources": list_items(args[6]),
                "limitations": list_items(args[7]),
                "verification": unquote(args[8]),
            }
        )
    if not entries:
        raise SystemExit("no cap() entries parsed — registry format changed?")
    return entries


def render(entries: list[dict]) -> str:
    counts = {status: sum(1 for e in entries if e["status"] == status) for status in STATUS_LABEL}
    lines: list[str] = []
    lines.append("# Capability matrix")
    lines.append("")
    lines.append(
        "Generated by `tools/generate_capability_matrix.py` from "
        "`app/src/main/java/com/adshield/pro/engine/platform/CapabilityRegistry.kt` — the same data the "
        "in-app **Status** tab renders. Do not edit by hand; run the script instead. CI fails if this "
        "file is stale."
    )
    lines.append("")
    lines.append("## How to read the statuses")
    lines.append("")
    lines.append("| Status | Meaning |")
    lines.append("| --- | --- |")
    lines.append("| IMPLEMENTED | The code path ships in this build and is covered by the JVM suite or a documented device check. |")
    lines.append("| PARTIAL | Part of the capability works; the rest is explicitly not shipped. |")
    lines.append("| PLANNED | Design exists, no shipping code path. Never presented as protection in the UI. |")
    lines.append("| UNAVAILABLE | Android or Play policy makes it impossible or dishonest to implement. |")
    lines.append("")
    lines.append("## Totals")
    lines.append("")
    lines.append("| Status | Count |")
    lines.append("| --- | ---: |")
    for status in STATUS_LABEL:
        lines.append(f"| {status} | {counts[status]} |")
    lines.append(f"| **Total** | **{len(entries)}** |")
    lines.append("")
    for area, label in AREA_LABEL.items():
        rows = [e for e in entries if e["area"] == area]
        if not rows:
            continue
        lines.append(f"## {label}")
        lines.append("")
        lines.append("| Capability | Status | Evidence (code) | Permissions | Data sources | Limitations | Verified by |")
        lines.append("| --- | --- | --- | --- | --- | --- | --- |")
        for row in rows:
            code = f"`{row['code']}`" if row["code"] else "—"
            permissions = "<br>".join(f"`{p.split('.')[-1]}`" for p in row["permissions"]) or "none"
            sources = "<br>".join(row["data_sources"]) or "—"
            limitations = "<br>".join(row["limitations"]) or "—"
            verification = row["verification"]
            lines.append(
                f"| **{row['name']}** | {row['status']} | {code} | {permissions} | {sources} | {limitations} | {verification} |"
            )
        lines.append("")
    lines.append("## Product rules this matrix enforces")
    lines.append("")
    lines.append("- A capability is never shown as protection before it ships: PLANNED rows have no UI and no code pointer.")
    lines.append("- Nothing is claimed that Android cannot deliver (charging-current control, browser fingerprint spoofing, real-time install interception).")
    lines.append("- Every implemented row names its code, its permissions, its data source, its limits and how it is verified.")
    lines.append("- Cloud AI is deliberately absent; all analysis runs on the device.")
    lines.append("")
    return "\n".join(lines)


def main() -> int:
    entries = parse(REGISTRY.read_text(encoding="utf-8"))
    rendered = render(entries)
    check = "--check" in sys.argv
    if check:
        current = OUTPUT.read_text(encoding="utf-8") if OUTPUT.exists() else ""
        if current.strip() != rendered.strip():
            print("docs/CAPABILITY_MATRIX.md is out of date; run tools/generate_capability_matrix.py", file=sys.stderr)
            return 1
        print(f"capability matrix up to date ({len(entries)} capabilities)")
        return 0
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(rendered, encoding="utf-8")
    print(f"wrote {OUTPUT.relative_to(ROOT)} ({len(entries)} capabilities)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
