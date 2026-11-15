/**
 * OmniShield X — application controller (AETHER AI Evolution, v5).
 *
 * Everything here runs in the browser with no backend. Modules:
 *   engine.js                  deterministic filter ladder (the only thing that blocks)
 *   dns/                       DoH/DoT transports, DNSSEC assessment, cache, pipeline
 *   aether/                    reputation scoring, lexical model, typosquat detection
 *   security/                  firewall AI, monitor, feeds, backup, privacy analytics
 *   ui/dom.js                  safe DOM helpers (textContent only — no innerHTML)
 */
import { OmniShieldFilterEngine } from './engine.js';
import { DnsClient } from './dns/client.js';
import { RESOLVERS, DotTransport } from './dns/resolvers.js';
import { auditChain } from './dns/chain.js';
import { classify as classifyVideoHost } from './guard/video-safety.js';
import { Aether } from './aether/index.js';
import { DomainClassifier } from './aether/classifier.js';
import { SIGNAL_WEIGHTS, EVIDENCE_FLOORS } from './aether/reputation.js';
import { SecurityMonitor } from './security/monitor.js';
import { FirewallAI, DEFAULT_CONFIG as FW_CONFIG } from './security/firewall-ai.js';
import { FeedManager, FEED_REGISTRY } from './security/feedmanager.js';
import { BackupManager, SCHEMA_VERSION } from './security/backup.js';
import { PrivateAnalytics } from './security/privacy.js';
import { PowerGovernor } from './security/power.js';
import { createStorage } from './security/storage.js';
import * as ui from './ui/dom.js';
import { h, mount, pill, note, empty, kpi, table, kv, number, plural, bytes, relativeTime, drawSeries, toast, download, setBusy } from './ui/dom.js';

const BUNDLED = {
  lite: { file: 'assets/core_lite_ads.txt', category: 'ADS', source: 'core_lite_ads', label: 'HaGeZi Multi LIGHT (bundled)' },
  supplement: { file: 'assets/core_supplement_ads.txt', category: 'ADS', source: 'core_supplement_ads', label: 'Curated apex supplement (bundled)' },
  aggressive: { file: 'assets/core_supplement_aggressive.txt', category: 'ADS', source: 'core_supplement_aggressive', label: 'Aggressive tier (optional, disabled)' }
};

const state = {
  storage: null,
  engine: null,
  aether: null,
  client: null,
  monitor: null,
  firewall: null,
  feeds: null,
  backups: null,
  analytics: null,
  settings: { aggressiveTier: false, autoUpdateFeeds: true, monitorRefreshMs: 1000 },
  lastBenchmark: null,
  benchmarkRunning: false,
  power: null,
  powerInfo: null,
  customRules: [],
  importedBackup: null,
  booted: false,
  training: false,
  trainingError: null,
  bundled: {},
  snapshotTimer: null,
  lastSnapshotId: null,
  currentPage: 'dashboard'
};

/* ------------------------------------------------------------------ */
/* Boot                                                                */
/* ------------------------------------------------------------------ */

function setBootStatus(text, cls = 'info') {
  const el = document.getElementById('boot-status');
  if (!el) return;
  ui.clear(el);
  el.className = `pill ${cls}`;
  el.appendChild(h('span', { class: 'dot' }));
  el.appendChild(document.createTextNode(` ${text}`));
}

async function fetchText(url) {
  const response = await fetch(url, { cache: 'no-cache' });
  if (!response.ok) throw new Error(`HTTP ${response.status} for ${url}`);
  return response.text();
}

async function boot() {
  try {
    state.storage = createStorage('omnishield-x');
    state.monitor = new SecurityMonitor();
    state.analytics = new PrivateAnalytics({ storage: state.storage });
    await state.analytics.load();
    state.feeds = new FeedManager({ storage: state.storage, onEvent: (e) => { if (e.ok === false) console.debug('[feeds]', e.reason, e.detail); } });
    state.backups = new BackupManager({ storage: state.storage, maxSnapshots: 4 });

    setBootStatus('loading rule sets…', 'info');
    state.engine = new OmniShieldFilterEngine();
    await loadBundledRules();

    state.aether = new Aether({ engine: state.engine });
    state.client = new DnsClient({ engine: state.engine, onObservation: handleObservation });
    state.firewall = new FirewallAI({ onEvent: (event) => {
      if (event.decision === 'block') toast(`Firewall AI blocked ${event.domain}: ${event.reasons[0] || ''}`, 'warn');
    } });

    await restoreConfiguration();
    applyUserRules();
    await loadCachedModel();

    state.booted = true;
    setBootStatus(`ready · ${number(state.engine.totalRules())} rules`, 'ok');
    updateNetworkStatus();
    renderAll();

    // Train the lexical model after first paint so the UI never blocks.
    setTimeout(() => { ensureModel().catch((err) => { state.trainingError = err.message; }); }, 300);
    registerServiceWorker();
    // The governor starts the monitor loop itself, with the interval it derives
    // from device power state — calling startMonitorLoop() separately here would
    // briefly install an untrottled timer and then replace it.
    startPowerGovernor();
    updateOnlineStatus();
    window.addEventListener('online', updateOnlineStatus);
    window.addEventListener('offline', updateOnlineStatus);
    window.addEventListener('pagehide', () => { saveSnapshot('pagehide').catch(() => {}); });
  } catch (err) {
    console.error(err);
    setBootStatus('boot failed', 'bad');
    mount('#dash-kpis', note(`Startup failed: ${err.message}. If you opened this file directly (file://), run it over HTTP (e.g. "npm run serve") — browsers block module and asset loading from file://.`, 'bad'));
  }
}

/**
 * Loads the bundled rule sets without blocking the first paint.
 *
 * `loadFilterList` on the 80k-line list is ~0.8 s of straight-line work. Doing that
 * synchronously made the tab look frozen (and on slower phones tripped the
 * "page unresponsive" prompt). The chunked loader yields between chunks and
 * reports progress, so the boot status keeps updating instead of the UI dying.
 */
async function loadBundledRules() {
  for (const key of ['lite', 'supplement']) {
    const descriptor = BUNDLED[key];
    try {
      const text = await fetchText(descriptor.file);
      const result = await state.engine.loadFilterListAsync(text, {
        category: descriptor.category,
        source: descriptor.source,
        onProgress: ({ fraction }) => {
          if (key === 'lite') setBootStatus(`loading rule sets… ${Math.round(fraction * 100)}%`, 'info');
        }
      });
      state.bundled[descriptor.source] = { ...result, label: descriptor.label, bytes: text.length };
    } catch (err) {
      state.bundled[descriptor.source] = { error: err.message, label: descriptor.label };
    }
  }
  if (state.settings.aggressiveTier) await enableAggressiveTier(true);
}

async function enableAggressiveTier(enable) {
  const descriptor = BUNDLED.aggressive;
  if (enable) {
    try {
      const text = await fetchText(descriptor.file);
      state.engine.loadFilterList(text, descriptor.category, descriptor.source);
      state.settings.aggressiveTier = true;
    } catch (err) {
      toast(`Could not load the aggressive tier: ${err.message}`, 'bad');
      return false;
    }
  } else {
    // Rebuild the engine without the aggressive source.
    state.settings.aggressiveTier = false;
    await rebuildEngine();
  }
  scheduleSnapshot('aggressive-tier');
  renderAll();
  return true;
}

async function rebuildEngine() {
  const engine = new OmniShieldFilterEngine();
  for (const key of ['lite', 'supplement']) {
    try {
      const text = await fetchText(BUNDLED[key].file);
      await engine.loadFilterListAsync(text, {
        category: BUNDLED[key].category,
        source: BUNDLED[key].source
      });
    } catch { /* keep going with whatever loads */ }
  }
  if (state.settings.aggressiveTier) {
    try { engine.loadFilterList(await fetchText(BUNDLED.aggressive.file), 'ADS', BUNDLED.aggressive.source); } catch { /* ignore */ }
  }
  state.customRules.forEach((rule) => engine.addUserRule(rule.pattern, rule.isAllow));
  state.engine = engine;
  state.aether.attachEngine(engine);
  state.client.setEngine(engine);
}

function handleObservation(event) {
  const domain = event.name;
  if (!domain) return;
  if (event.kind === 'blocked') {
    const category = event.blockedBy?.category || 'UNCATEGORISED';
    state.monitor.record({ kind: 'blocked', domain, category, latencyMs: event.latencyMs, at: event.at });
    state.analytics.record({ kind: 'blocked', category, domain, at: event.at });
    state.aether.observe(domain, { blocked: true, at: event.at });
    state.firewall.observe({ domain, app: 'browser', at: event.at });
    return;
  }
  state.monitor.record({ kind: 'lookup', domain, status: event.status, latencyMs: event.latencyMs, at: event.at });
  state.analytics.record({ kind: event.ok ? 'allowed' : 'error', category: 'DNS', domain, at: event.at });
  if (event.ok) {
    state.aether.observe(domain, { blocked: false, at: event.at });
    state.firewall.observe({ domain, app: 'browser', at: event.at });
  }
}

/* ------------------------------------------------------------------ */
/* Configuration persistence                                           */
/* ------------------------------------------------------------------ */

function buildState({ includeModel = false } = {}) {
  const aetherState = state.aether ? state.aether.exportState() : null;
  if (aetherState) {
    if (!includeModel) delete aetherState.classifier;
    aetherState.observations = (aetherState.observations || []).slice(-500);
  }
  return {
    schemaVersion: SCHEMA_VERSION,
    appVersion: '5.0.2',
    savedAt: new Date().toISOString(),
    settings: { ...state.settings },
    customRules: state.customRules.map((r) => ({ pattern: r.pattern, isAllow: r.isAllow, rawPattern: r.pattern, ruleType: r.isAllow ? 'ALLOW' : 'BLOCK', matchType: 'SUFFIX', isEnabled: true })),
    aether: aetherState,
    firewall: state.firewall ? { policies: state.firewall.snapshot().policies, config: state.firewall.config } : null,
    // Android backup fields are preserved verbatim when a backup was imported.
    protectionMode: state.importedBackup?.protectionMode ?? 'BALANCED',
    browserSiteExceptions: state.importedBackup?.browserSiteExceptions ?? [],
    excludedAppPackages: state.importedBackup?.excludedAppPackages ?? []
  };
}

