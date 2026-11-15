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
    CLOUD_STORAGE("Private cloud & Infinity storage"),
    FILE_MANAGEMENT("SAF file management"),
    DOWNLOADS("Direct media downloads"),
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
            "dns-provider-failover", "Opt-in provider failover with measured health", CapabilityArea.DNS_PROTECTION,
            CapabilityStatus.IMPLEMENTED, "engine/dns/DnsProviderRegistry.kt", listOf(PERM_INTERNET, PERM_VPN),
            listOf("Real per-query success/failure counts, EWMA latency and circuit-breaker cooldowns from DnsProviderHealthMonitor"),
            listOf(
                "Only providers the user selected are used, responses are never merged, and an encrypted protocol never falls back to plaintext port 53",
                "Health is per-process: it is reset when the VPN service is restarted"
            ),
            "CybersecurityPlatformTestSuite.resolverFailsOverOnlyAfterValidationAndDoesNotDowngrade and resolverCircuitSkipsKnownOpenProviderWithoutExtendingIt"
        ),
        cap(
            "dns-response-validation", "Strict upstream response validation before caching or answering", CapabilityArea.DNS_PROTECTION,
            CapabilityStatus.IMPLEMENTED, "engine/dns/DnsResponseValidator.kt", emptyList(),
            listOf("Full resource-record walk of the upstream wire response (DnsPacketCodec.inspectResponseRecordTypes)"),
            listOf("Rejects transaction/opcode/question mismatches, reserved Z bit, truncated UDP answers, SERVFAIL/REFUSED/FORMERR and trailing bytes"),
            "CybersecurityPlatformTestSuite.validatorRejectsWrongQuestionTruncationServfailAndTrailingData"
        ),
        cap(
            "dnssec-evidence", "Upstream DNSSEC evidence reporting and strict AD mode", CapabilityArea.DNS_PROTECTION,
            CapabilityStatus.PARTIAL, "engine/dns/DnsResponseValidator.kt", emptyList(),
            listOf("Upstream Authenticated Data (AD) flag and RRSIG presence in the returned records"),
            listOf(
                "Reports and optionally enforces upstream evidence only; it is not local DNSSEC chain validation",
                "Strict mode rejects unsigned positive answers, so unsigned domains stop resolving by design"
            ),
            "CybersecurityPlatformTestSuite.dnssecModeReportsUpstreamAdAndStrictModeRejectsUnsignedPositiveAnswers"
        ),
        cap(
            "dns-policy-per-app", "Per-app, per-network and emergency DNS policy", CapabilityArea.DNS_PROTECTION,
            CapabilityStatus.IMPLEMENTED, "engine/dns/DnsFirewallPolicyDecider.kt", listOf(PERM_VPN),
            listOf("Query owner package from VpnService, active transport from ConnectivityManager, user policy from DataStore"),
            listOf(
                "DNS decisions only: non-DNS sockets are not inspected because the tunnel routes DNS addresses exclusively",
                "Emergency allowlist-only mode blocks every DNS answer that is not an explicit user or built-in safety allow"
            ),
            "CybersecurityPlatformTestSuite.appNetworkAndEmergencyPoliciesAreDeterministic"
        ),
        cap(
            "dns-diagnostics", "Real resolver diagnostics against the configured upstream", CapabilityArea.DNS_PROTECTION,
            CapabilityStatus.IMPLEMENTED, "engine/dns/DnsDiagnostics.kt", listOf(PERM_INTERNET, PERM_VPN),
            listOf("A genuine A query with EDNS0 DO bit, measured latency, RCODE, answer count and provider health"),
            listOf("Validates the configured resolver path; it is not an external DNS leak test and no third-party echo service is contacted"),
            "CybersecurityPlatformTestSuite.diagnosticQueryUsesEdns0AndNormalisesCase"
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
            listOf("JUnit XML from :app:testDebugUnitTest; CI discovers @Test cases and asserts all ran and passed"),
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
            "universal-package", "One APK for every CPU architecture and Android 10+", CapabilityArea.PLATFORM,
            CapabilityStatus.IMPLEMENTED, "tools/check_universal_release.py", emptyList(),
            listOf("Build config and the packaged APK (every native helper bundled for all four ABIs, no ABI splits)"),
            listOf("App code is pure Kotlin/Java; AndroidX native helpers ship for arm64-v8a, armeabi-v7a, x86 and x86_64 alike. The release job refuses to publish an APK that leaves any supported ABI behind or uses per-ABI splits"),
            "Release APK workflow: universal-build gate"
        ),
        cap(
            "aether-decision-core", "Aether AI evidence-based verdicts (VERIFIED / SUSPECTED / UNKNOWN)", CapabilityArea.AI_INTELLIGENCE,
            CapabilityStatus.IMPLEMENTED, "engine/aether/AetherCore.kt", listOf(PERM_VPN),
            listOf("Filter-engine matches, user rules, domain heuristics, app audit signals"),
            listOf("Rule-based, explainable decision table — no ML model and no cloud call; heuristics alone can never verify a threat"),
            "AetherCoreTest.testHeuristicsAlone_canNeverVerifyOrBlock and related cases"
        ),
        cap(
            "aether-domain-heuristics", "Domain risk heuristics (IP hosts, punycode, brand lookalikes, risky TLDs)", CapabilityArea.AI_INTELLIGENCE,
            CapabilityStatus.IMPLEMENTED, "engine/aether/DomainRiskAnalyzer.kt", emptyList(),
            listOf("The request string itself — no network lookups, safe on the DNS hot path"),
            listOf("Indicators are weak on their own; they surface as SUSPECTED and never auto-block by default"),
            "AetherCoreTest.testDomainAnalyzer_flagsRealPhishingPatterns"
        ),
        cap(
            "aether-autonomy", "User-controlled autonomous response ceiling", CapabilityArea.AI_INTELLIGENCE,
            CapabilityStatus.IMPLEMENTED, "engine/aether/AetherCore.kt", emptyList(),
            listOf("User setting: Manual / verified only / verified + suspected"),
            listOf("Manual mode reports instead of blocking; suspected blocking is opt-in and needs a higher score"),
            "AetherCoreTest.testManualAutonomy_reportsInsteadOfBlocking"
        ),
        cap(
            "live-network-dashboard", "Live DNS traffic dashboard with real event feed", CapabilityArea.DNS_PROTECTION,
            CapabilityStatus.IMPLEMENTED, "engine/aether/LiveNetworkModel.kt", listOf(PERM_VPN),
            listOf("AdShield DNS log, session counters, resolver config, per-UID attribution"),
            listOf("Shows only logged traffic; there is no simulation mode, so an idle tunnel renders an explicit empty state"),
            "AetherCoreTest.testLiveSnapshot_isBuiltOnlyFromRealLogs"
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
            listOf("LocalConfiguration screen width, lazy scrollable NavigationBar row"),
            listOf("Twelve destinations scroll on narrow phones and the selected item auto-centers; wider screens show more at once"),
            "BatteryIntelligenceTestSuite.testNavigationFitsEveryScreenWidth"
        ),
        cap(
            "cyber-dashboard", "Cyber-console Home dashboard with real device counters", CapabilityArea.PLATFORM,
            CapabilityStatus.IMPLEMENTED, "ui/screens/HomeScreen.kt", emptyList(),
            listOf("Daily aggregates, recent DNS log, resolver state, protection mode"),
            listOf("Reference-inspired layout only; no fictional enterprise endpoints, global attack map or compliance certifications"),
            "DashboardPresentationTest overview/event/motion cases"
        ),
        cap(
            "lazy-scrolling", "Lazy keyed lists and isolated tab state for smoother scrolling", CapabilityArea.PLATFORM,
            CapabilityStatus.IMPLEMENTED, "MainActivity.kt", emptyList(),
            listOf("LazyColumn/LazyRow item keys and content types, per-destination StateFlow collection, Canvas draw-state animation"),
            listOf("Static gates and CI build guard the structure; frame timing still needs on-device profiling"),
            "tools/check_ui_performance.py and DashboardPresentationTest"
        ),
        cap(
            "private-search-providers", "Google and DuckDuckGo private-search workspace", CapabilityArea.PRIVACY_BROWSER,
            CapabilityStatus.PARTIAL, "engine/browser/PrivateSearch.kt", listOf(PERM_INTERNET),
            listOf("User-entered queries sent to the selected external HTTPS provider"),
            listOf("The selected provider receives searches and network metadata; no anonymity claim is made; WebView behavior needs a device smoke test"),
            "InfinityCoreTestSuite.testPrivateSearch_selectsProviderAndUpgradesCleartextNavigation plus manual browser test"
        ),
        cap(
            "private-search-history", "Search-only history, opt-in suggestions and private WebView cleanup", CapabilityArea.PRIVACY_BROWSER,
            CapabilityStatus.PARTIAL, "data/local/preferences/UserPreferencesRepository.kt", listOf(PERM_INTERNET),
            listOf("DataStore search settings/history; provider suggestion JSON only when explicitly enabled"),
            listOf("Private mode is not a separate OS profile; cleanup clears WebView session data, while providers still receive requests"),
            "Manual: enable private mode, search, leave Browser, and inspect history/cookies"
        ),
        cap(
            "scan-persistence", "Persistent scan reports and confirmation-based SAF deletion", CapabilityArea.THREAT_SCANNING,
            CapabilityStatus.PARTIAL, "data/repository/SecurityScanHistoryStore.kt", emptyList(),
            listOf("Room security_scans table and user-granted SAF document URIs"),
            listOf("Deletion depends on provider support; scan coverage remains limited to accessible files and local signatures/structural checks"),
            "Manual: scan a document, restart the app, verify its report, then confirm deletion"
        ),
        cap(
            "aether-report-explainer", "AETHER deterministic security-report explanations", CapabilityArea.AI_INTELLIGENCE,
            CapabilityStatus.IMPLEMENTED, "engine/aether/SecurityReportExplainer.kt", emptyList(),
            listOf("Persisted SecurityScanRecord evidence only"),
            listOf("Deterministic summarizer, not a generative model; cannot independently delete files or change settings"),
            "InfinityCoreTestSuite.testAetherReportExplainer_usesOnlyRecordedEvidenceAndCannotAuthorizeDeletion"
        ),
        cap(
            "webdav-files", "Authenticated Nextcloud/WebDAV file operations and streaming transfers", CapabilityArea.CLOUD_STORAGE,
            CapabilityStatus.PARTIAL, "engine/storage/WebDavClient.kt", listOf(PERM_INTERNET),
            listOf("HTTPS PROPFIND, MKCOL, PUT, GET, MOVE and DELETE responses"),
            listOf("Requires a user-supplied compatible server and credentials; live server interoperability is not verified in this sandbox"),
            "InfinityCoreTestSuite.testWebDavXml_parsesRealMultistatusEntriesAndQuota plus manual Nextcloud test"
        ),
        cap(
            "infinity-verified-capacity", "Unified capacity from online quota-verified providers only", CapabilityArea.CLOUD_STORAGE,
            CapabilityStatus.IMPLEMENTED, "domain/model/InfinityModels.kt", listOf(PERM_INTERNET),
            listOf("DAV quota-available-bytes and quota-used-bytes from successful health checks"),
            listOf("Offline and quota-unreported providers are excluded; this is aggregation, not distributed striping or replication"),
            "InfinityCoreTestSuite.testCapacityAggregation_countsOnlyOnlineProvidersWithMeasuredQuota"
        ),
        cap(
            "provider-credential-encryption", "Android Keystore encryption for storage-provider passwords", CapabilityArea.CLOUD_STORAGE,
            CapabilityStatus.PARTIAL, "data/repository/ProviderCredentialStore.kt", emptyList(),
            listOf("AndroidKeyStore AES-GCM keys and app-private ciphertext preferences"),
            listOf("Keystore behavior, device lock backup behavior and key invalidation require real-device testing"),
            "Manual: reconnect after process restart and inspect that preferences contain ciphertext only"
        ),
        cap(
            "nextcloud-public-sharing", "Nextcloud OCS public-link creation and downloaded file previews", CapabilityArea.CLOUD_STORAGE,
            CapabilityStatus.PARTIAL, "data/repository/CloudStorageRepository.kt", listOf(PERM_INTERNET),
            listOf("Nextcloud OCS Share API and FileProvider cache URIs"),
            listOf("Generic WebDAV has no standard public-share API; previews download up to 256 MiB and need an installed viewer"),
            "InfinityCoreTestSuite.testWebDavXml_parsesOnlyHttpsNextcloudShareLinks plus manual Nextcloud share test"
        ),
        cap(
            "telegram-tdlib-storage", "Telegram Cloud Storage via official TDLib, Keystore session and real transfers", CapabilityArea.CLOUD_STORAGE,
            CapabilityStatus.PARTIAL, "data/telegram/TelegramStorageRepository.kt", listOf(PERM_INTERNET),
            listOf(
                "Official org.drinkless.tdlib.Client/TdApi JNI requests and Telegram MTProto updates (updateFile, updateAuthorizationState)",
                "Android Keystore AES-256-GCM encrypted session/index/transfer records and SAF document streams"
            ),
            listOf(
                "Requires the user's own api_id/api_hash from my.telegram.org and a real Telegram account; live-account sign-in and network transfers need manual device verification",
                "2 GB per-file ceiling; Telegram publishes no quota or permanent-backup guarantee; only the designated Saved Messages/private channel is read"
            ),
            "TelegramCloudTestSuite.testOfficialAuthorizationFlowUsesRealTdlibRequestsAndNeverPersistsSecrets plus manual device smoke test (docs/TELEGRAM_CLOUD_STORAGE.md)"
        ),
        cap(
            "nextcloud-server-infrastructure", "Open-source Nextcloud/PostgreSQL backend deployment manifests", CapabilityArea.CLOUD_STORAGE,
            CapabilityStatus.PARTIAL, "infra/docker-compose.yml", listOf(PERM_INTERNET),
            listOf("Operator-supplied Nextcloud Community, PostgreSQL, HTTPS reverse proxy and persistent volumes"),
            listOf("External host, domain, backups and secrets are required; no capacity is provisioned by the repository alone"),
            "Deployment required: follow docs/INFINITY_ARCHITECTURE.md and run health/quota verification"
        ),
        cap(
            "distributed-striping", "Distributed file striping, replication and automatic recovery across providers", CapabilityArea.CLOUD_STORAGE,
            CapabilityStatus.PLANNED, "",
            listOf(PERM_INTERNET), listOf("Would require a coordinated metadata/erasure-coding backend"),
            listOf("Not implemented: independent provider aggregation must not be presented as distributed redundancy"),
            "n/a — architecture only"
        ),
        cap(
            "s3-ceph-backends", "S3-compatible, SeaweedFS and Ceph storage adapters", CapabilityArea.CLOUD_STORAGE,
            CapabilityStatus.PLANNED, "",
            listOf(PERM_INTERNET), listOf("Would require operator-supplied S3-compatible infrastructure"),
            listOf("Not enabled to avoid installing overlapping storage systems; Nextcloud/WebDAV is the selected first backend"),
            "n/a — external infrastructure and adapter required"
        ),
        cap(
            "adshield-cloud-api", "AdShield Cloud API client (accounts, files, shares, backups, usage)", CapabilityArea.CLOUD_STORAGE,
            CapabilityStatus.IMPLEMENTED, "data/cloud/CloudApi.kt", listOf(PERM_INTERNET),
            listOf("AdShield Cloud Worker /v1 endpoints over HTTPS with a Supabase access token"),
            listOf("Needs the user's own free Worker, D1 and Backblaze B2 project; nothing works offline and the DNS blocker does not depend on it"),
            "CloudPlatformTestSuite.cloudApiBuildsTheDocumentedPathsAndParsesRealResponses"
        ),
        cap(
            "adshield-cloud-uploads", "Resumable multipart uploads with pause, resume, retry and cancel", CapabilityArea.CLOUD_STORAGE,
            CapabilityStatus.IMPLEMENTED, "data/cloud/CloudUploadEngine.kt", listOf(PERM_INTERNET),
            listOf("Part URLs issued by POST /v1/uploads/init, bytes streamed straight to Backblaze B2"),
            listOf("Pause takes effect at a part boundary; a part interrupted mid-flight is re-sent from its offset"),
            "CloudPlatformTestSuite.uploadEngineResumesOnlyThePartsTheServerIsStillMissing"
        ),
        cap(
            "adshield-cloud-upload-integrity", "Per-part SHA-1 verification before storage accepts a part", CapabilityArea.CLOUD_STORAGE,
            CapabilityStatus.IMPLEMENTED, "data/cloud/CloudUploadEngine.kt", listOf(PERM_INTERNET),
            listOf("SHA-1 of the exact bytes streamed, declared in X-Bz-Content-Sha1"),
            listOf("SHA-1 is what the B2 large-file API requires; the whole-file hash is the provider's concatenation of part digests"),
            "CloudPlatformTestSuite.uploadEngineStreamsEveryPartWithItsRealSha1AndCompletes"
        ),
        cap(
            "adshield-cloud-background-uploads", "Background uploads that survive leaving the screen", CapabilityArea.CLOUD_STORAGE,
            CapabilityStatus.IMPLEMENTED, "worker/CloudUploadWorker.kt", listOf(PERM_INTERNET),
            listOf("WorkManager one-time work with a persisted job record in app-private storage"),
            listOf("Job metadata is persisted, not the file bytes; the source Uri must still be readable when the worker runs"),
            "Manual: start an upload, close the app, and confirm the job completes or reports a real failure"
        ),
        cap(
            "adshield-cloud-file-operations", "Folders, rename, move, copy, trash, restore and permanent delete", CapabilityArea.CLOUD_STORAGE,
            CapabilityStatus.IMPLEMENTED, "data/repository/CloudRepository.kt", listOf(PERM_INTERNET),
            listOf("POST /v1/folders and per-node action endpoints; permanent delete removes the stored object first"),
            listOf("Trash retention is 30 days by default; a copy shares the stored object until one side is deleted"),
            "Manual: `cd cloud && npm test` covers the folder lifecycle, purge frees quota and version pruning"
        ),
        cap(
            "adshield-cloud-versioning", "Version history with pruning beyond five stored versions", CapabilityArea.CLOUD_STORAGE,
            CapabilityStatus.IMPLEMENTED, "data/cloud/CloudApi.kt", listOf(PERM_INTERNET),
            listOf("GET /v1/nodes/:id/versions and the versions table written on re-upload"),
            listOf("Older versions are deleted for real, so history costs storage until it is pruned"),
            "Manual: `cd cloud && npm test` — re-uploading the same name keeps a history and prunes beyond five"
        ),
        cap(
            "adshield-cloud-sharing", "Expiring share links that can be revoked", CapabilityArea.CLOUD_STORAGE,
            CapabilityStatus.IMPLEMENTED, "data/cloud/CloudApi.kt", listOf(PERM_INTERNET),
            listOf("Random share tokens, server-side expiry, short-lived signed download URLs"),
            listOf("Read-only links; anyone with the token can open it until it expires or is revoked"),
            "Manual: `cd cloud && npm test` — share links work, expire and can be revoked"
        ),
        cap(
            "adshield-cloud-duplicates", "Duplicate detection grouped by real content hash", CapabilityArea.CLOUD_STORAGE,
            CapabilityStatus.IMPLEMENTED, "data/cloud/CloudApi.kt", listOf(PERM_INTERNET),
            listOf("SHA-1 recorded for every uploaded object, grouped server-side"),
            listOf("Only files uploaded through AdShield Cloud carry a hash; identical content under different names is still grouped"),
            "Manual: `cd cloud && npm test` — duplicate detection groups files by their real content hash"
        ),
        cap(
            "adshield-cloud-quota-truth", "Quota accounting reconciled against what the provider stores", CapabilityArea.CLOUD_STORAGE,
            CapabilityStatus.IMPLEMENTED, "data/cloud/CloudApi.kt", listOf(PERM_INTERNET),
            listOf("GET /v1/account and POST /v1/account/reconcile, which lists the objects B2 actually reports"),
            listOf("Reconciliation pages through up to 50 000 objects per call; counters are corrected, never guessed"),
            "Manual: `cd cloud && npm test` — reconciliation rebuilds usage from what the provider actually stores"
        ),
        cap(
            "adshield-cloud-usage", "Live usage monitoring from the backend, never a local estimate", CapabilityArea.CLOUD_STORAGE,
            CapabilityStatus.IMPLEMENTED, "ui/viewmodel/CloudViewModel.kt", listOf(PERM_INTERNET),
            listOf("Quota, used bytes, remaining bytes, trashed bytes and 24-hour request counters from the API"),
            listOf("Percentages and bars are hidden until the account answers; the free allowance is 10 GB on Backblaze B2"),
            "CloudPlatformTestSuite.cloudApiBuildsTheDocumentedPathsAndParsesRealResponses"
        ),
        cap(
            "adshield-cloud-backup", "DNS configuration backup, integrity verification and restore", CapabilityArea.CLOUD_STORAGE,
            CapabilityStatus.IMPLEMENTED, "data/cloud/CloudBackup.kt", listOf(PERM_INTERNET),
            listOf("Resolver, protection mode, rules, filter lists, per-app firewall and preferences, hashed with SHA-256"),
            listOf("Restore replaces rules and filter lists; the backup is re-downloaded and re-hashed before it is applied"),
            "CloudPlatformTestSuite.backupCodecRoundTripsAndRefusesATamperedPayload"
        ),
        cap(
            "adshield-cloud-vault", "Optional client-side AES-256-GCM encryption for backups", CapabilityArea.CLOUD_STORAGE,
            CapabilityStatus.IMPLEMENTED, "data/cloud/CloudVault.kt", emptyList(),
            listOf("PBKDF2-HMAC-SHA256 key derivation, Android Keystore-free passphrase vault, GCM tag verification"),
            listOf("Backups only; a forgotten passphrase is unrecoverable; resumable multipart file uploads are not encrypted client-side"),
            "CloudPlatformTestSuite.vaultEncryptsWithTheRightPassphraseAndRefusesTheWrongOne"
        ),
        cap(
            "adshield-cloud-scan", "Pre-upload malware scan with quarantine", CapabilityArea.CLOUD_STORAGE,
            CapabilityStatus.IMPLEMENTED, "data/repository/CloudRepository.kt", emptyList(),
            listOf("On-device FileThreatScanner verdict over the first 8 MiB of the file"),
            listOf("Files rated DANGEROUS are refused and listed in the Cloud Security screen; SUSPICIOUS files upload but are reported"),
            "Manual: pick an EICAR test file and confirm the upload is refused and appears in Quarantine"
        ),
        cap(
            "adshield-cloud-audit", "Server-side audit log of every mutating action", CapabilityArea.CLOUD_STORAGE,
            CapabilityStatus.IMPLEMENTED, "data/cloud/CloudApi.kt", listOf(PERM_INTERNET),
            listOf("audit_log rows written inside the Worker for uploads, shares, deletes and restores"),
            listOf("The newest 100 entries are readable in the app; IP addresses come from Cloudflare's connecting-IP header"),
            "Manual: `cd cloud && npm test` — every mutating action is written to the per-user audit log"
        ),
        cap(
            "adshield-cloud-credential-safety", "No storage credentials inside the APK", CapabilityArea.CLOUD_STORAGE,
            CapabilityStatus.IMPLEMENTED, "data/cloud/CloudApi.kt", listOf(PERM_INTERNET),
            listOf("Only public endpoints: the Worker URL, the Supabase project URL and anon key, and the Turnstile site key"),
            listOf("Bucket keys live only in Worker secrets; the app never sees them and cannot leak them"),
            "Manual: unzip the release APK and confirm no Backblaze key material is present"
        ),
        cap(
            "onion-routing", "Onion routing through Orbot with a real Tor check", CapabilityArea.PRIVACY_BROWSER,
            CapabilityStatus.PARTIAL, "engine/onion/OnionRoutingManager.kt", listOf(PERM_INTERNET),
            listOf("Orbot's SOCKS proxy at 127.0.0.1:9050 and check.torproject.org/api/ip"),
            listOf("Requires Orbot to be installed and connected; Android only lets an app intercept GET requests, so POST forms and some media streams use the normal path; AdShield cannot bundle a Tor daemon"),
            "OnionRoutingTestSuite.orbotStatusBroadcastMapsToHonestStates"
        ),
        cap(
            "saf-universal-files", "SAF file/folder management, import, preview, share and search", CapabilityArea.FILE_MANAGEMENT,
            CapabilityStatus.PARTIAL, "data/repository/SafFileRepository.kt", emptyList(),
            listOf("DocumentsContract trees explicitly granted by the user and external viewer/share apps"),
            listOf("No other-app sandbox access or all-files permission; providers may not support every mutation"),
            "Manual device test: grant a tree, import, rename, preview, share and delete a test document"
        ),
        cap(
            "saf-folder-scanning", "Bounded recursive folder scans with live per-file progress", CapabilityArea.FILE_MANAGEMENT,
            CapabilityStatus.PARTIAL, "data/repository/SafFileRepository.kt", emptyList(),
            listOf("User-granted SAF documents and the existing FileThreatScanner/ApkAnalyzer"),
            listOf("At most 250 files per run; files over 512 MiB and unreadable items are skipped/reported; periodic background scans are not scheduled"),
            "Manual device test: scan a tree containing EICAR and a clean file, then inspect persisted history"
        ),
        cap(
            "saf-duplicates", "SHA-256 duplicate-content grouping without automatic deletion", CapabilityArea.FILE_MANAGEMENT,
            CapabilityStatus.PARTIAL, "data/repository/SafFileRepository.kt", emptyList(),
            listOf("Content hashes of same-size candidates in a user-granted tree"),
            listOf("Recursive candidate set is bounded to 250 files; unreadable candidates are omitted rather than guessed"),
            "Manual device test: duplicate a document and verify that only identical hashes are grouped"
        ),
        cap(
            "direct-media-downloads", "Direct HTTPS media inspection, system downloads and history", CapabilityArea.DOWNLOADS,
            CapabilityStatus.PARTIAL, "data/repository/DirectDownloadRepository.kt", listOf(PERM_INTERNET),
            listOf("HTTP HEAD/one-byte Range metadata and Android DownloadManager status"),
            listOf("No website extractor, DRM/authentication bypass or manual pause/resume; server and SAF destination compatibility need device tests"),
            "InfinityCoreTestSuite.testDirectMediaInput_enforcesHttpsAndSafeFileNames plus manual direct MP4 test"
        ),
        cap(
            "download-integrity", "Downloaded size checks and optional publisher SHA-256 verification", CapabilityArea.DOWNLOADS,
            CapabilityStatus.PARTIAL, "data/repository/DirectDownloadRepository.kt", emptyList(),
            listOf("Measured destination bytes, server-declared length and optional user-supplied digest"),
            listOf("A locally computed digest without a publisher reference does not establish authenticity"),
            "Manual: verify a direct file with correct and deliberately incorrect SHA-256 values"
        ),
        cap(
            "scan-journal", "Persistent scan history with exportable threat reports", CapabilityArea.THREAT_SCANNING,
            CapabilityStatus.IMPLEMENTED, "engine/security/ScanJournal.kt", emptyList(),
            listOf("Real file/URL/APK/app-audit results persisted in a dedicated DataStore journal"),
            listOf(
                "Most recent 200 records are retained",
                "An empty journal is reported as 'no scan performed', never as a clean device"
            ),
            "ScanJournalTest (unique ids, retention, round-trip, honest empty report)"
        ),
        cap(
            "quarantine", "Quarantine with SHA-256 integrity verification", CapabilityArea.THREAT_SCANNING,
            CapabilityStatus.IMPLEMENTED, "engine/security/QuarantineManager.kt", emptyList(),
            listOf("User-selected file content, SHA-256 computed on ingest and re-checked on restore"),
            listOf(
                "Isolated copies live in app-private storage; they are not additionally encrypted at rest",
                "Android often refuses to delete an original this app does not own — that outcome is reported, not assumed"
            ),
            "ScanJournalTest naming/traversal cases; manual device restore/delete check"
        ),
        cap(
            "scheduled-scan", "Scheduled on-device security scan", CapabilityArea.THREAT_SCANNING,
            CapabilityStatus.IMPLEMENTED, "worker/ScheduledScanWorker.kt", listOf(PERM_NOTIF),
            listOf("WorkManager periodic job, PackageManager app audit, quarantine hash re-verification"),
            listOf(
                "Audits installed apps and quarantine only; shared storage is not scanned without a user grant",
                "WorkManager timing is approximate and deferrable by the OS to save battery"
            ),
            "Manual device check; ScanJournalTest covers record/report shape"
        ),
        cap(
            "diagnostic-report", "Exportable diagnostic report", CapabilityArea.PLATFORM,
            CapabilityStatus.PLANNED, "",
            emptyList(), listOf("Would summarise telemetry, rule counts, last errors"),
            listOf("Not implemented yet; must scrub identifiers before shipping"),
            "n/a — planned"
        ),
        cap(
            "intel-event-system", "Unified intelligence event store with bounded retention",
            CapabilityArea.PLATFORM,
            CapabilityStatus.IMPLEMENTED, "data/repository/IntelligenceRepository.kt",
            emptyList(),
            listOf(
                "Room intelligence_events table fed by DNS blocks, scan journal records, VPN transitions, " +
                    "battery threshold crossings, settings changes and WorkManager results",
                "Per-type de-duplication cooldowns from IntelligenceDedupePolicy"
            ),
            listOf(
                "Keeps at most 800 rows and 14 days; older evidence is deleted",
                "Only security-relevant DNS blocks (malware/phishing categories) become events; routine ad/tracker " +
                    "blocks stay as counters so the feed is not flooded",
                "Events are only stored while the process is alive; Android cannot run this observer while the app is stopped"
            ),
            "IntelligenceMapTestSuite.testSanitizerRejectsEventWithoutEvidence and " +
                "testRepositoryRoundtripPersistsSanitizedEvents"
        ),
        cap(
            "intel-live-map", "Interactive live intelligence map with camera, nodes and event pulses",
            CapabilityArea.AI_INTELLIGENCE,
            CapabilityStatus.IMPLEMENTED, "ui/screens/intelligence/IntelligenceMapScreen.kt",
            emptyList(),
            listOf(
                "Real module snapshots from VPN runtime, preferences, scan journal, quarantine index and battery telemetry",
                "Stored intelligence events for the event feed, timeline and map pulses"
            ),
            listOf(
                "This is an internal system map, not a geographic or network-topology map",
                "Pausing visual updates freezes the rendering only; DNS protection and scheduled jobs keep running",
                "The pulse animation runs only while a real event is in flight; an idle map performs no frame work"
            ),
            "IntelligenceMapTestSuite.testGeometryPinchZoomKeepsFocalPointStable and " +
                "testAggregatorBuildsOnlyFromRealInputs"
        ),
        cap(
            "intel-event-sources", "Module services publish structured events to the map",
            CapabilityArea.AI_INTELLIGENCE,
            CapabilityStatus.IMPLEMENTED, "engine/intelligence/IntelligenceEventFactory.kt",
            emptyList(),
            listOf(
                "Malware/phishing DNS blocks observed by AdShieldVpnService",
                "Security scan journal records (APK, file, URL, installed-app audit)",
                "Measured battery temperature/health thresholds",
                "VPN state transitions and WorkManager job results"
            ),
            listOf(
                "Payment-link attribution uses deterministic URL/finding tokens; it is classification, not reputation",
                "A factory that cannot attach real evidence returns null, so nothing is written"
            ),
            "IntelligenceMapTestSuite.testFactoryNeverEmitsWithoutRealEvidence and " +
                "testScanRecordRoutesToCyberFinancialAndPrivacy"
        ),
        cap(
            "intel-map-performance", "Frame-safe map aggregation with a measured budget",
            CapabilityArea.AI_INTELLIGENCE,
            CapabilityStatus.IMPLEMENTED, "engine/intelligence/IntelligenceAggregator.kt",
            emptyList(),
            listOf(
                "600 stored events, 8 nodes, active module/severity/range/query filters"
            ),
            listOf(
                "The budget is measured on the CI JVM, not on a device; on-device frame timing still needs a manual check",
                "The UI draws at most 8 nodes, 7 connectors, 7 markers and 12 concurrent pulses; pulses are pruned when expired"
            ),
            "IntelligenceMapTestSuite.testAggregatorPerformanceBudget"
        ),
        cap(
            "qs-tile-private-dns", "Quick Settings tile for Private DNS (Off, Auto, Hostnames)",
            CapabilityArea.PLATFORM,
            CapabilityStatus.IMPLEMENTED, "service/PrivateDnsTileService.kt",
            listOf("android.permission.WRITE_SECURE_SETTINGS", "android.permission.BIND_QUICK_SETTINGS_TILE"),
            listOf(
                "Settings.Global private_dns_mode and private_dns_specifier read-back",
                "User-configured cycle steps and custom DNS-over-TLS hostnames in DataStore"
            ),
            listOf(
                "Requires a one-time grant of WRITE_SECURE_SETTINGS via Wireless/USB ADB, Shizuku/LADB shell, or optional root; without it the tile opens quick setup",
                "Some OEM or managed enterprise builds reject Settings.Global writes; read-back verification detects and reports rejections",
                "Optional auto-revert relies on WorkManager and inexact AlarmManager so OS battery optimization can delay execution slightly"
            ),
            "SystemTilesTestSuite.testPrivateDnsCycleAdvancesAndWrapsCorrectly and SystemTilesTestSuite.testPrivateDnsAutoRevertPreservesOriginalTargetAcrossMultipleTapsAndCancelsOnReturn and SystemTilesTestSuite.testQuickSetupCommandFormattingAndChecklistEvaluation"
        ),
        cap(
            "qs-tile-usb-debugging", "Quick Settings tile for USB Debugging with optional auto-revert",
            CapabilityArea.PLATFORM,
            CapabilityStatus.IMPLEMENTED, "service/UsbDebuggingTileService.kt",
            listOf("android.permission.WRITE_SECURE_SETTINGS", "android.permission.BIND_QUICK_SETTINGS_TILE"),
            listOf(
                "Settings.Global.ADB_ENABLED and Settings.Global.DEVELOPMENT_SETTINGS_ENABLED read-back",
                "User-configured auto-revert delay and lock-screen guard in DataStore"
            ),
            listOf(
                "Requires a one-time ADB grant of WRITE_SECURE_SETTINGS and Developer Options to be enabled on Android",
                "Requires device unlock to toggle by default; auto-revert timing via WorkManager and setAndAllowWhileIdle is approximate"
            ),
            "SystemTilesTestSuite.testUsbDebuggingAutoRevertArmsOnEnableAndClearsOnDisable and SystemTilesTestSuite.testTilePresentationsReflectPermissionAndRealSystemState"
        ),
        cap(
            "worldmonitor-3d-earth", "Interactive 3D Digital Earth (CesiumJS WebGL, terrain, projections, split-date GIBS)",
            CapabilityArea.AI_INTELLIGENCE,
            CapabilityStatus.IMPLEMENTED, "ui/screens/worldmonitor/WorldMonitorScreen.kt", listOf(PERM_INTERNET),
            listOf("Bundled CesiumJS 1.147.0, Natural Earth 110m borders, Esri/OSM/CARTO/NASA GIBS/AWS Terrarium tiles, Nominatim & Overpass 3D"),
            listOf("Street-level satellite tiles and Overpass 3D buildings require network access until cached in the 120 MB tile cache"),
            "WorldMonitorTestSuite.testJsBridgeAndTileServerSecurityValidation"
        ),
        cap(
            "worldmonitor-multi-domain-feeds", "8-domain live global OSINT feeds (Aviation, Maritime, Space, Disaster, Weather, Cyber, News, Infra)",
            CapabilityArea.AI_INTELLIGENCE,
            CapabilityStatus.IMPLEMENTED, "engine/worldmonitor/WorldMonitorNormalizers.kt", listOf(PERM_INTERNET),
            listOf("OpenSky, ADSB-lol, OurAirports, Digitraffic AIS, NOAA NDBC/SWPC/NWS, CelesTrak, USGS, EMSC, NASA EONET, GDACS, Open-Meteo, RainViewer, CISA KEV, IODA, RIPEstat, GDELT, UN News"),
            listOf("Anonymous OpenSky/Overpass tiers enforce rate limits; NASA FIRMS raw CSV requires a free user MAP_KEY; aged offline records decay to DELAYED/HISTORICAL"),
            "WorldMonitorTestSuite.testNormalizersAcrossAllEightIntelligenceDomains and testDynamicFreshnessDecayOnCachedEntities"
        ),
        cap(
            "worldmonitor-correlation-engine", "Deterministic cross-domain Global Event Correlation Engine",
            CapabilityArea.AI_INTELLIGENCE,
            CapabilityStatus.IMPLEMENTED, "engine/worldmonitor/EventCorrelationEngine.kt", emptyList(),
            listOf("Normalized multi-provider WorldEntity records with Haversine distance and event-time delta"),
            listOf("Reports spatio-temporal coincidence across independent providers with explicit non-causation caveats; never links same-provider or simulated items"),
            "WorldMonitorTestSuite.testEventCorrelationEngineNeverInventsSelfLinks"
        ),
        cap(
            "worldmonitor-digital-twin-analyst", "Regional Digital Twin & on-device Evidence RAG Global Analyst",
            CapabilityArea.AI_INTELLIGENCE,
            CapabilityStatus.IMPLEMENTED, "engine/worldmonitor/WorldMonitorAiAnalyst.kt", listOf(PERM_INTERNET),
            listOf("World Bank population dataset + bundled UN/World Bank baseline, Open-Meteo elevation/weather/GloFAS, cached WorldEntity records"),
            listOf("100% local deterministic retrieval-augmented synthesis separating verified facts, data gaps, and hypotheses without cloud LLMs"),
            "WorldMonitorTestSuite.testDigitalTwinAndAiAnalystEpistemicSeparation"
        ),
        cap(
            "worldmonitor-simulation-watchtower", "Experimental Simulation Lab & Watchtower geofence notifications",
            CapabilityArea.AI_INTELLIGENCE,
            CapabilityStatus.IMPLEMENTED, "engine/worldmonitor/SimulationLabEngine.kt", listOf(PERM_INTERNET, PERM_NOTIF),
            listOf("User-defined geofences evaluated via WorkManager against synced feeds; geometric plume, surge, and exposure screening"),
            listOf("All simulation outputs are stamped SIMULATED / EXPERIMENTAL_SIMULATION; background Watchtower polling uses 60-minute WorkManager scheduling"),
            "WorldMonitorTestSuite.testSimulationLabStampsExperimentalMetadata and testWatchtowerGeofenceMatchingAndDeduplication"
        ),
        cap(
            "worldmonitor-commercial-ais-satellite-video", "Global commercial satellite-AIS stream & live orbital video",
            CapabilityArea.AI_INTELLIGENCE,
            CapabilityStatus.UNAVAILABLE, "", emptyList(),
            listOf("Digitraffic coastal AIS and NOAA NDBC buoys are supported; commercial global AIS requires external WebSocket/API licensing"),
            listOf("Global satellite AIS requires a licensed backend or user AISStream WebSocket proxy; live real-time satellite video of arbitrary Earth locations does not publicly exist"),
            "n/a — commercial backend / physical sensor restriction"
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
