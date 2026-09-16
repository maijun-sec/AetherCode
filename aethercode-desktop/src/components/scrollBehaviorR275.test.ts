// @vitest-environment jsdom
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * R275 desktop polish (2026-09-16): fix the chat scroll
 * "stuck at the top" + "89020 new" runaway-counter.
 *
 * <p>Two bugs that share a useEffect in MessageList.tsx (the
 * auto-scroll / unseen-count bookkeeping):
 *
 * <ol>
 *   <li>Initial auto-scroll skipped when content already exceeds
 *       the viewport: the prior round gated auto-scroll on
 *       `if (distance < 80)`. On initial mount, scrollTop=0 and
 *       scrollHeight is already larger than the viewport (the
 *       first batch of streamed content is taller than one
 *       screen), so distance ≫ 80 and the scroll branch never
 *       ran — the viewport never moved from scrollTop=0, and the
 *       user said "I'm stuck at the top, can't scroll to the
 *       bottom". The fix: always scroll when pinned. pinned=true
 *       means "user wants the bottom", so honour it.</li>
 *   <li>Unseen-count runaway: the prior round only advanced
 *       `lastSeenRef` inside the pinned branch. In the unpinned
 *       branch (user scrolled away) the code computed
 *       `delta = cur - lastSeenRef` but never updated
 *       lastSeenRef, so the next useEffect run recomputed
 *       `delta` with the SAME stale lastSeenRef — delta always
 *       reflected the total accumulated events. The user saw
 *       "89020 new" while actually being pinned and seeing the
 *       latest content. The fix: advance lastSeenRef in both
 *       branches, so delta is per-tick ("events arrived since
 *       this useEffect fired") not cumulative.</li>
 * </ol>
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

function readSrc(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

describe('R275: chat scroll behavior — always scroll when pinned + per-tick unseenCount', () => {
  it('auto-scroll branch must NOT gate on `if (distance < 80)` — always scrolls when pinned', () => {
    // The R275 fix replaces the prior `if (distance < 80)` gate
    // inside the `requestAnimationFrame(() => { ... })` with an
    // unconditional `listRef.current.scrollTop = scrollHeight`.
    // Without this, the user gets stuck at the top on initial
    // mount because the first batch of streamed content is
    // already taller than the viewport (distance ≫ 80).
    const src = readSrc('src/components/MessageList.tsx');
    // Extract the auto-scroll useEffect body. There are several
    // useEffect calls in this file; we want the one that handles
    // auto-scroll, which is the one right before `const
    // jumpToBottom = ...`.
    const jtIdx = src.indexOf('const jumpToBottom');
    expect(jtIdx, 'jumpToBottom declaration must exist').toBeGreaterThan(0);
    // walk backward to the previous `useEffect(`
    const slice = src.slice(0, jtIdx);
    const lastUseEffectStart = slice.lastIndexOf('useEffect(');
    expect(lastUseEffectStart, 'useEffect before jumpToBottom must exist').toBeGreaterThan(0);
    const body = slice.slice(lastUseEffectStart, jtIdx);
    // The auto-scroll branch is inside `if (pinned && el) { ...
    // requestAnimationFrame(() => { ... }); ... }`. The R275 fix
    // made the requestAnimationFrame body just
    // `listRef.current.scrollTop = listRef.current.scrollHeight`
    // — no distance gate.
    expect(
      body,
      'auto-scroll branch must always scroll when pinned (no ' +
        'distance gate). The prior round had `if (distance < 80) ' +
        'listRef.current.scrollTop = ...` which skipped the scroll ' +
        'on initial mount because content already exceeded the ' +
        'viewport. Always scroll when pinned is what the user ' +
        'wants.',
    ).toMatch(/listRef\.current\.scrollTop\s*=\s*listRef\.current\.scrollHeight/);
    // Stronger negative check: the distance gate must be gone
    // from the auto-scroll branch. We match the specific pattern
    // of the old gate — `if (distance < 80) { ... scrollTop =
    // scrollHeight ... }` — which is unlikely to appear in
    // comments because it's a long, distinctive code shape.
    expect(
      body,
      'auto-scroll branch must NOT gate on `if (distance < 80)` ' +
        'anymore — that gate skipped initial scroll because ' +
        'scrollHeight was already > clientHeight when the user ' +
        'first opened the chat.',
    ).not.toMatch(/if\s*\(\s*distance\s*<\s*80\s*\)\s*\{/);
  });

  it('unpinned branch must also advance lastSeenRef (per-tick delta)', () => {
    // The R275 fix moves the lastSeenRef update OUT of the
    // pinned-only branch so it always runs. Without this, every
    // subsequent useEffect run computed `delta = cur - lastSeenRef`
    // with the SAME stale lastSeenRef and the counter ballooned
    // to "89020 new" even while the user was seeing the latest
    // content (they were pinned but had briefly scrolled up).
    const src = readSrc('src/components/MessageList.tsx');
    const jtIdx = src.indexOf('const jumpToBottom');
    const slice = src.slice(0, jtIdx);
    const lastUseEffectStart = slice.lastIndexOf('useEffect(');
    const body = slice.slice(lastUseEffectStart, jtIdx);
    // Count occurrences of `lastSeenRef.current = cur` inside the
    // useEffect body — must be ≥ 2 (one in each branch).
    const matches = body.match(/lastSeenRef\.current\s*=\s*cur\b/g) ?? [];
    expect(
      matches.length,
      'lastSeenRef must be advanced in BOTH the pinned and ' +
        'unpinned branches so per-tick delta is correct.',
    ).toBeGreaterThanOrEqual(2);
  });

  it('jumpToBottom must use direct scrollTop assignment (no smooth scroll)', () => {
    // The prior round used `scrollIntoView({ behavior: 'smooth' })`.
    // On a 100k-row streaming transcript, smooth-scroll can fail to
    // trigger reliably (multiple invocations cancel each other,
    // the animation never lands). The user said "can't scroll to
    // bottom" — pointing at the jump-to-bottom button did nothing
    // because the smooth animation was being canceled.
    const src = readSrc('src/components/MessageList.tsx');
    const jtIdx = src.indexOf('const jumpToBottom');
    expect(jtIdx, 'jumpToBottom must exist').toBeGreaterThan(0);
    const jtEnd = src.indexOf('};', jtIdx);
    const body = src.slice(jtIdx, jtEnd);
    // Strip block + line comments so the negative check below
    // doesn't trip on R275's own commentary about the prior round.
    const stripped = body
      .replace(/\/\*[\s\S]*?\*\//g, '')
      .replace(/\/\/[^\n]*/g, '');
    expect(
      stripped,
      'jumpToBottom must use direct scrollTop assignment, not ' +
        'smooth scrollIntoView, so the click reliably scrolls on ' +
        'long streaming transcripts.',
    ).toMatch(/\.scrollTop\s*=\s*\w*\.scrollHeight/);
    expect(
      stripped,
      'jumpToBottom must NOT use scrollIntoView with behavior: ' +
        '"smooth" — it can be canceled by overlapping animations on ' +
        'large transcripts.',
    ).not.toMatch(/scrollIntoView\s*\(\s*\{\s*behavior:\s*['"]smooth['"]/);
  });
});