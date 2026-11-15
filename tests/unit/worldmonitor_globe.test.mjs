import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { JSDOM } from "jsdom";

const ROOT = process.cwd();
const ASSETS_DIR = path.join(ROOT, "app/src/main/assets/worldmonitor");

test("World Monitor 2035 asset bundle, CSP worker permissions, NaturalEarthII tiles, and baseline schema", () => {
    const html = fs.readFileSync(path.join(ASSETS_DIR, "index.html"), "utf8");
    assert.match(html, /Content-Security-Policy/);
    assert.match(html, /'unsafe-eval'/);
    assert.match(html, /worker-src[^;]*blob:[^;]*data:/);
    assert.match(html, /vendor\/cesium\/Cesium\.js/);
    assert.match(html, /vendor\/satellite\.min\.js/);
    assert.match(html, /vendor\/topojson-client\.min\.js/);

    const css = fs.readFileSync(path.join(ASSETS_DIR, "worldmonitor.css"), "utf8");
    assert.match(css, /touch-action:\s*none/);

    // Verify bundled NaturalEarthII offline globe tiles exist for synchronous 3D startup
    assert.ok(fs.existsSync(path.join(ASSETS_DIR, "vendor/cesium/Assets/Textures/NaturalEarthII/0/0/0.jpg")));
    assert.ok(fs.existsSync(path.join(ASSETS_DIR, "vendor/cesium/Assets/Textures/NaturalEarthII/0/1/0.jpg")));

    const baseline = JSON.parse(
        fs.readFileSync(path.join(ASSETS_DIR, "baseline/global_reference.json"), "utf8")
    );
    assert.ok(baseline.countries.length >= 25, "Expected >=25 sovereign countries in offline reference");
    assert.ok(baseline.airports.length >= 25, "Expected >=25 hub airports in offline reference");
    assert.ok(baseline.seaports.length >= 15, "Expected >=15 seaports in offline reference");
    assert.ok(baseline.submarineCables.length >= 8, "Expected >=8 submarine cables in offline reference");
    assert.ok(baseline.powerStations.length >= 10, "Expected >=10 power plants in offline reference");

    assert.ok(fs.existsSync(path.join(ASSETS_DIR, "vendor/satellite.LICENSE.md")));
    assert.ok(fs.existsSync(path.join(ASSETS_DIR, "vendor/topojson-client.LICENSE")));
    assert.ok(fs.existsSync(path.join(ASSETS_DIR, "vendor/world-atlas.LICENSE")));
});

test("World Monitor 2035 satellite.js SGP4 orbital propagation & Natural Earth TopoJSON decoding", () => {
    const dom = new JSDOM("<!doctype html><html><body></body></html>", {
        runScripts: "dangerously"
    });
    for (const rel of ["vendor/satellite.min.js", "vendor/topojson-client.min.js"]) {
        const script = dom.window.document.createElement("script");
        script.textContent = fs.readFileSync(path.join(ASSETS_DIR, rel), "utf8");
        dom.window.document.body.appendChild(script);
    }

    const { satellite, topojson } = dom.window;
    assert.ok(satellite, "satellite.js UMD global must attach to window");
    assert.ok(topojson, "topojson-client UMD global must attach to window");

    const worldAtlas = JSON.parse(
        fs.readFileSync(path.join(ASSETS_DIR, "vendor/countries-110m.json"), "utf8")
    );
    const geo = topojson.feature(worldAtlas, worldAtlas.objects.countries);
    assert.equal(geo.type, "FeatureCollection");
    assert.ok(geo.features.length >= 170, "Expected >=170 Natural Earth country polygons");

    const line1 = "1 25544U 98067A   26312.50000000  .00016717  00000+0  10270-3 0  9993";
    const line2 = "2 25544  51.6416 247.4627 0006703 130.5360 325.0288 15.50103472421535";
    const satrec = satellite.twoline2satrec(line1, line2);
    const when = new dom.window.Date("2026-11-08T12:00:00Z");
    const pv = satellite.propagate(satrec, when);
    assert.ok(pv && pv.position, "SGP4 propagation must return ECI position vector");

    const gmst = satellite.gstime(when);
    const gd = satellite.eciToGeodetic(pv.position, gmst);
    const latDeg = satellite.degreesLat(gd.latitude);
    const lonDeg = satellite.degreesLong(gd.longitude);
    assert.ok(latDeg >= -52.5 && latDeg <= 52.5, `ISS latitude must stay within inclination: ${latDeg}`);
    assert.ok(lonDeg >= -180 && lonDeg <= 180, `ISS longitude must be valid: ${lonDeg}`);
    assert.ok(gd.height > 350 && gd.height < 480, `ISS altitude km must be realistic LEO: ${gd.height}`);
});

