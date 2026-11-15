package com.adshield.pro

import com.adshield.pro.data.worldmonitor.WorldEntityRecord
import com.adshield.pro.domain.worldmonitor.FreshnessClass
import com.adshield.pro.domain.worldmonitor.GeoAccuracy
import com.adshield.pro.domain.worldmonitor.GlobeProjection
import com.adshield.pro.domain.worldmonitor.ProviderAuthRequirement
import com.adshield.pro.domain.worldmonitor.ProviderSyncStatus
import com.adshield.pro.domain.worldmonitor.SatellitePassPrediction
import com.adshield.pro.domain.worldmonitor.SimulationKind
import com.adshield.pro.domain.worldmonitor.VerificationStatus
import com.adshield.pro.domain.worldmonitor.WatchtowerRegion
import com.adshield.pro.domain.worldmonitor.WorldEntity
import com.adshield.pro.domain.worldmonitor.WorldIntelCategory
import com.adshield.pro.domain.worldmonitor.WorldProviderRuntimeState
import com.adshield.pro.domain.worldmonitor.WorldSeverity
import com.adshield.pro.domain.worldmonitor.WorldViewport
import com.adshield.pro.engine.worldmonitor.BaselineReferenceStore
import com.adshield.pro.engine.worldmonitor.CzmlAndGeoJsonBuilder
import com.adshield.pro.engine.worldmonitor.DigitalTwinEngine
import com.adshield.pro.engine.worldmonitor.EventCorrelationEngine
import com.adshield.pro.engine.worldmonitor.GeoSpatialIndex
import com.adshield.pro.engine.worldmonitor.NewsFeedParser
import com.adshield.pro.engine.worldmonitor.OrbitalAndGlobeMath
import com.adshield.pro.engine.worldmonitor.SimulationLabEngine
import com.adshield.pro.engine.worldmonitor.WatchtowerManager
import com.adshield.pro.engine.worldmonitor.WorldMonitorAiAnalyst
import com.adshield.pro.engine.worldmonitor.WorldMonitorAssetAndTileServer
import com.adshield.pro.engine.worldmonitor.WorldMonitorJsBridge
import com.adshield.pro.engine.worldmonitor.WorldMonitorNormalizers
import com.adshield.pro.engine.worldmonitor.WorldMonitorSyncEngine
import com.adshield.pro.engine.worldmonitor.WorldMonitorProviderRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Comprehensive JVM Verification Suite for OMNISHIELD X INFINITY — WORLD MONITOR 2035.
 */
class WorldMonitorTestSuite {

    private val fixedNowMs = 1_794_144_000_000L // 2026-11-08 UTC

    private fun spec(id: String) = requireNotNull(WorldMonitorProviderRegistry.find(id)) { "Missing spec $id" }

    @Test
    fun testProviderRegistryAndEpistemicContracts() {
        val all = WorldMonitorProviderRegistry.providers
        assertTrue("Expected at least 26 registered global providers", all.size >= 26)
        assertEquals("Provider IDs must be globally unique", all.size, all.map { it.providerId }.toSet().size)

        WorldIntelCategory.entries.forEach { cat ->
            assertTrue("Category $cat must have providers", WorldMonitorProviderRegistry.forCategory(cat).isNotEmpty())
        }

        all.forEach { s ->
            assertTrue("${s.providerId}: attribution required", s.attribution.isNotBlank())
            assertTrue("${s.providerId}: license required", s.licenseInfo.isNotBlank())
            assertTrue("${s.providerId}: docs URL must be HTTPS", s.docsUrl.startsWith("https://"))
            assertTrue("${s.providerId}: endpoint must be HTTPS", s.endpoint.startsWith("https://"))
        }

        val aisStream = WorldMonitorProviderRegistry.find("wm.maritime.aisstream")
        assertNotNull(aisStream)
        assertFalse("AISStream WebSocket requires external key/proxy", aisStream!!.implemented)
        assertEquals(ProviderAuthRequirement.REQUIRED_USER_KEY, aisStream.authRequirement)

        val firms = WorldMonitorProviderRegistry.find("wm.disaster.firms")
        assertNotNull(firms)
        assertEquals(ProviderAuthRequirement.REQUIRED_USER_KEY, firms!!.authRequirement)

        WorldMonitorProviderRegistry.providers.filter { it.providerId.startsWith("wm.space.celestrak.") }.forEach { ct ->
            assertTrue("${ct.providerId} must enforce >= 2h minimum poll gap", ct.minRequestGapMs >= 2 * 3_600_000L)
        }
    }

