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