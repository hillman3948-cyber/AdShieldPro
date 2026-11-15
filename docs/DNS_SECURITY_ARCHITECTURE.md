# DNS and threat-scanning hardening (Android core)

This document describes the cybersecurity slice added on top of the existing Android
application: resilient upstream DNS resolution, strict response validation, honest DNSSEC
reporting and deterministic DNS-only policy enforcement.

Everything below is DNS-level or file-level on-device behaviour. The tunnel still routes
**only** `10.215.173.2/32` and `fd00:ad5:1e1d::2/128`; no non-DNS socket is inspected, and
nothing here makes AdShield Pro a general-purpose IP firewall.

## 1. Upstream provider plan and failover

`engine/dns/DnsProviderRegistry.kt` turns configuration into an explicit ordered plan:

| Behaviour | Implementation |
| --- | --- |
| Ordered plan | Primary preset first, then the user-selected failover providers, in the configured order. Duplicates are removed. |
| Opt-in only | Without `failoverEnabled`, the plan is exactly one provider. |
| No silent downgrade | Plaintext UDP/TCP 53 never appears as a fallback for a DoH or DoT configuration, and no provider outside the user's selection is contacted. |
| No response merging | The first provider that returns a validated answer wins; answers are never combined. |
| Health ordering | `DnsProviderHealthMonitor` records real success/failure counts and EWMA latency per provider+protocol. `FASTEST_HEALTHY` sorts by measured EWMA only; unmeasured providers keep configured order. |
| Circuit breaking | After 3 consecutive failures a provider is skipped until a 30 s cooldown elapses. Cancellation and interruption are not counted as provider failures. Providers with an open circuit are skipped before the query plan is raced; when every circuit is open the full plan is still tried. |
| Hedged failover | The primary gets a 250 ms head start, then the runner-up is started in parallel and the first validated answer wins. Worst case is one head start plus one provider timeout instead of the sum of every provider timeout. A provider that loses a race is abandoned, not marked unhealthy. |
| Media-aware ordering | For media hostnames only, providers that forward EDNS Client Subnet (`DnsProviderPreset.forwardsClientSubnet`) are moved to the front, because the authoritative server chooses the video CDN edge from that subnet. Ordinary names keep the configured order. Controlled by `preferGeoAccurateMediaDns`. |
| Video Safety | `DnsMediaPlaybackGuard` refuses entity-level, substring-wildcard and shared-CDN rules at parse time (counted in `rejectedVideoUnsafeRules`), and installs media delivery domains as exceptions. Advert delivery hosts stay blocked. See `docs/DNS_AND_VIDEO_SAFETY.md`. |

Supported presets include NextDNS (`https://dns.nextdns.io/dns-query`) and Control D
(`p0.freedns.controld.com`) in addition to the previous providers. The custom preset accepts
explicit IPv4 **and** IPv6 endpoints.

## 2. Strict response validation

`engine/dns/DnsResponseValidator.kt` validates every upstream answer before it can enter the
cache or reach an application. A response is rejected when:

- the transaction ID, opcode or question section (domain, QTYPE, QCLASS) does not match the query;
- the QR bit is unset, the reserved Z bit is set, or the TC bit is set (the client must retry over TCP);
- QDCOUNT is not 1;
- the RCODE is SERVFAIL (2), FORMERR (1) or NOTIMP (4) — NXDOMAIN is a legitimate answer;
- resource records are malformed or the packet carries trailing bytes after the last record.

Record walking is performed by `DnsPacketCodec.inspectResponseRecordTypes`, which walks the
question, answer, authority and additional sections with bounded offsets and rejects
compression-pointer loops rather than trusting header counts.

## 3. DNSSEC: reporting, not local validation

The resolver reports upstream evidence only:

| Reported status | Meaning |
| --- | --- |
| `UPSTREAM_AUTHENTICATED` | The upstream resolver set the Authenticated Data (AD) bit. |
| `SIGNATURES_PRESENT_UNVALIDATED` | RRSIG records are present; no local cryptographic validation was performed. |
| `NOT_AUTHENTICATED` | No AD bit and no RRSIG observed. |

Two modes are offered:

- **Report upstream DNSSEC status** (default): normal unsigned resolution continues and the
  evidence is displayed in Settings diagnostics.
- **Strict: require AD on answers**: positive answers without the AD bit are rejected. This
  intentionally breaks unsigned domains. It is *not* local chain-of-trust validation — no
  DNSKEY/DS fetch and no signature verification happens on the device.

## 4. DNS-only policy: per-app, per-network, emergency

