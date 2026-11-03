package com.adshield.pro.aether

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adshield.pro.data.local.preferences.AppSettingsState
import com.adshield.pro.data.local.preferences.UserPreferencesRepository
import com.adshield.pro.data.repository.AdShieldRepository
import com.adshield.pro.domain.model.DnsActivityRecord
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** UI state for the AETHER AI tab. Reuses the existing Room log + DataStore settings. */
@HiltViewModel
class AetherViewModel @Inject constructor(
    val engine: AetherThreatEngine,
    val policy: AetherPolicy,
    private val preferencesRepository: UserPreferencesRepository,
    private val repository: AdShieldRepository
) : ViewModel() {

    val settings: StateFlow<AppSettingsState> = repository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettingsState())

    val recentLogs: StateFlow<List<DnsActivityRecord>> = repository.recentLogsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun analyze(target: String): AetherVerdict = engine.analyze(target, settings.value, policy)

    fun ask(message: String, level: String): AetherReply = engine.ask(message, level)

    fun updateSettings(
        enabled: Boolean, autoBlockConfirmed: Boolean,
        privacyLevel: String, explanationLevel: String
    ) {
        // Save the previous configuration so a compatibility problem can be rolled back.
        val before = settings.value
        policy.saveSnapshot(
            listOf(
                before.aetherEnabled, before.aetherAutoBlockConfirmed,
                before.aetherPrivacyLevel, before.aetherExplainLevel
            ).joinToString("|")
        )
        viewModelScope.launch {
            preferencesRepository.updateAetherSettings(
                enabled, autoBlockConfirmed, privacyLevel, explanationLevel
            )
        }
    }

    fun rollbackPolicy(): Boolean {
        val snapshot = policy.rollbackSnapshot() ?: return false
        val parts = snapshot.split("|")
        if (parts.size < 4) return false
        viewModelScope.launch {
            preferencesRepository.updateAetherSettings(
                parts[0].toBoolean(), parts[1].toBoolean(), parts[2], parts[3]
            )
        }
        return true
    }

    fun requestTemporaryAllow(
        domain: String, minutes: Int, approver: String, reason: String, phrase: String
    ): Result<AetherAllowEntry> = runCatching {
        policy.approveTemporaryAllow(domain, minutes, approver, reason, phrase)
    }

    fun revokeTemporaryAllow(domain: String) = policy.revoke(domain)

    fun reportFalsePositive(domain: String, note: String) {
        policy.markFalsePositive(domain)
        policy.appendUserAudit("feedback.note domain=$domain note=${note.take(80)}")
    }
}
