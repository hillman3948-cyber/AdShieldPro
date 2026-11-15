package com.adshield.pro.engine.worldmonitor

import com.adshield.pro.domain.worldmonitor.AiAnalystResponse
import com.adshield.pro.domain.worldmonitor.CitedFact
import com.adshield.pro.domain.worldmonitor.CorrelatedIncident
import com.adshield.pro.domain.worldmonitor.FreshnessClass
import com.adshield.pro.domain.worldmonitor.ProviderSyncStatus
import com.adshield.pro.domain.worldmonitor.VerificationStatus
import com.adshield.pro.domain.worldmonitor.WorldEntity
import com.adshield.pro.domain.worldmonitor.WorldIntelCategory
import com.adshield.pro.domain.worldmonitor.WorldProviderRuntimeState
import com.adshield.pro.domain.worldmonitor.WorldSeverity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * On-Device Retrieval-Augmented AI Global Analyst (Section 3).
 *
 * Operates 100% locally without any mandatory cloud or paid AI service. Every generated analysis
 * strictly separates:
 *  1. Verified Observations (direct provider records with timestamps and source URLs)
 *  2. Uncertainties & Data Gaps (offline caches, coarse geolocations, unconfigured optional keys)
 *  3. Analytical Hypotheses (spatio-temporal correlations and non-certain exposure inferences)
 */
object WorldMonitorAiAnalyst {

    fun analyze(
        userQuery: String,
        allEntities: List<WorldEntity>,
        correlations: List<CorrelatedIncident>,
        providerStates: List<WorldProviderRuntimeState>,
        selectedEntity: WorldEntity? = null,
        focusLat: Double? = null,
        focusLon: Double? = null,
        nowEpochMs: Long = System.currentTimeMillis()
    ): AiAnalystResponse {
        val cleanQuery = userQuery.trim().ifBlank { "Summarize current global intelligence posture" }
        val nonSimulated = allEntities.filter { it.freshness != FreshnessClass.SIMULATED }

        // Retrieve relevant entities via keyword + spatial + selection scoring
        val tokens = cleanQuery.lowercase()
            .split(Regex("[^a-z0-9.+-]+"))
            .filter { it.length >= 2 && it !in STOP_WORDS }

        val ranked: List<WorldEntity> = if (selectedEntity != null &&
            (cleanQuery.contains("selected", true) || cleanQuery.contains("explain", true) || cleanQuery.contains(selectedEntity.title, true))
        ) {
            val neighbors = nonSimulated
                .filter { it.id != selectedEntity.id }
                .sortedBy {
                    GeoSpatialIndex.haversineKm(
                        selectedEntity.latitude,
                        selectedEntity.longitude,
                        it.latitude,
                        it.longitude
                    )
                }
                .take(8)
            listOf(selectedEntity) + neighbors
        } else if (tokens.isNotEmpty()) {
            val scored = nonSimulated.mapNotNull { entity ->
                val haystack = "${entity.title} ${entity.summary} ${entity.subType} ${entity.providerName} ${entity.category.label} ${entity.countryCode.orEmpty()}".lowercase()
                var score = tokens.count { tok -> haystack.contains(tok) } * 12
                if (score > 0) score += entity.severity.rank * 4
                if (focusLat != null && focusLon != null) {
                    val dist = GeoSpatialIndex.haversineKm(focusLat, focusLon, entity.latitude, entity.longitude)
                    if (dist < 600.0 && cleanQuery.contains("here", true) || cleanQuery.contains("region", true)) {
                        score += 10
                    }
                }
                if (score > 0) entity to score else null
            }.sortedByDescending { it.second }.map { it.first }
            scored.ifEmpty {
                nonSimulated.sortedWith(compareByDescending<WorldEntity> { it.severity.rank }.thenByDescending { it.eventTimeEpochMs }).take(12)
            }
        } else {
            nonSimulated.sortedWith(compareByDescending<WorldEntity> { it.severity.rank }.thenByDescending { it.eventTimeEpochMs }).take(12)
        }

        val topEvidence = ranked.take(10)

        // 1. Verified Observations (only facts directly backed by non-simulated records)
        val verifiedFacts = topEvidence.map { entity ->
            CitedFact(
                statement = "[${entity.category.shortCode}/${entity.severity.label}] ${entity.title} — ${entity.summary} (Accuracy: ${entity.geoAccuracy.label}, Verification: ${entity.verificationStatus.label})",
                provider = entity.providerName,
                timestampEpochMs = entity.eventTimeEpochMs,
                freshness = entity.freshness,
                sourceUrl = entity.sourceUrl
            )
        }

        // 2. Uncertainties & Data Gaps (computed honestly from actual provider states and metadata)
        val uncertainties = ArrayList<String>()
        if (nonSimulated.isEmpty()) {
            uncertainties += "Local intelligence database currently contains zero synced live records; run Sync Now when internet connectivity is available."
        }
        val delayedCount = topEvidence.count { it.freshness == FreshnessClass.DELAYED || it.freshness == FreshnessClass.HISTORICAL }
        if (delayedCount > 0) {
            uncertainties += "$delayedCount of ${topEvidence.size} retrieved item(s) are DELAYED or HISTORICAL/reference records rather than real-time sensor observations."
        }
        val coarseCount = topEvidence.count {
            it.geoAccuracy == com.adshield.pro.domain.worldmonitor.GeoAccuracy.ASN_COUNTRY_LEVEL ||
                it.geoAccuracy == com.adshield.pro.domain.worldmonitor.GeoAccuracy.ADMIN_CENTROID
        }
        if (coarseCount > 0) {
            uncertainties += "$coarseCount item(s) use ASN/Country or Administrative Centroid coordinates; their pin positions represent jurisdiction/network registration, not street-level locations."
        }
        val singleSourceNews = topEvidence.count { it.verificationStatus == VerificationStatus.UNVERIFIED_REPORT }
        if (singleSourceNews > 0) {
            uncertainties += "$singleSourceNews media item(s) come from single-source automated GDELT extraction and await multi-source or official agency corroboration."
        }
        val offlineOrRateLimited = providerStates.filter {
            it.status == ProviderSyncStatus.OFFLINE ||
                it.status == ProviderSyncStatus.RATE_LIMITED ||
                it.status == ProviderSyncStatus.ERROR
        }
        if (offlineOrRateLimited.isNotEmpty()) {
            uncertainties += "${offlineOrRateLimited.size} provider(s) reported offline/rate-limited status on last poll (${offlineOrRateLimited.take(3).joinToString { it.providerId }}); coverage in those feeds may be incomplete."
        }
        val unconfiguredKeyFeeds = listOf("NASA FIRMS raw VIIRS CSV", "AISStream global AIS").joinToString(", ")
        uncertainties += "Optional key-gated feeds ($unconfiguredKeyFeeds) only contribute raw records when a user-supplied free key is configured in Provider Settings."

        // 3. Analytical Hypotheses (strictly non-certain correlations & exposure assessments)
        val hypotheses = ArrayList<String>()
        val relevantCorrelations = if (selectedEntity != null) {
            correlations.filter { selectedEntity.id in it.entityIds }
        } else {
            correlations.take(4)
        }
        for (corr in relevantCorrelations) {
            hypotheses += "[HYPOTHESIS · ${corr.confidence}% confidence] ${corr.ruleTitle}: ${corr.summary} (${corr.caveat})"
        }
        if (selectedEntity != null) {
            val nearbyInfra = nonSimulated.filter {
                it.category == WorldIntelCategory.INFRASTRUCTURE &&
                    GeoSpatialIndex.haversineKm(selectedEntity.latitude, selectedEntity.longitude, it.latitude, it.longitude) <= 250.0
            }
            if (nearbyInfra.isNotEmpty() && selectedEntity.category == WorldIntelCategory.DISASTER) {
                hypotheses += "[EXPOSURE INFERENCE] ${nearbyInfra.size} reference infrastructure asset(s) (${nearbyInfra.take(3).joinToString { it.title }}) lie within 250 km of ${selectedEntity.title}; operational impact is NOT confirmed without local authority bulletins."
            }
        }
        if (hypotheses.isEmpty()) {
            hypotheses += "No high-confidence cross-domain causal link is asserted beyond the retrieved observations above."
        }

        // Multi-source corroboration summary
        val distinctProviders = topEvidence.map { it.providerId }.distinct()
        val officialCount = topEvidence.count { it.verificationStatus == VerificationStatus.OFFICIAL_AGENCY }
        val multiSourceSummary = "Retrieved ${topEvidence.size} record(s) across ${distinctProviders.size} independent provider(s); " +
            "$officialCount originate from official scientific/government agencies."

        val byCategoryCounts = nonSimulated.groupingBy { it.category }.eachCount()
        val criticalTotal = nonSimulated.count { it.severity == WorldSeverity.CRITICAL || it.severity == WorldSeverity.HIGH }
        val headline = if (selectedEntity != null && cleanQuery.contains("explain", true)) {
            "Dossier Analysis: ${selectedEntity.title} (${selectedEntity.providerName})"
        } else {
            "Global Intelligence Synthesis — ${nonSimulated.size} tracked entities ($criticalTotal High/Critical across ${byCategoryCounts.size} domains)"
        }

        val markdown = buildMarkdownBriefing(
            query = cleanQuery,
            headline = headline,
            nowEpochMs = nowEpochMs,
            multiSourceSummary = multiSourceSummary,
            verified = verifiedFacts,
            uncertainties = uncertainties,
            hypotheses = hypotheses
        )

        return AiAnalystResponse(
            query = cleanQuery,
            engineMode = "On-Device Deterministic Evidence RAG (Zero Cloud AI)",
            generatedAtEpochMs = nowEpochMs,
            headline = headline,
            verifiedObservations = verifiedFacts,
            uncertaintiesAndGaps = uncertainties,
            analyticalHypotheses = hypotheses,
            multiSourceSummary = multiSourceSummary,
            markdownReport = markdown
        )
    }

