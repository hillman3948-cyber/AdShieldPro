package com.adshield.pro

import com.adshield.pro.domain.model.PrivateDnsCycleStep
import com.adshield.pro.domain.model.PrivateDnsHostnameOption
import com.adshield.pro.domain.model.PrivateDnsMode
import com.adshield.pro.domain.model.PrivateDnsSystemState
import com.adshield.pro.domain.model.PrivateDnsTileConfig
import com.adshield.pro.domain.model.TileVisualState
import com.adshield.pro.domain.model.UsbDebuggingSystemState
import com.adshield.pro.domain.model.UsbDebuggingTileConfig
import com.adshield.pro.engine.platform.SystemTileEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the Quick Settings system-preference tile rules (Private DNS cycling
 * across Off / Auto / Multiple Hostnames, hostname validation, USB Debugging toggling,
 * timer-based auto-revert, and honest tile presentation).
 */
class SystemTilesTestSuite {

    @Test
    fun testPrivateDnsModeFromGlobalSettingMapsKnownValues() {
        assertEquals(PrivateDnsMode.OFF, PrivateDnsMode.fromGlobalSetting("off"))
        assertEquals(PrivateDnsMode.OFF, PrivateDnsMode.fromGlobalSetting(" OFF "))
        assertEquals(PrivateDnsMode.AUTO, PrivateDnsMode.fromGlobalSetting("opportunistic"))
        assertEquals(PrivateDnsMode.AUTO, PrivateDnsMode.fromGlobalSetting(null))
        assertEquals(PrivateDnsMode.AUTO, PrivateDnsMode.fromGlobalSetting(""))
        assertEquals(PrivateDnsMode.HOSTNAME, PrivateDnsMode.fromGlobalSetting("hostname"))
    }

    @Test
    fun testPrivateDnsHostnameValidationAcceptsDoTHostsAndRejectsInvalidInputs() {
        assertEquals(
            "dns.adguard-dns.com",
            SystemTileEngine.validatePrivateDnsHostname("  DNS.AdGuard-DNS.com. ").getOrNull()
        )
        assertEquals(
            "one.one.one.one",
            SystemTileEngine.validatePrivateDnsHostname("one.one.one.one").getOrNull()
        )

        val invalid = listOf(
            "",
            "   ",
            "https://dns.google/dns-query",
            "dns.google:853",
            "1.1.1.1",
            "localhost",
            "bad host.com",
            "-leading.example.com",
            "trailing-.example.com",
            "empty..label.com",
            "123.456"
        )
        invalid.forEach { candidate ->
            assertTrue(
                "Expected '$candidate' to be rejected",
                SystemTileEngine.validatePrivateDnsHostname(candidate).isFailure
            )
        }
    }

    @Test
    fun testPrivateDnsCycleRespectsIncludedModesAndHostnames() {
        val config = PrivateDnsTileConfig(
            includeOffInCycle = true,
            includeAutoInCycle = true,
            hostnames = listOf(
                PrivateDnsHostnameOption("a", "AdGuard", "dns.adguard-dns.com", enabledInCycle = true),
                PrivateDnsHostnameOption("b", "Quad9", "dns.quad9.net", enabledInCycle = false),
                PrivateDnsHostnameOption("c", "Cloudflare", "one.one.one.one", enabledInCycle = true)
            )
        )

        val steps = SystemTileEngine.buildPrivateDnsCycle(config)
        assertEquals(4, steps.size)
        assertEquals(PrivateDnsCycleStep.Off, steps[0])
        assertEquals(PrivateDnsCycleStep.Auto, steps[1])
        assertEquals(PrivateDnsCycleStep.Hostname("AdGuard", "dns.adguard-dns.com"), steps[2])
        assertEquals(PrivateDnsCycleStep.Hostname("Cloudflare", "one.one.one.one"), steps[3])
    }