function applyUserRules() {
  for (const rule of state.customRules) state.engine.addUserRule(rule.pattern, rule.isAllow);
}

async function restoreConfiguration() {
  const recovery = await state.backups.recover();
  if (recovery.ok) {
    const saved = recovery.state || {};
    if (saved.settings) state.settings = { ...state.settings, ...saved.settings };
    if (Array.isArray(saved.customRules)) {
      state.customRules = saved.customRules
        .map((r) => ({
          pattern: String(r.pattern ?? r.rawPattern ?? ''),
          isAllow: r.isAllow ?? r.ruleType === 'ALLOW'
        }))
        .filter((r) => r.pattern);
    }
    if (saved.aether && state.aether) state.aether.importState(saved.aether);
    if (saved.firewall?.policies && state.firewall) {
      for (const policy of saved.firewall.policies) {
        for (const d of policy.deny || []) state.firewall.setPolicy(policy.app, d, 'block');
        for (const d of policy.allow || []) state.firewall.setPolicy(policy.app, d, 'allow');
      }
    }
    state.lastSnapshotId = recovery.id;
    if (recovery.skipped.length) {
      toast(`Recovered configuration ${recovery.id}, skipped ${recovery.skipped.length} corrupt snapshot(s).`, 'warn');
    }
  } else if (recovery.reason !== 'no-snapshots') {
    toast(`Configuration recovery: ${recovery.reason}. Starting from safe defaults.`, 'warn');
  }
}

function scheduleSnapshot(reason = 'change') {
  clearTimeout(state.snapshotTimer);
  state.snapshotTimer = setTimeout(() => saveSnapshot(reason).catch(() => {}), 1200);
}

async function saveSnapshot(reason = 'manual', { silent = true } = {}) {
  if (!state.booted && reason !== 'manual') return null;
  try {
    const saved = await state.backups.save(buildState(), { reason });
    state.lastSnapshotId = saved.id;
    if (!silent) toast('Configuration snapshot saved.', 'ok');
    renderSnapshots();
    return saved;
  } catch (err) {
    // Quota problems are common: prune the oldest snapshot and try once more.
    try {
      const list = await state.backups.list();
      if (list.length > 1) {
        await state.backups.remove(list[list.length - 1].id);
        const retry = await state.backups.save(buildState(), { reason });
        state.lastSnapshotId = retry.id;
        if (!silent) toast('Saved after pruning the oldest snapshot (storage was full).', 'warn');
        renderSnapshots();
        return retry;
      }
    } catch { /* fall through */ }
    if (!silent) toast(`Snapshot failed: ${err.message}`, 'bad');
    return null;
  }
}

async function loadCachedModel() {
  try {
    const raw = await state.storage.getItem('aether:model');
    if (!raw) return false;
    const classifier = DomainClassifier.fromJSON(JSON.parse(raw));
    if (!classifier) return false;
    setClassifier(classifier);
    return true;
  } catch { return false; }
}

function setClassifier(classifier) {
  state.aether.classifier = classifier;
  state.aether.reputation.setClassifier(classifier);
}

async function ensureModel(force = false) {
  if (state.training) return;
  if (!force && state.aether.classifier.isTrained) return;
  state.training = true;
  renderAetherModel();
  try {
    await new Promise((resolve) => setTimeout(resolve, 20));
    const metrics = state.aether.train();
    try {
      await state.storage.setItem('aether:model', JSON.stringify(state.aether.classifier.toJSON()));
    } catch { toast('Model trained but could not be cached (storage full). It will retrain next visit.', 'warn'); }
    state.trainingError = null;
    toast(`AETHER model trained: AUC ${metrics.auc}, precision ${metrics.atThreshold.precision} at the operating point.`, 'ok');
  } catch (err) {
    state.trainingError = err.message;
    toast(`Model training failed: ${err.message}`, 'bad');
  } finally {
    state.training = false;
    renderAetherModel();
    renderDashboard();
  }
}

/* ------------------------------------------------------------------ */
/* Router                                                              */
/* ------------------------------------------------------------------ */

const PAGE_RENDERERS = {
  dashboard: renderDashboard,
  aether: renderAether,
  dns: renderDns,
  firewall: renderFirewall,
  monitor: renderMonitor,
  feeds: renderFeeds,
  privacy: renderPrivacy,
  sync: renderSync,
  extension: renderExtension,
  status: renderStatus
};

function navigate(page) {
  const focusOnNavigate = state.booted && state.currentPage !== page;
  state.currentPage = page;
  for (const section of document.querySelectorAll('.page')) section.hidden = section.id !== `page-${page}`;
  for (const button of document.querySelectorAll('nav.tabs button')) {
    const active = button.dataset.page === page;
    button.classList.toggle('active', active);
    // Screen readers need to know which section is showing; the CSS class alone
    // conveys nothing to assistive technology.
    if (active) button.setAttribute('aria-current', 'page');
    else button.removeAttribute('aria-current');
  }
  const section = document.getElementById(`page-${page}`);
  if (section) {
    section.setAttribute('aria-current', 'true');
    // Move focus to the new page so keyboard and screen-reader users are not
    // left behind on the tab they just pressed.
    if (focusOnNavigate) {
      try { section.focus({ preventScroll: true }); } catch { section.focus(); }
    }
  }
  for (const other of document.querySelectorAll('.page')) {
    if (other !== section) other.removeAttribute('aria-current');
  }
  try {
    // Renderers may be async (they read shipped artifacts); a rejection must be
    // reported, never left as an unhandled promise.
    Promise.resolve(PAGE_RENDERERS[page]?.()).catch((err) => {
      console.error(err);
      toast(`Render error on ${page}: ${err.message}`, 'bad');
    });
  } catch (err) { console.error(err); toast(`Render error on ${page}: ${err.message}`, 'bad'); }
  if (location.hash !== `#${page}`) history.replaceState(null, '', `#${page}`);
}

function renderAll() {
  for (const render of Object.values(PAGE_RENDERERS)) {
    try { Promise.resolve(render()).catch((err) => console.error('render failed', err)); } catch (err) { console.error('render failed', err); }
  }
}

document.querySelectorAll('nav.tabs button').forEach((button) => {
  button.addEventListener('click', () => navigate(button.dataset.page));
});

/* ------------------------------------------------------------------ */
/* Dashboard                                                           */
/* ------------------------------------------------------------------ */

function renderDashboard() {
  if (!state.engine) return;
  const breakdown = state.engine.breakdown();
  const metrics = state.monitor.metrics();
  const clf = state.aether.classifier;
  const observations = state.aether.observations.size;

  mount('#dash-kpis',
    kpi(number(breakdown.total), 'block rules active in this browser'),
    kpi(number(breakdown.blockDomains), 'filtered domains'),
    kpi(number(breakdown.safetyEntries), 'safety allowlist entries (never blocked)'),
    kpi(clf.isTrained ? clf.metrics.auc.toFixed(3) : '—', 'AETHER model AUC (held-out)', clf.isTrained ? 'ok' : 'warn')
  );

  mount('#dash-aether',
    h('h2', {}, 'AETHER status', pill(clf.isTrained ? 'model active' : 'warming up', clf.isTrained ? 'ok' : 'warn')),
    h('p', { text: clf.isTrained
      ? 'Threat intelligence is live: feed matching, typosquat detection and a calibrated lexical model. Every verdict is explainable and reversible.'
      : 'Feed matching and heuristics are live. The lexical model is training in the background; it is only ever one small signal and never decides alone.' }),
    kv([
      ['Rules loaded', `${number(breakdown.total)} (${Object.entries(breakdown.sources).map(([s, n]) => `${s}: ${number(n)}`).join(' · ')})`],
      ['Signals weighted', Object.entries(SIGNAL_WEIGHTS).map(([id, w]) => `${id} ${w}`).join(' · ')],
      ['Evidence floors', Object.values(EVIDENCE_FLOORS).map((f) => `≥${f.score}`).join(' · ')],
      ['Domains observed', number(observations)],
      ['False positives reported', number(state.aether.stats.falsePositivesReported)],
      ['Model training', clf.isTrained ? `${clf.metrics.trainMs} ms on ${number(clf.metrics.trainSize)} samples · holdout ${number(clf.metrics.holdoutSize)}` : (state.training ? 'running…' : 'not yet trained')]
    ])
  );

  const power = state.powerInfo || state.power?.describe() || null;
  mount('#dash-resources',
    h('h2', { text: 'CPU, memory & battery' }),
    kv([
      ['Repaint cadence', `${state.monitorIntervalMs || state.settings.monitorRefreshMs} ms while a live view is open · 0 ms when the tab is hidden`],
      ['Power state', power ? power.summary : 'starting…'],
      ['Throttle', power && power.factor > 1 ? `${power.factor}x (band: ${power.band})` : 'none — default cadence'],
      ['Rule set memory', `${bytes(estimateRuleMemory(breakdown.total))} (estimate: ${Math.round((estimateRuleMemory(breakdown.total) / breakdown.total))} bytes per rule)`],
      ['Analysis cost', clf.isTrained ? 'model trained once per visit; no retraining loop' : 'training once, in the background']
    ]),
    h('div', { class: 'row', style: { marginTop: '10px' } },
      h('button', {
        class: 'sm',
        text: state.benchmarkRunning ? 'Measuring…' : 'Measure filtering cost',
        disabled: state.benchmarkRunning,
        onclick: () => {
          if (state.benchmarkRunning) return;
          state.benchmarkRunning = true;
          renderDashboard();
          // Yield once so the busy state paints before the loop blocks the thread.
          setTimeout(() => {
            let result = null;
            try { result = runEngineBenchmark(); } catch (err) { result = { ok: false, reason: err.message }; }
            state.lastBenchmark = result;
            state.benchmarkRunning = false;
            // The dashboard repaints every second; render from state so a measured
            // result survives the next tick instead of vanishing.
            renderBenchmark(result);
            renderDashboard();
          }, 20);
        }
      }),
      h('span', { class: 'tiny muted', text: 'Runs 5,000 real evaluations on this device' })
    ),
    h('div', { id: 'dash-bench', style: { marginTop: '8px' } }),
    h('p', { class: 'small muted', style: { marginTop: '8px' }, text: 'The monitor loop is the only recurring timer in this app. It is skipped entirely in a hidden tab and slowed on low battery when the browser exposes battery state. Nothing here affects what is filtered.' })
  );

  renderBenchmark(state.lastBenchmark);

  const categories = breakdown.categories || {};
  const maxCategory = Math.max(1, ...Object.values(categories));
  mount('#dash-protection',
    h('h2', { text: 'Protection by category' }),
    Object.keys(categories).length
      ? h('div', { class: 'bars' }, Object.entries(categories).sort((a, b) => b[1] - a[1]).map(([cat, count]) => h('div', { class: 'bar-row' },
        h('span', { text: cat }),
        h('div', { class: 'bar-track' }, h('div', { class: 'bar-fill', style: { width: `${(count / maxCategory) * 100}%` } })),
        h('span', { class: 'mono', text: number(count) })
      )))
      : empty('No rules loaded.'),
    h('p', { class: 'small muted', style: { marginTop: '10px' }, text: 'Category counts come from the loaded feeds, not from a marketing claim. Blocking only happens when a real rule matches.' })
  );

  mount('#dash-activity',
    h('h2', { text: 'This session' }),
    kv([
      ['Requests processed', number(metrics.lookups)],
      ['Blocked before leaving device', number(metrics.blocked)],
      ['Resolved', number(state.client.stats.network)],
      ['Cache hits', number(state.client.stats.cached)],
      ['DNS errors', number(state.client.stats.errors)],
      ['Median latency', metrics.latency.p50 === null ? '—' : `${metrics.latency.p50} ms`]
    ]),
    h('p', { class: 'small muted', style: { marginTop: '8px' }, text: metrics.lookups === 0
      ? 'No activity yet. Try the DNS Lab or AETHER to generate real measurements.'
      : 'Measured on this device. Nothing is estimated.' })
  );

  const feedRows = Object.entries(state.bundled).map(([source, info]) => [
    source,
    info.error ? pill('failed', 'bad') : pill('loaded', 'ok'),
    info.error ? info.error : number(info.added),
    info.error ? '—' : number(info.duplicates)
  ]);
  mount('#dash-integrity',
    h('h2', { text: 'Data integrity & recovery' }),
    table(['Source', 'State', 'Rules added', 'Duplicates skipped'], feedRows),
    h('div', { class: 'row', style: { marginTop: '12px' } },
      pill(state.lastSnapshotId ? `snapshot ${state.lastSnapshotId}` : 'no snapshot yet', state.lastSnapshotId ? 'ok' : 'warn'),
      pill(state.storage?.constructor?.name || 'storage', 'info'),
      pill('analytics: local only', 'info')
    )
  );
}

