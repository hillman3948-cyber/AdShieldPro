package com.adshield.pro.data.worldmonitor

import android.content.Context
import com.adshield.pro.data.repository.ProviderCredentialStore
import com.adshield.pro.domain.worldmonitor.EntityTrail
import com.adshield.pro.domain.worldmonitor.GeocodeSearchResult
import com.adshield.pro.domain.worldmonitor.OsmBuilding3D
import com.adshield.pro.domain.worldmonitor.ProviderAuthRequirement
import com.adshield.pro.domain.worldmonitor.ProviderSyncStatus
import com.adshield.pro.domain.worldmonitor.RainViewerFrame
import com.adshield.pro.domain.worldmonitor.WatchtowerAlert
import com.adshield.pro.domain.worldmonitor.WatchtowerRegion
import com.adshield.pro.domain.worldmonitor.WorldEntity
import com.adshield.pro.domain.worldmonitor.WorldIntelCategory
import com.adshield.pro.domain.worldmonitor.WorldProviderRuntimeState
import com.adshield.pro.domain.worldmonitor.WorldProviderSpec
import com.adshield.pro.domain.worldmonitor.WorldViewport
import com.adshield.pro.engine.worldmonitor.BaselineReferenceStore
import com.adshield.pro.engine.worldmonitor.GeoSpatialIndex
import com.adshield.pro.engine.worldmonitor.WatchtowerManager
import com.adshield.pro.engine.worldmonitor.WorldMonitorProviderRegistry
import com.adshield.pro.engine.worldmonitor.WorldMonitorSyncEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline-First Repository for OMNISHIELD X INFINITY — WORLD MONITOR 2035.
 */
