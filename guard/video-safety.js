/**
 * OmniShield X — Video Safety guard.
 *
 * WHY THIS EXISTS
 * ---------------
 * A DNS filter cannot see inside an HTTP response, so it cannot tell an in-stream
 * video advert apart from the video itself when both are served by the same
 * infrastructure. The HaGeZi baselines we bundle are already conservative, but
 * three kinds of rule still cause the classic "the player is fine but the video
 * never starts" report:
 *
 *   1. Entity-level rules. `||google.com^` or `||googlevideo.com^` blocks the
 *      media CDN together with every advert on it.
 *   2. Substring/wildcard rules. `*adserver*`, `*telemetry.*`, `*adtrack.*` will
 *      match a legitimate *hostname* such as `srv-adserver-3.googlevideo.com`
 *      because the match is not anchored to a real DNS label.
 *   3. Shared-CDN over-blocking. Blanket rules for generic edge/CDN zones
 *      (`akamaized.net`, `cloudfront.net`, ...) take playback down for every
 *      service that happens to sit behind the same platform, while blocking none
 *      of the third-party ad infrastructure that a targeted rule would catch.
 *
 * What this module does:
 *   - `classify(domain)` — is this host load-bearing for media playback?
 *   - `isDangerousRule(pattern)` — would this rule take playback down?
 *   - `explainDanger(pattern)` — why, in one sentence, for the UI.
 *   - `guardFilterEngine(engine)` — installs the video-safe policy on a filter
 *     engine instance (an exact exception for every protected host plus a refusal
 *     to load entity-level / substring / shared-CDN patterns).
 *
 * WHAT IT DOES NOT DO
 * -------------------
 * It does not whitelist advert hosts. `pubads.g.doubleclick.net`,
 * `static.doubleclick.net`, `s0.2mdn.net`, `securepubads.g.doubleclick.net`,
 * `imasdk.googleapis.com` and the equivalent third-party ad names on any other
 * service stay blocked: those are advert delivery, not content delivery. That is
 * the whole point — block the third-party ad infrastructure, keep the stream.
 *
 * Server-side stitched adverts (YouTube/Netflix/Twitch pre-rolls sold by the
 * platform) arrive on the same URL as the video. No DNS filter can separate them;
 * see docs/UNSUPPORTED.md.
 */

const LABEL_RE = /^[a-z0-9_-]{1,63}$/;

/** Lowest-precedence safety net: the registrable domains that carry playback. */
export const PROTECTED_REGISTRABLE_DOMAINS = Object.freeze([
  // Google / YouTube media delivery and player assets.
  'googlevideo.com',
  'youtube.com',
  'youtu.be',
  'ytimg.com',
  'ggpht.com',
  'yt3.ggpht.com',
  'youtube-nocookie.com',
  'youtubei.googleapis.com',
  'googlevideo.googleusercontent.com',
  // Netflix.
  'netflix.com',
  'nflxvideo.net',
  'nflximg.net',
  'nflxext.com',
  'nflxso.net',
  // Amazon Prime Video / IMDb TV.
  'primevideo.com',
  'amazonvideo.com',
  'aiv-cdn.net',
  'aiv-delivery.net',
  'media-amazon.com',
  // Disney+ / Hotstar / Hulu / HBO Max / Paramount+.
  'disneyplus.com',
  'dssott.com',
  'bamgrid.com',
  'hotstar.com',
  'hulu.com',
  'hbomax.com',
  'max.com',
  'paramountplus.com',
  // Twitch.
  'twitch.tv',
  'ttvnw.net',
  'jtvnw.net',
  // Meta / Instagram video.
  'fbcdn.net',
  'cdninstagram.com',
  'instagram.com',
  // TikTok.
  'tiktok.com',
  'tiktokcdn.com',
  'tiktokv.com',
  'tiktokcdn-us.com',
  'ibyteimg.com',
  'muscdn.com',
  'byteoversea.com',
  // Vimeo / Dailymotion / Rumble.
  'vimeo.com',
  'vimeocdn.com',
  'dailymotion.com',
  'dmcdn.net',
  'rumble.com',
  // Music playback (a broken stream is the same complaint).
  'spotify.com',
  'scdn.co',
  'soundcloud.com',
  'sndcdn.com',
  'deezer.com',
  'dzcdn.net',
  // Live / conferencing media.
  'zoom.us',
  'meet.google.com',
  'teams.microsoft.com',
  'teams.live.com',
  'webex.com'
]);

