package com.adshield.pro.engine.battery

import kotlin.math.abs

/**
 * AdShield Pro — battery diagnostics.
 *
 * Pure Kotlin so it can be unit-tested without a device. Every conclusion is a function of
 * readings the device actually produced: a rule that needs a value the device does not expose
 * never fires, and missing data is reported as missing rather than guessed. Confidence is scored
 * from the amount and stability of the evidence behind each rule.
 */
object BatteryDiagnostics {

    /** One point of the rolling history used to judge stability and rate. */
    data class Sample(
        val timestampEpochMs: Long,
        val levelPercent: Int?,
        val isCharging: Boolean,
        val temperatureCelsius: Float?,
        val voltageVolts: Float?,
        val currentMilliAmps: Int?,
        val powerWatts: Float?
    )

    /**
     * Normalises the current reading to "positive while charging".
     * Vendors disagree on the sign, so a value is only trusted when it agrees with the state;
     * otherwise the magnitude is returned and the sign is ignored.
     */
    fun normalisedCurrentMilliAmps(currentMilliAmps: Int?, isCharging: Boolean): Int? {
        val value = currentMilliAmps ?: return null
        if (value == 0) return 0
        val positiveWhileCharging = if (isCharging) value > 0 else value < 0
        return if (positiveWhileCharging) abs(value) else value
    }

    /** Battery-side power; null unless both voltage and current are actually available. */
    fun batteryPowerWatts(voltageVolts: Float?, currentMilliAmps: Int?): Float? {
        if (voltageVolts == null || currentMilliAmps == null) return null
        if (voltageVolts <= 0f) return null
        return voltageVolts * (abs(currentMilliAmps) / 1000f)
    }

    /** Coefficient of variation of the voltage samples, as a percentage of the mean. */
    fun voltageStabilityPercent(samples: List<Sample>): Float? {
        val voltages = samples.mapNotNull { it.voltageVolts }.filter { it > 0f }
        if (voltages.size < 5) return null
        val mean = voltages.average().toFloat()
        if (mean <= 0f) return null
        val variance = voltages.sumOf { ((it - mean) * (it - mean)).toDouble() } / voltages.size
        val deviation = kotlin.math.sqrt(variance).toFloat()
        return deviation / mean * 100f
    }

    /** Charging rate in %/hour measured from history; null when there is not enough data. */
    fun chargeRatePercentPerHour(samples: List<Sample>): Float? {
        val charging = samples.filter { it.isCharging && it.levelPercent != null }
        if (charging.size < 3) return null
        val first = charging.first()
        val last = charging.last()
        val minutes = (last.timestampEpochMs - first.timestampEpochMs) / 60_000f
        if (minutes < 5f) return null
        val delta = (last.levelPercent!! - first.levelPercent!!).toFloat()
        if (delta <= 0f) return 0f
        return delta / (minutes / 60f)
    }

    /** Same for discharge, returned as a positive %/hour figure. */
    fun drainRatePercentPerHour(samples: List<Sample>): Float? {
        val discharging = samples.filter { !it.isCharging && it.levelPercent != null }
        if (discharging.size < 3) return null
        val first = discharging.first()
        val last = discharging.last()
        val minutes = (last.timestampEpochMs - first.timestampEpochMs) / 60_000f
        if (minutes < 5f) return null
        val delta = (first.levelPercent!! - last.levelPercent!!).toFloat()
        return if (delta <= 0f) 0f else delta / (minutes / 60f)
    }

    /**
     * Minutes until full, extrapolated from the measured charging rate. Returns null when the
     * phone is already full/not charging or the rate is not yet measurable — the UI then shows
     * Android's own estimate (if any) and says it is an estimate.
     */
    fun minutesToFull(levelPercent: Int?, ratePercentPerHour: Float?): Int? {
        if (levelPercent == null || ratePercentPerHour == null) return null
        if (levelPercent >= 100 || ratePercentPerHour <= 0.5f) return null
        return (((100 - levelPercent) / ratePercentPerHour) * 60f).toInt().coerceAtLeast(1)
    }

    /** Minutes of runtime left, extrapolated from the measured drain rate. */
    fun minutesToEmpty(levelPercent: Int?, drainPercentPerHour: Float?): Int? {
        if (levelPercent == null || drainPercentPerHour == null || drainPercentPerHour <= 0.1f) return null
        return ((levelPercent / drainPercentPerHour) * 60f).toInt().coerceAtLeast(1)
    }

    /**
     * Counts charging -> not-charging -> charging transitions inside a session window: a phone
     * that keeps dropping off the charger has a cable/port/adapter problem.
     */
    fun countInterruptions(samples: List<Sample>): Int {
        var interruptions = 0
        var lastCharging = samples.firstOrNull()?.isCharging
        for (sample in samples.drop(1)) {
            if (lastCharging == true && !sample.isCharging) interruptions++
            lastCharging = sample.isCharging
        }
        return interruptions
    }