    @Test
    fun testPrivateDnsCycleAdvancesAndWrapsCorrectly() {
        val config = PrivateDnsTileConfig(
            includeOffInCycle = true,
            includeAutoInCycle = true,
            hostnames = listOf(
                PrivateDnsHostnameOption("a", "AdGuard", "dns.adguard-dns.com", enabledInCycle = true),
                PrivateDnsHostnameOption("c", "Cloudflare", "one.one.one.one", enabledInCycle = true)
            )
        )

        val step1 = SystemTileEngine.nextPrivateDnsStep(
            PrivateDnsSystemState(PrivateDnsMode.OFF, null), config
        )
        assertEquals(PrivateDnsCycleStep.Auto, step1)

        val step2 = SystemTileEngine.nextPrivateDnsStep(
            PrivateDnsSystemState(PrivateDnsMode.AUTO, null), config
        )
        assertEquals(PrivateDnsCycleStep.Hostname("AdGuard", "dns.adguard-dns.com"), step2)

        val step3 = SystemTileEngine.nextPrivateDnsStep(
            PrivateDnsSystemState(PrivateDnsMode.HOSTNAME, "DNS.AdGuard-DNS.COM"), config
        )
        assertEquals(PrivateDnsCycleStep.Hostname("Cloudflare", "one.one.one.one"), step3)

        val step4 = SystemTileEngine.nextPrivateDnsStep(
            PrivateDnsSystemState(PrivateDnsMode.HOSTNAME, "one.one.one.one"), config
        )
        assertEquals(PrivateDnsCycleStep.Off, step4)

        val unlisted = SystemTileEngine.nextPrivateDnsStep(
            PrivateDnsSystemState(PrivateDnsMode.HOSTNAME, "custom.other.example"), config
        )
        assertEquals(PrivateDnsCycleStep.Off, unlisted)
    }

    @Test
    fun testPrivateDnsCycleFallsBackSafelyWhenAllStepsDisabled() {
        val config = PrivateDnsTileConfig(
            includeOffInCycle = false,
            includeAutoInCycle = false,
            hostnames = emptyList()
        )
        assertEquals(
            listOf(PrivateDnsCycleStep.Off, PrivateDnsCycleStep.Auto),
            SystemTileEngine.buildPrivateDnsCycle(config)
        )
    }

    @Test
    fun testPrivateDnsAddMoveAndRemoveCustomHostnames() {
        val base = PrivateDnsTileConfig(
            hostnames = listOf(
                PrivateDnsHostnameOption("builtin-1", "AdGuard", "dns.adguard-dns.com", true, isBuiltIn = true),
                PrivateDnsHostnameOption("builtin-2", "Cloudflare", "one.one.one.one", true, isBuiltIn = true)
            )
        )

        val dup = SystemTileEngine.addCustomHostname(base, "Dup", "DNS.AdGuard-DNS.com") { "custom-1" }
        assertTrue(dup.isFailure)

        val added = SystemTileEngine.addCustomHostname(base, "Work DNS", "work.dns.example") { "custom-1" }
        assertTrue(added.isSuccess)
        val afterAdd = added.getOrThrow()
        assertEquals(3, afterAdd.hostnames.size)
        assertEquals("custom-1", afterAdd.hostnames.last().id)

        val moved = SystemTileEngine.moveHostname(afterAdd.hostnames, "custom-1", -1)
        assertEquals(listOf("builtin-1", "custom-1", "builtin-2"), moved.map { it.id })

        // Built-in presets cannot be deleted, custom ones can
        val stillHasBuiltin = SystemTileEngine.removeHostname(afterAdd, "builtin-1")
        assertEquals(3, stillHasBuiltin.hostnames.size)

        val removedCustom = SystemTileEngine.removeHostname(afterAdd, "custom-1")
        assertEquals(2, removedCustom.hostnames.size)
    }

