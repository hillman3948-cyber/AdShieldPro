package com.adshield.pro.data.repository
import com.adshield.pro.data.local.preferences.AppSettingsState
import com.adshield.pro.domain.model.DnsActivityRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
class AdShieldRepository {
    val settingsFlow: Flow<AppSettingsState> = flowOf(AppSettingsState())
    val recentLogsFlow: Flow<List<DnsActivityRecord>> = flowOf(emptyList())
}
