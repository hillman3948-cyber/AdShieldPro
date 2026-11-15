#!/usr/bin/env node
/**
 * OmniShield X — site assembler.
 *
 * The GitHub Pages deployment hosts two things on one domain:
 *
 *   /        ← docs/                 the marketing site (unchanged)
 *   /app/    ← the web platform      index.html, modules, feeds, extension zip
 *
 * Keeping the assembly in a script (rather than inline in the workflow) means the
 * exact same layout can be produced and checked locally, and that the
 * service-worker precache list is verified against the staged tree — the failure
 * mode this prevents is "the site deployed but the app cannot start offline".
 *
 * Usage: `node scripts/stage-site.mjs [--out _site] [--check-only]`
 */
import { cpSync, existsSync, mkdirSync, readFileSync, rmSync, statSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve, relative } from 'node:path';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');

/** Files the browser needs at the root of /app/. */
export const APP_ROOT_FILES = [
  'index.html', 'app.js', 'engine.js', 'sw.js', 'manifest.webmanifest', 'omnishield-x-extension.zip'
];
/** Directories copied wholesale (they contain only runtime assets). */
export const APP_DIRS = ['ui', 'dns', 'aether', 'security', 'guard', 'assets'];

/** Parses the service worker's precache list and reports which entries are missing. */
export function checkPrecache(appDir) {
  const swPath = resolve(appDir, 'sw.js');
  if (!existsSync(swPath)) return { ok: false, error: 'sw.js missing from the staged app', listed: [], missing: [] };
  const source = readFileSync(swPath, 'utf8');
  const listed = [...new Set([...source.matchAll(/'(\.\/[^']+)'/g)].map((m) => m[1]))];
  const missing = listed
    .filter((url) => url !== './')
    .map((url) => url.slice(2))
    .filter((rel) => !existsSync(resolve(appDir, rel)));
  return { ok: missing.length === 0, listed, missing, error: missing.length ? `missing: ${missing.join(', ')}` : null };
}

/**
 * Builds the deployable tree.
 * @param {{out?: string, root?: string}} [options]
 */
export function stageSite({ out = '_site', root = ROOT } = {}) {
  const target = resolve(root, out);
  rmSync(target, { recursive: true, force: true });
  mkdirSync(target, { recursive: true });

  // 1. Marketing site at the domain root.
  const docs = resolve(root, 'docs');
  if (!existsSync(resolve(docs, 'index.html'))) throw new Error('docs/index.html is missing');
  cpSync(docs, target, { recursive: true });

  // 2. The web platform under /app/.
  const appDir = resolve(target, 'app');
  mkdirSync(appDir, { recursive: true });
  const copied = [];
  for (const file of APP_ROOT_FILES) {
    const from = resolve(root, file);
    if (!existsSync(from)) throw new Error(`${file} is missing from the repository root`);
    cpSync(from, resolve(appDir, file));
    copied.push(file);
  }
  for (const dir of APP_DIRS) {
    const from = resolve(root, dir);
    if (!existsSync(from)) throw new Error(`${dir}/ is missing from the repository root`);
    cpSync(from, resolve(appDir, dir), { recursive: true });
    copied.push(`${dir}/`);
  }

  // 3. Nothing that only exists for development may leak into the artifact.
  for (const forbidden of ['node_modules', 'tests', 'scripts', 'package.json', '.git']) {
    if (existsSync(resolve(appDir, forbidden))) throw new Error(`${forbidden} must not be published under /app/`);
  }

  const precache = checkPrecache(appDir);
  if (!precache.ok) throw new Error(`staged app is not self-sufficient — ${precache.error}`);

  return {
    out: target,
    appDir,
    copied,
    precache: precache.listed.length,
    appBytes: directorySize(appDir),
    files: countFiles(appDir)
  };
}

function directorySize(dir) {
  let total = 0;
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = resolve(dir, entry.name);
    total += entry.isDirectory() ? directorySize(full) : statSync(full).size;
  }
  return total;
}

function countFiles(dir) {
  let total = 0;
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    total += entry.isDirectory() ? countFiles(resolve(dir, entry.name)) : 1;
  }
  return total;
}

/* CLI */
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const outIndex = process.argv.indexOf('--out');
  const out = outIndex !== -1 ? process.argv[outIndex + 1] : '_site';
  try {
    const result = stageSite({ out });
    console.log(`staged ${relative(ROOT, result.out) || result.out} — app ${(result.appBytes / 1048576).toFixed(2)} MB in ${result.files} files`);
    console.log(`${result.precache} precached assets verified · /app/ contains ${result.copied.length} entries`);
  } catch (err) {
    console.error(`staging failed: ${err.message}`);
    process.exit(1);
  }
}
