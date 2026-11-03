package com.adshield.pro.engine.battery

/**
 * Where a displayed battery value comes from. Every reading surfaced in the UI carries one of
 * these, so the Battery Intelligence screen can never present an estimate as a measurement.
 */
enum class BatteryReadingSource {
    /** Read directly from Android / the battery driver on this device. */
    HARDWARE,
    /** Derived from hardware readings (power = V x I, °F from °C, runtime from measured rate). */
    COMPUTED,
    /** Statistical estimate (e.g. time to full from observed charging rate). */
    ESTIMATED,
    /** The device does not expose this value to apps. */
    UNSUPPORTED,
    /** Supported in principle, but no reading was available at this moment. */
    UNAVAILABLE
}

/**
 * One measured/derived value with its provenance and unit.
 * [rawValue] is null whenever the source is UNSUPPORTED or UNAVAILABLE.
 */
data class BatteryReading<T>(
    val rawValue: T?,
    val source: BatteryReadingSource,
    val unit: String,
    val detail: String = ""
) {
    val isMeasured: Boolean get() = rawValue != null && (source == BatteryReadingSource.HARDWARE || source == BatteryReadingSource.COMPUTED)
    val label: String
        get() = when (source) {
            BatteryReadingSource.HARDWARE -> "Measured"
            BatteryReadingSource.COMPUTED -> "Computed"
            BatteryReadingSource.ESTIMATED -> "Estimated"
            BatteryReadingSource.UNSUPPORTED -> "Not supported on this device"
            BatteryReadingSource.UNAVAILABLE -> "Unavailable right now"
        }

    companion object {
        fun <T> measured(value: T, unit: String, detail: String = "") =
            BatteryReading(value, BatteryReadingSource.HARDWARE, unit, detail)

        fun <T> computed(value: T?, unit: String, detail: String = "") =
            if (value == null) unavailable(unit, detail) else BatteryReading(value, BatteryReadingSource.COMPUTED, unit, detail)

        fun <T> estimated(value: T?, unit: String, detail: String = "") =
            if (value == null) unavailable(unit, detail) else BatteryReading(value, BatteryReadingSource.ESTIMATED, unit, detail)

        fun <T> unsupported(unit: String, detail: String = "") =
            BatteryReading<T>(null, BatteryReadingSource.UNSUPPORTED, unit, detail)

        fun <T> unavailable(unit: String, detail: String = "") =
            BatteryReading<T>(null, BatteryReadingSource.UNAVAILABLE, unit, detail)
    }
}

/** Full, timestamped snapshot of what this device actually reports. */
data class BatteryTelemetry(
    val timestampEpochMs: Long,
    val levelPercent: BatteryReading<Int>,
    val statusLabel: String,
    val isCharging: Boolean,
    val isFull: Boolean,
    val pluggedLabel: String,
    val pluggedRaw: Int,
    val temperatureCelsius: BatteryReading<Float>,
    val voltageVolts: BatteryReading<Float>,
    val currentMilliAmps: BatteryReading<Int>,
    val currentAverageMilliAmps: BatteryReading<Int>,
    val chargeCounterMicroAmpHours: BatteryReading<Long>,
    val powerWatts: BatteryReading<Float>,
    val healthLabel: String,
    val healthRaw: Int,
    val technology: String,
    val chargeTimeRemainingMinutes: BatteryReading<Int>,
    val runtimeRemainingMinutes: BatteryReading<Int>,
    val manufacturer: String,
    val model: String,
    val cycleCount: BatteryReading<Int>,
    val chargeFullDesignMicroAmpHours: BatteryReading<Long>,
    val chargeFullMicroAmpHours: BatteryReading<Long>,
    /** Current / average current sign convention: positive = charging, negative = discharging. */
    val currentSignKnown: Boolean
) {
    val temperatureFahrenheit: Float? get() = temperatureCelsius.rawValue?.let { it * 9f / 5f + 32f }

    /** Share of the design capacity Android currently believes is stored, when both are exposed. */
    val capacityHealthPercent: Float?
        get() {
            val full = chargeFullMicroAmpHours.rawValue ?: return null
            val design = chargeFullDesignMicroAmpHours.rawValue ?: return null
            if (full <= 0 || design <= 0) return null
            return (full.toFloat() / design.toFloat() * 100f).coerceIn(0f, 150f)
        }
}

/** Severity used by the diagnostics list. */
enum class BatteryDiagnosticSeverity { INFO, NOTICE, WARNING, CRITICAL }

/**
 * One explainable diagnostic. [confidencePercent] is how sure the rule chain is given the
 * evidence actually present; rules that fire on missing data are scored low on purpose.
 */
data class BatteryDiagnostic(
    val title: String,
    val explanation: String,
    val recommendation: String,
    val severity: BatteryDiagnosticSeverity,
    val confidencePercent: Int,
    val evidence: List<String>
) {
    val confidenceLabel: String
        get() = when {
            confidencePercent >= 85 -> "High confidence"
            confidencePercent >= 60 -> "Medium confidence"
            else -> "Low confidence — limited data"
        }
}

/** Persistent charging/discharging session, stored locally. */
data class BatterySession(
    val id: Long,
    val startedEpochMs: Long,
    val endedEpochMs: Long,
    val startLevelPercent: Int,
    val endLevelPercent: Int,
    val peakTemperatureCelsius: Float?,
    val averageVoltageVolts: Float?,
    val averageCurrentMilliAmps: Float?,
    val peakPowerWatts: Float?,
    val pluggedLabel: String,
    val wasCharging: Boolean,
    val interruptions: Int
) {
    val durationMinutes: Int get() = (((endedEpochMs - startedEpochMs) / 60_000L).toInt()).coerceAtLeast(0)
    val levelDelta: Int get() = endLevelPercent - startLevelPercent
    /** Average charging speed actually observed, %/hour. */
    val levelPercentPerHour: Float?
        get() {
            if (!wasCharging || durationMinutes < 10) return null
            return levelDelta.toFloat() / (durationMinutes / 60f)
        }
}
