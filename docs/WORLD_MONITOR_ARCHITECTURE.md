# OMNISHIELD X INFINITY — WORLD MONITOR 2035 Architecture & Source Ledger

World Monitor 2035 is an additive, offline-resilient **3D Digital Earth & Multi-Domain Global Intelligence Platform** embedded inside the existing AdShield Pro Android APK (`com.adshield.pro`).

## 1. Non-Destructive Integration Guarantees
- **Navigation & Shell Preserved**: All 13 existing `MainNavDestination` tabs, `CyberBottomNav`, and isolated `*Destination` composables in `MainActivity.kt` remain intact. World Monitor 2035 opens additively via a dedicated `worldMonitorOpen` full-screen overlay (matching `ConnectorRegistryScreen`), accessible from the **Intel Command Panel**, **Home Dashboard**, and **Watchtower Notification** deep links.
- **Database Isolation**: World Monitor persists cached entities, kinematic trails, provider sync metadata, Watchtower geofences, and alert history in an isolated Room database (`world_monitor_2035.db`), leaving `AdShieldDatabase` v6 (`adshield_pro_v2.db`) untouched with zero WAL contention against `AdShieldVpnService`.
- **No Mandatory VPN or Cloud Backend**: All 26 default-enabled providers connect directly over HTTPS from the user's device without requiring the local DNS VPN to be active, without proprietary proxy servers, and without any paid or cloud AI service.

## 2. 3D Digital Earth & WebView Security Architecture
- **Bundled Open-Source Runtime (`app/src/main/assets/worldmonitor/vendor/`)**:
  - `cesium@1.147.0` (`Apache-2.0`) — WebGL 3D globe, Columbus 2.5D, and 2D Mercator projections; `Cesium.Ion.defaultAccessToken = ""` (zero Cesium Ion dependency).
  - `satellite.js@6.0.1` (`MIT`) — Client-side SGP4/SDP4 orbital propagation, ground tracks, and 24-hour overhead pass predictions.
  - `topojson-client@3.1.0` (`ISC`) + `world-atlas@2.0.2` (`ISC` / Natural Earth Public Domain) — Offline sovereign country borders.
- **Keyless 3D Terrain & Imagery**:
  - **Terrain**: Custom heightmap provider decoding public AWS Open Data Mapzen Terrarium elevation PNG tiles (`(R * 256 + G + B / 256) - 32768` meters MSL).
  - **Basemaps & Overlays**: Esri World Imagery, OpenStreetMap Standard, CARTO Dark Matter, NASA GIBS Blue Marble / VIIRS True Color (including dual-date split-screen historical comparison), NASA GIBS MODIS Cloud Top Temperature, and RainViewer animated global precipitation radar.
  - **120 MB Persistent Tile Cache**: `WorldMonitorAssetAndTileServer` proxies allowlisted HTTPS tile hosts through an OkHttp disk cache (`worldmonitor_tiles_v1`) so visited 3D terrain and satellite imagery render offline.
- **Hardened JS <-> Kotlin Bridge (`WorldMonitorJsBridge`)**:
  - `WebSettings`: `allowFileAccess = false`, `allowContentAccess = false`, `mixedContentMode = MIXED_CONTENT_NEVER_ALLOW`, `setSupportMultipleWindows(false)`, strict CSP in `index.html`.
  - Inbound `@JavascriptInterface` payloads enforce a 16 KB ceiling, strict JSON object parsing, finite WGS84 bounds (`[-90, 90]`, `[-180, 180]`), identifier regex whitelisting, and HTTPS-only external source URLs.
  - Outbound Kotlin -> JS commands are UTF-8 Base64 encoded (`window.WorldMonitorApp.receiveBase64(...)`) to eliminate script injection vectors.

## 3. Multi-Domain Provider Ledger (Categories A–H)