    @Test
    fun testPrivateDnsAutoRevertPreservesOriginalTargetAcrossMultipleTapsAndCancelsOnReturn() {
        val now = 1_700_000_000_000L
        val config = PrivateDnsTileConfig(
            autoRevertEnabled = true,
            autoRevertDelayMinutes = 10
        )

        // Tap 1: Auto -> Off (captures Auto as revert target)
        val afterTap1 = SystemTileEngine.onPrivateDnsChangedViaTile(
            previous = PrivateDnsSystemState(PrivateDnsMode.AUTO, null, readable = true),
            next = PrivateDnsCycleStep.Off,
            config = config,
            nowEpochMs = now
        )
        assertEquals(PrivateDnsMode.AUTO, afterTap1.autoRevertTargetMode)
        assertEquals(now + 10 * 60_000L, afterTap1.autoRevertDeadlineEpochMs)

        // Tap 2 before deadline: Off -> Hostname (keeps original Auto target!)
        val afterTap2 = SystemTileEngine.onPrivateDnsChangedViaTile(
            previous = PrivateDnsSystemState(PrivateDnsMode.OFF, null, readable = true),
            next = PrivateDnsCycleStep.Hostname("AdGuard", "dns.adguard-dns.com"),
            config = afterTap1,
            nowEpochMs = now + 5_000L
        )
        assertEquals(PrivateDnsMode.AUTO, afterTap2.autoRevertTargetMode)
        assertNotNull(SystemTileEngine.privateDnsTargetStep(afterTap2))

        // Due check
        assertFalse(
            SystemTileEngine.isPrivateDnsRevertDue(
                PrivateDnsSystemState(PrivateDnsMode.HOSTNAME, "dns.adguard-dns.com"),
                afterTap2,
                now + 30_000L
            )
        )
        assertTrue(
            SystemTileEngine.isPrivateDnsRevertDue(
                PrivateDnsSystemState(PrivateDnsMode.HOSTNAME, "dns.adguard-dns.com"),
                afterTap2,
                afterTap2.autoRevertDeadlineEpochMs
            )
        )

        // Tap 3 returns to Auto -> cancels pending revert timer
        val afterTap3 = SystemTileEngine.onPrivateDnsChangedViaTile(
            previous = PrivateDnsSystemState(PrivateDnsMode.HOSTNAME, "dns.adguard-dns.com"),
            next = PrivateDnsCycleStep.Auto,
            config = afterTap2,
            nowEpochMs = now + 10_000L
        )
        assertNull(afterTap3.autoRevertTargetMode)
        assertEquals(0L, afterTap3.autoRevertDeadlineEpochMs)
    }

    @Test
    fun testPrivateDnsAutoRevertDoesNotCaptureUnreadableSystemState() {
        val now = 1_700_000_000_000L
        val config = PrivateDnsTileConfig(autoRevertEnabled = true, autoRevertDelayMinutes = 15)
        val updated = SystemTileEngine.onPrivateDnsChangedViaTile(
            previous = PrivateDnsSystemState(PrivateDnsMode.AUTO, null, readable = false),
            next = PrivateDnsCycleStep.Off,
            config = config,
            nowEpochMs = now
        )
        assertNull(updated.autoRevertTargetMode)
        assertEquals(0L, updated.autoRevertDeadlineEpochMs)
    }

    @Test
    fun testUsbDebuggingAutoRevertArmsOnEnableAndClearsOnDisable() {
        val now = 1_700_000_000_000L
        val config = UsbDebuggingTileConfig(autoRevertEnabled = true, autoRevertDelayMinutes = 15)

        val enabled = SystemTileEngine.onUsbDebuggingToggled(true, config, now)
        assertEquals(now + 15 * 60_000L, enabled.autoRevertDeadlineEpochMs)

        // Not yet due well before deadline
        assertFalse(SystemTileEngine.isUsbDebuggingRevertDue(true, enabled, now + 60_000L))
        // Due within early wake tolerance and at exact deadline
        assertTrue(
            SystemTileEngine.isUsbDebuggingRevertDue(
                true,
                enabled,
                enabled.autoRevertDeadlineEpochMs - 2_000L
            )
        )
        // Never due if ADB is already Off
        assertFalse(
            SystemTileEngine.isUsbDebuggingRevertDue(
                false,
                enabled,
                enabled.autoRevertDeadlineEpochMs + 1_000L
            )
        )

        val disabled = SystemTileEngine.onUsbDebuggingToggled(false, enabled, now + 30_000L)
        assertEquals(0L, disabled.autoRevertDeadlineEpochMs)
    }

