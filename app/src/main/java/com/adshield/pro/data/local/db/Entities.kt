package com.adshield.pro.data.local.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "dns_logs",
    indices = [
        Index(value = ["timestamp_epoch_ms"]),
        Index(value = ["domain"]),
        Index(value = ["is_blocked"])
    ]
)
data class DnsLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "timestamp_epoch_ms")
    val timestampEpochMs: Long,
    @ColumnInfo(name = "domain")
    val domain: String,
    @ColumnInfo(name = "query_type")
    val queryType: String,
    @ColumnInfo(name = "is_blocked")
    val isBlocked: Boolean,
    @ColumnInfo(name = "category")
    val category: String?,
    @ColumnInfo(name = "matched_rule")
    val matchedRule: String?,
    @ColumnInfo(name = "source_list")
    val sourceList: String,
    @ColumnInfo(name = "latency_ms")
    val latencyMs: Int,
    @ColumnInfo(name = "protocol_used")
    val protocolUsed: String
)

@Entity(
    tableName = "custom_rules",
    indices = [
        Index(value = ["normalized_pattern", "rule_type"], unique = false)
    ]
)
data class CustomRuleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "raw_pattern")
    val rawPattern: String,
    @ColumnInfo(name = "normalized_pattern")
    val normalizedPattern: String,
    @ColumnInfo(name = "rule_type")
    val ruleType: String,
    @ColumnInfo(name = "match_type")
    val matchType: String,
    @ColumnInfo(name = "category")
    val category: String,
    @ColumnInfo(name = "priority")
    val priority: Int,
    @ColumnInfo(name = "is_enabled")
    val isEnabled: Boolean,
    @ColumnInfo(name = "comment")
    val comment: String,
    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long
)

@Entity(tableName = "filter_sources")
data class FilterSourceEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "description")
    val description: String,
    @ColumnInfo(name = "url")
    val url: String,
    @ColumnInfo(name = "license_name")
    val licenseName: String,
    @ColumnInfo(name = "category")
    val category: String,
    @ColumnInfo(name = "is_built_in")
    val isBuiltIn: Boolean,
    @ColumnInfo(name = "is_enabled")
    val isEnabled: Boolean,
    @ColumnInfo(name = "is_premium_only")
    val isPremiumOnly: Boolean,
    @ColumnInfo(name = "rule_count")
    val ruleCount: Int,
    @ColumnInfo(name = "duplicate_count")
    val duplicateCount: Int,
    @ColumnInfo(name = "last_updated_epoch_ms")
    val lastUpdatedEpochMs: Long,
    @ColumnInfo(name = "last_status_message")
    val lastStatusMessage: String,
    @ColumnInfo(name = "etag_or_hash", defaultValue = "")
    val etagOrHash: String = ""
)

@Entity(tableName = "daily_stats")
data class DailyStatEntity(
    @PrimaryKey
    @ColumnInfo(name = "date_epoch_day")
    val dateEpochDay: Long,
    @ColumnInfo(name = "total_queries")
    val totalQueries: Long,
    @ColumnInfo(name = "blocked_queries")
    val blockedQueries: Long,
    @ColumnInfo(name = "allowed_queries")
    val allowedQueries: Long,
    @ColumnInfo(name = "cached_queries")
    val cachedQueries: Long
)