| ID | Category | Provider | Auth | Default | Refresh / Gap | License & Attribution |
|---|---|---|---|---|---|---|
| `wm.aviation.opensky` | Aviation | OpenSky Network ADS-B | Keyless (Optional `wm.cred.opensky`) | On | 60s / 30s | OpenSky Terms (Non-commercial OSINT) |
| `wm.aviation.adsblol` | Aviation | ADSB.lol Community Emergency Squawks | Keyless | On | 60s / 30s | ODbL / ADSB.lol Community |
| `wm.aviation.ourairports` | Aviation | OurAirports Global Hub Catalog | Keyless | On | 7d / 1h | Public Domain (CC0) |
| `wm.maritime.digitraffic` | Maritime | Fintraffic Digitraffic Live AIS | Keyless | On | 2m / 60s | CC BY 4.0 · Fintraffic |
| `wm.maritime.ndbc` | Maritime | NOAA NDBC Global Ocean Buoys & Ships | Keyless | On | 15m / 5m | US Public Domain (NOAA) |
| `wm.maritime.aisstream` | Maritime | AISStream Global AIS (WebSocket) | Required Key (`wm.cred.aisstream`) | Off | External | Requires user key & WebSocket stream proxy |
| `wm.space.iss` | Space | WhereTheISS.at Live ISS Telemetry | Keyless | On | 20s / 10s | Open API (WTIA) |
| `wm.space.celestrak.stations` | Space | CelesTrak Space Stations (3LE) | Keyless | On | 6h / **2h min** | CelesTrak Public GP Policy |
| `wm.space.celestrak.weather` | Space | CelesTrak Weather & Earth Obs (3LE) | Keyless | On | 6h / **2h min** | CelesTrak Public GP Policy |
| `wm.space.celestrak.gnss` | Space | CelesTrak Navigation Satellites (3LE) | Keyless | On | 12h / **2h min** | CelesTrak Public GP Policy |
| `wm.space.launches` | Space | The Space Devs — Upcoming Launches | Keyless | On | 30m / 10m | CC BY-NC-SA 4.0 (Free Tier) |
| `wm.space.swpc.kp` | Space | NOAA SWPC Planetary K-Index | Keyless | On | 15m / 5m | US Public Domain (NOAA SWPC) |
| `wm.space.swpc.alerts` | Space | NOAA SWPC Solar & Geomagnetic Alerts | Keyless | On | 15m / 5m | US Public Domain (NOAA SWPC) |
| `wm.disaster.usgs.day` | Disaster | USGS Global Earthquakes (24h M2.5+) | Keyless | On | 5m / 1m | US Public Domain (USGS) |
| `wm.disaster.emsc` | Disaster | SeismicPortal / EMSC Global Quakes | Keyless | On | 5m / 2m | EMSC Open Access |
| `wm.disaster.eonet` | Disaster | NASA EONET Active Natural Events | Keyless | On | 15m / 5m | US Public Domain (NASA) |
| `wm.disaster.gdacs` | Disaster | UN/EC GDACS Global Disaster Alerts | Keyless | On | 15m / 5m | GDACS Open Humanitarian |
| `wm.disaster.firms` | Disaster | NASA FIRMS VIIRS Thermal Hotspots | Required Key (`wm.cred.nasa_firms`) | Off | 30m / 10m | US Public Domain (Free NASA `MAP_KEY`) |
| `wm.weather.openmeteo` | Weather | Open-Meteo Global Hub Synoptic Grid | Keyless | On | 20m / 5m | CC BY 4.0 (Open-Meteo) |
| `wm.weather.airquality` | Weather | Open-Meteo CAMS Global Air Quality | Keyless | On | 30m / 10m | CC BY 4.0 (Copernicus CAMS) |
| `wm.weather.marine` | Weather | Open-Meteo GloFAS River Discharge | Keyless | On | 1h / 15m | CC BY 4.0 (Copernicus GloFAS) |
| `wm.weather.nws` | Weather | NOAA NWS Active Severe Alerts | Keyless | On | 10m / 2m | US Public Domain (NOAA NWS) |
| `wm.weather.rainviewer` | Weather | RainViewer Global Radar Composite | Keyless | On | 10m / 3m | RainViewer Public Radar API |
| `wm.cyber.cisa.kev` | Cyber | CISA Known Exploited Vulnerabilities | Keyless | On | 6h / 30m | US Public Domain (CISA) |
| `wm.cyber.feodo` | Cyber | Abuse.ch Feodo Botnet C2 Tracker | Keyless | On | 30m / 10m | CC0 / Abuse.ch |
| `wm.cyber.ioda` | Cyber | IODA Internet Outage Detection | Keyless | On | 15m / 5m | Georgia Tech / CAIDA IODA |
| `wm.cyber.ripestat` | Cyber | RIPEstat Global BGP Routing Status | Keyless | On | 15m / 5m | RIPE NCC Open Data |
| `wm.news.bbc.world` | News | BBC News — World (RSS) | Keyless | On | 10m / 2m | BBC public RSS |
| `wm.news.aljazeera` | News | Al Jazeera English — All News (RSS) | Keyless | On | 10m / 2m | Al Jazeera public RSS |
| `wm.news.dw.world` | News | Deutsche Welle — World (RDF) | Keyless | On | 15m / 3m | DW public RSS |
| `wm.news.un` | News | UN News — Global (RSS) | Keyless | On | 20m / 5m | UN News public RSS |
| `wm.news.rappler` | News | Rappler — Philippines & World (RSS) | Keyless | On | 15m / 3m | Rappler public RSS |
| `wm.infra.baseline` | Infrastructure | Bundled Global Critical Infra Reference | Offline Asset | On | Bundled | CC0 / Public Domain / CC BY 4.0 |
| `wm.infra.overpass` | Infrastructure | OpenStreetMap Overpass 3D Buildings & Infra | Keyless | On (Zoomed) | 10m / 30s | ODbL © OpenStreetMap contributors |

