package com.adshield.pro.aether

import kotlin.math.ln
import kotlin.math.min

/**
 * AETHER AI on-device, explainable detection engine (pure Kotlin, JVM-testable).
 *
 * It combines a bundled subset of the SAME signed real threat intelligence used
 * by the AETHER backend with deterministic, transparent heuristics. Every
 * finding carries its rule id, weight, source, timestamp, confidence and
 * evidence. Only CONFIRMED intelligence (or an explicitly strict privacy level)
 * can auto-block; suspicious heuristics are reported, never silently blocked.
 *
 * No network calls, no file execution, no offensive actions, no opaque model.
 */
class AetherAnalyzer(
    private val threatBaseline: Map<String, AetherBaselineEntry>,
    private val privacyBaseline: Map<String, AetherBaselineEntry>,
    private val allowlist: Set<String>
) {
    companion object {
        const val RULESET_VERSION = "aether-android-1.0.0"

        private val SUSPICIOUS_TLDS = setOf(
            "tk", "ml", "ga", "cf", "gq", "top", "xyz", "zip", "mov", "click",
            "link", "download", "work", "loan", "review", "party", "gdn", "pw"
        )
        private val PHISHING_KEYWORDS = listOf(
            "login", "signin", "verify", "secure", "account", "update", "confirm",
            "bank", "wallet", "password", "billing", "invoice", "recover", "otp"
        )
        private val BRANDS = listOf(
            "paypal", "google", "gmail", "microsoft", "outlook", "apple", "icloud",
            "amazon", "facebook", "instagram", "whatsapp", "netflix", "github",
            "bpi", "gcash", "maya", "coinbase", "binance", "steam", "discord"
        )
        private val CONFUSABLES = mapOf('0' to 'o', '1' to 'l', '3' to 'e', '4' to 'a', '5' to 's')

        fun normalize(raw: String): String {
            var s = raw.trim().lowercase()
            if (s.contains("://")) s = s.substringAfter("://")
            if (s.contains("@")) s = s.substringAfterLast("@")
            s = s.substringBefore("/").substringBefore("?").substringBefore("#")
            if (s.contains(":")) s = s.substringBefore(":")
            s = s.trim().trimEnd('.')
            return runCatching { java.net.IDN.toASCII(s, java.net.IDN.ALLOW_UNASSIGNED) }.getOrDefault(s)
        }

        fun suffixes(domain: String): List<String> {
            val parts = domain.split('.')
            val out = ArrayList<String>(parts.size)
            for (i in 0 until parts.size - 1) {
                out.add(parts.subList(i, parts.size).joinToString("."))
            }
            return out
        }

        fun entropy(text: String): Double {
            if (text.isEmpty()) return 0.0
            val counts = HashMap<Char, Int>()
            for (c in text) counts[c] = (counts[c] ?: 0) + 1
            var result = 0.0
            for (count in counts.values) {
                val p = count.toDouble() / text.length
                result -= p * (ln(p) / ln(2.0))
            }
            return result
        }

        fun levenshteinBounded(a: String, b: String, maxDistance: Int): Int {
            if (kotlin.math.abs(a.length - b.length) > maxDistance) return maxDistance + 1
            var prev = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val cur = IntArray(b.length + 1)
                cur[0] = i
                var rowMin = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    cur[j] = min(min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost)
                    rowMin = min(rowMin, cur[j])
                }
                if (rowMin > maxDistance) return maxDistance + 1
                prev = cur
            }
            return prev[b.length]
        }

        fun skeleton(text: String): String = buildString {
            for (c in text.lowercase()) append(CONFUSABLES[c] ?: c)
        }

        fun registrable(domain: String): String {
            val parts = domain.split('.')
            return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else domain
        }

        fun failSafe(raw: String): AetherVerdict = AetherVerdict(
            domain = normalize(raw),
            classification = "engine_error",
            severity = AetherSeverity.NONE,
            risk = 0,
            confidence = 0.0,
            confirmed = false,
            shouldBlock = false,
            reasonCode = "fail_safe_engine_error_allow_dns",
            signals = emptyList(),
            recommendations = listOf("DNS resolution was allowed so connectivity is preserved."),
            explanations = mapOf(
                "beginner" to "We could not analyze this name, so we did not block it.",
                "intermediate" to "Analysis error; fail-open DNS applied to preserve connectivity.",
                "professional" to "Engine exception: fail-open DNS. The query is logged for review."
            ),
            rulesetVersion = RULESET_VERSION,
            failSafe = true
        )
    }

    fun analyze(raw: String, settings: AetherSettings, temporaryAllow: Boolean = false): AetherVerdict {
        val domain = normalize(raw)
        if (domain.isEmpty()) return verdictClean(domain, "unknown", emptyList(), settings, temporaryAllow)
        val now = System.currentTimeMillis().toString()
        val signals = ArrayList<AetherSignal>()
        val suffixes = suffixes(domain)
        val knownGood = allowlist.any { good -> domain == good || domain.endsWith(".$good") }

        // ---- verified third-party intelligence (bundled signed-feed subset) ----
        for (suffix in suffixes) {
            val entry = threatBaseline[suffix]
            if (entry != null) {
                signals.add(
                    AetherSignal(
                        id = "intel_confirmed",
                        detail = "'$domain' is listed as ${entry.category} by ${entry.source}.",
                        weight = 95,
                        source = "intel:${entry.source}",
                        observedAt = entry.observedAt,
                        confidence = entry.confidence,
                        evidence = entry.evidence,
                        verifiedIntel = true
                    )
                )
                break
            }
        }
        var privacyCategory = ""
        for (suffix in suffixes) {
            val entry = privacyBaseline[suffix]
            if (entry != null) {
                privacyCategory = entry.category
                signals.add(
                    AetherSignal(
                        id = if (entry.category == "url_shortener") "url_shortener" else "privacy_intel",
                        detail = "Known ${entry.category} endpoint '${entry.domain}'.",
                        weight = 38,
                        source = "intel:${entry.source}",
                        observedAt = entry.observedAt,
                        confidence = entry.confidence,
                        evidence = entry.evidence,
                        verifiedIntel = true
                    )
                )
                break
            }
        }

        // ---- deterministic heuristics -------------------------------------
        val labels = domain.split('.')
        val tld = labels.lastOrNull() ?: ""
        if (tld in SUSPICIOUS_TLDS) {
            signals.add(heuristic("suspicious_tld", "Top-level domain '.$tld' is frequently abused (not proof).", 26))
        }
        if (labels.size > 5) signals.add(heuristic("excessive_labels", "${labels.size} hostname labels is unusual.", 18))
        if (domain.contains("xn--")) signals.add(heuristic("homograph", "Punycode IDN hostname — check each character carefully.", 45))
        val longest = labels.maxByOrNull { it.length } ?: ""
        if (longest.length >= 12 && entropy(longest) >= 3.55) {
            val entropyText = "%.2f".format(entropy(longest))
            signals.add(heuristic("dga_label", "Hostname label '$longest' looks machine-generated (entropy $entropyText).", 45))
        }
        val reg = registrable(domain)
        val base = reg.substringBefore(".")
        for (brand in BRANDS) {
            if (domain.contains(brand) && !base.contains(brand) && !knownGood) {
                signals.add(heuristic("brand_in_subdomain", "Brand '$brand' appears in a subdomain of '$reg'.", 62))
                break
            }
        }
        if (PHISHING_KEYWORDS.any { domain.contains(it) }) {
            signals.add(heuristic("phishing_keywords", "Credential-related word in the hostname.", 20))
        }
        if (!knownGood && domain.contains(".")) {
            val rawLabel = labels.first()
            val firstLabel = skeleton(rawLabel)
            for (good in allowlist) {
                val goodLabel = skeleton(good.substringBefore("."))
                if (goodLabel.length < 4 || rawLabel == goodLabel) continue
                // Compare the ORIGINAL label: skeleton() maps '1'->'l', so
                // comparing skeletons alone would make "paypa1" == "paypal"
                // and silently miss the confusable typosquat.
                val oneEdit = levenshteinBounded(rawLabel, goodLabel, 1) <= 1
                val confusable = firstLabel == goodLabel && rawLabel != goodLabel
                if (oneEdit || confusable) {
                    signals.add(heuristic("typosquat", "'$domain' is one character away from known service '$good'.", 68))
                    break
                }
            }
        }

        // ---- aggregate ----------------------------------------------------
        val sorted = signals.sortedByDescending { it.weight }
        val maxWeight = sorted.firstOrNull()?.weight ?: 0
        var bonus = 0.0
        for (signal in sorted.drop(1).take(4)) bonus += 0.3 * min(40, signal.weight)
        var risk = min(100, (maxWeight + bonus).toInt())
        if (knownGood && sorted.none { it.verifiedIntel }) risk = kotlin.math.max(0, risk - 30)

        val confirmed = sorted.any { it.verifiedIntel && it.id == "intel_confirmed" }
        val heuristicCount = sorted.count { !it.verifiedIntel }
        val suspicious = !confirmed && risk >= 55 && (heuristicCount >= 2 || maxWeight >= 60)
        val privacyRisk = !confirmed && !suspicious && privacyCategory.isNotEmpty() &&
            sorted.all { it.id == "privacy_intel" || it.id == "url_shortener" }

        val classification = when {
            confirmed -> "confirmed_" + (sorted.firstOrNull { it.verifiedIntel && it.id == "intel_confirmed" }
                ?.detail?.substringAfter("listed as ")?.substringBefore(" by") ?: "malware")
            suspicious -> "suspicious"
            privacyRisk -> "privacy_risk"
            knownGood -> "benign"
            else -> "unknown"
        }
        val severity = when {
            confirmed -> AetherSeverity.HIGH
            suspicious -> if (risk >= 80) AetherSeverity.HIGH else AetherSeverity.MEDIUM
            privacyRisk -> AetherSeverity.LOW
            else -> AetherSeverity.NONE
        }
        val confidence = if (confirmed) 0.92 else if (suspicious) min(0.8, 0.55 + 0.05 * heuristicCount) else 0.35

        val shouldBlock = settings.enabled && !temporaryAllow && (
            (confirmed && settings.autoBlockConfirmed) ||
                (privacyRisk && settings.privacyLevel == "strict")
            )
        val reasonCode = when {
            temporaryAllow -> "approved_temporary_allowlist"
            !settings.enabled -> "aether_disabled"
            confirmed && settings.autoBlockConfirmed -> "confirmed_intelligence"
            privacyRisk && settings.privacyLevel == "strict" -> "privacy_level_strict"
            suspicious -> "suspicious_flagged_not_blocked"
            else -> "no_confirmed_threat"
        }
        return AetherVerdict(
            domain = domain,
            classification = classification,
            severity = severity,
            risk = risk,
            confidence = confidence,
            confirmed = confirmed,
            shouldBlock = shouldBlock,
            reasonCode = reasonCode,
            signals = sorted,
            recommendations = recommendations(classification),
            explanations = explanations(classification, domain, sorted, risk),
            rulesetVersion = RULESET_VERSION
        )
    }

    private fun heuristic(id: String, detail: String, weight: Int) = AetherSignal(
        id = id,
        detail = detail,
        weight = weight,
        source = "local-heuristic:$RULESET_VERSION",
        observedAt = System.currentTimeMillis().toString(),
        confidence = 0.6,
        evidence = "deterministic on-device rule; no third-party confirmation",
        verifiedIntel = false
    )

    private fun verdictClean(
        domain: String, classification: String, signals: List<AetherSignal>,
        settings: AetherSettings, temporaryAllow: Boolean
    ) = AetherVerdict(
        domain = domain, classification = classification, severity = AetherSeverity.NONE,
        risk = 0, confidence = 0.35, confirmed = false, shouldBlock = false,
        reasonCode = "no_confirmed_threat", signals = signals,
        recommendations = listOf("No action required. Keep protection enabled."),
        explanations = explanations(classification, domain, signals, 0),
        rulesetVersion = RULESET_VERSION
    )

    private fun recommendations(classification: String): List<String> = when {
        classification.startsWith("confirmed_") -> listOf(
            "Do not open this destination or enter credentials.",
            "If an app breaks, request an approved, expiring temporary allowlist entry.",
            "Report a false positive from the AETHER screen so it can be reviewed."
        )
        classification == "suspicious" -> listOf(
            "Verify the exact hostname through the official app or by typing the known domain.",
            "Heuristic findings are flagged, not blocked, to avoid false positives."
        )
        classification == "privacy_risk" -> listOf(
            "Choose your privacy level; strict mode blocks known tracker endpoints at DNS level."
        )
        else -> listOf("No confirmed threat found. Keep protection enabled and lists updated.")
    }

    private fun explanations(
        classification: String, domain: String, signals: List<AetherSignal>, risk: Int
    ): Map<String, String> {
        val top = signals.take(3).joinToString(", ") { it.id }.ifEmpty { "no signals" }
        val evidence = signals.firstOrNull { it.verifiedIntel }?.let {
            "${it.source} at ${it.observedAt} (confidence ${it.confidence})"
        } ?: "no third-party intelligence match"
        return when {
            classification.startsWith("confirmed_") -> mapOf(
                "beginner" to "This address is on a trusted security list. AETHER will block it. Do not visit it or type passwords there.",
                "intermediate" to "'$domain' matches a verified $classification indicator. Evidence: $evidence. You can request an approved, expiring temporary allowlist entry if an app breaks.",
                "professional" to "Confirmed $classification. Rules fired: $top. Risk=$risk. Evidence: $evidence. Heuristics are advisory; only verified intel auto-blocks."
            )
            classification == "suspicious" -> mapOf(
                "beginner" to "This address looks unusual and could be a trick, but we do not have proof. Check the exact address before signing in.",
                "intermediate" to "'$domain' triggered ${signals.size} heuristic signals ($top), risk $risk. No verified intelligence match, so it is flagged rather than blocked.",
                "professional" to "Suspicious (not confirmed). Rules: $top. Risk=$risk. Intel evidence: $evidence. Deterministic versioned rules; report false positives for review."
            )
            classification == "privacy_risk" -> mapOf(
                "beginner" to "This is a tracking or advertising address. It is about privacy, not viruses.",
                "intermediate" to "'$domain' is a known third-party tracker/ad endpoint. Your privacy level decides whether it is blocked.",
                "professional" to "Privacy classification only. Rules: $top. DNS-level blocking cannot see HTTPS bodies or first-party tracking."
            )
            else -> mapOf(
                "beginner" to "We did not find evidence that this address is dangerous. Keep protection on.",
                "intermediate" to "No confirmed intelligence match for '$domain' and no strong suspicious pattern (risk $risk). Absence of a match is not a guarantee of safety.",
                "professional" to "No confirmed threat. Rules: $top. Risk=$risk. Detection is limited to DNS-visible names and the bundled signed baseline; new threats may be missing."
            )
        }
    }
}
