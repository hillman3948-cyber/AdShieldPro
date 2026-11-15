/**
 * Unit tests — Video Safety guard and the DNS resilience upgrade.
 *
 * These lock in the two guarantees the user actually asked for:
 *   1. third-party video adverts are still blocked;
 *   2. the video itself keeps working (and a broken resolver never stalls it).
 */
import test from 'node:test';
import { strict as assert } from 'node:assert';
import { readFileSync } from 'node:fs';

import {
  classify,
  explainDanger,
  isDangerousRule,
  splitSafeRules,
  hasAdvertIntent,
  registrableDomain,
  PROTECTED_REGISTRABLE_DOMAINS
} from '../../guard/video-safety.js';
import { OmniShieldFilterEngine } from '../../engine.js';
import { ResolverPool, RESOLVERS } from '../../dns/resolvers.js';

/* ------------------------------------------------------------------ */
/* Guard: what stays playable                                          */
/* ------------------------------------------------------------------ */

test('media delivery hosts are classified as playback infrastructure', () => {
  const hosts = [
    'rr3---sn-ab5l6nr6.googlevideo.com',
    'i.ytimg.com',
    'www.youtube.com',
    'youtubei.googleapis.com',
    'ipv4-c001-mnl001-isp.1.oca.nflxvideo.net',
    'usher.ttvnw.net',
    'v16-webapp-prime.tiktok.com',
    'cdninstagram.com'
  ];
  for (const host of hosts) {
    const verdict = classify(host);
    assert.equal(verdict.protected, true, `${host} must stay playable`);
    assert.equal(verdict.adIntent, false, `${host} is not advert delivery`);
  }
});

test('third-party video advert delivery is never protected', () => {
  const adHosts = [
    'pubads.g.doubleclick.net',
    'securepubads.g.doubleclick.net',
    'static.doubleclick.net',
    's0.2mdn.net',
    'imasdk.googleapis.com',
    'pagead2.googlesyndication.com',
    'cdn.adsafeprotected.com'
  ];
  for (const host of adHosts) {
    const verdict = classify(host);
    assert.equal(verdict.protected, false, `${host} must stay blockable`);
    assert.equal(verdict.isAdvertDelivery ?? verdict.adIntent, true, `${host} is advert delivery`);
  }
});

test('an advert slot on a media domain is advert delivery, not playback', () => {
  assert.equal(classify('ads.twitch.tv').protected, false);
  assert.equal(classify('analytics.tiktok.com').protected, false);
  assert.equal(classify('preroll.youtube.com').protected, false);
});

test('a CDN node name that merely contains "adserver" stays playable', () => {
  // The whole reason substring wildcards are refused: this is a real CDN name.
  const verdict = classify('srv-adserver-3.googlevideo.com');
  assert.equal(verdict.protected, true);
  assert.equal(verdict.adIntent, false);
});

test('registrable domain handles the two-level suffix subset', () => {
  assert.equal(registrableDomain('a.b.example.com'), 'example.com');
  assert.equal(registrableDomain('video.example.com.ph'), 'example.com.ph');
  assert.equal(registrableDomain('localhost'), 'localhost');
});

test('advert intent is label-anchored, not substring-based', () => {
  assert.equal(hasAdvertIntent('ads.example.com'), true);
  assert.equal(hasAdvertIntent('pre-roll.example.com'), true);
  assert.equal(hasAdvertIntent('load.example.com'), false, '"load" must not match "ad"');
  assert.equal(hasAdvertIntent('gradient.example.com'), false);
});

/* ------------------------------------------------------------------ */
/* Guard: which rules are refused                                      */
/* ------------------------------------------------------------------ */