/* ------------------------------------------------------------------ */
/* AETHER page                                                         */
/* ------------------------------------------------------------------ */

function renderAether() {
  renderAetherModel();
  renderAetherFeedback();
}

function scoreColor(score) {
  if (score >= 70) return 'var(--red)';
  if (score >= 45) return 'var(--amber)';
  if (score >= 20) return 'var(--purple)';
  return 'var(--ok)';
}

function verdictPillClass(verdict) {
  if (verdict === 'high-confidence' || verdict === 'likely-malicious') return 'bad';
  if (verdict === 'suspicious') return 'warn';
  if (verdict === 'watch') return 'purple';
  return 'ok';
}

function analyseDomain(input) {
  const out = document.getElementById('aether-result');
  const domain = String(input || '').trim();
  if (!domain) { mount(out, note('Enter a hostname first.', 'warn')); return; }
  let report;
  try {
    report = state.aether.analyze(domain);
  } catch (err) {
    mount(out, note(`Analysis failed: ${err.message}`, 'bad'));
    return;
  }
  if (report.error === 'invalid-domain') {
    mount(out, note(report.summary, 'warn'));
    return;
  }

  const signalNodes = report.signals.length
    ? report.signals.sort((a, b) => b.contribution - a.contribution).map((signal) => h('div', { class: 'signal' },
      h('header', {},
        h('h4', { text: signal.label }),
        h('span', { class: `pill ${signal.contribution >= 15 ? 'bad' : signal.contribution > 0 ? 'warn' : ''}` },
          `${plural(signal.contribution, 'point')} · weight ${signal.weight}/${signal.baseWeight}${signal.weightMultiplier < 1 ? ` ×${signal.weightMultiplier}` : ''}`)
      ),
      h('p', { class: 'evidence', text: signal.evidence }),
      signal.contributions?.length
        ? h('p', { class: 'tiny mono', text: `deciding tokens (log-odds): ${signal.contributions.map((c) => `${c.token} (${c.weight > 0 ? '+' : ''}${c.weight})`).join(', ')}` })
        : null
    ))
    : [empty('No signal produced evidence for this hostname. That is not proof of safety — it only means nothing we can see matched.')];

  const guardrailNodes = report.guardrails.map((g) => note(`${g.label}: ${g.detail}`, 'ok'));
  const floorNodes = (report.floors || []).map((f) => note(`Evidence floor ${f.score}/100 — ${f.reason}`, 'warn'));

  const filterVerdict = report.filterVerdict;
  const blocked = filterVerdict.blocked;

  mount(out,
    h('div', { class: 'verdict ' + (blocked ? 'blocked' : 'allowed') },
      h('div', { class: 'gauge' },
        h('div', { class: 'gauge-ring', style: { '--v': String(report.score), '--c': scoreColor(report.score) } },
          h('span', {}, String(report.score), h('small', { text: 'of 100' }))),
        h('div', { style: { flex: '1 1 240px' } },
          h('h3', { text: report.domain }),
          h('div', { class: 'row', style: { marginTop: '4px' } },
            pill(report.verdictLabel, verdictPillClass(report.verdict)),
            pill(blocked ? `filtered by ${filterVerdict.source}` : 'allowed by filter', blocked ? 'bad' : 'ok'),
            report.capped ? pill('guardrail capped', 'info') : null
          ),
          h('p', { style: { marginTop: '8px' }, text: report.summary })
        )
      ),
      h('ul', {}, (report.filterVerdict.reasons || []).map((reason) => h('li', { text: reason })))
    ),
    guardrailNodes.length ? h('div', { style: { marginTop: '12px' }, class: 'grid' }, guardrailNodes) : null,
    floorNodes.length ? h('div', { style: { marginTop: '12px' }, class: 'grid' }, floorNodes) : null,
    h('h3', { text: `Evidence (${report.signals.length} signal${report.signals.length === 1 ? '' : 's'})` }),
    ...signalNodes,
    h('h3', { text: 'Actions' }),
    h('div', { class: 'row' },
      h('button', {
        class: 'primary sm',
        text: blocked ? 'Allow this domain' : 'Block this domain',
        onclick: () => toggleDomainRule(report.domain, blocked)
      }),
      h('button', {
        class: 'sm',
        text: 'Report false positive',
        disabled: !blocked,
        onclick: () => reportFalsePositive(report.domain)
      }),
      h('button', { class: 'sm ghost', text: 'Re-analyse', onclick: () => analyseDomain(report.domain) })
    ),
    note('AETHER scores are advisory evidence, not enforcement. Blocking happens only through filter rules — yours or the feed\'s — and can be undone at any time.', 'ok')
  );
}

function toggleDomainRule(domain, currentlyBlocked) {
  if (currentlyBlocked) {
    state.customRules = state.customRules.filter((r) => !(r.pattern === domain && r.isAllow));
    state.customRules.push({ pattern: domain, isAllow: true });
    state.engine.addUserRule(`||${domain}^`, true);
    toast(`${domain} added to your allowlist.`, 'ok');
  } else {
    state.customRules = state.customRules.filter((r) => !(r.pattern === domain && !r.isAllow));
    state.customRules.push({ pattern: domain, isAllow: false });
    state.engine.addUserRule(`||${domain}^`, false);
    toast(`${domain} blocked by your rule.`, 'ok');
  }
  state.aether.reputation.cache.clear();
  scheduleSnapshot('custom-rule');
  analyseDomain(domain);
  renderSync();
  renderDashboard();
}

function reportFalsePositive(domain) {
  const result = state.aether.reportFalsePositive(domain);
  if (!result.ok) { toast(result.error || 'Could not record the report.', 'bad'); return; }
  state.customRules = state.customRules.filter((r) => r.pattern !== domain);
  state.customRules.push({ pattern: domain, isAllow: true });
  toast(result.note, 'ok');
  scheduleSnapshot('false-positive');
  analyseDomain(domain);
  renderAetherFeedback();
  renderSync();
}

