package com.adshield.pro.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adshield.pro.data.local.preferences.AppSettingsState
import com.adshield.pro.domain.model.BatteryMode
import com.adshield.pro.domain.model.BatteryThermalSnapshot
import com.adshield.pro.domain.model.BottleneckSeverity
import com.adshield.pro.domain.model.DeviceHealthAssessment
import com.adshield.pro.domain.model.GameAppInfo
import com.adshield.pro.domain.model.GamingProfile
import com.adshield.pro.domain.model.JunkCleanMode
import com.adshield.pro.domain.model.JunkScanReport
import com.adshield.pro.domain.model.RamOptimizationResult
import com.adshield.pro.domain.model.RamSnapshot
import com.adshield.pro.domain.model.SmartBoostResult
import com.adshield.pro.domain.model.StorageMetrics
import com.adshield.pro.engine.optimizer.RuleBasedOptimizationPredictor
import com.adshield.pro.ui.theme.AlertRed
import com.adshield.pro.ui.theme.CyberCyan
import com.adshield.pro.ui.theme.EmeraldGlow
import com.adshield.pro.ui.theme.EmeraldPrimary
import com.adshield.pro.ui.theme.WarningAmber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class OptimizerSubTab(val label: String) {
    SMART_BOOST("Smart Boost"),
    JUNK_CLEANER("Junk Cleaner"),
    RAM_MANAGER("RAM Manager"),
    GAME_BOOSTER("Game Booster"),
    BATTERY_TEMP("Battery & Temp")
}

@Composable
fun OptimizerScreen(
    settings: AppSettingsState,
    ramSnapshot: RamSnapshot,
    ramUsageTrend: List<Float>,
    storageMetrics: StorageMetrics,
    batterySnapshot: BatteryThermalSnapshot,
    temperatureTrend: List<Float>,
    healthAssessment: DeviceHealthAssessment,
    smartBoostResult: SmartBoostResult?,
    isRunningSmartBoost: Boolean,
    junkScanReport: JunkScanReport?,
    isScanningJunk: Boolean,
    ramOptimizationResult: RamOptimizationResult?,
    installedGames: List<GameAppInfo>,
    isDndPolicyGranted: Boolean,
    onRunSmartBoost: () -> Unit,
    onScanJunk: (JunkCleanMode) -> Unit,
    onToggleJunkCandidate: (String, Boolean) -> Unit,
    onCleanSelectedJunk: () -> Unit,
    onAddExcludedJunkPath: (String) -> Unit,
    onRemoveExcludedJunkPath: (String) -> Unit,
    onUpdateSmartAutoSchedule: (Boolean, Int) -> Unit,
    onRunSmartRamOptimize: () -> Unit,
    onToggleFavoriteGame: (String, Boolean) -> Unit,
    onUpdateGamingSettings: (GamingProfile, Boolean, Int) -> Unit,
    onTriggerGameBoost: (String?, String?) -> Unit,
    onSelectBatteryMode: (BatteryMode) -> Unit
) {
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }
    val tabs = OptimizerSubTab.entries

    Column(modifier = Modifier.fillMaxSize()) {
        // Scrollable 5-Tab Bar inside Optimizer
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                tabs.forEachIndexed { index, tab ->
                    FilterChip(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        label = {
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1
                            )
                        }
                    )
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when (tabs[selectedTabIndex]) {
                OptimizerSubTab.SMART_BOOST -> SmartBoostTabContent(
                    settings = settings,
                    ram = ramSnapshot,
                    storage = storageMetrics,
                    battery = batterySnapshot,
                    assessment = healthAssessment,
                    boostResult = smartBoostResult,
                    isRunningBoost = isRunningSmartBoost,
                    onRunSmartBoost = onRunSmartBoost,
                    onUpdateAutoSchedule = onUpdateSmartAutoSchedule,
                    onOpenJunkTab = { selectedTabIndex = OptimizerSubTab.JUNK_CLEANER.ordinal }
                )

                OptimizerSubTab.JUNK_CLEANER -> JunkCleanerTabContent(
                    settings = settings,
                    storage = storageMetrics,
                    scanReport = junkScanReport,
                    isScanning = isScanningJunk,
                    onScanMode = onScanJunk,
                    onToggleCandidate = onToggleJunkCandidate,
                    onCleanSelected = onCleanSelectedJunk,
                    onAddExcludedPath = onAddExcludedJunkPath,
                    onRemoveExcludedPath = onRemoveExcludedJunkPath
                )

                OptimizerSubTab.RAM_MANAGER -> RamManagerTabContent(
                    ram = ramSnapshot,
                    ramTrend = ramUsageTrend,
                    ramResult = ramOptimizationResult,
                    onOptimizeRam = onRunSmartRamOptimize
                )

                OptimizerSubTab.GAME_BOOSTER -> GameBoosterTabContent(
                    settings = settings,
                    ram = ramSnapshot,
                    battery = batterySnapshot,
                    games = installedGames,
                    isDndGranted = isDndPolicyGranted,
                    onToggleFavorite = onToggleFavoriteGame,
                    onUpdateSettings = onUpdateGamingSettings,
                    onBoostAndLaunch = onTriggerGameBoost
                )

                OptimizerSubTab.BATTERY_TEMP -> BatteryThermalTabContent(
                    settings = settings,
                    battery = batterySnapshot,
                    tempTrend = temperatureTrend,
                    onSelectBatteryMode = onSelectBatteryMode
                )
            }
        }
    }
}

