package com.adshield.pro.ui.screens

import com.adshield.pro.BuildConfig
import com.adshield.pro.R

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adshield.pro.data.local.preferences.AppSettingsState
import com.adshield.pro.domain.model.BatteryThermalSnapshot
import com.adshield.pro.domain.model.DeviceHealthAssessment
import com.adshield.pro.domain.model.ProtectionMode
import com.adshield.pro.domain.model.RamSnapshot
import com.adshield.pro.domain.model.SmartBoostResult
import com.adshield.pro.domain.model.StorageMetrics
import com.adshield.pro.domain.model.TimeBucketStat
import com.adshield.pro.domain.model.VpnRuntimeState
import com.adshield.pro.domain.model.VpnStatus
import com.adshield.pro.engine.optimizer.RuleBasedOptimizationPredictor
import com.adshield.pro.ui.theme.AlertRed
import com.adshield.pro.ui.theme.CyberCyan
import com.adshield.pro.ui.theme.EmeraldGlow
import com.adshield.pro.ui.theme.EmeraldPrimary
import com.adshield.pro.ui.theme.WarningAmber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    vpnState: VpnRuntimeState,
    settings: AppSettingsState,
    allTimeTotal: Long,
    allTimeBlocked: Long,
    allTimeAllowed: Long,
    chartBuckets: List<TimeBucketStat>,
    isUpdatingFilters: Boolean,
    ramSnapshot: RamSnapshot,
    storageMetrics: StorageMetrics,
    batterySnapshot: BatteryThermalSnapshot,
    healthAssessment: DeviceHealthAssessment,
    smartBoostResult: SmartBoostResult?,
    isRunningSmartBoost: Boolean,
    isCheckingForUpdates: Boolean,
    onCheckForUpdates: () -> Unit,
    onToggleVpn: (Boolean) -> Unit,
    onPauseResumeVpn: () -> Unit,
    onSelectProtectionMode: (ProtectionMode) -> Unit,
    onTriggerFilterSync: () -> Unit,
    onRunOptimizeNow: () -> Unit,
    onOpenOptimizerTab: () -> Unit
) {
    val isConnected = vpnState.status == VpnStatus.CONNECTED
    val isPaused = vpnState.status == VpnStatus.PAUSED
    val isActiveOrPaused = isConnected || isPaused

    val shieldColor by animateColorAsState(
        targetValue = when (vpnState.status) {
            VpnStatus.CONNECTED -> EmeraldPrimary
            VpnStatus.PAUSED -> WarningAmber
            VpnStatus.STARTING, VpnStatus.RECONNECTING -> CyberCyan
            VpnStatus.ERROR -> AlertRed
            VpnStatus.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "shield_color"
    )

    val shieldScale by animateFloatAsState(
        targetValue = when (vpnState.status) {
            VpnStatus.CONNECTED -> 1f
            VpnStatus.PAUSED -> 0.94f
            VpnStatus.STARTING, VpnStatus.RECONNECTING -> 1.08f
            VpnStatus.ERROR -> 0.9f
            VpnStatus.DISCONNECTED -> 0.86f
        },
        animationSpec = tween(durationMillis = 420),
        label = "shield_state_scale"
    )
    val glowPulse = remember { Animatable(0f) }
    LaunchedEffect(isConnected) {
        if (!isConnected) {
            glowPulse.snapTo(0f)
            return@LaunchedEffect
        }
        while (true) {
            glowPulse.animateTo(1f, tween(durationMillis = 1400, easing = FastOutSlowInEasing))
            glowPulse.animateTo(0f, tween(durationMillis = 1400, easing = FastOutSlowInEasing))
        }
    }
    val outerWireRotation = remember { Animatable(0f) }
    val innerWireRotation = remember { Animatable(0f) }
    LaunchedEffect(isConnected) {
        if (!isConnected) {
            outerWireRotation.snapTo(0f)
            innerWireRotation.snapTo(0f)
            return@LaunchedEffect
        }
        coroutineScope {
            launch {
                while (true) {
                    outerWireRotation.animateTo(360f, tween(4200, easing = LinearEasing))
                    outerWireRotation.snapTo(0f)
                }
            }
            launch {
                while (true) {
                    innerWireRotation.animateTo(-360f, tween(3100, easing = LinearEasing))
                    innerWireRotation.snapTo(0f)
                }
            }
        }
    }
    val ringScale by animateFloatAsState(
        targetValue = if (vpnState.status == VpnStatus.STARTING || vpnState.status == VpnStatus.RECONNECTING) 1.1f else 1f,
        animationSpec = tween(durationMillis = 520),
        label = "shield_ring_scale"
    )

    // Combine session + historical stats safely (never fabricated)
    val displayTotal = maxOf(vpnState.sessionTotalQueries, allTimeTotal)
    val displayBlocked = maxOf(vpnState.sessionBlockedQueries, allTimeBlocked)
    val displayAllowed = maxOf(vpnState.sessionAllowedQueries, allTimeAllowed)
    val displayBlockPct = if (displayTotal > 0L) {
        (displayBlocked.toFloat() / displayTotal.toFloat()) * 100f
    } else 0f

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Top Header Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_adshield_mark),
                    contentDescription = "AdShield Pro shield logo",
                    modifier = Modifier.size(30.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "AdShield Pro",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "v${BuildConfig.VERSION_NAME} • Multi-Layer Local DNS & Browser Firewall",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Surface(
                modifier = Modifier.clickable(
                    enabled = !isCheckingForUpdates,
                    onClickLabel = "Check for app updates",
                    onClick = onCheckForUpdates
                ),
                shape = RoundedCornerShape(50),
                color = EmeraldPrimary.copy(alpha = 0.18f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.SystemUpdateAlt,
                        contentDescription = "Check for app updates",
                        tint = EmeraldGlow,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isCheckingForUpdates) "Checking…" else "Check updates",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = EmeraldGlow,
                        maxLines = 1
                    )
                }
            }
        }

        // Main Hero Shield & One-Tap Switch Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = shieldColor.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(24.dp)
                ),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                shieldColor.copy(alpha = 0.14f),
                                Color.Transparent
                            )
                        )
                    )
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                    // Bright halo and rotating signal arcs animate only while protection is active.
                Box(
                    modifier = Modifier.fillMaxWidth().height(184.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier.size(190.dp)
                            .graphicsLayer {
                                val pulse = glowPulse.value
                                alpha = if (isConnected) 0.68f + 0.30f * pulse else 0f
                                scaleX = 0.92f + 0.12f * pulse
                                scaleY = 0.92f + 0.12f * pulse
                            }
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        shieldColor.copy(alpha = 0.92f),
                                        shieldColor.copy(alpha = 0.55f),
                                        shieldColor.copy(alpha = 0.18f),
                                        Color.Transparent
                                    )
                                ),
                                CircleShape
                            )
                    )
                    Canvas(
                        modifier = Modifier.size(184.dp).graphicsLayer {
                            scaleX = ringScale
                            scaleY = ringScale
                            alpha = if (isConnected) 1f else 0.48f
                        }
                    ) {
                        val centerOffset = Offset(size.width / 2f, size.height / 2f)
                        val outerRadius = size.minDimension * 0.48f
                        val innerRadius = size.minDimension * 0.40f
                        drawCircle(shieldColor.copy(alpha = 0.25f), outerRadius, centerOffset, style = Stroke(1.dp.toPx()))
                        drawCircle(shieldColor.copy(alpha = 0.38f), innerRadius, centerOffset, style = Stroke(1.dp.toPx()))
                        if (isConnected) {
                            rotate(outerWireRotation.value, centerOffset) {
                                drawArc(
                                    color = EmeraldGlow.copy(alpha = 0.32f), startAngle = 0f, sweepAngle = 92f,
                                    useCenter = false,
                                    topLeft = Offset(centerOffset.x - outerRadius, centerOffset.y - outerRadius),
                                    size = Size(outerRadius * 2, outerRadius * 2),
                                    style = Stroke(width = 9.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                                )
                                drawArc(
                                    color = Color(0xFF72FFD0), startAngle = 0f, sweepAngle = 92f,
                                    useCenter = false,
                                    topLeft = Offset(centerOffset.x - outerRadius, centerOffset.y - outerRadius),
                                    size = Size(outerRadius * 2, outerRadius * 2),
                                    style = Stroke(width = 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                                )
                            }
                            rotate(innerWireRotation.value, centerOffset) {
                                drawArc(
                                    color = EmeraldGlow.copy(alpha = 0.30f), startAngle = 185f, sweepAngle = 74f,
                                    useCenter = false,
                                    topLeft = Offset(centerOffset.x - innerRadius, centerOffset.y - innerRadius),
                                    size = Size(innerRadius * 2, innerRadius * 2),
                                    style = Stroke(width = 7.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                                )
                                drawArc(
                                    color = Color(0xFF8CFFE0), startAngle = 185f, sweepAngle = 74f,
                                    useCenter = false,
                                    topLeft = Offset(centerOffset.x - innerRadius, centerOffset.y - innerRadius),
                                    size = Size(innerRadius * 2, innerRadius * 2),
                                    style = Stroke(width = 2.5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                                )
                            }
                        }
                    }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(128.dp).scale(shieldScale)
                            .clip(CircleShape)
                            .background(shieldColor.copy(alpha = 0.14f))
                            .border(2.dp, shieldColor.copy(alpha = 0.6f), CircleShape)
                            .clickable(onClickLabel = if (isActiveOrPaused) "Turn protection off" else "Turn protection on") {
                                onToggleVpn(!isActiveOrPaused)
                            }
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_adshield_mark),
                            contentDescription = "Protection status: ${vpnState.status.name.lowercase()}",
                            alpha = if (isConnected) 1f else 0.82f,
                            modifier = Modifier.size(84.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))

                // Actual VPN Connection Status Badge
                Surface(
                    color = shieldColor.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(50)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(shieldColor)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when (vpnState.status) {
                                VpnStatus.CONNECTED -> "Protected • ${vpnState.activeNetworkLabel}"
                                VpnStatus.PAUSED -> "Filtering paused"
                                VpnStatus.STARTING -> "Starting protection…"
                                VpnStatus.RECONNECTING -> "Reconnecting…"
                                VpnStatus.ERROR -> "Protection needs attention"
                                VpnStatus.DISCONNECTED -> "Protection off"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = shieldColor
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = when (vpnState.status) {
                        VpnStatus.CONNECTED -> "Filtering ads, trackers, and malware across apps via ${settings.resolverConfig.protocol.label}."
                        VpnStatus.PAUSED -> "Your connection is active, but DNS filtering is paused. Tap Resume to restart filtering."
                        VpnStatus.ERROR -> vpnState.lastError ?: "Unable to establish VPN tunnel."
                        else -> "Tap the switch below to start device-wide non-root DNS & VPN protection."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(14.dp))

                // One-Tap Switch Row (Cleanly separated from Pause/Resume button)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = null,
                            tint = shieldColor
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = when (vpnState.status) {
                                    VpnStatus.CONNECTED -> "Protection is on"
                                    VpnStatus.PAUSED -> "Filtering is paused"
                                    VpnStatus.STARTING, VpnStatus.RECONNECTING -> "Connecting…"
                                    VpnStatus.ERROR -> "Protection needs attention"
                                    VpnStatus.DISCONNECTED -> "Turn on protection"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (isConnected) "${vpnState.loadedRuleCount} rules active" else if (isPaused) "Filtering is paused" else "Device-wide DNS filtering",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Switch(
                        checked = isActiveOrPaused,
                        onCheckedChange = { onToggleVpn(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = EmeraldPrimary,
                            checkedBorderColor = EmeraldPrimary,
                            uncheckedThumbColor = Color(0xFF9AA4B2),
                            uncheckedTrackColor = Color(0xFF1E2636),
                            uncheckedBorderColor = Color(0xFF3A4558)
                        )
                    )
                }

                if (isActiveOrPaused) {
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onPauseResumeVpn,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = if (isPaused) Icons.Default.PlayCircle else Icons.Default.PauseCircle,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (isPaused) WarningAmber else EmeraldPrimary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isPaused) "Resume filtering" else "Pause filtering",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // Live DNS Request Counters (4-Card Grid — Real Queries Only)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DashboardStatCard(
                modifier = Modifier.weight(1f),
                title = "Total DNS Queries",
                value = java.text.NumberFormat.getIntegerInstance().format(displayTotal),
                subtitle = "Session: ${vpnState.sessionTotalQueries}",
                accentColor = CyberCyan
            )
            DashboardStatCard(
                modifier = Modifier.weight(1f),
                title = "Blocked Queries",
                value = java.text.NumberFormat.getIntegerInstance().format(displayBlocked),
                subtitle = "Ads & Trackers",
                accentColor = AlertRed
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DashboardStatCard(
                modifier = Modifier.weight(1f),
                title = "Allowed Queries",
                value = java.text.NumberFormat.getIntegerInstance().format(displayAllowed),
                subtitle = "Cached: ${vpnState.sessionCachedQueries}",
                accentColor = EmeraldPrimary
            )
            DashboardStatCard(
                modifier = Modifier.weight(1f),
                title = "Blocking Rate",
                value = if (displayTotal > 0L) String.format(Locale.getDefault(), "%.1f%%", displayBlockPct) else "—",
                subtitle = if (displayTotal > 0L) "Average latency: ${vpnState.averageLatencyMs} ms" else "No DNS requests yet",
                accentColor = WarningAmber
            )
        }

        // Prominent AI Smart Boost & Phone Optimization Card ("OPTIMIZE NOW")
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = CyberCyan.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(20.dp)
                ),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = null,
                            tint = CyberCyan,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "AI Smart Boost & Phone Optimizer",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "RAM: ${String.format(Locale.US, "%.0f%%", ramSnapshot.usagePercent)} • Free Storage: ${RuleBasedOptimizationPredictor.formatBytes(storageMetrics.availableBytes)} • ${String.format(Locale.US, "%.1f°C", batterySnapshot.temperatureCelsius)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Surface(
                        color = EmeraldPrimary.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.clickable { onOpenOptimizerTab() }
                    ) {
                        Text(
                            text = "Score ${healthAssessment.readinessScore}/100",
                            style = MaterialTheme.typography.labelMedium,
                            color = EmeraldGlow,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                Button(
                    onClick = onRunOptimizeNow,
                    enabled = !isRunningSmartBoost,
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Bolt, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isRunningSmartBoost) "ANALYZING & OPTIMIZING..." else "OPTIMIZE NOW",
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }

                if (isRunningSmartBoost) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = EmeraldPrimary
                    )
                }

                if (smartBoostResult != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = if (smartBoostResult.wasSkippedDueToRecentBoost) {
                                    "Optimization Summary: Already Optimized"
                                } else {
                                    "Optimization Summary (${RuleBasedOptimizationPredictor.formatBytes(smartBoostResult.bytesRecoveredAutomatically)} recovered)"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = EmeraldPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            smartBoostResult.actionsCompleted.forEach { act ->
                                Text(
                                    text = "✓ $act",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            if (smartBoostResult.pendingUserApprovalActions.isNotEmpty()) {
                                smartBoostResult.pendingUserApprovalActions.forEach { pending ->
                                    Text(
                                        text = "• Approval Needed: $pending",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = WarningAmber
                                    )
                                }
                            }
                            OutlinedButton(
                                onClick = onOpenOptimizerTab,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Open Full Phone Optimizer Suite")
                            }
                        }
                    }
                }
            }
        }

        // Real-Time Activity Chart Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "DNS Filtering Activity (Last 7 Days)",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Green = allowed • Red = blocked",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                RealTimeActivityBarChart(
                    buckets = chartBuckets,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                )
            }
        }

        // Protection Mode Selector (Standard, Strict, Privacy, Custom)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Active Protection Profile",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Switching profiles updates the in-memory filter engine immediately.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(10.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProtectionMode.entries.forEach { mode ->
                        val isSelected = settings.protectionMode == mode
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (isSelected) {
                                EmeraldPrimary.copy(alpha = 0.15f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    width = if (isSelected) 1.5.dp else 0.dp,
                                    color = if (isSelected) EmeraldPrimary else Color.Transparent,
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .clickable { onSelectProtectionMode(mode) }
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.Security,
                                            contentDescription = null,
                                            tint = if (isSelected) EmeraldPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = mode.title,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = if (isSelected) EmeraldGlow else MaterialTheme.colorScheme.onSurface
                                        )
                                    }

                                }

                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = mode.subtitle,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                if (isSelected && mode.warningNote != null) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Warning,
                                            contentDescription = null,
                                            tint = WarningAmber,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = mode.warningNote,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = WarningAmber
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // DNS Resolver & Last Blocklist Update Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Dns,
                            contentDescription = null,
                            tint = CyberCyan
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Resolver: ${settings.resolverConfig.preset.displayName}",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "${settings.resolverConfig.protocol.label} • IPv6 ${if (settings.resolverConfig.enableIpv6) "Enabled" else "Disabled"}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    IconButton(onClick = onTriggerFilterSync, enabled = !isUpdatingFilters) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Sync Filter Lists",
                            tint = EmeraldPrimary
                        )
                    }
                }

                if (isUpdatingFilters) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = EmeraldPrimary
                    )
                }

                val lastSyncStr = if (settings.lastFilterUpdateEpochMs > 0L) {
                    SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.US)
                        .format(Date(settings.lastFilterUpdateEpochMs))
                } else {
                    "Bundled v2.0 Baseline Ready (Tap Refresh to sync online lists)"
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = EmeraldPrimary,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Last Filter Sync: $lastSyncStr",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Transparent Technical Coverage Disclosure Card (Required by Prompt)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = CyberCyan,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Transparent Coverage & Privacy Disclosure",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "AdShield Pro blocks third-party ad SDKs, trackers, and malicious domains across games, utilities, news apps, and browsers without root or HTTPS decryption. Official YouTube, Facebook, TikTok, and some streaming apps deliver video ads from the same first-party servers as primary content and cannot be 100% blocked via DNS alone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun DashboardStatCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    subtitle: String,
    accentColor: Color
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                color = accentColor,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun RealTimeActivityBarChart(
    buckets: List<TimeBucketStat>,
    modifier: Modifier = Modifier
) {
    val maxTotal = (buckets.maxOfOrNull { it.totalQueries } ?: 1L).coerceAtLeast(1L)

    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (buckets.isEmpty()) return@Canvas
            val count = buckets.size
            val slotWidth = size.width / count
            val barWidth = (slotWidth * 0.55f).coerceAtMost(36.dp.toPx())

            buckets.forEachIndexed { index, bucket ->
                val centerX = index * slotWidth + (slotWidth / 2f)
                val left = centerX - (barWidth / 2f)

                val totalRatio = (bucket.totalQueries.toFloat() / maxTotal.toFloat()).coerceIn(0.04f, 1f)
                val blockedRatio = (bucket.blockedQueries.toFloat() / maxTotal.toFloat()).coerceIn(0f, totalRatio)

                val totalHeight = size.height * totalRatio
                val blockedHeight = size.height * blockedRatio

                // Allowed bar background (Emerald)
                drawRoundRect(
                    color = EmeraldPrimary.copy(alpha = 0.45f),
                    topLeft = Offset(left, size.height - totalHeight),
                    size = Size(barWidth, totalHeight),
                    cornerRadius = CornerRadius(8f, 8f)
                )

                // Blocked portion overlay (Red)
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

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            buckets.forEach { b ->
                Text(
                    text = b.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