function renderAetherModel() {
  const node = document.getElementById('aether-model');
  if (!node) return;
  const clf = state.aether?.classifier;
  if (!clf) { mount(node, h('h2', { text: 'Lexical model' }), empty('Engine not ready.')); return; }
  if (!clf.isTrained) {
    mount(node,
      h('h2', {}, 'Lexical model', pill(state.training ? 'training…' : 'not trained', 'warn')),
      h('p', { text: 'A multinomial Naive Bayes classifier over character n-grams, trained on this device from the bundled feeds. '
        + 'It is deliberately a small signal (weight 7/100) because lexical style alone cannot prove malice.' }),
      h('div', { class: 'row' },
        h('button', { class: 'primary sm', text: state.training ? 'Training…' : 'Train model now', disabled: state.training, onclick: () => ensureModel(true) })
      ),
      state.trainingError ? note(`Training error: ${state.trainingError}`, 'bad') : null
    );
    return;
  }
  const m = clf.metrics;
  const at = m.atThreshold || {};
  mount(node,
    h('h2', {}, 'Lexical model', pill(`AUC ${m.auc}`, m.auc >= 0.85 ? 'ok' : 'warn'), pill(`threshold ${m.operatingThreshold}`, 'info')),
    table(['Metric', 'Held-out value', 'Meaning'], [
      ["AUC (ROC)", String(m.auc), 'Ranking quality across all thresholds; 0.5 is random'],
      ['Operating threshold', String(m.operatingThreshold), m.atThreshold?.unreachable
        ? `Target specificity ${m.targetSpecificity} was not reached out-of-sample (holdout ${m.atThreshold.specificity}); the tuning split chose the conservative maximum`
        : `Chosen on the tuning split for specificity ≥ ${m.targetSpecificity}`],
      ['Precision at operating point', String(at.precision ?? '—'), 'Of domains it flags, how many were truly in the ad/threat corpus'],
      ['Recall at operating point', String(at.recall ?? '—'), 'Of ad/threat domains, how many it flags'],
      ['Specificity at operating point', String(at.specificity ?? '—'), 'Of legitimate domains, how many stay below the threshold'],
      ['Balanced accuracy', String(at.balancedAccuracy ?? '—'), 'Mean of recall and specificity — immune to the corpus ratio'],
      ['Precision at 1% base rate', String(at.precisionAtPrevalence?.[0.01] ?? '—'), 'If 1 in 100 domains were malicious, this is the chance a flagged domain really is'],
      ['Precision at 10% base rate', String(at.precisionAtPrevalence?.[0.1] ?? '—'), 'Same conversion at a threat-heavy base rate'],
      ['Best F1 (sweep)', String(m.bestF1), `At threshold ${m.bestThreshold} — reference only; F1 flatters a positive-heavy corpus, so it is not used`],
      ['Fit / tuning / holdout', `${number(m.fitSize ?? m.trainSize)} / ${number(m.tuneSize ?? m.tuningSize)} / ${number(m.holdoutSize)}`, 'Three-way split: threshold is tuned on the middle split, reported on the holdout'],
      ['Vocabulary', number(m.vocabSize), 'Character n-gram and word tokens'],
      ['Training time', `${m.trainMs} ms`, `On this device at ${new Date().toLocaleTimeString()}`]
    ]),
    h('div', { class: 'row', style: { marginTop: '10px' } },
      h('button', { class: 'sm', text: state.training ? 'Training…' : 'Retrain on current feeds', disabled: state.training, onclick: () => ensureModel(true) }),
      pill(m.binary ? 'presence features' : 'count features', 'info'),
      pill(m.featureMode, 'info')
    ),
    (m.atThreshold?.unreachable ? note(`Calibration note: ${m.atThreshold.reason || `the ${m.targetSpecificity} specificity target was not reached out-of-sample`}. Measured holdout specificity is ${m.atThreshold.specificity} and recall ${m.atThreshold.recall} at threshold ${m.operatingThreshold}. The model's weight stays at 7/100 regardless.`, 'warn') : null),
    note(`Honest limitation: the model only sees the characters of a hostname. It cannot see page content, certificates, WHOIS, or traffic. `
      + `Measured on this device, ${m.atThreshold?.fp ?? '—'} of ${m.atThreshold?.negatives ?? '—'} held-out legitimate domains were flagged — which is why its weight is 7/100 and why known-popular domains are hard-capped by a guardrail.`, 'warn')
  );
}

function renderAetherFeedback() {
  const node = document.getElementById('aether-feedback');
  if (!node) return;
  const snapshot = state.aether.reputation.statsSnapshot();
  const adapted = Object.entries(snapshot.feedback).filter(([, v]) => v.falsePositives + v.confirmations > 0);
  mount(node,
    h('h2', { text: 'Adaptive weighting (your feedback)' }),
    h('p', { class: 'small muted', text: 'Reporting a false positive shrinks the weight of the signals that fired, bounded to 40% of the published weight so evidence can never be silenced completely.' }),
    adapted.length
      ? table(['Signal', 'False positives', 'Confirmations', 'Weight multiplier', 'Effective weight'], adapted.map(([id, v]) => {
        const w = snapshot.weights[id] || {};
        return [id, String(v.falsePositives), String(v.confirmations), String(w.multiplier ?? 1), String(w.effective ?? w.base ?? '—')];
      }))
      : empty('No feedback recorded yet. The published weights are in full effect.')
  );
}

/* ------------------------------------------------------------------ */
/* DNS lab                                                             */
/* ------------------------------------------------------------------ */

async function resolveDomain() {
  const input = document.getElementById('dns-input');
  const type = document.getElementById('dns-type').value;
  const button = document.getElementById('dns-go');
  const out = document.getElementById('dns-result');
  const domain = String(input.value || '').trim();
  if (!domain) { mount(out, note('Enter a hostname first.', 'warn')); return; }
  setBusy(button, true, 'Resolving…');
  mount(out, h('p', { class: 'muted small', text: 'Contacting an encrypted resolver…' }));
  try {
    const result = type === 'dual'
      ? await state.client.lookupDualStack(domain)
      : await state.client.lookup(domain, { type });
    renderDnsResult(out, result, type);
    renderDnsCache();
    renderMonitor();
  } catch (err) {
    mount(out, note(`Resolution failed: ${err.message}`, 'bad'));
  } finally {
    setBusy(button, false);
  }
}

/**
 * Audits the CNAME chain of a resolution result: which links leave the queried
 * site, and whether any of them is already blocked or flagged. Purely advisory.
 */
function chainAuditFor(result) {
  if (!result || !Array.isArray(result.cname) || !result.cname.length) {
    return auditChain({ name: result?.name || '', cname: [], engine: state.engine });
  }
  return auditChain({
    name: result.name,
    cname: result.cname,
    engine: state.engine,
    reputation: (domain) => state.aether.reputation.analyze(domain)
  });
}

function chainAuditNodes(audit) {
  return [
    h('h3', { text: 'Redirect chain audit (DNS CNAME)' }),
    h('div', { class: 'row' },
      pill(audit.cloaking ? 'cloaking detected' : `${audit.depth} link(s)`, audit.cloaking ? 'warn' : 'info'),
      h('span', { class: 'small muted', text: audit.summary })
    ),
    audit.links.length
      ? table(['Chain link', 'Registrable domain', 'Filter', 'AETHER score'], audit.links.map((link) => [
        link.target,
        link.registrable || '—',
        link.blocked ? pill(`blocked · ${link.blockedBy.source}`, 'bad') : pill('no rule', 'ok'),
        link.score === null ? '—' : `${link.score} (${link.verdict})`
      ]))
      : empty('No CNAME links in this answer.'),
    h('ul', { style: { paddingLeft: '20px', marginTop: '8px' } }, (audit.evidence || []).map((line) => h('li', { class: 'small', text: line }))),
    note(audit.scope, 'info')
  ];
}

function renderDnsResult(out, result, type) {
  if (type === 'dual') {
    const a = result.results.a;
    const aaaa = result.results.aaaa;
    const cls = result.blocked ? 'blocked' : (a.ok || aaaa.ok) ? 'allowed' : 'error';
    mount(out,
      h('div', { class: `verdict ${cls}` },
        h('h3', { text: `${result.name} — ${result.blocked ? 'blocked' : a.ok || aaaa.ok ? 'resolved' : 'not resolved'}` }),
        h('ul', {},
          h('li', { text: `IPv4: ${a.addresses.join(', ') || '—'} ${a.status !== 'ok' ? `(${a.status})` : ''}` }),
          h('li', { text: `IPv6: ${aaaa.addresses.join(', ') || '—'} ${aaaa.status !== 'ok' ? `(${aaaa.status})` : ''}` }),
          h('li', { text: `Cache: ${a.cached ? 'hit' : 'miss'} · latency ${result.latencyMs} ms` }),
          ...(a.reasons || []).map((r) => h('li', { text: r }))
        )
      ),
      h('h3', { text: 'Per-family detail' }),
      table(['Query', 'Status', 'Resolver', 'Transport', 'DNSSEC', 'Latency'], [
        ['A', a.status, a.resolver?.name ?? '—', a.transport ?? '—', a.dnssec?.status ?? '—', `${a.latencyMs} ms`],
        ['AAAA', aaaa.status, aaaa.resolver?.name ?? '—', aaaa.transport ?? '—', aaaa.dnssec?.status ?? '—', `${aaaa.latencyMs} ms`]
      ]),
      ...(a.cname?.length ? chainAuditNodes(chainAuditFor(a)) : [])
    );
    return;
  }

  const cls = result.blocked ? 'blocked' : result.ok ? 'allowed' : 'error';
  const video = classifyVideoHost(result.name);
  mount(out,
    h('div', { class: `verdict ${cls}` },
      h('h3', { text: `${result.name} (${result.type})` }),
      h('ul', {}, (result.reasons || []).map((r) => h('li', { text: r })))
    ),
    (video.protected || video.adIntent)
      ? h('div', { class: 'row', style: { marginBottom: '8px' } },
        video.protected ? pill('media playback host — never blocked as an advert', 'ok') : null,
        video.adIntent ? pill('third-party advert host — safe to block', 'warn') : null,
        h('span', { class: 'small muted', text: video.reason })
      )
      : null,
    kv([
      ['Status', result.status],
      ['Addresses', result.addresses.join(', ') || '—'],
      ['CNAME chain', result.cname?.length ? result.cname.map((c) => c.to).join(' → ') : '—'],
      ['TTL', result.ttl ?? '—'],
      ['Resolver', result.resolver ? `${result.resolver.name} (${result.resolver.operator})` : '—'],
      ['Transport', result.transport ?? '—'],
      ['DNSSEC', result.dnssec ? `${result.dnssec.status} — ${result.dnssec.detail}` : '—'],
      ['Cached', result.cached ? (result.stale ? 'stale (refreshing)' : 'yes') : 'no'],
      ['Latency', `${result.latencyMs} ms`],
      ['Pipeline', (result.pipeline || []).join(' → ')],
      ['Fallback attempts', (result.attempts || []).length ? (result.attempts || []).map((a) => `${a.resolverId}:${a.ok ? 'ok' : a.reason || a.code}`).join(', ') : 'single attempt']
    ]),
    ...(result.cname?.length ? chainAuditNodes(chainAuditFor(result)) : [])
  );
}

function renderDns() {
  const health = state.client?.pool.health.snapshot() || [];
  const order = state.client?.pool.order().map((r) => r.id) || [];
  mount('#dns-resolvers',
    h('h2', { text: 'Encrypted resolvers' }),
    table(['Resolver', 'Policy (operator claim)', 'Client subnet (ECS)', 'Health', 'EWMA'], RESOLVERS.map((resolver) => {
      const hstate = health.find((s) => s.id === resolver.id) || {};
      const cls = hstate.available === false ? 'bad' : hstate.ewmaMs ? 'ok' : '';
      return [
        h('div', {}, h('div', { text: resolver.name }), h('span', { class: 'tiny muted mono', text: `${resolver.dohJson || resolver.dohWire}` })),
        h('div', { class: 'tiny' }, h('div', { text: resolver.filtering }), h('span', { class: 'muted', text: resolver.privacy })),
        h('div', { class: 'tiny' },
          pill(resolver.ecs === 'supported' ? 'forwards subnet' : 'no subnet', resolver.ecs === 'supported' ? 'ok' : 'info'),
          h('div', { class: 'muted', style: { marginTop: '4px' }, text: resolver.ecsNote })
        ),
        pill(hstate.available === false ? 'circuit open' : (hstate.successes ? 'healthy' : 'untested'), cls),
        hstate.ewmaMs ? `${Math.round(hstate.ewmaMs)} ms` : '—'
      ];
    })),
    h('p', { class: 'small muted', style: { marginTop: '8px' }, text: `Fallback order: ${order.join(' → ') || 'default'}. A failing resolver trips a circuit breaker after repeated failures and stops receiving traffic until its cooldown expires.` }),
    h('p', { class: 'small muted', text: 'Media hostnames are resolved through the first healthy resolver that forwards a client subnet (ECS), because the authoritative server picks the video CDN edge from it. Ordinary browsing keeps the order above. A slow resolver is hedged: the runner-up starts after 350 ms instead of waiting out the full timeout.' }),
    note(`DoT: ${DotTransport.capability().reason}`, 'warn')
  );

  renderDnsCache();
}

