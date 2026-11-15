package com.adshield.pro.domain.worldmonitor

import kotlinx.serialization.Serializable

/**
 * Eight independent Global Live Intelligence domains of OMNISHIELD X INFINITY — WORLD MONITOR 2035.
 */
@Serializable
enum class WorldIntelCategory(val label: String, val shortCode: String, val description: String) {
    AVIATION("Aviation", "AIR", "Global ADS-B aircraft positions, altitudes, headings, airports & density"),
    MARITIME("Maritime", "SEA", "Open AIS vessel positions, MMSI classifications, buoys, ports & lanes"),
    SPACE("Space & Orbit", "ORB", "CelesTrak TLE satellites, SGP4 orbits, ISS, passes, launches & NOAA SWPC"),
    DISASTER("Earth & Disasters", "HAZ", "USGS/EMSC earthquakes, NASA EONET, GDACS cyclones, floods & wildfires"),
    WEATHER("Global Weather", "MET", "Open-Meteo temperature, wind, pressure, air quality, waves & RainViewer radar"),
    CYBER("Global Cyber", "CYB", "CISA KEV, NVD CVEs, FIRST EPSS, Feodo C2, RIPEstat BGP & IODA outages"),
    NEWS("Global News", "NEWS", "BBC, Al Jazeera, DW, UN News and Rappler headlines with named-country or dateline placement"),
    INFRASTRUCTURE("Infrastructure", "INF", "Airports, seaports, submarine cables, power stations & OSM transport")
}

@Serializable
enum class WorldSeverity(val label: String, val rank: Int) {
    INFO("INFO", 0),
    LOW("LOW", 1),
    MEDIUM("MEDIUM", 2),
    HIGH("HIGH", 3),
    CRITICAL("CRITICAL", 4)
}

/**
 * Mandatory epistemic freshness badge (Section 9 — Trust & Intelligence Accuracy).
 * SIMULATED is strictly isolated to the Predictive & Simulation Lab and never mixed into live feeds.
 */
@Serializable
enum class FreshnessClass(val label: String, val description: String) {
    LIVE("LIVE", "Observed within the last 5 minutes from an active telemetry feed"),
    NEAR_REAL_TIME("NEAR-REAL-TIME", "Observed within the last 60 minutes"),
    DELAYED("DELAYED", "Older than 60 minutes or served from offline Room cache during weak connectivity"),
    HISTORICAL("HISTORICAL", "Archival observation or static reference dataset (>24h)"),
    ESTIMATED("ESTIMATED", "Physics/model propagation from measured elements (e.g., SGP4 orbit from TLE)"),
    SIMULATED("SIMULATED", "Experimental user-configured scenario in Simulation Lab — not an observation")
}

@Serializable
enum class GeoAccuracy(val label: String) {
    EXACT_GNSS("Exact GNSS / Surveyed"),
    RADAR_TRANSPONDER("ADS-B / AIS Transponder"),
    SEISMIC_EPICENTER("Seismic Network Solution"),
    SATELLITE_FOOTPRINT("Satellite Sensor / SGP4 Subpoint"),
    ADMIN_CENTROID("Country / Administrative Centroid"),
    BBOX_APPROXIMATE("Regional Bounding Box"),
    ASN_COUNTRY_LEVEL("ASN / Country Registry Level (Coarse)"),
    /** The point is the newsroom's own dateline, not a location named in the story. */
    SOURCE_DESK("Outlet Dateline (Not Story Location)"),
    UNKNOWN("Unspecified")
}

@Serializable
enum class VerificationStatus(val label: String) {
    OFFICIAL_AGENCY("Official Government / Scientific Agency"),
    MULTI_SOURCE_CORROBORATED("Multi-Source Corroborated"),
    PUBLIC_TRANSPONDER_FEED("Public Unencrypted Transponder Feed"),
    COMMUNITY_OPEN_DATA("Curated Open-Data Community"),
    UNVERIFIED_REPORT("Single-Source Automated Media Extraction"),
    EXPERIMENTAL_SIMULATION("Experimental Simulation — Non-Predictive")
}

@Serializable
enum class ProviderAuthRequirement(val label: String) {
    NONE_PUBLIC("Keyless Public API"),
    OPTIONAL_USER_KEY("Public + Optional User Key for Higher Quota"),
    REQUIRED_USER_KEY("Free User API Key Required"),
    BACKEND_PROXY_REQUIRED("Licensed / Server Backend Required")
}

