package com.adshield.pro.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adshield.pro.data.local.preferences.AppSettingsState
import com.adshield.pro.data.local.preferences.DarkThemePreference
import com.adshield.pro.data.local.preferences.ScanJournalStore
import com.adshield.pro.data.local.preferences.UserPreferencesRepository
import com.adshield.pro.data.repository.AdShieldRepository
import com.adshield.pro.BuildConfig
import com.adshield.pro.domain.model.AppExclusionInfo
import com.adshield.pro.domain.model.AppDnsPolicy
import com.adshield.pro.domain.model.DnsFirewallPolicy
import com.adshield.pro.domain.model.NetworkTransportPolicy
import com.adshield.pro.domain.model.AppDnsActivity
import com.adshield.pro.domain.model.BatteryMode
import com.adshield.pro.domain.model.BatteryThermalSnapshot
import com.adshield.pro.domain.model.CustomRule
import com.adshield.pro.domain.model.DeviceHealthAssessment
import com.adshield.pro.domain.model.DnsActivityRecord
import com.adshield.pro.domain.model.DnsResolverConfig
import com.adshield.pro.domain.model.DomainBlockCount
import com.adshield.pro.domain.model.FilterEvaluationResult
import com.adshield.pro.domain.model.FilterListSource
import com.adshield.pro.domain.model.GameAppInfo
import com.adshield.pro.domain.model.GamingProfile
import com.adshield.pro.domain.model.JunkCleanMode
import com.adshield.pro.domain.model.JunkScanReport
import com.adshield.pro.domain.model.PrivateDnsCycleStep
import com.adshield.pro.domain.model.ProtectionMode
import com.adshield.pro.domain.model.RamOptimizationResult
import com.adshield.pro.domain.model.RamSnapshot
import com.adshield.pro.domain.model.RuleCategory
import com.adshield.pro.domain.model.RuleConflictReport
import com.adshield.pro.domain.model.RuleMatchType
import com.adshield.pro.domain.model.RuleType
import com.adshield.pro.domain.model.SearchHistoryEntry
import com.adshield.pro.domain.model.SearchProvider
import com.adshield.pro.domain.model.SmartBoostResult
import com.adshield.pro.domain.model.SourceEffectiveness
import com.adshield.pro.domain.model.StorageMetrics
import com.adshield.pro.domain.model.SystemPreferenceWriteResult
import com.adshield.pro.domain.model.SystemTilesLiveStatus
import com.adshield.pro.domain.model.TimeBucketStat
import com.adshield.pro.domain.model.VpnRuntimeState
import com.adshield.pro.ui.model.ProtectionPipelineStatus
import com.adshield.pro.ui.model.ProtectionStatusEvaluator
import com.adshield.pro.domain.model.VpnStatus
import com.adshield.pro.data.repository.BatteryIntelligenceRepository
import com.adshield.pro.engine.battery.BatteryDiagnostic
import com.adshield.pro.engine.battery.BatteryDiagnostics
import com.adshield.pro.engine.battery.BatterySession
import com.adshield.pro.engine.aether.LiveNetworkModel
import com.adshield.pro.engine.battery.BatteryTelemetry
import com.adshield.pro.engine.browser.BrowserContentBlocker
import com.adshield.pro.engine.browser.SearchSuggestionClient
import com.adshield.pro.engine.platform.DeviceProfiler
import com.adshield.pro.engine.platform.SystemPreferencesController
import com.adshield.pro.engine.platform.SystemTileEngine
import com.adshield.pro.engine.dns.UpstreamDnsResolver
import com.adshield.pro.engine.dns.DnsDiagnosticReport
import com.adshield.pro.engine.dns.runDiagnostics
import com.adshield.pro.engine.filter.FilterRuleEngine
import com.adshield.pro.engine.optimizer.RuleBasedOptimizationPredictor
import com.adshield.pro.engine.optimizer.SystemOptimizerEngine
import com.adshield.pro.engine.security.AppRiskAuditor
import com.adshield.pro.engine.security.ScanKind
import com.adshield.pro.engine.security.QuarantineManager
import com.adshield.pro.engine.security.QuarantineNaming
import com.adshield.pro.engine.security.QuarantinedItem
import com.adshield.pro.engine.security.ScanJournal
import com.adshield.pro.engine.security.ScanJournalSummary
import com.adshield.pro.engine.security.ScanProgress
import com.adshield.pro.engine.security.ScanSource
import com.adshield.pro.engine.security.SecurityEngine
import com.adshield.pro.engine.security.SecurityLevel
import com.adshield.pro.engine.security.SecurityPosture
import com.adshield.pro.engine.security.SecurityScanRecord
import com.adshield.pro.engine.security.UrlThreatScanner
import com.adshield.pro.service.AdShieldVpnService
import com.adshield.pro.ui.UpdatePromptActivity
import com.adshield.pro.update.GitHubReleaseClient
import com.adshield.pro.worker.FilterUpdateWorker
import com.adshield.pro.worker.ScheduledScanWorker
import com.adshield.pro.worker.SmartOptimizationWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

enum class StatsChartRange(val label: String, val days: Int) {
    DAILY_7D("7 Days", 7),
    WEEKLY_14D("14 Days", 14),
    MONTHLY_30D("30 Days", 30)
}

