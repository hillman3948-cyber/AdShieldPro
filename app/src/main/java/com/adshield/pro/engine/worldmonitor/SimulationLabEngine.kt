package com.adshield.pro.engine.worldmonitor

import com.adshield.pro.domain.worldmonitor.FreshnessClass
import com.adshield.pro.domain.worldmonitor.SimulationKind
import com.adshield.pro.domain.worldmonitor.SimulationResult
import com.adshield.pro.domain.worldmonitor.SimulationRing
import com.adshield.pro.domain.worldmonitor.SimulationWaypoint
import com.adshield.pro.domain.worldmonitor.VerificationStatus
import com.adshield.pro.domain.worldmonitor.WorldEntity
import com.adshield.pro.domain.worldmonitor.WorldIntelCategory
import java.util.Locale

/**
 * Experimental Predictive & Simulation Lab (Section 3).
 *
 * Every output is explicitly stamped `FreshnessClass.SIMULATED` and
 * `VerificationStatus.EXPERIMENTAL_SIMULATION` so experimental scenarios can NEVER be mistaken
 * for real-time warnings or certain predictions.
 */
object SimulationLabEngine {

    fun runScenario(
        kind: SimulationKind,
        centerLat: Double,
        centerLon: Double,
        intensityOrRadiusParameter: Double,
        windBearingDeg: Double = 285.0,
        referenceEntities: List<WorldEntity>
    ): SimulationResult {
        val baseline = BaselineReferenceStore.load()
        return when (kind) {
            SimulationKind.WEATHER_PLUME -> runWeatherPlume(
                centerLat, centerLon, intensityOrRadiusParameter.coerceIn(40.0, 500.0), windBearingDeg, baseline
            )
            SimulationKind.FLOOD_SURGE -> runFloodSurge(
                centerLat, centerLon, intensityOrRadiusParameter.coerceIn(1.0, 10.0), referenceEntities, baseline
            )
            SimulationKind.INFRASTRUCTURE_EXPOSURE -> runInfrastructureExposure(
                centerLat, centerLon, intensityOrRadiusParameter.coerceIn(25.0, 600.0), baseline
            )
            SimulationKind.ENVIRONMENTAL_TREND -> runEnvironmentalTrend(
                centerLat, centerLon, intensityOrRadiusParameter.coerceIn(100.0, 1000.0), referenceEntities
            )
            SimulationKind.HISTORICAL_REPLAY -> runHistoricalBenchmarkReplay()
        }
    }

    private fun runWeatherPlume(
        lat: Double,
        lon: Double,
        radiusKm: Double,
        bearingDeg: Double,
        baseline: BaselineReferenceStore.GlobalReferencePayload
    ): SimulationResult {
        val wp1 = GeoSpatialIndex.destinationPoint(lat, lon, bearingDeg, radiusKm * 0.8)
        val wp2 = GeoSpatialIndex.destinationPoint(lat, lon, bearingDeg, radiusKm * 1.6)
        val airports = baseline.airports.filter {
            GeoSpatialIndex.haversineKm(lat, lon, it.lat, it.lon) <= radiusKm ||
                GeoSpatialIndex.haversineKm(wp1.first, wp1.second, it.lat, it.lon) <= radiusKm * 0.75
        }.map { "${it.iata} · ${it.name}" }
        val ports = baseline.seaports.filter {
            GeoSpatialIndex.haversineKm(lat, lon, it.lat, it.lon) <= radiusKm ||
                GeoSpatialIndex.haversineKm(wp1.first, wp1.second, it.lat, it.lon) <= radiusKm * 0.75
        }.map { "${it.locode} · ${it.name}" }

        return SimulationResult(
            active = true,
            kind = SimulationKind.WEATHER_PLUME,
            label = "Weather Scenario (${radiusKm.toInt()} km envelope @ ${bearingDeg.toInt()}°)",
            centerLat = lat,
            centerLon = lon,
            primaryParameterLabel = "Gale Envelope ${radiusKm.toInt()} km · Track ${bearingDeg.toInt()}°",
            exposedAirports = airports,
            exposedSeaports = ports,
            findings = listOf(
                "Hypothetical ${radiusKm.toInt()} km storm core projected along ${bearingDeg.toInt()}° azimuth.",
                "Intersects ${airports.size} major international airport(s) and ${ports.size} strategic seaport(s) in reference catalog.",
                "Experimental geometric envelope only — consult official WMO/PAGASA/NOAA bulletins for real cyclone tracks."
            ),
            rings = listOf(
                SimulationRing(lat, lon, radiusKm * 0.45, "#EF4444", 0.26, "Inner Gale Core"),
                SimulationRing(lat, lon, radiusKm, "#F59E0B", 0.14, "Outer Wind Envelope"),
                SimulationRing(wp1.first, wp1.second, radiusKm * 0.75, "#FBBF24", 0.12, "+12h Projected Sector")
            ),
            waypoints = listOf(
                SimulationWaypoint(lat, lon, "T+0h"),
                SimulationWaypoint(wp1.first, wp1.second, "T+12h"),
                SimulationWaypoint(wp2.first, wp2.second, "T+24h")
            )
        )
    }

