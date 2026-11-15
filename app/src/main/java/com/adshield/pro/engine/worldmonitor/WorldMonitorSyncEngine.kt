package com.adshield.pro.engine.worldmonitor

import com.adshield.pro.data.repository.ProviderCredentialStore
import com.adshield.pro.data.worldmonitor.WorldGeocodeCacheRecord
import com.adshield.pro.data.worldmonitor.WorldMonitorDao
import com.adshield.pro.data.worldmonitor.WorldProviderStateRecord
import com.adshield.pro.di.WorldMonitorHttpClient
import com.adshield.pro.domain.worldmonitor.GeocodeSearchResult
import com.adshield.pro.domain.worldmonitor.OsmBuilding3D
import com.adshield.pro.domain.worldmonitor.ProviderAuthRequirement
import com.adshield.pro.domain.worldmonitor.ProviderSyncStatus
import com.adshield.pro.domain.worldmonitor.RainViewerFrame
import com.adshield.pro.domain.worldmonitor.WorldEntity
import com.adshield.pro.domain.worldmonitor.WorldProviderSpec
import com.adshield.pro.domain.worldmonitor.WorldViewport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rate-limit-disciplined, HTTPS-only, ETag/Last-Modified-aware HTTP sync & geocoding engine
 * for World Monitor 2035.
 */
