package com.adshield.pro.data.worldmonitor

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WorldMonitorDao {

    @Query("SELECT * FROM world_entities ORDER BY eventTimeEpochMs DESC LIMIT :limit")
    fun observeEntities(limit: Int = 2500): Flow<List<WorldEntityRecord>>

    @Query("SELECT * FROM world_entities ORDER BY eventTimeEpochMs DESC LIMIT :limit")
    suspend fun getAllEntitiesSnapshot(limit: Int = 2500): List<WorldEntityRecord>

    @Query(
        """SELECT * FROM world_entities
           WHERE latitude BETWEEN :south AND :north
             AND longitude BETWEEN :west AND :east
           ORDER BY eventTimeEpochMs DESC
           LIMIT :limit"""
    )
    suspend fun getEntitiesInBox(
        south: Double,
        north: Double,
        west: Double,
        east: Double,
        limit: Int = 1200
    ): List<WorldEntityRecord>

    @Query(
        """SELECT * FROM world_entities
           WHERE title LIKE '%' || :query || '%'
              OR summary LIKE '%' || :query || '%'
              OR providerName LIKE '%' || :query || '%'
              OR subType LIKE '%' || :query || '%'
           ORDER BY eventTimeEpochMs DESC
           LIMIT :limit"""
    )
    suspend fun searchEntities(query: String, limit: Int = 100): List<WorldEntityRecord>

    @Query("SELECT * FROM world_entities WHERE id = :id LIMIT 1")
    suspend fun getEntityById(id: String): WorldEntityRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntities(records: List<WorldEntityRecord>)

    @Query("DELETE FROM world_entities WHERE providerId = :providerId")
    suspend fun deleteEntitiesForProvider(providerId: String)

    @Query("DELETE FROM world_entities WHERE expiresAtEpochMs < :cutoffEpochMs")
    suspend fun pruneExpiredEntities(cutoffEpochMs: Long)

    // ---------------- Provider State ----------------

    @Query("SELECT * FROM world_provider_state")
    fun observeProviderStates(): Flow<List<WorldProviderStateRecord>>

    @Query("SELECT * FROM world_provider_state")
    suspend fun getAllProviderStates(): List<WorldProviderStateRecord>

    @Query("SELECT * FROM world_provider_state WHERE providerId = :providerId LIMIT 1")
    suspend fun getProviderState(providerId: String): WorldProviderStateRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProviderState(record: WorldProviderStateRecord)

    // ---------------- Historical Movement Trails ----------------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTrailPoints(points: List<WorldEntityTrailPointRecord>)

    @Query(
        """SELECT * FROM world_entity_trails
           WHERE timestampEpochMs >= :sinceEpochMs
           ORDER BY entityId ASC, timestampEpochMs ASC
           LIMIT 3000"""
    )
    suspend fun getRecentTrailPoints(sinceEpochMs: Long): List<WorldEntityTrailPointRecord>

    @Query("DELETE FROM world_entity_trails WHERE timestampEpochMs < :cutoffEpochMs")
    suspend fun pruneOldTrailPoints(cutoffEpochMs: Long)

    // ---------------- Geocoding Cache ----------------

    @Query("SELECT * FROM world_geocode_cache WHERE queryKey = :queryKey AND expiresAtEpochMs > :nowEpochMs LIMIT 1")
    suspend fun getGeocodeCache(queryKey: String, nowEpochMs: Long): WorldGeocodeCacheRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putGeocodeCache(record: WorldGeocodeCacheRecord)

    @Query("DELETE FROM world_geocode_cache WHERE expiresAtEpochMs < :nowEpochMs")
    suspend fun pruneGeocodeCache(nowEpochMs: Long)

    // ---------------- Watchtower Regions & Alerts ----------------

    @Query("SELECT * FROM watchtower_regions ORDER BY createdAtEpochMs DESC")
    fun observeWatchRegions(): Flow<List<WatchtowerRegionRecord>>

    @Query("SELECT * FROM watchtower_regions WHERE enabled = 1")
    suspend fun getEnabledWatchRegions(): List<WatchtowerRegionRecord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWatchRegion(region: WatchtowerRegionRecord)

    @Query("DELETE FROM watchtower_regions WHERE id = :id")
    suspend fun deleteWatchRegion(id: String)

    @Query("SELECT * FROM watchtower_alerts ORDER BY triggeredAtEpochMs DESC LIMIT :limit")
    fun observeWatchAlerts(limit: Int = 200): Flow<List<WatchtowerAlertRecord>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertWatchAlerts(alerts: List<WatchtowerAlertRecord>): List<Long>

    @Query("DELETE FROM watchtower_alerts")
    suspend fun clearWatchAlerts()
}
