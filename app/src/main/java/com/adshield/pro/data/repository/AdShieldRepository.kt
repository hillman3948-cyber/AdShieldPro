package com.adshield.pro.data.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.adshield.pro.data.local.db.AdShieldDao
import com.adshield.pro.data.local.db.CustomRuleEntity
import com.adshield.pro.data.local.db.DailyStatEntity
import com.adshield.pro.data.local.db.DnsLogEntity
import com.adshield.pro.data.local.db.FilterSourceEntity
import com.adshield.pro.data.local.preferences.AppSettingsState
import com.adshield.pro.data.local.preferences.UserPreferencesRepository
import com.adshield.pro.domain.model.AppExclusionInfo
import com.adshield.pro.domain.model.AppDnsActivity
import com.adshield.pro.domain.model.CustomRule
import com.adshield.pro.domain.model.DnsActivityRecord
import com.adshield.pro.domain.model.DomainBlockCount
import com.adshield.pro.domain.model.FilterListSource
import com.adshield.pro.domain.model.ProtectionMode
import com.adshield.pro.domain.model.RuleCategory
import com.adshield.pro.domain.model.RuleConflictReport
import com.adshield.pro.domain.model.RuleMatchType
import com.adshield.pro.domain.model.RuleType
import com.adshield.pro.domain.model.SourceEffectiveness
import com.adshield.pro.domain.model.TimeBucketStat
import com.adshield.pro.domain.model.VpnRuntimeState
import com.adshield.pro.domain.model.VpnStatus
import com.adshield.pro.engine.filter.FilterRuleEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AdShieldRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: AdShieldDao,
    private val prefs: UserPreferencesRepository,
    private val filterEngine: FilterRuleEngine
) {
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val filterMutex = Mutex()
    private val statsMutex = Mutex()

    private val filterCacheDir: File by lazy {
        File(context.filesDir, "filter_cache").apply { mkdirs() }
    }

    private val filterDownloadClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private val jsonSerializer = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _vpnRuntimeState = MutableStateFlow(VpnRuntimeState())
    val vpnRuntimeState: StateFlow<VpnRuntimeState> = _vpnRuntimeState.asStateFlow()

    private val _liveTopApps = MutableStateFlow<Map<String, AppDnsActivity>>(emptyMap())
    val liveTopAppsFlow: Flow<List<AppDnsActivity>> = _liveTopApps.map { apps ->
        apps.values.sortedWith(compareByDescending<AppDnsActivity> { it.totalQueries }.thenByDescending { it.blockedQueries })
    }

    val settingsFlow: Flow<AppSettingsState> = prefs.settingsFlow

    val recentLogsFlow: Flow<List<DnsActivityRecord>> = dao.observeRecentLogs(250).map { list ->
        list.map { entity ->
            DnsActivityRecord(
                id = entity.id,
                timestampEpochMs = entity.timestampEpochMs,
                domain = entity.domain,
                queryType = entity.queryType,
                isBlocked = entity.isBlocked,
                category = entity.category?.let { runCatching { RuleCategory.valueOf(it) }.getOrNull() },
                matchedRule = entity.matchedRule,
                sourceList = entity.sourceList,
                latencyMs = entity.latencyMs,
                protocolUsed = entity.protocolUsed
            )
        }
    }

    val topBlockedDomainsFlow: Flow<List<DomainBlockCount>> = dao.observeTopBlockedDomains(15)
    val sourceEffectivenessFlow: Flow<List<SourceEffectiveness>> = dao.observeSourceEffectiveness()
    val allTimeTotalQueriesFlow: Flow<Long> = dao.observeAllTimeTotalQueries()
    val allTimeBlockedQueriesFlow: Flow<Long> = dao.observeAllTimeBlockedQueries()
    val allTimeAllowedQueriesFlow: Flow<Long> = dao.observeAllTimeAllowedQueries()

    val customRulesFlow: Flow<List<CustomRule>> = dao.observeCustomRules().map { entities ->
        entities.map { it.toDomain() }
    }

    val filterSourcesFlow: Flow<List<FilterListSource>> = dao.observeFilterSources().map { entities ->
        entities.map { it.toDomain() }
    }

    init {
        repositoryScope.launch {
            initializeEngineAndSeedSources()
        }
    }

    /**
     * Seeds built-in filter sources on first launch, compiles bundled + cached rules into
     * [FilterRuleEngine], and synchronizes custom rules and protection mode.
     */
    suspend fun initializeEngineAndSeedSources() = withContext(Dispatchers.IO) {
        filterMutex.withLock {
            val currentSettings = prefs.getSettingsSnapshot()
            filterEngine.updateProtectionMode(currentSettings.protectionMode)

            var sources = dao.getAllFilterSourcesNow()
            if (sources.isEmpty()) {
                val defaults = buildDefaultFilterSources()
                dao.upsertFilterSources(defaults)
                sources = defaults
            } else {
                val migrated = sources.map { src ->
                    if (src.isPremiumOnly || src.lastStatusMessage.contains("Premium", ignoreCase = true)) {
                        src.copy(
                            isPremiumOnly = false,
                            lastStatusMessage = if (src.lastStatusMessage.contains("Premium", ignoreCase = true)) {
                                "Ready to sync"
                            } else {
                                src.lastStatusMessage
                            }
                        )
                    } else {
                        src
                    }
                }
                if (migrated != sources) {
                    dao.upsertFilterSources(migrated)
                    sources = migrated
                }
            }

            val customRules = dao.getAllCustomRulesNow().map { it.toDomain() }
            filterEngine.updateCustomRules(customRules)

            rebuildActiveFilterListsLocked(sources)
            refreshRuntimeRuleCount()
        }
    }

    fun observeDailyChartBuckets(daysCount: Int): Flow<List<TimeBucketStat>> {
        val todayEpochDay = System.currentTimeMillis() / MILLIS_PER_DAY
        val minDay = todayEpochDay - (daysCount - 1)
        return dao.observeDailyStatsSince(minDay).map { entities ->
            val byDay = entities.associateBy { it.dateEpochDay }
            val sdf = SimpleDateFormat(if (daysCount <= 7) "EEE" else "MM/dd", Locale.US)
            (minDay..todayEpochDay).map { day ->
                val stat = byDay[day]
                val epochMs = day * MILLIS_PER_DAY
                TimeBucketStat(
                    label = sdf.format(Date(epochMs)),
                    startEpochMs = epochMs,
                    totalQueries = stat?.totalQueries ?: 0L,
                    blockedQueries = stat?.blockedQueries ?: 0L,
                    allowedQueries = stat?.allowedQueries ?: 0L
                )
            }
        }
    }

    // --- VPN Runtime State Synchronization ---

    fun updateVpnStatus(
        status: VpnStatus,
        networkLabel: String? = null,
        error: String? = null
    ) {
        _vpnRuntimeState.update { current ->
            val nowConnectedSince = when {
                status == VpnStatus.CONNECTED && current.connectedSinceEpochMs == null -> System.currentTimeMillis()
                status == VpnStatus.DISCONNECTED || status == VpnStatus.ERROR -> null
                else -> current.connectedSinceEpochMs
            }
            current.copy(
                status = status,
                connectedSinceEpochMs = nowConnectedSince,
                activeNetworkLabel = networkLabel ?: current.activeNetworkLabel,
                loadedRuleCount = filterEngine.getCurrentSnapshot().totalLoadedRules,
                lastError = error
            )
        }
    }

    fun syncRuntimeModeAndResolver(mode: ProtectionMode, settings: AppSettingsState) {
        _vpnRuntimeState.update { current ->
            current.copy(
                activeMode = mode,
                resolverConfig = settings.resolverConfig,
                loadedRuleCount = filterEngine.getCurrentSnapshot().totalLoadedRules
            )
        }
    }

    private fun refreshRuntimeRuleCount() {
        _vpnRuntimeState.update { current ->
            current.copy(loadedRuleCount = filterEngine.getCurrentSnapshot().totalLoadedRules)
        }
    }

    /**
     * Records a real DNS query event from [com.adshield.pro.service.AdShieldVpnService].
     * Never fabricates numbers; updates both live session counters, daily aggregate counters,
     * and (if enabled by the user) local detailed DNS logs.
     */
    suspend fun recordDnsQueryEvent(
        domain: String,
        queryType: String,
        isBlocked: Boolean,
        category: RuleCategory?,
        matchedRule: String?,
        sourceList: String,
        latencyMs: Int,
        protocolUsed: String,
        fromCache: Boolean,
        appPackageName: String? = null,
        appName: String? = null
    ) {
        // Live per-app counters are intentionally memory-only; detailed DNS logging remains optional.
        val appKey = appPackageName?.takeIf { it.isNotBlank() } ?: "unattributed"
        _liveTopApps.update { current ->
            val previous = current[appKey]
            current + (appKey to AppDnsActivity(
                packageName = appPackageName?.takeIf { it.isNotBlank() },
                appName = appName?.takeIf { it.isNotBlank() } ?: "Unattributed",
                totalQueries = (previous?.totalQueries ?: 0L) + 1L,
                blockedQueries = (previous?.blockedQueries ?: 0L) + if (isBlocked) 1L else 0L
            ))
        }

        // 1. Update live session state
        _vpnRuntimeState.update { state ->
            val newTotal = state.sessionTotalQueries + 1
            val newBlocked = state.sessionBlockedQueries + if (isBlocked) 1 else 0
            val newAllowed = state.sessionAllowedQueries + if (isBlocked) 0 else 1
            val newCached = state.sessionCachedQueries + if (fromCache) 1 else 0
            val newAvgLatency = if (state.sessionTotalQueries == 0L) {
                latencyMs
            } else {
                ((state.averageLatencyMs * 7) + latencyMs) / 8
            }
            state.copy(
                sessionTotalQueries = newTotal,
                sessionBlockedQueries = newBlocked,
                sessionAllowedQueries = newAllowed,
                sessionCachedQueries = newCached,
                averageLatencyMs = newAvgLatency
            )
        }

        val now = System.currentTimeMillis()
        val epochDay = now / MILLIS_PER_DAY

        // 2. Update persistent daily aggregate counter
        statsMutex.withLock {
            val existing = dao.getDailyStatForDay(epochDay)
            val updated = if (existing == null) {
                DailyStatEntity(
                    dateEpochDay = epochDay,
                    totalQueries = 1L,
                    blockedQueries = if (isBlocked) 1L else 0L,
                    allowedQueries = if (isBlocked) 0L else 1L,
                    cachedQueries = if (fromCache) 1L else 0L
                )
            } else {
                existing.copy(
                    totalQueries = existing.totalQueries + 1L,
                    blockedQueries = existing.blockedQueries + if (isBlocked) 1L else 0L,
                    allowedQueries = existing.allowedQueries + if (isBlocked) 0L else 1L,
                    cachedQueries = existing.cachedQueries + if (fromCache) 1L else 0L
                )
            }
            dao.upsertDailyStat(updated)
        }

        // 3. Store detailed DNS log only if enabled by user privacy preferences
        val currentSettings = prefs.getSettingsSnapshot()
        if (currentSettings.detailedDnsLoggingEnabled) {
            dao.insertDnsLog(
                DnsLogEntity(
                    timestampEpochMs = now,
                    domain = domain,
                    queryType = queryType,
                    isBlocked = isBlocked,
                    category = category?.name,
                    matchedRule = matchedRule,
                    sourceList = sourceList,
                    latencyMs = latencyMs,
                    protocolUsed = protocolUsed
                )
            )
        }
    }

    suspend fun clearAllStatisticsAndLogs() = withContext(Dispatchers.IO) {
        statsMutex.withLock {
            dao.clearAllDnsLogs()
            dao.clearAllDailyStats()
        }
        _liveTopApps.value = emptyMap()
        _vpnRuntimeState.update {
            it.copy(
                sessionTotalQueries = 0L,
                sessionBlockedQueries = 0L,
                sessionAllowedQueries = 0L,
                sessionCachedQueries = 0L,
                averageLatencyMs = 0
            )
        }
    }

    suspend fun enforceLogRetentionPolicy() = withContext(Dispatchers.IO) {
        val days = prefs.getSettingsSnapshot().logRetentionDays
        val cutoffMs = System.currentTimeMillis() - (days * MILLIS_PER_DAY)
        dao.pruneLogsOlderThan(cutoffMs)
    }

    // --- Protection Mode & Custom Rules Management (Zero VPN Restart Required) ---

    suspend fun selectProtectionMode(mode: ProtectionMode) {
        prefs.setProtectionMode(mode)
        filterEngine.updateProtectionMode(mode)
        _vpnRuntimeState.update { it.copy(activeMode = mode) }
    }

    suspend fun addCustomRule(
        rawInput: String,
        ruleType: RuleType,
        matchType: RuleMatchType?,
        comment: String
    ): Result<CustomRule> = withContext(Dispatchers.IO) {
        val validated = filterEngine.validateAndParseRuleInput(
            rawInput = rawInput,
            explicitType = ruleType,
            explicitMatchType = matchType,
            comment = comment
        ).getOrElse { return@withContext Result.failure(it) }

        val id = dao.insertCustomRule(validated.toEntity())
        val saved = validated.copy(id = id)
        syncCustomRulesToEngine()
        Result.success(saved)
    }

    suspend fun updateCustomRule(rule: CustomRule): Result<Unit> = withContext(Dispatchers.IO) {
        val validated = filterEngine.validateAndParseRuleInput(
            rawInput = rule.rawPattern,
            explicitType = rule.ruleType,
            explicitMatchType = rule.matchType,
            comment = rule.comment
        ).getOrElse { return@withContext Result.failure(it) }

        val updated = validated.copy(
            id = rule.id,
            isEnabled = rule.isEnabled,
            createdAtEpochMs = rule.createdAtEpochMs
        )
        dao.updateCustomRule(updated.toEntity())
        syncCustomRulesToEngine()
        Result.success(Unit)
    }

    suspend fun toggleCustomRuleEnabled(rule: CustomRule, enabled: Boolean) = withContext(Dispatchers.IO) {
        dao.updateCustomRule(rule.copy(isEnabled = enabled).toEntity())
        syncCustomRulesToEngine()
    }

    suspend fun deleteCustomRule(ruleId: Long) = withContext(Dispatchers.IO) {
        dao.deleteCustomRuleById(ruleId)
        syncCustomRulesToEngine()
    }

    suspend fun removeDuplicateCustomRules(): Int = withContext(Dispatchers.IO) {
        val all = dao.getAllCustomRulesNow()
        val seen = HashSet<String>()
        var removedCount = 0
        for (rule in all) {
            val key = "${rule.normalizedPattern}:${rule.ruleType}:${rule.matchType}"
            if (!seen.add(key)) {
                dao.deleteCustomRuleById(rule.id)
                removedCount++
            }
        }
        if (removedCount > 0) {
            syncCustomRulesToEngine()
        }
        removedCount
    }

    suspend fun analyzeCustomRuleConflicts(): List<RuleConflictReport> = withContext(Dispatchers.IO) {
        val rules = dao.getAllCustomRulesNow().map { it.toDomain() }
        filterEngine.detectConflictsAndDuplicates(rules)
    }

    suspend fun exportRulesAsText(): String = withContext(Dispatchers.IO) {
        val rules = dao.getAllCustomRulesNow()
        buildString {
            appendLine("# AdShield Pro v2.0 Custom Rules Export")
            appendLine("# Exported at: ${Date()}")
            for (r in rules) {
                val prefix = if (r.ruleType == RuleType.ALLOW.name) "@@||" else "||"
                val suffix = if (r.matchType == RuleMatchType.SUBDOMAIN.name) "^" else ""
                if (r.comment.isNotBlank()) {
                    appendLine("# ${r.comment}")
                }
                appendLine("$prefix${r.normalizedPattern}$suffix")
            }
        }
    }

    suspend fun importRulesFromText(content: String): Int = withContext(Dispatchers.IO) {
        var imported = 0
        val toInsert = ArrayList<CustomRuleEntity>()
        content.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) return@forEach
            val parsed = filterEngine.validateAndParseRuleInput(trimmed).getOrNull()
            if (parsed != null) {
                toInsert.add(parsed.toEntity())
                imported++
            }
        }
        if (toInsert.isNotEmpty()) {
            dao.insertCustomRules(toInsert)
            syncCustomRulesToEngine()
        }
        imported
    }

    private suspend fun syncCustomRulesToEngine() {
        val rules = dao.getAllCustomRulesNow().map { it.toDomain() }
        filterEngine.updateCustomRules(rules)
        refreshRuntimeRuleCount()
    }

    // --- Layer 5: Filter List Management, Atomic Updates & Rollback ---

    suspend fun toggleFilterSourceEnabled(source: FilterListSource, enabled: Boolean) = withContext(Dispatchers.IO) {
        filterMutex.withLock {
            dao.upsertFilterSource(source.copy(isEnabled = enabled).toEntity())
            val allSources = dao.getAllFilterSourcesNow()
            rebuildActiveFilterListsLocked(allSources)
            refreshRuntimeRuleCount()
        }
    }

    suspend fun addCustomFilterSource(
        name: String,
        url: String,
        category: RuleCategory
    ): Result<FilterListSource> = withContext(Dispatchers.IO) {
        val cleanName = name.trim()
        val cleanUrl = url.trim()
        if (cleanName.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Filter list name is required."))
        }
        if (!cleanUrl.startsWith("https://", ignoreCase = true)) {
            return@withContext Result.failure(IllegalArgumentException("Only secure HTTPS filter list URLs (https://...) are permitted."))
        }

        val id = "custom_" + sha256Hex(cleanUrl).take(12)
        val newSource = FilterListSource(
            id = id,
            name = cleanName,
            description = "User-added HTTPS filter source",
            url = cleanUrl,
            licenseName = "Custom Source",
            category = category,
            isBuiltIn = false,
            isEnabled = true,
            isPremiumOnly = false,
            ruleCount = 0,
            duplicateCount = 0,
            lastUpdatedEpochMs = 0L,
            lastStatusMessage = "Added — Ready to sync"
        )
        dao.upsertFilterSource(newSource.toEntity())
        Result.success(newSource)
    }

    suspend fun deleteCustomFilterSource(id: String) = withContext(Dispatchers.IO) {
        filterMutex.withLock {
            dao.deleteCustomFilterSource(id)
            File(filterCacheDir, "$id.txt").delete()
            File(filterCacheDir, "$id.bak").delete()
            val allSources = dao.getAllFilterSourcesNow()
            rebuildActiveFilterListsLocked(allSources)
            refreshRuntimeRuleCount()
        }
    }

    /**
     * Updates all enabled filter lists with source validation, atomic file replacement,
     * duplicate detection, and automatic rollback on failure.
     */
    suspend fun updateAllEnabledFilterLists(): Result<Int> = withContext(Dispatchers.IO) {
        filterMutex.withLock {
            val previousSnapshot = filterEngine.getCurrentSnapshot()
            val sources = dao.getAllFilterSourcesNow()
            var updatedSourcesCount = 0
            val now = System.currentTimeMillis()

            for (source in sources) {
                if (!source.isEnabled) continue
                if (source.url.startsWith("asset://")) {
                    // Refresh bundled asset rules
                    val assetContent = readBundledDefaultBlocklist()
                    val parsed = filterEngine.parseFilterListContent(
                        assetContent,
                        RuleCategory.valueOf(source.category),
                        source.name
                    ).getOrNull()
                    if (parsed != null) {
                        dao.upsertFilterSource(
                            source.copy(
                                ruleCount = parsed.totalValidRules,
                                duplicateCount = parsed.duplicateCount,
                                lastUpdatedEpochMs = now,
                                lastStatusMessage = "Bundled Active (${parsed.totalValidRules} rules)"
                            )
                        )
                        updatedSourcesCount++
                    }
                    continue
                }

                // Remote HTTPS Filter Source with Atomic Temp/Backup Swap
                val cacheFile = File(filterCacheDir, "${source.id}.txt")
                val tempFile = File(filterCacheDir, "${source.id}.tmp")
                val backupFile = File(filterCacheDir, "${source.id}.bak")

                val downloadResult = runCatching {
                    if (!source.url.startsWith("https://", ignoreCase = true)) {
                        throw SecurityException("Insecure non-HTTPS URL rejected")
                    }
                    val req = Request.Builder()
                        .url(source.url)
                        .header("User-Agent", "AdShieldPro/2.0 (Android; DNS-Filter-Updater)")
                        .build()

                    filterDownloadClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            throw IllegalStateException("HTTP ${resp.code}")
                        }
                        val bodyStr = resp.body?.string() ?: throw IllegalStateException("Empty response")
                        if (bodyStr.length > 15_000_000) {
                            throw IllegalStateException("Filter list exceeds safe memory limit (15 MB)")
                        }
                        val category = runCatching { RuleCategory.valueOf(source.category) }
                            .getOrDefault(RuleCategory.ADS)
                        val parsed = filterEngine.parseFilterListContent(bodyStr, category, source.name)
                            .getOrThrow()

                        if (parsed.totalValidRules == 0) {
                            throw IllegalStateException("Zero valid domain rules found in source")
                        }

                        // Write to temp file first
                        tempFile.writeText(bodyStr, Charsets.UTF_8)
                        // Backup previous cache file
                        if (cacheFile.exists()) {
                            cacheFile.copyTo(backupFile, overwrite = true)
                        }
                        tempFile.copyTo(cacheFile, overwrite = true)
                        tempFile.delete()

                        val hash = sha256Hex(bodyStr).take(16)
                        dao.upsertFilterSource(
                            source.copy(
                                ruleCount = parsed.totalValidRules,
                                duplicateCount = parsed.duplicateCount,
                                lastUpdatedEpochMs = now,
                                lastStatusMessage = "Updated (${parsed.totalValidRules} rules)",
                                etagOrHash = hash
                            )
                        )
                        updatedSourcesCount++
                    }
                }

                if (downloadResult.isFailure) {
                    tempFile.delete()
                    // Rollback from .bak if cacheFile was corrupted
                    if (!cacheFile.exists() && backupFile.exists()) {
                        backupFile.copyTo(cacheFile, overwrite = true)
                    }
                    val errMsg = downloadResult.exceptionOrNull()?.message ?: "Network error"
                    val fallbackNote = if (cacheFile.exists()) {
                        "Cached rules active (Update failed: $errMsg)"
                    } else {
                        "Bundled fallback active ($errMsg)"
                    }
                    dao.upsertFilterSource(
                        source.copy(
                            lastStatusMessage = fallbackNote
                        )
                    )
                }
            }

            // Atomically rebuild and swap engine snapshot; rollback to previousSnapshot if anything fails
            val rebuildRes = runCatching {
                val refreshedSources = dao.getAllFilterSourcesNow()
                rebuildActiveFilterListsLocked(refreshedSources)
            }
            if (rebuildRes.isFailure) {
                filterEngine.restoreSnapshot(previousSnapshot)
                return@withLock Result.failure(
                    rebuildRes.exceptionOrNull() ?: IllegalStateException("Engine swap rolled back")
                )
            }

            prefs.recordFilterUpdateTimestamp(now)
            refreshRuntimeRuleCount()
            Result.success(filterEngine.getCurrentSnapshot().totalLoadedRules)
        }
    }

    private fun rebuildActiveFilterListsLocked(sources: List<FilterSourceEntity>) {
        val mergedSubdomains = HashMap<String, FilterRuleEngine.FilterListEntry>(4096)
        val mergedExact = HashMap<String, FilterRuleEngine.FilterListEntry>()
        val mergedWildcards = ArrayList<FilterRuleEngine.WildcardEntry>()
        val mergedAllow = HashSet<String>()
        val now = System.currentTimeMillis()

        // Always load bundled baseline if enabled or if no other source has cached rules yet
        for (source in sources) {
            if (!source.isEnabled) continue
            val category = runCatching { RuleCategory.valueOf(source.category) }.getOrDefault(RuleCategory.ADS)

            val rawContent: String? = if (source.url.startsWith("asset://")) {
                readBundledDefaultBlocklist()
            } else {
                val cachedFile = File(filterCacheDir, "${source.id}.txt")
                if (cachedFile.exists()) runCatching { cachedFile.readText(Charsets.UTF_8) }.getOrNull() else null
            }

            if (!rawContent.isNullOrBlank()) {
                val parsed = filterEngine.parseFilterListContent(rawContent, category, source.name).getOrNull()
                if (parsed != null) {
                    for ((k, v) in parsed.subdomainRules) {
                        mergedSubdomains.putIfAbsent(k, v)
                    }
                    for ((k, v) in parsed.exactRules) {
                        mergedExact.putIfAbsent(k, v)
                    }
                    mergedWildcards.addAll(parsed.wildcardRules)
                    mergedAllow.addAll(parsed.allowSubdomains)

                    if (source.ruleCount == 0) {
                        repositoryScope.launch {
                            dao.upsertFilterSource(
                                source.copy(
                                    ruleCount = parsed.totalValidRules,
                                    duplicateCount = parsed.duplicateCount,
                                    lastUpdatedEpochMs = if (source.lastUpdatedEpochMs == 0L) now else source.lastUpdatedEpochMs
                                )
                            )
                        }
                    }
                }
            }
        }

        filterEngine.updateFilterListRules(
            subdomainRules = mergedSubdomains,
            exactRules = mergedExact,
            wildcardRules = mergedWildcards,
            allowSubdomains = mergedAllow
        )
    }

    private fun readBundledDefaultBlocklist(): String {
        return runCatching {
            context.assets.open("default_blocklist.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrDefault("")
    }

    // --- Backup and Restore ---

    @Serializable
    data class FullConfigurationBackup(
        val version: Int = 2,
        val exportedAtEpochMs: Long = System.currentTimeMillis(),
        val protectionMode: ProtectionMode,
        val customRules: List<CustomRule>,
        val browserSiteExceptions: Set<String>,
        val excludedAppPackages: Set<String>
    )

    suspend fun createConfigurationBackupJson(): String = withContext(Dispatchers.IO) {
        val settings = prefs.getSettingsSnapshot()
        val rules = dao.getAllCustomRulesNow().map { it.toDomain() }
        val backup = FullConfigurationBackup(
            protectionMode = settings.protectionMode,
            customRules = rules,
            browserSiteExceptions = settings.browserSiteExceptions,
            excludedAppPackages = settings.excludedAppPackages
        )
        jsonSerializer.encodeToString(backup)
    }

    suspend fun restoreConfigurationFromJson(rawJson: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val backup = jsonSerializer.decodeFromString<FullConfigurationBackup>(rawJson)
            prefs.setProtectionMode(backup.protectionMode)
            filterEngine.updateProtectionMode(backup.protectionMode)

            val validEntities = backup.customRules.mapNotNull { rule ->
                filterEngine.validateAndParseRuleInput(
                    rawInput = rule.rawPattern,
                    explicitType = rule.ruleType,
                    explicitMatchType = rule.matchType,
                    comment = rule.comment
                ).getOrNull()?.copy(isEnabled = rule.isEnabled)?.toEntity()
            }

            dao.clearAllCustomRules()
            dao.insertCustomRules(validEntities)
            syncCustomRulesToEngine()

            for (site in backup.browserSiteExceptions) {
                prefs.toggleBrowserSiteException(site, true)
            }
            for (pkg in backup.excludedAppPackages) {
                prefs.toggleExcludedAppPackage(pkg, true)
            }
            validEntities.size
        }
    }

    // --- Installed Apps & Per-App VPN Exclusions ---

    suspend fun getInstalledApps(excludedPackages: Set<String>): List<AppExclusionInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val apps = runCatching {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        }.getOrDefault(emptyList())

        apps
            .filter { appInfo ->
                appInfo.packageName != context.packageName &&
                    (pm.getLaunchIntentForPackage(appInfo.packageName) != null ||
                        (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0)
            }
            .map { appInfo ->
                val label = runCatching { pm.getApplicationLabel(appInfo).toString() }
                    .getOrDefault(appInfo.packageName)
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val isFirstPartyMux = appInfo.packageName in FIRST_PARTY_MULTIPLEXED_AD_PACKAGES
                val note = if (isFirstPartyMux) {
                    "First-party multiplexed stream: In-stream video/feed ads are served from the same domain as primary content and cannot be completely blocked via DNS alone."
                } else null

                AppExclusionInfo(
                    packageName = appInfo.packageName,
                    appName = label,
                    isSystemApp = isSystem,
                    isExcludedFromVpn = excludedPackages.contains(appInfo.packageName),
                    usesFirstPartyMultiplexedAds = isFirstPartyMux,
                    compatibilityNote = note
                )
            }
            .sortedWith(
                compareByDescending<AppExclusionInfo> { it.isExcludedFromVpn }
                    .thenByDescending { it.usesFirstPartyMultiplexedAds }
                    .thenBy { it.appName.lowercase() }
            )
    }

    private fun buildDefaultFilterSources(): List<FilterSourceEntity> {
        val now = System.currentTimeMillis()
        return listOf(
            FilterSourceEntity(
                id = "bundled_adshield_core",
                name = "AdShield Core Multi-Layer List",
                description = "Bundled offline-ready baseline covering major mobile ad SDKs, trackers, telemetry, and malware.",
                url = "asset://default_blocklist.txt",
                licenseName = "AdShield Pro Open Baseline (GPLv3/MIT Compatible)",
                category = RuleCategory.ADS.name,
                isBuiltIn = true,
                isEnabled = true,
                isPremiumOnly = false,
                ruleCount = 215,
                duplicateCount = 0,
                lastUpdatedEpochMs = now,
                lastStatusMessage = "Bundled & Active"
            ),
            FilterSourceEntity(
                id = "adguard_dns_filter",
                name = "AdGuard DNS Filter",
                description = "Comprehensive ad and tracker domain filter maintained by the AdGuard team.",
                url = "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt",
                licenseName = "GNU GPLv3",
                category = RuleCategory.ADS.name,
                isBuiltIn = true,
                isEnabled = true,
                isPremiumOnly = false,
                ruleCount = 0,
                duplicateCount = 0,
                lastUpdatedEpochMs = 0L,
                lastStatusMessage = "Tap Sync Now to download latest rules"
            ),
            FilterSourceEntity(
                id = "oisd_small",
                name = "OISD Small Blocklist",
                description = "Curated low-false-positive domain list engineered to prevent app breakage.",
                url = "https://small.oisd.nl/domainswild2",
                licenseName = "OISD Attribution License",
                category = RuleCategory.ADS.name,
                isBuiltIn = true,
                isEnabled = true,
                isPremiumOnly = false,
                ruleCount = 0,
                duplicateCount = 0,
                lastUpdatedEpochMs = 0L,
                lastStatusMessage = "Tap Sync Now to download latest rules"
            ),
            FilterSourceEntity(
                id = "hagezi_multi_light",
                name = "HaGeZi Multi LIGHT DNS",
                description = "Balanced multi-source intelligence blocking ads, affiliate trackers, and telemetry.",
                url = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/light.txt",
                licenseName = "GNU GPLv3",
                category = RuleCategory.TRACKERS.name,
                isBuiltIn = true,
                isEnabled = true,
                isPremiumOnly = false,
                ruleCount = 0,
                duplicateCount = 0,
                lastUpdatedEpochMs = 0L,
                lastStatusMessage = "Tap Sync Now to download latest rules"
            ),
            FilterSourceEntity(
                id = "urlhaus_malware",
                name = "URLHaus Malware & Phishing Filter",
                description = "Blocks active malware distribution, command-and-control, and phishing domains.",
                url = "https://malware-filter.gitlab.io/malware-filter/urlhaus-filter-domains-online.txt",
                licenseName = "CC0 1.0 Public Domain",
                category = RuleCategory.MALWARE.name,
                isBuiltIn = true,
                isEnabled = true,
                isPremiumOnly = false,
                ruleCount = 0,
                duplicateCount = 0,
                lastUpdatedEpochMs = 0L,
                lastStatusMessage = "Tap Sync Now to download latest rules"
            ),
            FilterSourceEntity(
                id = "stevenblack_hosts",
                name = "StevenBlack Unified Hosts",
                description = "Consolidated community hosts file targeting adware and mobile analytics.",
                url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
                licenseName = "MIT License",
                category = RuleCategory.STRICT_TELEMETRY.name,
                isBuiltIn = true,
                isEnabled = true,
                isPremiumOnly = false,
                ruleCount = 0,
                duplicateCount = 0,
                lastUpdatedEpochMs = 0L,
                lastStatusMessage = "Tap Sync Now to download latest rules"
            )
        )
    }

    private fun CustomRuleEntity.toDomain() = CustomRule(
        id = id,
        rawPattern = rawPattern,
        normalizedPattern = normalizedPattern,
        ruleType = runCatching { RuleType.valueOf(ruleType) }.getOrDefault(RuleType.BLOCK),
        matchType = runCatching { RuleMatchType.valueOf(matchType) }.getOrDefault(RuleMatchType.SUBDOMAIN),
        category = runCatching { RuleCategory.valueOf(category) }.getOrDefault(RuleCategory.CUSTOM),
        priority = priority,
        isEnabled = isEnabled,
        comment = comment,
        createdAtEpochMs = createdAtEpochMs
    )

    private fun CustomRule.toEntity() = CustomRuleEntity(
        id = id,
        rawPattern = rawPattern,
        normalizedPattern = normalizedPattern,
        ruleType = ruleType.name,
        matchType = matchType.name,
        category = category.name,
        priority = priority,
        isEnabled = isEnabled,
        comment = comment,
        createdAtEpochMs = createdAtEpochMs
    )

    private fun FilterSourceEntity.toDomain() = FilterListSource(
        id = id,
        name = name,
        description = description,
        url = url,
        licenseName = licenseName,
        category = runCatching { RuleCategory.valueOf(category) }.getOrDefault(RuleCategory.ADS),
        isBuiltIn = isBuiltIn,
        isEnabled = isEnabled,
        isPremiumOnly = isPremiumOnly,
        ruleCount = ruleCount,
        duplicateCount = duplicateCount,
        lastUpdatedEpochMs = lastUpdatedEpochMs,
        lastStatusMessage = lastStatusMessage,
        etagOrHash = etagOrHash
    )

    private fun FilterListSource.toEntity() = FilterSourceEntity(
        id = id,
        name = name,
        description = description,
        url = url,
        licenseName = licenseName,
        category = category.name,
        isBuiltIn = isBuiltIn,
        isEnabled = isEnabled,
        isPremiumOnly = isPremiumOnly,
        ruleCount = ruleCount,
        duplicateCount = duplicateCount,
        lastUpdatedEpochMs = lastUpdatedEpochMs,
        lastStatusMessage = lastStatusMessage,
        etagOrHash = etagOrHash
    )

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val MILLIS_PER_DAY = 86_400_000L

        val FIRST_PARTY_MULTIPLEXED_AD_PACKAGES = setOf(
            "com.google.android.youtube",
            "com.google.android.apps.youtube.music",
            "com.facebook.katana",
            "com.facebook.lite",
            "com.instagram.android",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "tv.twitch.android.app",
            "com.netflix.mediaclient",
            "com.spotify.music"
        )
    }
}
