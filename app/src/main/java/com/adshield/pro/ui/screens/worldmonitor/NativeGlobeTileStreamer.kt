package com.adshield.pro.ui.screens.worldmonitor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.util.LruCache
import com.adshield.pro.domain.worldmonitor.GlobeBasemap
import com.adshield.pro.domain.worldmonitor.GlobeProjection
import com.adshield.pro.engine.worldmonitor.OrbitalAndGlobeMath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Curated global & regional city/landmark/chokepoint gazetteer for instant 3D pin labels,
 * 1-tap quick teleportation, and 0-ms offline place lookup.
 */
data class GlobePlacePin(
    val name: String,
    val subtitle: String,
    val lat: Double,
    val lon: Double,
    val minZoom: Int,
    val isStrategicHub: Boolean = false
)

/**
 * Asynchronous multi-zoom Web Mercator slippy-tile streamer & 3D spherical quad renderer.
 * Projects live high-resolution satellite, street, dark-matter, and hybrid city-label tiles
 * (`z = 3..16`) directly onto the curved 3D Earth mesh with ancestor tile fallback.
 */
object NativeGlobeTileStreamer {

    data class TileEntry(
        val bitmap: Bitmap,
        val shader: BitmapShader
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fetchSemaphore = Semaphore(8)
    private val inFlightKeys: MutableSet<String> = Collections.synchronizedSet(HashSet())

    private val memoryCache = object : LruCache<String, TileEntry>(160) {
        override fun sizeOf(key: String, value: TileEntry): Int = 1
    }

    @Volatile
    private var httpClient: OkHttpClient? = null
    private val clientLock = Any()

    // Reusable 4x4 curved spherical patch buffers per tile (25 verts, 96 indices — zero per-frame GC)
    private const val SUBDIV = 4
    private const val PATCH_COLS = SUBDIV + 1
    private const val PATCH_VERTS = PATCH_COLS * PATCH_COLS
    private val patchVerts = FloatArray(PATCH_VERTS * 2)
    private val patchTexs = FloatArray(PATCH_VERTS * 2)
    private val patchVis = BooleanArray(PATCH_VERTS)
    private val patchIndices = ShortArray(SUBDIV * SUBDIV * 6)
    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val labelTilePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        alpha = 235
    }

