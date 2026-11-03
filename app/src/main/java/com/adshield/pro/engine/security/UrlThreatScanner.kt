package com.adshield.pro.engine.security

/**
 * AdShield Pro — Deep URL / Link Threat Analyzer (Pure Kotlin, unit-testable).
 *
 * Multi-layer static analysis of any URL or link pasted, clicked, or found inside files:
 * 1. Known-threat host check (fed live from the on-device malware/phishing filter lists).
 * 2. Credential leakage detection (`https://user:pass@host`).
 * 3. Punycode / homoglyph IDN abuse (`xn--` hosts used for brand spoofing).
 * 4. Raw IP-literal hosting combined with credential/login content.
 * 5. Brand typosquatting (Damerau-Levenshtein distance against a top-brand list).
 * 6. High-risk TLD abuse heuristics.
 * 7. URL shortener + nested-redirect chains.
 * 8. Phishing keyword + structure heuristics (login/verify/suspend patterns).
 *
 * The scanner never opens the URL; it is 100% static, local, and privacy-preserving.
 */
class UrlThreatScanner {

    enum class ThreatLevel {
        CLEAN,
        SUSPICIOUS,
        DANGEROUS
    }

    data class UrlVerdict(
        val inputUrl: String,
        val normalizedHost: String,
        val threatLevel: ThreatLevel,
        val riskScore: Int, // 0..100
        val findings: List<String>,
        val recommendation: String
    ) {
        val isSafe: Boolean get() = threatLevel == ThreatLevel.CLEAN
        val isKnownThreat: Boolean get() = findings.any { it.startsWith("Known malicious host") }
    }

    /** Result provider hook: returns true when the host (or a parent domain) is on the active malware/phishing blocklist. */
    interface KnownThreatHostLookup {
        fun isKnownThreatHost(host: String): Boolean
    }

    private val knownThreatLookup: KnownThreatHostLookup?

    constructor() {
        this.knownThreatLookup = null
    }

    constructor(knownThreatLookup: KnownThreatHostLookup) {
        this.knownThreatLookup = knownThreatLookup
    }
    fun scan(rawUrl: String): UrlVerdict {
        val findings = ArrayList<String>()
        var score = 0

        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) {
            return UrlVerdict(trimmed, "", ThreatLevel.CLEAN, 0, listOf("Empty input"), "Nothing to scan")
        }

        val workingUrl = if (!trimmed.contains("://")) "https://$trimmed" else trimmed
        val parsed = runCatching { java.net.URI(workingUrl) }.getOrNull()
        if (parsed == null || parsed.host.isNullOrBlank()) {
            return UrlVerdict(
                inputUrl = trimmed,
                normalizedHost = "",
                threatLevel = ThreatLevel.SUSPICIOUS,
                riskScore = 40,
                findings = listOf("Malformed or unparseable URL structure"),
                recommendation = "Do not open. Malformed links are commonly used to exploit handler bugs."
            )
        }

        val host = parsed.host.lowercase().trimEnd('.')
        val path = (parsed.rawPath ?: "").lowercase()
        val query = (parsed.rawQuery ?: "").lowercase()
        val scheme = parsed.scheme.lowercase()

        // 1. Known threat intelligence host check (highest confidence)
        if (knownThreatLookup != null) {
            var suffix: String? = host
            while (suffix != null) {
                if (knownThreatLookup.isKnownThreatHost(suffix)) {
                    findings.add("Known malicious host: $suffix (active malware/phishing blocklist match)")
                    score += 85
                    break
                }
                suffix = if (suffix.contains('.')) suffix.substringAfter('.') else null
            }
        }

        // 2. Credential leakage in URL
        if (!parsed.userInfo.isNullOrBlank()) {
            findings.add("Credentials embedded in URL (user:pass@host pattern)")
            score += 30
        }

        // 3. Punycode / homoglyph IDN abuse
        if (host.contains("xn--")) {
            val brandHit = BRAND_NAMES.firstOrNull { host.contains(it) || decodePunycodeContainsBrand(host, it) }
            if (brandHit != null) {
                findings.add("Punycode IDN host imitating brand '$brandHit' (homoglyph spoofing)")
                score += 45
            } else {
                findings.add("Punycode internationalized hostname (xn--) — verify legitimacy")
                score += 15
            }
        }

