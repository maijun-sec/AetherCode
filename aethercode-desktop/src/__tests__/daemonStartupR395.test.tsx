// Copyright (c) 2026 maijun
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

// @vitest-environment jsdom
import { describe, it, expect, afterEach } from 'vitest';
import { render, cleanup } from '@testing-library/react';
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { Welcome } from '../components/Welcome';
import { ReconnectBanner } from '../components/ReconnectBanner';
import { useStore } from '../store';
import { stripComments } from '../test-support/strip-comments';

/**
 * R395 (2026-11-23) — the daemon must start reliably, and when it
 * cannot, it must SAY WHY.
 *
 * Two symptoms, one root cause on each side.
 *
 * 1. Under load the daemon needs 20-35s to answer /healthz (JVM
 *    boot + engine assembly + the HTTP bind). The desktop gave it
 *    15s, so it KILLED a daemon that was two seconds away, the
 *    retry killed the next one, and the user saw nothing but
 *    "⚠ Disconnected" — no reason, no way to act.
 *
 * 2. The renderer's `initialize()` called `preWarmCwd()` on every
 *    launch. That called the Tauri command `pre_warm_daemon`, which
 *    R361 DELETED (the pre-warm design is gone: one daemon, and a
 *    cwd switch is a single `switchProject` RPC inside it). The
 *    call therefore always failed — and the failure was a
 *    `console.warn` nobody reads, so the dead branch survived
 *    untouched for dozens of rounds.
 *
 * The two fixes are different in kind, and that is the point:
 *   • the TIMEOUT is a value, and the value must be pinned against
 *     the requirement ("long enough for a slow start"), not
 *     against its own spelling;
 *   • the DEAD CALL is a contract, and the contract is between two
 *     files that can each drift — so it needs a CROSS-CHECK, not a
 *     string pin on one of them.
 *
 * Lesson: a fact that lives in TWO places needs a check that reads
 * BOTH. A pin on either one alone is satisfied by a rename.
 */

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');

function readSrc(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

/** every .ts / .tsx file under `src/`, EXCEPT the test files (a
 *  test may legitimately invoke a command that does not exist — a
 *  mock). */
function rendererSources(): string[] {
  const out: string[] = [];
  const walk = (dir: string) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const p = join(dir, entry.name);
      if (entry.isDirectory()) {
        walk(p);
      } else if (/\.tsx?$/.test(entry.name)) {
        if (/\.test\.tsx?$/.test(entry.name)) continue;
        out.push(p);
      }
    }
  };
  walk(join(root, 'src'));
  return out;
}

/** every .rs file of the Tauri crate — the Rust side of the wire. */
function rustSources(): string[] {
  const dir = join(root, 'src-tauri', 'src');
  return readdirSync(dir)
    .filter((n) => n.endsWith('.rs'))
    .map((n) => join(dir, n));
}

/** the body of a Rust function, by walking braces from its opening
 *  one. (String literals are NOT skipped here — the functions we
 *  read have no braces inside literals, and a truncated body would
 *  make the assertions vacuous.) */
function rustFnBody(src: string, signature: string): string {
  const start = src.indexOf(signature);
  if (start < 0) return '';
  let depth = 0;
  let seen = false;
  for (let i = start; i < src.length; i++) {
    const ch = src[i];
    if (ch === '{') {
      depth++;
      seen = true;
    } else if (ch === '}') {
      depth--;
      if (seen && depth === 0) return src.slice(start, i + 1);
    }
  }
  return '';
}