    val MAJOR_PLACES: List<GlobePlacePin> = listOf(
        // Philippines & Indo-Pacific Regional Detail
        GlobePlacePin("Manila", "Capital · Philippines", 14.5995, 120.9842, 3, true),
        GlobePlacePin("Quezon City", "Metro Manila · PH", 14.6760, 121.0437, 7),
        GlobePlacePin("Makati / BGC", "Financial District · PH", 14.5547, 121.0244, 8),
        GlobePlacePin("Cabanatuan", "Nueva Ecija · Central Luzon", 15.4865, 120.9734, 6),
        GlobePlacePin("Siclong / Laur", "Nueva Ecija · Central Luzon", 15.5680, 121.1920, 6),
        GlobePlacePin("Clark / Angeles", "Pampanga · Central Luzon", 15.1859, 120.5600, 6),
        GlobePlacePin("Subic Bay", "Zambales Freeport · PH", 14.8203, 120.2828, 6, true),
        GlobePlacePin("Baguio City", "Cordillera · PH", 16.4023, 120.5960, 6),
        GlobePlacePin("Cebu City", "Central Visayas · PH", 10.3157, 123.8854, 5),
        GlobePlacePin("Davao City", "Mindanao · PH", 7.1907, 125.4553, 5),
        GlobePlacePin("Iloilo City", "Western Visayas · PH", 10.7202, 122.5621, 6),
        GlobePlacePin("Puerto Princesa", "Palawan · PH", 9.7392, 118.7353, 6),
        GlobePlacePin("Legazpi · Mayon", "Bicol · PH", 13.1391, 123.7438, 6),
        GlobePlacePin("Zamboanga", "Mindanao · PH", 6.9214, 122.0790, 6),
        // Global Capitals, Megacities & Strategic Chokepoints
        GlobePlacePin("Tokyo", "Japan", 35.6762, 139.6503, 3, true),
        GlobePlacePin("Osaka", "Japan", 34.6937, 135.5023, 5),
        GlobePlacePin("Seoul", "South Korea", 37.5665, 126.9780, 3, true),
        GlobePlacePin("Taipei", "Taiwan", 25.0330, 121.5654, 4, true),
        GlobePlacePin("Hong Kong", "SAR", 22.3193, 114.1694, 4, true),
        GlobePlacePin("Singapore", "Strait Hub", 1.3521, 103.8198, 3, true),
        GlobePlacePin("Bangkok", "Thailand", 13.7563, 100.5018, 4),
        GlobePlacePin("Jakarta", "Indonesia", -6.2088, 106.8456, 4),
        GlobePlacePin("Kuala Lumpur", "Malaysia", 3.1390, 101.6869, 4),
        GlobePlacePin("Hanoi", "Vietnam", 21.0278, 105.8342, 4),
        GlobePlacePin("Beijing", "China", 39.9042, 116.4074, 3),
        GlobePlacePin("Shanghai", "China", 31.2304, 121.4737, 4),
        GlobePlacePin("Sydney", "Australia", -33.8688, 151.2093, 3),
        GlobePlacePin("New Delhi", "India", 28.6139, 77.2090, 3),
        GlobePlacePin("Mumbai", "India", 19.0760, 72.8777, 4),
        GlobePlacePin("Dubai", "UAE", 25.2048, 55.2708, 3, true),
        GlobePlacePin("Riyadh", "Saudi Arabia", 24.7136, 46.6753, 4),
        GlobePlacePin("Istanbul", "Turkiye · Bosporus", 41.0082, 28.9784, 4, true),
        GlobePlacePin("Cairo", "Egypt", 30.0444, 31.2357, 4),
        GlobePlacePin("London", "United Kingdom", 51.5074, -0.1278, 3, true),
        GlobePlacePin("Paris", "France", 48.8566, 2.3522, 3),
        GlobePlacePin("Berlin", "Germany", 52.5200, 13.4050, 4),
        GlobePlacePin("Rome", "Italy", 41.9028, 12.4964, 4),
        GlobePlacePin("Madrid", "Spain", 40.4168, -3.7038, 4),
        GlobePlacePin("Kyiv", "Ukraine", 50.4501, 30.5234, 4),
        GlobePlacePin("Nairobi", "Kenya", -1.2921, 36.8219, 4),
        GlobePlacePin("Lagos", "Nigeria", 6.5244, 3.3792, 4),
        GlobePlacePin("New York", "USA", 40.7128, -74.0060, 3, true),
        GlobePlacePin("Washington D.C.", "USA", 38.9072, -77.0369, 4, true),
        GlobePlacePin("Los Angeles", "USA", 34.0522, -118.2437, 4),
        GlobePlacePin("San Francisco", "USA", 37.7749, -122.4194, 4),
        GlobePlacePin("Chicago", "USA", 41.8781, -87.6298, 4),
        GlobePlacePin("Toronto", "Canada", 43.6532, -79.3832, 4),
        GlobePlacePin("Mexico City", "Mexico", 19.4326, -99.1332, 4),
        GlobePlacePin("Sao Paulo", "Brazil", -23.5505, -46.6333, 3),
        GlobePlacePin("Buenos Aires", "Argentina", -34.6037, -58.3816, 4),
        // Strategic Maritime Chokepoints
        GlobePlacePin("Strait of Malacca", "Maritime Chokepoint", 2.5000, 101.8000, 4, true),
        GlobePlacePin("Suez Canal", "Maritime Chokepoint", 30.5852, 32.2654, 4, true),
        GlobePlacePin("Strait of Hormuz", "Energy Chokepoint", 26.5667, 56.2500, 4, true),
        GlobePlacePin("Panama Canal", "Maritime Chokepoint", 9.0800, -79.6800, 4, true),
        GlobePlacePin("Taiwan Strait", "Strategic Waterway", 24.5000, 119.5000, 4, true)
    )