    /**
     * The diagnostic rule chain. Order matters: safety first, then data-quality caveats, then
     * charging-speed and behaviour findings.
     */
    fun diagnose(
        telemetry: BatteryTelemetry,
        history: List<Sample>,
        chargeRatePercentPerHour: Float?,
        drainRatePercentPerHour: Float?,
        interruptions: Int
    ): List<BatteryDiagnostic> {
        val results = ArrayList<BatteryDiagnostic>()
        val temp = telemetry.temperatureCelsius.rawValue
        val level = telemetry.levelPercent.rawValue
        val power = telemetry.powerWatts.rawValue
        val current = normalisedCurrentMilliAmps(telemetry.currentMilliAmps.rawValue, telemetry.isCharging)
        val stability = voltageStabilityPercent(history)
        val avgCurrent = history.mapNotNull { it.currentMilliAmps }.takeIf { it.size >= 5 }?.average()?.toFloat()
        val evidence = ArrayList<String>()

        // 1. Thermal safety ------------------------------------------------------
        if (temp != null) {
            when {
                temp >= 45f -> results.add(
                    BatteryDiagnostic(
                        title = "Battery is critically hot",
                        explanation = "The battery reports ${format1(temp)}°C (${format1(temp * 9f / 5f + 32f)}°F) while ${if (telemetry.isCharging) "charging" else "in use"}. At this temperature lithium cells age permanently and Android may throttle charging.",
                        recommendation = "Unplug the charger, close heavy apps and let the phone cool below 40°C before charging again.",
                        severity = BatteryDiagnosticSeverity.CRITICAL,
                        confidencePercent = 95,
                        evidence = listOf("Temperature ${format1(temp)}°C read from EXTRA_TEMPERATURE")
                    )
                )
                temp >= 40f -> results.add(
                    BatteryDiagnostic(
                        title = "Battery is running warm",
                        explanation = "Measured ${format1(temp)}°C. Charging above 40°C is the fastest way to lose capacity over time.",
                        recommendation = "Take the case off, keep the screen off while charging, and avoid gaming while plugged in.",
                        severity = BatteryDiagnosticSeverity.WARNING,
                        confidencePercent = 90,
                        evidence = listOf("Temperature ${format1(temp)}°C from EXTRA_TEMPERATURE")
                    )
                )
                temp <= 5f -> results.add(
                    BatteryDiagnostic(
                        title = "Battery is cold",
                        explanation = "The battery reports ${format1(temp)}°C. Charging below 5°C is limited by Android for safety and capacity reads low.",
                        recommendation = "Move the phone somewhere warmer before charging.",
                        severity = BatteryDiagnosticSeverity.WARNING,
                        confidencePercent = 85,
                        evidence = listOf("Temperature ${format1(temp)}°C")
                    )
                )
            }
        } else {
            results.add(
                BatteryDiagnostic(
                    title = "Temperature is not exposed by this device",
                    explanation = "This build of Android does not publish EXTRA_TEMPERATURE, so heat cannot be judged here — no guess is shown instead.",
                    recommendation = "Check the phone's own battery settings for temperature if you suspect overheating.",
                    severity = BatteryDiagnosticSeverity.INFO,
                    confidencePercent = 100,
                    evidence = listOf("BatteryManager.EXTRA_TEMPERATURE absent")
                )
            )
        }

        // 2. Charging speed ------------------------------------------------------
        if (telemetry.isCharging && !telemetry.isFull) {
            if (chargeRatePercentPerHour != null) {
                when {
                    chargeRatePercentPerHour >= 40f -> results.add(
                        BatteryDiagnostic(
                            title = "Charging fast",
                            explanation = "Measured gain of ${format1(chargeRatePercentPerHour)}% per hour over this session — typical of a healthy fast charger.",
                            recommendation = "No action needed.",
                            severity = BatteryDiagnosticSeverity.INFO,
                            confidencePercent = 85,
                            evidence = listOf("Session history: ${format1(chargeRatePercentPerHour)}%/h")
                        )
                    )
                    chargeRatePercentPerHour >= 15f -> results.add(
                        BatteryDiagnostic(
                            title = "Charging at a normal rate",
                            explanation = "Measured gain of ${format1(chargeRatePercentPerHour)}% per hour. Phones taper below 80%, so a slower rate near full is expected.",
                            recommendation = "No action needed.",
                            severity = BatteryDiagnosticSeverity.INFO,
                            confidencePercent = 80,
                            evidence = listOf("Session history: ${format1(chargeRatePercentPerHour)}%/h")
                        )
                    )
                    else -> results.add(
                        BatteryDiagnostic(
                            title = "Charging is slow",
                            explanation = "Only ${format1(chargeRatePercentPerHour)}% per hour was measured. With ${format1(power ?: 0f)} W reaching the battery, either the charger/cable is limiting current, the screen is consuming it, or the phone is thermally limiting charging.",
                            recommendation = "Try another cable and charger, plug straight into the adapter (no hub), keep the screen off, and compare this screen's measured power on both.",
                            severity = BatteryDiagnosticSeverity.WARNING,
                            confidencePercent = if (power != null) 80 else 55,
                            evidence = listOfNotNull(
                                "Session history: ${format1(chargeRatePercentPerHour)}%/h",
                                power?.let { "Battery-side power ${format1(it)} W" }
                            )
                        )
                    )
                }
            } else if (telemetry.isCharging) {
                results.add(
                    BatteryDiagnostic(
                        title = "Rate not measurable yet",
                        explanation = "Charging speed is calculated from real level changes over at least 5 minutes of this session. Not enough has been collected yet.",
                        recommendation = "Leave the phone plugged in for a few minutes and run Detect again.",
                        severity = BatteryDiagnosticSeverity.INFO,
                        confidencePercent = 100,
                        evidence = listOf("${history.size} samples so far")
                    )
                )
            }

            // Suspiciously low battery-side power while charging.
            if (power != null && power < 1.5f && level != null && level < 90) {
                results.add(
                    BatteryDiagnostic(
                        title = "Very low charge power",
                        explanation = "Only ${format1(power)} W is reaching the battery. A working charger normally delivers several watts; this looks like a data-only or failing cable/port.",
                        recommendation = "Swap the USB cable first, then the adapter. If power stays under 2 W, the phone's charge port may need service.",
                        severity = BatteryDiagnosticSeverity.WARNING,
                        confidencePercent = 75,
                        evidence = listOf(
                            "Power ${format1(power)} W = ${format1(telemetry.voltageVolts.rawValue ?: 0f)} V x ${format1(abs(current ?: 0) / 1000f)} A",
                            "Level $level%"
                        )
                    )
                )
            }

            // Charging interruptions.
            if (interruptions >= 2) {
                results.add(
                    BatteryDiagnostic(
                        title = "Charging keeps dropping out",
                        explanation = "$interruptions charging interruptions were recorded in this session. That pattern is typical of a loose connector, a worn cable, or a charger that cannot hold the current.",
                        recommendation = "Reseat the cable, clean the port, and try a different adapter.",
                        severity = BatteryDiagnosticSeverity.WARNING,
                        confidencePercent = 80,
                        evidence = listOf("$interruptions transitions from charging to not charging")
                    )
                )
            }

            // Voltage instability.
            if (stability != null && stability > 3f) {
                results.add(
                    BatteryDiagnostic(
                        title = "Charging power is unstable",
                        explanation = "Voltage varied by ${format1(stability)}% around its average during this session, which is more than normal drift.",
                        recommendation = "Try a different cable/adapter combination and watch whether voltage variation drops.",
                        severity = BatteryDiagnosticSeverity.NOTICE,
                        confidencePercent = 65,
                        evidence = listOf("Voltage variation ${format1(stability)}%")
                    )
                )
            }
        }

        // 3. Discharge behaviour -------------------------------------------------
        if (!telemetry.isCharging && drainRatePercentPerHour != null) {
            if (drainRatePercentPerHour >= 20f) {
                results.add(
                    BatteryDiagnostic(
                        title = "Battery is draining unusually fast",
                        explanation = "Measured drain of ${format1(drainRatePercentPerHour)}% per hour — that would empty the battery in roughly ${minutesToEmpty(level, drainRatePercentPerHour) ?: 0} minutes.",
                        recommendation = "Check which apps used the most power in Android settings, and enable the Ad Blocker's DNS filtering to cut background tracking traffic.",
                        severity = BatteryDiagnosticSeverity.WARNING,
                        confidencePercent = 70,
                        evidence = listOf("Session history: ${format1(drainRatePercentPerHour)}%/h")
                    )
                )
            } else {
                results.add(
                    BatteryDiagnostic(
                        title = "Drain rate is normal",
                        explanation = "Measured ${format1(drainRatePercentPerHour)}% per hour, about ${minutesToEmpty(level, drainRatePercentPerHour) ?: 0} minutes of runtime left at this rate.",
                        recommendation = "No action needed.",
                        severity = BatteryDiagnosticSeverity.INFO,
                        confidencePercent = 70,
                        evidence = listOf("Session history: ${format1(drainRatePercentPerHour)}%/h")
                    )
                )
            }
        }

        // 4. Health --------------------------------------------------------------
        val capacityHealth = telemetry.capacityHealthPercent
        if (capacityHealth != null && capacityHealth < 80f) {
            results.add(
                BatteryDiagnostic(
                    title = "Measured capacity is below 80% of design",
                    explanation = "Android reports a full-charge counter of ${telemetry.chargeFullMicroAmpHours.rawValue} µAh against a design capacity of ${telemetry.chargeFullDesignMicroAmpHours.rawValue} µAh (${format1(capacityHealth)}%).",
                    recommendation = "This is real measured degradation. Avoid heat and deep discharges; consider a battery replacement if runtime is a problem.",
                    severity = BatteryDiagnosticSeverity.WARNING,
                    confidencePercent = 80,
                    evidence = listOf("Full ${telemetry.chargeFullMicroAmpHours.rawValue} µAh vs design ${telemetry.chargeFullDesignMicroAmpHours.rawValue} µAh")
                )
            )
        }

        // Data-quality caveats, always last.
        if (!telemetry.currentSignKnown && telemetry.currentMilliAmps.rawValue != null) {
            results.add(
                BatteryDiagnostic(
                    title = "Current direction could not be verified",
                    explanation = "This device reports a current whose sign disagrees with its charging state, so power is calculated from the magnitude only. Power itself is still accurate; direction is not interpreted.",
                    recommendation = "No action needed — this is a vendor reporting quirk.",
                    severity = BatteryDiagnosticSeverity.INFO,
                    confidencePercent = 100,
                    evidence = listOf("Current ${telemetry.currentMilliAmps.rawValue} mA while ${telemetry.statusLabel.lowercase()}")
                )
            )
        }
        if (telemetry.chargeFullDesignMicroAmpHours.rawValue == null || telemetry.chargeFullMicroAmpHours.rawValue == null) {
            results.add(
                BatteryDiagnostic(
                    title = "Capacity degradation cannot be measured here",
                    explanation = "This device does not expose the full-charge or design capacity counters to apps. Android's health status (${telemetry.healthLabel}) is a vendor flag, not a measured capacity figure, and the two are not interchangeable.",
                    recommendation = "Use the measured time-to-full and runtime figures on this screen instead of a health percentage.",
                    severity = BatteryDiagnosticSeverity.INFO,
                    confidencePercent = 100,
                    evidence = listOf("CHARGE_COUNTER_FULL / FULL_DESIGN not reported")
                )
            )
        }
        if (avgCurrent != null && current != null && current != 0) {
            evidence.add("Average current ${avgCurrent.toInt()} mA")
        }

        if (results.isEmpty()) {
            results.add(
                BatteryDiagnostic(
                    title = "Everything measured looks normal",
                    explanation = "No abnormal temperature, power, voltage variation or degradation was measured from the readings this device provides.",
                    recommendation = "No action needed.",
                    severity = BatteryDiagnosticSeverity.INFO,
                    confidencePercent = 60,
                    evidence = evidence.ifEmpty { listOf("All checks ran against live readings") }
                )
            )
        }
        return results
    }

