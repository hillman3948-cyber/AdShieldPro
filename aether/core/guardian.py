"""Network guardian: real DNS resolution, measured latency/bytes, and anomaly
detection over *actually recorded* events.

No synthetic statistics are ever shown. If no events exist, the dashboard says
"no events yet". Bandwidth figures count only DNS wire bytes that this service
really sent/received; application traffic is not visible to a DNS-only filter.
"""
from __future__ import annotations

import math
import os
import re
import socket
import struct
import time
from collections import defaultdict
from pathlib import Path

from .util import epoch_now, is_bogon_ip, normalize_domain, percentile, registrable_domain, utcnow


def _build_query(domain: str, qtype: int = 1) -> tuple[bytes, int]:
    txid = int.from_bytes(os.urandom(2), "big")
    packet = bytearray(struct.pack("!HHHHHH", txid, 0x0100, 1, 0, 0, 0))
    for label in domain.split("."):
        raw = label.encode("ascii", "replace")[:63]
        packet.append(len(raw))
        packet += raw
    packet.append(0)
    packet += struct.pack("!HH", qtype, 1)
    return bytes(packet), txid


def _skip_name(data: bytes, offset: int) -> int:
    while offset < len(data):
        length = data[offset]
        if length == 0:
            return offset + 1
        if length & 0xC0 == 0xC0:
            return offset + 2
        offset += 1 + length
    return offset


def _parse_answers(data: bytes, ancount: int, qdcount: int = 1) -> list[dict]:
    answers = []
    try:
        offset = 12
        for _ in range(min(qdcount, 8)):
            offset = _skip_name(data, offset) + 4  # question name + qtype/qclass
        for _ in range(min(ancount, 32)):
            offset = _skip_name(data, offset)
            if offset + 10 > len(data):
                break
            rtype, _rclass, ttl, rdlength = struct.unpack("!HHIH", data[offset:offset + 10])
            offset += 10
            rdata = data[offset:offset + rdlength]
            offset += rdlength
            if rtype == 1 and rdlength == 4:
                answers.append({"ip": socket.inet_ntoa(rdata), "ttl": ttl, "type": "A"})
            elif rtype == 5:
                answers.append({"cname": _read_name(data, offset - rdlength), "ttl": ttl, "type": "CNAME"})
            elif rtype == 28 and rdlength == 16:
                answers.append({"ip": socket.inet_ntop(socket.AF_INET6, rdata), "ttl": ttl, "type": "AAAA"})
    except (struct.error, IndexError):
        pass
    return answers


def _read_name(data: bytes, offset: int) -> str:
    labels, jumps = [], 0
    try:
        while offset < len(data) and jumps < 12:
            length = data[offset]
            if length == 0:
                break
            if length & 0xC0 == 0xC0:
                pointer = ((length & 0x3F) << 8) | data[offset + 1]
                offset = pointer
                jumps += 1
                continue
            labels.append(data[offset + 1:offset + 1 + length].decode("ascii", "replace"))
            offset += 1 + length
    except IndexError:
        pass
    return ".".join(labels)


def _resolver_ip() -> str:
    configured = os.environ.get("AETHER_DNS_SERVER")
    if configured:
        return configured
    try:
        text = Path("/etc/resolv.conf").read_text()
        for line in text.splitlines():
            if line.strip().startswith("nameserver"):
                return line.split()[1]
    except OSError:
        pass
    return "127.0.0.53"


RCodes = {0: "NOERROR", 1: "FORMERR", 2: "SERVFAIL", 3: "NXDOMAIN", 5: "REFUSED"}


def parse_dns_response(response: bytes, txid: int) -> dict:
    """Parse a real DNS response header (RFC 1035) and its answer section.

    Header layout: ID(2), FLAGS(2), QDCOUNT(2), ANCOUNT(2), NSCOUNT(2), ARCOUNT(2).
    RCODE is the low 4 bits of FLAGS; QR is bit 15.
    """
    info = {"rcode": "", "answers": [], "qdcount": 0, "ancount": 0,
            "error": None, "transaction_id_ok": False}
    if len(response) < 12:
        info["error"] = "short-response"
        return info
    resp_id, flags, qdcount, ancount, _nscount, _arcount = struct.unpack("!HHHHHH", response[:12])
    info["qdcount"], info["ancount"] = qdcount, ancount
    info["transaction_id_ok"] = resp_id == txid
    if resp_id != txid:
        info["error"] = "transaction-id-mismatch"
        return info
    if not (flags & 0x8000):
        info["error"] = "not-a-response"
        return info
    rcode = flags & 0xF
    info["rcode"] = RCodes.get(rcode, f"RCODE{rcode}")
    info["answers"] = _parse_answers(response, ancount, qdcount)
    return info


