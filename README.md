# AdShield Pro v2.1 — Advanced Non-Root Android DNS Privacy & Filtering

**AdShield Pro v2.1** is an Android privacy application built with **Kotlin, Jetpack Compose (Material 3), Clean Architecture + MVVM, Room SQLite, DataStore, WorkManager, Hilt, and Android `VpnService`**.

It combines DNS filtering and browser-level content blocking to reduce requests to known advertising, tracking, and malicious domains. DNS filtering cannot remove every ad, inspect encrypted HTTPS payloads, or guarantee protection from every threat.

---

## 1. Multi-Layer Protection Architecture

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
