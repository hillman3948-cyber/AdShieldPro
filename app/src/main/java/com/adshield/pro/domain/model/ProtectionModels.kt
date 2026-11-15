package com.adshield.pro.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class RuleCategory(val displayName: String, val badgeColorHex: Long) {
    ADS("Advertising", 0xFFEF4444),
    TRACKERS("Tracking & Analytics", 0xFFF59E0B),
    MALWARE("Malware & Phishing", 0xFFEC4899),
    STRICT_TELEMETRY("Aggressive / Telemetry", 0xFF8B5CF6),
    CUSTOM("User Custom", 0xFF10B981)
}

@Serializable
enum class ProtectionMode(
    val title: String,
    val subtitle: String,
    val warningNote: String?,
    val activeCategories: Set<RuleCategory>,
    val blockSinkholeZeroIp: Boolean
) {
    STANDARD(
        title = "Standard Mode",
        subtitle = "Balanced ad, tracker, and malware filtering with minimal website breakage.",
        warningNote = null,
        activeCategories = setOf(RuleCategory.ADS, RuleCategory.TRACKERS, RuleCategory.MALWARE, RuleCategory.CUSTOM),
        blockSinkholeZeroIp = false
    ),
    STRICT(
        title = "Strict Mode",
        subtitle = "Aggressive blocking including OEM telemetry, social trackers, and wildcard heuristics.",
        warningNote = "Compatibility Warning: Strict Mode blocks social widgets, deep-link trackers, and telemetry endpoints that some apps or login flows may require.",
        activeCategories = setOf(
            RuleCategory.ADS,
            RuleCategory.TRACKERS,
            RuleCategory.MALWARE,
            RuleCategory.STRICT_TELEMETRY,
            RuleCategory.CUSTOM
        ),
        blockSinkholeZeroIp = true
    ),
    PRIVACY(
        title = "Privacy Mode",
        subtitle = "Focused on neutralizing analytics, behavioral fingerprinting, and malicious domains.",
        warningNote = null,
        activeCategories = setOf(RuleCategory.TRACKERS, RuleCategory.MALWARE, RuleCategory.STRICT_TELEMETRY, RuleCategory.CUSTOM),
        blockSinkholeZeroIp = false
    ),
    CUSTOM(
        title = "Custom Mode",
        subtitle = "Full user control over enabled blocklists, custom rules, wildcards, and exceptions.",
        warningNote = null,
        activeCategories = setOf(
            RuleCategory.ADS,
            RuleCategory.TRACKERS,
            RuleCategory.MALWARE,
            RuleCategory.STRICT_TELEMETRY,
            RuleCategory.CUSTOM
        ),
        blockSinkholeZeroIp = true
    )
}

@Serializable
enum class DnsProtocol(val label: String, val description: String) {
    DOH("DNS-over-HTTPS (DoH)", "RFC 8484 encrypted HTTPS POST queries on port 443"),
    DOT("DNS-over-TLS (DoT)", "RFC 7858 TLS-encrypted TCP stream on port 853"),
    UDP_TCP("Standard DNS (UDP/TCP)", "Protected low-latency UDP/TCP queries on port 53")
}

