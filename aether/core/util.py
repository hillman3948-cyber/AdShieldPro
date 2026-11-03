"""Shared, dependency-free helpers for AETHER AI.

Everything here is deterministic and local: no network calls, no model inference,
no hidden state. Keeping these primitives small makes the detection rules
auditable and testable.
"""
from __future__ import annotations

import hashlib
import ipaddress
import json
import math
import re
import unicodedata
from datetime import datetime, timezone
from urllib.parse import urlsplit, unquote

MAX_TEXT = 4096

# Small public-suffix subset. Used only to group subdomains for correlation,
# never to make an authoritative registrable-domain claim.
TWO_LEVEL_SUFFIXES = {
    "co.uk", "org.uk", "ac.uk", "gov.uk", "com.au", "net.au", "org.au", "co.nz",
    "com.br", "com.ph", "org.ph", "gov.ph", "co.jp", "co.kr", "com.cn", "com.sg",
    "com.mx", "com.tr", "co.za", "com.hk", "com.tw", "co.in", "com.ua", "com.ar",
}

SUSPICIOUS_TLDS = {
    "tk", "ml", "ga", "cf", "gq", "top", "xyz", "zip", "mov", "click", "link",
    "download", "work", "men", "loan", "review", "party", "gdn", "kim", "country",
    "stream", "science", "racing", "win", "bid", "trade", "date", "faith", "pw",
}

PHISHING_KEYWORDS = (
    "login", "signin", "sign-in", "verify", "verification", "secure", "security",
    "account", "accounts", "update", "confirm", "bank", "banking", "wallet",
    "password", "credential", "support", "billing", "invoice", "recover",
    "unlock", "suspend", "authenticate", "2fa", "otp", "paypal", "appleid",
)

CONFUSABLES = {
    "а": "a", "е": "e", "о": "o", "р": "p", "с": "c", "у": "y", "х": "x",
    "і": "i", "ј": "j", "ѕ": "s", "ԁ": "d", "һ": "h", "ѵ": "v", "ϲ": "c",
    "Α": "a", "Β": "b", "Ε": "e", "Ζ": "z", "Η": "h", "Ι": "i", "Κ": "k",
    "Μ": "m", "Ν": "n", "Ο": "o", "Ρ": "p", "Τ": "t", "Υ": "y", "Χ": "x",
    "0": "o", "1": "l", "3": "e", "4": "a", "5": "s", "7": "t", "8": "b",
    "rn": "m", "vv": "w", "|": "l", "!": "i",
}

INJECTION_PATTERNS = (
    r"ignore\s+(all\s+)?(previous|prior|above)\s+instructions",
    r"disregard\s+(your|the)\s+(system|previous)\s+(prompt|instructions)",
    r"reveal\s+(your|the)\s+(system|developer)\s+prompt",
    r"you\s+are\s+now\s+(a|an|dan|developer|admin)",
    r"(system|developer)\s*:\s*",
    r"jailbreak|dan\s+mode|do\s+anything\s+now",
    r"disable\s+(all\s+)?(protection|blocking|security|the\s+firewall)",
    r"exfiltrat|send\s+(the\s+)?(data|logs|token)\s+to",
)


def utcnow() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def iso_from_epoch(epoch: float | int) -> str:
    """Deterministic UTC ISO-8601 rendering of a Unix timestamp."""
    return datetime.fromtimestamp(float(epoch), timezone.utc).isoformat(
        timespec="seconds"
    ).replace("+00:00", "Z")


def epoch_now() -> float:
    return datetime.now(timezone.utc).timestamp()


def canonical_json(obj) -> str:
    return json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=True)


