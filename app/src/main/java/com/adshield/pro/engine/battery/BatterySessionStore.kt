package com.adshield.pro.engine.battery

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local, on-device store for charging/discharging session history.
 *
 * Sessions are small records, so a JSON list in private SharedPreferences is enough and avoids a
 * Room migration. Nothing leaves the device.
 */
@Singleton
class BatterySessionStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    @Serializable
    data class StoredSession(
        val id: Long,
        val startedEpochMs: Long,
        val endedEpochMs: Long,
        val startLevelPercent: Int,
        val endLevelPercent: Int,
        val peakTemperatureCelsius: Float? = null,
        val averageVoltageVolts: Float? = null,
        val averageCurrentMilliAmps: Float? = null,
        val peakPowerWatts: Float? = null,
        val pluggedLabel: String = "",
        val wasCharging: Boolean = false,
        val interruptions: Int = 0
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val prefs = context.getSharedPreferences("adshield_battery_sessions", Context.MODE_PRIVATE)

    fun load(): List<BatterySession> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<StoredSession>>(raw).map { it.toDomain() }
        }.getOrDefault(emptyList())
    }

    fun save(sessions: List<BatterySession>) {
        val stored = sessions.take(MAX_SESSIONS).map { it.toStored() }
        runCatching { prefs.edit().putString(KEY, json.encodeToString(stored)).apply() }
    }

    fun append(session: BatterySession): List<BatterySession> {
        val updated = (listOf(session) + load()).take(MAX_SESSIONS)
        save(updated)
        return updated
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private fun StoredSession.toDomain() = BatterySession(
        id, startedEpochMs, endedEpochMs, startLevelPercent, endLevelPercent,
        peakTemperatureCelsius, averageVoltageVolts, averageCurrentMilliAmps,
        peakPowerWatts, pluggedLabel, wasCharging, interruptions
    )

    private fun BatterySession.toStored() = StoredSession(
        id, startedEpochMs, endedEpochMs, startLevelPercent, endLevelPercent,
        peakTemperatureCelsius, averageVoltageVolts, averageCurrentMilliAmps,
        peakPowerWatts, pluggedLabel, wasCharging, interruptions
    )

    private companion object {
        const val KEY = "sessions_v1"
        const val MAX_SESSIONS = 100
    }
}
