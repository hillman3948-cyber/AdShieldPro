package com.adshield.pro.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        DnsLogEntity::class,
        CustomRuleEntity::class,
        FilterSourceEntity::class,
        DailyStatEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class AdShieldDatabase : RoomDatabase() {
    abstract fun adShieldDao(): AdShieldDao

    companion object {
        const val DATABASE_NAME = "adshield_pro_v2.db"

        /**
         * Explicit Room schema migration from v1 to v2 adding `etag_or_hash` to `filter_sources`
         * and creating indices on `dns_logs` for fast query filtering.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE filter_sources ADD COLUMN etag_or_hash TEXT NOT NULL DEFAULT ''"
                )
                // Every index declared on the v2 entities must exist after the migration,
                // otherwise Room's identity-hash validation fails at runtime and the app
                // crashes while opening the database. IF NOT EXISTS keeps this idempotent for
                // databases that already carried some of these indices.
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_dns_logs_timestamp_epoch_ms " +
                        "ON dns_logs(timestamp_epoch_ms)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_dns_logs_domain ON dns_logs(domain)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_dns_logs_is_blocked ON dns_logs(is_blocked)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_custom_rules_normalized_pattern_rule_type " +
                        "ON custom_rules(normalized_pattern, rule_type)"
                )
            }
        }
    }
}
