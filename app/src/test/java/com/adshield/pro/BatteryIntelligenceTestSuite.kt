package com.adshield.pro

import com.adshield.pro.engine.battery.BatteryDiagnosticSeverity
import com.adshield.pro.engine.battery.BatteryDiagnostics
import com.adshield.pro.engine.battery.BatteryHealthStatus
import com.adshield.pro.engine.battery.BatteryReading
import com.adshield.pro.engine.battery.BatteryReadingSource
import com.adshield.pro.engine.battery.BatterySession
import com.adshield.pro.engine.battery.BatteryTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Battery Intelligence — accuracy and honesty guarantees.
 *
 * These tests run on the JVM against the pure diagnostics layer: they prove that power is only
 * derived from two real measurements, that vendor sign conventions are normalised, that nothing
 * is extrapolated without enough samples, and that missing hardware data is reported as missing
 * rather than invented.
 */
class BatteryIntelligenceTestSuite {

    private fun telemetry(
        level: Int? = 50,
        charging: Boolean = false,
        temperatureCelsius: Float? = 32.6f,
        voltageVolts: Float? = 4.2f,
        currentMilliAmps: Int? = -500,
        currentSource: BatteryReadingSource = BatteryReadingSource.HARDWARE,
        capacityHealthFullUah: Long? = null,
        capacityHealthDesignUah: Long? = null,
        health: BatteryHealthStatus = BatteryHealthStatus.GOOD
    ): BatteryTelemetry {
        val voltage = voltageVolts?.let { BatteryReading.measured(it, "V") }
            ?: BatteryReading.unsupported("V")
        val current = currentMilliAmps?.let { BatteryReading(it, currentSource, "mA") }
            ?: BatteryReading.unsupported("mA")
        val power = BatteryDiagnostics.batteryPowerWatts(voltageVolts, currentMilliAmps)?.let {
            BatteryReading.computed(it, "W")
        } ?: BatteryReading.unavailable("W")
        val design = capacityHealthDesignUah?.let { BatteryReading.measured(it, "µAh") }
            ?: BatteryReading.unsupported("µAh")
        val full = capacityHealthFullUah?.let { BatteryReading.measured(it, "µAh") }
            ?: BatteryReading.unsupported("µAh")
        return BatteryTelemetry(
            timestampEpochMs = 0L,
            levelPercent = level?.let { BatteryReading.measured(it, "%") } ?: BatteryReading.unavailable("%"),
            statusLabel = if (charging) "Charging" else "Discharging",
            isCharging = charging,
            isFull = false,
            pluggedLabel = if (charging) "AC charger" else "Not plugged in",
            pluggedRaw = if (charging) 1 else 0,
            temperatureCelsius = temperatureCelsius?.let { BatteryReading.measured(it, "°C") }
                ?: BatteryReading.unsupported("°C"),
            voltageVolts = voltage,
            currentMilliAmps = current,
            currentAverageMilliAmps = current,
            chargeCounterMicroAmpHours = BatteryReading.unsupported("µAh"),
            powerWatts = power,
            healthLabel = "Good",
            health = health,
            technology = "Li-poly",
            chargeTimeRemainingMinutes = BatteryReading.unsupported("min"),
            runtimeRemainingMinutes = BatteryReading.unsupported("min"),
            manufacturer = "vivo",
            model = "V2310",
            cycleCount = BatteryReading.unsupported("cycles"),
            chargeFullDesignMicroAmpHours = design,
            chargeFullMicroAmpHours = full,
            currentSignKnown = true
        )
    }