@Serializable
enum class DnsProviderPreset(
    val id: String,
    val displayName: String,
    val providerSummary: String,
    val privacyDisclosure: String,
    val primaryIpv4: String,
    val secondaryIpv4: String,
    val primaryIpv6: String,
    val secondaryIpv6: String,
    val dohUrl: String,
    val dotHostname: String,
    val dotPort: Int = 853,
    /**
     * Whether the operator forwards EDNS Client Subnet (ECS) to authoritative
     * servers, per its published policy.
     *
     * This is the DNS setting that decides which CDN edge serves a video: the
     * authoritative server picks an edge from the resolver's address, so a
     * resolver that forwards a truncated client subnet returns an edge near you.
     * It is a real trade-off — the authoritative server learns a /24 of your
     * address — so it is a per-provider choice, never silently forced.
     */
    val forwardsClientSubnet: Boolean = false
) {
    CLOUDFLARE(
        id = "cloudflare",
        displayName = "Cloudflare (1.1.1.1)",
        providerSummary = "Ultra-fast global anycast DNS. Sends no client subnet, so CDN edge choice follows the resolver location.",
        privacyDisclosure = "Cloudflare commits not to sell browsing data or retain personal IP logs beyond 24 hours.",
        primaryIpv4 = "1.1.1.1",
        secondaryIpv4 = "1.0.0.1",
        primaryIpv6 = "2606:4700:4700::1111",
        secondaryIpv6 = "2606:4700:4700::1001",
        dohUrl = "https://cloudflare-dns.com/dns-query",
        dotHostname = "one.one.one.one"
    ),
    QUAD9(
        id = "quad9",
        displayName = "Quad9 Security (9.9.9.9)",
        providerSummary = "Swiss non-profit security DNS with built-in threat intelligence.",
        privacyDisclosure = "Operated by Quad9 Foundation under Swiss privacy law; no personally identifiable IP logging.",
        primaryIpv4 = "9.9.9.9",
        secondaryIpv4 = "149.112.112.112",
        primaryIpv6 = "2620:fe::fe",
        secondaryIpv6 = "2620:fe::9",
        dohUrl = "https://dns.quad9.net/dns-query",
        dotHostname = "dns.quad9.net"
    ),
    ADGUARD_UNFILTERED(
        id = "adguard",
        displayName = "AdGuard DNS",
        providerSummary = "Privacy-oriented encrypted DNS resolver optimized for ad-blocking clients.",
        privacyDisclosure = "Operated by AdGuard Software Ltd; zero personal profiling or third-party data sharing.",
        primaryIpv4 = "94.140.14.140",
        secondaryIpv4 = "94.140.14.141",
        primaryIpv6 = "2a10:50c0::1:ff",
        secondaryIpv6 = "2a10:50c0::2:ff",
        dohUrl = "https://unfiltered.adguard-dns.com/dns-query",
        dotHostname = "unfiltered.adguard-dns.com"
    ),
    GOOGLE(
        id = "google",
        displayName = "Google Public DNS (8.8.8.8)",
        providerSummary = "High-availability global DNS with DNSSEC validation.",
        privacyDisclosure = "Operated by Google LLC; temporary logs expire within 24-48 hours per Google Public DNS Privacy Policy.",
        primaryIpv4 = "8.8.8.8",
        secondaryIpv4 = "8.8.4.4",
        primaryIpv6 = "2001:4860:4860::8888",
        secondaryIpv6 = "2001:4860:4860::8844",
        dohUrl = "https://dns.google/dns-query",
        dotHostname = "dns.google",
        forwardsClientSubnet = true
    ),
    QUAD9_ECS(
        id = "quad9-ecs",
        displayName = "Quad9 Secure + ECS (9.9.9.11)",
        providerSummary = "Quad9's malware-blocking resolver that also forwards a truncated client subnet, for CDN-accurate video edges.",
        privacyDisclosure = "Operated by Quad9 Foundation under Swiss law. Forwarding ECS reveals a truncated subnet to authoritative servers — the documented trade-off of this endpoint.",
        primaryIpv4 = "9.9.9.11",
        secondaryIpv4 = "149.112.112.11",
        primaryIpv6 = "2620:fe::11",
        secondaryIpv6 = "2620:fe::fe:11",
        dohUrl = "https://dns11.quad9.net/dns-query",
        dotHostname = "dns11.quad9.net",
        forwardsClientSubnet = true
    ),
    NEXTDNS(
        id = "nextdns",
        displayName = "NextDNS",
        providerSummary = "Configurable encrypted DNS using the public NextDNS anycast resolver.",
        privacyDisclosure = "Operated by NextDNS; logging and analytics depend on the public resolver policy. No account profile or API key is embedded.",
        primaryIpv4 = "45.90.28.0",
        secondaryIpv4 = "45.90.30.0",
        primaryIpv6 = "2a07:a8c0::",
        secondaryIpv6 = "2a07:a8c1::",
        dohUrl = "https://dns.nextdns.io/dns-query",
        dotHostname = "dns.nextdns.io"
    ),
    CONTROL_D(
        id = "controld",
        displayName = "Control D",
        providerSummary = "Encrypted anycast DNS using Control D's unfiltered public resolver.",
        privacyDisclosure = "Operated by Control D / Windscribe. This uses the unfiltered public endpoint, not a private account configuration.",
        primaryIpv4 = "76.76.2.0",
        secondaryIpv4 = "76.76.10.0",
        primaryIpv6 = "2606:1a40::",
        secondaryIpv6 = "2606:1a40:1::",
        dohUrl = "https://freedns.controld.com/p0",
        dotHostname = "p0.freedns.controld.com"
    ),
    CUSTOM(
        id = "custom",
        displayName = "Custom DNS Resolver",
        providerSummary = "User-defined DoH endpoint, DoT hostname, or IPv4/IPv6 DNS server.",
        privacyDisclosure = "Subject to the privacy policy of your configured upstream DNS operator.",
        primaryIpv4 = "1.1.1.1",
        secondaryIpv4 = "9.9.9.9",
        primaryIpv6 = "2606:4700:4700::1111",
        secondaryIpv6 = "2620:fe::fe",
        dohUrl = "https://cloudflare-dns.com/dns-query",
        dotHostname = "one.one.one.one"
    )
}