    @Test
    fun testDelayMinutesAreClampedBetweenOneMinuteAndTwentyFourHours() {
        assertEquals(1, SystemTileEngine.clampDelayMinutes(0))
        assertEquals(1, SystemTileEngine.clampDelayMinutes(-10))
        assertEquals(15, SystemTileEngine.clampDelayMinutes(15))
        assertEquals(1440, SystemTileEngine.clampDelayMinutes(9999))
    }

    @Test
    fun testTilePresentationsReflectPermissionAndRealSystemState() {
        val now = 1_700_000_000_000L
        val dnsCfg = PrivateDnsTileConfig()
        val usbCfg = UsbDebuggingTileConfig(autoRevertEnabled = true, autoRevertDelayMinutes = 15)

        val dnsNeedsSetup = SystemTileEngine.presentPrivateDnsTile(
            PrivateDnsSystemState(),
            dnsCfg,
            hasPermission = false,
            nowEpochMs = now
        )
        assertEquals(TileVisualState.INACTIVE, dnsNeedsSetup.state)
        assertEquals("Tap for setup", dnsNeedsSetup.subtitle)

        val dnsHostActive = SystemTileEngine.presentPrivateDnsTile(
            PrivateDnsSystemState(PrivateDnsMode.HOSTNAME, "dns.adguard-dns.com", readable = true),
            dnsCfg,
            hasPermission = true,
            nowEpochMs = now
        )
        assertEquals(TileVisualState.ACTIVE, dnsHostActive.state)
        assertEquals("AdGuard DNS", dnsHostActive.subtitle)

        val usbOff = SystemTileEngine.presentUsbDebuggingTile(
            UsbDebuggingSystemState(enabled = false, developerOptionsEnabled = true, readable = true),
            usbCfg,
            hasPermission = true,
            nowEpochMs = now
        )
        assertEquals(TileVisualState.INACTIVE, usbOff.state)
        assertTrue(usbOff.subtitle.contains("Off"))

        val usbArmed = SystemTileEngine.presentUsbDebuggingTile(
            UsbDebuggingSystemState(enabled = true, developerOptionsEnabled = true, readable = true),
            usbCfg.copy(autoRevertDeadlineEpochMs = now + 15 * 60_000L),
            hasPermission = true,
            nowEpochMs = now
        )
        assertEquals(TileVisualState.ACTIVE, usbArmed.state)
        assertEquals("On · 15m", usbArmed.subtitle)
    }

    @Test
    fun testQuickSetupCommandFormattingAndChecklistEvaluation() {
        assertEquals(
            "adb shell pm grant com.adshield.pro.debug android.permission.WRITE_SECURE_SETTINGS",
            SystemTileEngine.buildAdbGrantCommand(" com.adshield.pro.debug ")
        )
        assertEquals(
            "pm grant com.adshield.pro android.permission.WRITE_SECURE_SETTINGS",
            SystemTileEngine.buildLocalShellGrantCommand("com.adshield.pro")
        )

        val pendingWithRoot = SystemTileEngine.evaluateQuickSetupChecklist(
            hasPermission = false,
            developerOptionsEnabled = false,
            hasSuBinary = true,
            isShizukuInstalled = true
        )
        assertFalse(pendingWithRoot.developerOptionsReady)
        assertFalse(pendingWithRoot.permissionGranted)
        assertFalse(pendingWithRoot.tilesReadyToUse)
        assertTrue(pendingWithRoot.rootQuickGrantAvailable)
        assertTrue(pendingWithRoot.shizukuDetected)

        val ready = SystemTileEngine.evaluateQuickSetupChecklist(
            hasPermission = true,
            developerOptionsEnabled = false,
            hasSuBinary = true,
            isShizukuInstalled = false
        )
        assertTrue(ready.developerOptionsReady)
        assertTrue(ready.permissionGranted)
        assertTrue(ready.tilesReadyToUse)
        assertFalse(ready.rootQuickGrantAvailable)
    }
}
