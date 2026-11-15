package com.adshield.pro.engine.platform

import com.adshield.pro.domain.model.PrivateDnsCycleStep
import com.adshield.pro.domain.model.PrivateDnsHostnameOption
import com.adshield.pro.domain.model.PrivateDnsMode
import com.adshield.pro.domain.model.PrivateDnsSystemState
import com.adshield.pro.domain.model.PrivateDnsTileConfig
import com.adshield.pro.domain.model.QuickSetupChecklistState
import com.adshield.pro.domain.model.TilePresentation
import com.adshield.pro.domain.model.TileVisualState
import com.adshield.pro.domain.model.UsbDebuggingSystemState
import com.adshield.pro.domain.model.UsbDebuggingTileConfig

/**
 * Pure, JVM-testable rules for Quick Settings system-preference tiles:
 *  - Private DNS (Off / Auto / Different Hostnames) cycle building, hostname validation,
 *    next-step transitions, and optional auto-revert back to the prior mode/hostname.
 *  - USB Debugging (On / Off) transitions and optional auto-revert back to Off.
 *  - Tile labels, subtitles, and visual states derived strictly from real system read-backs.
 */
object SystemTileEngine {

    const val MIN_REVERT_DELAY_MINUTES = 1
    const val MAX_REVERT_DELAY_MINUTES = 1440
    val PRESET_REVERT_MINUTES: List<Int> = listOf(5, 10, 15, 30, 60)

    /**
     * Small tolerance so an alarm/worker waking slightly early (Android wall/monotonic skew)
     * executes the scheduled revert rather than leaving it unexecuted.
     */
    const val REVERT_EARLY_TOLERANCE_MS = 5_000L

    private val ipv4Regex = Regex("^\\d+(\\.\\d+){3}$")
    private val labelRegex = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$")

    fun clampDelayMinutes(minutes: Int): Int =
        minutes.coerceIn(MIN_REVERT_DELAY_MINUTES, MAX_REVERT_DELAY_MINUTES)

    fun normalizeHostname(raw: String): String =
        raw.trim().lowercase().trimEnd('.')

    /**
     * Validates a DNS-over-TLS hostname suitable for Android's `private_dns_specifier`.
     * Android Private DNS rejects schemes, ports, paths, whitespace, and bare IP literals.
     */
    fun validatePrivateDnsHostname(raw: String): Result<String> {
        val candidate = normalizeHostname(raw)
        if (candidate.isEmpty()) {
            return Result.failure(IllegalArgumentException("Enter a Private DNS hostname."))
        }
        if (candidate.length > 253) {
            return Result.failure(IllegalArgumentException("Hostname exceeds 253 characters."))
        }
        if (candidate.any { it.isWhitespace() }) {
            return Result.failure(IllegalArgumentException("Hostname must not contain spaces."))
        }
        if ("://" in candidate || '/' in candidate || '?' in candidate || '#' in candidate || '@' in candidate) {
            return Result.failure(
                IllegalArgumentException("Enter only the hostname (for example dns.adguard-dns.com), without https:// or paths.")
            )
        }
        if (':' in candidate) {
            return Result.failure(
                IllegalArgumentException("Enter a DNS hostname without ports or IPv6 literals.")
            )
        }
        if (ipv4Regex.matches(candidate)) {
            return Result.failure(
                IllegalArgumentException("Android Private DNS requires a TLS hostname, not a raw IP address.")
            )
        }
        if (!candidate.contains('.') || candidate.startsWith('.') || candidate.contains("..")) {
            return Result.failure(
                IllegalArgumentException("Enter a valid domain hostname such as one.one.one.one.")
            )
        }
        val labels = candidate.split('.')
        for (label in labels) {
            if (label.isEmpty() || label.length > 63 || !labelRegex.matches(label)) {
                return Result.failure(
                    IllegalArgumentException("Invalid DNS label '$label' in hostname.")
                )
            }
        }
        if (labels.last().all { it.isDigit() }) {
            return Result.failure(
                IllegalArgumentException("Top-level domain must not be numeric.")
            )
        }
        return Result.success(candidate)
    }

