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

// R735 (2026-10-20) — MessageList auto-scroll first-paint regression.
//
// User symptom: "I open a session that already has a long transcript
// and the chat sits in the middle — I can scroll up to see the
// older messages, but scrolling down does nothing. There's no jump-
// to-bottom button and the only way to recover is to send a new
// prompt, which sometimes re-pins the viewport and sometimes
// doesn't."
//
// Root cause was a state-machine race between the scroll listener
// (registered on mount, deps = []) and the auto-scroll useEffect.
// On first paint the listener saw `scrollTop=0` with a tall
// `scrollHeight`, so distance > 80, so it set `pinned=false`. The
// auto-scroll effect's `if (pinned && el)` gate then never
// re-entered, and unseenCount climbed while the viewport stayed
// stuck at the top.
//
// The fix is a `useLayoutEffect` (synchronous, pre-paint) that
// snaps `scrollTop = scrollHeight` BEFORE the listener's first
// onScroll fires, plus a `userScrolledRef` that distinguishes
// "user actively scrolled away" from "programmatic snap landed".
//
// These tests are source-pin regression guards: they read the
// MessageList.tsx file and assert the fix's surface is present.
// A future refactor that drops the useLayoutEffect or the
// userScrolledRef will fail this test and force the engineer
// to re-validate the auto-scroll behaviour.

import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

// R748: derive the package root from this file's own location instead
// of process.cwd(). Under vitest's jsdom environment with multiple
// source-pin suites in one run, process.cwd() is not reliably the
// package root — this suite then read a non-existent path and failed
// with a confusing "expected undefined" mismatch rather than an
// ENOENT. Same fix as MessageListR748ScrollGate / ToolsPanel.
const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const TS = join(root, 'src', 'components', 'MessageList.tsx');
const source = readFileSync(TS, 'utf8');

