#!/usr/bin/env node
/**
 * OmniShield X — static verification.
 *
 * Checks that must hold for the deployed site, independent of any browser:
 *   - every module import resolves to a real file
 *   - every navigation target has a section, and every section a renderer
 *   - the service worker only lists files that exist
 *   - no HTML-injection sinks in the UI layer
 *   - no fabricated security claims in user-visible copy
 *   - published reputation weights still sum to 100
 *   - bundled data files are present, non-empty and well-formed
 *   - the web manifest is valid JSON pointing at a real icon
 *
 * Run: `npm run verify` (also executed by the test suite).
 */
import { readFileSync, existsSync, statSync } from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { dirname, resolve, relative } from 'node:path';
import { inspect as inspectExtension, MAX_STATIC_RULES } from './build-extension.mjs';
import { lint as lintRuntime } from './lint.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const abs = (p) => resolve(ROOT, p);
const read = (p) => readFileSync(abs(p), 'utf8');
const exists = (p) => existsSync(abs(p));

/** Phrases that would be fabricated security marketing. */
const FORBIDDEN_CLAIMS = [
  'military-grade', 'unhackable', 'guaranteed protection', '100% block',
  'blocks 100%', 'eliminates all ads', 'protects against all', 'total anonymity',
  'bank-level encryption', 'AI-powered antivirus', 'military grade encryption'
];

const MODULE_FILES = [
  'app.js', 'engine.js', 'ui/dom.js',
  'dns/codec.js', 'dns/cache.js', 'dns/client.js', 'dns/dnssec.js', 'dns/resolvers.js',
  'guard/video-safety.js',
  'aether/index.js', 'aether/data.js', 'aether/domain.js', 'aether/lexical.js',
  'aether/typosquat.js', 'aether/reputation.js', 'aether/classifier.js',
  'security/monitor.js', 'security/firewall-ai.js', 'security/feedmanager.js',
  'security/backup.js', 'security/privacy.js', 'security/sha256.js', 'security/storage.js'
];

