package com.adshield.pro.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.adshield.pro.data.local.preferences.UserPreferencesRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Restores AdShield Pro VPN protection automatically after device boot or app update
 * if the user had protection active and enabled boot restoration.
 */
@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {

    @Inject lateinit var preferencesRepository: UserPreferencesRepository

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val settings = preferencesRepository.getSettingsSnapshot()
                if (settings.vpnShouldRemainActive && settings.autoStartOnBoot) {
                    // Verify VPN consent is still granted before starting foreground service
                    val prepareIntent = VpnService.prepare(context)
                    if (prepareIntent == null) {
                        val vpnIntent = Intent(context, AdShieldVpnService::class.java).apply {
                            this.action = AdShieldVpnService.ACTION_START_VPN
                        }
                        ContextCompat.startForegroundService(context, vpnIntent)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
