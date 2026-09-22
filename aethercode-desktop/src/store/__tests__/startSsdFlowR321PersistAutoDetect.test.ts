// @vitest-environment jsdom
import { describe, it, expect } from 'vitest';

/**
 * R321 — SDD toggle persistence + auto-detect.
 *
 * Two regressions the previous build (R319) had:
 *   1. sddEnabled was NOT persisted to localStorage. Every
 *      desktop restart flipped the toggle back to off, the
 *      user's intent bypassed startSsdFlow, and the agent
 *      fell back to its agentic-loop vibe-coding habit. The
 *      user retested in a fresh desktop session, saw the
 *      agent writing pom.xml + src/ straight off, and reported
 *      "this round is worse than the last one".
 *   2. There was no fallback for "user clearly wants SDD but
 *      the toggle is off". The toggle is a small button that
 *      the user has to remember to click before typing.
 *
 * Fix:
 *   1. setSddEnabled() now writes prefs.sddEnabled to
 *      localStorage; initialize() reads it back so the toggle
 *      survives desktop restart.
 *   2. MessageInput Enter handler now auto-detects SDD
 *      intent keywords ("SDD 流程" / "规格化" / "/sdd" /
 *      "spec-kit" / "spec-kit" / "用 SDD" / "跑规格化") and
 *      flips the toggle on automatically + routes through
 *      startSsdFlow. Auto-detect only fires when no run is
 *      in flight; mid-run input is still treated as phase
 *      feedback.
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

describe('R321 SDD toggle persistence', () => {
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

describe('R321 SDD auto-detect', () => {
  it('MessageInput defines sddIntentKeyword regex', () => {
    expect(inputSource).toMatch(/const sddIntentKeyword\s*=\s*\/\(/);
  });

  it('keyword regex matches "SDD 流程" / "规格化" / "spec-kit"', () => {
    expect(inputSource).toMatch(/SDD\|sdd\|规格化\|spec-kit\|speckit\|规格驱动/);
  });

  it('Enter handler auto-enables + routes to startSsdFlow when toggle off but keyword matches', () => {
    // The new branch sits between `if (sdd.sddEnabled && intent)`
    // and `sendMessage()`. We check the three behaviours:
    //   1. auto-enable: `sdd.setSddEnabled(true)`
    //   2. clear input box
    //   3. route via startSsdFlow
    expect(inputSource).toMatch(/if\s*\(\s*!sdd\.sddEnabled\s*&&\s*!sdd\.sddActive\s*&&\s*sddIntentKeyword\.test\(intent\)\)/);
    expect(inputSource).toMatch(/sdd\.setSddEnabled\(true\)/);
    expect(inputSource).toMatch(/sdd\.startSsdFlow\(intent\)/);
  });

  it('does NOT auto-enable when a run is already active', () => {
    // The branch guard includes `!sdd.sddActive` — mid-run
    // input stays as phase feedback.
    expect(inputSource).toMatch(/!sdd\.sddEnabled\s*&&\s*!sdd\.sddActive/);
  });
});