package com.adshield.pro

import com.adshield.pro.engine.platform.CapabilityRegistry
import com.adshield.pro.engine.platform.CapabilityStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the capability registry — the app's own honesty contract.
 *
 * A capability marked implemented must name the code that implements it and must say how it is
 * verified; a PLANNED/UNAVAILABLE row must not point at code (so a half-finished module cannot
 * masquerade as shipping). File existence is checked by tools/generate_capability_matrix.py in
 * CI, which runs from the repository root.
 */
class CapabilityRegistryTest {

    // Source-file verification lives in tools/generate_capability_matrix.py (it runs from the
    // repository root in CI with a deterministic working directory); the JVM test asserts the
    // structural rules that do not need the filesystem.

    @Test
    fun testEveryCapabilityHasUniqueIdAndHonestStatus() {
        val ids = CapabilityRegistry.capabilities.map { it.id }
        assertEquals("capability ids must be unique", ids.size, ids.toSet().size)
        assertTrue("registry must stay non-trivial", CapabilityRegistry.capabilities.size >= 40)
    }

    @Test
    fun testImplementedCapabilitiesPointAtRealCode() {
        val implemented = CapabilityRegistry.capabilities.filter {
            it.status == CapabilityStatus.IMPLEMENTED || it.status == CapabilityStatus.PARTIAL
        }
        assertTrue(implemented.isNotEmpty())
        implemented.forEach { capability ->
            assertTrue(
                "${capability.id}: implemented capability must name its implementation file",
                capability.code.isNotBlank()
            )
            assertTrue(
                "${capability.id}: ${capability.code} must look like a source path",
                capability.code.endsWith(".kt") || capability.code.endsWith(".yml") || capability.code.endsWith(".py")
            )
            assertTrue(
                "${capability.id}: must document verification",
                capability.verification.isNotBlank() && capability.verification != "n/a — architecture only"
            )
            assertTrue(
                "${capability.id}: verification text is '${capability.verification}'",
                capability.verification.isNotBlank()
            )
            assertTrue(
                "${capability.id}: must document at least one data source",
                capability.dataSources.isNotEmpty()
            )
            assertTrue(
                "${capability.id}: must document its limitations",
                capability.limitations.isNotEmpty()
            )
        }
    }

    @Test
    fun testUnavailableCapabilitiesClaimNoCode() {
        CapabilityRegistry.capabilities
            .filter { it.status == CapabilityStatus.PLANNED || it.status == CapabilityStatus.UNAVAILABLE }
            .forEach { capability ->
                assertEquals(
                    "${capability.id}: a non-shipping capability must not point at code",
                    "",
                    capability.code
                )
                assertTrue(
                    "${capability.id}: must explain why it is not shipping",
                    capability.limitations.isNotEmpty()
                )
            }
    }

    @Test
    fun testNoCapabilityClaimsCloudAiOrChargeControl() {
        // Two explicit product promises: no cloud AI, no charging-current control claims.
        CapabilityRegistry.capabilities.forEach { capability ->
            val claimsControl = capability.status == CapabilityStatus.IMPLEMENTED &&
                capability.name.contains("Control charging", ignoreCase = true)
            assertTrue("${capability.id}: charging control cannot be implemented on Android", !claimsControl)
        }
        val cloudAi = CapabilityRegistry.capabilities.first { it.id == "ai-cloud-model" }
        assertEquals(CapabilityStatus.UNAVAILABLE, cloudAi.status)
        val chargeControl = CapabilityRegistry.capabilities.first { it.id == "battery-charge-control" }
        assertEquals(CapabilityStatus.UNAVAILABLE, chargeControl.status)
        val fingerprint = CapabilityRegistry.capabilities.first { it.id == "browser-fingerprint" }
        assertEquals(CapabilityStatus.UNAVAILABLE, fingerprint.status)
    }

    @Test
    fun testSummaryCountsMatchTheRegistry() {
        val summary = CapabilityRegistry.summary()
        assertTrue(summary.contains(CapabilityRegistry.count(CapabilityStatus.IMPLEMENTED).toString()))
        val total = CapabilityRegistry.capabilities.size
        assertEquals(
            total,
            CapabilityStatus.entries.sumOf { CapabilityRegistry.count(it) }
        )
    }
}
