package com.adshield.pro.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adshield.pro.data.worldmonitor.WorldMonitorRepository
import com.adshield.pro.domain.worldmonitor.AiAnalystResponse
import com.adshield.pro.domain.worldmonitor.CorrelatedIncident
import com.adshield.pro.domain.worldmonitor.DigitalTwinSnapshot
import com.adshield.pro.domain.worldmonitor.EntityTrail
import com.adshield.pro.domain.worldmonitor.FreshnessClass
import com.adshield.pro.domain.worldmonitor.GeocodeSearchResult
import com.adshield.pro.domain.worldmonitor.GlobeBasemap
import com.adshield.pro.domain.worldmonitor.GlobeProjection
import com.adshield.pro.domain.worldmonitor.GlobeStylePreset
import com.adshield.pro.domain.worldmonitor.GraphicsPerformanceMode
import com.adshield.pro.domain.worldmonitor.HistoricalImagerySplitState
import com.adshield.pro.domain.worldmonitor.OsmBuilding3D
import com.adshield.pro.domain.worldmonitor.SatellitePassPrediction
import com.adshield.pro.domain.worldmonitor.SimulationKind
import com.adshield.pro.domain.worldmonitor.SimulationResult
import com.adshield.pro.domain.worldmonitor.WatchtowerAlert
import com.adshield.pro.domain.worldmonitor.WatchtowerRegion
import com.adshield.pro.domain.worldmonitor.WorldEntity
import com.adshield.pro.domain.worldmonitor.WorldIntelCategory
import com.adshield.pro.domain.worldmonitor.WorldProviderRuntimeState
import com.adshield.pro.domain.worldmonitor.WorldSeverity
import com.adshield.pro.domain.worldmonitor.WorldTimelineWindow
import com.adshield.pro.domain.worldmonitor.WorldViewport
import com.adshield.pro.engine.worldmonitor.BaselineReferenceStore
import com.adshield.pro.engine.worldmonitor.CzmlAndGeoJsonBuilder
import com.adshield.pro.engine.worldmonitor.DigitalTwinEngine
import com.adshield.pro.engine.worldmonitor.EventCorrelationEngine
import com.adshield.pro.engine.worldmonitor.OrbitalAndGlobeMath
import com.adshield.pro.engine.worldmonitor.SimulationLabEngine
import com.adshield.pro.engine.worldmonitor.WorldMonitorAiAnalyst
import com.adshield.pro.engine.worldmonitor.WorldMonitorJsBridge
import com.adshield.pro.engine.worldmonitor.WorldMonitorProviderRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

enum class WorldMonitorDeckTab(val label: String) {
    FEED("Intel Feed"),
    NEWS("Global News"),
    LAYERS("Layers & 3D"),
    TWIN("Digital Twin"),
    CORRELATE("Correlate"),
    AI_ANALYST("AI Analyst"),
    SIM_LAB("Sim Lab"),
    TIMELINE("Time & Future"),
    WATCHTOWER("Watchtower"),
    SOURCES("Sources")
}

data class LayerUiState(
    val category: WorldIntelCategory,
    val visible: Boolean = true,
    val opacity: Float = 1.0f
)

data class ClickedLocationInfo(
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Int,
    val placeName: String?,
    val countryName: String?,
    val population: Long?
)

sealed interface NativeGlobeCommand {
    data class FlyTo(
        val lat: Double,
        val lon: Double,
        val altitudeMeters: Double,
        val pitchDeg: Double
    ) : NativeGlobeCommand

    data class CameraAction(val action: String) : NativeGlobeCommand
    data class SelectSatellite(val entityId: String) : NativeGlobeCommand
}

data class WorldMonitorUiState(
    val useNative3dEngine: Boolean = true,
    val globeReady: Boolean = true,
    val globeError: String? = null,
    val cesiumVersion: String = "Native-3D-GPU-2.0",
    val reloadNonce: Int = 0,
    val fullscreenMode: Boolean = false,
    val deckExpanded: Boolean = false,
    val activeTab: WorldMonitorDeckTab = WorldMonitorDeckTab.FEED,
    val isSyncing: Boolean = false,
    val statusBanner: String? = null,
    val viewport: WorldViewport = WorldViewport(),
    val basemap: GlobeBasemap = GlobeBasemap.SATELLITE_ESRI,
    val projection: GlobeProjection = GlobeProjection.GLOBE_3D,
    val stylePreset: GlobeStylePreset = GlobeStylePreset.CYBER_TACTICAL,
    val performanceMode: GraphicsPerformanceMode = GraphicsPerformanceMode.BALANCED,
    val terrain3dEnabled: Boolean = false,
    val bordersEnabled: Boolean = true,
    val atmosphereEnabled: Boolean = true,
    val dayNightLightingEnabled: Boolean = true,
    val labelsEnabled: Boolean = true,
    val clusteringEnabled: Boolean = true,
    val heatmapEnabled: Boolean = false,
    val cloudsOverlayEnabled: Boolean = false,
    val radarOverlayEnabled: Boolean = false,
    val trailsEnabled: Boolean = true,
    val historicalSplit: HistoricalImagerySplitState = HistoricalImagerySplitState(),
    val layerStates: Map<WorldIntelCategory, LayerUiState> =
        WorldIntelCategory.entries.associateWith { LayerUiState(it, visible = true, opacity = 1.0f) },
    val minSeverityFilter: WorldSeverity = WorldSeverity.INFO,
    val timelineWindow: WorldTimelineWindow = WorldTimelineWindow.ALL_CACHED,
    val timelineProgress: Float = 1.0f,
    val timelinePlaying: Boolean = false,
    val allEntities: List<WorldEntity> = emptyList(),
    val filteredEntities: List<WorldEntity> = emptyList(),
    val categoryCounts: Map<WorldIntelCategory, Int> = emptyMap(),
    /** Every cached headline, newest first — read by the Global News desk. */
    val newsEntities: List<WorldEntity> = emptyList(),
    /** News providers that are enabled but have no cached item, with the reason to show. */
    val newsSourceIssues: List<String> = emptyList(),
    val selectedEntity: WorldEntity? = null,
    val clickedLocation: ClickedLocationInfo? = null,
    val satellitePasses: List<SatellitePassPrediction> = emptyList(),
    val correlations: List<CorrelatedIncident> = emptyList(),
    val digitalTwin: DigitalTwinSnapshot? = null,
    val compareCountryIso: String = "JP",
    val aiResponse: AiAnalystResponse? = null,
    val simulation: SimulationResult = SimulationResult(
        active = false,
        kind = SimulationKind.WEATHER_PLUME,
        label = "Inactive",
        centerLat = 14.5995,
        centerLon = 120.9842,
        primaryParameterLabel = ""
    ),
    val searchQuery: String = "",
    val isSearching: Boolean = false,
    val searchResults: List<GeocodeSearchResult> = emptyList(),
    val providerStates: List<WorldProviderRuntimeState> = emptyList(),
    val watchRegions: List<WatchtowerRegion> = emptyList(),
    val watchAlerts: List<WatchtowerAlert> = emptyList(),
    val buildings3d: List<OsmBuilding3D> = emptyList(),
    val entityTrails: List<EntityTrail> = emptyList(),
    val futureOffsetHours: Int = 0,
    val autoOrbitEnabled: Boolean = false,
    val hudVisorEnabled: Boolean = true,
    val measureModeEnabled: Boolean = false,
    val measureStart: Pair<Double, Double>? = null,
    val measureEnd: Pair<Double, Double>? = null,
    val liveAutoSyncEnabled: Boolean = true,
    val livePulseCount: Int = 0,
    val lastLiveSyncEpochMs: Long = 0L,
    val activeCountryIso: String = "PH",
    val activeCountryName: String = "Philippines",
    val showCountryDirectory: Boolean = false,
    val countryFilterQuery: String = "",
    val hasOpenSkyKey: Boolean = false,
    val hasFirmsKey: Boolean = false,
    val hasAisStreamKey: Boolean = false
)

