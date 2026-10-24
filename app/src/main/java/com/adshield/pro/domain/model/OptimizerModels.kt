package com.adshield.pro.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class JunkCleanMode(val title: String, val subtitle: String) {
    QUICK_CLEAN(
        title = "Quick Clean",
        subtitle = "Fast scan of safe app temporary caches, residual logs, and staging files."
    ),
    DEEP_SCAN(
        title = "Deep Scan",
        subtitle = "Comprehensive scan including large files, SHA-256 duplicates, old downloads, and unused APKs."
    ),
    SCHEDULED_CLEAN(
        title = "Scheduled Clean",
        subtitle = "Automatic background cleanup of safe AdShield Pro temporary files via WorkManager."
    ),
    CUSTOM_CLEAN(
        title = "Custom Clean",
        subtitle = "User-selected categories and custom folder exclusion rules."
    )
}

@Serializable
enum class JunkCategory(
    val displayName: String,
    val description: String,
    val autoDeleteSafe: Boolean
) {
    APP_TEMP_CACHE(
        displayName = "App Temporary Cache",
        description = "Safe application cache, WebView HTTP cache, and code cache files.",
        autoDeleteSafe = true
    ),
    ADSHIELD_TEMP_STAGING(
        displayName = "AdShield Temp & Staging",
        description = "Residual blocklist download staging files and expired diagnostic cache.",
        autoDeleteSafe = true
    ),
    UNUSED_APKS(
        displayName = "Unused APK Installers",
        description = "Accessible .apk package archives that are no longer needed after installation.",
        autoDeleteSafe = false
    ),
    DUPLICATE_FILES(
        displayName = "Duplicate Files (SHA-256)",
        description = "Byte-identical files verified via SHA-256 cryptographic hash.",
        autoDeleteSafe = false
    ),
    OLD_DOWNLOADS(
        displayName = "Old Accessible Downloads",
        description = "Accessible files in Downloads older than 30 days (requires confirmation).",
        autoDeleteSafe = false
    ),
    LARGE_FILES(
        displayName = "Large Files (>10 MB)",
        description = "Large accessible files consuming significant storage space (requires confirmation).",
        autoDeleteSafe = false
    )
}

data class JunkCandidateItem(
    val id: String,
    val absolutePath: String,
    val displayName: String,
    val sizeBytes: Long,
    val category: JunkCategory,
    val lastModifiedEpochMs: Long,
    val sha256Hash: String? = null,
    val requiresUserConfirmation: Boolean = !category.autoDeleteSafe,
    val isSelected: Boolean = category.autoDeleteSafe
)

data class JunkScanReport(
    val mode: JunkCleanMode,
    val candidates: List<JunkCandidateItem>,
    val totalRecoverableBytes: Long,
    val safeAutoCleanBytes: Long,
    val confirmationRequiredBytes: Long,
    val scanDurationMs: Long,
    val scannedAtEpochMs: Long
)

@Serializable
data class CleanHistoryRecord(
    val id: Long = 0L,
    val timestampEpochMs: Long,
    val modeName: String,
    val bytesRecovered: Long,
    val filesDeletedCount: Int,
    val summary: String
)

data class StorageMetrics(
    val totalBytes: Long,
    val availableBytes: Long,
    val usedBytes: Long,
    val usedPercent: Float,
    val isLowStorage: Boolean
)

data class RamSnapshot(
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val usedRamBytes: Long,
    val usagePercent: Float,
    val isLowMemoryCondition: Boolean,
    val lowMemoryThresholdBytes: Long,
    val adShieldJavaHeapUsedBytes: Long,
    val adShieldNativeHeapUsedBytes: Long,
    val adShieldDnsCacheEntries: Int,
    val timestampEpochMs: Long = System.currentTimeMillis()
)

data class RamOptimizationResult(
    val before: RamSnapshot,
    val after: RamSnapshot,
    val adShieldBytesReclaimed: Long,
    val systemAvailableDeltaBytes: Long,
    val dnsCacheEntriesReleased: Int,
    val actionsTaken: List<String>,
    val recommendations: List<String>,
    val timestampEpochMs: Long = System.currentTimeMillis()
)