## 4. Epistemic Honesty & Known Limitations
- **Dynamic Staleness Decay**: Cached `LIVE` records automatically decay to `NEAR_REAL_TIME` (>5 min), `DELAYED` (>30 min), and `HISTORICAL` (>6 hours) when read offline so stale aircraft or ship positions are never presented as real-time.
- **Coarse Cyber & News Geolocation**: Cyber advisories (`wm.cyber.cisa.kev`, `wm.cyber.ioda`, `wm.cyber.ripestat`) carry `ASN_COUNTRY_LEVEL` accuracy tags rather than street-level coordinates. News items carry `ADMIN_CENTROID` when the headline names a mapped country, and the new `SOURCE_DESK` tag when it does not — the point is then the outlet's own dateline (BBC London, Al Jazeera Doha, DW Bonn, UN New York, Rappler Manila) and the entity summary says so in words. Coordinates are never jittered to imply a specific street address.
- **Commercial Global AIS & Live Satellite Video**: Keyless open AIS (`wm.maritime.digitraffic`) covers Baltic/Nordic waters while `wm.maritime.ndbc` covers global ocean buoys/SHIP reports; global satellite AIS requires commercial licensing or a user-configured AISStream WebSocket proxy. Real-time live video from Earth-observation satellites or private CCTV networks is not publicly available and is never fabricated.

## 5. Global News layer — what was broken and what now holds

