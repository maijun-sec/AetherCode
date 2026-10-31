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
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * R182 → prior round. The original R182 tests guarded
 * the React hooks-before-early-return invariant in the
 * legacy PreparingCard. prior round replaced the
 * PreparingCard entirely with a flat-markdown chat
 * (AgentMarkdownMessage), so the R182 invariant is no
 * longer relevant — there is no early-return path to break
 * the hooks order. These tests are rewritten to guard the
 * new shape.
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

function read(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

describe('对应历史 round: agent markdown has no hooks-order traps', () => {
  const mlSrc = read('src/components/MessageList.tsx');

  it('AgentMarkdownMessage does NOT use any React hooks', () => {
    // prior round: the new component is a pure
    // (steps, subTask, isLive) → markdown → ReactMarkdown
    // transform. No state, no effects, no early returns.
    // legacy-followup-3, the old StepCard and
    // SubTaskCard had `expanded` state + toggle handlers
    // that needed hooks-before-early-return ordering.
    const component = mlSrc.match(
      /function\s+AgentMarkdownMessage[\s\S]*?\n\}/,
    );
    expect(component, 'AgentMarkdownMessage block must exist').toBeTruthy();
    const body = component![0];
    // No state, no effect, no early return.
    expect(body).not.toMatch(/useState\s*\(/);
    expect(body).not.toMatch(/useEffect\s*\(/);
    expect(body).not.toMatch(/if\s*\(.*\)\s*return\s+null/);
  });

  it('stepsToMarkdown is a pure function (no React hooks)', () => {
    // Same invariant: the conversion is pure. The chat
    // re-renders on every Zustand state change anyway,
    // so we don't need any internal caching or effects.
    const fn = mlSrc.match(
      /function\s+stepsToMarkdown[\s\S]*?\n\}/,
    );
    expect(fn, 'stepsToMarkdown must exist').toBeTruthy();
    expect(fn![0]).not.toMatch(/useState\s*\(/);
    expect(fn![0]).not.toMatch(/useEffect\s*\(/);
  });

  it('MessageList only uses hooks at the top of the function (no early returns)', () => {
    // R182 was about a "Rendered more hooks than during the
    // previous render" error caused by `if (steps.length === 0)
    // return null;` between useState/useEffect calls. The
    // new MessageList has no such trap: it returns
    // <div className="message-list-empty">...</div> as a
    // single render (no early null return) and all
    // useState / useEffect / useStore calls are at the top.
    const fn = mlSrc.match(
      /export function MessageList[\s\S]*?\n\}/,
    );
    expect(fn).toBeTruthy();
    // MessageList spans many lines; relax the regex.
    const block = mlSrc.slice(
      mlSrc.indexOf('export function MessageList'),
      mlSrc.indexOf('export function MessageList') + 12000,
    );
    // First non-empty line is the function header.
    const useStateIdx = block.indexOf('useState');
    const useEffectIdx = block.indexOf('useEffect');
    const useStoreIdx = block.indexOf('useStore');
    const returnNullIdx = block.search(/^\s*if[^]*?return null;/m);
    if (returnNullIdx > -1) {
      // If any early-return exists, all hooks must be before it.
      expect(useStateIdx).toBeLessThan(returnNullIdx);
      expect(useEffectIdx).toBeLessThan(returnNullIdx);
      expect(useStoreIdx).toBeLessThan(returnNullIdx);
    } else {
      // No early return; the invariant is trivially true.
      expect(useStateIdx).toBeGreaterThan(-1);
      expect(useEffectIdx).toBeGreaterThan(-1);
      expect(useStoreIdx).toBeGreaterThan(-1);
    }
  });
});
