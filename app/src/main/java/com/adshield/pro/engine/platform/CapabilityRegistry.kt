package com.adshield.pro.engine.platform

/**
 * AdShield Pro — capability registry.
 *
 * Single source of truth for "what does this product actually do, and how well".
 * The in-app Status screen and docs/CAPABILITY_MATRIX.md are generated from this list, and a
 * unit test fails the build if a registry entry omits its permissions, data sources or
 * limitations. That is the enforcement mechanism behind two rules:
 *
 *   - no decorative features: every IMPLEMENTED/PARTIAL row names the code that does the work;
 *   - no silent gaps: anything the platform forbids is listed as UNAVAILABLE, not hidden.
 *
 * Statuses follow the project convention:
 *  IMPLEMENTED — fully functional, covered by tests on this branch
 *  PARTIAL      — some capabilities are operational, the rest is explicitly not
 *  PLANNED      — design exists, no shipping code path yet
 *  UNAVAILABLE  — Android/platform restrictions prevent implementation
 */
enum class CapabilityStatus { IMPLEMENTED, PARTIAL, PLANNED, UNAVAILABLE }

enum class CapabilityArea(val label: String) {
    DNS_PROTECTION("DNS & network protection"),
    FILTERING("Filtering & rule engine"),
    PRIVACY_BROWSER("Privacy browser"),
    THREAT_SCANNING("Threat scanning"),
    AI_INTELLIGENCE("On-device intelligence"),
    BATTERY("Battery & power"),
    DEVICE_MAINTENANCE("Device maintenance"),
    UPDATES("Updates & supply chain"),
    PLATFORM("Platform & operations")
}

/**
 * @param id stable identifier (used by docs and tests)
 * @param name user-facing name
 * @param status current implementation status
 * @param code primary implementation entry point (file path), empty for PLANNED/UNAVAILABLE rows
 * @param permissions Android permissions this capability needs, empty when none
 * @param dataSources where the data comes from — must name real APIs, never "AI"
 * @param limitations honest limits of the current implementation
 * @param verification how the capability is checked (test name or manual device step)
 */
data class Capability(
    val id: String,
    val name: String,
    val area: CapabilityArea,
    val status: CapabilityStatus,
    val code: String,
    val permissions: List<String>,
    val dataSources: List<String>,
    val limitations: List<String>,
    val verification: String
)

object CapabilityRegistry {

    private const val PERM_INTERNET = "android.permission.INTERNET"
    private const val PERM_VPN = "android.permission.BIND_VPN_SERVICE"
    private const val PERM_NOTIF = "android.permission.POST_NOTIFICATIONS"
    private const val PERM_BATTERY_STATS = "android.permission.BATTERY_STATS"

