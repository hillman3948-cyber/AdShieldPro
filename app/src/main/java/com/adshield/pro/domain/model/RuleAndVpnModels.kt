package com.adshield.pro.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class RuleType {
    BLOCK,
    ALLOW
}

@Serializable
enum class RuleMatchType {
    EXACT,
    SUBDOMAIN,
    WILDCARD
}

@Serializable
data class CustomRule(
    val id: Long = 0L,
    val rawPattern: String,
    val normalizedPattern: String,
    val ruleType: RuleType,
    val matchType: RuleMatchType,
    val category: RuleCategory = RuleCategory.CUSTOM,
    val priority: Int = if (ruleType == RuleType.ALLOW) 100 else 90,
    val isEnabled: Boolean = true,
    val comment: String = "",
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

@Serializable
data class FilterListSource(
    val id: String,
    val name: String,
    val description: String,
    val url: String,
    val licenseName: String,
    val category: RuleCategory,
    val isBuiltIn: Boolean,
    val isEnabled: Boolean,
    val isPremiumOnly: Boolean = false,
    val ruleCount: Int = 0,
    val duplicateCount: Int = 0,
    val lastUpdatedEpochMs: Long = 0L,
    val lastStatusMessage: String = "Bundled / Ready",
    val etagOrHash: String = ""
)

data class FilterEvaluationResult(
    val domain: String,
    val isBlocked: Boolean,
    val matchedRule: String?,
    val matchedCategory: RuleCategory?,
    val matchType: RuleMatchType?,
    val sourceName: String,
    val priority: Int,
    val evaluationTimeMicros: Long = 0L
)

data class RuleConflictReport(
    val conflictType: ConflictType,
    val primaryRule: CustomRule,
    val conflictingRule: CustomRule?,
    val conflictingFilterListDomain: String?,
    val explanation: String,
    val winningAction: RuleType
) {
    enum class ConflictType(val label: String) {
        EXACT_DUPLICATE("Exact Duplicate Rule"),
        ALLOW_OVERRIDES_BLOCK("Allowlist Overrides Block Rule"),
        SUBDOMAIN_SHADOWED_BY_PARENT("Subdomain Shadowed by Parent Rule"),
        WILDCARD_OVERLAP("Wildcard Overlap")
    }
}

enum class VpnStatus {
    DISCONNECTED,
    STARTING,
    CONNECTED,
    RECONNECTING,
    PAUSED,
    ERROR
}

data class VpnRuntimeState(
    val status: VpnStatus = VpnStatus.DISCONNECTED,
    val connectedSinceEpochMs: Long? = null,
    val activeMode: ProtectionMode = ProtectionMode.STANDARD,
    val resolverConfig: DnsResolverConfig = DnsResolverConfig(),
    val activeNetworkLabel: String = "Wi-Fi / Cellular",
    val sessionTotalQueries: Long = 0L,
    val sessionBlockedQueries: Long = 0L,
    val sessionAllowedQueries: Long = 0L,
    val sessionCachedQueries: Long = 0L,
    val averageLatencyMs: Int = 0,
    val loadedRuleCount: Int = 0,
    val lastError: String? = null
) {
    val blockingPercentage: Float
        get() = if (sessionTotalQueries > 0L) {
            (sessionBlockedQueries.toFloat() / sessionTotalQueries.toFloat()) * 100f
        } else 0f
}
