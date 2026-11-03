package com.adshield.pro.aether

import android.content.Context
import com.adshield.pro.data.local.preferences.AppSettingsState
import com.adshield.pro.engine.filter.FilterRuleEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android integration point for AETHER AI.
 *
 * Loads the bundled subset of the SAME signed real threat feed used by the
 * AETHER backend (see aether/tools/build_feed.py), then provides on-device
 * analysis, assistant and education to the existing VpnService and Compose UI.
 * All analysis is local. Analysis errors always FAIL OPEN so DNS keeps working.
 */
@Singleton
class AetherThreatEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val analyzerRef = AtomicReference<AetherAnalyzer?>(null)
    private val infoRef = AtomicReference<Map<String, String>>(emptyMap())

    val education = AetherEducation()

    val analyzer: AetherAnalyzer
        get() = analyzerRef.get() ?: buildAnalyzer().also { analyzerRef.set(it) }

    private val assistant: AetherAssistant by lazy { AetherAssistant(analyzer) }

    fun baselineInfo(): Map<String, String> {
        if (infoRef.get().isEmpty()) buildAnalyzer()
        return infoRef.get()
    }

    private fun buildAnalyzer(): AetherAnalyzer {
        val threats = HashMap<String, AetherBaselineEntry>()
        val privacy = HashMap<String, AetherBaselineEntry>()
        var feedVersion = "unknown"
        var threatCount = 0
        var privacyCount = 0
        parseAsset("aether_threat_baseline.txt") { parts, version ->
            if (version.isNotBlank()) feedVersion = version
            if (parts.size >= 5) {
                val domain = parts[1].trim().lowercase()
                threats[domain] = AetherBaselineEntry(
                    domain = domain,
                    category = parts[0].trim(),
                    source = parts[2].trim(),
                    observedAt = parts[3].trim(),
                    confidence = parts[4].toDoubleOrNull() ?: 0.9,
                    evidence = "bundled signed-feed baseline ($feedVersion)"
                )
                threatCount++
            }
        }
        parseAsset("aether_privacy_baseline.txt") { parts, _ ->
            if (parts.size >= 5) {
                val domain = parts[1].trim().lowercase()
                privacy[domain] = AetherBaselineEntry(
                    domain = domain,
                    category = parts[0].trim(),
                    source = parts[2].trim(),
                    observedAt = parts[3].trim(),
                    confidence = parts[4].toDoubleOrNull() ?: 0.85,
                    evidence = "curated evidence-linked privacy baseline"
                )
                privacyCount++
            }
        }
        val allowlist = HashSet(FilterRuleEngine.DEFAULT_SAFE_ALLOWLIST)
        allowlist.addAll(POPULAR_SERVICES)
        infoRef.set(
            mapOf(
                "feed_version" to feedVersion,
                "threat_indicators" to threatCount.toString(),
                "privacy_indicators" to privacyCount.toString(),
                "ruleset_version" to AetherAnalyzer.RULESET_VERSION,
                "source" to "aether/intel/feed.json (Ed25519-signed; bundled subset for offline use)"
            )
        )
        return AetherAnalyzer(threats, privacy, allowlist)
    }

    private fun parseAsset(name: String, onLine: (List<String>, String) -> Unit) {
        runCatching {
            var version = ""
            context.assets.open(name).bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.startsWith("#")) {
                        val marker = "feed_version="
                        if (line.contains(marker)) {
                            version = line.substringAfter(marker).substringBefore(" ").trim()
                        }
                        return@forEach
                    }
                    if (line.isBlank()) return@forEach
                    onLine(line.split('|'), version)
                }
            }
        }
    }

    fun toSettings(state: AppSettingsState): AetherSettings = AetherSettings(
        enabled = state.aetherEnabled,
        autoBlockConfirmed = state.aetherAutoBlockConfirmed,
        privacyLevel = state.aetherPrivacyLevel,
        explanationLevel = state.aetherExplainLevel
    )

    /** Called for EVERY DNS query by AdShieldVpnService. */
    fun analyze(
        rawDomain: String,
        settings: AppSettingsState,
        policy: AetherPolicy?
    ): AetherVerdict = runCatching {
        val temporaryAllow = policy?.isTemporaryAllowed(rawDomain) ?: false
        val result = analyzer.analyze(rawDomain, toSettings(settings), temporaryAllow)
        // False positives verified by the user suppress auto-blocking.
        if (result.shouldBlock && policy != null && policy.isVerifiedFalsePositive(result.domain)) {
            result.copy(shouldBlock = false, reasonCode = "verified_false_positive")
        } else result
    }.getOrElse { AetherAnalyzer.failSafe(rawDomain) }

    fun ask(message: String, level: String): AetherReply = runCatching {
        assistant.respond(message, level)
    }.getOrElse {
        AetherReply(
            answer = "The assistant could not process that request. Protection is unaffected.",
            sections = listOf("Assistant error" to "Analysis failed safely; DNS protection is unchanged."),
            injectionDetected = false, refused = false
        )
    }

    companion object {
        private val POPULAR_SERVICES = setOf(
            "google.com", "gmail.com", "accounts.google.com", "play.google.com",
            "youtube.com", "gstatic.com", "googleapis.com", "googleusercontent.com",
            "microsoft.com", "login.microsoftonline.com", "outlook.com", "office.com",
            "apple.com", "icloud.com", "support.apple.com", "wikipedia.org", "github.com",
            "cloudflare.com", "mozilla.org", "android.com", "paypal.com", "amazon.com",
            "facebook.com", "instagram.com", "whatsapp.com", "signal.org", "proton.me",
            "chase.com", "bankofamerica.com", "wellsfargo.com", "bpi.com.ph", "gcash.com",
            "gov.ph", "who.int", "nist.gov", "cisa.gov", "example.com", "iana.org"
        )
    }
}
