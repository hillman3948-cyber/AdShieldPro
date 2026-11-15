/**
 * OmniShield X Infinity — World Monitor 2035
 * Resilient Dual-Path 3D Digital Earth Engine:
 *   1. Primary: CesiumJS 1.147.0 WebGL2 / WebGL1 3D Globe (synchronous bundled NaturalEarthII
 *      offline base layer, direct-GPU PolylineCollection country borders, mobile-safe MSAA=1,
 *      continuous adaptive render loop, ResizeObserver, SGP4 satellite propagation).
 *   2. Self-Healing Failsafe: Hardware-accelerated HTML5 3D Orthographic Spherical Globe Engine
 *      automatically engaged if WebGL context creation fails, scene.renderError fires, or
 *      WebWorker tile tessellation produces zero tiles on restricted OEM WebViews.
 */
(function () {
  'use strict';

  const BRIDGE_NAME = 'WorldMonitorBridge';
  const ICON_CACHE = new Map();
  const DEG_TO_RAD = Math.PI / 180.0;
  const RAD_TO_DEG = 180.0 / Math.PI;

  const CATEGORY_COLORS = {
    DISASTER: '#EF4444',
    WEATHER: '#38BDF8',
    AVIATION: '#22D3EE',
    MARITIME: '#3B82F6',
    SPACE: '#A855F7',
    CYBER: '#F43F5E',
    NEWS: '#FBBF24',
    INFRASTRUCTURE: '#10B981',
    CONFLICT_HUMANITARIAN: '#F59E0B'
  };

  const SEVERITY_COLORS = {
    CRITICAL: '#EF4444',
    HIGH: '#F97316',
    MODERATE: '#F59E0B',
    LOW: '#10B981',
    INFO: '#38BDF8'
  };

  const state = {
    engineMode: 'cesium', // 'cesium' | 'canvas3d'
    viewer: null,
    canvasEngine: null,
    ready: false,
    commandQueue: [],
    performanceMode: 'BALANCED',
    projection: 'GLOBE_3D',
    basemap: 'SATELLITE_ESRI',
    basemapOpacity: 1.0,
    stylePreset: 'CYBER_TACTICAL',
    terrainEnabled: false,
    bordersVisible: true,
    atmosphereEnabled: true,
    dayNightLighting: true,
    labelsVisible: true,
    buildings3DEnabled: true,
    clusteringEnabled: true,
    heatmapEnabled: false,
    cloudsEnabled: false,
    cloudsOpacity: 0.52,
    radarEnabled: false,
    radarAnimating: false,
    radarOpacity: 0.58,
    radarFrames: [],
    radarFrameIndex: 0,
    radarTimer: null,
    imageryRefs: {
      offlineBase: null,
      primaryBase: null,
      labelsOverlay: null,
      cloudsOverlay: null,
      radarOverlay: null,
      heatmapOverlay: null,
      splitLeft: null,
      splitRight: null
    },
    dataSources: {},
    bordersCollection: null,
    countryFeatures: [],
    countryRings: [],
    correlationsSource: null,
    watchtowerSource: null,
    simulationSource: null,
    buildingsSource: null,
    satellites: new Map(),
    selectedSatelliteId: null,
    orbitTimer: null,
    clockTimer: null,
    watchdogTimer: null,
    lastViewportSentAt: 0,
    allEntitiesCache: [],
    trailsCache: [],
    correlationsCache: [],
    watchRegionsCache: [],
    buildingsCache: [],
    simulationCache: null,
    layerVisibility: {
      DISASTER: true,
      WEATHER: true,
      AVIATION: true,
      MARITIME: true,
      SPACE: true,
      CYBER: true,
      NEWS: true,
      INFRASTRUCTURE: true,
      CONFLICT_HUMANITARIAN: true
    },
    layerOpacity: {
      DISASTER: 1.0,
      WEATHER: 1.0,
      AVIATION: 1.0,
      MARITIME: 1.0,
      SPACE: 1.0,
      CYBER: 1.0,
      NEWS: 1.0,
      INFRASTRUCTURE: 1.0,
      CONFLICT_HUMANITARIAN: 1.0
    }
  };

  function hideBootSplash() {
    const el = document.getElementById('wmBootSplash');
    if (el) el.classList.add('hidden');
  }

  function postToAndroid(method, payload) {
    try {
      const bridge = window[BRIDGE_NAME];
      if (bridge && typeof bridge[method] === 'function') {
        bridge[method](typeof payload === 'string' ? payload : JSON.stringify(payload));
      }
    } catch (err) {
      console.warn('Bridge call failed:', method, err && err.message ? err.message : err);
    }
  }

  function formatCoord(lat, lon) {
    const ns = lat >= 0 ? 'N' : 'S';
    const ew = lon >= 0 ? 'E' : 'W';
    return `${Math.abs(lat).toFixed(2)}°${ns} ${Math.abs(lon).toFixed(2)}°${ew}`;
  }

  function formatDistanceMeters(meters) {
    if (!Number.isFinite(meters) || meters <= 0) return '0 m';
    if (meters >= 1000) {
      const km = meters / 1000;
      return km >= 100 ? `${Math.round(km).toLocaleString()} km` : `${km.toFixed(1)} km`;
    }
    return `${Math.round(meters)} m`;
  }

  function utcYesterdayIso() {
    const d = new Date(Date.now() - 30 * 3600 * 1000);
    return d.toISOString().slice(0, 10);
  }

  function getTacticalIcon(category, severity, subType, opacity) {
    const key = `${category}|${severity}|${subType || ''}|${Math.round((opacity || 1) * 20)}`;
    if (ICON_CACHE.has(key)) return ICON_CACHE.get(key);

    const size = 44;
    const canvas = document.createElement('canvas');
    canvas.width = size;
    canvas.height = size;
    const ctx = canvas.getContext('2d');
    if (!ctx) return '';

    const cx = size / 2;
    const cy = size / 2;
    const catColor = CATEGORY_COLORS[category] || '#38BDF8';
    const sevColor = SEVERITY_COLORS[severity] || catColor;
    const primary = (severity === 'CRITICAL' || severity === 'HIGH') ? sevColor : catColor;

    ctx.globalAlpha = Math.max(0.15, Math.min(1.0, opacity || 1.0));

    const grad = ctx.createRadialGradient(cx, cy, 3, cx, cy, 19);
    grad.addColorStop(0, primary + 'AA');
    grad.addColorStop(0.55, primary + '33');
    grad.addColorStop(1, primary + '00');
    ctx.fillStyle = grad;
    ctx.beginPath();
    ctx.arc(cx, cy, 19, 0, Math.PI * 2);
    ctx.fill();

    ctx.strokeStyle = '#020813';
    ctx.lineWidth = 2.2;
    ctx.fillStyle = primary;

    if (category === 'AVIATION') {
      ctx.beginPath();
      ctx.moveTo(cx, cy - 11);
      ctx.lineTo(cx + 3, cy - 3);
      ctx.lineTo(cx + 11, cy + 2);
      ctx.lineTo(cx + 11, cy + 5);
      ctx.lineTo(cx + 3, cy + 3);
      ctx.lineTo(cx + 2, cy + 9);
      ctx.lineTo(cx + 5, cy + 11);
      ctx.lineTo(cx - 5, cy + 11);
      ctx.lineTo(cx - 2, cy + 9);
      ctx.lineTo(cx - 3, cy + 3);
      ctx.lineTo(cx - 11, cy + 5);
      ctx.lineTo(cx - 11, cy + 2);
      ctx.lineTo(cx - 3, cy - 3);
      ctx.closePath();
      ctx.fill();
      ctx.stroke();
    } else if (category === 'MARITIME') {
      ctx.beginPath();
      ctx.moveTo(cx, cy - 10);
      ctx.lineTo(cx + 7, cy + 8);
      ctx.lineTo(cx, cy + 5);
      ctx.lineTo(cx - 7, cy + 8);
      ctx.closePath();
      ctx.fill();
      ctx.stroke();
    } else if (category === 'SPACE') {
      ctx.fillRect(cx - 3.5, cy - 3.5, 7, 7);
      ctx.strokeRect(cx - 3.5, cy - 3.5, 7, 7);
      ctx.fillStyle = '#38BDF8';
      ctx.fillRect(cx - 12, cy - 2.5, 6.5, 5);
      ctx.fillRect(cx + 5.5, cy - 2.5, 6.5, 5);
    } else if (category === 'DISASTER') {
      ctx.beginPath();
      ctx.moveTo(cx, cy - 10);
      ctx.lineTo(cx + 10, cy);
      ctx.lineTo(cx, cy + 10);
      ctx.lineTo(cx - 10, cy);
      ctx.closePath();
      ctx.fill();
      ctx.stroke();
      if (severity === 'CRITICAL') {
        ctx.strokeStyle = '#FCA5A5';
        ctx.lineWidth = 1.4;
        ctx.beginPath();
        ctx.arc(cx, cy, 14, 0, Math.PI * 2);
        ctx.stroke();
      }
    } else if (category === 'INFRASTRUCTURE') {
      ctx.beginPath();
      for (let i = 0; i < 6; i++) {
        const ang = (Math.PI / 3) * i - Math.PI / 6;
        const px = cx + 8.5 * Math.cos(ang);
        const py = cy + 8.5 * Math.sin(ang);
        if (i === 0) ctx.moveTo(px, py);
        else ctx.lineTo(px, py);
      }
      ctx.closePath();
      ctx.fill();
      ctx.stroke();
    } else if (category === 'NEWS') {
      // Headline marker: a filled pennant with a mast, so news is never mistaken for a
      // hazard diamond or an infrastructure hexagon when two stories share a centroid.
      const newsColor = severity === 'CRITICAL' || severity === 'HIGH' ? sevColor : catColor;
      ctx.fillStyle = newsColor;
      ctx.strokeStyle = '#020813';
      ctx.lineWidth = 1.6;
      ctx.beginPath();
      ctx.moveTo(cx - 5, cy - 10);
      ctx.lineTo(cx - 5, cy + 10);
      ctx.stroke();
      ctx.beginPath();
      ctx.moveTo(cx - 4, cy - 10);
      ctx.lineTo(cx + 10, cy - 6);
      ctx.lineTo(cx - 4, cy - 1);
      ctx.closePath();
      ctx.fill();
      ctx.stroke();
    } else if (category === 'CYBER') {
      ctx.beginPath();
      for (let i = 0; i < 8; i++) {
        const ang = (Math.PI / 4) * i;
        const px = cx + 8.5 * Math.cos(ang);
        const py = cy + 8.5 * Math.sin(ang);
        if (i === 0) ctx.moveTo(px, py);
        else ctx.lineTo(px, py);
      }
      ctx.closePath();
      ctx.fill();
      ctx.stroke();
    } else {
      ctx.beginPath();
      ctx.arc(cx, cy, 7.5, 0, Math.PI * 2);
      ctx.fill();
      ctx.stroke();
      ctx.fillStyle = '#F8FAFC';
      ctx.beginPath();
      ctx.arc(cx, cy, 2.4, 0, Math.PI * 2);
      ctx.fill();
    }

    const url = canvas.toDataURL('image/png');
    ICON_CACHE.set(key, url);
    return url;
  }

  function createClusterPin(count) {
    const key = `cluster:${count >= 100 ? '100+' : count}`;
    if (ICON_CACHE.has(key)) return ICON_CACHE.get(key);
    const size = 46;
    const canvas = document.createElement('canvas');
    canvas.width = size;
    canvas.height = size;
    const ctx = canvas.getContext('2d');
    if (!ctx) return '';

    const r = count >= 50 ? 20 : (count >= 15 ? 17 : 15);
    const color = count >= 50 ? '#EF4444' : (count >= 15 ? '#F59E0B' : '#0EA5E9');
    ctx.fillStyle = color + '38';
    ctx.beginPath();
    ctx.arc(23, 23, r + 3, 0, Math.PI * 2);
    ctx.fill();

    ctx.fillStyle = '#040D1A';
    ctx.strokeStyle = color;
    ctx.lineWidth = 2.2;
    ctx.beginPath();
    ctx.arc(23, 23, r - 2, 0, Math.PI * 2);
    ctx.fill();
    ctx.stroke();

    ctx.fillStyle = '#F8FAFC';
    ctx.font = 'bold 11px sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText(count > 999 ? '999+' : String(count), 23, 23.5);

    const dataUrl = canvas.toDataURL('image/png');
    ICON_CACHE.set(key, dataUrl);
    return dataUrl;
  }

  /** Synchronous, zero-XML bundled NaturalEarthII offline base imagery provider. */
  function createBundledOfflineBaseProvider() {
    return new Cesium.UrlTemplateImageryProvider({
      url: './vendor/cesium/Assets/Textures/NaturalEarthII/{z}/{x}/{reverseY}.jpg',
      tilingScheme: new Cesium.GeographicTilingScheme(),
      maximumLevel: 2,
      tileWidth: 256,
      tileHeight: 256,
      credit: 'Natural Earth II (Bundled Offline)'
    });
  }

  function createBasemapProvider(kind, customGibsDate) {
    const gibsDate = customGibsDate || utcYesterdayIso();
    switch (kind) {
      case 'SATELLITE_ESRI':
        return new Cesium.UrlTemplateImageryProvider({
          url: 'https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',
          maximumLevel: 19,
          credit: 'Esri, Maxar, Earthstar Geographics, USDA, USGS, AeroGRID'
        });
      case 'OPENSTREETMAP':
        return new Cesium.UrlTemplateImageryProvider({
          url: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
          maximumLevel: 19,
          credit: '© OpenStreetMap contributors (ODbL)'
        });
      case 'CARTO_DARK_MATTER':
        return new Cesium.UrlTemplateImageryProvider({
          url: 'https://a.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png',
          maximumLevel: 19,
          credit: '© CARTO · © OpenStreetMap contributors'
        });
      case 'OPEN_TOPO_MAP':
        return new Cesium.UrlTemplateImageryProvider({
          url: 'https://a.tile.opentopomap.org/{z}/{x}/{y}.png',
          maximumLevel: 17,
          credit: '© OpenTopoMap (CC-BY-SA) · © OpenStreetMap'
        });
      case 'DARK_TACTICAL':
        return new Cesium.UrlTemplateImageryProvider({
          url: 'https://services.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Base/MapServer/tile/{z}/{y}/{x}',
          maximumLevel: 16,
          credit: 'Esri, HERE, Garmin, OpenStreetMap contributors'
        });
      case 'NASA_GIBS_TRUECOLOR':
        return new Cesium.UrlTemplateImageryProvider({
          url: `https://gibs.earthdata.nasa.gov/wmts/epsg3857/best/VIIRS_SNPP_CorrectedReflectance_TrueColor/default/${gibsDate}/GoogleMapsCompatible_Level9/{z}/{y}/{x}.jpg`,
          maximumLevel: 9,
          credit: `NASA EOSDIS GIBS VIIRS SNPP (${gibsDate})`
        });
      case 'NASA_GIBS_NIGHTLIGHTS':
        return new Cesium.UrlTemplateImageryProvider({
          url: 'https://gibs.earthdata.nasa.gov/wmts/epsg3857/best/VIIRS_CityLights_2012/default/2012-01-01/GoogleMapsCompatible_Level8/{z}/{y}/{x}.jpg',
          maximumLevel: 8,
          credit: 'NASA Earth Observatory / NOAA NGDC VIIRS Nighttime Lights'
        });
      case 'OFFLINE_NATURAL_EARTH':
      default:
        return null;
    }
  }

  function attachOfflineFallbackWatcher(provider, getLayer) {
    if (!provider || !provider.errorEvent || typeof provider.errorEvent.addEventListener !== 'function') {
      return;
    }
    let consecutiveErrors = 0;
    provider.errorEvent.addEventListener(function () {
      consecutiveErrors += 1;
      const layer = getLayer();
      if (consecutiveErrors >= 6 && layer && state.viewer) {
        layer.show = false;
        state.viewer.scene.requestRender();
      }
    });
  }

  /**
   * Global 3D DEM Terrain Decoder using AWS Open Data Mapzen Terrarium PNG tiles.
   * Uses an isolated per-tile canvas and falls back cleanly to EllipsoidTerrainProvider on errors.
   */
  function createOpenTerrariumTerrainProvider() {
    const sampleSize = 32;
    const provider = new Cesium.CustomHeightmapTerrainProvider({
      width: sampleSize,
      height: sampleSize,
      tilingScheme: new Cesium.WebMercatorTilingScheme(),
      credit: 'Mapzen / AWS Open Data Global Terrarium DEM',
      callback: function (x, y, level) {
        if (level > 11) {
          return new Float32Array(sampleSize * sampleSize);
        }
        const url = `https://s3.amazonaws.com/elevation-tiles-prod/terrarium/${level}/${x}/${y}.png`;
        return Cesium.Resource.fetchImage({ url: url })
          .then(function (img) {
            const tileCanvas = document.createElement('canvas');
            tileCanvas.width = sampleSize;
            tileCanvas.height = sampleSize;
            const ctx = tileCanvas.getContext('2d', { willReadFrequently: true });
            if (!ctx) return new Float32Array(sampleSize * sampleSize);
            ctx.drawImage(img, 0, 0, sampleSize, sampleSize);
            const pixels = ctx.getImageData(0, 0, sampleSize, sampleSize).data;
            const heights = new Float32Array(sampleSize * sampleSize);
            for (let i = 0, p = 0; i < heights.length; i++, p += 4) {
              const r = pixels[p];
              const g = pixels[p + 1];
              const b = pixels[p + 2];
              const h = (r * 256.0 + g + b / 256.0) - 32768.0;
              heights[i] = Number.isFinite(h) && h > -11000 && h < 9000 ? h : 0.0;
            }
            return heights;
          })
          .catch(function () {
            return new Float32Array(sampleSize * sampleSize);
          });
      }
    });

    if (provider.errorEvent && typeof provider.errorEvent.addEventListener === 'function') {
      let terrainErrors = 0;
      provider.errorEvent.addEventListener(function () {
        terrainErrors += 1;
        if (terrainErrors >= 4 && state.viewer) {
          state.terrainEnabled = false;
          state.viewer.terrainProvider = new Cesium.EllipsoidTerrainProvider();
          state.viewer.scene.requestRender();
        }
      });
    }
    return provider;
  }

  function applyImageryTone(layer) {
    if (!layer) return;
    if (state.stylePreset === 'CYBER_TACTICAL') {
      layer.brightness = 0.94;
      layer.contrast = 1.12;
      layer.saturation = 0.92;
    } else if (state.stylePreset === 'NIGHT_OPS_DARK') {
      layer.brightness = 0.65;
      layer.contrast = 1.25;
      layer.saturation = 0.65;
    } else if (state.stylePreset === 'THERMAL_CONTRAST') {
      layer.brightness = 0.90;
      layer.contrast = 1.32;
      layer.saturation = 1.35;
    } else if (state.stylePreset === 'HIGH_VISIBILITY') {
      layer.brightness = 1.08;
      layer.contrast = 1.16;
      layer.saturation = 1.12;
    } else {
      layer.brightness = 1.0;
      layer.contrast = 1.0;
      layer.saturation = 1.0;
    }
  }

  async function ensureCountryTopologyLoaded() {
    if (state.countryRings.length > 0) return;
    try {
      if (typeof topojson === 'undefined') return;
      const resp = await fetch('./vendor/countries-110m.json');
      if (!resp.ok) return;
      const topo = await resp.json();
      if (!topo || !topo.objects || !topo.objects.countries) return;

      const mesh = topojson.mesh(topo, topo.objects.countries);
      if (mesh && mesh.type === 'MultiLineString' && Array.isArray(mesh.coordinates)) {
        state.countryRings = mesh.coordinates;
      }

      const fc = topojson.feature(topo, topo.objects.countries);
      if (fc && Array.isArray(fc.features)) {
        state.countryFeatures = fc.features;
      }
    } catch (err) {
      console.warn('Country topology load skipped:', err && err.message ? err.message : err);
    }
  }

  /**
   * Direct-GPU country borders using Cesium.PolylineCollection (zero WebWorker overhead,
   * guaranteed Frame-1 visibility even if TaskProcessor workers are busy).
   */
  async function loadBundledCountryBorders() {
    await ensureCountryTopologyLoaded();
    if (!state.viewer || state.countryRings.length === 0) return;

    if (state.bordersCollection) {
      state.viewer.scene.primitives.remove(state.bordersCollection);
      state.bordersCollection = null;
    }

    const collection = new Cesium.PolylineCollection();
    const borderMat = Cesium.Material.fromType('Color', {
      color: Cesium.Color.fromCssColorString('#22D3EE').withAlpha(0.52)
    });

    for (let i = 0; i < state.countryRings.length; i++) {
      const coords = state.countryRings[i];
      if (!Array.isArray(coords) || coords.length < 2) continue;
      const positions = [];
      for (let j = 0; j < coords.length; j++) {
        const pt = coords[j];
        if (Array.isArray(pt) && pt.length >= 2 && Number.isFinite(pt[0]) && Number.isFinite(pt[1])) {
          positions.push(Cesium.Cartesian3.fromDegrees(pt[0], pt[1], 1600));
        }
      }
      if (positions.length >= 2) {
        collection.add({
          positions: positions,
          width: 1.2,
          material: borderMat
        });
      }
    }

    collection.show = state.bordersVisible;
    state.viewer.scene.primitives.add(collection);
    state.bordersCollection = collection;
    state.viewer.scene.requestRender();
  }

  function updateTelemetryBarValues(latDeg, lonDeg, altM, scaleMeters) {
    const utcEl = document.getElementById('wmUtcClock');
    const coordsEl = document.getElementById('wmCamCoords');
    const altEl = document.getElementById('wmCamAlt');
    const scaleEl = document.getElementById('wmScaleLabel');

    if (utcEl) {
      const now = new Date();
      utcEl.textContent = now.toISOString().slice(11, 19) + ' UTC';
    }
    if (coordsEl) coordsEl.textContent = formatCoord(latDeg, lonDeg);
    if (altEl) altEl.textContent = `ALT ${formatDistanceMeters(altM)}`;
    if (scaleEl) scaleEl.textContent = `SCALE ${formatDistanceMeters(scaleMeters)}`;
  }

  function updateHudReadout() {
    if (state.engineMode === 'canvas3d' && state.canvasEngine) {
      state.canvasEngine.emitTelemetry();
      return;
    }
    const viewer = state.viewer;
    if (!viewer) return;

    const carto = viewer.camera.positionCartographic;
    if (!carto) return;
    const latDeg = Cesium.Math.toDegrees(carto.latitude);
    const lonDeg = Cesium.Math.toDegrees(carto.longitude);
    const altM = Math.max(0, carto.height);
    const groundHeight = viewer.scene.globe.getHeight(carto) || 0;
    const approxScale = Math.max(50, altM * 0.18);

    updateTelemetryBarValues(latDeg, lonDeg, altM, approxScale);

    const nowMs = Date.now();
    if (nowMs - state.lastViewportSentAt > 320) {
      state.lastViewportSentAt = nowMs;
      let west = Math.max(-180, lonDeg - 35);
      let south = Math.max(-85, latDeg - 25);
      let east = Math.min(180, lonDeg + 35);
      let north = Math.min(85, latDeg + 25);
      try {
        const rect = viewer.camera.computeViewRectangle(viewer.scene.globe.ellipsoid);
        if (rect) {
          west = Cesium.Math.toDegrees(rect.west);
          south = Cesium.Math.toDegrees(rect.south);
          east = Cesium.Math.toDegrees(rect.east);
          north = Cesium.Math.toDegrees(rect.north);
        }
      } catch (_) { /*horizon tilted*/ }

      postToAndroid('onViewportChanged', {
        centerLat: Number(latDeg.toFixed(5)),
        centerLon: Number(lonDeg.toFixed(5)),
        cameraAltitudeMeters: Math.round(altM),
        groundElevationMeters: Math.round(groundHeight),
        headingDeg: Number(Cesium.Math.toDegrees(viewer.camera.heading).toFixed(1)),
        pitchDeg: Number(Cesium.Math.toDegrees(viewer.camera.pitch).toFixed(1)),
        west: Number(west.toFixed(4)),
        south: Number(south.toFixed(4)),
        east: Number(east.toFixed(4)),
        north: Number(north.toFixed(4)),
        projection: state.projection
      });
    }
  }

  function propagatePointAtDate(satrec, dateObj) {
    if (typeof satellite === 'undefined' || !satrec) return null;
    const pv = satellite.propagate(satrec, dateObj);
    if (!pv || !pv.position || typeof pv.position.x !== 'number') return null;
    const gmst = satellite.gstime(dateObj);
    const gd = satellite.eciToGeodetic(pv.position, gmst);
    const lonDeg = satellite.degreesLong(gd.longitude);
    const latDeg = satellite.degreesLat(gd.latitude);
    const altMeters = Math.max(80000, gd.height * 1000.0);
    if (!Number.isFinite(lonDeg) || !Number.isFinite(latDeg) || !Number.isFinite(altMeters)) {
      return null;
    }
    let velKms = 7.66;
    if (pv.velocity && typeof pv.velocity.x === 'number') {
      velKms = Math.sqrt(
        pv.velocity.x * pv.velocity.x +
        pv.velocity.y * pv.velocity.y +
        pv.velocity.z * pv.velocity.z
      );
    }
    return { latDeg, lonDeg, altMeters, velKms };
  }

  function propagateSatellitesNow() {
    if (state.satellites.size === 0 || !state.layerVisibility.SPACE) return;
    const now = new Date();
    state.satellites.forEach(function (entry) {
      const pos = propagatePointAtDate(entry.satrec, now);
      if (!pos) return;
      entry.livePos = pos;
      entry.entityData.latitude = pos.latDeg;
      entry.entityData.longitude = pos.lonDeg;
      entry.entityData.altitudeMeters = pos.altMeters;
      entry.entityData.speedKnots = pos.velKms * 1943.84;
      if (state.engineMode === 'cesium' && entry.cesiumEntity) {
        entry.cesiumEntity.position = Cesium.Cartesian3.fromDegrees(pos.lonDeg, pos.latDeg, pos.altMeters);
      }
    });
    if (state.engineMode === 'cesium' && state.viewer) {
      state.viewer.scene.requestRender();
    }
  }

  function renderSatelliteOrbitAndGroundTrack(entityId) {
    if (state.engineMode === 'canvas3d') {
      if (state.canvasEngine) state.canvasEngine.rebuildSelectedOrbit(entityId);
      return;
    }
    const spaceDs = state.dataSources.SPACE;
    if (!spaceDs) return;

    state.satellites.forEach(function (entry) {
      if (entry.orbitEntity) {
        spaceDs.entities.remove(entry.orbitEntity);
        entry.orbitEntity = null;
      }
      if (entry.groundTrackEntity) {
        spaceDs.entities.remove(entry.groundTrackEntity);
        entry.groundTrackEntity = null;
      }
    });

    const selected = state.satellites.get(entityId);
    if (!selected) {
      if (state.viewer) state.viewer.scene.requestRender();
      return;
    }

    const nowMs = Date.now();
    const orbitPositions = [];
    const groundSegments = [[]];
    let prevLon = null;

    for (let min = -48; min <= 48; min += 2) {
      const dt = new Date(nowMs + min * 60 * 1000);
      const pt = propagatePointAtDate(selected.satrec, dt);
      if (!pt) continue;
      orbitPositions.push(Cesium.Cartesian3.fromDegrees(pt.lonDeg, pt.latDeg, pt.altMeters));

      if (prevLon !== null && Math.abs(pt.lonDeg - prevLon) > 170) {
        groundSegments.push([]);
      }
      groundSegments[groundSegments.length - 1].push(
        Cesium.Cartesian3.fromDegrees(pt.lonDeg, pt.latDeg, 2500)
      );
      prevLon = pt.lonDeg;
    }

    if (orbitPositions.length >= 2) {
      selected.orbitEntity = spaceDs.entities.add({
        polyline: {
          positions: orbitPositions,
          width: 2.2,
          material: new Cesium.PolylineGlowMaterialProperty({
            glowPower: 0.22,
            color: Cesium.Color.fromCssColorString('#C084FC').withAlpha(0.92)
          })
        }
      });
    }

    const validSeg = groundSegments.find(function (s) { return s.length >= 2; });
    if (validSeg) {
      selected.groundTrackEntity = spaceDs.entities.add({
        polyline: {
          positions: validSeg,
          width: 1.5,
          material: new Cesium.PolylineDashMaterialProperty({
            color: Cesium.Color.fromCssColorString('#38BDF8').withAlpha(0.75),
            dashLength: 12.0
          })
        }
      });
    }

    if (state.viewer) state.viewer.scene.requestRender();
  }

  function computeSatellitePasses(entityId, observerLatDeg, observerLonDeg, hoursAhead) {
    const entry = state.satellites.get(entityId);
    if (!entry || typeof satellite === 'undefined') return [];

    const horizonHours = Math.min(48, Math.max(1, hoursAhead || 24));
    const stepMs = 30 * 1000;
    const startMs = Date.now();
    const endMs = startMs + horizonHours * 3600 * 1000;
    const obsGd = {
      longitude: satellite.degreesToRadians(observerLonDeg),
      latitude: satellite.degreesToRadians(observerLatDeg),
      height: 0.05
    };

    const passes = [];
    let current = null;

    for (let t = startMs; t <= endMs; t += stepMs) {
      const d = new Date(t);
      const pv = satellite.propagate(entry.satrec, d);
      if (!pv || !pv.position) continue;
      const gmst = satellite.gstime(d);
      const ecf = satellite.eciToEcf(pv.position, gmst);
      const look = satellite.ecfToLookAngles(obsGd, ecf);
      const elDeg = look.elevation * RAD_TO_DEG;
      const azDeg = (look.azimuth * RAD_TO_DEG + 360.0) % 360.0;

      if (elDeg >= 10.0) {
        if (!current) {
          current = {
            aosEpochMs: t,
            losEpochMs: t,
            maxElevationDeg: elDeg,
            maxElevationEpochMs: t,
            aosAzimuthDeg: azDeg,
            losAzimuthDeg: azDeg
          };
        } else {
          current.losEpochMs = t;
          current.losAzimuthDeg = azDeg;
          if (elDeg > current.maxElevationDeg) {
            current.maxElevationDeg = elDeg;
            current.maxElevationEpochMs = t;
          }
        }
      } else if (current) {
        const riseAz = Number(current.aosAzimuthDeg.toFixed(0));
        const setAz = Number(current.losAzimuthDeg.toFixed(0));
        const maxEl = Number(current.maxElevationDeg.toFixed(1));
        const durSec = Math.max(30, Math.round((current.losEpochMs - current.aosEpochMs) / 1000));
        passes.push({
          name: (entry.entity && entry.entity.title) || entityId,
          observerLat: observerLatDeg,
          observerLon: observerLonDeg,
          riseEpochMs: current.aosEpochMs,
          culminationEpochMs: current.maxElevationEpochMs,
          setEpochMs: current.losEpochMs,
          maxElevationDeg: maxEl,
          riseAzimuthDeg: riseAz,
          setAzimuthDeg: setAz,
          durationSeconds: durSec,
          aosEpochMs: current.aosEpochMs,
          losEpochMs: current.losEpochMs,
          maxElevationEpochMs: current.maxElevationEpochMs,
          aosAzimuthDeg: riseAz,
          losAzimuthDeg: setAz,
          durationSec: durSec
        });
        current = null;
        if (passes.length >= 8) break;
      }
    }
    return passes;
  }

  async function rebuildDensityHeatmap() {
    if (!state.viewer) return;
    if (state.imageryRefs.heatmapOverlay) {
      state.viewer.imageryLayers.remove(state.imageryRefs.heatmapOverlay, true);
      state.imageryRefs.heatmapOverlay = null;
    }
    if (!state.heatmapEnabled || state.allEntitiesCache.length === 0) {
      state.viewer.scene.requestRender();
      return;
    }

    const w = 1024;
    const h = 512;
    const canvas = document.createElement('canvas');
    canvas.width = w;
    canvas.height = h;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    for (let i = 0; i < state.allEntitiesCache.length; i++) {
      const item = state.allEntitiesCache[i];
      if (!state.layerVisibility[item.category]) continue;
      if (!Number.isFinite(item.latitude) || !Number.isFinite(item.longitude)) continue;
      const x = ((item.longitude + 180.0) / 360.0) * w;
      const y = ((90.0 - item.latitude) / 180.0) * h;
      const weight = item.severity === 'CRITICAL' ? 0.55 : (item.severity === 'HIGH' ? 0.40 : 0.24);
      const radius = item.category === 'DISASTER' ? 22 : 14;
      const grad = ctx.createRadialGradient(x, y, 1, x, y, radius);
      grad.addColorStop(0, `rgba(239, 68, 68, ${weight})`);
      grad.addColorStop(0.45, `rgba(245, 158, 11, ${weight * 0.65})`);
      grad.addColorStop(0.80, `rgba(34, 211, 238, ${weight * 0.32})`);
      grad.addColorStop(1, 'rgba(2, 8, 19, 0)');
      ctx.fillStyle = grad;
      ctx.beginPath();
      ctx.arc(x, y, radius, 0, Math.PI * 2);
      ctx.fill();
    }

    try {
      const provider = await Cesium.SingleTileImageryProvider.fromUrl(canvas.toDataURL('image/png'), {
        rectangle: Cesium.Rectangle.MAX_VALUE,
        credit: 'Computed Geospatial Entity Density'
      });
      const layer = state.viewer.imageryLayers.addImageryProvider(provider);
      layer.alpha = 0.72;
      state.imageryRefs.heatmapOverlay = layer;
      state.viewer.scene.requestRender();
    } catch (err) {
      console.warn('Heatmap build warning:', err && err.message ? err.message : err);
    }
  }

  function registerSatellitesFromEntities(entities) {
    state.satellites.clear();
    if (typeof satellite === 'undefined' || !Array.isArray(entities)) return;
    for (let i = 0; i < entities.length; i++) {
      const item = entities[i];
      if (item.category === 'SPACE' && item.tleLine1 && item.tleLine2) {
        try {
          const satrec = satellite.twoline2satrec(item.tleLine1, item.tleLine2);
          state.satellites.set(item.id, {
            entityData: item,
            satrec: satrec,
            cesiumEntity: null,
            livePos: null,
            orbitEntity: null,
            groundTrackEntity: null
          });
        } catch (_) { /* ignore malformed TLE */ }
      }
    }
  }

  function selectDefaultIssIfNeeded() {
    if (state.selectedSatelliteId && state.satellites.has(state.selectedSatelliteId)) {
      renderSatelliteOrbitAndGroundTrack(state.selectedSatelliteId);
      return;
    }
    if (state.satellites.size > 0) {
      let issId = null;
      state.satellites.forEach(function (val, key) {
        if (String(val.entityData.title).toUpperCase().includes('ISS') || String(key).includes('25544')) {
          issId = key;
        }
      });
      if (issId) {
        state.selectedSatelliteId = issId;
        renderSatelliteOrbitAndGroundTrack(issId);
      }
    }
  }

  function addOrReplaceEntities(entities) {
    if (!Array.isArray(entities)) return;
    state.allEntitiesCache = entities;
    registerSatellitesFromEntities(entities);

    if (state.engineMode === 'canvas3d') {
      propagateSatellitesNow();
      selectDefaultIssIfNeeded();
      return;
    }
    if (!state.viewer) return;

    Object.keys(state.dataSources).forEach(function (cat) {
      state.dataSources[cat].entities.removeAll();
    });

    const maxPerLayer = state.performanceMode === 'LOW_POWER' ? 180 : (state.performanceMode === 'BALANCED' ? 450 : 900);
    const countsByCat = {};

    for (let i = 0; i < entities.length; i++) {
      const item = entities[i];
      const cat = item.category;
      const ds = state.dataSources[cat];
      if (!ds) continue;

      countsByCat[cat] = (countsByCat[cat] || 0) + 1;
      if (countsByCat[cat] > maxPerLayer) continue;

      const opacity = state.layerOpacity[cat] ?? 1.0;
      const altMeters = Number.isFinite(item.altitudeMeters) ? item.altitudeMeters : 0;
      const colorHex = item.severity === 'CRITICAL' || item.severity === 'HIGH'
        ? (SEVERITY_COLORS[item.severity] || CATEGORY_COLORS[cat])
        : (CATEGORY_COLORS[cat] || '#38BDF8');

      if (item.geometryType === 'LineString' && item.geometryCoordsJson) {
        try {
          const coords = JSON.parse(item.geometryCoordsJson);
          if (Array.isArray(coords) && coords.length >= 2) {
            const flatPositions = [];
            for (let c = 0; c < coords.length; c++) {
              if (Array.isArray(coords[c]) && coords[c].length >= 2) {
                flatPositions.push(Cesium.Cartesian3.fromDegrees(coords[c][0], coords[c][1], 500));
              }
            }
            if (flatPositions.length >= 2) {
              const lineEnt = ds.entities.add({
                id: `${item.id}::line`,
                name: item.title,
                polyline: {
                  positions: flatPositions,
                  width: cat === 'INFRASTRUCTURE' ? 2.2 : 2.8,
                  material: Cesium.Color.fromCssColorString(colorHex).withAlpha(0.75 * opacity)
                }
              });
              lineEnt.wmEntity = item;
            }
          }
        } catch (_) { /* ignore malformed line geometry */ }
      } else if (item.geometryType === 'Polygon' && item.geometryCoordsJson) {
        try {
          const ring = JSON.parse(item.geometryCoordsJson);
          if (Array.isArray(ring) && ring.length >= 3) {
            const hierarchy = ring.map(function (pt) {
              return Cesium.Cartesian3.fromDegrees(pt[0], pt[1]);
            });
            const polyEnt = ds.entities.add({
              id: `${item.id}::poly`,
              name: item.title,
              polygon: {
                hierarchy: hierarchy,
                material: Cesium.Color.fromCssColorString(colorHex).withAlpha(0.22 * opacity),
                outline: true,
                outlineColor: Cesium.Color.fromCssColorString(colorHex).withAlpha(0.80 * opacity),
                height: 800
              }
            });
            polyEnt.wmEntity = item;
          }
        } catch (_) { /* ignore malformed polygon geometry */ }
      }

      if (!Number.isFinite(item.latitude) || !Number.isFinite(item.longitude)) continue;

      const rotationRad = (cat === 'AVIATION' || cat === 'MARITIME') && Number.isFinite(item.headingDeg)
        ? -Cesium.Math.toRadians(item.headingDeg)
        : 0;

      const cesiumEnt = ds.entities.add({
        id: item.id,
        name: item.title,
        position: Cesium.Cartesian3.fromDegrees(item.longitude, item.latitude, Math.max(0, altMeters)),
        billboard: {
          image: getTacticalIcon(cat, item.severity, item.subType, opacity),
          width: cat === 'DISASTER' && item.severity === 'CRITICAL' ? 32 : 26,
          height: cat === 'DISASTER' && item.severity === 'CRITICAL' ? 32 : 26,
          rotation: rotationRad,
          alignedAxis: Cesium.Cartesian3.ZERO,
          verticalOrigin: Cesium.VerticalOrigin.CENTER,
          scaleByDistance: new Cesium.NearFarScalar(50000, 1.15, 18000000, 0.68),
          disableDepthTestDistance: cat === 'SPACE' ? 0 : Number.POSITIVE_INFINITY
        },
        label: {
          text: item.title ? String(item.title).slice(0, 26) : '',
          font: '600 10px sans-serif',
          fillColor: Cesium.Color.fromCssColorString('#F8FAFC'),
          outlineColor: Cesium.Color.fromCssColorString('#020813'),
          outlineWidth: 3,
          style: Cesium.LabelStyle.FILL_AND_OUTLINE,
          pixelOffset: new Cesium.Cartesian2(0, 20),
          distanceDisplayCondition: new Cesium.DistanceDisplayCondition(
            0,
            cat === 'SPACE' || item.severity === 'CRITICAL' ? 14000000 : 2800000
          ),
          disableDepthTestDistance: Number.POSITIVE_INFINITY
        }
      });
      cesiumEnt.wmEntity = item;

      const satEntry = state.satellites.get(item.id);
      if (satEntry) {
        satEntry.cesiumEntity = cesiumEnt;
      }
    }

    propagateSatellitesNow();
    selectDefaultIssIfNeeded();
    if (state.heatmapEnabled) {
      rebuildDensityHeatmap();
    }
    state.viewer.scene.requestRender();
  }

  function updateEntityTrails(trails) {
    if (!Array.isArray(trails)) return;
    state.trailsCache = trails;
    if (state.engineMode === 'canvas3d' || !state.viewer) return;

    trails.forEach(function (trail) {
      const ds = state.dataSources[trail.category];
      if (!ds || !Array.isArray(trail.points) || trail.points.length < 2) return;
      const positions = trail.points.map(function (pt) {
        return Cesium.Cartesian3.fromDegrees(pt.lon, pt.lat, Math.max(0, pt.altM || 0));
      });
      const existing = ds.entities.getById(`${trail.entityId}::trail`);
      if (existing) ds.entities.remove(existing);
      ds.entities.add({
        id: `${trail.entityId}::trail`,
        polyline: {
          positions: positions,
          width: 1.8,
          material: Cesium.Color.fromCssColorString(CATEGORY_COLORS[trail.category] || '#38BDF8').withAlpha(0.65)
        }
      });
    });
    state.viewer.scene.requestRender();
  }

  function updateCorrelations(incidents) {
    if (!Array.isArray(incidents)) return;
    state.correlationsCache = incidents;
    if (state.engineMode === 'canvas3d' || !state.correlationsSource) return;

    state.correlationsSource.entities.removeAll();
    incidents.forEach(function (inc) {
      if (Array.isArray(inc.links)) {
        inc.links.forEach(function (lnk, idx) {
          state.correlationsSource.entities.add({
            id: `corr::${inc.id}::${idx}`,
            polyline: {
              positions: [
                Cesium.Cartesian3.fromDegrees(lnk.fromLon, lnk.fromLat, 8000),
                Cesium.Cartesian3.fromDegrees(lnk.toLon, lnk.toLat, 8000)
              ],
              width: 2.5,
              material: new Cesium.PolylineDashMaterialProperty({
                color: Cesium.Color.fromCssColorString('#F472B6').withAlpha(0.9),
                dashLength: 14.0
              })
            }
          });
        });
      }
    });
    state.viewer.scene.requestRender();
  }

  function updateWatchRegions(regions) {
    if (!Array.isArray(regions)) return;
    state.watchRegionsCache = regions;
    if (state.engineMode === 'canvas3d' || !state.watchtowerSource) return;

    state.watchtowerSource.entities.removeAll();
    regions.forEach(function (reg) {
      if (!reg.enabled) return;
      state.watchtowerSource.entities.add({
        id: `watch::${reg.id}`,
        name: reg.name,
        position: Cesium.Cartesian3.fromDegrees(reg.centerLon, reg.centerLat, 0),
        ellipse: {
          semiMajorAxis: Math.max(5000, reg.radiusKm * 1000.0),
          semiMinorAxis: Math.max(5000, reg.radiusKm * 1000.0),
          material: Cesium.Color.fromCssColorString('#22D3EE').withAlpha(0.08),
          outline: true,
          outlineColor: Cesium.Color.fromCssColorString('#22D3EE').withAlpha(0.75),
          height: 1500
        },
        label: {
          text: `WATCH: ${reg.name}`,
          font: 'bold 10px sans-serif',
          fillColor: Cesium.Color.fromCssColorString('#22D3EE'),
          outlineColor: Cesium.Color.BLACK,
          outlineWidth: 2,
          style: Cesium.LabelStyle.FILL_AND_OUTLINE,
          distanceDisplayCondition: new Cesium.DistanceDisplayCondition(0, 9000000)
        }
      });
    });
    state.viewer.scene.requestRender();
  }

  function updateBuildings3D(buildings) {
    if (Array.isArray(buildings)) state.buildingsCache = buildings;
    if (state.engineMode === 'canvas3d' || !state.buildingsSource) return;

    state.buildingsSource.entities.removeAll();
    if (!state.buildings3DEnabled || !Array.isArray(buildings)) {
      if (state.viewer) state.viewer.scene.requestRender();
      return;
    }
    buildings.slice(0, 350).forEach(function (b) {
      if (!Array.isArray(b.footprint) || b.footprint.length < 3) return;
      const hierarchy = b.footprint.map(function (pt) {
        return Cesium.Cartesian3.fromDegrees(pt[0], pt[1]);
      });
      const heightM = Math.max(4, Math.min(600, b.heightMeters || 12));
      const ent = state.buildingsSource.entities.add({
        id: `bldg::${b.id}`,
        name: b.name || 'OSM Building Footprint',
        polygon: {
          hierarchy: hierarchy,
          height: 0,
          extrudedHeight: heightM,
          material: Cesium.Color.fromCssColorString('#1E3A5F').withAlpha(0.78),
          outline: true,
          outlineColor: Cesium.Color.fromCssColorString('#38BDF8').withAlpha(0.65)
        }
      });
      ent.wmBuilding = b;
    });
    state.viewer.scene.requestRender();
  }

  function setSimulationOverlay(simPayload) {
    state.simulationCache = simPayload || null;
    const banner = document.getElementById('wmSimBanner');
    if (!simPayload || !simPayload.active) {
      if (banner) banner.classList.add('hidden');
      if (state.simulationSource) state.simulationSource.entities.removeAll();
      if (state.viewer) state.viewer.scene.requestRender();
      return;
    }
    if (banner) {
      banner.textContent = `EXPERIMENTAL SIMULATION (${simPayload.label || 'SCENARIO'}) — NOT A CERTAIN PREDICTION`;
      banner.classList.remove('hidden');
    }
    if (state.engineMode === 'canvas3d' || !state.simulationSource) return;

    state.simulationSource.entities.removeAll();
    if (Array.isArray(simPayload.rings)) {
      simPayload.rings.forEach(function (ring, idx) {
        state.simulationSource.entities.add({
          id: `sim::ring::${idx}`,
          position: Cesium.Cartesian3.fromDegrees(ring.lon, ring.lat, 0),
          ellipse: {
            semiMajorAxis: Math.max(1000, ring.radiusKm * 1000.0),
            semiMinorAxis: Math.max(1000, ring.radiusKm * 1000.0),
            material: Cesium.Color.fromCssColorString(ring.colorHex || '#F59E0B').withAlpha(ring.alpha || 0.22),
            outline: true,
            outlineColor: Cesium.Color.fromCssColorString(ring.colorHex || '#F59E0B').withAlpha(0.9),
            height: 1200 + idx * 300
          },
          label: idx === 0 ? {
            text: `[SIMULATED] ${simPayload.label || 'Scenario'}`,
            font: 'bold 11px sans-serif',
            fillColor: Cesium.Color.fromCssColorString('#FDE68A'),
            outlineColor: Cesium.Color.BLACK,
            outlineWidth: 3,
            style: Cesium.LabelStyle.FILL_AND_OUTLINE,
            disableDepthTestDistance: Number.POSITIVE_INFINITY
          } : undefined
        });
      });
    }

    if (Array.isArray(simPayload.waypoints) && simPayload.waypoints.length >= 2) {
      const pts = simPayload.waypoints.map(function (wp) {
        return Cesium.Cartesian3.fromDegrees(wp.lon, wp.lat, 3000);
      });
      state.simulationSource.entities.add({
        id: 'sim::track',
        polyline: {
          positions: pts,
          width: 3.0,
          material: new Cesium.PolylineDashMaterialProperty({
            color: Cesium.Color.fromCssColorString('#FBBF24'),
            dashLength: 16.0
          })
        }
      });
    }
    state.viewer.scene.requestRender();
  }

  async function setBasemap(kind) {
    state.basemap = kind || 'SATELLITE_ESRI';
    if (state.engineMode === 'canvas3d' || !state.viewer) return;

    if (state.imageryRefs.primaryBase) {
      state.viewer.imageryLayers.remove(state.imageryRefs.primaryBase, true);
      state.imageryRefs.primaryBase = null;
    }
    if (state.basemap !== 'OFFLINE_NATURAL_EARTH') {
      try {
        const provider = createBasemapProvider(state.basemap);
        if (provider) {
          const layer = state.viewer.imageryLayers.addImageryProvider(provider, 1);
          layer.alpha = state.basemapOpacity;
          applyImageryTone(layer);
          state.imageryRefs.primaryBase = layer;
          attachOfflineFallbackWatcher(provider, function () { return state.imageryRefs.primaryBase; });
        }
      } catch (err) {
        console.warn('Basemap fallback to bundled NaturalEarthII:', err && err.message ? err.message : err);
      }
    }
    if (state.imageryRefs.labelsOverlay) {
      state.imageryRefs.labelsOverlay.show = state.labelsVisible && state.basemap !== 'OPENSTREETMAP';
    }
    state.viewer.scene.requestRender();
  }

  function setHistoricalSplit(cfg) {
    if (!state.viewer) return;
    const overlay = document.getElementById('wmSplitOverlay');
    const leftBadge = document.getElementById('wmSplitBadgeLeft');
    const rightBadge = document.getElementById('wmSplitBadgeRight');
    const bar = document.getElementById('wmSplitBar');

    if (state.imageryRefs.splitLeft) {
      state.viewer.imageryLayers.remove(state.imageryRefs.splitLeft, true);
      state.imageryRefs.splitLeft = null;
    }
    if (state.imageryRefs.splitRight) {
      state.viewer.imageryLayers.remove(state.imageryRefs.splitRight, true);
      state.imageryRefs.splitRight = null;
    }

    if (!cfg || !cfg.enabled) {
      if (overlay) overlay.classList.add('hidden');
      state.viewer.scene.requestRender();
      return;
    }

    const leftDate = cfg.leftDateIso || '2024-11-01';
    const rightDate = cfg.rightDateIso || utcYesterdayIso();
    const pos = Math.max(0.1, Math.min(0.9, cfg.splitPosition || 0.5));

    const leftLayer = state.viewer.imageryLayers.addImageryProvider(
      createBasemapProvider('NASA_GIBS_TRUECOLOR', leftDate)
    );
    leftLayer.splitDirection = Cesium.SplitDirection.LEFT;

    const rightLayer = state.viewer.imageryLayers.addImageryProvider(
      createBasemapProvider('NASA_GIBS_TRUECOLOR', rightDate)
    );
    rightLayer.splitDirection = Cesium.SplitDirection.RIGHT;

    state.imageryRefs.splitLeft = leftLayer;
    state.imageryRefs.splitRight = rightLayer;
    state.viewer.scene.splitPosition = pos;

    if (leftBadge) leftBadge.textContent = `NASA GIBS ${leftDate}`;
    if (rightBadge) rightBadge.textContent = `NASA GIBS ${rightDate}`;
    if (bar) bar.style.left = `${(pos * 100).toFixed(1)}%`;
    if (overlay) overlay.classList.remove('hidden');
    state.viewer.scene.requestRender();
  }

  function setCloudsOverlay(enabled, opacity) {
    state.cloudsEnabled = !!enabled;
    if (Number.isFinite(opacity)) state.cloudsOpacity = opacity;
    if (!state.viewer) return;
    if (state.imageryRefs.cloudsOverlay) {
      state.viewer.imageryLayers.remove(state.imageryRefs.cloudsOverlay, true);
      state.imageryRefs.cloudsOverlay = null;
    }
    if (state.cloudsEnabled) {
      const dateStr = utcYesterdayIso();
      const provider = new Cesium.UrlTemplateImageryProvider({
        url: `https://gibs.earthdata.nasa.gov/wmts/epsg3857/best/MODIS_Terra_Cloud_Top_Temp_Day/default/${dateStr}/GoogleMapsCompatible_Level6/{z}/{y}/{x}.png`,
        maximumLevel: 6,
        credit: 'NASA EOSDIS GIBS MODIS Cloud Top Temperature'
      });
      const layer = state.viewer.imageryLayers.addImageryProvider(provider);
      layer.alpha = state.cloudsOpacity;
      state.imageryRefs.cloudsOverlay = layer;
    }
    state.viewer.scene.requestRender();
  }

  function renderCurrentRadarFrame() {
    const stampEl = document.getElementById('wmRadarStamp');
    if (!state.viewer) {
      if (stampEl && (!state.radarEnabled || state.radarFrames.length === 0)) {
        stampEl.classList.add('hidden');
      }
      return;
    }
    if (state.imageryRefs.radarOverlay) {
      state.viewer.imageryLayers.remove(state.imageryRefs.radarOverlay, true);
      state.imageryRefs.radarOverlay = null;
    }
    if (!state.radarEnabled || state.radarFrames.length === 0) {
      if (stampEl) stampEl.classList.add('hidden');
      state.viewer.scene.requestRender();
      return;
    }
    const idx = state.radarFrameIndex % state.radarFrames.length;
    const frame = state.radarFrames[idx];
    const provider = new Cesium.UrlTemplateImageryProvider({
      url: `${frame.host}${frame.path}/256/{z}/{x}/{y}/2/1_1.png`,
      maximumLevel: 7,
      credit: 'RainViewer Global Weather Radar'
    });
    const layer = state.viewer.imageryLayers.addImageryProvider(provider);
    layer.alpha = state.radarOpacity;
    state.imageryRefs.radarOverlay = layer;

    if (stampEl) {
      const dt = new Date(frame.time * 1000);
      stampEl.textContent = `RADAR ${dt.toISOString().slice(11, 16)}Z`;
      stampEl.classList.remove('hidden');
    }
    state.viewer.scene.requestRender();
  }

  function setRadarConfiguration(cfg) {
    if (!cfg) return;
    if (Array.isArray(cfg.frames)) state.radarFrames = cfg.frames;
    if (typeof cfg.enabled === 'boolean') state.radarEnabled = cfg.enabled;
    if (typeof cfg.animating === 'boolean') state.radarAnimating = cfg.animating;
    if (Number.isFinite(cfg.opacity)) state.radarOpacity = cfg.opacity;

    if (state.radarTimer) {
      clearInterval(state.radarTimer);
      state.radarTimer = null;
    }
    if (state.radarEnabled && state.radarFrames.length > 0) {
      if (!state.radarAnimating) {
        state.radarFrameIndex = state.radarFrames.length - 1;
      } else {
        state.radarTimer = setInterval(function () {
          state.radarFrameIndex = (state.radarFrameIndex + 1) % state.radarFrames.length;
          renderCurrentRadarFrame();
        }, 1400);
      }
    }
    renderCurrentRadarFrame();
  }

  function scheduleOrbitTimer() {
    if (state.orbitTimer) {
      clearInterval(state.orbitTimer);
      state.orbitTimer = null;
    }
    const intervalMs = state.performanceMode === 'LOW_POWER' ? 4000 : 1200;
    state.orbitTimer = setInterval(propagateSatellitesNow, intervalMs);
  }

  function handleCommand(cmd) {
    if (!cmd || typeof cmd.type !== 'string') return;
    if (state.engineMode === 'canvas3d' && state.canvasEngine) {
      state.canvasEngine.handleCommand(cmd);
      return;
    }
    if (!state.viewer) return;

    switch (cmd.type) {
      case 'SET_BASEMAP':
        setBasemap(cmd.basemap || 'SATELLITE_ESRI');
        break;
      case 'SET_PROJECTION':
        state.projection = cmd.projection || 'GLOBE_3D';
        if (state.projection === 'MAP_2D') state.viewer.scene.morphTo2D(0.8);
        else if (state.projection === 'COLUMBUS_25D') state.viewer.scene.morphToColumbusView(0.8);
        else state.viewer.scene.morphTo3D(0.8);
        updateHudReadout();
        break;
      case 'SET_STYLE_PRESET':
        state.stylePreset = cmd.stylePreset || 'CYBER_TACTICAL';
        document.body.setAttribute('data-style', state.stylePreset);
        applyImageryTone(state.imageryRefs.primaryBase);
        state.viewer.scene.requestRender();
        break;
      case 'SET_TERRAIN':
        state.terrainEnabled = !!cmd.enabled;
        try {
          state.viewer.terrainProvider = (state.terrainEnabled && state.performanceMode !== 'LOW_POWER')
            ? createOpenTerrariumTerrainProvider()
            : new Cesium.EllipsoidTerrainProvider();
        } catch (_) {
          state.viewer.terrainProvider = new Cesium.EllipsoidTerrainProvider();
        }
        state.viewer.scene.requestRender();
        break;
      case 'SET_SCENE_FLAGS':
        if (typeof cmd.dayNightLighting === 'boolean') {
          state.dayNightLighting = cmd.dayNightLighting;
          state.viewer.scene.globe.enableLighting = state.dayNightLighting;
        }
        if (typeof cmd.atmosphere === 'boolean') {
          state.atmosphereEnabled = cmd.atmosphere;
          if (state.viewer.scene.skyAtmosphere) {
            state.viewer.scene.skyAtmosphere.show = state.atmosphereEnabled;
          }
          state.viewer.scene.globe.showGroundAtmosphere = state.atmosphereEnabled;
        }
        if (typeof cmd.borders === 'boolean') {
          state.bordersVisible = cmd.borders;
          if (state.bordersCollection) state.bordersCollection.show = state.bordersVisible;
        }
        if (typeof cmd.labels === 'boolean') {
          state.labelsVisible = cmd.labels;
          if (state.imageryRefs.labelsOverlay) {
            state.imageryRefs.labelsOverlay.show = state.labelsVisible && state.basemap !== 'OPENSTREETMAP';
          }
        }
        if (typeof cmd.buildings3D === 'boolean') {
          state.buildings3DEnabled = cmd.buildings3D;
          if (state.buildingsSource) state.buildingsSource.show = state.buildings3DEnabled;
        }
        if (typeof cmd.clustering === 'boolean') {
          state.clusteringEnabled = cmd.clustering;
          Object.keys(state.dataSources).forEach(function (k) {
            state.dataSources[k].clustering.enabled = state.clusteringEnabled;
          });
        }
        if (typeof cmd.heatmap === 'boolean') {
          state.heatmapEnabled = cmd.heatmap;
          rebuildDensityHeatmap();
        }
        state.viewer.scene.requestRender();
        break;
      case 'SET_CLOUDS':
        setCloudsOverlay(cmd.enabled, cmd.opacity);
        break;
      case 'SET_RADAR':
        setRadarConfiguration(cmd);
        break;
      case 'SET_HISTORICAL_SPLIT':
        setHistoricalSplit(cmd);
        break;
      case 'SET_LAYER_STATE':
        if (cmd.category && state.dataSources[cmd.category]) {
          if (typeof cmd.visible === 'boolean') {
            state.layerVisibility[cmd.category] = cmd.visible;
            state.dataSources[cmd.category].show = cmd.visible;
          }
          if (Number.isFinite(cmd.opacity)) {
            state.layerOpacity[cmd.category] = Math.max(0.1, Math.min(1.0, cmd.opacity));
          }
          if (state.heatmapEnabled) rebuildDensityHeatmap();
          state.viewer.scene.requestRender();
        }
        break;
      case 'SET_PERFORMANCE_MODE':
        state.performanceMode = cmd.mode || 'BALANCED';
        state.viewer.resolutionScale = state.performanceMode === 'LOW_POWER'
          ? 0.75
          : (state.performanceMode === 'BALANCED' ? 0.94 : 1.0);
        state.viewer.targetFrameRate = state.performanceMode === 'LOW_POWER' ? 28 : 60;
        state.viewer.scene.globe.maximumScreenSpaceError = state.performanceMode === 'LOW_POWER' ? 3.5 : 2.0;
        if (state.performanceMode === 'LOW_POWER' && state.terrainEnabled) {
          state.viewer.terrainProvider = new Cesium.EllipsoidTerrainProvider();
        }
        scheduleOrbitTimer();
        state.viewer.scene.requestRender();
        break;
      case 'UPDATE_ENTITIES':
        addOrReplaceEntities(cmd.entities || []);
        break;
      case 'UPDATE_TRAILS':
        updateEntityTrails(cmd.trails || []);
        break;
      case 'UPDATE_CORRELATIONS':
        updateCorrelations(cmd.incidents || []);
        break;
      case 'UPDATE_WATCH_REGIONS':
        updateWatchRegions(cmd.regions || []);
        break;
      case 'UPDATE_BUILDINGS_3D':
        updateBuildings3D(cmd.buildings || []);
        break;
      case 'SET_SIMULATION_OVERLAY':
        setSimulationOverlay(cmd.simulation);
        break;
      case 'LOAD_CZML':
        if (Array.isArray(cmd.czml)) {
          Cesium.CzmlDataSource.load(cmd.czml).then(function (ds) {
            state.viewer.dataSources.add(ds);
            state.viewer.scene.requestRender();
          });
        }
        break;
      case 'FLY_TO':
        if (Number.isFinite(cmd.lat) && Number.isFinite(cmd.lon)) {
          state.viewer.camera.flyTo({
            destination: Cesium.Cartesian3.fromDegrees(
              cmd.lon,
              cmd.lat,
              Math.max(350, cmd.altitudeMeters || 1400000)
            ),
            orientation: {
              heading: Cesium.Math.toRadians(cmd.headingDeg || 0),
              pitch: Cesium.Math.toRadians(cmd.pitchDeg || -78),
              roll: 0
            },
            duration: Number.isFinite(cmd.durationSec) ? cmd.durationSec : 1.25
          });
        }
        break;
      case 'CAMERA_ACTION':
        if (cmd.action === 'ZOOM_IN') {
          state.viewer.camera.zoomIn(Math.max(200, state.viewer.camera.positionCartographic.height * 0.38));
        } else if (cmd.action === 'ZOOM_OUT') {
          state.viewer.camera.zoomOut(Math.max(400, state.viewer.camera.positionCartographic.height * 0.55));
        } else if (cmd.action === 'TILT_3D') {
          if (state.projection !== 'GLOBE_3D') {
            state.projection = 'GLOBE_3D';
            state.viewer.scene.morphTo3D(0.5);
          }
          const c = state.viewer.camera.positionCartographic;
          const curPitchDeg = Cesium.Math.toDegrees(state.viewer.camera.pitch);
          const nextPitchDeg = curPitchDeg > -65 ? -88 : -44;
          const targetAlt = nextPitchDeg > -65 ? Math.min(c.height, 2600000) : c.height;
          state.viewer.camera.flyTo({
            destination: Cesium.Cartesian3.fromRadians(c.longitude, c.latitude, Math.max(800, targetAlt)),
            orientation: {
              heading: state.viewer.camera.heading,
              pitch: Cesium.Math.toRadians(nextPitchDeg),
              roll: 0
            },
            duration: 0.75
          });
        } else if (cmd.action === 'RESET_NORTH') {
          const c = state.viewer.camera.positionCartographic;
          state.viewer.camera.flyTo({
            destination: Cesium.Cartesian3.fromRadians(c.longitude, c.latitude, c.height),
            orientation: { heading: 0, pitch: Cesium.Math.toRadians(-88), roll: 0 },
            duration: 0.7
          });
        } else if (cmd.action === 'HOME' || cmd.action === 'HOME_GLOBE') {
          state.viewer.camera.flyTo({
            destination: Cesium.Cartesian3.fromDegrees(120.9842, 14.5995, 13800000),
            orientation: { heading: 0, pitch: Cesium.Math.toRadians(-88), roll: 0 },
            duration: 1.1
          });
        }
        state.viewer.scene.requestRender();
        break;
      case 'COMPUTE_SATELLITE_PASSES': {
        const passes = computeSatellitePasses(cmd.entityId, cmd.lat, cmd.lon, cmd.hoursAhead || 24);
        postToAndroid('onSatellitePassesComputed', {
          entityId: cmd.entityId,
          passes: passes
        });
        break;
      }
      case 'SELECT_SATELLITE_ORBIT':
        state.selectedSatelliteId = cmd.entityId || null;
        renderSatelliteOrbitAndGroundTrack(state.selectedSatelliteId);
        break;
      default:
        break;
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // SELF-HEALING HARDWARE-ACCELERATED 3D ORTHOGRAPHIC SPHERICAL CANVAS ENGINE
  // Activated automatically if WebGL / WebWorkers are unavailable or stalled.
  // ═══════════════════════════════════════════════════════════════════════════

  function createCanvas3DGlobeEngine(canvas) {
    const ctx = canvas.getContext('2d');
    const cam = {
      lat: 14.5995,
      lon: 120.9842,
      altMeters: 13800000,
      pitchDeg: -88,
      headingDeg: 0,
      velLon: 0,
      velLat: 0,
      flyAnim: null
    };

    const pointers = new Map();
    let lastPinchDist = null;
    let dragMoved = false;
    let downX = 0;
    let downY = 0;
    let selectedOrbitPts = [];
    let animFrameId = null;
    let renderedScreenMarkers = [];

    // Load bundled NaturalEarthII offline hemisphere textures for surface shading
    const texWest = new Image();
    const texEast = new Image();
    let texReadyCount = 0;
    texWest.onload = function () { texReadyCount += 1; };
    texEast.onload = function () { texReadyCount += 1; };
    texWest.src = './vendor/cesium/Assets/Textures/NaturalEarthII/0/0/0.jpg';
    texEast.src = './vendor/cesium/Assets/Textures/NaturalEarthII/0/1/0.jpg';

    const stars = [];
    for (let i = 0; i < 140; i++) {
      stars.push({
        u: ((i * 7919) % 1000) / 1000,
        v: ((i * 6271) % 1000) / 1000,
        r: (i % 3 === 0) ? 1.35 : 0.8,
        a: 0.25 + ((i * 37) % 60) / 100
      });
    }

    function resize() {
      const dpr = Math.min(2, window.devicePixelRatio || 1);
      const w = Math.max(320, window.innerWidth || canvas.clientWidth || 360);
      const h = Math.max(320, window.innerHeight || canvas.clientHeight || 640);
      if (canvas.width !== Math.round(w * dpr) || canvas.height !== Math.round(h * dpr)) {
        canvas.width = Math.round(w * dpr);
        canvas.height = Math.round(h * dpr);
      }
    }

    function globeRadiusPx(w, h) {
      const base = Math.min(w, h) * 0.41;
      const zoomFactor = Math.max(0.75, Math.min(14.0, 13800000 / Math.max(120000, cam.altMeters)));
      return base * zoomFactor;
    }

    function project3D(latDeg, lonDeg, altMeters, cx, cy, R) {
      if (state.projection === 'MAP_2D') {
        const dLon = (((lonDeg - cam.lon + 540) % 360) - 180);
        const scale = R / 95;
        return {
          x: cx + dLon * scale,
          y: cy - (latDeg - cam.lat * 0.35) * scale,
          z: 1,
          visible: true
        };
      }
      const phi = latDeg * DEG_TO_RAD;
      const lam = lonDeg * DEG_TO_RAD;
      const phi0 = cam.lat * DEG_TO_RAD;
      const lam0 = cam.lon * DEG_TO_RAD;
      const dLam = lam - lam0;
      const altScale = 1.0 + Math.max(0, altMeters || 0) / 6371000.0;

      const cosPhi = Math.cos(phi);
      const sinPhi = Math.sin(phi);
      const cosPhi0 = Math.cos(phi0);
      const sinPhi0 = Math.sin(phi0);
      const cosDLam = Math.cos(dLam);

      const cosC = sinPhi0 * sinPhi + cosPhi0 * cosPhi * cosDLam;
      const x = R * altScale * cosPhi * Math.sin(dLam);
      const y = -R * altScale * (cosPhi0 * sinPhi - sinPhi0 * cosPhi * cosDLam);
      return {
        x: cx + x,
        y: cy + y,
        z: cosC,
        visible: cosC > -0.04 * (altScale - 1.0) && cosC > 0.0
      };
    }

    function unprojectSphere(px, py, cx, cy, R) {
      if (state.projection === 'MAP_2D') {
        const scale = R / 95;
        const lon = (((cam.lon + (px - cx) / scale) + 540) % 360) - 180;
        const lat = Math.max(-85, Math.min(85, cam.lat * 0.35 - (py - cy) / scale));
        return { lat, lon };
      }
      const nx = (px - cx) / R;
      const ny = (cy - py) / R;
      const rho = Math.sqrt(nx * nx + ny * ny);
      if (rho > 1.0) return null;
      const c = Math.asin(rho);
      const cosC = Math.cos(c);
      const sinC = Math.sin(c);
      const phi0 = cam.lat * DEG_TO_RAD;
      const lam0 = cam.lon * DEG_TO_RAD;

      const lat = rho < 1e-6
        ? phi0
        : Math.asin(cosC * Math.sin(phi0) + (ny * sinC * Math.cos(phi0)) / rho);
      const lon = lam0 + Math.atan2(
        nx * sinC,
        rho * Math.cos(phi0) * cosC - ny * Math.sin(phi0) * sinC
      );
      return {
        lat: Math.max(-89.9, Math.min(89.9, lat * RAD_TO_DEG)),
        lon: (((lon * RAD_TO_DEG) + 540) % 360) - 180
      };
    }

    function emitTelemetry() {
      const approxScale = Math.max(80, cam.altMeters * 0.18);
      updateTelemetryBarValues(cam.lat, cam.lon, cam.altMeters, approxScale);
      const nowMs = Date.now();
      if (nowMs - state.lastViewportSentAt > 320) {
        state.lastViewportSentAt = nowMs;
        const span = Math.min(75, Math.max(2, (cam.altMeters / 13800000) * 55));
        postToAndroid('onViewportChanged', {
          centerLat: Number(cam.lat.toFixed(5)),
          centerLon: Number(cam.lon.toFixed(5)),
          cameraAltitudeMeters: Math.round(cam.altMeters),
          groundElevationMeters: 0,
          headingDeg: cam.headingDeg,
          pitchDeg: cam.pitchDeg,
          west: Number(Math.max(-180, cam.lon - span).toFixed(4)),
          south: Number(Math.max(-85, cam.lat - span * 0.7).toFixed(4)),
          east: Number(Math.min(180, cam.lon + span).toFixed(4)),
          north: Number(Math.min(85, cam.lat + span * 0.7).toFixed(4)),
          projection: state.projection
        });
      }
    }

    function rebuildSelectedOrbit(entityId) {
      selectedOrbitPts = [];
      const selected = state.satellites.get(entityId);
      if (!selected) return;
      const nowMs = Date.now();
      for (let min = -48; min <= 48; min += 2) {
        const pt = propagatePointAtDate(selected.satrec, new Date(nowMs + min * 60 * 1000));
        if (pt) selectedOrbitPts.push(pt);
      }
    }

    function drawRingCoords(coords, cx, cy, R, fillStyle, strokeStyle, lineWidth) {
      if (!Array.isArray(coords) || coords.length < 2) return;
      ctx.beginPath();
      let penDown = false;
      let anyVisible = false;
      for (let i = 0; i < coords.length; i++) {
        const pt = coords[i];
        const p = project3D(pt[1], pt[0], 0, cx, cy, R);
        if (p.visible) {
          anyVisible = true;
          if (!penDown) {
            ctx.moveTo(p.x, p.y);
            penDown = true;
          } else {
            ctx.lineTo(p.x, p.y);
          }
        } else {
          penDown = false;
        }
      }
      if (!anyVisible) return;
      if (fillStyle) {
        ctx.fillStyle = fillStyle;
        ctx.fill();
      }
      if (strokeStyle) {
        ctx.strokeStyle = strokeStyle;
        ctx.lineWidth = lineWidth || 1;
        ctx.stroke();
      }
    }

    function renderFrame() {
      resize();
      const dpr = Math.min(2, window.devicePixelRatio || 1);
      const w = canvas.width / dpr;
      const h = canvas.height / dpr;
      ctx.save();
      ctx.scale(dpr, dpr);

      // Camera flyTo / inertia update
      if (cam.flyAnim) {
        const t = Math.min(1, ( performance.now() - cam.flyAnim.start ) / cam.flyAnim.durationMs);
        const ease = 1 - Math.pow(1 - t, 3);
        cam.lat = cam.flyAnim.fromLat + (cam.flyAnim.toLat - cam.flyAnim.fromLat) * ease;
        let dLon = ((cam.flyAnim.toLon - cam.flyAnim.fromLon + 540) % 360) - 180;
        cam.lon = (((cam.flyAnim.fromLon + dLon * ease) + 540) % 360) - 180;
        cam.altMeters = cam.flyAnim.fromAlt + (cam.flyAnim.toAlt - cam.flyAnim.fromAlt) * ease;
        if (t >= 1) cam.flyAnim = null;
        emitTelemetry();
      } else if (pointers.size === 0 && (Math.abs(cam.velLon) > 0.01 || Math.abs(cam.velLat) > 0.01)) {
        cam.lon = (((cam.lon + cam.velLon) + 540) % 360) - 180;
        cam.lat = Math.max(-84, Math.min(84, cam.lat + cam.velLat));
        cam.velLon *= 0.91;
        cam.velLat *= 0.91;
        emitTelemetry();
      }

      // Deep space background + stars
      ctx.fillStyle = '#020813';
      ctx.fillRect(0, 0, w, h);
      for (let i = 0; i < stars.length; i++) {
        const st = stars[i];
        ctx.fillStyle = `rgba(186, 230, 253, ${st.a})`;
        ctx.beginPath();
        ctx.arc(st.u * w, st.v * h, st.r, 0, Math.PI * 2);
        ctx.fill();
      }

      const cx = w * 0.5;
      const cy = h * 0.48;
      const R = globeRadiusPx(w, h);

      if (state.projection !== 'MAP_2D') {
        // 3D Atmospheric outer halo
        if (state.atmosphereEnabled) {
          const halo = ctx.createRadialGradient(cx, cy, R * 0.92, cx, cy, R * 1.18);
          halo.addColorStop(0, 'rgba(34, 211, 238, 0.30)');
          halo.addColorStop(0.55, 'rgba(14, 165, 233, 0.11)');
          halo.addColorStop(1, 'rgba(2, 8, 19, 0)');
          ctx.fillStyle = halo;
          ctx.beginPath();
          ctx.arc(cx, cy, R * 1.18, 0, Math.PI * 2);
          ctx.fill();
        }

        // 3D Sphere Ocean Body with specular lighting
        const oceanGrad = ctx.createRadialGradient(cx - R * 0.28, cy - R * 0.28, R * 0.06, cx, cy, R);
        if (state.basemap === 'DARK_TACTICAL' || state.basemap === 'NASA_GIBS_NIGHTLIGHTS') {
          oceanGrad.addColorStop(0, '#0B1D36');
          oceanGrad.addColorStop(0.7, '#061124');
          oceanGrad.addColorStop(1, '#020711');
        } else {
          oceanGrad.addColorStop(0, '#134E7A');
          oceanGrad.addColorStop(0.58, '#0A2E52');
          oceanGrad.addColorStop(1, '#041428');
        }
        ctx.fillStyle = oceanGrad;
        ctx.beginPath();
        ctx.arc(cx, cy, R, 0, Math.PI * 2);
        ctx.fill();

        ctx.save();
        ctx.beginPath();
        ctx.arc(cx, cy, R, 0, Math.PI * 2);
        ctx.clip();
      } else {
        ctx.fillStyle = '#071B34';
        ctx.fillRect(0, 0, w, h);
        ctx.save();
      }

      // Render 3D Graticule (Lat/Lon Grid)
      ctx.strokeStyle = 'rgba(56, 189, 248, 0.13)';
      ctx.lineWidth = 0.7;
      for (let lat = -60; lat <= 60; lat += 30) {
        ctx.beginPath();
        let down = false;
        for (let lon = -180; lon <= 180; lon += 6) {
          const p = project3D(lat, lon, 0, cx, cy, R);
          if (p.visible) {
            if (!down) { ctx.moveTo(p.x, p.y); down = true; }
            else ctx.lineTo(p.x, p.y);
          } else down = false;
        }
        ctx.stroke();
      }
      for (let lon = -180; lon < 180; lon += 30) {
        ctx.beginPath();
        let down = false;
        for (let lat = -85; lat <= 85; lat += 5) {
          const p = project3D(lat, lon, 0, cx, cy, R);
          if (p.visible) {
            if (!down) { ctx.moveTo(p.x, p.y); down = true; }
            else ctx.lineTo(p.x, p.y);
          } else down = false;
        }
        ctx.stroke();
      }

      // Render 3D Continents from Natural Earth Country Polygons
      const landFill = (state.basemap === 'DARK_TACTICAL' || state.basemap === 'NASA_GIBS_NIGHTLIGHTS')
        ? 'rgba(17, 39, 66, 0.92)'
        : (state.basemap === 'OPENSTREETMAP'
          ? 'rgba(36, 84, 94, 0.88)'
          : 'rgba(24, 74, 69, 0.90)');
      for (let f = 0; f < state.countryFeatures.length; f++) {
        const geom = state.countryFeatures[f].geometry;
        if (!geom) continue;
        if (geom.type === 'Polygon') {
          for (let r = 0; r < geom.coordinates.length; r++) {
            drawRingCoords(geom.coordinates[r], cx, cy, R, r === 0 ? landFill : null, null, 0);
          }
        } else if (geom.type === 'MultiPolygon') {
          for (let pIdx = 0; pIdx < geom.coordinates.length; pIdx++) {
            const poly = geom.coordinates[pIdx];
            for (let r = 0; r < poly.length; r++) {
              drawRingCoords(poly[r], cx, cy, R, r === 0 ? landFill : null, null, 0);
            }
          }
        }
      }

      // Country Borders
      if (state.bordersVisible) {
        for (let i = 0; i < state.countryRings.length; i++) {
          drawRingCoords(state.countryRings[i], cx, cy, R, null, 'rgba(34, 211, 238, 0.56)', 1.05);
        }
      }

      // Day/Night Solar Terminator Shading
      if (state.dayNightLighting && state.projection !== 'MAP_2D') {
        const utcHours = (Date.now() % 86400000) / 3600000;
        const sunLon = ((12.0 - utcHours) * 15.0 + 540) % 360 - 180;
        const dSun = (((sunLon - cam.lon + 540) % 360) - 180) * DEG_TO_RAD;
        const nightX = cx - Math.sin(dSun) * R * 0.65;
        const termGrad = ctx.createRadialGradient(nightX, cy, R * 0.1, nightX, cy, R * 1.25);
        termGrad.addColorStop(0, 'rgba(1, 4, 12, 0.50)');
        termGrad.addColorStop(0.65, 'rgba(1, 4, 12, 0.16)');
        termGrad.addColorStop(1, 'rgba(1, 4, 12, 0)');
        ctx.fillStyle = termGrad;
        ctx.fillRect(cx - R, cy - R, R * 2, R * 2);
      }

      // Watchtower Geofence Rings
      for (let wIdx = 0; wIdx < state.watchRegionsCache.length; wIdx++) {
        const reg = state.watchRegionsCache[wIdx];
        if (!reg.enabled) continue;
        const degRadius = Math.max(0.5, (reg.radiusKm || 250) / 111.32);
        const ring = [];
        for (let a = 0; a <= 360; a += 12) {
          const rad = a * DEG_TO_RAD;
          ring.push([
            reg.centerLon + (degRadius * Math.cos(rad)) / Math.max(0.2, Math.cos(reg.centerLat * DEG_TO_RAD)),
            Math.max(-88, Math.min(88, reg.centerLat + degRadius * Math.sin(rad)))
          ]);
        }
        drawRingCoords(ring, cx, cy, R, 'rgba(34, 211, 238, 0.10)', 'rgba(34, 211, 238, 0.80)', 1.4);
      }

      // Simulation Hazard Rings
      if (state.simulationCache && state.simulationCache.active && Array.isArray(state.simulationCache.rings)) {
        state.simulationCache.rings.forEach(function (sRing) {
          const degR = Math.max(0.3, (sRing.radiusKm || 120) / 111.32);
          const ring = [];
          for (let a = 0; a <= 360; a += 12) {
            const rad = a * DEG_TO_RAD;
            ring.push([
              sRing.lon + (degR * Math.cos(rad)) / Math.max(0.2, Math.cos(sRing.lat * DEG_TO_RAD)),
              Math.max(-88, Math.min(88, sRing.lat + degR * Math.sin(rad)))
            ]);
          }
          drawRingCoords(ring, cx, cy, R, 'rgba(245, 158, 11, 0.20)', sRing.colorHex || '#F59E0B', 1.8);
        });
      }

      // Entity Trails
      for (let tIdx = 0; tIdx < state.trailsCache.length; tIdx++) {
        const tr = state.trailsCache[tIdx];
        if (!state.layerVisibility[tr.category] || !Array.isArray(tr.points) || tr.points.length < 2) continue;
        const coords = tr.points.map(function (pt) { return [pt.lon, pt.lat]; });
        drawRingCoords(coords, cx, cy, R, null, (CATEGORY_COLORS[tr.category] || '#38BDF8') + 'AA', 1.5);
      }

      // Correlated Incident Links
      ctx.setLineDash([5, 4]);
      for (let cIdx = 0; cIdx < state.correlationsCache.length; cIdx++) {
        const inc = state.correlationsCache[cIdx];
        if (!Array.isArray(inc.links)) continue;
        for (let lIdx = 0; lIdx < inc.links.length; lIdx++) {
          const lnk = inc.links[lIdx];
          drawRingCoords([[lnk.fromLon, lnk.fromLat], [lnk.toLon, lnk.toLat]], cx, cy, R, null, '#F472B6', 1.8);
        }
      }
      ctx.setLineDash([]);

      ctx.restore(); // Remove sphere clip so elevated 3D orbits & billboards draw cleanly above limb

      // Sphere Rim Highlight
      if (state.projection !== 'MAP_2D') {
        ctx.strokeStyle = 'rgba(56, 189, 248, 0.45)';
        ctx.lineWidth = 1.4;
        ctx.beginPath();
        ctx.arc(cx, cy, R, 0, Math.PI * 2);
        ctx.stroke();
      }

      // Selected Satellite 3D Orbit Ring
      if (selectedOrbitPts.length >= 2 && state.layerVisibility.SPACE) {
        ctx.strokeStyle = 'rgba(192, 132, 252, 0.88)';
        ctx.lineWidth = 2.0;
        ctx.beginPath();
        let down = false;
        for (let i = 0; i < selectedOrbitPts.length; i++) {
          const op = selectedOrbitPts[i];
          const p = project3D(op.latDeg, op.lonDeg, op.altMeters, cx, cy, R);
          if (p.visible) {
            if (!down) { ctx.moveTo(p.x, p.y); down = true; }
            else ctx.lineTo(p.x, p.y);
          } else down = false;
        }
        ctx.stroke();
      }

      // Live Intelligence Entities (3D altitude-aware markers + LineString cables)
      renderedScreenMarkers = [];
      const maxPerCat = state.performanceMode === 'LOW_POWER' ? 140 : 350;
      const counts = {};
      for (let i = 0; i < state.allEntitiesCache.length; i++) {
        const item = state.allEntitiesCache[i];
        const cat = item.category;
        if (!state.layerVisibility[cat]) continue;
        counts[cat] = (counts[cat] || 0) + 1;
        if (counts[cat] > maxPerCat) continue;

        const color = (item.severity === 'CRITICAL' || item.severity === 'HIGH')
          ? (SEVERITY_COLORS[item.severity] || CATEGORY_COLORS[cat])
          : (CATEGORY_COLORS[cat] || '#38BDF8');

        if (item.geometryType === 'LineString' && item.geometryCoordsJson) {
          try {
            const coords = JSON.parse(item.geometryCoordsJson);
            drawRingCoords(coords, cx, cy, R, null, color + 'AA', 1.6);
          } catch (_) { /* ignore */ }
        }

        if (!Number.isFinite(item.latitude) || !Number.isFinite(item.longitude)) continue;
        const p = project3D(item.latitude, item.longitude, item.altitudeMeters || 0, cx, cy, R);
        if (!p.visible) continue;

        const rad = item.severity === 'CRITICAL' ? 5.5 : 4.2;
        ctx.fillStyle = color + '44';
        ctx.beginPath();
        ctx.arc(p.x, p.y, rad + 4, 0, Math.PI * 2);
        ctx.fill();

        ctx.fillStyle = color;
        ctx.strokeStyle = '#020813';
        ctx.lineWidth = 1.4;
        ctx.beginPath();
        ctx.arc(p.x, p.y, rad, 0, Math.PI * 2);
        ctx.fill();
        ctx.stroke();

        if (item.severity === 'CRITICAL' || cat === 'SPACE' || cam.altMeters < 4500000) {
          ctx.fillStyle = '#F8FAFC';
          ctx.font = '600 9.5px sans-serif';
          ctx.fillText(String(item.title || '').slice(0, 22), p.x + 7, p.y + 3);
        }

        renderedScreenMarkers.push({ x: p.x, y: p.y, entity: item });
      }

      ctx.restore();
      animFrameId = requestAnimationFrame(renderFrame);
    }

    canvas.addEventListener('pointerdown', function (ev) {
      canvas.setPointerCapture(ev.pointerId);
      pointers.set(ev.pointerId, { x: ev.clientX, y: ev.clientY });
      cam.flyAnim = null;
      cam.velLon = 0;
      cam.velLat = 0;
      if (pointers.size === 1) {
        dragMoved = false;
        downX = ev.clientX;
        downY = ev.clientY;
      } else if (pointers.size === 2) {
        const pts = Array.from(pointers.values());
        lastPinchDist = Math.hypot(pts[0].x - pts[1].x, pts[0].y - pts[1].y);
      }
    });

    canvas.addEventListener('pointermove', function (ev) {
      const prev = pointers.get(ev.pointerId);
      if (!prev) return;
      const dx = ev.clientX - prev.x;
      const dy = ev.clientY - prev.y;
      pointers.set(ev.pointerId, { x: ev.clientX, y: ev.clientY });

      if (pointers.size === 1) {
        if (Math.hypot(ev.clientX - downX, ev.clientY - downY) > 5) {
          dragMoved = true;
        }
        const w = Math.max(320, window.innerWidth || 360);
        const h = Math.max(320, window.innerHeight || 640);
        const degPerPx = Math.max(0.015, (95 / globeRadiusPx(w, h)));
        cam.lon = (((cam.lon - dx * degPerPx) + 540) % 360) - 180;
        cam.lat = Math.max(-84, Math.min(84, cam.lat + dy * degPerPx));
        cam.velLon = -dx * degPerPx * 0.45;
        cam.velLat = dy * degPerPx * 0.45;
        emitTelemetry();
      } else if (pointers.size === 2) {
        dragMoved = true;
        const pts = Array.from(pointers.values());
        const dist = Math.hypot(pts[0].x - pts[1].x, pts[0].y - pts[1].y);
        if (lastPinchDist && dist > 8) {
          const ratio = lastPinchDist / dist;
          cam.altMeters = Math.max(150000, Math.min(32000000, cam.altMeters * ratio));
          emitTelemetry();
        }
        lastPinchDist = dist;
      }
    });

    function onPointerEnd(ev) {
      pointers.delete(ev.pointerId);
      if (pointers.size < 2) lastPinchDist = null;
      if (pointers.size === 0 && !dragMoved) {
        const rect = canvas.getBoundingClientRect();
        const px = ev.clientX - rect.left;
        const py = ev.clientY - rect.top;
        let hit = null;
        let bestDist = 20;
        for (let i = renderedScreenMarkers.length - 1; i >= 0; i--) {
          const m = renderedScreenMarkers[i];
          const d = Math.hypot(m.x - px, m.y - py);
          if (d < bestDist) {
            bestDist = d;
            hit = m.entity;
          }
        }
        if (hit) {
          postToAndroid('onEntitySelected', { entityId: hit.id });
          if (hit.category === 'SPACE') {
            state.selectedSatelliteId = hit.id;
            rebuildSelectedOrbit(hit.id);
          }
          return;
        }
        const w = Math.max(320, window.innerWidth || 360);
        const h = Math.max(320, window.innerHeight || 640);
        const geo = unprojectSphere(px, py, w * 0.5, h * 0.48, globeRadiusPx(w, h));
        if (geo) {
          postToAndroid('onLocationSelected', {
            lat: Number(geo.lat.toFixed(5)),
            lon: Number(geo.lon.toFixed(5)),
            elevationMeters: 0,
            countryName: '',
            terrainElevationMeters: 0,
            countryHint: ''
          });
        }
      }
    }

    canvas.addEventListener('pointerup', onPointerEnd);
    canvas.addEventListener('pointercancel', onPointerEnd);
    canvas.addEventListener('wheel', function (ev) {
      ev.preventDefault();
      const factor = ev.deltaY > 0 ? 1.18 : 0.84;
      cam.altMeters = Math.max(150000, Math.min(32000000, cam.altMeters * factor));
      emitTelemetry();
    }, { passive: false });

    function flyTo(lat, lon, altM) {
      cam.flyAnim = {
        start: performance.now(),
        durationMs: 900,
        fromLat: cam.lat,
        fromLon: cam.lon,
        fromAlt: cam.altMeters,
        toLat: Math.max(-85, Math.min(85, lat)),
        toLon: (((lon) + 540) % 360) - 180,
        toAlt: Math.max(150000, Math.min(32000000, altM || 2200000))
      };
    }

    return {
      start: function () {
        resize();
        emitTelemetry();
        if (!animFrameId) animFrameId = requestAnimationFrame(renderFrame);
      },
      stop: function () {
        if (animFrameId) {
          cancelAnimationFrame(animFrameId);
          animFrameId = null;
        }
      },
      emitTelemetry: emitTelemetry,
      rebuildSelectedOrbit: rebuildSelectedOrbit,
      handleCommand: function (cmd) {
        switch (cmd.type) {
          case 'SET_BASEMAP':
            state.basemap = cmd.basemap || 'SATELLITE_ESRI';
            break;
          case 'SET_PROJECTION':
            state.projection = cmd.projection || 'GLOBE_3D';
            emitTelemetry();
            break;
          case 'SET_STYLE_PRESET':
            state.stylePreset = cmd.stylePreset || 'CYBER_TACTICAL';
            document.body.setAttribute('data-style', state.stylePreset);
            break;
          case 'SET_SCENE_FLAGS':
            if (typeof cmd.dayNightLighting === 'boolean') state.dayNightLighting = cmd.dayNightLighting;
            if (typeof cmd.atmosphere === 'boolean') state.atmosphereEnabled = cmd.atmosphere;
            if (typeof cmd.borders === 'boolean') state.bordersVisible = cmd.borders;
            break;
          case 'SET_LAYER_STATE':
            if (cmd.category) {
              if (typeof cmd.visible === 'boolean') state.layerVisibility[cmd.category] = cmd.visible;
              if (Number.isFinite(cmd.opacity)) state.layerOpacity[cmd.category] = cmd.opacity;
            }
            break;
          case 'SET_PERFORMANCE_MODE':
            state.performanceMode = cmd.mode || 'BALANCED';
            scheduleOrbitTimer();
            break;
          case 'UPDATE_ENTITIES':
            addOrReplaceEntities(cmd.entities || []);
            break;
          case 'UPDATE_TRAILS':
            updateEntityTrails(cmd.trails || []);
            break;
          case 'UPDATE_CORRELATIONS':
            updateCorrelations(cmd.incidents || []);
            break;
          case 'UPDATE_WATCH_REGIONS':
            updateWatchRegions(cmd.regions || []);
            break;
          case 'UPDATE_BUILDINGS_3D':
            updateBuildings3D(cmd.buildings || []);
            break;
          case 'SET_SIMULATION_OVERLAY':
            setSimulationOverlay(cmd.simulation);
            break;
          case 'FLY_TO':
            if (Number.isFinite(cmd.lat) && Number.isFinite(cmd.lon)) {
              flyTo(cmd.lat, cmd.lon, cmd.altitudeMeters || 1800000);
            }
            break;
          case 'CAMERA_ACTION':
            if (cmd.action === 'ZOOM_IN') {
              cam.altMeters = Math.max(150000, cam.altMeters * 0.65);
            } else if (cmd.action === 'ZOOM_OUT') {
              cam.altMeters = Math.min(32000000, cam.altMeters * 1.48);
            } else if (cmd.action === 'TILT_3D') {
              state.projection = 'GLOBE_3D';
              cam.pitchDeg = cam.pitchDeg > -65 ? -88 : -44;
            } else if (cmd.action === 'RESET_NORTH') {
              cam.headingDeg = 0;
              cam.pitchDeg = -88;
            } else if (cmd.action === 'HOME' || cmd.action === 'HOME_GLOBE') {
              flyTo(14.5995, 120.9842, 13800000);
            }
            emitTelemetry();
            break;
          case 'COMPUTE_SATELLITE_PASSES': {
            const passes = computeSatellitePasses(cmd.entityId, cmd.lat, cmd.lon, cmd.hoursAhead || 24);
            postToAndroid('onSatellitePassesComputed', { entityId: cmd.entityId, passes: passes });
            break;
          }
          case 'SELECT_SATELLITE_ORBIT':
            state.selectedSatelliteId = cmd.entityId || null;
            rebuildSelectedOrbit(state.selectedSatelliteId);
            break;
          default:
            break;
        }
      }
    };
  }

  async function activateFallback3DCanvasGlobe(reason) {
    if (state.engineMode === 'canvas3d' && state.canvasEngine) return;
    console.warn('Activating 3D Spherical Canvas Globe failsafe:', reason);

    if (state.viewer) {
      try { state.viewer.destroy(); } catch (_) { /* ignore */ }
      state.viewer = null;
    }

    const cesiumDiv = document.getElementById('cesiumContainer');
    if (cesiumDiv) cesiumDiv.classList.add('hidden');

    const fallbackCanvas = document.getElementById('wmFallbackGlobeCanvas');
    if (!fallbackCanvas) return;
    fallbackCanvas.classList.remove('hidden');

    await ensureCountryTopologyLoaded();

    state.engineMode = 'canvas3d';
    state.canvasEngine = createCanvas3DGlobeEngine(fallbackCanvas);
    state.canvasEngine.start();

    scheduleOrbitTimer();
    if (!state.clockTimer) {
      state.clockTimer = setInterval(updateHudReadout, 1000);
    }

    hideBootSplash();

    const wasReady = state.ready;
    state.ready = true;
    while (state.commandQueue.length > 0) {
      handleCommand(state.commandQueue.shift());
    }
    if (state.allEntitiesCache.length > 0) {
      propagateSatellitesNow();
      selectDefaultIssIfNeeded();
    }
    if (!wasReady) {
      postToAndroid('onGlobeReady', {
        webgl: true,
        webglSupported: true,
        cesiumVersion: (typeof Cesium !== 'undefined' && Cesium.VERSION ? Cesium.VERSION : '1.147.0') + '-3DCanvas',
        timestampMs: Date.now()
      });
    }
  }

  function createCesiumViewerWithWebGlFallback() {
    function buildOptions(requestWebgl1) {
      return {
        baseLayer: new Cesium.ImageryLayer(createBundledOfflineBaseProvider()),
        terrainProvider: new Cesium.EllipsoidTerrainProvider(),
        animation: false,
        baseLayerPicker: false,
        fullscreenButton: false,
        vrButton: false,
        geocoder: false,
        homeButton: false,
        infoBox: false,
        sceneModePicker: false,
        selectionIndicator: false,
        timeline: false,
        navigationHelpButton: false,
        navigationInstructionsInitiallyVisible: false,
        scene3DOnly: false,
        shouldAnimate: true,
        requestRenderMode: false,
        targetFrameRate: 60,
        msaaSamples: 1,
        contextOptions: {
          requestWebgl1: !!requestWebgl1,
          webgl: {
            alpha: false,
            depth: true,
            stencil: false,
            antialias: true,
            powerPreference: 'high-performance',
            failIfMajorPerformanceCaveat: false
          }
        }
      };
    }

    try {
      return new Cesium.Viewer('cesiumContainer', buildOptions(false));
    } catch (webgl2Err) {
      console.warn('WebGL2 Cesium init failed, retrying WebGL1:', webgl2Err && webgl2Err.message ? webgl2Err.message : webgl2Err);
      const container = document.getElementById('cesiumContainer');
      if (container) container.innerHTML = '';
      return new Cesium.Viewer('cesiumContainer', buildOptions(true));
    }
  }

  async function initGlobe() {
    if (typeof Cesium === 'undefined') {
      await activateFallback3DCanvasGlobe('CesiumJS global unavailable');
      return;
    }

    try {
      Cesium.Ion.defaultAccessToken = '';
      const viewer = createCesiumViewerWithWebGlFallback();
      state.viewer = viewer;
      state.engineMode = 'cesium';
      state.imageryRefs.offlineBase = viewer.imageryLayers.get(0);

      // Scene & 3D Atmosphere configuration
      viewer.scene.backgroundColor = Cesium.Color.fromCssColorString('#020813');
      viewer.scene.globe.baseColor = Cesium.Color.fromCssColorString('#071A33');
      viewer.scene.globe.enableLighting = state.dayNightLighting;
      viewer.scene.globe.showGroundAtmosphere = state.atmosphereEnabled;
      viewer.scene.globe.depthTestAgainstTerrain = false;
      viewer.scene.globe.maximumScreenSpaceError = 2.0;
      if (viewer.scene.skyAtmosphere) {
        viewer.scene.skyAtmosphere.show = state.atmosphereEnabled;
      }
      viewer.resolutionScale = 0.94;

      // Explicit 3D Touch / Pointer Camera Controller tuning for mobile WebView
      const ctrl = viewer.scene.screenSpaceCameraController;
      ctrl.enableInputs = true;
      ctrl.enableRotate = true;
      ctrl.enableTranslate = true;
      ctrl.enableZoom = true;
      ctrl.enableTilt = true;
      ctrl.enableLook = true;
      ctrl.minimumZoomDistance = 120.0;
      ctrl.maximumZoomDistance = 42000000.0;
      ctrl.inertiaSpin = 0.88;
      ctrl.inertiaTranslate = 0.88;
      ctrl.inertiaZoom = 0.80;

      // Resize observer so Compose AndroidView layout passes immediately resize WebGL viewport
      const containerEl = document.getElementById('cesiumContainer');
      if (typeof ResizeObserver !== 'undefined' && containerEl) {
        const ro = new ResizeObserver(function () {
          if (state.viewer && !state.viewer.isDestroyed()) {
            state.viewer.resize();
            state.viewer.scene.requestRender();
          }
        });
        ro.observe(containerEl);
      }
      window.addEventListener('resize', function () {
        if (state.viewer && !state.viewer.isDestroyed()) {
          state.viewer.resize();
          state.viewer.scene.requestRender();
        }
      });

      // Self-heal on fatal WebGL render error
      if (viewer.scene.renderError && typeof viewer.scene.renderError.addEventListener === 'function') {
        viewer.scene.renderError.addEventListener(function (_scene, err) {
          activateFallback3DCanvasGlobe('WebGL renderError: ' + (err && err.message ? err.message : String(err)));
        });
      }

      // Watchdog: verify Cesium's WebWorker actually tessellated >=1 globe surface tile within 4.5s
      let surfaceTileRendered = false;
      viewer.scene.postRender.addEventListener(function () {
        if (surfaceTileRendered || !state.viewer) return;
        const surface = state.viewer.scene && state.viewer.scene.globe && state.viewer.scene.globe._surface;
        const count = surface && surface._tilesToRender ? surface._tilesToRender.length : 0;
        if (count > 0) {
          surfaceTileRendered = true;
          hideBootSplash();
        }
      });
      state.watchdogTimer = setTimeout(function () {
        hideBootSplash();
        if (state.engineMode === 'cesium' && !surfaceTileRendered) {
          activateFallback3DCanvasGlobe('Cesium WebWorker produced 0 globe tiles within watchdog window');
        }
      }, 4500);

      // Create per-category CustomDataSources with clustering
      Object.keys(CATEGORY_COLORS).forEach(function (cat) {
        const ds = new Cesium.CustomDataSource(`wm_${cat}`);
        ds.clustering.enabled = state.clusteringEnabled;
        ds.clustering.pixelRange = 36;
        ds.clustering.minimumClusterSize = 5;
        ds.clustering.clusterEvent.addEventListener(function (clusteredEntities, cluster) {
          cluster.label.show = false;
          cluster.billboard.show = true;
          cluster.billboard.id = cluster.label.id;
          cluster.billboard.verticalOrigin = Cesium.VerticalOrigin.CENTER;
          cluster.billboard.image = createClusterPin(clusteredEntities.length);
          cluster.billboard.width = 36;
          cluster.billboard.height = 36;
          cluster.billboard.disableDepthTestDistance = Number.POSITIVE_INFINITY;
        });
        viewer.dataSources.add(ds);
        state.dataSources[cat] = ds;
      });

      state.correlationsSource = new Cesium.CustomDataSource('wm_correlations');
      state.watchtowerSource = new Cesium.CustomDataSource('wm_watchtower');
      state.simulationSource = new Cesium.CustomDataSource('wm_simulation');
      state.buildingsSource = new Cesium.CustomDataSource('wm_buildings3d');
      viewer.dataSources.add(state.correlationsSource);
      viewer.dataSources.add(state.watchtowerSource);
      viewer.dataSources.add(state.simulationSource);
      viewer.dataSources.add(state.buildingsSource);

      // Initial orbital view centered over Indo-Pacific / Philippines
      viewer.camera.setView({
        destination: Cesium.Cartesian3.fromDegrees(120.9842, 14.5995, 13800000),
        orientation: {
          heading: 0,
          pitch: Cesium.Math.toRadians(-88),
          roll: 0
        }
      });

      // Click / Tap Picking
      const handler = new Cesium.ScreenSpaceEventHandler(viewer.scene.canvas);
      handler.setInputAction(function (movement) {
        const picked = viewer.scene.pick(movement.position);
        if (Cesium.defined(picked) && picked.id) {
          if (Array.isArray(picked.id) && picked.id.length > 0) {
            const first = picked.id[0];
            if (first && first.position) {
              const p = first.position.getValue(Cesium.JulianDate.now());
              if (p) {
                const c = Cesium.Cartographic.fromCartesian(p);
                viewer.camera.flyTo({
                  destination: Cesium.Cartesian3.fromRadians(
                    c.longitude,
                    c.latitude,
                    Math.max(80000, viewer.camera.positionCartographic.height * 0.35)
                  ),
                  duration: 0.85
                });
              }
            }
            return;
          }

          const ent = picked.id;
          if (ent.wmEntity) {
            postToAndroid('onEntitySelected', { entityId: ent.wmEntity.id });
            if (ent.wmEntity.category === 'SPACE') {
              state.selectedSatelliteId = ent.wmEntity.id;
              renderSatelliteOrbitAndGroundTrack(ent.wmEntity.id);
            }
            return;
          }
          if (ent.wmBuilding) {
            postToAndroid('onBuildingSelected', ent.wmBuilding);
            return;
          }
        }

        const ray = viewer.camera.getPickRay(movement.position);
        const cartesian = ray
          ? (viewer.scene.globe.pick(ray, viewer.scene) || viewer.camera.pickEllipsoid(movement.position, viewer.scene.globe.ellipsoid))
          : null;
        if (cartesian) {
          const carto = Cesium.Cartographic.fromCartesian(cartesian);
          const lat = Cesium.Math.toDegrees(carto.latitude);
          const lon = Cesium.Math.toDegrees(carto.longitude);
          const elev = viewer.scene.globe.getHeight(carto) || 0;
          postToAndroid('onLocationSelected', {
            lat: Number(lat.toFixed(5)),
            lon: Number(lon.toFixed(5)),
            elevationMeters: Math.round(elev),
            countryName: '',
            terrainElevationMeters: Math.round(elev),
            countryHint: ''
          });
        }
      }, Cesium.ScreenSpaceEventType.LEFT_CLICK);

      viewer.camera.changed.addEventListener(updateHudReadout);
      viewer.camera.moveEnd.addEventListener(updateHudReadout);

      // Split bar drag
      const splitBar = document.getElementById('wmSplitBar');
      if (splitBar) {
        let dragging = false;
        splitBar.addEventListener('pointerdown', function (ev) {
          dragging = true;
          splitBar.setPointerCapture(ev.pointerId);
        });
        splitBar.addEventListener('pointermove', function (ev) {
          if (!dragging || !state.viewer) return;
          const pct = Math.max(0.08, Math.min(0.92, ev.clientX / Math.max(1, window.innerWidth)));
          state.viewer.scene.splitPosition = pct;
          splitBar.style.left = `${(pct * 100).toFixed(1)}%`;
          state.viewer.scene.requestRender();
        });
        splitBar.addEventListener('pointerup', function () { dragging = false; });
      }

      // Mark globe ready BEFORE optional remote layers so bridge handshake never stalls
      state.ready = true;
      scheduleOrbitTimer();
      state.clockTimer = setInterval(updateHudReadout, 1000);
      updateHudReadout();

      while (state.commandQueue.length > 0) {
        handleCommand(state.commandQueue.shift());
      }

      postToAndroid('onGlobeReady', {
        webgl: true,
        webglSupported: true,
        cesiumVersion: Cesium.VERSION || '1.147.0',
        timestampMs: Date.now()
      });

      // Async non-blocking overlays (Esri Satellite + Labels + Bundled Country Borders)
      setBasemap(state.basemap).catch(function () {});
      try {
        const labelsProvider = new Cesium.UrlTemplateImageryProvider({
          url: 'https://services.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/{z}/{y}/{x}',
          maximumLevel: 19,
          credit: 'Esri Reference Boundaries & Labels'
        });
        const labelsLayer = viewer.imageryLayers.addImageryProvider(labelsProvider);
        labelsLayer.alpha = 0.86;
        labelsLayer.show = state.labelsVisible;
        state.imageryRefs.labelsOverlay = labelsLayer;
        attachOfflineFallbackWatcher(labelsProvider, function () { return state.imageryRefs.labelsOverlay; });
      } catch (_) { /* optional labels */ }

      loadBundledCountryBorders().catch(function () {});
      setTimeout(hideBootSplash, 900);
    } catch (err) {
      await activateFallback3DCanvasGlobe(err && err.message ? err.message : String(err));
    }
  }

  function decodeBase64Utf8(b64) {
    const bin = window.atob(b64);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) {
      bytes[i] = bin.charCodeAt(i);
    }
    if (typeof TextDecoder !== 'undefined') {
      return new TextDecoder('utf-8').decode(bytes);
    }
    let esc = '';
    for (let i = 0; i < bytes.length; i++) {
      esc += '%' + ('00' + bytes[i].toString(16)).slice(-2);
    }
    return decodeURIComponent(esc);
  }

  window.WorldMonitorHost = {
    dispatch: function (rawCommand) {
      try {
        const cmd = typeof rawCommand === 'string' ? JSON.parse(rawCommand) : rawCommand;
        if (!state.ready) {
          state.commandQueue.push(cmd);
          return true;
        }
        handleCommand(cmd);
        return true;
      } catch (err) {
        console.error('WorldMonitorHost.dispatch error:', err);
        return false;
      }
    },
    isReady: function () {
      return state.ready;
    },
    getEngineMode: function () {
      return state.engineMode;
    },
    destroy: function () {
      if (state.orbitTimer) { clearInterval(state.orbitTimer); state.orbitTimer = null; }
      if (state.clockTimer) { clearInterval(state.clockTimer); state.clockTimer = null; }
      if (state.radarTimer) { clearInterval(state.radarTimer); state.radarTimer = null; }
      if (state.watchdogTimer) { clearTimeout(state.watchdogTimer); state.watchdogTimer = null; }
      if (state.canvasEngine && typeof state.canvasEngine.stop === 'function') {
        state.canvasEngine.stop();
      }
      if (state.viewer) {
        try { state.viewer.destroy(); } catch (_) { /* ignore */ }
        state.viewer = null;
      }
    }
  };

  window.WorldMonitorApp = {
    receiveBase64: function (b64) {
      try {
        if (!b64 || typeof b64 !== 'string') return false;
        const jsonStr = decodeBase64Utf8(b64);
        return window.WorldMonitorHost.dispatch(jsonStr);
      } catch (err) {
        console.error('WorldMonitorApp.receiveBase64 error:', err);
        return false;
      }
    }
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initGlobe);
  } else {
    initGlobe();
  }
})();
