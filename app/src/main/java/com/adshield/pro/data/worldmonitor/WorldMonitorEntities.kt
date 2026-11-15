package com.adshield.pro.data.worldmonitor

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.adshield.pro.domain.worldmonitor.EntityTrailPoint
import com.adshield.pro.domain.worldmonitor.FreshnessClass
import com.adshield.pro.domain.worldmonitor.GeoAccuracy
import com.adshield.pro.domain.worldmonitor.ProviderSyncStatus
import com.adshield.pro.domain.worldmonitor.VerificationStatus
import com.adshield.pro.domain.worldmonitor.WatchtowerAlert
import com.adshield.pro.domain.worldmonitor.WatchtowerRegion
import com.adshield.pro.domain.worldmonitor.WorldEntity
import com.adshield.pro.domain.worldmonitor.WorldIntelCategory
import com.adshield.pro.domain.worldmonitor.WorldProviderRuntimeState
import com.adshield.pro.domain.worldmonitor.WorldSeverity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Entity(
    tableName = "world_entities",
    indices = [
        Index(value = ["category", "eventTimeEpochMs"]),
        Index(value = ["providerId"]),
        Index(value = ["latitude", "longitude"]),
        Index(value = ["geohash4"]),
        Index(value = ["expiresAtEpochMs"])
    ]
)
data class WorldEntityRecord(
    @PrimaryKey val id: String,
    val providerId: String,
    val providerName: String,
    val category: String,
    val subType: String,
    val title: String,
    val summary: String,
    val latitude: Double,
    val longitude: Double,
    val geohash4: String,
    val altitudeMeters: Double?,
    val headingDeg: Double?,
    val speedValue: Double?,
    val speedUnit: String?,
    val severity: String,
    val freshness: String,
    val geoAccuracy: String,
    val confidence: Int,
    val verificationStatus: String,
    val eventTimeEpochMs: Long,
    val retrievedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val sourceUrl: String,
    val attribution: String,
    val licenseInfo: String,
    val countryCode: String?,
    val geometryType: String,
    val geometryCoordsJson: String?,
    val metricsJson: String,
    val tleLine1: String?,
    val tleLine2: String?
) {
    fun toDomain(nowEpochMs: Long = System.currentTimeMillis()): WorldEntity {
        val parsedCategory = runCatching { WorldIntelCategory.valueOf(category) }
            .getOrDefault(WorldIntelCategory.DISASTER)
        val parsedSeverity = runCatching { WorldSeverity.valueOf(severity) }
            .getOrDefault(WorldSeverity.INFO)
        val baseFreshness = runCatching { FreshnessClass.valueOf(freshness) }
            .getOrDefault(FreshnessClass.NEAR_REAL_TIME)
        val ageMs = (nowEpochMs - eventTimeEpochMs).coerceAtLeast(0L)
        // Promote stale LIVE/NEAR_REAL_TIME items to DELAYED/HISTORICAL honestly when served from cache
        val effectiveFreshness = when {
            baseFreshness == FreshnessClass.ESTIMATED || baseFreshness == FreshnessClass.SIMULATED -> baseFreshness
            baseFreshness == FreshnessClass.HISTORICAL -> FreshnessClass.HISTORICAL
            ageMs > 24L * 3600_000L -> FreshnessClass.HISTORICAL
            ageMs > 60L * 60_000L -> FreshnessClass.DELAYED
            ageMs > 5L * 60_000L && baseFreshness == FreshnessClass.LIVE -> FreshnessClass.NEAR_REAL_TIME
            else -> baseFreshness
        }
        val metrics: Map<String, String> = runCatching {
            WorldRecordJson.decodeFromString<Map<String, String>>(metricsJson)
        }.getOrDefault(emptyMap())

        return WorldEntity(
            id = id,
            providerId = providerId,
            providerName = providerName,
            category = parsedCategory,
            subType = subType,
            title = title,
            summary = summary,
            latitude = latitude,
            longitude = longitude,
            altitudeMeters = altitudeMeters,
            headingDeg = headingDeg,
            speedValue = speedValue,
            speedUnit = speedUnit,
            severity = parsedSeverity,
            freshness = effectiveFreshness,
            geoAccuracy = runCatching { GeoAccuracy.valueOf(geoAccuracy) }.getOrDefault(GeoAccuracy.UNKNOWN),
            confidence = confidence,
            verificationStatus = runCatching { VerificationStatus.valueOf(verificationStatus) }
                .getOrDefault(VerificationStatus.OFFICIAL_AGENCY),
            eventTimeEpochMs = eventTimeEpochMs,
            retrievedAtEpochMs = retrievedAtEpochMs,
            expiresAtEpochMs = expiresAtEpochMs,
            sourceUrl = sourceUrl,
            attribution = attribution,
            licenseInfo = licenseInfo,
            countryCode = countryCode,
            geometryType = geometryType,
            geometryCoordsJson = geometryCoordsJson,
            metricsMap = metrics,
            tleLine1 = tleLine1,
            tleLine2 = tleLine2
        )
    }

    companion object {
        fun fromDomain(entity: WorldEntity, geohash4: String): WorldEntityRecord = WorldEntityRecord(
            id = entity.id,
            providerId = entity.providerId,
            providerName = entity.providerName,
            category = entity.category.name,
            subType = entity.subType,
            title = entity.title,
            summary = entity.summary,
            latitude = entity.latitude,
            longitude = entity.longitude,
            geohash4 = geohash4,
            altitudeMeters = entity.altitudeMeters,
            headingDeg = entity.headingDeg,
            speedValue = entity.speedValue,
            speedUnit = entity.speedUnit,
            severity = entity.severity.name,
            freshness = entity.freshness.name,
            geoAccuracy = entity.geoAccuracy.name,
            confidence = entity.confidence,
            verificationStatus = entity.verificationStatus.name,
            eventTimeEpochMs = entity.eventTimeEpochMs,
            retrievedAtEpochMs = entity.retrievedAtEpochMs,
            expiresAtEpochMs = entity.expiresAtEpochMs,
            sourceUrl = entity.sourceUrl,
            attribution = entity.attribution,
            licenseInfo = entity.licenseInfo,
            countryCode = entity.countryCode,
            geometryType = entity.geometryType,
            geometryCoordsJson = entity.geometryCoordsJson,
            metricsJson = WorldRecordJson.encodeToString(entity.metricsMap),
            tleLine1 = entity.tleLine1,
            tleLine2 = entity.tleLine2
        )
    }
}

