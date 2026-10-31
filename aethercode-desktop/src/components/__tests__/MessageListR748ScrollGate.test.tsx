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

// R748 (2026-10-21) — "scrolled up once → can never scroll back down".
//
// This is the follow-up regression to R735. R735 fixed the
// FIRST-PINT state machine (mount snap + userScrolledRef) but left
// userScrolledRef as a ONE-WAY LATCH:
//
//   set true  when the listener sees distance > 80
//   set false ONLY when the listener sees distance < 80 again
//
// The problem is that `distance` is measured against a scrollHeight
// that GROWS on every streamed token and every tool card. In a
// 45-minute research session the transcript grew from ~130 to ~420
// messages (589K chars). A viewport that was 40px from the bottom
// became 3000px from the bottom — with the user touching NOTHING.
// Because the gate is only cleared by reaching the new bottom, and
// the user never got there, auto-scroll stayed dead for the rest of
// the session: "最开始在最下面，然后我滚动上来后就下不去了".
//
// Two independent defects, both required for the symptom:
//
//   (A) ARMING — any `distance > 80` armed the gate, including the
//       fires caused by content growth rather than a user gesture.
//       Fix: compare scrollTop against the previous measurement. A
//       real gesture moves scrollTop; content growth does not.
//
//   (B) CLEARING — even a correct arming never expires. Fix: a TTL
//       (USER_SCROLL_GATE_TTL_MS) plus a timer that resumes
//       following once it lapses. This matches mainstream chat UIs:
//       "you scrolled away → we stop yanking; you idle → we resume".
//
// Source-pin guards, same rationale as MessageListR735AutoScroll:
// they assert the fix's surface is present so a refactor that drops
// it fails loudly instead of silently regressing.

import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { sliceBlock } from '../../test-support/slice-structural';

// R748: resolve the repo root from THIS file's own location, NOT from
// process.cwd(). Under vitest's jsdom environment with several
// source-pin suites running together, process.cwd() is not reliably
// the package root — an early version of this test read an empty
// string and every assertion failed with a confusing mismatch. The
// ToolsPanel suite already derives it this way; match it.
const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const TS = join(root, 'src', 'components', 'MessageList.tsx');
const source = readFileSync(TS, 'utf8');

describe('MessageList — R748 user-scrolled gate is not a permanent latch', () => {
  it('tracks the last scroll geometry to tell a gesture from content growth', () => {
    // Without these two refs the listener cannot distinguish
    // "the user dragged" from "the content grew underneath a
    // stationary viewport" — both fire a scroll event.
    expect(source).toMatch(/lastScrollTopRef\s*=\s*useRef\(/);
    expect(source).toMatch(/lastScrollHeightRef\s*=\s*useRef\(/);
  });

  it('arms the gate only when scrollTop itself moved', () => {
    // The core of fix (A). `scrollTopMoved` is the discriminator.
    expect(source).toMatch(/const\s+scrollTopMoved\s*=/);
    // ...and the arm site must be guarded by it, otherwise the
    // guard exists but is not actually wired in.
    expect(source).toMatch(
      /if\s*\(\s*scrollTopMoved\s*\)\s*\{\s*[^}]*userScrolledRef\.current\s*=\s*true/
    );
  });

  it('records WHEN the user last scrolled away, not just that they did', () => {
    // A boolean alone can never expire. The timestamp is what makes
    // the gate recoverable without the user reaching the bottom.
    expect(source).toMatch(/lastUserScrollAtRef\s*=\s*useRef\(/);
    expect(source).toMatch(/lastUserScrollAtRef\.current\s*=\s*Date\.now\(\)/);
  });

  it('gives the gate a TTL so it can expire on its own', () => {
    expect(source).toMatch(/USER_SCROLL_GATE_TTL_MS\s*=/);
    // isUserScrollGateActive() must consult the elapsed time.
    expect(source).toMatch(
      /Date\.now\(\)\s*-\s*lastUserScrollAtRef\.current\s*<\s*USER_SCROLL_GATE_TTL_MS/
    );
  });

  it('the auto-scroll effect gates on the TTL-aware check, not the raw ref', () => {
    // This is the line that actually decides whether auto-scroll
    // runs. Reading `userScrolledRef.current` directly here would
    // bypass the TTL entirely and restore the permanent latch.
    expect(source).toMatch(
      /shouldFollowTail\s*=\s*\(\s*pinned\s*\|\|\s*!\s*isUserScrollGateActive\(\)\s*\)\s*&&\s*!!el/
    );
    expect(source).not.toMatch(
      /shouldFollowTail\s*=\s*\(\s*pinned\s*\|\|\s*!\s*userScrolledRef\.current\s*\)/
    );
  });

  it('wakes up when the TTL lapses so following resumes without new content', () => {
    // An expiring gate is unobservable unless something re-arms
    // following. This timer is that something; without it the user
    // idles at the old scroll position forever.
    expect(source).toMatch(/setTimeout\(/);
    expect(source).toMatch(
      /!\s*isUserScrollGateActive\(\)\s*\)[\s\S]{0,200}resumeFollowing\(\)/
    );
  });

  it('resumeFollowing clears BOTH the gate and its timestamp', () => {
    // Clearing only the boolean would leave a stale timestamp that
    // re-arms the gate on the next scroll event.
    expect(source).toMatch(
      /const\s+resumeFollowing\s*=\s*\(\)\s*=>\s*\{[^}]*userScrolledRef\.current\s*=\s*false[^}]*lastUserScrollAtRef\.current\s*=\s*0/
    );
  });

  it('jumpToBottom uses resumeFollowing so the manual escape hatch fully resets', () => {
    // The "jump to bottom" button is the user's explicit "take me
    // back to live" signal. If it left the timestamp armed, the next
    // scroll event would re-engage the gate.
    //
    // R750c: this used to slice a fixed 900 characters forward. That number
    // is a guess about the function's length, and it rots the first time
    // someone writes a comment above the line it is checking — the token
    // slides out of the window and the gate starts failing for the wrong
    // reason. sliceBlock bounds on the function's own closing brace, which
    // no amount of commentary can move (see test-support/slice-structural).
    const jumpBody = sliceBlock(source, 'const jumpToBottom');
    expect(jumpBody).not.toBeNull();
    expect(jumpBody).toMatch(/resumeFollowing\(\)/);
  });
});