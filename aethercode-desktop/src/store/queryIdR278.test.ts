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
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { buildTimeline } from '../components/MessageList';
import type { ChatStep } from '../store';

/** A preamble step: no sub-task owner, but a queryId and a timestamp. */
function step(id: string, queryId: string, startedAt: number): ChatStep {
  return {
    id,
    subTaskId: null,
    queryId,
    startedAt,
    text: '产出',
    toolEvents: [],
    counters: { thinks: 0, fileReads: 0, fileWrites: 0, commands: 0, searches: 0, web: 0, other: 0 },
    done: true,
  } as ChatStep;
}

/**
 * R278 (2026-09-17): split the MessageList timeline's preamble
 * grouping by `queryId`. Two consecutive user prompts (both
 * without a sub-task) used to merge their steps into one
 * preamble event with the FIRST step's ts, so the second
 * prompt's user bubble sorted AFTER the merged content —
 * the user's persistent "second-prompt leak" complaint.
 *
 * The fix tags every step with `queryId` (= the daemon's
 * `runId` from the run_start stream event) at creation time,
 * then groups preamble steps by queryId so each user prompt
 * gets its own preamble block with its own startedAt.
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

function readSrc(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

describe('R278: preamble grouping by queryId — each user prompt gets its own preamble', () => {
  it('ChatStep declares an optional `queryId` field', () => {
    const src = readSrc('src/store/index.ts');
    // Source-pin the field. A future refactor that drops queryId
    // would silently re-introduce the second-prompt leak.
    const stepIface = src.match(/export\s+interface\s+ChatStep\s*\{[\s\S]*?\n\}/);
    expect(stepIface, 'ChatStep interface must exist').not.toBeNull();
    expect(stepIface![0]).toMatch(/\bqueryId\??:\s*string/);
  });

  it('run_start handler stamps new steps with the daemon runId', () => {
    const src = readSrc('src/store/index.ts');
    // The run_start case must read ev.runId and write it onto
    // the new step's queryId field.
    const runStartCase = src.match(/case\s+'run_start':\s*\{[\s\S]*?const newQueryId[\s\S]*?queryId:\s*newQueryId/);
    expect(
      runStartCase,
      'run_start case must extract ev.runId into newQueryId and tag the new step with queryId',
    ).not.toBeNull();
    // The case must handle the legacy / non-string runId case
    // (fall back to a fresh newId('query')).
    expect(runStartCase![0]).toMatch(/newId\('query'\)/);
  });

  it('MessageList timeline groups preamble steps by queryId (NOT one bucket)', () => {
    // ★ R394: this used to be a SOURCE-STRING pin — it sliced
    // MessageList.tsx between "const timeline = useMemo" and the
    // deps list and looked for the grouping code INSIDE that
    // slice. R394 moved the timeline body into the exported
    // `buildTimeline`, so the slice no longer contained the rule
    // and the pin went red — on a refactor that made the code
    // BETTER (the rule became directly testable).
    //
    // That is R384's Lesson 1233: a pin that names a LOCATION goes
    // red on a refactor; a pin that names the INVARIANT does not.
    // So the pin is now a BEHAVIOUR test: two prompts, two
    // preamble blocks, each with its own timestamp — which is the
    // actual "second-prompt leak" fix, and cannot be satisfied by
    // a lookup.
    const events = buildTimeline(
      [
        { id: 'm1', role: 'user', content: '第一个问题', timestamp: 5 } as never,
        { id: 'm2', role: 'user', content: '第二个问题', timestamp: 15 } as never,
      ],
      [
        step('s1', 'q1', 10),
        step('s2', 'q2', 20),
      ],
      [],
      null,
      false,
    );

    const preambles = events.filter((e) => e.kind === 'preamble') as { ts: number; steps: unknown[] }[];
    // ONE preamble per queryId bucket — not a single merged one.
    expect(preambles).toHaveLength(2);
    expect(preambles[0].ts).toBe(10);
    expect(preambles[1].ts).toBe(20);
    expect(preambles[0].steps).toHaveLength(1);
    expect(preambles[1].steps).toHaveLength(1);

    // And the second prompt's bubble sorts BEFORE its own preamble —
    // the leak this round fixed.
    const order = events.map((e) => e.kind);
    expect(order).toEqual(['user', 'preamble', 'user', 'preamble']);
  });
});