@Entity(tableName = "world_provider_state")
data class WorldProviderStateRecord(
    @PrimaryKey val providerId: String,
    val status: String,
    val enabled: Boolean,
    val lastAttemptEpochMs: Long?,
    val lastSuccessEpochMs: Long?,
    val httpCode: Int?,
    val itemCount: Int,
    val latencyMs: Long?,
    val errorMessage: String?,
    val etag: String?,
    val lastModified: String?,
    val nextAllowedEpochMs: Long?,
    val consecutiveFailures: Int
) {
    fun toDomain(): WorldProviderRuntimeState = WorldProviderRuntimeState(
        providerId = providerId,
        status = runCatching { ProviderSyncStatus.valueOf(status) }.getOrDefault(ProviderSyncStatus.IDLE),
        enabled = enabled,
        lastAttemptEpochMs = lastAttemptEpochMs,
        lastSuccessEpochMs = lastSuccessEpochMs,
        httpCode = httpCode,
        itemCount = itemCount,
        latencyMs = latencyMs,
        errorMessage = errorMessage,
        etag = etag,
        lastModified = lastModified,
        nextAllowedEpochMs = nextAllowedEpochMs,
        consecutiveFailures = consecutiveFailures
    )

    companion object {
        fun fromDomain(state: WorldProviderRuntimeState): WorldProviderStateRecord = WorldProviderStateRecord(
            providerId = state.providerId,
            status = state.status.name,
            enabled = state.enabled,
            lastAttemptEpochMs = state.lastAttemptEpochMs,
            lastSuccessEpochMs = state.lastSuccessEpochMs,
            httpCode = state.httpCode,
            itemCount = state.itemCount,
            latencyMs = state.latencyMs,
            errorMessage = state.errorMessage,
            etag = state.etag,
            lastModified = state.lastModified,
            nextAllowedEpochMs = state.nextAllowedEpochMs,
            consecutiveFailures = state.consecutiveFailures
        )
    }
}