    @Test
    fun testNormalizersAcrossAllEightIntelligenceDomains() {
        // A. Aviation: OpenSky + ADSB.lol + OurAirports CSV
        val openSkyJson = """{"time":1794143990,"states":[["4b1814","SWR100  ","Switzerland",1794143988,1794143989,8.5492,47.4582,10668.0,false,245.5,282.0,0.0,null,10800.0,"1000",false,0]]}"""
        val openSky = WorldMonitorNormalizers.normalize(spec("wm.aviation.opensky"), openSkyJson, fixedNowMs)
        assertEquals(1, openSky.size)
        assertEquals(WorldIntelCategory.AVIATION, openSky[0].category)
        assertEquals(FreshnessClass.LIVE, openSky[0].freshness)

        val adsbJson = """{"ac":[{"hex":"a00001","flight":"UAL1    ","lat":37.62,"lon":-122.37,"alt_baro":32000,"gs":470.0,"track":295.0,"t":"B789"}]}"""
        val adsb = WorldMonitorNormalizers.normalize(spec("wm.aviation.adsblol"), adsbJson, fixedNowMs)
        assertEquals(1, adsb.size)
        assertTrue(adsb[0].altitudeMeters!! > 9_000.0)

        val airportsCsv = "id,ident,type,name,latitude_deg,longitude_deg,elevation_ft,continent,iso_country,iso_region,municipality,scheduled_service,icao_code,iata_code\n" +
            "1,\"RPLL\",\"large_airport\",\"Ninoy Aquino International Airport\",14.5086,121.0194,75,\"AS\",\"PH\",\"PH-00\",\"Manila\",\"yes\",\"RPLL\",\"MNL\""
        val airports = WorldMonitorNormalizers.normalize(spec("wm.aviation.ourairports"), airportsCsv, fixedNowMs)
        assertEquals(1, airports.size)
        assertEquals(FreshnessClass.HISTORICAL, airports[0].freshness)

        // B. Maritime: Digitraffic AIS + NOAA NDBC buoys
        val aisJson = """{"features":[{"mmsi":230999111,"geometry":{"type":"Point","coordinates":[24.95,60.15]},"properties":{"sog":14.2,"cog":185.0,"heading":184,"navStat":0,"timestampExternal":1794143950000}}]}"""
        val vessels = WorldMonitorNormalizers.normalize(spec("wm.maritime.digitraffic"), aisJson, fixedNowMs)
        assertEquals(1, vessels.size)
        assertEquals(WorldIntelCategory.MARITIME, vessels[0].category)

        val ndbcTxt = "#STN LAT LON YYYY MM DD hh mm WDIR WSPD GST WVHT DPD APD MWD PRES PTDY ATMP WTMP\n" +
            "#deg deg yr mo dy hr mn degT m/s m/s m sec sec degT hPa hPa degC degC\n" +
            "46001 56.300 -147.900 2026 11 08 12 00 240 16.5 20.1 6.4 12 9 245 996.2 -1.5 7.2 8.1"
        val buoys = WorldMonitorNormalizers.normalize(spec("wm.maritime.ndbc"), ndbcTxt, fixedNowMs)
        assertEquals(1, buoys.size)

        // C. Space: ISS + CelesTrak 3LE + SWPC Kp
        val issJson = """{"name":"iss","id":25544,"latitude":14.60,"longitude":120.98,"altitude":418.5,"velocity":27580.2,"visibility":"daylight","timestamp":1794143995}"""
        val iss = WorldMonitorNormalizers.normalize(spec("wm.space.iss"), issJson, fixedNowMs)
        assertEquals(1, iss.size)
        assertEquals(GeoAccuracy.SATELLITE_FOOTPRINT, iss[0].geoAccuracy)

        val tle3le = """
            ISS (ZARYA)
            1 25544U 98067A   26312.50000000  .00016717  00000+0  10270-3 0  9993
            2 25544  51.6416 247.4627 0006703 130.5360 325.0288 15.50103472421535
        """.trimIndent()
        val sats = WorldMonitorNormalizers.normalize(spec("wm.space.celestrak.stations"), tle3le, fixedNowMs)
        assertEquals(1, sats.size)
        assertNotNull(sats[0].tleLine1)
        assertNotNull(sats[0].tleLine2)

        val swpcKp = """[["time_tag","Kp","a_running","station_count"],["2026-11-08 12:00:00.000","7.33","132","8"]]"""
        val kp = WorldMonitorNormalizers.normalize(spec("wm.space.swpc.kp"), swpcKp, fixedNowMs)
        assertEquals(1, kp.size)

        // D. Disaster: USGS + EONET + FIRMS
        val usgsJson = """{"features":[{"id":"us7000test","properties":{"mag":6.8,"place":"45 km E of Davao, Philippines","time":1794142000000,"url":"https://earthquake.usgs.gov/earthquakes/eventpage/us7000test","tsunami":1},"geometry":{"type":"Point","coordinates":[126.05,7.07,28.5]}}]}"""
        val quakes = WorldMonitorNormalizers.normalize(spec("wm.disaster.usgs.day"), usgsJson, fixedNowMs)
        assertEquals(1, quakes.size)
        assertEquals(VerificationStatus.OFFICIAL_AGENCY, quakes[0].verificationStatus)

        val eonetJson = """{"events":[{"id":"EONET_99","title":"Wildfire - Luzon","link":"https://eonet.gsfc.nasa.gov/api/v3/events/EONET_99","categories":[{"title":"Wildfires"}],"geometry":[{"date":"2026-11-08T10:00:00Z","type":"Point","coordinates":[121.10,15.40]}]}]}"""
        val eonet = WorldMonitorNormalizers.normalize(spec("wm.disaster.eonet"), eonetJson, fixedNowMs)
        assertEquals(1, eonet.size)

        val firmsCsv = "latitude,longitude,bright_ti4,scan,track,acq_date,acq_time,satellite,instrument,confidence,version,bright_ti5,frp,daynight\n" +
            "15.42,121.12,345.6,0.4,0.4,2026-11-08,0430,N20,VIIRS,h,2.0NRT,298.1,68.4,D"
        val firms = WorldMonitorNormalizers.normalize(spec("wm.disaster.firms"), firmsCsv, fixedNowMs)
        assertEquals(1, firms.size)

        // E. Weather: Open-Meteo + RainViewer
        val openMeteoJson = """{"latitude":14.6,"longitude":120.98,"current":{"temperature_2m":31.4,"precipitation":0.0,"wind_speed_10m":26.0,"wind_direction_10m":270,"surface_pressure":1007.2,"cloud_cover":45,"weather_code":1}}"""
        val wx = WorldMonitorNormalizers.normalize(spec("wm.weather.openmeteo"), openMeteoJson, fixedNowMs)
        assertEquals(1, wx.size)

        val rvFrames = WorldMonitorNormalizers.parseRainViewerFrames("""{"radar":{"past":[{"time":1794143000,"path":"/v2/radar/1794143000"}]}}""")
        assertEquals(1, rvFrames.size)

        // F. Cyber: CISA KEV + IODA Outage
        val kevJson = """{"vulnerabilities":[{"cveID":"CVE-2026-9999","vendorProject":"Acme","product":"EdgeGateway","vulnerabilityName":"Auth Bypass","dateAdded":"2026-11-07","shortDescription":"Actively exploited","knownRansomwareCampaignUse":"Known"}]}"""
        val kev = WorldMonitorNormalizers.normalize(spec("wm.cyber.cisa.kev"), kevJson, fixedNowMs)
        assertEquals(1, kev.size)

        // G. News: every outlet goes through the structural RSS reader.
        //
        // BBC World really ships titles in CDATA and *no* <description>. RDF/DW really uses
        // <dc:date>, not <pubDate>. An item with no usable date must not borrow "now". These three
        // properties are what the old document-wide regex reader got wrong, so they are asserted
        // on documents shaped like the live feeds rather than on a convenient fixture.
        val bbcRss = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/"><channel>
              <title>BBC News - World</title>
              <item>
                <title><![CDATA[Philippines quake shakes Mindanao]]></title>
                <link>https://www.bbc.co.uk/news/world-1?at_medium=RSS&amp;at_campaign=rss</link>
                <pubDate>Sat, 08 Nov 2026 11:00:00 GMT</pubDate>
                <category>Asia</category>
                <media:thumbnail url="https://ichef.bbci.co.uk/thumb.jpg"/>
              </item>
              <item>
                <title>Niger and Mali sign border accord</title>
                <link>https://www.bbc.co.uk/news/world-2</link>
              </item>
              <item>
                <title>Markets rally as bond yields ease</title>
                <link>https://www.bbc.co.uk/news/world-3</link>
                <pubDate>Sat, 08 Nov 2026 12:00:00 GMT</pubDate>
              </item>
            </channel></rss>
        """.trimIndent()
        val news = WorldMonitorNormalizers.normalize(spec("wm.news.bbc.world"), bbcRss, fixedNowMs)
        assertEquals(3, news.size)
        assertEquals(WorldIntelCategory.NEWS, news[0].category)
        assertEquals("Philippines quake shakes Mindanao", news[0].title)
        assertEquals("https://www.bbc.co.uk/news/world-1?at_medium=RSS&at_campaign=rss", news[0].sourceUrl)
        assertEquals("the channel <title> must never become an item title", false, news[0].title.contains("BBC News - World"))
        assertTrue("every item needs a stable id", news.all { it.id.startsWith("news:bbc:") })

        // Placement path 1: the headline names a country -> that country's centroid.
        assertEquals(GeoAccuracy.ADMIN_CENTROID, news[0].geoAccuracy)
        assertEquals("PH", news[0].countryCode?.uppercase())
        assertEquals("a feed-supplied image must be kept for inline reading",
            "https://ichef.bbci.co.uk/thumb.jpg", news[0].metricsMap["Image"])
        assertTrue("summary must say how the point was derived", news[0].summary.contains("centroid"))
        assertNotNull("the feed date must be preserved", news[0].eventTimeEpochMs)
        assertTrue("stale items must expire", news[0].expiresAtEpochMs > news[0].eventTimeEpochMs)

        // Placement path 2: no date -> HISTORICAL, never "now". Niger is named, and must not be
        // widened into Nigeria by a substring match.
        assertEquals(FreshnessClass.HISTORICAL, news[1].freshness)
        assertEquals("NE", news[1].countryCode)
        assertEquals(GeoAccuracy.ADMIN_CENTROID, news[1].geoAccuracy)

        // Placement path 3: nothing placeable -> the newsroom's own dateline, stated in words.
        assertEquals(GeoAccuracy.SOURCE_DESK, news[2].geoAccuracy)
        assertNull(news[2].countryCode)
        assertTrue("a dateline placement must be disclosed",
            news[2].summary.contains("dateline", ignoreCase = true))

        // RDF / RSS 1.0 with dc:date, as DW publishes it.
        val dwRdf = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns="http://purl.org/rss/1.0/"
                     xmlns:dc="http://purl.org/dc/elements/1.1/">
              <channel rdf:about="https://rss.dw.com/rdf/rss-en-world">
                <title>DW.com - World</title>
                <link>https://www.dw.com/en/</link>
              </channel>
              <item rdf:about="https://www.dw.com/en/quake/a-1">
                <title>Strong quake hits off Japan</title>
                <link>https://www.dw.com/en/quake/a-1</link>
                <description>Buildings swayed in Tokyo.</description>
                <dc:date>2026-11-08T10:30:00Z</dc:date>
              </item>
            </rdf:RDF>
        """.trimIndent()
        val dwNews = WorldMonitorNormalizers.normalize(spec("wm.news.dw.world"), dwRdf, fixedNowMs)
        assertEquals(1, dwNews.size)
        assertEquals("DW's dc:date must be read as the publication time",
            1_794_133_800_000L, dwNews[0].eventTimeEpochMs)
        assertEquals("JP", dwNews[0].countryCode)

        // A newsroom that answers with an HTML page must produce nothing rather than a fake story.
        val consentPage = "<!DOCTYPE html><html><body><h1>Before you continue</h1></body></html>"
        assertTrue(WorldMonitorNormalizers.normalize(spec("wm.news.un"), consentPage, fixedNowMs).isEmpty())

        // H. Infrastructure: Baseline + Overpass 3D Buildings
        val baseline = BaselineReferenceStore.buildBaselineEntities(fixedNowMs)
        assertTrue("Offline baseline should provide global airports, ports, power, cables, and TLEs", baseline.size >= 70)

        val overpassJson = """{"elements":[
            {"type":"way","id":101,"tags":{"building":"commercial","name":"Tower One","building:levels":"32"},"geometry":[{"lat":14.554,"lon":121.024},{"lat":14.555,"lon":121.024},{"lat":14.555,"lon":121.025},{"lat":14.554,"lon":121.025}]},
            {"type":"node","id":202,"lat":14.556,"lon":121.022,"tags":{"railway":"station","name":"Ayala Station"}}
        ]}"""
        val overpass = WorldMonitorNormalizers.parseOverpass(spec("wm.infra.overpass"), overpassJson, fixedNowMs)
        assertEquals(1, overpass.buildings3D.size)
        assertTrue(overpass.buildings3D[0].heightMeters > 80.0)
        assertEquals(1, overpass.infrastructureEntities.size)
    }

