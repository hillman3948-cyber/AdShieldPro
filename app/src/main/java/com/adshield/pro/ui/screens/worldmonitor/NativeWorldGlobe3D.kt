package com.adshield.pro.ui.screens.worldmonitor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import com.adshield.pro.domain.worldmonitor.GlobeBasemap
import com.adshield.pro.domain.worldmonitor.GlobeProjection
import com.adshield.pro.domain.worldmonitor.GlobeStylePreset
import com.adshield.pro.domain.worldmonitor.GraphicsPerformanceMode
import com.adshield.pro.domain.worldmonitor.WorldEntity
import com.adshield.pro.domain.worldmonitor.WorldIntelCategory
import com.adshield.pro.domain.worldmonitor.WorldSeverity
import com.adshield.pro.domain.worldmonitor.WorldViewport
import com.adshield.pro.engine.worldmonitor.BaselineReferenceStore
import com.adshield.pro.engine.worldmonitor.OrbitalAndGlobeMath
import com.adshield.pro.ui.viewmodel.NativeGlobeCommand
import com.adshield.pro.ui.viewmodel.WorldMonitorUiState
import com.adshield.pro.ui.viewmodel.WorldMonitorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Cached bundled offline globe texture (2048x1024 stitched NaturalEarthII level-2 mosaic),
 * continental relief elevation grid, and 595 sovereign coastline/border vector arcs.
 */
private object NativeGlobeAssetCache {
    @Volatile
    var earthBitmap: Bitmap? = null
        private set

    @Volatile
    var reliefGrid: FloatArray? = null
        private set

    @Volatile
    var countryArcs: List<FloatArray> = emptyList()
        private set

    const val RELIEF_W = 128
    const val RELIEF_H = 64

    private val loadLock = Any()

    fun ensureLoaded(context: Context) {
        if (earthBitmap != null && countryArcs.isNotEmpty()) return
        synchronized(loadLock) {
            if (earthBitmap == null) {
                earthBitmap = stitchNaturalEarthLevel2(context)
                earthBitmap?.let { bmp ->
                    reliefGrid = extractContinentalReliefGrid(bmp)
                }
            }
            if (countryArcs.isEmpty()) {
                countryArcs = runCatching {
                    val raw = context.assets
                        .open("worldmonitor/vendor/countries-110m.json")
                        .bufferedReader(Charsets.UTF_8)
                        .use { it.readText() }
                    OrbitalAndGlobeMath.decodeTopoJsonArcs(raw)
                }.getOrDefault(emptyList())
            }
        }
    }

    private fun stitchNaturalEarthLevel2(context: Context): Bitmap? {
        return runCatching {
            val tileSize = 256
            val cols = 8
            val rows = 4
            val mosaic = Bitmap.createBitmap(cols * tileSize, rows * tileSize, Bitmap.Config.RGB_565)
            val canvas = android.graphics.Canvas(mosaic)
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            var loadedTiles = 0
            for (x in 0 until cols) {
                for (tmsY in 0 until rows) {
                    val assetPath = "worldmonitor/vendor/cesium/Assets/Textures/NaturalEarthII/2/$x/$tmsY.jpg"
                    context.assets.open(assetPath).use { stream ->
                        val tile = BitmapFactory.decodeStream(stream, null, opts)
                        if (tile != null) {
                            val destLeft = (x * tileSize).toFloat()
                            val destTop = ((rows - 1 - tmsY) * tileSize).toFloat()
                            canvas.drawBitmap(tile, destLeft, destTop, null)
                            tile.recycle()
                            loadedTiles++
                        }
                    }
                }
            }
            if (loadedTiles > 0) mosaic else {
                mosaic.recycle()
                null
            }
        }.getOrNull()
    }

    private fun extractContinentalReliefGrid(bmp: Bitmap): FloatArray {
        val scaled = Bitmap.createScaledBitmap(bmp, RELIEF_W, RELIEF_H, true)
        val pixels = IntArray(RELIEF_W * RELIEF_H)
        scaled.getPixels(pixels, 0, RELIEF_W, 0, 0, RELIEF_W, RELIEF_H)
        if (scaled !== bmp) scaled.recycle()

        val relief = FloatArray(RELIEF_W * RELIEF_H)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            // Ocean pixels in NaturalEarthII are blue-dominant (b > r + 18 && b > g + 8)
            val isOcean = b > r + 16 && b > g + 6
            relief[i] = if (isOcean) {
                0f
            } else {
                val lum = (0.35f * r + 0.45f * g + 0.20f * b) / 255f
                (0.18f + lum * 0.82f).coerceIn(0f, 1f)
            }
        }
        return relief
    }

    fun sampleRelief(uNorm: Float, vNorm: Float): Float {
        val grid = reliefGrid ?: return 0f
        val gx = (uNorm.coerceIn(0f, 0.999f) * RELIEF_W).toInt()
        val gy = (vNorm.coerceIn(0f, 0.999f) * RELIEF_H).toInt()
        return grid[gy * RELIEF_W + gx]
    }
}

private class SphereMeshBuffers(val rows: Int = 36, val cols: Int = 72) {
    val vertCols = cols + 1
    val vertRows = rows + 1
    val numVertices = vertRows * vertCols
    val vertFloatCount = numVertices * 2
    val verts = FloatArray(vertFloatCount)
    val texs = FloatArray(vertFloatCount)
    val colors = IntArray(numVertices)
    val visible = BooleanArray(numVertices)
    val indices = ShortArray(rows * cols * 6)
    var configuredTexWidth = -1
    var configuredTexHeight = -1

    fun ensureTexCoords(texW: Int, texH: Int) {
        if (configuredTexWidth == texW && configuredTexHeight == texH) return
        configuredTexWidth = texW
        configuredTexHeight = texH
        var idx = 0
        for (r in 0..rows) {
            val vPx = (r.toFloat() / rows.toFloat()) * texH.toFloat()
            for (c in 0..cols) {
                val uPx = (c.toFloat() / cols.toFloat()) * texW.toFloat()
                texs[idx * 2] = uPx
                texs[idx * 2 + 1] = vPx
                idx++
            }
        }
    }
}

private data class PickableMarker(
    val x: Float,
    val y: Float,
    val hitRadiusPx: Float,
    val entity: WorldEntity?,
    val clusterZoomLat: Double = 0.0,
    val clusterZoomLon: Double = 0.0
)

private data class CameraFlight(
    val startMs: Long,
    val durationMs: Long,
    val fromLat: Double,
    val fromLon: Double,
    val fromAlt: Double,
    val fromPitch: Double,
    val fromHeading: Double,
    val toLat: Double,
    val toLon: Double,
    val toAlt: Double,
    val toPitch: Double,
    val toHeading: Double
)

/**
 * Hardware-accelerated pure-Kotlin 3D Digital Earth renderer powered by Skia/Vulkan GPU
 * triangle-mesh `drawVertices` + `BitmapShader`, real-time solar lighting, NORAD TLE J2
 * orbital mechanics, 595 sovereign country vectors, and 120-Hz multi-touch physics.
 */