test('entity-level and shared-CDN rules are refused with a reason', () => {
  const cases = {
    '||googlevideo.com^': 'ENTITY_LEVEL_RULE',
    '||google.com^': 'ENTITY_LEVEL_RULE',
    '||akamaized.net^': 'SHARED_CDN_BLANKET',
    '||cloudfront.net^': 'SHARED_CDN_BLANKET',
    '*adserver*': 'SUBSTRING_WILDCARD',
    '*telemetry.*': 'SUBSTRING_WILDCARD'
  };
  for (const [rule, code] of Object.entries(cases)) {
    const danger = explainDanger(rule);
    assert.ok(danger, `${rule} must be refused`);
    assert.equal(danger.code, code, rule);
    assert.ok(danger.detail.length > 20, `${rule} needs an explanation`);
  }
});

test('targeted advert rules are still accepted', () => {
  const safe = [
    '||pubads.g.doubleclick.net^',
    '||static.doubleclick.net^',
    '||s0.2mdn.net^',
    '||doubleclick.net^',
    '||ads.xiaomi.net^',
    '||d10lpgp6xz60nq.cloudfront.net^',
    '||srv-adserver-3.googlevideo.com^',
    '*.telemetry.*'
  ];
  for (const rule of safe) {
    assert.equal(isDangerousRule(rule), false, `${rule} must load`);
  }
});

test('splitSafeRules keeps targeted rules and reports the rest', () => {
  const { kept, dropped } = splitSafeRules([
    '||doubleclick.net^', '||googlevideo.com^', '*adserver*', '||s0.2mdn.net^'
  ]);
  assert.deepEqual(kept, ['||doubleclick.net^', '||s0.2mdn.net^']);
  assert.equal(dropped.length, 2);
});

/* ------------------------------------------------------------------ */
/* Engine integration                                                  */
/* ------------------------------------------------------------------ */

function engineWithBundledLists() {
  const engine = new OmniShieldFilterEngine();
  engine.loadFilterList(readFileSync('assets/core_lite_ads.txt', 'utf8'), 'ADS', 'core_lite_ads');
  engine.loadFilterList(readFileSync('assets/core_supplement_ads.txt', 'utf8'), 'ADS', 'core_supplement_ads');
  return engine;
}

test('bundled lists block third-party video adverts', () => {
  const engine = engineWithBundledLists();
  for (const host of ['pubads.g.doubleclick.net', 'static.doubleclick.net', 's0.2mdn.net', 'ads.twitch.tv']) {
    assert.equal(engine.evaluate(host).blocked, true, `${host} must be blocked`);
  }
});

test('bundled lists never block media playback hosts', () => {
  const engine = engineWithBundledLists();
  for (const host of [
    'rr3---sn-ab5l6nr6.googlevideo.com',
    'www.youtube.com',
    'i.ytimg.com',
    'usher.ttvnw.net',
    'ipv4-c001.nflxvideo.net',
    'cdninstagram.com'
  ]) {
    assert.equal(engine.evaluate(host).blocked, false, `${host} must resolve`);
  }
});

test('the engine refuses a user rule that would break playback', () => {
  const engine = new OmniShieldFilterEngine();
  assert.equal(engine.addUserRule('||googlevideo.com^', false), false);
  assert.equal(engine.rejectedRules.length, 1);
  assert.equal(engine.rejectedRules[0].code, 'ENTITY_LEVEL_RULE');
  // An explicit allow rule is always accepted.
  assert.equal(engine.addUserRule('||googlevideo.com^', true), true);
});

test('feed content is filtered by the guard and counted, not silently dropped', () => {
  const engine = new OmniShieldFilterEngine();
  const result = engine.loadFilterList(
    ['||googlevideo.com^', '||pubads.g.doubleclick.net^', '*adserver*', '||example-ads.net^'].join('\n'),
    'ADS',
    'test-feed'
  );
  assert.equal(result.videoRejected, 2);
  assert.equal(result.added, 2);
  assert.equal(engine.evaluate('pubads.g.doubleclick.net').blocked, true);
  assert.equal(engine.evaluate('rr1.googlevideo.com').blocked, false);
});