    @Test
    fun testGeoSpatialIndexCzmlAndGeoJsonExport() {
        val distKm = GeoSpatialIndex.haversineKm(14.5995, 120.9842, 35.6762, 139.6503)
        assertTrue("Expected Manila-Tokyo distance near 3000 km, got $distKm", distKm in 2950.0..3050.0)

        val datelineVp = WorldViewport(west = 175.0, south = -20.0, east = -175.0, north = 20.0)
        assertTrue(GeoSpatialIndex.inViewport(10.0, 179.0, datelineVp, padDeg = 0.0))
        assertTrue(GeoSpatialIndex.inViewport(10.0, -178.0, datelineVp, padDeg = 0.0))
        assertFalse(GeoSpatialIndex.inViewport(10.0, 120.0, datelineVp, padDeg = 0.0))

        val hash = GeoSpatialIndex.encodeGeohash(14.5995, 120.9842, 5)
        assertEquals(5, hash.length)

        val sample = BaselineReferenceStore.buildBaselineEntities(fixedNowMs).take(5)
        val geoJson = CzmlAndGeoJsonBuilder.buildGeoJsonFeatureCollection(sample)
        assertTrue(geoJson.contains("\"FeatureCollection\""))
        assertTrue(geoJson.contains("\"freshness\""))

        val czml = CzmlAndGeoJsonBuilder.buildCzmlDocument(sample)
        assertTrue(czml.contains("\"id\":\"document\""))
        assertTrue(czml.contains("\"cartographicDegrees\""))
    }

    @Test
    fun testEventCorrelationEngineNeverInventsSelfLinks() {
        val usgsQuake = WorldEntity(
            id = "usgs:1",
            providerId = "wm.disaster.usgs.day",
            providerName = "USGS",
            category = WorldIntelCategory.DISASTER,
            subType = "Earthquake",
            title = "M 7.1 — Offshore Mindanao",
            summary = "Depth 25 km",
            latitude = 7.10,
            longitude = 126.20,
            severity = WorldSeverity.CRITICAL,
            freshness = FreshnessClass.NEAR_REAL_TIME,
            geoAccuracy = GeoAccuracy.SEISMIC_EPICENTER,
            confidence = 98,
            verificationStatus = VerificationStatus.OFFICIAL_AGENCY,
            eventTimeEpochMs = fixedNowMs - 600_000L,
            retrievedAtEpochMs = fixedNowMs,
            expiresAtEpochMs = fixedNowMs + 3_600_000L,
            sourceUrl = "https://earthquake.usgs.gov/1",
            attribution = "USGS",
            licenseInfo = "Public Domain"
        )
        val sameProviderQuake = usgsQuake.copy(id = "usgs:2", latitude = 7.15)
        val gdacsTsunami = usgsQuake.copy(
            id = "gdacs:1",
            subType = "Tsunami Advisory",
            title = "Red Tsunami Alert Mindanao",
            providerId = "wm.disaster.gdacs",
            providerName = "GDACS",
            sourceUrl = "https://www.gdacs.org/1"
        )
        val simulatedItem = gdacsTsunami.copy(
            id = "sim:1",
            providerId = "wm.sim",
            freshness = FreshnessClass.SIMULATED,
            verificationStatus = VerificationStatus.EXPERIMENTAL_SIMULATION
        )

        assertEquals(0, EventCorrelationEngine.correlate(listOf(usgsQuake, sameProviderQuake, simulatedItem)).size)

        val correlated = EventCorrelationEngine.correlate(listOf(usgsQuake, gdacsTsunami, simulatedItem))
        assertEquals(1, correlated.size)
        assertTrue(correlated[0].caveat.isNotBlank())
        assertEquals(2, correlated[0].sourceLinks.size)
    }

