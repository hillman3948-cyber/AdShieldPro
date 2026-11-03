package com.adshield.pro.engine.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AdShield Pro — real-hardware battery telemetry.
 *
 * Every value comes from Android's own battery broadcast or [BatteryManager] properties on this
 * device. Nothing is simulated: when the device does not expose a property, the reading is
 * reported as UNSUPPORTED (or UNAVAILABLE when a reading is momentarily absent) and the UI shows
 * that instead of a number.
 *
 * Charge counter / current sign conventions vary per vendor, so the sign is only trusted when the
 * device agrees with the charging state; [BatteryTelemetry.currentSignKnown] records that.
 */
@Singleton
class BatteryTelemetrySource @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val batteryManager: BatteryManager? =
        context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

    private val _telemetry = MutableStateFlow(read())
    val telemetry: StateFlow<BatteryTelemetry> = _telemetry.asStateFlow()

    /** Called by the repository after each battery broadcast so the flow stays live. */
    fun refresh(): BatteryTelemetry {
        val snapshot = read()
        _telemetry.value = snapshot
        return snapshot
    }

    fun current(): BatteryTelemetry = _telemetry.value

    private fun stickyBatteryIntent(): Intent? =
        runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()

    private fun intProperty(property: Int): Int? {
        val manager = batteryManager ?: return null
        val value = runCatching { manager.getIntProperty(property) }.getOrDefault(Int.MIN_VALUE)
        // Android returns Integer.MIN_VALUE (or -1 on older vendor blobs) when unsupported.
        return if (value == Int.MIN_VALUE || value == Int.MAX_VALUE) null else value
    }

    private fun longProperty(property: Int): Long? {
        val manager = batteryManager ?: return null
        val value = runCatching { manager.getLongProperty(property) }.getOrDefault(Long.MIN_VALUE)
        return if (value == Long.MIN_VALUE || value == Long.MAX_VALUE) null else value
    }

    private fun read(): BatteryTelemetry {
        val now = System.currentTimeMillis()
        val intent = stickyBatteryIntent()

        // --- Level ---------------------------------------------------------------
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.coerceAtLeast(1) ?: 100
        val capacityProperty = intProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val levelPercent = when {
            level >= 0 -> ((level * 100) / scale).coerceIn(0, 100)
            capacityProperty != null && capacityProperty in 0..100 -> capacityProperty
            else -> null
        }

        // --- Temperature --------------------------------------------------------
        val tempPresent = intent?.hasExtra(BatteryManager.EXTRA_TEMPERATURE) == true
        val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val temperature = if (tempPresent && tempTenths != Int.MIN_VALUE && tempTenths != 0) {
            BatteryReading.measured(tempTenths / 10f, "°C", "BatteryManager.EXTRA_TEMPERATURE")
        } else {
            BatteryReading.unsupported<Float>("°C", "This device does not report battery temperature to apps")
        }

        // --- Voltage ------------------------------------------------------------
        val rawVoltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val voltage = when {
            rawVoltage == Int.MIN_VALUE || rawVoltage <= 0 ->
                BatteryReading.unsupported<Float>("V", "No voltage reported in ACTION_BATTERY_CHANGED")
            // Some vendor blobs report volts instead of millivolts (a phone never runs on 4 V vs 4000 mV).
            rawVoltage in 1..20 -> BatteryReading.measured(rawVoltage.toFloat(), "V", "Reported in volts")
            else -> BatteryReading.measured(rawVoltage / 1000f, "V", "Reported in millivolts")
        }

        // --- Current ------------------------------------------------------------
        val nowMicroAmps = intProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val averageMicroAmps = intProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)

        // --- Status / plugged ---------------------------------------------------
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
        val isFull = status == BatteryManager.BATTERY_STATUS_FULL
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val pluggedLabel = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC charger"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB port"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> "Not plugged in"
        }
        val statusLabel = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "Full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
            else -> "Unknown"
        }

        // --- Health / technology ------------------------------------------------
        val health = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
            ?: BatteryManager.BATTERY_HEALTH_UNKNOWN
        val healthStatus = when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> BatteryHealthStatus.GOOD
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> BatteryHealthStatus.OVERHEAT
            BatteryManager.BATTERY_HEALTH_DEAD -> BatteryHealthStatus.DEAD
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> BatteryHealthStatus.OVER_VOLTAGE
            BatteryManager.BATTERY_HEALTH_COLD -> BatteryHealthStatus.COLD
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> BatteryHealthStatus.FAILURE
            else -> BatteryHealthStatus.UNKNOWN
        }
        val healthLabel = when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheating"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Degraded / dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure reported"
            else -> "Not reported"
        }
        val technology = intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)
            ?.takeIf { it.isNotBlank() } ?: "Not reported"

        // --- Capacity counters --------------------------------------------------
        val chargeCounterUah = longProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            ?.takeIf { it > 0 }
        // Both counters are optional framework properties (absent on many devices), so they are
        // probed by name instead of a hard reference that would fail on older platforms.
        val chargeFullUah = readLongField("BATTERY_PROPERTY_CHARGE_COUNTER_FULL")
        val chargeFullDesignUah = readLongField("BATTERY_PROPERTY_CHARGE_COUNTER_FULL_DESIGN")

        // --- Time remaining -----------------------------------------------------
        val chargeTimeFromFramework: Long? = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                (batteryManager as? BatteryManager)?.computeChargeTimeRemaining()
            } else null
        }.getOrNull()?.takeIf { it > 0 && it != Long.MAX_VALUE }

        // --- Power (battery side only) -----------------------------------------
        // Sign conventions differ per vendor: many report discharge as negative, some as positive.
        // Trust the sign only if it agrees with the charging state.
        val voltageVolts = voltage.rawValue
        val currentMilliAmps = when {
            nowMicroAmps == null -> BatteryReading.unsupported<Int>("mA", "BATTERY_PROPERTY_CURRENT_NOW is not exposed on this device")
            nowMicroAmps == 0 -> BatteryReading.unavailable<Int>("mA", "Driver reports 0 mA")
            else -> BatteryReading.measured(nowMicroAmps / 1000, "mA", "BATTERY_PROPERTY_CURRENT_NOW")
        }
        val signKnown = currentMilliAmps.rawValue?.let { amps ->
            // A charging phone with a clearly negative current means the vendor uses the
            // opposite convention (or the reading is stale), so the absolute value is used
            // for power and the sign is not interpreted.
            if (isCharging) amps > 0 else true
        } ?: false
        val magnitudeAmps = currentMilliAmps.rawValue?.let { kotlin.math.abs(it) / 1000f }
        val powerWatts = if (voltageVolts != null && magnitudeAmps != null) {
            BatteryReading.computed(
                voltageVolts * magnitudeAmps,
                "W",
                "Battery-side power: voltage x measured current (never charger-side output)"
            )
        } else {
            BatteryReading.unavailable<Float>("W", "Needs both voltage and current, which this device did not provide")
        }

        return BatteryTelemetry(
            timestampEpochMs = now,
            levelPercent = if (levelPercent != null) {
                BatteryReading.measured(levelPercent, "%", "ACTION_BATTERY_CHANGED level/scale")
            } else {
                BatteryReading.unavailable("%", "No level reported")
            },
            statusLabel = statusLabel,
            isCharging = isCharging,
            isFull = isFull,
            pluggedLabel = pluggedLabel,
            pluggedRaw = plugged,
            temperatureCelsius = temperature,
            voltageVolts = voltage,
            currentMilliAmps = currentMilliAmps,
            currentAverageMilliAmps = averageMicroAmps?.let {
                BatteryReading.measured(it / 1000, "mA", "BATTERY_PROPERTY_CURRENT_AVERAGE")
            } ?: BatteryReading.unsupported("mA", "BATTERY_PROPERTY_CURRENT_AVERAGE is not exposed"),
            chargeCounterMicroAmpHours = chargeCounterUah?.let {
                BatteryReading.measured(it, "µAh", "BATTERY_PROPERTY_CHARGE_COUNTER")
            } ?: BatteryReading.unsupported("µAh", "BATTERY_PROPERTY_CHARGE_COUNTER is not exposed"),
            powerWatts = powerWatts,
            healthLabel = healthLabel,
            health = healthStatus,
            technology = technology,
            chargeTimeRemainingMinutes = if (isCharging && chargeTimeFromFramework != null) {
                BatteryReading.estimated(
                    (chargeTimeFromFramework / 60_000L).toInt(),
                    "min",
                    "BatteryManager.computeChargeTimeRemaining()"
                )
            } else {
                BatteryReading.unavailable("min", "Only available while the device is charging")
            },
            runtimeRemainingMinutes = BatteryReading.unsupported("min", "Android has no reliable runtime API; measured from your sessions instead"),
            manufacturer = Build.MANUFACTURER?.replaceFirstChar { it.uppercase() } ?: "Unknown",
            model = Build.MODEL ?: "Unknown",
            cycleCount = readCycleCount(),
            chargeFullDesignMicroAmpHours = chargeFullDesignUah?.let {
                BatteryReading.measured(it, "µAh", "BATTERY_PROPERTY_CHARGE_COUNTER_FULL_DESIGN")
            } ?: BatteryReading.unsupported("µAh", "Design capacity is not exposed to apps on this device"),
            chargeFullMicroAmpHours = chargeFullUah?.let {
                BatteryReading.measured(it, "µAh", "BATTERY_PROPERTY_CHARGE_COUNTER_FULL")
            } ?: BatteryReading.unsupported("µAh", "Full-charge capacity is not exposed to apps on this device"),
            currentSignKnown = signKnown
        )
    }

    /**
     * Reads an optional BatteryManager property by field name. These counters exist only on some
     * API levels and vendor builds, so a missing field simply yields null (=> UNSUPPORTED).
     */
    private fun readLongField(fieldName: String): Long? {
        return runCatching {
            val field = BatteryManager::class.java.getField(fieldName)
            val property = field.getInt(null)
            longProperty(property)
        }.getOrNull()?.takeIf { it > 0 }
    }

    /**
     * Cycle count comes from the Android 14+ system health API. It is read best-effort and
     * reported as UNSUPPORTED below API 34 or when the platform returns nothing.
     */
    private fun readCycleCount(): BatteryReading<Int> {
        if (Build.VERSION.SDK_INT < 34) {
            return BatteryReading.unsupported("cycles", "Requires Android 14+; this device is API ${Build.VERSION.SDK_INT}")
        }
        val cycles = runCatching {
            val method = BatteryManager::class.java.getMethod("getCycleCount")
            method.invoke(batteryManager) as? Int
        }.getOrNull()
        return if (cycles != null && cycles > 0) {
            BatteryReading.measured(cycles, "cycles", "BatteryManager.getCycleCount()")
        } else {
            BatteryReading.unsupported("cycles", "Android 14+ is present but the device does not report a cycle count")
        }
    }
}
