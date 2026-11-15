# AdShield Pro 2.10.2 — Android DNS Ad & Tracker Protection

[![Android build & test](https://github.com/yawiyuson231-oss/AdShieldPro/actions/workflows/android-build.yml/badge.svg)](https://github.com/yawiyuson231-oss/AdShieldPro/actions/workflows/android-build.yml)

AdShield Pro is a non-root Android app that filters DNS requests on-device and includes a built-in privacy browser and device-maintenance tools. It is built with Kotlin, Jetpack Compose (Material 3), Room, DataStore, WorkManager, Hilt, and Android `VpnService`.
## Website and downloads

- **Project website:** <https://yawiyuson231-oss.github.io/AdShieldPro/> — source in
  [docs/](docs), deployed by [`.github/workflows/deploy-website.yml`](.github/workflows/deploy-website.yml).
  One-time setup: repository **Settings → Pages → Source: GitHub Actions** (a repository admin has
  to click it once; the deploy workflow reports it if it is still off).
- **Direct APK download:** [latest AdShield Pro release](https://github.com/yawiyuson231-oss/AdShieldPro/releases/latest)
- [Browse all GitHub releases](https://github.com/yawiyuson231-oss/AdShieldPro/releases)

### Publishing a release

Every published build comes from one pipeline, [`.github/workflows/release.yml`](.github/workflows/release.yml):

```bash
# build the version currently in app/build.gradle.kts and publish tag v<versionName>
gh workflow run "Release APK" --ref main

# or publish a specific version: bump versionCode/versionName, commit, then
git tag v2.3.1 && git push origin v2.3.1
```

The workflow runs the JVM unit tests, builds the R8-minified release APK, checks its structure,
publishes it to GitHub Releases with a SHA-256 checksum, uploads `adshield-release.keystore` for
future upgrade installs, and refreshes the GitHub Pages site. Re-running it for an existing tag
replaces that release's APK assets instead of failing or creating a duplicate release.
To sign with your own key instead, add repository secrets `ADSHIELD_KEYSTORE_BASE64`,
`ADSHIELD_STORE_PASSWORD`, and optionally `ADSHIELD_KEY_ALIAS` / `ADSHIELD_KEY_PASSWORD`.

### Test APK before publishing

[`.github/workflows/test-apk.yml`](.github/workflows/test-apk.yml) builds and publishes **the same
signed, R8-minified APK as a GitHub pre-release**, so it can be installed and exercised on a real
device before the real release goes out:

```bash
# test whatever the branch already contains
gh workflow run "Test APK (pre-release)" --ref main

# test an unmerged pull request (builds exactly that commit, no merge needed)
gh workflow run "Test APK (pre-release)" --ref main -f source_ref=refs/pull/13/head

# a second round on the same version keeps a separate tag
gh workflow run "Test APK (pre-release)" --ref main -f source_ref=arena/my-branch -f tag=test-v2.10.0-rc2
```

It runs the unit tests, UI-performance gate, universal-build gate and a signing-continuity check,
then creates tag `test-v<versionName>` with `AdShieldPro-<versionName>-test.apk` and its SHA-256.
Design guarantees, enforced by the workflow itself:

- **Signed with the published release key**, never a throwaway one — the test APK installs *over*
  the current release as an update, and the real release later replaces it in place. If no release
  keystore is available the job fails instead of producing an un-upgradable APK.
- **Always a pre-release**, so `/releases/latest` — what the in-app updater and the website download
  link resolve — keeps pointing at the shipped version. A final step asserts this and fails if the
  test build ever became *Latest*.
- **Tags must start with `test-`**, so the build cannot match the `v*` tag trigger of the release
  pipeline. The keystore is never attached to a test release; only real releases carry it.

Once the test build is verified, publish for real with the **Release APK** workflow above.


## OmniShield X web platform (browser)

The same repository ships the **OmniShield X — AETHER AI Evolution** web platform: a
local-first DNS security, ad/tracker filtering and threat-intelligence app that runs entirely
in the browser (no backend, no accounts, no telemetry endpoint). It is static files: open it
over HTTP and it works offline after the first visit.

```bash
npm install               # jsdom, used only by the integration tests
npm test                  # 166 unit + 19 integration tests
npm run lint              # house lint rules for the shipped runtime code
npm run verify            # 86 static architecture, claim, artifact and packaging gates
npm run serve             # http://localhost:8080  (ES modules need HTTP, not file://)
npm run build:extension   # regenerate omnishield-x-extension.zip + assets/extension-facts.json
npm run stage             # assemble _site/ exactly as the Pages workflow does
```

| Document | Contents |
|---|---|
| [`docs/WEB_PLATFORM.md`](docs/WEB_PLATFORM.md) | Features, quick start, integration instructions |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Module map: DNS plumbing, AETHER, Firewall AI, recovery, power governor, extension builder |
| [`docs/AETHER.md`](docs/AETHER.md) | Signal weights, evidence floors, calibration methodology and measured metrics |
| [`docs/UNSUPPORTED.md`](docs/UNSUPPORTED.md) | What this platform deliberately does **not** do, and why |
| [`docs/WEB_TESTING.md`](docs/WEB_TESTING.md) | What the browser suite actually verifies |

Capabilities are also listed inside the app on the **Capabilities** page, including the rows
that read *not implemented* — nothing there is aspirational.

## OMNISHIELD X INFINITY native upgrade

The existing Android interface now also includes three integrated workspaces, without replacing the
original DNS, security, optimizer, battery, statistics, settings, or browser modules:

- **Cloud / Infinity** — authenticated Nextcloud/WebDAV folders, streaming upload/download, preview,
  rename/delete, Nextcloud public sharing, Android-Keystore-encrypted passwords, and unified capacity
  calculated only from online providers that return real DAV quota values. A separate **Telegram**
  provider signs in a user account directly through official TDLib, stores files in Saved Messages
  or a private channel, and supports real uploads/downloads with progress, retry, SHA-256
  deduplication and a Keystore-protected session. No backend server or paid service is required;
  Telegram does not promise permanent backup. See
  [`docs/TELEGRAM_CLOUD_STORAGE.md`](docs/TELEGRAM_CLOUD_STORAGE.md).
- **Files** — user-granted SAF trees, import/export through Android providers, preview/share,
  create/rename/delete, current-folder search, SHA-256 duplicate grouping, and bounded recursive scans
  with live progress and persistent reports.
- **Downloads** — direct permitted HTTPS media inspection, MP4/WebM/supported audio downloads through
  Android DownloadManager, destination selection, system queue/history, cancellation, and integrity
  checks. Website extraction, DRM bypass, and universal-site support are deliberately not claimed.
- **Private search and AETHER** — Google/DuckDuckGo provider selection, opt-in suggestions, local
  history controls and private WebView cleanup in the existing Browser; deterministic AETHER summaries
  of persisted security evidence in Security → History.

A real open-source Nextcloud Community + PostgreSQL + Caddy baseline is provided in [`infra/`](infra).
It requires an operator-owned host, domain, credentials, disks, quotas, and backups; merely cloning the
repository provisions no storage and grants no free capacity. Distributed striping/replication,
S3/Ceph adapters, and periodic folder scanning are explicitly listed as remaining work.

| Infinity document | Contents |
| --- | --- |
| [`docs/INFINITY_ARCHITECTURE.md`](docs/INFINITY_ARCHITECTURE.md) | Component boundaries, server deployment, WebDAV URL, capacity semantics, security and backup requirements |
| [`docs/INFINITY_VERIFICATION.md`](docs/INFINITY_VERIFICATION.md) | Feature-by-feature implementation/test status, evidence, external requirements and remaining issues |
| [`docs/TELEGRAM_CLOUD_STORAGE.md`](docs/TELEGRAM_CLOUD_STORAGE.md) | Official TDLib Android integration, direct account authorization, encrypted session, transfers, limits, security and device test checklist |
| [`docs/CAPABILITY_MATRIX.md`](docs/CAPABILITY_MATRIX.md) | Generated in-app/source-of-truth capability matrix |
| [`docs/DNS_SECURITY_ARCHITECTURE.md`](docs/DNS_SECURITY_ARCHITECTURE.md) | Upstream failover, response validation, DNSSEC reporting and DNS-only app/network policy |
| [`docs/INTELLIGENCE_MAP.md`](docs/INTELLIGENCE_MAP.md) | Live Intelligence Map: event pipeline, module data sources, gestures, honesty rules, performance budget and acceptance evidence |
| [`docs/CONNECTORS.md`](docs/CONNECTORS.md) | Connector platform: architecture, free-provider terms, Android limitations, privacy guarantees and verification steps |
| [`docs/CONNECTOR_VERIFICATION.md`](docs/CONNECTOR_VERIFICATION.md) | Generated per-connector verification table (110 entries, 103 implemented, honest gap to 350) |

## App screenshots

<table>
  <tr>
    <td align="center"><img src="docs/images/home-protection.jpg" alt="AdShield Pro home screen showing active DNS protection and statistics" width="280"><br><strong>Home protection</strong></td>
    <td align="center"><img src="docs/images/smart-boost.jpg" alt="AdShield Pro Smart Boost device health screen" width="280"><br><strong>Smart Boost</strong></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/images/protection-profile.jpg" alt="AdShield Pro DNS activity and protection profiles" width="280"><br><strong>Activity and protection profiles</strong></td>
    <td align="center"><img src="docs/images/dns-analytics.jpg" alt="AdShield Pro DNS analytics and live top apps" width="280"><br><strong>DNS analytics and live top apps</strong></td>
  </tr>
</table>

## How it Works

![Animated preview of AdShield Pro](docs/images/adshield-demo.gif)

## What's new in 2.10.2 — On-demand lookups, verified connectors, QR decoding

- **Ask a provider about one value.** Paste a domain, URL, IPv4 address, CVE, SHA-256 or
  `ecosystem:name@version` into the Intel registry and the app queries the free providers that
  accept it: CIRCL hashlookup, CVE.org, OSV.dev, deps.dev, urlscan, RIPEstat prefix/whois, RDAP,
  IPinfo, ARIN and Cloudflare/Google DNS-over-HTTPS. Answers are cached per provider with its TTL;
  an unreachable provider falls back to a cached answer explicitly labelled `CACHED`. Lookups run
  only when you press CHECK — never in the background. See [docs/CONNECTOR_LOOKUPS.md](docs/CONNECTOR_LOOKUPS.md).
- **Catalogue measured, not padded.** 176 unique connectors, 157 implemented (52 HTTPS feeds,
  105 Android-API probes), 6 enabled by default. 13 providers need a key, so they are declared and
  stay `NOT CONFIGURED`; 6 sources have no public machine interface and stay `SOURCE UNAVAILABLE`.
  URLhaus CSV/text, the abuse.ch CSV exports, MISP `special-use`, ReliefWeb v1 and the HaGeZi
  `hosts/tif.txt` path were retired after returning HTTP 410/404 — they are no longer counted.
- **30 new on-device probes**: default roles, device administrators, keyboards, installer
  provenance, private-DNS mode, VPN transport, TrafficStats, Wi-Fi security type, Widevine level,
  OpenGL ES, storage volumes, camera inventory, battery energy counters, JobScheduler,
  ContentObserver/FileObserver, notification channel, exact alarms and DataStore. Reads that need
  privileged permissions (user count) are dropped instead of faked.
- **QR images stay on the device.** Pick an image and the bundled ZXing core decodes it locally,
  then the payload goes through the same URL analyser. No screen capture, no gallery scan, and a
  decode failure is reported as "no code found" — not as "safe".
- **Fixed**: six FIN catalogue entries referenced probes that no dispatch branch implemented; the
  registry check panel now shows per-provider answers next to the local verdict.

Version note: `2.10.0` and `2.10.1` existed only as pre-release test builds, so their version codes
(21000/21001) are already taken. This stable release uses **21002** so every published build,
test or stable, can upgrade to it in place.

## What's new in 2.9.0 — Live Intelligence Map

- **Live Intelligence Map (Intel tab)** — an interactive Compose Canvas map of the seven modules
  around the OMNISHIELD X core. Drag to pan, pinch or double-tap to zoom, tap a node for its real
  details, long-press for contextual actions, drag nodes in layout-edit mode, reset or centre the
  camera with animated transitions. It is a live canvas, not an image, and there is no idle
  animation loop.
- **Unified event pipeline** — DNS malware/phishing blocks, scan-journal verdicts, battery
  temperature/health thresholds, VPN transitions, settings changes and WorkManager results are
  validated, redacted, de-duplicated and stored in a bounded Room table (800 rows / 14 days,
  schema v4 with an additive migration). Pulses, highlights and the timeline are driven by those
  stored events only.
- **No invented activity** — factories return `null` when a real observation cannot supply
  evidence, the sanitizer can raise but never lower severity, unsupported sensors report `OFFLINE`,
  and an idle device shows an explicit "no event recorded" sentence instead of demo data.
- **Command panel & timeline** — collapsible panel with overall health, seven live module states,
  measured counters (queries, blocks, DNS, privacy/financial/device findings, automation runs),
  active incidents; timeline with search, module, severity and time-range filters, plus a
  confirmation-gated history clear.
- **Honest pause** — pausing freezes the visualisation only; DNS protection and scheduled jobs keep
  running, and the header reports how many events arrived while paused.
- **Measured cost** — aggregating 600 stored events into 8 nodes with filters active takes
  **1.61 ms average / 2.23 ms p95** on the CI JVM (budget 25/40 ms); the event-feed filter pass is
  0.68 ms. The JVM suite fails the build if those budgets regress.
- **Docs** — see [`docs/INTELLIGENCE_MAP.md`](docs/INTELLIGENCE_MAP.md) for architecture, module
  mapping, event catalogue, integration steps and the acceptance-criteria evidence table.

## What's new in 2.7.0

- **Private search** — the in-app browser's address bar now searches with your choice of
  **DuckDuckGo** (default) or **Google**, persisted across restarts. Query terms are percent-encoded
  so a term can never inject extra query parameters, and the existing DNT/GPC headers,
  third-party cookie blocking and tracking-parameter stripping still apply. Choosing Google is
  labelled honestly: Google still receives the query.
- **Persistent scan journal** — scan results survive restarts (previously they were kept in memory
  and capped at 30). Stores the most recent 200 records in a dedicated DataStore file so a finished
  scan cannot trigger a settings-wide recomposition.
- **Exportable threat reports** — one tap writes a plain-text report through the system file picker,
  including real findings, scan source, duration and an explicit statement of the scanner's limits.
- **Quarantine** — isolate a flagged file into app-private storage with a neutralised filename,
  SHA-256 verified on ingest and re-verified on restore. Deleting the original is opt-in, and when
  Android refuses the deletion the app says so instead of pretending it succeeded.
- **Scheduled scanning** — an optional WorkManager job audits installed apps and re-verifies
  quarantine hashes, notifying only when something is flagged.
- **Real scan progress** — per-app progress during a device audit, indeterminate until the target
  count is actually known.
- **Duplicate-id crash fix** — scan ids used `System.currentTimeMillis()` while the history list
  keyed on them; two scans in the same millisecond could crash the list. Ids are now strictly
  increasing.
- **Honest gaps** — private cloud storage, the universal downloader and a full SAF file manager are
  registered as **not implemented** in the capability report rather than shown as working features.

## What's new in 2.6.0

- **Cyber-console UI** — dark navy / electric-blue dashboard inspired by a security operations
  console: animated local protection shield, block-mix donut, attack-vector bars, real activity
  feed and privacy badges that say what the build actually does. No fake global attack map,
  enterprise endpoint counts or compliance certifications.
- **Smoother scrolling** — Home and Live are lazy, keyed lists; tabs collect their own state so an
  idle tab cannot recompose the whole app; repeated rows declare content types; long screens get a
  scroll-to-top control.
- **Idle-safe motion** — the shield animates only while the tunnel is connected and traffic is
  recent, respects system animation settings, and reads animation state inside Canvas drawing
  instead of recomposing the tree every frame.
- **Dashboard honesty tests** — new JVM cases lock counter scope, clamping, event labels, motion
  gating and stable live-event ids. CI now discovers every `@Test` case and requires all of them
  to run and pass.

## What's new in 2.5.0

- **Aether AI decision core** — every block/allow is now an explainable verdict: VERIFIED (curated
  threat list or your own rule), SUSPECTED (heuristics only, never blocked by default) or UNKNOWN.
  Each verdict carries its evidence list and a confidence score, and the autonomy ceiling is yours:
  Manual → verified only → verified + suspected.
- **Live network dashboard** — animated shield fed exclusively by this device's DNS log: totals,
  block rate, per-category breakdown, top apps by UID attribution, resolver latency health and a
  live event feed. An idle tunnel renders an explicit empty state; there is no simulation mode.
- **Universal release gate** — the build now refuses to ship a non-universal APK and prints the
  device matrix (see below).

## What was new in 2.4.0

- **Battery Intelligence tab** — live hardware telemetry (level, temperature, voltage, current,
  charge counters, cycle count where Android exposes them), battery-side power, measured charging
  speed, charging diagnostics with evidence and confidence, real capacity-health measurement when
  the device reports it, and persistent charging history. Every tile states whether its value is
  measured, computed, estimated, unsupported or unavailable.
- **Security tab** — on-device link/phishing scanner, file and APK scanner, installed-app risk
  audit, protection posture and scan history.
- **367k offline rules** bundled in the APK (HaGeZi Multi Pro + TIF, lite list on low-RAM devices),
  so protection works with no network and a fresh install.
- **Status tab + [capability matrix](docs/CAPABILITY_MATRIX.md)** — a machine-checked list of what
  works, what is partial, what is planned and what Android does not allow. CI fails if the matrix
  drifts from the code.

## What AdShield Pro does

- **Device-wide DNS filtering:** Run a local Android VPN service that routes DNS requests to AdShield's on-device filter. Other app traffic continues over the device's normal network connection; AdShield does not send it through a remote VPN server.
- **Choose protection modes:** Standard, Strict, Privacy, and Custom modes control which categories and lists are active. Strict mode can interfere with some apps or sign-ins.
- **Manage blocklists and rules:** Enable built-in ad, tracker, and threat lists; add custom HTTPS list sources; create custom block and allow rules; and review rule conflicts. Filter lists can sync in the background.
- **Use the built-in Privacy Browser:** Filter browser requests, remove common tracking query parameters, block pop-ups, manage third-party cookies, and apply cosmetic hiding rules inside this browser.
- **See activity and statistics:** View DNS totals, allowed and blocked queries, recent local activity, trends, and a live top-app view when Android provides app attribution for a DNS request.
- **Run AI Smart Boost:** Review memory, storage, battery, and temperature signals; safely clean eligible AdShield-owned temporary files; and optimize AdShield's own DNS cache and memory use. The “AI” feature name refers to local, rule-based analysis and recommendations; it does not call a cloud AI service. Android controls memory used by other apps, so Smart Boost does not promise to kill other apps or free their memory.
- **Clean storage with review controls:** Quick, deep, scheduled, and custom scans distinguish safe app-owned temporary files from downloads, duplicate files, and APK installers that require user review or confirmation. Android scoped-storage rules limit which shared files the app can inspect.
- **Use Game Booster and battery modes:** Select games and choose AdShield workload profiles that adjust its cache, background filter sync, and local logging behavior. Battery and thermal tools show device readings and can adapt AdShield's own background work; they do not override Android's system-wide power controls.
- **Check for app updates:** Check GitHub Releases from Home or receive a background update notification when Android runs scheduled work. The app downloads the signed APK and opens Android's installer; users approve installation themselves.
- **Keep settings on the device:** Settings, custom rules, and activity history are stored locally. Allowed DNS lookups are sent to the upstream DNS provider selected in the app, and that provider's privacy terms apply.

## Building and testing

Everything needed is pinned in the repository (Gradle 9.7.1 wrapper, AGP 9.4.1, Kotlin 2.4.21,
`compileSdk = 37`, `minSdk = 29`). Install a JDK 17+ and Android SDK Platform 37, then:

```bash
./gradlew :app:assembleDebug        # debug APK
./gradlew :app:testDebugUnitTest    # JVM unit test suite
./gradlew :app:lintDebug            # Android Lint
```

The JVM suite (`AdShieldEngineTestSuite`, `SecurityCenterTestSuite`, `BatteryIntelligenceTestSuite`,
`AetherCoreTest`, `DashboardPresentationTest`, `PrivateSearchTest`, `ScanJournalTest`
and `CapabilityRegistryTest`) covers DNS
wire-format parsing, UDP/TCP packet framing and checksums, upstream response validation and cache
policy, UDP truncation and TCP segmentation limits, the filter rule engine, the browser blocker, and
in-app update asset selection (release APK over debug/split APKs), the battery power/rate maths and
its no-fabrication rules, the Aether decision table (no verdict without evidence), the live
dashboard model, dashboard counter/motion honesty rules, cross-device feature gates for API 29–37,
and the capability registry's honesty constraints.
See [docs/TESTING.md](docs/TESTING.md) for the full matrix, signing requirements and the on-device
smoke test checklist.

## Download and project links

- [Source code](https://github.com/yawiyuson231-oss/AdShieldPro)
- [Latest releases and APK downloads](https://github.com/yawiyuson231-oss/AdShieldPro/releases)
- [Privacy policy](https://github.com/yawiyuson231-oss/AdShieldPro/blob/main/docs/PRIVACY_POLICY.md)
- [Report a problem](https://github.com/yawiyuson231-oss/AdShieldPro/issues)

AdShield Pro is built with **Kotlin, Jetpack Compose (Material 3), Clean Architecture + MVVM, Room SQLite, DataStore, WorkManager, Hilt, and Android `VpnService`**. DNS filtering can reduce requests to known ad, tracking, and malicious domains, but it cannot guarantee protection from every threat.

---

## Technical architecture

### Layer 1 — Device-Wide Wire-Format DNS Engine (`engine/dns/`)
- **`DnsPacketCodec.kt`**: Full RFC 1035 / RFC 3596 wire-format DNS parser and response synthesizer. Supports IPv4 (`A`) and IPv6 (`AAAA`), `CNAME`, `HTTPS`, `SVCB`, `TXT`, and `MX` queries. Hardened against truncated headers and malicious cyclic compression pointer loops (`0xC0`).
- **`IpPacketFraming.kt`**: Full IPv4 and IPv6 packet parser and response builder supporting both **UDP (Port 53)** and **TCP (Port 53)** with RFC 1071 and RFC 8200 pseudo-header checksums.
- **`UpstreamDnsResolver.kt`**:
  - **DNS-over-HTTPS (DoH, RFC 8484)** via `application/dns-message` wireformat POST with bootstrap DNS resolution.
  - **DNS-over-TLS (DoT, RFC 7858)** over port 853 with TLS SNI and hostname certificate verification.
  - **Protected UDP & TCP Port 53** with automatic TCP upgrade on truncated (`TC=1`) responses.
  - **TTL-Aware LRU Cache** (`1024` entries) rewriting 2-byte Transaction IDs on cache hits for low-latency repeat lookups. When DoH or DoT fails, the resolver returns `SERVFAIL` instead of silently downgrading to plaintext; UDP/TCP is an explicit unencrypted mode.

### Layer 2 — High-Throughput Local VPN Service (`service/AdShieldVpnService.kt`)
- **DNS-Only TUN Routing**: Routes only virtual DNS server addresses (`10.215.173.2/32` and `fd00:ad5:1e1d::2/128`) into the TUN interface. Non-DNS application traffic flows directly over the physical network interface at native OS speed.
- **Loop Prevention**: Protects all upstream DoH, DoT, and UDP/TCP sockets via `VpnService.protect(socket)` and excludes `com.adshield.pro` from the tunnel.
- **Per-App Split Tunneling**: Supports per-app VPN exclusion via `VpnService.Builder.addDisallowedApplication()`.
- **Network Recovery & Boot Restoration**: Monitors Wi-Fi / Mobile Data transitions via `ConnectivityManager.NetworkCallback` and restores protection after device reboot via `BootCompletedReceiver`.

### Layer 3 — Privacy Browser Content & Cosmetic Blocker (`engine/browser/BrowserContentBlocker.kt`)
- Built-in Android `WebView` privacy browser (`ui/screens/BrowserScreen.kt`) with:
  - Subresource ad & tracker request interception (`shouldInterceptRequest`)
  - Cross-site tracking query parameter sanitizer (strips `fbclid`, `gclid`, `utm_*`, `msclkid`, `ttclid`, etc.)
  - Background pop-up blocker (`onCreateWindow`)
  - Cosmetic CSS + DOM element hiding for standard ad slots (`ins.adsbygoogle`, `[id^="div-gpt-ad"]`, `[data-ad-slot]`, etc.) plus custom CSS selectors
  - Per-website allowlist exceptions, third-party cookie blocking, and one-tap cookie/storage wipe.

### Layer 4 — Intelligent Lock-Free Filtering Rule Engine (`engine/filter/FilterRuleEngine.kt`)
- Uses an immutable `FilterSnapshot` inside an `AtomicReference` for lock-free microsecond lookups and zero-restart hot updates.
- Supports **Exact matching**, **$O(k)$ Subdomain suffix walk**, **Wildcard rules (`*`)**, and **Allowlist exceptions (`@@||domain^`)**.
- Strict priority evaluation:
  1. User Custom Allow Rules (`Priority 100`)
  2. User Custom Block Rules (`Priority 90`)
  3. System Safety Allowlist (`Priority 80`)
  4. Active Filter Lists filtered by `ProtectionMode` (`Standard`, `Strict`, `Privacy`, `Custom`) (`Priority 50`)
- Built-in **Duplicate & Conflict Analyzer** (`detectConflictsAndDuplicates`) and interactive **Live Domain Diagnostic Sandbox**.

### Layer 5 — Atomic Filter List Updater (`data/repository/AdShieldRepository.kt` & `worker/FilterUpdateWorker.kt`)
- Pre-configured with reputable filter sources (AdShield Core Bundled Baseline, AdGuard DNS Filter, OISD Small, HaGeZi Multi Light, URLHaus Malware Filter, StevenBlack Unified Hosts) + custom HTTPS filter URLs.
- Validates downloaded lists (rejecting HTML, scripts, or binary payloads), strips duplicates, writes atomically via `.tmp` and `.bak` files, and automatically rolls back on failure.

---

## 2. Technical Limitations & Honest Disclosures

1. **First-Party Multiplexed Streaming Ads:** Official YouTube, Facebook, TikTok, Instagram, and Twitch apps serve video/feed ads from the same first-party content delivery servers and TLS connections as legitimate videos. Blocking those domains via DNS would break video playback.
2. **Hardcoded Encrypted DNS Apps:** Apps that ignore Android system DNS and use proprietary internal DoH/QUIC resolvers bypass local DNS VPNs unless configured to use system DNS.
3. **External Third-Party Browser Cosmetic Filtering:** Due to Android process sandboxing, a standalone non-root app cannot inject cosmetic CSS into external third-party browsers; cosmetic DOM element hiding applies inside AdShield Pro's built-in Privacy Browser.

---

## 3. Project Structure

```text
PROJECT/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── assets/default_blocklist.txt
│       │   └── java/com/adshield/pro/
│       │       ├── AdShieldApplication.kt
│       │       ├── MainActivity.kt
│       │       ├── data/
│       │       │   ├── local/db/ (Entities.kt, AdShieldDao.kt, AdShieldDatabase.kt)
│       │       │   ├── local/preferences/UserPreferencesRepository.kt
│       │       │   └── repository/AdShieldRepository.kt
│       │       ├── di/AppModule.kt
│       │       ├── domain/model/ (ProtectionModels.kt, RuleAndVpnModels.kt, StatsAndBillingModels.kt)
│       │       ├── engine/
│       │       │   ├── browser/BrowserContentBlocker.kt
│       │       │   ├── dns/ (DnsPacketCodec.kt, IpPacketFraming.kt, UpstreamDnsResolver.kt)
│       │       │   └── filter/FilterRuleEngine.kt
│       │       ├── service/ (AdShieldVpnService.kt, BootCompletedReceiver.kt)
│       │       ├── ui/
│       │       │   ├── screens/ (HomeScreen, ActivityScreen, FiltersScreen, RulesScreen, BrowserScreen, SettingsScreen)
│       │       │   ├── theme/Theme.kt
│       │       │   └── viewmodel/AdShieldViewModel.kt
│       │       └── worker/FilterUpdateWorker.kt
│       └── test/java/com/adshield/pro/AdShieldEngineTestSuite.kt
└── docs/
    ├── BUILD_AND_SIGNING_GUIDE.md
    ├── GOOGLE_PLAY_RELEASE_CHECKLIST.md
    └── PRIVACY_POLICY.md
```