    @Test
    fun testDigitalTwinAndAiAnalystEpistemicSeparation() {
        val entities = BaselineReferenceStore.buildBaselineEntities(fixedNowMs).toMutableList()
        entities += WorldEntity(
            id = "sim:fake",
            providerId = "sim",
            providerName = "SimLab",
            category = WorldIntelCategory.DISASTER,
            subType = "Simulated Surge",
            title = "Experimental Surge",
            summary = "Not real",
            latitude = 14.59,
            longitude = 120.98,
            severity = WorldSeverity.CRITICAL,
            freshness = FreshnessClass.SIMULATED,
            geoAccuracy = GeoAccuracy.ADMIN_CENTROID,
            confidence = 10,
            verificationStatus = VerificationStatus.EXPERIMENTAL_SIMULATION,
            eventTimeEpochMs = fixedNowMs,
            retrievedAtEpochMs = fixedNowMs,
            expiresAtEpochMs = fixedNowMs + 60_000L,
            sourceUrl = "https://example.org",
            attribution = "Local",
            licenseInfo = "Local"
        )

        val twin = DigitalTwinEngine.buildRegionalTwin(
            latitude = 14.5995,
            longitude = 120.9842,
            elevationMeters = 16,
            allEntities = entities,
            compareCountryIso = "JP",
            nowEpochMs = fixedNowMs
        )
        assertTrue(twin.regionName.contains("Philippines"))
        assertTrue(twin.datasetComparison.isNotEmpty())
        assertEquals(0, twin.activeHazardCounts.values.sum())

        val ai = WorldMonitorAiAnalyst.analyze(
            userQuery = "What infrastructure and hazards exist near Manila?",
            allEntities = entities,
            correlations = emptyList(),
            providerStates = listOf(
                WorldProviderRuntimeState(providerId = "wm.disaster.usgs.day", enabled = true, status = ProviderSyncStatus.OFFLINE)
            ),
            focusLat = 14.5995,
            focusLon = 120.9842,
            nowEpochMs = fixedNowMs
        )
        assertTrue(ai.verifiedObservations.none { it.freshness == FreshnessClass.SIMULATED })
        assertTrue(ai.uncertaintiesAndGaps.any { it.contains("offline", ignoreCase = true) })
        assertTrue(ai.markdownReport.contains("## 1. Verified Observations"))
        assertTrue(ai.markdownReport.contains("## 2. Uncertainties & Coverage Gaps"))
        assertTrue(ai.markdownReport.contains("## 3. Analytical Inferences & Hypotheses"))
    }

    @Test
    fun testSimulationLabStampsExperimentalMetadata() {
        val baseline = BaselineReferenceStore.buildBaselineEntities(fixedNowMs)
        SimulationKind.entries.forEach { kind ->
            val result = SimulationLabEngine.runScenario(
                kind = kind,
                centerLat = 14.5995,
                centerLon = 120.9842,
                intensityOrRadiusParameter = if (kind == SimulationKind.FLOOD_SURGE) 4.0 else 200.0,
                referenceEntities = baseline
            )
            assertTrue(result.active)
            assertEquals(FreshnessClass.SIMULATED, result.freshness)
            assertEquals(VerificationStatus.EXPERIMENTAL_SIMULATION, result.verificationStatus)
            assertTrue(result.findings.isNotEmpty())
        }
    }

    @Test
    fun testWatchtowerGeofenceMatchingAndDeduplication() {
        val region = WatchtowerRegion(
            id = "w1",
            name = "Manila Watch",
            centerLat = 14.5995,
            centerLon = 120.9842,
            radiusKm = 250.0,
            categories = setOf(WorldIntelCategory.DISASTER),
            minSeverity = WorldSeverity.HIGH,
            createdAtEpochMs = fixedNowMs
        )
        val matchingQuake = WorldEntity(
            id = "q1",
            providerId = "wm.disaster.usgs.day",
            providerName = "USGS",
            category = WorldIntelCategory.DISASTER,
            subType = "Earthquake",
            title = "M 6.2 Batangas",
            summary = "Strong shaking",
            latitude = 13.90,
            longitude = 120.90,
            severity = WorldSeverity.HIGH,
            freshness = FreshnessClass.LIVE,
            geoAccuracy = GeoAccuracy.SEISMIC_EPICENTER,
            confidence = 96,
            verificationStatus = VerificationStatus.OFFICIAL_AGENCY,
            eventTimeEpochMs = fixedNowMs,
            retrievedAtEpochMs = fixedNowMs,
            expiresAtEpochMs = fixedNowMs + 3_600_000L,
            sourceUrl = "https://earthquake.usgs.gov",
            attribution = "USGS",
            licenseInfo = "Public Domain"
        )
        val distantQuake = matchingQuake.copy(id = "q2", latitude = 35.6, longitude = 139.6)
        val simulatedQuake = matchingQuake.copy(id = "q3", freshness = FreshnessClass.SIMULATED)

        val firstPass = WatchtowerManager.findMatchingAlerts(
            regions = listOf(region),
            entities = listOf(matchingQuake, distantQuake, simulatedQuake),
            nowEpochMs = fixedNowMs
        )
        assertEquals(1, firstPass.size)
        assertEquals("w1", firstPass[0].regionId)
        assertEquals("q1", firstPass[0].entityId)

        val secondPass = WatchtowerManager.findMatchingAlerts(
            regions = listOf(region),
            entities = listOf(matchingQuake),
            alreadySeenPairs = setOf("w1:q1"),
            nowEpochMs = fixedNowMs
        )
        assertTrue(secondPass.isEmpty())
    }

    @Test
    fun testJsBridgeAndTileServerSecurityValidation() {
        var capturedViewport: WorldViewport? = null
        var capturedEntityId: String? = null
        val bridge = WorldMonitorJsBridge(object : WorldMonitorJsBridge.Listener {
            override fun onGlobeReady(webglSupported: Boolean, cesiumVersion: String) {}
            override fun onGlobeError(errorMessage: String) {}
            override fun onViewportChanged(viewport: WorldViewport) { capturedViewport = viewport }
            override fun onEntitySelected(entityId: String) { capturedEntityId = entityId }
            override fun onBuildingSelected(id: String, name: String, heightMeters: Double, heightEstimated: Boolean, lat: Double, lon: Double) {}
            override fun onLocationSelected(lat: Double, lon: Double, terrainElevationMeters: Int, countryHint: String) {}
            override fun onSatellitePassesComputed(entityId: String, passes: List<SatellitePassPrediction>) {}
        })

        bridge.onViewportChanged("""{"centerLat":145.0,"centerLon":120.0,"cameraAltitudeMeters":1000}""")
        assertEquals(null, capturedViewport)

        bridge.onViewportChanged("""{"centerLat":14.5995,"centerLon":120.9842,"cameraAltitudeMeters":250000}""")
        assertNotNull(capturedViewport)

        bridge.onEntitySelected("""{"entityId":"<script>alert(1)</script>"}""")
        assertEquals(null, capturedEntityId)

        bridge.onEntitySelected("""{"entityId":"usgs:us7000test"}""")
        assertEquals("usgs:us7000test", capturedEntityId)

        assertTrue(WorldMonitorJsBridge.isAllowedHttpsSourceUrl("https://earthquake.usgs.gov/event"))
        assertFalse(WorldMonitorJsBridge.isAllowedHttpsSourceUrl("javascript:alert(1)"))
        assertFalse(WorldMonitorJsBridge.isAllowedHttpsSourceUrl("http://insecure.example.com"))

        assertTrue(WorldMonitorAssetAndTileServer.isAllowedTileHost("services.arcgisonline.com"))
        assertTrue(WorldMonitorAssetAndTileServer.isAllowedTileHost("gibs.earthdata.nasa.gov"))
        assertTrue(WorldMonitorAssetAndTileServer.isAllowedTileHost("s3.amazonaws.com"))
        assertFalse(WorldMonitorAssetAndTileServer.isAllowedTileHost("evil-tracker.example.com"))
        assertEquals("application/wasm", WorldMonitorAssetAndTileServer.resolveMimeType("/vendor/cesium/ThirdParty/draco.wasm", null))
        assertEquals("application/xml", WorldMonitorAssetAndTileServer.resolveMimeType("/vendor/cesium/Assets/Textures/NaturalEarthII/tilemapresource.xml", null))
        assertEquals("image/ktx2", WorldMonitorAssetAndTileServer.resolveMimeType("/vendor/cesium/Assets/Textures/sky.ktx2", null))
        assertEquals("text/javascript", WorldMonitorAssetAndTileServer.resolveMimeType("/assets/worldmonitor/worldmonitor.js", null))
    }

