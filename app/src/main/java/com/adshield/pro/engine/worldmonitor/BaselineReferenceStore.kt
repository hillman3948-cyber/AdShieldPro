package com.adshield.pro.engine.worldmonitor

import android.content.Context
import com.adshield.pro.domain.worldmonitor.FreshnessClass
import com.adshield.pro.domain.worldmonitor.GeocodeSearchResult
import com.adshield.pro.domain.worldmonitor.GeoAccuracy
import com.adshield.pro.domain.worldmonitor.VerificationStatus
import com.adshield.pro.domain.worldmonitor.WorldEntity
import com.adshield.pro.domain.worldmonitor.WorldIntelCategory
import com.adshield.pro.domain.worldmonitor.WorldSeverity
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Loads the bundled offline reference geography & infrastructure baseline
 * (`assets/worldmonitor/baseline/global_reference.json`), with a filesystem fallback so JVM unit
 * tests run against the exact same asset without needing Robolectric.
 */
object BaselineReferenceStore {

    @Serializable
    data class CountryRef(
        val iso2: String,
        val iso3: String,
        val num: String = "",
        val name: String,
        val capital: String,
        val lat: Double,
        val lon: Double,
        val population: Long,
        val popYear: Int = 2026
    )

    @Serializable
    data class AirportRef(
        val iata: String,
        val icao: String,
        val name: String,
        val city: String,
        val country: String,
        val lat: Double,
        val lon: Double,
        val elevM: Int
    )

    @Serializable
    data class SeaportRef(
        val locode: String,
        val name: String,
        val country: String,
        val lat: Double,
        val lon: Double,
        val kind: String
    )

    @Serializable
    data class SubmarineCableRef(
        val id: String,
        val name: String,
        val rfs: String,
        val coords: List<List<Double>>
    )

    @Serializable
    data class PowerStationRef(
        val id: String,
        val name: String,
        val country: String,
        val fuel: String,
        val capacityMw: Int,
        val lat: Double,
        val lon: Double
    )

    @Serializable
    data class ReferenceTle(
        val noradId: String,
        val name: String,
        val group: String,
        val line1: String,
        val line2: String
    )

    @Serializable
    data class GlobalReferencePayload(
        val schemaVersion: String = "2035.1",
        val countries: List<CountryRef> = emptyList(),
        val airports: List<AirportRef> = emptyList(),
        val seaports: List<SeaportRef> = emptyList(),
        val submarineCables: List<SubmarineCableRef> = emptyList(),
        val powerStations: List<PowerStationRef> = emptyList(),
        val referenceTles: List<ReferenceTle> = emptyList()
    )

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cached: GlobalReferencePayload? = null

    fun load(context: Context? = null): GlobalReferencePayload {
        cached?.let { return it }
        val raw = readRawJson(context)
        val parsed = if (raw != null) {
            runCatching { json.decodeFromString<GlobalReferencePayload>(raw) }.getOrNull()
        } else null
        val result = parsed ?: fallbackMinimal()
        cached = result
        return result
    }

    private fun readRawJson(context: Context?): String? {
        if (context != null) {
            runCatching {
                context.assets.open("worldmonitor/baseline/global_reference.json")
                    .bufferedReader(Charsets.UTF_8)
                    .use { return it.readText() }
            }
        }
        val candidates = listOf(
            File("app/src/main/assets/worldmonitor/baseline/global_reference.json"),
            File("src/main/assets/worldmonitor/baseline/global_reference.json"),
            File("../app/src/main/assets/worldmonitor/baseline/global_reference.json")
        )
        for (file in candidates) {
            if (file.isFile) {
                return runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
            }
        }
        return null
    }

    fun countryByIso(code: String?, context: Context? = null): CountryRef? {
        if (code.isNullOrBlank()) return null
        val norm = code.trim().uppercase()
        val data = load(context)
        return data.countries.firstOrNull {
            it.iso2.equals(norm, ignoreCase = true) ||
                it.iso3.equals(norm, ignoreCase = true) ||
                it.name.equals(code.trim(), ignoreCase = true)
        }
    }

    fun nearestCountry(lat: Double, lon: Double, context: Context? = null): CountryRef? {
        val data = load(context)
        return data.countries.minByOrNull { c ->
            GeoSpatialIndex.haversineKm(lat, lon, c.lat, c.lon)
        }
    }

    /** Offline search across sovereign countries, capitals, major airports and seaports. */
    fun searchOffline(query: String, context: Context? = null): List<GeocodeSearchResult> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val data = load(context)
        val out = ArrayList<GeocodeSearchResult>(16)

