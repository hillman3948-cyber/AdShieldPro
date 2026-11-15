package com.adshield.pro.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Https
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OfflineBolt
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adshield.pro.BuildConfig
import com.adshield.pro.R
import com.adshield.pro.data.local.preferences.AppSettingsState
import com.adshield.pro.domain.model.DnsActivityRecord
import com.adshield.pro.domain.model.ProtectionMode
import com.adshield.pro.domain.model.RuleCategory
import com.adshield.pro.domain.model.SmartBoostResult
import com.adshield.pro.domain.model.TimeBucketStat
import com.adshield.pro.domain.model.VpnRuntimeState
import com.adshield.pro.domain.model.VpnStatus
import com.adshield.pro.engine.aether.LiveNetworkModel
import com.adshield.pro.engine.optimizer.RuleBasedOptimizationPredictor
import com.adshield.pro.ui.components.BarRow
import com.adshield.pro.ui.components.CyberPanel
import com.adshield.pro.ui.components.DonutChart
import com.adshield.pro.ui.components.DonutSegment
import com.adshield.pro.ui.components.MetricSpec
import com.adshield.pro.ui.components.PrivacyBadge
import com.adshield.pro.ui.components.LiveProtectionStatusPanel
import com.adshield.pro.ui.components.ProtectionShield
import com.adshield.pro.ui.model.ProtectionPipelineStatus
import com.adshield.pro.ui.components.ResponsiveMetrics
import com.adshield.pro.ui.components.ScrollToTopButton
import com.adshield.pro.ui.components.Sparkline
import com.adshield.pro.ui.components.StatusPill
import com.adshield.pro.ui.model.DashboardPresentation
import com.adshield.pro.ui.theme.AlertRed
import com.adshield.pro.ui.theme.CyberCyan
import com.adshield.pro.ui.theme.ElectricBlue
import com.adshield.pro.ui.theme.EmeraldPrimary
import com.adshield.pro.ui.theme.NavyBorder
import com.adshield.pro.ui.theme.SkyBlueGlow
import com.adshield.pro.ui.theme.WarningAmber
import com.adshield.pro.ui.theme.formatInteger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    vpnState: VpnRuntimeState,
    settings: AppSettingsState,
    protectionStatus: ProtectionPipelineStatus,
    allTimeTotal: Long,
    allTimeBlocked: Long,
    allTimeAllowed: Long,
    malwareBlocks: Long,
    chartBuckets: List<TimeBucketStat>,
    recentLogs: List<DnsActivityRecord>,
    liveNetwork: LiveNetworkModel.NetworkSnapshot,
    isUpdatingFilters: Boolean,
    smartBoostResult: SmartBoostResult?,
    isRunningSmartBoost: Boolean,
    isCheckingForUpdates: Boolean,
    onCheckForUpdates: () -> Unit,
    onToggleVpn: (Boolean) -> Unit,
    onPauseResumeVpn: () -> Unit,
    onSelectProtectionMode: (ProtectionMode) -> Unit,
    onTriggerFilterSync: () -> Unit,
    onRunOptimizeNow: () -> Unit,
    onOpenOptimizerTab: () -> Unit,
    onOpenLiveTab: () -> Unit,
    onOpenStatisticsTab: () -> Unit,
    onOpenStatusTab: () -> Unit,
    onOpenWorldMonitor: () -> Unit = {}
) {
    val listState = rememberLazyListState()
    val screenWidth = LocalConfiguration.current.screenWidthDp
    val isConnected = vpnState.status == VpnStatus.CONNECTED
    val isPaused = vpnState.status == VpnStatus.PAUSED
    val isActiveOrPaused = isConnected || isPaused
    val overview = remember(allTimeTotal, allTimeBlocked, allTimeAllowed, vpnState.sessionTotalQueries, vpnState.sessionBlockedQueries, vpnState.sessionAllowedQueries, malwareBlocks, liveNetwork.threatsBlocked) {
        DashboardPresentation.overview(
            allTimeTotal, allTimeBlocked, allTimeAllowed,
            vpnState.sessionTotalQueries, vpnState.sessionBlockedQueries, vpnState.sessionAllowedQueries,
            malwareBlocks, liveNetwork.threatsBlocked
        )
    }
    val blockedSegments = remember(liveNetwork.categories) {
        liveNetwork.categories.filter { it.lane != LiveNetworkModel.TrafficLane.ALLOWED }.map { slice ->
            DonutSegment(slice.lane.label, slice.count, laneColor(slice.lane))
        }
    }
    val recentBlocked = blockedSegments.sumOf { it.value }
    val statusColor = when (vpnState.status) {
        VpnStatus.CONNECTED -> EmeraldPrimary
        VpnStatus.PAUSED -> WarningAmber
        VpnStatus.STARTING, VpnStatus.RECONNECTING -> CyberCyan
        VpnStatus.ERROR -> AlertRed
        VpnStatus.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusLabel = when (vpnState.status) {
        VpnStatus.CONNECTED -> "SYSTEM PROTECTED"
        VpnStatus.PAUSED -> "FILTERING PAUSED"
        VpnStatus.STARTING -> "STARTING PROTECTION"
        VpnStatus.RECONNECTING -> "RECONNECTING"
        VpnStatus.ERROR -> "NEEDS ATTENTION"
        VpnStatus.DISCONNECTED -> "PROTECTION OFF"
    }
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "header") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(R.drawable.ic_adshield_mark),
                        contentDescription = "AdShield Pro",
                        modifier = Modifier.size(34.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("AdShield Pro", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(
                            "Real-Time Threat Intelligence · v${BuildConfig.VERSION_NAME}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = onOpenWorldMonitor) {
                        Icon(
                            Icons.Default.Public,
                            contentDescription = "Open 3D World Monitor 2035",
                            tint = CyberCyan
                        )
                    }
                    IconButton(onClick = onCheckForUpdates, enabled = !isCheckingForUpdates) {
                        Icon(
                            Icons.Default.SystemUpdateAlt,
                            contentDescription = if (isCheckingForUpdates) "Checking for updates" else "Check for updates",
                            tint = SkyBlueGlow
                        )
                    }
                }
            }

            item(key = "world-monitor-top-banner") {
                CyberPanel(
                    title = "World Monitor 2035",
                    subtitle = "3D Digital Earth · Live Aviation, Maritime, Satellites, Hazards, Weather, Cyber & Infra",
                    trailing = { StatusPill("3D GLOBE", CyberCyan) }
                ) {
                    Button(
                        onClick = onOpenWorldMonitor,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
                    ) {
                        Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Open 3D World Monitor 2035", fontWeight = FontWeight.Bold)
                    }
                }
            }

            item(key = "shield") {
                CyberPanel(
                    title = "Local threat shield",
                    subtitle = "On-device DNS · ${settings.resolverConfig.protocol.label} · ${vpnState.activeNetworkLabel}",
                    trailing = { StatusPill(if (isConnected) "LIVE" else "IDLE", statusColor) }
                ) {
                    ProtectionShield(
                        status = vpnState.status,
                        hasTraffic = liveNetwork.hasData,
                        threatSignals = liveNetwork.threatsBlocked.toInt(),
                        onClick = { onToggleVpn(!isActiveOrPaused) },
                        modifier = Modifier.fillMaxWidth().height(250.dp)
                    )
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(statusLabel, color = if (isConnected) EmeraldPrimary else statusColor, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, fontSize = 14.sp)
                        Text(
                            text = when (vpnState.status) {
                                VpnStatus.CONNECTED -> "DNS filtering active · ${formatInteger(vpnState.loadedRuleCount)} rules loaded"
                                VpnStatus.PAUSED -> "Tunnel is paused. Resume to keep filtering."
                                VpnStatus.ERROR -> vpnState.lastError ?: "The tunnel could not start."
                                else -> "Tap the shield or the button to start non-root DNS protection."
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { onToggleVpn(!isActiveOrPaused) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = if (isActiveOrPaused) AlertRed.copy(alpha = 0.85f) else ElectricBlue)
                            ) {
                                Icon(if (isActiveOrPaused) Icons.Default.Shield else Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (isActiveOrPaused) "Turn off" else "Protect device", maxLines = 1)
                            }
                            OutlinedButton(onClick = onPauseResumeVpn, enabled = isActiveOrPaused, modifier = Modifier.weight(1f)) {
                                Icon(if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (isPaused) "Resume" else "Pause", maxLines = 1)
                            }
                        }
                        TextButton(onClick = onOpenLiveTab, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                            Text("Open live network dashboard", fontSize = 12.sp)
                        }
                    }
                }
            }

            item(key = "live_status") {
                LiveProtectionStatusPanel(
                    status = protectionStatus,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item(key = "overview") {
                CyberPanel(
                    title = "Threat overview",
                    subtitle = overview.scope.label,
                    trailing = { Text("${overview.blockPercent.toInt()}% blocked", color = SkyBlueGlow, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                ) {
                    Sparkline(
                        values = chartBuckets.map { it.blockedQueries },
                        secondaryValues = chartBuckets.map { it.totalQueries },
                        lineColor = AlertRed,
                        secondaryColor = ElectricBlue,
                        modifier = Modifier.fillMaxWidth().height(72.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    ResponsiveMetrics(
                        columns = if (screenWidth >= 600) 4 else 2,
                        metrics = listOf(
                            MetricSpec("Total queries", formatInteger(overview.total), overview.scope.label, Color.White),
                            MetricSpec("Blocked", formatInteger(overview.blocked), "Ads, trackers & threats", AlertRed),
                            MetricSpec("Allowed", formatInteger(overview.allowed), "Passed the filter", EmeraldPrimary),
                            MetricSpec("Threats", formatInteger(overview.threats), "Malware / phishing", WarningAmber)
                        )
                    )
                }
            }

            item(key = "active-threats") {
                CyberPanel(title = "Active threats", subtitle = "Recent logged block mix") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        DonutChart(
                            segments = blockedSegments,
                            centerValue = formatInteger(recentBlocked),
                            centerLabel = "RECENT",
                            modifier = Modifier.size(124.dp)
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            if (blockedSegments.isEmpty()) {
                                Text("No blocked traffic in the recent log yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            } else {
                                blockedSegments.take(5).forEach { segment ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(8.dp).clip(CircleShape).background(segment.color))
                                        Spacer(Modifier.width(8.dp))
                                        Text(segment.label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(formatInteger(segment.value), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item(key = "detection-engine") {
                val coverage = settings.protectionMode.activeCategories.size.toFloat() / RuleCategory.entries.size.toFloat()
                CyberPanel(title = "Threat detection engine", subtitle = "Local analysis · no cloud AI") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(64.dp).clip(CircleShape).background(ElectricBlue.copy(alpha = 0.10f)).border(1.dp, ElectricBlue.copy(alpha = 0.5f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Psychology, contentDescription = null, tint = SkyBlueGlow, modifier = Modifier.size(34.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            DetectionRow("DNS filter", if (isConnected) "Active" else "Standby", isConnected)
                            DetectionRow("Threat lists", if (vpnState.loadedRuleCount > 0) "Loaded" else "Loading", vpnState.loadedRuleCount > 0)
                            DetectionRow("Browser shield", if (settings.browserCosmeticFilteringEnabled) "Enabled" else "Off", settings.browserCosmeticFilteringEnabled)
                            DetectionRow("Offline baseline", "Bundled", true)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Category coverage", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                        Text("${(coverage * 100).toInt()}%", color = SkyBlueGlow, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(5.dp))
                    LinearProgressIndicator(progress = { coverage.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(6.dp), color = ElectricBlue, trackColor = NavyBorder.copy(alpha = 0.5f))
                }
            }

            item(key = "attack-vectors") {
                CyberPanel(title = "Top attack vectors", subtitle = "Share of recent blocked queries") {
                    if (blockedSegments.isEmpty()) {
                        Text("Nothing blocked yet. Categories appear here after real DNS traffic is filtered.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            blockedSegments.sortedByDescending { it.value }.take(5).forEach { segment ->
                                val percent = if (recentBlocked > 0) segment.value * 100f / recentBlocked else 0f
                                BarRow(segment.label, "${percent.toInt()}%", percent, segment.color)
                            }
                        }
                    }
                }
            }

            item(key = "activity-feed") {
                CyberPanel(
                    title = "Threat activity feed",
                    subtitle = if (settings.detailedDnsLoggingEnabled) "Live updates from this device" else "Detailed DNS logging is off",
                    trailing = { TextButton(onClick = onOpenStatisticsTab) { Text("View all", fontSize = 12.sp) } }
                ) {
                    val events = recentLogs.take(5)
                    if (events.isEmpty()) {
                        Text(
                            if (settings.detailedDnsLoggingEnabled) "No DNS events logged yet. Turn on protection and open an app to populate this feed."
                            else "Enable detailed logging in Settings to see per-domain events. Aggregate counters still work.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            events.forEach { record -> EventRow(record, timeFormat.format(Date(record.timestampEpochMs))) }
                        }
                    }
                }
            }

            item(key = "device-status") {
                ResponsiveMetrics(
                    columns = 3,
                    metrics = listOf(
                        MetricSpec("DNS tunnel", if (isConnected) "Active" else if (isPaused) "Paused" else "Off", vpnState.activeNetworkLabel, if (isConnected) EmeraldPrimary else WarningAmber),
                        MetricSpec("Filter core", formatInteger(vpnState.loadedRuleCount), "rules in memory", SkyBlueGlow),
                        MetricSpec("Resolver", if (vpnState.averageLatencyMs > 0) "${vpnState.averageLatencyMs} ms" else "—", settings.resolverConfig.protocol.label, CyberCyan)
                    )
                )
            }

            item(key = "privacy") {
                CyberPanel(title = "Privacy & coverage", subtitle = "What this build actually does — not a certification claim") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrivacyBadge("Local only", Icons.Default.Lock, true, Modifier.weight(1f))
                        PrivacyBadge("No root", Icons.Default.Shield, true, Modifier.weight(1f))
                        PrivacyBadge("No MITM", Icons.Default.Https, true, Modifier.weight(1f))
                        PrivacyBadge("Offline rules", Icons.Default.OfflineBolt, vpnState.loadedRuleCount > 0, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onOpenStatusTab, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.VerifiedUser, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("View capability report")
                    }
                }
            }

            item(key = "quick-actions") {
                CyberPanel(title = "Quick actions", subtitle = "Optimizer and filter maintenance") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = onRunOptimizeNow, enabled = !isRunningSmartBoost, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (isRunningSmartBoost) "Optimizing…" else "Optimize now", maxLines = 1)
                        }
                        OutlinedButton(onClick = onTriggerFilterSync, enabled = !isUpdatingFilters, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (isUpdatingFilters) "Syncing…" else "Sync lists", maxLines = 1)
                        }
                    }
                    if (isUpdatingFilters || isRunningSmartBoost) {
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = ElectricBlue)
                    }
                    smartBoostResult?.let { result ->
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (result.wasSkippedDueToRecentBoost) "Already optimized recently"
                            else "Recovered ${RuleBasedOptimizationPredictor.formatBytes(result.bytesRecoveredAutomatically)}",
                            color = EmeraldPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp
                        )
                        result.actionsCompleted.take(3).forEach { action ->
                            Text("✓ $action", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                        }
                        TextButton(onClick = onOpenOptimizerTab) { Text("Open full optimizer") }
                    }
                }
            }

            item(key = "protection-mode") {
                CyberPanel(title = "Protection profile", subtitle = "Applies immediately to the in-memory filter engine") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProtectionMode.entries.forEach { mode ->
                            val selected = settings.protectionMode == mode
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (selected) ElectricBlue.copy(alpha = 0.14f) else Color(0xFF08182B),
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onSelectProtectionMode(mode) }
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            if (selected) Icons.Default.CheckCircle else Icons.Default.Security,
                                            contentDescription = null,
                                            tint = if (selected) SkyBlueGlow else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(mode.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    }
                                    Text(mode.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
                                    if (selected && mode.warningNote != null) {
                                        Text(mode.warningNote, color = WarningAmber, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item(key = "resolver") {
                CyberPanel(title = "DNS resolver", subtitle = "${settings.resolverConfig.preset.displayName} · IPv6 ${if (settings.resolverConfig.enableIpv6) "on" else "off"}") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Dns, contentDescription = null, tint = CyberCyan)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(settings.resolverConfig.protocol.label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(
                                if (settings.lastFilterUpdateEpochMs > 0L) "Lists synced ${SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(settings.lastFilterUpdateEpochMs))}"
                                else "Bundled offline baseline ready",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp
                            )
                        }
                        IconButton(onClick = onTriggerFilterSync, enabled = !isUpdatingFilters) {
                            Icon(Icons.Default.Refresh, contentDescription = "Sync filter lists", tint = SkyBlueGlow)
                        }
                    }
                }
            }

            item(key = "disclosure") {
                CyberPanel(title = "Coverage disclosure", subtitle = "DNS-level protection has real limits") {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "AdShield blocks third-party ad, tracker and malicious domains without root or HTTPS decryption. Apps that serve video ads from the same first-party servers as their content (for example some YouTube, Facebook, TikTok and streaming apps) cannot be fully blocked by DNS alone.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
        ScrollToTopButton(state = listState, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
    }
}

@Composable
private fun DetectionRow(label: String, value: String, active: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (active) EmeraldPrimary else MaterialTheme.colorScheme.onSurfaceVariant))
        Spacer(Modifier.width(8.dp))
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, color = if (active) EmeraldPrimary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun EventRow(record: DnsActivityRecord, time: String) {
    val severity = DashboardPresentation.eventSeverity(record)
    val color = when (severity) {
        "CRITICAL" -> AlertRed
        "HIGH" -> Color(0xFFFF7A45)
        "MEDIUM" -> WarningAmber
        "CUSTOM" -> CyberCyan
        else -> EmeraldPrimary
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = color.copy(alpha = 0.16f), shape = RoundedCornerShape(5.dp)) {
            Text(severity, color = color, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(DashboardPresentation.eventTitle(record), color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(record.domain, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(time, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
    }
}

private fun laneColor(lane: LiveNetworkModel.TrafficLane): Color = when (lane) {
    LiveNetworkModel.TrafficLane.ADS -> Color(0xFFFF6B6B)
    LiveNetworkModel.TrafficLane.TRACKERS -> WarningAmber
    LiveNetworkModel.TrafficLane.THREATS -> AlertRed
    LiveNetworkModel.TrafficLane.CUSTOM -> CyberCyan
    LiveNetworkModel.TrafficLane.ALLOWED -> EmeraldPrimary
}

/** Kept public for the Statistics screen. */
@Composable
fun RealTimeActivityBarChart(
    buckets: List<TimeBucketStat>,
    modifier: Modifier = Modifier
) {
    val maxTotal = (buckets.maxOfOrNull { it.totalQueries } ?: 1L).coerceAtLeast(1L)
    Column(modifier = modifier) {
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            if (buckets.isEmpty()) return@Canvas
            val slotWidth = size.width / buckets.size
            val barWidth = (slotWidth * 0.55f).coerceAtMost(36.dp.toPx())
            buckets.forEachIndexed { index, bucket ->
                val centerX = index * slotWidth + slotWidth / 2f
                val left = centerX - barWidth / 2f
                val totalRatio = (bucket.totalQueries.toFloat() / maxTotal.toFloat()).coerceIn(0.04f, 1f)
                val blockedRatio = (bucket.blockedQueries.toFloat() / maxTotal.toFloat()).coerceIn(0f, totalRatio)
                val totalHeight = size.height * totalRatio
                val blockedHeight = size.height * blockedRatio
                drawRoundRect(
                    color = EmeraldPrimary.copy(alpha = 0.45f),
                    topLeft = Offset(left, size.height - totalHeight),
                    size = Size(barWidth, totalHeight),
                    cornerRadius = CornerRadius(8f, 8f)
                )
                if (blockedHeight > 1f) {
                    drawRoundRect(
                        color = AlertRed,
                        topLeft = Offset(left, size.height - blockedHeight),
                        size = Size(barWidth, blockedHeight),
                        cornerRadius = CornerRadius(8f, 8f)
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            buckets.forEach { bucket ->
                Text(bucket.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