    private fun samples(
        count: Int,
        startLevel: Int,
        endLevel: Int,
        startMs: Long,
        durationMs: Long,
        charging: Boolean,
        temperature: Float? = 33f
    ): List<BatteryDiagnostics.Sample> = (0 until count).map { index ->
        val fraction = index.toFloat() / (count - 1).coerceAtLeast(1)
        BatteryDiagnostics.Sample(
            timestampEpochMs = startMs + (durationMs * fraction).toLong(),
            levelPercent = (startLevel + (endLevel - startLevel) * fraction).toInt(),
            isCharging = charging,
            temperatureCelsius = temperature,
            voltageVolts = 4.2f,
            currentMilliAmps = if (charging) 1500 else -600,
            powerWatts = 6.3f
        )
    }

    // --- Power maths ---------------------------------------------------------

    @Test
    fun testPower_isVoltageTimesMeasuredCurrent_andNeverInvented() {
        // 4.0 V x 1.25 A = 5.0 W
        assertEquals(5.0f, BatteryDiagnostics.batteryPowerWatts(4.0f, 1250)!!, 0.001f)
        // Discharge current is a magnitude for battery-side power.
        assertEquals(2.4f, BatteryDiagnostics.batteryPowerWatts(4.0f, -600)!!, 0.001f)
        // No current reading -> no power figure at all (rather than a guess).
        assertNull(BatteryDiagnostics.batteryPowerWatts(4.0f, null))
        assertNull(BatteryDiagnostics.batteryPowerWatts(null, 1250))
        assertNull(BatteryDiagnostics.batteryPowerWatts(0f, 1250))
    }

    @Test
    fun testWattsFromMilliAmpsAndMillivolts_conversionIsExact() {
        // The reference screenshot's 3.0 V / 271 mA case must compute to 0.813 W.
        val volts = 3000 / 1000f
        val watts = BatteryDiagnostics.batteryPowerWatts(volts, 271)!!
        assertEquals(0.813f, watts, 0.0005f)
    }

    // --- Vendor sign conventions ---------------------------------------------

    @Test
    fun testCurrentSign_isNormalisedPerVendorConvention() {
        // Vendor A: negative while discharging.
        assertEquals(-600, BatteryDiagnostics.normalisedCurrentMilliAmps(-600, isCharging = false))
        // Vendor B: positive while discharging — flipped into the normalised convention.
        assertEquals(-600, BatteryDiagnostics.normalisedCurrentMilliAmps(600, isCharging = false))
        // Vendor A: positive while charging.
        assertEquals(1500, BatteryDiagnostics.normalisedCurrentMilliAmps(1500, isCharging = true))
        // Vendor B: negative while charging — flipped into the normalised convention.
        assertEquals(1500, BatteryDiagnostics.normalisedCurrentMilliAmps(-1500, isCharging = true))
        assertNull(BatteryDiagnostics.normalisedCurrentMilliAmps(null, isCharging = true))
    }

    // --- Rate / projections --------------------------------------------------

    @Test
    fun testChargeRate_isMeasuredFromRealSamplesOnly() {
        // 50% -> 70% over 30 minutes == 40 %/h
        val history = samples(5, startLevel = 50, endLevel = 70, startMs = 0, durationMs = 30 * 60_000L, charging = true)
        val rate = BatteryDiagnostics.chargeRatePercentPerHour(history)!!
        assertEquals(40f, rate, 0.5f)
        assertEquals(45, BatteryDiagnostics.minutesToFull(70, rate)!!) // 30 points at 40%/h
    }

    @Test
    fun testChargeRate_returnsNullWhenEvidenceIsInsufficient() {
        // Only two samples -> not a trend.
        val thin = samples(2, 50, 55, 0, 10 * 60_000L, charging = true)
        assertNull(BatteryDiagnostics.chargeRatePercentPerHour(thin))
        // Three samples but under five minutes -> too short to extrapolate.
        val short = samples(3, 50, 51, 0, 2 * 60_000L, charging = true)
        assertNull(BatteryDiagnostics.chargeRatePercentPerHour(short))
        // No charging samples at all.
        val discharging = samples(5, 80, 70, 0, 30 * 60_000L, charging = false)
        assertNull(BatteryDiagnostics.chargeRatePercentPerHour(discharging))
    }

