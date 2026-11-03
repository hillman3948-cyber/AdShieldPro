package com.adshield.pro

import com.adshield.pro.domain.model.ProtectionMode
import com.adshield.pro.domain.model.RuleCategory
import com.adshield.pro.engine.browser.BrowserContentBlocker
import com.adshield.pro.engine.filter.FilterRuleEngine
import com.adshield.pro.engine.security.ApkAnalyzer
import com.adshield.pro.engine.security.AppRiskAuditor
import com.adshield.pro.engine.security.FileThreatScanner
import com.adshield.pro.engine.security.UrlThreatScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * AdShield Pro Security Center engine test suite:
 * 1. FilterRuleEngine bloom fast-path preserves blocking correctness
 * 3. UrlThreatScanner phishing/typosquat/credential/known-threat detection
 * 4. FileThreatScanner EICAR, polyglot, zip-slip, macro-document detection
 * 5. ApkAnalyzer binary AXML manifest parsing (package + permissions) and APK risk triage
 * 6. AppRiskAuditor permission-stack triage
 */
class SecurityCenterTestSuite {

    // --- Filter engine fast path ---

    @Test
    fun testFilterEngine_bloomFastPath_preservesBlockingSemantics() {
        val engine = FilterRuleEngine()
        val parsed = engine.parseFilterListContent(
            "||ads.example.com^\n||tracker.io^\n@@||safe.tracker.io^",
            RuleCategory.ADS,
            "UnitTestList"
        ).getOrThrow()

        engine.updateFilterListRules(
            subdomainRules = parsed.subdomainRules,
            exactRules = parsed.exactRules,
            wildcardRules = parsed.wildcardRules,
            allowSubdomains = parsed.allowSubdomains
        )
        engine.updateProtectionMode(ProtectionMode.STANDARD)

        // Blocked domains remain blocked (fast path must not hide them)
        assertTrue(engine.evaluate("ads.example.com").isBlocked)
        assertTrue(engine.evaluate("sub.ads.example.com").isBlocked)
        assertTrue(engine.evaluate("tracker.io").isBlocked)

        // Exception rule wins over block
        assertTrue(!engine.evaluate("safe.tracker.io").isBlocked)

        // Clean domain short-circuits through the bloom fast-path
        val clean = engine.evaluate("totally-clean.example.org")
        assertTrue(!clean.isBlocked)
        assertTrue(clean.sourceName.startsWith("Allowed"))
        // Across many clean domains the bloom fast-path must engage (collision rate ~0.1%)
        val fastPathCount = (0 until 200).count {
            engine.evaluate("clean-host-$it.example.org").sourceName == "Allowed (Bloom Fast-Path)"
        }
        assertTrue("Bloom fast-path never engaged ($fastPathCount/200)", fastPathCount > 150)

        // With wildcard rules present the fast path must disable itself for correctness
        val wildcardParsed = engine.parseFilterListContent(
            "||ad*.wildcard.example^",
            RuleCategory.ADS,
            "WildcardList"
        ).getOrThrow()
        engine.updateFilterListRules(
            subdomainRules = wildcardParsed.subdomainRules,
            exactRules = wildcardParsed.exactRules,
            wildcardRules = wildcardParsed.wildcardRules,
            allowSubdomains = emptySet()
        )
        val withWildcard = engine.evaluate("totally-clean.example.org")
        assertTrue(!withWildcard.isBlocked)
        assertTrue(withWildcard.sourceName != "Allowed (Bloom Fast-Path)")
    }

    // --- 3. URL threat scanner ---

    @Test
    fun testUrlScanner_cleanUrlPasses() {
        val scanner = UrlThreatScanner()
        val verdict = scanner.scan("https://www.google.com/search?q=titan+shield")
        assertTrue(verdict.isSafe)
        assertTrue(verdict.findings.isEmpty())
    }

    @Test
    fun testUrlScanner_flagsPhishingPatterns() {
        val scanner = UrlThreatScanner()
        val verdict = scanner.scan("http://secure-paypal.com.login-verify.tk/account/update-payment?id=1")
        assertTrue("Expected elevated risk, got ${verdict.riskScore}: ${verdict.findings}", verdict.riskScore >= 40)
        assertTrue(
            verdict.threatLevel == UrlThreatScanner.ThreatLevel.DANGEROUS ||
                verdict.threatLevel == UrlThreatScanner.ThreatLevel.SUSPICIOUS
        )
    }