    private fun buildMarkdownBriefing(
        query: String,
        headline: String,
        nowEpochMs: Long,
        multiSourceSummary: String,
        verified: List<CitedFact>,
        uncertainties: List<String>,
        hypotheses: List<String>
    ): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return buildString {
            appendLine("# OMNISHIELD X — AI GLOBAL ANALYST BRIEFING")
            appendLine("- **Generated**: ${fmt.format(Date(nowEpochMs))}")
            appendLine("- **Query**: $query")
            appendLine("- **Synthesis**: $headline")
            appendLine("- **Corroboration**: $multiSourceSummary")
            appendLine()
            appendLine("## 1. Verified Observations")
            if (verified.isEmpty()) {
                appendLine("- None currently cached.")
            } else {
                verified.forEach { fact ->
                    appendLine("- **${fact.provider}** [${fact.freshness.label}]: ${fact.statement} (${fact.sourceUrl})")
                }
            }
            appendLine()
            appendLine("## 2. Uncertainties & Coverage Gaps")
            uncertainties.forEach { u -> appendLine("- $u") }
            appendLine()
            appendLine("## 3. Analytical Inferences & Hypotheses (Non-Certain)")
            hypotheses.forEach { h -> appendLine("- $h") }
        }
    }

    private val STOP_WORDS = setOf(
        "the", "and", "for", "with", "what", "where", "when", "how", "show", "tell",
        "about", "current", "global", "world", "are", "there", "any", "this", "that", "from"
    )
}