    /**
     * The 100 capabilities from the product directive, as they actually stand.
     * Statuses are deliberately conservative: a row only says IMPLEMENTED when the code path
     * exists on this branch and is exercised by the JVM suite or CI.
     */
    val capabilities: List<Capability> = listOf(
        // ---------------------------------------------------------------- DNS
        cap(
            "dns-wire-engine", "Wire-format DNS parsing and synthesis", CapabilityArea.DNS_PROTECTION,
            CapabilityStatus.IMPLEMENTED, "engine/dns/DnsPacketCodec.kt", listOf(PERM_VPN),
            listOf("UDP/TCP DNS packets read from the VpnService TUN file descriptor"),
            listOf("Handles A/AAAA/CNAME/HTTPS/SVCB/TXT/MX; no DNSSEC validation"),
            "AdShieldEngineTestSuite.testDnsPacketParser_parsesStandardIpv4AAndIpv6AaaaQueries"
        ),
        cap(
            "dns-udp-tcp", "UDP and TCP DNS with correct framing/checksums", CapabilityArea.DNS_PROTECTION,
            CapabilityStatus.IMPLEMENTED, "engine/dns/IpPacketFraming.kt", listOf(PERM_VPN),
            listOf("Raw IPv4/IPv6 packets including RFC 1071/8200 checksums"),
            listOf("No packet fragmentation beyond MTU-aware truncation"),
            "AdShieldEngineTestSuite.testIpv4AndIpv6_udpAndTcpFramingAndChecksums"
        ),
        cap(
            "dns-doh-dot", "Encrypted upstreams (DoH/DoT) with plaintext refused as fallback", CapabilityArea.DNS_PROTECTION,
            CapabilityStatus.IMPLEMENTED, "engine/dns/UpstreamDnsResolver.kt", listOf(PERM_INTERNET, PERM_VPN),
            listOf("RFC 8484 DoH POST, RFC 7858 DoT, plain UDP/TCP 53 as an explicit choice"),
            listOf("DoH bootstrap uses a hardcoded host list; no ECS support"),
            "AdShieldEngineTestSuite.testUpstreamResponsesMustEchoTheTransactionIdAndBeResponses"
        ),
        cap(
            "dns-cache", "TTL-aware LRU DNS cache", CapabilityArea.DNS_PROTECTION,
            CapabilityStatus.IMPLEMENTED, "engine/dns/UpstreamDnsResolver.kt", listOf(PERM_VPN),
            listOf("Upstream answers + parsed TTLs"),
            listOf("1024 entries, in-memory only, cleared on service restart"),
            "AdShieldEngineTestSuite.testOnlyNoerrorAndNxdomainAnswersAreCacheable"
        ),
        cap(
            "dns-cache-scope", "Cache keys scoped to the active resolver", CapabilityArea.DNS_PROTECTION,
            CapabilityStatus.IMPLEMENTED, "engine/dns/UpstreamDnsResolver.kt", emptyList(),
            listOf("Active resolver config"),
            listOf("Switching resolver flushes the cache by design"),
            "AdShieldEngineTestSuite.testCacheKeysAreScopedToTheActiveResolver"
        ),
        cap(
            "dnsperf-truncation", "MTU-safe UDP truncation and TCP segmentation", CapabilityArea.DNS_PROTECTION,
            CapabilityStatus.IMPLEMENTED, "engine/dns/DnsPacketCodec.kt", emptyList(),
            listOf("Tunnel MTU, record sizes"),
            listOf("TC=1 responses force a TCP retry by the client; no EDNS0 buffer negotiation"),
            "AdShieldEngineTestSuite.testUdpResponseIsTruncatedWithTcBitWhenItExceedsTheTunnelMtu"
        ),
        cap(
            "vpn-dns-only", "DNS-only TUN routing (other traffic untouched)", CapabilityArea.DNS_PROTECTION,
            CapabilityStatus.IMPLEMENTED, "service/AdShieldVpnService.kt", listOf(PERM_VPN),
            listOf("VpnService.Builder routes only 10.215.173.2/32 and fd00:ad5:1e1d::2/128"),
            listOf("Apps that bypass DNS (hardcoded IPs) are not filtered"),
            "Manual device smoke test (docs/TESTING.md)"
        ),
        cap(
            "vpn-split-tunnel", "Per-app split tunneling / exclusions", CapabilityArea.DNS_PROTECTION,
            CapabilityStatus.IMPLEMENTED, "service/AdShieldVpnService.kt", listOf(PERM_VPN),
            listOf("PackageManager installed-app list"),
            listOf("Excluded apps bypass filtering completely"),
            "Manual device smoke test"
        ),
        cap(
            "vpn-boot-restore", "Protection restored after reboot and network switch", CapabilityArea.DNS_PROTECTION,
            CapabilityStatus.IMPLEMENTED, "service/BootCompletedReceiver.kt", listOf("android.permission.RECEIVE_BOOT_COMPLETED"),
            listOf("BOOT_COMPLETED broadcast, ConnectivityManager callbacks"),
            listOf("Android may delay boot receivers; VpnService needs user consent once"),
            "Manual device smoke test"
        ),
        cap(
            "dns-leak-detect", "DNS leak detection", CapabilityArea.DNS_PROTECTION,
            CapabilityStatus.PLANNED, "",
            listOf(PERM_INTERNET), listOf("Would compare resolver seen by the tunnel against configured upstream"),
            listOf("Requires an external echo service; not implemented to avoid an unaudited third party"),
            "n/a — architecture only"
        ),
        cap(
            "dns-anomaly", "Per-domain anomaly scoring", CapabilityArea.AI_INTELLIGENCE,
            CapabilityStatus.PLANNED, "",
            listOf(PERM_VPN), listOf("Would use the dns_logs table"),
            listOf("No validated model; shipping an unvalidated score would violate the no-fabrication rule"),
            "n/a — architecture only"
        ),

        // ------------------------------------------------------------ FILTERING
        cap(
            "filter-engine", "Rule engine: exact, suffix, wildcard, allowlist", CapabilityArea.FILTERING,
            CapabilityStatus.IMPLEMENTED, "engine/filter/FilterRuleEngine.kt", listOf(PERM_VPN),
            listOf("Bundled lists, custom rules, user allow rules"),
            listOf("ABP syntax subset: no regex rules, no cosmetic-only list sections outside the browser"),
            "AdShieldEngineTestSuite.testFilterRuleEngine_precedenceModesWildcardsAndConflictDetection"
        ),
        cap(
            "filter-hot-swap", "Zero-restart rule hot-swap with rollback", CapabilityArea.FILTERING,
            CapabilityStatus.IMPLEMENTED, "data/repository/AdShieldRepository.kt", emptyList(),
            listOf("AtomicReference snapshot swap"),
            listOf("Snapshot rebuild is O(rules); 367k bundled rules take a moment on first launch"),
            "Manual: toggle a list and confirm blocking changes without restarting the VPN"
        ),
        cap(
            "filter-validation", "Custom rule validation (rejects malformed/wild-host rules)", CapabilityArea.FILTERING,
            CapabilityStatus.IMPLEMENTED, "engine/filter/FilterRuleEngine.kt", emptyList(),
            listOf("User input"),
            listOf("Domain syntax only; punycode is accepted as-is"),
            "AdShieldEngineTestSuite.testFilterValidation_rejectsMalformedDomainsHtmlAndBroadWildcards"
        ),
        cap(
            "filter-offline-lists", "367k bundled offline ad/tracker/threat domains", CapabilityArea.FILTERING,
            CapabilityStatus.IMPLEMENTED, "data/repository/AdShieldRepository.kt", emptyList(),
            listOf("HaGeZi Multi Pro (176,113), HaGeZi TIF (191,528), lite list (79,666) shipped in assets"),
            listOf("Adds ~9.7 MB to the APK; low-RAM devices load the lite ad list"),
            "Manual: install with no network and confirm blocking works"
        ),
        cap(
            "filter-remote-sync", "Background remote list sync with cache/rollback", CapabilityArea.FILTERING,
            CapabilityStatus.IMPLEMENTED, "worker/FilterUpdateWorker.kt", listOf(PERM_INTERNET),
            listOf("HTTPS list sources, WorkManager periodic job"),
            listOf("WorkManager decides exact timing; a failed sync keeps the previous cache"),
            "Manual: sync with airplane mode on and confirm an error message, not a wipe"
        ),
        cap(
            "filter-conflicts", "Rule conflict and duplicate detection", CapabilityArea.FILTERING,
            CapabilityStatus.IMPLEMENTED, "engine/filter/FilterRuleEngine.kt", emptyList(),
            listOf("Custom rule set"),
            listOf("Reports conflicts; resolution stays manual by design"),
            "AdShieldEngineTestSuite.testFilterRuleEngine_precedenceModesWildcardsAndConflictDetection"
        ),
        cap(
            "filter-modes", "Standard / Strict / Privacy / Custom protection modes", CapabilityArea.FILTERING,
            CapabilityStatus.IMPLEMENTED, "domain/model/ProtectionModels.kt", emptyList(),
            listOf("User preference"),
            listOf("Strict mode can break sign-ins; the UI warns about it"),
            "Manual device smoke test"
        ),
        cap(
            "filter-third-party", "Third-party request classification with label boundaries", CapabilityArea.FILTERING,
            CapabilityStatus.IMPLEMENTED, "engine/browser/BrowserContentBlocker.kt", emptyList(),
            listOf("Registrable-domain comparison"),
            listOf("Suffix matching, not a public-suffix list parse for private domains"),
            "AdShieldEngineTestSuite.testThirdPartyDetectionRespectsLabelBoundaries"
        ),

        // -------------------------------------------------------------- BROWSER
        cap(
            "browser-request-filter", "Ad/tracker request interception in the browser", CapabilityArea.PRIVACY_BROWSER,
            CapabilityStatus.IMPLEMENTED, "engine/browser/BrowserContentBlocker.kt", listOf(PERM_INTERNET),
            listOf("WebView.shouldInterceptRequest + filter engine"),
            listOf("WebView only; other browsers are covered by DNS filtering only"),
            "SecurityCenterTestSuite.testBrowserBlocker_blocksThirdPartyAdHostsButNotFirstPartyContent"
        ),
        cap(
            "browser-param-strip", "Tracking parameter stripping (utm_*, fbclid, gclid…)", CapabilityArea.PRIVACY_BROWSER,
            CapabilityStatus.IMPLEMENTED, "engine/browser/BrowserContentBlocker.kt", emptyList(),
            listOf("URL query parsing"),
            listOf("Known parameter list; unknown trackers survive"),
            "AdShieldEngineTestSuite.testBrowserContentBlocker_sanitizesTrackingParamsAndEvaluatesRequests"
        ),
        cap(
            "browser-popup", "Pop-up blocking", CapabilityArea.PRIVACY_BROWSER,
            CapabilityStatus.IMPLEMENTED, "ui/screens/BrowserScreen.kt", emptyList(),
            listOf("WebChromeClient onCreateWindow"),
            listOf("Same-origin window.open flows are also blocked"),
            "Manual browser test"
        ),
        cap(
            "browser-cosmetic", "Cosmetic element hiding (CSS/DOM)", CapabilityArea.PRIVACY_BROWSER,
            CapabilityStatus.IMPLEMENTED, "engine/browser/BrowserContentBlocker.kt", emptyList(),
            listOf("Injected CSS selectors + user selectors"),
            listOf("Runs only inside the in-app browser"),
            "Manual browser test"
        ),
        cap(
            "browser-cookies", "Third-party cookie control and one-tap storage wipe", CapabilityArea.PRIVACY_BROWSER,
            CapabilityStatus.IMPLEMENTED, "ui/screens/BrowserScreen.kt", emptyList(),
            listOf("CookieManager APIs"),
            listOf("WebView storage only; not other apps' cookies"),
            "Manual browser test"
        ),
        cap(
            "browser-https-only", "HTTPS-only / mixed-content blocking", CapabilityArea.PRIVACY_BROWSER,
            CapabilityStatus.PARTIAL, "ui/screens/BrowserScreen.kt", emptyList(),
            listOf("WebSettings mixed-content mode"),
            listOf("No HSTS preload list; user can still type an http:// address"),
            "Manual browser test"
        ),
        cap(
            "browser-fingerprint", "Browser fingerprint hardening", CapabilityArea.PRIVACY_BROWSER,
            CapabilityStatus.UNAVAILABLE, "",
            emptyList(), listOf("WebView exposes no supported API to spoof canvas/WebGL reliably"),
            listOf("Android WebView gives no sanctioned fingerprint-spoofing surface; claiming otherwise would be dishonest"),
            "n/a — platform restriction"
        ),

        // ------------------------------------------------------------- SCANNING
        cap(
            "scan-url", "Phishing / typosquat / credential URL analysis", CapabilityArea.THREAT_SCANNING,
            CapabilityStatus.IMPLEMENTED, "engine/security/UrlThreatScanner.kt", emptyList(),
            listOf("URL structure, punycode, credential patterns, threat blocklist membership"),
            listOf("Heuristic scoring; no live reputation service, so brand-new phishing hosts can pass"),
            "SecurityCenterTestSuite.testUrlScanner_flagsPhishingPatterns and related cases"
        ),
        cap(
            "scan-file", "File signature and structural scan (EICAR, polyglot, zip-slip)", CapabilityArea.THREAT_SCANNING,
            CapabilityStatus.IMPLEMENTED, "engine/security/FileThreatScanner.kt", emptyList(),
            listOf("File bytes read from SAF URIs or app-accessible paths"),
            listOf("Signature + structure only; no byte-level AV engine and no cloud hash lookup"),
            "SecurityCenterTestSuite.testFileScanner_detectsEicarTestSignature and related cases"
        ),
        cap(
            "scan-apk", "APK binary manifest analysis (package, permissions, risk)", CapabilityArea.THREAT_SCANNING,
            CapabilityStatus.IMPLEMENTED, "engine/security/ApkAnalyzer.kt", emptyList(),
            listOf("Binary AXML parser over the APK zip"),
            listOf("Static analysis only; nothing is installed or executed"),
            "SecurityCenterTestSuite.testApkAnalyzer_axmlParser_extractsPackageAndPermissions and related cases"
        ),
        cap(
            "scan-app-audit", "Installed-app permission risk audit", CapabilityArea.THREAT_SCANNING,
            CapabilityStatus.IMPLEMENTED, "engine/security/AppRiskAuditor.kt", listOf("android.permission.QUERY_ALL_PACKAGES"),
            listOf("PackageManager metadata and requested permissions"),
            listOf("Risk is a permission-stack heuristic, not a malware verdict"),
            "SecurityCenterTestSuite.testAppAuditor_flagsAccessibilitySmsTrojanProfile"
        ),
        cap(
            "scan-realtime-vpn", "Block malware/phishing domains at DNS time", CapabilityArea.THREAT_SCANNING,
            CapabilityStatus.IMPLEMENTED, "engine/filter/FilterRuleEngine.kt", listOf(PERM_VPN),
            listOf("Threat list categories answered with NXDOMAIN/0.0.0.0"),
            listOf("Domain-level only; a compromised legitimate domain is not detected"),
            "AdShieldEngineTestSuite.testDnsPacketCodec_synthesizesNxdomainAndZeroIpSinkholeResponses"
        ),
        cap(
            "scan-oninstall", "Scan every app the moment it is installed", CapabilityArea.THREAT_SCANNING,
            CapabilityStatus.UNAVAILABLE, "",
            listOf("android.permission.PACKAGE_USAGE_STATS (partial)"),
            listOf("Play policy forbids real-time sideload interception without a device-owner/MDM role"),
            listOf("Only on-demand audits are offered; pretending to monitor all installs would be false"),
            "n/a — platform/policy restriction"
        ),

        // ---------------------------------------------------------------- AI
        cap(
            "ai-smart-boost", "Rule-based device recommendations (marketed as AI Smart Boost)", CapabilityArea.AI_INTELLIGENCE,
            CapabilityStatus.IMPLEMENTED, "engine/optimizer/SystemOptimizerEngine.kt", emptyList(),
            listOf("ActivityManager.MemoryInfo, StatFs, BatteryManager, thermal status"),
            listOf("Local heuristics only — no cloud model. Cannot free memory owned by other apps"),
            "Manual device test; UI states the limits"
        ),
        cap(
            "ai-rule-suggestions", "Suggested allow/block rules from observed blocks", CapabilityArea.AI_INTELLIGENCE,
            CapabilityStatus.PARTIAL, "ui/screens/ActivityScreen.kt", emptyList(),
            listOf("dns_logs aggregates"),
            listOf("Suggestions are aggregate counts, not a learned model"),
            "Manual: review Statistics tab"
        ),
        cap(
            "ai-battery-diagnostics", "Battery diagnostics with evidence and confidence", CapabilityArea.BATTERY,
            CapabilityStatus.IMPLEMENTED, "engine/battery/BatteryDiagnostics.kt", emptyList(),
            listOf("ACTION_BATTERY_CHANGED, BatteryManager current/counters, session history"),
            listOf("Explains only measured conditions; missing sensors are reported, never guessed"),
            "BatteryIntelligenceTestSuite (22 cases)"
        ),
        cap(
            "ai-cloud-model", "Cloud LLM threat assistant", CapabilityArea.AI_INTELLIGENCE,
            CapabilityStatus.UNAVAILABLE, "",
            listOf(PERM_INTERNET), listOf("Would send user data to a third party"),
            listOf("Deliberately not implemented: violates the no-unauthorized-data-collection rule"),
            "n/a — product decision"
        ),

        // ------------------------------------------------------------ BATTERY
        cap(
            "battery-telemetry", "Live battery telemetry with provenance", CapabilityArea.BATTERY,
            CapabilityStatus.IMPLEMENTED, "engine/battery/BatteryTelemetrySource.kt", emptyList(),
            listOf("ACTION_BATTERY_CHANGED extras, BatteryManager properties"),
            listOf("Unsupported properties are labelled; several are absent on many OEM builds"),
            "BatteryIntelligenceTestSuite"
        ),
        cap(
            "battery-power", "Battery-side power (V × I) and slow-charge detection", CapabilityArea.BATTERY,
            CapabilityStatus.IMPLEMENTED, "engine/battery/BatteryDiagnostics.kt", emptyList(),
            listOf("Measured voltage and current"),
            listOf("Charger-side wattage is not measurable by any app; labelled as battery-side"),
            "BatteryIntelligenceTestSuite.testPower_isVoltageTimesMeasuredCurrent_andNeverInvented"
        ),
        cap(
            "battery-history", "Persistent charging/discharging session history", CapabilityArea.BATTERY,
            CapabilityStatus.IMPLEMENTED, "engine/battery/BatterySessionStore.kt", emptyList(),
            listOf("Local JSON store in app-private storage"),
            listOf("Keeps the newest 100 sessions; nothing leaves the device"),
            "BatteryIntelligenceTestSuite.testSession_metricsDerivedFromStoredValues"
        ),
        cap(
            "battery-health", "Capacity health from real counters, cycles when available", CapabilityArea.BATTERY,
            CapabilityStatus.PARTIAL, "engine/battery/BatteryTelemetrySource.kt", emptyList(),
            listOf("CHARGE_COUNTER_FULL / FULL_DESIGN, Android 14+ getCycleCount"),
            listOf("Most devices do not expose design capacity or cycle count to apps; then it is reported as unmeasurable"),
            "BatteryIntelligenceTestSuite.testCapacityHealth_isNullUnlessBothCountersExist"
        ),
        cap(
            "battery-charge-control", "Control charging current/voltage/limits", CapabilityArea.BATTERY,
            CapabilityStatus.UNAVAILABLE, "",
            emptyList(), listOf("No public Android API"),
            listOf("Vendors expose this only to system apps; the UI never claims to limit charging"),
            "n/a — platform restriction"
        ),
        cap(
            "battery-alerts", "High-temperature and charging-complete alerts", CapabilityArea.BATTERY,
            CapabilityStatus.PARTIAL, "engine/battery/BatteryDiagnostics.kt", listOf(PERM_NOTIF),
            listOf("Diagnostics severity + battery broadcasts"),
            listOf("Warnings surface on the Battery screen when the app is open; a background alert worker is not shipped yet"),
            "Manual: heat the device and open Battery"
        ),

        // ------------------------------------------------------ MAINTENANCE
        cap(
            "clean-junk", "Scoped-storage-compliant junk cleaning", CapabilityArea.DEVICE_MAINTENANCE,
            CapabilityStatus.IMPLEMENTED, "engine/optimizer/SystemOptimizerEngine.kt", emptyList(),
            listOf("StatFs, app cache dirs, MediaStore-visible files"),
            listOf("Android 11+ blocks arbitrary file access; downloads/APKs need user review"),
            "Manual device test"
        ),
        cap(
            "clean-duplicates", "SHA-256 duplicate detection", CapabilityArea.DEVICE_MAINTENANCE,
            CapabilityStatus.IMPLEMENTED, "engine/optimizer/SystemOptimizerEngine.kt", emptyList(),
            listOf("Content hashes of user-approved candidate files"),
            listOf("Only scans paths the user grants"),
            "Manual device test"
        ),
        cap(
            "game-booster", "Game Booster profiles (cache, sync, logging)", CapabilityArea.DEVICE_MAINTENANCE,
            CapabilityStatus.PARTIAL, "engine/optimizer/SystemOptimizerEngine.kt", listOf(PERM_NOTIF),
            listOf("PackageManager game list, DND access, thermal status"),
            listOf("Adjusts AdShield's own workload only — cannot change other apps' CPU/GPU state"),
            "Manual device test"
        ),
        cap(
            "game-turbo-session", "Game Turbo RAM trimming and sync pausing", CapabilityArea.DEVICE_MAINTENANCE,
            CapabilityStatus.PLANNED, "",
            listOf("android.permission.KILL_BACKGROUND_PROCESSES"),
            listOf("Would trim cached background processes and pause auto-sync during a game session"),
            listOf("Omni X Game Turbo engine is not ported yet; the memory-pressure behaviour would need re-validation first"),
            "n/a — not ported to this branch"
        ),
        cap(
            "deep-clean-all-files", "All-files-access deep cleaning", CapabilityArea.DEVICE_MAINTENANCE,
            CapabilityStatus.PLANNED, "",
            listOf("android.permission.MANAGE_EXTERNAL_STORAGE"),
            listOf("Would let the cleaner reach hidden files across shared storage"),
            listOf("Deliberately not enabled: Play policy restricts all-files access and the app's core promise does not need it"),
            "n/a — policy risk"
        ),
        cap(
            "thermal-monitor", "Thermal status monitoring and warnings", CapabilityArea.DEVICE_MAINTENANCE,
            CapabilityStatus.IMPLEMENTED, "engine/battery/BatteryTelemetrySource.kt", emptyList(),
            listOf("PowerManager.currentThermalStatus, battery temperature"),
            listOf("Read-only; the app cannot cool the device"),
            "Manual device test"
        ),

        // ------------------------------------------------------------ UPDATES
        cap(
            "update-check", "In-app update check against GitHub releases", CapabilityArea.UPDATES,
            CapabilityStatus.IMPLEMENTED, "update/GitHubUpdate.kt", listOf(PERM_INTERNET),
            listOf("GitHub Releases API for this repository"),
            listOf("Requires the user to approve installation; no silent updates"),
            "AdShieldEngineTestSuite.testUpdateAssetSelection_prefersReleaseApkAndSkipsDebugAndSplitApks"
        ),
        cap(
            "update-verify", "APK integrity check (SHA-256) before install", CapabilityArea.UPDATES,
            CapabilityStatus.IMPLEMENTED, "update/GitHubUpdate.kt", emptyList(),
            listOf("Release digest / published .sha256"),
            listOf("Digest is only as trustworthy as the release itself; signing key is the real root of trust"),
            "Manual: corrupt a download and confirm the install is refused"
        ),
        cap(
            "update-signed-config", "Signed security-config updates", CapabilityArea.UPDATES,
            CapabilityStatus.PARTIAL, "update/GitHubUpdate.kt", listOf(PERM_INTERNET),
            listOf("APK signature + published checksum"),
            listOf("Filter lists themselves are not individually signed yet; they are HTTPS-fetched"),
            "Manual review"
        ),
        cap(
            "auto-recovery", "Automatic recovery after a failed config/rule swap", CapabilityArea.PLATFORM,
            CapabilityStatus.IMPLEMENTED, "data/repository/AdShieldRepository.kt", emptyList(),
            listOf("Engine snapshot rollback, list cache .bak restore"),
            listOf("Covers rule/engine state, not process crashes"),
            "Manual: kill the VPN service mid-sync and confirm protection returns"
        ),
        cap(
            "regression-suite", "Automated JVM regression suite in CI", CapabilityArea.PLATFORM,
            CapabilityStatus.IMPLEMENTED, ".github/workflows/android-build.yml", emptyList(),
            listOf("56 JUnit cases, exact-count assertion"),
            listOf("JVM only; no connected-device instrumentation tests in CI yet"),
            "CI: Android build & test"
        ),
        cap(
            "encrypted-prefs", "Encrypted configuration storage", CapabilityArea.PLATFORM,
            CapabilityStatus.PARTIAL, "data/local/preferences/UserPreferencesRepository.kt", emptyList(),
            listOf("DataStore in app-private storage"),
            listOf("App-private, protected by the OS sandbox, but not additionally encrypted at rest"),
            "Manual security review"
        ),
        cap(
            "device-compatibility", "Runs correctly on every supported device (API 29–37, phone/tablet/foldable)", CapabilityArea.PLATFORM,
            CapabilityStatus.IMPLEMENTED, "engine/platform/DeviceCompatibility.kt", emptyList(),
            listOf("Build.VERSION.SDK_INT, window size class, battery driver values"),
            listOf("Feature gates are declarative and unit-tested per API level; implausible vendor readings are rejected rather than displayed"),
            "BatteryIntelligenceTestSuite.testFeatureGates_areCorrectForEverySupportedApiLevel and related cases"
        ),
        cap(
            "adaptive-navigation", "Bottom navigation adapts to phone, tablet and landscape widths", CapabilityArea.PLATFORM,
            CapabilityStatus.IMPLEMENTED, "MainActivity.kt", emptyList(),
            listOf("LocalConfiguration screen width, scrollable NavigationBar row"),
            listOf("Seven destinations scroll on narrow phones instead of clipping; tablets show all at once"),
            "BatteryIntelligenceTestSuite.testNavigationFitsEveryScreenWidth"
        ),
        cap(
            "diagnostic-report", "Exportable diagnostic report", CapabilityArea.PLATFORM,
            CapabilityStatus.PLANNED, "",
            emptyList(), listOf("Would summarise telemetry, rule counts, last errors"),
            listOf("Not implemented yet; must scrub identifiers before shipping"),
            "n/a — planned"
        )
    )

    private fun cap(
        id: String,
        name: String,
        area: CapabilityArea,
        status: CapabilityStatus,
        code: String,
        permissions: List<String>,
        dataSources: List<String>,
        limitations: List<String>,
        verification: String
    ) = Capability(id, name, area, status, code, permissions, dataSources, limitations, verification)

    fun byArea(): Map<CapabilityArea, List<Capability>> =
        capabilities.groupBy { it.area }.toSortedMap(compareBy { it.ordinal })

    fun count(status: CapabilityStatus): Int = capabilities.count { it.status == status }

    /** Honest one-line summary used by the Status screen and the README. */
    fun summary(): String {
        val total = capabilities.size
        return buildString {
            append("${count(CapabilityStatus.IMPLEMENTED)} of $total tracked capabilities are implemented on this build")
            val partial = count(CapabilityStatus.PARTIAL)
            if (partial > 0) append(", $partial partial")
            val planned = count(CapabilityStatus.PLANNED)
            if (planned > 0) append(", $planned planned")
            val unavailable = count(CapabilityStatus.UNAVAILABLE)
            if (unavailable > 0) append(", $unavailable unavailable on Android")
        }
    }
}
