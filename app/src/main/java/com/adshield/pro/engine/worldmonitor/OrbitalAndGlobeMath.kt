package com.adshield.pro.engine.worldmonitor

import com.adshield.pro.domain.worldmonitor.GlobeProjection
import com.adshield.pro.domain.worldmonitor.SatellitePassPrediction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure-Kotlin 3D Spherical Camera Math, Solar Ephemeris, NORAD TLE J2 Orbital Propagator,
 * Topocentric Satellite Pass Solver, and TopoJSON Vector Arc Decoder for the Native GPU 3D Globe.
 */
object OrbitalAndGlobeMath {

    const val EARTH_RADIUS_KM = 6_378.137
    const val EARTH_RADIUS_METERS = 6_371_000.0
    private const val DEG_TO_RAD = PI / 180.0
    private const val RAD_TO_DEG = 180.0 / PI
    private const val MU_EARTH_KM3_S2 = 398_600.4418
    private const val J2_EARTH = 1.08262668e-3

    data class ScreenPoint3D(
        val x: Float,
        val y: Float,
        val depth: Float,
        val visible: Boolean
    )

    data class LimbCircle(
        val centerX: Float,
        val centerY: Float,
        val radiusPx: Float
    )

    data class SatelliteStateVector(
        val latitude: Double,
        val longitude: Double,
        val altitudeMeters: Double,
        val speedKnots: Double
    )

    data class ParsedTleElements(
        val entityId: String,
        val title: String,
        val epochMs: Long,
        val inclinationRad: Double,
        val raan0Rad: Double,
        val eccentricity: Double,
        val argPerigee0Rad: Double,
        val meanAnomaly0Rad: Double,
        val meanMotionRadPerSec: Double,
        val semiMajorAxisKm: Double,
        val raanDotRadPerSec: Double,
        val argPerigeeDotRadPerSec: Double,
        val meanAnomalyDotRadPerSec: Double
    )

    fun normalizeLon(lonDeg: Double): Double {
        var lon = ((lonDeg + 180.0) % 360.0)
        if (lon < 0.0) lon += 360.0
        return lon - 180.0
    }

    /**
     * Continuous 64-bit globe radius from deep space (`34,000 km`) all the way down to
     * street/building level (`350 m`) with zero clamping cap.
     */
    fun globeRadiusPxDouble(viewportWidth: Float, viewportHeight: Float, cameraAltitudeMeters: Double): Double {
        val base = min(viewportWidth, viewportHeight).toDouble() * 0.43
        val altClamped = cameraAltitudeMeters.coerceIn(350.0, 34_000_000.0)
        val zoomFactor = if (altClamped >= 1_500_000.0) {
            (13_800_000.0 / altClamped).pow(0.76)
        } else {
            // Linear inverse-altitude scale below 1,500 km so 1 km altitude = true street scale!
            val anchor = (13_800_000.0 / 1_500_000.0).pow(0.76)
            anchor * (1_500_000.0 / altClamped)
        }
        return (base * zoomFactor).coerceIn(base * 0.58, base * 38_000.0)
    }

    fun globeRadiusPx(viewportWidth: Float, viewportHeight: Float, cameraAltitudeMeters: Double): Float =
        globeRadiusPxDouble(viewportWidth, viewportHeight, cameraAltitudeMeters).toFloat()

    /** Maps camera altitude in meters to Web Mercator slippy tile zoom level `z` (`2..17`). */
    fun altitudeToTileZoom(cameraAltitudeMeters: Double): Int {
        val alt = cameraAltitudeMeters.coerceIn(350.0, 34_000_000.0)
        // At 13,800 km -> z=2; every halving of altitude adds ~1 zoom level up to z=17 at 350 m
        val z = (2.2 + kotlin.math.ln(13_800_000.0 / alt) / kotlin.math.ln(2.0)).roundToInt()
        return z.coerceIn(2, 17)
    }