    fun buildPrivateDnsCycle(config: PrivateDnsTileConfig): List<PrivateDnsCycleStep> {
        val steps = mutableListOf<PrivateDnsCycleStep>()
        if (config.includeOffInCycle) {
            steps += PrivateDnsCycleStep.Off
        }
        if (config.includeAutoInCycle) {
            steps += PrivateDnsCycleStep.Auto
        }
        val seenHostnames = HashSet<String>()
        for (entry in config.hostnames) {
            if (!entry.enabledInCycle) continue
            val normalized = validatePrivateDnsHostname(entry.hostname).getOrNull() ?: continue
            if (seenHostnames.add(normalized)) {
                val label = entry.label.trim().ifBlank { normalized }
                steps += PrivateDnsCycleStep.Hostname(label = label, hostname = normalized)
            }
        }
        if (steps.isEmpty()) {
            return listOf(PrivateDnsCycleStep.Off, PrivateDnsCycleStep.Auto)
        }
        return steps
    }

    fun matchesStep(state: PrivateDnsSystemState, step: PrivateDnsCycleStep): Boolean = when (step) {
        PrivateDnsCycleStep.Off -> state.mode == PrivateDnsMode.OFF
        PrivateDnsCycleStep.Auto -> state.mode == PrivateDnsMode.AUTO
        is PrivateDnsCycleStep.Hostname -> {
            state.mode == PrivateDnsMode.HOSTNAME &&
                state.specifier != null &&
                normalizeHostname(state.specifier) == normalizeHostname(step.hostname)
        }
    }

    fun nextPrivateDnsStep(
        current: PrivateDnsSystemState,
        config: PrivateDnsTileConfig
    ): PrivateDnsCycleStep {
        val cycle = buildPrivateDnsCycle(config)
        val currentIndex = cycle.indexOfFirst { matchesStep(current, it) }
        return if (currentIndex == -1) {
            cycle.first()
        } else {
            cycle[(currentIndex + 1) % cycle.size]
        }
    }

    fun moveHostname(
        list: List<PrivateDnsHostnameOption>,
        id: String,
        delta: Int
    ): List<PrivateDnsHostnameOption> {
        if (delta == 0) return list
        val index = list.indexOfFirst { it.id == id }
        if (index == -1) return list
        val targetIndex = (index + delta).coerceIn(0, list.lastIndex)
        if (targetIndex == index) return list
        val mutable = list.toMutableList()
        val item = mutable.removeAt(index)
        mutable.add(targetIndex, item)
        return mutable
    }

    fun addCustomHostname(
        config: PrivateDnsTileConfig,
        label: String,
        rawHostname: String,
        idGenerator: () -> String = { "custom-${System.nanoTime()}" }
    ): Result<PrivateDnsTileConfig> {
        val validated = validatePrivateDnsHostname(rawHostname).getOrElse { return Result.failure(it) }
        val duplicate = config.hostnames.any { normalizeHostname(it.hostname) == validated }
        if (duplicate) {
            return Result.failure(IllegalArgumentException("$validated is already in your hostname list."))
        }
        val cleanLabel = label.trim().ifBlank { validated }.take(40)
        val entry = PrivateDnsHostnameOption(
            id = idGenerator(),
            label = cleanLabel,
            hostname = validated,
            enabledInCycle = true,
            isBuiltIn = false
        )
        return Result.success(config.copy(hostnames = config.hostnames + entry))
    }

    fun removeHostname(config: PrivateDnsTileConfig, id: String): PrivateDnsTileConfig {
        val target = config.hostnames.firstOrNull { it.id == id } ?: return config
        if (target.isBuiltIn) return config
        return config.copy(hostnames = config.hostnames.filterNot { it.id == id })
    }

    // ------------------------------------------------------------------ USB Debugging auto-revert

    fun onUsbDebuggingToggled(
        newEnabled: Boolean,
        config: UsbDebuggingTileConfig,
        nowEpochMs: Long
    ): UsbDebuggingTileConfig {
        val deadline = if (newEnabled && config.autoRevertEnabled) {
            nowEpochMs + clampDelayMinutes(config.autoRevertDelayMinutes) * 60_000L
        } else {
            0L
        }
        return config.copy(autoRevertDeadlineEpochMs = deadline)
    }

    fun armUsbDebuggingAutoRevert(
        config: UsbDebuggingTileConfig,
        nowEpochMs: Long
    ): UsbDebuggingTileConfig {
        val deadline = nowEpochMs + clampDelayMinutes(config.autoRevertDelayMinutes) * 60_000L
        return config.copy(autoRevertEnabled = true, autoRevertDeadlineEpochMs = deadline)
    }

    fun isUsbDebuggingRevertDue(
        adbEnabled: Boolean,
        config: UsbDebuggingTileConfig,
        nowEpochMs: Long
    ): Boolean {
        if (!adbEnabled || config.autoRevertDeadlineEpochMs <= 0L) return false
        return nowEpochMs + REVERT_EARLY_TOLERANCE_MS >= config.autoRevertDeadlineEpochMs
    }

