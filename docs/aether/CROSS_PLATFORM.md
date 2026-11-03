# Cross-Platform Intelligence (M11)

AETHER uses **platform-appropriate** integration. It never claims capabilities that
the operating system forbids.

| Platform | Integration method | What works | What does not |
|---|---|---|---|
| Android (existing app) | `VpnService` DNS-only TUN; `AetherThreatEngine` called on every DNS query in `AdShieldVpnService.processDnsQuery` | device-wide DNS filtering for apps using system DNS; confirmed-intelligence auto-block; bundled offline signed-feed subset (1,200 IOCs + privacy baseline); local activity log as audit trail; permission-based settings in the AETHER tab | only one always-on VPN at a time; apps with hardcoded DoH/QUIC bypass the VPN; first-party streaming ads; cosmetic filtering inside other browsers; killing other apps; HTTPS inspection |
| Windows | `aether_agent.py` forwards real OS-resolver observations, or the user configures system/DoH DNS + forwards real DNS logs | measured DNS events, policy decisions, dashboard | no kernel driver, no packet sniffing, no system-wide TLS interception; browser extension only sees that browser |
| macOS | same agent / OS resolver configuration; no root or CA required | same as Windows | no Network Extension / content filter is installed by this repo; no universal app-level ad blocking |
| Linux | agent, or forward real lines from an existing resolver log (`--tail`) | real DNS events, anomaly detection, device stats | no privileged packet capture; no iptables/nftables changes |
| Browsers | user supplies the real resource list from the browser network panel (Privacy tab); a future extension can POST the same shape | third-party tracker classification, tracking-parameter detection | no automatic inspection of all browser traffic without an extension; no claims about other browsers' internals |
| Home / business networks | run the AETHER service on a local host and point devices/agents at it; router-level DNS forwarding is an operator task | central event history, per-device stats, signed intel for the whole LAN | a phone VPN protects only that phone; no rogue-DHCP protection, no switch/router management, no traffic shaping |

## Permission boundaries

* Android: `INTERNET`, `ACCESS_NETWORK_STATE`, `VpnService` consent, foreground service,
  notifications, boot receiver — unchanged from the existing app. No root, no
  accessibility-service abuse, no `QUERY_ALL_PACKAGES` expansion, no installed CA.
* Desktop: the agent uses only `socket.getaddrinfo`, file reads of a log the user
  points at, and HTTPS to the local AETHER API. It does not bind privileged ports,
  install drivers or modify system DNS by itself.
* Assistant/education: security testing guidance is restricted to systems the user
  owns or has explicit written permission to test; labs require an explicit phrase.

## Data flow

```
Android VpnService ──(existing DNS path)──> AetherThreatEngine (on-device, offline)
        │                                        │
        └── existing Room activity log ◄──────────┘ (auditable decisions)

Desktop agent / real DNS logs ──POST /api/v1/dns/events──> AETHER service
Browser resource lists ─────────POST /api/v1/privacy/analyze──> Privacy Intelligence
Dashboard / CLI ─────────────────/api/v1/*──────────────────> signed intel + policy + audit
GitHub sources ──HTTPS──> signed feed builder ──Ed25519 verify──> active intel
```

All processing is local unless the operator explicitly configures an LLM endpoint or
enables the scheduled GitHub feed refresh. The preview environment exposes the
service through the Arena proxy; browser code always uses relative same-origin URLs,
never `localhost`, so it works inside the proxied preview.

## Resource consumption

The service is a threaded Python HTTP server with an in-memory indicator index and a
SQLite event store. Measured process RSS/CPU is exposed at `/api/v1/status`. The
Android engine uses immutable `HashMap` lookups on a 1,200-entry baseline with O(labels)
suffix walks — no per-query network calls, no background model inference, so battery
impact is limited to the existing VPN service. The scheduled feed refresh runs every
6 hours in a daemon thread and can be disabled with `AETHER_AUTO_UPDATE=0`.