    @Test
    fun testDrainRate_andRuntimeProjection() {
        // 80% -> 70% over 60 minutes == 10 %/h -> 50% left means ~300 minutes.
        val history = samples(5, 80, 70, 0, 60 * 60_000L, charging = false)
        val drain = BatteryDiagnostics.drainRatePercentPerHour(history)!!
        assertEquals(10f, drain, 0.5f)
        assertEquals(300, BatteryDiagnostics.minutesToEmpty(50, drain)!!)
        // A near-zero drain must not produce an absurd runtime figure.
        assertNull(BatteryDiagnostics.minutesToEmpty(50, 0.05f))
    }

    @Test
    fun testMinutesToFull_isNullWhenFullOrNotMeasurable() {
        assertNull(BatteryDiagnostics.minutesToFull(100, 40f))
        assertNull(BatteryDiagnostics.minutesToFull(50, null))
        assertNull(BatteryDiagnostics.minutesToFull(null, 40f))
        assertEquals(60, BatteryDiagnostics.minutesToFull(60, 40f)!!)
    }

    // --- Stability & interruptions -------------------------------------------

    @Test
    fun testVoltageStability_needsEnoughSamplesAndReportsSpread() {
        val steady = (0 until 10).map {
            BatteryDiagnostics.Sample(it.toLong() * 1000, 50, true, 33f, 4.20f, 1500, 6.3f)
        }
        assertEquals(0f, BatteryDiagnostics.voltageStabilityPercent(steady)!!, 0.001f)

        val noisy = listOf(4.0f, 4.4f, 4.0f, 4.4f, 4.0f, 4.4f).mapIndexed { index, volts ->
            BatteryDiagnostics.Sample(index.toLong() * 1000, 50, true, 33f, volts, 1500, 6.3f)
        }
        val spread = BatteryDiagnostics.voltageStabilityPercent(noisy)!!
        assertTrue("noisy voltage should show >3% variation, was $spread", spread > 3f)

        // Two samples are not enough to judge stability.
        assertNull(BatteryDiagnostics.voltageStabilityPercent(noisy.take(2)))
    }

    @Test
    fun testInterruptions_countOnlyRealChargingDropouts() {
        fun sample(charging: Boolean) = BatteryDiagnostics.Sample(0, 50, charging, 33f, 4.2f, 1000, 4.2f)
        assertEquals(
            2,
            BatteryDiagnostics.countInterruptions(
                listOf(sample(true), sample(true), sample(false), sample(true), sample(false), sample(true))
            )
        )
        assertEquals(0, BatteryDiagnostics.countInterruptions(listOf(sample(true), sample(true))))
    }

    // --- Diagnostics honesty --------------------------------------------------

    @Test
    fun testDiagnostics_slowChargingIsExplainedWithEvidence() {
        val history = samples(6, 20, 24, 0, 60 * 60_000L, charging = true) // 4 %/h == slow
        val diagnostics = BatteryDiagnostics.diagnose(
            telemetry = telemetry(level = 24, charging = true, currentMilliAmps = 271, voltageVolts = 3.0f),
            history = history,
            chargeRatePercentPerHour = 4f,
            drainRatePercentPerHour = null,
            interruptions = 0
        )
        val slow = diagnostics.firstOrNull { it.title.contains("slow", ignoreCase = true) }
        assertNotNull("a 4%/h charge must be reported as slow", slow)
        assertTrue(slow!!.explanation.contains("4", ignoreCase = false))
        assertTrue("evidence must cite the measurement", slow.evidence.isNotEmpty())
        assertTrue(slow.confidencePercent in 1..100)
    }

