package com.adshield.pro.ui.screens.intelligence

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adshield.pro.domain.intelligence.IntelligenceAction
import com.adshield.pro.domain.intelligence.IntelligenceEvent
import com.adshield.pro.domain.intelligence.IntelligenceFilters
import com.adshield.pro.domain.intelligence.IntelligenceMetric
import com.adshield.pro.domain.intelligence.IntelligenceModule
import com.adshield.pro.ui.model.ConnectorModuleSummaryUi
import com.adshield.pro.domain.intelligence.IntelligenceModuleState
import com.adshield.pro.domain.intelligence.IntelligenceSeverity
import com.adshield.pro.domain.intelligence.IntelligenceTimeRange
import com.adshield.pro.domain.intelligence.ModuleSnapshot
import com.adshield.pro.engine.intelligence.IntelligenceRecommendations
import com.adshield.pro.ui.model.IntelligenceMapUiState
import com.adshield.pro.ui.theme.AlertRed
import com.adshield.pro.ui.theme.CyberCyan
import com.adshield.pro.ui.theme.ElectricBlue
import com.adshield.pro.ui.theme.EmeraldPrimary
import com.adshield.pro.ui.theme.NavyBorder
import com.adshield.pro.ui.theme.SkyBlueGlow
import com.adshield.pro.ui.theme.WarningAmber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PanelShape = RoundedCornerShape(16.dp)

/** Glass-style surface used by every overlay on the map. */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .clip(PanelShape)
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xE6071626), Color(0xF0030A14))
                )
            )
            .border(1.dp, NavyBorder.copy(alpha = 0.9f), PanelShape)
    ) {
        content()
    }
}

@Composable
fun StatePill(
    state: IntelligenceModuleState,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val color = stateColor(state)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
            .padding(horizontal = if (compact) 5.dp else 8.dp, vertical = if (compact) 2.dp else 3.dp)
    ) {
        Text(
            text = state.label,
            color = color,
            fontSize = if (compact) 8.sp else 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            maxLines = 1
        )
    }
}

@Composable
fun SeverityPill(severity: IntelligenceSeverity, modifier: Modifier = Modifier) {
    val color = severityColor(severity)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(5.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = severity.label,
            color = color,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.4.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun ActionButton(
    action: IntelligenceAction,
    enabled: Boolean,
    onRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    val destructive = action == IntelligenceAction.STOP_PROTECTION ||
        action == IntelligenceAction.TOGGLE_LOCKDOWN
    OutlinedButton(
        onClick = onRequest,
        enabled = enabled,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = action.label,
            fontSize = 11.sp,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                destructive -> WarningAmber
                else -> CyberCyan
            },
            maxLines = 1
        )
    }
}

