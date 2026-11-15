package com.adshield.pro.domain.model

import kotlinx.serialization.Serializable

/**
 * Android system Private DNS modes mapped to `Settings.Global` (`private_dns_mode`).
 */
@Serializable
enum class PrivateDnsMode(val globalSettingValue: String, val label: String) {
    OFF("off", "Off"),
    AUTO("opportunistic", "Auto"),
    HOSTNAME("hostname", "Private DNS provider");

    companion object {
        fun fromGlobalSetting(raw: String?): PrivateDnsMode = when (raw?.trim()?.lowercase()) {
            "off" -> OFF
            "hostname" -> HOSTNAME
            "opportunistic", null, "" -> AUTO
            else -> AUTO
        }
    }
}

/**
 * One selectable Private DNS hostname option for the Quick Settings tile cycle and direct
 * quick-apply controls.
 */
@Serializable
data class PrivateDnsHostnameOption(
    val id: String,
    val label: String,
    val hostname: String,
    val enabledInCycle: Boolean = true,
    val isBuiltIn: Boolean = false
)

/**
 * One step in the Private DNS Quick Settings tile cycle.
 */
sealed class PrivateDnsCycleStep {
    data object Off : PrivateDnsCycleStep()
    data object Auto : PrivateDnsCycleStep()
    data class Hostname(val label: String, val hostname: String) : PrivateDnsCycleStep()
}

/**
 * Live system read-back of Android's Private DNS preference.
 */
data class PrivateDnsSystemState(
    val mode: PrivateDnsMode = PrivateDnsMode.AUTO,
    val specifier: String? = null,
    val readable: Boolean = true
)

/**
 * Live system read-back of Android's USB Debugging (`adb_enabled`) preference.
 */
data class UsbDebuggingSystemState(
    val enabled: Boolean = false,
    val developerOptionsEnabled: Boolean = false,
    val readable: Boolean = true
)

/**
 * User configuration and active auto-revert state for the Private DNS Quick Settings tile.
 */
@Serializable
data class PrivateDnsTileConfig(
    val includeOffInCycle: Boolean = true,
    val includeAutoInCycle: Boolean = true,
    val hostnames: List<PrivateDnsHostnameOption> = DEFAULT_PRIVATE_DNS_HOSTNAMES,
    val requireUnlockToToggle: Boolean = false,
    val autoRevertEnabled: Boolean = false,
    val autoRevertDelayMinutes: Int = 15,
    val autoRevertTargetMode: PrivateDnsMode? = null,
    val autoRevertTargetHostname: String? = null,
    val autoRevertDeadlineEpochMs: Long = 0L
)

/**
 * User configuration and active auto-revert state for the USB Debugging Quick Settings tile.
 */
@Serializable
data class UsbDebuggingTileConfig(
    val autoRevertEnabled: Boolean = false,
    val autoRevertDelayMinutes: Int = 15,
    val requireUnlockToToggle: Boolean = true,
    val autoRevertDeadlineEpochMs: Long = 0L
)

val DEFAULT_PRIVATE_DNS_HOSTNAMES: List<PrivateDnsHostnameOption> = listOf(
    PrivateDnsHostnameOption(
        id = "preset-adguard",
        label = "AdGuard DNS",
        hostname = "dns.adguard-dns.com",
        enabledInCycle = true,
        isBuiltIn = true
    ),
    PrivateDnsHostnameOption(
        id = "preset-cloudflare",
        label = "Cloudflare",
        hostname = "one.one.one.one",
        enabledInCycle = true,
        isBuiltIn = true
    ),
    PrivateDnsHostnameOption(
        id = "preset-quad9",
        label = "Quad9",
        hostname = "dns.quad9.net",
        enabledInCycle = false,
        isBuiltIn = true
    ),
    PrivateDnsHostnameOption(
        id = "preset-google",
        label = "Google",
        hostname = "dns.google",
        enabledInCycle = false,
        isBuiltIn = true
    ),
    PrivateDnsHostnameOption(
        id = "preset-nextdns",
        label = "NextDNS",
        hostname = "dns.nextdns.io",
        enabledInCycle = false,
        isBuiltIn = true
    ),
    PrivateDnsHostnameOption(
        id = "preset-controld",
        label = "Control D",
        hostname = "p0.freedns.controld.com",
        enabledInCycle = false,
        isBuiltIn = true
    )
)

enum class TileVisualState { ACTIVE, INACTIVE, UNAVAILABLE }

/**
 * Pure presentation model mapped onto `android.service.quicksettings.Tile` by the TileServices.
 */
data class TilePresentation(
    val label: String,
    val subtitle: String,
    val state: TileVisualState,
    val contentDescription: String
)

/**
 * Honest outcome of attempting to modify a `Settings.Global` preference.
 */
sealed class SystemPreferenceWriteResult {
    data class Success(val message: String) : SystemPreferenceWriteResult()
    data object PermissionDenied : SystemPreferenceWriteResult()
    data class RejectedBySystem(val reason: String) : SystemPreferenceWriteResult()
}

/**
 * Combined live status rendered by the Quick Settings Tiles configuration screen.
 */
data class SystemTilesLiveStatus(
    val hasWriteSecureSettingsPermission: Boolean = false,
    val privateDnsState: PrivateDnsSystemState = PrivateDnsSystemState(),
    val usbDebuggingState: UsbDebuggingSystemState = UsbDebuggingSystemState(),
    val isRootBinaryDetected: Boolean = false,
    val isShizukuInstalled: Boolean = false,
    val isLadbInstalled: Boolean = false
)

/**
 * Pure summary of the 3-step Quick Setup checklist for enabling Quick Settings tiles.
 */
data class QuickSetupChecklistState(
    val developerOptionsReady: Boolean,
    val permissionGranted: Boolean,
    val tilesReadyToUse: Boolean,
    val rootQuickGrantAvailable: Boolean,
    val shizukuDetected: Boolean
)
