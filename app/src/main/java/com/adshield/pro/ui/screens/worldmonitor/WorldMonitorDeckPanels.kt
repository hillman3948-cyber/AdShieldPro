package com.adshield.pro.ui.screens.worldmonitor

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adshield.pro.domain.worldmonitor.GlobeBasemap
import com.adshield.pro.domain.worldmonitor.GlobeProjection
import com.adshield.pro.domain.worldmonitor.GlobeStylePreset
import com.adshield.pro.domain.worldmonitor.GraphicsPerformanceMode
import com.adshield.pro.domain.worldmonitor.ProviderSyncStatus
import com.adshield.pro.domain.worldmonitor.SimulationKind
import com.adshield.pro.domain.worldmonitor.WorldIntelCategory
import com.adshield.pro.domain.worldmonitor.WorldSeverity
import com.adshield.pro.domain.worldmonitor.WorldTimelineWindow
import com.adshield.pro.engine.worldmonitor.BaselineReferenceStore
import com.adshield.pro.engine.worldmonitor.WorldMonitorJsBridge
import com.adshield.pro.engine.worldmonitor.WorldMonitorProviderRegistry
import com.adshield.pro.ui.theme.AlertRed
import com.adshield.pro.ui.theme.CyberCyan
import com.adshield.pro.ui.theme.ElectricBlue
import com.adshield.pro.ui.theme.EmeraldPrimary
import com.adshield.pro.ui.theme.NavyBorder
import com.adshield.pro.ui.theme.NavySurfaceVariant
import com.adshield.pro.ui.theme.SkyBlueGlow
import com.adshield.pro.ui.theme.TextPrimaryDark
import com.adshield.pro.ui.theme.TextSecondaryDark
import com.adshield.pro.ui.theme.WarningAmber
import com.adshield.pro.ui.viewmodel.WorldMonitorDeckTab
import com.adshield.pro.ui.viewmodel.WorldMonitorUiState
import com.adshield.pro.ui.viewmodel.WorldMonitorViewModel
import java.util.Locale

@Composable
internal fun WorldMonitorDeckContent(
    uiState: WorldMonitorUiState,
    viewModel: WorldMonitorViewModel
) {
    when (uiState.activeTab) {
        WorldMonitorDeckTab.FEED -> IntelFeedPanel(uiState, viewModel)
        WorldMonitorDeckTab.NEWS -> GlobalNewsPanel(uiState, viewModel)
        WorldMonitorDeckTab.LAYERS -> LayersAndMapPanel(uiState, viewModel)
        WorldMonitorDeckTab.TWIN -> DigitalTwinPanel(uiState, viewModel)
        WorldMonitorDeckTab.CORRELATE -> CorrelationPanel(uiState, viewModel)
        WorldMonitorDeckTab.AI_ANALYST -> AiAnalystPanel(uiState, viewModel)
        WorldMonitorDeckTab.SIM_LAB -> SimulationLabPanel(uiState, viewModel)
        WorldMonitorDeckTab.TIMELINE -> TimelinePanel(uiState, viewModel)
        WorldMonitorDeckTab.WATCHTOWER -> WatchtowerPanel(uiState, viewModel)
        WorldMonitorDeckTab.SOURCES -> SourcesAndCredentialsPanel(uiState, viewModel)
    }
}