        for (c in data.countries) {
            if (c.name.lowercase().contains(q) ||
                c.capital.lowercase().contains(q) ||
                c.iso2.lowercase() == q ||
                c.iso3.lowercase() == q
            ) {
                out += GeocodeSearchResult(
                    displayName = "${c.capital}, ${c.name} (${c.iso2})",
                    latitude = c.lat,
                    longitude = c.lon,
                    boundingBoxKm = 650.0,
                    category = "Country / Capital",
                    countryCode = c.iso2,
                    attribution = "Natural Earth / UN Reference (Offline Baseline)"
                )
            }
        }
        for (a in data.airports) {
            if (a.iata.lowercase() == q ||
                a.icao.lowercase() == q ||
                a.name.lowercase().contains(q) ||
                a.city.lowercase().contains(q)
            ) {
                out += GeocodeSearchResult(
                    displayName = "${a.name} (${a.iata}/${a.icao}) — ${a.city}",
                    latitude = a.lat,
                    longitude = a.lon,
                    boundingBoxKm = 18.0,
                    category = "International Airport",
                    countryCode = a.country,
                    attribution = "OurAirports Public Domain (Offline Baseline)"
                )
            }
        }
        for (p in data.seaports) {
            if (p.locode.lowercase() == q || p.name.lowercase().contains(q)) {
                out += GeocodeSearchResult(
                    displayName = "${p.name} (${p.locode})",
                    latitude = p.lat,
                    longitude = p.lon,
                    boundingBoxKm = 25.0,
                    category = p.kind,
                    countryCode = p.country,
                    attribution = "NGA World Port Index Public Domain (Offline Baseline)"
                )
            }
        }
        return out.take(15)
    }

    /** Builds normalized WorldEntity items for the static reference infrastructure & baseline TLEs. */
    fun buildBaselineEntities(nowEpochMs: Long = System.currentTimeMillis(), context: Context? = null): List<WorldEntity> {
        val data = load(context)
        val entities = ArrayList<WorldEntity>(
            data.airports.size + data.seaports.size + data.submarineCables.size +
                data.powerStations.size + data.referenceTles.size
        )
        val longExpiry = nowEpochMs + 30L * 24L * 3600_000L

        for (a in data.airports) {
            entities += WorldEntity(
                id = "infra:airport:${a.icao}",
                providerId = "wm.infra.baseline",
                providerName = "OurAirports (CC0)",
                category = WorldIntelCategory.INFRASTRUCTURE,
                subType = "Airport",
                title = "${a.iata} · ${a.name}",
                summary = "Major international airport in ${a.city} (${a.country}), field elevation ${a.elevM} m.",
                latitude = a.lat,
                longitude = a.lon,
                altitudeMeters = a.elevM.toDouble(),
                severity = WorldSeverity.INFO,
                freshness = FreshnessClass.HISTORICAL,
                geoAccuracy = GeoAccuracy.EXACT_GNSS,
                confidence = 98,
                verificationStatus = VerificationStatus.COMMUNITY_OPEN_DATA,
                eventTimeEpochMs = nowEpochMs,
                retrievedAtEpochMs = nowEpochMs,
                expiresAtEpochMs = longExpiry,
                sourceUrl = "https://ourairports.com/airports/${a.icao}/",
                attribution = "OurAirports (David Megginson & contributors)",
                licenseInfo = "CC0 1.0 Public Domain",
                countryCode = a.country,
                metricsMap = mapOf("IATA" to a.iata, "ICAO" to a.icao, "Elevation" to "${a.elevM} m")
            )
        }

        for (p in data.seaports) {
            entities += WorldEntity(
                id = "infra:seaport:${p.locode}",
                providerId = "wm.infra.baseline",
                providerName = "NGA World Port Index",
                category = WorldIntelCategory.INFRASTRUCTURE,
                subType = "Seaport",
                title = "${p.locode} · ${p.name}",
                summary = "${p.kind} (${p.country}). Reference maritime infrastructure node.",
                latitude = p.lat,
                longitude = p.lon,
                severity = WorldSeverity.INFO,
                freshness = FreshnessClass.HISTORICAL,
                geoAccuracy = GeoAccuracy.EXACT_GNSS,
                confidence = 96,
                verificationStatus = VerificationStatus.OFFICIAL_AGENCY,
                eventTimeEpochMs = nowEpochMs,
                retrievedAtEpochMs = nowEpochMs,
                expiresAtEpochMs = longExpiry,
                sourceUrl = "https://msi.nga.mil/Publications/WPI",
                attribution = "NGA World Port Index (US Gov Public Domain)",
                licenseInfo = "Public Domain",
                countryCode = p.country,
                metricsMap = mapOf("UN/LOCODE" to p.locode, "Classification" to p.kind)
            )
        }

        for (c in data.submarineCables) {
            val mid = c.coords.getOrNull(c.coords.size / 2) ?: continue
            val lon = mid.getOrNull(0) ?: continue
            val lat = mid.getOrNull(1) ?: continue
            entities += WorldEntity(
                id = "infra:cable:${c.id}",
                providerId = "wm.infra.baseline",
                providerName = "Submarine Cable Reference",
                category = WorldIntelCategory.INFRASTRUCTURE,
                subType = "Submarine Cable",
                title = c.name,
                summary = "Transoceanic submarine telecommunications cable system (RFS ${c.rfs}, ${c.coords.size} landing/route vertices).",
                latitude = lat,
                longitude = lon,
                severity = WorldSeverity.INFO,
                freshness = FreshnessClass.HISTORICAL,
                geoAccuracy = GeoAccuracy.BBOX_APPROXIMATE,
                confidence = 90,
                verificationStatus = VerificationStatus.COMMUNITY_OPEN_DATA,
                eventTimeEpochMs = nowEpochMs,
                retrievedAtEpochMs = nowEpochMs,
                expiresAtEpochMs = longExpiry,
                sourceUrl = "https://www.submarinecablemap.com/",
                attribution = "TeleGeography Submarine Cable Map",
                licenseInfo = "CC BY-SA 4.0",
                geometryType = "LineString",
                geometryCoordsJson = json.encodeToString(c.coords),
                metricsMap = mapOf("RFS" to c.rfs, "Landings" to c.coords.size.toString())
            )
        }

        for (ps in data.powerStations) {
            entities += WorldEntity(
                id = "infra:power:${ps.id}",
                providerId = "wm.infra.baseline",
                providerName = "WRI Global Power Plant DB",
                category = WorldIntelCategory.INFRASTRUCTURE,
                subType = "Power Facility",
                title = "${ps.name} (${ps.fuel})",
                summary = "${ps.fuel} generation facility (${ps.capacityMw} MW) in ${ps.country}.",
                latitude = ps.lat,
                longitude = ps.lon,
                severity = WorldSeverity.INFO,
                freshness = FreshnessClass.HISTORICAL,
                geoAccuracy = GeoAccuracy.EXACT_GNSS,
                confidence = 94,
                verificationStatus = VerificationStatus.COMMUNITY_OPEN_DATA,
                eventTimeEpochMs = nowEpochMs,
                retrievedAtEpochMs = nowEpochMs,
                expiresAtEpochMs = longExpiry,
                sourceUrl = "https://datasets.wri.org/dataset/globalpowerplantdatabase",
                attribution = "World Resources Institute — Global Power Plant Database",
                licenseInfo = "CC BY 4.0",
                countryCode = ps.country,
                metricsMap = mapOf("Fuel" to ps.fuel, "Capacity" to "${ps.capacityMw} MW")
            )
        }

        for (tle in data.referenceTles) {
            entities += WorldEntity(
                id = "space:norad:${tle.noradId}",
                providerId = "wm.space.celestrak.stations",
                providerName = "CelesTrak / NORAD GP Baseline",
                category = WorldIntelCategory.SPACE,
                subType = tle.group,
                title = "${tle.name} (NORAD ${tle.noradId})",
                summary = "${tle.group} orbital platform propagated via SGP4 (satellite.js). Refreshed automatically from CelesTrak GP when online.",
                latitude = 0.0,
                longitude = 0.0,
                altitudeMeters = 415_000.0,
                severity = WorldSeverity.INFO,
                freshness = FreshnessClass.ESTIMATED,
                geoAccuracy = GeoAccuracy.SATELLITE_FOOTPRINT,
                confidence = 90,
                verificationStatus = VerificationStatus.OFFICIAL_AGENCY,
                eventTimeEpochMs = nowEpochMs,
                retrievedAtEpochMs = nowEpochMs,
                expiresAtEpochMs = longExpiry,
                sourceUrl = "https://celestrak.org/NORAD/elements/gp.php?CATNR=${tle.noradId}&FORMAT=tle",
                attribution = "CelesTrak / 18 SDS NORAD GP Elements",
                licenseInfo = "Public GP Elements + satellite.js (MIT)",
                metricsMap = mapOf("NORAD" to tle.noradId, "Group" to tle.group, "Propagation" to "SGP4"),
                tleLine1 = tle.line1,
                tleLine2 = tle.line2
            )
        }

        return entities
    }

    private fun fallbackMinimal(): GlobalReferencePayload = GlobalReferencePayload(
        countries = listOf(
            CountryRef("PH", "PHL", "608", "Philippines", "Manila", 14.5995, 120.9842, 117_337_368L, 2024),
            CountryRef("US", "USA", "840", "United States", "Washington, D.C.", 38.9072, -77.0369, 334_914_895L, 2024),
            CountryRef("JP", "JPN", "392", "Japan", "Tokyo", 35.6762, 139.6503, 124_516_650L, 2024)
        )
    )
}
