package com.adshield.pro.engine.worldmonitor

import com.adshield.pro.domain.worldmonitor.CorrelatedIncident
import com.adshield.pro.domain.worldmonitor.CorrelationLink
import com.adshield.pro.domain.worldmonitor.FreshnessClass
import com.adshield.pro.domain.worldmonitor.WorldEntity
import com.adshield.pro.domain.worldmonitor.WorldIntelCategory
import com.adshield.pro.domain.worldmonitor.WorldSeverity
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Deterministic, evidence-backed Global Event Correlation Engine (Section 3).
 *
 * Rules of engagement:
 *  - Never links two items from the same providerId (self-correlation is tautological).
 *  - Never includes SIMULATED scenarios in real-world correlations.
 *  - Computes exact Haversine distance (km) and event-time delta (minutes).
 *  - Every output carries direct source links and an explicit non-causation caveat.
 */
object EventCorrelationEngine {

    private const val CAVEAT_SPATIO_TEMPORAL =
        "Spatio-temporal coincidence across independent feeds; physical causation requires domain verification."

    fun correlate(entities: List<WorldEntity>, maxResults: Int = 35): List<CorrelatedIncident> {
        val realEntities = entities.filter {
            it.freshness != FreshnessClass.SIMULATED &&
                GeoSpatialIndex.isValidCoordinate(it.latitude, it.longitude)
        }
        if (realEntities.size < 2) return emptyList()

        val incidents = ArrayList<CorrelatedIncident>()

        val disasters = realEntities.filter { it.category == WorldIntelCategory.DISASTER }
        val weather = realEntities.filter { it.category == WorldIntelCategory.WEATHER }
        val maritime = realEntities.filter { it.category == WorldIntelCategory.MARITIME }
        val cyber = realEntities.filter { it.category == WorldIntelCategory.CYBER }
        val news = realEntities.filter { it.category == WorldIntelCategory.NEWS }
        val infra = realEntities.filter { it.category == WorldIntelCategory.INFRASTRUCTURE }

        // 1. Earthquake <-> Tsunami / Independent Seismic / GDACS Corroboration
        val quakes = disasters.filter { it.subType.contains("Earthquake", ignoreCase = true) }
        val tsunamiOrGdacs = disasters.filter {
            it.subType.contains("Tsunami", ignoreCase = true) || it.providerId == "wm.disaster.gdacs" || it.providerId == "wm.disaster.emsc"
        }
        for (q in quakes) {
            for (other in tsunamiOrGdacs) {
                if (q.id == other.id || q.providerId == other.providerId) continue
                val distKm = GeoSpatialIndex.haversineKm(q.latitude, q.longitude, other.latitude, other.longitude)
                val dtMin = abs(q.eventTimeEpochMs - other.eventTimeEpochMs) / 60_000L
                if (distKm <= 350.0 && dtMin <= 360L) {
                    val isTsunami = q.subType.contains("Tsunami", true) || other.subType.contains("Tsunami", true)
                    val conf = (92 - (distKm / 12.0).roundToInt() - (dtMin / 30L).toInt()).coerceIn(60, 96)
                    incidents += CorrelatedIncident(
                        id = "corr:seismic:${q.id}:${other.id}",
                        ruleId = "RULE_SEISMIC_TSUNAMI_CORROBORATION",
                        ruleTitle = if (isTsunami) "Seismic Event & Tsunami Alert Coincidence" else "Multi-Agency Seismic Corroboration",
                        severity = maxSeverity(q.severity, other.severity),
                        confidence = conf,
                        distanceKm = round1(distKm),
                        timeDeltaMinutes = dtMin,
                        latitude = q.latitude,
                        longitude = q.longitude,
                        summary = "${q.title} (${q.providerName}) coincides within ${round1(distKm)} km and ${dtMin}m of ${other.title} (${other.providerName}).",
                        rationale = "Independent seismic/disaster agencies reported matching epicentral coordinates within 350 km and 6 hours.",
                        caveat = CAVEAT_SPATIO_TEMPORAL,
                        entityIds = listOf(q.id, other.id),
                        evidenceItems = listOf("${q.providerName}: ${q.title}", "${other.providerName}: ${other.title}"),
                        sourceLinks = listOf(q.sourceUrl, other.sourceUrl).distinct(),
                        links = listOf(CorrelationLink(q.latitude, q.longitude, other.latitude, other.longitude))
                    )
                }
            }
        }

        // 2. Wildfire <-> Meteorological Fire-Weather Conditions
        val fires = disasters.filter { it.subType.contains("Wildfire", true) || it.subType.contains("Fire", true) }
        for (fire in fires) {
            for (wx in weather) {
                val distKm = GeoSpatialIndex.haversineKm(fire.latitude, fire.longitude, wx.latitude, wx.longitude)
                if (distKm > 250.0) continue
                val windSpeed = wx.speedValue ?: 0.0
                val isDryOrWindy = windSpeed >= 20.0 || wx.summary.contains("Precip 0.0 mm")
                if (!isDryOrWindy) continue
                val dtMin = abs(fire.eventTimeEpochMs - wx.eventTimeEpochMs) / 60_000L
                val conf = (85 - (distKm / 10.0).roundToInt()).coerceIn(55, 90)
                incidents += CorrelatedIncident(
                    id = "corr:firewx:${fire.id}:${wx.id}",
                    ruleId = "RULE_WILDFIRE_METEOROLOGY",
                    ruleTitle = "Active Wildfire & Fire-Weather Coupling",
                    severity = maxSeverity(fire.severity, WorldSeverity.MEDIUM),
                    confidence = conf,
                    distanceKm = round1(distKm),
                    timeDeltaMinutes = dtMin,
                    latitude = fire.latitude,
                    longitude = fire.longitude,
                    summary = "${fire.title} observed ${round1(distKm)} km from meteorological station reporting ${wx.summary}.",
                    rationale = "Satellite wildfire detection overlaps elevated surface wind or dry synoptic conditions.",
                    caveat = CAVEAT_SPATIO_TEMPORAL,
                    entityIds = listOf(fire.id, wx.id),
                    evidenceItems = listOf("${fire.providerName}: ${fire.title}", "${wx.providerName}: ${wx.summary}"),
                    sourceLinks = listOf(fire.sourceUrl, wx.sourceUrl).distinct(),
                    links = listOf(CorrelationLink(fire.latitude, fire.longitude, wx.latitude, wx.longitude))
                )
            }
        }

        // 3. Tropical Cyclone / Severe Storm <-> Maritime Traffic & Seaport Exposure
        val storms = disasters.filter {
            it.subType.contains("Cyclone", true) ||
                it.subType.contains("Storm", true) ||
                it.subType.contains("Hurricane", true)
        } + weather.filter { it.severity.rank >= WorldSeverity.HIGH.rank }

        val maritimeTargets = maritime + infra.filter { it.subType == "Seaport" }
        for (storm in storms) {
            val exposed = maritimeTargets
                .filter { it.providerId != storm.providerId }
                .map { m -> m to GeoSpatialIndex.haversineKm(storm.latitude, storm.longitude, m.latitude, m.longitude) }
                .filter { (_, d) -> d <= 380.0 }
                .sortedBy { it.second }
                .take(2)
            for ((vesselOrPort, distKm) in exposed) {
                val dtMin = abs(storm.eventTimeEpochMs - vesselOrPort.eventTimeEpochMs) / 60_000L
                val conf = (88 - (distKm / 12.0).roundToInt()).coerceIn(58, 92)
                incidents += CorrelatedIncident(
                    id = "corr:stormsea:${storm.id}:${vesselOrPort.id}",
                    ruleId = "RULE_CYCLONE_MARITIME_EXPOSURE",
                    ruleTitle = "Severe Storm & Maritime / Port Proximity",
                    severity = maxSeverity(storm.severity, WorldSeverity.HIGH),
                    confidence = conf,
                    distanceKm = round1(distKm),
                    timeDeltaMinutes = dtMin,
                    latitude = storm.latitude,
                    longitude = storm.longitude,
                    summary = "${storm.title} is ${round1(distKm)} km from maritime asset ${vesselOrPort.title}.",
                    rationale = "Active cyclone or severe marine weather envelope intersects AIS vessel, ocean buoy or strategic seaport.",
                    caveat = CAVEAT_SPATIO_TEMPORAL,
                    entityIds = listOf(storm.id, vesselOrPort.id),
                    evidenceItems = listOf("${storm.providerName}: ${storm.title}", "${vesselOrPort.providerName}: ${vesselOrPort.title}"),
                    sourceLinks = listOf(storm.sourceUrl, vesselOrPort.sourceUrl).distinct(),
                    links = listOf(CorrelationLink(storm.latitude, storm.longitude, vesselOrPort.latitude, vesselOrPort.longitude))
                )
            }
        }

        // 4. Internet Outage <-> BGP Routing / Submarine Cable / High-Severity Disaster
        val outages = cyber.filter { it.subType.contains("Outage", true) || it.providerId == "wm.cyber.ioda" }
        val bgpOrHazardOrCable = cyber.filter { it.providerId != "wm.cyber.ioda" } +
            disasters.filter { it.severity.rank >= WorldSeverity.HIGH.rank } +
            infra.filter { it.subType == "Submarine Cable" }

        for (outage in outages) {
            for (peer in bgpOrHazardOrCable) {
                if (outage.providerId == peer.providerId) continue
                val sameCountry = !outage.countryCode.isNullOrBlank() && outage.countryCode == peer.countryCode
                val distKm = GeoSpatialIndex.haversineKm(outage.latitude, outage.longitude, peer.latitude, peer.longitude)
                if (!sameCountry && distKm > 550.0) continue
                val dtMin = abs(outage.eventTimeEpochMs - peer.eventTimeEpochMs) / 60_000L
                val conf = if (sameCountry) 82 else (78 - (distKm / 25.0).roundToInt()).coerceIn(52, 80)
                incidents += CorrelatedIncident(
                    id = "corr:outage:${outage.id}:${peer.id}",
                    ruleId = "RULE_OUTAGE_ROUTING_INFRASTRUCTURE",
                    ruleTitle = "Internet Outage & Routing / Physical Hazard Coincidence",
                    severity = maxSeverity(outage.severity, peer.severity),
                    confidence = conf,
                    distanceKm = round1(distKm),
                    timeDeltaMinutes = dtMin,
                    latitude = outage.latitude,
                    longitude = outage.longitude,
                    summary = "${outage.title} coincides with ${peer.title} (${peer.providerName}).",
                    rationale = "Public internet outage signal aligns geographically with BGP routing state, submarine cable corridor, or major geohazard.",
                    caveat = "Country-level BGP/probing telemetry cannot isolate individual fiber cuts without operator confirmation.",
                    entityIds = listOf(outage.id, peer.id),
                    evidenceItems = listOf("${outage.providerName}: ${outage.title}", "${peer.providerName}: ${peer.title}"),
                    sourceLinks = listOf(outage.sourceUrl, peer.sourceUrl).distinct(),
                    links = listOf(CorrelationLink(outage.latitude, outage.longitude, peer.latitude, peer.longitude))
                )
            }
        }

        // 5. Satellite / Official Hazard Observation <-> Geolocated International News
        for (haz in disasters.take(40)) {
            for (article in news) {
                val distKm = GeoSpatialIndex.haversineKm(haz.latitude, haz.longitude, article.latitude, article.longitude)
                val dtMin = abs(haz.eventTimeEpochMs - article.eventTimeEpochMs) / 60_000L
                if (distKm <= 220.0 && dtMin <= 24 * 60L) {
                    val conf = (84 - (distKm / 8.0).roundToInt()).coerceIn(55, 91)
                    incidents += CorrelatedIncident(
                        id = "corr:satnews:${haz.id}:${article.id}",
                        ruleId = "RULE_SENSOR_NEWS_CORROBORATION",
                        ruleTitle = "Physical Sensor & Geolocated News Corroboration",
                        severity = maxSeverity(haz.severity, article.severity),
                        confidence = conf,
                        distanceKm = round1(distKm),
                        timeDeltaMinutes = dtMin,
                        latitude = haz.latitude,
                        longitude = haz.longitude,
                        summary = "${haz.title} (${haz.providerName}) corroborated within ${round1(distKm)} km by ${article.title}.",
                        rationale = "Physical remote-sensing / seismic observation matches independent geolocated media reporting.",
                        caveat = CAVEAT_SPATIO_TEMPORAL,
                        entityIds = listOf(haz.id, article.id),
                        evidenceItems = listOf("${haz.providerName}: ${haz.title}", "${article.providerName}: ${article.title}"),
                        sourceLinks = listOf(haz.sourceUrl, article.sourceUrl).distinct(),
                        links = listOf(CorrelationLink(haz.latitude, haz.longitude, article.latitude, article.longitude))
                    )
                }
            }
        }

        return incidents
            .distinctBy { it.entityIds.sorted().joinToString("|") }
            .sortedWith(compareByDescending<CorrelatedIncident> { it.severity.rank }.thenByDescending { it.confidence })
            .take(maxResults)
    }

    private fun maxSeverity(a: WorldSeverity, b: WorldSeverity): WorldSeverity =
        if (a.rank >= b.rank) a else b

    private fun round1(v: Double): Double = "%.1f".format(Locale.US, v).toDouble()
}