def real_dns_lookup(domain: str, timeout: float = 2.0) -> dict:
    """Perform a REAL DNS A query and measure actual wire bytes and latency.
    Falls back to the OS resolver (getaddrinfo) when UDP/53 is unavailable."""
    domain = normalize_domain(domain)
    result = {
        "domain": domain, "method": "", "rcode": "", "answers": [],
        "latency_ms": None, "bytes_out": None, "bytes_in": None,
        "resolver": "", "error": None, "measured_at": utcnow(),
        "note": "Local DNS observation only. This is not a global DNS-hijack verdict.",
    }
    if not domain:
        result["error"] = "invalid-domain"
        return result
    query, txid = _build_query(domain)
    server = _resolver_ip()
    start = time.perf_counter()
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.settimeout(timeout)
        sock.sendto(query, (server, 53))
        response, _ = sock.recvfrom(4096)
        sock.close()
        result["latency_ms"] = round((time.perf_counter() - start) * 1000, 2)
        result["bytes_out"] = len(query)
        result["bytes_in"] = len(response)
        result["resolver"] = server
        result["method"] = "real-udp53-wire-query"
        parsed = parse_dns_response(response, txid)
        result["rcode"] = parsed["rcode"]
        result["answers"] = parsed["answers"]
        result["error"] = parsed["error"]
        result["qdcount"] = parsed["qdcount"]
        result["ancount"] = parsed["ancount"]
        return result
    except Exception as exc:
        result["wire_error"] = f"{type(exc).__name__}: {exc}"
    # Fallback: OS resolver, still real, but no wire-byte measurement.
    start = time.perf_counter()
    try:
        infos = socket.getaddrinfo(domain, None, socket.AF_INET)
        result["latency_ms"] = round((time.perf_counter() - start) * 1000, 2)
        result["answers"] = [{"ip": info[4][0], "ttl": None, "type": "A"}
                             for info in infos[:8]]
        result["rcode"] = "NOERROR"
        result["method"] = "os-getaddrinfo"
        result["resolver"] = "system resolver"
    except socket.gaierror as exc:
        result["latency_ms"] = round((time.perf_counter() - start) * 1000, 2)
        result["rcode"] = "NXDOMAIN" if exc.errno in (-2, 2, socket.EAI_NODATA) else "SERVFAIL"
        result["method"] = "os-getaddrinfo"
        result["error"] = str(exc)
    except Exception as exc:
        result["error"] = f"{type(exc).__name__}: {exc}"
        result["method"] = "os-getaddrinfo"
    return result