    /** Overall protection/health score from measured conditions only. */
    fun healthScore(
        telemetry: BatteryTelemetry,
        capacityHealthPercent: Float?,
        averageTemperatureCelsius: Float?,
        cycleCount: Int?
    ): Int {
        var score = 100
        if (averageTemperatureCelsius != null) {
            if (averageTemperatureCelsius >= 40f) score -= 20
            else if (averageTemperatureCelsius >= 37f) score -= 8
        }
        if (capacityHealthPercent != null) {
            when {
                capacityHealthPercent < 70f -> score -= 35
                capacityHealthPercent < 80f -> score -= 22
                capacityHealthPercent < 90f -> score -= 10
            }
        }
        if (cycleCount != null) {
            when {
                cycleCount >= 1000 -> score -= 25
                cycleCount >= 600 -> score -= 15
                cycleCount >= 300 -> score -= 6
            }
        }
        when (telemetry.healthRaw) {
            android.os.BatteryManager.BATTERY_HEALTH_OVERHEAT -> score -= 20
            android.os.BatteryManager.BATTERY_HEALTH_DEAD -> score -= 40
            android.os.BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> score -= 25
            android.os.BatteryManager.BATTERY_HEALTH_COLD -> score -= 10
        }
        return score.coerceIn(5, 100)
    }

    /** Human label for a score; always paired with the fact that it is a composite index. */
    fun healthLabel(score: Int): String = when {
        score >= 90 -> "Excellent"
        score >= 75 -> "Good"
        score >= 60 -> "Fair"
        score >= 40 -> "Worn"
        else -> "Degraded"
    }

    fun format1(value: Float): String {
        val rounded = kotlin.math.round(value * 10f) / 10f
        return if (rounded == rounded.toInt().toFloat()) rounded.toInt().toString() else rounded.toString()
    }
}