    // ------------------------------------------------------------------ Private DNS auto-revert

    fun onPrivateDnsChangedViaTile(
        previous: PrivateDnsSystemState,
        next: PrivateDnsCycleStep,
        config: PrivateDnsTileConfig,
        nowEpochMs: Long
    ): PrivateDnsTileConfig {
        if (!config.autoRevertEnabled) {
            return config.copy(
                autoRevertTargetMode = null,
                autoRevertTargetHostname = null,
                autoRevertDeadlineEpochMs = 0L
            )
        }

        val hasActivePendingTarget =
            config.autoRevertDeadlineEpochMs > nowEpochMs && config.autoRevertTargetMode != null

        val targetMode: PrivateDnsMode
        val targetHostname: String?
        if (hasActivePendingTarget) {
            targetMode = config.autoRevertTargetMode!!
            targetHostname = config.autoRevertTargetHostname
        } else {
            if (!previous.readable) {
                return config.copy(
                    autoRevertTargetMode = null,
                    autoRevertTargetHostname = null,
                    autoRevertDeadlineEpochMs = 0L
                )
            }
            if (previous.mode == PrivateDnsMode.HOSTNAME &&
                validatePrivateDnsHostname(previous.specifier.orEmpty()).isFailure
            ) {
                return config.copy(
                    autoRevertTargetMode = null,
                    autoRevertTargetHostname = null,
                    autoRevertDeadlineEpochMs = 0L
                )
            }
            targetMode = previous.mode
            targetHostname = if (previous.mode == PrivateDnsMode.HOSTNAME) {
                normalizeHostname(previous.specifier.orEmpty())
            } else {
                null
            }
        }

        val targetAsState = PrivateDnsSystemState(targetMode, targetHostname, readable = true)
        if (matchesStep(targetAsState, next)) {
            return config.copy(
                autoRevertTargetMode = null,
                autoRevertTargetHostname = null,
                autoRevertDeadlineEpochMs = 0L
            )
        }

        val deadline = nowEpochMs + clampDelayMinutes(config.autoRevertDelayMinutes) * 60_000L
        return config.copy(
            autoRevertTargetMode = targetMode,
            autoRevertTargetHostname = targetHostname,
            autoRevertDeadlineEpochMs = deadline
        )
    }

    fun privateDnsTargetStep(config: PrivateDnsTileConfig): PrivateDnsCycleStep? {
        val mode = config.autoRevertTargetMode ?: return null
        return when (mode) {
            PrivateDnsMode.OFF -> PrivateDnsCycleStep.Off
            PrivateDnsMode.AUTO -> PrivateDnsCycleStep.Auto
            PrivateDnsMode.HOSTNAME -> {
                val host = validatePrivateDnsHostname(config.autoRevertTargetHostname.orEmpty()).getOrNull()
                    ?: return null
                val label = config.hostnames
                    .firstOrNull { normalizeHostname(it.hostname) == host }
                    ?.label ?: host
                PrivateDnsCycleStep.Hostname(label = label, hostname = host)
            }
        }
    }

    fun isPrivateDnsRevertDue(
        current: PrivateDnsSystemState,
        config: PrivateDnsTileConfig,
        nowEpochMs: Long
    ): Boolean {
        if (config.autoRevertDeadlineEpochMs <= 0L) return false
        val target = privateDnsTargetStep(config) ?: return false
        if (nowEpochMs + REVERT_EARLY_TOLERANCE_MS < config.autoRevertDeadlineEpochMs) return false
        return !matchesStep(current, target)
    }

    fun buildAdbGrantCommand(packageName: String): String =
        "adb shell pm grant ${packageName.trim()} android.permission.WRITE_SECURE_SETTINGS"

    fun buildLocalShellGrantCommand(packageName: String): String =
        "pm grant ${packageName.trim()} android.permission.WRITE_SECURE_SETTINGS"

    fun evaluateQuickSetupChecklist(
        hasPermission: Boolean,
        developerOptionsEnabled: Boolean,
        hasSuBinary: Boolean,
        isShizukuInstalled: Boolean
    ): QuickSetupChecklistState = QuickSetupChecklistState(
        developerOptionsReady = developerOptionsEnabled || hasPermission,
        permissionGranted = hasPermission,
        tilesReadyToUse = hasPermission,
        rootQuickGrantAvailable = hasSuBinary && !hasPermission,
        shizukuDetected = isShizukuInstalled
    )

    // ------------------------------------------------------------------ Tile presentations

