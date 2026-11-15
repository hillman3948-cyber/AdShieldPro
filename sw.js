/**
 * OmniShield X — service worker (offline protection).
 *
 * Strategy
 *  - App shell + JavaScript modules + the small curated feeds: precached.
 *  - The large bundled rule list (assets/core_lite_ads.txt): stale-while-revalidate,
 *    so the first visit caches it and later visits work fully offline.
 *  - DNS-over-HTTPS requests are NEVER cached (they must always be live);
 *    they are excluded from interception entirely.
 *  - Navigations: network-first, falling back to the cached shell offline.
 *
 * Bump VERSION to invalidate caches on release.
 */
const VERSION = 'omnishield-x-v5.0.2';
const SHELL_CACHE = `${VERSION}-shell`;
const RULES_CACHE = `${VERSION}-rules`;

const SHELL_ASSETS = [
  './',
  './index.html',
  './app.js',
  './engine.js',
  './manifest.webmanifest',
  './assets/icon.svg',
  './assets/core_supplement_ads.txt',
  './assets/core_supplement_aggressive.txt',
  './ui/dom.js',
  './dns/codec.js',
  './dns/cache.js',
  './dns/client.js',
  './dns/dnssec.js',
  './dns/chain.js',
  './dns/resolvers.js',
  './guard/video-safety.js',
  './aether/index.js',
  './aether/data.js',
  './aether/domain.js',
  './aether/lexical.js',
  './aether/typosquat.js',
  './aether/reputation.js',
  './aether/classifier.js',
  './security/monitor.js',
  './security/firewall-ai.js',
  './security/feedmanager.js',
  './security/backup.js',
  './security/privacy.js',
  './security/power.js',
  './security/sha256.js',
  './security/storage.js'
];

const RULES_ASSETS = ['./assets/core_lite_ads.txt'];

self.addEventListener('install', (event) => {
  event.waitUntil((async () => {
    const shell = await caches.open(SHELL_CACHE);
    // addAll fails atomically; add individually so one missing file cannot
    // break the whole offline experience.
    await Promise.all(SHELL_ASSETS.map(async (url) => {
      try { await shell.add(new Request(url, { cache: 'reload' })); } catch { /* optional asset */ }
    }));
    const rules = await caches.open(RULES_CACHE);
    await Promise.all(RULES_ASSETS.map(async (url) => {
      try { await rules.add(new Request(url, { cache: 'reload' })); } catch { /* stays online-only */ }
    }));
    await self.skipWaiting();
  })());
});

self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    const keys = await caches.keys();
    await Promise.all(keys.filter((k) => !k.startsWith(VERSION)).map((k) => caches.delete(k)));
    await self.clients.claim();
  })());
});

self.addEventListener('fetch', (event) => {
  const { request } = event;
  if (request.method !== 'GET') return;

  const url = new URL(request.url);

  // Never touch DNS-over-HTTPS or any cross-origin API: answers must be live.
  if (url.origin !== self.location.origin) return;

  // Navigation: network-first with offline shell fallback.
  if (request.mode === 'navigate') {
    event.respondWith((async () => {
      try {
        const fresh = await fetch(request);
        const cache = await caches.open(SHELL_CACHE);
        cache.put('./index.html', fresh.clone()).catch(() => {});
        return fresh;
      } catch {
        const cache = await caches.open(SHELL_CACHE);
        return (await cache.match('./index.html')) || (await cache.match('./')) || Response.error();
      }
    })());
    return;
  }

  // Bundled rules: stale-while-revalidate so offline filtering keeps working.
  if (url.pathname.endsWith('/assets/core_lite_ads.txt') || url.pathname.endsWith('assets/core_lite_ads.txt')) {
    event.respondWith((async () => {
      const cache = await caches.open(RULES_CACHE);
      const cached = await cache.match(request, { ignoreSearch: true });
      const network = fetch(request)
        .then((response) => {
          if (response.ok) cache.put(request, response.clone()).catch(() => {});
          return response;
        })
        .catch(() => null);
      return cached || (await network) || new Response('', { status: 504, statusText: 'offline and not cached' });
    })());
    return;
  }

  // Everything else same-origin: cache-first with background refresh.
  event.respondWith((async () => {
    const cache = await caches.open(SHELL_CACHE);
    const cached = await cache.match(request, { ignoreSearch: true });
    if (cached) {
      fetch(request).then((response) => {
        if (response.ok) cache.put(request, response.clone()).catch(() => {});
      }).catch(() => {});
      return cached;
    }
    try {
      const response = await fetch(request);
      if (response.ok) cache.put(request, response.clone()).catch(() => {});
      return response;
    } catch {
      return new Response('offline', { status: 504, statusText: 'offline' });
    }
  })());
});
