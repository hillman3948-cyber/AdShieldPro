package com.adshield.pro.engine.optimizer

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import com.adshield.pro.data.local.preferences.UserPreferencesRepository
import com.adshield.pro.data.repository.AdShieldRepository
import com.adshield.pro.domain.model.BatteryMode
import com.adshield.pro.domain.model.BatteryThermalSnapshot
import com.adshield.pro.domain.model.BottleneckSeverity
import com.adshield.pro.domain.model.CleanHistoryRecord
import com.adshield.pro.domain.model.DeviceHealthAssessment
import com.adshield.pro.domain.model.GameAppInfo
import com.adshield.pro.domain.model.GameSessionRecord
import com.adshield.pro.domain.model.GamingProfile
import com.adshield.pro.domain.model.JunkCandidateItem
import com.adshield.pro.domain.model.JunkCategory
import com.adshield.pro.domain.model.JunkCleanMode
import com.adshield.pro.domain.model.JunkScanReport
import com.adshield.pro.domain.model.OptimizationPredictor
import com.adshield.pro.domain.model.PerformanceBottleneck
import com.adshield.pro.domain.model.RamOptimizationResult
import com.adshield.pro.domain.model.RamSnapshot
import com.adshield.pro.domain.model.SmartBoostResult
import com.adshield.pro.domain.model.StorageMetrics
import com.adshield.pro.engine.dns.UpstreamDnsResolver
import com.adshield.pro.worker.FilterUpdateWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rule-based intelligent device health predictor implementing [OptimizationPredictor].
 * Structured so an optional on-device TensorFlow Lite / AICore model can be swapped in via DI.
 */
class RuleBasedOptimizationPredictor : OptimizationPredictor {

    override fun evaluateDeviceHealth(
        ram: RamSnapshot,
        storage: StorageMetrics,
        battery: BatteryThermalSnapshot,
        recoverableJunkBytes: Long
    ): DeviceHealthAssessment {
        val bottlenecks = mutableListOf<PerformanceBottleneck>()
        val recommendations = mutableListOf<String>()
        var penaltyPoints = 0

        // 1. RAM Pressure Analysis
        if (ram.isLowMemoryCondition || ram.usagePercent >= 88f) {
            penaltyPoints += 25
            bottlenecks.add(
                PerformanceBottleneck(
                    title = "High System Memory Pressure (${String.format(Locale.US, "%.1f%%", ram.usagePercent)})",
                    detail = "Android low-memory threshold is approaching (${formatBytes(ram.availableRamBytes)} free).",
                    severity = BottleneckSeverity.HIGH,
                    recommendedAction = "Run Smart RAM Optimize to trim AdShield caches and close unused heavy foreground apps."
                )
            )
            recommendations.add("Trim in-memory DNS cache and release temporary WebView buffers.")
        } else if (ram.usagePercent >= 76f) {
            penaltyPoints += 10
            bottlenecks.add(
                PerformanceBottleneck(
                    title = "Elevated Memory Utilization (${String.format(Locale.US, "%.1f%%", ram.usagePercent)})",
                    detail = "Available RAM is ${formatBytes(ram.availableRamBytes)} out of ${formatBytes(ram.totalRamBytes)}.",
                    severity = BottleneckSeverity.MODERATE,
                    recommendedAction = "Release unused AdShield Pro cache allocations."
                )
            )
        }

        // Check AdShield Pro's own memory footprint (> 90 MB Java + Native heap)
        val ownTotalHeap = ram.adShieldJavaHeapUsedBytes + ram.adShieldNativeHeapUsedBytes
        if (ownTotalHeap > 90L * 1024L * 1024L || ram.adShieldDnsCacheEntries > 512) {
            penaltyPoints += 8
            bottlenecks.add(
                PerformanceBottleneck(
                    title = "AdShield Internal Cache Footprint (${formatBytes(ownTotalHeap)})",
                    detail = "In-memory DNS LRU cache holds ${ram.adShieldDnsCacheEntries} entries.",
                    severity = BottleneckSeverity.INFO,
                    recommendedAction = "Trim expired DNS cache entries to reduce background RAM usage."
                )
            )
        }

        // 2. Storage Pressure & Junk Analysis
        if (storage.isLowStorage || storage.usedPercent >= 90f) {
            penaltyPoints += 25
            bottlenecks.add(
                PerformanceBottleneck(
                    title = "Low Internal Storage (${String.format(Locale.US, "%.1f%%", storage.usedPercent)} full)",
                    detail = "Only ${formatBytes(storage.availableBytes)} remaining on /data partition.",
                    severity = BottleneckSeverity.HIGH,
                    recommendedAction = "Run Deep Scan in Junk Cleaner to review large files, unused APKs, and duplicates."
                )
            )
            recommendations.add("Free internal storage space to prevent SQLite and OS paging slowdowns.")
        }

        if (recoverableJunkBytes >= 5L * 1024L * 1024L) {
            penaltyPoints += 12
            bottlenecks.add(
                PerformanceBottleneck(
                    title = "Recoverable Temporary & Junk Files (${formatBytes(recoverableJunkBytes)})",
                    detail = "Detected temporary caches and redundant files eligible for cleanup.",
                    severity = BottleneckSeverity.MODERATE,
                    recommendedAction = "Run Clean Now to reclaim ${formatBytes(recoverableJunkBytes)}."
                )
            )
            recommendations.add("Clean ${formatBytes(recoverableJunkBytes)} of temporary cache and staging files.")
        } else if (recoverableJunkBytes >= 256L * 1024L) {
            penaltyPoints += 4
            recommendations.add("Clean ${formatBytes(recoverableJunkBytes)} of residual temporary cache files.")
        }

        // 3. Battery & Thermal Analysis
        if (battery.isOverheating) {
            penaltyPoints += 25
            bottlenecks.add(
                PerformanceBottleneck(
                    title = "Elevated Battery Temperature (${String.format(Locale.US, "%.1f°C", battery.temperatureCelsius)})",
                    detail = "Thermal status: ${battery.thermalStatusLabel}. High heat can trigger CPU/GPU thermal throttling.",
                    severity = BottleneckSeverity.HIGH,
                    recommendedAction = "Switch to Battery Saver mode to pause background syncs and reduce disk writes."
                )
            )
            recommendations.add("Pause background filter downloads and verbose disk logging until device cools below 39°C.")
        } else if (battery.isLowBattery && !battery.isCharging) {
            penaltyPoints += 10
            bottlenecks.add(
                PerformanceBottleneck(
                    title = "Low Battery Level (${battery.batteryPercent}%)",
                    detail = "Device is discharging at ${battery.batteryPercent}%.",
                    severity = BottleneckSeverity.MODERATE,
                    recommendedAction = "Enable Auto Battery Optimize to defer nonessential maintenance."
                )
            )
            recommendations.add("Extend background blocklist sync interval to conserve battery.")
        }

        val score = (100 - penaltyPoints).coerceIn(35, 100)
        val headline = when {
            score >= 90 -> "Optimal Condition — System Running Smoothly"
            score >= 75 -> "Good Condition — Minor Cache & Memory Optimization Available"
            score >= 60 -> "Attention Needed — Resource Bottlenecks Detected"
            else -> "Action Recommended — High Memory, Storage, or Thermal Load"
        }

        if (recommendations.isEmpty()) {
            recommendations.add("Device RAM, storage, and temperature are within healthy operating ranges.")
        }

        return DeviceHealthAssessment(
            readinessScore = score,
            statusHeadline = headline,
            bottlenecks = bottlenecks,
            recommendations = recommendations,
            isOptimizationRecommended = penaltyPoints >= 4 || recoverableJunkBytes > 64 * 1024L
        )
    }