class NetworkGuardian:
    def __init__(self, store, detection, policy, intel):
        self.store = store
        self.detection = detection
        self.policy = policy
        self.intel = intel

    def record(self, *, device: str, domain: str, analysis: dict, decision: dict,
               resolution: dict | None = None, qtype: str = "A", note: str = "") -> dict:
        resolution = resolution or {}
        event_id = self.store.add_event(
            device=device, domain=normalize_domain(domain),
            verdict=decision["action"],
            classification=analysis.get("classification", "unknown"),
            risk=analysis.get("risk", 0), severity=analysis.get("severity", "none"),
            ruleset=analysis.get("transparency", {}).get("ruleset_version", ""),
            source=",".join(s["source"] for s in analysis.get("signals", [])[:3]),
            latency_ms=resolution.get("latency_ms"),
            bytes_out=resolution.get("bytes_out"), bytes_in=resolution.get("bytes_in"),
            answers=resolution.get("answers"), qtype=qtype, note=note,
        )
        return {"event_id": event_id, "recorded": True}

    def beacon_context(self, domain: str) -> dict:
        events = [e for e in self.store.all_events(limit=5000)
                  if e["domain"] == normalize_domain(domain)]
        return self._beacon_info(events, domain)

    @staticmethod
    def _beacon_info(events: list[dict], domain: str) -> dict:
        if len(events) < 5:
            return {"is_beacon": False, "samples": len(events)}
        times = sorted(e["epoch"] for e in events)
        intervals = [b - a for a, b in zip(times, times[1:]) if b > a]
        if len(intervals) < 4:
            return {"is_beacon": False, "samples": len(events)}
        mean = sum(intervals) / len(intervals)
        variance = sum((x - mean) ** 2 for x in intervals) / len(intervals)
        cv = math.sqrt(variance) / mean if mean else 1.0
        return {"is_beacon": bool(5 <= mean <= 3600 and cv < 0.25),
                "samples": len(events), "mean_interval_s": round(mean, 2),
                "coefficient_of_variation": round(cv, 3)}

    def anomalies(self, events: list[dict] | None = None) -> list[dict]:
        events = events if events is not None else self.store.all_events(limit=5000)
        out: list[dict] = []
        if not events:
            return out
        now = epoch_now()
        by_device: dict[str, list[dict]] = defaultdict(list)
        by_domain: dict[str, list[dict]] = defaultdict(list)
        by_registrable: dict[str, set[str]] = defaultdict(set)
        for e in events:
            by_device[e["device"]].append(e)
            by_domain[e["domain"]].append(e)
            if now - e["epoch"] <= 300:
                by_registrable[registrable_domain(e["domain"])].add(e["domain"])
            if any(is_bogon_ip(str(a.get("ip", ""))) for a in e["answers"]):
                out.append({
                    "id": f"bogon-{e['id']}", "type": "dns_hijack_indicator",
                    "severity": "high", "domain": e["domain"],
                    "detail": "A DNS answer pointed to a private/reserved address.",
                    "evidence": f"event #{e['id']} at {e['ts']}",
                    "source": "real local DNS observation",
                })
        for device, items in by_device.items():
            recent = [e for e in items if now - e["epoch"] <= 10]
            if len(recent) > 40:
                out.append({
                    "id": f"burst-{device}", "type": "dns_query_burst", "severity": "medium",
                    "device": device, "detail": f"{len(recent)} DNS queries in 10 seconds.",
                    "evidence": "timestamps of recorded events",
                    "source": "real local event history",
                })
            nx = [e for e in items if e.get("answers") == [] and e.get("verdict") == "allow"]
            if len(items) >= 10 and len(nx) / len(items) > 0.5:
                out.append({
                    "id": f"nxdomain-{device}", "type": "nxdomain_rate", "severity": "medium",
                    "device": device, "detail": "More than half of observed lookups returned no answer.",
                    "evidence": f"{len(nx)}/{len(items)} recorded events",
                    "source": "real local event history",
                })
        for root, hosts in by_registrable.items():
            if len(hosts) >= 15:
                out.append({
                    "id": f"flood-{root}", "type": "unique_subdomain_flood", "severity": "medium",
                    "domain": root, "detail": f"{len(hosts)} distinct subdomains in 5 minutes.",
                    "evidence": "recorded event domains grouped by registrable domain",
                    "source": "real local event history",
                })
        for domain, items in by_domain.items():
            info = self._beacon_info(items, domain)
            if info.get("is_beacon"):
                out.append({
                    "id": f"beacon-{domain}", "type": "regular_beaconing", "severity": "high",
                    "domain": domain,
                    "detail": f"{info['samples']} evenly spaced queries "
                              f"(mean {info['mean_interval_s']}s, CV {info['coefficient_of_variation']}).",
                    "evidence": "inter-arrival times of recorded events",
                    "source": "real local event history",
                })
        return out[:50]

    def stats(self) -> dict:
        events = self.store.all_events(limit=5000)
        total = len(events)
        verdicts: dict[str, int] = defaultdict(int)
        classifications: dict[str, int] = defaultdict(int)
        severities: dict[str, int] = defaultdict(int)
        devices: dict[str, dict] = defaultdict(lambda: {"total": 0, "blocked": 0, "flagged": 0})
        domains: dict[str, int] = defaultdict(int)
        latencies, bytes_out, bytes_in = [], 0, 0
        buckets: dict[str, dict] = {}
        for e in events:
            verdicts[e["verdict"]] += 1
            classifications[e.get("classification") or "unknown"] += 1
            severities[e.get("severity") or "none"] += 1
            devices[e["device"]]["total"] += 1
            if e["verdict"] == "block":
                devices[e["device"]]["blocked"] += 1
            if e["verdict"] == "flag":
                devices[e["device"]]["flagged"] += 1
            domains[e["domain"]] += 1
            if e.get("latency_ms") is not None:
                latencies.append(float(e["latency_ms"]))
            bytes_out += int(e.get("bytes_out") or 0)
            bytes_in += int(e.get("bytes_in") or 0)
            hour = time.strftime("%Y-%m-%d %H:00", time.gmtime(e["epoch"]))
            bucket = buckets.setdefault(hour, {"hour": hour, "total": 0, "blocked": 0, "flagged": 0})
            bucket["total"] += 1
            if e["verdict"] == "block":
                bucket["blocked"] += 1
            if e["verdict"] == "flag":
                bucket["flagged"] += 1
        return {
            "measured_at": utcnow(), "total_events": total,
            "events": {"blocked": verdicts.get("block", 0), "flagged": verdicts.get("flag", 0),
                       "allowed": verdicts.get("allow", 0)},
            "classification_breakdown": dict(classifications),
            "severity_breakdown": dict(severities),
            "unique_domains": len(domains),
            "top_domains": [{"domain": d, "count": c} for d, c in
                            sorted(domains.items(), key=lambda x: -x[1])[:10]],
            "devices": dict(devices),
            "latency_ms": {
                "samples": len(latencies), "p50": percentile(latencies, 0.5),
                "p95": percentile(latencies, 0.95), "max": percentile(latencies, 1.0),
                "note": "Measured only for real DNS lookups performed by this service or a connected agent.",
            },
            "dns_bytes_measured": {
                "sent": bytes_out, "received": bytes_in,
                "note": "DNS wire bytes actually observed. Application bandwidth is NOT "
                        "visible to a DNS-only filter and is never estimated or simulated.",
            },
            "timeline": sorted(buckets.values(), key=lambda b: b["hour"])[-24:],
            "anomalies": self.anomalies(events),
            "protection_rate": round(verdicts.get("block", 0) / total, 4) if total else None,
            "empty_state_message": None if total else
                "No DNS events recorded yet. Use Investigate → Real DNS lookup, or connect "
                "the cross-platform agent to forward real queries.",
        }
