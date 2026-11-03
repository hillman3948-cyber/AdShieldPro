# AETHER AI API Reference (v1)

Base URL: same origin as the dashboard (e.g. `https://<preview-host>/`). All endpoints
are `/api/v1/…`. JSON request/response, UTF-8, no CORS required (same-origin).

## Authentication

| Method | How | Scopes |
|---|---|---|
| Browser session | `GET /` sets `aether_session` HttpOnly cookie (`SameSite=Lax`) and injects a CSRF token into `<meta name="csrf-token">`. Send `X-CSRF-Token` on every POST/DELETE. | `read, analyze, feedback, approve, policy` |
| Admin unlock | `POST /api/v1/session/authorize` with `{"admin_token": "…"}` adds `admin` to that session. Token is printed at server startup / `aether/data/admin.token`. | `+ admin` |
| Operator token | `Authorization: Bearer <aether/data/api.token>` (all scopes, CSRF not required). Used by the CLI agent and tests. | all incl. `agent` |

Rate limit: 240 requests/minute per identity. Body limit: 64 KB. Invalid JSON → 400.
Missing scope → 403 with the caller's scopes. No stack traces are returned.

## Read endpoints (scope `read` unless noted)

| GET | Returns |
|---|---|
| `/api/v1/config` | feature flags, limitations, docs links (any authenticated session) |
| `/api/v1/session` | current scopes + CSRF token |
| `/api/v1/status` | protection status, versions, 12 modules, measured CPU/RSS, auth mode, audit-chain status |
| `/api/v1/dashboard` | status + real stats + policy + intel + models + audit integrity + recent events + recommendations |
| `/api/v1/events?limit=&device=&severity=` | real event history |
| `/api/v1/network/stats` | totals, timeline, per-device stats, latency percentiles, DNS bytes, anomalies |
| `/api/v1/network/anomalies` | anomalies computed from real events |
| `/api/v1/policy` | full policy incl. expiring allowlist and precedence |
| `/api/v1/policy/snapshots` | versioned rollback snapshots |
| `/api/v1/intel` | feed version, digest, signature status, sources with retrieved timestamp/confidence/evidence, update history |
| `/api/v1/audit?limit=` | decrypted, hash-chained audit entries |
| `/api/v1/audit/verify` | chain verification for audit / events / snapshots |
| `/api/v1/models` | active model, measured metrics, validation gates, version history, verified-event FP rate |
| `/api/v1/education/lessons` | lessons + labs |
| `/api/v1/education/lessons/{id}?level=` | lesson content + quiz questions (no answers) |
| `/api/v1/education/labs` | labs + required permission phrase |
| `/api/v1/assistant/history?session_id=` | decrypted transcript (max 20) |
| `/api/v1/analyze?target=` | GET convenience analysis |

## Write endpoints

| POST / DELETE | Scope | Body / notes |
|---|---|---|
| `/api/v1/analyze` | `analyze` | `{target, redirect_chain?, answers?, resolve?, device_id?, note?, qtype?}` → `{analysis, decision, resolution, event}` |
| `/api/v1/dns/query` | `analyze` | `{domain, device_id?, note?}` — performs a **real** DNS lookup, logs measured latency/wire bytes |
| `/api/v1/dns/events` | `agent` | `{events:[{domain, epoch?, latency_ms?, bytes_out?, bytes_in?, answers?, device_id?, qtype?}]}` — forwards real agent observations |
| `/api/v1/privacy/analyze` | `analyze` | `{page_url, resources:[urls]}` — user-supplied evidence |
| `/api/v1/assistant` | `analyze` | `{message, level, session_id?}` → answer + sections + evidence + injection flags + optional analysis |
| `/api/v1/feedback` | `feedback` | `{domain, label: false_positive\|confirmed\|malicious\|benign, note, verified_by}` |
| `/api/v1/policy` | `policy` | any of `protection_mode, privacy_level, explanation_level, auto_block_confirmed, auto_block_suspicious, suspicious_risk_threshold` |
| `/api/v1/policy/user-rule` | `policy` | `{domain, action: allow\|block\|remove}` |
| `/api/v1/policy/allowlist` | `approve` | `{domain, minutes, reason, approved_by, approval_phrase}` — phrase must be exactly `I APPROVE THIS TEMPORARY ALLOWLIST` |
| `DELETE /api/v1/policy/allowlist?domain=` | `approve` | revoke override |
| `/api/v1/policy/rollback` | `admin` | `{version, reason}` |
| `/api/v1/intel/update` | `admin` | triggers a signed, verified feed refresh |
| `/api/v1/models/evaluate` | `admin` | `{config:{version?,weights?,thresholds?,enabled_rules?}}` → validation report (no activation) |
| `/api/v1/models/activate` | `admin` | `{version}` — requires a passed report |
| `/api/v1/models/rollback` | `admin` | `{version}` |
| `/api/v1/education/quiz` | `read` | `{lesson_id, answers:[indices]}` → graded result |
| `/api/v1/education/lab` | `read` | `{lab_id, permission_phrase}` |
| `/api/v1/session/authorize` | any | `{admin_token}` |

## Examples

```bash
TOKEN=$(cat aether/data/api.token)

# Investigate a suspicious URL and include a real DNS measurement
curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"target":"https://paypa1.com/login","resolve":true}' \
  http://127.0.0.1:8000/api/v1/analyze

# Confirmed threat decision (abbreviated response)
# analysis.classification = "confirmed_malware"; decision.action = "block";
# decision.reason_code = "confirmed_intelligence"; signals[0].source/observed_at/evidence...

# Measure accuracy of a candidate ruleset (admin scope)
curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"config":{"version":"candidate-1","thresholds":{"suspicious_risk":57}}}' \
  http://127.0.0.1:8000/api/v1/models/evaluate
```

## Static assets

`/`, `/index.html`, `/app.js`, `/styles.css`, `/favicon.svg`. Path traversal is
blocked by an explicit allowlist. Security headers: CSP (`default-src 'self'`,
no inline scripts), `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`,
`Cache-Control: no-store`. `X-Frame-Options` is intentionally omitted so the Arena
live preview can embed the dashboard.