def sha256_text(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def hmac_equal(a: str, b: str) -> bool:
    import hmac
    return hmac.compare_digest(a.encode(), b.encode())


def sanitize_text(value, limit: int = MAX_TEXT) -> str:
    """Treat user text strictly as data. Strip control characters and cap length."""
    if not isinstance(value, str):
        value = str(value)
    value = value[:limit]
    return "".join(ch for ch in value if ch in "\n\t" or (ord(ch) >= 32 and ord(ch) != 127))


def detect_prompt_injection(text: str) -> list[str]:
    """Return matched injection patterns. Detection does not alter behavior beyond
    emitting a warning: AETHER never executes instructions found in user content."""
    lowered = sanitize_text(text).lower()
    return [p for p in INJECTION_PATTERNS if re.search(p, lowered)]


def is_ip_literal(value: str) -> bool:
    try:
        ipaddress.ip_address(value.strip().strip("[]"))
        return True
    except ValueError:
        return False


def is_bogon_ip(value: str) -> bool:
    """True for addresses that should not appear as a public DNS answer."""
    try:
        ip = ipaddress.ip_address(value.strip())
    except ValueError:
        return False
    return bool(
        ip.is_private or ip.is_loopback or ip.is_link_local or ip.is_reserved
        or ip.is_multicast or ip.is_unspecified
    )


def idna_encode(value: str) -> str:
    try:
        return value.encode("idna").decode("ascii").lower()
    except (UnicodeError, UnicodeDecodeError):
        try:
            import encodings.idna  # noqa: F401
            return ".".join(
                part.encode("idna").decode("ascii") if any(ord(c) > 127 for c in part) else part
                for part in value.split(".")
            ).lower()
        except Exception:
            return value.lower()


LABEL_RE = re.compile(r"^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")


def valid_domain(domain: str) -> bool:
    if not domain or len(domain) > 253 or domain.startswith(".") or ".." in domain:
        return False
    labels = domain.split(".")
    if len(labels) < 2:
        return False
    if len(labels[-1]) < 2 or labels[-1].isdigit():
        return False
    return all(LABEL_RE.match(label) for label in labels)


def normalize_domain(raw) -> str:
    """Normalize a bare domain, a URL, or a host:port into a lowercase ASCII host."""
    text = sanitize_text(raw or "", 2048).strip().lower()
    if not text:
        return ""
    # Strip a scheme if present (including obfuscated ones), then userinfo.
    if "://" in text:
        text = text.split("://", 1)[1]
    if "@" in text:
        text = text.rsplit("@", 1)[1]
    text = text.split("/", 1)[0].split("?", 1)[0].split("#", 1)[0]
    if text.startswith("["):
        text = text[1:text.find("]")] if "]" in text else text[1:]
    elif text.count(":") == 1:
        text = text.split(":", 1)[0]
    text = text.strip().rstrip(".").strip()
    text = idna_encode(text)
    return text


def parse_url(raw: str) -> dict:
    """Best-effort URL decomposition. Never fetches anything."""
    out = {
        "raw": sanitize_text(raw or "", 2048),
        "scheme": "", "host": "", "port": None, "path": "", "query": "",
        "userinfo": False, "is_ip": False, "percent_encoded_host": False,
        "parse_error": False, "decoded_path": "",
    }
    try:
        s = urlsplit(out["raw"] if "://" in out["raw"] else "https://" + out["raw"])
        out["scheme"] = s.scheme.lower()
        hostname = s.hostname or ""
        out["host"] = normalize_domain(hostname)
        out["port"] = s.port
        out["path"] = s.path
        out["query"] = s.query
        out["decoded_path"] = unquote(s.path + ("?" + s.query if s.query else ""))
        out["userinfo"] = "@" in (s.netloc or "")
        out["percent_encoded_host"] = "%" in (s.netloc or "")
        out["is_ip"] = is_ip_literal(out["host"])
    except (ValueError, TypeError):
        out["parse_error"] = True
        out["host"] = normalize_domain(out["raw"])
    return out


def domain_suffixes(domain: str) -> list[str]:
    """Most-specific-first suffix walk, e.g. a.b.example.com -> [a.b.example.com, b.example.com, example.com]."""
    parts = domain.split(".")
    return [".".join(parts[i:]) for i in range(len(parts) - 1)]


def registrable_domain(domain: str) -> str:
    parts = domain.split(".")
    if len(parts) <= 2:
        return domain
    last2 = ".".join(parts[-2:])
    if last2 in TWO_LEVEL_SUFFIXES and len(parts) >= 3:
        return ".".join(parts[-3:])
    return last2


def shannon_entropy(text: str) -> float:
    if not text:
        return 0.0
    counts: dict[str, int] = {}
    for ch in text:
        counts[ch] = counts.get(ch, 0) + 1
    n = len(text)
    return -sum((c / n) * math.log2(c / n) for c in counts.values())


def digit_ratio(text: str) -> float:
    return sum(ch.isdigit() for ch in text) / max(1, len(text))


def consonant_ratio(text: str) -> float:
    letters = [c for c in text if c.isalpha()]
    if not letters:
        return 0.0
    vowels = set("aeiou")
    return sum(c.lower() not in vowels for c in letters) / len(letters)


def bounded_levenshtein(a: str, b: str, max_distance: int = 2) -> int:
    """Edit distance with an early cutoff, so it is cheap on DNS hot paths."""
    if abs(len(a) - len(b)) > max_distance:
        return max_distance + 1
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i] + [0] * len(b)
        row_min = cur[0]
        for j, cb in enumerate(b, 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb))
            row_min = min(row_min, cur[j])
        if row_min > max_distance:
            return max_distance + 1
        prev = cur
    return prev[-1]


def confusable_skeleton(text: str) -> str:
    lowered = text.lower()
    out = []
    i = 0
    while i < len(lowered):
        pair = lowered[i:i + 2]
        if pair in CONFUSABLES:
            out.append(CONFUSABLES[pair])
            i += 2
            continue
        out.append(CONFUSABLES.get(lowered[i], lowered[i]))
        i += 1
    return "".join(out)


def has_non_ascii(text: str) -> bool:
    return any(ord(ch) > 127 for ch in text)


def mixed_script(text: str) -> bool:
    scripts = set()
    for ch in text:
        if not ch.isalpha():
            continue
        name = unicodedata.name(ch, "").split(" ")[0]
        scripts.add(name)
    return len(scripts) > 1


def clamp(value: float, low: float = 0.0, high: float = 1.0) -> float:
    return max(low, min(high, value))


def percentile(values: list[float], p: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    k = (len(ordered) - 1) * p
    lo, hi = math.floor(k), math.ceil(k)
    if lo == hi:
        return round(ordered[lo], 2)
    return round(ordered[lo] * (hi - k) + ordered[hi] * (k - lo), 2)
