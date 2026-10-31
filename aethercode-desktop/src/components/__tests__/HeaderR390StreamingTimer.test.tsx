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
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Header } from '../Header';
import { useStore } from '../../store';
import { rpc } from '../../lib/methods';

/**
 * R390 (2026-10-29) - the "Streaming 0.0s / 0.1s" pill bounces forever.
 *
 * REPORTED SYMPTOM. The desktop Header's status pill read
 * "Streaming 0.0s", then "Streaming 0.1s", then "0.0s" again,
 * forever, while a run was actually in flight. Sometimes it showed a
 * slightly larger number. The user read it as "the run is stuck" or
 * "the timer is broken".
 *
 * THE ROOT CAUSE. The elapsed timer was computed as
 *
 *     const runStartedAt = currentQuery?.startedAt ?? lastChunkTs;
 *     const runElapsedMs = runStartedAt ? now - runStartedAt : 0;
 *
 * `lastChunkTs` is refreshed on EVERY chunk - it exists to answer
 * "has the stream gone quiet for > 60s?", NOT "how long has this run
 * been going?". Using it as the elapsed anchor means the elapsed time
 * is recomputed from the LAST chunk, so it is always < 100ms old. The
 * 100ms ticker then flips it between "0.0s" and "0.1s" depending on
 * where in the tick window the last chunk landed. Exactly the observed
 * bounce.
 *
 * WHY THE FALLBACK WAS EVER REACHED. The `?? lastChunkTs` branch only
 * runs when `currentQuery` is null. `currentQuery` is null in a real,
 * reachable state: `run_end` clears it, but a SUB-TASK can keep
 * streaming afterwards (the screenshot shows exactly this - the main
 * run ended, sub-tasks keep producing chunks). Those chunks re-arm
 * `isStreaming: true` in the `text_delta` reducer with no query to
 * time, so the fallback took over and the timer degenerated into
 * "time since the last chunk".
 *
 * THE FIX. The elapsed timer gets its OWN anchor in the store:
 * `streamingStartedAt`. It is stamped once, on the false->true
 * transition of `isStreaming`, and is never touched by an individual
 * chunk. The Header reads `currentQuery?.startedAt ?? streamingStartedAt`
 * and NEVER falls back to `lastChunkTs`.
 *
 * THESE TESTS. Two layers:
 *
 *   1. BEHAVIOUR (the real store + the real Header): drive the actual
 *      reducers, then render the Header and read the rendered pill.
 *      This is the layer that would have caught the bug.
 *
 *   2. SOURCE PINS: the invariants that a refactor could break -
 *      `lastChunkTs` must not be an elapsed anchor anywhere, the
 *      anchor must be set on the false->true transition, and it must
 *      not be written by the per-chunk path.
 *
 * NOTE ON WHY THE SOURCE PINS ARE NOT REDUNDANT. The behaviour test
 * proves the CURRENT code is right. The pins prove it stays right:
 * a future contributor who "simplifies" the Header back to
 * `?? lastChunkTs` would otherwise pass every behavioural test in
 * this file (the behaviour test drives the store, and the store
 * would still be correct) while silently reintroducing the bounce.
 */

const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
})();

const storeSrc = readFileSync(join(root, 'src', 'store', 'index.ts'), 'utf-8');
const headerSrc = readFileSync(join(root, 'src', 'components', 'Header.tsx'), 'utf-8');

/**
 * Drive the store's REAL stream-event reducer.
 *
 * The store subscribes to the daemon's `stream_event` notification
 * via `rpc.on('stream_event', ...)`. In production the notification
 * arrives over the Tauri `ws-notify` IPC channel; in a test we
 * dispatch to the registered handlers directly. That reaches the
 * exact same reducer the daemon drives - no test-only production
 * seam required.
 */
function emit(event: Record<string, unknown>) {
  const handlers = (rpc as any).handlers as Map<string, Set<(p: unknown) => void>>;
  const set = handlers.get('stream_event');
  if (!set) throw new Error('no stream_event handler registered - did the store import fail?');
  act(() => {
    for (const h of set) {
      h({ sessionId: '', event });
    }
  });
}