    companion object {
        fun formatBytes(bytes: Long): String {
            val b = bytes.coerceAtLeast(0L)
            return when {
                b >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f GB", b / (1024.0 * 1024.0 * 1024.0))
                b >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024.0))
                b >= 1024L -> String.format(Locale.US, "%.1f KB", b / 1024.0)
                else -> "$b B"
            }
        }
    }
}

@Singleton
class SystemOptimizerEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: AdShieldRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val upstreamDnsResolver: UpstreamDnsResolver
) {
    private val predictor: OptimizationPredictor = RuleBasedOptimizationPredictor()

    // Real-time circular trend buffers (up to 20 readings)
    private val ramUsageTrend = ArrayDeque<Float>(20)
    private val temperatureTrend = ArrayDeque<Float>(20)

    @Volatile
    private var activeGamingPackage: String? = null

    fun isGamingSessionActive(): Boolean = activeGamingPackage != null

    // --- 1. REAL-TIME SYSTEM SNAPSHOTS (RAM, STORAGE, BATTERY & THERMAL) ---

    fun readRamSnapshot(): RamSnapshot {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        val total = memInfo.totalMem.coerceAtLeast(1L)
        val avail = memInfo.availMem.coerceAtLeast(0L)
        val used = (total - avail).coerceAtLeast(0L)
        val pct = ((used.toFloat() / total.toFloat()) * 100f).coerceIn(0f, 100f)

        val runtime = Runtime.getRuntime()
        val javaUsed = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L)
        val nativeUsed = runCatching { Debug.getNativeHeapAllocatedSize() }.getOrDefault(0L)

        synchronized(ramUsageTrend) {
            if (ramUsageTrend.size >= 20) ramUsageTrend.removeFirst()
            ramUsageTrend.addLast(pct)
        }

        return RamSnapshot(
            totalRamBytes = total,
            availableRamBytes = avail,
            usedRamBytes = used,
            usagePercent = pct,
            isLowMemoryCondition = memInfo.lowMemory,
            lowMemoryThresholdBytes = memInfo.threshold,
            adShieldJavaHeapUsedBytes = javaUsed,
            adShieldNativeHeapUsedBytes = nativeUsed,
            adShieldDnsCacheEntries = upstreamDnsResolver.getCacheSize()
        )
    }

    fun getRamUsageTrend(): List<Float> = synchronized(ramUsageTrend) { ramUsageTrend.toList() }

    fun readStorageMetrics(): StorageMetrics {
        val dataPath = Environment.getDataDirectory()
        val statFs = StatFs(dataPath.path)
        val total = statFs.totalBytes.coerceAtLeast(1L)
        val avail = statFs.availableBytes.coerceAtLeast(0L)
        val used = (total - avail).coerceAtLeast(0L)
        val pct = ((used.toFloat() / total.toFloat()) * 100f).coerceIn(0f, 100f)
        val isLow = avail < (2L * 1024L * 1024L * 1024L) || pct >= 90f

        return StorageMetrics(
            totalBytes = total,
            availableBytes = avail,
            usedBytes = used,
            usedPercent = pct,
            isLowStorage = isLow
        )
    }

    fun readBatteryThermalSnapshot(): BatteryThermalSnapshot {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.coerceAtLeast(1) ?: 100
        val batteryPct = if (level >= 0) ((level * 100) / scale).coerceIn(0, 100) else 80

        val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 310) ?: 310
        val tempCelsius = (tempTenths / 10.0f).coerceIn(0f, 80f)

        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val chargingSourceLabel = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "Charging (AC Adapter)"
            BatteryManager.BATTERY_PLUGGED_USB -> "Charging (USB Port)"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Charging (Wireless)"
            else -> if (isCharging) "Charging" else "Discharging (On Battery)"
        }

        val rawVoltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val voltageMv = if (rawVoltage in 1..20) rawVoltage * 1000 else rawVoltage

        val health = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
            ?: BatteryManager.BATTERY_HEALTH_UNKNOWN
        val healthLabel = when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheating"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Degraded / Dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            else -> "Normal"
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { powerManager.currentThermalStatus }.getOrDefault(PowerManager.THERMAL_STATUS_NONE)
        } else {
            PowerManager.THERMAL_STATUS_NONE
        }

        val thermalStatusLabel = when (thermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "Nominal (No Throttling)"
            PowerManager.THERMAL_STATUS_LIGHT -> "Light Warmth"
            PowerManager.THERMAL_STATUS_MODERATE -> "Moderate Warmth"
            PowerManager.THERMAL_STATUS_SEVERE -> "Severe (Thermal Throttling Active)"
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "Critical Thermal Alert"
            else -> "Nominal"
        }

        val isOverheating = tempCelsius >= 41.0f ||
            health == BatteryManager.BATTERY_HEALTH_OVERHEAT ||
            thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
        val isLowBattery = batteryPct <= 20

        synchronized(temperatureTrend) {
            if (temperatureTrend.size >= 20) temperatureTrend.removeFirst()
            temperatureTrend.addLast(tempCelsius)
        }

        val recs = mutableListOf<String>()
        if (isOverheating) {
            recs.add("High temperature detected (${String.format(Locale.US, "%.1f°C", tempCelsius)}). Avoid heavy 3D gaming while fast-charging and switch AdShield to Battery Saver mode.")
        }
        if (isLowBattery && !isCharging) {
            recs.add("Battery is at $batteryPct%. Enable Battery Saver mode to defer background blocklist updates and reduce disk logging.")
        }
        if (recs.isEmpty()) {
            recs.add("Battery temperature (${String.format(Locale.US, "%.1f°C", tempCelsius)}) and voltage (${voltageMv} mV) are within normal operating limits.")
        }

        val vpnState = repository.vpnRuntimeState.value
        val adShieldImpact = "Event-driven TUN DNS loop • 0 polling wakeups • Avg lookup ${vpnState.averageLatencyMs} ms"

        return BatteryThermalSnapshot(
            batteryPercent = batteryPct,
            temperatureCelsius = tempCelsius,
            isCharging = isCharging,
            chargingSourceLabel = chargingSourceLabel,
            voltageMillivolts = voltageMv,
            healthStatusLabel = healthLabel,
            thermalStatusLabel = thermalStatusLabel,
            isOverheating = isOverheating,
            isLowBattery = isLowBattery,
            adShieldBatteryImpactSummary = adShieldImpact,
            recommendations = recs
        )
    }

    fun getTemperatureTrend(): List<Float> = synchronized(temperatureTrend) { temperatureTrend.toList() }

    fun evaluateCurrentHealth(recoverableJunkBytes: Long): DeviceHealthAssessment {
        val ram = readRamSnapshot()
        val storage = readStorageMetrics()
        val battery = readBatteryThermalSnapshot()
        return predictor.evaluateDeviceHealth(ram, storage, battery, recoverableJunkBytes)
    }

    // --- 2. AUTOMATIC JUNK CLEANER (SCOPED-STORAGE COMPLIANT, SHA-256 DUPLICATES) ---

    suspend fun scanJunkFiles(
        mode: JunkCleanMode,
        excludedPaths: Set<String>
    ): JunkScanReport = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val candidates = mutableListOf<JunkCandidateItem>()

        fun isExcluded(file: File): Boolean {
            val path = file.absolutePath
            // CRITICAL: Never touch active AdShield Room DB or compiled filter list rule caches!
            if (path.contains("databases") || path.contains("datastore")) return true
            if (file.parentFile?.name == "filter_cache" && file.extension == "txt") return true
            return excludedPaths.any { ex ->
                ex.isNotBlank() && path.contains(ex.trim(), ignoreCase = true)
            }
        }

        // 1. Scan safe app-owned cache directories (Quick Clean, Scheduled Clean, Deep Scan, Custom Clean)
        val safeCacheDirs = buildList {
            add(context.cacheDir)
            context.codeCacheDir?.let { add(it) }
            context.externalCacheDir?.let { add(it) }
        }

        safeCacheDirs.forEach { rootDir ->
            if (rootDir.exists() && rootDir.isDirectory) {
                rootDir.walkTopDown().maxDepth(5).forEach { file ->
                    if (file.isFile && file.length() > 0L && !isExcluded(file)) {
                        candidates.add(
                            JunkCandidateItem(
                                id = file.absolutePath,
                                absolutePath = file.absolutePath,
                                displayName = file.name,
                                sizeBytes = file.length(),
                                category = JunkCategory.APP_TEMP_CACHE,
                                lastModifiedEpochMs = file.lastModified(),
                                requiresUserConfirmation = false,
                                isSelected = true
                            )
                        )
                    }
                }
            }
        }

        // 2. Scan AdShield staging / residual .tmp or .bak files inside filesDir/filter_cache
        val filterCacheDir = File(context.filesDir, "filter_cache")
        if (filterCacheDir.exists()) {
            filterCacheDir.listFiles()?.forEach { file ->
                if (file.isFile && (file.name.endsWith(".tmp") || file.name.endsWith(".bak") || file.name.endsWith(".log")) && !isExcluded(file)) {
                    candidates.add(
                        JunkCandidateItem(
                            id = file.absolutePath,
                            absolutePath = file.absolutePath,
                            displayName = file.name,
                            sizeBytes = file.length(),
                            category = JunkCategory.ADSHIELD_TEMP_STAGING,
                            lastModifiedEpochMs = file.lastModified(),
                            requiresUserConfirmation = false,
                            isSelected = true
                        )
                    )
                }
            }
        }

        // 3. If Deep Scan or Custom Clean: scan accessible external app directories & public Downloads (where readable under Scoped Storage)
        if (mode == JunkCleanMode.DEEP_SCAN || mode == JunkCleanMode.CUSTOM_CLEAN) {
            val deepDirs = buildList {
                context.getExternalFilesDir(null)?.let { add(it) }
                runCatching {
                    val pubDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (pubDownloads != null && pubDownloads.exists() && pubDownloads.canRead()) {
                        add(pubDownloads)
                    }
                }
            }

            val potentialDuplicateFiles = mutableListOf<File>()
            val thirtyDaysAgoMs = System.currentTimeMillis() - (30L * 24L * 3600L * 1000L)

            deepDirs.forEach { dir ->
                dir.walkTopDown().maxDepth(4).forEach { file ->
                    if (file.isFile && file.canRead() && file.length() > 0L && !isExcluded(file)) {
                        val lowerName = file.name.lowercase(Locale.US)
                        when {
                            lowerName.endsWith(".apk") -> {
                                candidates.add(
                                    JunkCandidateItem(
                                        id = file.absolutePath,
                                        absolutePath = file.absolutePath,
                                        displayName = file.name,
                                        sizeBytes = file.length(),
                                        category = JunkCategory.UNUSED_APKS,
                                        lastModifiedEpochMs = file.lastModified(),
                                        requiresUserConfirmation = true,
                                        isSelected = false
                                    )
                                )
                            }
                            file.length() >= 10L * 1024L * 1024L -> {
                                candidates.add(
                                    JunkCandidateItem(
                                        id = file.absolutePath,
                                        absolutePath = file.absolutePath,
                                        displayName = file.name,
                                        sizeBytes = file.length(),
                                        category = JunkCategory.LARGE_FILES,
                                        lastModifiedEpochMs = file.lastModified(),
                                        requiresUserConfirmation = true,
                                        isSelected = false
                                    )
                                )
                            }
                            file.lastModified() in 1 until thirtyDaysAgoMs -> {
                                candidates.add(
                                    JunkCandidateItem(
                                        id = file.absolutePath,
                                        absolutePath = file.absolutePath,
                                        displayName = file.name,
                                        sizeBytes = file.length(),
                                        category = JunkCategory.OLD_DOWNLOADS,
                                        lastModifiedEpochMs = file.lastModified(),
                                        requiresUserConfirmation = true,
                                        isSelected = false
                                    )
                                )
                            }
                        }

                        if (file.length() in 1024L..(50L * 1024L * 1024L)) {
                            potentialDuplicateFiles.add(file)
                        }
                    }
                }
            }

            // Detect duplicate files by grouping by exact byte size first, then SHA-256 hashing
            val duplicates = findDuplicateFilesBySha256(potentialDuplicateFiles)
            duplicates.forEach { (dupFile, hashHex) ->
                if (candidates.none { it.absolutePath == dupFile.absolutePath }) {
                    candidates.add(
                        JunkCandidateItem(
                            id = dupFile.absolutePath,
                            absolutePath = dupFile.absolutePath,
                            displayName = "${dupFile.name} (Duplicate)",
                            sizeBytes = dupFile.length(),
                            category = JunkCategory.DUPLICATE_FILES,
                            lastModifiedEpochMs = dupFile.lastModified(),
                            sha256Hash = hashHex.take(16),
                            requiresUserConfirmation = true,
                            isSelected = false
                        )
                    )
                }
            }
        }

        val distinctCandidates = candidates.distinctBy { it.absolutePath }
            .sortedByDescending { it.sizeBytes }

        val totalBytes = distinctCandidates.sumOf { it.sizeBytes }
        val safeAutoBytes = distinctCandidates.filter { !it.requiresUserConfirmation }.sumOf { it.sizeBytes }
        val confirmBytes = distinctCandidates.filter { it.requiresUserConfirmation }.sumOf { it.sizeBytes }
        val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)

        JunkScanReport(
            mode = mode,
            candidates = distinctCandidates,
            totalRecoverableBytes = totalBytes,
            safeAutoCleanBytes = safeAutoBytes,
            confirmationRequiredBytes = confirmBytes,
            scanDurationMs = elapsed,
            scannedAtEpochMs = System.currentTimeMillis()
        )
    }

    /**
     * Pure helper for SHA-256 duplicate file identification.
     * Groups files by identical size first, then computes SHA-256 digest and returns
     * secondary copies (preserving the first original file in each hash group).
     */
    fun findDuplicateFilesBySha256(files: List<File>): List<Pair<File, String>> {
        val result = mutableListOf<Pair<File, String>>()
        val sameSizeGroups = files.filter { it.exists() && it.isFile && it.length() > 0L }
            .groupBy { it.length() }
            .filterValues { it.size > 1 }

        for ((_, group) in sameSizeGroups) {
            val seenHashes = mutableSetOf<String>()
            for (file in group) {
                val hash = computeFileSha256(file) ?: continue
                if (!seenHashes.add(hash)) {
                    result.add(file to hash)
                }
            }
        }
        return result
    }

    fun computeFileSha256(file: File): String? {
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            FileInputStream(file).use { fis ->
                var read = fis.read(buffer)
                while (read > 0) {
                    digest.update(buffer, 0, read)
                    read = fis.read(buffer)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }

    /**
     * Deletes only the candidate files that are selected (or safe auto-clean files when `autoOnly = true`).
     * Never deletes unselected user files or active DNS filter rules.
     */
    suspend fun executeJunkClean(
        candidates: List<JunkCandidateItem>,
        mode: JunkCleanMode,
        autoOnly: Boolean = false
    ): CleanHistoryRecord = withContext(Dispatchers.IO) {
        val eligible = if (autoOnly) {
            candidates.filter { !it.requiresUserConfirmation }
        } else {
            candidates.filter { it.isSelected }
        }

        var recoveredBytes = 0L
        var deletedCount = 0

        for (item in eligible) {
            val file = File(item.absolutePath)
            if (file.exists() && file.isFile) {
                val len = file.length()
                val deleted = runCatching { file.delete() }.getOrDefault(false)
                if (deleted) {
                    recoveredBytes += len
                    deletedCount++
                }
            }
        }

        val record = CleanHistoryRecord(
            id = System.currentTimeMillis(),
            timestampEpochMs = System.currentTimeMillis(),
            modeName = mode.title,
            bytesRecovered = recoveredBytes,
            filesDeletedCount = deletedCount,
            summary = if (deletedCount > 0) {
                "Cleaned $deletedCount file(s) (${RuleBasedOptimizationPredictor.formatBytes(recoveredBytes)} recovered)"
            } else {
                "Storage already clean (0 redundant files to remove)"
            }
        )

        if (deletedCount > 0) {
            preferencesRepository.recordCleanHistory(record)
        }
        record
    }

    // --- 3. SMART RAM MANAGER (SAFE CACHE TRIMMING, NO AGGRESSIVE PROCESS KILLING) ---

    suspend fun optimizeRam(): RamOptimizationResult = withContext(Dispatchers.IO) {
        val before = readRamSnapshot()
        val actions = mutableListOf<String>()

        // 1. Trim expired & excess DNS LRU cache entries (safe for active VPN)
        val releasedCacheCount = upstreamDnsResolver.trimExpiredAndExcessCache(targetSize = 96)
        if (releasedCacheCount > 0) {
            actions.add("Released $releasedCacheCount cached DNS response buffers.")
        } else {
            actions.add("Verified DNS resolver LRU cache is compact (${before.adShieldDnsCacheEntries} entries).")
        }

        // 2. Enforce SQLite log retention policy to trim old DNS logs
        repository.enforceLogRetentionPolicy()
        actions.add("Trimmed expired SQLite DNS telemetry logs outside retention window.")

        // 3. Request JVM garbage collection of unreachable objects inside AdShield Pro
        System.runFinalization()
        Runtime.getRuntime().gc()
        actions.add("Compacted AdShield Pro JVM heap allocations.")

        val after = readRamSnapshot()
        val adShieldReclaimed = (
            (before.adShieldJavaHeapUsedBytes + before.adShieldNativeHeapUsedBytes) -
                (after.adShieldJavaHeapUsedBytes + after.adShieldNativeHeapUsedBytes)
            ).coerceAtLeast(0L)
        val sysDelta = (after.availableRamBytes - before.availableRamBytes).coerceAtLeast(0L)

        val recommendations = buildList {
            if (after.isLowMemoryCondition || after.usagePercent >= 85f) {
                add("System RAM usage is ${String.format(Locale.US, "%.1f%%", after.usagePercent)}. Consider closing unused heavy games or video editors from Android Recents.")
            }
            if (after.adShieldJavaHeapUsedBytes > 64L * 1024L * 1024L) {
                add("Switch to Standard or Privacy filter lists if your device has less than 4 GB of physical RAM.")
            }
            if (isEmpty()) {
                add("AdShield Pro memory footprint is healthy (${RuleBasedOptimizationPredictor.formatBytes(after.adShieldJavaHeapUsedBytes)} Java heap). Active VPN protection was preserved without interruption.")
            }
        }

        RamOptimizationResult(
            before = before,
            after = after,
            adShieldBytesReclaimed = adShieldReclaimed,
            systemAvailableDeltaBytes = sysDelta,
            dnsCacheEntriesReleased = releasedCacheCount,
            actionsTaken = actions,
            recommendations = recommendations
        )
    }

    // --- 4. GAME BOOSTER MODE (INSTALLED GAME DISCOVERY, DND, PROFILES, DIRECT LAUNCH) ---

    suspend fun discoverInstalledGames(favoritePackages: Set<String>): List<GameAppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val installed = runCatching {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        }.getOrDefault(emptyList())

        val sessions = preferencesRepository.getSettingsSnapshot().gameSessionRecords
        val sessionCounts = sessions.groupingBy { it.packageName }.eachCount()
        val lastPlayedMap = sessions.groupBy { it.packageName }
            .mapValues { (_, list) -> list.maxOfOrNull { it.startedAtEpochMs } ?: 0L }

        installed.asSequence()
            .filter { appInfo ->
                appInfo.packageName != context.packageName &&
                    pm.getLaunchIntentForPackage(appInfo.packageName) != null
            }
            .map { appInfo ->
                val label = runCatching { pm.getApplicationLabel(appInfo).toString() }
                    .getOrDefault(appInfo.packageName)
                val isCategoryGame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appInfo.category == ApplicationInfo.CATEGORY_GAME
                } else {
                    @Suppress("DEPRECATION")
                    (appInfo.flags and ApplicationInfo.FLAG_IS_GAME) != 0
                }
                val isHeuristicGame = isCategoryGame ||
                    appInfo.packageName.contains("game", ignoreCase = true) ||
                    appInfo.packageName.contains("unity", ignoreCase = true) ||
                    appInfo.packageName.contains("roblox", ignoreCase = true) ||
                    appInfo.packageName.contains("genshin", ignoreCase = true) ||
                    appInfo.packageName.contains("mobile.legends", ignoreCase = true) ||
                    appInfo.packageName.contains("pubg", ignoreCase = true) ||
                    appInfo.packageName.contains("activision", ignoreCase = true) ||
                    appInfo.packageName.contains("supercell", ignoreCase = true) ||
                    appInfo.packageName.contains("mojang", ignoreCase = true)

                GameAppInfo(
                    packageName = appInfo.packageName,
                    appName = label,
                    isSystemDetectedGame = isHeuristicGame,
                    isFavorite = appInfo.packageName in favoritePackages,
                    lastLaunchedEpochMs = lastPlayedMap[appInfo.packageName] ?: 0L,
                    launchCount = sessionCounts[appInfo.packageName] ?: 0
                )
            }
            .sortedWith(
                compareByDescending<GameAppInfo> { it.isFavorite }
                    .thenByDescending { it.isSystemDetectedGame }
                    .thenBy { it.appName.lowercase(Locale.US) }
            )
            .toList()
    }

    fun isDoNotDisturbAccessGranted(): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.isNotificationPolicyAccessGranted
    }

    suspend fun applyGameBoostAndLaunch(
        packageName: String?,
        appName: String?
    ): List<String> = withContext(Dispatchers.IO) {
        val settings = preferencesRepository.getSettingsSnapshot()
        val profile = settings.gamingProfile
        val actions = mutableListOf<String>()

        // 1. Trim AdShield caches before gaming
        val trimmed = upstreamDnsResolver.trimExpiredAndExcessCache(targetSize = profile.dnsCacheMaxEntries / 4)
        actions.add("Prepared in-memory DNS cache for ${profile.title} gaming profile ($trimmed stale entries cleared).")

        // 2. Apply background disk-logging preference if profile minimizes disk I/O
        if (profile.minimizeDiskLogging && settings.detailedDnsLoggingEnabled) {
            preferencesRepository.setDetailedDnsLogging(false)
            actions.add("Paused verbose per-domain SQLite disk writes to minimize storage I/O latency.")
        }

        // 3. Apply Do Not Disturb if user enabled it AND Notification Policy permission is granted
        if (settings.gameBoosterSuppressNotifications) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.isNotificationPolicyAccessGranted) {
                runCatching {
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                    actions.add("Enabled Priority Do Not Disturb notification suppression.")
                }
            } else {
                actions.add("Do Not Disturb requires Android Notification Policy authorization (tap Grant DND Access).")
            }
        }

        // 4. Check thermal state before game launch
        val battery = readBatteryThermalSnapshot()
        if (battery.temperatureCelsius >= settings.gameBoosterTempWarningCelsius) {
            actions.add(
                "Thermal Warning: Battery is at ${String.format(Locale.US, "%.1f°C", battery.temperatureCelsius)} " +
                    "(threshold ${settings.gameBoosterTempWarningCelsius}°C). Lower in-game graphics or unplug charger to reduce thermal throttling."
            )
        }

        // 5. Record session and launch game if specified
        if (!packageName.isNullOrBlank()) {
            activeGamingPackage = packageName
            val ram = readRamSnapshot()
            preferencesRepository.recordGameSession(
                GameSessionRecord(
                    packageName = packageName,
                    appName = appName ?: packageName,
                    profileUsed = profile,
                    startedAtEpochMs = System.currentTimeMillis(),
                    startRamAvailableMb = ram.availableRamBytes / (1024L * 1024L),
                    startBatteryPercent = battery.batteryPercent,
                    startTempCelsius = battery.temperatureCelsius
                )
            )
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                actions.add("Launched ${appName ?: packageName}.")
            }
        }

        actions
    }

    // --- 5. BATTERY & TEMPERATURE OPTIMIZER ---

    suspend fun runAutoBatteryOptimize(selectedMode: BatteryMode? = null): List<String> = withContext(Dispatchers.IO) {
        val currentSettings = preferencesRepository.getSettingsSnapshot()
        val battery = readBatteryThermalSnapshot()
        val effectiveMode = selectedMode ?: currentSettings.batteryMode
        preferencesRepository.setBatteryMode(effectiveMode)

        val actions = mutableListOf<String>()

        when (effectiveMode) {
            BatteryMode.BATTERY_SAVER -> {
                preferencesRepository.setDetailedDnsLogging(false)
                preferencesRepository.setAutoUpdateFilters(true, 48)
                FilterUpdateWorker.scheduleOrCancelPeriodicUpdates(context, true, 48)
                actions.add("Extended background filter update schedule to 48 hours.")
                actions.add("Disabled verbose per-domain SQLite disk logging (anonymous daily totals remain active).")
            }
            BatteryMode.GAMING -> {
                preferencesRepository.setDetailedDnsLogging(false)
                upstreamDnsResolver.trimExpiredAndExcessCache(256)
                actions.add("Paused verbose disk logging and optimized in-memory DNS cache for low-latency gaming.")
            }
            BatteryMode.SMART_AUTOMATIC -> {
                if (battery.isOverheating || (battery.isLowBattery && !battery.isCharging)) {
                    preferencesRepository.setDetailedDnsLogging(false)
                    preferencesRepository.setAutoUpdateFilters(true, 48)
                    FilterUpdateWorker.scheduleOrCancelPeriodicUpdates(context, true, 48)
                    actions.add(
                        "Smart Automatic detected ${if (battery.isOverheating) "high temperature (${battery.temperatureCelsius}°C)" else "low battery (${battery.batteryPercent}%)"}: throttled background syncs to 48h and paused verbose disk writes."
                    )
                } else {
                    preferencesRepository.setAutoUpdateFilters(true, 24)
                    FilterUpdateWorker.scheduleOrCancelPeriodicUpdates(context, true, 24)
                    actions.add("Smart Automatic verified healthy battery (${battery.batteryPercent}%, ${battery.temperatureCelsius}°C): balanced 24h sync schedule active.")
                }
            }
            BatteryMode.BALANCED -> {
                preferencesRepository.setDetailedDnsLogging(true)
                preferencesRepository.setAutoUpdateFilters(true, 24)
                FilterUpdateWorker.scheduleOrCancelPeriodicUpdates(context, true, 24)
                actions.add("Applied Balanced battery profile (24h background sync & local activity logging enabled).")
            }
        }

        val trimmed = upstreamDnsResolver.trimExpiredAndExcessCache(128)
        if (trimmed > 0) {
            actions.add("Trimmed $trimmed expired DNS cache entries to reduce background memory retention.")
        }
        actions.add("Active VPN ad-blocking tunnel was preserved without interruption.")
        actions
    }

    // --- 6. ONE-TAP AI SMART BOOST & DASHBOARD "OPTIMIZE NOW" PIPELINE ---

    suspend fun runOneTapSmartBoost(forceRun: Boolean = false): SmartBoostResult = withContext(Dispatchers.IO) {
        val settings = preferencesRepository.getSettingsSnapshot()
        val now = System.currentTimeMillis()
        val beforeRam = readRamSnapshot()
        val storageBefore = readStorageMetrics()
        val battery = readBatteryThermalSnapshot()

        // Scan both safe temporary caches and deep candidates requiring user approval
        val scanReport = scanJunkFiles(JunkCleanMode.DEEP_SCAN, settings.excludedJunkPaths)

        // Check cooldown: if boosted within last 45 seconds and safe junk < 32 KB and no high memory/thermal pressure
        val secondsSinceLastBoost = (now - settings.lastSmartBoostEpochMs) / 1000L
        if (!forceRun &&
            secondsSinceLastBoost in 0..45 &&
            scanReport.safeAutoCleanBytes < 32 * 1024L &&
            !beforeRam.isLowMemoryCondition &&
            !battery.isOverheating
        ) {
            return@withContext SmartBoostResult(
                beforeRam = beforeRam,
                afterRam = beforeRam,
                storageBefore = storageBefore,
                storageAfter = storageBefore,
                batterySnapshot = battery,
                bytesRecoveredAutomatically = 0L,
                bytesRequiringUserApproval = scanReport.confirmationRequiredBytes,
                filesCleanedCount = 0,
                actionsCompleted = listOf(
                    "Optimization unnecessary — device was optimized ${secondsSinceLastBoost}s ago and is running cleanly.",
                    "Active VPN & DNS ad-blocking rules remain untouched."
                ),
                pendingUserApprovalActions = if (scanReport.confirmationRequiredBytes > 0L) {
                    listOf("${RuleBasedOptimizationPredictor.formatBytes(scanReport.confirmationRequiredBytes)} in large/duplicate/APK files available for review in Junk Cleaner.")
                } else {
                    emptyList()
                },
                wasSkippedDueToRecentBoost = true
            )
        }

        val actionsCompleted = mutableListOf<String>()
        val pendingApprovals = mutableListOf<String>()

        // Step 1: Clean safe app-owned temporary files automatically
        val cleanRecord = executeJunkClean(
            candidates = scanReport.candidates,
            mode = JunkCleanMode.QUICK_CLEAN,
            autoOnly = true
        )
        if (cleanRecord.filesDeletedCount > 0) {
            actionsCompleted.add(
                "Cleaned ${cleanRecord.filesDeletedCount} temporary cache file(s) (${RuleBasedOptimizationPredictor.formatBytes(cleanRecord.bytesRecovered)} recovered)."
            )
        } else {
            actionsCompleted.add("Checked temporary cache directories (already clean).")
        }

        // Step 2: Optimize AdShield Pro RAM & caches safely
        val ramResult = optimizeRam()
        actionsCompleted.addAll(ramResult.actionsTaken)

        // Step 3: Check battery & thermal state and apply safe workload adjustments if overheating or low battery
        if (battery.isOverheating || (battery.isLowBattery && !battery.isCharging)) {
            val batteryActions = runAutoBatteryOptimize(BatteryMode.SMART_AUTOMATIC)
            actionsCompleted.addAll(batteryActions.take(1))
        } else {
            actionsCompleted.add(
                "Verified battery health (${battery.batteryPercent}%, ${String.format(Locale.US, "%.1f°C", battery.temperatureCelsius)} — ${battery.thermalStatusLabel})."
            )
        }

        // Step 4: Identify actions requiring explicit user approval
        if (scanReport.confirmationRequiredBytes > 0L) {
            val count = scanReport.candidates.count { it.requiresUserConfirmation }
            pendingApprovals.add(
                "Review $count large/duplicate/APK file(s) (${RuleBasedOptimizationPredictor.formatBytes(scanReport.confirmationRequiredBytes)}) in Junk Cleaner."
            )
        }
        if (beforeRam.isLowMemoryCondition) {
            pendingApprovals.add("Android reports low system RAM: close unused background apps via Android System Settings.")
        }

        preferencesRepository.recordSmartBoostTimestamp(System.currentTimeMillis())

        val afterRam = readRamSnapshot()
        val storageAfter = readStorageMetrics()

        SmartBoostResult(
            beforeRam = beforeRam,
            afterRam = afterRam,
            storageBefore = storageBefore,
            storageAfter = storageAfter,
            batterySnapshot = battery,
            bytesRecoveredAutomatically = cleanRecord.bytesRecovered,
            bytesRequiringUserApproval = scanReport.confirmationRequiredBytes,
            filesCleanedCount = cleanRecord.filesDeletedCount,
            actionsCompleted = actionsCompleted,
            pendingUserApprovalActions = pendingApprovals,
            wasSkippedDueToRecentBoost = false
        )
    }
}
