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
 * R415: the engine's CONSISTENCY VERDICT must reach the user.
 *
 * <p>The daemon computes, from the tree, whether a turn changed every
 * copy of every declaration it touched, and puts the verdict on the wire
 * as a `side_note` of kind `consistency`.
 *
 * <p>★ AND THE R208 FALLBACK WOULD HAVE SWALLOWED IT ★ the desktop's
 * `case 'side_note'` deliberately pushes nothing for unknown kinds (an
 * engine control signal must not become a chat line). So a new kind
 * that nobody wires up is invisible — the verdict would be computed,
 * put on the wire, and dropped.
 *
 * <p>★ A VERDICT NOBODY CAN SEE DID NOT HAPPEN ★ this test pins the
 * explicit arm: the kind must have its OWN branch, and that branch must
 * put the message into the transcript.
 *
 * <p>Test approach: source-pin, like `sideNoteR208.test.ts` — the
 * handler lives inlined in a giant switch, and the store is not
 * constructible in a unit test.
 */
const storeSrc = readFileSync(
  join(__dirname, 'index.ts'),
  'utf-8',
);

describe('R415: the consistency verdict reaches the transcript', () => {
  it('the side_note handler has an explicit consistency arm', () => {
    const start = storeSrc.indexOf("case 'side_note':");
    expect(start).toBeGreaterThan(0);
    const window = storeSrc.slice(start, start + 25000);
    const idx = window.indexOf("'consistency'");
    expect(idx, 'the side_note handler must know the consistency kind').toBeGreaterThan(0);
    // It must be an explicit `else if` arm — NOT part of the fallback.
    const armStart = window.lastIndexOf('else if', idx);
    expect(armStart).toBeGreaterThan(0);
    const arm = window.slice(armStart, armStart + 3000);
    expect(arm).toContain("'consistency'");
  });

  it('the consistency arm pushes the message into the transcript', () => {
    const start = storeSrc.indexOf("case 'side_note':");
    const window = storeSrc.slice(start, start + 25000);
    const idx = window.indexOf("'consistency'");
    const armStart = window.lastIndexOf('else if', idx);
    // Walk braces to find the closing `}` of the arm.
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
    const armBody = window.slice(armStart, i);
    expect(armBody, 'the verdict must be visible in the chat').toMatch(/s\.messages/);
    expect(armBody).toMatch(/content:\s*msg/);
  });

  it('the consistency arm comes BEFORE the R208 fallback', () => {
    // If the arm sat after the fallback it would be unreachable, and
    // the verdict would be swallowed exactly as before.
    const start = storeSrc.indexOf("case 'side_note':");
    const window = storeSrc.slice(start, start + 25000);
    const consistencyIdx = window.indexOf("'consistency'");
    const fallbackIdx = window.lastIndexOf('} else {');
    expect(fallbackIdx).toBeGreaterThan(0);
    expect(consistencyIdx, 'the arm must precede the fallback').toBeLessThan(fallbackIdx);
  });
});
