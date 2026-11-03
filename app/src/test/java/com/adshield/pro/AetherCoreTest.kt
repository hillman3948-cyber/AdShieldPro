package com.adshield.pro

import com.adshield.pro.domain.model.AppDnsActivity
import com.adshield.pro.domain.model.DnsActivityRecord
import com.adshield.pro.domain.model.DomainBlockCount
import com.adshield.pro.domain.model.FilterEvaluationResult
import com.adshield.pro.domain.model.RuleCategory
import com.adshield.pro.domain.model.VpnRuntimeState
import com.adshield.pro.domain.model.VpnStatus
import com.adshield.pro.engine.aether.AetherAction
import com.adshield.pro.engine.aether.AetherCore
import com.adshield.pro.engine.aether.DomainRiskAnalyzer
import com.adshield.pro.engine.aether.LiveNetworkModel
import com.adshield.pro.engine.aether.ThreatVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Aether AI guarantees.
 *
 * The directive's hard rules are encoded here as tests:
 *  - never classify something as malware without sufficient evidence;
 *  - a heuristic can never block on its own unless the user opted in;
 *  - a user allow rule always wins (false-positive reduction);
 *  - every verdict carries traceable evidence and a confidence value;
 *  - the live dashboard only ever shows real logged traffic.
 */
class AetherCoreTest {

    private fun blockedBy(category: RuleCategory, source: String = "HaGeZi TIF"): FilterEvaluationResult =
        FilterEvaluationResult(
            domain = "example.test",
            isBlocked = true,
            matchedRule = "||example.test^",
            matchedCategory = category,
            matchType = null,
            sourceName = source,
            priority = 90
        )

    private fun allowed(source: String = "Allowed (no rule match)"): FilterEvaluationResult =
        FilterEvaluationResult(
            domain = "example.test",
            isBlocked = false,
            matchedRule = null,
            matchedCategory = null,
            matchType = null,
            sourceName = source,
            priority = 0
        )

    // --- Evidence & verdicts -------------------------------------------------

    @Test
    fun testThreatListMatch_isVerifiedAndBlocksByDefault() {
        val assessment = AetherCore.assessDnsQuery(
            "malware-host.test",
            blockedBy(RuleCategory.MALWARE),
            autonomy = AetherCore.AutonomyLevel.VERIFIED_ONLY
        )
        assertEquals(ThreatVerdict.VERIFIED, assessment.verdict)
        assertEquals(AetherAction.BLOCK, assessment.action)
        assertTrue("evidence must name its source", assessment.evidence.any { it.source == "HaGeZi TIF" })
        assertTrue(assessment.confidence >= 90)
        assertTrue(assessment.auditLine().contains("VERIFIED"))
    }

    @Test
    fun testHeuristicsAlone_canNeverVerifyOrBlock() {
        // A domain that trips many heuristics but is on no list.
        val target = "http://paypal-secure-login.verify-account.tk/update/kyc"
        val findings = DomainRiskAnalyzer.analyze(target)
        assertTrue("the test domain should look suspicious", DomainRiskAnalyzer.riskScore(findings) >= 45)

        val assessment = AetherCore.assessDnsQuery(
            DomainRiskAnalyzer.hostOf(target),
            allowed(),
            heuristics = findings,
            autonomy = AetherCore.AutonomyLevel.VERIFIED_ONLY
        )
        assertEquals(ThreatVerdict.SUSPECTED, assessment.verdict)
        assertFalse("heuristics must never auto-block under verified-only", assessment.isBlocked)
        assertEquals(AetherAction.ALLOW_AND_REVIEW, assessment.action)
    }

    @Test
    fun testUserAllowRule_alwaysWins() {
        val assessment = AetherCore.assessDnsQuery(
            "tracker.test",
            allowed("Allowed (Custom User Rule)"),
            heuristics = DomainRiskAnalyzer.analyze("http://tracker.test/login"),
            autonomy = AetherCore.AutonomyLevel.VERIFIED_AND_SUSPECTED
        )
        assertEquals(ThreatVerdict.ALLOWED, assessment.verdict)
        assertEquals(AetherAction.ALLOW, assessment.action)
        assertTrue(assessment.autonomyNote.contains("your own rule", ignoreCase = true))
    }

    @Test
    fun testManualAutonomy_reportsInsteadOfBlocking() {
        val assessment = AetherCore.assessDnsQuery(
            "malware-host.test",
            blockedBy(RuleCategory.MALWARE),
            autonomy = AetherCore.AutonomyLevel.OFF
        )
        assertEquals(ThreatVerdict.VERIFIED, assessment.verdict)
        assertEquals("manual mode must not block", AetherAction.ALLOW_AND_REVIEW, assessment.action)
    }