    @Test
    fun testDynamicFreshnessDecayOnCachedEntities() {
        val liveAircraft = WorldEntity(
            id = "opensky:test",
            providerId = "wm.aviation.opensky",
            providerName = "OpenSky",
            category = WorldIntelCategory.AVIATION,
            subType = "Aircraft",
            title = "TEST01",
            summary = "Airborne",
            latitude = 14.5,
            longitude = 121.0,
            severity = WorldSeverity.INFO,
            freshness = FreshnessClass.LIVE,
            geoAccuracy = GeoAccuracy.RADAR_TRANSPONDER,
            confidence = 88,
            verificationStatus = VerificationStatus.PUBLIC_TRANSPONDER_FEED,
            eventTimeEpochMs = fixedNowMs,
            retrievedAtEpochMs = fixedNowMs,
            expiresAtEpochMs = fixedNowMs + 3_600_000L,
            sourceUrl = "https://opensky-network.org",
            attribution = "OpenSky",
            licenseInfo = "OpenSky Terms"
        )
        val record = WorldEntityRecord.fromDomain(liveAircraft, "wdw4")

        assertEquals(FreshnessClass.LIVE, record.toDomain(fixedNowMs + 60_000L).freshness)
        assertEquals(FreshnessClass.NEAR_REAL_TIME, record.toDomain(fixedNowMs + 10 * 60_000L).freshness)
        assertEquals(FreshnessClass.DELAYED, record.toDomain(fixedNowMs + 90 * 60_000L).freshness)
        assertEquals(FreshnessClass.HISTORICAL, record.toDomain(fixedNowMs + 26 * 3_600_000L).freshness)
    }

    @Test
    fun testNativeGlobe3DProjectionAndInverseRayCast() {
        val cx = 540f
        val cy = 960f
        val rPx = 420f
        val camLat = 14.5995
        val camLon = 120.9842
        val pitch = -45.0
        val heading = 22.5

        val testPoints = listOf(
            14.5995 to 120.9842,
            35.6762 to 139.6503,
            -12.0 to 95.4,
            48.2 to 105.7
        )
        for ((lat, lon) in testPoints) {
            val sp = OrbitalAndGlobeMath.projectToScreen(
                latDeg = lat,
                lonDeg = lon,
                altitudeMeters = 0.0,
                camLatDeg = camLat,
                camLonDeg = camLon,
                pitchDeg = pitch,
                headingDeg = heading,
                projection = GlobeProjection.GLOBE_3D,
                cx = cx,
                cy = cy,
                radiusPx = rPx
            )
            assertTrue("Expected visible point ($lat, $lon)", sp.visible)
            val inv = OrbitalAndGlobeMath.unprojectFromScreen(
                px = sp.x,
                py = sp.y,
                camLatDeg = camLat,
                camLonDeg = camLon,
                pitchDeg = pitch,
                headingDeg = heading,
                projection = GlobeProjection.GLOBE_3D,
                cx = cx,
                cy = cy,
                radiusPx = rPx
            )
            assertNotNull(inv)
            assertTrue("Lat mismatch: ${inv!!.first} vs $lat", abs(inv.first - lat) < 0.05)
            assertTrue("Lon mismatch: ${inv.second} vs $lon", abs(inv.second - lon) < 0.05)
        }

        // Far-antipodal point (New York when centered on Manila) must be occluded on 3D globe
        val nyc = OrbitalAndGlobeMath.projectToScreen(
            40.7128, -74.0060, 0.0, camLat, camLon, -88.0, 0.0, GlobeProjection.GLOBE_3D, cx, cy, rPx
        )
        assertFalse(nyc.visible)
    }

    @Test
    fun testPureKotlinTlePropagationAndTopoJsonDecoding() {
        val l1 = "1 25544U 98067A   26312.50000000  .00016717  00000+0  10270-3 0  9993"
        val l2 = "2 25544  51.6416 247.4627 0006703 130.5360 325.0288 15.50103472421535"
        val parsed = OrbitalAndGlobeMath.parseTle("celestrak:25544", "ISS (ZARYA)", l1, l2)
        assertNotNull(parsed)

        val sv = OrbitalAndGlobeMath.propagateTle(parsed!!, fixedNowMs)
        assertTrue(sv.latitude in -52.5..52.5)
        assertTrue(sv.longitude in -180.0..180.0)
        assertTrue("Expected LEO altitude ~415km, got ${sv.altitudeMeters}", sv.altitudeMeters in 350_000.0..500_000.0)

        val track = OrbitalAndGlobeMath.buildOrbitTrack(parsed, fixedNowMs, stepMinutes = 2)
        assertTrue(track.size >= 48)

        val passes = OrbitalAndGlobeMath.predictPasses(parsed, 14.5995, 120.9842, fixedNowMs, hoursAhead = 36)
        assertTrue("Expected at least 1 ISS pass over Manila within 36h", passes.isNotEmpty())
        assertTrue(passes.first().maxElevationDeg >= 10.0)

        val topoFile = File("src/main/assets/worldmonitor/vendor/countries-110m.json")
            .takeIf { it.exists() }
            ?: File("app/src/main/assets/worldmonitor/vendor/countries-110m.json")
        assertTrue("countries-110m.json must exist", topoFile.exists())
        val arcs = OrbitalAndGlobeMath.decodeTopoJsonArcs(topoFile.readText(Charsets.UTF_8))
        assertEquals(595, arcs.size)
    }