    /** Converts `(latDeg, lonDeg)` at zoom `z` to slippy tile `(tileX, tileY)`. */
    fun latLonToTileXY(latDeg: Double, lonDeg: Double, zoom: Int): Pair<Int, Int> {
        val n = 1 shl zoom.coerceIn(0, 20)
        val clampedLat = latDeg.coerceIn(-85.05112878, 85.05112878)
        val latRad = clampedLat * DEG_TO_RAD
        val x = (((normalizeLon(lonDeg) + 180.0) / 360.0) * n).toInt().coerceIn(0, n - 1)
        val y = ((1.0 - kotlin.math.ln(kotlin.math.tan(latRad) + 1.0 / cos(latRad)) / PI) * 0.5 * n)
            .toInt()
            .coerceIn(0, n - 1)
        return x to y
    }

    /** Returns `[westLon, southLat, eastLon, northLat]` bounds of slippy tile `(z, x, y)`. */
    fun tileBoundsLatLon(zoom: Int, tileX: Int, tileY: Int): DoubleArray {
        val n = (1 shl zoom.coerceIn(0, 20)).toDouble()
        val west = (tileX / n) * 360.0 - 180.0
        val east = ((tileX + 1) / n) * 360.0 - 180.0
        val northRad = kotlin.math.atan(kotlin.math.sinh(PI * (1.0 - 2.0 * tileY / n)))
        val southRad = kotlin.math.atan(kotlin.math.sinh(PI * (1.0 - 2.0 * (tileY + 1) / n)))
        return doubleArrayOf(west, southRad * RAD_TO_DEG, east, northRad * RAD_TO_DEG)
    }

