package com.adshield.pro.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.adshield.pro.MainActivity
import com.adshield.pro.R
import com.adshield.pro.data.local.preferences.UserPreferencesRepository
import com.adshield.pro.domain.model.SystemPreferenceWriteResult
import com.adshield.pro.domain.model.TileVisualState
import com.adshield.pro.engine.platform.SystemPreferencesController
import com.adshield.pro.engine.platform.SystemTileEngine
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Quick Settings tile cycling Android's Private DNS setting across Off, Auto (opportunistic),
 * and user-selected DNS-over-TLS hostnames.
 */
@AndroidEntryPoint
class PrivateDnsTileService : TileService() {

    @Inject lateinit var controller: SystemPreferencesController
    @Inject lateinit var preferencesRepository: UserPreferencesRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onTileAdded() {
        super.onTileAdded()
        refreshTile()
    }

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        if (!controller.hasWriteSecureSettingsPermission()) {
            openSetupScreen()
            return
        }
        serviceScope.launch {
            val requireUnlock = preferencesRepository.getSettingsSnapshot()
                .privateDnsTileConfig.requireUnlockToToggle
            if (requireUnlock && isLocked) {
                withContext(Dispatchers.Main) {
                    unlockAndRun {
                        serviceScope.launch { performCycle() }
                    }
                }
            } else {
                performCycle()
            }
        }
    }

    private suspend fun performCycle() {
        when (val result = controller.cyclePrivateDnsFromTile()) {
            is SystemPreferenceWriteResult.PermissionDenied -> {
                withContext(Dispatchers.Main) { openSetupScreen() }
            }
            is SystemPreferenceWriteResult.RejectedBySystem -> {
                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, result.reason, Toast.LENGTH_LONG).show()
                }
            }
            is SystemPreferenceWriteResult.Success -> Unit
        }
        refreshTileNow()
    }

    private fun refreshTile() {
        serviceScope.launch {
            controller.executeDueAutoReverts()
            refreshTileNow()
        }
    }

    private suspend fun refreshTileNow() {
        val settings = preferencesRepository.getSettingsSnapshot()
        val status = controller.readLiveStatus()
        val presentation = SystemTileEngine.presentPrivateDnsTile(
            state = status.privateDnsState,
            config = settings.privateDnsTileConfig,
            hasPermission = status.hasWriteSecureSettingsPermission,
            nowEpochMs = System.currentTimeMillis()
        )
        withContext(Dispatchers.Main) {
            val tile = qsTile ?: return@withContext
            tile.label = presentation.label
            tile.subtitle = presentation.subtitle
            tile.contentDescription = presentation.contentDescription
            tile.icon = Icon.createWithResource(
                this@PrivateDnsTileService,
                R.drawable.ic_tile_private_dns
            )
            tile.state = when (presentation.state) {
                TileVisualState.ACTIVE -> Tile.STATE_ACTIVE
                TileVisualState.INACTIVE -> Tile.STATE_INACTIVE
                TileVisualState.UNAVAILABLE -> Tile.STATE_UNAVAILABLE
            }
            tile.updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun openSetupScreen() {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = SystemPreferencesController.ACTION_OPEN_QUICK_TILES
            putExtra(
                SystemPreferencesController.EXTRA_TILE_COMPONENT,
                ComponentName(this@PrivateDnsTileService, PrivateDnsTileService::class.java)
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                SETUP_PENDING_INTENT_REQ,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            startActivityAndCollapse(intent)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val SETUP_PENDING_INTENT_REQ = 7201
    }
}