describe('R395: the renderer only asks for commands that exist', () => {
  it('every invoke() in the shipped renderer has a Rust command behind it', () => {
    // the Rust commands: `#[tauri::command]` followed by `fn NAME(`
    const rustCommands = new Set<string>();
    const cmdRe = /#\[tauri::command\][\s\S]{0,400}?\bfn\s+([a-zA-Z0-9_]+)\s*\(/g;
    for (const file of rustSources()) {
      const libRs = readFileSync(file, 'utf-8');
      let m: RegExpExecArray | null;
      while ((m = cmdRe.exec(libRs)) !== null) rustCommands.add(m[1]);
    }
    expect(
      rustCommands.size,
      'the Rust side must expose its commands — the scan found none, so the cross-check would be vacuous',
    ).toBeGreaterThan(5);

    const orphans: string[] = [];
    for (const file of rendererSources()) {
      const src = readFileSync(file, 'utf-8');
      const rel = file.replace(root + '\\', '').replace(root + '/', '');
      const invokeRe = /\binvoke(?:<[^>]*>)?\(\s*['"]([a-zA-Z0-9_]+)['"]/g;
      let im: RegExpExecArray | null;
      while ((im = invokeRe.exec(src)) !== null) {
        const cmd = im[1];
        if (!rustCommands.has(cmd)) orphans.push(`${rel}: invoke('${cmd}')`);
      }
    }
    expect(
      orphans,
      'the renderer invokes commands the Rust side does not have — each one is a dead call that fails at runtime and is invisible unless it is logged',
    ).toEqual([]);
  });

  it('the pre-warm surface is gone from the renderer (R361 retired it; R395 removed the corpse)', () => {
    // R395: the pins look at CODE. The comment that explains WHY
    // the API is gone is the most useful line in the file, and a pin
    // that trips on it forces the explanation to be deleted (or
    // hidden) to keep the gate green.
    for (const rel of [
      'src/store/index.ts',
      'src/components/StatusBar.tsx',
      'src/lib/methods.ts',
    ]) {
      const code = stripComments(readSrc(rel), 'ts');
      expect(code, `${rel} must not carry the retired pre-warm API`).not.toMatch(
        /\bpreWarm\b|\bpreWarmCwd\b|\bdiscardPreWarm\b|\bPreWarmInfo\b/,
      );
    }
    // and no `invoke` of the deleted Rust commands anywhere in the renderer
    const invokeRe = /\binvoke(?:<[^>]*>)?\(\s*['"](pre_warm_daemon|discard_pre_warm)['"]/g;
    for (const file of rendererSources()) {
      expect(readFileSync(file, 'utf-8')).not.toMatch(invokeRe);
    }
  });
});

describe('R395: the daemon gets long enough to start, and says why when it does not', () => {
  const rustCode = () => stripComments(readSrc('src-tauri/src/lib.rs'), 'rust');

  it('the health window outlasts a slow start on a loaded machine', () => {
    const m = rustCode().match(/const\s+DAEMON_HEALTH_TIMEOUT_MS\s*:\s*u64\s*=\s*([0-9_]+)\s*;/);
    expect(m, 'the health-window constant must exist').toBeTruthy();
    const ms = Number((m![1] ?? '').replace(/_/g, ''));
    // The requirement, not the spelling: a daemon on a loaded box
    // needs 20-35s. Pin the FLOOR so a future round may widen it.
    expect(
      ms,
      `the daemon health window must outlast a slow start (got ${ms}ms; the old 15s killed daemons that were about to answer)`,
    ).toBeGreaterThanOrEqual(30_000);
  });

  it('the window the spawn actually waits is the configurable one', () => {
    const code = rustCode();
    const accessor = rustFnBody(code, 'fn daemon_health_timeout_ms()');
    expect(accessor, 'the env-aware accessor must exist').not.toBe('');
    expect(
      accessor,
      'the accessor must read the env override and fall back to the constant',
    ).toMatch(/AETHERCODE_DAEMON_HEALTH_TIMEOUT_MS/);
    expect(accessor).toMatch(/DAEMON_HEALTH_TIMEOUT_MS/);

    const spawn = rustFnBody(code, 'async fn spawn_daemon_on_port(');
    expect(spawn, 'spawn_daemon_on_port must exist').not.toBe('');
    expect(
      spawn,
      'the spawn must wait on the accessor, not on the raw constant — otherwise the override is dead',
    ).toMatch(/daemon_health_timeout_ms\(\)/);
  });

  it('the failure message names the port, the reason and the log', () => {
    const code = rustCode();

    const builder = rustFnBody(code, 'fn daemon_start_failure(');
    expect(builder, 'the message builder must exist').not.toBe('');
    expect(builder, 'the message must quote the daemon\'s own log').toMatch(/daemon_log_tail/);
    expect(builder, 'the message must name the log file').toMatch(/log_path/);

    // every failure mode routes through it
    const spawn = rustFnBody(code, 'async fn spawn_daemon_on_port(');
    expect(spawn, 'spawn_daemon_on_port must exist').not.toBe('');
    expect(spawn, 'the spawn must use the builder').toMatch(/daemon_start_failure/);
    expect(
      spawn.match(/daemon_start_failure\(/g)?.length ?? 0,
      'the spawn has three ways to fail (exited at once / crashed / never answered) — each must be explained',
    ).toBeGreaterThanOrEqual(3);
    // The old opaque string must be gone from the code that produces
    // the message — "never came up" told the user nothing.
    expect(
      spawn,
      'the old opaque string must be gone — "never came up" told the user nothing',
    ).not.toMatch(/never came up/);
  });
});

describe('R395: the user can READ what the daemon said', () => {
  afterEach(() => cleanup());

  const MULTILINE =
    'daemon did not start on port 22888: it did not answer /healthz within 60s, so it was stopped\n' +
    'Its log (C:\\Users\\maijun\\AppData\\Local\\Temp\\aethercode-daemon-port22888.log) says:\n' +
    '  ERROR java.lang.OutOfMemoryError: Java heap space';

  it('the error panel shows EVERY line of the message', () => {
    const { container } = render(<Welcome error={MULTILINE} />);
    const text = container.textContent ?? '';
    expect(text).toContain('it did not answer /healthz within 60s');
    expect(text).toContain('aethercode-daemon-port22888.log');
    expect(text).toContain('OutOfMemoryError');
  });

  it('the line breaks survive — the message is not collapsed into one paragraph', () => {
    // The invariant is "the user can tell the three parts apart".
    // A <p> without pre-wrap collapses them into one run-on line,
    // which is exactly what the user saw before.
    const css = readSrc('src/components/Welcome.css');
    const rule = css.match(/\.welcome-error-msg\s*\{[^}]*\}/)?.[0] ?? '';
    expect(rule, 'the error-message rule must exist').not.toBe('');
    expect(
      rule,
      'the error message must keep its line breaks (white-space: pre-wrap)',
    ).toMatch(/white-space\s*:\s*pre-wrap/);
  });

  it('the one-line banner shows the HEADLINE only', () => {
    useStore.setState({
      connectionState: 'error',
      reconnectAttempts: 3,
      initError: MULTILINE,
    } as never);
    const { container } = render(<ReconnectBanner />);
    const detail = container.querySelector('.reconnect-banner-detail')?.textContent ?? '';
    expect(detail).toContain('it did not answer /healthz within 60s');
    expect(
      detail,
      'the banner is a one-line strip — the log excerpt belongs in the error panel',
    ).not.toContain('OutOfMemoryError');
  });
});
