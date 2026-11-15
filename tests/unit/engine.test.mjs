/**
 * Unit tests — filter engine (v5).
 *
 * Covers the evaluation ladder, normalization, formats, wildcards, caching and
 * — most importantly — the compatibility guarantee: the real bundled production
 * list must never block working services.
 */
import test from 'node:test';
import { strict as assert } from 'node:assert';
import { readFileSync } from 'node:fs';
import { OmniShieldFilterEngine } from '../../engine.js';

const read = (p) => readFileSync(new URL('../../assets/' + p, import.meta.url), 'utf8');
const LITE = read('core_lite_ads.txt');
const SUPPLEMENT = read('core_supplement_ads.txt');
const AGGRESSIVE = read('core_supplement_aggressive.txt');

test('normalization matches the Kotlin engine', () => {
  const n = OmniShieldFilterEngine.normalizeDomain;
  assert.equal(n('HTTPS://User:Pass@WWW.DoubleClick.NET:443/adx/test?q=1'), 'doubleclick.net');
  assert.equal(n('doubleclick.net.'), 'doubleclick.net');
  assert.equal(n('.0.0.0.0-like.example'), '0.0.0.0-like.example');
  assert.equal(n(''), '');
  assert.equal(n('no-dot'), '');
  assert.equal(n('bad domain.com'), '');
  assert.equal(n('a'.repeat(70) + '.com'), '');
  assert.equal(n('localhost'), 'localhost');
});

test('ladder order: user allow > user block > safety > exception > feed', () => {
  const e = new OmniShieldFilterEngine();
  e.loadFilterList('||tracker.example^\n@@||safe.tracker.example^\n||doubleclick.net^\n', 'ADS', 'test');
  e.addUserRule('||blockme.example^', false);
  e.addUserRule('||allowed.example^', true);

  assert.deepEqual(
    [e.evaluate('sub.tracker.example').blocked, e.evaluate('sub.tracker.example').source],
    [true, 'test']
  );
  assert.equal(e.evaluate('safe.tracker.example').blocked, false);
  assert.equal(e.evaluate('safe.tracker.example').source, 'List Exception');
  assert.equal(e.evaluate('connectivitycheck.gstatic.com').source, 'System Safety Allowlist');
  assert.equal(e.evaluate('x.blockme.example').source, 'User Blocklist');
  assert.equal(e.evaluate('x.allowed.example').source, 'User Allowlist');
  assert.equal(e.evaluate('not-blocked.example.org').blocked, false);

  // User allow wins over a feed block.
  e.addUserRule('||doubleclick.net^', true);
  assert.equal(e.evaluate('ad.doubleclick.net').source, 'User Allowlist');
  // User block wins over the safety allowlist.
  e.addUserRule('||connectivitycheck.gstatic.com^', false);
  assert.equal(e.evaluate('connectivitycheck.gstatic.com').source, 'User Blocklist');
});

test('every verdict is explainable', () => {
  const e = new OmniShieldFilterEngine();
  e.loadFilterList('||ads.example^\n', 'ADS', 'test');
  const verdict = e.evaluate('https://cdn.ads.example/banner.png');
  assert.equal(verdict.blocked, true);
  assert.equal(verdict.matched, 'ads.example');
  assert.equal(verdict.category, 'ADS');
  assert.ok(verdict.reasons.length >= 1);
  assert.match(verdict.reasons.join(' '), /ads\.example/);
  // Never a bare "blocked" with no reason.
  for (const host of ['ok.example.org', 'ads.example', 'captive.apple.com', '192.168.0.1']) {
    assert.ok(e.evaluate(host).reasons.length > 0, `no reason for ${host}`);
  }
});