@Serializable
data class DnsResolverConfig(
    val preset: DnsProviderPreset = DnsProviderPreset.CLOUDFLARE,
    val protocol: DnsProtocol = DnsProtocol.DOH,
    val customDohUrl: String = "https://cloudflare-dns.com/dns-query",
    val customDotHost: String = "one.one.one.one",
    val customPrimaryIpv4: String = "1.1.1.1",
    val customSecondaryIpv4: String = "1.0.0.1",
    val customPrimaryIpv6: String = "2606:4700:4700::1111",
    val customSecondaryIpv6: String = "2606:4700:4700::1001",
    val enableIpv6: Boolean = true,
    val failoverEnabled: Boolean = false,
    val failoverProviderIds: Set<String> = emptySet(),
    val fastestHealthyProvider: Boolean = false,
    val dnssecMode: DnssecMode = DnssecMode.REPORT_ONLY,
    /**
     * Prefer a subnet-forwarding resolver for media/CDN hostnames.
     *
     * Sequential failover and a single resolver choice cannot serve both goals:
     * the fastest resolver for browsing is often the one that hands a video CDN
     * the wrong edge. When this is on, media hostnames are resolved through the
     * first healthy provider that forwards a client subnet (the selected provider
     * when it already does), while everything else keeps the configured order.
     */
    val preferGeoAccurateMediaDns: Boolean = true
) {
    val activeDohUrl: String
        get() = if (preset == DnsProviderPreset.CUSTOM) customDohUrl.ifBlank { preset.dohUrl } else preset.dohUrl

    val activeDotHost: String
        get() = if (preset == DnsProviderPreset.CUSTOM) customDotHost.ifBlank { preset.dotHostname } else preset.dotHostname

    val activePrimaryIpv4: String
        get() = if (preset == DnsProviderPreset.CUSTOM) customPrimaryIpv4.ifBlank { preset.primaryIpv4 } else preset.primaryIpv4

    val activeSecondaryIpv4: String
        get() = if (preset == DnsProviderPreset.CUSTOM) customSecondaryIpv4.ifBlank { preset.secondaryIpv4 } else preset.secondaryIpv4

    /** Primary IPv6 resolver address, used for IPv6-only networks and as a fallback. */
    val activePrimaryIpv6: String
        get() = if (preset == DnsProviderPreset.CUSTOM) customPrimaryIpv6.ifBlank { preset.primaryIpv6 } else preset.primaryIpv6

    /** Secondary IPv6 resolver address, used for IPv6-only networks and as a fallback. */
    val activeSecondaryIpv6: String
        get() = if (preset == DnsProviderPreset.CUSTOM) customSecondaryIpv6.ifBlank { preset.secondaryIpv6 } else preset.secondaryIpv6
}


enum class DnsSelectionMode { CONFIGURED_ORDER, FASTEST_HEALTHY }

@Serializable
enum class DnssecMode(val label: String, val description: String) {
    REPORT_ONLY(
        "Report upstream DNSSEC status",
        "Preserves normal unsigned DNS resolution and reports AD/RRSIG evidence; does not claim local chain validation."
    ),
    REQUIRE_AUTHENTICATED_ANSWERS(
        "Strict: require AD on answers",
        "Rejects positive answers without the upstream Authenticated Data bit. This intentionally blocks unsigned domains and is not local DNSSEC validation."
    )
}

@Serializable
enum class AppDnsPolicy(val label: String) {
    DEFAULT("Use global protection"),
    ALLOW_ALL("Allow all DNS (bypass domain filters)"),
    BLOCK_ALL("Block all DNS for this app")
}

@Serializable
enum class NetworkTransportPolicy(val label: String) {
    WIFI("Wi-Fi"),
    CELLULAR("Mobile data"),
    ETHERNET("Ethernet"),
    OTHER("Other / VPN")
}

@Serializable
data class DnsFirewallPolicy(
    val appPolicies: Map<String, AppDnsPolicy> = emptyMap(),
    val protectedTransports: Set<NetworkTransportPolicy> = setOf(
        NetworkTransportPolicy.WIFI,
        NetworkTransportPolicy.CELLULAR,
        NetworkTransportPolicy.ETHERNET,
        NetworkTransportPolicy.OTHER
    ),
    val emergencyAllowlistOnly: Boolean = false
)