@Composable
private fun SmartBoostTabContent(
    settings: AppSettingsState,
    ram: RamSnapshot,
    storage: StorageMetrics,
    battery: BatteryThermalSnapshot,
    assessment: DeviceHealthAssessment,
    boostResult: SmartBoostResult?,
    isRunningBoost: Boolean,
    onRunSmartBoost: () -> Unit,
    onUpdateAutoSchedule: (Boolean, Int) -> Unit,
    onOpenJunkTab: () -> Unit
) {
    val scoreColor = when {
        assessment.readinessScore >= 85 -> EmeraldPrimary
        assessment.readinessScore >= 65 -> WarningAmber
        else -> AlertRed
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Hero AI Smart Boost Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, scoreColor.copy(alpha = 0.45f), RoundedCornerShape(22.dp)),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = CyberCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "AI SMART BOOST ENGINE",
                        style = MaterialTheme.typography.labelMedium,
                        color = CyberCyan,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(110.dp)
                        .clip(CircleShape)
                        .background(scoreColor.copy(alpha = 0.14f))
                        .border(2.dp, scoreColor, CircleShape)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${assessment.readinessScore}",
                            style = MaterialTheme.typography.headlineLarge,
                            color = scoreColor,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "HEALTH",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = assessment.statusHeadline,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 3-Column Real Telemetry Strip
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MiniTelemetryPill(
                        modifier = Modifier.weight(1f),
                        label = "RAM Used",
                        value = String.format(Locale.US, "%.0f%%", ram.usagePercent),
                        sub = "${RuleBasedOptimizationPredictor.formatBytes(ram.availableRamBytes)} free",
                        accent = if (ram.usagePercent >= 85f) AlertRed else CyberCyan
                    )
                    MiniTelemetryPill(
                        modifier = Modifier.weight(1f),
                        label = "Storage",
                        value = String.format(Locale.US, "%.0f%%", storage.usedPercent),
                        sub = "${RuleBasedOptimizationPredictor.formatBytes(storage.availableBytes)} free",
                        accent = if (storage.isLowStorage) AlertRed else EmeraldPrimary
                    )
                    MiniTelemetryPill(
                        modifier = Modifier.weight(1f),
                        label = "Temp & Bat",
                        value = String.format(Locale.US, "%.1f°C", battery.temperatureCelsius),
                        sub = "${battery.batteryPercent}% • ${if (battery.isCharging) "CHG" else "BAT"}",
                        accent = if (battery.isOverheating) AlertRed else WarningAmber
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = onRunSmartBoost,
                    enabled = !isRunningBoost,
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Bolt, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isRunningBoost) "Running Diagnostics & Optimizing..." else "ONE-TAP SMART BOOST",
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }

                if (isRunningBoost) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = EmeraldPrimary
                    )
                }
            }
        }

        // Before & After Real Measurements Result Card
        if (boostResult != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = EmeraldPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (boostResult.wasSkippedDueToRecentBoost) {
                                "Optimization Status: Already Optimized"
                            } else {
                                "Smart Boost Before & After Measurements"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (!boostResult.wasSkippedDueToRecentBoost) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            MiniTelemetryPill(
                                modifier = Modifier.weight(1f),
                                label = "Storage Cleaned",
                                value = RuleBasedOptimizationPredictor.formatBytes(boostResult.bytesRecoveredAutomatically),
                                sub = "${boostResult.filesCleanedCount} temp file(s)",
                                accent = EmeraldPrimary
                            )
                            MiniTelemetryPill(
                                modifier = Modifier.weight(1f),
                                label = "AdShield Heap",
                                value = "${RuleBasedOptimizationPredictor.formatBytes(boostResult.beforeRam.adShieldJavaHeapUsedBytes)} → ${RuleBasedOptimizationPredictor.formatBytes(boostResult.afterRam.adShieldJavaHeapUsedBytes)}",
                                sub = "DNS Cache: ${boostResult.afterRam.adShieldDnsCacheEntries}",
                                accent = CyberCyan
                            )
                        }
                    }

                    boostResult.actionsCompleted.forEach { action ->
                        Text(
                            text = "✓ $action",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    if (boostResult.pendingUserApprovalActions.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Actions Requiring Your Approval:",
                            style = MaterialTheme.typography.labelMedium,
                            color = WarningAmber,
                            fontWeight = FontWeight.Bold
                        )
                        boostResult.pendingUserApprovalActions.forEach { pending ->
                            Text(
                                text = "• $pending",
                                style = MaterialTheme.typography.labelMedium,
                                color = WarningAmber
                            )
                        }
                        OutlinedButton(
                            onClick = onOpenJunkTab,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Review Files in Junk Cleaner")
                        }
                    }
                }
            }
        }

        // Detected Bottlenecks & Recommendations Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Performance Bottleneck Analysis",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                if (assessment.bottlenecks.isEmpty()) {
                    Text(
                        text = "No active memory, storage, or thermal bottlenecks detected. All system metrics are within healthy thresholds.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    assessment.bottlenecks.forEach { b ->
                        val badgeColor = when (b.severity) {
                            BottleneckSeverity.HIGH -> AlertRed
                            BottleneckSeverity.MODERATE -> WarningAmber
                            BottleneckSeverity.INFO -> CyberCyan
                        }
                        Surface(
                            color = badgeColor.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = b.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = badgeColor,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = b.detail,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Recommendation: ${b.recommendedAction}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // Central Automatic Smart Maintenance Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                    ) {
                        Text(
                            text = "Automatic Smart Maintenance",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Cleans safe app temp caches via WorkManager. Pauses automatically during gaming or low battery.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.smartAutoOptimizeEnabled,
                        onCheckedChange = {
                            onUpdateAutoSchedule(it, settings.autoJunkCleanIntervalHours)
                        }
                    )
                }

                if (settings.smartAutoOptimizeEnabled) {
                    Text(
                        text = "Maintenance Interval:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(12 to "Every 12h", 24 to "Daily (24h)", 48 to "Every 48h").forEach { (hrs, label) ->
                            FilterChip(
                                selected = settings.autoJunkCleanIntervalHours == hrs,
                                onClick = { onUpdateAutoSchedule(true, hrs) },
                                label = { Text(label, maxLines = 1) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun JunkCleanerTabContent(
    settings: AppSettingsState,
    storage: StorageMetrics,
    scanReport: JunkScanReport?,
    isScanning: Boolean,
    onScanMode: (JunkCleanMode) -> Unit,
    onToggleCandidate: (String, Boolean) -> Unit,
    onCleanSelected: () -> Unit,
    onAddExcludedPath: (String) -> Unit,
    onRemoveExcludedPath: (String) -> Unit
) {
    var showExclusionDialog by remember { mutableStateOf(false) }
    val currentMode = scanReport?.mode ?: JunkCleanMode.QUICK_CLEAN
    val candidates = scanReport?.candidates ?: emptyList()
    val selectedBytes = candidates.filter { it.isSelected }.sumOf { it.sizeBytes }
    val dateFormat = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.US) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Storage Overview & Recoverable Size Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp)
                        ) {
                            Text(
                                text = "Automatic Junk & Storage Cleaner",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Internal Storage: ${RuleBasedOptimizationPredictor.formatBytes(storage.usedBytes)} / ${RuleBasedOptimizationPredictor.formatBytes(storage.totalBytes)} (${String.format(Locale.US, "%.1f%%", storage.usedPercent)})",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = { onScanMode(currentMode) },
                            enabled = !isScanning
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Rescan", tint = EmeraldPrimary)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MiniTelemetryPill(
                            modifier = Modifier.weight(1f),
                            label = "Safe Auto-Clean",
                            value = RuleBasedOptimizationPredictor.formatBytes(scanReport?.safeAutoCleanBytes ?: 0L),
                            sub = "App temp caches",
                            accent = EmeraldPrimary
                        )
                        MiniTelemetryPill(
                            modifier = Modifier.weight(1f),
                            label = "Reviewable",
                            value = RuleBasedOptimizationPredictor.formatBytes(scanReport?.confirmationRequiredBytes ?: 0L),
                            sub = "Dupes/APKs/Large",
                            accent = WarningAmber
                        )
                        MiniTelemetryPill(
                            modifier = Modifier.weight(1f),
                            label = "Total Cleaned",
                            value = RuleBasedOptimizationPredictor.formatBytes(settings.totalRecoveredBytesEver),
                            sub = "All-time saved",
                            accent = CyberCyan
                        )
                    }

                    // 4 Cleaning Modes Selector
                    Text(
                        text = "Scan & Cleaning Mode:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        JunkCleanMode.entries.forEach { mode ->
                            FilterChip(
                                selected = currentMode == mode,
                                onClick = { onScanMode(mode) },
                                label = { Text(mode.title, maxLines = 1) }
                            )
                        }
                    }

                    if (isScanning) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = EmeraldPrimary
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onCleanSelected,
                            enabled = !isScanning && selectedBytes > 0L,
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "CLEAN NOW (${RuleBasedOptimizationPredictor.formatBytes(selectedBytes)})",
                                maxLines = 1
                            )
                        }

                        OutlinedButton(onClick = { showExclusionDialog = true }) {
                            Icon(Icons.Default.FolderOff, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Exclusions (${settings.excludedJunkPaths.size})", maxLines = 1)
                        }
                    }
                }
            }
        }

        // File Previews Before Deletion
        item {
            Text(
                text = "File Previews Before Deletion (${candidates.size} found)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Safe app temp caches are pre-selected. Large files, SHA-256 duplicates, and APKs require your explicit checkbox confirmation.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (candidates.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Text(
                        text = "No redundant temporary or junk files detected in ${currentMode.title}. Switch to Deep Scan to check for duplicate files, large files, and unused APKs.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            items(candidates, key = { it.id }) { item ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = item.isSelected,
                            onCheckedChange = { onToggleCandidate(item.id, it) }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp)
                        ) {
                            Text(
                                text = item.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${item.category.displayName} • ${RuleBasedOptimizationPredictor.formatBytes(item.sizeBytes)}" +
                                    (if (item.sha256Hash != null) " • SHA256: ${item.sha256Hash}" else ""),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (item.requiresUserConfirmation) WarningAmber else EmeraldPrimary
                            )
                            Text(
                                text = item.absolutePath,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // Cleaning History Section
        if (settings.cleanHistoryRecords.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Recent Cleaning History",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        settings.cleanHistoryRecords.take(6).forEach { rec ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${dateFormat.format(Date(rec.timestampEpochMs))} • ${rec.modeName}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "+${RuleBasedOptimizationPredictor.formatBytes(rec.bytesRecovered)} (${rec.filesDeletedCount} files)",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = EmeraldPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showExclusionDialog) {
        var newPath by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showExclusionDialog = false },
            title = { Text("Cleanup Folder & File Exclusions") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Files or folders matching these substrings will never be flagged or deleted:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedTextField(
                            value = newPath,
                            onValueChange = { newPath = it },
                            label = { Text("Folder or filename pattern") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                if (newPath.isNotBlank()) {
                                    onAddExcludedPath(newPath)
                                    newPath = ""
                                }
                            }
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                        }
                    }
                    settings.excludedJunkPaths.forEach { path ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = path,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { onRemoveExcludedPath(path) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove", tint = AlertRed)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showExclusionDialog = false }) {
                    Text("Done")
                }
            }
        )
    }
}