    private fun ensureClient(context: Context): OkHttpClient {
        httpClient?.let { return it }
        synchronized(clientLock) {
            httpClient?.let { return it }
            val cacheDir = File(context.applicationContext.cacheDir, "wm_native_globe_tiles_v1")
            val built = OkHttpClient.Builder()
                .cache(Cache(cacheDir, 96L * 1024L * 1024L))
                .connectTimeout(4_000, TimeUnit.MILLISECONDS)
                .readTimeout(5_000, TimeUnit.MILLISECONDS)
                .callTimeout(6_500, TimeUnit.MILLISECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
            httpClient = built
            return built
        }
    }

    private fun buildTileUrl(layerKey: String, z: Int, x: Int, y: Int): String? {
        return when (layerKey) {
            "SATELLITE_ESRI" ->
                "https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$z/$y/$x"
            "ESRI_LABELS" ->
                "https://services.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/$z/$y/$x"
            "OPENSTREETMAP" ->
                "https://tile.openstreetmap.org/$z/$x/$y.png"
            "CARTO_DARK_MATTER" ->
                "https://a.basemaps.cartocdn.com/dark_all/$z/$x/$y.png"
            "OPEN_TOPO_MAP" ->
                "https://a.tile.opentopomap.org/$z/$x/$y.png"
            "NASA_GIBS_TRUECOLOR" -> {
                val gz = z.coerceAtMost(9)
                "https://gibs.earthdata.nasa.gov/wmts/epsg3857/best/VIIRS_SNPP_CorrectedReflectance_TrueColor/default/2024-11-01/GoogleMapsCompatible_Level9/$gz/$y/$x.jpg"
            }
            else -> null
        }
    }

    private fun requestTileAsync(
        context: Context,
        layerKey: String,
        z: Int,
        x: Int,
        y: Int,
        isAlphaOverlay: Boolean,
        onTileLoaded: () -> Unit
    ) {
        val cacheKey = "$layerKey:$z:$x:$y"
        if (memoryCache.get(cacheKey) != null) return
        if (!inFlightKeys.add(cacheKey)) return

        val url = buildTileUrl(layerKey, z, x, y) ?: run {
            inFlightKeys.remove(cacheKey)
            return
        }

        scope.launch {
            try {
                fetchSemaphore.withPermit {
                    val client = ensureClient(context)
                    val req = Request.Builder()
                        .url(url)
                        .header("User-Agent", "OmniShieldWorldMonitor/2.10.6 (Android; Open-Geospatial)")
                        .get()
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use
                        val bytes = resp.body?.bytes() ?: return@use
                        if (bytes.isEmpty()) return@use
                        val opts = BitmapFactory.Options().apply {
                            inPreferredConfig = if (isAlphaOverlay) {
                                Bitmap.Config.ARGB_8888
                            } else {
                                Bitmap.Config.RGB_565
                            }
                        }
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return@use
                        val shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                        memoryCache.put(cacheKey, TileEntry(bmp, shader))
                        onTileLoaded()
                    }
                }
            } catch (_: Throwable) {
                // Offline or transient network error — base NaturalEarthII sphere remains intact
            } finally {
                inFlightKeys.remove(cacheKey)
            }
        }
    }