@Singleton
class WorldMonitorRepository @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val dao: WorldMonitorDao,
    private val syncEngine: WorldMonitorSyncEngine,
    private val credentialStore: ProviderCredentialStore
) {
    private val syncSemaphore = Semaphore(4)

    private val _rainViewerFrames = MutableStateFlow<List<RainViewerFrame>>(emptyList())
    val rainViewerFrames: StateFlow<List<RainViewerFrame>> = _rainViewerFrames.asStateFlow()

    private val _buildings3D = MutableStateFlow<List<OsmBuilding3D>>(emptyList())
    val buildings3D: StateFlow<List<OsmBuilding3D>> = _buildings3D.asStateFlow()

    val entitiesFlow: Flow<List<WorldEntity>> = dao.observeEntities(4_000)
        .map { rows ->
            val now = System.currentTimeMillis()
            rows.map { it.toDomain(now) }
        }

    val providerStatesFlow: Flow<List<WorldProviderRuntimeState>> = dao.observeProviderStates()
        .map { rows ->
            val byId = rows.associateBy { it.providerId }
            WorldMonitorProviderRegistry.providers.map { spec ->
                byId[spec.providerId]?.toDomain() ?: defaultProviderState(spec)
            }
        }

    val watchRegionsFlow: Flow<List<WatchtowerRegion>> = dao.observeWatchRegions()
        .map { list -> list.map { it.toDomain() } }

    val watchAlertsFlow: Flow<List<WatchtowerAlert>> = dao.observeWatchAlerts(200)
        .map { list -> list.map { it.toDomain() } }

    suspend fun ensureBaselineSeeded() = withContext(Dispatchers.IO) {
        val state = dao.getProviderState("wm.infra.baseline")
        if (state == null || state.itemCount == 0 || state.etag != "bundled-v3") {
            val now = System.currentTimeMillis()
            val entities = BaselineReferenceStore.buildBaselineEntities(now, appContext)
            dao.upsertEntities(
                entities.map { e ->
                    WorldEntityRecord.fromDomain(e, GeoSpatialIndex.encodeGeohash(e.latitude, e.longitude, 4))
                }
            )
            dao.upsertProviderState(
                WorldProviderStateRecord(
                    providerId = "wm.infra.baseline",
                    status = ProviderSyncStatus.OK.name,
                    enabled = true,
                    lastAttemptEpochMs = now,
                    lastSuccessEpochMs = now,
                    httpCode = 200,
                    itemCount = entities.size,
                    latencyMs = 1L,
                    errorMessage = null,
                    etag = "bundled-v3",
                    lastModified = null,
                    nextAllowedEpochMs = null,
                    consecutiveFailures = 0
                )
            )
        }
    }

    suspend fun syncEnabledProviders(
        categories: Set<WorldIntelCategory> = WorldIntelCategory.entries.toSet(),
        viewport: WorldViewport? = null,
        force: Boolean = false,
        notifyWatchtower: Boolean = true
    ): Int = withContext(Dispatchers.IO) {
        ensureBaselineSeeded()
        val now = System.currentTimeMillis()
        dao.pruneExpiredEntities(now)
        dao.pruneOldTrailPoints(now - 12L * 3_600_000L)
        dao.pruneGeocodeCache(now)

        val storedStates = dao.getAllProviderStates().associateBy { it.providerId }
        val specs = WorldMonitorProviderRegistry.providers.filter { spec ->
            val enabledFlag = storedStates[spec.providerId]?.enabled ?: spec.defaultEnabled
            spec.category in categories &&
                spec.providerId != "wm.infra.baseline" &&
                spec.providerId != "wm.infra.overpass" &&
                enabledFlag
        }

        val outcomes = coroutineScope {
            specs.map { spec ->
                async {
                    syncSemaphore.withPermit {
                        val outcome = syncEngine.syncProvider(spec, viewport = viewport, force = force, nowEpochMs = now)
                        persistOutcome(spec, outcome, now)
                        outcome
                    }
                }
            }.awaitAll()
        }

        val freshEntities = outcomes.flatMap { it.entities }
        if (freshEntities.isNotEmpty()) {
            WatchtowerManager.evaluateAndPersist(
                context = appContext,
                dao = dao,
                candidates = freshEntities,
                notifyUser = notifyWatchtower
            )
        }
        outcomes.count { it.status == ProviderSyncStatus.OK }
    }

    suspend fun syncSingleProvider(
        providerId: String,
        viewport: WorldViewport? = null,
        force: Boolean = true
    ): WorldMonitorSyncEngine.ProviderFetchOutcome = withContext(Dispatchers.IO) {
        val spec = WorldMonitorProviderRegistry.find(providerId)
            ?: return@withContext WorldMonitorSyncEngine.ProviderFetchOutcome(
                providerId,
                ProviderSyncStatus.ERROR,
                errorMessage = "Unknown provider $providerId"
            )
        val now = System.currentTimeMillis()
        val outcome = syncEngine.syncProvider(spec, viewport = viewport, force = force, nowEpochMs = now)
        persistOutcome(spec, outcome, now)
        if (outcome.entities.isNotEmpty()) {
            WatchtowerManager.evaluateAndPersist(appContext, dao, outcome.entities, notifyUser = false)
        }
        outcome
    }

    suspend fun syncViewportLiveProviders(
        viewport: WorldViewport,
        force: Boolean = true
    ): Int = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        dao.pruneExpiredEntities(now)
        val storedStates = dao.getAllProviderStates().associateBy { it.providerId }
        val regionalIds = listOf(
            "wm.aviation.airplaneslive",
            "wm.aviation.opensky",
            "wm.space.iss",
            "wm.weather.openmeteo",
            "wm.weather.airquality",
            "wm.weather.marine",
            "wm.weather.waves",
            "wm.weather.forecast72h",
            "wm.cyber.ripestat",
            "wm.infra.wikipedia.geo"
        )
        val specs = regionalIds.mapNotNull { WorldMonitorProviderRegistry.find(it) }
            .filter { spec -> storedStates[spec.providerId]?.enabled ?: spec.defaultEnabled }

        val outcomes = coroutineScope {
            specs.map { spec ->
                async {
                    syncSemaphore.withPermit {
                        val outcome = syncEngine.syncProvider(spec, viewport = viewport, force = force, nowEpochMs = now)
                        persistOutcome(spec, outcome, now)
                        outcome
                    }
                }
            }.awaitAll()
        }
        val freshEntities = outcomes.flatMap { it.entities }
        if (freshEntities.isNotEmpty()) {
            WatchtowerManager.evaluateAndPersist(appContext, dao, freshEntities, notifyUser = false)
        }
        outcomes.count { it.status == ProviderSyncStatus.OK }
    }

    suspend fun syncBuildingsAndStreetInfra(viewport: WorldViewport): WorldMonitorSyncEngine.ProviderFetchOutcome =
        withContext(Dispatchers.IO) {
            coroutineScope {
                val overpassDef = async { syncSingleProvider("wm.infra.overpass", viewport = viewport, force = true) }
                val wikiDef = async { syncSingleProvider("wm.infra.wikipedia.geo", viewport = viewport, force = true) }
                val airDef = async { syncSingleProvider("wm.aviation.airplaneslive", viewport = viewport, force = true) }
                val ov = overpassDef.await()
                val wk = wikiDef.await()
                airDef.await()
                ov.copy(entities = ov.entities + wk.entities)
            }
        }

    private suspend fun persistOutcome(
        spec: WorldProviderSpec,
        outcome: WorldMonitorSyncEngine.ProviderFetchOutcome,
        nowEpochMs: Long
    ) {
        if (outcome.rainViewerFrames.isNotEmpty()) {
            _rainViewerFrames.value = outcome.rainViewerFrames
        }
        if (outcome.buildings3D.isNotEmpty()) {
            _buildings3D.value = outcome.buildings3D
        }
        if (outcome.entities.isNotEmpty()) {
            dao.upsertEntities(
                outcome.entities.map { e ->
                    WorldEntityRecord.fromDomain(e, GeoSpatialIndex.encodeGeohash(e.latitude, e.longitude, 4))
                }
            )

            if (spec.category == WorldIntelCategory.AVIATION ||
                spec.category == WorldIntelCategory.MARITIME ||
                spec.providerId == "wm.space.iss"
            ) {
                val trailRows = outcome.entities
                    .filter { it.speedValue != null || spec.providerId == "wm.space.iss" }
                    .take(220)
                    .map { e ->
                        WorldEntityTrailPointRecord(
                            entityId = e.id,
                            category = e.category.name,
                            latitude = e.latitude,
                            longitude = e.longitude,
                            altitudeMeters = e.altitudeMeters ?: 0.0,
                            timestampEpochMs = nowEpochMs
                        )
                    }
                if (trailRows.isNotEmpty()) {
                    dao.insertTrailPoints(trailRows)
                }
            }
        }
    }

    suspend fun loadRecentTrails(categories: Set<WorldIntelCategory>): List<EntityTrail> = withContext(Dispatchers.IO) {
        val since = System.currentTimeMillis() - 3L * 3_600_000L
        val catNames = categories.map { it.name }.toSet()
        val points = dao.getRecentTrailPoints(since).filter { it.category in catNames }
        val out = ArrayList<EntityTrail>()
        points.groupBy { it.entityId }.forEach { (entityId, rows) ->
            if (rows.size >= 2) {
                val parsedCat = runCatching { WorldIntelCategory.valueOf(rows.first().category) }
                    .getOrDefault(WorldIntelCategory.AVIATION)
                out += EntityTrail(
                    entityId = entityId,
                    category = parsedCat,
                    points = rows.sortedBy { it.timestampEpochMs }.map { it.toTrailPoint() }
                )
            }
        }
        out
    }

    suspend fun setProviderEnabled(providerId: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val existing = dao.getProviderState(providerId)
        if (existing != null) {
            dao.upsertProviderState(existing.copy(enabled = enabled))
        } else {
            val spec = WorldMonitorProviderRegistry.find(providerId) ?: return@withContext
            dao.upsertProviderState(
                WorldProviderStateRecord(
                    providerId = spec.providerId,
                    status = ProviderSyncStatus.IDLE.name,
                    enabled = enabled,
                    lastAttemptEpochMs = null,
                    lastSuccessEpochMs = null,
                    httpCode = null,
                    itemCount = 0,
                    latencyMs = null,
                    errorMessage = null,
                    etag = null,
                    lastModified = null,
                    nextAllowedEpochMs = null,
                    consecutiveFailures = 0
                )
            )
        }
    }

    fun hasCredential(slot: String): Boolean = !credentialStore.get(slot).isNullOrBlank()

    fun saveCredential(slot: String, value: String) {
        val clean = value.trim()
        if (clean.isEmpty()) credentialStore.remove(slot) else credentialStore.put(slot, clean)
    }

    suspend fun addWatchRegion(region: WatchtowerRegion): Int = withContext(Dispatchers.IO) {
        dao.upsertWatchRegion(WatchtowerRegionRecord.fromDomain(region))
        val now = System.currentTimeMillis()
        val cached = dao.getAllEntitiesSnapshot(2500).map { it.toDomain(now) }
        if (cached.isNotEmpty()) {
            WatchtowerManager.evaluateAndPersist(appContext, dao, cached, notifyUser = false).size
        } else 0
    }

    suspend fun evaluateWatchtowerNow(): Int = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cached = dao.getAllEntitiesSnapshot(2500).map { it.toDomain(now) }
        if (cached.isEmpty()) 0
        else WatchtowerManager.evaluateAndPersist(appContext, dao, cached, notifyUser = false).size
    }

    suspend fun deleteWatchRegion(id: String) = withContext(Dispatchers.IO) {
        dao.deleteWatchRegion(id)
    }

    suspend fun clearAlerts() = withContext(Dispatchers.IO) {
        dao.clearWatchAlerts()
    }

    suspend fun searchLocations(query: String): List<GeocodeSearchResult> =
        syncEngine.searchLocations(query)

    suspend fun lookupElevationAndPlace(lat: Double, lon: Double): Pair<Int?, String?> =
        syncEngine.lookupElevationAndPlace(lat, lon)

    suspend fun fetchLiveWorldBankPopulation(iso2: String): Pair<Long, Int>? =
        syncEngine.fetchLiveWorldBankPopulation(iso2)

    private fun defaultProviderState(spec: WorldProviderSpec): WorldProviderRuntimeState {
        val status = when {
            !spec.implemented -> ProviderSyncStatus.BACKEND_REQUIRED
            spec.credentialSlot != null &&
                spec.authRequirement == ProviderAuthRequirement.REQUIRED_USER_KEY &&
                !hasCredential(spec.credentialSlot) -> ProviderSyncStatus.NEEDS_API_KEY
            else -> ProviderSyncStatus.IDLE
        }
        return WorldProviderRuntimeState(
            providerId = spec.providerId,
            status = status,
            enabled = spec.defaultEnabled,
            errorMessage = spec.notes.ifBlank { null }
        )
    }
}
