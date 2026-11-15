package com.adshield.pro.data.worldmonitor

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Isolated Room SQLite store (`world_monitor_2035.db`) for OMNISHIELD X INFINITY — WORLD MONITOR.
 *
 * Kept separate from [com.adshield.pro.data.local.db.AdShieldDatabase] so high-volume geospatial
 * tile/entity caching never contends with foreground DNS-VPN writes or alters existing app schemas.
 */
@Database(
    entities = [
        WorldEntityRecord::class,
        WorldProviderStateRecord::class,
        WorldEntityTrailPointRecord::class,
        WorldGeocodeCacheRecord::class,
        WatchtowerRegionRecord::class,
        WatchtowerAlertRecord::class
    ],
    version = 1,
    exportSchema = false
)
abstract class WorldMonitorDatabase : RoomDatabase() {
    abstract fun worldMonitorDao(): WorldMonitorDao

    companion object {
        const val DATABASE_NAME = "world_monitor_2035.db"
    }
}