    /**
     * Renders live high-resolution Web Mercator city/street/satellite tiles onto the 3D sphere
     * whenever zoomed below `5,500 km` (`z >= 4`). Uses ancestor tile fallback so zooming never flickers.
     */
    fun renderHighResTilesOnSphere(
        context: Context,
        nc: Canvas,
        basemap: GlobeBasemap,
        labelsEnabled: Boolean,
        camLat: Double,
        camLon: Double,
        camAltMeters: Double,
        camPitch: Double,
        camHeading: Double,
        projection: GlobeProjection,
        cx: Float,
        cy: Float,
        rPx: Float,
        viewportW: Float,
        colorFilter: android.graphics.ColorFilter?,
        onTileLoaded: () -> Unit
    ) {
        if (basemap == GlobeBasemap.OFFLINE_NATURAL_EARTH || camAltMeters > 5_500_000.0) return

        val rawZ = OrbitalAndGlobeMath.altitudeToTileZoom(camAltMeters)
        val maxLayerZ = when (basemap) {
            GlobeBasemap.NASA_GIBS_TRUECOLOR -> 9
            GlobeBasemap.OPEN_TOPO_MAP -> 15
            else -> 16
        }
        val z = rawZ.coerceIn(4, maxLayerZ)
        val nTiles = 1 shl z
        val (centerTx, centerTy) = OrbitalAndGlobeMath.latLonToTileXY(camLat, camLon, z)

        val layerKey = basemap.name
        val radiusRing = if (camAltMeters < 80_000.0) 2 else 2

        tilePaint.colorFilter = colorFilter

        // Spiral / center-outward traversal so center city tiles always load first
        for (dy in -radiusRing..radiusRing) {
            val ty = centerTy + dy
            if (ty < 0 || ty >= nTiles) continue
            for (dx in -radiusRing..radiusRing) {
                val tx = ((centerTx + dx) % nTiles + nTiles) % nTiles
                val bounds = OrbitalAndGlobeMath.tileBoundsLatLon(z, tx, ty)

                val directKey = "$layerKey:$z:$tx:$ty"
                val directEntry = memoryCache.get(directKey)
                if (directEntry != null && !directEntry.bitmap.isRecycled) {
                    drawCurvedTilePatch(
                        nc = nc,
                        entry = directEntry,
                        bounds = bounds,
                        u0 = 0f,
                        v0 = 0f,
                        u1 = 1f,
                        v1 = 1f,
                        camLat = camLat,
                        camLon = camLon,
                        camPitch = camPitch,
                        camHeading = camHeading,
                        projection = projection,
                        cx = cx,
                        cy = cy,
                        rPx = rPx,
                        viewportW = viewportW,
                        paint = tilePaint
                    )
                } else {
                    requestTileAsync(context, layerKey, z, tx, ty, isAlphaOverlay = false, onTileLoaded = onTileLoaded)
                    // Seamless ancestor fallback (check z-1, z-2, z-3)
                    drawAncestorFallbackIfCached(
                        nc = nc,
                        layerKey = layerKey,
                        targetZ = z,
                        tx = tx,
                        ty = ty,
                        bounds = bounds,
                        camLat = camLat,
                        camLon = camLon,
                        camPitch = camPitch,
                        camHeading = camHeading,
                        projection = projection,
                        cx = cx,
                        cy = cy,
                        rPx = rPx,
                        viewportW = viewportW,
                        paint = tilePaint
                    )
                }

                // Hybrid City, Town, Highway & Street Labels on Satellite imagery!
                if (labelsEnabled && basemap == GlobeBasemap.SATELLITE_ESRI && z >= 5) {
                    val lblKey = "ESRI_LABELS:$z:$tx:$ty"
                    val lblEntry = memoryCache.get(lblKey)
                    if (lblEntry != null && !lblEntry.bitmap.isRecycled) {
                        drawCurvedTilePatch(
                            nc = nc,
                            entry = lblEntry,
                            bounds = bounds,
                            u0 = 0f,
                            v0 = 0f,
                            u1 = 1f,
                            v1 = 1f,
                            camLat = camLat,
                            camLon = camLon,
                            camPitch = camPitch,
                            camHeading = camHeading,
                            projection = projection,
                            cx = cx,
                            cy = cy,
                            rPx = rPx,
                            viewportW = viewportW,
                            paint = labelTilePaint
                        )
                    } else {
                        requestTileAsync(context, "ESRI_LABELS", z, tx, ty, isAlphaOverlay = true, onTileLoaded = onTileLoaded)
                    }
                }
            }
        }
    }

