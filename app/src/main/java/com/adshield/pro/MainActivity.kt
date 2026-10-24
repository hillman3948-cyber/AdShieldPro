package com.adshield.pro

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adshield.pro.domain.model.RuleMatchType
import com.adshield.pro.domain.model.VpnStatus
import com.adshield.pro.ui.screens.ActivityScreen
import com.adshield.pro.ui.screens.AdBlockerHubScreen
import com.adshield.pro.ui.screens.HomeScreen
import com.adshield.pro.ui.screens.OptimizerScreen
import com.adshield.pro.ui.screens.SettingsScreen
import com.adshield.pro.ui.theme.AdShieldProTheme
import com.adshield.pro.ui.viewmodel.AdShieldViewModel
import dagger.hilt.android.AndroidEntryPoint

enum class MainNavDestination(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    AD_BLOCKER("Ad Blocker", Icons.Default.Shield),
    OPTIMIZER("Optimizer", Icons.Default.Bolt),
    STATISTICS("Statistics", Icons.Default.Analytics),
    SETTINGS("Settings", Icons.Default.Settings)
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: AdShieldViewModel = hiltViewModel()
            val settings by viewModel.settingsState.collectAsStateWithLifecycle()

            AdShieldProTheme(themePreference = settings.darkThemeMode) {
                AdShieldMainApp(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun AdShieldMainApp(viewModel: AdShieldViewModel) {
    val context = LocalContext.current
    var currentTab by rememberSaveable { mutableStateOf(MainNavDestination.HOME) }
    val snackbarHostState = remember { SnackbarHostState() }

    val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()
    val recentLogs by viewModel.recentLogs.collectAsStateWithLifecycle()
    val liveTopApps by viewModel.liveTopApps.collectAsStateWithLifecycle()
    val topBlockedDomains by viewModel.topBlockedDomains.collectAsStateWithLifecycle()
    val sourceEffectiveness by viewModel.sourceEffectiveness.collectAsStateWithLifecycle()
    val allTimeTotal by viewModel.allTimeTotalQueries.collectAsStateWithLifecycle()
    val allTimeBlocked by viewModel.allTimeBlockedQueries.collectAsStateWithLifecycle()
    val allTimeAllowed by viewModel.allTimeAllowedQueries.collectAsStateWithLifecycle()
    val chartBuckets by viewModel.chartBuckets.collectAsStateWithLifecycle()
    val selectedChartRange by viewModel.selectedChartRange.collectAsStateWithLifecycle()
    val customRules by viewModel.customRules.collectAsStateWithLifecycle()
    val ruleConflicts by viewModel.ruleConflicts.collectAsStateWithLifecycle()
    val filterSources by viewModel.filterSources.collectAsStateWithLifecycle()
    val isUpdatingFilters by viewModel.isUpdatingFilters.collectAsStateWithLifecycle()
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
    val sandboxResult by viewModel.sandboxTestResult.collectAsStateWithLifecycle()
    val uiFeedback by viewModel.uiFeedbackMessage.collectAsStateWithLifecycle()

    // Phone Optimizer & AI Smart Boost states
    val ramSnapshot by viewModel.ramSnapshot.collectAsStateWithLifecycle()
    val ramUsageTrend by viewModel.ramUsageTrend.collectAsStateWithLifecycle()
    val storageMetrics by viewModel.storageMetrics.collectAsStateWithLifecycle()
    val batterySnapshot by viewModel.batterySnapshot.collectAsStateWithLifecycle()
    val temperatureTrend by viewModel.temperatureTrend.collectAsStateWithLifecycle()
    val healthAssessment by viewModel.healthAssessment.collectAsStateWithLifecycle()
    val smartBoostResult by viewModel.smartBoostResult.collectAsStateWithLifecycle()
    val isRunningSmartBoost by viewModel.isRunningSmartBoost.collectAsStateWithLifecycle()
    val junkScanReport by viewModel.junkScanReport.collectAsStateWithLifecycle()
    val isScanningJunk by viewModel.isScanningJunk.collectAsStateWithLifecycle()
    val ramOptimizationResult by viewModel.ramOptimizationResult.collectAsStateWithLifecycle()
    val installedGames by viewModel.installedGames.collectAsStateWithLifecycle()
    val isDndPolicyGranted by viewModel.isDndPolicyGranted.collectAsStateWithLifecycle()

    var showVpnConsentHelp by remember { mutableStateOf(false) }

    // Optional Android 13+ notification permission launcher (requested AFTER VPN consent,
    // so the two system dialogs never race each other). Service starts regardless of result.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        viewModel.startVpnService()
    }

    val startAfterConsent: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.startVpnService()
        }
    }

    // Explicit Android VpnService consent dialog launcher
    val vpnConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // Some OEM ROMs return RESULT_CANCELED even after granting; re-verify with prepare().
        if (result.resultCode == Activity.RESULT_OK || VpnService.prepare(context) == null) {
            startAfterConsent()
        } else {
            showVpnConsentHelp = true
        }
    }

    val launchVpnConsent: () -> Unit = {
        val prepareIntent = try {
            VpnService.prepare(context)
        } catch (e: Exception) {
            null.also { showVpnConsentHelp = true }
        }
        if (prepareIntent != null) {
            try {
                vpnConsentLauncher.launch(prepareIntent)
            } catch (e: Exception) {
                showVpnConsentHelp = true
            }
        } else if (!showVpnConsentHelp) {
            startAfterConsent()
        }
    }

    val handleToggleVpn: (Boolean) -> Unit = { enable ->
        if (enable) {
            launchVpnConsent()
        } else {
            viewModel.stopVpnService()
        }
    }

    if (showVpnConsentHelp) {
        AlertDialog(
            onDismissRequest = { showVpnConsentHelp = false },
            title = { Text("VPN permission needed") },
            text = {
                Text(
                    "AdShield Pro filters ads through a local, on-device VPN. Android didn't grant " +
                        "the VPN permission. This usually happens when:\n\n" +
                        "• \"Cancel\" was tapped on the connection request, or\n" +
                        "• Another VPN app is set as \"Always-on VPN\" (often with \"Block connections " +
                        "without VPN\" enabled).\n\n" +
                        "Tap Try Again and choose OK. If no dialog appears, open VPN settings, turn off " +
                        "Always-on for the other VPN app, then try again."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showVpnConsentHelp = false
                    launchVpnConsent()
                }) { Text("Try Again") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showVpnConsentHelp = false
                    try {
                        context.startActivity(
                            Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (e: Exception) {
                        context.startActivity(
                            Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }) { Text("Open VPN Settings") }
            }
        )
    }

    LaunchedEffect(uiFeedback) {
        val msg = uiFeedback
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearFeedback()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            NavigationBar {
                MainNavDestination.entries.forEach { dest ->
                    NavigationBarItem(
                        selected = currentTab == dest,
                        onClick = { currentTab = dest },
                        icon = {
                            Icon(
                                imageVector = dest.icon,
                                contentDescription = dest.label
                            )
                        },
                        label = {
                            Text(
                                text = dest.label,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        alwaysShowLabel = true
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentTab) {
                MainNavDestination.HOME -> HomeScreen(
                    vpnState = vpnState,
                    settings = settings,
                    allTimeTotal = allTimeTotal,
                    allTimeBlocked = allTimeBlocked,
                    allTimeAllowed = allTimeAllowed,
                    chartBuckets = chartBuckets,
                    isUpdatingFilters = isUpdatingFilters,
                    ramSnapshot = ramSnapshot,
                    storageMetrics = storageMetrics,
                    batterySnapshot = batterySnapshot,
                    healthAssessment = healthAssessment,
                    smartBoostResult = smartBoostResult,
                    isRunningSmartBoost = isRunningSmartBoost,
                    onToggleVpn = handleToggleVpn,
                    onPauseResumeVpn = {
                        if (vpnState.status == VpnStatus.PAUSED) {
                            viewModel.resumeVpnService()
                        } else {
                            viewModel.pauseVpnService()
                        }
                    },
                    onSelectProtectionMode = viewModel::setProtectionMode,
                    onTriggerFilterSync = viewModel::triggerManualFilterUpdate,
                    onRunOptimizeNow = viewModel::runOneTapSmartBoost,
                    onOpenOptimizerTab = { currentTab = MainNavDestination.OPTIMIZER }
                )

                MainNavDestination.AD_BLOCKER -> AdBlockerHubScreen(
                    sources = filterSources,
                    rules = customRules,
                    conflicts = ruleConflicts,
                    sandboxResult = sandboxResult,
                    settings = settings,
                    isUpdatingFilters = isUpdatingFilters,
                    browserContentBlocker = viewModel.browserContentBlocker,
                    onToggleSource = viewModel::toggleFilterSource,
                    onSyncAllNow = viewModel::triggerManualFilterUpdate,
                    onAddCustomSource = viewModel::addCustomFilterSource,
                    onDeleteCustomSource = viewModel::deleteCustomFilterSource,
                    onUpdateAutoSyncSchedule = viewModel::updateAutoSyncSchedule,
                    onAddRule = viewModel::addCustomRule,
                    onUpdateRule = viewModel::updateCustomRule,
                    onToggleRule = viewModel::toggleCustomRule,
                    onDeleteRule = viewModel::deleteCustomRule,
                    onDeduplicateRules = viewModel::deduplicateCustomRules,
                    onImportRulesText = viewModel::importRulesText,
                    onExportRulesText = viewModel::exportRulesText,
                    onTestDomainSandbox = viewModel::testDomainInSandbox,
                    onToggleSiteException = viewModel::toggleBrowserSiteException,
                    onUpdateBrowserPrivacy = viewModel::updateBrowserPrivacySettings,
                    onAddCustomCosmeticSelector = viewModel::addCustomCosmeticSelector,
                    onShowFeedback = viewModel::showFeedback
                )

                MainNavDestination.OPTIMIZER -> OptimizerScreen(
                    settings = settings,
                    ramSnapshot = ramSnapshot,
                    ramUsageTrend = ramUsageTrend,
                    storageMetrics = storageMetrics,
                    batterySnapshot = batterySnapshot,
                    temperatureTrend = temperatureTrend,
                    healthAssessment = healthAssessment,
                    smartBoostResult = smartBoostResult,
                    isRunningSmartBoost = isRunningSmartBoost,
                    junkScanReport = junkScanReport,
                    isScanningJunk = isScanningJunk,
                    ramOptimizationResult = ramOptimizationResult,
                    installedGames = installedGames,
                    isDndPolicyGranted = isDndPolicyGranted,
                    onRunSmartBoost = viewModel::runOneTapSmartBoost,
                    onScanJunk = viewModel::scanJunk,
                    onToggleJunkCandidate = viewModel::toggleJunkCandidateSelection,
                    onCleanSelectedJunk = viewModel::cleanSelectedJunk,
                    onAddExcludedJunkPath = viewModel::addExcludedJunkPath,
                    onRemoveExcludedJunkPath = viewModel::removeExcludedJunkPath,
                    onUpdateSmartAutoSchedule = viewModel::updateSmartAutoOptimizeSchedule,
                    onRunSmartRamOptimize = viewModel::runSmartRamOptimize,
                    onToggleFavoriteGame = viewModel::toggleFavoriteGame,
                    onUpdateGamingSettings = viewModel::updateGamingBoosterSettings,
                    onTriggerGameBoost = viewModel::triggerGameBoost,
                    onSelectBatteryMode = viewModel::selectAndApplyBatteryMode
                )

                MainNavDestination.STATISTICS -> ActivityScreen(
                    logs = recentLogs,
                    liveTopApps = liveTopApps,
                    topBlockedDomains = topBlockedDomains,
                    sourceEffectiveness = sourceEffectiveness,
                    chartBuckets = chartBuckets,
                    selectedRange = selectedChartRange,
                    allTimeTotal = allTimeTotal,
                    allTimeBlocked = allTimeBlocked,
                    allTimeAllowed = allTimeAllowed,
                    settings = settings,
                    onSelectRange = viewModel::selectChartRange,
                    onToggleDetailedLogging = viewModel::setDetailedLogging,
                    onChangeRetentionDays = viewModel::setLogRetentionDays,
                    onClearHistory = viewModel::clearAllHistory,
                    onQuickRuleFromLog = { domain, ruleType ->
                        viewModel.addCustomRule(
                            rawPattern = domain,
                            ruleType = ruleType,
                            matchType = RuleMatchType.SUBDOMAIN,
                            comment = "Added from Activity Log"
                        )
                    }
                )

                MainNavDestination.SETTINGS -> SettingsScreen(
                    settings = settings,
                    installedApps = installedApps,
                    onUpdateResolverConfig = viewModel::updateDnsResolverConfig,
                    onLoadInstalledApps = viewModel::loadInstalledApps,
                    onToggleAppExclusion = viewModel::toggleAppExclusion,
                    onToggleAutoStartBoot = viewModel::setAutoStartOnBoot,
                    onChangeThemePreference = viewModel::setDarkThemePreference,
                    onExportBackupJson = viewModel::exportFullBackupJson,
                    onRestoreBackupJson = viewModel::restoreFullBackupJson
                )
            }
        }
    }
}