function renderDnsCache() {
  if (!state.client) return;
  const snapshot = state.client.snapshot();
  mount('#dns-cache',
    h('h2', { text: 'Cache & latency' }),
    kv([
      ['Entries', `${snapshot.cache.entries} / ${snapshot.cache.maxEntries}`],
      ['Live entries', String(snapshot.cache.live)],
      ['Hits / misses', `${snapshot.cache.stats.hits} / ${snapshot.cache.stats.misses}`],
      ['Negative hits', String(snapshot.cache.stats.negativeHits)],
      ['Single-flight joins', String(snapshot.cache.stats.singleFlightJoins)],
      ['p50 / p95 latency', snapshot.latency.p50 === null ? '—' : `${snapshot.latency.p50} / ${snapshot.latency.p95} ms`],
      ['Blocked lookups', String(snapshot.stats.blocked)],
      ['Network lookups', String(snapshot.stats.network)]
    ]),
    h('div', { class: 'row', style: { marginTop: '10px' } },
      h('button', { class: 'sm', text: 'Flush cache', onclick: () => { state.client.cache.clear(); renderDnsCache(); toast('DNS cache flushed.', 'ok'); } })
    )
  );
}

/* ------------------------------------------------------------------ */
/* Firewall AI                                                         */
/* ------------------------------------------------------------------ */

function renderFirewall() {
  const snapshot = state.firewall?.snapshot();
  if (!snapshot) return;
  mount('#fw-state',
    h('h2', {}, 'Observed behaviour', pill(`${number(snapshot.destinations)} destinations`, 'info')),
    kv([
      ['Observations recorded', number(snapshot.stats.observations)],
      ['Evaluations', number(snapshot.stats.evaluations)],
      ['Monitor decisions', number(snapshot.stats.monitors)],
      ['Block recommendations', number(snapshot.stats.blocks)],
      ['Beaconing detections', number(snapshot.stats.beaconing)],
      ['Tunnelling detections', number(snapshot.stats.tunneling)],
      ['Rate anomalies', number(snapshot.stats.rateAnomalies)],
      ['Fan-out events', number(snapshot.stats.fanout)]
    ]),
    h('p', { class: 'small muted', style: { marginTop: '8px' } },
      'Sources: DNS Lab resolutions, AETHER analyses and any observation log you import. '
      + 'The browser cannot passively capture other applications\' traffic — that requires the Android VPN core — so this list only reflects what OmniShield X itself performed or was given.'),
    snapshot.policies.length
      ? table(['App', 'Denied', 'Allowed'], snapshot.policies.map((p) => [p.app, p.deny.join(', ') || '—', p.allow.join(', ') || '—']))
      : empty('No per-app policies set. Safe default: monitor only, never auto-block.'),
    h('h2', { style: { marginTop: '14px' }, text: 'Detector thresholds (as configured, not as marketed)' }),
    table(['Detector', 'Threshold'], firewallThresholdRows()),
    h('p', { class: 'small muted', style: { marginTop: '8px' },
      text: 'These are the live values used by the detectors above. Blocks require at least two independent indicators, and the safe default remains monitor-only.' })
  );
}

/**
 * Renders DEFAULT_CONFIG as a readable list. The numbers come from the module,
 * not from a copy of the docs, so the UI cannot drift from the behaviour.
 */
function firewallThresholdRows() {
  const rows = [];
  for (const [key, value] of Object.entries(FW_CONFIG)) {
    if (value && typeof value === 'object' && !Array.isArray(value)) {
      for (const [sub, subValue] of Object.entries(value)) {
        rows.push([`${key}.${sub}`, String(subValue)]);
      }
    } else if (!Array.isArray(value)) {
      rows.push([key, String(value)]);
    } else {
      rows.push([key, value.length ? value.join(', ') : '—']);
    }
  }
  return rows;
}

function runFirewallSelfTest() {
  const out = document.getElementById('fw-selftest-out');
  const fw = new FirewallAI({ clock: () => 1_700_000_000_000 });
  const base = 1_700_000_000_000;
  const results = [];

  // Beacon: exact 60s cadence.
  for (let i = 0; i < 12; i++) fw.observe({ domain: 'beacon.fixture', at: base + i * 60_000 });
  results.push(['Beaconing (60s cadence)', fw.detectBeaconing('beacon.fixture')]);

  // Jittered traffic must not fire.
  let t = base;
  for (const gap of [5_000, 120_000, 9_000, 300_000, 15_000, 45_000, 7_000, 200_000]) {
    t += gap; fw.observe({ domain: 'human.fixture', at: t });
  }
  results.push(['Beaconing (human jitter)', fw.detectBeaconing('human.fixture')]);

  // Tunnel: long, unique, high-entropy labels.
  const alphabet = 'abcdefghijklmnopqrstuvwxyz0123456789';
  for (let i = 0; i < 24; i++) {
    let token = '';
    for (let j = 0; j < 48; j++) token += alphabet[(i * 7 + j * 11) % alphabet.length];
    fw.observe({ domain: `${token}.${i}.chunk.data.tunnel.fixture`, at: base + i * 1000 });
  }
  results.push(['DNS tunnelling', fw.detectTunneling('tunnel.fixture')]);

  results.push(['Fan-out (70 distinct)', (() => {
    for (let i = 0; i < 70; i++) fw.observe({ domain: `spray${i}.fixture`, at: base + i * 300 });
    return fw.detectFanout(base + 21_000);
  })()]);

  mount(out,
    note('These fixtures are synthetic test vectors used to verify the detectors. They are NOT your traffic and are never counted as protection.', 'warn'),
    table(['Detector', 'Fired', 'Confidence', 'Measured detail'], results.map(([name, result]) => [
      name,
      result.fired ? pill('yes', 'bad') : pill('no', 'ok'),
      result.confidence || result.reason || '—',
      result.fired
        ? (result.cv !== undefined ? `CV ${result.cv}, mean ${result.meanIntervalMs} ms` :
          result.avgEntropy !== undefined ? `entropy ${result.avgEntropy}, ${result.uniqueSubdomains} unique` :
            `${result.distinct} distinct`)
        : (result.reason || 'within normal range')
    ])),
    h('div', { class: 'row', style: { marginTop: '10px' } }, ...results.map(([name, result]) =>
      pill(`${name}: ${result.fired ? 'fires' : 'ignores'}`, result.fired ? 'warn' : 'ok'))),
    note('Interpretation matters: a periodic check-in can be command-and-control OR a legitimate app polling an API. '
      + 'That is exactly why two independent signals are required before a block recommendation.', 'ok')
  );
}

function inspectFirewall() {
  const domain = String(document.getElementById('fw-input').value || '').trim();
  const out = document.getElementById('fw-inspect-out');
  if (!domain) { mount(out, note('Enter a destination that appears in the observation list.', 'warn')); return; }
  const report = state.firewall.inspect(domain);
  const reputation = state.aether.analyze(domain);
  const decision = state.firewall.evaluate(domain, { reputation });
  mount(out,
    h('div', { class: `verdict ${decision.decision === 'block' ? 'blocked' : decision.decision === 'monitor' ? 'error' : 'allowed'}` },
      h('h3', { text: `${decision.domain} → ${decision.decision.toUpperCase()} (confidence: ${decision.confidence})` }),
      h('ul', {}, decision.reasons.map((r) => h('li', { text: r })))
    ),
    h('h3', { text: 'Raw detector output' }),
    table(['Detector', 'Fired', 'Detail'], [
      ['Beaconing', report.beaconing.fired ? 'yes' : 'no', report.beaconing.evidence || report.beaconing.reason || '—'],
      ['Rate', report.rate.fired ? 'yes' : 'no', report.rate.evidence || report.rate.reason || '—'],
      ['Tunnelling', report.tunneling.fired ? 'yes' : 'no', report.tunneling.evidence || report.tunneling.reason || '—']
    ]),
    kv([
      ['Observations', String(report.observations)],
      ['Registrable domain', report.parent],
      ['Entropy (main label)', String(report.features.entropy)],
      ['Labels', String(report.features.labelCount)],
      ['AETHER reputation', `${reputation.score}/100 (${reputation.verdictLabel})`]
    ])
  );
}

function importFirewallLog(file) {
  const reader = new FileReader();
  reader.onload = () => {
    try {
      const parsed = JSON.parse(String(reader.result));
      const entries = Array.isArray(parsed) ? parsed : parsed.observations || parsed.entries || parsed.logs;
      if (!Array.isArray(entries)) throw new Error('expected an array, or an object with observations/entries/logs');
      const accepted = state.firewall.importLog(entries);
      toast(`Imported ${accepted} observation(s).`, 'ok');
      renderFirewall();
      renderMonitor();
    } catch (err) {
      toast(`Import failed: ${err.message}`, 'bad');
    }
  };
  reader.readAsText(file);
}

/* ------------------------------------------------------------------ */
/* Monitor                                                             */
/* ------------------------------------------------------------------ */

