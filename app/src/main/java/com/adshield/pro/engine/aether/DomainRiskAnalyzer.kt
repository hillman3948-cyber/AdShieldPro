package com.adshield.pro.engine.aether

/**
 * Aether AI — domain risk heuristics (pure Kotlin, unit-testable).
 *
 * These are the signals a phishing domain usually leaks: a raw IP host, punycode homographs, a
 * brand name embedded in someone else's domain, credential-bait paths, many labels, an
 * executable-looking TLD. Each heuristic is weak on its own, so the engine reports them as
 * SUSPECTED evidence only — never as VERIFIED malware.
 *
 * Findings accumulate; the score saturates at 100. Nothing here performs network lookups: the
 * analyzer sees only the string it is given, which is why it can run on the DNS hot path.
 */
object DomainRiskAnalyzer {

    data class Finding(val code: String, val explanation: String, val weight: Int)

    private val SAFE_BRANDS = setOf(
        "google", "facebook", "instagram", "whatsapp", "youtube", "apple", "microsoft",
        "amazon", "netflix", "paypal", "binance", "gcash", "maya", "bdo", "bpi", "grab",
        "spotify", "tiktok", "telegram", "twitter", "x", "gmail", "outlook", "yahoo"
    )

    private val SUSPICIOUS_TLDS = setOf(
        "zip", "mov", "tk", "ml", "ga", "cf", "gq", "top", "work", "click", "link",
        "rest", "buzz", "cam", "sbs", "icu", "cyou", "quest", "lol", "mom"
    )

    private val EXECUTABLE_SUFFIXES = setOf("apk", "exe", "scr", "bat", "cmd", "msi", "jar", "vbs", "js")

    private val CREDENTIAL_WORDS = listOf(
        "login", "signin", "verify", "account", "update", "secure", "banking", "wallet",
        "password", "confirm", "unlock", "recovery", "billing", "invoice", "kyc"
    )

    private val SHORTENERS = setOf(
        "bit.ly", "tinyurl.com", "t.co", "goo.gl", "is.gd", "ow.ly", "buff.ly", "cutt.ly", "rb.gy"
    )

    /** Host portion of a URL or a bare host, lower-cased, without a trailing dot. */
    fun hostOf(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val withoutScheme = trimmed.substringAfter("://", trimmed)
        val hostPart = withoutScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        return hostPart.substringAfterLast('@').trimEnd('.').lowercase()
    }

    fun analyze(rawTarget: String): List<Finding> {
        val host = hostOf(rawTarget)
        if (host.isEmpty()) return listOf(Finding("empty", "No host could be read from the input", 0))
        val findings = ArrayList<Finding>()
        val labels = host.split('.').filter { it.isNotEmpty() }
        val tld = labels.lastOrNull().orEmpty()

        if (isIpLiteral(host)) {
            findings += Finding("ip-host", "The address is a raw IP, not a domain name", 30)
        }
        if (host.startsWith("xn--") || labels.any { it.startsWith("xn--") }) {
            findings += Finding("punycode", "Punycode labels can visually imitate another domain", 22)
        }
        if (labels.size >= 5) {
            findings += Finding("deep-subdomain", "Unusually deep sub-domain chain (${labels.size} labels)", 12)
        }
        if (host.length > 45) {
            findings += Finding("long-host", "Very long host name (${host.length} characters)", 8)
        }
        if (host.count { it == '-' } >= 3) {
            findings += Finding("many-hyphens", "Several hyphens, typical of look-alike domains", 10)
        }
        if (tld in SUSPICIOUS_TLDS) {
            findings += Finding("risky-tld", "The .$tld zone is disproportionately used for abuse", 18)
        }
        if (tld in EXECUTABLE_SUFFIXES) {
            findings += Finding("executable-tld", "A .$tld suffix often hosts dropped installers", 20)
        }
        // Brand embedded in a domain that is not the brand's own domain.
        val matchedBrand = SAFE_BRANDS.firstOrNull { brand ->
            labels.any { it.contains(brand) } && !isBrandDomain(host, brand)
        }
        if (matchedBrand != null) {
            findings += Finding(
                "brand-lookalike",
                "Mentions \"$matchedBrand\" but is not a $matchedBrand domain",
                25
            )
        }
        val lowerTarget = rawTarget.lowercase()
        val credentialWord = CREDENTIAL_WORDS.firstOrNull { lowerTarget.contains(it) }
        if (credentialWord != null) {
            findings += Finding("credential-bait", "Path or query contains \"$credentialWord\", a common phishing lure", 15)
        }
        if (host in SHORTENERS) {
            findings += Finding("shortener", "Link shorteners hide the real destination", 10)
        }
        val scheme = rawTarget.substringBefore("://", "").lowercase()
        if (scheme == "http") {
            findings += Finding("plain-http", "Served over plain HTTP with no transport encryption", 10)
        }
        if (rawTarget.length > 180) {
            findings += Finding("long-url", "Unusually long URL (${rawTarget.length} characters)", 6)
        }
        return findings
    }

    /** Total heuristic weight, saturated at 100. */
    fun riskScore(findings: List<Finding>): Int = findings.sumOf { it.weight }.coerceIn(0, 100)

    private fun isIpLiteral(host: String): Boolean {
        if (host.isEmpty()) return false
        if (host.contains(':')) return true // IPv6 literal (any colon form)
        val parts = host.split('.')
        if (parts.size != 4) return false
        return parts.all { part -> part.isNotEmpty() && part.all { it.isDigit() } && part.toIntOrNull() in 0..255 }
    }

    /** True when the host is the brand's own domain or a subdomain of it. */
    private fun isBrandDomain(host: String, brand: String): Boolean {
        val labels = host.split('.').filter { it.isNotEmpty() }
        for (index in labels.indices) {
            val candidate = labels.subList(index, labels.size).joinToString(".")
            if (candidate == "$brand.com" || candidate == "$brand.net" || candidate == "$brand.org" ||
                candidate == "$brand.io" || candidate == "$brand.co" || candidate == "$brand.app" ||
                candidate == "$brand.ph" || candidate == "$brand.tv" || candidate == "$brand.me"
            ) return true
        }
        return false
    }
}
