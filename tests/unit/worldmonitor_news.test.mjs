/**
 * Unit tests — World Monitor "Global News" layer.
 *
 * The News layer failed in two independent places, and both are covered here because neither
 * is visible to the Kotlin suite:
 *
 *  1. Transport / source list — every `wm.news.*` provider must point at a real, keyless,
 *     HTTPS news document and must route through the structural RSS reader. A provider whose
 *     endpoint 404s (the retired GDELT 2.0 Geo API did) contributes zero headlines forever,
 *     which is exactly the "I can't see the news" symptom.
 *
 *  2. Rendering — `WorldMonitorScreen` composed a `NEWS` entity, but the bundled globe script
 *     only created Cesium data sources for the categories listed in `CATEGORY_COLORS` and its
 *     `layerVisibility` map. News entities were therefore skipped by `addOrReplaceEntities`
 *     (`const ds = state.dataSources[cat]; if (!ds) continue;`) and never drawn.
 *
 * These assertions read the shipped sources, so a regression fails the ordinary web CI job
 * without needing an Android toolchain.
 */
import test from 'node:test';
import { strict as assert } from 'node:assert';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const ROOT = new URL('../../', import.meta.url);
const read = (p) => readFileSync(new URL(p, ROOT), 'utf8');

const REGISTRY = 'app/src/main/java/com/adshield/pro/engine/worldmonitor/WorldMonitorProviderRegistry.kt';
const NORMALIZERS = 'app/src/main/java/com/adshield/pro/engine/worldmonitor/WorldMonitorNormalizers.kt';
const PARSER = 'app/src/main/java/com/adshield/pro/engine/worldmonitor/NewsFeedParser.kt';
const GLOBE = 'app/src/main/assets/worldmonitor/worldmonitor.js';
const PANELS = 'app/src/main/java/com/adshield/pro/ui/screens/worldmonitor/WorldMonitorDeckPanels.kt';
const VIEWMODEL = 'app/src/main/java/com/adshield/pro/ui/viewmodel/WorldMonitorViewModel.kt';

/** Returns the source text of one `WorldProviderSpec(...)` block by providerId. */
function providerBlock(source, providerId) {
  const marker = `providerId = "${providerId}"`;
  const at = source.indexOf(marker);
  if (at < 0) return null;
  const start = source.lastIndexOf('WorldProviderSpec(', at);
  const end = source.indexOf(')', source.indexOf('docsUrl', at));
  return source.slice(start, end > start ? end + 1 : source.length);
}

function newsProviderIds(source) {
  return [...source.matchAll(/providerId = "(wm\.news\.[a-z0-9.]+)"/g)].map((m) => m[1]);
}

test('every Global News provider is HTTPS, keyless and routed through the RSS reader', () => {
  const registry = read(REGISTRY);
  const ids = newsProviderIds(registry);
  assert.ok(ids.length >= 4, `expected at least 4 news outlets, found ${ids.length}`);

  for (const id of ids) {
    const block = providerBlock(registry, id);
    assert.ok(block, `missing provider block for ${id}`);
    const endpoint = /endpoint = "([^"]+)"/.exec(block)?.[1];
    assert.ok(endpoint?.startsWith('https://'), `${id} endpoint must be HTTPS, got ${endpoint}`);
    assert.match(block, /authRequirement = ProviderAuthRequirement\.NONE_PUBLIC/, `${id} must be keyless`);
    assert.match(block, /normalizerId = "news-rss"/, `${id} must use the structural RSS reader`);
    assert.match(block, /defaultEnabled = true/, `${id} must be enabled by default`);
    // A provider marked unimplemented is silently skipped by the sync engine.
    assert.doesNotMatch(block, /implemented = false/, `${id} must be implemented`);
  }
});

test('the retired GDELT 2.0 Geo endpoint is not advertised as a working source', () => {
  const registry = read(REGISTRY);
  assert.doesNotMatch(
    registry,
    /gdeltproject\.org\/api\/v2\/geo/,
    'GDELT 2.0 Geo returns 404 for keyless requests and must not be listed as a live provider'
  );
});