// The status pill's dot. Built from a codepoint so this file
// survives a round-trip through any editor/encoding.
const DOT = String.fromCodePoint(0x25cf);

const baseState = {
  isStreaming: false,
  isConnected: true,
  currentQuery: null,
  lastChunkTs: 0,
  streamingStartedAt: 0,
  engineState: { state: 'idle' },
  tasks: [],
  currentTaskId: null,
  connectionState: 'connected',
  lastFailedAt: null,
  lastFailedStopReason: null,
  pendingPermissions: [],
  theme: 'dark',
  cwd: null,
} as any;

beforeEach(() => {
  useStore.setState({ ...baseState });
  // Fake timers so the Header's 100ms ticker is under our control.
  vi.useFakeTimers();
  vi.setSystemTime(new Date('2026-10-29T12:00:00Z'));
});

afterEach(() => {
  vi.useRealTimers();
  useStore.setState({ ...baseState });
});

describe('R390 - the store keeps a stable streaming-burst anchor', () => {
  it('stamps the anchor when streaming starts and NEVER moves it on a chunk', () => {
    // A chunk arrives at T0.
    emit({ type: 'text_delta', text: 'hello' });
    const afterFirst = useStore.getState().streamingStartedAt;
    expect(afterFirst).toBeGreaterThan(0);

    // Time passes, and MORE chunks arrive. The anchor must not move -
    // this is the whole point: the elapsed timer counts from the
    // START of the burst, not from the last chunk.
    vi.setSystemTime(new Date('2026-10-29T12:00:05Z'));
    emit({ type: 'text_delta', text: ' world' });
    expect(useStore.getState().streamingStartedAt).toBe(afterFirst);

    // `lastChunkTs` DID move - proving the chunk was really applied
    // and that the anchor is not simply frozen because nothing ran.
    expect(useStore.getState().lastChunkTs).toBeGreaterThan(afterFirst);
  });

  it('clears the anchor when the run ends, so the next burst starts fresh', () => {
    emit({ type: 'text_delta', text: 'hello' });
    expect(useStore.getState().streamingStartedAt).toBeGreaterThan(0);

    emit({ type: 'run_end', stopReason: 'done' });
    expect(useStore.getState().isStreaming).toBe(false);
    expect(useStore.getState().streamingStartedAt).toBe(0);

    // The next burst must NOT inherit the previous run's start time.
    vi.setSystemTime(new Date('2026-10-29T12:10:00Z'));
    emit({ type: 'text_delta', text: 'new run' });
    const next = useStore.getState().streamingStartedAt;
    expect(next).toBeGreaterThan(0);
    // The new anchor is at the NEW time, not the old one.
    expect(next).toBeGreaterThan(Date.parse('2026-10-29T12:09:00Z'));
  });
});

