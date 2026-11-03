package com.adshield.pro.engine.platform

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.adshield.pro.engine.battery.BatteryTelemetry
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What this specific device can do, measured at runtime.
 *
 * The Status screen shows these rows so a user on an old or unusual phone sees exactly which
 * capabilities their hardware unlocks, instead of wondering why a number is missing. Every
 * boolean here is derived from a real query — no defaults are guessed.
 */
data class DeviceProfile(
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdkInt: Int,
    val supportedPlatform: Boolean,
    val formFactor: DeviceCompatibility.FormFactor,
    val cpuArchitectures: List<String>,
    val hasTelephony: Boolean,
    val hasWifi: Boolean,
    val hasBluetooth: Boolean,
    val hasNfc: Boolean,
    val hasTouchscreen: Boolean,
    val hasGps: Boolean,
    val notificationPermission: String,
    val batteryChecks: List<BatterySupportRow>
)

/** One battery capability row for this device: supported, or why not. */
data class BatterySupportRow(
    val label: String,
    val value: String,
    val supported: Boolean
)

@Singleton
class DeviceProfiler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun profile(
        telemetry: BatteryTelemetry,
        screenWidthDp: Int,
        smallestWidthDp: Int
    ): DeviceProfile {
        val features = context.packageManager

        fun has(systemFeature: String): Boolean =
            runCatching { features.hasSystemFeature(systemFeature) }.getOrDefault(false)

        return DeviceProfile(
            manufacturer = Build.MANUFACTURER?.replaceFirstChar { it.uppercase() } ?: "Unknown",
            model = Build.MODEL ?: "Unknown",
            androidRelease = DeviceCompatibility.apiToRelease(Build.VERSION.SDK_INT),
            sdkInt = Build.VERSION.SDK_INT,
            supportedPlatform = DeviceCompatibility.isSupportedPlatform(Build.VERSION.SDK_INT),
            formFactor = DeviceCompatibility.formFactor(screenWidthDp, smallestWidthDp),
            cpuArchitectures = Build.SUPPORTED_ABIS?.toList().orEmpty(),
            hasTelephony = has(PackageManager.FEATURE_TELEPHONY),
            hasWifi = has(PackageManager.FEATURE_WIFI),
            hasBluetooth = has(PackageManager.FEATURE_BLUETOOTH),
            hasNfc = has(PackageManager.FEATURE_NFC),
            hasTouchscreen = has(PackageManager.FEATURE_TOUCHSCREEN),
            hasGps = has(PackageManager.FEATURE_LOCATION_GPS),
            notificationPermission = if (DeviceCompatibility.supports(Build.VERSION.SDK_INT, "notification-permission")) {
                "Askable (Android 13+)"
            } else {
                "Granted at install on this Android version"
            },
            batteryChecks = batteryChecks(telemetry)
        )
    }

    /** The hardware-support matrix the Battery tab relies on. */
    private fun batteryChecks(telemetry: BatteryTelemetry): List<BatterySupportRow> = listOf(
        row("Battery level", telemetry.levelPercent.rawValue?.let { "$it%" }, telemetry.levelPercent.rawValue != null),
        row("Temperature", telemetry.temperatureCelsius.rawValue?.let { "${BatteryDiagnosticsFormat.one(it)}°C" },
            telemetry.temperatureCelsius.rawValue != null),
        row("Voltage", telemetry.voltageVolts.rawValue?.let { "${BatteryDiagnosticsFormat.one(it)} V" },
            telemetry.voltageVolts.rawValue != null),
        row("Current", telemetry.currentMilliAmps.rawValue?.let { "$it mA" }, telemetry.currentMilliAmps.rawValue != null),
        row("Average current", telemetry.currentAverageMilliAmps.rawValue?.let { "$it mA" },
            telemetry.currentAverageMilliAmps.rawValue != null),
        row("Charge counter", telemetry.chargeCounterMicroAmpHours.rawValue?.let { "${it / 1000} mAh" },
            telemetry.chargeCounterMicroAmpHours.rawValue != null),
        row("Design capacity", telemetry.chargeFullDesignMicroAmpHours.rawValue?.let { "${it / 1000} mAh" },
            telemetry.chargeFullDesignMicroAmpHours.rawValue != null),
        row("Full-charge capacity", telemetry.chargeFullMicroAmpHours.rawValue?.let { "${it / 1000} mAh" },
            telemetry.chargeFullMicroAmpHours.rawValue != null),
        row("Cycle count", telemetry.cycleCount.rawValue?.let { "$it" }, telemetry.cycleCount.rawValue != null),
        row("Charge time estimate", telemetry.chargeTimeRemainingMinutes.rawValue?.let { "$it min" },
            telemetry.chargeTimeRemainingMinutes.rawValue != null)
    )

    private fun row(label: String, value: String?, supported: Boolean) =
        BatterySupportRow(label, value ?: "Not exposed by this device", supported)
}

/** Tiny formatter shared by the status screens (kept here to avoid a UI dependency). */
object BatteryDiagnosticsFormat {
    fun one(value: Float): String {
        val rounded = kotlin.math.round(value * 10f) / 10f
        return if (rounded == rounded.toInt().toFloat()) rounded.toInt().toString() else rounded.toString()
    }
}
