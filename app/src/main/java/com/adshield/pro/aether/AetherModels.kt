package com.adshield.pro.aether

/**
 * AETHER AI on-device data models.
 *
 * These are plain Kotlin types with no Android dependencies so the detection
 * logic can be unit-tested on the JVM. Confirmed intelligence is kept separate
 * from "suspicious" heuristic findings.
 */
data class AetherBaselineEntry(
    val domain: String,
    val category: String,
    val source: String,
    val observedAt: String,
    val confidence: Double,
    val evidence: String = "bundled signed-feed baseline"
)

data class AetherSettings(
    val enabled: Boolean = true,
    val autoBlockConfirmed: Boolean = true,
    val privacyLevel: String = "balanced", // minimal | balanced | strict
    val explanationLevel: String = "beginner" // beginner | intermediate | professional
)

enum class AetherSeverity { NONE, LOW, MEDIUM, HIGH, CRITICAL }

data class AetherSignal(
    val id: String,
    val detail: String,
    val weight: Int,
    val source: String,
    val observedAt: String,
    val confidence: Double,
    val evidence: String,
    val verifiedIntel: Boolean
)

data class AetherVerdict(
    val domain: String,
    val classification: String,
    val severity: AetherSeverity,
    val risk: Int,
    val confidence: Double,
    val confirmed: Boolean,
    val shouldBlock: Boolean,
    val reasonCode: String,
    val signals: List<AetherSignal>,
    val recommendations: List<String>,
    val explanations: Map<String, String>,
    val rulesetVersion: String,
    val failSafe: Boolean = false
)

data class AetherAllowEntry(
    val domain: String,
    val expiresAtEpochMs: Long,
    val approvedBy: String,
    val reason: String,
    val minutes: Int
)

data class AetherReply(
    val answer: String,
    val sections: List<Pair<String, String>>,
    val injectionDetected: Boolean,
    val refused: Boolean,
    val verdict: AetherVerdict? = null
)