function renderMonitor() {
  if (!state.monitor) return;
  const metrics = state.monitor.metrics();
  mount('#mon-kpis',
    kpi(number(metrics.lookups), 'requests processed'),
    kpi(number(metrics.blocked), 'blocked before resolution'),
    kpi(metrics.blockedRatio === null ? '—' : `${metrics.blockedRatio}%`, 'blocked share'),
    kpi(metrics.latency.p95 === null ? '—' : `${metrics.latency.p95} ms`, 'p95 resolution latency')
  );

  const canvas = document.getElementById('mon-chart');
  drawSeries(canvas, [
    { points: metrics.series.lookups, color: '#2e7dff', fill: 'rgba(46,125,255,.25)' },
    { points: metrics.series.blocked, color: '#ff5470' }
  ], { height: 90 });
  mount('#mon-legend',
    h('span', {}, pill('requests', 'info')),
    h('span', {}, pill('blocked', 'bad')),
    h('span', { class: 'muted', text: `bucket = ${metrics.bucketMs / 1000}s` })
  );

  const categories = Object.entries(metrics.categories).sort((a, b) => b[1] - a[1]);
  const max = Math.max(1, ...categories.map(([, v]) => v));
  mount('#mon-categories',
    h('h2', { text: 'Blocked by category' }),
    categories.length
      ? h('div', { class: 'bars' }, categories.map(([cat, count]) => h('div', { class: 'bar-row' },
        h('span', { text: cat }),
        h('div', { class: 'bar-track' }, h('div', { class: 'bar-fill hot', style: { width: `${(count / max) * 100}%` } })),
        h('span', { class: 'mono', text: number(count) })
      )))
      : empty('Nothing blocked in this session yet.')
  );

  mount('#mon-top',
    h('h2', { text: 'Most active domains' }),
    metrics.topDomains.length
      ? table(['Domain', 'Requests', 'Blocked', 'Category'], metrics.topDomains.map((d) => [d.domain, number(d.count), number(d.blocked), d.category || '—']))
      : empty('No domain activity recorded.')
  );

  mount('#mon-events',
    h('h2', { text: 'Recent events' }),
    metrics.recent.length
      ? table(['Time', 'Kind', 'Domain', 'Detail'], [...metrics.recent].reverse().map((e) => [
        new Date(e.at).toLocaleTimeString(),
        e.kind,
        e.domain || '—',
        e.kind === 'blocked' ? (e.category || 'blocked') : e.kind === 'lookup' ? `${e.status || ''} ${e.latencyMs ? `${e.latencyMs} ms` : ''}`.trim() : (e.decision || '')
      ]))
      : empty('No events yet. Use the DNS Lab or AETHER to produce real measurements.')
  );
}

/**
 * Rough in-memory cost of the loaded rules. Measured from the engine's own
 * counts, with a conservative per-rule allowance for the stored pattern string
 * plus the Set/Map slot — labelled as an estimate everywhere it is shown.
 */
function estimateRuleMemory(totalRules) {
  return Math.round((Number(totalRules) || 0) * 120);
}

/**
 * Measures this device's actual filtering cost: evaluates a deterministic sample
 * of the loaded rule set and reports per-op latency plus throughput. Nothing is
 * cached or estimated — the number shown is the number measured just now.
 */
function runEngineBenchmark(iterations = 5000) {
  const domains = [...state.engine.filterSubdomains.keys()];
  if (!domains.length) return { ok: false, reason: 'no rules loaded, so there is nothing to measure' };
  const sample = domains.slice(0, Math.min(iterations, domains.length));
  const samples = new Float64Array(sample.length);
  const startedAt = performance.now();
  for (let i = 0; i < sample.length; i++) {
    const t0 = performance.now();
    state.engine.evaluate(sample[i]);
    samples[i] = performance.now() - t0;
  }
  const totalMs = performance.now() - startedAt;
  const sorted = Array.from(samples).sort((a, b) => a - b);
  const pct = (p) => sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * p))];
  return {
    ok: true,
    evaluated: sample.length,
    totalMs: Math.round(totalMs * 100) / 100,
    perSecond: Math.round(sample.length / (totalMs / 1000)),
    p50us: Math.round(pct(0.5) * 1000),
    p95us: Math.round(pct(0.95) * 1000),
    meanUs: Math.round((totalMs * 1000 / sample.length) * 10) / 10,
    measuredAt: new Date().toISOString()
  };
}

function renderBenchmark(result) {
  const node = document.getElementById('dash-bench');
  if (!node) return;
  if (!result) { mount(node, empty('No benchmark run yet. It measures your device, not a marketing claim.')); return; }
  if (!result.ok) { mount(node, note(result.reason, 'warn')); return; }
  mount(node,
    kv([
      ['Evaluations measured', `${number(result.evaluated)} (sampled from the loaded rule set)`],
      ['Total time', `${result.totalMs} ms`],
      ['Mean per evaluation', `${result.meanUs} µs`],
      ['p50 / p95', `${result.p50us} µs / ${result.p95us} µs`],
      ['Throughput', `${number(result.perSecond)} evaluations/second`],
      ['Measured', relativeTime(result.measuredAt)]
    ]),
    note('Measured on this device just now, using performance.now() around real engine.evaluate() calls. Rule matching is a Set/Map walk; no network or model inference is involved.', 'info')
  );
}

function startMonitorLoop() {
  clearInterval(state.monitorTimer);
  const interval = state.power
    ? state.power.intervalFor(state.settings.monitorRefreshMs)
    : state.settings.monitorRefreshMs;
  state.monitorIntervalMs = interval;
  state.monitorTimer = setInterval(() => {
    // Background tabs do no work at all: a hidden tab cannot show a live view.
    if (document.hidden) return;
    if (state.currentPage === 'monitor') renderMonitor();
    if (state.currentPage === 'dashboard') renderDashboard();
  }, interval);
  return interval;
}

/**
 * Battery-aware scheduling. The Battery Status API is not available in every
 * browser (and is deliberately absent in Safari); when it is missing the app
 * keeps the default cadence and says so instead of pretending to optimise.
 */
async function startPowerGovernor() {
  state.power = new PowerGovernor({
    onChange: (info) => {
      state.powerInfo = info;
      startMonitorLoop();
      if (state.currentPage === 'dashboard') renderDashboard();
      if (state.currentPage === 'status') renderStatus();
      toast(`Repaint cadence changed: ${info.summary}`, 'info');
    }
  });
  state.powerInfo = await state.power.start();
  startMonitorLoop();
  if (state.currentPage === 'dashboard') renderDashboard();
}

/* ------------------------------------------------------------------ */
/* Feeds                                                               */
/* ------------------------------------------------------------------ */

async function renderFeeds() {
  if (!state.feeds) return;
  const statuses = await state.feeds.list();
  mount('#feeds-list',
    h('h2', { text: 'Feed registry' }),
    table(['Feed', 'Category', 'Source', 'Installed', 'Rules', 'Integrity'], FEED_REGISTRY.map((feed) => {
      const status = statuses.find((s) => s.id === feed.id) || {};
      return [
        h('div', {}, h('div', { text: feed.name }), h('span', { class: 'tiny muted', text: `${feed.license} · ${feed.homepage}` })),
        feed.category,
        h('span', { class: 'tiny mono', text: feed.url.replace('https://raw.githubusercontent.com/', '').slice(0, 48) + '…' }),
        status.installed ? pill(`v ${relativeTime(status.fetchedAt)}`, status.stale ? 'warn' : 'ok') : pill('bundled only', 'info'),
        status.installed ? number(status.rules) : (feed.id === 'core_lite_ads' && state.bundled.core_lite_ads?.added ? number(state.bundled.core_lite_ads.added) : '—'),
        status.installed ? h('span', { class: 'tiny mono', text: status.hash?.slice(0, 16) }) : '—'
      ];
    })),
    h('p', { class: 'small muted', style: { marginTop: '8px' }, text: 'Online updates are optional. The bundled copy in assets/ is always available offline and is never modified.' }),
    h('h3', { text: 'Optional aggressive tier' }),
    h('div', { class: 'row' },
      h('button', {
        class: state.settings.aggressiveTier ? 'danger sm' : 'sm',
        text: state.settings.aggressiveTier ? 'Disable aggressive tier' : 'Enable aggressive tier',
        onclick: async () => {
          const target = !state.settings.aggressiveTier;
          if (target && !confirm('The aggressive tier blocks tag managers and social SDKs. Some sites may break. You can undo this instantly, and every block stays labelled. Continue?')) return;
          await enableAggressiveTier(target);
          toast(target ? 'Aggressive tier enabled — reversible at any time.' : 'Aggressive tier disabled.', target ? 'warn' : 'ok');
        }
      }),
      pill(state.settings.aggressiveTier ? 'enabled' : 'disabled (safe default)', state.settings.aggressiveTier ? 'warn' : 'ok')
    )
  );
}

async function checkFeedUpdates() {
  const button = document.getElementById('feeds-check');
  const status = document.getElementById('feeds-status');
  setBusy(button, true, 'Checking…');
  const lines = [];
  try {
    for (const feed of FEED_REGISTRY) {
      let result;
      try {
        result = await state.feeds.update(feed.id);
      } catch (err) {
        result = { ok: false, reason: 'exception', detail: err.message };
      }
      if (result.ok && result.changed) {
        const text = await state.feeds.content(feed.id);
        state.engine.loadFilterList(text, feed.category, feed.id);
        state.aether.reputation.cache.clear();
        lines.push(note(`${feed.name}: updated — ${number(result.meta.rules)} rules, sha256 ${result.meta.hash.slice(0, 12)}…`, 'ok'));
      } else if (result.ok) {
        lines.push(note(`${feed.name}: already up to date (${result.reason}).`, 'ok'));
      } else {
        lines.push(note(`${feed.name}: not applied — ${result.reason}${result.detail ? ` (${result.detail})` : ''}.`
          + (result.retained ? ` Previous copy retained (${number(result.retained.rules)} rules).` : ' No copy installed; bundled rules stay active.'), 'warn'));
      }
    }
    lines.push(note('Feed updates are validated before use: structure, SHA-256 integrity, and rollback protection against truncated lists.', 'ok'));
    mount(status, ...lines);
    await saveSnapshot('feed-update');
    renderAll();
  } finally {
    setBusy(button, false);
  }
}

async function verifyFeeds() {
  const status = document.getElementById('feeds-status');
  const results = [];
  for (const feed of FEED_REGISTRY) {
    const result = await state.feeds.verify(feed.id);
    results.push(note(`${feed.name}: ${result.ok ? 'integrity OK' : `not verified (${result.reason})`}`, result.ok ? 'ok' : 'warn'));
  }
  mount(status, ...results);
}

/* ------------------------------------------------------------------ */
/* Privacy                                                             */
/* ------------------------------------------------------------------ */