@HiltViewModel
class AdShieldViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: AdShieldRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val filterRuleEngine: FilterRuleEngine,
    private val upstreamDnsResolver: UpstreamDnsResolver,
    val browserContentBlocker: BrowserContentBlocker,
    private val optimizerEngine: SystemOptimizerEngine,
    private val securityEngine: SecurityEngine,
    private val scanJournalStore: ScanJournalStore,
    private val quarantineManager: QuarantineManager,
    private val batteryRepository: BatteryIntelligenceRepository,
    private val deviceProfiler: DeviceProfiler,
    private val searchSuggestionClient: SearchSuggestionClient,
    private val systemPreferencesController: SystemPreferencesController
) : ViewModel() {

    val vpnState: StateFlow<VpnRuntimeState> = repository.vpnRuntimeState

    val settingsState: StateFlow<AppSettingsState> = repository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettingsState())

    val recentLogs: StateFlow<List<DnsActivityRecord>> = repository.recentLogsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val liveTopApps: StateFlow<List<AppDnsActivity>> = repository.liveTopAppsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val topBlockedDomains: StateFlow<List<DomainBlockCount>> = repository.topBlockedDomainsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val sourceEffectiveness: StateFlow<List<SourceEffectiveness>> = repository.sourceEffectivenessFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allTimeTotalQueries: StateFlow<Long> = repository.allTimeTotalQueriesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val allTimeBlockedQueries: StateFlow<Long> = repository.allTimeBlockedQueriesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val allTimeAllowedQueries: StateFlow<Long> = repository.allTimeAllowedQueriesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val customRules: StateFlow<List<CustomRule>> = repository.customRulesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filterSources: StateFlow<List<FilterListSource>> = repository.filterSourcesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedChartRange = MutableStateFlow(StatsChartRange.DAILY_7D)
    val selectedChartRange: StateFlow<StatsChartRange> = _selectedChartRange.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val chartBuckets: StateFlow<List<TimeBucketStat>> = _selectedChartRange
        .flatMapLatest { range -> repository.observeDailyChartBuckets(range.days) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _ruleConflicts = MutableStateFlow<List<RuleConflictReport>>(emptyList())
    val ruleConflicts: StateFlow<List<RuleConflictReport>> = _ruleConflicts.asStateFlow()

    private val _installedApps = MutableStateFlow<List<AppExclusionInfo>>(emptyList())
    val installedApps: StateFlow<List<AppExclusionInfo>> = _installedApps.asStateFlow()

    private val _dnsDiagnosticReport = MutableStateFlow<DnsDiagnosticReport?>(null)
    val dnsDiagnosticReport: StateFlow<DnsDiagnosticReport?> = _dnsDiagnosticReport.asStateFlow()
    private val _isRunningDnsDiagnostics = MutableStateFlow(false)
    val isRunningDnsDiagnostics: StateFlow<Boolean> = _isRunningDnsDiagnostics.asStateFlow()

    /**
     * Live working status. The ticker only re-evaluates the pipeline; every value inside the
     * snapshot is still read from real runtime state (VPN status, loaded rules, measured
     * resolver latency and circuit state), never from a guess.
     */
    private val _statusTick = MutableStateFlow(0L)

    val protectionStatus: StateFlow<ProtectionPipelineStatus> = combine(vpnState, settingsState, _statusTick) { vpn, settings, _ ->
        ProtectionStatusEvaluator.evaluate(
            vpn = vpn,
            settings = settings,
            health = upstreamDnsResolver.providerHealthSnapshots(),
            nowMs = System.currentTimeMillis()
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        ProtectionStatusEvaluator.evaluate(VpnRuntimeState(), AppSettingsState(), emptyList(), System.currentTimeMillis())
    )

    private val _isUpdatingFilters = MutableStateFlow(false)
    val isUpdatingFilters: StateFlow<Boolean> = _isUpdatingFilters.asStateFlow()

    private val _uiFeedbackMessage = MutableStateFlow<String?>(null)
    val uiFeedbackMessage: StateFlow<String?> = _uiFeedbackMessage.asStateFlow()

    private val _isCheckingForUpdates = MutableStateFlow(false)
    val isCheckingForUpdates: StateFlow<Boolean> = _isCheckingForUpdates.asStateFlow()

    private val _sandboxTestResult = MutableStateFlow<FilterEvaluationResult?>(null)
    val sandboxTestResult: StateFlow<FilterEvaluationResult?> = _sandboxTestResult.asStateFlow()


    // --- Phone Optimizer & AI Smart Boost Live States ---
    private val _ramSnapshot = MutableStateFlow(optimizerEngine.readRamSnapshot())
    val ramSnapshot: StateFlow<RamSnapshot> = _ramSnapshot.asStateFlow()

    private val _ramUsageTrend = MutableStateFlow(optimizerEngine.getRamUsageTrend())
    val ramUsageTrend: StateFlow<List<Float>> = _ramUsageTrend.asStateFlow()

    private val _storageMetrics = MutableStateFlow(optimizerEngine.readStorageMetrics())
    val storageMetrics: StateFlow<StorageMetrics> = _storageMetrics.asStateFlow()

    private val _batterySnapshot = MutableStateFlow(optimizerEngine.readBatteryThermalSnapshot())
    val batterySnapshot: StateFlow<BatteryThermalSnapshot> = _batterySnapshot.asStateFlow()

    private val _temperatureTrend = MutableStateFlow(optimizerEngine.getTemperatureTrend())
    val temperatureTrend: StateFlow<List<Float>> = _temperatureTrend.asStateFlow()

    private val _healthAssessment = MutableStateFlow(optimizerEngine.evaluateCurrentHealth(0L))
    val healthAssessment: StateFlow<DeviceHealthAssessment> = _healthAssessment.asStateFlow()

    private val _smartBoostResult = MutableStateFlow<SmartBoostResult?>(null)
    val smartBoostResult: StateFlow<SmartBoostResult?> = _smartBoostResult.asStateFlow()

    private val _isRunningSmartBoost = MutableStateFlow(false)
    val isRunningSmartBoost: StateFlow<Boolean> = _isRunningSmartBoost.asStateFlow()

    private val _junkScanReport = MutableStateFlow<JunkScanReport?>(null)
    val junkScanReport: StateFlow<JunkScanReport?> = _junkScanReport.asStateFlow()

    private val _isScanningJunk = MutableStateFlow(false)
    val isScanningJunk: StateFlow<Boolean> = _isScanningJunk.asStateFlow()

    private val _ramOptimizationResult = MutableStateFlow<RamOptimizationResult?>(null)
    val ramOptimizationResult: StateFlow<RamOptimizationResult?> = _ramOptimizationResult.asStateFlow()

    private val _installedGames = MutableStateFlow<List<GameAppInfo>>(emptyList())
    val installedGames: StateFlow<List<GameAppInfo>> = _installedGames.asStateFlow()

    private val _isDndPolicyGranted = MutableStateFlow(optimizerEngine.isDoNotDisturbAccessGranted())
    val isDndPolicyGranted: StateFlow<Boolean> = _isDndPolicyGranted.asStateFlow()

    init {
        viewModelScope.launch {
            while (isActive) {
                delay(3_000L)
                _statusTick.value = System.currentTimeMillis()
            }
        }
        viewModelScope.launch {
            customRules.collect {
                refreshRuleConflicts()
            }
        }
        // Security Center: keep the malware-block counter and protection score live.
        viewModelScope.launch {
            repository.malwareBlocksFlow.collect { count ->
                _malwareBlocks.value = count
                refreshSecurityPosture()
            }
        }
        // Restore the scheduled-scan job after process death / app upgrade. WorkManager keeps the
        // enqueue itself persistent; this reconciles it with the current user preference.
        viewModelScope.launch {
            val settings = preferencesRepository.getSettingsSnapshot()
            ScheduledScanWorker.scheduleOrCancel(
                appContext,
                settings.scheduledScanEnabled,
                settings.scheduledScanIntervalHours
            )
        }
        // Initial Junk quick scan & periodic lightweight telemetry refresh while UI is open
        viewModelScope.launch {
            scanJunk(JunkCleanMode.QUICK_CLEAN)
            loadInstalledGames()
            while (isActive) {
                refreshSystemTelemetry()
                delay(5000L)
            }
        }
    }

    fun showFeedback(message: String) {
        _uiFeedbackMessage.value = message
    }

    fun clearFeedback() {
        _uiFeedbackMessage.value = null
    }

    fun checkForUpdates() {
        if (_isCheckingForUpdates.value) return
        viewModelScope.launch {
            _isCheckingForUpdates.value = true
            try {
                val release = withContext(Dispatchers.IO) { GitHubReleaseClient.latest() }
                when {
                    release == null -> showFeedback("Couldn't check for updates. Check your internet connection and try again.")
                    release.code <= BuildConfig.VERSION_CODE -> showFeedback("AdShield Pro is up to date.")
                    else -> {
                        val intent = Intent(appContext, UpdatePromptActivity::class.java).apply {
                            putExtra(UpdatePromptActivity.TAG, release.tag)
                            putExtra(UpdatePromptActivity.NAME, release.name)
                            putExtra(UpdatePromptActivity.CODE, release.code)
                            putExtra(UpdatePromptActivity.APK, release.apk)
                            putExtra(UpdatePromptActivity.SHA, release.sha256)
                            putExtra(UpdatePromptActivity.NOTES, release.notes)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        }
                        appContext.startActivity(intent)
                    }
                }
            } catch (_: Exception) {
                showFeedback("Couldn't check for updates. Check your internet connection and try again.")
            } finally {
                _isCheckingForUpdates.value = false
            }
        }
    }

    // --- System Optimizer & AI Smart Boost Actions ---

    fun refreshSystemTelemetry() {
        _ramSnapshot.value = optimizerEngine.readRamSnapshot()
        _ramUsageTrend.value = optimizerEngine.getRamUsageTrend()
        _storageMetrics.value = optimizerEngine.readStorageMetrics()
        _batterySnapshot.value = optimizerEngine.readBatteryThermalSnapshot()
        _temperatureTrend.value = optimizerEngine.getTemperatureTrend()
        _isDndPolicyGranted.value = optimizerEngine.isDoNotDisturbAccessGranted()
        val junkBytes = _junkScanReport.value?.totalRecoverableBytes ?: 0L
        _healthAssessment.value = optimizerEngine.evaluateCurrentHealth(junkBytes)
    }

    fun runOneTapSmartBoost() {
        if (_isRunningSmartBoost.value) return
        viewModelScope.launch {
            _isRunningSmartBoost.value = true
            val result = optimizerEngine.runOneTapSmartBoost(forceRun = false)
            _smartBoostResult.value = result
            // Refresh junk scan and telemetry after boost
            val updatedScan = optimizerEngine.scanJunkFiles(
                mode = _junkScanReport.value?.mode ?: JunkCleanMode.QUICK_CLEAN,
                excludedPaths = settingsState.value.excludedJunkPaths
            )
            _junkScanReport.value = updatedScan
            refreshSystemTelemetry()
            _isRunningSmartBoost.value = false

            if (result.wasSkippedDueToRecentBoost) {
                showFeedback("Device is already optimized (boosted recently).")
            } else {
                showFeedback(
                    "Smart Boost complete: ${RuleBasedOptimizationPredictor.formatBytes(result.bytesRecoveredAutomatically)} cleaned, DNS & RAM caches optimized."
                )
            }
        }
    }

    fun scanJunk(mode: JunkCleanMode) {
        if (_isScanningJunk.value) return
        viewModelScope.launch {
            _isScanningJunk.value = true
            val report = optimizerEngine.scanJunkFiles(
                mode = mode,
                excludedPaths = settingsState.value.excludedJunkPaths
            )
            _junkScanReport.value = report
            _healthAssessment.value = optimizerEngine.evaluateCurrentHealth(report.totalRecoverableBytes)
            _isScanningJunk.value = false
        }
    }

    fun toggleJunkCandidateSelection(candidateId: String, selected: Boolean) {
        val current = _junkScanReport.value ?: return
        val updatedList = current.candidates.map { item ->
            if (item.id == candidateId) item.copy(isSelected = selected) else item
        }
        _junkScanReport.value = current.copy(candidates = updatedList)
    }

    fun cleanSelectedJunk() {
        val current = _junkScanReport.value ?: return
        viewModelScope.launch {
            _isScanningJunk.value = true
            val record = optimizerEngine.executeJunkClean(
                candidates = current.candidates,
                mode = current.mode,
                autoOnly = false
            )
            val refreshed = optimizerEngine.scanJunkFiles(
                mode = current.mode,
                excludedPaths = settingsState.value.excludedJunkPaths
            )
            _junkScanReport.value = refreshed
            refreshSystemTelemetry()
            _isScanningJunk.value = false
            showFeedback(record.summary)
        }
    }

    fun addExcludedJunkPath(pathPattern: String) {
        viewModelScope.launch {
            preferencesRepository.addExcludedJunkPath(pathPattern)
            scanJunk(_junkScanReport.value?.mode ?: JunkCleanMode.QUICK_CLEAN)
            showFeedback("Excluded '$pathPattern' from junk cleanup.")
        }
    }

    fun removeExcludedJunkPath(pathPattern: String) {
        viewModelScope.launch {
            preferencesRepository.removeExcludedJunkPath(pathPattern)
            scanJunk(_junkScanReport.value?.mode ?: JunkCleanMode.QUICK_CLEAN)
        }
    }

    fun updateSmartAutoOptimizeSchedule(enabled: Boolean, intervalHours: Int) {
        viewModelScope.launch {
            preferencesRepository.setSmartAutoOptimizeSchedule(enabled, intervalHours)
            SmartOptimizationWorker.scheduleOrCancelSmartMaintenance(appContext, enabled, intervalHours)
            showFeedback(
                if (enabled) "Automatic Smart Maintenance scheduled every $intervalHours hours."
                else "Automatic Smart Maintenance paused."
            )
        }
    }

    fun runSmartRamOptimize() {
        viewModelScope.launch {
            val res = optimizerEngine.optimizeRam()
            _ramOptimizationResult.value = res
            refreshSystemTelemetry()
            showFeedback("RAM Manager trimmed ${res.dnsCacheEntriesReleased} cache entries & compacted AdShield heap.")
        }
    }

    fun loadInstalledGames() {
        viewModelScope.launch {
            val favs = settingsState.value.favoriteGamePackages
            _installedGames.value = optimizerEngine.discoverInstalledGames(favs)
        }
    }

    fun toggleFavoriteGame(packageName: String, isFavorite: Boolean) {
        viewModelScope.launch {
            preferencesRepository.toggleFavoriteGame(packageName, isFavorite)
            val updatedFavs = if (isFavorite) {
                settingsState.value.favoriteGamePackages + packageName
            } else {
                settingsState.value.favoriteGamePackages - packageName
            }
            _installedGames.value = optimizerEngine.discoverInstalledGames(updatedFavs)
            showFeedback(
                if (isFavorite) "Added to Favorite Games."
                else "Removed from Favorite Games."
            )
        }
    }

    fun updateGamingBoosterSettings(
        profile: GamingProfile,
        suppressNotifications: Boolean,
        tempWarningCelsius: Int
    ) {
        viewModelScope.launch {
            preferencesRepository.updateGamingBoosterSettings(profile, suppressNotifications, tempWarningCelsius)
            showFeedback("Game Booster set to ${profile.title} profile.")
        }
    }

    fun triggerGameBoost(packageName: String? = null, appName: String? = null) {
        viewModelScope.launch {
            val actions = optimizerEngine.applyGameBoostAndLaunch(packageName, appName)
            refreshSystemTelemetry()
            loadInstalledGames()
            showFeedback(actions.firstOrNull() ?: "Game Booster applied.")
        }
    }

    fun selectAndApplyBatteryMode(mode: BatteryMode) {
        viewModelScope.launch {
            val actions = optimizerEngine.runAutoBatteryOptimize(mode)
            refreshSystemTelemetry()
            showFeedback(actions.firstOrNull() ?: "Applied ${mode.title} battery profile.")
        }
    }

    // --- VPN Lifecycle Controls ---

    fun startVpnService() {
        val intent = Intent(appContext, AdShieldVpnService::class.java).apply {
            action = AdShieldVpnService.ACTION_START_VPN
        }
        ContextCompat.startForegroundService(appContext, intent)
    }

    fun stopVpnService() {
        val intent = Intent(appContext, AdShieldVpnService::class.java).apply {
            action = AdShieldVpnService.ACTION_STOP_VPN
        }
        appContext.startService(intent)
    }

    fun pauseVpnService() {
        val intent = Intent(appContext, AdShieldVpnService::class.java).apply {
            action = AdShieldVpnService.ACTION_PAUSE_VPN
        }
        appContext.startService(intent)
    }

    fun resumeVpnService() {
        val intent = Intent(appContext, AdShieldVpnService::class.java).apply {
            action = AdShieldVpnService.ACTION_RESUME_VPN
        }
        appContext.startService(intent)
    }

    // --- Protection Modes ---

    fun setProtectionMode(mode: ProtectionMode) {
        viewModelScope.launch {
            repository.selectProtectionMode(mode)
            upstreamDnsResolver.clearCache()
            showFeedback("Switched to ${mode.title} (applied immediately without VPN restart)")
        }
    }

    // --- DNS Resolver Settings ---

    fun updateDnsResolverConfig(config: DnsResolverConfig) {
        viewModelScope.launch {
            val prevIpv6 = settingsState.value.resolverConfig.enableIpv6
            preferencesRepository.updateResolverConfig(config)
            upstreamDnsResolver.clearCache()
            repository.syncRuntimeModeAndResolver(settingsState.value.protectionMode, settingsState.value.copy(resolverConfig = config))
            if (prevIpv6 != config.enableIpv6) {
                reloadTunnelIfConnected()
            }
            showFeedback("DNS Resolver updated to ${config.preset.displayName} (${config.protocol.label})")
        }
    }

    fun runDnsDiagnostics(domain: String) {
        if (_isRunningDnsDiagnostics.value) return
        viewModelScope.launch {
            _isRunningDnsDiagnostics.value = true
            try {
                _dnsDiagnosticReport.value = upstreamDnsResolver.runDiagnostics(domain, settingsState.value.resolverConfig)
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                showFeedback("DNS diagnostic failed: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                _isRunningDnsDiagnostics.value = false
            }
        }
    }

    // --- Filter List Updates & Sources ---

    fun triggerManualFilterUpdate() {
        if (_isUpdatingFilters.value) return
        viewModelScope.launch {
            _isUpdatingFilters.value = true
            val res = repository.updateAllEnabledFilterLists()
            _isUpdatingFilters.value = false
            res.fold(
                onSuccess = { totalRules ->
                    upstreamDnsResolver.clearCache()
                    showFeedback("Filter lists synchronized: $totalRules active rules loaded.")
                },
                onFailure = { err ->
                    showFeedback("Filter update rolled back safely: ${err.message}")
                }
            )
        }
    }

    fun toggleFilterSource(source: FilterListSource, enabled: Boolean) {
        viewModelScope.launch {
            repository.toggleFilterSourceEnabled(source, enabled)
            upstreamDnsResolver.clearCache()
        }
    }

    fun addCustomFilterSource(name: String, url: String, category: RuleCategory) {
        viewModelScope.launch {
            val res = repository.addCustomFilterSource(name, url, category)
            res.fold(
                onSuccess = {
                    showFeedback("Added filter source '${it.name}'. Syncing rules...")
                    triggerManualFilterUpdate()
                },
                onFailure = { err ->
                    showFeedback(err.message ?: "Invalid filter list URL")
                }
            )
        }
    }

    fun deleteCustomFilterSource(sourceId: String) {
        viewModelScope.launch {
            repository.deleteCustomFilterSource(sourceId)
            showFeedback("Custom filter source removed.")
        }
    }

    fun updateAutoSyncSchedule(enabled: Boolean, intervalHours: Int) {
        viewModelScope.launch {
            preferencesRepository.setAutoUpdateFilters(enabled, intervalHours)
            FilterUpdateWorker.scheduleOrCancelPeriodicUpdates(appContext, enabled, intervalHours)
            showFeedback(
                if (enabled) "Automatic filter updates scheduled every $intervalHours hours."
                else "Automatic filter updates paused."
            )
        }
    }

    // --- Custom Rules Management ---

    fun addCustomRule(
        rawPattern: String,
        ruleType: RuleType,
        matchType: RuleMatchType?,
        comment: String
    ) {
        viewModelScope.launch {
            val res = repository.addCustomRule(rawPattern, ruleType, matchType, comment)
            res.fold(
                onSuccess = { rule ->
                    upstreamDnsResolver.clearCache()
                    refreshRuleConflicts()
                    showFeedback("Rule '${rule.normalizedPattern}' (${rule.ruleType}) applied immediately.")
                },
                onFailure = { err ->
                    showFeedback(err.message ?: "Malformed domain rule")
                }
            )
        }
    }

    fun updateCustomRule(rule: CustomRule) {
        viewModelScope.launch {
            val res = repository.updateCustomRule(rule)
            res.fold(
                onSuccess = {
                    upstreamDnsResolver.clearCache()
                    refreshRuleConflicts()
                    showFeedback("Updated rule '${rule.normalizedPattern}'.")
                },
                onFailure = { err ->
                    showFeedback(err.message ?: "Invalid rule edit")
                }
            )
        }
    }

    fun toggleCustomRule(rule: CustomRule, enabled: Boolean) {
        viewModelScope.launch {
            repository.toggleCustomRuleEnabled(rule, enabled)
            upstreamDnsResolver.clearCache()
            refreshRuleConflicts()
        }
    }

    fun deleteCustomRule(ruleId: Long) {
        viewModelScope.launch {
            repository.deleteCustomRule(ruleId)
            upstreamDnsResolver.clearCache()
            refreshRuleConflicts()
            showFeedback("Rule deleted.")
        }
    }

    fun deduplicateCustomRules() {
        viewModelScope.launch {
            val removed = repository.removeDuplicateCustomRules()
            refreshRuleConflicts()
            showFeedback(
                if (removed > 0) "Removed $removed duplicate custom rule(s)."
                else "No duplicate rules found."
            )
        }
    }

    fun refreshRuleConflicts() {
        viewModelScope.launch {
            _ruleConflicts.value = repository.analyzeCustomRuleConflicts()
        }
    }

    fun importRulesText(text: String) {
        viewModelScope.launch {
            val count = repository.importRulesFromText(text)
            upstreamDnsResolver.clearCache()
            refreshRuleConflicts()
            showFeedback("Imported $count valid domain rule(s).")
        }
    }

    suspend fun exportRulesText(): String = repository.exportRulesAsText()

    fun testDomainInSandbox(domainInput: String) {
        _sandboxTestResult.value = filterRuleEngine.evaluate(domainInput)
    }

    // --- Statistics & Privacy Controls ---

    fun selectChartRange(range: StatsChartRange) {
        _selectedChartRange.value = range
    }

    fun setDetailedLogging(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setDetailedDnsLogging(enabled)
            showFeedback(
                if (enabled) "Local DNS activity logging enabled."
                else "Detailed domain logging disabled. Only anonymous aggregate totals will be counted."
            )
        }
    }

    fun setLogRetentionDays(days: Int) {
        viewModelScope.launch {
            preferencesRepository.setLogRetentionDays(days)
            repository.enforceLogRetentionPolicy()
            showFeedback("Log retention set to $days days.")
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            repository.clearAllStatisticsAndLogs()
            showFeedback("All local DNS logs and statistics history cleared.")
        }
    }

    // --- Per-App Exclusions & System Settings ---

    fun loadInstalledApps() {
        viewModelScope.launch {
            val settings = settingsState.value
            _installedApps.value = repository.getInstalledApps(
                settings.excludedAppPackages, settings.dnsFirewallPolicy.appPolicies
            )
        }
    }

    fun toggleAppExclusion(packageName: String, excluded: Boolean) {
        viewModelScope.launch {
            preferencesRepository.toggleExcludedAppPackage(packageName, excluded)
            loadInstalledApps()
            reloadTunnelIfConnected()
            showFeedback(
                if (excluded) "Excluded $packageName from VPN tunnel."
                else "Included $packageName in VPN protection."
            )
        }
    }

    fun setAppDnsPolicy(packageName: String, policy: AppDnsPolicy) {
        viewModelScope.launch {
            val current = settingsState.value.dnsFirewallPolicy
            val updated = current.appPolicies.toMutableMap().apply {
                if (policy == AppDnsPolicy.DEFAULT) remove(packageName) else put(packageName, policy)
            }
            preferencesRepository.updateDnsFirewallPolicy(current.copy(appPolicies = updated))
            upstreamDnsResolver.clearCache()
            loadInstalledApps()
            showFeedback("${packageName}: ${policy.label}")
        }
    }

    fun setTransportProtected(transport: NetworkTransportPolicy, protected: Boolean) {
        viewModelScope.launch {
            val current = settingsState.value.dnsFirewallPolicy
            val transports = current.protectedTransports.toMutableSet().apply {
                if (protected) add(transport) else remove(transport)
            }
            preferencesRepository.updateDnsFirewallPolicy(current.copy(protectedTransports = transports))
            showFeedback("${transport.label} DNS filtering ${if (protected) "enabled" else "set to passthrough"}")
        }
    }

    fun setEmergencyAllowlistOnly(enabled: Boolean) {
        viewModelScope.launch {
            val current = settingsState.value.dnsFirewallPolicy
            preferencesRepository.updateDnsFirewallPolicy(current.copy(emergencyAllowlistOnly = enabled))
            upstreamDnsResolver.clearCache()
            showFeedback(
                if (enabled) "Emergency DNS mode enabled: only explicit user/system allowlisted domains resolve."
                else "Emergency DNS mode disabled. Global and per-app policies restored."
            )
        }
    }

    fun setAutoStartOnBoot(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setAutoStartOnBoot(enabled)
        }
    }

    fun setDarkThemePreference(pref: DarkThemePreference) {
        viewModelScope.launch {
            preferencesRepository.setDarkThemePreference(pref)
        }
    }

    /** Private search provider used by the in-app browser's address bar. */
    fun setSearchProvider(provider: SearchProvider) {
        viewModelScope.launch {
            preferencesRepository.setSearchProvider(provider)
        }
    }

    // --- Browser Controls ---

    fun toggleBrowserSiteException(host: String, allowlisted: Boolean) {
        viewModelScope.launch {
            preferencesRepository.toggleBrowserSiteException(host, allowlisted)
            showFeedback(
                if (allowlisted) "Added $host to browser allowlist exceptions."
                else "Removed $host from browser exceptions."
            )
        }
    }

    fun updateBrowserPrivacySettings(
        cosmetic: Boolean,
        popups: Boolean,
        blockThirdPartyCookies: Boolean,
        jsEnabled: Boolean
    ) {
        viewModelScope.launch {
            preferencesRepository.updateBrowserPrivacyOptions(
                cosmeticFiltering = cosmetic,
                popupBlocking = popups,
                blockThirdPartyCookies = blockThirdPartyCookies,
                javaScriptEnabled = jsEnabled
            )
        }
    }

    fun updatePrivateSearchOptions(
        provider: SearchProvider,
        privateMode: Boolean,
        historyEnabled: Boolean,
        suggestionsEnabled: Boolean,
        automaticDeletion: Boolean
    ) {
        viewModelScope.launch {
            preferencesRepository.updatePrivateSearchOptions(
                provider, privateMode, historyEnabled, suggestionsEnabled, automaticDeletion
            )
        }
    }

    fun recordSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            preferencesRepository.recordSearch(
                SearchHistoryEntry(
                    query = trimmed.take(500),
                    provider = settingsState.value.searchProvider,
                    timestampEpochMs = System.currentTimeMillis()
                )
            )
        }
    }

    fun clearSearchHistory() {
        viewModelScope.launch { preferencesRepository.clearSearchHistory() }
    }

    fun fetchSearchSuggestions(query: String, onResult: (List<String>) -> Unit) {
        val settings = settingsState.value
        if (!settings.searchSuggestionsEnabled) {
            onResult(emptyList())
            return
        }
        viewModelScope.launch {
            val suggestions = runCatching {
                searchSuggestionClient.fetch(query, settings.searchProvider)
            }.getOrDefault(emptyList())
            onResult(suggestions)
        }
    }

    fun addCustomCosmeticSelector(selector: String) {
        viewModelScope.launch {
            preferencesRepository.addCustomCosmeticSelector(selector)
            showFeedback("Added custom CSS element-hiding selector: $selector")
        }
    }

    fun removeCustomCosmeticSelector(selector: String) {
        viewModelScope.launch {
            preferencesRepository.removeCustomCosmeticSelector(selector)
        }
    }

    // --- Backup & Restore ---

    suspend fun exportFullBackupJson(): String = repository.createConfigurationBackupJson()

    fun restoreFullBackupJson(rawJson: String) {
        viewModelScope.launch {
            val res = repository.restoreConfigurationFromJson(rawJson)
            res.fold(
                onSuccess = { ruleCount ->
                    upstreamDnsResolver.clearCache()
                    refreshRuleConflicts()
                    showFeedback("Restored configuration backup ($ruleCount custom rules).")
                },
                onFailure = { err ->
                    showFeedback("Restore failed: ${err.message}")
                }
            )
        }
    }

    private fun reloadTunnelIfConnected() {
        val currentStatus = vpnState.value.status
        if (currentStatus == com.adshield.pro.domain.model.VpnStatus.CONNECTED ||
            currentStatus == com.adshield.pro.domain.model.VpnStatus.PAUSED
        ) {
            val intent = Intent(appContext, AdShieldVpnService::class.java).apply {
                action = AdShieldVpnService.ACTION_RELOAD_TUNNEL_CONFIG
            }
            appContext.startService(intent)
        }
    }

    // --- Security Center (on-device anti-virus / anti-phishing) ---

    private val _malwareBlocks = MutableStateFlow(0L)
    val malwareBlocks: StateFlow<Long> = _malwareBlocks.asStateFlow()

    private val _securityPosture = MutableStateFlow(
        SecurityPosture(
            protectionScore = 0,
            highRiskApps = 0,
            threatSignatures = 0,
            webProtectionActive = false
        )
    )
    val securityPosture: StateFlow<SecurityPosture> = _securityPosture.asStateFlow()

    private val _appAudits = MutableStateFlow<List<AppRiskAuditor.AppRiskAudit>>(emptyList())
    val appAudits: StateFlow<List<AppRiskAuditor.AppRiskAudit>> = _appAudits.asStateFlow()

    private val _isAuditingApps = MutableStateFlow(false)
    val isAuditingApps: StateFlow<Boolean> = _isAuditingApps.asStateFlow()

    private val _urlScanResult = MutableStateFlow<UrlThreatScanner.UrlVerdict?>(null)
    val urlScanResult: StateFlow<UrlThreatScanner.UrlVerdict?> = _urlScanResult.asStateFlow()

    private val _fileScanResult = MutableStateFlow<SecurityScanRecord?>(null)
    val fileScanResult: StateFlow<SecurityScanRecord?> = _fileScanResult.asStateFlow()

    private val _isScanningSecurity = MutableStateFlow(false)
    val isScanningSecurity: StateFlow<Boolean> = _isScanningSecurity.asStateFlow()

    /** Persisted scan journal — survives process death and restarts. */
    val securityHistory: StateFlow<List<SecurityScanRecord>> = scanJournalStore.historyFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val scanSummary: StateFlow<ScanJournalSummary> = securityHistory
        .map { ScanJournal.summarize(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ScanJournalSummary())

    val quarantineItems: StateFlow<List<QuarantinedItem>> = scanJournalStore.quarantineFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _scanProgress = MutableStateFlow<ScanProgress?>(null)
    val scanProgress: StateFlow<ScanProgress?> = _scanProgress.asStateFlow()

    private var lastScannedUri: Uri? = null
    private var lastScannedName: String? = null

    fun scanUrl(rawUrl: String) {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) {
            showFeedback("Enter a link to scan.")
            return
        }
        viewModelScope.launch {
            _isScanningSecurity.value = true
            val verdict = securityEngine.scanUrl(trimmed)
            _urlScanResult.value = verdict
            val record = SecurityScanRecord(
                id = System.currentTimeMillis(),
                kind = ScanKind.URL,
                target = trimmed.take(120),
                threatLevel = when (verdict.threatLevel) {
                    UrlThreatScanner.ThreatLevel.DANGEROUS -> SecurityLevel.DANGEROUS
                    UrlThreatScanner.ThreatLevel.SUSPICIOUS -> SecurityLevel.SUSPICIOUS
                    else -> SecurityLevel.CLEAN
                },
                riskScore = verdict.riskScore,
                threatName = verdict.findings.firstOrNull(),
                findings = verdict.findings,
                recommendation = verdict.recommendation,
                timestampEpochMs = System.currentTimeMillis()
            )
            scanJournalStore.record(record)
            _isScanningSecurity.value = false
        }
    }

    fun scanFile(file: File) {
        viewModelScope.launch {
            _isScanningSecurity.value = true
            _scanProgress.value = ScanProgress(label = file.name, completed = 0, total = 1)
            val startedAt = System.currentTimeMillis()
            val scanned = securityEngine.scanFile(file)
            val record = scanJournalStore.record(
                scanned.copy(durationMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L))
            )
            _fileScanResult.value = record
            lastScannedUri = Uri.fromFile(file)
            lastScannedName = file.name
            _scanProgress.value = ScanProgress(label = file.name, completed = 1, total = 1)
            refreshSecurityPosture()
            _isScanningSecurity.value = false
            showFeedback(scanFeedback(record, record.target))
        }
    }

    fun scanUri(uri: Uri, displayName: String) {
        viewModelScope.launch {
            _isScanningSecurity.value = true
            _scanProgress.value = ScanProgress(label = displayName, completed = 0, total = 1)
            val startedAt = System.currentTimeMillis()
            val scanned = securityEngine.scanUriContent(uri, displayName)
            if (scanned != null) {
                // sourceUri keeps the SAF handle so the report can offer a verified delete later.
                val record = scanJournalStore.record(
                    scanned.copy(
                        durationMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L),
                        sourceUri = uri.toString()
                    )
                )
                _fileScanResult.value = record
                lastScannedUri = uri
                lastScannedName = displayName
                _scanProgress.value = ScanProgress(label = displayName, completed = 1, total = 1)
                showFeedback(scanFeedback(record, displayName))
            } else {
                showFeedback("Could not read the selected file for scanning. No result was recorded.")
            }
            _isScanningSecurity.value = false
        }
    }

    // --- Quarantine (explicit user actions only; never automatic deletion) ---

    /**
     * Isolates the most recently scanned file. [removeOriginal] is opt-in, and the feedback states
     * plainly when Android refused to delete the original.
     */
    fun quarantineLastScannedFile(removeOriginal: Boolean) {
        val uri = lastScannedUri
        val record = _fileScanResult.value
        if (uri == null || record == null) {
            showFeedback("Scan a file first, then quarantine it.")
            return
        }
        viewModelScope.launch {
            val item = quarantineManager.isolate(uri, lastScannedName ?: record.target, record)
            if (item == null) {
                showFeedback("Could not read the file to quarantine it. Nothing was changed.")
                return@launch
            }
            val removed = if (removeOriginal) quarantineManager.tryRemoveOriginal(uri) else false
            val stored = item.copy(originalRemoved = removed)
            scanJournalStore.addQuarantined(stored)
            showFeedback(
                when {
                    removed -> "Quarantined ${stored.displayName}; the original was deleted."
                    removeOriginal -> "Quarantined ${stored.displayName}. Android did not allow deleting the original — remove it manually."
                    else -> "Quarantined ${stored.displayName}. The original is still in place."
                }
            )
        }
    }

    /** Restores a quarantined file to a user-chosen destination, verifying its hash. */
    fun restoreQuarantined(item: QuarantinedItem, destination: Uri) {
        viewModelScope.launch {
            val ok = quarantineManager.exportTo(item, destination)
            if (ok) {
                showFeedback("Restored ${item.displayName} (SHA-256 verified).")
            } else {
                showFeedback("Restore failed: the quarantined copy is missing or failed hash verification.")
            }
        }
    }

    fun deleteQuarantined(item: QuarantinedItem) {
        viewModelScope.launch {
            val deleted = quarantineManager.delete(item)
            if (deleted) {
                scanJournalStore.removeQuarantined(item.id)
                showFeedback("Permanently deleted the quarantined copy of ${item.displayName}.")
            } else {
                showFeedback("Could not delete the quarantined copy.")
            }
        }
    }

    fun verifyQuarantined(item: QuarantinedItem) {
        viewModelScope.launch {
            val intact = quarantineManager.verifyIntegrity(item)
            showFeedback(
                if (intact) "${item.displayName}: SHA-256 matches the recorded digest."
                else "${item.displayName}: integrity check FAILED. Do not restore this file."
            )
        }
    }

    fun quarantineDiskUsageBytes(): Long = quarantineManager.diskUsageBytes()

    // --- Scan history management & reports ---

    fun clearScanHistory() {
        viewModelScope.launch {
            scanJournalStore.clearHistory()
            showFeedback("Scan history cleared. Quarantined files were kept.")
        }
    }

    /** Builds the exportable report from real records only. */
    suspend fun buildScanReport(): String = withContext(Dispatchers.Default) {
        ScanJournal.buildTextReport(
            history = scanJournalStore.historySnapshot(),
            appVersion = com.adshield.pro.BuildConfig.VERSION_NAME,
            generatedAtEpochMs = System.currentTimeMillis()
        )
    }

    fun setScheduledScan(enabled: Boolean, intervalHours: Int) {
        viewModelScope.launch {
            preferencesRepository.setScheduledScan(enabled, intervalHours)
            ScheduledScanWorker.scheduleOrCancel(appContext, enabled, intervalHours)
            showFeedback(
                if (enabled) "Scheduled scan enabled every ${intervalHours.coerceIn(12, 168)} hours."
                else "Scheduled scan disabled."
            )
        }
    }

    fun auditInstalledApps() {
        if (_isAuditingApps.value) return
        viewModelScope.launch {
            _isAuditingApps.value = true
            _scanProgress.value = ScanProgress(label = "Installed applications", completed = 0, total = 0)
            val startedAt = System.currentTimeMillis()
            val audits = securityEngine.auditInstalledApps { completed, total, target ->
                _scanProgress.value = ScanProgress(label = target, completed = completed, total = total)
            }
            _appAudits.value = audits
            val flagged = audits.count { it.riskLevel == AppRiskAuditor.RiskLevel.HIGH }
            scanJournalStore.record(
                SecurityScanRecord(
                    id = 0L,
                    kind = ScanKind.APP_AUDIT,
                    target = "Device app audit — ${audits.size} installed app(s) examined",
                    threatLevel = when {
                        flagged > 0 -> SecurityLevel.DANGEROUS
                        audits.any { it.riskLevel == AppRiskAuditor.RiskLevel.MEDIUM } -> SecurityLevel.SUSPICIOUS
                        else -> SecurityLevel.CLEAN
                    },
                    riskScore = audits.maxOfOrNull { it.riskScore } ?: 0,
                    threatName = audits.firstOrNull { it.riskLevel == AppRiskAuditor.RiskLevel.HIGH }?.profile?.appName,
                    findings = buildList {
                        add("${audits.size} installed application(s) audited.")
                        add("$flagged high-risk app(s) detected.")
                        add("Other apps' private files were not examined: Android does not grant access.")
                    },
                    recommendation = if (flagged > 0) {
                        "Review the flagged apps in the Apps tab."
                    } else {
                        "No high-risk permission stacks matched. This is not a guarantee of a malware-free device."
                    },
                    timestampEpochMs = System.currentTimeMillis(),
                    scanSource = ScanSource.MANUAL,
                    durationMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
                )
            )
            refreshSecurityPosture(highRisk = flagged)
            _isAuditingApps.value = false
            _scanProgress.value = null
            showFeedback(
                if (flagged > 0) "Full app audit complete: $flagged high-risk app(s) found — review them in Security."
                else "Full app audit complete: no high-risk apps detected."
            )
        }
    }

    fun refreshSecurityPosture(highRisk: Int? = null) {
        val riskApps = highRisk ?: _securityPosture.value.highRiskApps
        val vpnActive = repository.vpnRuntimeState.value.status == VpnStatus.CONNECTED
        val signatures = securityEngine.activeThreatSignatureCount()
        _securityPosture.value = SecurityEngine.buildSecurityPosture(
            highRiskApps = riskApps,
            threatSignatures = signatures,
            vpnActive = vpnActive
        )
    }

    fun deleteScannedDocument(record: SecurityScanRecord) {
        val source = record.sourceUri
        if (source == null) {
            showFeedback("This report has no deletable SAF document. Use the file manager to grant access first.")
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val rows = appContext.contentResolver.delete(Uri.parse(source), null, null)
                    if (rows <= 0) error("The storage provider did not delete the document")
                }
            }
            result.fold(
                onSuccess = {
                    scanJournalStore.removeHistory(record.id)
                    if (_fileScanResult.value?.id == record.id) _fileScanResult.value = null
                    showFeedback("Deleted the selected document. This action cannot be undone by AdShield.")
                },
                onFailure = { showFeedback("Delete failed; the original remains: ${it.message ?: "storage provider error"}") }
            )
        }
    }

    fun clearSecurityHistory() {
        viewModelScope.launch {
            scanJournalStore.clearHistory()
            showFeedback("Persisted security scan history cleared.")
        }
    }

    private fun scanFeedback(record: SecurityScanRecord, label: String): String = when (record.threatLevel) {
        SecurityLevel.DANGEROUS -> "THREAT: ${record.threatName ?: label} — do not open or install."
        SecurityLevel.SUSPICIOUS -> "Suspicious file: ${record.findings.firstOrNull() ?: "risk indicators found"}."
        else -> "File is clean: no signatures or structural threats found."
    }

    // --- Battery Intelligence (real hardware telemetry only) ---

    val batteryTelemetry: StateFlow<BatteryTelemetry> = batteryRepository.telemetry
    val batteryHistory: StateFlow<List<BatteryDiagnostics.Sample>> = batteryRepository.history
    val batterySessions: StateFlow<List<BatterySession>> = batteryRepository.sessions
    val batteryDiagnostics: StateFlow<List<BatteryDiagnostic>> = batteryRepository.diagnostics
    val batteryLastDiagnosticAtMs: StateFlow<Long> = batteryRepository.lastDiagnosticAtMs

    private val _isDiagnosingBattery = MutableStateFlow(false)
    val isDiagnosingBattery: StateFlow<Boolean> = _isDiagnosingBattery.asStateFlow()

    private val _batteryHealthScore = MutableStateFlow(batteryRepository.healthScore())
    val batteryHealthScore: StateFlow<Int> = _batteryHealthScore.asStateFlow()

    private val _batteryMinutesToFull = MutableStateFlow<Int?>(null)
    val batteryMinutesToFull: StateFlow<Int?> = _batteryMinutesToFull.asStateFlow()

    private val _batteryMinutesToEmpty = MutableStateFlow<Int?>(null)
    val batteryMinutesToEmpty: StateFlow<Int?> = _batteryMinutesToEmpty.asStateFlow()

    private val _batteryChargeRate = MutableStateFlow<Float?>(null)
    val batteryChargeRate: StateFlow<Float?> = _batteryChargeRate.asStateFlow()

    private fun refreshBatteryDerived() {
        _batteryHealthScore.value = batteryRepository.healthScore()
        _batteryChargeRate.value = batteryRepository.chargeRatePercentPerHour()
        _batteryMinutesToFull.value = batteryRepository.minutesToFull()
        _batteryMinutesToEmpty.value = batteryRepository.minutesToEmpty()
    }

    /** Runs the on-device diagnostic chain against live readings. */
    fun runBatteryDiagnosis() {
        if (_isDiagnosingBattery.value) return
        viewModelScope.launch {
            _isDiagnosingBattery.value = true
            // Sample the sensors once more so the report reflects "now" rather than the last broadcast.
            batteryRepository.runDiagnostics()
            refreshBatteryDerived()
            val worst = batteryRepository.diagnostics.value.maxByOrNull { it.severity.ordinal }
            showFeedback(
                when (worst?.severity) {
                    null -> "Battery diagnostic complete."
                    com.adshield.pro.engine.battery.BatteryDiagnosticSeverity.CRITICAL ->
                        "Battery alert: ${worst.title}."
                    com.adshield.pro.engine.battery.BatteryDiagnosticSeverity.WARNING ->
                        "Battery diagnostic: ${worst.title}."
                    else -> "Battery diagnostic complete — no faults found in the measured readings."
                }
            )
            _isDiagnosingBattery.value = false
        }
    }

    fun refreshBatteryReadings() {
        batteryRepository.telemetry.value.let { refreshBatteryDerived() }
    }

    /** Runtime capability profile for the Status screen (no defaults guessed). */
    fun deviceProfile(screenWidthDp: Int, smallestWidthDp: Int) =
        deviceProfiler.profile(batteryRepository.telemetry.value, screenWidthDp, smallestWidthDp)

    fun clearBatterySessions() {
        batteryRepository.clearSessions()
        showFeedback("Battery session history cleared.")
    }

    // --- Aether AI (live network intelligence) ---

    /**
     * The live network snapshot is derived from the repository's own streams (session runtime,
     * DNS log, top apps, blocked domains). It is recomputed whenever any of them changes, so the
     * dashboard can only ever show traffic that actually happened.
     */
    val liveNetwork: StateFlow<LiveNetworkModel.NetworkSnapshot> = combine(
        repository.vpnRuntimeState,
        recentLogs,
        liveTopApps,
        topBlockedDomains
    ) { runtime, logs, apps, blocked ->
        LiveNetworkModel.snapshot(runtime, logs, apps, blocked)
    }.flowOn(Dispatchers.Default).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        LiveNetworkModel.snapshot(
            repository.vpnRuntimeState.value,
            emptyList(),
            emptyList(),
            emptyList()
        )
    )

    // --- Quick Settings System-Preference Tiles (Private DNS & USB Debugging) ---

    private val _systemTilesStatus = MutableStateFlow(systemPreferencesController.readLiveStatus())
    val systemTilesStatus: StateFlow<SystemTilesLiveStatus> = _systemTilesStatus.asStateFlow()

    private val _isGrantingQuickTilePermission = MutableStateFlow(false)
    val isGrantingQuickTilePermission: StateFlow<Boolean> = _isGrantingQuickTilePermission.asStateFlow()

    fun refreshSystemTilesStatus() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                systemPreferencesController.executeDueAutoReverts()
                _systemTilesStatus.value = systemPreferencesController.readLiveStatus()
            }
        }
    }

    fun applyPrivateDnsStep(step: PrivateDnsCycleStep) {
        viewModelScope.launch {
            val result = systemPreferencesController.applyPrivateDnsStep(step, fromTile = false)
            _systemTilesStatus.value = withContext(Dispatchers.IO) {
                systemPreferencesController.readLiveStatus()
            }
            handleSystemWriteResult(result)
        }
    }

    fun cyclePrivateDnsNow() {
        viewModelScope.launch {
            val result = systemPreferencesController.cyclePrivateDnsFromTile()
            _systemTilesStatus.value = withContext(Dispatchers.IO) {
                systemPreferencesController.readLiveStatus()
            }
            handleSystemWriteResult(result)
        }
    }

    fun setPrivateDnsIncludeOff(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.updatePrivateDnsTileConfig { it.copy(includeOffInCycle = enabled) }
            systemPreferencesController.requestTileUpdates()
        }
    }

    fun setPrivateDnsIncludeAuto(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.updatePrivateDnsTileConfig { it.copy(includeAutoInCycle = enabled) }
            systemPreferencesController.requestTileUpdates()
        }
    }

    fun setPrivateDnsHostnameEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.updatePrivateDnsTileConfig { cfg ->
                cfg.copy(
                    hostnames = cfg.hostnames.map { item ->
                        if (item.id == id) item.copy(enabledInCycle = enabled) else item
                    }
                )
            }
            systemPreferencesController.requestTileUpdates()
        }
    }

    fun movePrivateDnsHostname(id: String, delta: Int) {
        viewModelScope.launch {
            preferencesRepository.updatePrivateDnsTileConfig { cfg ->
                cfg.copy(hostnames = SystemTileEngine.moveHostname(cfg.hostnames, id, delta))
            }
            systemPreferencesController.requestTileUpdates()
        }
    }

    fun addPrivateDnsCustomHostname(
        label: String,
        hostname: String,
        onAdded: () -> Unit = {}
    ) {
        viewModelScope.launch {
            var errorMessage: String? = null
            preferencesRepository.updatePrivateDnsTileConfig { cfg ->
                val added = SystemTileEngine.addCustomHostname(cfg, label, hostname)
                added.fold(
                    onSuccess = { it },
                    onFailure = { err ->
                        errorMessage = err.message ?: "Invalid Private DNS hostname."
                        cfg
                    }
                )
            }
            if (errorMessage != null) {
                showFeedback(errorMessage!!)
            } else {
                systemPreferencesController.requestTileUpdates()
                onAdded()
                showFeedback("Added Private DNS hostname to tile cycle.")
            }
        }
    }

    fun removePrivateDnsHostname(id: String) {
        viewModelScope.launch {
            preferencesRepository.updatePrivateDnsTileConfig { cfg ->
                SystemTileEngine.removeHostname(cfg, id)
            }
            systemPreferencesController.requestTileUpdates()
        }
    }

    fun setPrivateDnsRequireUnlock(requireUnlock: Boolean) {
        viewModelScope.launch {
            preferencesRepository.updatePrivateDnsTileConfig {
                it.copy(requireUnlockToToggle = requireUnlock)
            }
        }
    }

    fun setPrivateDnsAutoRevert(enabled: Boolean, delayMinutes: Int) {
        viewModelScope.launch {
            val clamped = SystemTileEngine.clampDelayMinutes(delayMinutes)
            preferencesRepository.updatePrivateDnsTileConfig {
                it.copy(
                    autoRevertEnabled = enabled,
                    autoRevertDelayMinutes = clamped,
                    autoRevertTargetMode = if (enabled) it.autoRevertTargetMode else null,
                    autoRevertTargetHostname = if (enabled) it.autoRevertTargetHostname else null,
                    autoRevertDeadlineEpochMs = if (enabled) it.autoRevertDeadlineEpochMs else 0L
                )
            }
            if (!enabled) {
                systemPreferencesController.cancelPrivateDnsAutoRevert()
            } else {
                systemPreferencesController.requestTileUpdates()
            }
        }
    }

    fun cancelPrivateDnsAutoRevert() {
        viewModelScope.launch {
            systemPreferencesController.cancelPrivateDnsAutoRevert()
            showFeedback("Cancelled Private DNS auto-revert timer.")
        }
    }

    fun setUsbDebuggingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val result = systemPreferencesController.setUsbDebuggingEnabled(enabled, fromTile = false)
            _systemTilesStatus.value = withContext(Dispatchers.IO) {
                systemPreferencesController.readLiveStatus()
            }
            handleSystemWriteResult(result)
        }
    }

    fun setUsbDebuggingAutoRevert(enabled: Boolean, delayMinutes: Int) {
        viewModelScope.launch {
            val clamped = SystemTileEngine.clampDelayMinutes(delayMinutes)
            preferencesRepository.updateUsbDebuggingTileConfig {
                it.copy(
                    autoRevertEnabled = enabled,
                    autoRevertDelayMinutes = clamped,
                    autoRevertDeadlineEpochMs = if (enabled) it.autoRevertDeadlineEpochMs else 0L
                )
            }
            if (!enabled) {
                systemPreferencesController.cancelUsbDebuggingAutoRevert()
            } else {
                systemPreferencesController.requestTileUpdates()
            }
        }
    }

    fun armUsbDebuggingAutoRevertNow() {
        viewModelScope.launch {
            val result = systemPreferencesController.armUsbDebuggingAutoRevertNow()
            _systemTilesStatus.value = withContext(Dispatchers.IO) {
                systemPreferencesController.readLiveStatus()
            }
            handleSystemWriteResult(result)
        }
    }

    fun cancelUsbDebuggingAutoRevert() {
        viewModelScope.launch {
            systemPreferencesController.cancelUsbDebuggingAutoRevert()
            showFeedback("Cancelled USB Debugging auto-revert timer.")
        }
    }

    fun setUsbDebuggingRequireUnlock(requireUnlock: Boolean) {
        viewModelScope.launch {
            preferencesRepository.updateUsbDebuggingTileConfig {
                it.copy(requireUnlockToToggle = requireUnlock)
            }
        }
    }

    fun notifyQuickTileFeedback(message: String) {
        showFeedback(message)
    }

    fun tryGrantQuickTilePermissionWithRoot() {
        if (_isGrantingQuickTilePermission.value) return
        viewModelScope.launch {
            _isGrantingQuickTilePermission.value = true
            try {
                val result = systemPreferencesController.tryGrantPermissionWithRoot()
                _systemTilesStatus.value = withContext(Dispatchers.IO) {
                    systemPreferencesController.readLiveStatus()
                }
                handleSystemWriteResult(result)
            } finally {
                _isGrantingQuickTilePermission.value = false
            }
        }
    }

    private fun handleSystemWriteResult(result: SystemPreferenceWriteResult) {
        when (result) {
            is SystemPreferenceWriteResult.Success -> showFeedback(result.message)
            is SystemPreferenceWriteResult.PermissionDenied ->
                showFeedback("Grant WRITE_SECURE_SETTINGS via ADB once to let AdShield Pro toggle this preference.")
            is SystemPreferenceWriteResult.RejectedBySystem -> showFeedback(result.reason)
        }
    }
}
