package com.adshield.pro.engine.worldmonitor

import com.adshield.pro.domain.worldmonitor.EntityTrail
import com.adshield.pro.domain.worldmonitor.WorldEntity
import com.adshield.pro.domain.worldmonitor.WorldSeverity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Standards-compliant RFC 7946 GeoJSON and Cesium CZML 1.0 packet generator (Section 7).
 */
object CzmlAndGeoJsonBuilder {

    private fun isoUtc(epochMs: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(epochMs))
    }

    /** Builds a standard RFC 7946 GeoJSON FeatureCollection with provenance properties. */
    fun buildGeoJsonFeatureCollection(entities: List<WorldEntity>): String {
        val root = buildJsonObject {
            put("type", "FeatureCollection")
            put(
                "features",
                buildJsonArray {
                    for (entity in entities) {
                        if (!GeoSpatialIndex.isValidCoordinate(entity.latitude, entity.longitude)) continue
                        add(
                            buildJsonObject {
                                put("type", "Feature")
                                put("id", entity.id)
                                put(
                                    "geometry",
                                    buildJsonObject {
                                        put("type", "Point")
                                        put(
                                            "coordinates",
                                            buildJsonArray {
                                                add(JsonPrimitive(entity.longitude))
                                                add(JsonPrimitive(entity.latitude))
                                                entity.altitudeMeters?.let { add(JsonPrimitive(it)) }
                                            }
                                        )
                                    }
                                )
                                put(
                                    "properties",
                                    buildJsonObject {
                                        put("title", entity.title)
                                        put("summary", entity.summary)
                                        put("category", entity.category.name)
                                        put("subType", entity.subType)
                                        put("severity", entity.severity.name)
                                        put("providerId", entity.providerId)
                                        put("providerName", entity.providerName)
                                        put("freshness", entity.freshness.name)
                                        put("geoAccuracy", entity.geoAccuracy.name)
                                        put("confidence", entity.confidence)
                                        put("verificationStatus", entity.verificationStatus.name)
                                        put("eventTimeIso", isoUtc(entity.eventTimeEpochMs))
                                        put("retrievedAtIso", isoUtc(entity.retrievedAtEpochMs))
                                        put("sourceUrl", entity.sourceUrl)
                                        put("attribution", entity.attribution)
                                    }
                                )
                            }
                        )
                    }
                }
            )
        }
        return root.toString()
    }

    /** Builds a Cesium CZML 1.0 packet array for time-dynamic entities and movement trails. */
    fun buildCzmlDocument(
        entities: List<WorldEntity>,
        trails: List<EntityTrail> = emptyList(),
        nowEpochMs: Long = System.currentTimeMillis()
    ): String {
        val packets = ArrayList<JsonElement>(entities.size + trails.size + 1)

        // Packet 0: Required CZML document header
        packets += buildJsonObject {
            put("id", "document")
            put("name", "OMNISHIELD X World Monitor 2035")
            put("version", "1.0")
            put(
                "clock",
                buildJsonObject {
                    put("currentTime", isoUtc(nowEpochMs))
                    put("multiplier", 1)
                }
            )
        }

        for (entity in entities) {
            if (!GeoSpatialIndex.isValidCoordinate(entity.latitude, entity.longitude)) continue
            val rgba = severityRgba(entity.severity)
            packets += buildJsonObject {
                put("id", entity.id)
                put("name", entity.title)
                put("description", "${entity.summary} — Source: ${entity.providerName} (${entity.freshness.label})")
                put(
                    "position",
                    buildJsonObject {
                        put(
                            "cartographicDegrees",
                            buildJsonArray {
                                add(JsonPrimitive(entity.longitude))
                                add(JsonPrimitive(entity.latitude))
                                add(JsonPrimitive(entity.altitudeMeters ?: 0.0))
                            }
                        )
                    }
                )
                put(
                    "point",
                    buildJsonObject {
                        put("pixelSize", if (entity.severity == WorldSeverity.CRITICAL) 11 else 8)
                        put("color", buildJsonObject { put("rgba", JsonArray(rgba.map { JsonPrimitive(it) })) })
                    }
                )
            }
        }

        for (trail in trails) {
            if (trail.points.size < 2) continue
            val epoch0 = trail.points.first().timestampEpochMs
            packets += buildJsonObject {
                put("id", "${trail.entityId}::czml-path")
                put("name", "${trail.entityId} Track")
                put(
                    "polyline",
                    buildJsonObject {
                        put("width", 2)
                        put(
                            "positions",
                            buildJsonObject {
                                put(
                                    "cartographicDegrees",
                                    buildJsonArray {
                                        for (pt in trail.points) {
                                            add(JsonPrimitive(pt.lon))
                                            add(JsonPrimitive(pt.lat))
                                            add(JsonPrimitive(pt.altM))
                                        }
                                    }
                                )
                            }
                        )
                        put("epoch", isoUtc(epoch0))
                    }
                )
            }
        }

        return JsonArray(packets).toString()
    }

    private fun severityRgba(severity: WorldSeverity): List<Int> = when (severity) {
        WorldSeverity.CRITICAL -> listOf(239, 68, 68, 255)
        WorldSeverity.HIGH -> listOf(249, 115, 22, 255)
        WorldSeverity.MEDIUM -> listOf(245, 158, 11, 255)
        WorldSeverity.LOW -> listOf(34, 211, 238, 255)
        WorldSeverity.INFO -> listOf(56, 189, 248, 240)
    }
}