    @Test
    fun testUrlScanner_flagsEmbeddedCredentialsAndRawIpLogins() {
        val scanner = UrlThreatScanner()
        val verdict = scanner.scan("https://user:secret@bank-login.example.com/session")
        assertTrue(verdict.findings.any { it.contains("Credentials embedded") })

        val ipVerdict = scanner.scan("http://192.168.13.37/login?token=abc")
        assertTrue(ipVerdict.findings.any { it.contains("Raw IP address host") })
        assertTrue(ipVerdict.riskScore >= 20)
    }

    @Test
    fun testUrlScanner_usesKnownThreatLookup() {
        val scanner = UrlThreatScanner(
            object : UrlThreatScanner.KnownThreatHostLookup {
                override fun isKnownThreatHost(host: String): Boolean = host == "evil-c2.example.net"
            }
        )
        val verdict = scanner.scan("https://sub.evil-c2.example.net/payload")
        assertTrue(verdict.isKnownThreat)
        assertEquals(UrlThreatScanner.ThreatLevel.DANGEROUS, verdict.threatLevel)
    }

    @Test
    fun testUrlScanner_flagsTyposquatting() {
        val scanner = UrlThreatScanner()
        val verdict = scanner.scan("https://paypa1.com/login")
        assertTrue(
            "Expected typosquat finding, got: ${verdict.findings}",
            verdict.findings.any { it.contains("typosquat", ignoreCase = true) }
        )
        assertTrue(verdict.riskScore >= 20)
    }

    // --- 4. File threat scanner ---

    private fun writeTempFile(name: String, bytes: ByteArray): File {
        val file = File.createTempFile("tst_", name)
        file.writeBytes(bytes)
        return file
    }

    @Test
    fun testFileScanner_detectsEicarTestSignature() {
        val scanner = FileThreatScanner()
        val file = writeTempFile("eicar.com", FileThreatScanner.EICAR_STRING.toByteArray(Charsets.ISO_8859_1))
        try {
            val verdict = scanner.scanFile(file)
            assertEquals(FileThreatScanner.ThreatLevel.DANGEROUS, verdict.threatLevel)
            assertEquals("EICAR.Test.File", verdict.threatName)
            assertNotNull(verdict.sha256)
        } finally {
            file.delete()
        }
    }