    @Test
    fun testDiagnostics_hotBatteryIsCritical_andColdIsWarned() {
        val hot = BatteryDiagnostics.diagnose(
            telemetry = telemetry(temperatureCelsius = 46f, charging = true),
            history = emptyList(), chargeRatePercentPerHour = null, drainRatePercentPerHour = null, interruptions = 0
        )
        assertEquals(BatteryDiagnosticSeverity.CRITICAL, hot.first().severity)

        val cold = BatteryDiagnostics.diagnose(
            telemetry = telemetry(temperatureCelsius = 2f),
            history = emptyList(), chargeRatePercentPerHour = null, drainRatePercentPerHour = null, interruptions = 0
        )
        assertEquals(BatteryDiagnosticSeverity.WARNING, cold.first().severity)
    }

    @Test
    fun testDiagnostics_missingTemperatureIsReportedAsNotExposed() {
        val diagnostics = BatteryDiagnostics.diagnose(
            telemetry = telemetry(temperatureCelsius = null),
            history = emptyList(), chargeRatePercentPerHour = null, drainRatePercentPerHour = null, interruptions = 0
        )
        val notice = diagnostics.firstOrNull { it.title.contains("Temperature is not exposed") }
        assertNotNull("missing sensor must be stated, not guessed", notice)
        assertEquals(100, notice!!.confidencePercent)
    }

    @Test
    fun testDiagnostics_needsTimeBeforeJudgingChargeSpeed() {
        val diagnostics = BatteryDiagnostics.diagnose(
            telemetry = telemetry(charging = true),
            history = emptyList(), chargeRatePercentPerHour = null, drainRatePercentPerHour = null, interruptions = 0
        )
        assertTrue(
            "with no history the app must say it is still measuring",
            diagnostics.any { it.title.contains("not measurable yet", ignoreCase = true) }
        )
        // It must never claim a measured slow/fast verdict without samples.
        assertTrue(diagnostics.none { it.title == "Charging is slow" || it.title == "Charging fast" })
    }

    @Test
    fun testDiagnostics_repeatedDropoutsAreFlagged() {
        val diagnostics = BatteryDiagnostics.diagnose(
            telemetry = telemetry(charging = true),
            history = emptyList(), chargeRatePercentPerHour = 30f, drainRatePercentPerHour = null, interruptions = 3
        )
        assertTrue(diagnostics.any { it.title.contains("dropping out", ignoreCase = true) })
    }

    @Test
    fun testDiagnostics_lowChargePowerCallsOutCableOrCharger() {
        val diagnostics = BatteryDiagnostics.diagnose(
            telemetry = telemetry(level = 40, charging = true, currentMilliAmps = 271, voltageVolts = 3.0f),
            history = emptyList(), chargeRatePercentPerHour = 20f, drainRatePercentPerHour = null, interruptions = 0
        )
        val lowPower = diagnostics.firstOrNull { it.title.contains("Very low charge power") }
        assertNotNull(lowPower)
        assertTrue(lowPower!!.recommendation.contains("cable", ignoreCase = true))
    }

    @Test
    fun testDiagnostics_neverInventsCapacityDegradationWithoutCounters() {
        val diagnostics = BatteryDiagnostics.diagnose(
            telemetry = telemetry(capacityHealthFullUah = null, capacityHealthDesignUah = null),
            history = emptyList(), chargeRatePercentPerHour = null, drainRatePercentPerHour = null, interruptions = 0
        )
        assertTrue(
            "without counters there must be no degradation claim",
            diagnostics.none { it.title.contains("below 80%") }
        )
        assertTrue(
            "the limitation itself must be explained",
            diagnostics.any { it.title.contains("cannot be measured", ignoreCase = true) }
        )
    }

    @Test
    fun testDiagnostics_flagsRealMeasuredCapacityLoss() {
        val diagnostics = BatteryDiagnostics.diagnose(
            telemetry = telemetry(capacityHealthFullUah = 3_800_000, capacityHealthDesignUah = 5_000_000),
            history = emptyList(), chargeRatePercentPerHour = null, drainRatePercentPerHour = null, interruptions = 0
        )
        val degradation = diagnostics.firstOrNull { it.title.contains("below 80%") }
        assertNotNull("76% of design capacity is real measured loss", degradation)
        assertTrue(degradation!!.explanation.contains("76"))
    }