@Serializable
enum class GamingProfile(
    val title: String,
    val subtitle: String,
    val dnsCacheMaxEntries: Int,
    val pauseBackgroundSync: Boolean,
    val minimizeDiskLogging: Boolean
) {
    BALANCED(
        title = "Balanced",
        subtitle = "Standard DNS caching and normal background telemetry while gaming.",
        dnsCacheMaxEntries = 2048,
        pauseBackgroundSync = true,
        minimizeDiskLogging = false
    ),
    PERFORMANCE(
        title = "Performance",
        subtitle = "Pauses background blocklist syncs and minimizes disk I/O for lowest DNS latency.",
        dnsCacheMaxEntries = 4096,
        pauseBackgroundSync = true,
        minimizeDiskLogging = true
    ),
    BATTERY_SAVER(
        title = "Battery Saver",
        subtitle = "Reduces background wakeups and thermal load during extended gaming sessions.",
        dnsCacheMaxEntries = 1024,
        pauseBackgroundSync = true,
        minimizeDiskLogging = true
    )
}

data class GameAppInfo(
    val packageName: String,
    val appName: String,
    val isSystemDetectedGame: Boolean,
    val isFavorite: Boolean,
    val lastLaunchedEpochMs: Long = 0L,
    val launchCount: Int = 0
)

@Serializable
data class GameSessionRecord(
    val packageName: String,
    val appName: String,
    val profileUsed: GamingProfile,
    val startedAtEpochMs: Long,
    val startRamAvailableMb: Long,
    val startBatteryPercent: Int,
    val startTempCelsius: Float
)

@Serializable
enum class BatteryMode(
    val title: String,
    val subtitle: String,
    val recommendedSyncHours: Int,
    val enableDetailedDiskLogs: Boolean
) {
    BALANCED(
        title = "Balanced",
        subtitle = "Standard 24h filter sync and normal local DNS activity logging.",
        recommendedSyncHours = 24,
        enableDetailedDiskLogs = true
    ),
    BATTERY_SAVER(
        title = "Battery Saver",
        subtitle = "Minimizes disk writes and extends background sync intervals to conserve power.",
        recommendedSyncHours = 48,
        enableDetailedDiskLogs = false
    ),
    GAMING(
        title = "Gaming",
        subtitle = "Prioritizes low-latency in-memory DNS resolution and defers background tasks.",
        recommendedSyncHours = 24,
        enableDetailedDiskLogs = false
    ),
    SMART_AUTOMATIC(
        title = "Smart Automatic",
        subtitle = "Dynamically adapts background workload based on battery level and temperature.",
        recommendedSyncHours = 24,
        enableDetailedDiskLogs = true
    )
}

data class BatteryThermalSnapshot(
    val batteryPercent: Int,
    val temperatureCelsius: Float,
    val isCharging: Boolean,
    val chargingSourceLabel: String,
    val voltageMillivolts: Int,
    val healthStatusLabel: String,
    val thermalStatusLabel: String,
    val isOverheating: Boolean,
    val isLowBattery: Boolean,
    val adShieldBatteryImpactSummary: String,
    val recommendations: List<String>,
    val timestampEpochMs: Long = System.currentTimeMillis()
)

data class PerformanceBottleneck(
    val title: String,
    val detail: String,
    val severity: BottleneckSeverity,
    val recommendedAction: String
)

enum class BottleneckSeverity {
    INFO,
    MODERATE,
    HIGH
}

/**
 * Modular interface for intelligent device health evaluation.
 * Default implementation uses deterministic rule-based telemetry analysis,
 * structured so an optional on-device TensorFlow Lite / AICore model can be plugged in.
 */
interface OptimizationPredictor {
    fun evaluateDeviceHealth(
        ram: RamSnapshot,
        storage: StorageMetrics,
        battery: BatteryThermalSnapshot,
        recoverableJunkBytes: Long
    ): DeviceHealthAssessment
}

data class DeviceHealthAssessment(
    val readinessScore: Int, // 0..100 based on real measured RAM/Storage/Thermal metrics
    val statusHeadline: String,
    val bottlenecks: List<PerformanceBottleneck>,
    val recommendations: List<String>,
    val isOptimizationRecommended: Boolean
)

data class SmartBoostResult(
    val beforeRam: RamSnapshot,
    val afterRam: RamSnapshot,
    val storageBefore: StorageMetrics,
    val storageAfter: StorageMetrics,
    val batterySnapshot: BatteryThermalSnapshot,
    val bytesRecoveredAutomatically: Long,
    val bytesRequiringUserApproval: Long,
    val filesCleanedCount: Int,
    val actionsCompleted: List<String>,
    val pendingUserApprovalActions: List<String>,
    val wasSkippedDueToRecentBoost: Boolean,
    val executedAtEpochMs: Long = System.currentTimeMillis()
)
