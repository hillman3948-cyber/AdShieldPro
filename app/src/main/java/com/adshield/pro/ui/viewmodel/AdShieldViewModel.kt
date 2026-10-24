package com.adshield.pro.ui.viewmodel

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adshield.pro.data.local.preferences.AppSettingsState
import com.adshield.pro.data.local.preferences.DarkThemePreference
import com.adshield.pro.data.local.preferences.UserPreferencesRepository
import com.adshield.pro.data.repository.AdShieldRepository
import com.adshield.pro.BuildConfig
import com.adshield.pro.domain.model.AppExclusionInfo
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
import com.adshield.pro.domain.model.ProtectionMode
import com.adshield.pro.domain.model.RamOptimizationResult
import com.adshield.pro.domain.model.RamSnapshot
import com.adshield.pro.domain.model.RuleCategory
import com.adshield.pro.domain.model.RuleConflictReport
import com.adshield.pro.domain.model.RuleMatchType
import com.adshield.pro.domain.model.RuleType
import com.adshield.pro.domain.model.SmartBoostResult
import com.adshield.pro.domain.model.SourceEffectiveness
import com.adshield.pro.domain.model.StorageMetrics
import com.adshield.pro.domain.model.TimeBucketStat
import com.adshield.pro.domain.model.VpnRuntimeState
import com.adshield.pro.engine.browser.BrowserContentBlocker
import com.adshield.pro.engine.dns.UpstreamDnsResolver
import com.adshield.pro.engine.filter.FilterRuleEngine
import com.adshield.pro.engine.optimizer.RuleBasedOptimizationPredictor
import com.adshield.pro.engine.optimizer.SystemOptimizerEngine
import com.adshield.pro.service.AdShieldVpnService
import com.adshield.pro.ui.UpdatePromptActivity
import com.adshield.pro.update.GitHubReleaseClient
import com.adshield.pro.worker.FilterUpdateWorker
import com.adshield.pro.worker.SmartOptimizationWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val optimizerEngine: SystemOptimizerEngine
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
            customRules.collect {
                refreshRuleConflicts()
            }
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
            val excluded = settingsState.value.excludedAppPackages
            _installedApps.value = repository.getInstalledApps(excluded)
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
}