    @Test
    fun testCapacityHealth_isNullUnlessBothCountersExist() {
        assertNull(telemetry(capacityHealthFullUah = 4_000_000, capacityHealthDesignUah = null).capacityHealthPercent)
        assertNull(telemetry(capacityHealthFullUah = null, capacityHealthDesignUah = 5_000_000).capacityHealthPercent)
        val percent = telemetry(capacityHealthFullUah = 4_500_000, capacityHealthDesignUah = 5_000_000).capacityHealthPercent!!
        assertEquals(90f, percent, 0.01f)
    }

    @Test
    fun testHealthScore_dropsWithRealWearSignals() {
        val pristine = BatteryDiagnostics.healthScore(telemetry(), null, null, null)
        assertEquals(100, pristine)
        val worn = BatteryDiagnostics.healthScore(telemetry(), capacityHealthPercent = 72f, averageTemperatureCelsius = 41f, cycleCount = 900)
        // 100 - 22 (capacity) - 20 (heat) - 15 (cycles) = 43
        assertEquals(
            "worn score with capacity=72%, 41C avg, 900 cycles, health=${telemetry().health}",
            43,
            worn
        )
        assertTrue("wear signals must lower the score", worn < 50)
        assertTrue(worn >= 5)
        assertEquals("Worn", BatteryDiagnostics.healthLabel(worn))
        assertEquals("Excellent", BatteryDiagnostics.healthLabel(95))
        // Labels are bands of the same score, not independent numbers.
        assertEquals("Good", BatteryDiagnostics.healthLabel(80))
        assertEquals("Fair", BatteryDiagnostics.healthLabel(65))
        assertEquals("Degraded", BatteryDiagnostics.healthLabel(20))
    }

    @Test
    fun testFahrenheitConversion_matchesReferenceScreenshot() {
        val reading = telemetry(temperatureCelsius = 32.6f)
        assertEquals(90.68f, reading.temperatureFahrenheit!!, 0.01f)
        assertEquals("90.7", BatteryDiagnostics.format1(reading.temperatureFahrenheit!!))
    }

    @Test
    fun testSession_metricsDerivedFromStoredValues() {
        val session = BatterySession(
            id = 1,
            startedEpochMs = 0,
            endedEpochMs = 30 * 60_000L,
            startLevelPercent = 20,
            endLevelPercent = 40,
            peakTemperatureCelsius = 36.5f,
            averageVoltageVolts = 4.1f,
            averageCurrentMilliAmps = 1500f,
            peakPowerWatts = 6.2f,
            pluggedLabel = "AC charger",
            wasCharging = true,
            interruptions = 0
        )
        assertEquals(30, session.durationMinutes)
        assertEquals(20, session.levelDelta)
        assertEquals(40f, session.levelPercentPerHour!!, 0.01f)

        // A session that is too short never claims a rate.
        assertNull(session.copy(endedEpochMs = 5 * 60_000L).levelPercentPerHour)
    }

    @Test
    fun testReadingSources_areLabelledForTheUi() {
        assertEquals("Measured", BatteryReading.measured(1, "%").label)
        assertEquals("Computed", BatteryReading.computed(1, "W").label)
        assertEquals("Estimated", BatteryReading.estimated(1, "min").label)
        assertEquals("Not supported on this device", BatteryReading.unsupported<Int>("°C").label)
        assertEquals("Unavailable right now", BatteryReading.unavailable<Int>("mA").label)
        // A null value is never reported as measured.
        assertTrue(!BatteryReading.estimated<Int>(null, "min").isMeasured)
        assertTrue(!BatteryReading.unavailable<Int>("min").isMeasured)
        assertNull(BatteryReading.unsupported<Int>("mA").rawValue)
    }
}