@Singleton
class WorldMonitorSyncEngine @Inject constructor(
    @WorldMonitorHttpClient private val httpClient: OkHttpClient,
    private val dao: WorldMonitorDao,
    private val credentialStore: ProviderCredentialStore
) {
    private val nominatimMutex = Mutex()
    private var lastNominatimCallMs: Long = 0L

    data class ProviderFetchOutcome(
        val providerId: String,
        val status: ProviderSyncStatus,
        val entities: List<WorldEntity> = emptyList(),
        val rainViewerFrames: List<RainViewerFrame> = emptyList(),
        val buildings3D: List<OsmBuilding3D> = emptyList(),
        val errorMessage: String? = null
    )

    suspend fun syncProvider(
        spec: WorldProviderSpec,
        viewport: WorldViewport? = null,
        force: Boolean = false,
        nowEpochMs: Long = System.currentTimeMillis()
    ): ProviderFetchOutcome = withContext(Dispatchers.IO) {
        val existingState = dao.getProviderState(spec.providerId)
        if (existingState != null && !existingState.enabled) {
            return@withContext ProviderFetchOutcome(spec.providerId, ProviderSyncStatus.IDLE)
        }

        // 1. Offline / Bundled Baseline Catalog
        if (spec.providerId == "wm.infra.baseline") {
            val items = BaselineReferenceStore.buildBaselineEntities(nowEpochMs)
            saveProviderState(spec.providerId, existingState, ProviderSyncStatus.OK, 200, items.size, 0L, null, nowEpochMs)
            return@withContext ProviderFetchOutcome(spec.providerId, ProviderSyncStatus.OK, entities = items)
        }

        // 2. Unimplemented / Backend-proxy-gated feeds (e.g. AISStream WebSocket)
        if (!spec.implemented) {
            val hasCred = spec.credentialSlot?.let { !credentialStore.get(it).isNullOrBlank() } == true
            val status = if (hasCred) ProviderSyncStatus.BACKEND_REQUIRED else ProviderSyncStatus.NEEDS_API_KEY
            val note = spec.notes.ifBlank { "Requires external WebSocket stream proxy or API credentials." }
            saveProviderState(spec.providerId, existingState, status, null, 0, null, note, nowEpochMs)
            return@withContext ProviderFetchOutcome(spec.providerId, status, errorMessage = note)
        }

        // 3. Required API key check (e.g. NASA FIRMS MAP_KEY)
        val secret = spec.credentialSlot?.let { credentialStore.get(it) }
        if (spec.authRequirement == ProviderAuthRequirement.REQUIRED_USER_KEY && secret.isNullOrBlank()) {
            val note = spec.notes.ifBlank { "Add your free API key in Sources settings to enable this feed." }
            saveProviderState(spec.providerId, existingState, ProviderSyncStatus.NEEDS_API_KEY, null, 0, null, note, nowEpochMs)
            return@withContext ProviderFetchOutcome(spec.providerId, ProviderSyncStatus.NEEDS_API_KEY, errorMessage = note)
        }

        // 4. Respect backoff & minimum provider gap (CelesTrak 2-hour policy strictly enforced!)
        val isCelestrak = spec.providerId.startsWith("wm.space.celestrak.")
        if (existingState != null) {
            val nextAllowed = existingState.nextAllowedEpochMs ?: 0L
            if (nowEpochMs < nextAllowed && (isCelestrak || !force)) {
                return@withContext ProviderFetchOutcome(
                    spec.providerId,
                    ProviderSyncStatus.RATE_LIMITED,
                    errorMessage = "Cooling down until rate-limit window expires"
                )
            }
            val minGap = when {
                isCelestrak -> spec.minRequestGapMs
                force && spec.supportsViewportBbox -> 2_500L
                force -> 8_000L
                else -> spec.refreshIntervalMs
            }
            val lastOk = existingState.lastSuccessEpochMs ?: 0L
            if (lastOk > 0L && (nowEpochMs - lastOk) < minGap) {
                val curStatus = runCatching { ProviderSyncStatus.valueOf(existingState.status) }
                    .getOrDefault(ProviderSyncStatus.OK)
                return@withContext ProviderFetchOutcome(spec.providerId, curStatus)
            }
        }

        // 5. Special handling for Overpass 3D Buildings & Local Infra (POST with bbox < 0.6 deg)
        if (spec.providerId == "wm.infra.overpass") {
            return@withContext fetchOverpassViewport(spec, existingState, viewport, nowEpochMs)
        }

        // 6. Build HTTPS request URL
        val resolvedUrl = resolveEndpointUrl(spec, secret, viewport)
        if (!resolvedUrl.startsWith("https://", ignoreCase = true)) {
            saveProviderState(spec.providerId, existingState, ProviderSyncStatus.ERROR, null, 0, null, "Blocked non-HTTPS URL", nowEpochMs)
            return@withContext ProviderFetchOutcome(spec.providerId, ProviderSyncStatus.ERROR, errorMessage = "Non-HTTPS endpoint")
        }

        val reqBuilder = Request.Builder()
            .url(resolvedUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, application/xml, text/csv, text/plain, */*")
            .get()

        if (spec.providerId == "wm.aviation.opensky" && !secret.isNullOrBlank()) {
            if (secret.contains(':')) {
                val user = secret.substringBefore(':')
                val pass = secret.substringAfter(':')
                reqBuilder.header("Authorization", Credentials.basic(user, pass))
            } else {
                reqBuilder.header("Authorization", "Bearer $secret")
            }
        }

        // Only attach conditional ETag/Last-Modified headers for static-URL global feeds (never for coordinate/country parameterized feeds)
        if (!spec.supportsViewportBbox && !force) {
            existingState?.etag?.takeIf { it.isNotBlank() }?.let { reqBuilder.header("If-None-Match", it) }
            existingState?.lastModified?.takeIf { it.isNotBlank() }?.let { reqBuilder.header("If-Modified-Since", it) }
        }

        val qLat = viewport?.centerLat ?: 14.5995
        val qLon = viewport?.centerLon ?: 120.9842
        return@withContext executeFeedRequest(spec, reqBuilder.build(), existingState, nowEpochMs, qLat, qLon)
    }

    private suspend fun fetchOverpassViewport(
        spec: WorldProviderSpec,
        existingState: WorldProviderStateRecord?,
        viewport: WorldViewport?,
        nowEpochMs: Long
    ): ProviderFetchOutcome {
        val vp = viewport ?: return ProviderFetchOutcome(spec.providerId, ProviderSyncStatus.IDLE)
        // Always clamp Overpass query to a fast, polite ±0.032° (~7 km) city sector around crosshair
        val halfSpan = 0.032
        val s = "%.5f".format(Locale.US, (vp.centerLat - halfSpan).coerceIn(-85.0, 85.0))
        val w = "%.5f".format(Locale.US, (vp.centerLon - halfSpan).coerceIn(-180.0, 180.0))
        val n = "%.5f".format(Locale.US, (vp.centerLat + halfSpan).coerceIn(-85.0, 85.0))
        val e = "%.5f".format(Locale.US, (vp.centerLon + halfSpan).coerceIn(-180.0, 180.0))

        val ql = """
            [out:json][timeout:14];
            (
              way["building"]($s,$w,$n,$e);
              node["amenity"="hospital"]($s,$w,$n,$e);
              node["railway"="station"]($s,$w,$n,$e);
              node["power"="substation"]($s,$w,$n,$e);
              node["man_made"="mast"]($s,$w,$n,$e);
            );
            out body geom center 220;
        """.trimIndent()
        val formBody = ("data=" + URLEncoder.encode(ql, "UTF-8"))
            .toRequestBody("application/x-www-form-urlencoded".toMediaType())

        val req = Request.Builder()
            .url(spec.endpoint)
            .header("User-Agent", USER_AGENT)
            .post(formBody)
            .build()

        val started = System.currentTimeMillis()
        return try {
            httpClient.newCall(req).execute().use { resp ->
                val latency = System.currentTimeMillis() - started
                if (!resp.isSuccessful) {
                    val status = if (resp.code == 429) ProviderSyncStatus.RATE_LIMITED else ProviderSyncStatus.ERROR
                    saveProviderState(spec.providerId, existingState, status, resp.code, 0, latency, "HTTP ${resp.code}", nowEpochMs)
                    return ProviderFetchOutcome(spec.providerId, status, errorMessage = "HTTP ${resp.code}")
                }
                val raw = readBoundedUtf8(resp.body.byteStream(), MAX_BODY_BYTES)
                val parsed = WorldMonitorNormalizers.parseOverpass(spec, raw, nowEpochMs)
                saveProviderState(
                    spec.providerId,
                    existingState,
                    ProviderSyncStatus.OK,
                    200,
                    parsed.infrastructureEntities.size + parsed.buildings3D.size,
                    latency,
                    null,
                    nowEpochMs
                )
                ProviderFetchOutcome(
                    providerId = spec.providerId,
                    status = ProviderSyncStatus.OK,
                    entities = parsed.infrastructureEntities,
                    buildings3D = parsed.buildings3D
                )
            }
        } catch (e: Exception) {
            saveProviderState(spec.providerId, existingState, ProviderSyncStatus.OFFLINE, null, 0, null, e.message, nowEpochMs)
            ProviderFetchOutcome(spec.providerId, ProviderSyncStatus.OFFLINE, errorMessage = e.message)
        }
    }

    private suspend fun executeFeedRequest(
        spec: WorldProviderSpec,
        request: Request,
        existingState: WorldProviderStateRecord?,
        nowEpochMs: Long,
        queryLat: Double,
        queryLon: Double
    ): ProviderFetchOutcome {
        val started = System.currentTimeMillis()
        return try {
            httpClient.newCall(request).execute().use { response ->
                val latency = System.currentTimeMillis() - started
                val code = response.code
                if (code == 304) {
                    saveProviderState(
                        spec.providerId,
                        existingState,
                        ProviderSyncStatus.OK,
                        304,
                        existingState?.itemCount ?: 0,
                        latency,
                        null,
                        nowEpochMs,
                        etag = response.header("ETag") ?: existingState?.etag,
                        lastMod = response.header("Last-Modified") ?: existingState?.lastModified
                    )
                    return ProviderFetchOutcome(spec.providerId, ProviderSyncStatus.OK)
                }

                if (code == 429) {
                    val retrySec = response.header("Retry-After")?.trim()?.toLongOrNull()?.coerceIn(30L, 7_200L) ?: 300L
                    saveProviderState(
                        spec.providerId,
                        existingState,
                        ProviderSyncStatus.RATE_LIMITED,
                        429,
                        existingState?.itemCount ?: 0,
                        latency,
                        "Rate limited (retry in ${retrySec}s)",
                        nowEpochMs,
                        backoffMs = retrySec * 1000L
                    )
                    return ProviderFetchOutcome(spec.providerId, ProviderSyncStatus.RATE_LIMITED, errorMessage = "HTTP 429")
                }

                if (!response.isSuccessful) {
                    val failures = (existingState?.consecutiveFailures ?: 0) + 1
                    val backoff = (30_000L * (1L shl failures.coerceAtMost(6))).coerceAtMost(1_800_000L)
                    saveProviderState(
                        spec.providerId,
                        existingState,
                        ProviderSyncStatus.ERROR,
                        code,
                        existingState?.itemCount ?: 0,
                        latency,
                        "HTTP $code",
                        nowEpochMs,
                        consecutiveFailures = failures,
                        backoffMs = backoff
                    )
                    return ProviderFetchOutcome(spec.providerId, ProviderSyncStatus.ERROR, errorMessage = "HTTP $code")
                }

                val bodyText = readBoundedUtf8(response.body.byteStream(), MAX_BODY_BYTES)
                val rainFrames = if (spec.providerId == "wm.weather.rainviewer") {
                    WorldMonitorNormalizers.parseRainViewerFrames(bodyText)
                } else emptyList()

                val entities = if (spec.providerId == "wm.weather.rainviewer") {
                    emptyList()
                } else {
                    WorldMonitorNormalizers.normalize(spec, bodyText, nowEpochMs, queryLat, queryLon)
                }

                val count = if (spec.providerId == "wm.weather.rainviewer") rainFrames.size else entities.size
                // HTTP 200 with a body this feed cannot be parsed from is a failure the user can see,
                // not a success. A news outlet that answers with a consent wall, an error page or a
                // changed markup used to be recorded as OK/0 items, which in the Global News desk is
                // indistinguishable from "there is no news today". The body is inspected for the
                // formats this provider class must be able to read, and anything else is reported.
                val unreadable = if (count == 0) unreadableBodyNote(spec, bodyText) else null
                saveProviderState(
                    spec.providerId,
                    existingState,
                    if (unreadable == null) ProviderSyncStatus.OK else ProviderSyncStatus.ERROR,
                    code,
                    count,
                    latency,
                    unreadable,
                    nowEpochMs,
                    etag = response.header("ETag"),
                    lastMod = response.header("Last-Modified")
                )
                ProviderFetchOutcome(
                    providerId = spec.providerId,
                    status = ProviderSyncStatus.OK,
                    entities = entities,
                    rainViewerFrames = rainFrames
                )
            }
        } catch (e: Exception) {
            val failures = (existingState?.consecutiveFailures ?: 0) + 1
            val backoff = (20_000L * failures.coerceAtMost(6)).coerceAtMost(600_000L)
            saveProviderState(
                spec.providerId,
                existingState,
                ProviderSyncStatus.OFFLINE,
                null,
                existingState?.itemCount ?: 0,
                null,
                e.message ?: "Network unreachable",
                nowEpochMs,
                consecutiveFailures = failures,
                backoffMs = backoff
            )
            ProviderFetchOutcome(spec.providerId, ProviderSyncStatus.OFFLINE, errorMessage = e.message)
        }
    }

    /**
     * Global geographic search: combines instant offline baseline matches (countries, capitals,
     * airports, seaports) with cached + live OpenStreetMap Nominatim geocoding.
     */
    suspend fun searchLocations(query: String): List<GeocodeSearchResult> = withContext(Dispatchers.IO) {
        val clean = query.trim()
        if (clean.length < 2) return@withContext emptyList()

        val coordParts = clean.split(Regex("[,\\s]+")).filter { it.isNotBlank() }
        if (coordParts.size == 2) {
            val lat = coordParts[0].toDoubleOrNull()
            val lon = coordParts[1].toDoubleOrNull()
            if (lat != null && lon != null && GeoSpatialIndex.isValidCoordinate(lat, lon)) {
                return@withContext listOf(
                    GeocodeSearchResult(
                        displayName = "Coordinates %.4f°, %.4f°".format(Locale.US, lat, lon),
                        latitude = lat,
                        longitude = lon,
                        boundingBoxKm = 45.0,
                        category = "Coordinate",
                        attribution = "Direct WGS84 Input"
                    )
                )
            }
        }

        val offlineMatches = BaselineReferenceStore.searchOffline(clean)
        val now = System.currentTimeMillis()
        val cacheKey = "q:${clean.lowercase(Locale.US)}"
        val cachedGeocode = dao.getGeocodeCache(cacheKey, now)?.let { rec ->
            runCatching { json.decodeFromString<List<GeocodeSearchResult>>(rec.payloadJson) }.getOrNull()
        }
        val liveMatches = cachedGeocode ?: runCatching {
            val photon = runCatching { queryPhoton(clean) }.getOrDefault(emptyList())
            val nom = if (photon.size < 4) runCatching { queryNominatim(clean) }.getOrDefault(emptyList()) else emptyList()
            val fetched = (photon + nom).distinctBy {
                "${"%.3f".format(Locale.US, it.latitude)}:${"%.3f".format(Locale.US, it.longitude)}"
            }
            if (fetched.isNotEmpty()) {
                dao.putGeocodeCache(
                    WorldGeocodeCacheRecord(
                        queryKey = cacheKey,
                        payloadJson = json.encodeToString(fetched),
                        fetchedAtEpochMs = now,
                        expiresAtEpochMs = now + 7L * 24 * 3_600_000L
                    )
                )
            }
            fetched
        }.getOrDefault(emptyList())

        (offlineMatches + liveMatches)
            .distinctBy { "${"%.3f".format(Locale.US, it.latitude)}:${"%.3f".format(Locale.US, it.longitude)}" }
            .take(14)
    }

    suspend fun lookupElevationAndPlace(lat: Double, lon: Double): Pair<Int?, String?> = withContext(Dispatchers.IO) {
        val elev = runCatching {
            val url = "https://api.open-meteo.com/v1/elevation?latitude=${"%.4f".format(Locale.US, lat)}&longitude=${"%.4f".format(Locale.US, lon)}"
            val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null
                else {
                    val obj = json.parseToJsonElement(readBoundedUtf8(resp.body.byteStream(), 16_384)).jsonObject
                    obj["elevation"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.doubleOrNull?.toInt()
                }
            }
        }.getOrNull()

        val place = runCatching {
            nominatimMutex.withLock {
                val wait = 1_100L - (System.currentTimeMillis() - lastNominatimCallMs)
                if (wait > 0) delay(wait)
                lastNominatimCallMs = System.currentTimeMillis()
                val url = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=16&lat=${"%.5f".format(Locale.US, lat)}&lon=${"%.5f".format(Locale.US, lon)}"
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept-Language", "en")
                    .get()
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) null
                    else {
                        val obj = json.parseToJsonElement(readBoundedUtf8(resp.body.byteStream(), 32_768)).jsonObject
                        obj["display_name"]?.jsonPrimitive?.content
                    }
                }
            }
        }.getOrNull()

        elev to place
    }

    suspend fun fetchLiveWorldBankPopulation(iso2: String): Pair<Long, Int>? = withContext(Dispatchers.IO) {
        runCatching {
            val code = iso2.trim().lowercase(Locale.US)
            if (code.length != 2) return@runCatching null
            val url = "https://api.worldbank.org/v2/country/$code/indicator/SP.POP.TOTL?format=json&per_page=4"
            val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val root = json.parseToJsonElement(readBoundedUtf8(resp.body.byteStream(), 32_768)) as? JsonArray ?: return@use null
                val entries = root.getOrNull(1) as? JsonArray ?: return@use null
                for (el in entries) {
                    val obj = el as? JsonObject ?: continue
                    val value = obj["value"]?.jsonPrimitive?.longOrNull ?: continue
                    val yr = obj["date"]?.jsonPrimitive?.intOrNull ?: 2024
                    if (value > 0L) return@use (value to yr)
                }
                null
            }
        }.getOrNull()
    }

    private fun queryPhoton(query: String): List<GeocodeSearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://photon.komoot.io/api/?q=$encoded&limit=8&lang=en"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val root = json.parseToJsonElement(readBoundedUtf8(resp.body.byteStream(), 131_072)).jsonObject
            val features = root["features"] as? JsonArray ?: return emptyList()
            return features.mapNotNull { el ->
                val fObj = el as? JsonObject ?: return@mapNotNull null
                val coords = (fObj["geometry"] as? JsonObject)?.get("coordinates") as? JsonArray ?: return@mapNotNull null
                val lon = coords.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val lat = coords.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                if (!GeoSpatialIndex.isValidCoordinate(lat, lon)) return@mapNotNull null
                val props = fObj["properties"] as? JsonObject ?: return@mapNotNull null
                val name = props["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val city = props["city"]?.jsonPrimitive?.content
                val state = props["state"]?.jsonPrimitive?.content
                val country = props["country"]?.jsonPrimitive?.content
                val cc = props["countrycode"]?.jsonPrimitive?.content?.uppercase(Locale.US)
                val osmVal = props["osm_value"]?.jsonPrimitive?.content ?: props["type"]?.jsonPrimitive?.content ?: "place"
                val labelParts = listOfNotNull(name, city?.takeIf { it != name }, state, country)
                val bboxKm = when (osmVal.lowercase(Locale.US)) {
                    "country" -> 1_400.0
                    "state", "province", "region" -> 220.0
                    "city" -> 18.0
                    "town", "municipality", "district" -> 6.5
                    else -> 1.8
                }
                GeocodeSearchResult(
                    displayName = labelParts.joinToString(", "),
                    latitude = lat,
                    longitude = lon,
                    boundingBoxKm = bboxKm,
                    category = osmVal.replaceFirstChar { it.uppercase() },
                    countryCode = cc,
                    attribution = "© OpenStreetMap contributors (Photon)"
                )
            }
        }
    }

    private suspend fun queryNominatim(query: String): List<GeocodeSearchResult> = nominatimMutex.withLock {
        val wait = 1_100L - (System.currentTimeMillis() - lastNominatimCallMs)
        if (wait > 0) delay(wait)
        lastNominatimCallMs = System.currentTimeMillis()

        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=6&addressdetails=1&q=$encoded"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "en")
            .get()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val arr = json.parseToJsonElement(readBoundedUtf8(resp.body.byteStream(), 131_072)).jsonArray
            arr.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val lat = obj["lat"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val lon = obj["lon"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                if (!GeoSpatialIndex.isValidCoordinate(lat, lon)) return@mapNotNull null
                val name = obj["display_name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val type = obj["type"]?.jsonPrimitive?.content ?: "Place"
                val addr = obj["address"] as? JsonObject
                val cc = addr?.get("country_code")?.jsonPrimitive?.content?.uppercase(Locale.US)
                val bboxKm = when (type.lowercase(Locale.US)) {
                    "country" -> 1_400.0
                    "state", "administrative" -> 180.0
                    "city" -> 18.0
                    "town", "suburb", "village" -> 5.0
                    else -> 1.8
                }
                GeocodeSearchResult(
                    displayName = name,
                    latitude = lat,
                    longitude = lon,
                    boundingBoxKm = bboxKm,
                    category = type.replaceFirstChar { it.uppercase() },
                    countryCode = cc,
                    attribution = "© OpenStreetMap contributors (Nominatim)"
                )
            }
        }
    }

    private fun resolveEndpointUrl(spec: WorldProviderSpec, secret: String?, viewport: WorldViewport?): String {
        var url = spec.endpoint
        if (url.contains("{apiKey}") && !secret.isNullOrBlank()) {
            url = url.replace("{apiKey}", URLEncoder.encode(secret.trim(), "UTF-8"))
        }
        val lat = viewport?.centerLat ?: 14.5995
        val lon = viewport?.centerLon ?: 120.9842
        val isOpenSky = spec.providerId == "wm.aviation.opensky"
        val west = if (isOpenSky) {
            (lon - 12.0).coerceIn(-180.0, 180.0)
        } else {
            viewport?.west ?: -180.0
        }
        val south = if (isOpenSky) {
            (lat - 12.0).coerceIn(-85.0, 85.0)
        } else {
            viewport?.south ?: -70.0
        }
        val east = if (isOpenSky) {
            (lon + 12.0).coerceIn(-180.0, 180.0)
        } else {
            viewport?.east ?: 180.0
        }
        val north = if (isOpenSky) {
            (lat + 12.0).coerceIn(-85.0, 85.0)
        } else {
            viewport?.north ?: 75.0
        }
        val countryIso = BaselineReferenceStore.nearestCountry(lat, lon)?.iso2 ?: "PH"
        url = url.replace("{west}", "%.2f".format(Locale.US, west))
            .replace("{south}", "%.2f".format(Locale.US, south))
            .replace("{east}", "%.2f".format(Locale.US, east))
            .replace("{north}", "%.2f".format(Locale.US, north))
            .replace("{lat}", "%.3f".format(Locale.US, lat))
            .replace("{lon}", "%.3f".format(Locale.US, lon))
            .replace("{country}", countryIso)
        return url
    }

    private suspend fun saveProviderState(
        providerId: String,
        previous: WorldProviderStateRecord?,
        status: ProviderSyncStatus,
        httpCode: Int?,
        itemCount: Int,
        latencyMs: Long?,
        error: String?,
        nowEpochMs: Long,
        etag: String? = previous?.etag,
        lastMod: String? = previous?.lastModified,
        consecutiveFailures: Int = if (status == ProviderSyncStatus.OK) 0 else (previous?.consecutiveFailures ?: 0),
        backoffMs: Long = 0L
    ) {
        dao.upsertProviderState(
            WorldProviderStateRecord(
                providerId = providerId,
                status = status.name,
                enabled = previous?.enabled ?: true,
                lastAttemptEpochMs = nowEpochMs,
                lastSuccessEpochMs = if (status == ProviderSyncStatus.OK) nowEpochMs else previous?.lastSuccessEpochMs,
                httpCode = httpCode,
                itemCount = itemCount,
                latencyMs = latencyMs,
                errorMessage = error,
                etag = etag,
                lastModified = lastMod,
                nextAllowedEpochMs = if (backoffMs > 0L) nowEpochMs + backoffMs else null,
                consecutiveFailures = consecutiveFailures
            )
        )
    }

    companion object {
        /**
         * Explain an HTTP 200 that yielded nothing. Returns null when the body is a document this
         * provider is expected to parse (an outlet may legitimately have zero current items), so
         * only genuinely unreadable answers are flagged.
         */
        internal fun unreadableBodyNote(spec: WorldProviderSpec, body: String): String? {
            val trimmed = body.trimStart()
            if (trimmed.isEmpty()) return "Empty response body (HTTP 200, 0 bytes)"
            val looksLikeFeed = trimmed.startsWith("<?xml") || trimmed.startsWith("<rss") ||
                trimmed.startsWith("<feed") || trimmed.startsWith("<rdf") ||
                trimmed.contains("<rss ", ignoreCase = true) ||
                trimmed.contains("<rdf:RDF", ignoreCase = true) ||
                trimmed.contains("<feed ", ignoreCase = true) ||
                trimmed.contains("<channel>", ignoreCase = true)
            val looksLikeJson = trimmed.startsWith("{") || trimmed.startsWith("[")
            val looksLikeHtml = trimmed.startsWith("<!doctype", ignoreCase = true) ||
                trimmed.startsWith("<html", ignoreCase = true)
            return when {
                looksLikeHtml -> "Outlet answered with an HTML page instead of a feed (consent wall, " +
                    "captcha or error page). Nothing was stored."
                spec.normalizerId == "news-rss" && !looksLikeFeed ->
                    "Response is not an RSS/Atom/RDF document, so no headline could be read from it."
                looksLikeFeed || looksLikeJson -> null
                else -> null
            }
        }

        private const val USER_AGENT = "OmniShieldX-WorldMonitor/2035.1 (Android; Passive-OSINT-Earth-Viewer)"
        private const val MAX_BODY_BYTES = 8 * 1024 * 1024

        private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

        internal fun readBoundedUtf8(stream: java.io.InputStream, maxBytes: Int): String {
            val out = ByteArrayOutputStream(minOf(maxBytes, 65_536))
            val buf = ByteArray(8_192)
            var total = 0
            while (true) {
                val read = stream.read(buf)
                if (read <= 0) break
                val allowed = minOf(read, maxBytes - total)
                if (allowed > 0) {
                    out.write(buf, 0, allowed)
                    total += allowed
                }
                if (total >= maxBytes) break
            }
            return out.toString(StandardCharsets.UTF_8.name())
        }
    }
}