@Composable
fun IntelligenceTopBar(
    state: IntelligenceMapUiState,
    onToggleLive: () -> Unit,
    onOpenTimeline: () -> Unit,
    onResetCamera: () -> Unit,
    onToggleLayoutEditing: () -> Unit,
    onResetLayout: () -> Unit,
    onOpenCommandPanel: () -> Unit,
    onOpenWorldMonitor: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val system = state.system
    val overallColor = stateColor(system.overallState)
    GlassPanel(modifier = modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "OMNISHIELD X // LIVE INTELLIGENCE",
                        color = Color(0xFFF8FAFC),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = system.overallLabel,
                        color = overallColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                OutlinedButton(
                    onClick = onOpenWorldMonitor,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
                    modifier = Modifier.height(30.dp).padding(end = 6.dp)
                ) {
                    Icon(Icons.Default.Public, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("3D Earth", color = CyberCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(overallColor.copy(alpha = 0.12f))
                        .border(1.dp, overallColor.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "${system.healthPercent}%",
                        color = overallColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { system.healthPercent / 100f },
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                color = overallColor,
                trackColor = Color(0xFF10304C)
            )
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                IconButton(onClick = onToggleLive, modifier = Modifier.size(34.dp)) {
                    Icon(
                        imageVector = if (state.isLive) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isLive) "Pause visual updates" else "Resume live updates",
                        tint = if (state.isLive) CyberCyan else WarningAmber,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    text = when {
                        state.isLive -> "LIVE"
                        else -> "PAUSED · ${state.newEventsWhilePaused} NEW"
                    },
                    color = if (state.isLive) CyberCyan else WarningAmber,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp,
                    modifier = Modifier.width(84.dp),
                    maxLines = 1
                )
                Text(
                    text = "${system.storedEventCount} EVENTS · ${system.buildDurationMs} ms BUILD",
                    color = Color(0xFF7C93AF),
                    fontSize = 8.sp,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onOpenTimeline, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Default.Timeline,
                        contentDescription = "Event timeline, filters and search",
                        tint = SkyBlueGlow,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(onClick = onResetCamera, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Default.MyLocation,
                        contentDescription = "Reset camera to the fitted view",
                        tint = SkyBlueGlow,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(onClick = onToggleLayoutEditing, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = if (state.isEditingLayout) "Stop editing node layout" else "Edit node layout",
                        tint = if (state.isEditingLayout) WarningAmber else SkyBlueGlow,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(onClick = onResetLayout, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Default.RestartAlt,
                        contentDescription = "Reset node layout",
                        tint = SkyBlueGlow,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(onClick = onOpenCommandPanel, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Default.FilterAlt,
                        contentDescription = "Command panel",
                        tint = SkyBlueGlow,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            if (state.isEditingLayout) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "LAYOUT EDIT: drag a node to reposition it. Pinch still zooms. Positions are saved on this device.",
                    color = WarningAmber,
                    fontSize = 9.sp,
                    maxLines = 2
                )
            }
            if (!state.isLive) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Visual updates paused. DNS protection, VPN filtering and scheduled jobs keep running.",
                    color = Color(0xFF94A3B8),
                    fontSize = 9.sp,
                    maxLines = 2
                )
            }
        }
    }
}

@Composable
fun IntelligenceCommandPanel(
    state: IntelligenceMapUiState,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onSelectModule: (IntelligenceModule) -> Unit,
    onOpenModule: (IntelligenceModule) -> Unit,
    onOpenTimeline: () -> Unit,
    onOpenConnectors: () -> Unit = {},
    onOpenWorldMonitor: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val system = state.system
    GlassPanel(modifier = modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpanded() }
            ) {
                Text(
                    text = "COMMAND",
                    color = StateAccent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${system.activeIssueCount} INCIDENT(S) · ${system.monitoringModuleCount} NOMINAL",
                    color = Color(0xFF94A3B8),
                    fontSize = 9.sp,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                    contentDescription = if (expanded) "Collapse command panel" else "Expand command panel",
                    tint = Color(0xFF7C93AF),
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(
                    items = IntelligenceModule.nodes,
                    key = { module -> module.id },
                    contentType = { "module-chip" }
                ) { module ->
                    val snapshot = system.snapshotFor(module)
                    ModuleChip(
                        module = module,
                        snapshot = snapshot,
                        selected = state.selectedModule == module,
                        onClick = { onSelectModule(module) }
                    )
                }
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "SYSTEM METRICS (MEASURED / COMPUTED)",
                        color = Color(0xFF7C93AF),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    system.commandMetrics.chunked(2).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            row.forEach { metric ->
                                CommandMetricTile(metric, Modifier.weight(1f))
                            }
                            repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "ACTIVE INCIDENTS",
                        color = Color(0xFF7C93AF),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    if (system.incidents.isEmpty()) {
                        Text(
                            text = "No verified issue is active in the last 24 hours.",
                            color = EmeraldPrimary,
                            fontSize = 10.sp
                        )
                    } else {
                        system.incidents.take(4).forEach { incident ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectModule(incident.module) }
                                    .padding(vertical = 3.dp)
                            ) {
                                SeverityPill(incident.severity)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = incident.title,
                                    color = Color(0xFFE2E8F0),
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = incident.module.shortLabel,
                                    color = moduleAccent(incident.module),
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = onOpenTimeline,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("Timeline & filters", fontSize = 10.sp, color = CyberCyan)
                        }
                        OutlinedButton(
                            onClick = { onOpenModule(state.selectedModule ?: IntelligenceModule.NETWORK_DEFENSE) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("Open selected module", fontSize = 10.sp, color = CyberCyan)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        TextButton(onClick = onOpenConnectors, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)) {
                            Text("Feeds & connectors →", fontSize = 9.sp, color = SkyBlueGlow)
                        }
                        TextButton(onClick = onOpenWorldMonitor, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)) {
                            Text("3D World Monitor 2035 →", fontSize = 9.sp, color = CyberCyan, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Every value above is read from this device. Empty modules say so instead of inventing activity.",
                        color = Color(0xFF64748B),
                        fontSize = 8.sp,
                        maxLines = 2
                    )
                }
            }
        }
    }
}

private val StateAccent = CyberCyan

@Composable
private fun ModuleChip(
    module: IntelligenceModule,
    snapshot: ModuleSnapshot?,
    selected: Boolean,
    onClick: () -> Unit
) {
    val state = snapshot?.state ?: IntelligenceModuleState.OFFLINE
    val color = stateColor(state)
    Column(
        modifier = Modifier
            .width(76.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) color.copy(alpha = 0.16f) else Color(0xFF071322))
            .border(
                1.dp,
                if (selected) color.copy(alpha = 0.85f) else NavyBorder.copy(alpha = 0.8f),
                RoundedCornerShape(9.dp)
            )
            .clickable { onClick() }
            .padding(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = module.shortLabel,
                color = Color(0xFFE2E8F0),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = state.label,
            color = color,
            fontSize = 8.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
        Text(
            text = if ((snapshot?.activeAlerts ?: 0) > 0) {
                "${snapshot?.activeAlerts} alert(s)"
            } else {
                snapshot?.lastUpdateEpochMs?.let { formatRelative(it) } ?: "no data yet"
            },
            color = Color(0xFF7C93AF),
            fontSize = 7.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CommandMetricTile(metric: IntelligenceMetric, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF061324))
            .border(1.dp, NavyBorder.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Text(
            text = metric.label.uppercase(),
            color = Color(0xFF7C93AF),
            fontSize = 7.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.4.sp,
            maxLines = 1
        )
        Text(
            text = metric.value,
            color = Color(0xFFE2E8F0),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = metric.source.label,
            color = when (metric.source.name) {
                "MEASURED" -> EmeraldPrimary
                "COMPUTED" -> CyberCyan
                "CONFIGURED" -> SkyBlueGlow
                else -> WarningAmber
            },
            fontSize = 7.sp,
            maxLines = 1
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeDetailSheet(
    state: IntelligenceMapUiState,
    module: IntelligenceModule,
    onDismiss: () -> Unit,
    onOpenModule: (IntelligenceModule) -> Unit,
    onRequestAction: (IntelligenceAction, IntelligenceModule) -> Unit,
    onSelectEvent: (IntelligenceEvent) -> Unit,
    onCenterOnModule: (IntelligenceModule) -> Unit,
    connectorSummary: ConnectorModuleSummaryUi? = null,
    onOpenConnectors: () -> Unit = {}
) {
    val snapshot = state.system.snapshotFor(module) ?: return
    val history = state.historyFor(module)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.82f).dp
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF04101D),
        contentColor = Color(0xFFE2E8F0)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = maxSheetHeight),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "detail-header", contentType = "header") {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(stateColor(snapshot.state))
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = module.displayName.uppercase(),
                            color = Color(0xFFF8FAFC),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                            modifier = Modifier.weight(1f)
                        )
                        StatePill(snapshot.state)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = module.purpose,
                        color = Color(0xFF94A3B8),
                        fontSize = 10.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = snapshot.stateDetail,
                        color = stateColor(snapshot.state),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Last update: " +
                            (snapshot.lastUpdateEpochMs?.let { formatTimestamp(it) } ?: "no timestamp — not observed yet"),
                        color = Color(0xFF7C93AF),
                        fontSize = 9.sp
                    )
                }
            }

            item(key = "detail-metrics", contentType = "metrics") {
                Column {
                    SectionTitle("MEASUREMENTS & STATE")
                    snapshot.metrics.forEach { metric ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = metric.label,
                                color = Color(0xFF94A3B8),
                                fontSize = 10.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = metric.value,
                                color = Color(0xFFE2E8F0),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = metric.source.label,
                                color = CyberCyan,
                                fontSize = 8.sp
                            )
                        }
                    }
                    if (snapshot.activeAlerts > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${snapshot.activeAlerts} unmitigated alert(s) in the last 24 h",
                            color = WarningAmber,
                            fontSize = 10.sp
                        )
                    }
                }
            }

            item(key = "detail-latest", contentType = "latest") {
                Column {
                    SectionTitle("LATEST VERIFIED EVENT")
                    val event = snapshot.latestEvent
                    if (event == null) {
                        Text(
                            text = "No stored event for this module yet. The state above comes from live readings only.",
                            color = Color(0xFF94A3B8),
                            fontSize = 10.sp
                        )
                    } else {
                        EventCard(event, onClick = { onSelectEvent(event) })
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "EVIDENCE",
                            color = Color(0xFF7C93AF),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = event.evidence,
                            color = Color(0xFFCBD5E1),
                            fontSize = 10.sp
                        )
                    }
                }
            }

            item(key = "detail-connectors", contentType = "connectors") {
                connectorSummary?.let { summary ->
                    GlassPanel(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                "MODULE CONNECTORS",
                                color = Color(0xFF7C93AF),
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                            Text(
                                "${summary.implemented}/${summary.total} implemented · ${summary.connected} connected now",
                                color = Color(0xFFCBD5E1),
                                fontSize = 10.sp
                            )
                            Text(
                                summary.lastSuccessEpochMs?.let { "Last successful sync ${formatRelative(it)}" }
                                    ?: "No successful sync recorded yet",
                                color = Color(0xFF7C93AF),
                                fontSize = 8.sp
                            )
                            Spacer(Modifier.height(4.dp))
                            TextButton(
                                onClick = onOpenConnectors,
                                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                            ) {
                                Text("Open connector registry →", fontSize = 9.sp, color = SkyBlueGlow)
                            }
                        }
                    }
                }
            }

            item(key = "detail-recommendations", contentType = "recommendations") {
                Column {
                    SectionTitle("RECOMMENDED ACTIONS")
                    IntelligenceRecommendations.forSnapshot(snapshot).forEach { line ->
                        Row(modifier = Modifier.padding(vertical = 1.dp)) {
                            Text("•", color = CyberCyan, fontSize = 11.sp)
                            Spacer(Modifier.width(6.dp))
                            Text(line, color = Color(0xFFCBD5E1), fontSize = 10.sp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    SectionTitle("AVAILABLE MODULE CONTROLS")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            snapshot.actions.filter { it != IntelligenceAction.OPEN_MODULE }.forEach { action ->
                                val enabled = isActionAvailable(action, state, module)
                                ActionButton(
                                    action = action,
                                    enabled = enabled,
                                    onRequest = { onRequestAction(action, module) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            if (snapshot.actions.none { it != IntelligenceAction.OPEN_MODULE }) {
                                Text(
                                    text = "This module exposes no direct control here. Open it to act on the evidence.",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onOpenModule(module) },
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
                        ) {
                            Text("Open ${module.shortLabel}", fontSize = 11.sp)
                        }
                        OutlinedButton(onClick = { onCenterOnModule(module) }) {
                            Text("Center on map", fontSize = 11.sp, color = CyberCyan)
                        }
                    }
                }
            }

            item(key = "detail-history-title", contentType = "history-title") {
                Column {
                    HorizontalDivider(color = NavyBorder.copy(alpha = 0.6f))
                    Spacer(Modifier.height(8.dp))
                    SectionTitle("EVENT HISTORY FOR THIS MODULE (${history.size})")
                }
            }

            if (history.isEmpty()) {
                item(key = "detail-history-empty", contentType = "history-empty") {
                    Text(
                        text = "No stored events for this module.",
                        color = Color(0xFF94A3B8),
                        fontSize = 10.sp
                    )
                }
            } else {
                items(
                    items = history,
                    key = { event -> "detail-history-${event.id}" },
                    contentType = { "detail-history-row" }
                ) { event ->
                    EventCard(event, onClick = { onSelectEvent(event) })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntelligenceTimelineSheet(
    state: IntelligenceMapUiState,
    onDismiss: () -> Unit,
    onSetModule: (IntelligenceModule?) -> Unit,
    onSetSeverity: (IntelligenceSeverity?) -> Unit,
    onSetRange: (IntelligenceTimeRange) -> Unit,
    onSetQuery: (String) -> Unit,
    onClearFilters: () -> Unit,
    onSelectEvent: (IntelligenceEvent) -> Unit,
    onRequestClearHistory: () -> Unit
) {
    val filters = state.filters
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val events = state.system.filteredEvents
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.88f).dp
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF04101D),
        contentColor = Color(0xFFE2E8F0)
    ) {
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = maxSheetHeight)) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "EVENT TIMELINE",
                        color = Color(0xFFF8FAFC),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${events.size} / ${state.system.storedEventCount}",
                        color = CyberCyan,
                        fontSize = 10.sp
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close timeline", tint = Color(0xFF94A3B8))
                    }
                }
                OutlinedTextField(
                    value = filters.query,
                    onValueChange = onSetQuery,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Search events, modules, evidence", fontSize = 10.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (filters.query.isNotEmpty()) {
                            IconButton(onClick = { onSetQuery("") }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear search")
                            }
                        }
                    }
                )
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item(key = "filter-all-modules", contentType = "module-filter") {
                        FilterChip(
                            selected = filters.module == null,
                            onClick = { onSetModule(null) },
                            label = { Text("All modules", fontSize = 9.sp) }
                        )
                    }
                    items(
                        items = IntelligenceModule.nodes,
                        key = { module -> "filter-${module.id}" },
                        contentType = { "module-filter" }
                    ) { module ->
                        FilterChip(
                            selected = filters.module == module,
                            onClick = { onSetModule(if (filters.module == module) null else module) },
                            label = { Text(module.shortLabel, fontSize = 9.sp) }
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(
                        items = IntelligenceSeverity.entries,
                        key = { severity -> "severity-${severity.name}" },
                        contentType = { "severity-filter" }
                    ) { severity ->
                        FilterChip(
                            selected = filters.severity == severity,
                            onClick = { onSetSeverity(if (filters.severity == severity) null else severity) },
                            label = { Text(severity.label, fontSize = 9.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = severityColor(severity).copy(alpha = 0.22f),
                                selectedLabelColor = severityColor(severity)
                            )
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(
                        items = IntelligenceTimeRange.entries,
                        key = { range -> "range-${range.name}" },
                        contentType = { "range-filter" }
                    ) { range ->
                        FilterChip(
                            selected = filters.range == range,
                            onClick = { onSetRange(range) },
                            label = { Text(range.label, fontSize = 9.sp) }
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onClearFilters, enabled = filters.isActive) {
                        Text("Reset filters", fontSize = 10.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onRequestClearHistory) {
                        Text("Clear history", fontSize = 10.sp, color = AlertRed)
                    }
                }
            }
            HorizontalDivider(color = NavyBorder.copy(alpha = 0.6f))
            if (events.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (state.isHistoryEmpty) {
                            "No intelligence event has been recorded yet. Protection can be fully active with no event — nothing is simulated to fill this list."
                        } else {
                            "No event matches the current filters."
                        },
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(
                        items = events,
                        key = { event -> "timeline-${event.id}" },
                        contentType = { "timeline-row" }
                    ) { event ->
                        EventCard(event, onClick = { onSelectEvent(event) })
                    }
                }
            }
        }
    }
}

@Composable
fun EventCard(event: IntelligenceEvent, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF071322))
            .border(1.dp, NavyBorder.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SeverityPill(event.severity)
            Spacer(Modifier.width(6.dp))
            Text(
                text = event.module.shortLabel,
                color = moduleAccent(event.module),
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = formatTimestamp(event.timestampEpochMs),
                color = Color(0xFF64748B),
                fontSize = 8.sp
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = event.title,
            color = Color(0xFFF1F5F9),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = event.evidence,
            color = Color(0xFF94A3B8),
            fontSize = 9.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (event.isMitigated) {
            Text(
                text = "Mitigated by protection",
                color = EmeraldPrimary,
                fontSize = 8.sp
            )
        }
    }
}