    private fun runFloodSurge(
        lat: Double,
        lon: Double,
        waterRiseMeters: Double,
        entities: List<WorldEntity>,
        baseline: BaselineReferenceStore.GlobalReferencePayload
    ): SimulationResult {
        val searchRadiusKm = (waterRiseMeters * 28.0).coerceIn(30.0, 280.0)
        val lowAirports = baseline.airports.filter {
            GeoSpatialIndex.haversineKm(lat, lon, it.lat, it.lon) <= searchRadiusKm && it.elevM <= (waterRiseMeters * 3.0 + 15)
        }.map { "${it.iata} (${it.elevM}m MSL)" }
        val coastalPorts = baseline.seaports.filter {
            GeoSpatialIndex.haversineKm(lat, lon, it.lat, it.lon) <= searchRadiusKm
        }.map { "${it.locode} · ${it.name}" }
        val glofas = entities.firstOrNull {
            it.providerId == "wm.weather.marine" &&
                GeoSpatialIndex.haversineKm(lat, lon, it.latitude, it.longitude) <= 350.0
        }

        return SimulationResult(
            active = true,
            kind = SimulationKind.FLOOD_SURGE,
            label = "Flood / Surge Scenario (+${"%.1f".format(Locale.US, waterRiseMeters)} m)",
            centerLat = lat,
            centerLon = lon,
            primaryParameterLabel = "+${"%.1f".format(Locale.US, waterRiseMeters)} m Surge / Stage Threshold",
            exposedAirports = lowAirports,
            exposedSeaports = coastalPorts,
            findings = listOfNotNull(
                "Evaluated +${"%.1f".format(Locale.US, waterRiseMeters)} m water-stage scenario across ${searchRadiusKm.toInt()} km coastal/riparian buffer.",
                glofas?.let { "Measured Copernicus GloFAS context nearby: ${it.title}." }
                    ?: "Sync Weather module to pair this scenario with live Copernicus GloFAS river discharge.",
                "${lowAirports.size} low-elevation airfield(s) and ${coastalPorts.size} coastal port(s) lie inside the screening buffer.",
                "Screening uses reference elevation & GloFAS discharge; it is not a hydrodynamic inundation model."
            ),
            rings = listOf(
                SimulationRing(lat, lon, searchRadiusKm * 0.5, "#38BDF8", 0.28, "Primary Riparian/Coastal Zone"),
                SimulationRing(lat, lon, searchRadiusKm, "#0284C7", 0.14, "Extended Basin Screening Zone")
            )
        )
    }