/**
 * Hosts that are not on a protected registrable domain but are still required
 * for the player to start. `imasdk.googleapis.com` is deliberately absent: it is
 * Google's ad SDK, so blocking it is the intended behaviour.
 */
export const PROTECTED_HOSTS = Object.freeze([
  'youtubei.googleapis.com',
  'play.googleapis.com',
  'redirector.googlevideo.com'
]);

/**
 * Generic edge/CDN zones. Blanket blocking one of these removes playback (and
 * every download) for unrelated services; the registrable domain is almost never
 * the right granularity for a CDN. A host-level rule inside the zone is fine.
 */
export const SHARED_CDN_ZONES = Object.freeze([
  'akamaized.net',
  'akamaihd.net',
  'akamai.net',
  'cloudfront.net',
  'fastly.net',
  'fastlylb.net',
  'edgekey.net',
  'edgesuite.net',
  'llnwd.net',
  'azureedge.net',
  'b-cdn.net',
  'kwcdn.com',
  'cdngslb.com',
  'gcdn.net',
  'w.cdngslb.com',
  'cloudflare.net'
]);

/**
 * Entity-level domains. A rule for the whole entity is never worth the breakage:
 * if an advert needs blocking, the specific host delivering it is the rule.
 */
export const ENTITY_LEVEL_DOMAINS = Object.freeze([
  'google.com',
  'googleapis.com',
  'gstatic.com',
  'googleusercontent.com',
  'youtube.com',
  'googlevideo.com',
  'ytimg.com',
  'ggpht.com',
  'facebook.com',
  'fbcdn.net',
  'instagram.com',
  'whatsapp.net',
  'messenger.com',
  'microsoft.com',
  'live.com',
  'office.com',
  'office.net',
  'sharepoint.com',
  'microsoftonline.com',
  'windows.com',
  'apple.com',
  'icloud.com',
  'amazon.com',
  'amazonaws.com',
  'cloudfront.net',
  'akamaized.net',
  'akamaihd.net',
  'fastly.net',
  'netflix.com',
  'tiktok.com',
  'twitch.tv',
  'vimeo.com',
  'dailymotion.com',
  'spotify.com',
  'soundcloud.com',
  'twitter.com',
  'x.com',
  'linkedin.com',
  'reddit.com',
  'snapchat.com',
  'telegram.org',
  'paypal.com',
  'stripe.com',
  'shopify.com',
  'github.com',
  'githubusercontent.com'
]);

/**
 * Labels that make a host the *player*, never an advert slot. `srv-adserver-3`
 * is not in this list, so a substring rule still cannot hide behind it: the
 * anchor check in `isDangerousRule` runs before this list is consulted.
 */
const PLAYER_LABEL_TOKENS = Object.freeze([
  'video', 'vod', 'media', 'stream', 'streaming', 'player', 'playback',
  'manifest', 'm3u8', 'mpd', 'segment', 'segments', 'hls', 'dash', 'live',
  'drm', 'widevine', 'playready', 'fairplay', 'license',
  'manifest-googlevideo', 'redirector', 'content', 'cdn'
]);

/** Advert-intent tokens. Any host carrying one of these is ad delivery. */
const ADVERT_LABEL_TOKENS = Object.freeze([
  'ad', 'ads', 'adserver', 'adservice', 'advert', 'advertising', 'adv',
  'doubleclick', 'imasdk', 'prebid', 'vast', 'vmap', 'vastads', 'adunit',
  'beacon', 'analytics', 'telemetry', 'tracking', 'tracker', 'metrics',
  'pixel', 'sponsor', 'sponsored', 'sponsorads', 'promo', 'banner', 'preroll',
  'midroll', 'postroll', 'interstitial',
  // Telemetry/log collectors published as subdomains of a media domain. These
  // are the names an upstream list deliberately blocks (views.hulu.com,
  // log.byteoversea.com, mcs.tiktokv.com, smetrics.disneyplus.com, ...).
  'log', 'logs', 'log2', 'logger', 'logging', 'gplog', 'maplog', 'eventlog',
  'eventlogger', 'eventgateway', 'events', 'mon', 'monitoring', 'mcs', 'tsa',
  'smetrics', 'perfmetrics', 'customerevents', 'telemetry2', 'pulse', 'tags',
  'promoted', 'adeventtracker', 'aet', 'views', 'dradis', 'stats'
]);

