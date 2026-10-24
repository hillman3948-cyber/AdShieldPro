package com.adshield.pro.domain.model

data class DnsActivityRecord(
    val id: Long = 0L,
    val timestampEpochMs: Long,
    val domain: String,
    val queryType: String,
    val isBlocked: Boolean,
    val category: RuleCategory?,
    val matchedRule: String?,
    val sourceList: String,
    val latencyMs: Int,
    val protocolUsed: String
)

data class DomainBlockCount(
    val domain: String,
    val category: String?,
    val count: Long
)

data class SourceEffectiveness(
    val sourceList: String,
    val blockedCount: Long
)

data class TimeBucketStat(
    val label: String,
    val startEpochMs: Long,
    val totalQueries: Long,
    val blockedQueries: Long,
    val allowedQueries: Long
)

data class AppExclusionInfo(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean,
    val isExcludedFromVpn: Boolean,
    val usesFirstPartyMultiplexedAds: Boolean,
    val compatibilityNote: String?
)