describe('R390 - the Header pill does not bounce between 0.0s and 0.1s', () => {
  it('keeps counting up while chunks keep arriving (sub-task streaming)', () => {
    // The exact reported state: `currentQuery` is null (the main run
    // ended) while a sub-task keeps streaming.
    useStore.setState({
      ...baseState,
      isStreaming: true,
      currentQuery: null,
      streamingStartedAt: Date.parse('2026-10-29T12:00:00Z'),
      lastChunkTs: Date.parse('2026-10-29T12:00:00Z'),
    });

    render(<Header onSettingsClick={() => {}} />);

    const readPill = () => screen.getByText(new RegExp(`${DOT} Streaming|${DOT} Thinking`)).textContent ?? '';

    // T0: the pill reads the burst start.
    expect(readPill()).toBe(`${DOT} Streaming 0.0s`);

    // A chunk lands 3s in. Pre-fix this RESET the elapsed time to
    // ~0 (because the anchor was `lastChunkTs`, refreshed per chunk).
    vi.setSystemTime(new Date('2026-10-29T12:00:03Z'));
    emit({ type: 'text_delta', text: 'chunk' });
    act(() => { vi.advanceTimersByTime(100); });

    // Post-fix the elapsed time reflects the BURST, not the chunk.
    // (3s wall clock + the 100ms the ticker advanced = 3.1s.)
    expect(readPill()).toBe(`${DOT} Streaming 3.1s`);

    // Another chunk 400ms later. Pre-fix the display dropped back to
    // ~0.0s here - that is the reported "0.0 / 0.1 / 0.0" bounce.
    vi.setSystemTime(new Date('2026-10-29T12:00:03.4Z'));
    emit({ type: 'text_delta', text: 'chunk2' });
    act(() => { vi.advanceTimersByTime(100); });

    expect(readPill()).toBe(`${DOT} Streaming 3.5s`);
  });

  it('never renders an elapsed time SMALLER than the burst age', () => {
    // The bounce is a MONOTONICITY violation: the displayed elapsed
    // time must never go backwards. Assert that directly over a
    // sequence of chunks - this is the property the user saw
    // violated, stated as an invariant rather than as a snapshot.
    useStore.setState({
      ...baseState,
      isStreaming: true,
      currentQuery: null,
      streamingStartedAt: Date.parse('2026-10-29T12:00:00Z'),
      lastChunkTs: Date.parse('2026-10-29T12:00:00Z'),
    });

    render(<Header onSettingsClick={() => {}} />);

    const readSeconds = () => {
      const m = new RegExp(`${DOT} Streaming ([\\d.]+)s`).exec(screen.getByText(new RegExp(`${DOT} Streaming`)).textContent ?? '');
      return m ? parseFloat(m[1]) : -1;
    };

    let last = -1;
    for (let i = 1; i <= 6; i++) {
      vi.setSystemTime(new Date(Date.parse('2026-10-29T12:00:00Z') + i * 500));
      emit({ type: 'text_delta', text: `chunk ${i}` });
      act(() => { vi.advanceTimersByTime(100); });
      const now = readSeconds();
      expect(now).toBeGreaterThanOrEqual(last);
      last = now;
    }
    // And it really did advance - a frozen timer would also be
    // monotonic, and would not be a fix.
    expect(last).toBeGreaterThan(2);
  });
});

describe('R390 - source pins', () => {
  it('the Header never uses lastChunkTs as an elapsed-time anchor', () => {
    // The exact anti-pattern that caused the bug.
    expect(headerSrc).not.toMatch(/runStartedAt\s*=\s*[^;]*\?\?\s*lastChunkTs/);
    // And the positive form.
    expect(headerSrc).toMatch(/runStartedAt\s*=\s*currentQuery\?\.startedAt\s*\?\?\s*streamingStartedAt/);
  });

  it('the store stamps the anchor on the false->true transition', () => {
    // Guarded by `s.isStreaming ? s.streamingStartedAt : Date.now()` -
    // the stamp happens ONLY on the transition, so a duplicate
    // run_start cannot reset the timer mid-burst.
    expect(storeSrc).toMatch(
      /streamingStartedAt:\s*s\.isStreaming\s*\?\s*s\.streamingStartedAt\s*:\s*Date\.now\(\)/
    );
  });

  it('the per-chunk path does not move the anchor', () => {
    // The `text_delta` reducer writes the anchor with the SAME
    // transition guard as everywhere else - it does NOT write an
    // unconditional `Date.now()`.
    const textDelta = /case 'text_delta':[\s\S]*?break;/m.exec(storeSrc);
    expect(textDelta).not.toBeNull();
    const body = textDelta![0];
    // No unguarded stamp anywhere in the chunk path.
    expect(body).not.toMatch(/streamingStartedAt:\s*Date\.now\(\)/);
  });

  it('the anchor is declared in the store state and reset on init', () => {
    expect(storeSrc).toMatch(/streamingStartedAt:\s*number;/);
    expect(storeSrc).toMatch(/streamingStartedAt:\s*0,/);
  });
});
