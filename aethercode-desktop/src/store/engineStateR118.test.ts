import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * refreshEngineState store action + periodic timer.
 *
 * Mirrors the prior round pattern: an initialize()-time
 * snapshot was a recovery trap (any later failure left
 * the field permanently null). R118 adds:
 *   1. A `refreshEngineState()` action that re-fetches
 *      getState().
 *   2. A `engineStateRefreshedAt` field with the wall
 *      clock of the last successful refresh.
 *   3. A 15 s periodic timer that calls
 *      refreshEngineState() (guarded by connectionState).
 *   4. setModel / setPermissionMode / switchProvider
 *      kick a refresh after the optimistic local update
 *      so the canonical state lands without waiting for
 *      the next tick.
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

describe('R118: refreshEngineState action', () => {
  const storeSrc = readFileSync(join(root, 'src', 'store', 'index.ts'), 'utf-8');

  it('declares refreshEngineState in the AppState interface', () => {
    expect(storeSrc).toMatch(/refreshEngineState:\s*\(\)\s*=>\s*Promise<void>/);
  });

  it('declares engineStateRefreshedAt on the AppState interface', () => {
    expect(storeSrc).toMatch(/engineStateRefreshedAt:\s*number/);
  });

  it('initial state seeds engineStateRefreshedAt: 0', () => {
    expect(storeSrc).toMatch(/engineStateRefreshedAt:\s*0/);
  });

  it('refreshEngineState action exists in the implementation', () => {
    expect(storeSrc).toMatch(/refreshEngineState:\s*async\s*\(\)\s*=>\s*\{/);
  });

  it('refreshEngineState calls rpc.getState()', () => {
    const block = storeSrc.match(/refreshEngineState:\s*async\s*\(\)\s*=>\s*\{[\s\S]*?^\s{4}\}/m);
    expect(block).toBeTruthy();
    expect(block![0]).toContain('rpc.getState()');
  });

  it('refreshEngineState stamps Date.now() on success', () => {
    const block = storeSrc.match(/refreshEngineState:\s*async\s*\(\)\s*=>\s*\{[\s\S]*?^\s{4}\}/m);
    expect(block).toBeTruthy();
    expect(block![0]).toContain('engineStateRefreshedAt: Date.now()');
  });

  it('refreshEngineState preserves prior state on failure (no blanking)', () => {
    // legacy, a getState failure during initialize
    // silently left engineState as null forever. The
    // prior round lesson applied: the catch logs and
    // returns; no set() on the failure path.
    const block = storeSrc.match(/refreshEngineState:\s*async\s*\(\)\s*=>\s*\{[\s\S]*?^\s{4}\}/m);
    expect(block).toBeTruthy();
    // The block has a try/catch — the catch should NOT
    // call set() to clear the field. The whole catch
    // body is just `console.warn`.
    const catchBody = block![0].match(/catch\s*\([^)]*\)\s*\{[\s\S]*?\}/);
    expect(catchBody).toBeTruthy();
    expect(catchBody![0]).not.toMatch(/set\(\s*\{[^}]*engineState\s*:\s*null/);
  });

  it('initialize() seeds engineStateRefreshedAt from the boot snapshot', () => {
    const initBlock = storeSrc.match(/set\(\{[\s\S]*?engineState:\s*state,[\s\S]*?\}\);/);
    expect(initBlock).toBeTruthy();
    expect(initBlock![0]).toContain('engineStateRefreshedAt: Date.now()');
  });

  it('initialize() schedules a 15s periodic refresh', () => {
    expect(storeSrc).toContain('engineStateTimer');
    expect(storeSrc).toMatch(/engineStateTimer\s*=\s*window\.setInterval/);
    // 15_000ms = 15s (vs the 30s toolsTimer / 5s
    // skipStatsTimer). The interval is module-scope
    // and lives next to the other timers.
    expect(storeSrc).toMatch(/engineStateTimer[\s\S]*?15_000/);
  });
});

describe('R118: setModel / setPermissionMode / switchProvider wire refresh', () => {
  const storeSrc = readFileSync(join(root, 'src', 'store', 'index.ts'), 'utf-8');

  it('setModel kicks a refreshEngineState after the optimistic update', () => {
    // The R118 pattern: optimistic local set then a
    // canonical refresh so any other fields the daemon
    // adjusted server-side also land.
    const block = storeSrc.match(/setModel:\s*async\s*\(model:\s*string\)\s*=>\s*\{[\s\S]*?^\s{4}\}/m);
    expect(block).toBeTruthy();
    expect(block![0]).toContain('void get().refreshEngineState()');
  });

  it('setPermissionMode kicks a refreshEngineState', () => {
    const block = storeSrc.match(/setPermissionMode:\s*async\s*\(mode:\s*string\)\s*=>\s*\{[\s\S]*?^\s{4}\}/m);
    expect(block).toBeTruthy();
    expect(block![0]).toContain('void get().refreshEngineState()');
  });

  it('switchProvider routes through refreshEngineState (replaces inline getState)', () => {
    // legacy, switchProvider had its own inline
    // `rpc.getState()` + `set((cur) => ({ ...cur, engineState: s }))`
    // block. R118 replaces that with the unified
    // refreshEngineState call so the engineStateRefreshedAt
    // stamp is updated and the failure path matches.
    const block = storeSrc.match(/switchProvider:\s*async\s*\([^)]*\)\s*=>\s*\{[\s\S]*?^\s{4}\}/m);
    expect(block).toBeTruthy();
    expect(block![0]).toContain('await get().refreshEngineState()');
    // The old inline getState block should be gone.
    expect(block![0]).not.toMatch(/const\s+s\s*=\s*await\s*rpc\.getState\(\)/);
  });
});