@Composable
private fun IntelFeedPanel(
    uiState: WorldMonitorUiState,
    viewModel: WorldMonitorViewModel
) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(items = WorldSeverity.entries, key = { it.name }) { sev ->
                    FilterChip(
                        selected = uiState.minSeverityFilter == sev,
                        onClick = { viewModel.setMinSeverityFilter(sev) },
                        label = { Text("≥ ${sev.label}", fontSize = 10.sp) }
                    )
                }
            }
            TextButton(
                onClick = {
                    val geoJson = viewModel.exportVisibleGeoJson()
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "application/geo+json"
                        putExtra(Intent.EXTRA_SUBJECT, "WorldMonitor2035.geojson")
                        putExtra(Intent.EXTRA_TEXT, geoJson)
                    }
                    runCatching { context.startActivity(Intent.createChooser(share, "Export GeoJSON")) }
                }
            ) {
                Text("GeoJSON", color = CyberCyan, fontSize = 11.sp)
            }
            TextButton(
                onClick = {
                    val czml = viewModel.exportVisibleCzml()
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_SUBJECT, "WorldMonitor2035.czml")
                        putExtra(Intent.EXTRA_TEXT, czml)
                    }
                    runCatching { context.startActivity(Intent.createChooser(share, "Export CZML")) }
                }
            ) {
                Text("CZML", color = SkyBlueGlow, fontSize = 11.sp)
            }
        }

        val list = uiState.filteredEntities.take(250)
        if (list.isEmpty()) {
            Text(
                "No cached entities match the current category/severity/time filters. Tap Sync in the top bar.",
                color = TextSecondaryDark,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 12.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                items(items = list, key = { it.id }) { entity ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(NavySurfaceVariant, RoundedCornerShape(10.dp))
                            .border(1.dp, NavyBorder, RoundedCornerShape(10.dp))
                            .clickable { viewModel.focusEntity(entity) }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                BadgePill(entity.category.shortCode, ElectricBlue)
                                BadgePill(entity.severity.label, severityColor(entity.severity))
                                BadgePill(entity.freshness.label, freshnessColor(entity.freshness))
                                Text(
                                    entity.providerName,
                                    color = CyberCyan,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                entity.title,
                                color = TextPrimaryDark,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                entity.summary,
                                color = TextSecondaryDark,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            "%.1f°, %.1f°".format(Locale.US, entity.latitude, entity.longitude),
                            color = TextSecondaryDark,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GlobalNewsPanel(
    uiState: WorldMonitorUiState,
    viewModel: WorldMonitorViewModel
) {
    val context = LocalContext.current
    val outlets = remember(uiState.newsEntities) {
        listOf<String?>(null) + uiState.newsEntities.map { it.providerId }.distinct().sorted()
    }
    var outletFilter by remember { mutableStateOf<String?>(null) }
    val rows = remember(uiState.newsEntities, outletFilter) {
        if (outletFilter == null) uiState.newsEntities else uiState.newsEntities.filter { it.providerId == outletFilter }
    }
    val newsProviders = remember {
        WorldMonitorProviderRegistry.providers.filter { it.category == WorldIntelCategory.NEWS }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "${rows.size} headline${if (rows.size == 1) "" else "s"} from ${uiState.newsEntities.map { it.providerId }.distinct().size}/${newsProviders.size} live outlet(s)",
                color = CyberCyan,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
            TextButton(
                onClick = { newsProviders.forEach { viewModel.syncSingleProvider(it.providerId) } }
            ) {
                Text("Refresh all", color = EmeraldPrimary, fontSize = 11.sp)
            }
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            items(items = outlets, key = { it ?: "all" }) { outlet ->
                FilterChip(
                    selected = outletFilter == outlet,
                    onClick = { outletFilter = outlet },
                    label = {
                        Text(
                            outlet?.let { id ->
                                newsProviders.firstOrNull { it.providerId == id }?.attribution?.substringBefore("(")?.trim() ?: id
                            } ?: "All outlets",
                            fontSize = 10.sp
                        )
                    }
                )
            }
        }

        if (rows.isEmpty()) {
            Text(
                "No headline is cached yet. Tap \"Refresh all\" — each outlet is fetched over plain HTTPS, " +
                    "and the exact per-outlet result is reported under Sources if one is blocked on this network.",
                color = TextSecondaryDark,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 12.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(items = rows.take(200), key = { it.id }) { entity ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(NavySurfaceVariant, RoundedCornerShape(10.dp))
                            .border(1.dp, severityColor(entity.severity).copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                            .clickable { viewModel.focusEntity(entity) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BadgePill(entity.severity.label, severityColor(entity.severity))
                            BadgePill(entity.freshness.label, freshnessColor(entity.freshness))
                            Text(
                                entity.providerName.substringBefore("(").trim(),
                                color = CyberCyan,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                entity.metricsMap["Published (UTC)"]?.substringAfter(' ') ?: "time not supplied",
                                color = TextSecondaryDark,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Text(
                            entity.title,
                            color = TextPrimaryDark,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (entity.summary.isNotBlank()) {
                            Text(
                                entity.summary,
                                color = TextSecondaryDark,
                                fontSize = 11.sp,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                entity.metricsMap["Place"] ?: "—",
                                color = TextSecondaryDark,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = {
                                    if (WorldMonitorJsBridge.isAllowedHttpsSourceUrl(entity.sourceUrl)) {
                                        runCatching {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(entity.sourceUrl)))
                                        }
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                            ) {
                                Text("Read at source ↗", color = SkyBlueGlow, fontSize = 10.sp)
                            }
                        }
                    }
                }

                if (uiState.newsSourceIssues.isNotEmpty()) {
                    item(key = "silent_outlets") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(AlertRed.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text("Outlets with nothing cached", color = AlertRed, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            uiState.newsSourceIssues.forEach { issue ->
                                Text(issue, color = TextSecondaryDark, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LayersAndMapPanel(
    uiState: WorldMonitorUiState,
    viewModel: WorldMonitorViewModel
) {
    var leftDate by remember(uiState.historicalSplit.leftDateIso) { mutableStateOf(uiState.historicalSplit.leftDateIso) }
    var rightDate by remember(uiState.historicalSplit.rightDateIso) { mutableStateOf(uiState.historicalSplit.rightDateIso) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "renderer_engine") {
            Text("3D Renderer Engine", color = CyberCyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item(key = "native_gpu") {
                    FilterChip(
                        selected = uiState.useNative3dEngine,
                        onClick = { viewModel.setUseNative3dEngine(true) },
                        label = { Text("Native 3D GPU (Instant)", fontSize = 11.sp) }
                    )
                }
                item(key = "cesium_webgl") {
                    FilterChip(
                        selected = !uiState.useNative3dEngine,
                        onClick = { viewModel.setUseNative3dEngine(false) },
                        label = { Text("CesiumJS WebGL Street Tiles", fontSize = 11.sp) }
                    )
                }
            }
        }

        item(key = "basemaps") {
            Text("Basemap Imagery", color = CyberCyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(items = GlobeBasemap.entries, key = { it.name }) { style ->
                    FilterChip(
                        selected = uiState.basemap == style,
                        onClick = { viewModel.setBasemap(style) },
                        label = { Text(style.label, fontSize = 11.sp) }
                    )
                }
            }
        }

        item(key = "projection_and_preset") {
            Text("Projection & Visual Style Preset", color = CyberCyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(items = GlobeProjection.entries, key = { it.name }) { proj ->
                    FilterChip(
                        selected = uiState.projection == proj,
                        onClick = { viewModel.setProjection(proj) },
                        label = { Text(proj.label, fontSize = 11.sp) }
                    )
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(items = GlobeStylePreset.entries, key = { it.name }) { preset ->
                    FilterChip(
                        selected = uiState.stylePreset == preset,
                        onClick = { viewModel.setStylePreset(preset) },
                        label = { Text(preset.label, fontSize = 11.sp) }
                    )
                }
            }
        }

        item(key = "scene_toggles") {
            Text("Earth Scene, Clustering, Density & Graphics Mode", color = CyberCyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item(key = "terrain") {
                    FilterChip(
                        selected = uiState.terrain3dEnabled,
                        onClick = { viewModel.setTerrainEnabled(!uiState.terrain3dEnabled) },
                        label = { Text("3D Terrain", fontSize = 11.sp) }
                    )
                }
                item(key = "borders") {
                    FilterChip(
                        selected = uiState.bordersEnabled,
                        onClick = { viewModel.updateSceneFlags(borders = !uiState.bordersEnabled) },
                        label = { Text("Borders", fontSize = 11.sp) }
                    )
                }
                item(key = "daynight") {
                    FilterChip(
                        selected = uiState.dayNightLightingEnabled,
                        onClick = { viewModel.updateSceneFlags(dayNight = !uiState.dayNightLightingEnabled) },
                        label = { Text("Day/Night", fontSize = 11.sp) }
                    )
                }
                item(key = "cluster") {
                    FilterChip(
                        selected = uiState.clusteringEnabled,
                        onClick = { viewModel.updateSceneFlags(clustering = !uiState.clusteringEnabled) },
                        label = { Text("Cluster", fontSize = 11.sp) }
                    )
                }
                item(key = "heatmap") {
                    FilterChip(
                        selected = uiState.heatmapEnabled,
                        onClick = { viewModel.updateSceneFlags(heatmap = !uiState.heatmapEnabled) },
                        label = { Text("Density Heatmap", fontSize = 11.sp) }
                    )
                }
                item(key = "clouds") {
                    FilterChip(
                        selected = uiState.cloudsOverlayEnabled,
                        onClick = { viewModel.setCloudsOverlay(!uiState.cloudsOverlayEnabled) },
                        label = { Text("GIBS Clouds", fontSize = 11.sp) }
                    )
                }
                item(key = "radar") {
                    FilterChip(
                        selected = uiState.radarOverlayEnabled,
                        onClick = { viewModel.setRadarOverlay(!uiState.radarOverlayEnabled) },
                        label = { Text("Live Radar", fontSize = 11.sp) }
                    )
                }
                item(key = "trails") {
                    FilterChip(
                        selected = uiState.trailsEnabled,
                        onClick = { viewModel.setTrailsEnabled(!uiState.trailsEnabled) },
                        label = { Text("Trails", fontSize = 11.sp) }
                    )
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(items = GraphicsPerformanceMode.entries, key = { it.name }) { mode ->
                    FilterChip(
                        selected = uiState.performanceMode == mode,
                        onClick = { viewModel.setPerformanceMode(mode) },
                        label = { Text(mode.label, fontSize = 11.sp) }
                    )
                }
            }
        }

        item(key = "historical_split") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NavySurfaceVariant, RoundedCornerShape(10.dp))
                    .padding(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("NASA GIBS Historical Split Comparison", color = TextPrimaryDark, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Switch(
                        checked = uiState.historicalSplit.enabled,
                        onCheckedChange = { viewModel.updateHistoricalSplit(enabled = it, leftDateIso = leftDate, rightDateIso = rightDate) }
                    )
                }
                if (uiState.historicalSplit.enabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = leftDate,
                            onValueChange = { leftDate = it },
                            label = { Text("Left YYYY-MM-DD", fontSize = 10.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = rightDate,
                            onValueChange = { rightDate = it },
                            label = { Text("Right YYYY-MM-DD", fontSize = 10.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = { viewModel.updateHistoricalSplit(true, leftDate, rightDate) },
                            modifier = Modifier.align(Alignment.CenterVertically)
                        ) {
                            Text("Apply", fontSize = 11.sp)
                        }
                    }
                    Slider(
                        value = uiState.historicalSplit.splitPosition,
                        onValueChange = { viewModel.updateHistoricalSplit(splitPosition = it) },
                        valueRange = 0.1f..0.9f
                    )
                }
            }
        }

        items(items = WorldIntelCategory.entries, key = { "layercfg:${it.name}" }) { cat ->
            val cfg = uiState.layerStates[cat] ?: return@items
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NavySurfaceVariant, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${cat.label} Opacity (${(cfg.opacity * 100).toInt()}%)", color = TextPrimaryDark, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Switch(
                        checked = cfg.visible,
                        onCheckedChange = { viewModel.updateLayerState(cat, visible = it) }
                    )
                }
                Slider(
                    value = cfg.opacity,
                    onValueChange = { viewModel.updateLayerState(cat, opacity = it) },
                    valueRange = 0.2f..1.0f
                )
            }
        }
    }
}

@Composable
private fun DigitalTwinPanel(
    uiState: WorldMonitorUiState,
    viewModel: WorldMonitorViewModel
) {
    val twin = uiState.digitalTwin
    val context = LocalContext.current
    val countries = remember(context) { BaselineReferenceStore.load(context.applicationContext).countries }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item(key = "twin_controls") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = twin?.regionName ?: "Synthesizing Regional Digital Twin…",
                    color = TextPrimaryDark,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = { viewModel.refreshDigitalTwin() },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Text("Sample Viewport", fontSize = 11.sp, color = CyberCyan)
                }
            }
        }

        if (twin != null) {
            item(key = "twin_summary") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(NavySurfaceVariant, RoundedCornerShape(10.dp))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        "Population: ${twin.populationCount?.let { "%,d (%d)".format(Locale.US, it, twin.populationYear ?: 2024) } ?: "Oceanic"} · ${twin.populationSource}",
                        color = EmeraldPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text("Weather: ${twin.weatherSummary}", color = TextPrimaryDark, fontSize = 11.sp)
                    Text("Air Quality: ${twin.airQualitySummary}", color = TextSecondaryDark, fontSize = 11.sp)
                    Text("Hydrology: ${twin.riverDischargeSummary}", color = TextSecondaryDark, fontSize = 11.sp)
                    Text(
                        "Infrastructure (450 km): " + twin.infrastructureCounts.entries.joinToString(" · ") { "${it.key}: ${it.value}" },
                        color = CyberCyan,
                        fontSize = 11.sp
                    )
                    Text(
                        "Active Hazards (450 km): " + twin.activeHazardCounts.entries.joinToString(" · ") { "${it.key}: ${it.value}" },
                        color = WarningAmber,
                        fontSize = 11.sp
                    )
                }
            }

            item(key = "twin_compare_selector") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Compare Across All ${countries.size} Countries (${twin.comparisonTargetName ?: uiState.compareCountryIso}):",
                        color = CyberCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "🌍 Fly Country",
                        color = EmeraldPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable { viewModel.setShowCountryDirectory(true) }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    items(items = countries, key = { it.iso2 }) { c ->
                        FilterChip(
                            selected = uiState.compareCountryIso == c.iso2,
                            onClick = { viewModel.refreshDigitalTwin(c.iso2) },
                            label = { Text("${c.iso2} · ${c.name}", fontSize = 10.sp) }
                        )
                    }
                }
            }

            items(items = twin.datasetComparison, key = { it.metric }) { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(NavySurfaceVariant, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1.2f)) {
                        Text(row.metric, color = TextPrimaryDark, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        Text(row.source, color = TextSecondaryDark, fontSize = 9.sp)
                    }
                    Text(row.primaryValue, color = CyberCyan, fontSize = 11.sp, modifier = Modifier.weight(0.9f))
                    Text(row.secondaryValue, color = SkyBlueGlow, fontSize = 11.sp, modifier = Modifier.weight(0.9f))
                }
            }
        }
    }
}

@Composable
private fun CorrelationPanel(
    uiState: WorldMonitorUiState,
    viewModel: WorldMonitorViewModel
) {
    if (uiState.correlations.isEmpty()) {
        Text(
            "No multi-source spatio-temporal correlations detected in the current filter window. Sync Disaster, Weather, Maritime, Cyber, and News feeds to evaluate cross-domain links.",
            color = TextSecondaryDark,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(items = uiState.correlations, key = { it.id }) { inc ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NavySurfaceVariant, RoundedCornerShape(10.dp))
                    .border(1.dp, severityColor(inc.severity).copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                    .clickable { viewModel.flyTo(inc.latitude, inc.longitude, 420_000.0, -50.0) }
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    BadgePill(inc.severity.label, severityColor(inc.severity))
                    BadgePill("${inc.confidence}% CONF", CyberCyan)
                    Text(
                        "${inc.distanceKm} km · Δ${inc.timeDeltaMinutes}m",
                        color = TextSecondaryDark,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Text(inc.ruleTitle, color = TextPrimaryDark, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(inc.summary, color = TextPrimaryDark, fontSize = 11.sp)
                Text("Caveat: ${inc.caveat}", color = WarningAmber, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun AiAnalystPanel(
    uiState: WorldMonitorUiState,
    viewModel: WorldMonitorViewModel
) {
    val context = LocalContext.current
    var prompt by remember { mutableStateOf("") }
    val presets = remember {
        listOf(
            "Global hazard & cyber posture",
            "Active earthquakes & tsunamis",
            "Maritime & severe weather risks",
            "Space weather & ISS orbital status"
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item(key = "analyst_input") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    placeholder = { Text("Ask on-device Evidence RAG Analyst…", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = { viewModel.askAiAnalyst(prompt) },
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
                ) {
                    Text("Analyze", fontSize = 11.sp)
                }
            }
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                items(items = presets, key = { it }) { p ->
                    FilterChip(
                        selected = false,
                        onClick = {
                            prompt = p
                            viewModel.askAiAnalyst(p)
                        },
                        label = { Text(p, fontSize = 10.sp) }
                    )
                }
            }
        }

        val resp = uiState.aiResponse
        if (resp != null) {
            item(key = "analyst_header") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(resp.headline, color = TextPrimaryDark, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text("${resp.engineMode} · ${resp.multiSourceSummary}", color = CyberCyan, fontSize = 10.sp)
                    }
                    TextButton(
                        onClick = {
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = "text/markdown"
                                putExtra(Intent.EXTRA_SUBJECT, "WorldMonitor_AI_Briefing.md")
                                putExtra(Intent.EXTRA_TEXT, resp.markdownReport)
                            }
                            runCatching { context.startActivity(Intent.createChooser(share, "Share Briefing")) }
                        }
                    ) {
                        Text("Export MD", color = EmeraldPrimary, fontSize = 11.sp)
                    }
                }
            }

            item(key = "verified_title") {
                Text("1. Verified Observations (Direct Provider Records)", color = EmeraldPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            items(items = resp.verifiedObservations, key = { "${it.provider}:${it.statement.hashCode()}" }) { fact ->
                Text(
                    text = "• [${fact.provider} · ${fact.freshness.label}] ${fact.statement}",
                    color = TextPrimaryDark,
                    fontSize = 11.sp
                )
            }

            item(key = "uncertainties_title") {
                Text("2. Uncertainties & Coverage Gaps", color = WarningAmber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            items(items = resp.uncertaintiesAndGaps, key = { "gap:${it.hashCode()}" }) { gap ->
                Text("• $gap", color = TextSecondaryDark, fontSize = 11.sp)
            }

            item(key = "hypotheses_title") {
                Text("3. Analytical Hypotheses (Non-Certain)", color = SkyBlueGlow, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            items(items = resp.analyticalHypotheses, key = { "hyp:${it.hashCode()}" }) { hyp ->
                Text("• $hyp", color = TextPrimaryDark, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun SimulationLabPanel(
    uiState: WorldMonitorUiState,
    viewModel: WorldMonitorViewModel
) {
    var selectedKind by remember { mutableStateOf(SimulationKind.WEATHER_PLUME) }
    var paramSlider by remember { mutableFloatStateOf(180f) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item(key = "sim_disclaimer") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AlertRed.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
                    .border(1.dp, WarningAmber, RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                Text(
                    "EXPERIMENTAL SIMULATION LAB — NOT REAL-TIME WARNINGS",
                    color = WarningAmber,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
                Text(
                    "Scenarios run locally around the current globe crosshair and are strictly tagged SIMULATED.",
                    color = TextSecondaryDark,
                    fontSize = 10.sp
                )
            }
        }

        item(key = "sim_selector") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(items = SimulationKind.entries, key = { it.name }) { kind ->
                    FilterChip(
                        selected = selectedKind == kind,
                        onClick = {
                            selectedKind = kind
                            paramSlider = when (kind) {
                                SimulationKind.FLOOD_SURGE -> 3.5f
                                SimulationKind.WEATHER_PLUME -> 180f
                                SimulationKind.INFRASTRUCTURE_EXPOSURE -> 220f
                                SimulationKind.ENVIRONMENTAL_TREND -> 450f
                                SimulationKind.HISTORICAL_REPLAY -> 1f
                            }
                        },
                        label = { Text(kind.label, fontSize = 11.sp) }
                    )
                }
            }
        }

        item(key = "sim_controls") {
            val unitLabel = if (selectedKind == SimulationKind.FLOOD_SURGE) {
                "Surge / Stage Parameter: +${"%.1f".format(Locale.US, paramSlider)} m"
            } else {
                "Envelope Radius Parameter: ${paramSlider.toInt()} km"
            }
            if (selectedKind != SimulationKind.HISTORICAL_REPLAY) {
                Text(unitLabel, color = TextPrimaryDark, fontSize = 11.sp)
                Slider(
                    value = paramSlider,
                    onValueChange = { paramSlider = it },
                    valueRange = if (selectedKind == SimulationKind.FLOOD_SURGE) 1f..10f else 40f..600f
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.runSimulation(selectedKind, paramSlider.toDouble()) },
                    colors = ButtonDefaults.buttonColors(containerColor = WarningAmber)
                ) {
                    Text("Run Scenario at Crosshair", color = TextPrimaryDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                if (uiState.simulation.active) {
                    OutlinedButton(onClick = viewModel::clearSimulation) {
                        Text("Clear Overlay", color = AlertRed, fontSize = 11.sp)
                    }
                }
            }
        }

        if (uiState.simulation.active) {
            items(items = uiState.simulation.findings, key = { "simf:${it.hashCode()}" }) { f ->
                Text("• $f", color = TextPrimaryDark, fontSize = 11.sp)
            }
        }
    }
}

private data class WatchGeofencePreset(
    val id: String,
    val label: String,
    val lat: Double,
    val lon: Double,
    val radiusKm: Double
)

private val FUTURE_HORIZON_STEPS = listOf(0, 1, 6, 12, 24, 48, 72)

private val WATCH_SEVERITY_CHOICES = listOf(
    WorldSeverity.LOW,
    WorldSeverity.MEDIUM,
    WorldSeverity.HIGH,
    WorldSeverity.CRITICAL
)

private val WATCH_PRESET_REGIONS = listOf(
    WatchGeofencePreset("ph_wps", "🇵🇭 Manila & West PH Sea", 14.5995, 120.9842, 420.0),
    WatchGeofencePreset("luzon_tw", "🇯🇵 Luzon–Taiwan Strait", 20.8000, 121.5000, 380.0),
    WatchGeofencePreset("hormuz", "⚓ Strait of Hormuz", 26.5667, 56.2500, 320.0),
    WatchGeofencePreset("suez_red", "⚓ Red Sea & Suez", 27.8000, 33.8000, 480.0),
    WatchGeofencePreset("us_pac", "🇺🇸 US Pacific Seismic", 37.7749, -122.4194, 520.0)
)

@Composable
private fun TimelinePanel(
    uiState: WorldMonitorUiState,
    viewModel: WorldMonitorViewModel
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        item(key = "live_sync_control") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NavySurfaceVariant, RoundedCornerShape(10.dp))
                    .border(1.dp, EmeraldPrimary.copy(alpha = 0.60f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (uiState.liveAutoSyncEnabled) "● Continuous 45s LIVE Telemetry Active" else "○ LIVE Auto-Sync Paused",
                        color = if (uiState.liveAutoSyncEnabled) EmeraldPrimary else WarningAmber,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                    Text(
                        text = "Active Country: ${uiState.activeCountryName} (${uiState.activeCountryIso}) · Pulse #${uiState.livePulseCount}",
                        color = TextSecondaryDark,
                        fontSize = 10.sp
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = viewModel::toggleLiveAutoSync,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text(if (uiState.liveAutoSyncEnabled) "Pause" else "Auto", fontSize = 10.sp, color = CyberCyan)
                    }
                    Button(
                        onClick = viewModel::pulseLiveNow,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 3.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text("LIVE NOW", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = Color.Black)
                    }
                }
            }
        }

        item(key = "future_horizon") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NavySurfaceVariant, RoundedCornerShape(10.dp))
                    .border(1.dp, CyberCyan.copy(alpha = 0.50f), RoundedCornerShape(10.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    "Predictive Future Horizon (SGP4 Orbits + Dead-Reckoning + 72h Forecast)",
                    color = WarningAmber,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(items = FUTURE_HORIZON_STEPS, key = { "fut:$it" }) { hrs ->
                        FilterChip(
                            selected = uiState.futureOffsetHours == hrs,
                            onClick = { viewModel.setFutureOffsetHours(hrs) },
                            label = {
                                Text(
                                    if (hrs == 0) "LIVE NOW" else "+${hrs}H",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        )
                    }
                }
            }
        }

        item(key = "past_window") {
            Text("Historical Event Window & Replay", color = CyberCyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(items = WorldTimelineWindow.entries, key = { it.name }) { preset ->
                    FilterChip(
                        selected = uiState.timelineWindow == preset,
                        onClick = { viewModel.setTimelineWindow(preset) },
                        label = { Text(preset.label, fontSize = 11.sp) }
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = viewModel::toggleTimelinePlayback,
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
                ) {
                    Text(if (uiState.timelinePlaying) "Pause" else "Play", fontSize = 11.sp)
                }
                Slider(
                    value = uiState.timelineProgress,
                    onValueChange = viewModel::setTimelineProgress,
                    valueRange = 0.05f..1.0f,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${(uiState.timelineProgress * 100).toInt()}%",
                    color = CyberCyan,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Text(
                "Showing ${uiState.filteredEntities.size} of ${uiState.allEntities.size} records (${if (uiState.futureOffsetHours > 0) "Future +${uiState.futureOffsetHours}h ESTIMATED" else uiState.timelineWindow.label}).",
                color = TextSecondaryDark,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun WatchtowerPanel(
    uiState: WorldMonitorUiState,
    viewModel: WorldMonitorViewModel
) {
    var regionName by remember { mutableStateOf("") }
    var radiusKm by remember { mutableFloatStateOf(300f) }
    var minSev by remember { mutableStateOf(WorldSeverity.MEDIUM) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item(key = "watch_presets") {
            Text(
                "1-Tap Regional Watchtower Presets:",
                color = CyberCyan,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                items(items = WATCH_PRESET_REGIONS, key = { it.id }) { preset ->
                    FilterChip(
                        selected = false,
                        onClick = {
                            viewModel.createWatchRegionAtCoordinates(
                                name = preset.label,
                                lat = preset.lat,
                                lon = preset.lon,
                                radiusKm = preset.radiusKm,
                                minSeverity = minSev
                            )
                        },
                        label = { Text(preset.label, fontSize = 10.sp) }
                    )
                }
            }
        }

        item(key = "watch_create") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NavySurfaceVariant, RoundedCornerShape(10.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Arm Geofence at Crosshair (%.3f°, %.3f°)".format(
                            Locale.US,
                            uiState.viewport.centerLat,
                            uiState.viewport.centerLon
                        ),
                        color = EmeraldPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(
                        onClick = viewModel::scanWatchtowerAlertsNow,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("Scan Alerts Now", color = CyberCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = regionName,
                        onValueChange = { regionName = it },
                        placeholder = { Text("Region label (e.g. Manila Bay)", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            viewModel.createWatchRegionAtViewport(
                                name = regionName,
                                radiusKm = radiusKm.toDouble(),
                                minSeverity = minSev,
                                categories = WorldIntelCategory.entries.toSet()
                            )
                            regionName = ""
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
                    ) {
                        Text("Arm", fontSize = 11.sp)
                    }
                }
                Text("Radius: ${radiusKm.toInt()} km · Min Severity: ≥ ${minSev.label}", color = TextSecondaryDark, fontSize = 10.sp)
                Slider(value = radiusKm, onValueChange = { radiusKm = it }, valueRange = 15f..1500f)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(items = WATCH_SEVERITY_CHOICES, key = { it.name }) { s ->
                        FilterChip(
                            selected = minSev == s,
                            onClick = { minSev = s },
                            label = { Text("≥ ${s.label}", fontSize = 10.sp) }
                        )
                    }
                }
            }
        }

        items(items = uiState.watchRegions, key = { it.id }) { region ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NavySurfaceVariant, RoundedCornerShape(8.dp))
                    .border(1.dp, EmeraldPrimary.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(region.name, color = TextPrimaryDark, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "%.3f°, %.3f° · ${region.radiusKm.toInt()} km · ≥ ${region.minSeverity.label}".format(
                            Locale.US,
                            region.centerLat,
                            region.centerLon
                        ),
                        color = CyberCyan,
                        fontSize = 10.sp
                    )
                }
                TextButton(onClick = { viewModel.flyTo(region.centerLat, region.centerLon, (region.radiusKm * 2_200.0).coerceIn(15_000.0, 900_000.0)) }) {
                    Text("Fly", color = SkyBlueGlow, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = { viewModel.deleteWatchRegion(region.id) }) {
                    Text("Delete", color = AlertRed, fontSize = 11.sp)
                }
            }
        }

        item(key = "alerts_header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Triggered Watchtower Alerts (${uiState.watchAlerts.size})",
                    color = WarningAmber,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                if (uiState.watchAlerts.isNotEmpty()) {
                    TextButton(onClick = viewModel::clearWatchAlerts) {
                        Text("Clear History", color = TextSecondaryDark, fontSize = 11.sp)
                    }
                }
            }
        }

        if (uiState.watchAlerts.isEmpty()) {
            item(key = "alerts_empty") {
                Text(
                    if (uiState.watchRegions.isEmpty()) {
                        "No active geofences armed yet. Tap any preset above or click 'Arm' to monitor live events."
                    } else {
                        "Geofences active and scanning — 0 matching events inside armed radius/severity right now."
                    },
                    color = TextSecondaryDark,
                    fontSize = 11.sp
                )
            }
        } else {
            items(items = uiState.watchAlerts, key = { "${it.regionId}:${it.entityId}:${it.id}" }) { alert ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(NavySurfaceVariant, RoundedCornerShape(8.dp))
                        .border(1.dp, severityColor(alert.severity).copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                        .clickable {
                            val matchedEnt = uiState.allEntities.firstOrNull { it.id == alert.entityId }
                            if (matchedEnt != null) {
                                viewModel.focusEntity(matchedEnt)
                            } else {
                                uiState.watchRegions.firstOrNull { it.id == alert.regionId }?.let { reg ->
                                    viewModel.flyTo(reg.centerLat, reg.centerLon, 75_000.0, pitchDeg = -52.0)
                                }
                            }
                        }
                        .padding(8.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                        BadgePill(alert.severity.label, severityColor(alert.severity))
                        Text(
                            "${alert.regionName} · ${alert.title} (${"%.0f".format(Locale.US, alert.distanceKm)} km)",
                            color = TextPrimaryDark,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text("${alert.summary} (${alert.providerName})", color = TextSecondaryDark, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun SourcesAndCredentialsPanel(
    uiState: WorldMonitorUiState,
    viewModel: WorldMonitorViewModel
) {
    var firmsInput by remember { mutableStateOf("") }
    var openSkyInput by remember { mutableStateOf("") }
    val stateById = remember(uiState.providerStates) { uiState.providerStates.associateBy { it.providerId } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item(key = "cred_vault") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NavySurfaceVariant, RoundedCornerShape(10.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    "Optional User API Keys (Android Keystore AES-256-GCM — Zero Embedded Secrets)",
                    color = CyberCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = firmsInput,
                        onValueChange = { firmsInput = it },
                        placeholder = {
                            Text(
                                if (uiState.hasFirmsKey) "NASA FIRMS MAP_KEY stored (enter blank to clear)" else "NASA FIRMS free MAP_KEY",
                                fontSize = 10.sp
                            )
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = {
                        viewModel.saveApiCredential("wm.cred.nasa_firms", firmsInput)
                        firmsInput = ""
                    }) {
                        Text("Save", fontSize = 11.sp)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = openSkyInput,
                        onValueChange = { openSkyInput = it },
                        placeholder = {
                            Text(
                                if (uiState.hasOpenSkyKey) "OpenSky user:pass stored" else "Optional OpenSky user:password",
                                fontSize = 10.sp
                            )
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = {
                        viewModel.saveApiCredential("wm.cred.opensky", openSkyInput)
                        openSkyInput = ""
                    }) {
                        Text("Save", fontSize = 11.sp)
                    }
                }
            }
        }

        items(
            items = WorldMonitorProviderRegistry.providers.sortedBy { if (it.category == WorldIntelCategory.NEWS) 0 else 1 },
            key = { it.providerId }
        ) { spec ->
            val st = stateById[spec.providerId]
            val status = st?.status ?: ProviderSyncStatus.IDLE
            val statusColor = when (status) {
                ProviderSyncStatus.OK -> EmeraldPrimary
                ProviderSyncStatus.RATE_LIMITED, ProviderSyncStatus.NEEDS_API_KEY, ProviderSyncStatus.CACHED_FALLBACK -> WarningAmber
                ProviderSyncStatus.ERROR, ProviderSyncStatus.OFFLINE, ProviderSyncStatus.BACKEND_REQUIRED -> AlertRed
                else -> TextSecondaryDark
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NavySurfaceVariant, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                            BadgePill(spec.category.shortCode, ElectricBlue)
                            BadgePill(status.label, statusColor)
                            Text(spec.name, color = TextPrimaryDark, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Text(
                            "${spec.licenseInfo} · Cached: ${st?.itemCount ?: 0} · Refresh ${(spec.refreshIntervalMs / 60_000L).coerceAtLeast(1)}m",
                            color = TextSecondaryDark,
                            fontSize = 10.sp
                        )
                        st?.errorMessage?.let { err ->
                            Text(err, color = WarningAmber, fontSize = 10.sp)
                            if (spec.category == WorldIntelCategory.NEWS) {
                                Text(
                                    "News fetches plain HTTPS from this device. If this outlet is blocked on your " +
                                        "network, its headlines are unavailable here and the other outlets still load.",
                                    color = TextSecondaryDark,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                    if (spec.implemented && spec.providerId != "wm.infra.baseline") {
                        TextButton(onClick = { viewModel.syncSingleProvider(spec.providerId) }) {
                            Text("Sync", color = CyberCyan, fontSize = 11.sp)
                        }
                        Switch(
                            checked = st?.enabled ?: spec.defaultEnabled,
                            onCheckedChange = { viewModel.setProviderEnabled(spec.providerId, it) }
                        )
                    }
                }
            }
        }
    }
}