    @Test
    fun testSuspectedBlocking_requiresExplicitOptInAndHigherScore() {
        val findings = DomainRiskAnalyzer.analyze("http://paypal-secure-login.verify-account.tk/update/kyc")
        val optedIn = AetherCore.assessDnsQuery(
            "paypal-secure-login.verify-account.tk",
            allowed(),
            heuristics = findings,
            autonomy = AetherCore.AutonomyLevel.VERIFIED_AND_SUSPECTED
        )
        assertEquals(ThreatVerdict.SUSPECTED, optedIn.verdict)
        assertEquals(AetherAction.BLOCK, optedIn.action)
        assertTrue(optedIn.autonomyNote.contains("setting"))

        // Weak suspicion stays allowed even when opted in.
        val weak = AetherCore.assessDnsQuery(
            "some-long-name.example.com",
            allowed(),
            heuristics = DomainRiskAnalyzer.analyze("https://some-long-name.example.com/page"),
            autonomy = AetherCore.AutonomyLevel.VERIFIED_AND_SUSPECTED
        )
        assertFalse(weak.isBlocked)
    }

    @Test
    fun testUnknownIsAFirstClassAnswer() {
        val assessment = AetherCore.assessDnsQuery("news.example.org", allowed())
        assertEquals(ThreatVerdict.ALLOWED, assessment.verdict)
        assertEquals(0, assessment.riskScore)
        assertEquals(0, assessment.confidence)

        val noisy = AetherCore.assessDnsQuery(
            "news.example.org",
            allowed(),
            heuristics = DomainRiskAnalyzer.analyze("http://news.example.org/x")
        )
        assertEquals(ThreatVerdict.UNKNOWN, noisy.verdict)
        assertTrue(noisy.riskScore in 1..44)
    }

    @Test
    fun testAdsList_isVerifiedButNotThreatIntelligence() {
        val assessment = AetherCore.assessDnsQuery("ads.network.test", blockedBy(RuleCategory.ADS, "HaGeZi Multi Pro"))
        assertEquals(ThreatVerdict.VERIFIED, assessment.verdict)
        assertTrue("ad lists are weaker evidence than threat intel", assessment.confidence < 95)
        assertTrue(assessment.evidence.first().explanation.contains("advertising", ignoreCase = true))
    }

    @Test
    fun testAppAssessment_neverBlocksAndExplainsSignals() {
        val assessment = AetherCore.assessApp(
            packageName = "com.suspicious.app",
            appName = "Free VPN Pro",
            highRiskSignals = 4,
            signalExplanations = listOf("Requests accessibility service", "Reads SMS", "Overlay permission", "Sideloaded"),
            fromUnknownInstaller = true
        )
        assertEquals(AetherAction.ALLOW_AND_REVIEW, assessment.action)
        assertEquals(ThreatVerdict.SUSPECTED, assessment.verdict)
        assertTrue(assessment.autonomyNote.contains("never blocked automatically"))
        assertTrue(assessment.evidence.any { it.kind.name == "APP_RISK" })
    }

    // --- Heuristic analyzer --------------------------------------------------

    @Test
    fun testDomainAnalyzer_flagsRealPhishingPatterns() {
        val ipHost = DomainRiskAnalyzer.analyze("http://185.203.116.7/login")
        assertTrue(ipHost.any { it.code == "ip-host" })

        val puny = DomainRiskAnalyzer.analyze("https://xn--pypal-4ve.com/signin")
        assertTrue(puny.any { it.code == "punycode" })

        val brand = DomainRiskAnalyzer.analyze("https://paypal.com.secure-verify.tk/login")
        assertTrue("brand lookalike must be flagged", brand.any { it.code == "brand-lookalike" })

        val riskyTld = DomainRiskAnalyzer.analyze("https://free-gift-card.tk/claim")
        assertTrue(riskyTld.any { it.code == "risky-tld" })

        val executable = DomainRiskAnalyzer.analyze("https://cdn.example.zip/setup")
        assertTrue(executable.any { it.code == "executable-tld" || it.code == "risky-tld" })
    }

    @Test
    fun testDomainAnalyzer_doesNotFlagLegitimateDomains() {
        listOf(
            "https://www.google.com/search?q=test",
            "https://paypal.com/signin",
            "https://accounts.google.com/signin",
            "https://github.com/yawiyuson231-oss/AdShieldPro"
        ).forEach { url ->
            val findings = DomainRiskAnalyzer.analyze(url)
            val risk = DomainRiskAnalyzer.riskScore(findings)
            assertTrue("$url should stay low risk, was $risk with ${findings.map { it.code }}", risk < 45)
        }
    }

