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
 * R714: the `await_user_decision` stream-event handler in the
 * store must populate `awaitingUserDecision.options` from the
 * event payload, with a hard fallback to
 * {@code DEFAULT_DECISION_OPTIONS} when the field is missing or
 * empty.
 *
 * <p>Why the fallback matters: the original banner only showed
 * a short {@code summary} string and three buttons
 * (Continue / Change / Abort), which left the user with no
 * idea what to do when the per-todo controller paused the run.
 * The fix is two-layered:
 * <ol>
 *   <li>The engine now sends a structured {@code options} array
 *       (A/B/C/D menu) on the wire — see
 *       {@code StreamEvent.AwaitUserDecision.options} in
 *       {@code aethercode-core}.</li>
 *   <li>The store reads {@code options} from the event and
 *       stashes it on {@code awaitingUserDecision.options} so
 *       the banner can render clickable cards.</li>
 * </ol>
 *
 * <p>Without the fallback (item 2's last branch), an older
 * engine build (or a mid-deploy wire mismatch) would leave
 * {@code awaitingUserDecision.options} undefined and the
 * banner would render zero option cards — same symptom R714 is
 * trying to fix. So this test pins the fallback contract.
 *
 * <p>Source-pin pattern (matches sideNoteR208.test.ts): the
 * handler is inlined inside a giant switch, so we read the
 * source file and assert structural invariants.
 */
const storeSrc = readFileSync(
  join(__dirname, '..', 'store', 'index.ts'),
  'utf-8',
);

describe("R714: await_user_decision handler populates options with fallback", () => {
  it("the case body assigns options: from the event payload", () => {
    // Find the `case 'await_user_decision':` block. The
    // case body must contain `options:` to push the
    // structured menu into state — without that line, the
    // banner has nothing to render.
    const start = storeSrc.indexOf("case 'await_user_decision':");
    expect(start).toBeGreaterThan(0);
    const caseEndMatch = storeSrc
      .slice(start)
      .match(/\r?\n\s+break;\r?\n\s+\}\r?\n/);
    expect(caseEndMatch, 'case await_user_decision must end with break; }').toBeTruthy();
    const caseEnd = start + caseEndMatch!.index! + caseEndMatch![0].length;
    const caseBody = storeSrc.slice(start, caseEnd);
    expect(caseBody).toMatch(/options\s*:/);
    // Reads the wire field by name (`(ev as any).options`).
    // This is the source-of-truth handoff from the engine
    // to the renderer.
    expect(caseBody).toMatch(/\(ev as any\)\.options/);
  });

  it("the fallback path uses DEFAULT_DECISION_OPTIONS when options is missing/empty", () => {
    // The contract: if `(ev as any).options` is undefined or
    // an empty array, the store fills in DEFAULT_DECISION_OPTIONS
    // so the banner still renders 4 clickable cards. Pre-R714
    // builds (or any wire mismatch) MUST degrade to the same
    // menu the user sees on a healthy wire — never to zero
    // cards.
    const start = storeSrc.indexOf("case 'await_user_decision':");
    const caseEndMatch = storeSrc
      .slice(start)
      .match(/\r?\n\s+break;\r?\n\s+\}\r?\n/);
    const caseEnd = start + caseEndMatch!.index! + caseEndMatch![0].length;
    const caseBody = storeSrc.slice(start, caseEnd);
    expect(caseBody).toContain('DEFAULT_DECISION_OPTIONS');
    // The fallback is an Array.isArray / length check on
    // the wire field. Pin the shape so a refactor that
    // removes the empty-array branch (e.g. truthiness-only)
    // fails here — we want an empty array to also fall
    // back, otherwise the banner renders zero cards.
    expect(caseBody).toMatch(/Array\.isArray/);
    expect(caseBody).toMatch(/length\s*>\s*0/);
  });

  it("DEFAULT_DECISION_OPTIONS has exactly 4 options with letters A/B/C/D", () => {
    // Pin the menu shape — letters are part of the wire
    // contract with the LLM (the model expects to see
    // "(A)" / "(B)" / "(C)" / "(D)" in user replies), so a
    // rename here would invalidate every prior transcript
    // that references those letters. Keep the menu
    // symmetric (4 options, A..D) unless you have a
    // versioned migration plan.
    const idx = storeSrc.indexOf('DEFAULT_DECISION_OPTIONS');
    expect(idx).toBeGreaterThan(0);
    const declStart = storeSrc.lastIndexOf('export const', idx);
    const declEnd = storeSrc.indexOf('];', idx);
    const decl = storeSrc.slice(declStart, declEnd + 2);
    expect(decl).toContain("letter: 'A'");
    expect(decl).toContain("letter: 'B'");
    expect(decl).toContain("letter: 'C'");
    expect(decl).toContain("letter: 'D'");
    // Count occurrences of `letter: 'X'` to assert exactly 4.
    const letterMatches = decl.match(/letter:\s*'[A-D]'/g);
    expect(letterMatches).not.toBeNull();
    expect(letterMatches!.length).toBe(4);
  });

  it("AwaitingDecisionBanner renders options via awaiting.options.map", () => {
    // The banner must iterate `awaiting.options` and emit
    // one `awaiting-option-card` button per option. Pin the
    // shape so a refactor that drops the map (e.g. renders
    // only the legacy 3 buttons) fails here.
    const bannerSrc = readFileSync(
      join(__dirname, '..', 'components', 'AwaitingDecisionBanner.tsx'),
      'utf-8',
    );
    expect(bannerSrc).toContain("awaiting.options.map");
    expect(bannerSrc).toContain('awaiting-option-card');
    expect(bannerSrc).toContain('awaiting-option-letter');
    expect(bannerSrc).toContain('awaiting-option-label');
    // Each option must be clickable and inject its letter
    // + description into the input box.
    expect(bannerSrc).toMatch(/onClick=\{[\s\S]*?onPick/);
    expect(bannerSrc).toContain('Pick (${opt.letter})');
  });
});