    @Test
    fun testStreetLevelSlippyTileMathAndGeodesicRuler() {
        assertEquals(17, OrbitalAndGlobeMath.altitudeToTileZoom(500.0))
        assertEquals(14, OrbitalAndGlobeMath.altitudeToTileZoom(5_000.0))
        assertEquals(2, OrbitalAndGlobeMath.altitudeToTileZoom(12_000_000.0))

        val (tx, ty) = OrbitalAndGlobeMath.latLonToTileXY(14.5995, 120.9842, 12)
        val bounds = OrbitalAndGlobeMath.tileBoundsLatLon(12, tx, ty)
        assertTrue(14.5995 in bounds[1]..bounds[3])
        assertTrue(120.9842 in bounds[0]..bounds[2])

        // At street zoom (400m altitude), off-screen coordinates must safely clamp inside ±24,000 px
        val streetRadius = OrbitalAndGlobeMath.globeRadiusPx(1080f, 2200f, 400.0)
        assertTrue(streetRadius > 2_000_000f)
        val offscreen = OrbitalAndGlobeMath.projectToScreen(
            14.85, 121.35, 0.0, 14.5995, 120.9842, -52.0, 0.0, GlobeProjection.GLOBE_3D, 540f, 1100f, streetRadius
        )
        assertTrue(abs(offscreen.x) <= 24_001f && abs(offscreen.y) <= 24_001f)

        // Geodesic distance & dead-reckoning projection (Manila -> Tokyo ~2,995 km NE)
        val mnlTyoKm = OrbitalAndGlobeMath.geodesicDistanceKm(14.5995, 120.9842, 35.6762, 139.6503)
        assertTrue("Expected ~2995km, got $mnlTyoKm", mnlTyoKm in 2950.0..3050.0)
        val brg = OrbitalAndGlobeMath.initialBearingDeg(14.5995, 120.9842, 35.6762, 139.6503)
        assertTrue("Expected NE bearing ~36 deg, got $brg", brg in 30.0..45.0)
        val dest = OrbitalAndGlobeMath.projectAlongBearing(14.5995, 120.9842, brg, mnlTyoKm)
        assertTrue(abs(dest.first - 35.6762) < 0.25)
        assertTrue(abs(dest.second - 139.6503) < 0.25)
    }

    @Test
    fun testExpandedFreeKeylessProvidersAndNewNormalizers() {
        val providers = WorldMonitorProviderRegistry.providers
        assertTrue("Expected >= 49 providers, got ${providers.size}", providers.size >= 49)
        providers.filter { it.endpoint.isNotBlank() }.forEach { s ->
            assertTrue("Non-HTTPS endpoint in ${s.providerId}", s.endpoint.startsWith("https://"))
        }
        assertTrue(WorldMonitorAssetAndTileServer.isAllowedTileHost("a.tile.opentopomap.org"))
        assertTrue(WorldMonitorAssetAndTileServer.isAllowedTileHost("a.basemaps.cartocdn.com"))

        // 1. Spaceflight News API v4
        val snapiSpec = spec("wm.space.snapi")
        val snapiEnts = WorldMonitorNormalizers.normalize(
            snapiSpec,
            """{"results":[{"id":901,"title":"Starship Flight Launch from Boca Chica","summary":"Orbital test flight","url":"https://spaceflightnow.com/test","news_site":"Spaceflight Now","published_at":"2026-11-08T10:00:00Z"}]}""",
            fixedNowMs
        )
        assertEquals(1, snapiEnts.size)
        assertEquals(WorldIntelCategory.SPACE, snapiEnts.first().category)

        // 2. USGS Volcanoes
        val volcSpec = spec("wm.disaster.volcanoes")
        val volcEnts = WorldMonitorNormalizers.normalize(
            volcSpec,
            """[{"vnum":"332010","vname":"Kilauea","latitude":19.421,"longitude":-155.287,"colorCode":"ORANGE","alertLevel":"WATCH","obsAbbr":"HVO"}]""",
            fixedNowMs
        )
        assertEquals(1, volcEnts.size)
        assertEquals(WorldSeverity.HIGH, volcEnts.first().severity)

        // 3. UN ReliefWeb Disasters
        val rwSpec = spec("wm.disaster.reliefweb")
        val rwEnts = WorldMonitorNormalizers.normalize(
            rwSpec,
            """{"data":[{"id":"52001","fields":{"name":"Philippines: Super Typhoon Emergency","status":"alert","url":"https://reliefweb.int/disaster/52001","primary_country":{"iso3":"PHL","name":"Philippines"},"type":[{"name":"Tropical Cyclone"}],"date":{"created":"2026-11-08T00:00:00+00:00"}}}]}""",
            fixedNowMs
        )
        assertEquals(1, rwEnts.size)

        // 4. Open-Meteo Marine Waves
        val waveSpec = spec("wm.weather.waves")
        val waveEnts = WorldMonitorNormalizers.normalize(
            waveSpec,
            """{"current":{"wave_height":6.4,"wave_direction":45.0,"wave_period":11.2}}""",
            fixedNowMs
        )
        assertEquals(1, waveEnts.size)
        assertEquals(WorldSeverity.CRITICAL, waveEnts.first().severity)

        // 5. Open-Meteo 72h Future Forecast
        val fcSpec = spec("wm.weather.forecast72h")
        val times = (0 until 72).joinToString(",") { "\"2026-11-08T00:00\"" }
        val temps = (0 until 72).joinToString(",") { "29.5" }
        val winds = (0 until 72).joinToString(",") { "88.0" }
        val precs = (0 until 72).joinToString(",") { "80.0" }
        val fcEnts = WorldMonitorNormalizers.normalize(
            fcSpec,
            """{"hourly":{"time":[$times],"temperature_2m":[$temps],"wind_speed_10m":[$winds],"precipitation_probability":[$precs]}}""",
            fixedNowMs
        )
        assertEquals(5, fcEnts.size)
        assertEquals(FreshnessClass.ESTIMATED, fcEnts.first().freshness)

        // 6. URLhaus CSV
        val uhSpec = spec("wm.cyber.urlhaus")
        val uhEnts = WorldMonitorNormalizers.normalize(
            uhSpec,
            "# comment\n\"310001\",\"2026-11-08 11:00:00\",\"https://bad.ru/loader.bin\",\"online\",\"2026-11-08\",\"malware_download\",\"mozi,elf\",\"https://urlhaus.abuse.ch/url/310001/\",\"reporter\"",
            fixedNowMs
        )
        assertEquals(1, uhEnts.size)

        // 7. SANS ISC DShield
        val dsSpec = spec("wm.cyber.sans.dshield")
        val dsEnts = WorldMonitorNormalizers.normalize(
            dsSpec,
            """[{"ip":"185.220.101.1","count":15400,"attacks":920,"ascountry":"DE","asname":"ExampleNet"}]""",
            fixedNowMs
        )
        assertEquals(1, dsEnts.size)

        // 8. Tor Onionoo Relays
        val torSpec = spec("wm.cyber.tor.relays")
        val torEnts = WorldMonitorNormalizers.normalize(
            torSpec,
            """{"relays":[{"nickname":"FastExit01","fingerprint":"ABCDEF1234567890","latitude":50.1109,"longitude":8.6821,"country":"de","observed_bandwidth":65000000,"flags":["Exit","Fast","Running"]}]}""",
            fixedNowMs
        )
        assertEquals(1, torEnts.size)

        // 9. Wikipedia GeoSearch Local Landmarks
        val wikiSpec = spec("wm.infra.wikipedia.geo")
        val wikiEnts = WorldMonitorNormalizers.normalize(
            wikiSpec,
            """{"query":{"geosearch":[{"pageid":18957,"title":"Rizal Park","lat":14.5832,"lon":120.9794,"dist":180.5}]}}""",
            fixedNowMs
        )
        assertEquals(1, wikiEnts.size)
        assertEquals("Rizal Park", wikiEnts.first().title)
    }