@Serializable
enum class ProviderSyncStatus(val label: String) {
    IDLE("Idle"),
    SYNCING("Syncing"),
    OK("Active"),
    CACHED_FALLBACK("Offline Cache"),
    RATE_LIMITED("Rate Limited"),
    NEEDS_API_KEY("Key Optional / Unconfigured"),
    OFFLINE("Network Unreachable"),
    ERROR("Provider Error"),
    BACKEND_REQUIRED("External Backend Required")
}

/**
 * Complete modular provider contract (Section 6 — Data Source Architecture).
 */
@Serializable
data class WorldProviderSpec(
    val providerId: String,
    val name: String,
    val category: WorldIntelCategory,
    val endpoint: String,
    val authRequirement: ProviderAuthRequirement,
    val credentialSlot: String? = null,
    val refreshIntervalMs: Long,
    val minRequestGapMs: Long,
    val rateLimitPolicy: String,
    val maxRetries: Int = 2,
    val normalizerId: String,
    val attribution: String,
    val licenseInfo: String,
    val docsUrl: String,
    val supportsViewportBbox: Boolean = false,
    val defaultEnabled: Boolean = true,
    val implemented: Boolean = true,
    val notes: String = ""
)

@Serializable
data class WorldProviderRuntimeState(
    val providerId: String,
    val status: ProviderSyncStatus = ProviderSyncStatus.IDLE,
    val enabled: Boolean = true,
    val lastAttemptEpochMs: Long? = null,
    val lastSuccessEpochMs: Long? = null,
    val httpCode: Int? = null,
    val itemCount: Int = 0,
    val latencyMs: Long? = null,
    val errorMessage: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val nextAllowedEpochMs: Long? = null,
    val consecutiveFailures: Int = 0
)

/**
 * Canonical normalized intelligence entity with complete provenance and trust metadata.
 */
@Serializable
data class WorldEntity(
    val id: String,
    val providerId: String,
    val providerName: String,
    val category: WorldIntelCategory,
    val subType: String,
    val title: String,
    val summary: String,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double? = null,
    val headingDeg: Double? = null,
    val speedValue: Double? = null,
    val speedUnit: String? = null,
    val severity: WorldSeverity = WorldSeverity.INFO,
    val freshness: FreshnessClass = FreshnessClass.NEAR_REAL_TIME,
    val geoAccuracy: GeoAccuracy = GeoAccuracy.UNKNOWN,
    val confidence: Int = 80,
    val verificationStatus: VerificationStatus = VerificationStatus.OFFICIAL_AGENCY,
    val eventTimeEpochMs: Long,
    val retrievedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val sourceUrl: String,
    val attribution: String,
    val licenseInfo: String,
    val countryCode: String? = null,
    val geometryType: String = "Point",
    val geometryCoordsJson: String? = null,
    val metricsMap: Map<String, String> = emptyMap(),
    val tleLine1: String? = null,
    val tleLine2: String? = null
)

@Serializable
data class EntityTrailPoint(
    val lat: Double,
    val lon: Double,
    val altM: Double = 0.0,
    val timestampEpochMs: Long
)

@Serializable
data class EntityTrail(
    val entityId: String,
    val category: WorldIntelCategory,
    val points: List<EntityTrailPoint>
)

@Serializable
enum class GlobeProjection(val label: String) {
    GLOBE_3D("3D Globe"),
    COLUMBUS_25D("2.5D Columbus"),
    MAP_2D("2D Mercator")
}

@Serializable
enum class GlobeBasemap(val label: String, val attribution: String) {
    SATELLITE_ESRI("Satellite + Hybrid Cities (Esri)", "Esri, Maxar, Earthstar Geographics, USDA, USGS"),
    OPENSTREETMAP("OpenStreetMap Streets & Places", "© OpenStreetMap contributors (ODbL)"),
    CARTO_DARK_MATTER("Dark Tactical City Streets", "© OpenStreetMap contributors · © CARTO"),
    OPEN_TOPO_MAP("Topographic Terrain & Contours", "© OpenStreetMap contributors · OpenTopoMap (CC-BY-SA)"),
    NASA_GIBS_TRUECOLOR("NASA GIBS VIIRS True Color", "NASA EOSDIS Global Imagery Browse Services"),
    OFFLINE_NATURAL_EARTH("Offline Natural Earth II", "Natural Earth II (Public Domain, bundled in APK)")
}

