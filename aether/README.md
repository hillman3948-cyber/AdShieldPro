# AETHER AI — OMNISHIELD X INFINITY

A working, privacy-first cybersecurity service and dashboard that extends the existing
AdShield Pro Android DNS protection with explainable threat detection, signed threat
intelligence, autonomous policy control, a tool-using security assistant, network
monitoring, privacy analysis, education and a measured model-improvement pipeline.

**Design principle:** real protection, measurable accuracy and user control over
exaggerated AI claims. AETHER does not pretend to be a general-purpose artificial
intelligence. Its default brain is a deterministic, versioned, fully explainable rule
engine with verifiable evidence; an optional OpenAI-compatible LLM can rephrase
grounded facts but can never choose tools or change policy.

---

## Quick start (live dashboard + APIs)

```bash
bash aether/run.sh                     # creates .venv, installs cryptography, serves 0.0.0.0:8000
AETHER_PORT=8080 bash aether/run.sh     # custom port
```

Open `/` for the dashboard. The server prints an **operator API token** and an
**admin token** at startup (also stored in `aether/data/api.token` and
`aether/data/admin.token`, git-ignored, mode 0600). The browser gets an HttpOnly
session cookie plus a CSRF token automatically; CLI/agent clients use:

```bash
curl -H "Authorization: Bearer $(cat aether/data/api.token)" \
     http://127.0.0.1:8000/api/v1/dashboard
```

Run the tests:

```bash
.venv/bin/python aether/run_tests.py          # Python core + HTTP integration (44 tests)
bash aether/tools/kotlin_jvm/run.sh           # Kotlin 2.0.21 compile + 12 JVM unit tests + 17 integration checks
```

The Python suite needs no network. The Kotlin harness downloads a JDK 17 and the
matching Kotlin compiler once (from PyPI/npm) and uses small JVM stubs for Android /
Compose / Hilt APIs; it is not a replacement for `./gradlew assembleDebug`.

Rebuild the signed bundled feed, the labeled corpus and the Android offline assets
(requires HTTPS access to `api.github.com`):

```bash
.venv/bin/python aether/tools/build_feed.py          # network
.venv/bin/python aether/tools/build_feed.py --offline # uses SHA-pinned cache, zero network calls
AETHER_OFFLINE=1 bash aether/run.sh            # service uses only cached/verified intel
```

Cross-platform real DNS event agent:

```bash
AETHER_API_TOKEN=$(cat aether/data/api.token) \
  python3 aether/agent/aether_agent.py --domain example.com
AETHER_API_TOKEN=$(cat aether/data/api.token) \
  python3 aether/agent/aether_agent.py --tail /path/to/real/dns.log
```

---

## What is actually implemented

| Area | Working implementation |
|---|---|
| Detection | Versioned rule engine + 14,419 real indicators from 4 evidence-linked sources; confirmed vs suspicious distinction; rule ids, weights, source, timestamp, confidence, evidence; 3 explanation levels |
| Protection | Policy precedence, confirmed-only auto-block by default, user rules, approved expiring allowlist, verified false-positive suppression, versioned rollback, fail-open DNS on engine error |
| Intelligence | Ed25519-signed feed, SHA-256 digest, sequence anti-rollback, atomic swap after verification, 6-hour scheduled refresh, per-source provenance |
| Network | **Real** UDP/53 wire DNS lookups with measured latency and bytes, NXDOMAIN/SERVFAIL handling, event history, burst / NXDOMAIN / unique-subdomain / beacon / bogon anomaly detection, device stats |
| Assistant | Retrieval + tools (investigate, real DNS check, stats), prompt-injection resistance, offensive-request refusal, encrypted transcripts, optional grounded LLM adapter |
| Privacy | Third-party tracker/ad/telemetry classification from signed baseline, 3 privacy levels, tracking-parameter detection on user-supplied resource lists |
| Education | 10 lessons × 3 levels, graded quizzes from fixed answer keys, labs gated by an explicit permission phrase |
| Dashboard | 10 views, live data only, charts from real events, recommendations with evidence, audit-chain status |
| Improvement | Labeled corpus incl. real held-out IOCs, precision/recall/FP/F1, compatibility gate, validation gate, versioned models, activation + rollback |
| Android | `VpnService` hook, on-device analyzer with bundled signed-feed subset, Compose AETHER tab, permission-based settings, existing Room log as audit trail |

## Honest limitations

* DNS names only — no HTTPS payload inspection, no file execution, no offensive actions.
* A DNS filter cannot block first-party streaming ads, apps with hardcoded DoH/QUIC resolvers, or inject cosmetic filtering into other Android browsers.
* Only one always-on VPN can run on Android at a time.
* Held-out real indicators that are NOT in the active feed are mostly missed by heuristics (measured recall ~2%); this is reported, not hidden.
* Heuristic findings are *suspicious*, never proof. Only signed third-party intelligence auto-blocks by default.
* Desktop/browser integration sees only queries forwarded by the agent or user-supplied evidence; no packet sniffing, no root, no CA installation.
* Android release builds require a JDK, Android SDK 36 and a signing keystore; see `docs/BUILD_AND_SIGNING_GUIDE.md`.

## Documentation

- [`docs/aether/MODULES.md`](../docs/aether/MODULES.md) — all 12 modules, capabilities, evidence, limitations
- [`docs/aether/API.md`](../docs/aether/API.md) — authenticated endpoints, scopes, examples
- [`docs/aether/SECURITY.md`](../docs/aether/SECURITY.md) — privacy, crypto, auth, fail-safety, audit
- [`docs/aether/CROSS_PLATFORM.md`](../docs/aether/CROSS_PLATFORM.md) — Android / Windows / macOS / Linux / browsers / networks
- [`docs/aether/TEST_RESULTS.md`](../docs/aether/TEST_RESULTS.md) — measured test + performance results