@Composable
fun ModuleQuickActionsDialog(
    module: IntelligenceModule,
    state: IntelligenceMapUiState,
    onDismiss: () -> Unit,
    onInspect: () -> Unit,
    onCenter: () -> Unit,
    onOpenModule: () -> Unit,
    onRequestAction: (IntelligenceAction) -> Unit
) {
    val snapshot = state.system.snapshotFor(module)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(module.displayName, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = snapshot?.stateDetail ?: "No snapshot available",
                    color = Color(0xFF94A3B8),
                    fontSize = 10.sp
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("CONTEXT ACTIONS", color = CyberCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                listOf(
                    "Inspect details" to onInspect,
                    "Center on map" to onCenter,
                    "Open module" to onOpenModule
                ).forEach { (label, action) ->
                    OutlinedButton(
                        onClick = { action() },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        Text(label, fontSize = 11.sp, color = CyberCyan)
                    }
                }
                val control = snapshot?.actions?.firstOrNull { it != IntelligenceAction.OPEN_MODULE }
                if (control != null) {
                    OutlinedButton(
                        onClick = { onRequestAction(control) },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        Text(control.label, fontSize = 11.sp, color = WarningAmber)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
fun ActionConfirmationDialog(
    action: IntelligenceAction,
    module: IntelligenceModule,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(action.label) },
        text = {
            Text(
                buildString {
                    append(action.description)
                    append("\n\nModule: ").append(module.displayName)
                    if (action == IntelligenceAction.TOGGLE_LOCKDOWN) {
                        append("\n\nLockdown blocks every domain that is not explicitly allowed. " +
                            "Normal browsing and app traffic will fail until you release it.")
                    }
                },
                fontSize = 12.sp
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    "Confirm",
                    color = if (action == IntelligenceAction.STOP_PROTECTION) AlertRed else CyberCyan
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun ClearHistoryConfirmationDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clear intelligence history?") },
        text = {
            Text(
                "This permanently deletes the stored intelligence events on this device. " +
                    "DNS logs, scan journal, settings and protection are not affected.",
                fontSize = 12.sp
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete events", color = AlertRed) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun isActionAvailable(
    action: IntelligenceAction,
    state: IntelligenceMapUiState,
    module: IntelligenceModule
): Boolean {
    val network = state.system.snapshotFor(IntelligenceModule.NETWORK_DEFENSE)
    return when (action) {
        IntelligenceAction.OPEN_MODULE, IntelligenceAction.SYNC_FILTERS, IntelligenceAction.RUN_APP_AUDIT,
        IntelligenceAction.RUN_DEVICE_DIAGNOSIS, IntelligenceAction.SCHEDULE_SCAN -> true
        IntelligenceAction.START_PROTECTION ->
            network?.state == IntelligenceModuleState.DISABLED || network?.state == IntelligenceModuleState.OFFLINE ||
                network?.state == IntelligenceModuleState.CRITICAL
        IntelligenceAction.STOP_PROTECTION ->
            network?.state == IntelligenceModuleState.MONITORING || network?.state == IntelligenceModuleState.WARNING
        IntelligenceAction.TOGGLE_LOCKDOWN -> module == IntelligenceModule.EMERGENCY_INTELLIGENCE
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = Color(0xFF7C93AF),
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.7.sp
    )
    Spacer(Modifier.height(4.dp))
}

private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
private val dateTimeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

fun formatTimestamp(epochMs: Long): String =
    if (isSameDay(epochMs)) timeFormatter.format(Date(epochMs))
    else dateTimeFormatter.format(Date(epochMs))

fun formatRelative(epochMs: Long): String {
    val deltaMinutes = ((System.currentTimeMillis() - epochMs) / 60_000L).coerceAtLeast(0L)
    return when {
        deltaMinutes < 1L -> "just now"
        deltaMinutes < 60L -> "${deltaMinutes}m ago"
        deltaMinutes < 60L * 24L -> "${deltaMinutes / 60L}h ago"
        else -> "${deltaMinutes / (60L * 24L)}d ago"
    }
}

private fun isSameDay(epochMs: Long): Boolean {
    val now = Date()
    val then = Date(epochMs)
    val nowFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    return nowFormat.format(now) == nowFormat.format(then)
}
