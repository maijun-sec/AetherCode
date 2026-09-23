// @vitest-environment jsdom
import { describe, it, expect } from 'vitest';

/**
 * R337 regression test: MessageInput's approve/skip regex
 * must route pure-Chinese keywords correctly.
 *
 * R322's regex used `\b`, which is ASCII-only (`\w` =
 * `[A-Za-z0-9_]`). When the user typed "继续" or
 * "继续下一阶段" (no trailing space), `\b` did NOT match
 * between Chinese characters and end-of-string. The user's
 * "继续" then fell through to the modify branch and the agent
 * received it as action=modify feedback instead of as
 * action=approve phase advance. The agent's own SKILL.md said
 * "按 SKILL.md 映射规则, 这是 approve 信号, 但 desktop 把
 * 它路由为 action=modify" — this is exactly the bug R337
 * fixes.
 *
 * Fix: drop the `\b` trailing boundary. Match any input that
 * STARTS with the keyword (the same R322 intent). This lets
 * the user type "继续" / "继续下一阶段" / "跳过需求澄清" and
 * have them route to approve / skip without typing ASCII.
 *
 * Trade-off: pure-Chinese feedback that starts with the
 * keyword (e.g. "继续看一下") will also route as approve.
 * The agent will advance and the user can correct. We
 * accept this trade-off — the alternative (strict delimiter
 * check) breaks too many valid skip-with-suffix inputs.
 */

// The same regex the MessageInput now uses. Keep in sync.
const APPROVE_HEAD_RE = /^(✅|继续下一阶段|继续|next|ok|advance|go)/i;
const APPROVE_EN_RE = /^(approve|advance|next|continue)/i;
const SKIP_HEAD_RE = /^(⏭️|跳过|skip)/i;
const SKIP_EN_RE = /^(skip|skip[- ]?next)/i;

function classify(intent: string): 'approve' | 'skip' | 'modify' {
  const t = intent.trim();
  if (!t) return 'modify';
  const lower = t.toLowerCase();
  if (APPROVE_HEAD_RE.test(t) || APPROVE_EN_RE.test(lower)) return 'approve';
  if (SKIP_HEAD_RE.test(t) || SKIP_EN_RE.test(lower)) return 'skip';
  return 'modify';
}

describe('R337 MessageInput approve/skip regex (pure-Chinese keywords)', () => {
  it('routes "继续下一阶段" as approve', () => {
    // The exact input the user typed in R336 retest. The
    // previous round routed this as modify because `\b`
    // failed between Chinese chars and end-of-string.
    expect(classify('继续下一阶段')).toBe('approve');
  });

  it('routes "继续" alone as approve', () => {
    expect(classify('继续')).toBe('approve');
  });

  it('routes "跳过" alone as skip', () => {
    expect(classify('跳过')).toBe('skip');
  });

  it('routes "跳过下一阶段" as skip', () => {
    expect(classify('跳过下一阶段')).toBe('skip');
  });

  it('routes "跳过需求澄清" as skip', () => {
    // R322 spec: accept any input starting with the skip
    // verb, even if followed by a phase title.
    expect(classify('跳过需求澄清')).toBe('skip');
  });

  it('routes ASCII keywords exactly as before', () => {
    expect(classify('ok')).toBe('approve');
    expect(classify('next')).toBe('approve');
    expect(classify('go')).toBe('approve');
    expect(classify('advance')).toBe('approve');
    expect(classify('approve')).toBe('approve');
    expect(classify('skip')).toBe('skip');
    expect(classify('skip next')).toBe('skip');
    expect(classify('skip-next')).toBe('skip');
  });

  it('routes "✅" (continue) as approve', () => {
    expect(classify('✅')).toBe('approve');
    expect(classify('⏭️')).toBe('skip');
  });

  it('routes "✅ ok" / "继续 next" / "继续 ok" as approve', () => {
    expect(classify('✅ ok')).toBe('approve');
    expect(classify('继续 next')).toBe('approve');
    expect(classify('继续, ok')).toBe('approve');
    expect(classify('继续。')).toBe('approve'); // Chinese period
    expect(classify('跳过 next')).toBe('skip');
  });

  it('does NOT route plain feedback as approve', () => {
    expect(classify('我觉得需求不够清晰')).toBe('modify');
    expect(classify('请改一下')).toBe('modify');
  });

  it('treats leading/trailing whitespace as inert', () => {
    expect(classify('  继续下一阶段  ')).toBe('approve');
    expect(classify('\t继续\n')).toBe('approve');
  });

  it('pin: source drops \\b trailing boundary (R322 design intent)', () => {
    // Source-pin test: ensure future edits don't reintroduce
    // the ASCII-only `\b` regression. We verify by reading
    // the MessageInput.tsx source.
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const { readFileSync } = require('node:fs');
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const { join } = require('node:path');
    const src = readFileSync(
      join(process.cwd(), 'src', 'components', 'MessageInput.tsx'),
      'utf8',
    );
    // The approve/skip lines must NOT use `\b` as a trailing
    // boundary (it was the bug). Search for the patterns
    // we replaced.
    expect(src).not.toMatch(/继续下一阶段\|继续\|next\|ok\|advance\|go\)\\b/);
    expect(src).not.toMatch(/跳过\|skip\)\\b/);
    // Verify the new prefix-only regex is present.
    expect(src).toMatch(/继续下一阶段\|继续\|next\|ok\|advance\|go\)\/i/);
    expect(src).toMatch(/跳过\|skip\)\/i/);
  });
});