function renderPrivacy() {
  const report = state.analytics.report();
  const days = report.days.length
    ? table(['Day', 'Reported events', 'Suppressed', 'Distinct domains', 'Categories'], report.days.map((d) => [
      d.day, number(d.reportedEvents), `${number(d.suppressedEvents)} (${d.suppressedBuckets} buckets)`, number(d.distinctDomains),
      Object.entries(d.categories).map(([c, n]) => `${c}: ${n}`).join(', ') || '—'
    ]))
    : empty('No analytics recorded yet.');

  mount('#privacy-out',
    h('h2', { text: 'Aggregate report' }),
    days,
    kv([
      ['Reported events', number(report.totals.reported)],
      ['Suppressed events (k-anonymity)', number(report.totals.suppressed)],
      ['k threshold', String(report.k)],
      ['Retention', `${report.retentionDays} days`],
      ['Leaves device', 'no']
    ])
  );

  mount('#privacy-note',
    h('h2', { text: 'What is stored' }),
    kv([
      ['Hostnames', 'Never stored in the clear — salted SHA-256 prefix only'],
      ['Storage key', 'omnishield-x:analytics:state'],
      ['Network transmission', 'None; no analytics endpoint exists in this app'],
      ['Suppression', `Any category with fewer than ${report.k} events is withheld from the report`]
    ]),
    note('This is local aggregation, not a cryptographic anonymity system: someone with access to this browser profile could correlate hashes. '
      + 'Nothing is uploaded, so the exposure is limited to this device.', 'warn')
  );
}

/* ------------------------------------------------------------------ */
/* Backup & sync                                                       */
/* ------------------------------------------------------------------ */

async function renderSnapshots() {
  if (!state.backups) return;
  const list = await state.backups.list();
  mount('#snapshot-list',
    h('h2', { text: 'Snapshots' }),
    list.length
      ? table(['Snapshot', 'Created', 'Reason', 'Schema', 'Size', 'Integrity', ''], list.map((s) => [
        h('span', { class: 'mono tiny', text: s.id }),
        s.createdAt ? relativeTime(s.createdAt) : '—',
        s.reason || '—',
        s.schemaVersion ?? '—',
        bytes(s.bytes),
        s.corrupt ? pill('corrupt', 'bad') : (s.valid ? pill('verified', 'ok') : pill('invalid', 'bad')),
        s.corrupt ? '—' : h('button', {
          class: 'sm ghost', text: 'Restore',
          onclick: async () => {
            const result = await state.backups.restore(s.id);
            if (!result.ok) { toast(`Restore failed: ${result.reason}`, 'bad'); return; }
            toast('Snapshot restored. Reloading configuration…', 'ok');
            location.reload();
          }
        })
      ]))
      : empty('No snapshots yet — one is created automatically after changes.'),
    h('p', { class: 'small muted', style: { marginTop: '8px' }, text: 'Hashes are verified before any restore; a corrupt snapshot is reported and skipped, never silently applied.' })
  );
}

function renderSync() {
  const status = document.getElementById('sync-status');
  if (status) {
    mount(status,
      h('div', { class: 'row' },
        pill(state.lastSnapshotId ? `last snapshot ${state.lastSnapshotId}` : 'no snapshot', state.lastSnapshotId ? 'ok' : 'warn'),
        pill(`${state.customRules.length} custom rule${state.customRules.length === 1 ? '' : 's'}`, 'info'),
        pill(`${state.aether?.observations.size ?? 0} observed domains`, 'info')
      )
    );
  }
  const wrap = document.getElementById('rules-body');
  if (wrap) {
    ui.clear(wrap);
    if (!state.customRules.length) {
      wrap.appendChild(h('tr', {}, h('td', { colspan: 5, class: 'empty' }, 'No custom rules yet. Allow or block a domain from AETHER, or import an Android backup.')));
    }
    for (const rule of state.customRules) {
      wrap.appendChild(h('tr', {},
        h('td', { class: 'mono', text: rule.pattern }),
        h('td', {}, pill(rule.isAllow ? 'ALLOW' : 'BLOCK', rule.isAllow ? 'ok' : 'bad')),
        h('td', { text: 'suffix' }),
        h('td', { text: 'on' }),
        h('td', {},
          h('button', {
            class: 'sm ghost', text: 'Remove',
            onclick: () => {
              state.customRules = state.customRules.filter((r) => r !== rule);
              state.engine.removeUserRule(`||${rule.pattern}^`, rule.isAllow);
              scheduleSnapshot('rule-removed');
              renderSync();
              renderDashboard();
              toast(`Removed ${rule.isAllow ? 'allow' : 'block'} rule for ${rule.pattern}.`, 'ok');
            }
          })
        )
      ));
    }
    document.getElementById('sync-table-wrap').hidden = false;
  }
  renderSnapshots();
}

function importAndroidBackup(file) {
  const status = document.getElementById('import-status');
  const reader = new FileReader();
  reader.onload = () => {
    try {
      const data = JSON.parse(String(reader.result));
      if (!Array.isArray(data.customRules)) throw new Error('not an OmniShield backup (customRules array missing)');
      state.importedBackup = data;
      let applied = 0;
      for (const rule of data.customRules) {
        const pattern = String(rule.rawPattern ?? rule.pattern ?? '').trim();
        if (!pattern) continue;
        const isAllow = rule.ruleType === 'ALLOW' || rule.isAllow === true;
        if (state.customRules.some((r) => r.pattern === pattern && r.isAllow === isAllow)) continue;
        state.customRules.push({ pattern, isAllow });
        if (state.engine.addUserRule(pattern, isAllow)) applied++;
      }
      state.aether.reputation.cache.clear();
      const notes = [
        note(`Imported: ${data.customRules.length} custom rule(s) (${applied} applied to the engine), `
          + `${data.browserSiteExceptions?.length || 0} site exception(s), ${data.excludedAppPackages?.length || 0} app exclusion(s).`, 'ok'),
        note('Imported values are rendered as text only — file contents are never interpreted as markup.', 'ok')
      ];
      if (data.protectionMode) {
        notes.push(note(`Protection mode in the backup: ${typeof data.protectionMode === 'object' ? data.protectionMode.title : data.protectionMode}`, 'ok'));
      }
      mount(status, ...notes);
      document.getElementById('export-btn').disabled = false;
      scheduleSnapshot('android-import');
      renderSync();
      renderDashboard();
    } catch (err) {
      mount(status, note(`Import failed: ${err.message}`, 'bad'));
    }
  };
  reader.readAsText(file);
}

function exportMergedConfig() {
  const payload = {
    ...(state.importedBackup || {}),
    ...buildState({ includeModel: true }),
    exportedAt: new Date().toISOString(),
    exportedBy: 'OmniShield X web platform v5.0.2'
  };
  download('omnishield-x-config.json', JSON.stringify(payload, null, 2));
  toast('Merged configuration exported.', 'ok');
}

/* ------------------------------------------------------------------ */
/* Extension & capabilities                                            */
/* ------------------------------------------------------------------ */

async function renderExtension() {
  const breakdown = state.engine?.breakdown();
  const facts = await fetchJSONSafe('./assets/extension-facts.json');
  const artifactRows = facts
    ? [
        ['Rules shipped in the ZIP', `${number(facts.rules)} static rules in ${facts.rulesets} ruleset files`],
        ['Unique domains', number(facts.uniqueDomains)],
        ['Feed lines considered', `${number(facts.feedLinesConsidered)} → capped at ${number(facts.maxStaticRules)} by feed order`],
        ['Archive size', bytes(facts.bytes)],
        ['Archive version', String(facts.version)]
      ]
    : [['Rules shipped in the ZIP', 'facts file unavailable — rebuild with npm run build:extension']];

  mount('#extension-facts',
    h('h2', { text: 'Extension facts (measured from the shipped ZIP, not marketed)' }),
    kv([
      ...artifactRows,
      ['Rules in this web core', `${number(breakdown?.total ?? 0)} (same feeds, different engine)`],
      ['Why not all of them', `Chrome guarantees ${number(facts?.maxStaticRules ?? 30000)} static rules per extension; anything above that is shared with every other installed blocker and can fail to enable`],
      ['Permissions requested', 'declarativeNetRequest, declarativeNetRequestFeedback, storage'],
      ['Host access', 'a content script matches http/https pages to inject cosmetic CSS — it reads no page content and sends nothing'],
      ['Network calls', 'none at runtime; rules are static files'],
      ['Counts in the popup', 'from the browser\'s own matched-rule events, and only where the browser exposes them — otherwise the popup shows "—" and the reason'],
      ['Cannot do', 'filter other apps, filter traffic in other browsers, or block first-party ads served from the site\'s own domain (rules are third-party only)']
    ]),
    note('This is a content blocker, not a privacy VPN. Chrome limits how many static rules an extension may ship, which is why the extension carries a capped subset of the 79k-rule web core. The builder script (npm run build:extension) regenerates the ZIP deterministically from the bundled feeds.', 'warn')
  );
}

/** Fetch + parse JSON, returning null instead of throwing on any failure. */
async function fetchJSONSafe(url) {
  try {
    const response = await fetch(url, { cache: 'no-cache' });
    if (!response.ok) return null;
    return await response.json();
  } catch {
    return null;
  }
}