        // 4. Raw IP literal host
        if (host.matches(RAW_IPV4_REGEX) || (host.startsWith("[") && host.endsWith("]"))) {
            findings.add("Raw IP address host instead of a domain name")
            score += 25
            if (path.contains("login") || path.contains("verify") || path.contains("secure") || query.contains("token")) {
                findings.add("IP host serving credential/login-style content — classic phishing setup")
                score += 30
            }
        }

        // 5. Brand typosquatting
        val registrableDomain = registrableSuffixOf(host)
        for (brand in BRAND_NAMES) {
            if (registrableDomain == brand) break
            val base = registrableDomain.substringBeforeLast('.')
            if (base != brand && damerauLevenshteinDistance(base, brand) <= 2 && base.length >= brand.length - 1) {
                findings.add("Possible typosquat of '$brand': '$registrableDomain'")
                score += 40
                break
            }
            if (base != brand && base.contains(brand) && base != "$brand" && registrableDomain !in BRAND_CANONICAL_DOMAINS) {
                val tld = registrableDomain.substringAfterLast('.')
                if (tld in HIGH_RISK_TLDS || tld !in COMMON_LEGIT_TLDS) {
                    findings.add("Brand name '$brand' embedded in unrelated domain '$registrableDomain'")
                    score += 25
                    break
                }
            }
        }

        // 6. High-risk TLD
        val tld = registrableDomain.substringAfterLast('.', "")
        if (tld in HIGH_RISK_TLDS) {
            findings.add("High-abuse TLD '.$tld' — disproportionately used in malware and phishing campaigns")
            score += 15
        }

        // 7. URL shortener / redirect chain
        if (registrableDomain in URL_SHORTENERS) {
            findings.add("URL shortener redirects hide the true destination")
            score += 12
        }

        // 8. Phishing content heuristics
        var phishingKeywords = 0
        for (kw in PHISHING_KEYWORDS) {
            if (path.contains(kw) || query.contains(kw)) phishingKeywords++
        }
        if (phishingKeywords >= 2) {
            findings.add("Credential-harvesting language pattern ($phishingKeywords phishing keywords in path)")
            score += 25
        } else if (phishingKeywords == 1 && (scheme != "https")) {
            findings.add("Credential-style page served without HTTPS encryption")
            score += 20
        }
        if (host.count { it == '-' } >= 3 && host.contains("secure") || host.contains("-login") || host.contains("login-")) {
            findings.add("Hyphenated spoof-pattern hostname (secure-login style)")
            score += 10
        }
        if (host.split('.').size >= 5) {
            findings.add("Excessive subdomain depth (frequently used to bury the real registrable domain)")
            score += 8
        }

        val level = when {
            score >= 60 -> ThreatLevel.DANGEROUS
            score >= 20 -> ThreatLevel.SUSPICIOUS
            else -> ThreatLevel.CLEAN
        }
        val recommendation = when (level) {
            ThreatLevel.DANGEROUS -> "BLOCKED category threat profile. Do not open this link. Report and delete the message that contained it."
            ThreatLevel.SUSPICIOUS -> "Open with extreme caution only if you independently trust the sender; prefer typing the official domain manually."
            ThreatLevel.CLEAN -> "No threat indicators found by local static analysis. Exercise normal caution."
        }