    fun formatRemainingShort(remainingMs: Long): String {
        if (remainingMs <= 0L) return "soon"
        val totalMinutes = ((remainingMs + 59_999L) / 60_000L).toInt()
        return when {
            remainingMs < 60_000L -> "<1m"
            totalMinutes < 60 -> "${totalMinutes}m"
            totalMinutes % 60 == 0 -> "${totalMinutes / 60}h"
            else -> "${totalMinutes / 60}h ${totalMinutes % 60}m"
        }
    }

    fun presentPrivateDnsTile(
        state: PrivateDnsSystemState,
        config: PrivateDnsTileConfig,
        hasPermission: Boolean,
        nowEpochMs: Long
    ): TilePresentation {
        if (!hasPermission) {
            return TilePresentation(
                label = "Private DNS",
                subtitle = "Tap for setup",
                state = TileVisualState.INACTIVE,
                contentDescription = "Private DNS Quick Settings tile requires WRITE_SECURE_SETTINGS setup"
            )
        }
        if (!state.readable) {
            return TilePresentation(
                label = "Private DNS",
                subtitle = "Unavailable",
                state = TileVisualState.INACTIVE,
                contentDescription = "Private DNS setting is unreadable on this device"
            )
        }

        val revertSuffix = if (config.autoRevertDeadlineEpochMs > nowEpochMs && config.autoRevertTargetMode != null) {
            " · ${formatRemainingShort(config.autoRevertDeadlineEpochMs - nowEpochMs)}"
        } else {
            ""
        }

        return when (state.mode) {
            PrivateDnsMode.OFF -> TilePresentation(
                label = "Private DNS",
                subtitle = "Off$revertSuffix",
                state = TileVisualState.INACTIVE,
                contentDescription = "Private DNS is Off$revertSuffix"
            )
            PrivateDnsMode.AUTO -> TilePresentation(
                label = "Private DNS",
                subtitle = "Auto$revertSuffix",
                state = TileVisualState.ACTIVE,
                contentDescription = "Private DNS is Auto (opportunistic)$revertSuffix"
            )
            PrivateDnsMode.HOSTNAME -> {
                val rawHost = state.specifier?.trim().orEmpty()
                val normalized = normalizeHostname(rawHost)
                val match = config.hostnames.firstOrNull { normalizeHostname(it.hostname) == normalized }
                val shortName = match?.label?.takeIf { it.isNotBlank() }
                    ?: rawHost.ifBlank { "Hostname" }
                TilePresentation(
                    label = "Private DNS",
                    subtitle = "$shortName$revertSuffix",
                    state = TileVisualState.ACTIVE,
                    contentDescription = "Private DNS is set to ${rawHost.ifBlank { shortName }}$revertSuffix"
                )
            }
        }
    }

    fun presentUsbDebuggingTile(
        state: UsbDebuggingSystemState,
        config: UsbDebuggingTileConfig,
        hasPermission: Boolean,
        nowEpochMs: Long
    ): TilePresentation {
        if (!hasPermission) {
            return TilePresentation(
                label = "USB Debugging",
                subtitle = "Tap for setup",
                state = TileVisualState.INACTIVE,
                contentDescription = "USB Debugging Quick Settings tile requires WRITE_SECURE_SETTINGS setup"
            )
        }
        if (!state.readable) {
            return TilePresentation(
                label = "USB Debugging",
                subtitle = "Unavailable",
                state = TileVisualState.INACTIVE,
                contentDescription = "USB Debugging setting is unreadable on this device"
            )
        }

        if (state.enabled) {
            val subtitle = when {
                config.autoRevertDeadlineEpochMs > nowEpochMs ->
                    "On · ${formatRemainingShort(config.autoRevertDeadlineEpochMs - nowEpochMs)}"
                config.autoRevertEnabled ->
                    "On · ${clampDelayMinutes(config.autoRevertDelayMinutes)}m"
                else -> "On"
            }
            return TilePresentation(
                label = "USB Debugging",
                subtitle = subtitle,
                state = TileVisualState.ACTIVE,
                contentDescription = "USB Debugging is On ($subtitle)"
            )
        }

        val subtitle = if (config.autoRevertEnabled) {
            "Off · ${clampDelayMinutes(config.autoRevertDelayMinutes)}m revert"
        } else {
            "Off"
        }
        return TilePresentation(
            label = "USB Debugging",
            subtitle = subtitle,
            state = TileVisualState.INACTIVE,
            contentDescription = "USB Debugging is Off"
        )
    }
}