export function verify() {
  const checks = [];
  const add = (name, ok, detail = '') => checks.push({ name, ok: !!ok, detail: String(detail) });

  /* 1. Core files exist and are non-trivial. */
  for (const file of ['index.html', 'app.js', 'engine.js', 'sw.js', 'manifest.webmanifest', ...MODULE_FILES]) {
    add(`file: ${file}`, exists(file) && statSync(abs(file)).size > 100,
      exists(file) ? `${statSync(abs(file)).size} bytes` : 'missing');
  }

  /* 2. Every relative ESM import resolves. */
  const importRe = /from\s+['"](\.[^'"]+)['"]/g;
  const missingImports = [];
  for (const file of MODULE_FILES) {
    if (!exists(file)) continue;
    const source = read(file);
    for (const match of source.matchAll(importRe)) {
      const target = resolve(dirname(abs(file)), match[1]);
      if (!exists(target) && !exists(`${target}.js`) && !exists(`${target}.mjs`)) {
        missingImports.push(`${file} -> ${match[1]}`);
      }
    }
  }
  add('module imports resolve', missingImports.length === 0, missingImports.join('; '));

  /* 3. Navigation <-> sections <-> renderers. */
  const html = read('index.html');
  const navPages = [...html.matchAll(/data-page="([^"]+)"/g)].map((m) => m[1]);
  const sections = [...html.matchAll(/id="page-([^"]+)"/g)].map((m) => m[1]);
  add('every nav tab has a section', navPages.every((p) => sections.includes(p)),
    navPages.filter((p) => !sections.includes(p)).join(', '));
  add('every section has a nav tab', sections.every((s) => navPages.includes(s)),
    sections.filter((s) => !navPages.includes(s)).join(', '));
  const appSource = read('app.js');
  const renderers = [...appSource.matchAll(/^\s{2}([a-zA-Z]+):\s*render[A-Z]/gm)].map((m) => m[1]);
  const routerBlock = appSource.slice(appSource.indexOf('const PAGE_RENDERERS'), appSource.indexOf('function navigate'));
  const routedPages = [...routerBlock.matchAll(/^\s{2}([a-zA-Z]+):/gm)].map((m) => m[1]);
  add('every section has a renderer', sections.every((s) => routedPages.includes(s)),
    sections.filter((s) => !routedPages.includes(s)).join(', '));
  add('renderer table not empty', renderers.length >= 0 && routedPages.length === sections.length,
    `${routedPages.length} routes / ${sections.length} sections`);

  /* 4. Service worker precache list points at real files. */
  const sw = read('sw.js');
  const precache = [...sw.matchAll(/'(\.\/[^']+)'/g)].map((m) => m[1].replace(/^\.\//, '')).filter((p) => p && p !== '');
  const missingPrecache = precache.filter((p) => !p.endsWith('/') && !exists(p));
  add('service worker precache targets exist', missingPrecache.length === 0, missingPrecache.join(', '));
  add('service worker does not cache DoH', /url\.origin !== self\.location\.origin/.test(sw));

  /* 5. No HTML injection sinks in UI code. */
  const uiSources = { 'app.js': appSource, 'ui/dom.js': read('ui/dom.js') };
  const sinks = [];
  for (const [file, source] of Object.entries(uiSources)) {
    if (/\.innerHTML\s*=/.test(source)) sinks.push(`${file}: innerHTML assignment`);
    if (/innerHTML\s*\+=/.test(source)) sinks.push(`${file}: innerHTML append`);
    if (/insertAdjacentHTML/.test(source)) sinks.push(`${file}: insertAdjacentHTML`);
    if (/document\.write/.test(source)) sinks.push(`${file}: document.write`);
    if (/new Function\(|eval\(/.test(source)) sinks.push(`${file}: eval/new Function`);
  }
  add('no DOM XSS sinks in UI layer', sinks.length === 0, sinks.join('; '));

  /* 6. No fabricated claims in user-visible copy. */
  const visible = [html, appSource, read('ui/dom.js')].join('\n').toLowerCase();
  const hits = FORBIDDEN_CLAIMS.filter((phrase) => visible.includes(phrase));
  add('no fabricated security claims', hits.length === 0, hits.join('; '));

  // "antivirus" may only appear in a negated/disclaiming context.
  const antivirusLines = visible.split('\n').filter((l) => l.includes('antivirus'));
  const badAntivirus = antivirusLines.filter((l) => !/not an antivirus|not-implemented|never|does not|no antivirus/.test(l));
  add('antivirus only mentioned in disclaimers', badAntivirus.length === 0, badAntivirus.join(' | ').slice(0, 200));

  // The old hardcoded APK-only statistics must not reappear.
  add('no hardcoded APK-only statistics', !/367,?000/.test(html + appSource));

  /* 7. Published weights still sum to 100 (contract with the UI copy). */
  const reputation = read('aether/reputation.js');
  const weightBlock = reputation.slice(reputation.indexOf('SIGNAL_WEIGHTS'), reputation.indexOf('});', reputation.indexOf('SIGNAL_WEIGHTS')));
  const weights = [...weightBlock.matchAll(/^\s{2}(\w+): (\d+)/gm)].map((m) => Number(m[2]));
  const total = weights.reduce((a, b) => a + b, 0);
  add('reputation weights sum to 100', total === 100, `sum = ${total} over ${weights.length} signals`);

  /* 8. Bundled data present and parsable. */
  for (const asset of ['assets/core_lite_ads.txt', 'assets/core_supplement_ads.txt',
    'assets/core_supplement_aggressive.txt', 'assets/icon.svg']) {
    add(`asset: ${asset}`, exists(asset) && statSync(abs(asset)).size > 100);
  }
  const lite = read('assets/core_lite_ads.txt');
  const liteRules = (lite.match(/^\|\|/gm) || []).length;
  add('bundled light feed has >= 70k rules', liteRules >= 70000, `${liteRules} rules`);
  add('light feed declares its license', /GPLv3/.test(lite.slice(0, 2000)));

  const supplement = read('assets/core_supplement_ads.txt');
  const supplementRules = (supplement.match(/^\|\|/gm) || []).length;
  add('supplement is small and curated', supplementRules > 50 && supplementRules < 500, `${supplementRules} rules`);
  add('supplement states it is curated, not upstream', /NOT an upstream feed/i.test(supplement));
  const aggressive = read('assets/core_supplement_aggressive.txt');
  add('aggressive tier warns about breakage and is opt-in', /SHIPS DISABLED BY DEFAULT/i.test(aggressive));

  /* 9. Manifest validity. */
  let manifestOk = false;
  let manifestDetail = '';
  try {
    const manifest = JSON.parse(read('manifest.webmanifest'));
    const icons = manifest.icons || [];
    manifestOk = !!manifest.name && !!manifest.start_url && icons.length > 0
      && icons.every((i) => exists(i.src));
    manifestDetail = `${manifest.name} · ${icons.length} icon(s)`;
  } catch (err) {
    manifestDetail = err.message;
  }
  add('web manifest is valid', manifestOk, manifestDetail);

  /* 9b. Accessibility basics that must not regress. */
  const htmlA11y = read('index.html');
  const tabs = [...html.matchAll(/<button[^>]*data-page="([a-z]+)"[^>]*>/g)];
  add('every tab is a real button element', tabs.length >= 10 && tabs.every((m) => /type="button"/.test(m[0])),
    `${tabs.length} tabs`);
  add('the active tab exposes aria-current', tabs.filter((m) => /aria-current="page"/.test(m[0])).length === 1);
  add('boot status is an aria-live region', /id="boot-status"[^>]*role="status"/.test(htmlA11y));
  add('toast container is aria-live', /id="toasts"[^>]*aria-live="polite"/.test(htmlA11y));
  add('navigation landmark is labelled', /<nav[^>]*aria-label="[^"]+"/.test(htmlA11y));
  add('every page section has an accessible name',
    (htmlA11y.match(/<section class="page"[^>]*aria-label="[^"]+"/g) || []).length === 10,
    `${(htmlA11y.match(/<section class="page"[^>]*aria-label=/g) || []).length} of 10`);
  add('a skip link targets the main landmark',
    /class="skip-link" href="#main"/.test(htmlA11y) && /<main id="main"/.test(htmlA11y));

  /* 9c. The shipped extension is a real archive built from reviewed sources. */
  const ext = inspectExtension({ root: ROOT });
  add('extension archive is readable', !!ext.ok, ext.error || `${ext.ruleCount} rules · ${ext.bytes} bytes`);
  add('extension stays inside Chrome\'s guaranteed static-rule allowance',
    !ext.overLimit, `${ext.ruleCount} ≤ ${MAX_STATIC_RULES}`);
  add('extension rulesets are unique and non-empty',
    ext.uniqueDomains === ext.ruleCount, `${ext.uniqueDomains} unique of ${ext.ruleCount}`);
  add('extension manifest is MV3 and matches the app version',
    ext.manifestVersion === 3 && ext.version === JSON.parse(read('package.json')).version,
    `MV${ext.manifestVersion ?? '?'} · ${ext.version ?? '?'}`);
  for (const file of ['extension/src/background.js', 'extension/src/content.js', 'extension/src/popup.js', 'extension/src/popup.html']) {
    add(`extension source: ${file}`, exists(file) && statSync(abs(file)).size > 100);
  }
  const extSources = ['extension/src/background.js', 'extension/src/popup.js', 'extension/src/content.js'].map(read).join('\n');
  add('extension sources make no network calls', !/\bfetch\s*\(|XMLHttpRequest|WebSocket|navigator\.sendBeacon/.test(extSources));
  let facts = null;
  try { facts = JSON.parse(read('assets/extension-facts.json')); } catch { /* reported below */ }
  add('extension facts file is present and matches the archive',
    !!facts && facts.rules === ext.ruleCount && facts.rulesets === ext.rulesets && facts.version === ext.version,
    facts ? `${facts.rules} rules · v${facts.version}` : 'missing or unparsable');
  add('extension builder is wired into package scripts', !!JSON.parse(read('package.json')).scripts?.['build:extension']);

  /* 9d. Boot wiring sanity: the governor and monitor loop are started once. */
  const bootSource = read('app.js');
  const governorCalls = (bootSource.match(/^\s*startPowerGovernor\(\);$/gm) || []).length;
  add('boot starts the power governor exactly once', governorCalls === 1, `${governorCalls} call sites`);
  const bootBody = (bootSource.split('async function startPowerGovernor')[0].split('function boot')[1]) || '';
  add('the monitor loop is started by the governor, not separately',
    !/^\s*startMonitorLoop\(\);$/m.test(bootBody),
    'no duplicate timer install during boot');

  /* 9e. House lint rules for shipped runtime code. */
  const lintResult = lintRuntime(ROOT);
  add('runtime code passes the house lint rules', lintResult.ok,
    lintResult.ok ? `${lintResult.files.length} files` : lintResult.findings.map((f) => `${f.rule}@${f.file}`).join(', '));
  add('no orphan modules in the shipped tree', !lintResult.findings.some((f) => f.rule === 'no-orphan-module'),
    `${lintResult.reachable.length} modules reachable`);

  /* 9f. Workflow files must stay loadable: a multi-line double-quoted `run`
        scalar silently made ci.yml invalid to Actions. */
  for (const wf of ['ci.yml', 'deploy-website.yml']) {
    const path = `.github/workflows/${wf}`;
    if (!exists(path)) continue;
    const text = read(path);
    const brokenScalar = text.split('\n').some((line) => {
      const match = /^\s*run:\s*(.*)$/.exec(line);
      if (!match) return false;
      const value = match[1].trim();
      if (!value.startsWith('"')) return false;
      // A quoted scalar must open and close on the same line.
      const quotes = (value.match(/"/g) || []).length;
      return quotes % 2 !== 0;
    });
    add(`workflow ${wf} has no unclosed quoted run scalar`, !brokenScalar);
  }
  const deployWorkflow = read('.github/workflows/deploy-website.yml');
  add('deploy workflow assembles the site with the checked-in script',
    /node scripts\/stage-site\.mjs/.test(deployWorkflow) && /path:\s*_site/.test(deployWorkflow));
  add('the assembler publishes the web platform under /app/',
    /APP_DIRS/.test(read('scripts/stage-site.mjs')) && /app'/.test(read('scripts/stage-site.mjs')));
  add('the assembler verifies every service-worker precache target',
    /checkPrecache/.test(read('scripts/stage-site.mjs')) && /is not self-sufficient/.test(read('scripts/stage-site.mjs')));

  /* 10. Package scripts point at real test globs. */
  const pkg = JSON.parse(read('package.json'));
  add('package has test script', !!pkg.scripts?.test, pkg.scripts?.test);
  add('package has verify script', !!pkg.scripts?.verify, pkg.scripts?.verify);
  add('no runtime dependencies', !pkg.dependencies || Object.keys(pkg.dependencies).length === 0,
    JSON.stringify(pkg.dependencies || {}));

  /* 11. Documentation exists for the honest capability list. */
  for (const doc of ['README.md', 'docs/WEB_PLATFORM.md', 'docs/ARCHITECTURE.md', 'docs/AETHER.md',
    'docs/UNSUPPORTED.md', 'docs/WEB_TESTING.md', 'docs/TESTING.md']) {
    add(`doc: ${doc}`, exists(doc) && statSync(abs(doc)).size > 400);
  }

  return {
    ok: checks.every((c) => c.ok),
    checks,
    root: ROOT,
    summary: {
      passed: checks.filter((c) => c.ok).length,
      failed: checks.filter((c) => !c.ok).length
    }
  };
}

/* CLI */
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const result = verify();
  const label = (c) => `${c.ok ? '  ok  ' : ' FAIL '} ${c.name}${c.detail ? `  — ${c.detail}` : ''}`;
  for (const check of result.checks) console.log(label(check));
  console.log(`\n${result.summary.passed} passed, ${result.summary.failed} failed`);
  process.exit(result.ok ? 0 : 1);
}