@Serializable
enum class GlobeStylePreset(val label: String, val isVisualFilter: Boolean) {
    STANDARD("Standard True-Color", false),
    CYBER_TACTICAL("OmniShield Cyber Navy", true),
    NIGHT_VISION("Night-Ops Phosphor (Visual Filter)", true),
    MONOCHROME_ANALYTIC("Analytic Monochrome (Visual Filter)", true)
}

@Serializable
enum class GraphicsPerformanceMode(val label: String, val description: String) {
    HIGH("High Fidelity", "Full resolution, 1.2s orbital tick, 3D heightmap terrain available"),
    BALANCED("Balanced Mobile", "0.92x scale, 2.5s orbital tick, clustered markers"),
    LOW_POWER("Battery Saver", "0.72x scale, ellipsoid terrain, aggressive clustering, 5s orbital tick")
}

@Serializable
data class WorldViewport(
    val west: Double = -180.0,
    val south: Double = -85.0,
    val east: Double = 180.0,
    val north: Double = 85.0,
    val centerLat: Double = 14.5995,
    val centerLon: Double = 120.9842,
    val cameraAltitudeMeters: Long = 14_500_000L,
    val groundElevationMeters: Int = 0,
    val headingDeg: Double = 0.0,
    val pitchDeg: Double = -90.0,
    val projection: GlobeProjection = GlobeProjection.GLOBE_3D
)

@Serializable
data class HistoricalImagerySplitState(
    val enabled: Boolean = false,
    val leftDateIso: String = "2024-11-01",
    val rightDateIso: String = "2026-11-06",
    val splitPosition: Float = 0.5f
)

@Serializable
data class RainViewerFrame(
    val time: Long,
    val host: String,
    val path: String
)

@Serializable
data class OsmBuilding3D(
    val id: String,
    val name: String,
    val heightMeters: Double,
    val heightEstimated: Boolean,
    val footprint: List<List<Double>>
)

@Serializable
data class GeocodeSearchResult(
    val displayName: String,
    val latitude: Double,
    val longitude: Double,
    val boundingBoxKm: Double = 40.0,
    val category: String = "Place",
    val countryCode: String? = null,
    val attribution: String = "© OpenStreetMap contributors (Nominatim)"
)

@Serializable
data class SelectedLocationInfo(
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Int,
    val displayName: String,
    val countryName: String?,
    val countryCode: String?,
    val weatherSummary: String?,
    val airQualitySummary: String?,
    val nearbyEntityCount: Int,
    val attribution: String
)

@Serializable
data class CorrelationLink(
    val fromLat: Double,
    val fromLon: Double,
    val toLat: Double,
    val toLon: Double
)

/**
 * Output of the deterministic, non-hallucinating Global Event Correlation Engine (Section 3).
 */
@Serializable
data class CorrelatedIncident(
    val id: String,
    val ruleId: String,
    val ruleTitle: String,
    val severity: WorldSeverity,
    val confidence: Int,
    val distanceKm: Double,
    val timeDeltaMinutes: Long,
    val latitude: Double,
    val longitude: Double,
    val summary: String,
    val rationale: String,
    val caveat: String,
    val entityIds: List<String>,
    val evidenceItems: List<String>,
    val sourceLinks: List<String>,
    val links: List<CorrelationLink>
)

@Serializable
data class DatasetComparisonRow(
    val metric: String,
    val primaryValue: String,
    val secondaryValue: String,
    val source: String
)

@Serializable
data class DigitalTwinSnapshot(
    val regionName: String,
    val countryCode: String?,
    val latitude: Double,
    val longitude: Double,
    val radiusKm: Double,
    val populationCount: Long?,
    val populationYear: Int?,
    val populationSource: String,
    val elevationMeters: Int,
    val weatherSummary: String,
    val airQualitySummary: String,
    val riverDischargeSummary: String,
    val infrastructureCounts: Map<String, Int>,
    val activeHazardCounts: Map<String, Int>,
    val cyberSignalsCount: Int,
    val comparisonTargetName: String?,
    val datasetComparison: List<DatasetComparisonRow>,
    val generatedAtEpochMs: Long
)

