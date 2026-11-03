# Security, Privacy & Reliability (M10)

## Data minimization

* Only pseudonymous device identifiers (opaque string, ≤64 chars), domain names, rule
  ids, decisions, measured timings and encrypted free-text notes are stored locally
  in `aether/data/aether.sqlite3`.
* No raw IP addresses of users, no user agents, no account identifiers, no browsing
  content, no cookies, no location. DNS answers observed during a real lookup are
  stored (needed for bogon/IP-reputation checks).
* No personal security data is sold, shared or uploaded. Only outbound requests are
  HTTPS to the sources in `aether/intel/sources.json` (GitHub API) and, optionally,
  a user-configured LLM endpoint.
* Assistant transcripts and feedback notes are AES-256-GCM encrypted and capped at 20
  messages per session. Event retention is bounded (50,000 rows) with chain re-sealing.

## Cryptography

| Purpose | Mechanism | Implementation |
|---|---|---|
| Threat-intel authenticity | Ed25519 signature over canonical manifest + SHA-256 feed digest | `core/crypto.py`, `core/intel.py` |
| Anti-rollback | monotonic `sequence`, verified at load and update | `verify_feed_file` |
| Sensitive fields at rest | AES-256-GCM with random 96-bit nonce, AAD `aether-field-v1` | `KeyStore.encrypt_text` |
| Audit integrity | SHA-256 hash chain over canonical rows (genesis = 64 zeros) | `core/store.py` |
| Tokens | `secrets.token_urlsafe(32)`, constant-time comparison | `KeyStore`, `api.py` |

Private signing key, encryption key, API token and admin token are generated on first
run in `aether/data/keys/` and `aether/data/` with mode 0600 and are git-ignored.
Only the **public** verification key is committed (`aether/intel/trusted_keys/`), so a
fresh clone can verify the bundled feed without any secret. A fresh clone that
generates a new local key registers its public key in the local trust store; signed
runtime updates are verified against that trust store.

Feed activation is atomic: candidate → `.tmp` → `os.replace` → **verify** → swap.
Signature failure, digest mismatch, malformed schema, sequence rollback or network
error all leave the previous verified feed active. `GET /api/v1/intel` shows the
verifying key, digest and last update status.

## API security

* HttpOnly `SameSite=Lax` session cookie; CSRF double-submit header required for all
  browser POST/DELETE requests.
* Bearer operator token for headless/agent clients (full scopes, no CSRF needed
  because no cookie is involved).
* Scope-based authorization on every endpoint; admin actions (feed refresh, model
  activation/rollback, policy rollback) require an explicit admin unlock with the
  local admin token.
* 240 req/min rate limit, 64 KB body cap, strict `application/json` validation,
  explicit static-file allowlist, no stack traces to clients.
* CSP blocks inline scripts, external resources and object embedding.

## Prompt injection & unsafe instructions

User-supplied text is sanitized (control characters removed, length capped) and
matched against 8 injection patterns. Matches produce a visible warning and are
**ignored**: the assistant never follows instructions in content, never changes
policy, never reveals a hidden system prompt and never selects tools based on
untrusted directives. The optional LLM receives a fixed defensive system prompt plus
grounded facts only, and its output cannot alter tools, evidence or policy. Offensive
requests without an ownership/permission marker are refused; labs require an exact
permission phrase. AETHER never executes suspicious files or performs offensive actions.

## Fail-safe behavior

* Analysis engine exception → `allow` with `reason_code = fail_safe_engine_error`,
  logged to the audit trail. DNS connectivity is preserved (fail-open).
* Encrypted DNS resolver failure in the Android app continues to return SERVFAIL
  rather than silently downgrading to plaintext (existing behavior preserved).
* Intel update failure → previous verified feed stays active.
* Model validation failure → candidate cannot be activated; previous validated
  ruleset stays active; rollback available.
* Temporary allowlist entries always expire (max 24h); verified false positives
  suppress auto-blocking without deleting intelligence.

## Transparency & audit

Every finding includes rule id, weight, source, timestamp, confidence, evidence and
ruleset version. Decisions include policy version and reason code. Events, audit
entries and policy snapshots are hash-chained; `GET /api/v1/audit/verify` walks all
three chains. Tests prove that editing an audit row breaks verification and that
encrypted fields contain no plaintext.

## Residual risks / limitations

* Local private keys must be protected by the operator; this is a single-user /
  trusted-network service, not a hardened public multi-tenant platform.
* Feed trust is rooted in the upstream projects plus the local signing key. Sources
  are fetched over TLS from GitHub; a compromised upstream could inject indicators,
  which is why compatibility checks, allowlists, verified FP labels and rollback exist.
* DNS-only filtering cannot inspect encrypted payloads; a confirmed indicator is
  evidence about a *name*, not a guarantee about every resource served from it.
* No automatic software update installation: APK updates in the existing Android app
  still require the user to approve installation, and release builds require a real
  signing keystore (see `docs/BUILD_AND_SIGNING_GUIDE.md`).
