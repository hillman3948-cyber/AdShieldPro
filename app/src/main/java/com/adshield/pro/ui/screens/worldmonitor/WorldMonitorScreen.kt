package com.adshield.pro.ui.screens.worldmonitor

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adshield.pro.domain.worldmonitor.FreshnessClass
import com.adshield.pro.domain.worldmonitor.GeocodeSearchResult
import com.adshield.pro.domain.worldmonitor.SatellitePassPrediction
import com.adshield.pro.domain.worldmonitor.WorldEntity
import com.adshield.pro.domain.worldmonitor.WorldIntelCategory
import com.adshield.pro.domain.worldmonitor.WorldSeverity
import com.adshield.pro.engine.worldmonitor.BaselineReferenceStore
import com.adshield.pro.engine.worldmonitor.OrbitalAndGlobeMath
import com.adshield.pro.engine.worldmonitor.WorldMonitorAssetAndTileServer
import com.adshield.pro.engine.worldmonitor.WorldMonitorJsBridge
import kotlin.math.roundToInt
import com.adshield.pro.ui.theme.AlertRed
import com.adshield.pro.ui.theme.CyberCyan
import com.adshield.pro.ui.theme.DeepNavyBackground
import com.adshield.pro.ui.theme.ElectricBlue
import com.adshield.pro.ui.theme.EmeraldPrimary
import com.adshield.pro.ui.theme.NavyBorder
import com.adshield.pro.ui.theme.NavySurface
import com.adshield.pro.ui.theme.NavySurfaceVariant
import com.adshield.pro.ui.theme.SkyBlueGlow
import com.adshield.pro.ui.theme.TextPrimaryDark
import com.adshield.pro.ui.theme.TextSecondaryDark
import com.adshield.pro.ui.theme.WarningAmber
import com.adshield.pro.ui.viewmodel.ClickedLocationInfo
import com.adshield.pro.ui.viewmodel.WorldMonitorDeckTab
import com.adshield.pro.ui.viewmodel.WorldMonitorUiState
import com.adshield.pro.ui.viewmodel.WorldMonitorViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private data class QuickTeleportCity(
    val id: String,
    val label: String,
    val lat: Double,
    val lon: Double,
    val altMeters: Double
)

private val QUICK_TELEPORT_PLACES = listOf(
    QuickTeleportCity("mnl", "🇵🇭 Manila", 14.5995, 120.9842, 3_600.0),
    QuickTeleportCity("ceb", "🇵🇭 Cebu", 10.3157, 123.8854, 4_200.0),
    QuickTeleportCity("dvo", "🇵🇭 Davao", 7.1907, 125.4553, 4_800.0),
    QuickTeleportCity("sic", "🇵🇭 Siclong", 15.4850, 120.9660, 2_800.0),
    QuickTeleportCity("tyo", "🇯🇵 Tokyo", 35.6762, 139.6503, 4_200.0),
    QuickTeleportCity("sel", "🇰🇷 Seoul", 37.5665, 126.9780, 4_200.0),
    QuickTeleportCity("sin", "🇸🇬 Singapore", 1.3521, 103.8198, 4_200.0),
    QuickTeleportCity("jkt", "🇮🇩 Jakarta", -6.2088, 106.8456, 4_800.0),
    QuickTeleportCity("bkk", "🇹🇭 Bangkok", 13.7563, 100.5018, 4_500.0),
    QuickTeleportCity("del", "🇮🇳 Delhi", 28.6139, 77.2090, 5_200.0),
    QuickTeleportCity("syd", "🇦🇺 Sydney", -33.8688, 151.2093, 4_200.0),
    QuickTeleportCity("dxb", "🇦🇪 Dubai", 25.2048, 55.2708, 4_500.0),
    QuickTeleportCity("ruh", "🇸🇦 Riyadh", 24.7136, 46.6753, 5_200.0),
    QuickTeleportCity("ist", "🇹🇷 Istanbul", 41.0082, 28.9784, 4_600.0),
    QuickTeleportCity("cai", "🇪🇬 Cairo", 30.0444, 31.2357, 4_800.0),
    QuickTeleportCity("nbo", "🇰🇪 Nairobi", -1.2921, 36.8219, 4_800.0),
    QuickTeleportCity("los", "🇳🇬 Lagos", 6.5244, 3.3792, 4_800.0),
    QuickTeleportCity("lon", "🇬🇧 London", 51.5072, -0.1276, 4_200.0),
    QuickTeleportCity("par", "🇫🇷 Paris", 48.8566, 2.3522, 4_000.0),
    QuickTeleportCity("ber", "🇩🇪 Berlin", 52.5200, 13.4050, 4_200.0),
    QuickTeleportCity("iev", "🇺🇦 Kyiv", 50.4501, 30.5234, 4_600.0),
    QuickTeleportCity("nyc", "🇺🇸 NYC", 40.7128, -74.0060, 3_800.0),
    QuickTeleportCity("sfo", "🇺🇸 SF", 37.7749, -122.4194, 4_200.0),
    QuickTeleportCity("yyz", "🇨🇦 Toronto", 43.6532, -79.3832, 4_200.0),
    QuickTeleportCity("mex", "🇲🇽 Mexico City", 19.4326, -99.1332, 5_200.0),
    QuickTeleportCity("gru", "🇧🇷 São Paulo", -23.5505, -46.6333, 4_800.0),
    QuickTeleportCity("eze", "🇦🇷 Buenos Aires", -34.6037, -58.3816, 4_800.0),
    QuickTeleportCity("hmz", "⚓ Hormuz", 26.5667, 56.2500, 180_000.0),
    QuickTeleportCity("suz", "⚓ Suez", 30.5852, 32.2654, 95_000.0),
    QuickTeleportCity("pnm", "⚓ Panama", 9.0800, -79.6800, 85_000.0)
)