@Composable
fun NativeWorldGlobe3D(
    uiState: WorldMonitorUiState,
    viewModel: WorldMonitorViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var assetsReady by remember { mutableStateOf(NativeGlobeAssetCache.earthBitmap != null) }

    // Camera state owned locally at 120 Hz for zero-recomposition-lag touch spin, synced to ViewModel
    var camLat by remember { mutableDoubleStateOf(14.5995) }
    var camLon by remember { mutableDoubleStateOf(120.9842) }
    var camAlt by remember { mutableDoubleStateOf(13_800_000.0) }
    var camPitch by remember { mutableDoubleStateOf(-86.0) }
    var camHeading by remember { mutableDoubleStateOf(0.0) }
    var velLat by remember { mutableDoubleStateOf(0.0) }
    var velLon by remember { mutableDoubleStateOf(0.0) }
    var activeFlight by remember { mutableStateOf<CameraFlight?>(null) }
    var selectedSatId by remember { mutableStateOf<String?>(null) }
    var orbitClockMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var lastViewportSyncMs by remember { mutableLongStateOf(0L) }
    var tileRedrawTick by remember { mutableIntStateOf(0) }
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val latestMeasureMode by rememberUpdatedState(uiState.measureModeEnabled)

    val mesh = remember { SphereMeshBuffers(rows = 36, cols = 72) }
    val pickTargets = remember { ArrayList<PickableMarker>(512) }
    val jsonParser = remember { Json { ignoreUnknownKeys = true } }

    // Reusable native Android Paint instances (zero per-frame Paint allocation)
    val paints = remember {
        object {
            val spherePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.0f
                color = 0x2238BDF8
            }
            val orbitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3.2f
                color = 0xE6C084FC.toInt()
            }
            val orbitBackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.8f
                color = 0x55C084FC
            }
            val dashedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
            }
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFF8FAFC.toInt()
                textSize = 23f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                setShadowLayer(5f, 0f, 1.5f, 0xEE020813.toInt())
            }
            val hudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xCCBAE6FD.toInt()
                textSize = 21f
                typeface = Typeface.MONOSPACE
            }
            val reusablePath = Path()
            val limbClipPath = Path()
            var cachedShaderBitmap: Bitmap? = null
            var cachedBasemap: GlobeBasemap? = null
            var cachedPreset: GlobeStylePreset? = null
        }
    }

    // Load 2048x1024 stitched NaturalEarthII texture + 595 TopoJSON border arcs off the main thread
    LaunchedEffect(context) {
        if (!assetsReady) {
            withContext(Dispatchers.IO) {
                NativeGlobeAssetCache.ensureLoaded(context.applicationContext)
            }
            assetsReady = true
        }
        viewModel.onGlobeReady(true, "Native-3D-GPU-2.0")
    }

    val sovereignCountries = remember(context) { BaselineReferenceStore.load(context.applicationContext).countries }

    // Parse TLE orbital catalog whenever entities update
    val parsedSatellites = remember(uiState.filteredEntities) {
        val map = LinkedHashMap<String, OrbitalAndGlobeMath.ParsedTleElements>()
        for (entity in uiState.filteredEntities) {
            if (entity.category == WorldIntelCategory.SPACE && !entity.tleLine1.isNullOrBlank()) {
                OrbitalAndGlobeMath.parseTle(entity.id, entity.title, entity.tleLine1, entity.tleLine2)?.let {
                    map[entity.id] = it
                }
            }
        }
        map
    }

    LaunchedEffect(parsedSatellites) {
        if (selectedSatId == null || selectedSatId !in parsedSatellites) {
            selectedSatId = parsedSatellites.keys.firstOrNull { id ->
                id.contains("25544") || parsedSatellites[id]?.title?.contains("ISS", ignoreCase = true) == true
            } ?: parsedSatellites.keys.firstOrNull()
        }
    }

    // 1-Hz live satellite propagation tick (paused in LOW_POWER to 3s)
    LaunchedEffect(uiState.performanceMode, parsedSatellites.isNotEmpty()) {
        while (isActive) {
            orbitClockMs = System.currentTimeMillis()
            delay(if (uiState.performanceMode == GraphicsPerformanceMode.LOW_POWER) 3_000L else 1_000L)
        }
    }

    // Collect native camera flight & action commands from ViewModel
    LaunchedEffect(viewModel) {
        viewModel.nativeGlobeCommands.collect { cmd ->
            val now = System.currentTimeMillis()
            when (cmd) {
                is NativeGlobeCommand.FlyTo -> {
                    velLat = 0.0
                    velLon = 0.0
                    activeFlight = CameraFlight(
                        startMs = now,
                        durationMs = 820L,
                        fromLat = camLat,
                        fromLon = camLon,
                        fromAlt = camAlt,
                        fromPitch = camPitch,
                        fromHeading = camHeading,
                        toLat = cmd.lat.coerceIn(-89.0, 89.0),
                        toLon = OrbitalAndGlobeMath.normalizeLon(cmd.lon),
                        toAlt = cmd.altitudeMeters.coerceIn(350.0, 30_000_000.0),
                        toPitch = cmd.pitchDeg.coerceIn(-90.0, -28.0),
                        toHeading = camHeading
                    )
                }
                is NativeGlobeCommand.CameraAction -> {
                    velLat = 0.0
                    velLon = 0.0
                    when (cmd.action) {
                        "ZOOM_IN" -> {
                            activeFlight = CameraFlight(
                                now, 300L, camLat, camLon, camAlt, camPitch, camHeading,
                                camLat, camLon, (camAlt * 0.50).coerceAtLeast(350.0), camPitch, camHeading
                            )
                        }
                        "ZOOM_OUT" -> {
                            activeFlight = CameraFlight(
                                now, 300L, camLat, camLon, camAlt, camPitch, camHeading,
                                camLat, camLon, (camAlt * 1.85).coerceAtMost(30_000_000.0), camPitch, camHeading
                            )
                        }
                        "TILT_3D" -> {
                            val nextPitch = if (camPitch > -64.0) -88.0 else -42.0
                            val nextAlt = if (nextPitch > -64.0) min(camAlt, 4_800_000.0) else camAlt
                            activeFlight = CameraFlight(
                                now, 520L, camLat, camLon, camAlt, camPitch, camHeading,
                                camLat, camLon, nextAlt, nextPitch, camHeading
                            )
                        }
                        "RESET_NORTH" -> {
                            activeFlight = CameraFlight(
                                now, 450L, camLat, camLon, camAlt, camPitch, camHeading,
                                camLat, camLon, camAlt, -88.0, 0.0
                            )
                        }
                        "HOME", "HOME_GLOBE" -> {
                            activeFlight = CameraFlight(
                                now, 780L, camLat, camLon, camAlt, camPitch, camHeading,
                                14.5995, 120.9842, 13_800_000.0, -86.0, 0.0
                            )
                        }
                    }
                }
                is NativeGlobeCommand.SelectSatellite -> {
                    selectedSatId = cmd.entityId
                }
            }
        }
    }

    // Smooth 60/120-Hz animation loop ONLY while flight, touch inertia, or 360° Auto-Orbit is active
    val hasInertia = abs(velLat) > 1e-6 || abs(velLon) > 1e-6
    LaunchedEffect(activeFlight != null, hasInertia, uiState.autoOrbitEnabled) {
        while (isActive && (activeFlight != null || abs(velLat) > 1e-6 || abs(velLon) > 1e-6 || uiState.autoOrbitEnabled)) {
            withFrameNanos { }
            val now = System.currentTimeMillis()
            val flight = activeFlight
            if (flight != null) {
                val t = ((now - flight.startMs).toDouble() / flight.durationMs.toDouble()).coerceIn(0.0, 1.0)
                val ease = 1.0 - (1.0 - t) * (1.0 - t) * (1.0 - t)
                val dLon = OrbitalAndGlobeMath.normalizeLon(flight.toLon - flight.fromLon)
                camLat = (flight.fromLat + (flight.toLat - flight.fromLat) * ease).coerceIn(-89.0, 89.0)
                camLon = OrbitalAndGlobeMath.normalizeLon(flight.fromLon + dLon * ease)
                camAlt = flight.fromAlt + (flight.toAlt - flight.fromAlt) * ease
                camPitch = flight.fromPitch + (flight.toPitch - flight.fromPitch) * ease
                camHeading = (flight.fromHeading + (flight.toHeading - flight.fromHeading) * ease + 360.0) % 360.0
                if (t >= 1.0) activeFlight = null
            } else {
                if (abs(velLat) > 1e-6 || abs(velLon) > 1e-6) {
                    camLat = (camLat + velLat).coerceIn(-88.5, 88.5)
                    camLon = OrbitalAndGlobeMath.normalizeLon(camLon + velLon)
                    velLat *= 0.90
                    velLon *= 0.90
                    if (abs(velLat) <= 1e-6 && abs(velLon) <= 1e-6) {
                        velLat = 0.0
                        velLon = 0.0
                    }
                }
                if (uiState.autoOrbitEnabled) {
                    camPitch += (-46.0 - camPitch) * 0.05
                    camHeading = (camHeading + 0.34) % 360.0
                }
            }
            if (now - lastViewportSyncMs > 280L) {
                lastViewportSyncMs = now
                pushViewportToViewModel(viewModel, camLat, camLon, camAlt, camPitch, camHeading, uiState.projection)
            }
        }
    }

    Canvas(
        modifier = modifier.pointerInput(uiState.projection) {
            var lastTapTimeMs = 0L
            var lastTapX = 0f
            var lastTapY = 0f

            awaitEachGesture {
                val firstDown = awaitFirstDown(requireUnconsumed = false)
                activeFlight = null
                velLat = 0.0
                velLon = 0.0

                val downTime = System.currentTimeMillis()
                val startX = firstDown.position.x
                val startY = firstDown.position.y
                var movedSignificantly = false
                var hadMultiTouch = false

                do {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }

                    if (pressed.size == 1 && !hadMultiTouch) {
                        val ch = pressed[0]
                        val dx = ch.position.x - ch.previousPosition.x
                        val dy = ch.position.y - ch.previousPosition.y
                        if (hypot(ch.position.x - startX, ch.position.y - startY) > 12f) {
                            movedSignificantly = true
                        }
                        if (ch.positionChanged() && (abs(dx) > 0.1f || abs(dy) > 0.1f)) {
                            val rPxDouble = OrbitalAndGlobeMath.globeRadiusPxDouble(
                                size.width.toFloat(),
                                size.height.toFloat(),
                                camAlt
                            ).coerceAtLeast(80.0)
                            // Exact 1-radian / R_px sensitivity locks streets 1:1 under your fingertip at ALL zoom levels!
                            val degPerPx = (57.2957795 / rPxDouble).coerceIn(1e-6, 0.65)
                            val hRad = camHeading * PI / 180.0
                            val dLonLocal = -dx * degPerPx
                            val dLatLocal = dy * degPerPx
                            val rotDLon = dLonLocal * cos(hRad) + dLatLocal * sin(hRad)
                            val rotDLat = -dLonLocal * sin(hRad) + dLatLocal * cos(hRad)
                            val cosLatScale = cos(camLat * PI / 180.0).coerceAtLeast(0.18)

                            camLon = OrbitalAndGlobeMath.normalizeLon(camLon + rotDLon / cosLatScale)
                            camLat = (camLat + rotDLat).coerceIn(-88.5, 88.5)
                            velLon = (rotDLon / cosLatScale) * 0.38
                            velLat = rotDLat * 0.38
                            ch.consume()

                            val now = System.currentTimeMillis()
                            if (now - lastViewportSyncMs > 250L) {
                                lastViewportSyncMs = now
                                pushViewportToViewModel(
                                    viewModel, camLat, camLon, camAlt, camPitch, camHeading, uiState.projection
                                )
                            }
                        }
                    } else if (pressed.size >= 2) {
                        hadMultiTouch = true
                        movedSignificantly = true
                        val p0 = pressed[0]
                        val p1 = pressed[1]

                        val prevDist = hypot(
                            p0.previousPosition.x - p1.previousPosition.x,
                            p0.previousPosition.y - p1.previousPosition.y
                        )
                        val curDist = hypot(
                            p0.position.x - p1.position.x,
                            p0.position.y - p1.position.y
                        )
                        if (prevDist > 16f && curDist > 16f) {
                            val zoomRatio = (prevDist / curDist).toDouble().coerceIn(0.72, 1.38)
                            camAlt = (camAlt * zoomRatio).coerceIn(350.0, 30_000_000.0)
                        }

                        // Two-finger vertical drag tilts 3D horizon pitch; twist rotates compass heading
                        val avgDy = ((p0.position.y - p0.previousPosition.y) + (p1.position.y - p1.previousPosition.y)) * 0.5f
                        if (abs(avgDy) > 1.2f && uiState.projection == GlobeProjection.GLOBE_3D) {
                            camPitch = (camPitch - avgDy * 0.16).coerceIn(-90.0, -28.0)
                        }

                        val prevAng = atan2(
                            (p1.previousPosition.y - p0.previousPosition.y).toDouble(),
                            (p1.previousPosition.x - p0.previousPosition.x).toDouble()
                        )
                        val curAng = atan2(
                            (p1.position.y - p0.position.y).toDouble(),
                            (p1.position.x - p0.position.x).toDouble()
                        )
                        var dAngDeg = (curAng - prevAng) * 180.0 / PI
                        if (dAngDeg > 180.0) dAngDeg -= 360.0
                        if (dAngDeg < -180.0) dAngDeg += 360.0
                        if (abs(dAngDeg) in 0.4..18.0) {
                            camHeading = (camHeading - dAngDeg + 360.0) % 360.0
                        }

                        pressed.forEach { it.consume() }
                        val now = System.currentTimeMillis()
                        if (now - lastViewportSyncMs > 250L) {
                            lastViewportSyncMs = now
                            pushViewportToViewModel(
                                viewModel, camLat, camLon, camAlt, camPitch, camHeading, uiState.projection
                            )
                        }
                    }
                } while (event.changes.any { it.pressed })

                // Single-tap / Double-tap handling
                if (!movedSignificantly && !hadMultiTouch && System.currentTimeMillis() - downTime < 360L) {
                    val now = System.currentTimeMillis()
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val cx = w * 0.5f
                    val cy = h * 0.47f
                    val rPx = OrbitalAndGlobeMath.globeRadiusPx(w, h, camAlt)

                    if (now - lastTapTimeMs < 320L && hypot(startX - lastTapX, startY - lastTapY) < 48f) {
                        lastTapTimeMs = 0L
                        val geo = OrbitalAndGlobeMath.unprojectFromScreen(
                            startX, startY, camLat, camLon, camPitch, camHeading, uiState.projection, cx, cy, rPx
                        )
                        val targetLat = geo?.first ?: camLat
                        val targetLon = geo?.second ?: camLon
                        activeFlight = CameraFlight(
                            now, 480L, camLat, camLon, camAlt, camPitch, camHeading,
                            targetLat, targetLon, (camAlt * 0.42).coerceAtLeast(350.0), camPitch, camHeading
                        )
                    } else {
                        lastTapTimeMs = now
                        lastTapX = startX
                        lastTapY = startY

                        if (latestMeasureMode) {
                            val geo = OrbitalAndGlobeMath.unprojectFromScreen(
                                startX, startY, camLat, camLon, camPitch, camHeading, uiState.projection, cx, cy, rPx
                            )
                            if (geo != null) {
                                viewModel.recordMeasurePoint(geo.first, geo.second)
                            }
                        } else {
                            // Hit-test rendered markers (topmost first)
                            var hitMarker: PickableMarker? = null
                            var bestDist = Float.MAX_VALUE
                            for (idx in pickTargets.size - 1 downTo 0) {
                                val m = pickTargets[idx]
                                val d = hypot(m.x - startX, m.y - startY)
                                if (d <= m.hitRadiusPx && d < bestDist) {
                                    bestDist = d
                                    hitMarker = m
                                }
                            }

                            if (hitMarker != null) {
                                val ent = hitMarker.entity
                                if (ent != null) {
                                    viewModel.onEntitySelected(ent.id)
                                    if (ent.category == WorldIntelCategory.SPACE) {
                                        selectedSatId = ent.id
                                    }
                                } else {
                                    // Cluster or City Gazetteer pin tapped -> fly smoothly down into city/cluster
                                    activeFlight = CameraFlight(
                                        now, 620L, camLat, camLon, camAlt, camPitch, camHeading,
                                        hitMarker.clusterZoomLat,
                                        hitMarker.clusterZoomLon,
                                        (camAlt * 0.28).coerceIn(3_200.0, 220_000.0),
                                        -52.0,
                                        camHeading
                                    )
                                }
                            } else {
                                val geo = OrbitalAndGlobeMath.unprojectFromScreen(
                                    startX, startY, camLat, camLon, camPitch, camHeading, uiState.projection, cx, cy, rPx
                                )
                                if (geo != null) {
                                    val uNorm = ((geo.second + 180.0) / 360.0).toFloat()
                                    val vNorm = ((90.0 - geo.first) / 180.0).toFloat()
                                    val estElevM = (NativeGlobeAssetCache.sampleRelief(uNorm, vNorm) * 1_650f).roundToInt()
                                    viewModel.onLocationSelected(geo.first, geo.second, estElevM, "")
                                }
                            }
                        }
                    }
                } else {
                    pushViewportToViewModel(
                        viewModel, camLat, camLon, camAlt, camPitch, camHeading, uiState.projection
                    )
                }
            }
        }
    ) {
        val w = size.width
        val h = size.height
        if (w <= 4f || h <= 4f) return@Canvas

        // Observe async slippy-tile arrivals so city/street tiles sharpen immediately on download
        val redrawGeneration = tileRedrawTick
        if (redrawGeneration < 0) return@Canvas

        val effectiveEpochMs = orbitClockMs + uiState.futureOffsetHours * 3_600_000L
        val cx = w * 0.5f
        val cy = h * 0.47f
        val rPx = OrbitalAndGlobeMath.globeRadiusPx(w, h, camAlt)
        val limb = OrbitalAndGlobeMath.computeLimbCircle(cx, cy, rPx, camPitch)
        val is3D = uiState.projection == GlobeProjection.GLOBE_3D

        drawIntoCanvas { composeCanvas ->
            val nc = composeCanvas.nativeCanvas

            // 1. Deep-Space Background & Parallax Starfield
            nc.drawColor(0xFF020813.toInt())
            val starShiftX = ((-camLon / 360.0) * w * 0.25).toFloat()
            val starShiftY = ((camLat / 180.0) * h * 0.18).toFloat()
            for (i in 0 until 110) {
                val sx = (((i * 7919) % 1000) / 1000f * w + starShiftX + w) % w
                val sy = (((i * 6271) % 1000) / 1000f * h + starShiftY + h) % h
                val alpha = 75 + (i * 37) % 140
                paints.fillPaint.shader = null
                paints.fillPaint.color = (alpha shl 24) or 0xBAE6FD
                nc.drawCircle(sx, sy, if (i % 4 == 0) 2.2f else 1.25f, paints.fillPaint)
            }

            // Precompute selected satellite 3D orbital track (back-side occluded behind sphere!)
            val activeOrbitTrack = selectedSatId?.let { id ->
                parsedSatellites[id]?.let { tle -> OrbitalAndGlobeMath.buildOrbitTrack(tle, effectiveEpochMs, 2) }
            }.orEmpty()

            if (is3D && activeOrbitTrack.size >= 2 && uiState.layerStates[WorldIntelCategory.SPACE]?.visible != false) {
                drawOrbitPass(
                    nc = nc,
                    track = activeOrbitTrack,
                    frontSide = false,
                    camLat = camLat,
                    camLon = camLon,
                    camPitch = camPitch,
                    camHeading = camHeading,
                    projection = uiState.projection,
                    cx = cx,
                    cy = cy,
                    rPx = rPx,
                    limb = limb,
                    path = paints.reusablePath,
                    paint = paints.orbitBackPaint
                )
            }

            // 2. Rayleigh 3D Atmospheric Outer Halo
            if (is3D && uiState.atmosphereEnabled) {
                val outerR = limb.radiusPx * 1.15f
                paints.fillPaint.shader = RadialGradient(
                    limb.centerX,
                    limb.centerY,
                    outerR,
                    intArrayOf(0x00020813, 0x220EA5E9, 0x5822D3EE, 0x1638BDF8, 0x00020813),
                    floatArrayOf(0f, 0.80f, 0.875f, 0.94f, 1f),
                    Shader.TileMode.CLAMP
                )
                nc.drawCircle(limb.centerX, limb.centerY, outerR, paints.fillPaint)
                paints.fillPaint.shader = null
            }

            // 3. Textured & Solar-Illuminated 3D Sphere Mesh (`drawVertices`)
            nc.save()
            if (is3D) {
                paints.limbClipPath.reset()
                paints.limbClipPath.addCircle(limb.centerX, limb.centerY, limb.radiusPx, Path.Direction.CW)
                nc.clipPath(paints.limbClipPath)
            }

            // Base ocean sphere fill (ensures zero sub-pixel rim gap and instant Frame-1 render)
            if (is3D) {
                paints.fillPaint.shader = RadialGradient(
                    limb.centerX - limb.radiusPx * 0.28f,
                    limb.centerY - limb.radiusPx * 0.28f,
                    limb.radiusPx * 1.15f,
                    intArrayOf(0xFF134B78.toInt(), 0xFF0A2A4D.toInt(), 0xFF031024.toInt()),
                    floatArrayOf(0f, 0.62f, 1f),
                    Shader.TileMode.CLAMP
                )
                nc.drawCircle(limb.centerX, limb.centerY, limb.radiusPx, paints.fillPaint)
                paints.fillPaint.shader = null
            } else {
                nc.drawColor(0xFF061A33.toInt())
            }

            val earthBmp = NativeGlobeAssetCache.earthBitmap
            val (sunLat, sunLon) = OrbitalAndGlobeMath.computeSubSolarPoint(effectiveEpochMs)
            val sunPhi = sunLat * PI / 180.0
            val sunLam = sunLon * PI / 180.0
            val sunX = cos(sunPhi) * cos(sunLam)
            val sunY = cos(sunPhi) * sin(sunLam)
            val sunZ = sin(sunPhi)

            if (earthBmp != null && !earthBmp.isRecycled) {
                if (paints.cachedShaderBitmap !== earthBmp) {
                    paints.spherePaint.shader = BitmapShader(
                        earthBmp,
                        Shader.TileMode.CLAMP,
                        Shader.TileMode.CLAMP
                    )
                    paints.cachedShaderBitmap = earthBmp
                }
                if (paints.cachedBasemap != uiState.basemap || paints.cachedPreset != uiState.stylePreset) {
                    paints.spherePaint.colorFilter = buildGlobeColorFilter(uiState.basemap, uiState.stylePreset)
                    paints.cachedBasemap = uiState.basemap
                    paints.cachedPreset = uiState.stylePreset
                }

                mesh.ensureTexCoords(earthBmp.width, earthBmp.height)
                val terrainScale = if (uiState.terrain3dEnabled && is3D) 260_000.0 else 0.0

                var vIdx = 0
                for (r in 0..mesh.rows) {
                    val vFrac = r.toFloat() / mesh.rows.toFloat()
                    val lat = 90.0 - vFrac * 180.0
                    val latRad = lat * PI / 180.0
                    val cosLat = cos(latRad)
                    val sinLat = sin(latRad)

                    for (c in 0..mesh.cols) {
                        val uFrac = c.toFloat() / mesh.cols.toFloat()
                        val lon = -180.0 + uFrac * 360.0
                        val lonRad = lon * PI / 180.0

                        val reliefAlt = if (terrainScale > 0.0) {
                            NativeGlobeAssetCache.sampleRelief(uFrac, vFrac) * terrainScale
                        } else 0.0

                        val pt = OrbitalAndGlobeMath.projectToScreen(
                            latDeg = lat,
                            lonDeg = lon,
                            altitudeMeters = reliefAlt,
                            camLatDeg = camLat,
                            camLonDeg = camLon,
                            pitchDeg = camPitch,
                            headingDeg = camHeading,
                            projection = uiState.projection,
                            cx = cx,
                            cy = cy,
                            radiusPx = rPx
                        )
                        mesh.verts[vIdx * 2] = pt.x
                        mesh.verts[vIdx * 2 + 1] = pt.y
                        mesh.visible[vIdx] = pt.visible

                        // Per-vertex 3D solar illumination & terminator tint
                        val shadeColor = if (uiState.dayNightLightingEnabled && is3D) {
                            val nx = cosLat * cos(lonRad)
                            val ny = cosLat * sin(lonRad)
                            val nz = sinLat
                            val sunDot = (nx * sunX + ny * sunY + nz * sunZ).toFloat()
                            computeTerminatorVertexColor(sunDot, uiState.stylePreset)
                        } else {
                            0xFFFFFFFF.toInt()
                        }
                        mesh.colors[vIdx] = shadeColor
                        vIdx++
                    }
                }

                var indexCursor = 0
                for (r in 0 until mesh.rows) {
                    val rowStart = r * mesh.vertCols
                    val nextRowStart = (r + 1) * mesh.vertCols
                    for (c in 0 until mesh.cols) {
                        val i00 = rowStart + c
                        val i10 = i00 + 1
                        val i01 = nextRowStart + c
                        val i11 = i01 + 1

                        if (mesh.visible[i00] || mesh.visible[i10] || mesh.visible[i01] || mesh.visible[i11]) {
                            // Guard against wrap jumps in 2D equirectangular mode
                            if (!is3D) {
                                val maxSpan = w * 0.45f
                                if (abs(mesh.verts[i10 * 2] - mesh.verts[i00 * 2]) > maxSpan ||
                                    abs(mesh.verts[i11 * 2] - mesh.verts[i01 * 2]) > maxSpan
                                ) {
                                    continue
                                }
                            }
                            mesh.indices[indexCursor++] = i00.toShort()
                            mesh.indices[indexCursor++] = i10.toShort()
                            mesh.indices[indexCursor++] = i01.toShort()
                            mesh.indices[indexCursor++] = i10.toShort()
                            mesh.indices[indexCursor++] = i11.toShort()
                            mesh.indices[indexCursor++] = i01.toShort()
                        }
                    }
                }

                if (indexCursor > 0) {
                    nc.drawVertices(
                        android.graphics.Canvas.VertexMode.TRIANGLES,
                        mesh.vertFloatCount,
                        mesh.verts,
                        0,
                        mesh.texs,
                        0,
                        mesh.colors,
                        0,
                        mesh.indices,
                        0,
                        indexCursor,
                        paints.spherePaint
                    )
                }

                // Multi-zoom slippy city/street/satellite/hybrid-label tile streaming (z = 4..16)!
                NativeGlobeTileStreamer.renderHighResTilesOnSphere(
                    context = context,
                    nc = nc,
                    basemap = uiState.basemap,
                    labelsEnabled = uiState.labelsEnabled,
                    camLat = camLat,
                    camLon = camLon,
                    camAltMeters = camAlt,
                    camPitch = camPitch,
                    camHeading = camHeading,
                    projection = uiState.projection,
                    cx = cx,
                    cy = cy,
                    rPx = rPx,
                    viewportW = w,
                    colorFilter = paints.spherePaint.colorFilter,
                    onTileLoaded = {
                        mainHandler.post { tileRedrawTick++ }
                    }
                )
            }

            // Historical Before/After Split Comparison tint
            if (uiState.historicalSplit.enabled) {
                val splitX = w * uiState.historicalSplit.splitPosition.coerceIn(0.1f, 0.9f)
                paints.fillPaint.color = 0x2838BDF8
                nc.drawRect(0f, 0f, splitX, h, paints.fillPaint)
                paints.strokePaint.color = 0xFF38BDF8.toInt()
                paints.strokePaint.strokeWidth = 3f
                nc.drawLine(splitX, 0f, splitX, h, paints.strokePaint)
            }

            // 4. 15-Degree 3D Graticule
            var gLat = -60.0
            while (gLat <= 60.0) {
                paints.reusablePath.reset()
                var penDown = false
                var gLon = -180.0
                while (gLon <= 180.0) {
                    val p = OrbitalAndGlobeMath.projectToScreen(
                        gLat, gLon, 0.0, camLat, camLon, camPitch, camHeading, uiState.projection, cx, cy, rPx
                    )
                    if (p.visible && (!is3D || penDown.not() || abs(p.x - cx) < w)) {
                        if (!penDown) {
                            paints.reusablePath.moveTo(p.x, p.y)
                            penDown = true
                        } else {
                            paints.reusablePath.lineTo(p.x, p.y)
                        }
                    } else {
                        penDown = false
                    }
                    gLon += 8.0
                }
                nc.drawPath(paints.reusablePath, paints.gridPaint)
                gLat += 30.0
            }
            var mLon = -180.0
            while (mLon < 180.0) {
                paints.reusablePath.reset()
                var penDown = false
                var mLat = -84.0
                while (mLat <= 84.0) {
                    val p = OrbitalAndGlobeMath.projectToScreen(
                        mLat, mLon, 0.0, camLat, camLon, camPitch, camHeading, uiState.projection, cx, cy, rPx
                    )
                    if (p.visible) {
                        if (!penDown) {
                            paints.reusablePath.moveTo(p.x, p.y)
                            penDown = true
                        } else {
                            paints.reusablePath.lineTo(p.x, p.y)
                        }
                    } else {
                        penDown = false
                    }
                    mLat += 6.0
                }
                nc.drawPath(paints.reusablePath, paints.gridPaint)
                mLon += 30.0
            }

            // 5. Bundled 595 Sovereign Country & Coastline 3D Borders
            if (uiState.bordersEnabled) {
                paints.borderPaint.color = 0x8C22D3EE.toInt()
                paints.borderPaint.strokeWidth = if (camAlt < 3_000_000.0) 1.9f else 1.25f
                val arcs = NativeGlobeAssetCache.countryArcs
                for (arc in arcs) {
                    paints.reusablePath.reset()
                    var penDown = false
                    var prevX = 0f
                    var idx = 0
                    while (idx + 1 < arc.size) {
                        val lon = arc[idx].toDouble()
                        val lat = arc[idx + 1].toDouble()
                        val p = OrbitalAndGlobeMath.projectToScreen(
                            lat, lon, 0.0, camLat, camLon, camPitch, camHeading, uiState.projection, cx, cy, rPx
                        )
                        if (p.visible && (!penDown || abs(p.x - prevX) < w * 0.42f)) {
                            if (!penDown) {
                                paints.reusablePath.moveTo(p.x, p.y)
                                penDown = true
                            } else {
                                paints.reusablePath.lineTo(p.x, p.y)
                            }
                            prevX = p.x
                        } else {
                            penDown = false
                        }
                        idx += 2
                    }
                    nc.drawPath(paints.reusablePath, paints.borderPaint)
                }
            }

            // 5B. 202 Sovereign Countries & Capitals 3D Map Layer (zoom-decluttered)
            if (uiState.labelsEnabled) {
                val minPop = when {
                    camAlt > 7_000_000.0 -> 28_000_000L
                    camAlt > 3_200_000.0 -> 6_500_000L
                    camAlt > 1_400_000.0 -> 850_000L
                    else -> 0L
                }
                val cellW = if (camAlt > 3_500_000.0) 118f else 92f
                val cellH = 34f
                val usedCells = HashSet<Long>(64)
                val savedTextSize = paints.labelPaint.textSize
                paints.labelPaint.textSize = if (camAlt < 1_800_000.0) 24f else 21f

                for (country in sovereignCountries) {
                    val isActiveCountry = country.iso2 == uiState.activeCountryIso
                    if (!isActiveCountry && country.population < minPop) continue
                    val cp = OrbitalAndGlobeMath.projectToScreen(
                        country.lat, country.lon, 0.0, camLat, camLon, camPitch, camHeading, uiState.projection, cx, cy, rPx
                    )
                    if (!cp.visible || cp.x < 24f || cp.x > w - 80f || cp.y < 30f || cp.y > h - 30f) continue
                    val gx = (cp.x / cellW).toInt()
                    val gy = (cp.y / cellH).toInt()
                    val cellKey = (gx.toLong() shl 32) or (gy.toLong() and 0xFFFFFFFFL)
                    if (!isActiveCountry && !usedCells.add(cellKey)) continue
                    usedCells.add(cellKey)

                    paints.fillPaint.shader = null
                    paints.fillPaint.color = if (isActiveCountry) 0xFF10B981.toInt() else 0xCC38BDF8.toInt()
                    nc.drawCircle(cp.x, cp.y, if (isActiveCountry) 6.2f else 3.6f, paints.fillPaint)
                    if (isActiveCountry) {
                        paints.strokePaint.color = 0xAA34D399.toInt()
                        paints.strokePaint.strokeWidth = 2.0f
                        nc.drawCircle(cp.x, cp.y, 11.5f, paints.strokePaint)
                    }
                    val countryLabel = if (camAlt < 1_350_000.0) {
                        "★ ${country.capital} · ${country.name}"
                    } else {
                        country.name
                    }
                    paints.labelPaint.color = if (isActiveCountry) 0xFF6EE7B7.toInt() else 0xDCE2E8F0.toInt()
                    nc.drawText(countryLabel, cp.x + 8f, cp.y + 6f, paints.labelPaint)
                }
                paints.labelPaint.textSize = savedTextSize
                paints.labelPaint.color = 0xFFF8FAFC.toInt()
            }

            // Optional Clouds / Radar atmospheric sweeps
            if (uiState.cloudsOverlayEnabled) {
                for (band in 0 until 9) {
                    val bLat = -55.0 + band * 14.0 + sin(orbitClockMs / 60_000.0 + band) * 4.0
                    val bLon = OrbitalAndGlobeMath.normalizeLon(-150.0 + band * 41.0 + (orbitClockMs / 300_000.0) % 360.0)
                    val p = OrbitalAndGlobeMath.projectToScreen(
                        bLat, bLon, 6_000.0, camLat, camLon, camPitch, camHeading, uiState.projection, cx, cy, rPx
                    )
                    if (p.visible) {
                        val cr = rPx * 0.19f
                        paints.fillPaint.shader = RadialGradient(
                            p.x, p.y, cr,
                            intArrayOf(0x36F8FAFC, 0x16E2E8F0, 0x00FFFFFF),
                            floatArrayOf(0f, 0.55f, 1f),
                            Shader.TileMode.CLAMP
                        )
                        nc.drawCircle(p.x, p.y, cr, paints.fillPaint)
                        paints.fillPaint.shader = null
                    }
                }
            }

            if (uiState.heatmapEnabled) {
                for (ent in uiState.filteredEntities.take(260)) {
                    val p = OrbitalAndGlobeMath.projectToScreen(
                        ent.latitude, ent.longitude, 0.0, camLat, camLon, camPitch, camHeading, uiState.projection, cx, cy, rPx
                    )
                    if (!p.visible) continue
                    val hr = if (ent.severity == WorldSeverity.CRITICAL) 44f else 28f
                    paints.fillPaint.shader = RadialGradient(
                        p.x, p.y, hr,
                        intArrayOf(0x66EF4444, 0x33F59E0B, 0x0022D3EE),
                        floatArrayOf(0f, 0.5f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    nc.drawCircle(p.x, p.y, hr, paints.fillPaint)
                }
                paints.fillPaint.shader = null
            }

            // 6. Watchtower 3D Geodesic Geofences & Simulation Lab Rings
            for (reg in uiState.watchRegions) {
                if (!reg.enabled) continue
                drawGeodesicCircle3D(
                    nc = nc,
                    centerLat = reg.centerLat,
                    centerLon = reg.centerLon,
                    radiusKm = reg.radiusKm,
                    fillColor = 0x1822D3EE,
                    strokeColor = 0xCC22D3EE.toInt(),
                    strokeWidth = 2.4f,
                    camLat = camLat,
                    camLon = camLon,
                    camPitch = camPitch,
                    camHeading = camHeading,
                    projection = uiState.projection,
                    cx = cx,
                    cy = cy,
                    rPx = rPx,
                    path = paints.reusablePath,
                    fillPaint = paints.fillPaint,
                    strokePaint = paints.strokePaint
                )
            }

            if (uiState.simulation.active) {
                for (ring in uiState.simulation.rings) {
                    val parsedRingColor = runCatching {
                        android.graphics.Color.parseColor(ring.colorHex)
                    }.getOrDefault(0xFFF59E0B.toInt())
                    val alphaInt = ((ring.alpha.coerceIn(0.08, 0.50)) * 255.0).roundToInt()
                    val fillCol = (alphaInt shl 24) or (parsedRingColor and 0x00FFFFFF)
                    val strokeCol = (0xE6 shl 24) or (parsedRingColor and 0x00FFFFFF)
                    drawGeodesicCircle3D(
                        nc = nc,
                        centerLat = ring.lat,
                        centerLon = ring.lon,
                        radiusKm = ring.radiusKm,
                        fillColor = fillCol,
                        strokeColor = strokeCol,
                        strokeWidth = 2.8f,
                        camLat = camLat,
                        camLon = camLon,
                        camPitch = camPitch,
                        camHeading = camHeading,
                        projection = uiState.projection,
                        cx = cx,
                        cy = cy,
                        rPx = rPx,
                        path = paints.reusablePath,
                        fillPaint = paints.fillPaint,
                        strokePaint = paints.strokePaint
                    )
                }
            }

            // Correlated Multi-Source 3D Ballistic Great-Circle Arcs
            paints.dashedPaint.color = 0xE6F472B6.toInt()
            paints.dashedPaint.strokeWidth = 2.6f
            for (corr in uiState.correlations) {
                for (lnk in corr.links) {
                    paints.reusablePath.reset()
                    var down = false
                    for (step in 0..16) {
                        val f = step / 16.0
                        val iLat = lnk.fromLat + (lnk.toLat - lnk.fromLat) * f
                        val dL = OrbitalAndGlobeMath.normalizeLon(lnk.toLon - lnk.fromLon)
                        val iLon = OrbitalAndGlobeMath.normalizeLon(lnk.fromLon + dL * f)
                        val arcAlt = sin(f * PI) * 280_000.0
                        val p = OrbitalAndGlobeMath.projectToScreen(
                            iLat, iLon, arcAlt, camLat, camLon, camPitch, camHeading, uiState.projection, cx, cy, rPx
                        )
                        if (p.visible) {
                            if (!down) { paints.reusablePath.moveTo(p.x, p.y); down = true }
                            else paints.reusablePath.lineTo(p.x, p.y)
                        } else down = false
                    }
                    nc.drawPath(paints.reusablePath, paints.dashedPaint)
                }
            }

            // Entity Historical Motion Trails
            if (uiState.trailsEnabled && uiState.entityTrails.isNotEmpty()) {
                for (trail in uiState.entityTrails) {
                    if (trail.points.size < 2) continue
                    paints.borderPaint.color = categoryColorInt(trail.category, WorldSeverity.INFO, 155)
                    paints.borderPaint.strokeWidth = 2.2f
                    paints.reusablePath.reset()
                    var down = false
                    for (tp in trail.points) {
                        val sp = OrbitalAndGlobeMath.projectToScreen(
                            tp.lat, tp.lon, tp.altM, camLat, camLon, camPitch, camHeading, uiState.projection, cx, cy, rPx
                        )
                        if (sp.visible) {
                            if (!down) { paints.reusablePath.moveTo(sp.x, sp.y); down = true }
                            else paints.reusablePath.lineTo(sp.x, sp.y)
                        } else down = false
                    }
                    nc.drawPath(paints.reusablePath, paints.borderPaint)
                }
            }

            // 3D Extruded OSM City Buildings (ground walls + elevated roof prism!)
            if (uiState.buildings3d.isNotEmpty()) {
                paints.strokePaint.color = 0xCC38BDF8.toInt()
                paints.strokePaint.strokeWidth = 1.6f
                paints.fillPaint.color = 0xBB1E3A5F.toInt()
                for (bldg in uiState.buildings3d.take(260)) {
                    if (bldg.footprint.size < 3) continue
                    val roofAlt = (bldg.heightMeters * 14.0).coerceIn(24.0, 4_500.0)
                    paints.reusablePath.reset()
                    var ok = true
                    bldg.footprint.forEachIndexed { idx, pt ->
                        if (pt.size >= 2) {
                            val baseSp = OrbitalAndGlobeMath.projectToScreen(
                                pt[1], pt[0], 0.0, camLat, camLon, camPitch, camHeading, uiState.projection, cx, cy, rPx
                            )
                            val roofSp = OrbitalAndGlobeMath.projectToScreen(
                                pt[1], pt[0], roofAlt, camLat, camLon, camPitch, camHeading, uiState.projection, cx, cy, rPx
                            )
                            if (!roofSp.visible) {
                                ok = false
                            } else {
                                if (baseSp.visible) {
                                    paints.strokePaint.color = 0x6622D3EE
                                    nc.drawLine(baseSp.x, baseSp.y, roofSp.x, roofSp.y, paints.strokePaint)
                                }
                                if (idx == 0) paints.reusablePath.moveTo(roofSp.x, roofSp.y)
                                else paints.reusablePath.lineTo(roofSp.x, roofSp.y)
                            }
                        }
                    }
                    if (ok) {
                        paints.strokePaint.color = 0xCC38BDF8.toInt()
                        paints.reusablePath.close()
                        nc.drawPath(paints.reusablePath, paints.fillPaint)
                        nc.drawPath(paints.reusablePath, paints.strokePaint)
                    }
                }
            }

            // Interactive 2-Point Geodesic Distance & Flight-Time Ruler Arc
            val mStart = uiState.measureStart
            if (mStart != null) {
                val spA = OrbitalAndGlobeMath.projectToScreen(
                    mStart.first, mStart.second, 500.0, camLat, camLon, camPitch, camHeading, uiState.projection, cx, cy, rPx
                )
                val mEnd = uiState.measureEnd
                if (mEnd != null) {
                    paints.dashedPaint.color = 0xFFFBBF24.toInt()
                    paints.dashedPaint.strokeWidth = 3.4f
                    paints.reusablePath.reset()
                    var down = false
                    for (step in 0..24) {
                        val f = step / 24.0
                        val iLat = mStart.first + (mEnd.first - mStart.first) * f
                        val dL = OrbitalAndGlobeMath.normalizeLon(mEnd.second - mStart.second)
                        val iLon = OrbitalAndGlobeMath.normalizeLon(mStart.second + dL * f)
                        val lift = sin(f * PI) * 160_000.0
                        val p = OrbitalAndGlobeMath.projectToScreen(
                            iLat, iLon, lift, camLat, camLon, camPitch, camHeading, uiState.projection, cx, cy, rPx
                        )
                        if (p.visible) {
                            if (!down) { paints.reusablePath.moveTo(p.x, p.y); down = true }
                            else paints.reusablePath.lineTo(p.x, p.y)
                        } else down = false
                    }
                    nc.drawPath(paints.reusablePath, paints.dashedPaint)
                    val spB = OrbitalAndGlobeMath.projectToScreen(
                        mEnd.first, mEnd.second, 500.0, camLat, camLon, camPitch, camHeading, uiState.projection, cx, cy, rPx
                    )
                    if (spB.visible) {
                        paints.fillPaint.color = 0xFFFBBF24.toInt()
                        nc.drawCircle(spB.x, spB.y, 10f, paints.fillPaint)
                        nc.drawText("B", spB.x + 13f, spB.y + 6f, paints.labelPaint)
                    }
                }
                if (spA.visible) {
                    paints.fillPaint.color = 0xFF10B981.toInt()
                    nc.drawCircle(spA.x, spA.y, 10f, paints.fillPaint)
                    nc.drawText("A", spA.x + 13f, spA.y + 6f, paints.labelPaint)
                }
            }

            nc.restore() // Release sphere clip so elevated orbital satellites & icons float cleanly above Earth's limb!

            // Subtle crisp atmospheric rim ring
            if (is3D) {
                paints.strokePaint.color = 0x8838BDF8.toInt()
                paints.strokePaint.strokeWidth = 2.0f
                nc.drawCircle(limb.centerX, limb.centerY, limb.radiusPx, paints.strokePaint)
            }

            // 7. Front-Side 3D Satellite Orbit Ring + Ground Track
            if (activeOrbitTrack.size >= 2 && uiState.layerStates[WorldIntelCategory.SPACE]?.visible != false) {
                drawOrbitPass(
                    nc = nc,
                    track = activeOrbitTrack,
                    frontSide = true,
                    camLat = camLat,
                    camLon = camLon,
                    camPitch = camPitch,
                    camHeading = camHeading,
                    projection = uiState.projection,
                    cx = cx,
                    cy = cy,
                    rPx = rPx,
                    limb = limb,
                    path = paints.reusablePath,
                    paint = paints.orbitPaint
                )
            }

            // 8. Live Tactical Intelligence Entities + Clustering + Submarine Cables
            pickTargets.clear()
            val maxEntities = when (uiState.performanceMode) {
                GraphicsPerformanceMode.LOW_POWER -> 220
                GraphicsPerformanceMode.BALANCED -> 550
                GraphicsPerformanceMode.HIGH -> 1_100
            }

            val shouldCluster = uiState.clusteringEnabled && camAlt > 3_800_000.0
            val clusterCellPx = 68f
            val clusterBuckets = if (shouldCluster) HashMap<Long, MutableList<Pair<OrbitalAndGlobeMath.ScreenPoint3D, WorldEntity>>>() else null

            var drawnCount = 0
            for (entity in uiState.filteredEntities) {
                if (drawnCount >= maxEntities) break
                val layerCfg = uiState.layerStates[entity.category]
                if (layerCfg?.visible == false) continue
                val opacity = (layerCfg?.opacity ?: 1f).coerceIn(0.2f, 1f)

                // Draw LineString submarine cables / storm tracks directly on globe
                if (entity.geometryType == "LineString" && !entity.geometryCoordsJson.isNullOrBlank()) {
                    drawEntityLineGeometry(
                        nc = nc,
                        coordsJson = entity.geometryCoordsJson.orEmpty(),
                        colorInt = categoryColorInt(entity.category, entity.severity, (opacity * 195).toInt()),
                        camLat = camLat,
                        camLon = camLon,
                        camPitch = camPitch,
                        camHeading = camHeading,
                        projection = uiState.projection,
                        cx = cx,
                        cy = cy,
                        rPx = rPx,
                        w = w,
                        json = jsonParser,
                        path = paints.reusablePath,
                        strokePaint = paints.borderPaint
                    )
                }

                // Live / Future Horizon TLE satellite & moving trajectory projection
                val satTle = if (entity.category == WorldIntelCategory.SPACE) parsedSatellites[entity.id] else null
                val satVec = satTle?.let { OrbitalAndGlobeMath.propagateTle(it, effectiveEpochMs) }

                var eLat = satVec?.latitude ?: entity.latitude
                var eLon = satVec?.longitude ?: entity.longitude
                val eAlt = satVec?.altitudeMeters ?: (entity.altitudeMeters ?: 0.0)

                // Dead-reckon moving aircraft & ships both in real-time LIVE mode (0H) and Future Horizon (+1H..+72H)!
                if ((entity.category == WorldIntelCategory.AVIATION || entity.category == WorldIntelCategory.MARITIME) &&
                    (entity.speedValue ?: 0.0) > 5.0 &&
                    entity.headingDeg != null
                ) {
                    val speedKmh = (entity.speedValue ?: 0.0) * 1.852
                    if (uiState.futureOffsetHours == 0) {
                        val liveElapsedHrs = ((orbitClockMs - entity.retrievedAtEpochMs).coerceIn(0L, 120_000L)) / 3_600_000.0
                        if (liveElapsedHrs > 0.0001) {
                            val livePos = OrbitalAndGlobeMath.projectAlongBearing(
                                eLat, eLon, entity.headingDeg, speedKmh * liveElapsedHrs
                            )
                            eLat = livePos.first
                            eLon = livePos.second
                        }
                    } else {
                        val liveSp = OrbitalAndGlobeMath.projectToScreen(
                            eLat, eLon, eAlt, camLat, camLon, camPitch, camHeading, uiState.projection, cx, cy, rPx
                        )
                        val hrs = if (entity.category == WorldIntelCategory.AVIATION) {
                            min(6, uiState.futureOffsetHours).toDouble()
                        } else {
                            uiState.futureOffsetHours.toDouble()
                        }
                        val projected = OrbitalAndGlobeMath.projectAlongBearing(
                            eLat, eLon, entity.headingDeg, speedKmh * hrs
                        )
                        eLat = projected.first
                        eLon = projected.second
                        val futSp = OrbitalAndGlobeMath.projectToScreen(
                            eLat, eLon, eAlt, camLat, camLon, camPitch, camHeading, uiState.projection, cx, cy, rPx
                        )
                        if (liveSp.visible && futSp.visible) {
                            paints.dashedPaint.color = 0xAA22D3EE.toInt()
                            paints.dashedPaint.strokeWidth = 2.0f
                            nc.drawLine(liveSp.x, liveSp.y, futSp.x, futSp.y, paints.dashedPaint)
                        }
                    }
                }

                val sp = OrbitalAndGlobeMath.projectToScreen(
                    eLat, eLon, eAlt, camLat, camLon, camPitch, camHeading, uiState.projection, cx, cy, rPx
                )
                if (!sp.visible || sp.x < -40f || sp.x > w + 40f || sp.y < -40f || sp.y > h + 40f) continue
                drawnCount++

                if (clusterBuckets != null &&
                    entity.category != WorldIntelCategory.SPACE &&
                    entity.severity != WorldSeverity.CRITICAL &&
                    uiState.selectedEntity?.id != entity.id
                ) {
                    val gx = (sp.x / clusterCellPx).toInt()
                    val gy = (sp.y / clusterCellPx).toInt()
                    val key = (gx.toLong() shl 32) or (gy.toLong() and 0xFFFFFFFFL)
                    clusterBuckets.getOrPut(key) { ArrayList(4) }.add(sp to entity)
                } else {
                    drawTacticalMarker(
                        nc = nc,
                        sp = sp,
                        entity = entity,
                        opacity = opacity,
                        isSelected = uiState.selectedEntity?.id == entity.id,
                        showLabel = entity.severity == WorldSeverity.CRITICAL ||
                            entity.category == WorldIntelCategory.SPACE ||
                            camAlt < 2_600_000.0 ||
                            uiState.selectedEntity?.id == entity.id,
                        fillPaint = paints.fillPaint,
                        strokePaint = paints.strokePaint,
                        labelPaint = paints.labelPaint,
                        path = paints.reusablePath
                    )
                    pickTargets.add(PickableMarker(sp.x, sp.y, 34f, entity))
                }
            }

            if (clusterBuckets != null) {
                for (group in clusterBuckets.values) {
                    if (group.size < 4) {
                        for ((sp, ent) in group) {
                            val opacity = (uiState.layerStates[ent.category]?.opacity ?: 1f).coerceIn(0.2f, 1f)
                            drawTacticalMarker(
                                nc = nc,
                                sp = sp,
                                entity = ent,
                                opacity = opacity,
                                isSelected = false,
                                showLabel = camAlt < 2_600_000.0,
                                fillPaint = paints.fillPaint,
                                strokePaint = paints.strokePaint,
                                labelPaint = paints.labelPaint,
                                path = paints.reusablePath
                            )
                            pickTargets.add(PickableMarker(sp.x, sp.y, 32f, ent))
                        }
                    } else {
                        var sumX = 0f
                        var sumY = 0f
                        var sumLat = 0.0
                        var sumLon = 0.0
                        var maxSev = WorldSeverity.INFO
                        for ((sp, ent) in group) {
                            sumX += sp.x
                            sumY += sp.y
                            sumLat += ent.latitude
                            sumLon += ent.longitude
                            if (ent.severity.rank > maxSev.rank) maxSev = ent.severity
                        }
                        val cxAvg = sumX / group.size
                        val cyAvg = sumY / group.size
                        val badgeR = if (group.size >= 25) 26f else 22f
                        val ringCol = when (maxSev) {
                            WorldSeverity.CRITICAL -> 0xFFEF4444.toInt()
                            WorldSeverity.HIGH -> 0xFFF97316.toInt()
                            WorldSeverity.MEDIUM -> 0xFFF59E0B.toInt()
                            else -> 0xFF22D3EE.toInt()
                        }
                        paints.fillPaint.color = (0x38 shl 24) or (ringCol and 0x00FFFFFF)
                        nc.drawCircle(cxAvg, cyAvg, badgeR + 6f, paints.fillPaint)
                        paints.fillPaint.color = 0xEE040D1A.toInt()
                        nc.drawCircle(cxAvg, cyAvg, badgeR, paints.fillPaint)
                        paints.strokePaint.color = ringCol
                        paints.strokePaint.strokeWidth = 3.0f
                        nc.drawCircle(cxAvg, cyAvg, badgeR, paints.strokePaint)

                        val countStr = group.size.toString()
                        val tw = paints.labelPaint.measureText(countStr)
                        nc.drawText(countStr, cxAvg - tw * 0.5f, cyAvg + 8f, paints.labelPaint)

                        pickTargets.add(
                            PickableMarker(
                                x = cxAvg,
                                y = cyAvg,
                                hitRadiusPx = badgeR + 12f,
                                entity = null,
                                clusterZoomLat = sumLat / group.size,
                                clusterZoomLon = sumLon / group.size
                            )
                        )
                    }
                }
            }

            // 9. Interactive Global & Regional City / Chokepoint 3D Pins (tap any city pin to dive in!)
            if (uiState.labelsEnabled && camAlt > 35_000.0) {
                val curZoom = OrbitalAndGlobeMath.altitudeToTileZoom(camAlt)
                for (place in NativeGlobeTileStreamer.MAJOR_PLACES) {
                    if (curZoom < place.minZoom) continue
                    val sp = OrbitalAndGlobeMath.projectToScreen(
                        place.lat, place.lon, 0.0, camLat, camLon, camPitch, camHeading, uiState.projection, cx, cy, rPx
                    )
                    if (!sp.visible || sp.x < 16f || sp.x > w - 16f || sp.y < 16f || sp.y > h - 16f) continue

                    if (place.isStrategicHub) {
                        paints.fillPaint.color = 0xEE0C4A6E.toInt()
                        nc.drawCircle(sp.x, sp.y, 6.5f, paints.fillPaint)
                        paints.strokePaint.color = 0xFF38BDF8.toInt()
                        paints.strokePaint.strokeWidth = 2.0f
                        nc.drawCircle(sp.x, sp.y, 6.5f, paints.strokePaint)
                    } else {
                        paints.fillPaint.color = 0xFFF8FAFC.toInt()
                        nc.drawCircle(sp.x, sp.y, 5.2f, paints.fillPaint)
                        paints.strokePaint.color = 0xFF0F172A.toInt()
                        paints.strokePaint.strokeWidth = 1.8f
                        nc.drawCircle(sp.x, sp.y, 5.2f, paints.strokePaint)
                    }
                    nc.drawText(place.name, sp.x + 9f, sp.y + 5f, paints.labelPaint)
                    pickTargets.add(
                        PickableMarker(
                            x = sp.x,
                            y = sp.y,
                            hitRadiusPx = 26f,
                            entity = null,
                            clusterZoomLat = place.lat,
                            clusterZoomLon = place.lon
                        )
                    )
                }
            }
        }
    }
}

private fun pushViewportToViewModel(
    viewModel: WorldMonitorViewModel,
    lat: Double,
    lon: Double,
    altMeters: Double,
    pitchDeg: Double,
    headingDeg: Double,
    projection: GlobeProjection
) {
    val span = ((altMeters / 13_800_000.0) * 52.0).coerceIn(0.003, 80.0)
    val uNorm = ((lon + 180.0) / 360.0).toFloat()
    val vNorm = ((90.0 - lat) / 180.0).toFloat()
    val groundElev = (NativeGlobeAssetCache.sampleRelief(uNorm, vNorm) * 1_650f).roundToInt()
    viewModel.onViewportChanged(
        WorldViewport(
            west = (lon - span).coerceIn(-180.0, 180.0),
            south = (lat - span * 0.7).coerceIn(-85.0, 85.0),
            east = (lon + span).coerceIn(-180.0, 180.0),
            north = (lat + span * 0.7).coerceIn(-85.0, 85.0),
            centerLat = lat,
            centerLon = lon,
            cameraAltitudeMeters = altMeters.toLong(),
            groundElevationMeters = groundElev,
            headingDeg = headingDeg,
            pitchDeg = pitchDeg,
            projection = projection
        )
    )
}

private fun computeTerminatorVertexColor(sunDot: Float, preset: GlobeStylePreset): Int {
    if (preset == GlobeStylePreset.NIGHT_VISION) {
        val glow = (0.55f + (1f - sunDot.coerceIn(-0.4f, 0.6f)) * 0.45f).coerceIn(0.45f, 1f)
        val r = (120 * glow).toInt().coerceIn(0, 255)
        val g = (255 * glow).toInt().coerceIn(0, 255)
        val b = (175 * glow).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
    // Smooth twilight transition across sunDot in [-0.20, +0.22]
    val t = ((sunDot + 0.20f) / 0.42f).coerceIn(0f, 1f)
    val smooth = t * t * (3f - 2f * t)
    val r = (52 + (203 * smooth)).toInt().coerceIn(0, 255)
    val g = (66 + (189 * smooth)).toInt().coerceIn(0, 255)
    val b = (104 + (151 * smooth)).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

private fun buildGlobeColorFilter(basemap: GlobeBasemap, preset: GlobeStylePreset): ColorMatrixColorFilter? {
    val cm = ColorMatrix()
    when (basemap) {
        GlobeBasemap.NASA_GIBS_TRUECOLOR -> {
            cm.setSaturation(1.22f)
            cm.postConcat(
                ColorMatrix(
                    floatArrayOf(
                        1.06f, 0f, 0f, 0f, -2f,
                        0f, 1.09f, 0f, 0f, 0f,
                        0f, 0f, 1.14f, 0f, 6f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
        GlobeBasemap.OPENSTREETMAP -> {
            cm.setSaturation(1.18f)
            cm.postConcat(
                ColorMatrix(
                    floatArrayOf(
                        1.10f, 0f, 0f, 0f, 10f,
                        0f, 1.12f, 0f, 0f, 10f,
                        0f, 0f, 1.08f, 0f, 8f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
        else -> {
            cm.setSaturation(1.14f)
            cm.postConcat(
                ColorMatrix(
                    floatArrayOf(
                        1.08f, 0f, 0f, 0f, -4f,
                        0f, 1.10f, 0f, 0f, -2f,
                        0f, 0f, 1.12f, 0f, 4f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
    }

    when (preset) {
        GlobeStylePreset.CYBER_TACTICAL -> cm.postConcat(
            ColorMatrix(
                floatArrayOf(
                    0.86f, 0f, 0f, 0f, 0f,
                    0f, 0.96f, 0f, 0f, 4f,
                    0f, 0f, 1.10f, 0f, 12f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        GlobeStylePreset.NIGHT_VISION -> {
            cm.setSaturation(0.25f)
            cm.postConcat(
                ColorMatrix(
                    floatArrayOf(
                        0.32f, 0f, 0f, 0f, 0f,
                        0f, 1.12f, 0f, 0f, 14f,
                        0f, 0f, 0.56f, 0f, 6f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
        GlobeStylePreset.MONOCHROME_ANALYTIC -> {
            cm.setSaturation(0.08f)
            cm.postConcat(
                ColorMatrix(
                    floatArrayOf(
                        1.05f, 0f, 0f, 0f, 4f,
                        0f, 1.08f, 0f, 0f, 6f,
                        0f, 0f, 1.15f, 0f, 12f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
        GlobeStylePreset.STANDARD -> Unit
    }
    return ColorMatrixColorFilter(cm)
}

private fun drawOrbitPass(
    nc: android.graphics.Canvas,
    track: List<OrbitalAndGlobeMath.SatelliteStateVector>,
    frontSide: Boolean,
    camLat: Double,
    camLon: Double,
    camPitch: Double,
    camHeading: Double,
    projection: GlobeProjection,
    cx: Float,
    cy: Float,
    rPx: Float,
    limb: OrbitalAndGlobeMath.LimbCircle,
    path: Path,
    paint: Paint
) {
    path.reset()
    var penDown = false
    for (pt in track) {
        val phi = pt.latitude * PI / 180.0
        val phi0 = camLat * PI / 180.0
        val dLon = (pt.longitude - camLon) * PI / 180.0
        val unitEz = sin(phi0) * sin(phi) + cos(phi0) * cos(phi) * cos(dLon)

        val sp = OrbitalAndGlobeMath.projectToScreen(
            pt.latitude, pt.longitude, pt.altitudeMeters,
            camLat, camLon, camPitch, camHeading, projection, cx, cy, rPx
        )
        val outsideLimb = hypot((sp.x - limb.centerX).toDouble(), (sp.y - limb.centerY).toDouble()) >= limb.radiusPx
        val isFront = projection != GlobeProjection.GLOBE_3D || unitEz >= 0.0 || outsideLimb

        if (isFront == frontSide) {
            if (!penDown) {
                path.moveTo(sp.x, sp.y)
                penDown = true
            } else {
                path.lineTo(sp.x, sp.y)
            }
        } else {
            penDown = false
        }
    }
    nc.drawPath(path, paint)
}

private fun drawGeodesicCircle3D(
    nc: android.graphics.Canvas,
    centerLat: Double,
    centerLon: Double,
    radiusKm: Double,
    fillColor: Int,
    strokeColor: Int,
    strokeWidth: Float,
    camLat: Double,
    camLon: Double,
    camPitch: Double,
    camHeading: Double,
    projection: GlobeProjection,
    cx: Float,
    cy: Float,
    rPx: Float,
    path: Path,
    fillPaint: Paint,
    strokePaint: Paint
) {
    val degR = (radiusKm / 111.32).coerceIn(0.25, 35.0)
    val cosLat = cos(centerLat * PI / 180.0).coerceAtLeast(0.2)
    path.reset()
    var down = false
    var any = false
    var a = 0
    while (a <= 360) {
        val rad = a * PI / 180.0
        val lat = (centerLat + degR * sin(rad)).coerceIn(-89.0, 89.0)
        val lon = OrbitalAndGlobeMath.normalizeLon(centerLon + (degR * cos(rad)) / cosLat)
        val sp = OrbitalAndGlobeMath.projectToScreen(
            lat, lon, 1_500.0, camLat, camLon, camPitch, camHeading, projection, cx, cy, rPx
        )
        if (sp.visible) {
            any = true
            if (!down) { path.moveTo(sp.x, sp.y); down = true }
            else path.lineTo(sp.x, sp.y)
        } else down = false
        a += 10
    }
    if (!any) return
    fillPaint.color = fillColor
    strokePaint.color = strokeColor
    strokePaint.strokeWidth = strokeWidth
    nc.drawPath(path, fillPaint)
    nc.drawPath(path, strokePaint)
}

private fun drawEntityLineGeometry(
    nc: android.graphics.Canvas,
    coordsJson: String,
    colorInt: Int,
    camLat: Double,
    camLon: Double,
    camPitch: Double,
    camHeading: Double,
    projection: GlobeProjection,
    cx: Float,
    cy: Float,
    rPx: Float,
    w: Float,
    json: Json,
    path: Path,
    strokePaint: Paint
) {
    runCatching {
        val arr = json.parseToJsonElement(coordsJson).jsonArray
        if (arr.size < 2) return
        path.reset()
        var down = false
        var prevX = 0f
        for (el in arr) {
            val pair = el.jsonArray
            val lon = pair.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: continue
            val lat = pair.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: continue
            val sp = OrbitalAndGlobeMath.projectToScreen(
                lat, lon, 800.0, camLat, camLon, camPitch, camHeading, projection, cx, cy, rPx
            )
            if (sp.visible && (!down || abs(sp.x - prevX) < w * 0.42f)) {
                if (!down) { path.moveTo(sp.x, sp.y); down = true }
                else path.lineTo(sp.x, sp.y)
                prevX = sp.x
            } else down = false
        }
        strokePaint.color = colorInt
        strokePaint.strokeWidth = 2.4f
        nc.drawPath(path, strokePaint)
    }
}

private fun categoryColorInt(category: WorldIntelCategory, severity: WorldSeverity, alpha: Int = 255): Int {
    val rgb = if (severity == WorldSeverity.CRITICAL || severity == WorldSeverity.HIGH) {
        if (severity == WorldSeverity.CRITICAL) 0xEF4444 else 0xF97316
    } else {
        when (category) {
            WorldIntelCategory.DISASTER -> 0xEF4444
            WorldIntelCategory.WEATHER -> 0x38BDF8
            WorldIntelCategory.AVIATION -> 0x22D3EE
            WorldIntelCategory.MARITIME -> 0x3B82F6
            WorldIntelCategory.SPACE -> 0xA855F7
            WorldIntelCategory.CYBER -> 0xF43F5E
            WorldIntelCategory.NEWS -> 0xF59E0B
            WorldIntelCategory.INFRASTRUCTURE -> 0x10B981
        }
    }
    return (alpha.coerceIn(0, 255) shl 24) or rgb
}

private fun drawTacticalMarker(
    nc: android.graphics.Canvas,
    sp: OrbitalAndGlobeMath.ScreenPoint3D,
    entity: WorldEntity,
    opacity: Float,
    isSelected: Boolean,
    showLabel: Boolean,
    fillPaint: Paint,
    strokePaint: Paint,
    labelPaint: Paint,
    path: Path
) {
    val alpha = (opacity * 255f).roundToInt().coerceIn(50, 255)
    val mainCol = categoryColorInt(entity.category, entity.severity, alpha)
    val glowCol = categoryColorInt(entity.category, entity.severity, (alpha * 0.28f).roundToInt())

    val r = when {
        isSelected -> 13.5f
        entity.severity == WorldSeverity.CRITICAL -> 11.5f
        else -> 9.0f
    }

    fillPaint.shader = null
    fillPaint.color = glowCol
    nc.drawCircle(sp.x, sp.y, r + 8f, fillPaint)

    if (isSelected || entity.severity == WorldSeverity.CRITICAL) {
        strokePaint.color = if (isSelected) 0xFF22D3EE.toInt() else 0xCCFCA5A5.toInt()
        strokePaint.strokeWidth = 2.2f
        nc.drawCircle(sp.x, sp.y, r + 5f, strokePaint)
    }

    fillPaint.color = mainCol
    strokePaint.color = 0xEE020813.toInt()
    strokePaint.strokeWidth = 2.0f

    when (entity.category) {
        WorldIntelCategory.DISASTER -> {
            path.reset()
            path.moveTo(sp.x, sp.y - r)
            path.lineTo(sp.x + r, sp.y)
            path.lineTo(sp.x, sp.y + r)
            path.lineTo(sp.x - r, sp.y)
            path.close()
            nc.drawPath(path, fillPaint)
            nc.drawPath(path, strokePaint)
        }
        WorldIntelCategory.AVIATION, WorldIntelCategory.MARITIME -> {
            nc.save()
            nc.translate(sp.x, sp.y)
            nc.rotate((entity.headingDeg ?: 0.0).toFloat())
            path.reset()
            path.moveTo(0f, -r * 1.15f)
            path.lineTo(r * 0.78f, r * 0.95f)
            path.lineTo(0f, r * 0.45f)
            path.lineTo(-r * 0.78f, r * 0.95f)
            path.close()
            nc.drawPath(path, fillPaint)
            nc.drawPath(path, strokePaint)
            nc.restore()
        }
        WorldIntelCategory.SPACE -> {
            nc.drawRect(sp.x - 5f, sp.y - 5f, sp.x + 5f, sp.y + 5f, fillPaint)
            nc.drawRect(sp.x - 5f, sp.y - 5f, sp.x + 5f, sp.y + 5f, strokePaint)
            fillPaint.color = 0xFF38BDF8.toInt()
            nc.drawRect(sp.x - 15f, sp.y - 3.5f, sp.x - 7f, sp.y + 3.5f, fillPaint)
            nc.drawRect(sp.x + 7f, sp.y - 3.5f, sp.x + 15f, sp.y + 3.5f, fillPaint)
        }
        else -> {
            nc.drawCircle(sp.x, sp.y, r * 0.78f, fillPaint)
            nc.drawCircle(sp.x, sp.y, r * 0.78f, strokePaint)
        }
    }

    if (showLabel && entity.title.isNotBlank()) {
        val shortTitle = if (entity.title.length > 24) entity.title.take(24) + "…" else entity.title
        nc.drawText(shortTitle, sp.x + r + 7f, sp.y + 6f, labelPaint)
    }
}
