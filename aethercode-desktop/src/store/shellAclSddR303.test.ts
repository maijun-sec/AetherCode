// @vitest-environment jsdom
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * R303 (2026-09-21) — `TauriSsdDriver.start()` calls the
 * shell plugin's `Command.spawn()` to launch the daemon
 * JVM subprocess. In Tauri 2.x every plugin command is
 * gated by an ACL (`capabilities/*.json`) — the default
 * capability pre-R303 only allowed `dialog` plus the
 * `core:*` defaults, so `Command.spawn()` rejected with
 * `Command plugin:shell|spawn not allowed by ACL`.
 *
 * The desktop chip strip still rendered the
 * `TauriSsdDriver` branch (R302 fix landed — store had a
 * real jarPath + cwd from `get_app_paths`), but the
 * subprocess never spawned. The raw Tauri error string
 * was dumped into a `SDD spawn failed` system card,
 * reading like a Windows event log entry — accurate but
 * unfriendly.
 *
 * <p>Two-part fix:
 * <ol>
 *   <li><b>capabilities/default.json</b>: add
 *       <code>shell:default</code>,
 *       <code>shell:allow-spawn</code>,
 *       <code>shell:allow-stdin-write</code>,
 *       <code>shell:allow-kill</code>,
 *       <code>shell:allow-execute</code> so the
 *       TauriSsdDriver can spawn the JVM, write to its
 *       stdin (per-phase accept/revise), and kill it on
 *       stop. Without these the desktop can't drive an
 *       SDD run.</li>
 *   <li><b>friendlySsdSpawnError</b> helper at module
 *       scope in <code>src/store/index.ts</code>:
 *       translates the raw Tauri errors into actionable
 *       Chinese user-facing messages. Covers 4 cases:
 *       ACL denial, java missing, jar missing, NEEDS_CWD.
 *       The catch handler always also emits
 *       <code>[R303-sdd-spawn-failed]</code> to
 *       <code>%TEMP%\\aethercode-desktop-daemon-info.log</code>
 *       so future regressions keep a paper trail.</li>
 * </ol>
 *
 * <p>This file source-pins both fixes. A runtime test
 * would need a full Tauri runtime to exercise
 * `Command.spawn()` ACL enforcement; source-pinning the
 * permission list + the friendly-translator function
 * presence is sufficient to catch regressions.
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

function readSrc(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

describe('R303: Tauri shell ACL for TauriSsdDriver + friendly spawn-error translation', () => {
  const capabilities = JSON.parse(readSrc('src-tauri/capabilities/default.json'));
  const storeSrc = readSrc('src/store/index.ts');

  it('capabilities/default.json grants shell:allow-spawn for TauriSsdDriver', () => {
    expect(Array.isArray(capabilities.permissions), 'permissions must be an array').toBe(true);
    expect(capabilities.permissions).toContain('shell:default');
    expect(capabilities.permissions).toContain('shell:allow-spawn');
  });

  it('capabilities/default.json grants shell:allow-stdin-write for per-phase accept/revise', () => {
    expect(capabilities.permissions).toContain('shell:allow-stdin-write');
  });

  it('capabilities/default.json grants shell:allow-kill + shell:allow-execute', () => {
    expect(capabilities.permissions).toContain('shell:allow-kill');
    expect(capabilities.permissions).toContain('shell:allow-execute');
  });

  it('src/store/index.ts defines a friendlySddSpawnError translator', () => {
    // The helper must exist at module scope (so the
    // catch handler can call it without polluting the
    // store closure). Look for the function declaration.
    // Note: the function name uses double 'd' (Sdd, matching
    // the SDD = Spec-Driven Development project abbreviation).
    expect(storeSrc).toMatch(/function\s+friendlySddSpawnError\s*\(/);
    // The helper must handle the ACL case explicitly —
    // without that case the user still sees the raw
    // "not allowed by ACL" message after R303 ships.
    const fnBlock = storeSrc.slice(
      storeSrc.indexOf('function friendlySddSpawnError'),
      storeSrc.indexOf('function friendlySddSpawnError') + 4000,
    );
    expect(fnBlock).toMatch(/not allowed by ACL/i);
    expect(fnBlock).toMatch(/ENOENT|No such file or directory/i);
    expect(fnBlock).toMatch(/NEEDS_CWD/i);
  });

  it('startSsdFlow catch handler calls friendlySddSpawnError + logs R303-sdd-spawn-failed', () => {
    // The R303 catch block must:
    //   1. Build a `friendly` string via the translator
    //   2. Append a <details> Raw error block
    //   3. Log to %TEMP%\aethercode-desktop-daemon-info.log
    //      with the [R303-sdd-spawn-failed] prefix
    const catchMarker = 'Promise.resolve(driver.start()).catch(async (err: unknown) => {';
    const catchIdx = storeSrc.indexOf(catchMarker);
    expect(catchIdx, 'startSsdFlow catch handler must exist').toBeGreaterThan(0);
    const catchBlock = storeSrc.slice(catchIdx, catchIdx + 3000);
    expect(catchBlock).toContain('friendlySddSpawnError(');
    expect(catchBlock).toContain('<details>');
    expect(catchBlock).toContain('R303-sdd-spawn-failed');
    expect(catchBlock).toContain('append_text_file');
  });

  it('R303 friendly translator mentions R303 capability change in the user message', () => {
    // The user-facing ACL message should point at the
    // build that added the permissions so a confused
    // user knows whether they're already on the fixed
    // build or still on the old one.
    const fnBlock = storeSrc.slice(
      storeSrc.indexOf('function friendlySddSpawnError'),
      storeSrc.indexOf('function friendlySddSpawnError') + 4000,
    );
    expect(fnBlock).toMatch(/shell:allow-(spawn|stdin-write|kill|execute)/);
    expect(fnBlock).toMatch(/R303/);
  });
});