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

import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

/**
 * R416 — the loop warning must REACH the user.
 *
 * <p>★ WHAT WAS BROKEN ★ the engine's loop detector emits its warnings
 * with the kind `loop_warn_<tier>` (underscores — see
 * `ProgressLoopDetector.tiered`), and R416 adds `loop_warn_stall` for
 * the new stall pattern. The desktop's `side_note` handler looked for
 * `loop-warn-1` / `loop-warn-2` — DASHES — so it matched nothing the
 * engine has ever sent. The consequence: the LoopGuardBanner could
 * never mount, the warning fell into the R208 fallback (which pushes
 * nothing to the transcript), and the user was never told that the
 * agent was looping.
 *
 * <p>★ WHY A CROSS-LAYER TEST ★ the two spellings live in two
 * languages, in two repositories-worth of source. A test on either side
 * alone proves nothing: the desktop's own test could pin its own
 * (wrong) spelling and stay green. This test reads the ENGINE's
 * source, extracts the kinds it can actually emit, and asserts that
 * the desktop's arm accepts every one of them.
 *
 * <p>★ THE MUTATION THIS WITNESS CATCHES ★
 * <ul>
 *   <li>desktop prefix `loop_warn_` → `loop-warn_` ⇒ red (the engine's
 *       kinds no longer start with it);</li>
 *   <li>engine prefix `loop_warn_` → `loop-warn_` ⇒ red (the desktop's
 *       prefix no longer covers it).</li>
 * </ul>
 * Both are the original defect, reintroduced.
 */
const repoRoot = join(__dirname, '..', '..', '..');
const javaSrc = readFileSync(
  join(
    repoRoot,
    'aethercode',
    'aethercode-core',
    'src',
    'main',
    'java',
    'org',
    'aethercode',
    'core',
    'engine',
    'ProgressLoopDetector.java',
  ),
  'utf-8',
);
const storeSrc = readFileSync(join(__dirname, 'index.ts'), 'utf-8');

/** The kinds the engine's detector can emit for a loop WARNING. */
function engineLoopWarningKinds(): string[] {
  const kinds: string[] = [];
  // (1) the tiered ladder: "loop_warn_" + currentTier
  const ladder = javaSrc.match(/"(loop_warn_)"\s*\+/);
  expect(ladder, 'the detector must build its warning kind from a "loop_warn_" prefix').toBeTruthy();
  // the ladder can reach any tier, so the interesting kinds are the
  // two the engine warns at (WARN_BEFORE_STOP is 2).
  kinds.push(`${ladder![1]}1`, `${ladder![1]}2`);
  // (2) R416's stall warning — an explicit literal.
  const stall = javaSrc.match(/"(loop_warn_[a-z_]+)"/);
  expect(stall, 'the stall warning kind must be an explicit literal in the detector').toBeTruthy();
  kinds.push(stall![1]);
  return kinds;
}

/** The prefix the desktop's side_note arm matches on. */
function desktopLoopWarningPrefix(): string {
  const arm = storeSrc.match(/kind\.startsWith\('([^']+)'\)/);
  expect(
    arm,
    "the desktop's side_note arm must key on the engine's prefix — " +
      'if it matches nothing, the warning is silently dropped',
  ).toBeTruthy();
  return arm![1];
}

describe('R416: the loop warning reaches the user', () => {
  it('the desktop accepts EVERY loop-warning kind the engine can emit', () => {
    const kinds = engineLoopWarningKinds();
    const prefix = desktopLoopWarningPrefix();
    for (const kind of kinds) {
      expect(
        kind.startsWith(prefix),
        `the engine emits "${kind}" but the desktop only accepts kinds starting with ` +
          `"${prefix}" — the LoopGuardBanner would never mount`,
      ).toBe(true);
    }
  });

  it('the engine and the desktop agree on the spelling (one vocabulary)', () => {
    const prefix = desktopLoopWarningPrefix();
    expect(
      prefix,
      "the desktop's prefix must be the engine's own prefix — a second spelling is a " +
        'second copy of the truth, and the copies drift',
    ).toBe('loop_warn_');
  });

  it('the arm is reachable: the store really has a loop-warning branch', () => {
    const start = storeSrc.indexOf("case 'side_note':");
    expect(start).toBeGreaterThan(0);
    const window = storeSrc.slice(start, start + 25000);
    expect(window).toMatch(/kind\.startsWith\('loop_warn_'\)/);
    // and the banner state it feeds must still exist
    expect(storeSrc).toContain('loopWarn');
  });
});
