package com.adshield.pro.data.repository

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.adshield.pro.engine.battery.BatteryDiagnostic
import com.adshield.pro.engine.battery.BatteryDiagnostics
import com.adshield.pro.engine.battery.BatterySession
import com.adshield.pro.engine.battery.BatterySessionStore
import com.adshield.pro.engine.battery.BatteryTelemetry
import com.adshield.pro.engine.battery.BatteryTelemetrySource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AdShield Pro — Battery Intelligence state holder.
 *
 * Receives the system's ACTION_BATTERY_CHANGED broadcasts (the only source of real hardware
 * battery values on Android), keeps a rolling in-memory history for the charts and measured
 * rates, and records charging/discharging sessions locally.
 */
@Singleton
class BatteryIntelligenceRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telemetrySource: BatteryTelemetrySource,
    private val sessionStore: BatterySessionStore
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val telemetry: StateFlow<BatteryTelemetry> = telemetrySource.telemetry

    private val _history = MutableStateFlow<List<BatteryDiagnostics.Sample>>(emptyList())
    val history: StateFlow<List<BatteryDiagnostics.Sample>> = _history.asStateFlow()

    private val _sessions = MutableStateFlow(sessionStore.load())
    val sessions: StateFlow<List<BatterySession>> = _sessions.asStateFlow()

    private val _diagnostics = MutableStateFlow(emptyList<BatteryDiagnostic>())
    val diagnostics: StateFlow<List<BatteryDiagnostic>> = _diagnostics.asStateFlow()

    private val _averageTemperature = MutableStateFlow<Float?>(null)
    val averageTemperature: StateFlow<Float?> = _averageTemperature.asStateFlow()

    private val _lastDiagnosticAtMs = MutableStateFlow(0L)
    val lastDiagnosticAtMs: StateFlow<Long> = _lastDiagnosticAtMs.asStateFlow()

    // Open session bookkeeping (not exposed; mirrored into _sessions when it closes).
    private var sessionStartMs = System.currentTimeMillis()
    private var sessionStartLevel: Int? = null
    private var sessionWasCharging = telemetry.value.isCharging
    private var sessionPeakTemp: Float? = null
    private var sessionVoltageSum = 0.0
    private var sessionVoltageCount = 0
    private var sessionCurrentSum = 0.0
    private var sessionCurrentCount = 0
    private var sessionPeakPower: Float? = null
    private var sessionInterruptions = 0
    private var lastChargingState = telemetry.value.isCharging

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            onBatteryChanged()
        }
    }

    init {
        // ACTION_BATTERY_CHANGED is a sticky, non-exported system broadcast: registering with a
        // null receiver at first read already yields the current state, and an explicit receiver
        // keeps history building while the Battery tab is open. Android stops delivering these to
        // a stopped app, which is why history restarts on launch and the UI says so.
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        sessionStartLevel = telemetry.value.levelPercent.rawValue
        onBatteryChanged()
    }

    private fun onBatteryChanged() {
        val snapshot = telemetrySource.refresh()
        val sample = BatteryDiagnostics.Sample(
            timestampEpochMs = snapshot.timestampEpochMs,
            levelPercent = snapshot.levelPercent.rawValue,
            isCharging = snapshot.isCharging || snapshot.isFull,
            temperatureCelsius = snapshot.temperatureCelsius.rawValue,
            voltageVolts = snapshot.voltageVolts.rawValue,
            currentMilliAmps = snapshot.currentMilliAmps.rawValue,
            powerWatts = snapshot.powerWatts.rawValue
        )

        // Keep the newest 360 samples (~ up to a few hours at Android's 1% granularity).
        _history.value = (_history.value + sample).takeLast(MAX_SAMPLES)

        sessionPeakTemp = listOfNotNull(sessionPeakTemp, sample.temperatureCelsius).maxOrNull()
        sample.voltageVolts?.let { sessionVoltageSum += it; sessionVoltageCount++ }
        sample.currentMilliAmps?.let { sessionCurrentSum += kotlin.math.abs(it); sessionCurrentCount++ }
        sessionPeakPower = listOfNotNull(sessionPeakPower, sample.powerWatts).maxOrNull()

        val chargingNow = snapshot.isCharging || snapshot.isFull
        if (lastChargingState && !chargingNow) sessionInterruptions++
        lastChargingState = chargingNow

        // A session ends when the charging state flips (plug/unplug) after real time has passed.
        if (chargingNow != sessionWasCharging) {
            closeSession(chargingNow)
        }

        _averageTemperature.value = _history.value.mapNotNull { it.temperatureCelsius }
            .takeLast(60)
            .takeIf { it.size >= 3 }
            ?.average()
            ?.toFloat()
    }

    private fun closeSession(nextCharging: Boolean) {
        val now = System.currentTimeMillis()
        val endLevel = telemetry.value.levelPercent.rawValue
        val startLevel = sessionStartLevel
        val durationMs = now - sessionStartMs
        // Ignore blips shorter than 60 s or sessions without a readable level.
        if (startLevel != null && endLevel != null && durationMs >= MIN_SESSION_MS) {
            val session = BatterySession(
                id = now,
                startedEpochMs = sessionStartMs,
                endedEpochMs = now,
                startLevelPercent = startLevel,
                endLevelPercent = endLevel,
                peakTemperatureCelsius = sessionPeakTemp,
                averageVoltageVolts = if (sessionVoltageCount > 0) (sessionVoltageSum / sessionVoltageCount).toFloat() else null,
                averageCurrentMilliAmps = if (sessionCurrentCount > 0) (sessionCurrentSum / sessionCurrentCount).toFloat() else null,
                peakPowerWatts = sessionPeakPower,
                pluggedLabel = telemetry.value.pluggedLabel,
                wasCharging = sessionWasCharging,
                interruptions = sessionInterruptions
            )
            _sessions.value = sessionStore.append(session)
        }
        // Start a fresh session.
        sessionStartMs = now
        sessionStartLevel = endLevel
        sessionWasCharging = nextCharging
        sessionPeakTemp = null
        sessionVoltageSum = 0.0
        sessionVoltageCount = 0
        sessionCurrentSum = 0.0
        sessionCurrentCount = 0
        sessionPeakPower = null
        sessionInterruptions = 0
        lastChargingState = nextCharging
    }

    /**
     * Runs the diagnostic chain against the current readings plus the session history.
     * Only real readings are used; missing ones are reported as missing.
     */
    fun runDiagnostics(): List<BatteryDiagnostic> {
        val snapshot = telemetry.value
        val history = _history.value
        val result = BatteryDiagnostics.diagnose(
            telemetry = snapshot,
            history = history,
            chargeRatePercentPerHour = BatteryDiagnostics.chargeRatePercentPerHour(history),
            drainRatePercentPerHour = BatteryDiagnostics.drainRatePercentPerHour(history),
            interruptions = sessionInterruptions
        )
        _diagnostics.value = result
        _lastDiagnosticAtMs.value = System.currentTimeMillis()
        return result
    }

    fun healthScore(): Int = BatteryDiagnostics.healthScore(
        telemetry = telemetry.value,
        capacityHealthPercent = telemetry.value.capacityHealthPercent,
        averageTemperatureCelsius = _averageTemperature.value,
        cycleCount = telemetry.value.cycleCount.rawValue
    )

    fun chargeRatePercentPerHour(): Float? = BatteryDiagnostics.chargeRatePercentPerHour(_history.value)

    fun drainRatePercentPerHour(): Float? = BatteryDiagnostics.drainRatePercentPerHour(_history.value)

    fun minutesToFull(): Int? = BatteryDiagnostics.minutesToFull(
        telemetry.value.levelPercent.rawValue,
        chargeRatePercentPerHour()
    )

    fun minutesToEmpty(): Int? = BatteryDiagnostics.minutesToEmpty(
        telemetry.value.levelPercent.rawValue,
        drainRatePercentPerHour()
    )

    fun clearSessions() {
        sessionStore.clear()
        _sessions.value = emptyList()
    }

    private companion object {
        const val MAX_SAMPLES = 360
        const val MIN_SESSION_MS = 60_000L
    }
}
