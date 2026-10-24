package com.adshield.pro.data.local.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.adshield.pro.domain.model.BatteryMode
import com.adshield.pro.domain.model.CleanHistoryRecord
import com.adshield.pro.domain.model.DnsProtocol
import com.adshield.pro.domain.model.DnsProviderPreset
import com.adshield.pro.domain.model.DnsResolverConfig
import com.adshield.pro.domain.model.GameSessionRecord
import com.adshield.pro.domain.model.GamingProfile
import com.adshield.pro.domain.model.ProtectionMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.adShieldDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "adshield_pro_preferences"
)

data class AppSettingsState(
    val vpnShouldRemainActive: Boolean = false,
    val autoStartOnBoot: Boolean = true,
    val darkThemeMode: DarkThemePreference = DarkThemePreference.DARK,
    val protectionMode: ProtectionMode = ProtectionMode.STANDARD,
    val resolverConfig: DnsResolverConfig = DnsResolverConfig(),
    val detailedDnsLoggingEnabled: Boolean = true,
    val logRetentionDays: Int = 7,
    val autoUpdateFiltersEnabled: Boolean = true,
    val updateIntervalHours: Int = 24,
    val lastFilterUpdateEpochMs: Long = 0L,
    val excludedAppPackages: Set<String> = emptySet(),
    val browserSiteExceptions: Set<String> = emptySet(),
    val browserCosmeticFilteringEnabled: Boolean = true,
    val browserPopupBlockingEnabled: Boolean = true,
    val browserBlockThirdPartyCookies: Boolean = true,
    val browserJavaScriptEnabled: Boolean = true,
    val customCosmeticSelectors: Set<String> = emptySet(),
    // --- Phone Optimizer & AI Smart Boost Settings ---
    val smartAutoOptimizeEnabled: Boolean = true,
    val autoJunkCleanIntervalHours: Int = 24,
    val excludedJunkPaths: Set<String> = emptySet(),
    val totalRecoveredBytesEver: Long = 0L,
    val cleanHistoryRecords: List<CleanHistoryRecord> = emptyList(),
    val favoriteGamePackages: Set<String> = emptySet(),
    val gamingProfile: GamingProfile = GamingProfile.BALANCED,
    val gameBoosterSuppressNotifications: Boolean = false,
    val gameBoosterTempWarningCelsius: Int = 41,
    val gameSessionRecords: List<GameSessionRecord> = emptyList(),
    val batteryMode: BatteryMode = BatteryMode.SMART_AUTOMATIC,
    val lastSmartBoostEpochMs: Long = 0L
)

enum class DarkThemePreference(val label: String) {
    DARK("Dark (Default)"),
    LIGHT("Light"),
    SYSTEM("System")
}

@Singleton
class UserPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private object Keys {
        val VPN_ACTIVE = booleanPreferencesKey("vpn_should_remain_active")
        val AUTO_START_BOOT = booleanPreferencesKey("auto_start_on_boot")
        val DARK_THEME = stringPreferencesKey("dark_theme_mode")
        val PROTECTION_MODE = stringPreferencesKey("protection_mode")
        val DNS_PRESET = stringPreferencesKey("dns_preset")
        val DNS_PROTOCOL = stringPreferencesKey("dns_protocol")
        val CUSTOM_DOH_URL = stringPreferencesKey("custom_doh_url")
        val CUSTOM_DOT_HOST = stringPreferencesKey("custom_dot_host")
        val CUSTOM_IPV4_PRIMARY = stringPreferencesKey("custom_ipv4_primary")
        val CUSTOM_IPV4_SECONDARY = stringPreferencesKey("custom_ipv4_secondary")
        val ENABLE_IPV6 = booleanPreferencesKey("enable_ipv6")
        val DETAILED_LOGGING = booleanPreferencesKey("detailed_dns_logging_enabled")
        val LOG_RETENTION_DAYS = intPreferencesKey("log_retention_days")
        val AUTO_UPDATE_FILTERS = booleanPreferencesKey("auto_update_filters")
        val UPDATE_INTERVAL_HOURS = intPreferencesKey("update_interval_hours")
        val LAST_FILTER_UPDATE_MS = longPreferencesKey("last_filter_update_ms")
        val EXCLUDED_PACKAGES = stringSetPreferencesKey("excluded_app_packages")
        val BROWSER_EXCEPTIONS = stringSetPreferencesKey("browser_site_exceptions")
        val BROWSER_COSMETIC = booleanPreferencesKey("browser_cosmetic_filtering")
        val BROWSER_POPUP_BLOCK = booleanPreferencesKey("browser_popup_blocking")
        val BROWSER_BLOCK_3P_COOKIES = booleanPreferencesKey("browser_block_3p_cookies")
        val BROWSER_JS_ENABLED = booleanPreferencesKey("browser_js_enabled")
        val CUSTOM_COSMETIC_SELECTORS = stringSetPreferencesKey("custom_cosmetic_selectors")

