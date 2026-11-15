package com.adshield.pro.engine.worldmonitor

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import okhttp3.Cache
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Local Asset + Persistent Tile Cache Interceptor for CesiumJS WebView.
 *
 * Responsibilities:
 *  1. Serves bundled `/assets/worldmonitor/` files under `https://appassets.androidplatform.net/`
 *     with normalized MIME types and permissive CORS headers so `blob:` / `data:` ES module
 *     WebWorkers (`CESIUM_WORKERS`) can load terrain/IAU2006/worker chunks across all Android versions.
 *  2. Allows native Chromium handling (`return null`) for internal `blob:` and `data:` schemes
 *     used by CesiumJS geometry WebWorkers and canvas-rendered tactical icons.
 *  3. Proxies allowlisted HTTPS map/terrain/weather tile requests through a fast-timeout OkHttp
 *     disk cache so visited tiles render offline without stalling local APK asset loads.
 *  4. Blocks unexpected non-HTTPS or non-allowlisted third-party hosts.
 */
class WorldMonitorAssetAndTileServer(
    context: Context
) {
    private val appContext = context.applicationContext

    private val assetLoader: WebViewAssetLoader = WebViewAssetLoader.Builder()
        .setDomain(ASSET_HOST)
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(appContext))
        .build()

    private val tileClient: OkHttpClient by lazy {
        val dispatcher = Dispatcher().apply {
            maxRequests = 48
            maxRequestsPerHost = 16
        }
        val cacheDir = File(appContext.cacheDir, "worldmonitor_tiles_v1")
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .cache(Cache(cacheDir, 120L * 1024L * 1024L))
            .connectTimeout(3_500, TimeUnit.MILLISECONDS)
            .readTimeout(4_500, TimeUnit.MILLISECONDS)
            .callTimeout(5_500, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url ?: return blockedResponse("Invalid URI")
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return blockedResponse("Missing scheme")

        // Let Chromium natively handle internal blob: (Cesium WebWorkers) and data: (canvas icons) URLs
        if (scheme == "blob" || scheme == "data") {
            return null
        }

        if (scheme != "https") {
            return blockedResponse("Non-HTTPS scheme blocked")
        }

        val host = uri.host?.lowercase(Locale.US) ?: return blockedResponse("Missing host")

        if (request.method.equals("OPTIONS", ignoreCase = true) &&
            (host == ASSET_HOST || isAllowedTileHost(host))
        ) {
            return WebResourceResponse(
                "text/plain",
                "UTF-8",
                200,
                "OK",
                CORS_HEADERS,
                ByteArrayInputStream(ByteArray(0))
            )
        }

        // 1. Local bundled CesiumJS + worldmonitor assets
        if (host == ASSET_HOST) {
            val res = assetLoader.shouldInterceptRequest(uri) ?: return null
            val normalizedMime = resolveMimeType(uri.path.orEmpty(), res.mimeType)
            res.mimeType = normalizedMime
            res.responseHeaders = (res.responseHeaders ?: emptyMap()) + CORS_HEADERS + mapOf(
                "Cross-Origin-Resource-Policy" to "cross-origin",
                "Cache-Control" to "public, max-age=31536000, immutable"
            )
            return res
        }

        // 2. Allowlisted public imagery / elevation / weather tile hosts with disk caching
        if (isAllowedTileHost(host)) {
            return proxyCachedTile(uri)
        }

        // 3. Reject unexpected third-party hosts from inside the globe WebView
        return blockedResponse("Host not allowlisted: $host")
    }

    private fun proxyCachedTile(uri: Uri): WebResourceResponse {
        return try {
            val req = Request.Builder()
                .url(uri.toString())
                .header("User-Agent", "OmniShieldX-WorldMonitor/2035.2 (Android; TileCache)")
                .get()
                .build()
            val response = tileClient.newCall(req).execute()
            if (!response.isSuccessful) {
                response.close()
                return WebResourceResponse(
                    "text/plain",
                    "UTF-8",
                    response.code.coerceIn(400, 599),
                    response.message.ifBlank { "Upstream Error" },
                    CORS_HEADERS,
                    ByteArrayInputStream(ByteArray(0))
                )
            }
            val body = response.body
            val contentType = response.header("Content-Type")
                ?.substringBefore(';')
                ?.trim()
                ?.ifBlank { null }
                ?: resolveMimeType(uri.path.orEmpty(), "application/octet-stream")
            val bytes = body.bytes()
            response.close()

            WebResourceResponse(
                contentType,
                null,
                200,
                "OK",
                CORS_HEADERS + mapOf(
                    "Cross-Origin-Resource-Policy" to "cross-origin",
                    "Cache-Control" to "public, max-age=604800"
                ),
                ByteArrayInputStream(bytes)
            )
        } catch (_: Exception) {
            WebResourceResponse(
                "text/plain",
                "UTF-8",
                404,
                "Offline",
                CORS_HEADERS,
                ByteArrayInputStream(ByteArray(0))
            )
        }
    }

    companion object {
        const val ASSET_HOST = "appassets.androidplatform.net"
        const val ENTRY_URL = "https://appassets.androidplatform.net/assets/worldmonitor/index.html"

        private val CORS_HEADERS = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
            "Access-Control-Allow-Headers" to "Origin, Accept, Content-Type, Range"
        )

        private val ALLOWED_TILE_SUFFIXES = listOf(
            "arcgisonline.com",
            "openstreetmap.org",
            "basemaps.cartocdn.com",
            "tile.opentopomap.org",
            "earthdata.nasa.gov",
            "rainviewer.com",
            "s3.amazonaws.com"
        )

        internal fun isAllowedTileHost(host: String): Boolean {
            val h = host.lowercase(Locale.US)
            return ALLOWED_TILE_SUFFIXES.any { suffix -> h == suffix || h.endsWith(".$suffix") }
        }

        internal fun resolveMimeType(path: String, fallback: String?): String {
            val lower = path.lowercase(Locale.US)
            return when {
                lower.endsWith(".js") || lower.endsWith(".mjs") -> "text/javascript"
                lower.endsWith(".wasm") -> "application/wasm"
                lower.endsWith(".ktx2") -> "image/ktx2"
                lower.endsWith(".json") || lower.endsWith(".geojson") || lower.endsWith(".czml") || lower.endsWith(".topojson") -> "application/json"
                lower.endsWith(".css") -> "text/css"
                lower.endsWith(".html") -> "text/html"
                lower.endsWith(".png") -> "image/png"
                lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
                lower.endsWith(".svg") -> "image/svg+xml"
                lower.endsWith(".xml") -> "application/xml"
                lower.endsWith(".woff2") -> "font/woff2"
                lower.endsWith(".ttf") -> "font/ttf"
                !fallback.isNullOrBlank() -> fallback
                else -> "application/octet-stream"
            }
        }

        private fun blockedResponse(reason: String): WebResourceResponse =
            WebResourceResponse(
                "text/plain",
                "UTF-8",
                403,
                "Blocked",
                mapOf("Access-Control-Allow-Origin" to "null"),
                ByteArrayInputStream(reason.toByteArray(Charsets.UTF_8))
            )
    }
}