    private fun drawAncestorFallbackIfCached(
        nc: Canvas,
        layerKey: String,
        targetZ: Int,
        tx: Int,
        ty: Int,
        bounds: DoubleArray,
        camLat: Double,
        camLon: Double,
        camPitch: Double,
        camHeading: Double,
        projection: GlobeProjection,
        cx: Float,
        cy: Float,
        rPx: Float,
        viewportW: Float,
        paint: Paint
    ) {
        for (shift in 1..3) {
            val pz = targetZ - shift
            if (pz < 3) break
            val scale = 1 shl shift
            val px = tx shr shift
            val py = ty shr shift
            val pEntry = memoryCache.get("$layerKey:$pz:$px:$py")
            if (pEntry != null && !pEntry.bitmap.isRecycled) {
                val subX = tx and (scale - 1)
                val subY = ty and (scale - 1)
                val inv = 1f / scale.toFloat()
                drawCurvedTilePatch(
                    nc = nc,
                    entry = pEntry,
                    bounds = bounds,
                    u0 = subX * inv,
                    v0 = subY * inv,
                    u1 = (subX + 1) * inv,
                    v1 = (subY + 1) * inv,
                    camLat = camLat,
                    camLon = camLon,
                    camPitch = camPitch,
                    camHeading = camHeading,
                    projection = projection,
                    cx = cx,
                    cy = cy,
                    rPx = rPx,
                    viewportW = viewportW,
                    paint = paint
                )
                return
            }
        }
    }

    private fun drawCurvedTilePatch(
        nc: Canvas,
        entry: TileEntry,
        bounds: DoubleArray,
        u0: Float,
        v0: Float,
        u1: Float,
        v1: Float,
        camLat: Double,
        camLon: Double,
        camPitch: Double,
        camHeading: Double,
        projection: GlobeProjection,
        cx: Float,
        cy: Float,
        rPx: Float,
        viewportW: Float,
        paint: Paint
    ) {
        val west = bounds[0]
        val south = bounds[1]
        val east = bounds[2]
        val north = bounds[3]
        val bmpW = entry.bitmap.width.toFloat()
        val bmpH = entry.bitmap.height.toFloat()

        var idx = 0
        var anyVis = false
        for (r in 0..SUBDIV) {
            val rf = r.toFloat() / SUBDIV.toFloat()
            val lat = north - (north - south) * rf
            val vPx = (v0 + (v1 - v0) * rf) * bmpH
            for (c in 0..SUBDIV) {
                val cf = c.toFloat() / SUBDIV.toFloat()
                val lon = west + (east - west) * cf
                val uPx = (u0 + (u1 - u0) * cf) * bmpW

                val sp = OrbitalAndGlobeMath.projectToScreen(
                    latDeg = lat,
                    lonDeg = lon,
                    altitudeMeters = 0.0,
                    camLatDeg = camLat,
                    camLonDeg = camLon,
                    pitchDeg = camPitch,
                    headingDeg = camHeading,
                    projection = projection,
                    cx = cx,
                    cy = cy,
                    radiusPx = rPx
                )
                patchVerts[idx * 2] = sp.x
                patchVerts[idx * 2 + 1] = sp.y
                patchTexs[idx * 2] = uPx
                patchTexs[idx * 2 + 1] = vPx
                patchVis[idx] = sp.visible
                if (sp.visible) anyVis = true
                idx++
            }
        }
        if (!anyVis) return

        var ic = 0
        val is3D = projection == GlobeProjection.GLOBE_3D
        for (r in 0 until SUBDIV) {
            val r0 = r * PATCH_COLS
            val r1 = (r + 1) * PATCH_COLS
            for (c in 0 until SUBDIV) {
                val i00 = r0 + c
                val i10 = i00 + 1
                val i01 = r1 + c
                val i11 = i01 + 1
                if (patchVis[i00] || patchVis[i10] || patchVis[i01] || patchVis[i11]) {
                    if (!is3D && abs(patchVerts[i10 * 2] - patchVerts[i00 * 2]) > viewportW * 0.45f) {
                        continue
                    }
                    patchIndices[ic++] = i00.toShort()
                    patchIndices[ic++] = i10.toShort()
                    patchIndices[ic++] = i01.toShort()
                    patchIndices[ic++] = i10.toShort()
                    patchIndices[ic++] = i11.toShort()
                    patchIndices[ic++] = i01.toShort()
                }
            }
        }
        if (ic == 0) return

        paint.shader = entry.shader
        nc.drawVertices(
            Canvas.VertexMode.TRIANGLES,
            PATCH_VERTS * 2,
            patchVerts,
            0,
            patchTexs,
            0,
            null,
            0,
            patchIndices,
            0,
            ic,
            paint
        )
        paint.shader = null
    }
}
