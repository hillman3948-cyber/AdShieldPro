package com.adshield.pro.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.adshield.pro.domain.model.DomainBlockCount
import com.adshield.pro.domain.model.SourceEffectiveness
import kotlinx.coroutines.flow.Flow

@Dao
interface AdShieldDao {

    // --- DNS Activity Logs ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDnsLog(log: DnsLogEntity)

    @Query("SELECT * FROM dns_logs ORDER BY timestamp_epoch_ms DESC LIMIT :limit")
    fun observeRecentLogs(limit: Int = 250): Flow<List<DnsLogEntity>>

    @Query("SELECT COUNT(*) FROM dns_logs")
    fun observeTotalLoggedCount(): Flow<Long>

    @Query("SELECT COUNT(*) FROM dns_logs WHERE is_blocked = 1")
    fun observeBlockedLoggedCount(): Flow<Long>

    @Query(
        """
        SELECT domain, category, COUNT(*) AS count 
        FROM dns_logs 
        WHERE is_blocked = 1 
        GROUP BY domain 
        ORDER BY count DESC 
        LIMIT :limit
        """
    )
    fun observeTopBlockedDomains(limit: Int = 15): Flow<List<DomainBlockCount>>

    @Query(
        """
        SELECT source_list AS sourceList, COUNT(*) AS blockedCount 
        FROM dns_logs 
        WHERE is_blocked = 1 
        GROUP BY source_list 
        ORDER BY blockedCount DESC
        """
    )
    fun observeSourceEffectiveness(): Flow<List<SourceEffectiveness>>

    @Query("DELETE FROM dns_logs WHERE timestamp_epoch_ms < :cutoffEpochMs")
    suspend fun pruneLogsOlderThan(cutoffEpochMs: Long): Int

    @Query("DELETE FROM dns_logs")
    suspend fun clearAllDnsLogs()

    // --- Aggregated Daily Stats ---
    @Query("SELECT * FROM daily_stats WHERE date_epoch_day >= :minEpochDay ORDER BY date_epoch_day ASC")
    fun observeDailyStatsSince(minEpochDay: Long): Flow<List<DailyStatEntity>>

    @Query("SELECT COALESCE(SUM(total_queries), 0) FROM daily_stats")
    fun observeAllTimeTotalQueries(): Flow<Long>

    @Query("SELECT COALESCE(SUM(blocked_queries), 0) FROM daily_stats")
    fun observeAllTimeBlockedQueries(): Flow<Long>

    @Query("SELECT COALESCE(SUM(allowed_queries), 0) FROM daily_stats")
    fun observeAllTimeAllowedQueries(): Flow<Long>

    @Query("SELECT * FROM daily_stats WHERE date_epoch_day = :epochDay LIMIT 1")
    suspend fun getDailyStatForDay(epochDay: Long): DailyStatEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDailyStat(stat: DailyStatEntity)

    @Query("DELETE FROM daily_stats WHERE date_epoch_day < :minEpochDay")
    suspend fun pruneDailyStatsOlderThan(minEpochDay: Long)

    @Query("DELETE FROM daily_stats")
    suspend fun clearAllDailyStats()

    // --- Custom Rules ---
    @Query("SELECT * FROM custom_rules ORDER BY priority DESC, created_at_epoch_ms DESC")
    fun observeCustomRules(): Flow<List<CustomRuleEntity>>

    @Query("SELECT * FROM custom_rules ORDER BY priority DESC, created_at_epoch_ms DESC")
    suspend fun getAllCustomRulesNow(): List<CustomRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCustomRule(rule: CustomRuleEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCustomRules(rules: List<CustomRuleEntity>)

    @Update
    suspend fun updateCustomRule(rule: CustomRuleEntity)

    @Query("DELETE FROM custom_rules WHERE id = :ruleId")
    suspend fun deleteCustomRuleById(ruleId: Long)

    @Query("DELETE FROM custom_rules")
    suspend fun clearAllCustomRules()

    // --- Filter List Sources ---
    @Query("SELECT * FROM filter_sources ORDER BY is_built_in DESC, name ASC")
    fun observeFilterSources(): Flow<List<FilterSourceEntity>>

    @Query("SELECT * FROM filter_sources ORDER BY is_built_in DESC, name ASC")
    suspend fun getAllFilterSourcesNow(): List<FilterSourceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFilterSources(sources: List<FilterSourceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFilterSource(source: FilterSourceEntity)

    @Query("DELETE FROM filter_sources WHERE id = :id AND is_built_in = 0")
    suspend fun deleteCustomFilterSource(id: String)
}
