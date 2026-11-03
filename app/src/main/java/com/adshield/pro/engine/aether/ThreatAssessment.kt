package com.adshield.pro.engine.aether

/**
 * Aether AI — evidence model.
 *
 * Every security decision in this app is expressed as an [Evidence] item with a named source, a
 * weight and a human-readable explanation, then combined into a [ThreatAssessment]. That makes
 * each verdict reproducible and auditable: a blocked domain can always be traced back to the list
 * entry, the rule, or the heuristic that produced it.
 *
 * The three verdict classes are deliberately blunt:
 *  - VERIFIED  — a curated blocklist/threat-intel match or an explicit user rule. Sufficient
 *                evidence to block without a doubt.
 *  - SUSPECTED — heuristic indicators only (young domain, punycode, credential bait, an app that
 *                trips several risk signals). Never auto-blocked on DNS alone; surfaced for review.
 *  - UNKNOWN   — not enough evidence either way. The default, and the most common answer.
 */

/** Where an evidence item came from. */
enum class EvidenceKind {
    /** Curated malware/phishing/threat-intelligence list entry. */
    THREAT_LIST,
    /** An ad/tracker list entry. */
    AD_LIST,
    /** A user allow rule. */
    USER_ALLOW,
    /** A user block rule. */
    USER_BLOCK,
    /** Built-in safety allowlist (banking, OS update, auth domains). */
    SAFE_ALLOWLIST,
    /** URL/domain heuristic such as punycode, IP-literal host, credential bait. */
    HEURISTIC,
    /** Installed-app permission or installer-signal risk. */
    APP_RISK,
    /** File signature / structural finding. */
    FILE_SIGNATURE,
    /** Behavioural observation, e.g. repeat blocks in a short window. */
    BEHAVIOUR
}

enum class ThreatVerdict { VERIFIED, SUSPECTED, UNKNOWN, ALLOWED }

enum class AetherAction {
    /** Answer the query with a sinkhole/NXDOMAIN. */
    BLOCK,
    /** Resolve normally and log. */
    ALLOW,
    /** Resolve normally, keep evidence, and ask the user to review. */
    ALLOW_AND_REVIEW
}

/**
 * @param confidence 0..100, how strongly this single item supports a verdict
 * @param explanation plain-language reason shown in the UI and in the decision log
 */
data class Evidence(
    val kind: EvidenceKind,
    val source: String,
    val confidence: Int,
    val explanation: String
) {
    init {
        require(confidence in 0..100) { "evidence confidence must be 0..100" }
    }
}

/**
 * Result of evaluating one domain/URL/app target.
 *
 * @param riskScore 0..100 aggregate risk, weighted per evidence kind (threat lists dominate)
 * @param confidence 0..100 how complete the evidence is (not how dangerous the target is)
 * @param autonomyNote what the autonomous engine is allowed to do with this verdict
 */
data class ThreatAssessment(
    val target: String,
    val verdict: ThreatVerdict,
    val action: AetherAction,
    val riskScore: Int,
    val confidence: Int,
    val evidence: List<Evidence>,
    val autonomyNote: String,
    val decidedAtEpochMs: Long
) {
    val isBlocked: Boolean get() = action == AetherAction.BLOCK
    val verdictLabel: String
        get() = when (verdict) {
            ThreatVerdict.VERIFIED -> "Verified threat"
            ThreatVerdict.SUSPECTED -> "Suspected — needs review"
            ThreatVerdict.UNKNOWN -> "Unknown — not enough evidence"
            ThreatVerdict.ALLOWED -> "Allowed"
        }

    /** One-line audit string, used by the decision log. */
    fun auditLine(): String =
        "$target | $verdict | ${action.name} | risk=$riskScore conf=$confidence | " +
            evidence.joinToString("; ") { "${it.kind}:${it.source}" }
}

/** A recorded security decision, for the decision log the directive requires. */
data class SecurityDecision(
    val id: Long,
    val timestampEpochMs: Long,
    val target: String,
    val verdict: ThreatVerdict,
    val action: AetherAction,
    val riskScore: Int,
    val confidence: Int,
    val reason: String,
    val automated: Boolean
)
