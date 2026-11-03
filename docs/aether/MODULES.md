# AETHER AI — Module Documentation & Limitations

Every module below is implemented in working code. Each entry states what it really
does, where it lives, what evidence it produces, and what it cannot do. No
simulated statistics are shown anywhere in the product.

| # | Module | Status | Primary code |
|---|---|---|---|
| M1 | Security Brain | working | `aether/core/detection.py`, `app/.../aether/AetherAnalyzer.kt` |
| M2 | Universal Cyber Defense | working | `aether/core/intel.py`, `aether/intel/feed.json` |
| M3 | Autonomous Protection | working | `aether/core/policy.py`, `AetherPolicy.kt` |
| M4 | Security Assistant | working | `aether/core/assistant.py`, `AetherAssistant.kt` |
| M5 | Threat Intelligence | working | `aether/core/intel.py`, `aether/tools/build_feed.py` |
| M6 | Network Guardian | working | `aether/core/guardian.py` |
| M7 | Privacy Intelligence | working | `aether/core/privacy.py` |
| M8 | Security Education | working | `aether/core/education.py`, `AetherEducation.kt` |
| M9 | Global Dashboard | working | `aether/web/*`, `aether/core/api.py` |
| M10 | Security/Privacy/Reliability | working | `aether/core/crypto.py`, `store.py`, `api.py` |
| M11 | Cross-Platform Intelligence | working (documented limits) | `aether/agent/aether_agent.py`, Android integration |
| M12 | Continuous Improvement | working | `aether/core/improvement.py`, `aether/corpus/*` |

---

## M1 — AETHER Security Brain

**Does:** analyzes domains, URLs and IP literals with a deterministic, versioned rule
engine. Combines (a) exact/parent-suffix matches against verified signed intelligence
and (b) explainable heuristics: typosquat / confusable-character detection, IDN
homograph, `@` userinfo, suspicious TLDs, credential keywords, DGA-like label
entropy, excessive subdomain depth, non-standard ports, encoded hosts, redirect-chain
crossing, DNS bogon answers, TTL anomalies and real beaconing regularity.

**Evidence per finding:** `id`, `weight`, `detail`, `source`, `observed_at`,
`confidence`, `category`, `evidence`, `kind` (`verified-intel` / `heuristic` /
`observed-traffic`). Three explanation levels (beginner / intermediate /
professional) plus actionable recommendations are generated for every analysis.
Confidence is explicitly capped for heuristic-only findings (0.80), and suspicious
is never presented as confirmed.

**Not:** a generative LLM, a malware sandbox, or an opaque classifier. It does not
execute files, open URLs, inspect TLS payloads or claim guaranteed detection.

---

## M2 — Universal Cyber Defense

**Does:** classifies phishing, malware, scam, C2/malvertising, ad/tracker/telemetry
infrastructure, suspicious redirects (from a user/browser-supplied chain), malicious
IP reputation (both host IPs and real DNS answers), DNS hijack indicators (private /
reserved answers, TTL 0), dangerous connection patterns (beaconing from real event
history) and known URL shorteners.

**Data:** 14,419 indicators (11,419 domains + 3,000 IPs) from four real sources with
license + URL + commit SHA + retrieval timestamp. Categories `malware` (9,000),
`phishing` (3,003), `scam` (2,500), `tracker/ad/telemetry/url_shortener` (33).
Privacy categories are explicitly labelled as privacy risks, not malware.

**Not:** HTTPS content filtering, first-party video-ad blocking, antivirus scanning,
or protection against domains absent from feeds and heuristics. Newly registered
malicious domains can be missed — measured held-out recall is reported honestly in
the Models view.

---

## M3 — Autonomous AI Protection

**Does:** evaluates every analyzed DNS request against a configurable policy:

1. approved, unexpired temporary allowlist
2. explicit user allow/block rules
3. verified false-positive labels (suppress auto-block, keep the finding visible)
4. confirmed intelligence → **block** (default on)
5. privacy tracker → block only at `strict`, flag at `balanced`, allow at `minimal`
6. suspicious heuristics → flag; auto-block only if the user explicitly enables it
   and risk ≥ threshold
7. allow

Changes are versioned snapshots with one-step rollback; temporary allow entries
require the exact phrase `I APPROVE THIS TEMPORARY ALLOWLIST`, an approver, a reason
and an expiry (max 24h). Engine errors **fail open** so DNS connectivity is preserved.
All decisions write hash-chained audit entries.