    private fun runInfrastructureExposure(
        lat: Double,
        lon: Double,
        radiusKm: Double,
        baseline: BaselineReferenceStore.GlobalReferencePayload
    ): SimulationResult {
        val airports = baseline.airports.filter { GeoSpatialIndex.haversineKm(lat, lon, it.lat, it.lon) <= radiusKm }
            .map { "${it.iata} · ${it.name}" }
        val ports = baseline.seaports.filter { GeoSpatialIndex.haversineKm(lat, lon, it.lat, it.lon) <= radiusKm }
            .map { "${it.locode} · ${it.name}" }
        val power = baseline.powerStations.filter { GeoSpatialIndex.haversineKm(lat, lon, it.lat, it.lon) <= radiusKm }
            .map { "${it.name} (${it.capacityMw} MW ${it.fuel})" }
        val cables = baseline.submarineCables.filter { cable ->
            cable.coords.any { pt ->
                pt.size >= 2 && GeoSpatialIndex.haversineKm(lat, lon, pt[1], pt[0]) <= radiusKm
            }
        }.map { it.name }
        val nearestCountry = BaselineReferenceStore.nearestCountry(lat, lon)

        return SimulationResult(
            active = true,
            kind = SimulationKind.INFRASTRUCTURE_EXPOSURE,
            label = "Infrastructure Exposure (${radiusKm.toInt()} km)",
            centerLat = lat,
            centerLon = lon,
            primaryParameterLabel = "${radiusKm.toInt()} km Geodesic Exposure Ring",
            exposedAirports = airports,
            exposedSeaports = ports,
            exposedPowerPlants = power,
            exposedSubmarineCables = cables,
            findings = listOfNotNull(
                nearestCountry?.let { "Nearest sovereign demographic context: ${it.name} (World Bank pop. %,d).".format(Locale.US, it.population) },
                "Within ${radiusKm.toInt()} km: ${airports.size} hub airport(s), ${ports.size} seaport(s), ${power.size} major power plant(s), ${cables.size} submarine cable corridor(s)."
            ),
            rings = listOf(
                SimulationRing(lat, lon, radiusKm * 0.5, "#F59E0B", 0.22, "50% Inner Ring"),
                SimulationRing(lat, lon, radiusKm, "#22D3EE", 0.12, "${radiusKm.toInt()} km Outer Ring")
            )
        )
    }

    private fun runEnvironmentalTrend(
        lat: Double,
        lon: Double,
        radiusKm: Double,
        entities: List<WorldEntity>
    ): SimulationResult {
        val local = entities.filter {
            it.freshness != FreshnessClass.SIMULATED &&
                GeoSpatialIndex.haversineKm(lat, lon, it.latitude, it.longitude) <= radiusKm
        }
        val quakes = local.count { it.category == WorldIntelCategory.DISASTER && it.subType.contains("Earthquake", true) }
        val fires = local.count { it.category == WorldIntelCategory.DISASTER && it.subType.contains("Fire", true) }
        val wx = local.filter { it.category == WorldIntelCategory.WEATHER }

        return SimulationResult(
            active = true,
            kind = SimulationKind.ENVIRONMENTAL_TREND,
            label = "Environmental Trend Window (${radiusKm.toInt()} km)",
            centerLat = lat,
            centerLon = lon,
            primaryParameterLabel = "${radiusKm.toInt()} km Multi-Dataset Trend",
            findings = listOf(
                "Sampled ${local.size} cached observation(s) inside ${radiusKm.toInt()} km.",
                "Seismic count in window: $quakes · Wildfire/thermal detections: $fires · Weather/Air nodes: ${wx.size}.",
                "Tip: Enable 'Historical Split' in Imagery Controls to compare NASA GIBS VIIRS satellite reflectance across two dates."
            ),
            rings = listOf(
                SimulationRing(lat, lon, radiusKm, "#34D399", 0.14, "Environmental Analysis Footprint")
            )
        )
    }

    private fun runHistoricalBenchmarkReplay(): SimulationResult {
        // Documented historical benchmark: 2024-04-03 M7.4 Hualien seismic & Pacific tsunami advisory geometry
        val epiLat = 23.819
        val epiLon = 121.562
        return SimulationResult(
            active = true,
            kind = SimulationKind.HISTORICAL_REPLAY,
            label = "Historical Replay: 2024 M7.4 Hualien Seismic Benchmark",
            centerLat = epiLat,
            centerLon = epiLon,
            primaryParameterLabel = "Archival Benchmark (2024-04-03 23:58 UTC · M7.4)",
            exposedSubmarineCables = listOf("Apricot Intra-Asia Cable (Taiwan East Coast Corridor)"),
            findings = listOf(
                "Replaying documented 2024-04-03 M7.4 Hualien offshore seismic benchmark (23.819°N, 121.562°E, depth 34.8 km).",
                "Illustrates concentric strong-motion (~120 km) and regional tsunami advisory (~450 km) screening rings alongside Luzon Strait submarine cable corridors.",
                "Archival educational replay — NOT an active earthquake or tsunami warning."
            ),
            rings = listOf(
                SimulationRing(epiLat, epiLon, 120.0, "#EF4444", 0.25, "Historical Strong-Motion Zone"),
                SimulationRing(epiLat, epiLon, 450.0, "#F59E0B", 0.12, "Historical Regional Advisory Envelope")
            ),
            freshness = FreshnessClass.SIMULATED,
            verificationStatus = VerificationStatus.EXPERIMENTAL_SIMULATION
        )
    }
}