@Entity(
    tableName = "world_entity_trails",
    indices = [
        Index(value = ["entityId", "timestampEpochMs"]),
        Index(value = ["category"])
    ]
)
data class WorldEntityTrailPointRecord(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0L,
    val entityId: String,
    val category: String,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double,
    val timestampEpochMs: Long
) {
    fun toTrailPoint(): EntityTrailPoint = EntityTrailPoint(
        lat = latitude,
        lon = longitude,
        altM = altitudeMeters,
        timestampEpochMs = timestampEpochMs
    )
}

@Entity(
    tableName = "world_geocode_cache",
    indices = [Index(value = ["expiresAtEpochMs"])]
)
data class WorldGeocodeCacheRecord(
    @PrimaryKey val queryKey: String,
    val payloadJson: String,
    val fetchedAtEpochMs: Long,
    val expiresAtEpochMs: Long
)

@Entity(tableName = "watchtower_regions")
data class WatchtowerRegionRecord(
    @PrimaryKey val id: String,
    val name: String,
    val centerLat: Double,
    val centerLon: Double,
    val radiusKm: Double,
    val categoriesCsv: String,
    val minSeverity: String,
    val providerFilter: String?,
    val enabled: Boolean,
    val createdAtEpochMs: Long
) {
    fun toDomain(): WatchtowerRegion = WatchtowerRegion(
        id = id,
        name = name,
        centerLat = centerLat,
        centerLon = centerLon,
        radiusKm = radiusKm,
        categories = categoriesCsv.split(",")
            .mapNotNull { token -> runCatching { WorldIntelCategory.valueOf(token.trim()) }.getOrNull() }
            .toSet()
            .ifEmpty { WorldIntelCategory.entries.toSet() },
        minSeverity = runCatching { WorldSeverity.valueOf(minSeverity) }.getOrDefault(WorldSeverity.MEDIUM),
        providerFilter = providerFilter,
        enabled = enabled,
        createdAtEpochMs = createdAtEpochMs
    )

    companion object {
        fun fromDomain(region: WatchtowerRegion): WatchtowerRegionRecord = WatchtowerRegionRecord(
            id = region.id,
            name = region.name,
            centerLat = region.centerLat,
            centerLon = region.centerLon,
            radiusKm = region.radiusKm,
            categoriesCsv = region.categories.joinToString(",") { it.name },
            minSeverity = region.minSeverity.name,
            providerFilter = region.providerFilter,
            enabled = region.enabled,
            createdAtEpochMs = region.createdAtEpochMs
        )
    }
}

@Entity(
    tableName = "watchtower_alerts",
    indices = [
        Index(value = ["triggeredAtEpochMs"]),
        Index(value = ["regionId", "entityId"], unique = true)
    ]
)
data class WatchtowerAlertRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val regionId: String,
    val regionName: String,
    val entityId: String,
    val category: String,
    val severity: String,
    val title: String,
    val summary: String,
    val providerName: String,
    val sourceUrl: String,
    val distanceKm: Double,
    val triggeredAtEpochMs: Long
) {
    fun toDomain(): WatchtowerAlert = WatchtowerAlert(
        id = id,
        regionId = regionId,
        regionName = regionName,
        entityId = entityId,
        category = runCatching { WorldIntelCategory.valueOf(category) }.getOrDefault(WorldIntelCategory.DISASTER),
        severity = runCatching { WorldSeverity.valueOf(severity) }.getOrDefault(WorldSeverity.MEDIUM),
        title = title,
        summary = summary,
        providerName = providerName,
        sourceUrl = sourceUrl,
        distanceKm = distanceKm,
        triggeredAtEpochMs = triggeredAtEpochMs
    )
}

internal val WorldRecordJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
