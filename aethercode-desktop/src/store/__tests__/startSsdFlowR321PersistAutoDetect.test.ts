// @vitest-environment jsdom
import { describe, it, expect } from 'vitest';

/**
 * R321 — SDD toggle persistence (still active in R331).
 *
 * The original R321 fix had two parts:
 *   1. sddEnabled persistence to localStorage — STILL ACTIVE.
 *      setSddEnabled() writes prefs.sddEnabled; initialize()
 *      reads it back. Surviving desktop restarts.
 *   2. Auto-detect SDD intent in MessageInput (the regex
 *      branch) — SUPERSEDED by R331 mode picker. The user
 *      asked for 规范化执行 at session-creation time instead
 *      of inferring from chat input. See
 *      createNewSessionR331Mode.test.ts for the new contract.
 *
 * Source-pin style: read the store and assert the helper
 * branches + the persistence wiring.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const STORE_TS = join(process.cwd(), 'src', 'store', 'index.ts');
const INPUT_TS = join(process.cwd(), 'src', 'components', 'MessageInput.tsx');
const storeSource = readFileSync(STORE_TS, 'utf8');
const inputSource = readFileSync(INPUT_TS, 'utf8');

describe('R321 SDD toggle persistence (still active in R331)', () => {
  it('EnginePrefs schema adds sddEnabled?', () => {
    expect(storeSource).toMatch(/sddEnabled\?:\s*boolean;/);
  });

  it('setSddEnabled persists sddEnabled to enginePrefs', () => {
    expect(storeSource).toMatch(/writeEnginePrefs\(\{\s*\.\.\.prefs,\s*sddEnabled:\s*on\s*\}\)/);
  });

  it('initialize() reads prefs.sddEnabled and restores the toggle', () => {
    expect(storeSource).toMatch(/prefs\.sddEnabled[\s\S]*?set\(\{\s*sddEnabled:\s*prefs\.sddEnabled\s*\}\)/);
  });
});

describe('R321 SDD auto-detect — superseded by R331 mode picker', () => {
  it('MessageInput no longer has the sddIntentKeyword regex branch', () => {
    // R331: explicit mode picker at session creation
    // replaces the implicit chat-input regex. Verifying the
    // absence here pins the regression — if someone re-adds
    // the auto-detect branch, this test fails immediately.
    expect(inputSource).not.toMatch(/sddIntentKeyword/);
    expect(inputSource).not.toMatch(/!sdd\.sddEnabled\s*&&\s*!sdd\.sddActive\s*&&\s*sddIntentKeyword\.test/);
  });

  it('createNewSession opens the mode picker when called without mode', () => {
    // The new explicit-design entry point. Replaces the
    // implicit regex path entirely.
    expect(storeSource).toMatch(/createNewSession:\s*\(opts\)\s*=>\s*\{[\s\S]*?set\(\{\s*pendingNewSession:\s*\{\s*cwd:/);
  });
});