    @Test
    fun testHostExtraction_handlesPortsUsersAndTrailingDots() {
        assertEquals("example.com", DomainRiskAnalyzer.hostOf("https://user:pass@example.com:8443/x?y=1"))
        assertEquals("example.com", DomainRiskAnalyzer.hostOf("example.com."))
        assertEquals("example.com", DomainRiskAnalyzer.hostOf("HTTP://Example.COM/Path"))
        assertEquals("", DomainRiskAnalyzer.hostOf("   "))
    }

    @Test
    fun testRiskAggregation_listMatchDominates() {
        val listOnly = AetherCore.aggregateRisk(
            listOf(com.adshield.pro.engine.aether.Evidence(com.adshield.pro.engine.aether.EvidenceKind.THREAT_LIST, "TIF", 95, "on list")),
            heuristicScore = 0
        )
        assertEquals(95, listOnly)
        val withNoise = AetherCore.aggregateRisk(
            listOf(com.adshield.pro.engine.aether.Evidence(com.adshield.pro.engine.aether.EvidenceKind.HEURISTIC, "punycode", 22, "x")),
            heuristicScore = 22
        )
        assertEquals(22, withNoise)
        assertEquals(0, AetherCore.aggregateRisk(emptyList(), 0))
    }

    // --- Live dashboard model ------------------------------------------------

    private fun record(domain: String, blocked: Boolean, category: RuleCategory?, latency: Int = 40) =
        DnsActivityRecord(
            timestampEpochMs = 1_700_000_000_000L,
            domain = domain,
            queryType = "A",
            isBlocked = blocked,
            category = category,
            matchedRule = null,
            sourceList = if (blocked) "HaGeZi TIF" else "Allowed",
            latencyMs = latency,
            protocolUsed = "DOH"
        )

    @Test
    fun testLiveSnapshot_isBuiltOnlyFromRealLogs() {
        val empty = LiveNetworkModel.snapshot(VpnRuntimeState(status = VpnStatus.CONNECTED), emptyList(), emptyList(), emptyList())
        assertFalse(empty.hasData)
        assertNotNull(empty.emptyReason)
        assertEquals(0, empty.totalQueries)

        val logs = listOf(
            record("ads.test", blocked = true, category = RuleCategory.ADS),
            record("track.test", blocked = true, category = RuleCategory.TRACKERS),
            record("evil.test", blocked = true, category = RuleCategory.MALWARE),
            record("news.test", blocked = false, category = null, latency = 120)
        )
        val snapshot = LiveNetworkModel.snapshot(
            VpnRuntimeState(status = VpnStatus.CONNECTED),
            logs,
            listOf(AppDnsActivity("com.app", "App", 10, 3)),
            listOf(DomainBlockCount("ads.test", "ADS", 5))
        )
        assertTrue(snapshot.hasData)
        assertEquals(4, snapshot.totalQueries.toInt())
        assertEquals(3, snapshot.blockedQueries.toInt())
        assertEquals(1, snapshot.allowedQueries.toInt())
        assertEquals(1, snapshot.threatsBlocked.toInt())
        assertEquals(4, snapshot.uniqueDomains)
        assertEquals(75f, snapshot.blockRatePercent, 0.01f)
        assertTrue(snapshot.categories.any { it.lane == LiveNetworkModel.TrafficLane.THREATS })
        assertEquals(1, snapshot.topApps.size)
    }

    @Test
    fun testConnectionHealth_isHonestAboutMissingData() {
        val idle = LiveNetworkModel.snapshot(VpnRuntimeState(status = VpnStatus.CONNECTED), emptyList(), emptyList(), emptyList())
        assertTrue("an idle tunnel is not a failure", idle.connectionHealth.isHealthy)
        assertTrue(idle.connectionHealth.label.contains("Waiting", ignoreCase = true))

        val off = LiveNetworkModel.snapshot(VpnRuntimeState(status = VpnStatus.DISCONNECTED), emptyList(), emptyList(), emptyList())
        assertFalse(off.connectionHealth.isHealthy)

        val slow = LiveNetworkModel.snapshot(
            VpnRuntimeState(status = VpnStatus.CONNECTED),
            listOf(record("a.test", false, null, latency = 900), record("b.test", false, null, latency = 800)),
            emptyList(), emptyList()
        )
        assertFalse(slow.connectionHealth.isHealthy)
        assertTrue(slow.connectionHealth.label.contains("unhealthy", ignoreCase = true))
        assertEquals(850, slow.averageLatencyMs!!)
    }
}
