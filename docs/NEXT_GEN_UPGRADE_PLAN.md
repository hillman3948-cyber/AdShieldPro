# AdShield Pro Next-Generation Upgrade Plan

**Audit date:** 2026-10-24  
**Scope:** AdShield Pro v2.1.0 and the public BlockAds Android reference

## Current architecture and reference review

AdShield Pro already uses Kotlin, Jetpack Compose/Material 3, MVVM, Hilt, Room, DataStore, WorkManager, and Android `VpnService`. Its local TUN interface routes DNS to an on-device filter; it supports filter sources and custom rules, DoH/DoT/UDP selection, per-app VPN exclusions, IPv6 configuration, local query history, an embedded browser, and JSON configuration export/restore. The current app already has a midnight navy and emerald visual identity and a substantial protection dashboard. This means the next step is to improve correctness and clarity around those real capabilities, not add simulated analytics.

The public [BlockAds Android repository](https://github.com/pass-with-high-score/blockads-android) provides a broader feature set for comparison: DNS/VPN filtering, optional root proxy, optional browser HTTPS filtering with a local CA, Quick Settings and widget controls, regional filter defaults, and more language coverage. Those features have additional device, certificate, VPN coexistence, and compatibility tradeoffs. The repository is GPL-3.0; this audit does not copy or port its source. Any future reuse or distribution of a derivative must be reviewed against GPL-3.0 source, notice, and installation-information terms.

## Findings addressed in this update

- The IPv6 tunnel literals contained an invalid hexadecimal character, and setup errors were swallowed. The addresses are now valid ULA literals; setup failures reach the existing VPN error path instead of being silently ignored.
- DoH and DoT previously fell back to plaintext DNS after an upstream failure. The resolver now returns SERVFAIL for that request and keeps using the protocol the user selected. Explicit UDP/TCP mode remains available and is disclosed as unencrypted.
- Android backup was enabled for app-private data, including local settings and query history. Platform cloud backup and device-transfer backup are now disabled; users can still use the app's explicit JSON configuration export/restore.
- Release signing previously fell back to the debug key. Release builds now require a real signing keystore. AdShield Pro is free, so the purchase screen, billing SDK, and verifier requirement have been removed.
- Google Play now requires API 36 for new submissions and updates. The project is set to compile/target API 36 and uses the minimum compatible AGP 8.9.3 / Gradle 8.11.1 pair.

## Capability assessment

| Capability | Current status | Recommended direction |
|---|---|---|
| Device-wide DNS filtering without root | Implemented through local `VpnService` | Keep DNS-only routing and state the Android VPN coexistence limitation clearly. |
| Domain, hosts, custom allow/block rules, filter updates | Implemented | Improve failure reporting, list conflict visibility, and parser coverage before adding more formats. |
| DoH and DoT | Implemented; downgrade defect fixed | Keep fail-closed behavior. Show actual resolver/protocol and make any plaintext choice explicit. |
| IPv4 and IPv6 | Intended; invalid IPv6 address defect fixed | Validate on IPv4-only, IPv6-only, and dual-stack networks before release. |
| Application policies | User-controlled VPN exclusions exist | Android exposes limited per-app DNS attribution; avoid claiming per-app histories the VPN cannot reliably observe. |
| Browser cosmetic filtering | Available in the embedded browser | Keep browser-only cosmetic/scriptlet claims scoped to supported rules; DNS filtering cannot remove first-party video ads. |
| AI threat detection/recommendations | Not implemented | Start with explainable local heuristics and user-approved suggestions; never auto-block unknown domains. |
| Root filtering | Not implemented | Optional root mode needs a separate threat model and device testing; do not combine with normal VPN claims. |
| HTTPS payload inspection | Not implemented | Do not add broad TLS interception or root certificates. Android certificate pinning and user trust controls make it unreliable and unsafe as a general feature. |
| Live per-app analytics and precise bandwidth savings | Not reliably supported | Report only measured local DNS counters; label estimates and avoid attributing traffic the service does not see. |
| Quick Settings tile, widget, Filipino localization, search | Not confirmed as complete | Treat as follow-on product work after API 36 behavior and accessibility are validated. |

## User-visible effects of this update

- When IPv6 protection is enabled, the VPN can now build the intended IPv6 DNS route. If Android rejects that route, the service reports a setup error instead of silently claiming the tunnel is fully configured.
- If a DoH/DoT provider is down, allowed domain lookups can fail until the encrypted resolver works again or the user deliberately selects UDP/TCP. This avoids accidental plaintext leakage.
- App-private data is no longer included in platform backups or device transfers. Users who want to move custom rules and settings should export the JSON configuration first; DNS query history is not part of that export.
- Local debug builds remain available without production secrets. Release builds stop before packaging until the signing configuration is supplied.
- Raising target SDK to API 36 applies newer Android behavior and is required for Play submission. It does not itself add HTTPS inspection or bypass other VPNs.

## Implementation and release phases

1. **Correctness and privacy (done in this update):** repair IPv6 setup, prevent encrypted DNS downgrade, disable automatic app-data backup, correct privacy disclosures.
2. **Release integrity (done in source; credentials remain operator-provided):** update target/tooling baseline; refuse debug-signed release; require production verifier URL.
3. **Build and regression validation (partly verified):** `testDebugUnitTest`, `assembleDebug`, and `lintDebug` passed on AGP 8.9.3 / API 36; API 36 was installed by the build tool. No Android device is attached (`adb devices` is empty), so physical install/upgrade, IPv4-only/dual-stack, network-switch, battery, and memory checks remain pending. No performance superiority claim without a reproducible comparison against the reference.
4. **Product follow-up:** complete a screen-by-screen UX refresh; audit optimizer copy against actual Android APIs; improve resolver/VPN diagnostics; add accessibility/navigation checks; then prioritize search, Quick Settings tile/widget, and Filipino strings. Lint reports newer versions for 21 AndroidX/Test libraries plus AGP 9.4.1; update those in a separate compatibility pass because the larger upgrades need migration and regression checks.
5. **Store readiness:** finish VPN and special-use foreground-service declarations, privacy/data-safety answers, signed AAB, and user testing. Do not submit until these external items are complete.

## External requirements

- Install Android SDK `platforms;android-36` (build-tools 36.0.0 is already present in the inspected SDK).
- Configure `ADSHIELD_KEYSTORE_PATH`, `ADSHIELD_KEYSTORE_PASSWORD`, `ADSHIELD_KEY_ALIAS`, and `ADSHIELD_KEY_PASSWORD` for a signed release.
- Complete Google Play VpnService and special-use foreground-service declarations and the Data Safety form.
- Confirm the developer owns the relevant branding, package identity, and any list content distributed with the app.