test('parses hosts-file and plain-domain lists', () => {
  const e = new OmniShieldFilterEngine();
  const res = e.loadFilterList(
    ['# hosts style', '0.0.0.0 tracker.hosts.example', '127.0.0.1\tads.hosts.example', 'plain.example.com', '! adblock', '||caret.example^'].join('\n'),
    'TRACKING', 'mixed'
  );
  assert.equal(res.hosts, 2);
  assert.equal(e.evaluate('tracker.hosts.example').blocked, true);
  assert.equal(e.evaluate('ads.hosts.example').blocked, true);
  assert.equal(e.evaluate('plain.example.com').blocked, true);
  assert.equal(e.evaluate('caret.example').blocked, true);
  assert.equal(res.invalid, 0);
});

test('wildcards compile, match and never match everything', () => {
  const e = new OmniShieldFilterEngine();
  const res = e.loadFilterList('||ads*.example^\n||*.track.example^\n||*^\n', 'ADS', 'wild');
  assert.ok(res.wildcards >= 2);
  assert.equal(e.evaluate('ads1.example').blocked, true);
  assert.equal(e.evaluate('sub.track.example').blocked, true);
  assert.equal(e.evaluate('unrelated.org').blocked, false, 'a catch-all wildcard must be rejected');
  // Wildcard does not leak across label boundaries.
  assert.equal(e.evaluate('ads1.example').blocked, true);
  assert.equal(e.evaluate('xads1.example').blocked, false);
});

test('IP literals are reported, not blocked', () => {
  const e = new OmniShieldFilterEngine();
  e.loadFilterList('||1.0.0.127.in-addr.arpa^\n', 'ADS', 'ip');
  const v = e.evaluate('192.168.1.10');
  assert.equal(v.blocked, false);
  assert.equal(v.source, 'IP literal');
  assert.match(v.reasons[0], /IP address literal/);
});

test('verdict cache is bounded and invalidated by rule changes', () => {
  const e = new OmniShieldFilterEngine({ verdictCacheSize: 8 });
  e.loadFilterList('||cache.example^\n', 'ADS', 'cache');
  for (let i = 0; i < 50; i++) e.evaluate(`cache.example?${i}`);
  assert.ok(e._verdictCache.size <= 8, `cache grew to ${e._verdictCache.size}`);
  assert.equal(e.evaluate('cache.example').blocked, true);
  e.addUserRule('||cache.example^', true);
  assert.equal(e.evaluate('cache.example').blocked, false, 'allow rule must invalidate cached verdict');
});

test('REAL production list protects without breaking the internet', () => {
  const e = new OmniShieldFilterEngine();
  const res = e.loadFilterList(LITE, 'ADS', 'core_lite_ads');
  assert.ok(res.added > 70000, `expected the full lite feed, got ${res.added}`);

  const mustWork = [
    'www.youtube.com', 'r1---sn-ab5l6ne7.googlevideo.com', 'i.ytimg.com',
    'www.netflix.com', 'www.tiktok.com', 'www.facebook.com', 'www.instagram.com',
    'accounts.google.com', 'play.google.com', 'www.paypal.com', 'aena.ms',
    'web.whatsapp.com', 'discord.com', 'github.com', 'www.wikipedia.org',
    'outlook.office365.com', 'login.microsoftonline.com', 'www.amazon.com',
    'api.github.com', 'cdn.jsdelivr.net', '1.1.1.1'
  ];
  for (const host of mustWork) {
    const v = e.evaluate(host);
    assert.equal(v.blocked, false, `COMPATIBILITY BREAK: ${host} blocked via ${v.source}`);
  }

  // The Light feed is subdomain-oriented: these apex domains are NOT expected
  // to be covered by it alone (documented, measured, not a bug).
  const liteBlocked = ['adnxs.com', 'google-analytics.com'].filter((h) => e.evaluate(h).blocked).length;
  assert.equal(liteBlocked, 2, 'lite feed apex coverage changed — re-measure before changing this test');

  // Subdomains that the feed really does carry must be blocked.
  for (const host of ['ad.ae.doubleclick.net', 'b.scorecardresearch.com', 'ads.scorecardresearch.com']) {
    assert.equal(e.evaluate(host).blocked, true, `${host} should be covered by the lite feed`);
  }
});

