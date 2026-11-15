package com.adshield.pro.engine.worldmonitor

import com.adshield.pro.domain.worldmonitor.DatasetComparisonRow
import com.adshield.pro.domain.worldmonitor.DigitalTwinSnapshot
import com.adshield.pro.domain.worldmonitor.FreshnessClass
import com.adshield.pro.domain.worldmonitor.WorldEntity
import com.adshield.pro.domain.worldmonitor.WorldIntelCategory
import java.util.Locale

/**
 * Global Digital Twin Synthesis Engine (Section 3).
 *
 * Combines geographic elevation, meteorological/air/flood observations, critical infrastructure,
 * active hazards, cyber telemetry, and UN/World Bank demographic reference data for any region,
 * and computes side-by-side regional comparisons.
 */
object DigitalTwinEngine {

    fun buildRegionalTwin(
        latitude: Double,
        longitude: Double,
        elevationMeters: Int,
        radiusKm: Double = 450.0,
        allEntities: List<WorldEntity>,
        compareCountryIso: String? = null,
        liveWorldBankPop: Pair<Long, Int>? = null,
        nowEpochMs: Long = System.currentTimeMillis()
    ): DigitalTwinSnapshot {
        val real = allEntities.filter { it.freshness != FreshnessClass.SIMULATED }
        val primaryCountry = BaselineReferenceStore.nearestCountry(latitude, longitude)
        val inRadius = real.filter {
            GeoSpatialIndex.haversineKm(latitude, longitude, it.latitude, it.longitude) <= radiusKm
        }

        val nearestWx = inRadius
            .filter { it.providerId == "wm.weather.openmeteo" }
            .minByOrNull { GeoSpatialIndex.haversineKm(latitude, longitude, it.latitude, it.longitude) }
        val nearestAir = inRadius
            .filter { it.providerId == "wm.weather.airquality" }
            .minByOrNull { GeoSpatialIndex.haversineKm(latitude, longitude, it.latitude, it.longitude) }
        val nearestFlood = inRadius
            .filter { it.providerId == "wm.weather.marine" }
            .minByOrNull { GeoSpatialIndex.haversineKm(latitude, longitude, it.latitude, it.longitude) }

        val infraCounts = linkedMapOf(
            "Airports" to inRadius.count { it.subType.contains("Airport", true) },
            "Seaports" to inRadius.count { it.subType.contains("Seaport", true) },
            "Submarine Cables" to inRadius.count { it.subType.contains("Submarine Cable", true) },
            "Power Facilities" to inRadius.count { it.subType.contains("Power", true) },
            "OSM Transport / Telecom" to inRadius.count { it.providerId == "wm.infra.overpass" }
        )

        val hazardCounts = linkedMapOf(
            "Earthquakes" to inRadius.count { it.category == WorldIntelCategory.DISASTER && it.subType.contains("Earthquake", true) },
            "Wildfires" to inRadius.count { it.category == WorldIntelCategory.DISASTER && it.subType.contains("Fire", true) },
            "Cyclones / Storms" to inRadius.count { it.category == WorldIntelCategory.DISASTER && (it.subType.contains("Cyclone", true) || it.subType.contains("Storm", true)) },
            "Floods / Volcanoes" to inRadius.count { it.category == WorldIntelCategory.DISASTER && (it.subType.contains("Flood", true) || it.subType.contains("Volcano", true)) }
        )

        val cyberCount = inRadius.count { it.category == WorldIntelCategory.CYBER }
        val popValue = liveWorldBankPop?.first ?: primaryCountry?.population
        val popYear = liveWorldBankPop?.second ?: primaryCountry?.popYear
        val popSource = if (liveWorldBankPop != null) {
            "World Bank Open Data API (SP.POP.TOTL)"
        } else {
            "World Bank / UN Demographic Reference Baseline"
        }

        // Optional side-by-side comparison against a second country/region
        val compareRef = compareCountryIso?.let { BaselineReferenceStore.countryByIso(it) }
            ?: BaselineReferenceStore.load().countries.firstOrNull { it.iso2 != primaryCountry?.iso2 }

        val comparisonRows = if (compareRef != null) {
            val compEntities = real.filter {
                GeoSpatialIndex.haversineKm(compareRef.lat, compareRef.lon, it.latitude, it.longitude) <= radiusKm
            }
            listOf(
                DatasetComparisonRow(
                    metric = "National Population",
                    primaryValue = popValue?.let { "%,d (%d)".format(Locale.US, it, popYear ?: 2024) } ?: "—",
                    secondaryValue = "%,d (%d)".format(Locale.US, compareRef.population, compareRef.popYear),
                    source = "World Bank SP.POP.TOTL"
                ),
                DatasetComparisonRow(
                    metric = "Critical Infrastructure (${radiusKm.toInt()} km)",
                    primaryValue = "${infraCounts.values.sum()} nodes",
                    secondaryValue = "${compEntities.count { it.category == WorldIntelCategory.INFRASTRUCTURE }} nodes",
                    source = "OurAirports / WPI / TeleGeography / OSM"
                ),
                DatasetComparisonRow(
                    metric = "Active Natural Hazards (${radiusKm.toInt()} km)",
                    primaryValue = "${hazardCounts.values.sum()} events",
                    secondaryValue = "${compEntities.count { it.category == WorldIntelCategory.DISASTER }} events",
                    source = "USGS / NASA EONET / GDACS"
                ),
                DatasetComparisonRow(
                    metric = "Aviation & Maritime Tracks",
                    primaryValue = "${inRadius.count { it.category == WorldIntelCategory.AVIATION || it.category == WorldIntelCategory.MARITIME }} active",
                    secondaryValue = "${compEntities.count { it.category == WorldIntelCategory.AVIATION || it.category == WorldIntelCategory.MARITIME }} active",
                    source = "OpenSky / ADSB.lol / Fintraffic / NDBC"
                ),
                DatasetComparisonRow(
                    metric = "Cyber & Outage Signals",
                    primaryValue = "$cyberCount signals",
                    secondaryValue = "${compEntities.count { it.category == WorldIntelCategory.CYBER }} signals",
                    source = "CISA KEV / Feodo / IODA / RIPEstat"
                )
            )
        } else emptyList()

        return DigitalTwinSnapshot(
            regionName = primaryCountry?.let { "${it.name} (near ${it.capital})" } ?: "Oceanic / International Sector",
            countryCode = primaryCountry?.iso2,
            latitude = latitude,
            longitude = longitude,
            radiusKm = radiusKm,
            populationCount = popValue,
            populationYear = popYear,
            populationSource = popSource,
            elevationMeters = elevationMeters,
            weatherSummary = nearestWx?.let { "${it.title} — ${it.summary}" }
                ?: "No cached synoptic station within ${radiusKm.toInt()} km; sync Weather module to measure.",
            airQualitySummary = nearestAir?.let { "${it.title} — ${it.summary}" }
                ?: "Air quality not yet sampled for this sector.",
            riverDischargeSummary = nearestFlood?.title
                ?: "GloFAS hydrological discharge not yet sampled for this sector.",
            infrastructureCounts = infraCounts,
            activeHazardCounts = hazardCounts,
            cyberSignalsCount = cyberCount,
            comparisonTargetName = compareRef?.name,
            datasetComparison = comparisonRows,
            generatedAtEpochMs = nowEpochMs
        )
    }
}