test('all news normalizer ids resolve to the one real parser', () => {
  const normalizers = read(NORMALIZERS);
  const parser = read(PARSER);
  // The dispatcher must route news to parseNewsFeed …
  assert.match(normalizers, /"news-rss" -> parseNewsFeed\(/, 'normalizer dispatch must route news-rss');
  // … and no provider may still reference the deleted, document-wide-regex parsers.
  assert.doesNotMatch(normalizers, /"un-news-rss"/, 'obsolete un-news-rss route must be gone');
  assert.doesNotMatch(normalizers, /"gdelt-geojson"/, 'obsolete gdelt-geojson route must be gone');
  assert.match(normalizers, /fun parseNewsFeed\(/, 'parseNewsFeed must exist');
  // The structural reader is the only place that pairs a title with a link.
  assert.match(parser, /ITEM_BLOCK/, 'NewsFeedParser must isolate <item>/<entry> blocks');
  assert.match(parser, /fun parseDate\(/, 'NewsFeedParser must expose honest date parsing');
});

test('news summaries never jitter a coordinate onto an invented location', () => {
  const normalizers = read(NORMALIZERS);
  const start = normalizers.indexOf('fun parseNewsFeed(');
  const end = normalizers.indexOf('private fun newsSubType');
  const body = normalizers.slice(start, end);
  assert.ok(start > 0 && end > start, 'parseNewsFeed body not found');
  // The old implementation added `((idx % 7) - 3) * 0.012` to a centroid, which places a story
  // at a fake street address. Country centroid or outlet dateline only.
  assert.doesNotMatch(body, /0\.012/, 'coordinates must not be jittered with a fabricated offset');
  assert.match(body, /GeoAccuracy\.ADMIN_CENTROID/, 'named countries keep centroid accuracy');
  assert.match(body, /GeoAccuracy\.SOURCE_DESK/, 'unmatched items must be labelled as a dateline');
});

test('the globe draws the NEWS layer instead of dropping it', () => {
  const globe = read(GLOBE);
  // A category only gets a Cesium data source if it is in CATEGORY_COLORS.
  assert.match(globe, /CATEGORY_COLORS = \{[\s\S]*?NEWS:\s*'#[0-9A-Fa-f]{6}'/, 'NEWS needs a colour/tactical icon entry');
  assert.match(globe, /layerVisibility:\s*\{[\s\S]*?NEWS:\s*true/, 'NEWS must start visible');
  assert.match(globe, /category === 'NEWS'/, 'NEWS needs its own tactical marker');
  // Same omission existed for the cyber layer, which the provider registry also populates.
  assert.match(globe, /CATEGORY_COLORS = \{[\s\S]*?CYBER:\s*'#[0-9A-Fa-f]{6}'/, 'CYBER needs a colour entry');
});

test('the app exposes a readable Global News desk', () => {
  const viewModel = read(VIEWMODEL);
  const panels = read(PANELS);
  assert.match(viewModel, /NEWS\("Global News"\)/, 'the deck needs a Global News tab');
  assert.match(viewModel, /val newsEntities: List<WorldEntity>/, 'news must be derived as its own list');
  assert.match(panels, /WorldMonitorDeckTab\.NEWS -> GlobalNewsPanel\(/, 'the tab must render a panel');
  assert.match(panels, /private fun GlobalNewsPanel\(/, 'GlobalNewsPanel must exist');
  // The panel must show the real headline text and the real source link.
  assert.match(panels, /entity\.title/, 'the desk must render the headline itself');
  assert.match(panels, /Read at source/, 'the desk must link to the outlet article');
  // Silent outlets are reported, not hidden behind an empty list.
  assert.match(panels, /newsSourceIssues/, 'the desk must explain outlets that returned nothing');
});