        return UrlVerdict(
            inputUrl = trimmed,
            normalizedHost = host,
            threatLevel = level,
            riskScore = score.coerceAtMost(100),
            findings = findings,
            recommendation = recommendation
        )
    }

    private fun decodePunycodeContainsBrand(host: String, brand: String): Boolean {
        // Lightweight defense: brands written in ASCII inside punycode labels (common sloppy spoofing).
        return host.split('.').any { label -> label.startsWith("xn--") && label.drop(4).contains(brand.take(4)) }
    }

    private fun registrableSuffixOf(host: String): String {
        val parts = host.split('.').filter { it.isNotBlank() }
        if (parts.size <= 2) return host
        val twoLevelTlds = setOf("co.uk", "com.au", "co.id", "co.jp", "com.br", "co.in", "com.mx", "co.za", "com.ph", "co.kr")
        val lastTwo = parts.takeLast(2).joinToString(".")
        return if (lastTwo in twoLevelTlds && parts.size >= 3) parts.takeLast(3).joinToString(".") else lastTwo
    }

    /** Damerau-Levenshtein (optimal string alignment) distance. */
    private fun damerauLevenshteinDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                d[i][j] = minOf(
                    d[i - 1][j] + 1,
                    d[i][j - 1] + 1,
                    d[i - 1][j - 1] + cost
                )
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
                }
            }
        }
        return d[a.length][b.length]
    }

    companion object {
        private val RAW_IPV4_REGEX = Regex("^(\\d{1,3}\\.){3}\\d{1,3}$")

        /** Top targeted brands for phishing typosquat detection. */
        val BRAND_NAMES = setOf(
            "google", "gmail", "facebook", "instagram", "whatsapp", "amazon", "apple", "icloud",
            "microsoft", "outlook", "office365", "netflix", "paypal", "visa", "mastercard",
            "binance", "coinbase", "metamask", "revolut", "wise", "dhl", "fedex", "ups",
            "steam", "roblox", "minecraft", "epicgames", "playstation", "xbox", "nintendo",
            "gcash", "maya", "bpi", "bdo", "unionbank", "gcashin", "loadcentral",
            "mobilelegends", "tiktok", "shopee", "lazada", "garena", "riotgames"
        )

        /** Canonical official domains that legitimately contain a brand string. */
        val BRAND_CANONICAL_DOMAINS = setOf(
            "google.com", "gmail.com", "facebook.com", "instagram.com", "whatsapp.com",
            "amazon.com", "apple.com", "icloud.com", "microsoft.com", "outlook.com",
            "office.com", "netflix.com", "paypal.com", "visa.com", "mastercard.com",
            "binance.com", "coinbase.com", "metamask.io", "revolut.com", "wise.com",
            "dhl.com", "fedex.com", "ups.com", "steampowered.com", "roblox.com",
            "minecraft.net", "epicgames.com", "playstation.com", "xbox.com", "nintendo.com",
            "tiktok.com", "shopee.com", "shopee.ph", "lazada.com", "lazada.com.ph",
            "garena.com", "riotgames.com", "mobilelegends.com"
        )

        /** TLDs with documented high abuse rates (heuristic weight only). */
        val HIGH_RISK_TLDS = setOf(
            "zip", "mov", "top", "xyz", "click", "country", "kim", "work", "loan", "men",
            "mom", "party", "review", "stream", "surf", "cam", "rest", "fit", "icu",
            "gq", "cf", "tk", "ml", "ga", "buzz", "monster", "quest", "cfd", "sbs"
        )

        private val COMMON_LEGIT_TLDS = setOf(
            "com", "org", "net", "edu", "gov", "mil", "int", "io", "co", "app", "dev",
            "ph", "us", "uk", "ca", "de", "fr", "jp", "au", "in", "br", "id", "sg", "my",
            "tv", "me", "ai", "info", "biz", "name", "pro", "shop", "site", "online", "store"
        )

        private val URL_SHORTENERS = setOf(
            "bit.ly", "tinyurl.com", "t.co", "goo.gl", "ow.ly", "is.gd", "buff.ly",
            "cutt.ly", "rebrand.ly", "shorturl.at", "rb.gy", "tiny.cc", "shorte.st",
            "adf.ly", "bc.vc", "ouo.io", "linktr.ee", "s.id", "shp.ee"
        )

        private val PHISHING_KEYWORDS = listOf(
            "verify-account", "verifyaccount", "account-verify", "login-verify",
            "secure-login", "account-suspended", "accountlocked", "account-locked",
            "update-payment", "updatebilling", "billing-update", "confirm-identity",
            "signin-confirm", "unlock-account", "recover-account", "password-reset-verify",
            "wallet-connect", "seed-phrase", "claim-reward", "free-gems", "free-diamonds"
        )
    }
}