    @Test
    fun testFileScanner_detectsPolyglotExecutableInImage() {
        val scanner = FileThreatScanner()
        val bytes = ByteArrayOutputStream().apply {
            write(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())) // JPEG SOI
            write(ByteArray(4096))
            write(byteArrayOf(0x4D, 0x5A)) // MZ (Windows PE header) hidden inside
            write(ByteArray(2048))
        }.toByteArray()
        val file = writeTempFile("innocent.jpg", bytes)
        try {
            val verdict = scanner.scanFile(file)
            assertEquals(FileThreatScanner.ThreatLevel.DANGEROUS, verdict.threatLevel)
            assertTrue(verdict.threatName!!.contains("Polyglot"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun testFileScanner_detectsZipSlipArchive() {
        val bytes = ByteArrayOutputStream().apply {
            ZipOutputStream(this).use { zip ->
                zip.putNextEntry(ZipEntry("../../../data/evil.txt"))
                zip.write("malicious".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        val scanner = FileThreatScanner()
        val file = writeTempFile("archive.zip", bytes)
        try {
            val verdict = scanner.scanFile(file)
            assertTrue(verdict.findings.any { it.contains("Zip-Slip") || it.contains("path traversal", ignoreCase = true) })
            assertEquals(FileThreatScanner.ThreatLevel.DANGEROUS, verdict.threatLevel)
        } finally {
            file.delete()
        }
    }

    @Test
    fun testFileScanner_flagsMacroOfficeDocuments() {
        val bytes = ByteArrayOutputStream().apply {
            ZipOutputStream(this).use { zip ->
                zip.putNextEntry(ZipEntry("[Content_Types].xml"))
                zip.write("<Types/>".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("word/vbaProject.bin"))
                zip.write(ByteArray(512))
                zip.closeEntry()
            }
        }.toByteArray()
        val scanner = FileThreatScanner()
        val file = writeTempFile("invoice.docx", bytes)
        try {
            val verdict = scanner.scanFile(file)
            assertTrue(verdict.findings.any { it.contains("VBA macro") })
            assertTrue(verdict.riskScore >= 25)
        } finally {
            file.delete()
        }
    }

    @Test
    fun testFileScanner_cleanFilePasses() {
        val scanner = FileThreatScanner()
        val file = writeTempFile("notes.txt", "The quick brown fox jumps over the lazy dog.".toByteArray())
        try {
            val verdict = scanner.scanFile(file)
            assertTrue(verdict.isClean)
            assertNull(verdict.threatName)
        } finally {
            file.delete()
        }
    }

    // --- 5. APK analyzer + AXML parser ---

    /** Builds a minimal valid binary AndroidManifest.xml (AXML) for parser verification. */
    private fun buildAxml(packageName: String, permissions: List<String>): ByteArray {
        val strings = ArrayList<String>()
        fun intern(s: String): Int {
            val existing = strings.indexOf(s)
            if (existing >= 0) return existing
            strings.add(s)
            return strings.size - 1
        }
        intern("manifest")
        val pkgAttrNameIdx = intern("package")
        val pkgValueIdx = intern(packageName)
        intern("uses-permission")
        val nameAttrIdx = intern("name")
        val permValueIndexes = permissions.map { intern(it) }

        // --- String pool (UTF-8) ---
        val stringBlock = ByteArrayOutputStream()
        val offsets = ArrayList<Int>()
        for (s in strings) {
            offsets.add(stringBlock.size())
            val utf8 = s.toByteArray(Charsets.UTF_8)
            stringBlock.write(utf8.size) // utf16 length (ASCII-safe: same)
            stringBlock.write(utf8.size) // utf8 length
            stringBlock.write(utf8)
            stringBlock.write(0)
        }
        var rawStrings = stringBlock.toByteArray()
        if (rawStrings.size % 4 != 0) {
            rawStrings += ByteArray(4 - rawStrings.size % 4)
        }
        val stringsStart = 28 + 4 * strings.size
        val poolSize = stringsStart + rawStrings.size
        val pool = ByteBuffer.allocate(poolSize).order(ByteOrder.LITTLE_ENDIAN)
        pool.putShort(0x0001) // type
        pool.putShort(28) // headerSize
        pool.putInt(poolSize)
        pool.putInt(strings.size)
        pool.putInt(0) // styleCount
        pool.putInt(0x100) // UTF8 flag
        pool.putInt(stringsStart)
        pool.putInt(0) // stylesStart
        for (off in offsets) pool.putInt(off)
        pool.put(rawStrings)

        fun startTag(elementNameIdx: Int, attrNameIdx: Int, attrValueIdx: Int): ByteArray {
            val chunkSize = 16 + 20 + 20
            val b = ByteBuffer.allocate(chunkSize).order(ByteOrder.LITTLE_ENDIAN)
            b.putShort(0x0102) // START_ELEMENT
            b.putShort(16) // headerSize
            b.putInt(chunkSize)
            b.putInt(1) // lineNumber
            b.putInt(-1) // comment
            b.putInt(-1) // ns
            b.putInt(elementNameIdx)
            b.putShort(20) // attributeStart
            b.putShort(20) // attributeSize
            b.putShort(1) // attributeCount
            b.putShort(0) // idIndex
            b.putShort(0) // classIndex
            b.putShort(0) // styleIndex
            // attribute
            b.putInt(-1) // ns
            b.putInt(attrNameIdx)
            b.putInt(attrValueIdx) // rawValue
            b.putShort(8) // typed value size
            b.put(0) // res0
            b.put(3) // dataType = STRING
            b.putInt(attrValueIdx) // data = string pool index
            return b.array()
        }

        val chunks = ArrayList<ByteArray>()
        chunks.add(pool.array())
        chunks.add(startTag(intern("manifest"), pkgAttrNameIdx, pkgValueIdx))
        for (permIdx in permValueIndexes) {
            chunks.add(startTag(intern("uses-permission"), nameAttrIdx, permIdx))
        }

        val totalSize = 8 + chunks.sumOf { it.size }
        val root = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        root.putShort(0x0003) // RES_XML_TYPE
        root.putShort(8) // headerSize
        root.putInt(totalSize)

        val out = ByteArrayOutputStream()
        out.write(root.array())
        for (c in chunks) out.write(c)
        return out.toByteArray()
    }

    @Test
    fun testApkAnalyzer_axmlParser_extractsPackageAndPermissions() {
        val analyzer = ApkAnalyzer()
        val axml = buildAxml(
            packageName = "com.trojan.dropper",
            permissions = listOf(
                "android.permission.RECEIVE_SMS",
                "android.permission.READ_SMS",
                "android.permission.ACCESSIBILITY_SERVICE"
            )
        )
        val (pkg, perms) = analyzer.parseBinaryAndroidManifest(axml)
        assertEquals("com.trojan.dropper", pkg)
        assertTrue(perms.contains("android.permission.RECEIVE_SMS"))
        assertTrue(perms.contains("android.permission.READ_SMS"))
        assertTrue(perms.contains("android.permission.ACCESSIBILITY_SERVICE"))

        // Garbage input must never throw
        val (nullPkg, emptyPerms) = analyzer.parseBinaryAndroidManifest(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        assertNull(nullPkg)
        assertTrue(emptyPerms.isEmpty())
    }

    @Test
    fun testApkAnalyzer_flagsSmsInterceptionDropperProfile() {
        val report = ApkAnalyzer().run {
            // Direct triage via the parser output fed through analyzeApk's permission weights
            val axml = buildAxml(
                packageName = "lucky.patcher.free",
                permissions = listOf(
                    "android.permission.RECEIVE_SMS",
                    "android.permission.READ_SMS",
                    "android.permission.REQUEST_INSTALL_PACKAGES"
                )
            )
            val tmp = writeTempFile("dropper.apk", buildFakeApk(axml))
            try {
                analyzeApk(tmp)
            } finally {
                tmp.delete()
            }
        }
        assertEquals("lucky.patcher.free", report.packageName)
        assertTrue(report.requestedPermissions.contains("android.permission.RECEIVE_SMS"))
        assertTrue(report.riskReasons.any { it.contains("SMS interception", ignoreCase = true) })
        assertTrue(report.riskReasons.any { it.contains("cracker/modware", ignoreCase = true) })
        assertTrue(report.riskScore >= 25)
    }

    /** Builds a minimal zip that looks like an APK: AndroidManifest.xml + classes.dex. */
    private fun buildFakeApk(axml: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(axml)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write("dex\n035\u0000".toByteArray() + ByteArray(256))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("META-INF/CERT.RSA"))
            zip.write(ByteArray(64))
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    // --- 6. Installed app auditor ---

    @Test
    fun testAppAuditor_flagsAccessibilitySmsTrojanProfile() {
        val auditor = AppRiskAuditor()
        val audit = auditor.audit(
            AppRiskAuditor.AppSecurityProfile(
                packageName = "com.bad.trojan",
                appName = "Flash Cleaner PRO",
                versionName = "1.0",
                targetSdkVersion = 26,
                requestedPermissions = listOf(
                    "android.permission.INTERNET",
                    "android.permission.ACCESSIBILITY_SERVICE",
                    "android.permission.BIND_ACCESSIBILITY_SERVICE",
                    "android.permission.RECEIVE_SMS",
                    "android.permission.READ_SMS",
                    "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
                    "android.permission.REQUEST_INSTALL_PACKAGES"
                ),
                installerPackageName = null,
                isSystemApp = false
            )
        )
        assertEquals(AppRiskAuditor.RiskLevel.HIGH, audit.riskLevel)
        assertTrue(audit.findings.any { it.contains("Accessibility") })
        assertTrue(audit.findings.any { it.contains("SMS interception", ignoreCase = true) })
        assertTrue(audit.findings.any { it.contains("Unknown install source", ignoreCase = true) })

        val trusted = auditor.audit(
            AppRiskAuditor.AppSecurityProfile(
                packageName = "com.good.app",
                appName = "Nice App",
                versionName = "2.0",
                targetSdkVersion = 35,
                requestedPermissions = listOf("android.permission.INTERNET", "android.permission.CAMERA"),
                installerPackageName = "com.android.vending",
                isSystemApp = false
            )
        )
        assertEquals(AppRiskAuditor.RiskLevel.TRUSTED, trusted.riskLevel)
    }

    // --- Browser request interception (as used by the Privacy Browser) ---

    @Test
    fun testBrowserBlocker_blocksThirdPartyAdHostsButNotFirstPartyContent() {
        val blocker = BrowserContentBlocker()
        // A known ad endpoint on a third-party host is blocked.
        val adRequest = blocker.evaluateRequest(
            requestUrl = "https://pubads.g.doubleclick.net/gampad/ads?iu=/video",
            pageHost = "mysite.com",
            allowlistedSites = emptySet()
        )
        assertTrue("ad host should be intercepted", adRequest.shouldBlock)

        // Same-origin content from the page itself is not blocked.
        val firstParty = blocker.evaluateRequest(
            requestUrl = "https://mysite.com/video/episode1.mp4",
            pageHost = "mysite.com",
            allowlistedSites = emptySet()
        )
        assertTrue("first-party media must not be blocked", !firstParty.shouldBlock)

        // Allowlisted sites bypass filtering entirely.
        val allowlisted = blocker.evaluateRequest(
            requestUrl = "https://pubads.g.doubleclick.net/gampad/ads",
            pageHost = "mysite.com",
            allowlistedSites = setOf("mysite.com")
        )
        assertTrue("allowlisted page must not be filtered", !allowlisted.shouldBlock)
    }
}
