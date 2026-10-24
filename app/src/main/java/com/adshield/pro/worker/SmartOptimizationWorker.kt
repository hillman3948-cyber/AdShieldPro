package com.adshield.pro.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.adshield.pro.MainActivity
import com.adshield.pro.R
import com.adshield.pro.data.local.preferences.UserPreferencesRepository
import com.adshield.pro.domain.model.JunkCleanMode
import com.adshield.pro.engine.optimizer.RuleBasedOptimizationPredictor
import com.adshield.pro.engine.optimizer.SystemOptimizerEngine
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

@HiltWorker
class SmartOptimizationWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val optimizerEngine: SystemOptimizerEngine,
    private val preferencesRepository: UserPreferencesRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val settings = preferencesRepository.getSettingsSnapshot()
        if (!settings.smartAutoOptimizeEnabled) {
            return Result.success()
        }

        // 1. Never interfere with an active gaming session
        if (optimizerEngine.isGamingSessionActive()) {
            return Result.success()
        }

        // 2. Pause nonessential maintenance during low-battery or overheating conditions
        val battery = optimizerEngine.readBatteryThermalSnapshot()
        if ((battery.isLowBattery && !battery.isCharging) || battery.isOverheating) {
            optimizerEngine.runAutoBatteryOptimize()
            return Result.success()
        }

        // 3. Automatically clean only safe, AdShield-owned temporary files
        val scanReport = optimizerEngine.scanJunkFiles(
            mode = JunkCleanMode.DEEP_SCAN,
            excludedPaths = settings.excludedJunkPaths
        )
        if (scanReport.safeAutoCleanBytes > 0L) {
            optimizerEngine.executeJunkClean(
                candidates = scanReport.candidates,
                mode = JunkCleanMode.SCHEDULED_CLEAN,
                autoOnly = true
            )
        }

        // 4. Trim expired DNS cache entries & expired SQLite logs safely
        optimizerEngine.optimizeRam()

        // 5. Notify user only if storage is low or large user-confirmable junk is detected
        val storage = optimizerEngine.readStorageMetrics()
        if (storage.isLowStorage || scanReport.confirmationRequiredBytes >= 25L * 1024L * 1024L) {
            postMaintenanceRecommendationNotification(
                isLowStorage = storage.isLowStorage,
                availableStorageBytes = storage.availableBytes,
                confirmableJunkBytes = scanReport.confirmationRequiredBytes
            )
        }

        return Result.success()
    }

    private fun postMaintenanceRecommendationNotification(
        isLowStorage: Boolean,
        availableStorageBytes: Long,
        confirmableJunkBytes: Long
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                OPTIMIZER_CHANNEL_ID,
                "Smart Optimizer Maintenance",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Alerts when device storage is low or manual file cleanup is recommended"
            }
            nm.createNotificationChannel(channel)
        }

        val openIntent = Intent(appContext, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            202,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val message = if (isLowStorage) {
            "Low storage detected (${RuleBasedOptimizationPredictor.formatBytes(availableStorageBytes)} free). Open AdShield Optimizer to review cleanable files."
        } else {
            "${RuleBasedOptimizationPredictor.formatBytes(confirmableJunkBytes)} in large/duplicate files can be reviewed in Junk Cleaner."
        }

        val notification = NotificationCompat.Builder(appContext, OPTIMIZER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield_logo)
            .setContentTitle("AdShield Smart Optimizer")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        nm.notify(OPTIMIZER_NOTIFICATION_ID, notification)
    }

    companion object {
        private const val WORK_NAME = "adshield_smart_auto_optimization_work"
        private const val OPTIMIZER_CHANNEL_ID = "adshield_optimizer_channel"
        private const val OPTIMIZER_NOTIFICATION_ID = 4202

        fun scheduleOrCancelSmartMaintenance(
            context: Context,
            enabled: Boolean,
            intervalHours: Int
        ) {
            val workManager = WorkManager.getInstance(context)
            if (!enabled) {
                workManager.cancelUniqueWork(WORK_NAME)
                return
            }

            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<SmartOptimizationWorker>(
                repeatInterval = intervalHours.coerceIn(6, 168).toLong(),
                repeatIntervalTimeUnit = TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
