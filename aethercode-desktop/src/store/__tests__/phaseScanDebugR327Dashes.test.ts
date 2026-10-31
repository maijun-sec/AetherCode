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
import { describe, it, expect } from 'vitest';

/**
 * R327 — the chip-state scan in MessageList uses a regex to
 * detect the agent's pause message ("第 N 阶段完成 — <title>").
 * The original regex required an em-dash (—, U+2014); if the
 * agent emitted an en-dash (–, U+2013) or hyphen-minus (-)
 * instead, the match would silently fail and the chip would
 * never flip to pending-confirm — leaving the user staring
 * at a chip that's "done" but no ABDE buttons appearing.
 *
 * The fix widened the dash character class to accept all
 * three. These tests pin the new behaviour so any future
 * regression is caught immediately.
 */
describe('R327 widened dash regex', () => {
  const doneRe = /第\s*(\d+)\s*阶段完成\s*[—–-]\s*([^\n\r]+)/;

  it('matches em-dash (U+2014) — original behaviour', () => {
    const m = '✅ 第 2 阶段完成 — 需求分析'.match(doneRe);
    expect(m?.[1]).toBe('2');
    expect(m?.[2]?.trim()).toBe('需求分析');
  });

  it('matches en-dash (U+2013)', () => {
    const m = '✅ 第 2 阶段完成 – 需求分析'.match(doneRe);
    expect(m?.[1]).toBe('2');
    expect(m?.[2]?.trim()).toBe('需求分析');
  });

  it('matches ASCII hyphen-minus (-)', () => {
    const m = '✅ 第 2 阶段完成 - 需求分析'.match(doneRe);
    expect(m?.[1]).toBe('2');
    expect(m?.[2]?.trim()).toBe('需求分析');
  });

  it('matches no-space variant "第 2 阶段完成—需求分析"', () => {
    const m = '第 2 阶段完成—需求分析'.match(doneRe);
    expect(m?.[1]).toBe('2');
    expect(m?.[2]?.trim()).toBe('需求分析');
  });

  it('matches no-space-around-num variant "第2阶段完成 — 需求分析"', () => {
    const m = '第2阶段完成 — 需求分析'.match(doneRe);
    expect(m?.[1]).toBe('2');
    expect(m?.[2]?.trim()).toBe('需求分析');
  });
});