The News layer used to be listed as live while producing nothing. Three independent faults had to
be fixed; each one alone is enough to empty the layer, which is why the symptom ("I cannot see the
news") looked like a single bug.

### 5.1 The source list contained an endpoint that no longer answers

`wm.news.gdelt` pointed at `https://api.gdeltproject.org/api/v2/geo/geo`. A keyless request to that
path returns **HTTP 404** (the 2.0 Geo API was retired), so the provider failed on every sync and
contributed zero rows. It also had `refreshIntervalMs = 20m`, so the failure repeated all day while
the Sources panel reported an error the user had no reason to open.

The registry now lists five outlets that answer a plain keyless HTTPS `GET` with a real feed
document: BBC World, Al Jazeera English, Deutsche Welle World, UN News and Rappler. Every one is
`NONE_PUBLIC`, `implemented = true`, `defaultEnabled = true`, and probed by
`tools/probe_intel_endpoints.py` (module `NEWS`) so a provider that starts returning a consent page
is caught in CI rather than in the app.

### 5.2 The RSS reader paired titles with the wrong links

The old `parseUnNewsRss` searched the **whole document** for `<item>` with one regex and then
searched the same whole document for `<title>`, `<description>` and `<link>`. On any feed with a
channel-level `<title>` (all of them), on Atom (which puts the URL in an attribute), or on RDF/DC
(which uses `<dc:date>`, not `<pubDate>`), it read the channel's title or the next item's link.
Items were dropped or given a link to the section front page.

`NewsFeedParser` replaces it:

| Property | Behaviour |
| --- | --- |
| Item isolation | `<item>` / `<entry>` blocks are matched first; every field is read from inside its own block, so a channel title can never leak into an item |
| Formats | RSS 2.0, RDF / RSS 1.0 (`dc:date`, `dc:creator`), Atom 1.0 (`<link rel="alternate" href>`, `<published>`) |
| Text hygiene | CDATA unwrapped, named and numeric entities decoded, HTML stripped from summaries; URLs are never HTML-stripped |
| Images | `media:content`, `media:thumbnail`, `enclosure[type=image]` |
| Dates | RFC-822 and ISO-8601 only, and the pattern must consume the **whole** string. An ambiguous or unknown value returns `null` — it is never replaced with "now", which is what made week-old articles look like breaking news |
| Failure | A consent page, error document, or a document with no `<item>` yields zero rows instead of invented ones |

The reported time zone bug is worth calling out: `SimpleDateFormat("…zzz")` partially consumes
`+0800` and stops, so Rappler's `…21:34:39 +0800` was read as 21:34 UTC — eight hours in the
future. Numeric offsets are now parsed before named zones and a partial match is rejected.

### 5.3 The globe never drew the NEWS category

`worldmonitor.js` creates one Cesium `CustomDataSource` per key of `CATEGORY_COLORS`, and
`addOrReplaceEntities` skips any entity whose category has no data source
(`const ds = state.dataSources[cat]; if (!ds) continue;`). `NEWS` (and `CYBER`) were missing from
both `CATEGORY_COLORS` and `layerVisibility`, so even a correctly parsed headline was silently
dropped before rendering. Both categories now have a colour, a visibility entry and their own
tactical marker (pennant for news, octagon for cyber).

### 5.4 The headlines are now readable in the app

The 3D globe shows a marker per story, which is not how anyone reads news. The deck now has a
**Global News** tab:

- newest first, one row per distinct headline, outlet filter chips, live count (`n headlines from
  m/5 live outlets`);
- severity and freshness badges, the outlet's own publication time, the place the point was
  derived from, and a **Read at source ↗** button that opens the article in the system browser
  (HTTPS-only, validated by `WorldMonitorJsBridge.isAllowedHttpsSourceUrl`);
- tapping a row centres the globe on the story and opens the dossier card;
- outlets that returned nothing are listed by name with their runtime status and error message, so
  a blocked network never looks like "there is no news today".

That last point needed a second fix below the UI. A provider that answered **HTTP 200** with a
consent wall, a captcha or changed markup was recorded as `OK` with zero items, which is
indistinguishable from a quiet news day. `WorldMonitorSyncEngine` now inspects the body when
nothing parsed: an HTML page, an empty body, or a document that is not RSS/Atom/RDF is stored as an
error with the reason ("Outlet answered with an HTML page instead of a feed"), while a genuine
feed that happens to have no current items still counts as a successful sync.

### 5.5 Why the country matcher changed

The previous matcher returned the first country in the baseline whose name appeared in the text.
With a 234-entry list ordered by name, "Nigeria" matched before "Niger", "Sudan" before "South
Sudan", and "Congo" before "DR Congo" — misplacing stories by thousands of kilometres. It also
skipped every country whose name is shorter than four characters, so a story about Chad, Cuba,
Fiji, Iran, Iraq, Laos, Mali, Oman, Peru or Togo could never be placed anywhere but the dateline.

Matching is now word-bounded and longest-name-first, so the most specific candidate wins and a
name only counts as a whole word. The capital fallback ("Manila summit opens") runs only when the
item names no country at all — a story that says "Japan envoy visits Manila" belongs to Japan.

### 5.6 Verification

| Check | Where |
| --- | --- |
| Feed structure, entities, images, dates, junk documents | `WorldMonitorTestSuite.testNewsFeedParserReadsRssRdfAndAtom`, `…DecodesEntitiesAndRejectsInventedDates` |
| Time-zone strictness (Manila `+0800`, `GMT`, `Z`, ambiguous rejection) | `WorldMonitorTestSuite.testNewsParserKeepsTimezonesStrictInsteadOfGuessing` |
| Every outlet is HTTPS, keyless, implemented, routed to `news-rss` | `WorldMonitorTestSuite.testEveryNewsProviderDeclaresAReachableKeylessEndpoint` |
| Consent pages never become headlines, and are reported instead of counted as success | `WorldMonitorTestSuite.testNewsParsingFailsHonestlyWhenTheFeedIsNotANewsDocument`, `…testAnOutletThatAnswersWithAPageIsReportedNotShownAsActive` |
| Short country names match; names match as words, not substrings | `WorldMonitorTestSuite.testNormalizersAcrossAllEightIntelligenceDomains` |
| Registry / normalizer / globe / UI wiring, and the retired GDELT endpoint staying out | `tests/unit/worldmonitor_news.test.mjs` (runs in the ordinary web CI job) |
| Endpoint reachability | `tools/probe_intel_endpoints.py` module `NEWS`, recorded in `docs/INTEL_LIVE_SOURCES.md` |

The News layer still has honest limits: the feeds are headline + summary + link only (full article
text is not fetched or republished), placement is a country centroid or the outlet's dateline, an
item whose feed omits a timestamp is tagged `HISTORICAL` rather than being presented as fresh, and
an outlet that blocks this network shows a named error in the Global News tab instead of silently
disappearing.

Reading does not require leaving the app: each row carries the full headline, the full summary the
outlet published, the outlet, its own publication time and its placement basis. "Read at source"
is an optional extra for the full article, and is only offered for HTTPS URLs that pass
`WorldMonitorJsBridge.isAllowedHttpsSourceUrl`.