@Composable
fun WorldMonitorScreen(
    viewModel: WorldMonitorViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepNavyBackground)
    ) {
        if (uiState.useNative3dEngine) {
            NativeWorldGlobe3D(
                uiState = uiState,
                viewModel = viewModel,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            WorldMonitorGlobeWebView(
                viewModel = viewModel,
                reloadNonce = uiState.reloadNonce,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Futuristic Center Holographic Targeting Visor HUD (non-blocking crosshair + live telemetry)
        if (uiState.hudVisorEnabled) {
            HolographicCenterVisorHud(
                uiState = uiState,
                onArmWatchHere = {
                    val label = "Visor Sector %.2f, %.2f".format(
                        Locale.US,
                        uiState.viewport.centerLat,
                        uiState.viewport.centerLon
                    )
                    viewModel.createWatchRegionAtCoordinates(
                        name = label,
                        lat = uiState.viewport.centerLat,
                        lon = uiState.viewport.centerLon,
                        radiusKm = 250.0
                    )
                    viewModel.selectTab(WorldMonitorDeckTab.WATCHTOWER)
                },
                onScan3dCity = viewModel::load3dBuildingsAtViewport,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        if (uiState.fullscreenMode) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .background(NavySurface.copy(alpha = 0.90f), RoundedCornerShape(20.dp))
                    .border(
                        1.dp,
                        Brush.horizontalGradient(listOf(CyberCyan, ElectricBlue)),
                        RoundedCornerShape(20.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) {
                    Text("Exit", color = TextPrimaryDark, fontSize = 12.sp)
                }
                TextButton(onClick = viewModel::toggleFullscreen) {
                    Text("Show HUD", color = CyberCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    text = "%.4f°, %.4f°".format(Locale.US, uiState.viewport.centerLat, uiState.viewport.centerLon),
                    color = TextSecondaryDark,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(end = 6.dp)
                )
            }
        } else {
            WorldMonitorTopHud(
                uiState = uiState,
                onBack = onBack,
                onSync = { viewModel.syncAllEnabled(force = true) },
                onPulseLive = viewModel::pulseLiveNow,
                onOpenCountries = { viewModel.setShowCountryDirectory(true) },
                onReloadGlobe = viewModel::reloadGlobe,
                onLoadBuildings = viewModel::load3dBuildingsAtViewport,
                onToggleFullscreen = viewModel::toggleFullscreen,
                onSearchChange = viewModel::updateSearchQuery,
                onSearchSubmit = viewModel::submitGeographicSearch,
                onSelectSearchResult = viewModel::selectSearchResult,
                onTeleportCity = { city ->
                    viewModel.flyTo(city.lat, city.lon, city.altMeters, pitchDeg = -54.0)
                },
                onToggleCategory = { cat ->
                    val cur = uiState.layerStates[cat]?.visible ?: true
                    viewModel.updateLayerState(cat, visible = !cur)
                },
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }

        if (uiState.showCountryDirectory) {
            WorldCountryDirectoryModal(
                uiState = uiState,
                onQueryChange = viewModel::updateCountryFilterQuery,
                onSelectCountry = viewModel::selectCountry,
                onDismiss = { viewModel.setShowCountryDirectory(false) },
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 14.dp, vertical = 28.dp)
            )
        }

        // Interactive 2-Point Geodesic Distance & Transit-Time Ruler Banner
        if (uiState.measureModeEnabled) {
            GeodesicRulerHudBanner(
                start = uiState.measureStart,
                end = uiState.measureEnd,
                onClear = viewModel::clearMeasurePoints,
                onClose = viewModel::toggleMeasureMode,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        bottom = if (uiState.deckExpanded && !uiState.fullscreenMode) 316.dp else 72.dp,
                        start = 12.dp,
                        end = 62.dp
                    )
            )
        }

        // 2035 Neon Tactical Floating Control Pod
        NeonTacticalButtonPod(
            autoOrbitActive = uiState.autoOrbitEnabled,
            visorActive = uiState.hudVisorEnabled,
            rulerActive = uiState.measureModeEnabled,
            onZoomIn = { viewModel.cameraAction("ZOOM_IN") },
            onZoomOut = { viewModel.cameraAction("ZOOM_OUT") },
            onTilt3D = { viewModel.cameraAction("TILT_3D") },
            onToggleOrbit = viewModel::toggleAutoOrbit,
            onToggleVisor = viewModel::toggleHudVisor,
            onToggleRuler = viewModel::toggleMeasureMode,
            onLoad3dCity = viewModel::load3dBuildingsAtViewport,
            onHome = { viewModel.cameraAction("HOME") },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 8.dp)
        )

        val clicked = uiState.clickedLocation
        if (clicked != null && uiState.selectedEntity == null && !uiState.measureModeEnabled) {
            ClickedLocationCard(
                info = clicked,
                onDismiss = viewModel::dismissClickedLocation,
                onStreetDive = {
                    viewModel.flyTo(clicked.latitude, clicked.longitude, 1_100.0, pitchDeg = -52.0)
                    viewModel.dismissClickedLocation()
                },
                onLoad3dCity = {
                    viewModel.flyTo(clicked.latitude, clicked.longitude, 3_600.0, pitchDeg = -48.0)
                    viewModel.load3dBuildingsAtViewport()
                    viewModel.dismissClickedLocation()
                },
                onOpenTwin = {
                    viewModel.dismissClickedLocation()
                    viewModel.selectTab(WorldMonitorDeckTab.TWIN)
                },
                onCreateWatch = {
                    val regionTitle = clicked.placeName?.substringBefore(",")?.take(36)
                        ?: "Sector %.2f, %.2f".format(Locale.US, clicked.latitude, clicked.longitude)
                    viewModel.createWatchRegionAtCoordinates(
                        name = regionTitle,
                        lat = clicked.latitude,
                        lon = clicked.longitude,
                        radiusKm = 250.0
                    )
                    viewModel.dismissClickedLocation()
                    viewModel.selectTab(WorldMonitorDeckTab.WATCHTOWER)
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (uiState.deckExpanded && !uiState.fullscreenMode) 310.dp else 64.dp, start = 12.dp, end = 60.dp)
            )
        }

        val selected = uiState.selectedEntity
        if (selected != null) {
            SelectedEntityDossierCard(
                entity = selected,
                passes = uiState.satellitePasses,
                onDismiss = viewModel::dismissSelectedEntity,
                onCenter = { viewModel.focusEntity(selected) },
                onAskAnalyst = {
                    viewModel.askAiAnalyst("Explain selected entity ${selected.title} and assess corroboration and regional exposure")
                    viewModel.dismissSelectedEntity()
                },
                onPredictPasses = { viewModel.computePassesForSelectedSatellite(selected) },
                onOpenSourceUrl = { url ->
                    if (WorldMonitorJsBridge.isAllowedHttpsSourceUrl(url)) {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (uiState.deckExpanded && !uiState.fullscreenMode) 310.dp else 64.dp, start = 10.dp, end = 54.dp)
            )
        }

        if (!uiState.fullscreenMode) {
            WorldMonitorBottomDeck(
                uiState = uiState,
                viewModel = viewModel,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
private fun WorldMonitorGlobeWebView(
    viewModel: WorldMonitorViewModel,
    reloadNonce: Int,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val tileAndAssetServer: WorldMonitorAssetAndTileServer = remember(context) { WorldMonitorAssetAndTileServer(context) }
    val jsBridge: WorldMonitorJsBridge = remember(viewModel) { WorldMonitorJsBridge(viewModel) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var pageLoaded by remember { mutableStateOf(false) }
    val pendingCommands = remember { ArrayDeque<String>() }

    LaunchedEffect(viewModel) {
        viewModel.jsCommands.collect { expr ->
            val wv = webViewRef
            if (wv != null && pageLoaded) {
                wv.evaluateJavascript(expr, null)
            } else {
                if (pendingCommands.size >= 256) pendingCommands.removeFirst()
                pendingCommands.addLast(expr)
            }
        }
    }

    LaunchedEffect(reloadNonce) {
        if (reloadNonce > 0) {
            pageLoaded = false
            webViewRef?.loadUrl(WorldMonitorAssetAndTileServer.ENTRY_URL)
        }
    }

    DisposableEffect(lifecycleOwner, webViewRef) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> webViewRef?.onResume()
                Lifecycle.Event.ON_PAUSE -> webViewRef?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            webViewRef?.let { wv ->
                wv.removeJavascriptInterface(WorldMonitorJsBridge.BRIDGE_NAME)
                wv.stopLoading()
                wv.destroy()
            }
            webViewRef = null
            pageLoaded = false
            pendingCommands.clear()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(android.graphics.Color.parseColor("#020813"))
                setLayerType(View.LAYER_TYPE_NONE, null)
                isFocusable = true
                isFocusableInTouchMode = true
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                setOnTouchListener { v, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN,
                        MotionEvent.ACTION_POINTER_DOWN,
                        MotionEvent.ACTION_MOVE -> v.parent?.requestDisallowInterceptTouchEvent(true)
                        MotionEvent.ACTION_UP,
                        MotionEvent.ACTION_CANCEL -> v.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    false
                }
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    @Suppress("DEPRECATION")
                    allowFileAccessFromFileURLs = false
                    @Suppress("DEPRECATION")
                    allowUniversalAccessFromFileURLs = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    mediaPlaybackRequiresUserGesture = false
                    setSupportZoom(false)
                    builtInZoomControls = false
                    displayZoomControls = false
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    setSupportMultipleWindows(false)
                    javaScriptCanOpenWindowsAutomatically = false
                    cacheMode = WebSettings.LOAD_DEFAULT
                }
                jsBridge.attachTo(this)
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        if (consoleMessage != null &&
                            consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR
                        ) {
                            val msg = consoleMessage.message().orEmpty()
                            if (msg.contains("Uncaught", ignoreCase = true) ||
                                msg.contains("SecurityError", ignoreCase = true)
                            ) {
                                viewModel.onGlobeError("Globe JS: ${msg.take(140)}")
                            }
                        }
                        return true
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest
                    ): WebResourceResponse? = tileAndAssetServer.intercept(request)

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest
                    ): Boolean = true

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        pageLoaded = true
                        val wv = view ?: return
                        while (pendingCommands.isNotEmpty()) {
                            wv.evaluateJavascript(pendingCommands.removeFirst(), null)
                        }
                    }

                    override fun onRenderProcessGone(
                        view: WebView?,
                        detail: RenderProcessGoneDetail?
                    ): Boolean {
                        pageLoaded = false
                        viewModel.onGlobeError("WebView GPU process reset — tap Retry to restore 3D Earth.")
                        return true
                    }
                }
                loadUrl(WorldMonitorAssetAndTileServer.ENTRY_URL)
                webViewRef = this
            }
        }
    )
}

