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
 * R417: the TURN BUDGET must be reachable from the desktop, and the
 * verdict that says a turn hit it must REACH the user.
 *
 * <p>★ THE KNOB THAT WAS OUT OF REACH ★ R416's live matrix measured a
 * turn whose work was done and which kept running for another nine
 * minutes (104 model calls, every one of them different). The turn-COUNT
 * budget cannot catch that, and R416's handover found the reason the user
 * could not bound it either: the daemon published `maxTurns` READ-ONLY,
 * and the desktop launches the daemon with a fixed command line — so the
 * one knob that stops a runaway turn was reachable from the CLI and from
 * nowhere else.
 *
 * <p>⇒ R417 adds `setTurnBudget` (both axes) and puts it in the Settings
 * panel.
 *
 * <p>★ AND THE VERDICT MUST BE VISIBLE ★ the desktop's `case 'side_note'`
 * deliberately pushes NOTHING for unknown kinds (R208: an engine control
 * signal must not become a chat line). A new kind that nobody wires up is
 * invisible — the turn would stop, and the user would read a truncated
 * answer as the whole thing.
 *
 * <p>Test approach: source-pin, like `consistencySideNoteR415.test.ts` —
 * the handler lives inlined in a giant switch, and the store is not
 * constructible in a unit test.
 */
const root = join(__dirname, '..');
const storeSrc = readFileSync(join(__dirname, 'index.ts'), 'utf-8');
const methodsSrc = readFileSync(join(root, 'lib', 'methods.ts'), 'utf-8');
const panelSrc = readFileSync(join(root, 'components', 'SettingsPanel.tsx'), 'utf-8');

/** The body of the `else if` arm that mentions `kind`, walked by braces. */
function armBodyFor(kind: string): string {
  const start = storeSrc.indexOf("case 'side_note':");
  expect(start).toBeGreaterThan(0);
  const window = storeSrc.slice(start, start + 25000);
  const idx = window.indexOf(`'${kind}'`);
  expect(idx, `the side_note handler must know the ${kind} kind`).toBeGreaterThan(0);
  const armStart = window.lastIndexOf('else if', idx);
  expect(armStart, `the ${kind} kind must have its OWN arm`).toBeGreaterThan(0);
  let depth = 0;
  let i = armStart;
  while (i < window.length) {
    const ch = window[i];
    if (ch === '{') depth++;
    else if (ch === '}') {
      depth--;
      if (depth === 0) {
        i++;
        break;
      }
    }
    i++;
  }
  return window.slice(armStart, i);
}

describe('R417: the max_duration verdict reaches the transcript', () => {
  it('the side_note handler has an explicit max_duration arm', () => {
    const arm = armBodyFor('max_duration');
    expect(arm).toContain("'max_duration'");
  });

  it('the max_duration arm pushes the message into the transcript', () => {
    const arm = armBodyFor('max_duration');
    expect(arm, 'the verdict must be visible in the chat').toMatch(/s\.messages/);
    expect(arm).toMatch(/content:\s*msg/);
  });

  it('the max_duration arm comes BEFORE the R208 fallback', () => {
    const start = storeSrc.indexOf("case 'side_note':");
    const window = storeSrc.slice(start, start + 25000);
    const idx = window.indexOf("'max_duration'");
    const fallbackIdx = window.lastIndexOf('} else {');
    expect(fallbackIdx).toBeGreaterThan(0);
    expect(idx, 'the arm must precede the fallback').toBeLessThan(fallbackIdx);
  });

  it('the desktop does NOT swallow the kind into the fallback', () => {
    // The fallback must not have grown a 'max_duration' case of its own
    // — a copy of the arm inside the fallback would be unreachable.
    const start = storeSrc.indexOf("case 'side_note':");
    const window = storeSrc.slice(start, start + 25000);
    const fallbackIdx = window.lastIndexOf('} else {');
    const fallback = window.slice(fallbackIdx);
    expect(fallback).not.toContain("'max_duration'");
  });
});

describe('R417: the turn budget knob is reachable from the desktop', () => {
  it('the rpc client declares setTurnBudget', () => {
    expect(methodsSrc).toMatch(/setTurnBudget\(opts:\s*\{[\s\S]*?maxTurnSeconds\?:\s*number/);
    expect(methodsSrc).toContain("return this.call('setTurnBudget', opts);");
  });

  it('the rpc client returns the effective values', () => {
    const block = methodsSrc.match(/setTurnBudget\(opts:[^)]*\)\s*:\s*Promise<\{[^}]*\}>/);
    expect(block).toBeTruthy();
    expect(block![0]).toContain('maxTurns');
    expect(block![0]).toContain('maxTurnSeconds');
    expect(block![0]).toContain('disabled');
  });

  it('EngineState declares BOTH axes', () => {
    // ★ THE DECLARATION WAS THE MISSING PIECE ★ both values were on the
    // wire (getState) and unreachable from TypeScript, so no renderer
    // could have shown them without changing the interface first.
    const iface = methodsSrc.slice(
      methodsSrc.indexOf('export interface EngineState'),
      methodsSrc.indexOf('export interface ToolInfo'),
    );
    expect(iface).toMatch(/maxTurns\?:\s*number/);
    expect(iface).toMatch(/maxTurnSeconds\?:\s*number/);
  });

  it('the store declares and implements a setTurnBudget action', () => {
    expect(storeSrc).toMatch(
      /setTurnBudget:\s*\(opts:\s*\{[\s\S]*?\}\s*\)\s*=>\s*Promise</,
    );
    const block = storeSrc.match(/setTurnBudget:\s*async\s*\([\s\S]*?^\s{4}\}/m);
    expect(block).toBeTruthy();
    expect(block![0]).toContain('rpc.setTurnBudget(opts)');
  });

  it('initialize() restores the budget the user set', () => {
    expect(storeSrc).toMatch(
      /if\s*\(prefs\.maxTurns !== undefined \|\| prefs\.maxTurnSeconds !== undefined\)/,
    );
    expect(storeSrc).toContain('await rpc.setTurnBudget({');
  });

  it('the Settings panel has the budget controls', () => {
    expect(panelSrc).toContain('Turn budget');
    expect(panelSrc).toContain('setTurnBudget');
    // The panel must be able to turn the limit OFF as well as on — a
    // bound the user cannot lift is a bound the user has to live with.
    expect(panelSrc).toMatch(/budgetTimeEnabled/);
    expect(panelSrc).toMatch(/budgetTurnsEnabled/);
  });

  it('the panel pushes ONLY the axes the user touched', () => {
    // ★ THE OVERWRITE HAZARD ★ the daemon may have been started with
    // `--max-turns 30`; a panel that pushed its own default on mount
    // would silently overwrite that.
    expect(panelSrc).toMatch(/budgetTimeDirty/);
    expect(panelSrc).toMatch(/budgetTurnsDirty/);
    expect(panelSrc).toMatch(/if \(budgetTimeDirty\) payload\.maxTurnSeconds/);
    expect(panelSrc).toMatch(/if \(budgetTurnsDirty\) payload\.maxTurns/);
  });
});
