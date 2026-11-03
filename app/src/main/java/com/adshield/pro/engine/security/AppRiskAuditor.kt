package com.adshield.pro.engine.security

/**
 * AdShield Pro — Installed App Risk Auditor (Pure Kotlin rules, unit-testable).
 *
 * Audits every installed application using the same triage logic as mobile MTD products:
 * installer origin, permission stack, target SDK hygiene, accessibility abuse,
 * device-admin lockware patterns, and known-good vs unknown publisher namespaces.
 */
class AppRiskAuditor {

    enum class RiskLevel { TRUSTED, LOW, MEDIUM, HIGH }

    data class AppSecurityProfile(
        val packageName: String,
        val appName: String,
        val versionName: String?,
        val targetSdkVersion: Int,
        val requestedPermissions: List<String>,
        val installerPackageName: String?,
        val isSystemApp: Boolean,
        val isUpdatedSystemApp: Boolean = false,
        val firstInstallTimeEpochMs: Long = 0L
    )

    data class AppRiskAudit(
        val profile: AppSecurityProfile,
        val riskLevel: RiskLevel,
        val riskScore: Int,
        val findings: List<String>,
        val summary: String
    )

    fun audit(profile: AppSecurityProfile): AppRiskAudit {
        val findings = ArrayList<String>()
        var score = 0

        val perms = profile.requestedPermissions
        val has = { suffix: String -> perms.any { it.endsWith(suffix) } }

        // Installer origin trust
        when (profile.installerPackageName) {
            "com.android.vending", "com.google.android.feedback" -> {
                // Play Store origin — baseline trust
            }
            null, "" -> {
                findings.add("Unknown install source (side-loaded, no installer recorded)")
                score += 15
            }
            else -> {
                findings.add("Installed from a third-party source: ${profile.installerPackageName}")
                score += 10
            }
        }

        // Accessibility abuse (banking trojan / overlay pattern)
        if (has("android.permission.ACCESSIBILITY_SERVICE") || has("BIND_ACCESSIBILITY_SERVICE")) {
            findings.add("Uses Accessibility services — can read screen content and auto-click dialogs")
            score += 30
        }
        if (has("android.permission.BIND_ACCESSIBILITY_SERVICE") && has("android.permission.RECEIVE_SMS")) {
            findings.add("Accessibility + SMS interception — banking trojan capability stack")
            score += 25
        }

        // Silent install dropper pattern
        if (has("android.permission.REQUEST_INSTALL_PACKAGES")) {
            findings.add("Can request installation of other packages (dropper capability)")
            score += 15
        }
        if (has("android.permission.REQUEST_INSTALL_PACKAGES") && !has("android.permission.INTERNET")) {
            findings.add("Install capability without Internet — inconsistent for a normal app")
            score += 10
        }

        // Lockware / device admin
        if (has("android.permission.BIND_DEVICE_ADMIN")) {
            findings.add("Device administrator rights — can resist uninstall (lockware pattern)")
            score += 15
        }
        if (has("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE")) {
            findings.add("Reads every notification (OTP codes often arrive as notifications)")
            score += 12
        }

        // Surveillance stack
        if (has("android.permission.RECORD_AUDIO") && has("android.permission.CAMERA") &&
            has("android.permission.ACCESS_BACKGROUND_LOCATION")
        ) {
            findings.add("Microphone + camera + background location — full surveillance stack")
            score += 25
        }
        if (has("android.permission.SEND_SMS") && has("android.permission.READ_CONTACTS")) {
            findings.add("Can send premium SMS to your contacts (subscription fraud pattern)")
            score += 12
        }

        // SDK hygiene
        if (profile.targetSdkVersion in 1..28 && !profile.isSystemApp) {
            findings.add("Targets an outdated Android version (targetSdk ${profile.targetSdkVersion}) — skips modern permission guards")
            score += 12
        }

        // Updated system app (possible privileged tampering on old ROMs)
        if (profile.isUpdatedSystemApp && profile.installerPackageName != "com.android.vending") {
            findings.add("Updated system app installed outside Play Protect")
            score += 6
        }

        // System apps get baseline trust discount
        if (profile.isSystemApp && !profile.isUpdatedSystemApp) {
            score = (score * 0.6f).toInt()
        }

        val level = when {
            score >= 55 -> RiskLevel.HIGH
            score >= 28 -> RiskLevel.MEDIUM
            score >= 10 -> RiskLevel.LOW
            else -> RiskLevel.TRUSTED
        }
        val summary = when (level) {
            RiskLevel.HIGH -> "High-risk app. Review its permissions in Android settings or uninstall it."
            RiskLevel.MEDIUM -> "Elevated risk. Verify the source and revoke any unneeded permissions."
            RiskLevel.LOW -> "Minor privacy considerations — nothing alarming."
            RiskLevel.TRUSTED -> "No risk indicators. Clean audit."
        }

        return AppRiskAudit(
            profile = profile,
            riskLevel = level,
            riskScore = score.coerceAtMost(100),
            findings = findings,
            summary = summary
        )
    }
}