test("World Monitor 2035 3D globe runtime self-heals to 3D spherical canvas engine when WebGL is absent", async () => {
    const html = fs.readFileSync(path.join(ASSETS_DIR, "index.html"), "utf8")
        .replace(/<script src="\.\/vendor\/cesium\/Cesium\.js"><\/script>/, "")
        .replace(/<script src="\.\/vendor\/satellite\.min\.js"><\/script>/, "")
        .replace(/<script src="\.\/vendor\/topojson-client\.min\.js"><\/script>/, "")
        .replace(/<script src="\.\/worldmonitor\.js"><\/script>/, "");

    const dom = new JSDOM(html, {
        url: "https://appassets.androidplatform.net/assets/worldmonitor/index.html",
        runScripts: "dangerously"
    });
    const w = dom.window;
    w.TransformStream = globalThis.TransformStream;
    w.ReadableStream = globalThis.ReadableStream;
    w.WritableStream = globalThis.WritableStream;
    w.TextDecoder = globalThis.TextDecoder;
    w.TextEncoder = globalThis.TextEncoder;
    w.requestAnimationFrame = () => 1;
    w.cancelAnimationFrame = () => {};

    w.HTMLCanvasElement.prototype.getContext = function (type) {
        if (type !== "2d") return null;
        return {
            save() {}, restore() {}, scale() {}, fillRect() {}, clearRect() {},
            beginPath() {}, closePath() {}, moveTo() {}, lineTo() {}, arc() {}, fill() {}, stroke() {}, clip() {},
            setLineDash() {}, fillText() {}, strokeRect() {}, drawImage() {},
            getImageData: () => ({ data: new Uint8ClampedArray(32 * 32 * 4) }),
            createRadialGradient: () => ({ addColorStop() {} })
        };
    };
    w.HTMLCanvasElement.prototype.toDataURL = () => "data:image/png;base64,AA==";
    w.fetch = async (u) => {
        const rel = String(u).replace(/^\.\//, "");
        const buf = fs.readFileSync(path.join(ASSETS_DIR, rel));
        return new Response(buf, { status: 200 });
    };

    const readyPayloads = [];
    const passPayloads = [];
    w.WorldMonitorBridge = {
        onGlobeReady: (p) => readyPayloads.push(JSON.parse(p)),
        onGlobeError: () => {},
        onViewportChanged: () => {},
        onSatellitePassesComputed: (p) => passPayloads.push(JSON.parse(p))
    };

    for (const rel of ["vendor/satellite.min.js", "vendor/topojson-client.min.js", "worldmonitor.js"]) {
        const script = w.document.createElement("script");
        script.textContent = fs.readFileSync(path.join(ASSETS_DIR, rel), "utf8");
        w.document.body.appendChild(script);
    }

    await new Promise((r) => setTimeout(r, 80));

    assert.equal(w.WorldMonitorHost.isReady(), true);
    assert.equal(w.WorldMonitorHost.getEngineMode(), "canvas3d");
    assert.equal(readyPayloads.length, 1);
    assert.equal(readyPayloads[0].webgl, true);
    assert.equal(readyPayloads[0].webglSupported, true);
    assert.equal(
        w.CESIUM_BASE_URL,
        "https://appassets.androidplatform.net/assets/worldmonitor/vendor/cesium/"
    );

    // Verify exact Base64 bridge path used by WorldMonitorJsBridge.buildJsDispatchExpression
    const updateCmdJson = JSON.stringify({
        type: "UPDATE_ENTITIES",
        entities: [{
            id: "celestrak:25544",
            category: "SPACE",
            severity: "INFO",
            title: "ISS (ZARYA) — 軌道",
            latitude: 0,
            longitude: 0,
            tleLine1: "1 25544U 98067A   26312.50000000  .00016717  00000+0  10270-3 0  9993",
            tleLine2: "2 25544  51.6416 247.4627 0006703 130.5360 325.0288 15.50103472421535"
        }]
    });
    const b64 = Buffer.from(updateCmdJson, "utf8").toString("base64");
    assert.equal(w.WorldMonitorApp.receiveBase64(b64), true);

    assert.equal(w.WorldMonitorHost.dispatch({ type: "CAMERA_ACTION", action: "TILT_3D" }), true);
    assert.equal(w.WorldMonitorHost.dispatch({ type: "CAMERA_ACTION", action: "HOME" }), true);

    const passCmdB64 = Buffer.from(
        JSON.stringify({
            type: "COMPUTE_SATELLITE_PASSES",
            entityId: "celestrak:25544",
            lat: 14.5995,
            lon: 120.9842,
            hoursAhead: 36
        }),
        "utf8"
    ).toString("base64");
    assert.equal(w.WorldMonitorApp.receiveBase64(passCmdB64), true);
    assert.equal(passPayloads.length, 1);
    assert.equal(passPayloads[0].entityId, "celestrak:25544");
    if (passPayloads[0].passes.length > 0) {
        const firstPass = passPayloads[0].passes[0];
        assert.equal(typeof firstPass.riseEpochMs, "number");
        assert.equal(typeof firstPass.culminationEpochMs, "number");
        assert.equal(typeof firstPass.setEpochMs, "number");
        assert.equal(typeof firstPass.durationSeconds, "number");
    }

    w.WorldMonitorHost.destroy();
    dom.window.close();
});
