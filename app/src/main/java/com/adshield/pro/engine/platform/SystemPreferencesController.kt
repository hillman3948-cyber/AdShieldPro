package com.adshield.pro.engine.platform

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.TileService
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.adshield.pro.MainActivity
import com.adshield.pro.R
import com.adshield.pro.data.local.preferences.UserPreferencesRepository
import com.adshield.pro.data.repository.IntelligenceRecorder
import com.adshield.pro.domain.model.PrivateDnsCycleStep
import com.adshield.pro.domain.model.PrivateDnsMode
import com.adshield.pro.domain.model.PrivateDnsSystemState
import com.adshield.pro.domain.model.SystemPreferenceWriteResult
import com.adshield.pro.domain.model.SystemTilesLiveStatus
import com.adshield.pro.domain.model.UsbDebuggingSystemState
import com.adshield.pro.engine.intelligence.IntelligenceEventFactory
import com.adshield.pro.service.AutoRevertReceiver
import com.adshield.pro.service.PrivateDnsTileService
import com.adshield.pro.service.UsbDebuggingTileService
import com.adshield.pro.worker.AutoRevertTileWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates Android `Settings.Global` reads/writes, Quick Settings tile refreshes, and
 * durable auto-revert scheduling (WorkManager + permissionless AlarmManager) for Private DNS
 * and USB Debugging tiles.
 */