/** Hostname labels that describe the player itself rather than an advert slot. */
function hasPlayerIntent(labels) {
  return labels.some((label) => PLAYER_LABEL_TOKENS.some((token) => label.includes(token)));
}

/** True when the hostname carries an advert-intent label (`ad`, `ads`, `preroll`, ...). */
export function hasAdvertIntent(domain) {
  const normalized = normalizeDomain(domain);
  if (!normalized) return false;
  const labels = normalized.split('.');
  return labels.some((label) => {
    if (ADVERT_LABEL_TOKENS.includes(label)) return true;
    // `ads-server`, `vast-ads` style compound labels.
    if (label.split(/[-_]/).some((part) => ADVERT_LABEL_TOKENS.includes(part))) return true;
    // `pre-roll`, `mid_roll` style: separators are not significant in a hostname.
    return ADVERT_LABEL_TOKENS.includes(label.replace(/[-_]/g, ''));
  });
}

/** Bare lowercase hostname, or '' when the input cannot be a hostname. */
export function normalizeDomain(input) {
  let s = String(input ?? '').trim().toLowerCase();
  s = s.replace(/^[a-z][a-z0-9+.-]*:\/\//, '');
  s = s.replace(/^\/\//, '');
  s = s.replace(/^[^/@]*@/, '');
  s = s.split('/')[0].split('?')[0].split('#')[0].split(':')[0];
  s = s.replace(/^\.+/, '').replace(/\.+$/, '');
  if (!s || s.length > 253) return '';
  if (!/^[a-z0-9.-]+$/.test(s)) return '';
  const labels = s.split('.');
  if (labels.some((label) => label.length === 0 || label.length > 63 || !LABEL_RE.test(label))) return '';
  return s;
}

/** Registrable domain using the same focused public-suffix subset as aether/domain.js. */
const TWO_LEVEL_SUFFIXES = new Set([
  'co', 'com', 'net', 'org', 'gov', 'edu', 'ac', 'or', 'ne', 'go', 'gob',
  'nic', 'res', 'sch', 'mil', 'info', 'biz'
]);

export function registrableDomain(input) {
  const name = normalizeDomain(input);
  if (!name) return '';
  const labels = name.split('.');
  if (labels.length <= 2) return name;
  const last = labels[labels.length - 1];
  const penultimate = labels[labels.length - 2];
  if (last.length === 2 && TWO_LEVEL_SUFFIXES.has(penultimate)) {
    return labels.slice(-3).join('.');
  }
  return labels.slice(-2).join('.');
}

/**
 * The protected media zone a host belongs to (`googlevideo.com`), or null.
 *
 * Exported because the evaluator needs it: the Video Safety exemption may only
 * apply when the *blocking rule is the media domain itself* (a blanket rule that
 * would take playback down). A rule for a specific host under the zone
 * (`log.byteoversea.com`, `views.hulu.com`) is a deliberate block of an ad or
 * telemetry endpoint and must survive.
 */
export function protectedZone(domain) {
  const name = normalizeDomain(domain);
  if (!name) return null;
  for (const entry of PROTECTED_REGISTRABLE_DOMAINS) {
    if (name === entry || name.endsWith(`.${entry}`)) return entry;
  }
  return null;
}

const onProtectedRegistrable = protectedZone;

/**
 * True when an advert/telemetry token appears in the *subdomain* of a media
 * domain — `ads.twitch.tv`, `analytics.tiktok.com`, `log.byteoversea.com`,
 * `views.hulu.com`, `player-telemetry.vimeo.com`.
 *
 * These are the names upstream lists deliberately block, and the exemption must
 * never cover them: exempting an entire media registrable domain silently
 * unblocked 195 ad and telemetry hosts (TikTok ad services, Spotify's ad event
 * tracker, Hulu's ad views, Netflix telemetry).
 *
 * Compound delivery node names such as `srv-adserver-3.googlevideo.com` are the
 * exception: the token must form a whole label, or the first segment of a
 * hyphenated label (`ads-service`, `player-telemetry`), not appear mid-word.
 */
function hasAdvertSubdomainLabel(domain, protectedEntry) {
  if (!protectedEntry) return false;
  const suffix = `.${protectedEntry}`;
  if (!domain.endsWith(suffix)) return false;
  const subLabels = domain.slice(0, -suffix.length).split('.');
  return subLabels.some((label) => {
    if (ADVERT_LABEL_TOKENS.includes(label)) return true;
    if (ADVERT_LABEL_TOKENS.includes(label.replace(/[-_]/g, ''))) return true;
    const head = label.split(/[-_]/)[0];
    // `ads-service`, `log-gateway`, `player-telemetry`: lead token only, so a
    // per-node name like `srv-adserver-3` is not caught by its middle segment.
    return ADVERT_LABEL_TOKENS.includes(head) && label.includes('-');
  });
}

/** The protected verdict, shared by the registrable-domain and explicit-host cases. */
function protectedBranch(domain, labels, protectedEntry, directHost) {
  if (directHost) {
    return {
      domain,
      protected: true,
      adIntent: false,
      reason: `Required for the player to resolve (${directHost}).`,
      blockedBy: null,
      matched: directHost
    };
  }
  return {
    domain,
    protected: true,
    adIntent: false,
    reason: hasPlayerIntent(labels)
      ? `Media playback host on ${protectedEntry} (player/stream naming).`
      : `Media delivery domain (${protectedEntry}). A rule for this name removes the stream, not the advert.`,
    blockedBy: null,
    matched: protectedEntry
  };
}

/**
 * Classifies a hostname for media safety.
 *
 * @param {string} input hostname or URL
 * @returns {{domain:string, protected:boolean, reason:string, adIntent:boolean,
 *            blockedBy:null|string, matched:string|null}}
 */
export function classify(input) {
  const domain = normalizeDomain(input);
  if (!domain) {
    return { domain: '', protected: false, reason: 'Input is not a hostname.', adIntent: false, blockedBy: null, matched: null };
  }

  const adIntent = hasAdvertIntent(domain);
  const labels = domain.split('.');
  const protectedEntry = onProtectedRegistrable(domain);
  const directHost = PROTECTED_HOSTS.find((host) => domain === host || domain.endsWith(`.${host}`)) || null;

  // 1. Third-party advert slots are identified by name, not by heuristic. Only
  //    these explicit hosts override the media guard — they are advert delivery,
  //    and blocking them is what removes a video ad without removing the video.
  if (isAdvertDeliveryZone(domain)) {
    return {
      domain,
      protected: false,
      adIntent: true,
      reason: 'Advert delivery host: blocking it removes a video ad, not the video.',
      blockedBy: 'advert-intent',
      matched: null
    };
  }

  // 2. An advert slot published as a subdomain of a media domain (`ads.twitch.tv`)
  //    is still advert delivery, not playback infrastructure.
  if (hasAdvertSubdomainLabel(domain, protectedEntry)) {
    return {
      domain,
      protected: false,
      adIntent: true,
      reason: `Advert slot on ${protectedEntry}: blocking it removes the ad request, not the stream.`,
      blockedBy: 'advert-subdomain',
      matched: null
    };
  }

  // 2b. An advert/telemetry subdomain of a media domain is advert delivery, not
  //     playback. This is the case that must not be exempted wholesale.
  if (hasAdvertSubdomainLabel(domain, protectedEntry)) {
    return {
      domain,
      protected: false,
      adIntent: true,
      reason: `Advert or telemetry slot on ${protectedEntry}: blocking it removes tracking, not the stream.`,
      blockedBy: 'advert-subdomain',
      matched: null
    };
  }

  // 3. Any other name on a media delivery domain is playback infrastructure.
  if (protectedEntry || directHost) {
    return protectedBranch(domain, labels, protectedEntry, directHost);
  }

  // 4. Everything else with advert intent is ad infrastructure that is not
  //    load-bearing for media; classifying it as advert delivery keeps the
  //    dashboard honest about what a block costs.
  if (adIntent) {
    return {
      domain,
      protected: false,
      adIntent: true,
      reason: 'Advert or measurement host: blocking it does not affect media playback.',
      blockedBy: 'advert-intent',
      matched: null
    };
  }

  return {
    domain,
    protected: false,
    adIntent: false,
    reason: 'Not a protected media host.',
    blockedBy: null,
    matched: null
  };
}

/** Third-party ad slots that happen to live on a media domain's registrable name. */
const AD_DELIVERY_HOSTS = Object.freeze([
  'pubads.g.doubleclick.net',
  'securepubads.g.doubleclick.net',
  'static.doubleclick.net',
  'stats.g.doubleclick.net',
  'ad.doubleclick.net',
  's0.2mdn.net',
  's1.2mdn.net',
  'imasdk.googleapis.com',
  'googleads.g.doubleclick.net',
  'tpc.googlesyndication.com',
  'pagead2.googlesyndication.com',
  'secure.insightexpressai.com',
  'display.ravm.tv',
  'cdn.adsafeprotected.com',
  'static.ads-twitter.com'
]);

function isAdvertDeliveryZone(domain) {
  return AD_DELIVERY_HOSTS.some((host) => domain === host || domain.endsWith(`.${host}`));
}

/** Strips adblock syntax down to the bare pattern the engine would compile. */
export function patternOf(rule) {
  let body = String(rule ?? '').trim();
  if (!body || body.startsWith('!') || body.startsWith('#')) return '';
  if (body.startsWith('@@')) body = body.slice(2).trim();
  if (body.startsWith('||')) body = body.slice(2);
  else if (body.startsWith('|')) body = body.slice(1);
  const caret = body.indexOf('^');
  if (caret >= 0) body = body.slice(0, caret);
  return body.split('$')[0].trim().toLowerCase();
}

/**
 * Would loading this rule take video playback down?
 *
 * @param {string} rule adblock-style line (`||example.com^`, `*adserver*`, plain host)
 * @returns {boolean}
 */
export function isDangerousRule(rule) {
  return explainDanger(rule) !== null;
}

/**
 * Explains why a rule is video-unsafe, or returns null when the rule is fine.
 *
 * @param {string} rule
 * @returns {null|{code:string, pattern:string, detail:string}}
 */
export function explainDanger(rule) {
  const pattern = patternOf(rule);
  if (!pattern) return null;

  // 1. Substring wildcards. `*adserver*` matches any hostname *containing* the
  //    substring, which includes legitimate per-server CDN names.
  if (pattern.includes('*')) {
    const literal = pattern.replace(/\*/g, '').replace(/^\*|\*$/g, '');
    if (literal.length < 4) {
      return {
        code: 'WILDCARD_TOO_BROAD',
        pattern,
        detail: `Wildcard "${pattern}" matches almost every host and will break media playback.`
      };
    }
    // A wildcard is only safe when the remaining literal text is a real label
    // sequence anchored to a domain (`ads*.example.com` is fine, `*ads*` is not).
    const anchored = pattern.startsWith('*.') || pattern.startsWith('*');
    const suffix = pattern.split('*').pop() || '';
    const suffixLooksLikeDomain = /^[a-z0-9-]+(\.[a-z0-9-]+)+$/.test(suffix.replace(/^\./, ''));
    if (anchored && !pattern.startsWith('*.') && !suffixLooksLikeDomain) {
      return {
        code: 'SUBSTRING_WILDCARD',
        pattern,
        detail: `Wildcard "${pattern}" matches a substring of a hostname, not a DNS label, so it also matches legitimate player and CDN hosts.`
      };
    }
    const literalDomain = registrableDomain(literal);
    if (literalDomain && PROTECTED_REGISTRABLE_DOMAINS.some((z) => literalDomain === z || literalDomain.endsWith(`.${z}`))) {
      return {
        code: 'WILDCARD_ON_MEDIA_DOMAIN',
        pattern,
        detail: `Wildcard "${pattern}" applies to a media delivery domain (${literalDomain}).`
      };
    }
    return null;
  }

  const domain = normalizeDomain(pattern);
  if (!domain) return null;

  // 2. A blanket rule for a shared edge/CDN zone. Checked before the entity list
  //    because the CDN reason is the more precise explanation for those names.
  if (SHARED_CDN_ZONES.includes(domain)) {
    return {
      code: 'SHARED_CDN_BLANKET',
      pattern: domain,
      detail: `"${domain}" is a shared CDN zone. Blocking the whole zone breaks playback for unrelated services; block the specific host inside the zone instead.`
    };
  }

  // 3. Entity-level rules. Anything broader than the actual ad host.
  if (ENTITY_LEVEL_DOMAINS.includes(domain)) {
    return {
      code: 'ENTITY_LEVEL_RULE',
      pattern: domain,
      detail: `"${domain}" is an entity-level domain: blocking it removes logins, updates and media delivery along with any advert. Block the specific ad host instead.`
    };
  }

  return null;
}

/**
 * Filters a list of rules down to the video-safe subset.
 *
 * @param {string[]} rules
 * @returns {{kept:string[], dropped:Array<{rule:string, code:string, detail:string}>}}
 */
export function splitSafeRules(rules) {
  const kept = [];
  const dropped = [];
  for (const rule of rules ?? []) {
    const danger = explainDanger(rule);
    if (danger) dropped.push({ rule, code: danger.code, detail: danger.detail });
    else kept.push(rule);
  }
  return { kept, dropped };
}

/**
 * Installs the video-safe policy on an OmniShieldFilterEngine-shaped object.
 *
 * The engine only needs three capabilities: `addUserRule(pattern, isAllow)`,
 * `customAllowSuffix` (a Set) and `customAllowExact` (a Set). Anything else is
 * left untouched, so the guard also works with the Android engine port.
 *
 * @param {object} engine
 * @param {{onDrop?:(entry:{rule:string,code:string,detail:string})=>void}} [options]
 * @returns {{exceptions:number, dropped:Array}}
 */
export function guardFilterEngine(engine, options = {}) {
  const dropped = [];
  const record = (entry) => {
    dropped.push(entry);
    if (options.onDrop) {
      try { options.onDrop(entry); } catch { /* observers must not break the guard */ }
    }
  };

  for (const entry of PROTECTED_REGISTRABLE_DOMAINS) {
    try { engine.addUserRule(entry, true); } catch { /* engine refused: keep going */ }
  }
  for (const host of PROTECTED_HOSTS) {
    try { engine.addUserRule(host, true); } catch { /* ignore */ }
  }

  // Refuse later user rules that would break playback, with a reason attached.
  const originalAdd = engine.addUserRule;
  if (typeof originalAdd === 'function' && !engine.__videoGuardInstalled) {
    engine.addUserRule = function guardedAddUserRule(pattern, isAllow) {
      const danger = explainDanger(pattern);
      if (danger && !isAllow) {
        record({ rule: pattern, code: danger.code, detail: danger.detail });
        return false;
      }
      return originalAdd.call(this, pattern, isAllow);
    };
    engine.__videoGuardInstalled = true;
  }

  const exceptionCount = (engine.customAllowSuffix?.size ?? 0) + (engine.customAllowExact?.size ?? 0);
  return { exceptions: exceptionCount, dropped };
}

export default {
  PROTECTED_REGISTRABLE_DOMAINS,
  PROTECTED_HOSTS,
  SHARED_CDN_ZONES,
  ENTITY_LEVEL_DOMAINS,
  classify,
  explainDanger,
  isDangerousRule,
  splitSafeRules,
  guardFilterEngine,
  hasAdvertIntent,
  protectedZone,
  registrableDomain,
  normalizeDomain,
  patternOf
};
