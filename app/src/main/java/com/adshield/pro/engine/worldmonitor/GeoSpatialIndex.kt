package com.adshield.pro.engine.worldmonitor

import com.adshield.pro.domain.worldmonitor.WorldEntity
import com.adshield.pro.domain.worldmonitor.WorldViewport
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure-JVM spherical geodesy, Base-32 Geohash spatial indexing, antimeridian-safe viewport
 * clipping, and geospatial density binning.
 */
object GeoSpatialIndex {

    const val EARTH_MEAN_RADIUS_KM = 6_371.0088
    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"

    data class DensityBin(
        val cellId: String,
        val centerLat: Double,
        val centerLon: Double,
        val count: Int,
        val weightedScore: Double
    )

    fun isValidCoordinate(lat: Double, lon: Double): Boolean =
        lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0

    fun normalizeLon(lon: Double): Double {
        if (!lon.isFinite()) return 0.0
        var wrapped = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        if (wrapped == -180.0 && lon > 0) wrapped = 180.0
        return wrapped
    }

    /** Great-circle distance in kilometers using the Haversine formula. */
    fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)
        val a = sin(dLat / 2.0).pow(2) +
            cos(rLat1) * cos(rLat2) * sin(dLon / 2.0).pow(2)
        val clamped = a.coerceIn(0.0, 1.0)
        return 2.0 * EARTH_MEAN_RADIUS_KM * asin(sqrt(clamped))
    }

    /** Initial forward azimuth in degrees [0, 360). */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLambda = Math.toRadians(lon2 - lon1)
        val y = sin(dLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Computes destination (lat, lon) given start point, initial bearing, and great-circle distance. */
    fun destinationPoint(
        latDeg: Double,
        lonDeg: Double,
        bearingDeg: Double,
        distanceKm: Double
    ): Pair<Double, Double> {
        val angular = distanceKm / EARTH_MEAN_RADIUS_KM
        val brng = Math.toRadians(bearingDeg)
        val phi1 = Math.toRadians(latDeg)
        val lambda1 = Math.toRadians(lonDeg)

        val sinPhi2 = sin(phi1) * cos(angular) + cos(phi1) * sin(angular) * cos(brng)
        val phi2 = asin(sinPhi2.coerceIn(-1.0, 1.0))
        val y = sin(brng) * sin(angular) * cos(phi1)
        val x = cos(angular) - sin(phi1) * sin(phi2)
        val lambda2 = lambda1 + atan2(y, x)
        return Math.toDegrees(phi2).coerceIn(-90.0, 90.0) to normalizeLon(Math.toDegrees(lambda2))
    }

    /** Standard Niemeyer Base-32 Geohash spatial key for fast SQLite prefix indexing. */
    fun encodeGeohash(lat: Double, lon: Double, precision: Int = 5): String {
        val safePrecision = precision.coerceIn(1, 9)
        var minLat = -90.0
        var maxLat = 90.0
        var minLon = -180.0
        var maxLon = 180.0
        val clampedLat = lat.coerceIn(-90.0, 90.0)
        val clampedLon = normalizeLon(lon)

        val out = StringBuilder(safePrecision)
        var isEvenBit = true
        var bit = 0
        var chIndex = 0
        val masks = intArrayOf(16, 8, 4, 2, 1)

        while (out.length < safePrecision) {
            if (isEvenBit) {
                val mid = (minLon + maxLon) * 0.5
                if (clampedLon >= mid) {
                    chIndex = chIndex or masks[bit]
                    minLon = mid
                } else {
                    maxLon = mid
                }
            } else {
                val mid = (minLat + maxLat) * 0.5
                if (clampedLat >= mid) {
                    chIndex = chIndex or masks[bit]
                    minLat = mid
                } else {
                    maxLat = mid
                }
            }
            isEvenBit = !isEvenBit
            if (bit < 4) {
                bit++
            } else {
                out.append(BASE32[chIndex])
                bit = 0
                chIndex = 0
            }
        }
        return out.toString()
    }

    /** Antimeridian-safe bounding-box test (`west > east` crosses the 180th meridian). */
    fun inViewport(lat: Double, lon: Double, viewport: WorldViewport, padDeg: Double = 2.0): Boolean {
        if (!isValidCoordinate(lat, lon)) return false
        val south = (viewport.south - padDeg).coerceAtLeast(-90.0)
        val north = (viewport.north + padDeg).coerceAtMost(90.0)
        if (lat < south || lat > north) return false

        val west = viewport.west - padDeg
        val east = viewport.east + padDeg
        if (east - west >= 355.0) return true
        val normLon = normalizeLon(lon)
        val normWest = normalizeLon(west)
        val normEast = normalizeLon(east)
        return if (normWest <= normEast) {
            normLon in normWest..normEast
        } else {
            // Crosses the International Date Line
            normLon >= normWest || normLon <= normEast
        }
    }

    /** Aggregates point entities into geospatial density cells for flight/maritime/hazard density maps. */
    fun computeDensityBins(entities: List<WorldEntity>, cellSizeDeg: Double = 5.0): List<DensityBin> {
        val step = cellSizeDeg.coerceIn(0.5, 30.0)
        data class Acc(var count: Int = 0, var score: Double = 0.0, val cLat: Double, val cLon: Double)
        val buckets = LinkedHashMap<String, Acc>()
        for (entity in entities) {
            if (!isValidCoordinate(entity.latitude, entity.longitude)) continue
            val latIdx = floor((entity.latitude + 90.0) / step).toInt()
            val lonIdx = floor((normalizeLon(entity.longitude) + 180.0) / step).toInt()
            val key = "$latIdx:$lonIdx"
            val cLat = (-90.0 + (latIdx + 0.5) * step).coerceIn(-89.5, 89.5)
            val cLon = (-180.0 + (lonIdx + 0.5) * step).coerceIn(-179.5, 179.5)
            val acc = buckets.getOrPut(key) { Acc(cLat = cLat, cLon = cLon) }
            acc.count += 1
            acc.score += (entity.severity.rank + 1).toDouble()
        }
        return buckets.entries
            .map { (k, v) -> DensityBin(k, v.cLat, v.cLon, v.count, v.score) }
            .sortedByDescending { it.weightedScore }
    }
}