**Android:** `AdShieldVpnService.processDnsQuery()` calls
`AetherThreatEngine.analyze()` on the existing DNS path after the original
`FilterRuleEngine` evaluation. Existing blocking behavior is untouched; AETHER only
adds confirmed-intelligence / strict-privacy blocks and logs them into the existing
Room activity log with source `AETHER AI: …`.

---

## M4 — AI Security Assistant

**Does:** deterministic intent classification over 11 evidence-linked knowledge
topics plus real tools — `investigate-domain`, `real-dns-lookup`, `network-stats`,
knowledge retrieval. Covers phishing, malware warnings, DNS troubleshooting, Wi-Fi,
Android/Windows/macOS/Linux hardening, privacy/tracking, account compromise,
incident response, defensive/ethical hacking, secure development and how AETHER works.
Answers are available at three explanation levels and include citations/evidence.

**Safety:** all user text is treated as untrusted data; 8 prompt-injection patterns
are detected and explicitly ignored; the assistant is read-only (cannot change
policy); offensive requests without ownership/permission are refused; transcripts are
AES-256-GCM encrypted, capped to 20 messages per session.

**Optional real LLM:** set `AETHER_LLM_URL` (+ `AETHER_LLM_API_KEY`,
`AETHER_LLM_MODEL`) to an OpenAI-compatible endpoint. The model receives ONLY the
sanitized question and grounded facts, with a fixed defensive system prompt. It
cannot select tools, change settings or invent evidence; any failure falls back to
the deterministic answer. Tested against a local stub LLM server. Disabled by default.

**Not:** a general chatbot, exploit generator, or autonomous agent. No offensive
guidance, no credential requests.

---

## M5 — AI Threat Intelligence

**Does:** fetches real indicators over HTTPS from an allowlist of hosts
(`api.github.com` etc.) using the GitHub contents/blob API with SHA-pinned caching:

| Source | Kind | Category | Records | License |
|---|---|---|---|---|
| HaGeZi Threat Intelligence Feeds (mini) | domain | malware / C2 | 9,000 sampled | GPL-3.0-or-later |
| HaGeZi Fake Shops | domain | scam | 2,500 | GPL-3.0-or-later |
| Phishing.Database — new domains today | domain | phishing | 3 (all) | MIT |
| Phishing.Database — active phishing IPs | ip | phishing | 3,000 | MIT |
| Curated evidence-linked privacy baseline | domain | tracker/ad/telemetry | 33 | curated |

Each feed is written atomically and signed with a local Ed25519 key. Before a feed
becomes active the service verifies: signature against trusted public keys, SHA-256
digest, schema, and monotonic sequence (anti-rollback). A failed update keeps the
previous verified feed. The scheduler refreshes every 6 hours; manual refresh is
admin-gated. `AETHER_OFFLINE=1` (or `build_feed.py --offline`) restricts the service
and feed builder to the SHA-pinned cache only, with a unit test proving no network
call is made. The UI shows source, retrieved timestamp, confidence, evidence URL,
license and commit SHA for every source.

**Not:** a claim of exhaustive global coverage. Sources are sampled deterministically
for resource efficiency (documented in `sources.json`). Feed freshness depends on the
upstream projects and network availability.

---

## M6 — AI Network Guardian

**Does:** performs a **real** RFC 1035 UDP/53 DNS query to the configured/system
resolver, measures actual latency and wire bytes sent/received, parses RCODE, TTL and
A/AAAA/CNAME answers with compression pointers, and falls back to the OS
`getaddrinfo` resolver when UDP/53 is blocked. Every analyzed request is recorded as
a real event. Anomaly detection runs only on recorded events: query bursts, NXDOMAIN
rate, unique-subdomain floods, regular beaconing (coefficient-of-variation test), and
bogon DNS answers. Per-device stats, severity breakdown, hourly timeline, and
measured p50/p95 latency are computed from those events.

**Honesty:** the `dns_bytes_measured` counter is DNS wire traffic only. The API
explicitly states that application bandwidth is NOT visible to a DNS-only filter and
is never estimated. With zero events the dashboard reports "no events yet" rather
than fabricated charts.

---

## M7 — AI Privacy Intelligence

