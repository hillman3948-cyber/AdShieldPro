package com.adshield.pro.engine.browser

import android.webkit.WebResourceResponse
import com.adshield.pro.engine.filter.FilterRuleEngine
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Layer 3 — Privacy Browser Content & Cosmetic Filtering Engine.
 *
 * Provides:
 * 1. Subresource & navigation ad/tracker request interception via [FilterRuleEngine]
 * 2. Tracking parameter sanitizer (strips fbclid, gclid, utm_*, msclkid, ttclid, etc.)
 * 3. Per-website allowlist exceptions
 * 4. Cosmetic CSS + MutationObserver DOM element hiding for supported web pages
 *
 * Note: As disclosed in the UI and documentation, Android security sandboxing prevents
 * a non-root app from injecting cosmetic CSS into external third-party browsers; cosmetic
 * filtering operates inside AdShield Pro's built-in Privacy Browser.
 */
@Singleton
class BrowserContentBlocker @Inject constructor(
    private val filterRuleEngine: FilterRuleEngine
) {

    data class InterceptionDecision(
        val shouldBlock: Boolean,
        val reason: String,
        val matchedRule: String? = null
    )

    private val sessionBlockedSubresources = AtomicLong(0L)
    private val sessionPopupsBlocked = AtomicLong(0L)
    private val sessionTrackersStripped = AtomicLong(0L)

    fun getBlockedSubresourceCount(): Long = sessionBlockedSubresources.get()
    fun getBlockedPopupCount(): Long = sessionPopupsBlocked.get()
    fun getStrippedTrackerParamCount(): Long = sessionTrackersStripped.get()

    fun recordPopupBlocked() {
        sessionPopupsBlocked.incrementAndGet()
    }

    /**
     * Evaluates whether a WebView resource request should be blocked.
     */
    fun evaluateRequest(
        requestUrl: String,
        pageHost: String?,
        allowlistedSites: Set<String>
    ): InterceptionDecision {
        val uri = runCatching { URI(requestUrl) }.getOrNull()
            ?: return InterceptionDecision(false, "Malformed URI")
        val scheme = uri.scheme?.lowercase() ?: return InterceptionDecision(false, "No scheme")
        if (scheme != "http" && scheme != "https") {
            return InterceptionDecision(false, "Non-HTTP scheme")
        }

        val requestHost = uri.host?.lowercase()?.removePrefix("www.") ?: return InterceptionDecision(false, "No host")
        val normalizedPageHost = pageHost?.lowercase()?.removePrefix("www.")

        // Check if current website is allowlisted by the user
        if (normalizedPageHost != null && isHostInExceptionSet(normalizedPageHost, allowlistedSites)) {
            return InterceptionDecision(false, "Page allowlisted by user ($normalizedPageHost)")
        }

        // Evaluate request host against Layer 4 FilterRuleEngine
        val eval = filterRuleEngine.evaluate(requestHost)
        if (eval.isBlocked) {
            sessionBlockedSubresources.incrementAndGet()
            return InterceptionDecision(
                shouldBlock = true,
                reason = "Blocked by ${eval.sourceName}",
                matchedRule = eval.matchedRule
            )
        }

        // Evaluate URL path heuristics for known ad/tracker scripts when third-party
        val isThirdParty = normalizedPageHost != null &&
            !requestHost.endsWith(normalizedPageHost) &&
            !normalizedPageHost.endsWith(requestHost)

        val lowerPath = (uri.rawPath ?: "").lowercase()
        if (isThirdParty && THIRD_PARTY_AD_PATH_PATTERNS.any { lowerPath.contains(it) }) {
            sessionBlockedSubresources.incrementAndGet()
            return InterceptionDecision(
                shouldBlock = true,
                reason = "Third-party ad script path heuristic",
                matchedRule = lowerPath
            )
        }

        return InterceptionDecision(false, "Allowed")
    }

    /**
     * Strips known cross-site tracking query parameters (gclid, fbclid, utm_*, etc.) from URLs.
     */
    fun sanitizeTrackingParameters(rawUrl: String): String {
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return rawUrl
        val rawQuery = uri.rawQuery
        if (uri.isOpaque || rawQuery.isNullOrEmpty()) return rawUrl

        val pairs = rawQuery.split('&').filter { it.isNotEmpty() }
        val keptPairs = ArrayList<String>(pairs.size)
        var removedCount = 0L

        for (pair in pairs) {
            val paramName = pair.substringBefore('=').lowercase()
            if (paramName in TRACKING_QUERY_PARAMS ||
                paramName.startsWith("utm_") ||
                paramName.startsWith("hsa_")
            ) {
                removedCount++
            } else {
                keptPairs.add(pair)
            }
        }

        if (removedCount == 0L) return rawUrl
        sessionTrackersStripped.addAndGet(removedCount)

        val baseWithoutQueryAndFrag = rawUrl.substringBefore('?').substringBefore('#')
        val fragment = uri.rawFragment?.let { "#$it" } ?: ""
        return if (keptPairs.isEmpty()) {
            "$baseWithoutQueryAndFrag$fragment"
        } else {
            "$baseWithoutQueryAndFrag?${keptPairs.joinToString("&")}$fragment"
        }
    }

    /**
     * Creates an empty, harmless WebResourceResponse to substitute for blocked ad/tracker requests.
     */
    fun createEmptyBlockedResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            204,
            "Blocked by AdShield Pro",
            mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Cache-Control" to "no-store"
            ),
            ByteArrayInputStream(ByteArray(0))
        )
    }

    /**
     * Generates a safe JavaScript snippet that injects cosmetic CSS rules and a lightweight
     * MutationObserver to collapse ad placeholders and sponsored containers.
     */
    fun buildCosmeticFilterScript(
        pageHost: String?,
        allowlistedSites: Set<String>,
        customSelectors: Set<String>
    ): String? {
        val normalizedHost = pageHost?.lowercase()?.removePrefix("www.")
        if (normalizedHost != null && isHostInExceptionSet(normalizedHost, allowlistedSites)) {
            return null
        }

        val combinedSelectors = (DEFAULT_COSMETIC_SELECTORS + customSelectors)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.contains('<') && !it.contains('\'') && !it.contains('"') }
            .distinct()

        val cssRuleString = combinedSelectors.joinToString(", ") +
            " { display: none !important; visibility: hidden !important; height: 0 !important; min-height: 0 !important; pointer-events: none !important; }"

        val escapedCss = cssRuleString.replace("\\", "\\\\").replace("`", "\\`")

        return """
            (function() {
                if (window.__adshieldCosmeticApplied) return;
                window.__adshieldCosmeticApplied = true;
                const styleId = 'adshield-pro-cosmetic-style';
                function applyStyle() {
                    if (!document.getElementById(styleId) && document.head) {
                        const style = document.createElement('style');
                        style.id = styleId;
                        style.type = 'text/css';
                        style.appendChild(document.createTextNode(`$escapedCss`));
                        document.head.appendChild(style);
                    }
                }
                applyStyle();
                document.addEventListener('DOMContentLoaded', applyStyle);
            })();
        """.trimIndent()
    }

    fun isHostInExceptionSet(host: String, allowlistedSites: Set<String>): Boolean {
        val clean = host.lowercase().removePrefix("www.")
        return allowlistedSites.any { exc ->
            val cleanExc = exc.lowercase().removePrefix("www.")
            clean == cleanExc || clean.endsWith(".$cleanExc")
        }
    }

    companion object {
        private val TRACKING_QUERY_PARAMS = setOf(
            "fbclid",
            "gclid",
            "gclsrc",
            "dclid",
            "msclkid",
            "ttclid",
            "twclid",
            "yclid",
            "mc_cid",
            "mc_eid",
            "_ga",
            "_gl",
            "igshid",
            "vero_id",
            "rb_clickid"
        )

        private val THIRD_PARTY_AD_PATH_PATTERNS = listOf(
            "/pagead/",
            "/ads/ads.js",
            "/adserver/",
            "/prebid",
            "/gpt.js",
            "/show_ads.js",
            "/adsbygoogle.js"
        )

        val DEFAULT_COSMETIC_SELECTORS = listOf(
            "ins.adsbygoogle",
            "[id^=google_ads_iframe_]",
            "[id^=div-gpt-ad]",
            "[data-ad-slot]",
            "[data-ad-client]",
            "[data-google-query-id]",
            ".ad-banner",
            ".ad-container",
            ".ad-slot",
            ".ad-wrapper",
            ".advertisement-box",
            ".sponsored-banner",
            ".taboola-widget",
            ".outbrain-widget",
            "iframe[src*=doubleclick]",
            "iframe[src*=googlesyndication]",
            "iframe[src*=adnxs]",
            "iframe[src*=taboola]"
        )
    }
}