@Serializable
data class CitedFact(
    val statement: String,
    val provider: String,
    val timestampEpochMs: Long,
    val freshness: FreshnessClass,
    val sourceUrl: String
)

/**
 * Epistemically partitioned response from the AI Global Analyst (Section 3).
 * Explicitly separates verified observations, uncertainties/gaps, and analytical hypotheses.
 */
@Serializable
data class AiAnalystResponse(
    val query: String,
    val engineMode: String,
    val generatedAtEpochMs: Long,
    val headline: String,
    val verifiedObservations: List<CitedFact>,
    val uncertaintiesAndGaps: List<String>,
    val analyticalHypotheses: List<String>,
    val multiSourceSummary: String,
    val markdownReport: String
)

@Serializable
enum class SimulationKind(val label: String, val summary: String) {
    WEATHER_PLUME("Weather & Storm Radius", "Configurable storm/wind hazard footprint over surrounding infrastructure"),
    FLOOD_SURGE("Flood & Coastal Surge", "Combines GloFAS river discharge context with configurable surge radius"),
    INFRASTRUCTURE_EXPOSURE("Infrastructure & Population Ring", "Evaluates airports, seaports, cables & power assets inside hazard rings"),
    ENVIRONMENTAL_TREND("Environmental Trend Delta", "Computes multi-parameter environmental & seismic activity anomalies"),
    HISTORICAL_REPLAY("Historical Benchmark Sequence", "Replays documented historical geohazard propagation geometry")
}

@Serializable
data class SimulationRing(
    val lat: Double,
    val lon: Double,
    val radiusKm: Double,
    val colorHex: String,
    val alpha: Double,
    val label: String
)

@Serializable
data class SimulationWaypoint(
    val lat: Double,
    val lon: Double,
    val label: String
)

@Serializable
data class SimulationResult(
    val active: Boolean,
    val kind: SimulationKind,
    val label: String,
    val centerLat: Double,
    val centerLon: Double,
    val primaryParameterLabel: String,
    val disclaimer: String = "EXPERIMENTAL SIMULATION — Non-predictive exploratory model. Never treat as an official forecast or warning.",
    val exposedAirports: List<String> = emptyList(),
    val exposedSeaports: List<String> = emptyList(),
    val exposedPowerPlants: List<String> = emptyList(),
    val exposedSubmarineCables: List<String> = emptyList(),
    val findings: List<String> = emptyList(),
    val rings: List<SimulationRing> = emptyList(),
    val waypoints: List<SimulationWaypoint> = emptyList(),
    val freshness: FreshnessClass = FreshnessClass.SIMULATED,
    val verificationStatus: VerificationStatus = VerificationStatus.EXPERIMENTAL_SIMULATION
)

@Serializable
data class SatellitePassPrediction(
    val entityId: String,
    val name: String,
    val observerLat: Double,
    val observerLon: Double,
    val riseEpochMs: Long,
    val culminationEpochMs: Long,
    val setEpochMs: Long,
    val maxElevationDeg: Double,
    val riseAzimuthDeg: Double,
    val setAzimuthDeg: Double,
    val durationSeconds: Int
)

@Serializable
data class WatchtowerRegion(
    val id: String,
    val name: String,
    val centerLat: Double,
    val centerLon: Double,
    val radiusKm: Double,
    val categories: Set<WorldIntelCategory>,
    val minSeverity: WorldSeverity,
    val providerFilter: String? = null,
    val enabled: Boolean = true,
    val createdAtEpochMs: Long
)

@Serializable
data class WatchtowerAlert(
    val id: Long = 0L,
    val regionId: String,
    val regionName: String,
    val entityId: String,
    val category: WorldIntelCategory,
    val severity: WorldSeverity,
    val title: String,
    val summary: String,
    val providerName: String,
    val sourceUrl: String,
    val distanceKm: Double,
    val triggeredAtEpochMs: Long
)

@Serializable
enum class WorldTimelineWindow(val label: String, val durationMs: Long?) {
    LIVE_1H("1H", 60L * 60_000L),
    LAST_6H("6H", 6L * 60L * 60_000L),
    LAST_24H("24H", 24L * 60L * 60_000L),
    LAST_7D("7D", 7L * 24L * 60L * 60_000L),
    ALL_CACHED("ALL", null)
}
