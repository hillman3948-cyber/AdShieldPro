#!/usr/bin/env python3
"""AETHER cross-platform DNS event agent (Android companion / desktop).

It forwards *real* DNS observations to the AETHER service. It does not sniff
packets, does not require root/administrator privileges, does not install a CA,
and does not modify system settings. On desktop OSes it resolves names through
the OS resolver and forwards measured latency/answers; it can also forward real
lines from a DNS log file produced by software the user already runs.

Usage:
  AETHER_API_TOKEN=<token> python3 aether/agent/aether_agent.py --status
  AETHER_API_TOKEN=<token> python3 aether/agent/aether_agent.py --domain example.com
  AETHER_API_TOKEN=<token> python3 aether/agent/aether_agent.py --domains-file names.txt
  AETHER_API_TOKEN=<token> python3 aether/agent/aether_agent.py --tail /var/log/dns.log

Environment:
  AETHER_API_URL   default http://127.0.0.1:8000  (use the proxied preview URL for remote UI)
  AETHER_API_TOKEN the operator token printed by the AETHER server (required for /dns/events)
  AETHER_DEVICE_ID optional opaque pseudonymous device label (default: platform hostname hash)
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import re
import socket
import sys
import time
import urllib.error
import urllib.request

API_URL = os.environ.get("AETHER_API_URL", "http://127.0.0.1:8000").rstrip("/")
TOKEN = os.environ.get("AETHER_API_TOKEN", "")
DEVICE = os.environ.get("AETHER_DEVICE_ID") or (
    platform.system().lower() + "-" + hashlib.sha256(platform.node().encode()).hexdigest()[:8]
)
DOMAIN_RE = re.compile(r"^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,}$", re.I)


def call(path: str, payload: dict | None = None) -> dict:
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(API_URL + path, data=data,
                                 method="POST" if data else "GET",
                                 headers={"Accept": "application/json",
                                          **({"Content-Type": "application/json"} if data else {}),
                                          **({"Authorization": "Bearer " + TOKEN} if TOKEN else {})})
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            return json.loads(response.read())
    except urllib.error.HTTPError as err:
        raise RuntimeError(f"HTTP {err.code}: {err.read().decode(err='replace')}") from err


def observe(domain: str) -> dict | None:
    """One real OS resolution with measured latency and real answers."""
    domain = domain.strip().lower().rstrip(".")
    if not DOMAIN_RE.match(domain):
        return None
    start = time.perf_counter()
    epoch = time.time()
    try:
        infos = socket.getaddrinfo(domain, None)
        answers = [{"ip": info[4][0]} for info in infos[:8]]
        rcode = "NOERROR"
    except socket.gaierror:
        answers, rcode = [], "NXDOMAIN"
    latency_ms = round((time.perf_counter() - start) * 1000, 2)
    return {"domain": domain, "epoch": epoch, "device_id": DEVICE,
            "latency_ms": latency_ms, "answers": answers, "rcode": rcode,
            "qtype": "A", "note": f"real OS resolver observation on {platform.system()}"}


def send(events: list[dict]) -> dict:
    if not TOKEN:
        raise RuntimeError("AETHER_API_TOKEN is required (the endpoint needs the agent scope)")
    return call("/api/v1/dns/events", {"events": events[:200]})


def tail_file(path: str, batch: int) -> None:
    """Follow a real DNS log file. Only lines containing a real domain are used."""
    print(f"[aether] following real log {path} (Ctrl+C to stop)…")
    with open(path, "r", encoding="utf-8", errors="replace") as handle:
        handle.seek(0, os.SEEK_END)
        buffer: list[dict] = []
        while True:
            line = handle.readline()
            if not line:
                time.sleep(0.5)
                if buffer:
                    print(send(buffer)); buffer.clear()
                continue
            candidates = re.findall(r"\b(?:[a-z0-9-]+\.)+[a-z]{2,}\b", line, re.I)
            for domain in candidates[:3]:
                buffer.append({"domain": domain.lower(), "epoch": time.time(),
                               "device_id": DEVICE, "qtype": "A",
                               "answers": [], "note": "forwarded real log line"})
            if len(buffer) >= batch:
                print(send(buffer)); buffer.clear()


def main() -> int:
    p = argparse.ArgumentParser(description="AETHER real DNS event agent")
    p.add_argument("--status", action="store_true", help="print service status")
    p.add_argument("--domain", action="append", default=[], help="resolve a real domain (repeatable)")
    p.add_argument("--domains-file", help="file with one real domain per line")
    p.add_argument("--tail", help="follow a real DNS log file")
    p.add_argument("--batch", type=int, default=20)
    args = p.parse_args()
    if args.status:
        print(json.dumps(call("/api/v1/status"), indent=2))
        return 0
    if args.tail:
        try:
            tail_file(args.tail, max(1, args.batch))
        except KeyboardInterrupt:
            print("\n[aether] stopped")
        return 0
    domains = list(args.domain)
    if args.domains_file:
        with open(args.domains_file, encoding="utf-8", errors="replace") as f:
            domains += [line.strip() for line in f if line.strip() and not line.startswith("#")]
    if not domains:
        p.print_help()
        return 1
    events = [e for d in domains if (e := observe(d)) is not None]
    if not events:
        print("No valid domains supplied.", file=sys.stderr)
        return 1
    print(json.dumps(send(events), indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, OSError) as exc:
        print(f"[aether] error: {exc}", file=sys.stderr)
        raise SystemExit(1)
