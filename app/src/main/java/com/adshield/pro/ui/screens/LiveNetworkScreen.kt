package com.adshield.pro.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adshield.pro.domain.model.VpnStatus
import com.adshield.pro.engine.aether.LiveNetworkModel
import com.adshield.pro.ui.components.BarRow
import com.adshield.pro.ui.components.CyberPanel
import com.adshield.pro.ui.components.MetricSpec
import com.adshield.pro.ui.components.ProtectionShield
import com.adshield.pro.ui.components.ResponsiveMetrics
import com.adshield.pro.ui.components.ScrollToTopButton
import com.adshield.pro.ui.components.StatusPill
import com.adshield.pro.ui.theme.AlertRed
import com.adshield.pro.ui.theme.CyberCyan
import com.adshield.pro.ui.theme.EmeraldPrimary
import com.adshield.pro.ui.theme.SkyBlueGlow
import com.adshield.pro.ui.theme.WarningAmber
import com.adshield.pro.ui.theme.formatInteger
import java.text.DateFormat
import java.util.Date

/**
 * Aether live network dashboard. Every number comes from the device's own DNS log; the event
 * feed is lazy and keyed by log id so long sessions scroll without composing hundreds of rows.
 */
@Composable
fun LiveNetworkScreen(
    snapshot: LiveNetworkModel.NetworkSnapshot,
    onToggleProtection: (() -> Unit)? = null,
    onOpenWorldMonitor: (() -> Unit)? = null
) {
    val listState = rememberLazyListState()
    val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.MEDIUM) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "live-shield") {
                CyberPanel(
                    title = "Live network",
                    subtitle = "${snapshot.modeLabel} · ${snapshot.protocolLabel}",
                    trailing = { StatusPill(if (snapshot.vpnConnected) "● LIVE" else "IDLE", if (snapshot.vpnConnected) EmeraldPrimary else WarningAmber) }
                ) {
                    ProtectionShield(
                        status = if (snapshot.vpnConnected) VpnStatus.CONNECTED else VpnStatus.DISCONNECTED,
                        hasTraffic = snapshot.hasData,
                        threatSignals = snapshot.threatsBlocked.toInt(),
                        modifier = Modifier.fillMaxWidth().height(210.dp)
                    )
                    Text(
                        if (snapshot.hasData) "${formatInteger(snapshot.blockedQueries)} blocked in the recent log window"
                        else snapshot.emptyReason ?: "Waiting for real DNS traffic.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    if (onToggleProtection != null || onOpenWorldMonitor != null) {
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (onToggleProtection != null) {
                                Button(
                                    onClick = onToggleProtection,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (snapshot.vpnConnected) AlertRed else EmeraldPrimary
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = if (snapshot.vpnConnected) "Stop Live Shield" else "▶ Start Live DNS",
                                        color = if (snapshot.vpnConnected) Color.White else Color.Black,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                            if (onOpenWorldMonitor != null) {
                                OutlinedButton(
                                    onClick = onOpenWorldMonitor,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = "🌍 3D World Live",
                                        color = CyberCyan,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item(key = "live-counters") {
                CyberPanel(
                    title = "Live packet telemetry",
                    subtitle = if (snapshot.hasData) "Latest ${snapshot.totalQueries} logged queries" else "Real-time DNS & threat counters"
                ) {
                    ResponsiveMetrics(
                        columns = 2,
                        metrics = listOf(
                            MetricSpec("Logged", formatInteger(snapshot.totalQueries), "recent window", Color.White),
                            MetricSpec("Blocked", formatInteger(snapshot.blockedQueries), "${snapshot.blockRatePercent.toInt()}% of recent", AlertRed),
                            MetricSpec("Allowed", formatInteger(snapshot.allowedQueries), "passed filter", EmeraldPrimary),
                            MetricSpec("Threats", formatInteger(snapshot.threatsBlocked), "malware / phishing", WarningAmber)
                        )
                    )
                }
            }

            item(key = "live-health") {
                CyberPanel(title = "Connection health", subtitle = snapshot.connectionHealth.detail) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (snapshot.connectionHealth.isHealthy) Icons.Default.CheckCircle else Icons.Default.Block,
                            contentDescription = null,
                            tint = if (snapshot.connectionHealth.isHealthy) EmeraldPrimary else WarningAmber,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(snapshot.connectionHealth.label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    ResponsiveMetrics(
                        columns = 2,
                        metrics = listOf(
                            MetricSpec("Resolver", snapshot.resolverLabel.take(18), snapshot.protocolLabel, SkyBlueGlow),
                            MetricSpec("Avg latency", snapshot.averageLatencyMs?.let { "$it ms" } ?: "—", "Recent queries", CyberCyan)
                        )
                    )
                }
            }

            if (snapshot.hasData) {
                item(key = "live-categories") {
                    CyberPanel(title = "What was blocked", subtitle = "Categories from real matched rules") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            snapshot.categories.forEach { slice ->
                                BarRow(slice.lane.label, formatInteger(slice.count), slice.shareOfTotal, laneColor(slice.lane))
                            }
                        }
                    }
                }

                if (snapshot.topApps.isNotEmpty()) {
                    item(key = "live-apps") {
                        CyberPanel(title = "Top apps by DNS activity", subtitle = "Attribution uses Android's UID where available") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                snapshot.topApps.take(5).forEach { app ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(app.appName, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(formatInteger(app.totalQueries), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                                        Spacer(Modifier.width(8.dp))
                                        Text("${formatInteger(app.blockedQueries)} blocked", color = WarningAmber, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                item(key = "live-feed-header") {
                    Text("LIVE EVENT FEED", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 0.8.sp)
                }
                items(snapshot.recentEvents, key = { it.id }, contentType = { "dns-event" }) { event ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF061221)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(laneColor(event.lane)))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(event.domain, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(event.reason, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(timeFormat.format(Date(event.timestampEpochMs)), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                        }
                    }
                }
            } else {
                item(key = "live-empty") {
                    CyberPanel(title = "No live traffic yet", subtitle = "This dashboard never simulates activity") {
                        Text(
                            snapshot.emptyReason ?: "Nothing has been captured yet.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Open a few apps with protection on and real DNS events will appear here. If detailed logging is off, aggregate counters still update but per-domain events are not stored.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
        ScrollToTopButton(state = listState, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
    }
}

private fun laneColor(lane: LiveNetworkModel.TrafficLane): Color = when (lane) {
    LiveNetworkModel.TrafficLane.ADS -> Color(0xFFFF6B6B)
    LiveNetworkModel.TrafficLane.TRACKERS -> WarningAmber
    LiveNetworkModel.TrafficLane.THREATS -> AlertRed
    LiveNetworkModel.TrafficLane.CUSTOM -> CyberCyan
    LiveNetworkModel.TrafficLane.ALLOWED -> EmeraldPrimary
}
