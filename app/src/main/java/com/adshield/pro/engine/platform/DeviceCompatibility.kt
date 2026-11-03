package com.adshield.pro.engine.platform

/**
 * AdShield Pro — cross-device compatibility layer.
 *
 * This app has to behave correctly on every Android device from minSdk to the newest release,
 * on phones, tablets and foldables, with or without vendor battery properties. Rather than
 * sprinkling `if (SDK_INT >= …)` through the UI, the rules live here as pure functions so they
 * can be unit-tested for every API level and form factor without a device.
 *
 * Nothing in this file invents data: each decision is either "supported / not supported on this
 * platform" or "usable / unusable on this device".
 */
object DeviceCompatibility {

    const val MIN_SUPPORTED_SDK = 29
    const val MAX_TRACKED_SDK = 37

    enum class FormFactor { PHONE, LARGE_PHONE, TABLET, FOLDABLE }

    /** Coarse screen class from the shortest width in dp, the way Android itself buckets windows. */
    fun formFactor(screenWidthDp: Int, smallestWidthDp: Int): FormFactor = when {
        smallestWidthDp >= 600 -> FormFactor.TABLET
        screenWidthDp >= 600 -> FormFactor.FOLDABLE
        screenWidthDp >= 480 -> FormFactor.LARGE_PHONE
        else -> FormFactor.PHONE
    }

    /**
     * How many bottom-bar destinations fit comfortably, so the navigation adapts instead of
     * clipping (seven destinations do not fit a 360dp phone).
     */
    fun visibleNavItems(screenWidthDp: Int, itemCount: Int): Int {
        if (itemCount <= 0) return 0
        if (screenWidthDp <= 0) return 0
        val perItem = if (screenWidthDp >= 600) 88 else 60
        val fitting = screenWidthDp / perItem
        return fitting.coerceIn(1, itemCount)
    }

    /**
     * Feature gates. Keys are stable and mirrored by [CapabilityRegistry]; values are the first
     * API level at which the platform exposes the feature, or null when it exists on every
     * supported release.
     */
    private val firstApiLevel = mapOf(
        "notification-permission" to 33,
        "thermal-status" to 29,
        "detailed-network-usage" to 29,
        "charge-time-remaining" to 28,
        "cycle-count" to 34,
        "locale-per-app" to 33,
        "predictive-back" to 33,
        "battery-saver-extended" to 29
    )

    /** True when [feature] is available on [sdkInt]; unknown features are treated as available. */
    fun supports(sdkInt: Int, feature: String): Boolean {
        val required = firstApiLevel[feature] ?: return true
        return sdkInt >= required
    }

    /** Human explanation for a gated feature, used in the UI instead of hiding it silently. */
    fun unsupportedReason(sdkInt: Int, feature: String): String? {
        val required = firstApiLevel[feature] ?: return null
        if (sdkInt >= required) return null
        return "${feature.replace('-', ' ')} needs Android ${apiToRelease(required)} or newer " +
            "(this device is API $sdkInt)"
    }

    /** Rough Android release name for a feature message ("API 34" is friendlier than nothing). */
    fun apiToRelease(api: Int): String = when {
        api >= 36 -> "16+ (API $api)"
        api == 35 -> "15 (API 35)"
        api == 34 -> "14 (API 34)"
        api == 33 -> "13 (API 33)"
        api == 32 || api == 31 -> "12 (API 31–32)"
        api == 30 -> "11 (API 30)"
        api == 29 -> "10 (API 29)"
        else -> "API $api"
    }

    /** True when the running platform is within the range this build was designed and tested for. */
    fun isSupportedPlatform(sdkInt: Int): Boolean = sdkInt in MIN_SUPPORTED_SDK..MAX_TRACKED_SDK

    /**
     * A battery reading is only trustworthy when the value is present and inside the physically
     * plausible range for a phone/laptop battery. Vendor blobs sometimes emit 0, 255 or absurd
     * values; those are rejected so the UI can say "unavailable" instead of showing nonsense.
     */
    fun isPlausibleTemperature(celsius: Float?): Boolean = celsius != null && celsius > -20f && celsius < 90f

    fun isPlausibleVoltage(volts: Float?): Boolean = volts != null && volts > 2.5f && volts < 5.5f

    fun isPlausibleCurrentMilliAmps(milliAmps: Int?): Boolean {
        if (milliAmps == null) return false
        val magnitude = kotlin.math.abs(milliAmps)
        // 0 mA is a driver quirk, not a measurement; 20 A is beyond any phone battery path.
        return magnitude in 1..20_000
    }

    fun isPlausibleLevel(percent: Int?): Boolean = percent != null && percent in 0..100

    /**
     * Normalises the voltage unit some vendor blobs get wrong (reporting volts where millivolts
     * are expected). A phone battery never runs at 4 V * 1000, and never at 0.004 V.
     */
    fun normaliseVoltageToVolts(rawValue: Int): Float? = when {
        rawValue <= 0 -> null
        rawValue in 1..20 -> rawValue.toFloat()
        rawValue in 2_500..6_000 -> rawValue / 1000f
        else -> null
    }

    /**
     * Some devices report charging power / time in units of 1000x. Android's own
     * `computeChargeTimeRemaining` returns milliseconds on all supported releases, but vendor
     * forks have returned seconds; anything implausible is rejected rather than shown.
     */
    fun normaliseChargeTimeMinutes(rawMillis: Long?): Int? {
        if (rawMillis == null || rawMillis <= 0L) return null
        val minutes = rawMillis / 60_000L
        return when {
            minutes in 1..1_440 -> minutes.toInt()          // up to 24 h
            rawMillis / 1_000L in 60..86_400 -> (rawMillis / 60_000L).toInt() // seconds-based fork
            else -> null
        }
    }
}