test('the exemption never covers a specific ad/telemetry host (both defence layers)', () => {
  // The bug this locks down: exempting whole media domains unblocked 195 real
  // ad/telemetry hosts (TikTok ad services, Spotify's ad event tracker, Hulu's
  // ad views, Netflix and Zoom telemetry).
  const engine = new OmniShieldFilterEngine();
  const loaded = engine.loadFilterList(
    ['||googlevideo.com^', '||log.byteoversea.com^', '||views.hulu.com^',
      '||adeventtracker.spotify.com^', '||mcs.tiktokv.com^'].join('\n'),
    'TRACKERS',
    'test-feed'
  );

  // Layer 1: the blanket media rule is refused while loading.
  assert.equal(loaded.videoRejected, 1);
  assert.equal(engine.evaluate('rr3---sn-ab5l6nr6.googlevideo.com').blocked, false);

  // Layer 2: even if such a rule reaches the table by another path, the
  // evaluator ignores it for playback hosts but enforces host-level rules.
  engine.filterSubdomains.set('googlevideo.com', { category: 'TRACKERS', source: 'imported' });
  engine._invalidate();
  const playback = engine.evaluate('rr3---sn-ab5l6nr6.googlevideo.com');
  assert.equal(playback.blocked, false, 'a blanket media rule must not stop playback');
  assert.equal(playback.source, 'Video Safety');

  for (const host of ['log.byteoversea.com', 'views.hulu.com',
    'adeventtracker.spotify.com', 'mcs.tiktokv.com']) {
    assert.equal(engine.evaluate(host).blocked, true, `${host} is a deliberate block`);
  }
});

test('no shipped list rule for a specific ad/telemetry host is exempted', () => {
  const engine = engineWithBundledLists();
  const rules = readFileSync('assets/core_lite_ads.txt', 'utf8')
    .split('\n').map((l) => l.trim()).filter((l) => l.startsWith('||'))
    .map((l) => l.slice(2).split('^')[0].toLowerCase());

  const wronglyExempted = rules.filter((host) => {
    const verdict = engine.evaluate(host);
    return !verdict.blocked && verdict.source === 'Video Safety' && host !== verdict.matched;
  });
  assert.deepEqual(wronglyExempted, [], 'a host rule must never be exempted by the media guard');
});

test('a user block still outranks the video exemption (explicit beats implicit)', () => {
  const engine = new OmniShieldFilterEngine();
  engine.videoSafe = false; // simulate the escape hatch: no guard
  engine.loadFilterList('||googlevideo.com^\n', 'ADS', 'hand-written');
  assert.equal(engine.evaluate('rr1.googlevideo.com').blocked, true);
  assert.ok(PROTECTED_REGISTRABLE_DOMAINS.includes('googlevideo.com'));
});

/* ------------------------------------------------------------------ */
/* Chunked loading (the "site lags / freezes" fix)                     */
/* ------------------------------------------------------------------ */

test('loadFilterListAsync yields the event loop and never splits a rule', async () => {
  const text = readFileSync('assets/core_supplement_ads.txt', 'utf8');
  const engine = new OmniShieldFilterEngine();
  let yields = 0;
  const timer = setInterval(() => { yields++; }, 0);
  // Tiny chunks force many boundaries, including ones inside rule lines.
  const result = await engine.loadFilterListAsync(text, {
    category: 'ADS', source: 'chunked', chunkChars: 512
  });
  clearInterval(timer);

  assert.ok(result.added > 50, `expected the curated rules, got ${result.added}`);
  assert.ok(result.chunks > 5, 'expected several chunks');
  assert.ok(yields > 0, 'the loader must yield to the event loop');
  // No exception rule may have been mangled into a block rule.
  assert.equal(engine.evaluate('doubleclick.net').blocked, true);
  assert.equal(engine.evaluate('rr1.googlevideo.com').blocked, false);
});