        // Optimizer & Smart Boost keys
        val SMART_AUTO_OPTIMIZE = booleanPreferencesKey("smart_auto_optimize_enabled")
        val AUTO_JUNK_CLEAN_HOURS = intPreferencesKey("auto_junk_clean_interval_hours")
        val EXCLUDED_JUNK_PATHS = stringSetPreferencesKey("excluded_junk_paths")
        val TOTAL_RECOVERED_BYTES_EVER = longPreferencesKey("total_recovered_bytes_ever")
        val CLEAN_HISTORY_JSON = stringPreferencesKey("clean_history_records_json")
        val FAVORITE_GAME_PACKAGES = stringSetPreferencesKey("favorite_game_packages")
        val GAMING_PROFILE = stringPreferencesKey("gaming_profile")
        val GAME_SUPPRESS_NOTIF = booleanPreferencesKey("game_suppress_notifications")
        val GAME_TEMP_WARNING_C = intPreferencesKey("game_temp_warning_celsius")
        val GAME_SESSIONS_JSON = stringPreferencesKey("game_sessions_json")
        val BATTERY_MODE = stringPreferencesKey("battery_mode")
        val LAST_SMART_BOOST_MS = longPreferencesKey("last_smart_boost_epoch_ms")
    }

    val settingsFlow: Flow<AppSettingsState> = context.adShieldDataStore.data.map { prefs ->
        val preset = runCatching {
            DnsProviderPreset.valueOf(prefs[Keys.DNS_PRESET] ?: DnsProviderPreset.CLOUDFLARE.name)
        }.getOrDefault(DnsProviderPreset.CLOUDFLARE)

        val protocol = runCatching {
            DnsProtocol.valueOf(prefs[Keys.DNS_PROTOCOL] ?: DnsProtocol.DOH.name)
        }.getOrDefault(DnsProtocol.DOH)

        val mode = runCatching {
            ProtectionMode.valueOf(prefs[Keys.PROTECTION_MODE] ?: ProtectionMode.STANDARD.name)
        }.getOrDefault(ProtectionMode.STANDARD)

        val theme = runCatching {
            DarkThemePreference.valueOf(prefs[Keys.DARK_THEME] ?: DarkThemePreference.DARK.name)
        }.getOrDefault(DarkThemePreference.DARK)

        val gamingProfile = runCatching {
            GamingProfile.valueOf(prefs[Keys.GAMING_PROFILE] ?: GamingProfile.BALANCED.name)
        }.getOrDefault(GamingProfile.BALANCED)

        val batteryMode = runCatching {
            BatteryMode.valueOf(prefs[Keys.BATTERY_MODE] ?: BatteryMode.SMART_AUTOMATIC.name)
        }.getOrDefault(BatteryMode.SMART_AUTOMATIC)

        val cleanHistory = prefs[Keys.CLEAN_HISTORY_JSON]?.let { raw ->
            runCatching { json.decodeFromString<List<CleanHistoryRecord>>(raw) }.getOrDefault(emptyList())
        } ?: emptyList()

        val gameSessions = prefs[Keys.GAME_SESSIONS_JSON]?.let { raw ->
            runCatching { json.decodeFromString<List<GameSessionRecord>>(raw) }.getOrDefault(emptyList())
        } ?: emptyList()

        AppSettingsState(
            vpnShouldRemainActive = prefs[Keys.VPN_ACTIVE] ?: false,
            autoStartOnBoot = prefs[Keys.AUTO_START_BOOT] ?: true,
            darkThemeMode = theme,
            protectionMode = mode,
            resolverConfig = DnsResolverConfig(
                preset = preset,
                protocol = protocol,
                customDohUrl = prefs[Keys.CUSTOM_DOH_URL] ?: "https://cloudflare-dns.com/dns-query",
                customDotHost = prefs[Keys.CUSTOM_DOT_HOST] ?: "one.one.one.one",
                customPrimaryIpv4 = prefs[Keys.CUSTOM_IPV4_PRIMARY] ?: "1.1.1.1",
                customSecondaryIpv4 = prefs[Keys.CUSTOM_IPV4_SECONDARY] ?: "1.0.0.1",
                enableIpv6 = prefs[Keys.ENABLE_IPV6] ?: true
            ),
            detailedDnsLoggingEnabled = prefs[Keys.DETAILED_LOGGING] ?: true,
            logRetentionDays = prefs[Keys.LOG_RETENTION_DAYS] ?: 7,
            autoUpdateFiltersEnabled = prefs[Keys.AUTO_UPDATE_FILTERS] ?: true,
            updateIntervalHours = prefs[Keys.UPDATE_INTERVAL_HOURS] ?: 24,
            lastFilterUpdateEpochMs = prefs[Keys.LAST_FILTER_UPDATE_MS] ?: 0L,
            excludedAppPackages = prefs[Keys.EXCLUDED_PACKAGES] ?: emptySet(),
            browserSiteExceptions = prefs[Keys.BROWSER_EXCEPTIONS] ?: emptySet(),
            browserCosmeticFilteringEnabled = prefs[Keys.BROWSER_COSMETIC] ?: true,
            browserPopupBlockingEnabled = prefs[Keys.BROWSER_POPUP_BLOCK] ?: true,
            browserBlockThirdPartyCookies = prefs[Keys.BROWSER_BLOCK_3P_COOKIES] ?: true,
            browserJavaScriptEnabled = prefs[Keys.BROWSER_JS_ENABLED] ?: true,
            customCosmeticSelectors = prefs[Keys.CUSTOM_COSMETIC_SELECTORS] ?: emptySet(),
            smartAutoOptimizeEnabled = prefs[Keys.SMART_AUTO_OPTIMIZE] ?: true,
            autoJunkCleanIntervalHours = prefs[Keys.AUTO_JUNK_CLEAN_HOURS] ?: 24,
            excludedJunkPaths = prefs[Keys.EXCLUDED_JUNK_PATHS] ?: emptySet(),
            totalRecoveredBytesEver = prefs[Keys.TOTAL_RECOVERED_BYTES_EVER] ?: 0L,
            cleanHistoryRecords = cleanHistory,
            favoriteGamePackages = prefs[Keys.FAVORITE_GAME_PACKAGES] ?: emptySet(),
            gamingProfile = gamingProfile,
            gameBoosterSuppressNotifications = prefs[Keys.GAME_SUPPRESS_NOTIF] ?: false,
            gameBoosterTempWarningCelsius = prefs[Keys.GAME_TEMP_WARNING_C] ?: 41,
            gameSessionRecords = gameSessions,
            batteryMode = batteryMode,
            lastSmartBoostEpochMs = prefs[Keys.LAST_SMART_BOOST_MS] ?: 0L
        )
    }

    suspend fun getSettingsSnapshot(): AppSettingsState = settingsFlow.first()

    suspend fun setVpnShouldRemainActive(active: Boolean) {
        context.adShieldDataStore.edit { it[Keys.VPN_ACTIVE] = active }
    }

    suspend fun setAutoStartOnBoot(enabled: Boolean) {
        context.adShieldDataStore.edit { it[Keys.AUTO_START_BOOT] = enabled }
    }

    suspend fun setDarkThemePreference(pref: DarkThemePreference) {
        context.adShieldDataStore.edit { it[Keys.DARK_THEME] = pref.name }
    }

    suspend fun setProtectionMode(mode: ProtectionMode) {
        context.adShieldDataStore.edit { it[Keys.PROTECTION_MODE] = mode.name }
    }

    suspend fun updateResolverConfig(config: DnsResolverConfig) {
        context.adShieldDataStore.edit { prefs ->
            prefs[Keys.DNS_PRESET] = config.preset.name
            prefs[Keys.DNS_PROTOCOL] = config.protocol.name
            prefs[Keys.CUSTOM_DOH_URL] = config.customDohUrl.trim()
            prefs[Keys.CUSTOM_DOT_HOST] = config.customDotHost.trim()
            prefs[Keys.CUSTOM_IPV4_PRIMARY] = config.customPrimaryIpv4.trim()
            prefs[Keys.CUSTOM_IPV4_SECONDARY] = config.customSecondaryIpv4.trim()
            prefs[Keys.ENABLE_IPV6] = config.enableIpv6
        }
    }

    suspend fun setDetailedDnsLogging(enabled: Boolean) {
        context.adShieldDataStore.edit { it[Keys.DETAILED_LOGGING] = enabled }
    }

    suspend fun setLogRetentionDays(days: Int) {
        context.adShieldDataStore.edit { it[Keys.LOG_RETENTION_DAYS] = days.coerceIn(1, 90) }
    }

    suspend fun setAutoUpdateFilters(enabled: Boolean, intervalHours: Int) {
        context.adShieldDataStore.edit {
            it[Keys.AUTO_UPDATE_FILTERS] = enabled
            it[Keys.UPDATE_INTERVAL_HOURS] = intervalHours.coerceIn(6, 168)
        }
    }

    suspend fun recordFilterUpdateTimestamp(epochMs: Long) {
        context.adShieldDataStore.edit { it[Keys.LAST_FILTER_UPDATE_MS] = epochMs }
    }

    suspend fun toggleExcludedAppPackage(packageName: String, excluded: Boolean) {
        context.adShieldDataStore.edit { prefs ->
            val current = prefs[Keys.EXCLUDED_PACKAGES]?.toMutableSet() ?: mutableSetOf()
            if (excluded) current.add(packageName) else current.remove(packageName)
            prefs[Keys.EXCLUDED_PACKAGES] = current
        }
    }

    suspend fun toggleBrowserSiteException(host: String, allowlisted: Boolean) {
        val normalized = host.trim().lowercase().removePrefix("www.")
        if (normalized.isBlank()) return
        context.adShieldDataStore.edit { prefs ->
            val current = prefs[Keys.BROWSER_EXCEPTIONS]?.toMutableSet() ?: mutableSetOf()
            if (allowlisted) current.add(normalized) else current.remove(normalized)
            prefs[Keys.BROWSER_EXCEPTIONS] = current
        }
    }

    suspend fun updateBrowserPrivacyOptions(
        cosmeticFiltering: Boolean,
        popupBlocking: Boolean,
        blockThirdPartyCookies: Boolean,
        javaScriptEnabled: Boolean
    ) {
        context.adShieldDataStore.edit { prefs ->
            prefs[Keys.BROWSER_COSMETIC] = cosmeticFiltering
            prefs[Keys.BROWSER_POPUP_BLOCK] = popupBlocking
            prefs[Keys.BROWSER_BLOCK_3P_COOKIES] = blockThirdPartyCookies
            prefs[Keys.BROWSER_JS_ENABLED] = javaScriptEnabled
        }
    }

    suspend fun addCustomCosmeticSelector(selector: String) {
        val trimmed = selector.trim()
        if (trimmed.isBlank()) return
        context.adShieldDataStore.edit { prefs ->
            val current = prefs[Keys.CUSTOM_COSMETIC_SELECTORS]?.toMutableSet() ?: mutableSetOf()
            current.add(trimmed)
            prefs[Keys.CUSTOM_COSMETIC_SELECTORS] = current
        }
    }

    suspend fun removeCustomCosmeticSelector(selector: String) {
        context.adShieldDataStore.edit { prefs ->
            val current = prefs[Keys.CUSTOM_COSMETIC_SELECTORS]?.toMutableSet() ?: mutableSetOf()
            current.remove(selector)
            prefs[Keys.CUSTOM_COSMETIC_SELECTORS] = current
        }
    }

    // --- Optimizer Persistence Methods ---

    suspend fun setSmartAutoOptimizeSchedule(enabled: Boolean, intervalHours: Int) {
        context.adShieldDataStore.edit { prefs ->
            prefs[Keys.SMART_AUTO_OPTIMIZE] = enabled
            prefs[Keys.AUTO_JUNK_CLEAN_HOURS] = intervalHours.coerceIn(6, 168)
        }
    }

    suspend fun addExcludedJunkPath(pathPattern: String) {
        val trimmed = pathPattern.trim()
        if (trimmed.isBlank()) return
        context.adShieldDataStore.edit { prefs ->
            val current = prefs[Keys.EXCLUDED_JUNK_PATHS]?.toMutableSet() ?: mutableSetOf()
            current.add(trimmed)
            prefs[Keys.EXCLUDED_JUNK_PATHS] = current
        }
    }

    suspend fun removeExcludedJunkPath(pathPattern: String) {
        context.adShieldDataStore.edit { prefs ->
            val current = prefs[Keys.EXCLUDED_JUNK_PATHS]?.toMutableSet() ?: mutableSetOf()
            current.remove(pathPattern)
            prefs[Keys.EXCLUDED_JUNK_PATHS] = current
        }
    }

    suspend fun recordCleanHistory(record: CleanHistoryRecord) {
        context.adShieldDataStore.edit { prefs ->
            val prevTotal = prefs[Keys.TOTAL_RECOVERED_BYTES_EVER] ?: 0L
            prefs[Keys.TOTAL_RECOVERED_BYTES_EVER] = prevTotal + record.bytesRecovered.coerceAtLeast(0L)

            val existing = prefs[Keys.CLEAN_HISTORY_JSON]?.let { raw ->
                runCatching { json.decodeFromString<List<CleanHistoryRecord>>(raw) }.getOrDefault(emptyList())
            } ?: emptyList()
            val updated = (listOf(record) + existing).take(30)
            prefs[Keys.CLEAN_HISTORY_JSON] = json.encodeToString(updated)
        }
    }

    suspend fun recordSmartBoostTimestamp(epochMs: Long) {
        context.adShieldDataStore.edit { prefs ->
            prefs[Keys.LAST_SMART_BOOST_MS] = epochMs
        }
    }

    suspend fun toggleFavoriteGame(packageName: String, isFavorite: Boolean) {
        context.adShieldDataStore.edit { prefs ->
            val current = prefs[Keys.FAVORITE_GAME_PACKAGES]?.toMutableSet() ?: mutableSetOf()
            if (isFavorite) current.add(packageName) else current.remove(packageName)
            prefs[Keys.FAVORITE_GAME_PACKAGES] = current
        }
    }

    suspend fun updateGamingBoosterSettings(
        profile: GamingProfile,
        suppressNotifications: Boolean,
        tempWarningCelsius: Int
    ) {
        context.adShieldDataStore.edit { prefs ->
            prefs[Keys.GAMING_PROFILE] = profile.name
            prefs[Keys.GAME_SUPPRESS_NOTIF] = suppressNotifications
            prefs[Keys.GAME_TEMP_WARNING_C] = tempWarningCelsius.coerceIn(36, 50)
        }
    }

    suspend fun recordGameSession(session: GameSessionRecord) {
        context.adShieldDataStore.edit { prefs ->
            val existing = prefs[Keys.GAME_SESSIONS_JSON]?.let { raw ->
                runCatching { json.decodeFromString<List<GameSessionRecord>>(raw) }.getOrDefault(emptyList())
            } ?: emptyList()
            val updated = (listOf(session) + existing).take(25)
            prefs[Keys.GAME_SESSIONS_JSON] = json.encodeToString(updated)
        }
    }

    suspend fun setBatteryMode(mode: BatteryMode) {
        context.adShieldDataStore.edit { prefs ->
            prefs[Keys.BATTERY_MODE] = mode.name
        }
    }
}
