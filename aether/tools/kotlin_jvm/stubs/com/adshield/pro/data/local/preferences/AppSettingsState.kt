package com.adshield.pro.data.local.preferences
data class AppSettingsState(
    val aetherEnabled: Boolean = true,
    val aetherAutoBlockConfirmed: Boolean = true,
    val aetherPrivacyLevel: String = "balanced",
    val aetherExplainLevel: String = "beginner"
)
class UserPreferencesRepository {
    suspend fun updateAetherSettings(
        enabled: Boolean, autoBlockConfirmed: Boolean,
        privacyLevel: String, explanationLevel: String
    ) { }
}