`engine/dns/DnsFirewallPolicyDecider.kt` is pure and deterministic. For every DNS query the
VPN service supplies the owning package and the active transport (`ConnectivityManager`), then
applies, in order:

1. **Emergency allowlist-only** — when enabled, every answer is blocked unless it came from a
   `User Allowlist`, `System Safety Allowlist`, or a non-blocking `User Custom Rule` decision.
   Ordinary blocklist matches never become allow exceptions. The Settings control asks for
   explicit confirmation because this blocks nearly all DNS.
2. **Per-app policy** — `BLOCK_ALL` blocks every DNS query from that package; `ALLOW_ALL`
   bypasses domain filtering for that package.
3. **Per-network policy** — a transport that is not in `protectedTransports` passes through
   without domain filtering, and the UI states that clearly.
4. Otherwise the normal filter-engine decision is used.

This governs DNS decisions only. Apps that use hardcoded IPs, HTTP DNS, or their own encrypted
transport are unaffected, because those packets never enter the tunnel.

## 5. Resolver diagnostics

Settings can run a real diagnostic query (`engine/dns/DnsDiagnostics.kt`): a genuine A query
with EDNS0 and the DO bit, sent through the configured provider plan. It reports the resolved
name, RCODE, answer count, the provider that answered, measured latency, cache status, DNSSEC
evidence and per-provider health.

**It is not a DNS leak test.** No third-party echo service is contacted, so it verifies the
configured resolver path and upstream behaviour, not whether some other resolver on the network
can also observe the device.

## 6. Local file scanning (no YARA)

`FileThreatScanner` keeps the pre-existing, purely local checks: exact signature matches
(industry-standard EICAR test string), polyglot/steganographic executables hidden inside image
and PDF containers, script-based droppers (PowerShell `-enc`, `certutil -urlcache`, `mshta`,
`curl | sh`), macro-laden Office documents, zip-slip archive entries and suspicious
double-extension naming.

A YARA-compatible rule subset was implemented and then **removed** at the maintainer's request,
together with its tests, its capability row and its documentation. The scanner therefore does
**not** support YARA rules: no rule parser ships in this build, and native YARA and ClamAV are
not integrated. Detection remains signature- and structure-based, which the honest limitation
below repeats.

## 7. Verification evidence

JVM tests in `app/src/test/java/com/adshield/pro/CybersecurityPlatformTestSuite.kt`:

| Test | Asserts |
| --- | --- |
| `providerPlanIsExplicitAndNeverCrossFailsPlaintextDns` | plan ordering, preset endpoints, no plaintext failover |
| `healthCircuitOpensClosesAndUsesOnlyMeasuredLatency` | 3-strike circuit, cooldown, EWMA arithmetic from recorded samples |
| `validatorRejectsWrongQuestionTruncationServfailAndTrailingData` | question mismatch, TC bit, SERVFAIL, trailing bytes |
| `dnssecModeReportsUpstreamAdAndStrictModeRejectsUnsignedPositiveAnswers` | AD reporting and strict rejection |
| `resolverFailsOverOnlyAfterValidationAndDoesNotDowngrade` | real failover to the selected provider after a validated answer |
| `resolverCircuitSkipsKnownOpenProviderWithoutExtendingIt` | open circuit is skipped without inflating failure counts |
| `appNetworkAndEmergencyPoliciesAreDeterministic` | per-app, per-transport and emergency decisions |
| `diagnosticQueryUsesEdns0AndNormalisesCase` | EDNS0/DO diagnostic query construction and input validation |
| `fileScannerFlagsEncodedPowerShellDropper` | scanner verdict is driven by a real dropper-pattern match |

CI: Android build & test run `41868023328` (commit `c14f997`) compiled Kotlin, assembled the
debug and R8 release APKs, ran the full JVM suite, Android lint, the test-count gate and the APK
installability gate successfully.

## 8. What is still unverified

- No physical device or emulator run: VPN routing, per-app attribution, battery impact and
  transport detection still need the manual checklist in `docs/TESTING.md`.
- No live interoperability campaign against NextDNS, Control D or a custom IPv6 resolver from a
  real handset.
- No external DNS leak test, as stated above.
- No malware corpus benchmark and no YARA/native-engine integration: the scanner is a signature and
  structure triage aid, not a detection claim.
- No local DNSSEC validation, no DoH3/oblivious DoH, and no general IP-level firewalling.

## 8. DoH bootstrap bound

An encrypted endpoint's own hostname is never resolved through the tunnel. Every
shipped provider is in a literal-address table, and any other lookup through the
system resolver is capped at 1.5 s, so a broken network cannot stall the DoH path
(which every query crosses) for seconds at a time.
