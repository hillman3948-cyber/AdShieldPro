package com.adshield.pro.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adshield.pro.engine.aether.LiveNetworkModel
import com.adshield.pro.ui.theme.CyberCyan
import com.adshield.pro.ui.theme.EmeraldPrimary
import com.adshield.pro.ui.theme.WarningAmber
import java.text.DateFormat
import java.util.Date

private val CardSurface = Color(0xFF101C2C)
private val MutedText = Color(0xFF9FB6B8)

/**
 * Aether AI — live network dashboard.
 *
 * The shield and its traffic lanes animate only while real DNS events exist; every number is
 * read from [LiveNetworkModel], which is built from this device's DNS log. When there is no
 * traffic the screen says why instead of animating invented activity.
 */
@Composable
fun LiveNetworkScreen(snapshot: LiveNetworkModel.NetworkSnapshot) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { ShieldVisualizer(snapshot) }
        item { HealthCard(snapshot) }
        if (snapshot.hasData) {
            item { CountersRow(snapshot) }
            item { CategoryBreakdown(snapshot) }
            if (snapshot.topApps.isNotEmpty()) item { TopAppsCard(snapshot) }
            item { EventFeed(snapshot) }
        } else {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = CardSurface)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("No live traffic yet", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            snapshot.emptyReason ?: "Nothing has been captured on this session.",
                            fontSize = 12.sp,
                            color = MutedText
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "This dashboard is fed only by AdShield's own DNS log — there is no simulated " +
                                "traffic. Open a few apps with protection on and events will appear here.",
                            fontSize = 11.sp,
                            color = MutedText.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShieldVisualizer(snapshot: LiveNetworkModel.NetworkSnapshot) {
    val transition = rememberInfiniteTransition(label = "shield")
    // Animation speed is derived from real block activity: idle tunnel = slow pulse.
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (snapshot.blockedQueries > 0) 1600 else 3200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse"
    )
    val activeColor = when {
        !snapshot.vpnConnected -> WarningAmber
        snapshot.threatsBlocked > 0 -> Color(0xFFFF4F74)
        else -> EmeraldPrimary
    }
    Surface(color = Color(0xFF08131F), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Aether live view", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                if (snapshot.vpnConnected) "Tunnel active · ${snapshot.modeLabel} · ${snapshot.protocolLabel}"
                else "Tunnel inactive",
                fontSize = 11.sp,
                color = if (snapshot.vpnConnected) EmeraldPrimary else WarningAmber
            )
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val maxRadius = size.minDimension / 2f
                    // Orbiting lanes: one per active category present in real data.
                    val lanes = snapshot.categories.take(4)
                    lanes.forEachIndexed { index, slice ->
                        val radius = maxRadius * (0.42f + index * 0.13f)
                        drawCircle(Color.White.copy(alpha = 0.07f), radius = radius, center = center, style = Stroke(1.5f))
                        val angle = pulse * 360f + index * 60f
                        val rad = Math.toRadians(angle.toDouble())
                        val point = Offset(
                            center.x + (radius * kotlin.math.cos(rad)).toFloat(),
                            center.y + (radius * kotlin.math.sin(rad)).toFloat()
                        )
                        drawCircle(laneColor(slice.lane), radius = 5f + slice.shareOfTotal / 6f, center = point)
                    }
                    // Central shield: two overlapping arcs, rotating while traffic flows.
                    rotate(degrees = if (snapshot.hasData) pulse * 90f else 0f, pivot = center) {
                        drawArc(
                            color = activeColor.copy(alpha = 0.85f),
                            startAngle = 20f,
                            sweepAngle = 240f,
                            useCenter = false,
                            topLeft = Offset(center.x - maxRadius * 0.3f, center.y - maxRadius * 0.3f),
                            size = androidx.compose.ui.geometry.Size(maxRadius * 0.6f, maxRadius * 0.6f),
                            style = Stroke(width = 4f)
                        )
                        drawArc(
                            color = CyberCyan.copy(alpha = 0.55f),
                            startAngle = 200f,
                            sweepAngle = 140f,
                            useCenter = false,
                            topLeft = Offset(center.x - maxRadius * 0.22f, center.y - maxRadius * 0.22f),
                            size = androidx.compose.ui.geometry.Size(maxRadius * 0.44f, maxRadius * 0.44f),
                            style = Stroke(width = 3f)
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Shield, contentDescription = null, tint = activeColor, modifier = Modifier.size(34.dp))
                    Text(
                        "${snapshot.blockedQueries}",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text("blocked this session", fontSize = 10.sp, color = MutedText)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                snapshot.categories.take(3).forEach { slice ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(laneColor(slice.lane), RoundedCornerShape(50))
                        )
                        Spacer(Modifier.height(0.dp))
                        Text(
                            "  ${slice.lane.label} ${slice.count}",
                            fontSize = 10.sp,
                            color = MutedText
                        )
                    }
                }
            }
        }
    }
}

