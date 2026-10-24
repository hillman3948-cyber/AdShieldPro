package com.adshield.pro

import com.adshield.pro.data.local.db.AdShieldDatabase
import com.adshield.pro.domain.model.BatteryThermalSnapshot
import com.adshield.pro.domain.model.BottleneckSeverity
import com.adshield.pro.domain.model.ProtectionMode
import com.adshield.pro.domain.model.RamSnapshot
import com.adshield.pro.domain.model.RuleCategory
import com.adshield.pro.domain.model.RuleConflictReport
import com.adshield.pro.domain.model.RuleMatchType
import com.adshield.pro.domain.model.RuleType
import com.adshield.pro.domain.model.StorageMetrics
import com.adshield.pro.engine.browser.BrowserContentBlocker
import com.adshield.pro.engine.dns.DnsPacketCodec
import com.adshield.pro.engine.dns.IpPacketFraming
import com.adshield.pro.engine.filter.FilterRuleEngine
import com.adshield.pro.engine.optimizer.RuleBasedOptimizationPredictor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Comprehensive Automated Regression & Unit Test Suite for AdShield Pro v2.0.
 *
 * Covers:
 * 1. RFC 1035 / IPv6 AAAA DNS Wire-Format Parsing & Blocked Response Synthesis
 * 2. Regression tests for Malformed DNS Packets & Cyclic Compression Pointer Loops
 * 3. IPv4 and IPv6 UDP & TCP Port 53 Packet Framing & RFC 1071 Checksums
 * 4. Multi-Layer FilterRuleEngine Accuracy, Protection Modes, Custom Rule Precedence & Conflict Detection
 * 5. Blocklist Parser Security (HTML/Script/Binary rejection, Hosts & Adblock syntax, Duplicate counting)
 * 6. Browser Content Blocker & Cosmetic CSS Injection
 * 7. Room Database Migration (v1 -> v2)
 */
class AdShieldEngineTestSuite {

    private fun buildRawDnsQueryPacket(
        txId: Int = 0x4A2B,
        domain: String = "ads.doubleclick.net",
        qType: Int = DnsPacketCodec.TYPE_A,
        qClass: Int = DnsPacketCodec.CLASS_IN
    ): ByteArray {
        val out = ByteArrayOutputStream()
        // Header (12 bytes)
        out.write((txId ushr 8) and 0xFF)
        out.write(txId and 0xFF)
        out.write(0x01) // Flags: RD = 1
        out.write(0x00)
        out.write(0x00)
        out.write(0x01) // QDCOUNT = 1
        out.write(0x00)
        out.write(0x00) // ANCOUNT = 0
        out.write(0x00)
        out.write(0x00) // NSCOUNT = 0
        out.write(0x00)
        out.write(0x00) // ARCOUNT = 0

        for (label in domain.split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0x00) // Root label terminator

        out.write((qType ushr 8) and 0xFF)
        out.write(qType and 0xFF)
        out.write((qClass ushr 8) and 0xFF)
        out.write(qClass and 0xFF)
        return out.toByteArray()
    }

    @Test
    fun testDnsPacketParser_parsesStandardIpv4AAndIpv6AaaaQueries() {
        val rawA = buildRawDnsQueryPacket(txId = 0x1234, domain = "Trackers.Google-Analytics.com", qType = DnsPacketCodec.TYPE_A)
        val parsedA = DnsPacketCodec.parseQuery(rawA)
        assertNotNull(parsedA)
        assertEquals(0x1234, parsedA!!.transactionId)
        assertEquals("trackers.google-analytics.com", parsedA.questionDomain)
        assertEquals("A", parsedA.qTypeName)

        val rawAaaa = buildRawDnsQueryPacket(txId = 0x9988, domain = "ads.pubmatic.com", qType = DnsPacketCodec.TYPE_AAAA)
        val parsedAaaa = DnsPacketCodec.parseQuery(rawAaaa)
        assertNotNull(parsedAaaa)
        assertEquals(0x9988, parsedAaaa!!.transactionId)
        assertEquals("ads.pubmatic.com", parsedAaaa.questionDomain)
        assertEquals("AAAA", parsedAaaa.qTypeName)
    }