    @Test
    fun testGlobal234CountriesAndLiveFeedsEndToEnd() {
        // 1. Verify expanded 234-country planetary baseline catalog
        val catalog = BaselineReferenceStore.load()
        assertTrue("Expected >= 230 countries & territories, got ${catalog.countries.size}", catalog.countries.size >= 230)
        assertTrue("Expected >= 65 international airports, got ${catalog.airports.size}", catalog.airports.size >= 65)
        assertTrue("Expected >= 45 global seaports, got ${catalog.seaports.size}", catalog.seaports.size >= 45)
        assertEquals("PH", catalog.countries.first().iso2)

        for (iso in listOf("PH", "US", "JP", "IS", "GL", "BR", "NG", "KE", "FJ", "UA", "AQ", "SG", "AU", "DE")) {
            assertNotNull("Expected country $iso in BaselineReferenceStore", BaselineReferenceStore.countryByIso(iso))
        }
        assertTrue("Offline search for Reykjavik must succeed", BaselineReferenceStore.searchOffline("Reykjavik").isNotEmpty())
        assertTrue("Offline search for Nuuk must succeed", BaselineReferenceStore.searchOffline("Nuuk").isNotEmpty())
        assertTrue("Offline search for Clark CRK must succeed", BaselineReferenceStore.searchOffline("CRK").isNotEmpty())
        assertEquals("IS", BaselineReferenceStore.nearestCountry(64.1466, -21.9426)?.iso2)

        // 2. Every non-news provider still avoids the retired HTTP 410 URLs. The exact count is
        // asserted once, in testExpandedFreeKeylessProvidersAndNewNormalizers.
        val providers = WorldMonitorProviderRegistry.providers
        assertEquals(WorldMonitorProviderRegistry.providers.map { it.providerId }.distinct().size, providers.size)
        providers.forEach { p ->
            assertFalse("Retired ReliefWeb v1 URL must not appear", p.endpoint.contains("api.reliefweb.int/v1"))
            assertFalse("Retired URLhaus CSV URL must not appear", p.endpoint.contains("urlhaus.abuse.ch/downloads/csv_recent"))
        }

        // 3. Verify GDACS 24h XML RSS parsing in reliefweb-disasters normalizer
        val gdacsRss = """
            <?xml version="1.0" encoding="utf-8"?>
            <rss version="2.0" xmlns:geo="http://www.w3.org/2003/01/geo/wgs84_pos#" xmlns:gdacs="http://www.gdacs.org">
              <channel>
                <item>
                  <title>Red earthquake alert (Magnitude 6.9M) in Japan</title>
                  <description>Earthquake of magnitude 6.9M in Honshu, Japan.</description>
                  <link>https://www.gdacs.org/report.aspx?eventtype=EQ&amp;eventid=1999999</link>
                  <geo:lat>38.297</geo:lat>
                  <geo:long>142.373</geo:long>
                  <gdacs:alertlevel>Red</gdacs:alertlevel>
                  <gdacs:country>Japan</gdacs:country>
                </item>
              </channel>
            </rss>
        """.trimIndent()
        val gdacsEnts = WorldMonitorNormalizers.normalize(spec("wm.disaster.reliefweb"), gdacsRss, fixedNowMs)
        assertEquals(1, gdacsEnts.size)
        assertEquals(WorldSeverity.CRITICAL, gdacsEnts.first().severity)
        assertEquals("JP", gdacsEnts.first().countryCode)

        // 4. Verify abuse.ch SSLBL live IPv4/CSV blacklist parsing in urlhaus-csv normalizer
        val sslblRaw = """
            # SSLBL Botnet C2 IP Blacklist
            2026-11-08 11:00:00,198.51.100.42,443
            203.0.113.77
        """.trimIndent()
        val sslblEnts = WorldMonitorNormalizers.normalize(spec("wm.cyber.urlhaus"), sslblRaw, fixedNowMs)
        assertEquals(2, sslblEnts.size)
        assertEquals(WorldIntelCategory.CYBER, sslblEnts.first().category)

        // 5. Verify OpenPhish live phishing feed geolocation
        val phishRaw = """
            https://secure-login-verify.jp/account
            https://update-token-portal.br/auth
        """.trimIndent()
        val phishEnts = WorldMonitorNormalizers.normalize(spec("wm.cyber.openphish"), phishRaw, fixedNowMs)
        assertEquals(2, phishEnts.size)
        assertEquals("JP", phishEnts[0].countryCode)
        assertEquals("BR", phishEnts[1].countryCode)

        // 6. Verify dynamic {country} RIPEstat BGP posture for Japan & Iceland
        val ripeJpRaw = """{"data":{"query_parameters":{"resource":"JP"},"resources":{"asn":["2497","2516","4713"],"ipv4":["1.0.16.0/20"],"ipv6":["2001:200::/32"]}}}"""
        val ripeJp = WorldMonitorNormalizers.normalize(spec("wm.cyber.ripestat"), ripeJpRaw, fixedNowMs, 35.6762, 139.6503)
        assertEquals(1, ripeJp.size)
        assertEquals("JP", ripeJp.first().countryCode)
        assertTrue(ripeJp.first().title.contains("Japan"))

        // 7. Verify batched multi-capital Open-Meteo JsonArray parsing
        val batchWeatherRaw = """
            [
              {"latitude":14.60,"longitude":120.98,"current":{"temperature_2m":30.8,"precipitation":0.2,"wind_speed_10m":18.0,"wind_direction_10m":90,"surface_pressure":1009.0,"cloud_cover":40,"weather_code":1}},
              {"latitude":64.15,"longitude":-21.94,"current":{"temperature_2m":2.4,"precipitation":1.1,"wind_speed_10m":34.0,"wind_direction_10m":210,"surface_pressure":994.0,"cloud_cover":88,"weather_code":61}}
            ]
        """.trimIndent()
        val batchWx = WorldMonitorNormalizers.normalize(spec("wm.weather.global.capitals"), batchWeatherRaw, fixedNowMs)
        assertEquals(2, batchWx.size)

        // 8. Verify BBC World News RSS does not collide with UN News RSS
        val bbcRss = """
            <rss version="2.0"><channel>
              <item><title>Iceland volcanic fissure monitored near Grindavik</title><description>Scientists in Iceland monitor seismic swarm; Reykjavik remains unaffected.</description><link>https://www.bbc.com/news/world-europe-1001</link></item>
            </channel></rss>
        """.trimIndent()
        val bbcEnts = WorldMonitorNormalizers.normalize(spec("wm.news.bbc.world"), bbcRss, fixedNowMs)
        assertEquals(1, bbcEnts.size)
        assertTrue(bbcEnts.first().id.startsWith("news:bbc:"))
        assertEquals("IS", bbcEnts.first().countryCode)
    }

    // ==================== GLOBAL NEWS PIPELINE ====================

