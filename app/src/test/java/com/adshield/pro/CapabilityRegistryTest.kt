package com.adshield.pro

import com.adshield.pro.engine.platform.CapabilityRegistry
import com.adshield.pro.engine.platform.CapabilityStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the capability registry — the app's own honesty contract.
 *
 * If a capability is marked implemented it must name the code that implements it, that file must
 * exist on disk, and it must say how it is verified. Rows claiming PLANNED/UNAVAILABLE must not
 * point at code (so a half-finished module cannot masquerade as shipping).
 */
class CapabilityRegistryTest {

    /**
     * The Gradle unit-test task's working directory is not guaranteed across AGP versions, so
     * the source root is discovered instead of assumed: walk up from the module dir until the
     * package directory containing this registry exists.
     */
    private val sourceRoot: File by lazy {
        val candidates = listOf(
            File("src/main/java/com/adshield/pro"),
            File("app/src/main/java/com/adshield/pro"),
            File(System.getProperty("user.dir"), "app/src/main/java/com/adshield/pro"),
            File(System.getProperty("user.dir"), "src/main/java/com/adshield/pro")
        )
        candidates.firstOrNull { it.isDirectory }
            ?: throw AssertionError("could not locate the app source root; looked in " +
                candidates.joinToString { it.absolutePath })
    }

    /**
     * Repository root, for capabilities implemented outside src/ (CI workflow, tools).
     * Found by walking up from the package directory until a directory that contains both the
     * app module and .github is reached, so an off-by-one in the depth cannot break the build.
     */
    private val repoRoot: File by lazy {
        var dir: File? = sourceRoot
        while (dir != null) {
            if (File(dir, "app").isDirectory && File(dir, ".github").isDirectory) return@lazy dir
            dir = dir.parentFile
        }
        throw AssertionError("could not locate the repository root above ${sourceRoot.absolutePath}")
    }

    /** Resolves a registry code pointer, which may live either in the app sources or in CI/tools. */
    private fun codeFile(path: String): File =
        File(sourceRoot, path).takeIf { it.exists() } ?: File(repoRoot, path)

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
                "${capability.id}: ${capability.code} does not exist (registry is out of date). " +
                    "repoRoot=${repoRoot.absolutePath}",
                codeFile(capability.code).isFile
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