**Does:** given a real page URL + resource URL list (from the user's own browser
network panel or an extension), it identifies known third-party trackers, ad and
telemetry endpoints from the signed baseline, tracks tracking query parameters
(`fbclid`, `gclid`, `utm_*`, …), scores privacy risk and recommends a privacy level.
Users choose `minimal`, `balanced` or `strict`.

**Not:** inspection of HTTPS bodies, cookies, form fields or actual data collection.
A DNS filter cannot see first-party or server-side tracking. On Android, app-level ad
blocking inside other apps is impossible without root/VPN-level restrictions; the UI
says so.

---

## M8 — AI Security Education

**Does:** 10 lessons (foundations, networking, DNS security, phishing, Wi-Fi,
device hardening, privacy, incident response, secure development, ethical hacking)
each with beginner/intermediate/professional text; quizzes graded from fixed answer
keys (no AI grading); three defensive labs requiring the exact phrase
`I OWN OR HAVE EXPLICIT PERMISSION TO TEST THESE SYSTEMS`. Labs cover DNS
observation, audit/rollback and detection-accuracy measurement only.

**Not:** exploit code, payloads, target lists or offensive automation. Security
testing guidance is limited to systems the user owns or has explicit permission to test.

---

## M9 — Global AI Security Dashboard

**Does:** 10 live views (Overview, Investigate, Assistant, Network, Privacy, Intel,
Learn, Models, Events & Audit, Settings) served same-origin with a strict CSP.
Overview shows real protection status, 12 active modules, threats detected/blocked,
suspicious domains, DNS activity, privacy stats, AI recommendations with evidence,
latest intel updates and audit-chain integrity. Investigate shows the full signal
table with source/timestamp/confidence/evidence, three explanations and
recommendations. Charts are drawn from real hourly event buckets.

**Not:** simulated charts, invented counters, or marketing "AI protection scores".

---

## M10 — Security, Privacy & Reliability

**Does:** AES-256-GCM encryption for notes, feedback, assistant transcripts and audit
details; Ed25519 signed/verified intel updates; HttpOnly session cookies + CSRF
double-submit for browser writes; Bearer operator tokens for agents; scope-based
permissions (`read`, `analyze`, `feedback`, `approve`, `policy`, `admin`, `agent`);
rate limiting (240 req/min); 64 KB body cap; strict JSON validation; security headers;
no stack traces to clients; append-only SHA-256 hash chains for events, audit and
policy snapshots with a verification endpoint; minimal pseudonymous data collection;
no sale/upload of personal security data; transparent decisions; prompt-injection
resistance; fail-open DNS on engine errors; measured process CPU/RSS; bounded event
retention with chain re-sealing.

**Not:** a claim of absolute security. Private keys and tokens live in a git-ignored
local directory; operators must protect them. The service is designed for local /
trusted-network deployment, not as a public multi-tenant internet service.

---

## M11 — Cross-Platform Intelligence

See [`CROSS_PLATFORM.md`](CROSS_PLATFORM.md). Android uses the existing VpnService;
desktop uses the agent or OS resolver configuration; browsers supply real resource
lists. Platform restrictions are stated explicitly — in particular no claim of
universal application-level ad blocking.

---

## M12 — Continuous AI Improvement

**Does:** evaluates every detection ruleset on a labeled corpus containing real
active-feed indicators, real **held-out** IOCs excluded from the active feed,
curated benign services, clearly-labelled synthetic typosquat fixtures, and verified
user feedback labels. Reports precision, recall, false-positive rate, F1 (validation,
holdout, verified splits), plus a compatibility check against 27 popular service
domains. Candidate rules must pass schema validation, recall ≥ 0.65, validation FP
rate ≤ 0.12, no precision regression > 0.20 and zero compatibility failures before
an admin can activate them. Models are versioned with their exact metrics and
rollback restores a previous validated version. Verified false-positive feedback is
used to compute a measured FP rate on real events.

**Measured baseline (2026-11-03):** validation precision 1.000, recall 0.929,
FP rate 0.000, F1 0.963 (349 items); held-out real IOC recall 0.020 (200 items).
The low held-out recall is intentionally visible: heuristics cannot replace fresh
threat intelligence, so the system reports the gap rather than claiming perfection.

**Not:** autonomous self-modifying AI. No AI-generated rule can activate without
passing the validation and compatibility gates plus explicit admin action.
