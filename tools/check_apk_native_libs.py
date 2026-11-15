#!/usr/bin/env python3
"""Verify the pinned TDLib/OpenSSL native runtime inside a built APK.

`:app:fetchTdlibNative` installs twelve checksum-verified .so files into
`app/src/main/jniLibs/`. This tool closes the loop: it confirms that the APK
actually contains all four ABIs and that the *packaged bytes* still match
`tdlib/native-checksums.sha256` (a corrupted, swapped or unverified library
must never be reported as a successful build).

Usage:
    python3 tools/check_apk_native_libs.py app/build/outputs/apk/debug/app-debug.apk

Exit status is 0 only when every expected entry is present and hash-matched.
"""

from __future__ import annotations

import hashlib
import sys
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
CHECKSUMS_FILE = REPO_ROOT / "tdlib" / "native-checksums.sha256"
EXPECTED_LIBRARIES = 12
APK_PREFIX = "lib/"


def load_pinned_checksums() -> dict[str, str]:
    if not CHECKSUMS_FILE.is_file():
        raise SystemExit(f"missing pinned checksums: {CHECKSUMS_FILE}")
    pinned: dict[str, str] = {}
    for raw in CHECKSUMS_FILE.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split(None, 1)
        if len(parts) != 2:
            raise SystemExit(f"invalid TDLib checksum line: {line}")
        digest, relative_path = parts[0].strip().lower(), parts[1].strip()
        if len(digest) != 64 or any(c not in "0123456789abcdef" for c in digest):
            raise SystemExit(f"invalid SHA-256 digest in checksum file: {digest}")
        if relative_path in pinned:
            raise SystemExit(f"duplicate checksum entry: {relative_path}")
        pinned[relative_path] = digest
    if len(pinned) != EXPECTED_LIBRARIES:
        raise SystemExit(
            f"expected {EXPECTED_LIBRARIES} pinned libraries, found {len(pinned)}"
        )
    return pinned


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def verify(apk_path: Path) -> int:
    pinned = load_pinned_checksums()
    if not apk_path.is_file():
        raise SystemExit(f"APK not found: {apk_path}")

    try:
        archive = zipfile.ZipFile(apk_path)
    except zipfile.BadZipFile as exc:
        raise SystemExit(f"not a valid APK/ZIP: {apk_path}: {exc}") from exc

    with archive:
        entries = set(archive.namelist())
        problems: list[str] = []
        abis: set[str] = set()
        for relative_path, expected_digest in sorted(pinned.items()):
            apk_entry = APK_PREFIX + relative_path
            if apk_entry not in entries:
                problems.append(f"missing {apk_entry}")
                continue
            actual_digest = sha256(archive.read(apk_entry))
            if actual_digest != expected_digest:
                problems.append(
                    f"checksum mismatch for {apk_entry}: "
                    f"expected {expected_digest}, got {actual_digest}"
                )
                continue
            abis.add(relative_path.split("/", 1)[0])

        if problems:
            print(f"FAIL: {apk_path}", file=sys.stderr)
            for problem in problems:
                print(f"  - {problem}", file=sys.stderr)
            return 1

        print(
            f"OK: {apk_path.name} packages {len(pinned)} SHA-256-verified TDLib/OpenSSL "
            f"libraries across {len(abis)} ABIs ({', '.join(sorted(abis))})"
        )
        return 0


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(
            "usage: python3 tools/check_apk_native_libs.py <path-to-apk>",
            file=sys.stderr,
        )
        return 2
    return verify(Path(argv[1]))


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