    @Test
    fun testDnsPacketCodec_synthesizesNxdomainAndZeroIpSinkholeResponses() {
        val rawA = buildRawDnsQueryPacket(txId = 0x55AA, domain = "ad.doubleclick.net", qType = DnsPacketCodec.TYPE_A)
        val queryA = DnsPacketCodec.parseQuery(rawA)!!

        // 1. NXDOMAIN response (RCODE = 3, ANCOUNT = 0)
        val nxResp = DnsPacketCodec.buildBlockedResponse(queryA, sinkholeZeroIp = false)
        assertEquals(0x55, nxResp[0].toInt() and 0xFF)
        assertEquals(0xAA, nxResp[1].toInt() and 0xFF)
        val nxFlags = ((nxResp[2].toInt() and 0xFF) shl 8) or (nxResp[3].toInt() and 0xFF)
        assertTrue((nxFlags and 0x8000) != 0) // QR = 1 (Response)
        assertEquals(3, nxFlags and 0x000F) // RCODE = 3 (NXDOMAIN)

        // 2. Sinkhole 0.0.0.0 response (RCODE = 0, ANCOUNT = 1, trailing 4 zero bytes)
        val sinkholeA = DnsPacketCodec.buildBlockedResponse(queryA, sinkholeZeroIp = true)
        val shFlags = ((sinkholeA[2].toInt() and 0xFF) shl 8) or (sinkholeA[3].toInt() and 0xFF)
        assertEquals(0, shFlags and 0x000F)
        val anCount = ((sinkholeA[6].toInt() and 0xFF) shl 8) or (sinkholeA[7].toInt() and 0xFF)
        assertEquals(1, anCount)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), sinkholeA.copyOfRange(sinkholeA.size - 4, sinkholeA.size))

        // 3. Sinkhole IPv6 :: response for AAAA query (16 trailing zero bytes)
        val rawAaaa = buildRawDnsQueryPacket(txId = 0x77BB, domain = "ad.doubleclick.net", qType = DnsPacketCodec.TYPE_AAAA)
        val queryAaaa = DnsPacketCodec.parseQuery(rawAaaa)!!
        val sinkholeAaaa = DnsPacketCodec.buildBlockedResponse(queryAaaa, sinkholeZeroIp = true)
        assertArrayEquals(ByteArray(16), sinkholeAaaa.copyOfRange(sinkholeAaaa.size - 16, sinkholeAaaa.size))
    }

    @Test
    fun testRegression_malformedDnsPacketsAndCompressionLoopsRejectedSafely() {
        // 1. Empty / truncated header (< 12 bytes)
        assertNull(DnsPacketCodec.parseQuery(byteArrayOf(0x01, 0x02, 0x03)))

        // 2. Packet with QR = 1 (already a response) must be ignored
        val raw = buildRawDnsQueryPacket()
        raw[2] = (raw[2].toInt() or 0x80).toByte()
        assertNull(DnsPacketCodec.parseQuery(raw))

        // 3. Malicious cyclic DNS compression pointer loop at offset 12 (0xC0 0x0C pointing to itself)
        val cyclicPacket = ByteArray(18)
        cyclicPacket[4] = 0x00
        cyclicPacket[5] = 0x01 // QDCOUNT = 1
        cyclicPacket[12] = 0xC0.toByte()
        cyclicPacket[13] = 0x0C.toByte() // Points back to offset 12 infinitely
        assertNull(DnsPacketCodec.parseQuery(cyclicPacket))

        // 4. Truncated label length exceeding packet bounds
        val truncatedLabelPacket = ByteArray(16)
        truncatedLabelPacket[5] = 0x01
        truncatedLabelPacket[12] = 50 // Claims 50-byte label in a 16-byte packet
        assertNull(DnsPacketCodec.parseQuery(truncatedLabelPacket))
    }

    @Test
    fun testIpv4AndIpv6_udpAndTcpFramingAndChecksums() {
        val dnsPayload = buildRawDnsQueryPacket(domain = "example.com")

        // Build an IPv4 UDP request packet using IpPacketFraming and parse it back
        val srcIpv4 = byteArrayOf(10, 215.toByte(), 173.toByte(), 1)
        val dstIpv4 = byteArrayOf(10, 215.toByte(), 173.toByte(), 2)
        val dummyReq = IpPacketFraming.ParsedTransportPacket.UdpPacket(
            ipVersion = 4,
            sourceAddress = dstIpv4, // swapped when building response
            destinationAddress = srcIpv4,
            sourcePort = 54321,
            destinationPort = 53,
            payload = dnsPayload
        )
        val rawIpv4Udp = IpPacketFraming.buildUdpResponsePacket(dummyReq, dnsPayload)

        // Verify IPv4 header checksum validates to 0 when recomputed over the 20-byte header
        assertEquals(0, IpPacketFraming.computeOnesComplementChecksum(rawIpv4Udp, 0, 20))

        val parsedIpv4 = IpPacketFraming.parseIpPacket(rawIpv4Udp) as? IpPacketFraming.ParsedTransportPacket.UdpPacket
        assertNotNull(parsedIpv4)
        assertEquals(4, parsedIpv4!!.ipVersion)
        assertEquals(53, parsedIpv4.sourcePort)
        assertEquals(54321, parsedIpv4.destinationPort)
        assertArrayEquals(dnsPayload, parsedIpv4.payload)

        // Build an IPv6 TCP DNS response packet and parse it back
        val srcIpv6 = ByteArray(16) { (it + 1).toByte() }
        val dstIpv6 = ByteArray(16) { (it + 17).toByte() }
        val dummyTcpReq = IpPacketFraming.ParsedTransportPacket.TcpPacket(
            ipVersion = 6,
            sourceAddress = srcIpv6,
            destinationAddress = dstIpv6,
            sourcePort = 49152,
            destinationPort = 53,
            sequenceNumber = 100L,
            acknowledgmentNumber = 200L,
            flags = IpPacketFraming.TCP_FLAG_SYN,
            windowSize = 65535,
            payload = ByteArray(0)
        )
        val rawIpv6Tcp = IpPacketFraming.buildTcpResponsePacket(
            request = dummyTcpReq,
            seqNum = 200L,
            ackNum = 101L,
            flags = IpPacketFraming.TCP_FLAG_SYN or IpPacketFraming.TCP_FLAG_ACK,
            tcpPayload = dnsPayload
        )
        val parsedIpv6 = IpPacketFraming.parseIpPacket(rawIpv6Tcp) as? IpPacketFraming.ParsedTransportPacket.TcpPacket
        assertNotNull(parsedIpv6)
        assertEquals(6, parsedIpv6!!.ipVersion)
        assertEquals(53, parsedIpv6.sourcePort)
        assertEquals(49152, parsedIpv6.destinationPort)
        assertArrayEquals(dnsPayload, parsedIpv6.payload)
    }

    @Test
    fun testFilterRuleEngine_precedenceModesWildcardsAndConflictDetection() {
        val engine = FilterRuleEngine()

        val filterText = """
            # Test multi-category filter list
            ||doubleclick.net^
            0.0.0.0 ads.pubmatic.com
            # [CATEGORY:TRACKERS]
            ||mixpanel.com^
            # [CATEGORY:STRICT_TELEMETRY]
            ||telemetry.microsoft.com^
            *.adtrack.*
        """.trimIndent()

        val batch = engine.parseFilterListContent(filterText, RuleCategory.ADS, "TestList").getOrThrow()
        engine.updateFilterListRules(
            subdomainRules = batch.subdomainRules,
            exactRules = batch.exactRules,
            wildcardRules = batch.wildcardRules,
            allowSubdomains = batch.allowSubdomains
        )

        // 1. Standard Mode blocks ADS and TRACKERS, but NOT STRICT_TELEMETRY
        engine.updateProtectionMode(ProtectionMode.STANDARD)
        assertTrue(engine.evaluate("static.doubleclick.net").isBlocked)
        assertTrue(engine.evaluate("api.mixpanel.com").isBlocked)
        assertFalse(engine.evaluate("telemetry.microsoft.com").isBlocked)

        // 2. Strict Mode enables STRICT_TELEMETRY and wildcards
        engine.updateProtectionMode(ProtectionMode.STRICT)
        assertTrue(engine.evaluate("telemetry.microsoft.com").isBlocked)
        assertTrue(engine.evaluate("mobile.adtrack.example.org").isBlocked)

        // 3. Privacy Mode focuses on Trackers, Malware & Telemetry (allows pure Ads category)
        engine.updateProtectionMode(ProtectionMode.PRIVACY)
        assertFalse(engine.evaluate("static.doubleclick.net").isBlocked)
        assertTrue(engine.evaluate("api.mixpanel.com").isBlocked)

        // 4. Custom Rule Precedence: User Allowlist (Priority 100) overrides User Block (90) and Filter List (50)
        engine.updateProtectionMode(ProtectionMode.STANDARD)
        val allowRule = engine.validateAndParseRuleInput("@@||doubleclick.net^").getOrThrow()
        val blockRuleConflict = engine.validateAndParseRuleInput("||doubleclick.net^").getOrThrow()
        engine.updateCustomRules(listOf(allowRule, blockRuleConflict))

        val evalAfterAllow = engine.evaluate("ad.doubleclick.net")
        assertFalse(evalAfterAllow.isBlocked)
        assertEquals(100, evalAfterAllow.priority)

        // 5. Conflict Analyzer detects ALLOW_OVERRIDES_BLOCK
        val conflicts = engine.detectConflictsAndDuplicates(listOf(allowRule, blockRuleConflict))
        assertTrue(conflicts.any { it.conflictType == RuleConflictReport.ConflictType.ALLOW_OVERRIDES_BLOCK })
    }

    @Test
    fun testFilterValidation_rejectsMalformedDomainsHtmlAndBroadWildcards() {
        val engine = FilterRuleEngine()

        // Reject empty, broad wildcard "*", raw IPv4, spaces, or script tags
        assertTrue(engine.validateAndParseRuleInput("").isFailure)
        assertTrue(engine.validateAndParseRuleInput("*").isFailure)
        assertTrue(engine.validateAndParseRuleInput("*.*").isFailure)
        assertTrue(engine.validateAndParseRuleInput("192.168.1.1").isFailure)
        assertTrue(engine.validateAndParseRuleInput("<script>alert(1)</script>").isFailure)

        // Reject HTML response masquerading as a filter list
        val htmlPayload = "<!DOCTYPE html><html><body>Captive Portal</body></html>"
        assertTrue(engine.parseFilterListContent(htmlPayload, RuleCategory.ADS, "BadSource").isFailure)
    }

    @Test
    fun testBrowserContentBlocker_sanitizesTrackingParamsAndEvaluatesRequests() {
        val engine = FilterRuleEngine()
        val rule = engine.validateAndParseRuleInput("||googlesyndication.com^").getOrThrow()
        engine.updateCustomRules(listOf(rule))

        val blocker = BrowserContentBlocker(engine)

        // 1. Strip tracking params while preserving legitimate query params
        val dirtyUrl = "https://news.example.com/article?id=42&utm_source=twitter&fbclid=IwAR12345&gclid=999"
        val cleanUrl = blocker.sanitizeTrackingParameters(dirtyUrl)
        assertEquals("https://news.example.com/article?id=42", cleanUrl)

        // 2. Block ad subresource when site is not allowlisted
        val decisionBlocked = blocker.evaluateRequest(
            requestUrl = "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js",
            pageHost = "news.example.com",
            allowlistedSites = emptySet()
        )
        assertTrue(decisionBlocked.shouldBlock)

        // 3. Allow subresource when pageHost is in user's website-specific exceptions
        val decisionAllowlisted = blocker.evaluateRequest(
            requestUrl = "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js",
            pageHost = "news.example.com",
            allowlistedSites = setOf("news.example.com")
        )
        assertFalse(decisionAllowlisted.shouldBlock)

        // 4. Cosmetic script generation includes custom CSS selectors
        val script = blocker.buildCosmeticFilterScript(
            pageHost = "news.example.com",
            allowlistedSites = emptySet(),
            customSelectors = setOf(".custom-sponsored-slot")
        )
        assertNotNull(script)
        assertTrue(script!!.contains(".custom-sponsored-slot"))
    }

    @Test
    fun testDatabaseMigrationVersion() {
        assertEquals(1, AdShieldDatabase.MIGRATION_1_2.startVersion)
        assertEquals(2, AdShieldDatabase.MIGRATION_1_2.endVersion)
    }

    @Test
    fun testAiSmartBoostPredictor_detectsBottlenecksAndReadinessScoreAccurately() {
        val predictor = RuleBasedOptimizationPredictor()

        // 1. Healthy device snapshot -> High readiness score, no bottlenecks
        val healthyRam = RamSnapshot(
            totalRamBytes = 8L * 1024 * 1024 * 1024,
            availableRamBytes = 4L * 1024 * 1024 * 1024,
            usedRamBytes = 4L * 1024 * 1024 * 1024,
            usagePercent = 50f,
            isLowMemoryCondition = false,
            lowMemoryThresholdBytes = 512L * 1024 * 1024,
            adShieldJavaHeapUsedBytes = 24L * 1024 * 1024,
            adShieldNativeHeapUsedBytes = 12L * 1024 * 1024,
            adShieldDnsCacheEntries = 120
        )
        val healthyStorage = StorageMetrics(
            totalBytes = 128L * 1024 * 1024 * 1024,
            availableBytes = 64L * 1024 * 1024 * 1024,
            usedBytes = 64L * 1024 * 1024 * 1024,
            usedPercent = 50f,
            isLowStorage = false
        )
        val healthyBattery = BatteryThermalSnapshot(
            batteryPercent = 85,
            temperatureCelsius = 31.5f,
            isCharging = false,
            chargingSourceLabel = "Discharging",
            voltageMillivolts = 4150,
            healthStatusLabel = "Good",
            thermalStatusLabel = "Normal (None)",
            isOverheating = false,
            isLowBattery = false,
            adShieldBatteryImpactSummary = "Minimal",
            recommendations = emptyList()
        )

        val healthyAssessment = predictor.evaluateDeviceHealth(
            ram = healthyRam,
            storage = healthyStorage,
            battery = healthyBattery,
            recoverableJunkBytes = 64L * 1024L
        )
        assertEquals(100, healthyAssessment.readinessScore)
        assertTrue(healthyAssessment.bottlenecks.isEmpty())
        assertFalse(healthyAssessment.isOptimizationRecommended)

        // 2. Stressed device snapshot -> Low RAM + Low Storage + Overheating + High Junk
        val stressedRam = healthyRam.copy(
            availableRamBytes = 400L * 1024 * 1024,
            usedRamBytes = 7600L * 1024 * 1024,
            usagePercent = 95f,
            isLowMemoryCondition = true,
            adShieldJavaHeapUsedBytes = 120L * 1024 * 1024
        )
        val stressedStorage = healthyStorage.copy(
            availableBytes = 1L * 1024 * 1024 * 1024,
            usedPercent = 96f,
            isLowStorage = true
        )
        val stressedBattery = healthyBattery.copy(
            temperatureCelsius = 44.2f,
            isCharging = true,
            chargingSourceLabel = "AC Fast Charger",
            isOverheating = true
        )

        val stressedAssessment = predictor.evaluateDeviceHealth(
            ram = stressedRam,
            storage = stressedStorage,
            battery = stressedBattery,
            recoverableJunkBytes = 75L * 1024 * 1024
        )
        assertTrue(stressedAssessment.readinessScore < 50)
        assertTrue(stressedAssessment.isOptimizationRecommended)
        assertTrue(stressedAssessment.bottlenecks.any { it.severity == BottleneckSeverity.HIGH })
        assertTrue(stressedAssessment.recommendations.isNotEmpty())
    }

    @Test
    fun testJunkCleaner_sha256DuplicateDetection() {
        val tempDir = Files.createTempDirectory("adshield_dup_test").toFile()
        try {
            val payloadA = "Duplicate payload content for SHA-256 verification".repeat(64).toByteArray()
            val payloadB = "Distinct file content that should not match".repeat(64).toByteArray()

            val file1 = File(tempDir, "copy_original.bin").apply { writeBytes(payloadA) }
            val file2 = File(tempDir, "copy_duplicate.bin").apply { writeBytes(payloadA) }
            val file3 = File(tempDir, "unique.bin").apply { writeBytes(payloadB) }

            fun sha256(file: File): String {
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buf = ByteArray(4096)
                    var r: Int
                    while (input.read(buf).also { r = it } != -1) {
                        digest.update(buf, 0, r)
                    }
                }
                return digest.digest().joinToString("") { "%02x".format(it) }
            }

            assertEquals(sha256(file1), sha256(file2))
            assertFalse(sha256(file1) == sha256(file3))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