test('chunked and single-pass loading produce the same verdicts', async () => {
  const text = readFileSync('assets/core_supplement_ads.txt', 'utf8');
  const sync = new OmniShieldFilterEngine();
  sync.loadFilterList(text, 'ADS', 'sync');
  const chunked = new OmniShieldFilterEngine();
  await chunked.loadFilterListAsync(text, { category: 'ADS', source: 'chunked', chunkChars: 700 });

  for (const host of ['doubleclick.net', 'pubads.g.doubleclick.net', 'example.com',
    'rr1.googlevideo.com', 'www.youtube.com', 'ads.twitch.tv']) {
    assert.equal(chunked.evaluate(host).blocked, sync.evaluate(host).blocked, host);
  }
});

/* ------------------------------------------------------------------ */
/* DNS upgrade: ECS policy and hedged failover                         */
/* ------------------------------------------------------------------ */

test('resolver registry documents its client-subnet policy', () => {
  for (const resolver of RESOLVERS) {
    assert.ok(['none', 'supported'].includes(resolver.ecs), `${resolver.id} needs an ECS policy`);
    assert.ok(typeof resolver.ecsNote === 'string' && resolver.ecsNote.length > 10,
      `${resolver.id} needs an explanation`);
  }
  assert.ok(RESOLVERS.some((r) => r.ecs === 'supported'), 'at least one ECS resolver is needed');
  const quad9 = RESOLVERS.find((r) => r.id === 'quad9-ecs');
  assert.equal(quad9.ecs, 'supported');
  assert.equal(quad9.ipv4, '9.9.9.11');
});

function fakeTransport(behaviour) {
  return {
    calls: [],
    async query(resolver, name, type) {
      this.calls.push(resolver.id);
      const b = behaviour[resolver.id] || { delay: 5 };
      if (b.hang) await new Promise(() => {});
      await new Promise((resolve) => setTimeout(resolve, b.delay ?? 5));
      if (b.fail) throw new Error(b.fail);
      return {
        transport: 'DoH-WIRE',
        message: {
          rcode: 0,
          answers: [{ name, type: 1, data: { address: '203.0.113.9' }, ttl: 60 }],
          questions: [{ name, type: 1 }],
          authorities: [],
          additionals: [],
          flags: { authenticatedData: false }
        }
      };
    }
  };
}

test('media names prefer a subnet-forwarding resolver', async () => {
  const transport = fakeTransport({});
  const pool = new ResolverPool({ transport, resolvers: RESOLVERS, raceDelayMs: 0 });
  await pool.query('usher.ttvnw.net', 'A');
  const first = RESOLVERS.find((r) => r.id === transport.calls[0]);
  assert.equal(first.ecs, 'supported', `expected an ECS resolver first, got ${first.id}`);
});

test('ordinary names keep the configured order and do not force ECS', async () => {
  const transport = fakeTransport({});
  const pool = new ResolverPool({ transport, resolvers: RESOLVERS, raceDelayMs: 0 });
  await pool.query('example.com', 'A');
  assert.equal(transport.calls.length, 1);
  assert.equal(transport.calls[0], 'cloudflare', 'health order is kept for non-media names');
});

test('a hanging resolver is hedged instead of blocking the lookup', async () => {
  const transport = fakeTransport({ cloudflare: { hang: true } });
  const pool = new ResolverPool({ transport, resolvers: RESOLVERS, raceDelayMs: 80 });
  const started = Date.now();
  const result = await pool.query('example.com', 'A');
  const elapsed = Date.now() - started;

  assert.equal(result.status, 'ok');
  assert.equal(result.raced, true);
  assert.ok(elapsed < 1000, `hedge must not wait for a timeout, took ${elapsed} ms`);
  assert.deepEqual(transport.calls.slice(0, 2), ['cloudflare', 'google']);
});

test('every resolver failing returns one clean error, not a hang', async () => {
  const behaviour = Object.fromEntries(RESOLVERS.map((r) => [r.id, { fail: 'HTTP 503' }]));
  const transport = fakeTransport(behaviour);
  const pool = new ResolverPool({ transport, resolvers: RESOLVERS, raceDelayMs: 40 });
  const result = await pool.query('example.com', 'A');
  assert.equal(result.status, 'error');
  assert.ok(result.attempts.length >= 2);
  assert.match(result.error, /503/);
});