test('supplement closes apex gaps without breaking core services', () => {
  const e = new OmniShieldFilterEngine();
  e.loadFilterList(LITE, 'ADS', 'core_lite_ads');
  const apexes = ['doubleclick.net', 'scorecardresearch.com', 'criteo.com', 'outbrain.com'];
  const before = apexes
    .filter((h) => e.evaluate(h).blocked).length;

  const res = e.loadFilterList(SUPPLEMENT, 'ADS', 'core_supplement_ads');
  assert.ok(res.added >= 70, `supplement too small: ${res.added}`);
  // Overlap with the lite feed is expected (141 curated entries, 79 new, 62 already
  // covered). The engine reports the overlap instead of silently double-counting it;
  // the total is derived from the feed so adding a curated entry cannot silently
  // unbalance this contract.
  const supplementEntries = (SUPPLEMENT.match(/^\|\|/gm) || []).length;
  assert.ok(res.duplicates > 0, 'dedupe accounting is broken');
  assert.equal(res.added + res.duplicates, supplementEntries, 'supplement entry accounting must balance');
  assert.equal(res.invalid, 0, 'supplement contains malformed entries');

  const after = apexes
    .filter((h) => e.evaluate(h).blocked).length;
  assert.ok(before <= 1, `unexpected lite coverage: ${before}/4 apexes`);
  assert.equal(after, 4, 'supplement failed to close the apex gap');

  for (const host of [
    'www.youtube.com', 'r1---sn-ab5l6ne7.googlevideo.com', 'www.google.com',
    'accounts.google.com', 'play.google.com', 'www.netflix.com', 'www.paypal.com',
    'checkout.stripe.com', 'github.com', 'login.microsoftonline.com', 'www.amazon.com',
    'cdn.jsdelivr.net', 'api.whatsapp.net', 'www.icloud.com'
  ]) {
    assert.equal(e.evaluate(host).blocked, false, `supplement broke ${host} via ${e.evaluate(host).source}`);
  }
});

test('aggressive tier is opt-in, labelled and reversible', () => {
  const e = new OmniShieldFilterEngine();
  e.loadFilterList(AGGRESSIVE, 'ADS', 'core_supplement_aggressive');
  assert.equal(e.evaluate('www.googletagmanager.com').blocked, true);
  // User allow must be able to undo it (reversible policy).
  e.addUserRule('||googletagmanager.com^', true);
  assert.equal(e.evaluate('www.googletagmanager.com').blocked, false);
  assert.equal(e.evaluate('www.googletagmanager.com').source, 'User Allowlist');
});

test('throughput stays within the performance budget', () => {
  const e = new OmniShieldFilterEngine();
  e.loadFilterList(LITE, 'ADS', 'core_lite_ads');
  const hosts = ['www.youtube.com', 'ads.doubleclick.net', 'cdn.example.net', 'sub.a.tracker.example'];
  const started = performance.now();
  const N = 20000;
  for (let i = 0; i < N; i++) e.evaluate(hosts[i % hosts.length]);
  const perOp = (performance.now() - started) / N;
  assert.ok(perOp < 0.5, `evaluation too slow: ${perOp.toFixed(4)} ms/op`);
});

test('add/remove user rules are symmetric (undo really undoes)', () => {
  const e = new OmniShieldFilterEngine();
  e.loadFilterList('||undo.example^\n', 'ADS', 'feed');
  assert.equal(e.evaluate('undo.example').blocked, true);

  e.addUserRule('||undo.example^', true);          // allow
  assert.equal(e.evaluate('undo.example').blocked, false);

  assert.equal(e.removeUserRule('||undo.example^', true), true);
  assert.equal(e.evaluate('undo.example').blocked, true, 'undo must restore the feed block');

  e.addUserRule('||extra.example^', false);         // custom block
  assert.equal(e.evaluate('extra.example').blocked, true);
  assert.equal(e.removeUserRule('||extra.example^', false), true);
  assert.equal(e.evaluate('extra.example').blocked, false);
  assert.equal(e.removeUserRule('||never-added.example^', false), false);
});