const CAPABILITIES = [
  ['Deterministic DNS filter ladder', 'works', 'engine.test.mjs — ladder order, user allow overrides, undo symmetry'],
  ['Hosts-file + Adblock + plain-domain parsing', 'works', 'engine.test.mjs — formats'],
  ['Wildcard rules (with catch-all rejection)', 'works', 'engine.test.mjs — wildcards'],
  ['DoH (JSON API)', 'works', 'dns-transport.test.mjs — DoH JSON'],
  ['DoH (RFC 8484 wire)', 'works', 'dns-transport.test.mjs — wire'],
  ['DoT (DNS over TLS)', 'partial', 'Codec + framing implemented and tested; browsers cannot open TCP:853'],
  ['DNSSEC: AD-bit trust from validating resolver', 'works', 'dns-transport.test.mjs — DNSSEC status'],
  ['DNSSEC: local chain-of-trust validation', 'not-implemented', 'Requires walking root KSK → ZSK; documented limitation'],
  ['IPv4 + IPv6 dual-stack resolution', 'works', 'dns-client.test.mjs — dual-stack'],
  ['TTL + negative caching, single-flight', 'works', 'cache.test.mjs'],
  ['Resolver health & circuit-breaker fallback', 'works', 'dns-transport.test.mjs — fallback/breaker'],
  ['AETHER reputation with attributable evidence', 'works', 'reputation.test.mjs — every point attributable'],
  ['Lexical ML model with held-out metrics', 'works', 'classifier.test.mjs — AUC/precision floors'],
  ['Typosquat / homograph / IDN detection', 'works', 'lexical.test.mjs'],
  ['False-positive guardrails (popularity, allowlist)', 'works', 'reputation.test.mjs'],
  ['Firewall AI behavioural detection', 'works', 'firewall.test.mjs — 16 tests, positive + negative'],
  ['Real-time monitoring', 'works', 'monitor.test.mjs — measured events only'],
  ['Feed updates with integrity + rollback protection', 'works', 'infrastructure.test.mjs — feed manager'],
  ['Config backup, migration, auto-recovery', 'works', 'infrastructure.test.mjs — backup manager'],
  ['Offline operation (bundled rules + service worker)', 'works', 'sw.js caches the shell and bundled lists'],
  ['CNAME cloaking / DNS redirect-chain audit', 'works', 'chain.test.mjs — named targets, blocked-by attribution, advisory only'],
  ['HTTP redirect analysis (301/302/JS)', 'not-implemented', 'Invisible to a DNS filter; only the CNAME chain can be audited'],
  ['Privacy analytics with k-anonymity', 'works', 'infrastructure.test.mjs — privacy'],
  ['Battery/CPU-aware repaint scheduling', 'works', 'power.test.mjs — bands, clamping, honest unsupported path'],
  ['Battery Status API in every browser', 'partial', 'Chrome/Edge expose it; Safari does not. Falls back to the default cadence and says so'],
  ['Passive capture of other apps\' traffic', 'not-implemented', 'Requires OS-level VPN (Android core); browsers cannot do this'],
  ['Antivirus / file scanning', 'not-implemented', 'This is DNS filtering, not an antivirus. Never claimed.'],
  ['Universal ad blocking (all apps, all browsers)', 'not-implemented', 'No technology can guarantee that; see capabilities notes'],
  ['Certificate interception / TLS decryption', 'rejected', 'Deliberately never implemented — it would break your security']
];

function renderStatus() {
  mount('#capabilities',
    table(['Capability', 'Status', 'Evidence / reason'], CAPABILITIES.map(([name, status, evidence]) => [
      name,
      status === 'works' ? pill('verified', 'ok')
        : status === 'partial' ? pill('partial', 'warn')
          : status === 'rejected' ? pill('never', 'info') : pill('not implemented', 'bad'),
      evidence
    ]))
  );
  mount('#limits',
    h('h2', { text: 'Explicit limitations of this deployment' }),
    h('ul', { style: { paddingLeft: '20px', display: 'grid', gap: '6px' } },
      h('li', { text: 'This is a browser application: it cannot install a VPN, capture other apps\' DNS, or block traffic outside this page. The Android core does that.' }),
      h('li', { text: 'AETHER\'s lexical score is a weak signal by measurement (AUC ≈ 0.86), weighted 7/100, and its precision at a 1% base rate is ≈ 0.06. It can raise a flag; it cannot block.' }),
      h('li', { text: 'The classifier is trained on the bundled feeds plus a curated legitimate-domain list. Unfamiliar legitimate domains can be flagged as "ad-like" — the popularity guardrail and low weight limit the impact.' }),
      h('li', { text: 'DNSSEC trust is delegated to the upstream resolver\'s AD bit. OmniShield X does not verify signatures locally.' }),
      h('li', { text: 'Public suffix handling covers common multi-label ccTLD suffixes, not the full Public Suffix List.' }),
      h('li', { text: 'First-party advertising served from the site\'s own domain cannot be filtered by DNS.' }),
      h('li', { text: 'Feed content is trusted as published by its maintainers (HaGeZi, GPLv3); a compromised upstream is mitigated by hash and shrink checks, not eliminated.' })
    )
  );
}

/* ------------------------------------------------------------------ */
/* Status helpers                                                      */
/* ------------------------------------------------------------------ */

function updateNetworkStatus() {
  const el = document.getElementById('net-status');
  if (!el) return;
  ui.clear(el);
  const online = navigator.onLine;
  el.className = `pill ${online ? 'ok' : 'warn'}`;
  el.appendChild(h('span', { class: 'dot' }));
  el.appendChild(document.createTextNode(online ? ' network available' : ' offline — bundled rules active'));
}

function updateOnlineStatus() {
  updateNetworkStatus();
  if (!navigator.onLine || !state.settings.autoUpdateFeeds || !state.feeds) return;
  if (state.feedCheckScheduled) return;
  // Delayed so the first paint and the initial rule load are never competing with
  // multi-megabyte feed downloads on a slow connection.
  state.feedCheckScheduled = true;
  setTimeout(() => { checkFeedUpdatesQuietly().finally(() => { state.feedCheckScheduled = false; }); }, 15000);
}

async function checkFeedUpdatesQuietly() {
  if (state.feedCheckRunning) return;
  state.feedCheckRunning = true;
  try {
    for (const feed of FEED_REGISTRY) {
      try {
        const result = await state.feeds.update(feed.id);
        if (result.ok && result.changed) {
          const text = await state.feeds.content(feed.id);
          state.engine.loadFilterList(text, feed.category, feed.id);
          toast(`${feed.name} updated (${number(result.meta.rules)} rules).`, 'ok');
          renderDashboard();
        }
      } catch { /* stay on bundled rules */ }
    }
  } finally {
    state.feedCheckRunning = false;
  }
}

function registerServiceWorker() {
  if (!('serviceWorker' in navigator)) return;
  if (!/^https?:$/.test(location.protocol)) return;
  navigator.serviceWorker.register('sw.js').catch((err) => console.warn('service worker registration failed', err));
}

/* ------------------------------------------------------------------ */
/* Event wiring                                                        */
/* ------------------------------------------------------------------ */

function wireEvents() {
  const aetherInput = document.getElementById('aether-input');
  document.getElementById('aether-go').addEventListener('click', () => analyseDomain(aetherInput.value));
  aetherInput.addEventListener('keydown', (event) => { if (event.key === 'Enter') analyseDomain(aetherInput.value); });
  document.getElementById('aether-demo').addEventListener('click', () => {
    const shapes = ['ads.doubleclick.net', 'paypa1.com', 'secure-paypal-login.tk', 'accounts.google.com', 'my-random-blog.example'];
    const next = shapes[(Math.floor(Math.random() * shapes.length))];
    aetherInput.value = next;
    analyseDomain(next);
  });

  const dnsInput = document.getElementById('dns-input');
  document.getElementById('dns-go').addEventListener('click', resolveDomain);
  dnsInput.addEventListener('keydown', (event) => { if (event.key === 'Enter') resolveDomain(); });
  document.getElementById('dns-flush').addEventListener('click', () => {
    state.client.cache.clear();
    renderDnsCache();
    toast('DNS cache flushed.', 'ok');
  });

  document.getElementById('fw-selftest').addEventListener('click', runFirewallSelfTest);
  document.getElementById('fw-go').addEventListener('click', inspectFirewall);
  document.getElementById('fw-export').addEventListener('click', () => {
    const snapshot = state.firewall.snapshot();
    download('omnishield-firewall-observations.json', JSON.stringify({
      exportedAt: new Date().toISOString(),
      stats: snapshot.stats,
      note: 'Destination scale only; no payloads are ever captured.',
      observations: [...state.firewall.destinations.values()]
        .filter((d) => !d.domain.startsWith('parent:'))
        .map((d) => ({ domain: d.domain, app: d.app, count: d.count, firstSeen: d.firstSeen, lastSeen: d.lastSeen }))
    }, null, 2));
  });
  document.getElementById('fw-import').addEventListener('change', (event) => {
    const file = event.target.files?.[0];
    if (file) importFirewallLog(file);
  });

  document.getElementById('feeds-check').addEventListener('click', checkFeedUpdates);
  document.getElementById('feeds-verify').addEventListener('click', verifyFeeds);

  document.getElementById('privacy-export').addEventListener('click', () => {
    download('omnishield-analytics.json', state.analytics.exportJSON());
    toast('Aggregate report exported.', 'ok');
  });
  document.getElementById('privacy-clear').addEventListener('click', async () => {
    if (!confirm('Delete all locally stored analytics? This cannot be undone.')) return;
    await state.analytics.clear();
    renderPrivacy();
    toast('Analytics deleted.', 'ok');
  });

  document.getElementById('snapshot-save').addEventListener('click', () => saveSnapshot('manual', { silent: false }));
  document.getElementById('snapshot-recover').addEventListener('click', async () => {
    const result = await state.backups.recover();
    const status = document.getElementById('sync-status');
    if (result.ok) {
      mount(status, note(`Newest valid snapshot ${result.id} verified (${relativeTime(result.createdAt)}${result.skipped.length ? `, ${result.skipped.length} corrupt skipped` : ''}).`, 'ok'));
    } else {
      mount(status, note(`Recovery: ${result.reason}. ${result.detail || ''}`, 'warn'));
    }
  });

  document.getElementById('import-file').addEventListener('change', (event) => {
    const file = event.target.files?.[0];
    if (file) importAndroidBackup(file);
  });
  document.getElementById('import-btn').addEventListener('click', () => {
    const file = document.getElementById('import-file').files?.[0];
    if (!file) { mount('#import-status', note('Choose a JSON backup exported from the Android app first.', 'warn')); return; }
    importAndroidBackup(file);
  });
  document.getElementById('export-btn').addEventListener('click', exportMergedConfig);
}

/* ------------------------------------------------------------------ */
/* Start                                                               */
/* ------------------------------------------------------------------ */

wireEvents();
const initial = location.hash.replace('#', '');
const ready = boot().then(() => {
  if (initial && PAGE_RENDERERS[initial]) navigate(initial);
  else navigate('dashboard');
  return state;
});

/**
 * Test/automation hook. Exposes the live application state and the boot promise
 * so the integration suite can drive the real UI instead of a mock of it.
 */
globalThis.__omnishield = {
  state,
  ready,
  navigate,
  renderAll,
  analyseDomain,
  resolveDomain,
  runFirewallSelfTest,
  checkFeedUpdates,
  saveSnapshot,
  buildState,
  startMonitorLoop,
  stop() {
    clearInterval(state.monitorTimer);
    try { state.power?.stop(); } catch { /* ignore */ }
  }
};
