// @vitest-environment jsdom
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * R276 desktop polish (2026-09-16): the user asked for a tighter fold
 * policy on the agent's think blocks in the chat panel.
 *
 *   "推荐在任务执行时，最近的一次的 思考 始终展开，当结束后，后面如果有新的 思考 或者
 *    tool 执行，就折叠，如果这是最后一次思考，就始终展开。"
 *
 * Translation:
 *   - While a task is running (isLive=true), the most recent think block
 *     is expanded so the user sees new thinking stream in without clicking.
 *   - After a task is done, history is collapsed: older thinks / tools
 *     default-fold so the chat panel doesn't drown in 50 short thinks.
 *   - The FINAL think (the last think block in the chat, regardless of
 *     position) is always expanded — it's the model's concluding
 *     reasoning before the answer, and the user wants to see it.
 *
 * This file pins the implementation: there's a `defaultOpenFor` helper
 * inside MessageList.tsx that computes the open/closed state per block
 * kind, and the BlockView's think branch now respects `defaultOpen` (no
 * more "<500 chars always open" historical heuristic).
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

function readSrc(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

describe('R276: think fold policy — final think open, history folded, live tail open', () => {
  it('AgentMarkdownMessage computes `finalThinkIdx` from the LAST think in `blocks`', () => {
    const src = readSrc('src/components/MessageList.tsx');
    // The new code path must walk blocks backward looking for `kind === 'think'`
    // and remember the last such index — that's the "final think" that the
    // user wants always-open.
    expect(
      src,
      'AgentMarkdownMessage must compute the last think index (final-think ' +
        'marker) so BlockView can keep it expanded regardless of isLive.',
    ).toMatch(/finalThinkIdx/);
    expect(
      src,
      'finalThinkIdx must be found by iterating backwards from the end ' +
        '(`for (let i = blocks.length - 1; i >= 0; i--)`).',
    ).toMatch(/for\s*\(\s*let\s+i\s*=\s*blocks\.length\s*-\s*1\s*;\s*i\s*>=\s*0\s*;\s*i--\s*\)/);
  });

  it('BlockView think branch must use `defaultOpen` prop, not a size heuristic', () => {
    const src = readSrc('src/components/MessageList.tsx');
    // Locate the think branch INSIDE BlockView, not inside the
    // defaultOpenFor helper (which also references 'kind === "think"'
    // but is conceptually unrelated). The BlockView starts with
    // `function BlockView({ block, defaultOpen })`; we slice from
    // there to `function AgentMarkdownMessage`.
    const bvStart = src.indexOf('function BlockView');
    const amStart = src.indexOf('function AgentMarkdownMessage');
    expect(bvStart, 'BlockView function must exist').toBeGreaterThan(0);
    expect(amStart, 'AgentMarkdownMessage function must exist').toBeGreaterThan(bvStart);
    const blockView = src.slice(bvStart, amStart);
    // Find the think branch inside BlockView.
    const thinkStart = blockView.indexOf('if (block.kind === \'think\')');
    const thinkEnd = blockView.indexOf('if (block.kind === \'tool\')');
    expect(thinkStart, 'think branch inside BlockView must exist').toBeGreaterThan(0);
    expect(thinkEnd, 'tool branch inside BlockView must exist').toBeGreaterThan(thinkStart);
    const thinkBranch = blockView.slice(thinkStart, thinkEnd);
    // No more size-based "always open if short" heuristic.
    expect(
      thinkBranch,
      'BlockView think branch must NOT keep the historical ' +
        '`if (!isLong) { return <details open ... }` short-circuit — that ' +
        'forced every short think to render open regardless of policy.',
    ).not.toMatch(/if\s*\(\s*!isLong\s*\)/);
    expect(
      thinkBranch,
      'BlockView think branch must NOT have a hardcoded `<details open ' +
        'className="agent-block agent-block-think">` — every think must use ' +
        'the `defaultOpen` prop now so the fold policy is uniform.',
    ).not.toMatch(/<details\s+open\s+className="agent-block agent-block-think">/);
    // The new branch renders `<details open={defaultOpen} ...>`, just like the tool branch.
    expect(
      thinkBranch,
      'BlockView think branch must use `<details open={defaultOpen} ...>` so ' +
        'the fold policy can decide per-block.',
    ).toMatch(/<details\s+open=\{defaultOpen\}\s+className="agent-block agent-block-think">/);
  });

  it('`defaultOpenFor` helper exists and folds non-final history by default', () => {
    const src = readSrc('src/components/MessageList.tsx');
    expect(
      src,
      'A `defaultOpenFor(block, opts)` helper must exist as the single ' +
        'source of truth for which block kinds default-open vs default-folded.',
    ).toMatch(/function\s+defaultOpenFor\s*\(/);
    expect(
      src,
      '`defaultOpenFor` must special-case header + result kinds to ' +
        'always-open (anchors).',
    ).toMatch(/defaultOpenFor[\s\S]*?kind\s*===\s*['"]header['"][\s\S]*?return\s+true/);
    expect(
      src,
      '`defaultOpenFor` must special-case `kind === "think"` with ' +
        '`isFinalThink` short-circuit (final think is always-open).',
    ).toMatch(/isFinalThink[\s\S]*?return\s+true/);
  });

  it('AgentMarkdownMessage must call defaultOpenFor per block (not the old `isLive ? isLast : true` literal)', () => {
    const src = readSrc('src/components/MessageList.tsx');
    // The historical inline defaultOpen was: `isLive ? isLast : true`. The new
    // code routes through `defaultOpenFor(b, { isLast, isFinalThink, isLive })`.
    // Slice from the AgentMarkdownMessage function start (so we don't accidentally
    // pick up defaultOpenFor's own defaultOpenFor-line which is unreachable code
    // that doesn't match anyway).
    const amStart = src.indexOf('function AgentMarkdownMessage');
    expect(amStart, 'AgentMarkdownMessage must exist').toBeGreaterThan(0);
    const slice = src.slice(amStart);
    expect(
      slice,
      'The old per-block literal `const defaultOpen = isLive ? isLast : true` ' +
        'must be gone — fold policy now lives in `defaultOpenFor` and is ' +
        'different per block kind.',
    ).not.toMatch(/const\s+defaultOpen\s*=\s*isLive\s*\?\s*isLast\s*:\s*true/);
    expect(
      slice,
      'AgentMarkdownMessage must call defaultOpenFor(b, { isLast, isFinalThink, isLive }).',
    ).toMatch(/defaultOpenFor\(\s*b\s*,\s*\{[\s\S]*?isLast[\s\S]*?isFinalThink[\s\S]*?isLive[\s\S]*?\}\s*\)/);
  });
});