@HiltViewModel
class WorldMonitorViewModel @Inject constructor(
    private val repository: WorldMonitorRepository
) : ViewModel(), WorldMonitorJsBridge.Listener {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private val _uiState = MutableStateFlow(
        WorldMonitorUiState(
            hasOpenSkyKey = repository.hasCredential("wm.cred.opensky"),
            hasFirmsKey = repository.hasCredential("wm.cred.nasa_firms"),
            hasAisStreamKey = repository.hasCredential("wm.cred.aisstream")
        )
    )
    val uiState: StateFlow<WorldMonitorUiState> = _uiState.asStateFlow()

    private val _jsCommands = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val jsCommands: SharedFlow<String> = _jsCommands.asSharedFlow()

    private val _nativeGlobeCommands = MutableSharedFlow<NativeGlobeCommand>(extraBufferCapacity = 64)
    val nativeGlobeCommands: SharedFlow<NativeGlobeCommand> = _nativeGlobeCommands.asSharedFlow()

    private var timelinePlayJob: Job? = null
    private var viewportLiveSyncJob: Job? = null
    private var lastSyncedCountryIso: String = "PH"
    private var lastSyncedViewportLat: Double = 14.5995
    private var lastSyncedViewportLon: Double = 120.9842

    init {
        viewModelScope.launch {
            repository.ensureBaselineSeeded()
            syncAllEnabled(force = false)
        }
        // Continuous 45-second real-time live telemetry loop (flights, ISS, local country weather/AQI/waves/BGP & global feeds)
        viewModelScope.launch {
            while (isActive) {
                delay(45_000L)
                val cur = _uiState.value
                if (!cur.liveAutoSyncEnabled || cur.isSyncing) continue
                val vp = cur.viewport
                val okRegional = repository.syncViewportLiveProviders(vp, force = true)
                repository.syncEnabledProviders(
                    categories = cur.layerStates.filterValues { it.visible }.keys,
                    viewport = vp,
                    force = false,
                    notifyWatchtower = false
                )
                refreshTrailsOnGlobe()
                val now = System.currentTimeMillis()
                _uiState.update {
                    it.copy(
                        livePulseCount = it.livePulseCount + 1,
                        lastLiveSyncEpochMs = if (okRegional > 0) now else it.lastLiveSyncEpochMs
                    )
                }
            }
        }
        viewModelScope.launch {
            repository.entitiesFlow.collectLatest { entities ->
                recomputeDerivedIntelligence(entities)
            }
        }
        viewModelScope.launch {
            repository.providerStatesFlow.collectLatest { states ->
                _uiState.update { it.copy(providerStates = states) }
            }
        }
        viewModelScope.launch {
            repository.watchRegionsFlow.collectLatest { regions ->
                _uiState.update { it.copy(watchRegions = regions) }
                pushWatchRegionsToGlobe(regions)
            }
        }
        viewModelScope.launch {
            repository.watchAlertsFlow.collectLatest { alerts ->
                _uiState.update { it.copy(watchAlerts = alerts) }
            }
        }
        viewModelScope.launch {
            repository.buildings3D.collectLatest { buildings ->
                _uiState.update { it.copy(buildings3d = buildings) }
                if (buildings.isNotEmpty()) pushBuildingsToGlobe(buildings)
            }
        }
    }

    // ── Bridge Listener Callbacks ────────────────────────────────────────────

    override fun onGlobeReady(webglSupported: Boolean, cesiumVersion: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    globeReady = webglSupported,
                    cesiumVersion = cesiumVersion,
                    globeError = if (webglSupported) null else "WebGL hardware context unavailable on this device."
                )
            }
            pushFullStateToGlobe()
        }
    }

    override fun onGlobeError(errorMessage: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(globeError = errorMessage) }
        }
    }

    override fun onViewportChanged(viewport: WorldViewport) {
        viewModelScope.launch {
            val nearest = BaselineReferenceStore.nearestCountry(viewport.centerLat, viewport.centerLon)
            _uiState.update {
                it.copy(
                    viewport = viewport,
                    activeCountryIso = nearest?.iso2 ?: it.activeCountryIso,
                    activeCountryName = nearest?.name ?: it.activeCountryName
                )
            }
            val countryChanged = nearest != null && nearest.iso2 != lastSyncedCountryIso
            val movedDeg = kotlin.math.abs(viewport.centerLat - lastSyncedViewportLat) +
                kotlin.math.abs(viewport.centerLon - lastSyncedViewportLon)
            if (_uiState.value.liveAutoSyncEnabled && (countryChanged || movedDeg >= 3.5)) {
                viewportLiveSyncJob?.cancel()
                viewportLiveSyncJob = viewModelScope.launch {
                    delay(1_250L)
                    lastSyncedCountryIso = nearest?.iso2 ?: lastSyncedCountryIso
                    lastSyncedViewportLat = viewport.centerLat
                    lastSyncedViewportLon = viewport.centerLon
                    val ok = repository.syncViewportLiveProviders(viewport, force = true)
                    refreshTrailsOnGlobe()
                    if (ok > 0) {
                        _uiState.update { s -> s.copy(lastLiveSyncEpochMs = System.currentTimeMillis()) }
                    }
                    if (_uiState.value.activeTab == WorldMonitorDeckTab.TWIN) {
                        refreshDigitalTwin()
                    }
                }
            }
        }
    }

    override fun onEntitySelected(entityId: String) {
        viewModelScope.launch {
            val match = _uiState.value.allEntities.firstOrNull { it.id == entityId }
            _uiState.update {
                it.copy(
                    selectedEntity = match,
                    satellitePasses = emptyList()
                )
            }
            if (match != null && match.category == WorldIntelCategory.SPACE && !match.tleLine1.isNullOrBlank()) {
                computePassesForSelectedSatellite(match)
            }
        }
    }

    override fun onBuildingSelected(
        id: String,
        name: String,
        heightMeters: Double,
        heightEstimated: Boolean,
        lat: Double,
        lon: Double
    ) {
        viewModelScope.launch {
            val country = BaselineReferenceStore.nearestCountry(lat, lon)
            _uiState.update {
                it.copy(
                    clickedLocation = ClickedLocationInfo(
                        latitude = lat,
                        longitude = lon,
                        elevationMeters = heightMeters.toInt(),
                        placeName = "$name (${heightMeters.toInt()} m · ${if (heightEstimated) "OSM Levels Est." else "Surveyed"})",
                        countryName = country?.name,
                        population = country?.population
                    )
                )
            }
        }
    }

    override fun onLocationSelected(lat: Double, lon: Double, terrainElevationMeters: Int, countryHint: String) {
        viewModelScope.launch {
            val country = BaselineReferenceStore.nearestCountry(lat, lon)
            _uiState.update {
                it.copy(
                    activeCountryIso = country?.iso2 ?: it.activeCountryIso,
                    activeCountryName = country?.name ?: it.activeCountryName,
                    clickedLocation = ClickedLocationInfo(
                        latitude = lat,
                        longitude = lon,
                        elevationMeters = terrainElevationMeters,
                        placeName = countryHint.ifBlank { country?.let { c -> "Sector near ${c.capital}, ${c.name}" } },
                        countryName = country?.name,
                        population = country?.population
                    )
                )
            }
            // Concurrently stream live regional flights, weather, AQI, waves, BGP & landmarks for the tapped country/sector!
            launch {
                val tapVp = WorldViewport(
                    west = (lon - 8.0).coerceIn(-180.0, 180.0),
                    south = (lat - 8.0).coerceIn(-85.0, 85.0),
                    east = (lon + 8.0).coerceIn(-180.0, 180.0),
                    north = (lat + 8.0).coerceIn(-85.0, 85.0),
                    centerLat = lat,
                    centerLon = lon,
                    cameraAltitudeMeters = _uiState.value.viewport.cameraAltitudeMeters
                )
                repository.syncViewportLiveProviders(tapVp, force = true)
                refreshTrailsOnGlobe()
            }
            val (liveElev, reverseName) = repository.lookupElevationAndPlace(lat, lon)
            _uiState.update { cur ->
                val loc = cur.clickedLocation ?: return@update cur
                cur.copy(
                    clickedLocation = loc.copy(
                        elevationMeters = liveElev ?: loc.elevationMeters,
                        placeName = reverseName ?: loc.placeName
                    )
                )
            }
        }
    }

    override fun onSatellitePassesComputed(entityId: String, passes: List<SatellitePassPrediction>) {
        viewModelScope.launch {
            _uiState.update { it.copy(satellitePasses = passes) }
        }
    }

    // ── User Actions ─────────────────────────────────────────────────────────

    fun selectTab(tab: WorldMonitorDeckTab) {
        _uiState.update { it.copy(activeTab = tab, deckExpanded = true) }
        if (tab == WorldMonitorDeckTab.TWIN) {
            refreshDigitalTwin()
        } else if (tab == WorldMonitorDeckTab.AI_ANALYST && _uiState.value.aiResponse == null) {
            askAiAnalyst("Summarize current global intelligence posture and major hazards")
        }
    }

    fun toggleDeckExpanded() {
        _uiState.update { it.copy(deckExpanded = !it.deckExpanded) }
    }

    fun toggleFullscreen() {
        _uiState.update { cur ->
            val next = !cur.fullscreenMode
            cur.copy(fullscreenMode = next, deckExpanded = if (next) false else cur.deckExpanded)
        }
    }

    fun reloadGlobe() {
        val native = _uiState.value.useNative3dEngine
        if (native) {
            _nativeGlobeCommands.tryEmit(NativeGlobeCommand.CameraAction("HOME_GLOBE"))
        }
        _uiState.update {
            it.copy(
                globeReady = native,
                globeError = null,
                reloadNonce = it.reloadNonce + 1,
                statusBanner = if (native) {
                    "Native 3D GPU Digital Earth reset to Indo-Pacific view."
                } else {
                    "Reinitializing Cesium WebGL 3D Digital Earth engine…"
                }
            )
        }
    }

    fun dismissSelectedEntity() {
        _uiState.update { it.copy(selectedEntity = null, satellitePasses = emptyList()) }
    }

    fun dismissClickedLocation() {
        _uiState.update { it.copy(clickedLocation = null) }
    }

    fun syncAllEnabled(force: Boolean = true) {
        if (_uiState.value.isSyncing) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true, statusBanner = "Synchronizing ${WorldMonitorProviderRegistry.providers.size} global & regional live feeds…") }
            val vp = _uiState.value.viewport
            val okCount = repository.syncEnabledProviders(
                categories = _uiState.value.layerStates.filterValues { it.visible }.keys,
                viewport = vp,
                force = force,
                notifyWatchtower = force
            )
            refreshTrailsOnGlobe()
            if (_uiState.value.radarOverlayEnabled) {
                pushRadarStateToGlobe(true)
            }
            val now = System.currentTimeMillis()
            _uiState.update {
                it.copy(
                    isSyncing = false,
                    lastLiveSyncEpochMs = now,
                    livePulseCount = it.livePulseCount + 1,
                    statusBanner = "LIVE SYNC OK · $okCount feed(s) refreshed · ${_uiState.value.allEntities.size} entities active"
                )
            }
        }
    }

    fun pulseLiveNow() {
        _uiState.update {
            it.copy(
                liveAutoSyncEnabled = true,
                futureOffsetHours = 0,
                timelineWindow = WorldTimelineWindow.ALL_CACHED,
                timelineProgress = 1.0f
            )
        }
        recomputeDerivedIntelligence(_uiState.value.allEntities)
        syncAllEnabled(force = true)
    }

    fun toggleLiveAutoSync() {
        val next = !_uiState.value.liveAutoSyncEnabled
        _uiState.update {
            it.copy(
                liveAutoSyncEnabled = next,
                statusBanner = if (next) {
                    "LIVE Auto-Sync enabled (45s continuous flight, space, weather, seismic & cyber telemetry)"
                } else {
                    "LIVE Auto-Sync paused — tap ● LIVE anytime to resume"
                }
            )
        }
        if (next) pulseLiveNow()
    }

    fun setShowCountryDirectory(show: Boolean) {
        _uiState.update { it.copy(showCountryDirectory = show, countryFilterQuery = if (show) it.countryFilterQuery else "") }
    }

    fun updateCountryFilterQuery(query: String) {
        _uiState.update { it.copy(countryFilterQuery = query) }
    }

    fun selectCountry(countryIso: String) {
        val country = BaselineReferenceStore.countryByIso(countryIso) ?: return
        val targetVp = WorldViewport(
            west = (country.lon - 8.0).coerceIn(-180.0, 180.0),
            south = (country.lat - 8.0).coerceIn(-85.0, 85.0),
            east = (country.lon + 8.0).coerceIn(-180.0, 180.0),
            north = (country.lat + 8.0).coerceIn(-85.0, 85.0),
            centerLat = country.lat,
            centerLon = country.lon,
            cameraAltitudeMeters = 680_000L
        )
        lastSyncedCountryIso = country.iso2
        lastSyncedViewportLat = country.lat
        lastSyncedViewportLon = country.lon
        _uiState.update {
            it.copy(
                activeCountryIso = country.iso2,
                activeCountryName = country.name,
                showCountryDirectory = false,
                countryFilterQuery = "",
                viewport = targetVp,
                statusBanner = "Flying to ${country.name} (${country.capital}) · Syncing live airspace, weather, AQI & BGP…"
            )
        }
        flyTo(country.lat, country.lon, altitudeMeters = 680_000.0, pitchDeg = -54.0)
        viewModelScope.launch {
            val ok = repository.syncViewportLiveProviders(targetVp, force = true)
            refreshTrailsOnGlobe()
            if (_uiState.value.activeTab == WorldMonitorDeckTab.TWIN) {
                refreshDigitalTwin()
            }
            _uiState.update { s ->
                s.copy(
                    lastLiveSyncEpochMs = System.currentTimeMillis(),
                    statusBanner = "${country.name} (${country.iso2}) LIVE · $ok local feed(s) synced around ${country.capital}"
                )
            }
        }
    }

    fun syncSingleProvider(providerId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(statusBanner = "Refreshing $providerId…") }
            val outcome = repository.syncSingleProvider(providerId, viewport = _uiState.value.viewport, force = true)
            _uiState.update {
                it.copy(
                    statusBanner = outcome.errorMessage ?: "Provider $providerId updated (${outcome.entities.size} records)"
                )
            }
        }
    }

    fun load3dBuildingsAtViewport() {
        viewModelScope.launch {
            val vp = _uiState.value.viewport
            if (vp.cameraAltitudeMeters > 18_000L) {
                flyTo(vp.centerLat, vp.centerLon, altitudeMeters = 4_800.0, pitchDeg = -48.0)
            }
            _uiState.update { it.copy(statusBanner = "Scanning 3D city buildings, Wikipedia landmarks & live aircraft…") }
            val outcome = repository.syncBuildingsAndStreetInfra(vp)
            _uiState.update {
                it.copy(
                    statusBanner = outcome.errorMessage
                        ?: "City Scan: ${outcome.buildings3D.size} 3D building(s) & ${outcome.entities.size} local place(s) loaded"
                )
            }
        }
    }

    fun setFutureOffsetHours(hours: Int) {
        val clamped = hours.coerceIn(0, 72)
        _uiState.update {
            it.copy(
                futureOffsetHours = clamped,
                statusBanner = if (clamped == 0) {
                    "Returned to Live Real-Time Telemetry"
                } else {
                    "Future Horizon +${clamped}H active — SGP4 orbits, flight/ship vectors & 72h weather projected"
                }
            )
        }
        if (clamped > 0) {
            viewModelScope.launch {
                repository.syncSingleProvider("wm.weather.forecast72h", viewport = _uiState.value.viewport, force = false)
            }
        }
    }

    fun toggleAutoOrbit() {
        val next = !_uiState.value.autoOrbitEnabled
        _uiState.update {
            it.copy(
                autoOrbitEnabled = next,
                statusBanner = if (next) "360° 3D Drone Orbit engaged" else "360° Drone Orbit paused"
            )
        }
    }

    fun toggleHudVisor() {
        _uiState.update { it.copy(hudVisorEnabled = !it.hudVisorEnabled) }
    }

    fun toggleMeasureMode() {
        val next = !_uiState.value.measureModeEnabled
        _uiState.update {
            it.copy(
                measureModeEnabled = next,
                measureStart = if (next) it.measureStart else null,
                measureEnd = if (next) it.measureEnd else null,
                statusBanner = if (next) "Geodesic Ruler active: tap any 2 points on Earth to measure distance & flight time" else null
            )
        }
    }

    fun recordMeasurePoint(lat: Double, lon: Double) {
        _uiState.update { cur ->
            if (cur.measureStart == null || cur.measureEnd != null) {
                cur.copy(
                    measureStart = lat to lon,
                    measureEnd = null,
                    statusBanner = "Point A set (%.3f°, %.3f°) — tap Point B on the globe".format(Locale.US, lat, lon)
                )
            } else {
                val a = cur.measureStart
                val km = OrbitalAndGlobeMath.geodesicDistanceKm(a.first, a.second, lat, lon)
                val brg = OrbitalAndGlobeMath.initialBearingDeg(a.first, a.second, lat, lon)
                val jetHrs = km / 880.0
                cur.copy(
                    measureEnd = lat to lon,
                    statusBanner = "Distance: %,.1f km (%,.1f NM) · Bearing %.0f° · Jet %.1fh".format(
                        Locale.US, km, km * 0.539957, brg, jetHrs
                    )
                )
            }
        }
    }

    fun clearMeasurePoints() {
        _uiState.update { it.copy(measureStart = null, measureEnd = null, measureModeEnabled = false) }
    }

    // ── Globe & Layer Controls ───────────────────────────────────────────────

    fun setUseNative3dEngine(useNative: Boolean) {
        _uiState.update {
            it.copy(
                useNative3dEngine = useNative,
                globeReady = useNative,
                globeError = null,
                cesiumVersion = if (useNative) "Native-3D-GPU-2.0" else "1.147.0"
            )
        }
    }

    fun setBasemap(basemap: GlobeBasemap) {
        _uiState.update { it.copy(basemap = basemap) }
        sendCommand(buildJsonObject {
            put("type", "SET_BASEMAP")
            put("basemap", basemap.name)
        }.toString())
    }

    fun setProjection(projection: GlobeProjection) {
        _uiState.update { it.copy(projection = projection) }
        sendCommand(buildJsonObject {
            put("type", "SET_PROJECTION")
            put("projection", projection.name)
        }.toString())
    }

    fun setStylePreset(preset: GlobeStylePreset) {
        _uiState.update { it.copy(stylePreset = preset) }
        sendCommand(buildJsonObject {
            put("type", "SET_STYLE_PRESET")
            put("stylePreset", preset.name)
        }.toString())
    }

    fun setPerformanceMode(mode: GraphicsPerformanceMode) {
        _uiState.update { it.copy(performanceMode = mode) }
        sendCommand(buildJsonObject {
            put("type", "SET_PERFORMANCE_MODE")
            put("mode", mode.name)
        }.toString())
    }

    fun setTerrainEnabled(enabled: Boolean) {
        _uiState.update { it.copy(terrain3dEnabled = enabled) }
        sendCommand(buildJsonObject {
            put("type", "SET_TERRAIN")
            put("enabled", enabled)
        }.toString())
    }

    fun updateSceneFlags(
        borders: Boolean = _uiState.value.bordersEnabled,
        atmosphere: Boolean = _uiState.value.atmosphereEnabled,
        dayNight: Boolean = _uiState.value.dayNightLightingEnabled,
        labels: Boolean = _uiState.value.labelsEnabled,
        clustering: Boolean = _uiState.value.clusteringEnabled,
        heatmap: Boolean = _uiState.value.heatmapEnabled
    ) {
        _uiState.update {
            it.copy(
                bordersEnabled = borders,
                atmosphereEnabled = atmosphere,
                dayNightLightingEnabled = dayNight,
                labelsEnabled = labels,
                clusteringEnabled = clustering,
                heatmapEnabled = heatmap
            )
        }
        sendCommand(buildJsonObject {
            put("type", "SET_SCENE_FLAGS")
            put("borders", borders)
            put("atmosphere", atmosphere)
            put("dayNightLighting", dayNight)
            put("labels", labels)
            put("clustering", clustering)
            put("heatmap", heatmap)
        }.toString())
    }

    fun setCloudsOverlay(enabled: Boolean) {
        _uiState.update { it.copy(cloudsOverlayEnabled = enabled) }
        sendCommand(buildJsonObject {
            put("type", "SET_CLOUDS")
            put("enabled", enabled)
            put("opacity", 0.55)
        }.toString())
    }

    fun setRadarOverlay(enabled: Boolean) {
        _uiState.update { it.copy(radarOverlayEnabled = enabled) }
        viewModelScope.launch {
            if (enabled && repository.rainViewerFrames.value.isEmpty()) {
                repository.syncSingleProvider("wm.weather.rainviewer", force = true)
            }
            pushRadarStateToGlobe(enabled)
        }
    }

    fun updateHistoricalSplit(
        enabled: Boolean = _uiState.value.historicalSplit.enabled,
        leftDateIso: String = _uiState.value.historicalSplit.leftDateIso,
        rightDateIso: String = _uiState.value.historicalSplit.rightDateIso,
        splitPosition: Float = _uiState.value.historicalSplit.splitPosition
    ) {
        val next = HistoricalImagerySplitState(
            enabled = enabled,
            leftDateIso = leftDateIso,
            rightDateIso = rightDateIso,
            splitPosition = splitPosition.coerceIn(0.1f, 0.9f)
        )
        _uiState.update { it.copy(historicalSplit = next) }
        sendCommand(buildJsonObject {
            put("type", "SET_HISTORICAL_SPLIT")
            put("enabled", next.enabled)
            put("leftDateIso", next.leftDateIso)
            put("rightDateIso", next.rightDateIso)
            put("splitPosition", next.splitPosition.toDouble())
        }.toString())
    }

    fun updateLayerState(
        category: WorldIntelCategory,
        visible: Boolean? = null,
        opacity: Float? = null
    ) {
        val cur = _uiState.value.layerStates[category] ?: LayerUiState(category)
        val updated = cur.copy(
            visible = visible ?: cur.visible,
            opacity = (opacity ?: cur.opacity).coerceIn(0.15f, 1.0f)
        )
        _uiState.update { s -> s.copy(layerStates = s.layerStates + (category to updated)) }
        sendCommand(buildJsonObject {
            put("type", "SET_LAYER_STATE")
            put("category", category.name)
            put("visible", updated.visible)
            put("opacity", updated.opacity.toDouble())
        }.toString())
        recomputeDerivedIntelligence(_uiState.value.allEntities)
    }

    fun setTrailsEnabled(enabled: Boolean) {
        _uiState.update { it.copy(trailsEnabled = enabled) }
        viewModelScope.launch { refreshTrailsOnGlobe() }
    }

    fun setMinSeverityFilter(severity: WorldSeverity) {
        _uiState.update { it.copy(minSeverityFilter = severity) }
        recomputeDerivedIntelligence(_uiState.value.allEntities)
    }

    // ── Camera & Geographic Search ───────────────────────────────────────────

    fun cameraAction(action: String) {
        if (action == "TILT_3D") {
            if (_uiState.value.projection != GlobeProjection.GLOBE_3D) {
                setProjection(GlobeProjection.GLOBE_3D)
            }
            _nativeGlobeCommands.tryEmit(NativeGlobeCommand.CameraAction("TILT_3D"))
            sendCommand(buildJsonObject {
                put("type", "CAMERA_ACTION")
                put("action", "TILT_3D")
            }.toString())
            return
        }
        val normalized = if (action == "HOME") "HOME_GLOBE" else action
        _nativeGlobeCommands.tryEmit(NativeGlobeCommand.CameraAction(normalized))
        sendCommand(buildJsonObject {
            put("type", "CAMERA_ACTION")
            put("action", normalized)
        }.toString())
    }

    fun flyTo(lat: Double, lon: Double, altitudeMeters: Double = 350_000.0, pitchDeg: Double = -58.0) {
        _nativeGlobeCommands.tryEmit(NativeGlobeCommand.FlyTo(lat, lon, altitudeMeters, pitchDeg))
        sendCommand(buildJsonObject {
            put("type", "FLY_TO")
            put("lat", lat)
            put("lon", lon)
            put("altitudeMeters", altitudeMeters)
            put("pitchDeg", pitchDeg)
        }.toString())
    }

    fun focusEntity(entity: WorldEntity) {
        _uiState.update { it.copy(selectedEntity = entity) }
        val targetAlt = when (entity.category) {
            WorldIntelCategory.SPACE -> (entity.altitudeMeters ?: 420_000.0).coerceAtLeast(1_200_000.0)
            WorldIntelCategory.AVIATION, WorldIntelCategory.MARITIME -> 85_000.0
            else -> 220_000.0
        }
        flyTo(entity.latitude, entity.longitude, targetAlt, -55.0)
        if (entity.category == WorldIntelCategory.SPACE && !entity.tleLine1.isNullOrBlank()) {
            _nativeGlobeCommands.tryEmit(NativeGlobeCommand.SelectSatellite(entity.id))
            sendCommand(buildJsonObject {
                put("type", "SELECT_SATELLITE_ORBIT")
                put("entityId", entity.id)
            }.toString())
            computePassesForSelectedSatellite(entity)
        }
    }

    fun computePassesForSelectedSatellite(entity: WorldEntity) {
        val vp = _uiState.value.viewport
        viewModelScope.launch(Dispatchers.Default) {
            val tle = OrbitalAndGlobeMath.parseTle(entity.id, entity.title, entity.tleLine1, entity.tleLine2)
            if (tle != null) {
                val nativePasses = OrbitalAndGlobeMath.predictPasses(
                    tle = tle,
                    observerLatDeg = vp.centerLat,
                    observerLonDeg = vp.centerLon,
                    hoursAhead = 24
                )
                _uiState.update { it.copy(satellitePasses = nativePasses) }
            }
        }
        sendCommand(buildJsonObject {
            put("type", "COMPUTE_SATELLITE_PASSES")
            put("entityId", entity.id)
            put("lat", vp.centerLat)
            put("lon", vp.centerLon)
            put("hoursAhead", 24)
        }.toString())
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun submitGeographicSearch() {
        val q = _uiState.value.searchQuery.trim()
        if (q.length < 2) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true) }
            val results = repository.searchLocations(q)
            _uiState.update { it.copy(isSearching = false, searchResults = results) }
            results.firstOrNull()?.let { top ->
                selectSearchResult(top)
            }
        }
    }

    fun selectSearchResult(result: GeocodeSearchResult) {
        val nearest = BaselineReferenceStore.countryByIso(result.countryCode)
            ?: BaselineReferenceStore.nearestCountry(result.latitude, result.longitude)
        val alt = (result.boundingBoxKm * 1_450.0).coerceIn(2_400.0, 2_800_000.0)
        val targetVp = WorldViewport(
            west = (result.longitude - 8.0).coerceIn(-180.0, 180.0),
            south = (result.latitude - 8.0).coerceIn(-85.0, 85.0),
            east = (result.longitude + 8.0).coerceIn(-180.0, 180.0),
            north = (result.latitude + 8.0).coerceIn(-85.0, 85.0),
            centerLat = result.latitude,
            centerLon = result.longitude,
            cameraAltitudeMeters = alt.toLong()
        )
        _uiState.update {
            it.copy(
                searchResults = emptyList(),
                searchQuery = result.displayName,
                viewport = targetVp,
                activeCountryIso = nearest?.iso2 ?: it.activeCountryIso,
                activeCountryName = nearest?.name ?: it.activeCountryName
            )
        }
        flyTo(result.latitude, result.longitude, alt, -54.0)
        viewModelScope.launch {
            repository.syncViewportLiveProviders(targetVp, force = true)
            refreshTrailsOnGlobe()
        }
    }

    // ── Digital Twin, AI Analyst, Simulation Lab, Timeline, Watchtower ───────

    fun refreshDigitalTwin(compareCountryIso: String = _uiState.value.compareCountryIso) {
        viewModelScope.launch(Dispatchers.Default) {
            val vp = _uiState.value.viewport
            val nearest = BaselineReferenceStore.nearestCountry(vp.centerLat, vp.centerLon)
            val livePop = nearest?.iso2?.let { repository.fetchLiveWorldBankPopulation(it) }
            val elev = _uiState.value.clickedLocation?.elevationMeters ?: vp.groundElevationMeters
            val snapshot = DigitalTwinEngine.buildRegionalTwin(
                latitude = vp.centerLat,
                longitude = vp.centerLon,
                elevationMeters = elev,
                radiusKm = 450.0,
                allEntities = _uiState.value.allEntities,
                compareCountryIso = compareCountryIso,
                liveWorldBankPop = livePop
            )
            _uiState.update { it.copy(digitalTwin = snapshot, compareCountryIso = compareCountryIso) }
        }
    }

    fun askAiAnalyst(query: String) {
        viewModelScope.launch(Dispatchers.Default) {
            val s = _uiState.value
            val response = WorldMonitorAiAnalyst.analyze(
                userQuery = query,
                allEntities = s.allEntities,
                correlations = s.correlations,
                providerStates = s.providerStates,
                selectedEntity = s.selectedEntity,
                focusLat = s.viewport.centerLat,
                focusLon = s.viewport.centerLon
            )
            _uiState.update { it.copy(aiResponse = response, activeTab = WorldMonitorDeckTab.AI_ANALYST, deckExpanded = true) }
        }
    }

    fun runSimulation(kind: SimulationKind, parameterValue: Double, bearingDeg: Double = 285.0) {
        viewModelScope.launch(Dispatchers.Default) {
            val vp = _uiState.value.viewport
            val result = SimulationLabEngine.runScenario(
                kind = kind,
                centerLat = vp.centerLat,
                centerLon = vp.centerLon,
                intensityOrRadiusParameter = parameterValue,
                windBearingDeg = bearingDeg,
                referenceEntities = _uiState.value.allEntities
            )
            _uiState.update { it.copy(simulation = result) }
            sendCommand(buildJsonObject {
                put("type", "SET_SIMULATION_OVERLAY")
                put("simulation", json.parseToJsonElement(json.encodeToString(result)))
            }.toString())
            flyTo(result.centerLat, result.centerLon, 950_000.0, -55.0)
        }
    }

    fun clearSimulation() {
        val cleared = _uiState.value.simulation.copy(active = false, rings = emptyList(), waypoints = emptyList())
        _uiState.update { it.copy(simulation = cleared) }
        sendCommand(buildJsonObject {
            put("type", "SET_SIMULATION_OVERLAY")
            put("simulation", JsonNull)
        }.toString())
    }

    fun setTimelineWindow(window: WorldTimelineWindow) {
        _uiState.update { it.copy(timelineWindow = window, timelineProgress = 1.0f) }
        recomputeDerivedIntelligence(_uiState.value.allEntities)
    }

    fun setTimelineProgress(progress: Float) {
        _uiState.update { it.copy(timelineProgress = progress.coerceIn(0.05f, 1.0f)) }
        recomputeDerivedIntelligence(_uiState.value.allEntities)
    }

    fun toggleTimelinePlayback() {
        val nextPlaying = !_uiState.value.timelinePlaying
        _uiState.update { it.copy(timelinePlaying = nextPlaying) }
        timelinePlayJob?.cancel()
        if (nextPlaying) {
            timelinePlayJob = viewModelScope.launch {
                var p = if (_uiState.value.timelineProgress >= 0.98f) 0.10f else _uiState.value.timelineProgress
                while (isActive && _uiState.value.timelinePlaying) {
                    delay(900L)
                    p += 0.08f
                    if (p >= 1.0f) {
                        setTimelineProgress(1.0f)
                        _uiState.update { it.copy(timelinePlaying = false) }
                        break
                    } else {
                        setTimelineProgress(p)
                    }
                }
            }
        }
    }

    fun createWatchRegionAtViewport(
        name: String,
        radiusKm: Double,
        minSeverity: WorldSeverity,
        categories: Set<WorldIntelCategory>
    ) {
        viewModelScope.launch {
            val vp = _uiState.value.viewport
            val region = WatchtowerRegion(
                id = "watch:${UUID.randomUUID().toString().take(8)}",
                name = name.trim().ifBlank {
                    "Sector %.2f°, %.2f°".format(Locale.US, vp.centerLat, vp.centerLon)
                },
                centerLat = vp.centerLat,
                centerLon = vp.centerLon,
                radiusKm = radiusKm.coerceIn(15.0, 2_500.0),
                categories = categories,
                minSeverity = minSeverity,
                enabled = true,
                createdAtEpochMs = System.currentTimeMillis()
            )
            val matched = repository.addWatchRegion(region)
            _uiState.update {
                it.copy(
                    statusBanner = "Watchtower '${region.name}' armed · $matched active alert(s) detected in sector"
                )
            }
        }
    }

    fun createWatchRegionAtCoordinates(
        name: String,
        lat: Double,
        lon: Double,
        radiusKm: Double = 280.0,
        minSeverity: WorldSeverity = WorldSeverity.MEDIUM
    ) {
        viewModelScope.launch {
            val region = WatchtowerRegion(
                id = "watch:${UUID.randomUUID().toString().take(8)}",
                name = name.trim().ifBlank { "Sector %.2f°, %.2f°".format(Locale.US, lat, lon) },
                centerLat = lat,
                centerLon = lon,
                radiusKm = radiusKm.coerceIn(15.0, 2_500.0),
                categories = WorldIntelCategory.entries.toSet(),
                minSeverity = minSeverity,
                enabled = true,
                createdAtEpochMs = System.currentTimeMillis()
            )
            val matched = repository.addWatchRegion(region)
            flyTo(lat, lon, (radiusKm * 3_400.0).coerceIn(85_000.0, 2_200_000.0), -52.0)
            _uiState.update {
                it.copy(
                    statusBanner = "Watchtower '${region.name}' armed · $matched active alert(s) inside ${radiusKm.toInt()} km"
                )
            }
        }
    }

    fun scanWatchtowerAlertsNow() {
        viewModelScope.launch {
            val matched = repository.evaluateWatchtowerNow()
            _uiState.update {
                it.copy(statusBanner = "Watchtower scan complete · $matched new alert(s) matched active geofences")
            }
        }
    }

    fun deleteWatchRegion(id: String) {
        viewModelScope.launch { repository.deleteWatchRegion(id) }
    }

    fun clearWatchAlerts() {
        viewModelScope.launch { repository.clearAlerts() }
    }

    fun setProviderEnabled(providerId: String, enabled: Boolean) {
        viewModelScope.launch {
            repository.setProviderEnabled(providerId, enabled)
            if (enabled) syncSingleProvider(providerId)
        }
    }

    fun saveApiCredential(slot: String, secret: String) {
        repository.saveCredential(slot, secret)
        _uiState.update {
            it.copy(
                hasOpenSkyKey = repository.hasCredential("wm.cred.opensky"),
                hasFirmsKey = repository.hasCredential("wm.cred.nasa_firms"),
                hasAisStreamKey = repository.hasCredential("wm.cred.aisstream"),
                statusBanner = if (secret.isBlank()) "Removed credential $slot" else "Encrypted credential saved in Android Keystore ($slot)"
            )
        }
        WorldMonitorProviderRegistry.providers.firstOrNull { it.credentialSlot == slot }?.let { spec ->
            if (secret.isNotBlank() && spec.implemented) syncSingleProvider(spec.providerId)
        }
    }

    fun exportVisibleGeoJson(): String =
        CzmlAndGeoJsonBuilder.buildGeoJsonFeatureCollection(_uiState.value.filteredEntities)

    fun exportVisibleCzml(): String =
        CzmlAndGeoJsonBuilder.buildCzmlDocument(_uiState.value.filteredEntities)

    // ── Internal State Synchronization with CesiumJS ─────────────────────────

    private fun recomputeDerivedIntelligence(entities: List<WorldEntity>) {
        viewModelScope.launch(Dispatchers.Default) {
            val state = _uiState.value
            val now = System.currentTimeMillis()
            val counts = entities.groupingBy { it.category }.eachCount()

            val windowHorizon = state.timelineWindow.durationMs
            val cutoffStart = if (windowHorizon != null) now - windowHorizon else 0L
            val cutoffEnd = if (windowHorizon != null) {
                cutoffStart + (windowHorizon * state.timelineProgress).toLong()
            } else {
                Long.MAX_VALUE
            }

            val visible = entities.filter { e ->
                val cfg = state.layerStates[e.category]
                val catOk = cfg?.visible != false
                val sevOk = e.severity.rank >= state.minSeverityFilter.rank
                val timeOk = when (state.timelineWindow) {
                    WorldTimelineWindow.ALL_CACHED -> true
                    WorldTimelineWindow.LIVE_1H -> {
                        e.freshness == FreshnessClass.LIVE ||
                            e.freshness == FreshnessClass.NEAR_REAL_TIME ||
                            e.freshness == FreshnessClass.HISTORICAL ||
                            e.freshness == FreshnessClass.ESTIMATED ||
                            e.retrievedAtEpochMs >= now - 6 * 3_600_000L ||
                            e.eventTimeEpochMs >= cutoffStart
                    }
                    else -> {
                        e.freshness == FreshnessClass.HISTORICAL ||
                            e.freshness == FreshnessClass.ESTIMATED ||
                            (e.eventTimeEpochMs in cutoffStart..(cutoffEnd + 72 * 3_600_000L))
                    }
                }
                catOk && sevOk && timeOk
            }

            val corrs = EventCorrelationEngine.correlate(visible)

            // The Global News desk reads the news layer on its own terms: newest first, and it
            // reports which outlets are silent instead of showing an unexplained empty list.
            val news = entities
                .filter { it.category == WorldIntelCategory.NEWS }
                .sortedByDescending { it.eventTimeEpochMs }
                .distinctBy { it.title.lowercase(Locale.US).take(80) }
            val newsProviderCounts = news.groupingBy { it.providerId }.eachCount()
            val silentNews = WorldMonitorProviderRegistry.providers
                .filter { it.category == WorldIntelCategory.NEWS }
                .filter { spec -> (state.providerStates.firstOrNull { it.providerId == spec.providerId }?.enabled ?: spec.defaultEnabled) }
                .filter { spec -> (newsProviderCounts[spec.providerId] ?: 0) == 0 }
                .map { spec ->
                    val runtime = state.providerStates.firstOrNull { it.providerId == spec.providerId }
                    val status = runtime?.status?.name ?: "NOT SYNCED"
                    val detail = runtime?.errorMessage?.takeIf { it.isNotBlank() } ?: "no cached headline"
                    "${spec.name}: $status — $detail"
                }

            _uiState.update {
                it.copy(
                    allEntities = entities,
                    filteredEntities = visible,
                    categoryCounts = counts,
                    newsEntities = news,
                    newsSourceIssues = silentNews,
                    correlations = corrs
                )
            }
            pushEntitiesAndCorrelationsToGlobe(visible, corrs)
        }
    }

    private suspend fun pushFullStateToGlobe() {
        val s = _uiState.value
        setBasemap(s.basemap)
        setProjection(s.projection)
        setStylePreset(s.stylePreset)
        setPerformanceMode(s.performanceMode)
        setTerrainEnabled(s.terrain3dEnabled)
        updateSceneFlags(
            borders = s.bordersEnabled,
            atmosphere = s.atmosphereEnabled,
            dayNight = s.dayNightLightingEnabled,
            labels = s.labelsEnabled,
            clustering = s.clusteringEnabled,
            heatmap = s.heatmapEnabled
        )
        s.layerStates.values.forEach { cfg ->
            sendCommand(buildJsonObject {
                put("type", "SET_LAYER_STATE")
                put("category", cfg.category.name)
                put("visible", cfg.visible)
                put("opacity", cfg.opacity.toDouble())
            }.toString())
        }
        pushEntitiesAndCorrelationsToGlobe(s.filteredEntities, s.correlations)
        pushWatchRegionsToGlobe(s.watchRegions)
        refreshTrailsOnGlobe()
    }

    private fun pushEntitiesAndCorrelationsToGlobe(
        entities: List<WorldEntity>,
        correlations: List<CorrelatedIncident>
    ) {
        if (!_uiState.value.globeReady) return
        val capped = entities.take(2_500)
        sendCommand(buildJsonObject {
            put("type", "UPDATE_ENTITIES")
            put("entities", json.parseToJsonElement(json.encodeToString(capped)))
        }.toString())

        sendCommand(buildJsonObject {
            put("type", "UPDATE_CORRELATIONS")
            put("incidents", json.parseToJsonElement(json.encodeToString(correlations)))
        }.toString())
    }

    private suspend fun refreshTrailsOnGlobe() {
        if (!_uiState.value.trailsEnabled) {
            _uiState.update { it.copy(entityTrails = emptyList()) }
            if (_uiState.value.globeReady) sendCommand("""{"type":"UPDATE_TRAILS","trails":[]}""")
            return
        }
        val activeCats = _uiState.value.layerStates.filterValues { it.visible }.keys
        val trails = repository.loadRecentTrails(activeCats)
        _uiState.update { it.copy(entityTrails = trails) }
        if (!_uiState.value.globeReady) return
        sendCommand(buildJsonObject {
            put("type", "UPDATE_TRAILS")
            put("trails", json.parseToJsonElement(json.encodeToString(trails)))
        }.toString())
    }

    private fun pushWatchRegionsToGlobe(regions: List<WatchtowerRegion>) {
        if (!_uiState.value.globeReady) return
        sendCommand(buildJsonObject {
            put("type", "UPDATE_WATCH_REGIONS")
            put("regions", json.parseToJsonElement(json.encodeToString(regions)))
        }.toString())
    }

    private fun pushBuildingsToGlobe(buildings: List<OsmBuilding3D>) {
        if (!_uiState.value.globeReady) return
        sendCommand(buildJsonObject {
            put("type", "UPDATE_BUILDINGS_3D")
            put("buildings", json.parseToJsonElement(json.encodeToString(buildings)))
        }.toString())
    }

    private fun pushRadarStateToGlobe(enabled: Boolean) {
        val frames = repository.rainViewerFrames.value
        sendCommand(buildJsonObject {
            put("type", "SET_RADAR")
            put("enabled", enabled)
            put("animating", true)
            put("opacity", 0.58)
            put("frames", json.parseToJsonElement(json.encodeToString(frames)))
        }.toString())
    }

    private fun sendCommand(commandJsonObject: String) {
        val jsExpr = WorldMonitorJsBridge.buildJsDispatchExpression(commandJsonObject)
        _jsCommands.tryEmit(jsExpr)
    }
}
