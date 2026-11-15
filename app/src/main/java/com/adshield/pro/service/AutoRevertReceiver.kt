package com.adshield.pro.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.adshield.pro.engine.platform.SystemPreferencesController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Receives inexact `AlarmManager.setAndAllowWhileIdle` triggers for Private DNS / USB Debugging
 * auto-revert timers and performs the revert idempotently via [SystemPreferencesController].
 */
@AndroidEntryPoint
class AutoRevertReceiver : BroadcastReceiver() {

    @Inject lateinit var controller: SystemPreferencesController

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_AUTO_REVERT) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                controller.executeDueAutoReverts()
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_AUTO_REVERT = "com.adshield.pro.action.AUTO_REVERT_TILE"
        const val EXTRA_TARGET = "extra_revert_target"
    }
}
