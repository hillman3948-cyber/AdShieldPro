package com.adshield.pro.engine.aether

import com.adshield.pro.domain.model.AppDnsActivity
import com.adshield.pro.domain.model.DnsActivityRecord
import com.adshield.pro.domain.model.DomainBlockCount
import com.adshield.pro.domain.model.RuleCategory
import com.adshield.pro.domain.model.VpnRuntimeState
import com.adshield.pro.domain.model.VpnStatus

/**
 * Aether AI — live network model for the visual dashboard.
 *
 * Everything here is derived from the app's own DNS log, session counters and resolver config.
 * There is no simulated traffic and no invented statistic: when there is no data the model says
 * so and the UI shows an empty state instead of an animated fiction.
 */
object LiveNetworkModel {

    /** Traffic lanes drawn around the shield — classified from the matched rule category. */
    enum class TrafficLane(val label: String) {
        ADS("Advertising"),
        TRACKERS("Trackers & analytics"),
        THREATS("Malware & phishing"),
        CUSTOM("Your rules"),
        ALLOWED("Allowed traffic")
    }

    data class CategorySlice(val lane: TrafficLane, val count: Long, val shareOfTotal: Float)

    data class LiveEvent(
        val timestampEpochMs: Long,
        val domain: String,
        val lane: TrafficLane,
        val blocked: Boolean,
        val appName: String?,
        val latencyMs: Int,
        val reason: String
    )

    data class NetworkSnapshot(
        val hasData: Boolean,
        val vpnConnected: Boolean,
        val modeLabel: String,
        val resolverLabel: String,
        val protocolLabel: String,
        val totalQueries: Long,
        val blockedQueries: Long,
        val allowedQueries: Long,
        val blockRatePercent: Float,
        val threatsBlocked: Long,
        val uniqueDomains: Int,
        val averageLatencyMs: Int?,
        val worstLatencyMs: Int?,
        val categories: List<CategorySlice>,
        val topApps: List<AppDnsActivity>,
        val topBlocked: List<DomainBlockCount>,
        val recentEvents: List<LiveEvent>,
        val connectionHealth: ConnectionHealth,
        val emptyReason: String?
    )

    data class ConnectionHealth(
        val label: String,
        val detail: String,
        val isHealthy: Boolean
    )

    /** Builds the snapshot from the repository's real streams. */
    fun snapshot(
        runtime: VpnRuntimeState,
        logs: List<DnsActivityRecord>,
        topApps: List<AppDnsActivity>,
        topBlocked: List<DomainBlockCount>,
        appLabels: Map<String, String> = emptyMap()
    ): NetworkSnapshot {
        val connected = runtime.status == VpnStatus.CONNECTED
        val total = logs.size.toLong()
        val blocked = logs.count { it.isBlocked }.toLong()
        val allowed = total - blocked
        val threats = logs.count { it.isBlocked && it.category == RuleCategory.MALWARE }.toLong()
        val latencies = logs.map { it.latencyMs }.filter { it > 0 }

        val categories = buildList {
            RuleCategory.entries.forEach { category ->
                val lane = laneFor(category)
                if (lane == null) return@forEach
                val count = logs.count { it.isBlocked && it.category == category }.toLong()
                if (count > 0) add(CategorySlice(lane, count, if (total > 0) count * 100f / total else 0f))
            }
            if (allowed > 0) add(CategorySlice(TrafficLane.ALLOWED, allowed, if (total > 0) allowed * 100f / total else 0f))
        }.sortedByDescending { it.count }

        val events = logs.take(40).map { record ->
            LiveEvent(
                timestampEpochMs = record.timestampEpochMs,
                domain = record.domain,
                lane = when {
                    record.isBlocked -> laneFor(record.category) ?: TrafficLane.CUSTOM
                    else -> TrafficLane.ALLOWED
                },
                blocked = record.isBlocked,
                appName = null,
                latencyMs = record.latencyMs,
                reason = when {
                    !record.isBlocked -> "Allowed by ${record.sourceList.ifBlank { "the active lists" }}"
                    record.sourceList.isBlank() -> "Blocked (${record.category?.displayName ?: "policy"})"
                    else -> "Blocked by ${record.sourceList}"
                }
            )
        }

        val emptyReason = when {
            !connected -> "Protection is off, so no DNS traffic is flowing through AdShield."
            total == 0L -> "Protection is on and the tunnel is idle — nothing has been logged yet on this session."
            else -> null
        }

        return NetworkSnapshot(
            hasData = total > 0,
            vpnConnected = connected,
            modeLabel = runtime.activeMode.title,
            resolverLabel = resolverLabel(runtime),
            protocolLabel = runtime.resolverConfig.protocol.name,
            totalQueries = total,
            blockedQueries = blocked,
            allowedQueries = allowed,
            blockRatePercent = if (total > 0) blocked * 100f / total else 0f,
            threatsBlocked = threats,
            uniqueDomains = logs.map { it.domain }.distinct().size,
            averageLatencyMs = latencies.takeIf { it.isNotEmpty() }?.average()?.toInt(),
            worstLatencyMs = latencies.maxOrNull(),
            categories = categories,
            topApps = topApps,
            topBlocked = topBlocked,
            recentEvents = events,
            connectionHealth = health(connected, runtime, latencies),
            emptyReason = emptyReason
        )
    }

    private fun laneFor(category: RuleCategory?): TrafficLane? = when (category) {
        RuleCategory.ADS -> TrafficLane.ADS
        RuleCategory.TRACKERS, RuleCategory.STRICT_TELEMETRY -> TrafficLane.TRACKERS
        RuleCategory.MALWARE -> TrafficLane.THREATS
        RuleCategory.CUSTOM -> TrafficLane.CUSTOM
        null -> null
    }

    private fun resolverLabel(runtime: VpnRuntimeState): String {
        val config = runtime.resolverConfig
        return when (config.protocol.name) {
            "DOH" -> config.activeDohUrl
            "DOT" -> config.activeDotHost
            else -> "${config.customPrimaryIpv4} (plain DNS)"
        }
    }

    /**
     * Health is judged from real signals only: tunnel state plus observed resolver latency.
     * No latency data means "unknown", not "excellent".
     */
    private fun health(connected: Boolean, runtime: VpnRuntimeState, latencies: List<Int>): ConnectionHealth = when {
        !connected -> ConnectionHealth(
            "Protection inactive",
            "The local DNS tunnel is not running (${runtime.status.name.lowercase()}).",
            false
        )
        latencies.isEmpty() -> ConnectionHealth(
            "Waiting for traffic",
            "Tunnel is up; no DNS latency has been measured yet in this session.",
            true
        )
        latencies.average() < 120 -> ConnectionHealth(
            "Healthy",
            "Average DNS latency ${latencies.average().toInt()} ms across ${latencies.size} queries.",
            true
        )
        latencies.average() < 400 -> ConnectionHealth(
            "Slow resolver",
            "Average DNS latency ${latencies.average().toInt()} ms — try a closer DoH/DoT provider.",
            false
        )
        else -> ConnectionHealth(
            "Resolver unhealthy",
            "Average DNS latency ${latencies.average().toInt()} ms; consider switching resolver.",
            false
        )
    }
}
