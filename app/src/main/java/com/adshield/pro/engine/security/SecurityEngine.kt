package com.adshield.pro.engine.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.adshield.pro.domain.model.RuleCategory
import com.adshield.pro.engine.filter.FilterRuleEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AdShield Pro — Security Center orchestrator.
 *
 * Anti-virus + anti-phishing that works on ANY app, ANY file, ANY link, ANY website:
 * - WEB/LINK PROTECTION: live threat-intelligence hosts from the on-device filter engine
 *   (both the DNS VPN and this scanner read the same updated malware lists) plus deep URL
 *   static analysis (typosquatting, punycode, credential patterns, dropper structures).
 * - FILE PROTECTION: signature + structural scanning of any accessible file — polyglot
 *   executables, script droppers, macro documents, zip-slip archives, EICAR test standard.
 * - APP PROTECTION: static APK analysis with a real binary-manifest parser plus a whole-device
 *   installed-app audit using MTD-grade permission-stack triage.
 *
 * 100% on-device. Files and links never leave the phone.
 */
@Singleton
class SecurityEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val filterRuleEngine: FilterRuleEngine
) {

    val urlScanner: UrlThreatScanner = UrlThreatScanner(
        object : UrlThreatScanner.KnownThreatHostLookup {
            override fun isKnownThreatHost(host: String): Boolean = isHostOnActiveThreatBlocklist(host)
        }
    )

    private val fileScanner = FileThreatScanner()
    private val apkAnalyzer = ApkAnalyzer()
    private val appAuditor = AppRiskAuditor()

    // --- Web / link protection -------------------------------------------------

    /** True when the host (exact or parent) is on any active MALWARE-category list. */
    fun isHostOnActiveThreatBlocklist(host: String): Boolean {
        val snap = filterRuleEngine.getCurrentSnapshot()
        if (snap.protectionMode.activeCategories.contains(RuleCategory.MALWARE)) {
            var suffix: String? = host.lowercase().trimEnd('.')
            while (suffix != null) {
                val entry = snap.filterListSubdomains[suffix] ?: snap.filterListExact[suffix]
                if (entry != null && entry.category == RuleCategory.MALWARE) {
                    return true
                }
                suffix = if (suffix.contains('.')) suffix.substringAfter('.') else null
            }
        }
        return false
    }

    fun scanUrl(rawUrl: String): UrlThreatScanner.UrlVerdict = urlScanner.scan(rawUrl)

    // --- File protection -------------------------------------------------------

    suspend fun scanFile(file: File): SecurityScanRecord = withContext(Dispatchers.IO) {
        val fileVerdict = fileScanner.scanFile(file)
        val apkReport = if (file.name.lowercase().let { it.endsWith(".apk") || it.endsWith(".apks") || it.endsWith(".xapk") }) {
            runCatching { apkAnalyzer.analyzeApk(file) }.getOrNull()
        } else null

        val combinedScore = maxOf(fileVerdict.riskScore, apkReport?.riskScore ?: 0)
        val combinedFindings = fileVerdict.findings + (apkReport?.riskReasons ?: emptyList())
        val threatName = apkReport?.let { r ->
            if (r.threatLevel == ApkAnalyzer.ThreatLevel.DANGEROUS || r.threatLevel == ApkAnalyzer.ThreatLevel.SUSPICIOUS) {
                "Risky APK: ${r.packageName ?: file.name} (score ${r.riskScore})"
            } else fileVerdict.threatName
        } ?: fileVerdict.threatName

        val level = when {
            fileVerdict.threatLevel == FileThreatScanner.ThreatLevel.DANGEROUS ||
                apkReport?.threatLevel == ApkAnalyzer.ThreatLevel.DANGEROUS -> SecurityLevel.DANGEROUS
            fileVerdict.threatLevel == FileThreatScanner.ThreatLevel.SUSPICIOUS ||
                apkReport?.threatLevel == ApkAnalyzer.ThreatLevel.SUSPICIOUS -> SecurityLevel.SUSPICIOUS
            else -> SecurityLevel.CLEAN
        }

        SecurityScanRecord(
            id = System.currentTimeMillis(),
            kind = ScanKind.FILE,
            target = file.absolutePath,
            threatLevel = level,
            riskScore = combinedScore,
            threatName = threatName,
            findings = combinedFindings,
            recommendation = if (apkReport != null && level != SecurityLevel.CLEAN) {
                "Do not install this APK. ${apkReport.riskReasons.firstOrNull() ?: "Risk indicators found."}"
            } else fileVerdict.recommendation,
            timestampEpochMs = System.currentTimeMillis()
        )
    }

    suspend fun scanUriContent(uri: Uri, displayName: String): SecurityScanRecord? = withContext(Dispatchers.IO) {
        val temp = File(context.cacheDir, "sec_scan_${System.currentTimeMillis()}_${displayName.take(60).replace(Regex("[^A-Za-z0-9._-]"), "_")}")
        try {
            val stream = context.contentResolver.openInputStream(uri)
            if (stream == null) {
                temp.delete()
                return@withContext null
            }
            stream.use { input ->
                temp.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
            }
        } catch (t: Throwable) {
            temp.delete()
            return@withContext null
        }
        try {
            val record = scanFile(temp)
            record.copy(target = displayName)
        } finally {
            temp.delete()
        }
    }

    // --- Installed app protection ---------------------------------------------

    suspend fun auditInstalledApps(): List<AppRiskAuditor.AppRiskAudit> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val apps = runCatching { pm.getInstalledApplications(PackageManager.GET_PERMISSIONS) }
            .getOrDefault(emptyList())

        apps.map { appInfo ->
            val requested: List<String> = runCatching {
                pm.getPackageInfo(appInfo.packageName, PackageManager.GET_PERMISSIONS)
                    .requestedPermissions?.toList()
            }.getOrNull() ?: emptyList()
            val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching {
                    pm.getInstallSourceInfo(appInfo.packageName).installingPackageName
                }.getOrNull()
            } else {
                @Suppress("DEPRECATION")
                runCatching { pm.getInstallerPackageName(appInfo.packageName) }.getOrNull()
            }
            val label = runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrDefault(appInfo.packageName)
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdated = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

            AppRiskAuditor.AppSecurityProfile(
                packageName = appInfo.packageName,
                appName = label,
                versionName = runCatching { pm.getPackageInfo(appInfo.packageName, 0).versionName }.getOrNull(),
                targetSdkVersion = appInfo.targetSdkVersion,
                requestedPermissions = requested,
                installerPackageName = installer,
                isSystemApp = isSystem,
                isUpdatedSystemApp = isUpdated,
                firstInstallTimeEpochMs = runCatching {
                    pm.getPackageInfo(appInfo.packageName, 0).firstInstallTime
                }.getOrDefault(0L)
            )
        }.map { appAuditor.audit(it) }
            .sortedWith(compareByDescending<AppRiskAuditor.AppRiskAudit> { it.riskScore }.thenBy { it.profile.appName.lowercase() })
    }

    // --- Security posture summary ----------------------------------------------

    fun activeThreatSignatureCount(): Int {
        val snap = filterRuleEngine.getCurrentSnapshot()
        return snap.filterListSubdomains.values.count {
            it.category == RuleCategory.MALWARE
        }
    }

    companion object {
        fun buildSecurityPosture(
            highRiskApps: Int,
            threatSignatures: Int,
            vpnActive: Boolean
        ): SecurityPosture {
            var score = 100
            if (!vpnActive) score -= 40
            score -= (highRiskApps * 12).coerceAtMost(36)
            if (threatSignatures < 1000) score -= 15
            return SecurityPosture(
                protectionScore = score.coerceIn(0, 100),
                highRiskApps = highRiskApps,
                threatSignatures = threatSignatures,
                webProtectionActive = vpnActive && threatSignatures > 0
            )
        }
    }
}

enum class SecurityLevel { CLEAN, SUSPICIOUS, DANGEROUS }

enum class ScanKind { FILE, URL, APK, APP_AUDIT }

data class SecurityScanRecord(
    val id: Long,
    val kind: ScanKind,
    val target: String,
    val threatLevel: SecurityLevel,
    val riskScore: Int,
    val threatName: String?,
    val findings: List<String>,
    val recommendation: String,
    val timestampEpochMs: Long
)

data class SecurityPosture(
    val protectionScore: Int,
    val highRiskApps: Int,
    val threatSignatures: Int,
    val webProtectionActive: Boolean
)