@Composable
private fun RamManagerTabContent(
    ram: RamSnapshot,
    ramTrend: List<Float>,
    ramResult: RamOptimizationResult?,
    onOptimizeRam: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Memory, contentDescription = null, tint = CyberCyan)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Smart RAM & Memory Manager",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (ram.isLowMemoryCondition) {
                                "ALERT: Android Low-Memory Flag Active"
                            } else {
                                "Normal Memory Pressure • Threshold: ${RuleBasedOptimizationPredictor.formatBytes(ram.lowMemoryThresholdBytes)}"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (ram.isLowMemoryCondition) AlertRed else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                LinearProgressIndicator(
                    progress = { (ram.usagePercent / 100f).coerceIn(0.02f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp)),
                    color = if (ram.usagePercent >= 85f) AlertRed else CyberCyan,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MiniTelemetryPill(
                        modifier = Modifier.weight(1f),
                        label = "Total RAM",
                        value = RuleBasedOptimizationPredictor.formatBytes(ram.totalRamBytes),
                        sub = "Physical",
                        accent = CyberCyan
                    )
                    MiniTelemetryPill(
                        modifier = Modifier.weight(1f),
                        label = "Available",
                        value = RuleBasedOptimizationPredictor.formatBytes(ram.availableRamBytes),
                        sub = "${String.format(Locale.US, "%.1f%%", 100f - ram.usagePercent)} free",
                        accent = EmeraldPrimary
                    )
                    MiniTelemetryPill(
                        modifier = Modifier.weight(1f),
                        label = "Estimated Used",
                        value = RuleBasedOptimizationPredictor.formatBytes(ram.usedRamBytes),
                        sub = "${String.format(Locale.US, "%.1f%%", ram.usagePercent)} used",
                        accent = WarningAmber
                    )
                }

                Button(
                    onClick = onOptimizeRam,
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Memory, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("SMART RAM OPTIMIZE", fontWeight = FontWeight.Bold)
                }
            }
        }

        // AdShield Pro Self-Memory Diagnostic Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "AdShield Pro Internal Memory Footprint",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Monitors AdShield Pro's own heap and DNS LRU cache so background protection stays ultra-light.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MiniTelemetryPill(
                        modifier = Modifier.weight(1f),
                        label = "Java Heap",
                        value = RuleBasedOptimizationPredictor.formatBytes(ram.adShieldJavaHeapUsedBytes),
                        sub = "JVM Allocated",
                        accent = EmeraldPrimary
                    )
                    MiniTelemetryPill(
                        modifier = Modifier.weight(1f),
                        label = "Native Heap",
                        value = RuleBasedOptimizationPredictor.formatBytes(ram.adShieldNativeHeapUsedBytes),
                        sub = "C++ / SQLite",
                        accent = CyberCyan
                    )
                    MiniTelemetryPill(
                        modifier = Modifier.weight(1f),
                        label = "DNS Cache",
                        value = "${ram.adShieldDnsCacheEntries}",
                        sub = "LRU Entries",
                        accent = WarningAmber
                    )
                }
            }
        }

        // Real-Time RAM Usage Trend Sparkline Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Real-Time RAM Usage Trend (%)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                MetricTrendLineChart(
                    points = ramTrend,
                    minVal = 0f,
                    maxVal = 100f,
                    lineColor = CyberCyan,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(90.dp)
                )
            }
        }

        if (ramResult != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Last RAM Optimization Report",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = EmeraldPrimary
                    )
                    ramResult.actionsTaken.forEach { act ->
                        Text("✓ $act", style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    ramResult.recommendations.forEach { rec ->
                        Text(
                            text = "• $rec",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GameBoosterTabContent(
    settings: AppSettingsState,
    ram: RamSnapshot,
    battery: BatteryThermalSnapshot,
    games: List<GameAppInfo>,
    isDndGranted: Boolean,
    onToggleFavorite: (String, Boolean) -> Unit,
    onUpdateSettings: (GamingProfile, Boolean, Int) -> Unit,
    onBoostAndLaunch: (String?, String?) -> Unit
) {
    val context = LocalContext.current
    var showAllAppsFilter by remember { mutableStateOf(false) }
    val displayedGames = remember(games, showAllAppsFilter) {
        val favOrGames = games.filter { it.isFavorite || it.isSystemDetectedGame }
        if (showAllAppsFilter || favOrGames.isEmpty()) games else favOrGames
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Gaming Dashboard Header Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.SportsEsports, contentDescription = null, tint = EmeraldPrimary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Game Booster Dashboard",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Optimizes AdShield background I/O, DNS cache latency, and DND while gaming",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MiniTelemetryPill(
                            modifier = Modifier.weight(1f),
                            label = "Free RAM",
                            value = RuleBasedOptimizationPredictor.formatBytes(ram.availableRamBytes),
                            sub = "${String.format(Locale.US, "%.0f%%", ram.usagePercent)} used",
                            accent = CyberCyan
                        )
                        MiniTelemetryPill(
                            modifier = Modifier.weight(1f),
                            label = "Temp",
                            value = String.format(Locale.US, "%.1f°C", battery.temperatureCelsius),
                            sub = battery.thermalStatusLabel,
                            accent = if (battery.isOverheating) AlertRed else EmeraldPrimary
                        )
                        MiniTelemetryPill(
                            modifier = Modifier.weight(1f),
                            label = "Battery",
                            value = "${battery.batteryPercent}%",
                            sub = if (battery.isCharging) "Charging" else "On Battery",
                            accent = WarningAmber
                        )
                    }

                    Button(
                        onClick = { onBoostAndLaunch(null, null) },
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Bolt, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("APPLY GAME BOOST (${settings.gamingProfile.title.uppercase()})", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Gaming Profile & DND Configuration Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Gaming Performance Profile",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        GamingProfile.entries.forEach { prof ->
                            FilterChip(
                                selected = settings.gamingProfile == prof,
                                onClick = {
                                    onUpdateSettings(
                                        prof,
                                        settings.gameBoosterSuppressNotifications,
                                        settings.gameBoosterTempWarningCelsius
                                    )
                                },
                                label = { Text(prof.title, maxLines = 1) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Text(
                        text = settings.gamingProfile.subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp)
                        ) {
                            Text(
                                text = "Do Not Disturb During Gaming",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (isDndGranted) {
                                    "Authorized: Priority DND activates when launching games"
                                } else {
                                    "Requires Android Notification Policy permission"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.gameBoosterSuppressNotifications,
                            onCheckedChange = {
                                onUpdateSettings(
                                    settings.gamingProfile,
                                    it,
                                    settings.gameBoosterTempWarningCelsius
                                )
                            }
                        )
                    }

                    if (!isDndGranted) {
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(intent)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.DoNotDisturbOn, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Grant Android DND Policy Access")
                        }
                    }

                    Text(
                        text = "Thermal Warning Threshold:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(39, 41, 43).forEach { tempC ->
                            FilterChip(
                                selected = settings.gameBoosterTempWarningCelsius == tempC,
                                onClick = {
                                    onUpdateSettings(
                                        settings.gamingProfile,
                                        settings.gameBoosterSuppressNotifications,
                                        tempC
                                    )
                                },
                                label = { Text("${tempC}°C", maxLines = 1) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // Favorite & Installed Games Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Launch Games (${displayedGames.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = showAllAppsFilter,
                    onClick = { showAllAppsFilter = !showAllAppsFilter },
                    label = { Text(if (showAllAppsFilter) "Showing All Apps" else "Show All Apps") }
                )
            }
        }

        items(displayedGames.take(40), key = { it.packageName }) { game ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { onToggleFavorite(game.packageName, !game.isFavorite) }) {
                            Icon(
                                imageVector = if (game.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                                contentDescription = "Favorite",
                                tint = if (game.isFavorite) WarningAmber else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Column {
                            Text(
                                text = game.appName,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${if (game.isSystemDetectedGame) "Game" else "App"} • Sessions: ${game.launchCount}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Button(
                        onClick = { onBoostAndLaunch(game.packageName, game.appName) },
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Boost & Play", maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun BatteryThermalTabContent(
    settings: AppSettingsState,
    battery: BatteryThermalSnapshot,
    tempTrend: List<Float>,
    onSelectBatteryMode: (BatteryMode) -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (battery.isOverheating) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = AlertRed.copy(alpha = 0.16f))
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = AlertRed)
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "High Temperature Alert (${String.format(Locale.US, "%.1f°C", battery.temperatureCelsius)})",
                            style = MaterialTheme.typography.titleMedium,
                            color = AlertRed,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Thermal Status: ${battery.thermalStatusLabel}. Reduce screen brightness, pause heavy 3D games, and switch to Battery Saver mode.",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }

        // Battery & Thermal Real-Time Telemetry Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.BatteryChargingFull, contentDescription = null, tint = EmeraldPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Battery Health & Thermal Monitor",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${battery.chargingSourceLabel} • Health: ${battery.healthStatusLabel}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MiniTelemetryPill(
                        modifier = Modifier.weight(1f),
                        label = "Battery Level",
                        value = "${battery.batteryPercent}%",
                        sub = if (battery.isCharging) "Charging" else "Discharging",
                        accent = if (battery.isLowBattery) AlertRed else EmeraldPrimary
                    )
                    MiniTelemetryPill(
                        modifier = Modifier.weight(1f),
                        label = "Temperature",
                        value = String.format(Locale.US, "%.1f°C", battery.temperatureCelsius),
                        sub = battery.thermalStatusLabel,
                        accent = if (battery.isOverheating) AlertRed else WarningAmber
                    )
                    MiniTelemetryPill(
                        modifier = Modifier.weight(1f),
                        label = "Voltage",
                        value = "${battery.voltageMillivolts} mV",
                        sub = "Health: ${battery.healthStatusLabel}",
                        accent = CyberCyan
                    )
                }

                Button(
                    onClick = { onSelectBatteryMode(settings.batteryMode) },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Thermostat, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("AUTO BATTERY OPTIMIZE", fontWeight = FontWeight.Bold)
                }
            }
        }

        // 4 Selectable Battery Modes Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Battery & Workload Modes",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                BatteryMode.entries.forEach { mode ->
                    val selected = settings.batteryMode == mode
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (selected) {
                            EmeraldPrimary.copy(alpha = 0.15f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = if (selected) 1.5.dp else 0.dp,
                                color = if (selected) EmeraldPrimary else Color.Transparent,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { onSelectBatteryMode(mode) }
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = mode.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = if (selected) EmeraldGlow else MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = mode.subtitle,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // Temperature Trend Chart Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Battery Temperature Trend (°C)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                MetricTrendLineChart(
                    points = tempTrend,
                    minVal = 20f,
                    maxVal = 55f,
                    lineColor = WarningAmber,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(90.dp)
                )
                Text(
                    text = "AdShield Background Impact: ${battery.adShieldBatteryImpactSummary}",
                    style = MaterialTheme.typography.labelMedium,
                    color = EmeraldPrimary
                )
            }
        }

        // System Battery Settings Shortcut Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Android System Battery Controls",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                battery.recommendations.forEach { rec ->
                    Text(
                        text = "• $rec",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                val intent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Battery Saver", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                val intent = Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Battery Usage", maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniTelemetryPill(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    sub: String,
    accent: Color
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = accent,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = sub,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun MetricTrendLineChart(
    points: List<Float>,
    minVal: Float,
    maxVal: Float,
    lineColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        if (points.isEmpty()) return@Canvas
        val range = (maxVal - minVal).coerceAtLeast(1f)
        val count = points.size
        val stepX = if (count > 1) size.width / (count - 1) else size.width

        val path = Path()
        points.forEachIndexed { idx, v ->
            val x = if (count > 1) idx * stepX else size.width / 2f
            val normalized = ((v - minVal) / range).coerceIn(0.05f, 0.95f)
            val y = size.height * (1f - normalized)
            if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
            drawCircle(color = lineColor, radius = 4.dp.toPx(), center = Offset(x, y))
        }
        if (count > 1) {
            drawPath(path = path, color = lineColor, style = Stroke(width = 2.5.dp.toPx()))
        }
    }
}