    /** Geodesic great-circle distance in kilometers between two coordinates. */
    fun geodesicDistanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = (lat2 - lat1) * DEG_TO_RAD
        val dLon = normalizeLon(lon2 - lon1) * DEG_TO_RAD
        val p1 = lat1 * DEG_TO_RAD
        val p2 = lat2 * DEG_TO_RAD
        val a = sin(dLat * 0.5).pow(2.0) + cos(p1) * cos(p2) * sin(dLon * 0.5).pow(2.0)
        return 2.0 * EARTH_RADIUS_KM * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Initial forward compass bearing (`0..360°`) from `(lat1, lon1)` to `(lat2, lon2)`. */
    fun initialBearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = lat1 * DEG_TO_RAD
        val p2 = lat2 * DEG_TO_RAD
        val dLon = normalizeLon(lon2 - lon1) * DEG_TO_RAD
        val y = sin(dLon) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dLon)
        return ((atan2(y, x) * RAD_TO_DEG) + 360.0) % 360.0
    }

    /** Dead-reckons `(lat, lon)` along `headingDeg` by `distanceKm` on the spherical Earth. */
    fun projectAlongBearing(latDeg: Double, lonDeg: Double, headingDeg: Double, distanceKm: Double): Pair<Double, Double> {
        if (abs(distanceKm) < 1e-4) return latDeg to lonDeg
        val angDist = distanceKm / EARTH_RADIUS_KM
        val brng = headingDeg * DEG_TO_RAD
        val lat1 = latDeg * DEG_TO_RAD
        val lon1 = lonDeg * DEG_TO_RAD

        val sinLat2 = sin(lat1) * cos(angDist) + cos(lat1) * sin(angDist) * cos(brng)
        val lat2 = asin(sinLat2.coerceIn(-1.0, 1.0))
        val y = sin(brng) * sin(angDist) * cos(lat1)
        val x = cos(angDist) - sin(lat1) * sin(lat2)
        val lon2 = lon1 + atan2(y, x)
        return (lat2 * RAD_TO_DEG).coerceIn(-89.5, 89.5) to normalizeLon(lon2 * RAD_TO_DEG)
    }

    /**
     * Exact silhouette circle of the tilted 3D Earth on screen:
     * Under tilted orthographic projection `ux^2 + (uy + sin(tilt))^2 <= 1`,
     * the limb is a circle of radius `R` centered at `(cx, cy + R * sin(tilt))`.
     */
    fun computeLimbCircle(
        cx: Float,
        cy: Float,
        radiusPx: Float,
        pitchDeg: Double
    ): LimbCircle {
        val tiltRad = (90.0 + pitchDeg.coerceIn(-90.0, -22.0)) * DEG_TO_RAD
        val shiftY = (radiusPx * sin(tiltRad)).toFloat()
        return LimbCircle(
            centerX = cx,
            centerY = cy + shiftY,
            radiusPx = radiusPx
        )
    }

    /**
     * Projects geographic `(latDeg, lonDeg, altitudeMeters)` to 3D screen space in
     * `GLOBE_3D`, `COLUMBUS_25D`, or `MAP_2D` mode.
     */
    fun projectToScreen(
        latDeg: Double,
        lonDeg: Double,
        altitudeMeters: Double,
        camLatDeg: Double,
        camLonDeg: Double,
        pitchDeg: Double,
        headingDeg: Double,
        projection: GlobeProjection,
        cx: Float,
        cy: Float,
        radiusPx: Float
    ): ScreenPoint3D {
        if (projection == GlobeProjection.MAP_2D || projection == GlobeProjection.COLUMBUS_25D) {
            val dLon = normalizeLon(lonDeg - camLonDeg)
            val dLat = latDeg - camLatDeg
            val degScale = radiusPx / 62.0f
            val hRad = (-headingDeg * DEG_TO_RAD)
            val rx = (dLon * cos(hRad) - dLat * sin(hRad)).toFloat()
            val ry = (dLon * sin(hRad) + dLat * cos(hRad)).toFloat()
            val perspTilt = if (projection == GlobeProjection.COLUMBUS_25D) 0.76f else 1.0f
            val altLift = if (projection == GlobeProjection.COLUMBUS_25D) {
                (max(0.0, altitudeMeters) / 95_000.0).toFloat().coerceAtMost(48f)
            } else 0f
            return ScreenPoint3D(
                x = cx + rx * degScale,
                y = cy - ry * degScale * perspTilt - altLift,
                depth = 1f,
                visible = abs(dLon) <= 180.0
            )
        }

        val phi = latDeg * DEG_TO_RAD
        val phi0 = camLatDeg * DEG_TO_RAD
        val dLon = (lonDeg - camLonDeg) * DEG_TO_RAD

        // Visual orbital altitude scale so LEO satellites (400 km) visibly float above Earth's limb
        val altNorm = if (altitudeMeters > 1_000.0) {
            val km = altitudeMeters / 1_000.0
            min(0.55, (km / EARTH_RADIUS_KM) * 1.45)
        } else {
            max(0.0, altitudeMeters) / EARTH_RADIUS_METERS
        }
        val s = 1.0 + altNorm

        val cosPhi = cos(phi)
        val sinPhi = sin(phi)
        val cosPhi0 = cos(phi0)
        val sinPhi0 = sin(phi0)
        val cosDLon = cos(dLon)

        val ex = s * cosPhi * sin(dLon)
        val ey = s * (cosPhi0 * sinPhi - sinPhi0 * cosPhi * cosDLon)
        val ez = s * (sinPhi0 * sinPhi + cosPhi0 * cosPhi * cosDLon)

        val hRad = -headingDeg * DEG_TO_RAD
        val cosH = cos(hRad)
        val sinH = sin(hRad)
        val hx = ex * cosH - ey * sinH
        val hy = ex * sinH + ey * cosH
        val hz = ez

        val tiltRad = (90.0 + pitchDeg.coerceIn(-90.0, -22.0)) * DEG_TO_RAD
        val cosT = cos(tiltRad)
        val sinT = sin(tiltRad)

        val ux = hx
        val uy = hy * cosT + (hz - 1.0) * sinT
        val w = -hy * sinT + (hz - 1.0) * cosT

        // Surface visibility test: ez > 0 on unit sphere, or elevated satellite in front of limb circle
        val unitEz = ez / s
        val rawScreenX = cx.toDouble() + radiusPx.toDouble() * ux
        val rawScreenY = cy.toDouble() - radiusPx.toDouble() * uy
        val screenX = rawScreenX.coerceIn(-24_000.0, 24_000.0).toFloat()
        val screenY = rawScreenY.coerceIn(-24_000.0, 24_000.0).toFloat()
        val limbCenterY = cy.toDouble() + radiusPx.toDouble() * sinT
        val outsideLimb = hypot(rawScreenX - cx.toDouble(), rawScreenY - limbCenterY) >= radiusPx.toDouble() * 0.992
        val inRasterBounds = abs(rawScreenX) < 22_000.0 && abs(rawScreenY) < 22_000.0
        val visible = inRasterBounds && ((unitEz > 0.015) || (altNorm > 0.01 && (unitEz > -0.22 && outsideLimb)))

        return ScreenPoint3D(
            x = screenX,
            y = screenY,
            depth = (1.0 + w).toFloat(),
            visible = visible
        )
    }

    /**
     * Exact closed-form inverse ray-cast from screen pixel `(px, py)` onto the tilted/rotated 3D Earth.
     * Returns `(latDeg, lonDeg)` or `null` if the tap missed the Earth's limb into deep space.
     */
    fun unprojectFromScreen(
        px: Float,
        py: Float,
        camLatDeg: Double,
        camLonDeg: Double,
        pitchDeg: Double,
        headingDeg: Double,
        projection: GlobeProjection,
        cx: Float,
        cy: Float,
        radiusPx: Float
    ): Pair<Double, Double>? {
        if (radiusPx <= 1f) return null
        if (projection == GlobeProjection.MAP_2D || projection == GlobeProjection.COLUMBUS_25D) {
            val degScale = radiusPx / 62.0f
            val perspTilt = if (projection == GlobeProjection.COLUMBUS_25D) 0.76f else 1.0f
            val rx = (px - cx) / degScale
            val ry = (cy - py) / (degScale * perspTilt)
            val hRad = headingDeg * DEG_TO_RAD
            val dLon = rx * cos(hRad) - ry * sin(hRad)
            val dLat = rx * sin(hRad) + ry * cos(hRad)
            val lat = (camLatDeg + dLat).coerceIn(-89.5, 89.5)
            val lon = normalizeLon(camLonDeg + dLon)
            return lat to lon
        }

        val ux = ((px - cx) / radiusPx).toDouble()
        val uy = ((cy - py) / radiusPx).toDouble()
        val tiltRad = (90.0 + pitchDeg.coerceIn(-90.0, -22.0)) * DEG_TO_RAD
        val ct = cos(tiltRad)
        val st = sin(tiltRad)

        val disc = ct * ct - (ux * ux + uy * uy + 2.0 * uy * st)
        if (disc < 0.0) return null

        val w = -ct + sqrt(disc)
        val hx = ux
        val hy = uy * ct - w * st
        val hz = 1.0 + uy * st + w * ct

        val hRad = headingDeg * DEG_TO_RAD
        val ex = hx * cos(hRad) - hy * sin(hRad)
        val ey = hx * sin(hRad) + hy * cos(hRad)
        val ez = hz

        val phi0 = camLatDeg * DEG_TO_RAD
        val sinPhi = (ez * sin(phi0) + ey * cos(phi0)).coerceIn(-1.0, 1.0)
        val lat = asin(sinPhi) * RAD_TO_DEG
        val lon = normalizeLon(
            camLonDeg + atan2(ex, ez * cos(phi0) - ey * sin(phi0)) * RAD_TO_DEG
        )
        return lat.coerceIn(-89.9, 89.9) to lon
    }

    /** Computes current UTC sub-solar `(latDeg, lonDeg)` for realistic 3D day/night illumination. */
    fun computeSubSolarPoint(epochMs: Long = System.currentTimeMillis()): Pair<Double, Double> {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = epochMs }
        val dayOfYear = cal.get(Calendar.DAY_OF_YEAR)
        val utcHours = cal.get(Calendar.HOUR_OF_DAY) +
            cal.get(Calendar.MINUTE) / 60.0 +
            cal.get(Calendar.SECOND) / 3600.0
        val declDeg = -23.44 * cos(2.0 * PI * (dayOfYear + 10.0) / 365.25)
        val subSolarLon = normalizeLon((12.0 - utcHours) * 15.0)
        return declDeg to subSolarLon
    }

    /** Parses standard 2-line NORAD TLE orbital elements for pure-Kotlin J2 propagation. */
    fun parseTle(entityId: String, title: String, line1: String?, line2: String?): ParsedTleElements? {
        val l1 = line1?.trim() ?: return null
        val l2 = line2?.trim() ?: return null
        if (l1.length < 60 || l2.length < 63) return null
        return runCatching {
            val yr2 = l1.substring(18, 20).trim().toInt()
            val fullYear = if (yr2 < 57) 2000 + yr2 else 1900 + yr2
            val epochDay = l1.substring(20, 32).trim().toDouble()
            val jan1Cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                clear()
                set(fullYear, Calendar.JANUARY, 1, 0, 0, 0)
            }
            val epochMs = jan1Cal.timeInMillis + ((epochDay - 1.0) * 86_400_000.0).toLong()

            val incRad = l2.substring(8, 16).trim().toDouble() * DEG_TO_RAD
            val raan0Rad = l2.substring(17, 25).trim().toDouble() * DEG_TO_RAD
            val ecc = ("0." + l2.substring(26, 33).trim()).toDouble().coerceIn(0.0, 0.95)
            val argp0Rad = l2.substring(34, 42).trim().toDouble() * DEG_TO_RAD
            val m0Rad = l2.substring(43, 51).trim().toDouble() * DEG_TO_RAD
            val revPerDay = l2.substring(52, 63).trim().toDouble().coerceIn(0.05, 25.0)

            val n0 = revPerDay * 2.0 * PI / 86_400.0
            val aKm = (MU_EARTH_KM3_S2 / (n0 * n0)).pow(1.0 / 3.0)
            val pKm = aKm * (1.0 - ecc * ecc)
            val j2Factor = 1.5 * J2_EARTH * (EARTH_RADIUS_KM / pKm).pow(2.0) * n0
            val cosInc = cos(incRad)

            ParsedTleElements(
                entityId = entityId,
                title = title,
                epochMs = epochMs,
                inclinationRad = incRad,
                raan0Rad = raan0Rad,
                eccentricity = ecc,
                argPerigee0Rad = argp0Rad,
                meanAnomaly0Rad = m0Rad,
                meanMotionRadPerSec = n0,
                semiMajorAxisKm = aKm,
                raanDotRadPerSec = -j2Factor * cosInc,
                argPerigeeDotRadPerSec = 0.5 * j2Factor * (5.0 * cosInc * cosInc - 1.0),
                meanAnomalyDotRadPerSec = n0 + 0.5 * j2Factor * sqrt(1.0 - ecc * ecc) * (3.0 * cosInc * cosInc - 1.0)
            )
        }.getOrNull()
    }

    /** Propagates parsed TLE to `targetEpochMs` returning geodetic `(lat, lon, altMeters, speedKnots)`. */
    fun propagateTle(tle: ParsedTleElements, targetEpochMs: Long): SatelliteStateVector {
        val dtSec = (targetEpochMs - tle.epochMs) / 1000.0
        val raan = tle.raan0Rad + tle.raanDotRadPerSec * dtSec
        val argp = tle.argPerigee0Rad + tle.argPerigeeDotRadPerSec * dtSec
        var meanAnom = (tle.meanAnomaly0Rad + tle.meanAnomalyDotRadPerSec * dtSec) % (2.0 * PI)
        if (meanAnom < 0.0) meanAnom += 2.0 * PI

        val ecc = tle.eccentricity
        var eAnom = meanAnom
        repeat(6) {
            eAnom -= (eAnom - ecc * sin(eAnom) - meanAnom) / (1.0 - ecc * cos(eAnom))
        }

        val oneMinusE2 = max(1e-8, 1.0 - ecc * ecc)
        val denom = 1.0 - ecc * cos(eAnom)
        val trueAnom = atan2(sqrt(oneMinusE2) * sin(eAnom) / denom, (cos(eAnom) - ecc) / denom)
        val rKm = tle.semiMajorAxisKm * denom
        val u = argp + trueAnom

        val cosRaan = cos(raan)
        val sinRaan = sin(raan)
        val cosU = cos(u)
        val sinU = sin(u)
        val cosInc = cos(tle.inclinationRad)
        val sinInc = sin(tle.inclinationRad)

        val xEci = rKm * (cosRaan * cosU - sinRaan * sinU * cosInc)
        val yEci = rKm * (sinRaan * cosU + cosRaan * sinU * cosInc)
        val zEci = rKm * (sinU * sinInc)

        val jd = targetEpochMs / 86_400_000.0 + 2_440_587.5
        val tCent = (jd - 2_451_545.0) / 36_525.0
        var gmstDeg = (280.46061837 + 360.98564736629 * (jd - 2_451_545.0) + 0.000387933 * tCent * tCent) % 360.0
        if (gmstDeg < 0.0) gmstDeg += 360.0
        val gmst = gmstDeg * DEG_TO_RAD

        val xEcf = xEci * cos(gmst) + yEci * sin(gmst)
        val yEcf = -xEci * sin(gmst) + yEci * cos(gmst)
        val zEcf = zEci

        val lonDeg = atan2(yEcf, xEcf) * RAD_TO_DEG
        val hyp = hypot(xEcf, yEcf)
        val latDeg = (atan2(zEcf, hyp * (1.0 - 0.00669438)) * RAD_TO_DEG).coerceIn(-89.9, 89.9)
        val localEarthRadiusKm = EARTH_RADIUS_KM * (1.0 - 0.0033528 * sin(latDeg * DEG_TO_RAD).pow(2.0))
        val altKm = max(90.0, sqrt(xEcf * xEcf + yEcf * yEcf + zEcf * zEcf) - localEarthRadiusKm)
        val speedKmS = sqrt(MU_EARTH_KM3_S2 * max(0.0, 2.0 / rKm - 1.0 / tle.semiMajorAxisKm))

        return SatelliteStateVector(
            latitude = latDeg,
            longitude = normalizeLon(lonDeg),
            altitudeMeters = altKm * 1000.0,
            speedKnots = speedKmS * 1_943.844
        )
    }

    /** Generates a full orbital revolution around `nowMs` (`[-48 min .. +48 min]`). */
    fun buildOrbitTrack(tle: ParsedTleElements, nowMs: Long, stepMinutes: Int = 2): List<SatelliteStateVector> {
        val pts = ArrayList<SatelliteStateVector>(50)
        var minOffset = -48
        while (minOffset <= 48) {
            pts.add(propagateTle(tle, nowMs + minOffset * 60_000L))
            minOffset += stepMinutes
        }
        return pts
    }

    /** Solves upcoming topocentric observer passes above 10° elevation for the next `hoursAhead` hours. */
    fun predictPasses(
        tle: ParsedTleElements,
        observerLatDeg: Double,
        observerLonDeg: Double,
        nowMs: Long = System.currentTimeMillis(),
        hoursAhead: Int = 24
    ): List<SatellitePassPrediction> {
        val results = ArrayList<SatellitePassPrediction>(8)
        val obsLat = observerLatDeg * DEG_TO_RAD
        val obsLon = observerLonDeg * DEG_TO_RAD
        val cosObsLat = cos(obsLat)
        val sinObsLat = sin(obsLat)
        val cosObsLon = cos(obsLon)
        val sinObsLon = sin(obsLon)
        val obsX = EARTH_RADIUS_KM * cosObsLat * cosObsLon
        val obsY = EARTH_RADIUS_KM * cosObsLat * sinObsLon
        val obsZ = EARTH_RADIUS_KM * sinObsLat

        val endMs = nowMs + hoursAhead.coerceIn(1, 48) * 3_600_000L
        val stepMs = 30_000L

        var inPass = false
        var riseMs = 0L
        var setMs = 0L
        var culmMs = 0L
        var maxElDeg = 0.0
        var riseAzDeg = 0.0
        var setAzDeg = 0.0

        var t = nowMs
        while (t <= endMs) {
            val sv = propagateTle(tle, t)
            val rSat = EARTH_RADIUS_KM + sv.altitudeMeters / 1000.0
            val sLat = sv.latitude * DEG_TO_RAD
            val sLon = sv.longitude * DEG_TO_RAD
            val sx = rSat * cos(sLat) * cos(sLon)
            val sy = rSat * cos(sLat) * sin(sLon)
            val sz = rSat * sin(sLat)

            val rx = sx - obsX
            val ry = sy - obsY
            val rz = sz - obsZ

            // Topocentric South-East-Zenith (SEZ) -> East, North, Up
            val east = -sinObsLon * rx + cosObsLon * ry
            val north = -sinObsLat * cosObsLon * rx - sinObsLat * sinObsLon * ry + cosObsLat * rz
            val up = cosObsLat * cosObsLon * rx + cosObsLat * sinObsLon * ry + sinObsLat * rz
            val range = sqrt(east * east + north * north + up * up).coerceAtLeast(1e-3)

            val elDeg = asin((up / range).coerceIn(-1.0, 1.0)) * RAD_TO_DEG
            val azDeg = ((atan2(east, north) * RAD_TO_DEG) + 360.0) % 360.0

            if (elDeg >= 10.0) {
                if (!inPass) {
                    inPass = true
                    riseMs = t
                    setMs = t
                    culmMs = t
                    maxElDeg = elDeg
                    riseAzDeg = azDeg
                    setAzDeg = azDeg
                } else {
                    setMs = t
                    setAzDeg = azDeg
                    if (elDeg > maxElDeg) {
                        maxElDeg = elDeg
                        culmMs = t
                    }
                }
            } else if (inPass) {
                results.add(
                    SatellitePassPrediction(
                        entityId = tle.entityId,
                        name = tle.title,
                        observerLat = observerLatDeg,
                        observerLon = observerLonDeg,
                        riseEpochMs = riseMs,
                        culminationEpochMs = culmMs,
                        setEpochMs = setMs,
                        maxElevationDeg = (maxElDeg * 10.0).roundToInt() / 10.0,
                        riseAzimuthDeg = riseAzDeg.roundToInt().toDouble(),
                        setAzimuthDeg = setAzDeg.roundToInt().toDouble(),
                        durationSeconds = max(30, ((setMs - riseMs) / 1000L).toInt())
                    )
                )
                inPass = false
                if (results.size >= 8) break
            }
            t += stepMs
        }
        return results
    }

    /**
     * Decodes a TopoJSON topology (e.g., `countries-110m.json`) into deduplicated
     * `[lon0, lat0, lon1, lat1, ...]` `FloatArray` coastline and international border arcs.
     */
    fun decodeTopoJsonArcs(rawJson: String): List<FloatArray> {
        val json = Json { ignoreUnknownKeys = true }
        val root = json.parseToJsonElement(rawJson).jsonObject
        val transform = root["transform"]?.jsonObject ?: return emptyList()
        val scaleArr = transform["scale"]?.jsonArray ?: return emptyList()
        val transArr = transform["translate"]?.jsonArray ?: return emptyList()
        val sx = scaleArr.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return emptyList()
        val sy = scaleArr.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return emptyList()
        val tx = transArr.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return emptyList()
        val ty = transArr.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return emptyList()

        val arcsJson = root["arcs"]?.jsonArray ?: return emptyList()
        val decoded = ArrayList<FloatArray>(arcsJson.size)

        for (arcEl in arcsJson) {
            val pts = arcEl.jsonArray
            if (pts.size < 2) continue
            val coords = FloatArray(pts.size * 2)
            var qx = 0L
            var qy = 0L
            var validCount = 0
            for (ptEl in pts) {
                val pair = ptEl.jsonArray
                val dx = pair.getOrNull(0)?.jsonPrimitive?.intOrNull ?: continue
                val dy = pair.getOrNull(1)?.jsonPrimitive?.intOrNull ?: continue
                qx += dx
                qy += dy
                val lon = (qx * sx + tx).toFloat()
                val lat = (qy * sy + ty).toFloat().coerceIn(-89.9f, 89.9f)
                coords[validCount * 2] = lon
                coords[validCount * 2 + 1] = lat
                validCount++
            }
            if (validCount >= 2) {
                decoded.add(if (validCount == pts.size) coords else coords.copyOf(validCount * 2))
            }
        }
        return decoded
    }
}