    @Test
    fun testNewsFeedParserReadsRssRdfAndAtom() {
        val rss = """
            <rss><channel><title>Channel title that must not leak</title>
              <item><title>First headline</title><link>https://example.org/a</link>
                <description>Plain summary</description><pubDate>Fri, 13 Nov 2026 10:00:00 GMT</pubDate></item>
            </channel></rss>
        """.trimIndent()
        val rssItems = NewsFeedParser.parse(rss)
        assertEquals(1, rssItems.size)
        assertEquals("First headline", rssItems[0].title)
        assertEquals("https://example.org/a", rssItems[0].link)

        val rdf = """
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <item rdf:about="https://www.dw.com/en/story/a-1">
                <title>DW RDF story</title>
                <link>https://www.dw.com/en/story/a-1</link>
                <description>RDF summary</description>
                <dc:date>2026-11-14T11:40:00Z</dc:date>
                <dc:creator>DW Newsroom</dc:creator>
              </item>
            </rdf:RDF>
        """.trimIndent()
        val rdfItems = NewsFeedParser.parse(rdf)
        assertEquals(1, rdfItems.size)
        assertEquals("DW RDF story", rdfItems[0].title)
        assertEquals("DW Newsroom", rdfItems[0].author)
        assertNotNull("RFC-3339 date in dc:date must parse", rdfItems[0].publishedEpochMs)

        val atom = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Atom channel</title>
              <entry>
                <title>Atom story</title>
                <link rel="self" href="https://example.org/feed"/>
                <link rel="alternate" href="https://example.org/atom-story"/>
                <summary>Atom summary</summary>
                <published>2026-11-14T09:00:00Z</published>
              </entry>
            </feed>
        """.trimIndent()
        val atomItems = NewsFeedParser.parse(atom)
        assertEquals(1, atomItems.size)
        assertEquals("https://example.org/atom-story", atomItems[0].link)
        assertNotNull(atomItems[0].publishedEpochMs)

        // Structural strictness: junk is dropped, never guessed.
        assertEquals(0, NewsFeedParser.parse("<rss><channel><title>no items here</title></channel></rss>").size)
        assertEquals(0, NewsFeedParser.parse("").size)
        assertEquals(0, NewsFeedParser.parse("<html><body>captive portal</body></html>").size)
        val untitled = "<rss><channel><item><link>https://example.org/x</link></item></channel></rss>"
        assertEquals(0, NewsFeedParser.parse(untitled).size)
    }

    @Test
    fun testNewsFeedParserDecodesEntitiesAndRejectsInventedDates() {
        val feed = """
            <rss><channel><item>
              <title>Caf&#233; &#x2014; prices &amp; jobs</title>
              <link>https://example.org/b</link>
              <description>&lt;b&gt;Escaped&lt;/b&gt; HTML &#8212; quoted &quot;text&quot;</description>
            </item></channel></rss>
        """.trimIndent()
        val item = NewsFeedParser.parse(feed).single()
        assertEquals("Café — prices & jobs", item.title)
        assertEquals("Escaped HTML — quoted \"text\"", item.summary)
        assertEquals(null, item.publishedEpochMs)
        assertEquals(null, NewsFeedParser.parseDate("not a date"))
        assertNotNull(NewsFeedParser.parseDate("2026-11-14"))
    }

    @Test
    fun testNewsParserKeepsTimezonesStrictInsteadOfGuessing() {
        // A partial SimpleDateFormat match used to drop "+0800" and read Manila wall-clock time as
        // UTC, shifting every Rappler item eight hours into the future.
        val manila = requireNotNull(NewsFeedParser.parseDate("Sat, 14 Nov 2026 21:34:39 +0800"))
        assertEquals(1_794_663_279_000L, manila)
        // Named zones must still work.
        val gmt = requireNotNull(NewsFeedParser.parseDate("Sat, 14 Nov 2026 14:20:57 GMT"))
        assertEquals(1_794_666_057_000L, gmt)
        // RDF / Dublin Core.
        val dc = requireNotNull(NewsFeedParser.parseDate("2026-11-14T11:40:00Z"))
        assertEquals(1_794_656_400_000L, dc)
        // No zone at all is ambiguous, so it must not be silently assumed to be UTC.
        assertEquals(null, NewsFeedParser.parseDate("14 Nov 2026 21:34:39"))
        assertEquals(null, NewsFeedParser.parseDate("yesterday"))
    }

    @Test
    fun testNewsParsingFailsHonestlyWhenTheFeedIsNotANewsDocument() {
        val consentPage = "<!DOCTYPE html><html><body>Please enable JavaScript</body></html>"
        assertEquals(0, WorldMonitorNormalizers.normalize(spec("wm.news.aljazeera"), consentPage, fixedNowMs).size)
        assertFalse(
            "an HTML error page must not become a headline",
            WorldMonitorNormalizers.normalize(spec("wm.news.dw.world"), consentPage, fixedNowMs).isNotEmpty()
        )
    }

    @Test
    fun testAnOutletThatAnswersWithAPageIsReportedNotShownAsActive() {
        // A 200 with a consent wall used to be recorded as OK with 0 items, which the Global News
        // desk could only show as an unexplained empty list. The sync engine now inspects the body:
        // the note must be produced for an HTML page and must stay silent for a real feed (an outlet
        // is allowed to have zero current items).
        val spec = spec("wm.news.bbc.world")
        val noteFor = { body: String -> WorldMonitorSyncEngine.unreadableBodyNote(spec, body) }

        assertNotNull("an HTML consent wall must be explained",
            noteFor("<!DOCTYPE html><html><body>Before you continue</body></html>"))
        assertTrue("the explanation must name the cause",
            noteFor("<html><body>captcha</body></html>")!!.contains("HTML page"))
        assertNotNull("a non-feed body on a news provider must be explained",
            noteFor("Service temporarily unavailable"))
        assertEquals("an empty body must be explained", true, noteFor("   ")!!.contains("Empty response"))
        assertEquals("a real RSS document must not be flagged", null,
            noteFor("<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>x</title></channel></rss>"))
        assertEquals("an RDF document must not be flagged", null,
            noteFor("<rdf:RDF xmlns:rdf=\"x\"><channel><title>x</title></channel></rdf:RDF>"))
        // A JSON document from a JSON provider is normal and never flagged...
        val jsonSpec = spec("wm.disaster.usgs.day")
        assertEquals("a JSON provider must not be flagged", null,
            WorldMonitorSyncEngine.unreadableBodyNote(jsonSpec, "{\"features\":[]}"))
        // ...but a news outlet that answers in JSON has no headline in it, so it is explained.
        assertNotNull("JSON from a news outlet must still be explained",
            noteFor("{\"ok\":true}"))
    }

    @Test
    fun testEveryNewsProviderDeclaresAReachableKeylessEndpoint() {
        val news = WorldMonitorProviderRegistry.providers.filter { it.category == WorldIntelCategory.NEWS }
        assertTrue("Global News must ship more than one outlet", news.size >= 4)
        for (spec in news) {
            assertTrue("${spec.providerId} must be HTTPS", spec.endpoint.startsWith("https://"))
            assertEquals(ProviderAuthRequirement.NONE_PUBLIC, spec.authRequirement)
            assertEquals("news-rss", spec.normalizerId)
            assertTrue("${spec.providerId} must be enabled by default", spec.defaultEnabled)
            assertTrue("${spec.providerId} must be implemented", spec.implemented)
            assertTrue("${spec.providerId} summary must not be blank", spec.attribution.isNotBlank())
        }
        // The retired endpoint must not silently come back.
        assertTrue(
            "GDELT 2.0 Geo no longer answers keyless requests and must not be listed",
            news.none { it.endpoint.contains("gdeltproject.org/api/v2/geo") }
        )
    }

}