private fun laneColor(lane: LiveNetworkModel.TrafficLane): Color = when (lane) {
    LiveNetworkModel.TrafficLane.ADS -> Color(0xFFFF6B6B)
    LiveNetworkModel.TrafficLane.TRACKERS -> WarningAmber
    LiveNetworkModel.TrafficLane.THREATS -> Color(0xFFFF4F74)
    LiveNetworkModel.TrafficLane.CUSTOM -> CyberCyan
    LiveNetworkModel.TrafficLane.ALLOWED -> EmeraldPrimary
}

@Composable
private fun HealthCard(snapshot: LiveNetworkModel.NetworkSnapshot) {
    Card(colors = CardDefaults.cardColors(containerColor = CardSurface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (snapshot.connectionHealth.isHealthy) Icons.Default.CheckCircle else Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = if (snapshot.connectionHealth.isHealthy) EmeraldPrimary else WarningAmber,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.height(0.dp))
                Text(
                    "  ${snapshot.connectionHealth.label}",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(snapshot.connectionHealth.detail, fontSize = 11.sp, color = MutedText)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("Resolver", snapshot.resolverLabel.take(28))
                Stat("Avg latency", snapshot.averageLatencyMs?.let { "$it ms" } ?: "—")
            }
        }
    }
}

@Composable
private fun CountersRow(snapshot: LiveNetworkModel.NetworkSnapshot) {
    Card(colors = CardDefaults.cardColors(containerColor = CardSurface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("Logged queries", snapshot.totalQueries.toString())
                Stat("Blocked", snapshot.blockedQueries.toString())
                Stat("Allowed", snapshot.allowedQueries.toString())
                Stat("Threats", snapshot.threatsBlocked.toString())
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { (snapshot.blockRatePercent / 100f).coerceIn(0f, 1f) },
                color = EmeraldPrimary,
                modifier = Modifier.fillMaxWidth().height(5.dp)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${"%.1f".format(snapshot.blockRatePercent)}% of logged queries were blocked · " +
                    "${snapshot.uniqueDomains} distinct domains",
                fontSize = 10.sp,
                color = MutedText
            )
        }
    }
}

@Composable
private fun CategoryBreakdown(snapshot: LiveNetworkModel.NetworkSnapshot) {
    Card(colors = CardDefaults.cardColors(containerColor = CardSurface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("What was blocked", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            snapshot.categories.forEach { slice ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                    Icon(Icons.Default.Block, contentDescription = null, tint = laneColor(slice.lane), modifier = Modifier.size(14.dp))
                    Text("  ${slice.lane.label}", fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text("${slice.count}  (${"%.0f".format(slice.shareOfTotal)}%)", fontSize = 11.sp, color = MutedText)
                }
            }
        }
    }
}

@Composable
private fun TopAppsCard(snapshot: LiveNetworkModel.NetworkSnapshot) {
    Card(colors = CardDefaults.cardColors(containerColor = CardSurface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("Top apps by DNS activity", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(
                "Attribution comes from Android's UID for each DNS request; some traffic cannot be attributed.",
                fontSize = 10.sp,
                color = MutedText
            )
            Spacer(Modifier.height(8.dp))
            snapshot.topApps.take(5).forEach { app ->
                Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(app.appName, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text("${app.totalQueries}", fontSize = 11.sp, color = MutedText)
                    Spacer(Modifier.height(0.dp))
                    Text("  ·  ${app.blockedQueries} blocked", fontSize = 11.sp, color = WarningAmber)
                }
            }
        }
    }
}

@Composable
private fun EventFeed(snapshot: LiveNetworkModel.NetworkSnapshot) {
    Column {
        Text("Live event feed", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        snapshot.recentEvents.forEach { event ->
            Card(
                colors = CardDefaults.cardColors(containerColor = CardSurface),
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
            ) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .background(laneColor(event.lane), RoundedCornerShape(50))
                    )
                    Spacer(Modifier.height(0.dp))
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(event.domain, fontSize = 12.sp)
                        Text(event.reason, fontSize = 10.sp, color = MutedText)
                    }
                    Text(
                        DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(event.timestampEpochMs)),
                        fontSize = 9.sp,
                        color = MutedText
                    )
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White)
        Text(label, fontSize = 9.sp, color = MutedText)
    }
}