@Composable
private fun WorldMonitorTopHud(
    uiState: WorldMonitorUiState,
    onBack: () -> Unit,
    onSync: () -> Unit,
    onPulseLive: () -> Unit,
    onOpenCountries: () -> Unit,
    onReloadGlobe: () -> Unit,
    onLoadBuildings: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onSearchChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    onSelectSearchResult: (GeocodeSearchResult) -> Unit,
    onTeleportCity: (QuickTeleportCity) -> Unit,
    onToggleCategory: (WorldIntelCategory) -> Unit,
    modifier: Modifier = Modifier
) {
    val camAltM = uiState.viewport.cameraAltitudeMeters
    val zoomTile = OrbitalAndGlobeMath.altitudeToTileZoom(camAltM.toDouble())
    val altLabel = if (camAltM < 10_000L) {
        "%,d m".format(Locale.US, camAltM)
    } else {
        "%,d km".format(Locale.US, camAltM / 1000L)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Surface(
            color = NavySurface.copy(alpha = 0.92f),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    1.2.dp,
                    Brush.horizontalGradient(
                        listOf(CyberCyan.copy(alpha = 0.72f), ElectricBlue.copy(alpha = 0.55f), EmeraldPrimary.copy(alpha = 0.55f))
                    ),
                    RoundedCornerShape(16.dp)
                )
        ) {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimaryDark)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text(
                                text = "WORLD MONITOR 2035",
                                color = TextPrimaryDark,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            BadgePill("Z$zoomTile", SkyBlueGlow)
                            Surface(
                                color = (if (uiState.futureOffsetHours > 0) WarningAmber else EmeraldPrimary).copy(alpha = 0.22f),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier
                                    .border(
                                        1.dp,
                                        if (uiState.futureOffsetHours > 0) WarningAmber else EmeraldPrimary,
                                        RoundedCornerShape(6.dp)
                                    )
                                    .clickable(onClick = onPulseLive)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = when {
                                        uiState.futureOffsetHours > 0 -> "FUTURE +${uiState.futureOffsetHours}H"
                                        uiState.isSyncing -> "● SYNCING"
                                        uiState.liveAutoSyncEnabled -> "● LIVE"
                                        else -> "○ PAUSED"
                                    },
                                    color = if (uiState.futureOffsetHours > 0) WarningAmber else EmeraldPrimary,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                        Text(
                            text = "%s (%s) · %.3f°, %.3f° · %s · %,d nodes".format(
                                Locale.US,
                                uiState.activeCountryName,
                                uiState.activeCountryIso,
                                uiState.viewport.centerLat,
                                uiState.viewport.centerLon,
                                altLabel,
                                uiState.filteredEntities.size
                            ),
                            color = TextSecondaryDark,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Surface(
                        color = EmeraldPrimary.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .border(1.dp, EmeraldPrimary.copy(alpha = 0.65f), RoundedCornerShape(8.dp))
                            .clickable(onClick = onOpenCountries)
                            .padding(horizontal = 7.dp, vertical = 4.dp)
                    ) {
                        Text("🌍 234", color = EmeraldPrimary, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                    }

                    Spacer(Modifier.width(4.dp))

                    Surface(
                        color = CyberCyan.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .border(1.dp, CyberCyan.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                            .clickable(onClick = onLoadBuildings)
                            .padding(horizontal = 7.dp, vertical = 4.dp)
                    ) {
                        Text("3D City", color = CyberCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(Modifier.width(4.dp))

                    Surface(
                        color = ElectricBlue.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .border(1.dp, ElectricBlue.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                            .clickable(onClick = onToggleFullscreen)
                            .padding(horizontal = 7.dp, vertical = 4.dp)
                    ) {
                        Text("HUD", color = SkyBlueGlow, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }

                    IconButton(onClick = onSync, modifier = Modifier.size(34.dp)) {
                        if (uiState.isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = CyberCyan
                            )
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Sync Now", tint = EmeraldPrimary)
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = uiState.searchQuery,
                        onValueChange = onSearchChange,
                        placeholder = {
                            Text(
                                "Search 234 countries, capitals, cities, airports (MNL/JFK), or lat,lon…",
                                fontSize = 11.sp,
                                color = TextSecondaryDark
                            )
                        },
                        singleLine = true,
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(16.dp))
                        },
                        trailingIcon = {
                            if (uiState.searchQuery.isNotEmpty()) {
                                IconButton(onClick = { onSearchChange("") }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear", tint = TextSecondaryDark, modifier = Modifier.size(14.dp))
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSearchSubmit() }),
                        textStyle = MaterialTheme.typography.bodySmall.copy(color = TextPrimaryDark, fontSize = 12.sp),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Button(
                        onClick = onSearchSubmit,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.height(40.dp)
                    ) {
                        Text("Fly", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // 1-Tap Quick-Teleport Global Countries, Capitals & Chokepoint Bar
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    item(key = "all_countries_btn") {
                        Surface(
                            color = EmeraldPrimary.copy(alpha = 0.20f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .border(1.dp, EmeraldPrimary, RoundedCornerShape(10.dp))
                                .clickable(onClick = onOpenCountries)
                                .padding(horizontal = 9.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "🌍 All 234 Countries",
                                color = EmeraldPrimary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                    items(items = QUICK_TELEPORT_PLACES, key = { it.id }) { place ->
                        Surface(
                            color = NavySurfaceVariant.copy(alpha = 0.88f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .border(1.dp, NavyBorder, RoundedCornerShape(10.dp))
                                .clickable { onTeleportCity(place) }
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = place.label,
                                color = TextPrimaryDark,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                if (uiState.searchResults.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 165.dp)
                            .padding(top = 4.dp)
                            .background(NavySurfaceVariant, RoundedCornerShape(8.dp))
                            .border(1.dp, CyberCyan.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                    ) {
                        items(items = uiState.searchResults, key = { "${it.displayName}:${it.latitude}:${it.longitude}" }) { res ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectSearchResult(res) }
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(res.displayName, color = TextPrimaryDark, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${res.category} · ${res.attribution}", color = TextSecondaryDark, fontSize = 10.sp)
                                }
                                Text(
                                    "%.3f, %.3f".format(Locale.US, res.latitude, res.longitude),
                                    color = CyberCyan,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }

                uiState.globeError?.let { err ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = err,
                            color = AlertRed,
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = onReloadGlobe,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Retry 3D", color = CyberCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                uiState.statusBanner?.let { msg ->
                    Text(
                        text = msg,
                        color = CyberCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
            }
        }

        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            items(items = WorldIntelCategory.entries, key = { it.name }) { cat ->
                val enabled = uiState.layerStates[cat]?.visible != false
                val count = uiState.categoryCounts[cat] ?: 0
                FilterChip(
                    selected = enabled,
                    onClick = { onToggleCategory(cat) },
                    label = {
                        Text(
                            text = "${cat.shortCode} ($count)",
                            fontSize = 10.sp,
                            fontWeight = if (enabled) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CyberCyan.copy(alpha = 0.22f),
                        selectedLabelColor = CyberCyan,
                        containerColor = NavySurface.copy(alpha = 0.88f),
                        labelColor = TextSecondaryDark
                    )
                )
            }
        }
    }
}

@Composable
private fun WorldMonitorBottomDeck(
    uiState: WorldMonitorUiState,
    viewModel: WorldMonitorViewModel,
    modifier: Modifier = Modifier
) {
    Surface(
        color = NavySurface.copy(alpha = 0.94f),
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, NavyBorder, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(items = WorldMonitorDeckTab.entries, key = { it.name }) { tab ->
                        val active = uiState.activeTab == tab
                        val badgeSuffix = when (tab) {
                            WorldMonitorDeckTab.NEWS ->
                                if (uiState.newsEntities.isNotEmpty()) " (${uiState.newsEntities.size})" else ""
                            WorldMonitorDeckTab.CORRELATE ->
                                if (uiState.correlations.isNotEmpty()) " (${uiState.correlations.size})" else ""
                            WorldMonitorDeckTab.WATCHTOWER ->
                                if (uiState.watchAlerts.isNotEmpty()) " (${uiState.watchAlerts.size})" else ""
                            else -> ""
                        }
                        FilterChip(
                            selected = active,
                            onClick = { viewModel.selectTab(tab) },
                            label = {
                                Text(
                                    text = tab.label + badgeSuffix,
                                    fontSize = 11.sp,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CyberCyan.copy(alpha = 0.22f),
                                selectedLabelColor = CyberCyan,
                                containerColor = NavySurfaceVariant,
                                labelColor = TextSecondaryDark
                            )
                        )
                    }
                }
                IconButton(onClick = viewModel::toggleDeckExpanded, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = if (uiState.deckExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                        contentDescription = "Toggle Deck",
                        tint = TextPrimaryDark
                    )
                }
            }

            if (uiState.deckExpanded) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(255.dp)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    WorldMonitorDeckContent(uiState = uiState, viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
private fun ClickedLocationCard(
    info: ClickedLocationInfo,
    onDismiss: () -> Unit,
    onStreetDive: () -> Unit,
    onLoad3dCity: () -> Unit,
    onOpenTwin: () -> Unit,
    onCreateWatch: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = NavySurface.copy(alpha = 0.96f),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(1.2.dp, CyberCyan.copy(alpha = 0.65f), RoundedCornerShape(14.dp))
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = info.placeName ?: "Earth Coordinate Inspection",
                        color = TextPrimaryDark,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Lat %.4f° · Lon %.4f° · Elev %,d m MSL%s".format(
                            Locale.US,
                            info.latitude,
                            info.longitude,
                            info.elevationMeters,
                            info.population?.let { " · Pop %,d".format(Locale.US, it) }.orEmpty()
                        ),
                        color = CyberCyan,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(26.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondaryDark, modifier = Modifier.size(16.dp))
                }
            }
            Row(
                modifier = Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = onStreetDive,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("Street Zoom", fontSize = 10.sp, color = SkyBlueGlow, fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onClick = onLoad3dCity,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("3D City", fontSize = 10.sp, color = CyberCyan, fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onClick = onCreateWatch,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("Arm Watch", fontSize = 10.sp, color = EmeraldPrimary, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onOpenTwin,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("Twin", fontSize = 10.sp, color = TextPrimaryDark)
                }
            }
        }
    }
}

@Composable
private fun HolographicCenterVisorHud(
    uiState: WorldMonitorUiState,
    onArmWatchHere: () -> Unit,
    onScan3dCity: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lat = uiState.viewport.centerLat
    val lon = uiState.viewport.centerLon
    val z = OrbitalAndGlobeMath.altitudeToTileZoom(uiState.viewport.cameraAltitudeMeters.toDouble())
    val solarOffsetHours = (lon / 15.0).roundToInt()
    val effectiveEpoch = System.currentTimeMillis() + uiState.futureOffsetHours * 3_600_000L
    val localEpoch = effectiveEpoch + solarOffsetHours * 3_600_000L
    val hh = ((localEpoch / 3_600_000L) % 24L + 24L) % 24L
    val mm = ((localEpoch / 60_000L) % 60L + 60L) % 60L

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Futuristic non-obstructive reticle ring
        Box(
            modifier = Modifier
                .size(52.dp)
                .border(1.2.dp, CyberCyan.copy(alpha = 0.68f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(EmeraldPrimary, CircleShape)
            )
        }
        Spacer(Modifier.height(4.dp))
        Surface(
            color = NavySurface.copy(alpha = 0.84f),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.border(1.dp, CyberCyan.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Z$z · %.4f°, %.4f° · Solar %02d:%02d".format(Locale.US, lat, lon, hh, mm),
                    color = CyberCyan,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "+Watch",
                    color = EmeraldPrimary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(onClick = onArmWatchHere)
                )
                Text(
                    text = "3D",
                    color = SkyBlueGlow,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(onClick = onScan3dCity)
                )
            }
        }
    }
}

@Composable
private fun GeodesicRulerHudBanner(
    start: Pair<Double, Double>?,
    end: Pair<Double, Double>?,
    onClear: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = NavySurface.copy(alpha = 0.95f),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(1.2.dp, WarningAmber.copy(alpha = 0.78f), RoundedCornerShape(14.dp))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "GEODESIC RULER & TRANSIT CALCULATOR",
                    color = WarningAmber,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                val summary = when {
                    start == null -> "Tap Point A anywhere on Earth…"
                    end == null -> "Point A locked (%.3f°, %.3f°) — tap Point B!".format(Locale.US, start.first, start.second)
                    else -> {
                        val distKm = OrbitalAndGlobeMath.geodesicDistanceKm(start.first, start.second, end.first, end.second)
                        val distNm = distKm * 0.539957
                        val brg = OrbitalAndGlobeMath.initialBearingDeg(start.first, start.second, end.first, end.second)
                        val jetHrs = distKm / 880.0
                        val shipDays = (distKm / 37.0) / 24.0
                        "%,.1f km (%,.1f nm) · Brg %.0f° · Jet %.1fh · Ship %.1fd".format(
                            Locale.US, distKm, distNm, brg, jetHrs, shipDays
                        )
                    }
                }
                Text(
                    text = summary,
                    color = TextPrimaryDark,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            TextButton(onClick = onClear, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text("Reset", color = CyberCyan, fontSize = 11.sp)
            }
            TextButton(onClick = onClose, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text("Done", color = WarningAmber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun NeonTacticalButtonPod(
    autoOrbitActive: Boolean,
    visorActive: Boolean,
    rulerActive: Boolean,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onTilt3D: () -> Unit,
    onToggleOrbit: () -> Unit,
    onToggleVisor: () -> Unit,
    onToggleRuler: () -> Unit,
    onLoad3dCity: () -> Unit,
    onHome: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = NavySurface.copy(alpha = 0.84f),
        shape = RoundedCornerShape(22.dp),
        modifier = modifier
            .border(
                1.2.dp,
                Brush.verticalGradient(listOf(CyberCyan.copy(alpha = 0.70f), ElectricBlue.copy(alpha = 0.50f))),
                RoundedCornerShape(22.dp)
            )
            .padding(vertical = 6.dp, horizontal = 5.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(5.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            GlobePodButton("+", active = false, accent = CyberCyan, onClick = onZoomIn)
            GlobePodButton("−", active = false, accent = CyberCyan, onClick = onZoomOut)
            GlobePodButton("3D", active = false, accent = SkyBlueGlow, onClick = onTilt3D)
            GlobePodButton("↻", active = autoOrbitActive, accent = EmeraldPrimary, onClick = onToggleOrbit)
            GlobePodButton("🎯", active = visorActive, accent = CyberCyan, onClick = onToggleVisor)
            GlobePodButton("📏", active = rulerActive, accent = WarningAmber, onClick = onToggleRuler)
            GlobePodButton("🏙️", active = false, accent = SkyBlueGlow, onClick = onLoad3dCity)
            GlobePodButton("🌐", active = false, accent = EmeraldPrimary, onClick = onHome)
        }
    }
}

@Composable
private fun GlobePodButton(
    label: String,
    active: Boolean,
    accent: Color,
    onClick: () -> Unit
) {
    Surface(
        color = if (active) accent.copy(alpha = 0.28f) else NavySurfaceVariant.copy(alpha = 0.90f),
        shape = CircleShape,
        modifier = Modifier
            .size(34.dp)
            .border(1.dp, if (active) accent else NavyBorder, CircleShape)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                color = if (active) accent else TextPrimaryDark,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun SelectedEntityDossierCard(
    entity: WorldEntity,
    passes: List<SatellitePassPrediction>,
    onDismiss: () -> Unit,
    onCenter: () -> Unit,
    onAskAnalyst: () -> Unit,
    onPredictPasses: () -> Unit,
    onOpenSourceUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val fmt = remember {
        SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
    val metricEntries = remember(entity.metricsMap) { entity.metricsMap.entries.take(8) }

    Surface(
        color = NavySurface.copy(alpha = 0.96f),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp)
            .border(1.dp, severityColor(entity.severity), RoundedCornerShape(14.dp))
    ) {
        LazyColumn(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            item(key = "header") {
                Row(verticalAlignment = Alignment.Top) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BadgePill(entity.category.shortCode, ElectricBlue)
                            BadgePill(entity.severity.label, severityColor(entity.severity))
                            BadgePill(entity.freshness.label, freshnessColor(entity.freshness))
                        }
                        Text(
                            text = entity.title,
                            color = TextPrimaryDark,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                        Text(text = entity.summary, color = TextSecondaryDark, fontSize = 12.sp)
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(26.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = TextSecondaryDark, modifier = Modifier.size(16.dp))
                    }
                }
            }

            item(key = "trust_metadata") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(NavySurfaceVariant, RoundedCornerShape(8.dp))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "Provider: ${entity.providerName} · Confidence ${entity.confidence}% (${entity.verificationStatus.label})",
                        color = CyberCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Geo Accuracy: ${entity.geoAccuracy.label} (%.4f°, %.4f°)".format(Locale.US, entity.latitude, entity.longitude),
                        color = TextPrimaryDark,
                        fontSize = 11.sp
                    )
                    Text(
                        text = "Event: ${fmt.format(Date(entity.eventTimeEpochMs))} · Synced: ${fmt.format(Date(entity.retrievedAtEpochMs))}",
                        color = TextSecondaryDark,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Attribution: ${entity.attribution} · ${entity.licenseInfo}",
                        color = TextSecondaryDark,
                        fontSize = 10.sp
                    )
                }
            }

            if (metricEntries.isNotEmpty()) {
                item(key = "attributes") {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (entry in metricEntries) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(entry.key, color = TextSecondaryDark, fontSize = 11.sp)
                                Text(entry.value, color = TextPrimaryDark, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }

            if (passes.isNotEmpty()) {
                item(key = "sat_passes_header") {
                    Text(
                        "Predicted 24h Overhead Passes (>10° elevation):",
                        color = EmeraldPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                items(items = passes.take(4), key = { "${it.entityId}:${it.riseEpochMs}" }) { pass ->
                    Text(
                        text = "• ${fmt.format(Date(pass.riseEpochMs))} · Max El ${"%.1f".format(Locale.US, pass.maxElevationDeg)}° · ${pass.durationSeconds}s",
                        color = TextPrimaryDark,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            item(key = "actions") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    OutlinedButton(
                        onClick = onCenter,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("Center", fontSize = 11.sp, color = CyberCyan)
                    }
                    OutlinedButton(
                        onClick = onAskAnalyst,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("AI Brief", fontSize = 11.sp, color = EmeraldPrimary)
                    }
                    if (!entity.tleLine1.isNullOrBlank()) {
                        OutlinedButton(
                            onClick = onPredictPasses,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("Passes", fontSize = 11.sp, color = SkyBlueGlow)
                        }
                    }
                    OutlinedButton(
                        onClick = { onOpenSourceUrl(entity.sourceUrl) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("Source ↗", fontSize = 11.sp, color = TextPrimaryDark)
                    }
                }
            }
        }
    }
}

@Composable
internal fun BadgePill(text: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.20f), RoundedCornerShape(6.dp))
            .border(1.dp, color.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(text = text, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

internal fun severityColor(severity: WorldSeverity): Color = when (severity) {
    WorldSeverity.CRITICAL -> AlertRed
    WorldSeverity.HIGH -> WarningAmber
    WorldSeverity.MEDIUM -> SkyBlueGlow
    WorldSeverity.LOW -> EmeraldPrimary
    WorldSeverity.INFO -> CyberCyan
}

internal fun freshnessColor(freshness: FreshnessClass): Color = when (freshness) {
    FreshnessClass.LIVE -> EmeraldPrimary
    FreshnessClass.NEAR_REAL_TIME -> CyberCyan
    FreshnessClass.DELAYED -> WarningAmber
    FreshnessClass.HISTORICAL -> SkyBlueGlow
    FreshnessClass.ESTIMATED -> Color(0xFFA78BFA)
    FreshnessClass.SIMULATED -> AlertRed
}

@Composable
private fun WorldCountryDirectoryModal(
    uiState: WorldMonitorUiState,
    onQueryChange: (String) -> Unit,
    onSelectCountry: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val countries = remember(context) { BaselineReferenceStore.load(context.applicationContext).countries }
    val q = uiState.countryFilterQuery.trim()
    val filtered = remember(countries, q) {
        if (q.isEmpty()) {
            countries
        } else {
            countries.filter { c ->
                c.name.contains(q, ignoreCase = true) ||
                    c.capital.contains(q, ignoreCase = true) ||
                    c.iso2.equals(q, ignoreCase = true) ||
                    c.iso3.equals(q, ignoreCase = true)
            }
        }
    }

    Surface(
        color = NavySurface.copy(alpha = 0.97f),
        shape = RoundedCornerShape(18.dp),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp)
            .border(
                1.5.dp,
                Brush.horizontalGradient(listOf(EmeraldPrimary, CyberCyan, ElectricBlue)),
                RoundedCornerShape(18.dp)
            )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "🌍 Global Country & Territory Directory (${countries.size})",
                        color = TextPrimaryDark,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "Tap any country to fly 3D Earth & stream live flights, weather, AQI, waves & BGP",
                        color = TextSecondaryDark,
                        fontSize = 11.sp
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondaryDark)
                }
            }

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = uiState.countryFilterQuery,
                onValueChange = onQueryChange,
                placeholder = {
                    Text(
                        "Filter ${countries.size} countries by name, capital, or ISO code (PH, JP, IS, BR…)",
                        fontSize = 11.sp,
                        color = TextSecondaryDark
                    )
                },
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, tint = EmeraldPrimary, modifier = Modifier.size(16.dp))
                },
                trailingIcon = {
                    if (uiState.countryFilterQuery.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = TextSecondaryDark, modifier = Modifier.size(14.dp))
                        }
                    }
                },
                textStyle = MaterialTheme.typography.bodySmall.copy(color = TextPrimaryDark, fontSize = 12.sp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
            )

            Spacer(Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(items = filtered, key = { it.iso2 }) { country ->
                    val isSelected = country.iso2 == uiState.activeCountryIso
                    val popLabel = when {
                        country.population >= 1_000_000_000L -> "%.2fB".format(Locale.US, country.population / 1_000_000_000.0)
                        country.population >= 1_000_000L -> "%.1fM".format(Locale.US, country.population / 1_000_000.0)
                        else -> "%,d".format(Locale.US, country.population)
                    }
                    Surface(
                        color = if (isSelected) EmeraldPrimary.copy(alpha = 0.18f) else NavySurfaceVariant.copy(alpha = 0.90f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                1.dp,
                                if (isSelected) EmeraldPrimary else NavyBorder,
                                RoundedCornerShape(10.dp)
                            )
                            .clickable { onSelectCountry(country.iso2) }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    BadgePill(country.iso2, if (isSelected) EmeraldPrimary else CyberCyan)
                                    Text(
                                        text = country.name,
                                        color = TextPrimaryDark,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    text = "★ ${country.capital} · Pop $popLabel · %.2f°, %.2f°".format(
                                        Locale.US,
                                        country.lat,
                                        country.lon
                                    ),
                                    color = TextSecondaryDark,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = if (isSelected) "ACTIVE ●" else "FLY →",
                                color = if (isSelected) EmeraldPrimary else CyberCyan,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