@Singleton
class SystemPreferencesController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesRepository: UserPreferencesRepository,
    private val intelligenceRecorder: IntelligenceRecorder
) {

    private val mutex = Mutex()

    fun hasWriteSecureSettingsPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED

    fun readPrivateDnsState(): PrivateDnsSystemState {
        val resolver = context.contentResolver
        val rawMode = try {
            Settings.Global.getString(resolver, KEY_PRIVATE_DNS_MODE)
        } catch (_: SecurityException) {
            return PrivateDnsSystemState(mode = PrivateDnsMode.AUTO, specifier = null, readable = false)
        } catch (_: Exception) {
            return PrivateDnsSystemState(mode = PrivateDnsMode.AUTO, specifier = null, readable = false)
        }
        val specifier = runCatching {
            Settings.Global.getString(resolver, KEY_PRIVATE_DNS_SPECIFIER)
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        return PrivateDnsSystemState(
            mode = PrivateDnsMode.fromGlobalSetting(rawMode),
            specifier = specifier,
            readable = true
        )
    }

    fun readUsbDebuggingState(): UsbDebuggingSystemState {
        val resolver = context.contentResolver
        val adbInt = try {
            Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED)
        } catch (_: Settings.SettingNotFoundException) {
            0
        } catch (_: SecurityException) {
            return UsbDebuggingSystemState(enabled = false, developerOptionsEnabled = false, readable = false)
        } catch (_: Exception) {
            return UsbDebuggingSystemState(enabled = false, developerOptionsEnabled = false, readable = false)
        }
        val devInt = try {
            Settings.Global.getInt(resolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED)
        } catch (_: Exception) {
            0
        }
        return UsbDebuggingSystemState(
            enabled = adbInt == 1,
            developerOptionsEnabled = devInt == 1,
            readable = true
        )
    }

    fun readLiveStatus(): SystemTilesLiveStatus = SystemTilesLiveStatus(
        hasWriteSecureSettingsPermission = hasWriteSecureSettingsPermission(),
        privateDnsState = readPrivateDnsState(),
        usbDebuggingState = readUsbDebuggingState(),
        isRootBinaryDetected = isRootBinaryDetected(),
        isShizukuInstalled = isPackageLaunchable(PKG_SHIZUKU),
        isLadbInstalled = isPackageLaunchable(PKG_LADB)
    )

    fun isRootBinaryDetected(): Boolean = SU_CANDIDATE_PATHS.any { path ->
        runCatching { File(path).exists() }.getOrDefault(false)
    }

    private fun isPackageLaunchable(packageName: String): Boolean = runCatching {
        context.packageManager.getLaunchIntentForPackage(packageName) != null
    }.getOrDefault(false)

    /**
     * Attempts a one-tap permission grant via `su` on rooted devices and immediately verifies
     * with [hasWriteSecureSettingsPermission]. Fails cleanly and honestly on non-rooted devices.
     */
    suspend fun tryGrantPermissionWithRoot(): SystemPreferenceWriteResult = withContext(Dispatchers.IO) {
        if (hasWriteSecureSettingsPermission()) {
            requestTileUpdates()
            return@withContext SystemPreferenceWriteResult.Success("WRITE_SECURE_SETTINGS is already granted.")
        }
        if (!isRootBinaryDetected()) {
            return@withContext SystemPreferenceWriteResult.RejectedBySystem(
                "No root (su) binary detected on this device. Use Wireless Debugging (LADB/Shizuku) or PC ADB."
            )
        }
        var process: Process? = null
        try {
            val command = SystemTileEngine.buildLocalShellGrantCommand(context.packageName)
            process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val finished = process.waitFor(10, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@withContext SystemPreferenceWriteResult.RejectedBySystem(
                    "Root shell prompt timed out. Approve the superuser dialog or use ADB."
                )
            }
            if (hasWriteSecureSettingsPermission()) {
                intelligenceRecorder.emit(
                    IntelligenceEventFactory.privacySettingChanged(
                        title = "Quick Settings tile permission granted",
                        detail = "Granted WRITE_SECURE_SETTINGS on device via root shell",
                        timestampEpochMs = System.currentTimeMillis()
                    )
                )
                requestTileUpdates()
                SystemPreferenceWriteResult.Success("Permission granted! Quick Settings tiles are now ready.")
            } else {
                SystemPreferenceWriteResult.RejectedBySystem(
                    "Root grant was denied or failed (exit ${process.exitValue()}). Use Wireless Debugging or PC ADB."
                )
            }
        } catch (error: Exception) {
            SystemPreferenceWriteResult.RejectedBySystem(
                "Root grant unavailable (${error.message ?: error.javaClass.simpleName}). Use Wireless Debugging or PC ADB."
            )
        } finally {
            runCatching { process?.destroy() }
        }
    }

    suspend fun cyclePrivateDnsFromTile(): SystemPreferenceWriteResult = withContext(Dispatchers.IO) {
        val current = readPrivateDnsState()
        val settings = preferencesRepository.getSettingsSnapshot()
        val next = SystemTileEngine.nextPrivateDnsStep(current, settings.privateDnsTileConfig)
        applyPrivateDnsStep(step = next, fromTile = true)
    }

    suspend fun applyPrivateDnsStep(
        step: PrivateDnsCycleStep,
        fromTile: Boolean
    ): SystemPreferenceWriteResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!hasWriteSecureSettingsPermission()) {
                return@withContext SystemPreferenceWriteResult.PermissionDenied
            }

            val previous = readPrivateDnsState()
            val writeError = writePrivateDnsSetting(step)
            if (writeError != null) {
                return@withContext writeError
            }

            val now = System.currentTimeMillis()
            val updatedConfig = preferencesRepository.updatePrivateDnsTileConfig { current ->
                if (fromTile) {
                    SystemTileEngine.onPrivateDnsChangedViaTile(previous, step, current, now)
                } else {
                    current.copy(
                        autoRevertTargetMode = null,
                        autoRevertTargetHostname = null,
                        autoRevertDeadlineEpochMs = 0L
                    )
                }
            }

            if (updatedConfig.autoRevertDeadlineEpochMs > now) {
                scheduleAutoRevert(TARGET_PRIVATE_DNS, updatedConfig.autoRevertDeadlineEpochMs)
            } else {
                cancelAutoRevert(TARGET_PRIVATE_DNS)
            }

            val label = stepDescription(step)
            intelligenceRecorder.emit(
                IntelligenceEventFactory.privacySettingChanged(
                    title = "Private DNS switched to $label",
                    detail = "System Private DNS changed via ${if (fromTile) "Quick Settings tile" else "app controls"} to $label",
                    timestampEpochMs = now
                )
            )
            requestTileUpdates()
            SystemPreferenceWriteResult.Success("Private DNS set to $label")
        }
    }

    suspend fun toggleUsbDebuggingFromTile(): SystemPreferenceWriteResult = withContext(Dispatchers.IO) {
        val current = readUsbDebuggingState()
        setUsbDebuggingEnabled(!current.enabled, fromTile = true)
    }

    suspend fun setUsbDebuggingEnabled(
        enabled: Boolean,
        fromTile: Boolean = false
    ): SystemPreferenceWriteResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!hasWriteSecureSettingsPermission()) {
                return@withContext SystemPreferenceWriteResult.PermissionDenied
            }

            val writeError = writeUsbDebuggingSetting(enabled)
            if (writeError != null) {
                return@withContext writeError
            }

            val now = System.currentTimeMillis()
            val updatedConfig = preferencesRepository.updateUsbDebuggingTileConfig { current ->
                SystemTileEngine.onUsbDebuggingToggled(enabled, current, now)
            }

            if (updatedConfig.autoRevertDeadlineEpochMs > now) {
                scheduleAutoRevert(TARGET_USB_DEBUGGING, updatedConfig.autoRevertDeadlineEpochMs)
            } else {
                cancelAutoRevert(TARGET_USB_DEBUGGING)
            }

            val stateText = if (enabled) "On" else "Off"
            val revertDetail = if (updatedConfig.autoRevertDeadlineEpochMs > now) {
                " (auto-revert in ${SystemTileEngine.clampDelayMinutes(updatedConfig.autoRevertDelayMinutes)} min)"
            } else {
                ""
            }
            intelligenceRecorder.emit(
                IntelligenceEventFactory.privacySettingChanged(
                    title = "USB Debugging turned $stateText",
                    detail = "System ADB setting changed via ${if (fromTile) "Quick Settings tile" else "app controls"} to $stateText$revertDetail",
                    timestampEpochMs = now
                )
            )
            requestTileUpdates()
            SystemPreferenceWriteResult.Success("USB Debugging turned $stateText$revertDetail")
        }
    }

    suspend fun armUsbDebuggingAutoRevertNow(): SystemPreferenceWriteResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val currentState = readUsbDebuggingState()
            if (!currentState.enabled) {
                return@withContext SystemPreferenceWriteResult.RejectedBySystem("USB Debugging is already Off.")
            }
            val now = System.currentTimeMillis()
            val updated = preferencesRepository.updateUsbDebuggingTileConfig { current ->
                SystemTileEngine.armUsbDebuggingAutoRevert(current, now)
            }
            scheduleAutoRevert(TARGET_USB_DEBUGGING, updated.autoRevertDeadlineEpochMs)
            requestTileUpdates()
            val mins = SystemTileEngine.clampDelayMinutes(updated.autoRevertDelayMinutes)
            SystemPreferenceWriteResult.Success("USB Debugging will automatically turn Off in $mins min.")
        }
    }

    suspend fun cancelUsbDebuggingAutoRevert() = withContext(Dispatchers.IO) {
        mutex.withLock {
            preferencesRepository.updateUsbDebuggingTileConfig {
                it.copy(autoRevertDeadlineEpochMs = 0L)
            }
            cancelAutoRevert(TARGET_USB_DEBUGGING)
            requestTileUpdates()
        }
    }

    suspend fun cancelPrivateDnsAutoRevert() = withContext(Dispatchers.IO) {
        mutex.withLock {
            preferencesRepository.updatePrivateDnsTileConfig {
                it.copy(
                    autoRevertTargetMode = null,
                    autoRevertTargetHostname = null,
                    autoRevertDeadlineEpochMs = 0L
                )
            }
            cancelAutoRevert(TARGET_PRIVATE_DNS)
            requestTileUpdates()
        }
    }

    /**
     * Executes any overdue auto-reverts idempotently and cleans up timers whose settings were
     * already reverted manually outside the app. Safe to call from workers, alarms, boot, or UI.
     */
    suspend fun executeDueAutoReverts(): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val settings = preferencesRepository.getSettingsSnapshot()
            var revertedCount = 0
            var stateChanged = false

            // 1. USB Debugging auto-revert check
            val usbConfig = settings.usbDebuggingTileConfig
            val usbState = readUsbDebuggingState()
            if (SystemTileEngine.isUsbDebuggingRevertDue(usbState.enabled, usbConfig, now)) {
                stateChanged = true
                if (hasWriteSecureSettingsPermission()) {
                    val err = writeUsbDebuggingSetting(false)
                    if (err == null) {
                        revertedCount++
                        preferencesRepository.updateUsbDebuggingTileConfig { it.copy(autoRevertDeadlineEpochMs = 0L) }
                        cancelAutoRevert(TARGET_USB_DEBUGGING)
                        intelligenceRecorder.emit(
                            IntelligenceEventFactory.automationJob(
                                jobLabel = "USB Debugging auto-revert",
                                success = true,
                                detail = "USB Debugging automatically reverted to Off after scheduled timer",
                                durationMs = 0L,
                                timestampEpochMs = now
                            )
                        )
                        postAutoRevertNotification(
                            notificationId = NOTIF_ID_USB_REVERT,
                            title = "USB Debugging reverted to Off",
                            message = "AdShield Pro automatically disabled USB Debugging after the configured timer."
                        )
                    } else {
                        // Clear deadline so we don't spin endlessly on a system rejection
                        preferencesRepository.updateUsbDebuggingTileConfig { it.copy(autoRevertDeadlineEpochMs = 0L) }
                        cancelAutoRevert(TARGET_USB_DEBUGGING)
                        intelligenceRecorder.emit(
                            IntelligenceEventFactory.automationJob(
                                jobLabel = "USB Debugging auto-revert",
                                success = false,
                                detail = "System rejected reverting USB Debugging to Off",
                                durationMs = 0L,
                                timestampEpochMs = now
                            )
                        )
                    }
                } else {
                    preferencesRepository.updateUsbDebuggingTileConfig { it.copy(autoRevertDeadlineEpochMs = 0L) }
                    cancelAutoRevert(TARGET_USB_DEBUGGING)
                }
            } else if (!usbState.enabled && usbConfig.autoRevertDeadlineEpochMs > 0L) {
                stateChanged = true
                preferencesRepository.updateUsbDebuggingTileConfig { it.copy(autoRevertDeadlineEpochMs = 0L) }
                cancelAutoRevert(TARGET_USB_DEBUGGING)
            }

            // 2. Private DNS auto-revert check
            val dnsConfig = settings.privateDnsTileConfig
            val dnsState = readPrivateDnsState()
            if (SystemTileEngine.isPrivateDnsRevertDue(dnsState, dnsConfig, now)) {
                stateChanged = true
                val targetStep = SystemTileEngine.privateDnsTargetStep(dnsConfig)
                if (targetStep != null && hasWriteSecureSettingsPermission()) {
                    val err = writePrivateDnsSetting(targetStep)
                    if (err == null) {
                        revertedCount++
                        val label = stepDescription(targetStep)
                        preferencesRepository.updatePrivateDnsTileConfig {
                            it.copy(
                                autoRevertTargetMode = null,
                                autoRevertTargetHostname = null,
                                autoRevertDeadlineEpochMs = 0L
                            )
                        }
                        cancelAutoRevert(TARGET_PRIVATE_DNS)
                        intelligenceRecorder.emit(
                            IntelligenceEventFactory.automationJob(
                                jobLabel = "Private DNS auto-revert",
                                success = true,
                                detail = "Private DNS automatically reverted to $label",
                                durationMs = 0L,
                                timestampEpochMs = now
                            )
                        )
                        postAutoRevertNotification(
                            notificationId = NOTIF_ID_DNS_REVERT,
                            title = "Private DNS reverted",
                            message = "AdShield Pro automatically restored Private DNS to $label."
                        )
                    } else {
                        preferencesRepository.updatePrivateDnsTileConfig {
                            it.copy(
                                autoRevertTargetMode = null,
                                autoRevertTargetHostname = null,
                                autoRevertDeadlineEpochMs = 0L
                            )
                        }
                        cancelAutoRevert(TARGET_PRIVATE_DNS)
                    }
                } else {
                    preferencesRepository.updatePrivateDnsTileConfig {
                        it.copy(
                            autoRevertTargetMode = null,
                            autoRevertTargetHostname = null,
                            autoRevertDeadlineEpochMs = 0L
                        )
                    }
                    cancelAutoRevert(TARGET_PRIVATE_DNS)
                }
            } else if (dnsConfig.autoRevertDeadlineEpochMs > 0L &&
                now + SystemTileEngine.REVERT_EARLY_TOLERANCE_MS >= dnsConfig.autoRevertDeadlineEpochMs
            ) {
                stateChanged = true
                preferencesRepository.updatePrivateDnsTileConfig {
                    it.copy(
                        autoRevertTargetMode = null,
                        autoRevertTargetHostname = null,
                        autoRevertDeadlineEpochMs = 0L
                    )
                }
                cancelAutoRevert(TARGET_PRIVATE_DNS)
            }

            if (stateChanged) {
                requestTileUpdates()
            }
            revertedCount
        }
    }

    /**
     * Re-arms AlarmManager/WorkManager after device reboot or executes overdue reverts immediately.
     */
    suspend fun reconcileAutoRevertTimers() {
        executeDueAutoReverts()
        val now = System.currentTimeMillis()
        val settings = preferencesRepository.getSettingsSnapshot()
        if (settings.usbDebuggingTileConfig.autoRevertDeadlineEpochMs > now) {
            scheduleAutoRevert(TARGET_USB_DEBUGGING, settings.usbDebuggingTileConfig.autoRevertDeadlineEpochMs)
        }
        if (settings.privateDnsTileConfig.autoRevertDeadlineEpochMs > now) {
            scheduleAutoRevert(TARGET_PRIVATE_DNS, settings.privateDnsTileConfig.autoRevertDeadlineEpochMs)
        }
    }

    fun requestTileUpdates() {
        runCatching {
            TileService.requestListeningState(
                context,
                ComponentName(context, PrivateDnsTileService::class.java)
            )
        }
        runCatching {
            TileService.requestListeningState(
                context,
                ComponentName(context, UsbDebuggingTileService::class.java)
            )
        }
    }

    private fun writePrivateDnsSetting(step: PrivateDnsCycleStep): SystemPreferenceWriteResult? {
        val resolver = context.contentResolver
        return try {
            when (step) {
                PrivateDnsCycleStep.Off -> {
                    val ok = Settings.Global.putString(resolver, KEY_PRIVATE_DNS_MODE, PrivateDnsMode.OFF.globalSettingValue)
                    val readBack = readPrivateDnsState()
                    if (!ok || (readBack.readable && readBack.mode != PrivateDnsMode.OFF)) {
                        SystemPreferenceWriteResult.RejectedBySystem("Android kept Private DNS unchanged.")
                    } else null
                }
                PrivateDnsCycleStep.Auto -> {
                    val ok = Settings.Global.putString(resolver, KEY_PRIVATE_DNS_MODE, PrivateDnsMode.AUTO.globalSettingValue)
                    val readBack = readPrivateDnsState()
                    if (!ok || (readBack.readable && readBack.mode != PrivateDnsMode.AUTO)) {
                        SystemPreferenceWriteResult.RejectedBySystem("Android kept Private DNS unchanged.")
                    } else null
                }
                is PrivateDnsCycleStep.Hostname -> {
                    val normalized = SystemTileEngine.validatePrivateDnsHostname(step.hostname)
                        .getOrElse {
                            return SystemPreferenceWriteResult.RejectedBySystem(it.message ?: "Invalid hostname")
                        }
                    val okSpec = Settings.Global.putString(resolver, KEY_PRIVATE_DNS_SPECIFIER, normalized)
                    val okMode = Settings.Global.putString(resolver, KEY_PRIVATE_DNS_MODE, PrivateDnsMode.HOSTNAME.globalSettingValue)
                    val readBack = readPrivateDnsState()
                    val matched = readBack.mode == PrivateDnsMode.HOSTNAME &&
                        SystemTileEngine.normalizeHostname(readBack.specifier.orEmpty()) == normalized
                    if (!okSpec || !okMode || (readBack.readable && !matched)) {
                        SystemPreferenceWriteResult.RejectedBySystem("Android did not apply Private DNS hostname '$normalized'.")
                    } else null
                }
            }
        } catch (_: SecurityException) {
            SystemPreferenceWriteResult.PermissionDenied
        } catch (error: Exception) {
            SystemPreferenceWriteResult.RejectedBySystem(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun writeUsbDebuggingSetting(enabled: Boolean): SystemPreferenceWriteResult? {
        val resolver = context.contentResolver
        return try {
            val targetInt = if (enabled) 1 else 0
            val ok = Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, targetInt)
            val readBack = readUsbDebuggingState()
            if (!ok || (readBack.readable && readBack.enabled != enabled)) {
                val reason = if (enabled && !readBack.developerOptionsEnabled) {
                    "Android kept USB Debugging off because Developer Options is disabled. Enable Developer Options in Android Settings first."
                } else {
                    "Android did not apply the USB Debugging change."
                }
                SystemPreferenceWriteResult.RejectedBySystem(reason)
            } else {
                null
            }
        } catch (_: SecurityException) {
            SystemPreferenceWriteResult.PermissionDenied
        } catch (error: Exception) {
            SystemPreferenceWriteResult.RejectedBySystem(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun scheduleAutoRevert(target: String, deadlineEpochMs: Long) {
        val delayMs = (deadlineEpochMs - System.currentTimeMillis()).coerceAtLeast(1_000L)
        val workName = workNameFor(target)
        runCatching {
            val request = OneTimeWorkRequestBuilder<AutoRevertTileWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                workName,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
        runCatching {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return@runCatching
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                deadlineEpochMs,
                alarmPendingIntent(target)
            )
        }
    }

    private fun cancelAutoRevert(target: String) {
        runCatching {
            WorkManager.getInstance(context).cancelUniqueWork(workNameFor(target))
        }
        runCatching {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return@runCatching
            alarmManager.cancel(alarmPendingIntent(target))
        }
    }

    private fun alarmPendingIntent(target: String): PendingIntent {
        val requestCode = if (target == TARGET_USB_DEBUGGING) ALARM_REQ_USB else ALARM_REQ_DNS
        val intent = Intent(context, AutoRevertReceiver::class.java).apply {
            action = AutoRevertReceiver.ACTION_AUTO_REVERT
            putExtra(AutoRevertReceiver.EXTRA_TARGET, target)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun workNameFor(target: String): String =
        if (target == TARGET_USB_DEBUGGING) WORK_USB_REVERT else WORK_DNS_REVERT

    private fun stepDescription(step: PrivateDnsCycleStep): String = when (step) {
        PrivateDnsCycleStep.Off -> "Off"
        PrivateDnsCycleStep.Auto -> "Auto"
        is PrivateDnsCycleStep.Hostname ->
            if (step.label.equals(step.hostname, ignoreCase = true)) step.hostname
            else "${step.label} (${step.hostname})"
    }

    private fun postAutoRevertNotification(
        notificationId: Int,
        title: String,
        message: String
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Quick Settings auto-revert",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notifies when a temporary Quick Settings preference automatically reverts"
        }
        nm.createNotificationChannel(channel)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_QUICK_TILES
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_shield)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        nm.notify(notificationId, notification)
    }

    companion object {
        const val KEY_PRIVATE_DNS_MODE = "private_dns_mode"
        const val KEY_PRIVATE_DNS_SPECIFIER = "private_dns_specifier"

        const val ACTION_OPEN_QUICK_TILES = "com.adshield.pro.action.OPEN_QUICK_TILES"
        const val EXTRA_TILE_COMPONENT = "com.adshield.pro.extra.TILE_COMPONENT"

        const val TARGET_USB_DEBUGGING = "usb_debugging"
        const val TARGET_PRIVATE_DNS = "private_dns"

        private const val WORK_USB_REVERT = "adshield_auto_revert_usb_debugging"
        private const val WORK_DNS_REVERT = "adshield_auto_revert_private_dns"

        private const val ALARM_REQ_USB = 7101
        private const val ALARM_REQ_DNS = 7102

        private const val CHANNEL_ID = "adshield_quick_tiles_revert_channel"
        private const val NOTIF_ID_USB_REVERT = 4301
        private const val NOTIF_ID_DNS_REVERT = 4302

        const val PKG_SHIZUKU = "moe.shizuku.privileged.api"
        const val PKG_LADB = "com.draco.ladb"

        private val SU_CANDIDATE_PATHS = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/vendor/bin/su",
            "/su/bin/su",
            "/system/bin/.ext/.su",
            "/data/local/su",
            "/data/local/xbin/su"
        )
    }
}