describe('MessageList — R735 auto-scroll first-paint', () => {
  it('imports useLayoutEffect from react', () => {
    // The mount-snap effect is useLayoutEffect (not useEffect)
    // so it runs BEFORE the browser paints and BEFORE the scroll
    // listener's first onScroll event. A useEffect would be too
    // late — by the time the effect runs, the listener has
    // already mis-classified the initial scroll position.
    expect(source).toMatch(
      /import\s*\{[^}]*\buseLayoutEffect\b[^}]*\}\s*from\s*['"]react['"]/
    );
  });

  it('defines a userScrolledRef to distinguish programmatic vs user scrolls', () => {
    // The ref is the single source of truth for "did the user
    // actually drag the scrollbar". A boolean state variable
    // would re-render on every change and cause its own race;
    // a ref is read inside the listener and the auto-scroll
    // effect without triggering React updates.
    expect(source).toMatch(/userScrolledRef\s*=\s*useRef\(false\)/);
  });

  it('has a useLayoutEffect that snaps scrollTop to scrollHeight on mount', () => {
    // Match: useLayoutEffect(() => { ... el.scrollTop = el.scrollHeight; ... }, [currentSessionId])
    // The session-id dep ensures session switches re-snap; the
    // absence of `messages` / `steps` deps means we don't double-
    // snap on every streamed token.
    const block = source.match(
      /useLayoutEffect\(\s*\(\s*\)\s*=>\s*\{[\s\S]*?scrollTop\s*=\s*\w+\.scrollHeight[\s\S]*?\},\s*\[\s*currentSessionId\s*\]/
    );
    expect(block, 'expected useLayoutEffect snapping scrollTop to scrollHeight on currentSessionId change').toBeTruthy();
  });

  it('the mount-snap CONVERGES over multiple frames (R735-fix1)', () => {
    // Regression guard for the "content sits in the middle" report
    // that followed the initial R735 ship. A SINGLE synchronous
    // snap reads a stale scrollHeight (markdown blocks, <details>
    // folds, and the streaming cursor all resolve their heights
    // in subsequent layout passes), so the viewport lands mid-
    // transcript and the newest tool card ends up rendered ABOVE
    // the user prompt bubble. The fix re-snaps across up to
    // MAX_SNAP_FRAMES animation frames until the measured bottom
    // offset stops moving.
    expect(source).toMatch(/MAX_SNAP_FRAMES\s*=\s*\d+/);
    expect(source).toMatch(
      /const converge = \(\) => \{[\s\S]*?requestAnimationFrame\(converge\)/
    );
    // The loop must be cancellable so a session switch mid-converge
    // doesn't leave an orphaned rAF writing scrollTop on the new
    // session's list.
    expect(source).toMatch(/cancelAnimationFrame\(rafId\)/);
  });

  it('the mount-snap resets userScrolledRef to false BEFORE the scrollTop assignment', () => {
    // If we cleared the ref AFTER the assignment, the scroll
    // listener's onScroll event (fired by the assignment) would
    // see userScrolledRef=true (carried over from a prior
    // session) and flip pinned=false — the very bug we're
    // fixing. The order matters.
    const block = source.match(
      /userScrolledRef\.current\s*=\s*false;[\s\S]*?scrollTop\s*=\s*\w+\.scrollHeight/
    );
    expect(block, 'userScrolledRef must be cleared before the scrollTop assignment').toBeTruthy();
  });

  it('the auto-scroll useEffect follows the tail unless the user actively scrolled', () => {
    // Pre-R735: `if (pinned && el) { ... }` — silently dropped
    // auto-scroll if the first listener tick mis-classified
    // pinned. R735: `shouldFollowTail = (pinned || !userScrolledRef.current) && el`
    // — even if pinned is false (because the listener flipped
    // it for some reason), auto-scroll still fires as long as
    // the user hasn't actively scrolled away.
    //
    // R748: the right-hand side is now `!isUserScrollGateActive()`
    // rather than `!userScrolledRef.current`. The raw boolean was a
    // PERMANENT latch — it could only be cleared by the user
    // reaching the bottom again, which content growth made
    // unreachable — so auto-scroll stayed dead after a single
    // scroll-up. `isUserScrollGateActive()` is the TTL-aware
    // version and preserves this test's intent (follow the tail
    // unless the user scrolled away) while making it recoverable.
    // See MessageListR748ScrollGate.test.tsx for the full contract.
    const block = source.match(
      /shouldFollowTail\s*=\s*\(pinned\s*\|\|\s*!\s*isUserScrollGateActive\(\)\)\s*&&\s*!!el/
    );
    expect(block, 'auto-scroll useEffect must gate on (pinned || !isUserScrollGateActive())').toBeTruthy();
  });

  it('the scroll listener flips userScrolledRef to true on a real user scroll', () => {
    // distance > 80 means the user dragged the scrollbar away
    // from the bottom; the next auto-scroll useEffect run
    // must respect that. Pre-R735 this branch didn't exist
    // and the auto-scroll kept firing even after the user
    // explicitly scrolled up.
    const block = source.match(
      /distance\s*>\s*80[\s\S]*?userScrolledRef\.current\s*=\s*true/
    );
    expect(block, 'scroll listener must set userScrolledRef=true when distance > 80').toBeTruthy();
  });

  it('jumpToBottom clears userScrolledRef before snapping to bottom', () => {
    // Without this, the user clicking the jump-to-bottom button
    // would still be "user-scrolled" on the next render, and
    // the next streamed token would skip auto-scroll.
    //
    // R748: the reset moved into the `resumeFollowing()` helper,
    // which clears BOTH the boolean and its TTL timestamp — the
    // timestamp matters because a stale one would re-arm the gate
    // on the next scroll event. The INTENT is unchanged: the manual
    // jump must fully disarm the user-scrolled gate before snapping.
    const jumpIdx = source.indexOf('const jumpToBottom');
    expect(jumpIdx, 'jumpToBottom declaration must exist').toBeGreaterThan(0);
    const body = source.slice(jumpIdx, jumpIdx + 900);
    expect(
      body,
      'jumpToBottom must call resumeFollowing() (which clears userScrolledRef ' +
        'and the TTL timestamp) before snapping to the bottom'
    ).toMatch(/resumeFollowing\(\)/);
  });
});
