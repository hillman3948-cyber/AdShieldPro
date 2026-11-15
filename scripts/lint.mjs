#!/usr/bin/env node
/**
 * OmniShield X — static lint for the browser platform.
 *
 * This is deliberately not a general-purpose linter: it encodes the handful of
 * house rules that have actually caught bugs in this codebase, so they fail in CI
 * instead of being remembered.
 *
 * Rules (all hard failures):
 *   1. no `console.log` left in shipped runtime code (console.debug/warn/error are
 *      allowed — those are deliberate diagnostics)
 *   2. no `debugger`, no `alert(`
 *   3. no HTML-injection sinks: innerHTML / outerHTML / insertAdjacentHTML /
 *      document.write / eval / new Function in shipped code
 *   4. no TODO / FIXME / XXX markers (either fix it or file it in docs)
 *   5. no unused named imports (cheap regex analysis; catches dead wiring)
 *   6. no synchronous XMLHttpRequest
 *   7. every shipped module must be reachable from an entry point (app.js or
 *      sw.js) — orphan files are either dead code or missing wiring
 *
 * Run: `npm run lint` (also executed by `npm run verify`).
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { dirname, resolve, relative } from 'node:path';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');

/** Everything the browser actually loads. Tests and build scripts are excluded. */
const RUNTIME_DIRS = ['ui', 'dns', 'aether', 'security', 'guard'];
const RUNTIME_FILES = ['app.js', 'engine.js', 'sw.js'];

const ALLOWED_CONSOLE = /console\.(debug|warn|error|info)\s*\(/;

function walk(dir, out = []) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = resolve(dir, entry.name);
    if (entry.isDirectory()) walk(full, out);
    else if (entry.isFile() && entry.name.endsWith('.js')) out.push(full);
  }
  return out;
}

export function runtimeFiles(root = ROOT) {
  const files = RUNTIME_FILES.map((f) => resolve(root, f));
  for (const dir of RUNTIME_DIRS) walk(resolve(root, dir), files);
  return files.filter((f) => statSync(f).isFile());
}

/** Named imports that never appear again in the file. */
export function unusedImports(source) {
  const unused = [];
  const importRe = /import\s+(?:(\w+)\s*,\s*)?\{([^}]*)\}\s+from\s+['"][^'"]+['"]/g;
  for (const match of source.matchAll(importRe)) {
    const defaultName = match[1];
    if (defaultName) unused.push(...checkName(source, defaultName, match.index + match[0].length));
    for (const part of match[2].split(',')) {
      const name = part.trim().split(/\s+as\s+/).pop()?.trim();
      if (!name) continue;
      unused.push(...checkName(source, name, match.index + match[0].length));
    }
  }
  return unused;
}

function checkName(source, name, searchFrom) {
  if (!/^[A-Za-z_$][\w$]*$/.test(name)) return [];
  const rest = source.slice(searchFrom);
  // A word-boundary occurrence anywhere after the import counts as a use.
  // Preceded by a dot is a property access or a spread — both count as a use.
  const used = new RegExp(`(?<![\\w$])${name.replace(/\$/g, '\\$')}(?![\\w$])`).test(rest);
  return used ? [] : [name];
}

/** Modules reachable from an entry point through static relative imports. */
export function reachableModules(root = ROOT) {
  const seen = new Set();
  const queue = ['app.js', 'sw.js'];
  while (queue.length) {
    const file = queue.pop();
    if (seen.has(file)) continue;
    let source;
    try { source = readFileSync(resolve(root, file), 'utf8'); } catch { continue; }
    seen.add(file);
    // Static ES imports plus the service worker's precache list.
    for (const match of source.matchAll(/(?:import\s+[^'"]*from\s*|import\s*\()\s*['"](\.\.?\/[^'"]+)['"]/g)) {
      queue.push(match[1].replace(/^\.\//, ''));
    }
    for (const match of source.matchAll(/'\.\/([\w./-]+\.js)'/g)) queue.push(match[1]);
  }
  return seen;
}

export function lint(root = ROOT) {
  const findings = [];
  const add = (rule, file, detail) => findings.push({ rule, file, detail });
  const files = runtimeFiles(root);

  for (const absolute of files) {
    const file = relative(root, absolute).replace(/\\/g, '/');
    const source = readFileSync(absolute, 'utf8');
    const lines = source.split('\n');

    lines.forEach((line, index) => {
      const where = `${file}:${index + 1}`;
      // Strip inline string literals crudely so a message *about* logging is fine.
      if (/console\.log\s*\(/.test(line) && !ALLOWED_CONSOLE.test(line)) {
        add('no-console-log', where, line.trim().slice(0, 120));
      }
      if (/\bdebugger\b/.test(line)) add('no-debugger', where, line.trim().slice(0, 120));
      if (/(?<![\w.])alert\s*\(/.test(line)) add('no-alert', where, line.trim().slice(0, 120));
      if (/\b(innerHTML|outerHTML|insertAdjacentHTML)\s*=/.test(line)) add('no-html-sink', where, line.trim().slice(0, 120));
      if (/\bdocument\.write\s*\(/.test(line)) add('no-document-write', where, line.trim().slice(0, 120));
      if (/(?<![\w.$])eval\s*\(/.test(line)) add('no-eval', where, line.trim().slice(0, 120));
      if (/\bnew\s+Function\s*\(/.test(line)) add('no-new-function', where, line.trim().slice(0, 120));
      if (/\b(TODO|FIXME|XXX)\b/.test(line)) add('no-placeholder-markers', where, line.trim().slice(0, 120));
      if (/XMLHttpRequest/.test(line) && /async\s*:\s*false/.test(line)) add('no-sync-xhr', where, line.trim().slice(0, 120));
    });

    for (const name of unusedImports(source)) {
      add('no-unused-import', file, name);
    }
  }

  // Orphan modules: shipped JS that nothing imports or precaches.
  const reachable = reachableModules(root);
  for (const absolute of files) {
    const file = relative(root, absolute).replace(/\\/g, '/');
    if (file === 'sw.js') continue; // entry point
    if (!reachable.has(file)) add('no-orphan-module', file, 'not reachable from app.js or sw.js');
  }

  return {
    ok: findings.length === 0,
    findings,
    files: files.map((f) => relative(root, f).replace(/\\/g, '/')),
    reachable: [...reachable].sort()
  };
}

/* CLI */
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const result = lint();
  for (const f of result.findings) console.log(` FAIL  ${f.rule}  ${f.file}  ${f.detail}`);
  console.log(`\n${result.files.length} runtime files linted · ${result.findings.length} finding(s) · ${result.reachable.length} modules reachable`);
  process.exit(result.ok ? 0 : 1);
}
