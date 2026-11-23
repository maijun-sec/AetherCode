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

// R394 (2026-11-23) — "分多个大阶段，然后只在第一个显示了".
//
// The user ran a task, then MODIFIED it. The chat came back as one big
// blob of work followed by SEVEN empty stage cards — the whole plan,
// painted as headings, with nothing under any of them.
//
// The cause: the model declared the plan at the END of the turn (one
// `todo_write` with all seven items marked completed). Every work step
// was therefore opened BEFORE the plan existed, so no step could be
// attributed to a stage; the work stayed in the preamble bucket and the
// seven cards had nothing to show.
//
// The fix: a stage card stands for the WORK of that stage. A stage with
// no output is a plan item, and the plan already lives in the right
// panel's "Plan" tab — so the chat must not repeat it as empty
// headings.
//
// These tests drive `buildTimeline` directly: the rule is behaviour, and
// a rule that can only be checked by reading the source is a rule
// nobody checks.

import { describe, it, expect } from 'vitest';

import { buildTimeline, type TimelineEvent } from '../components/MessageList';
import type { ChatStep, ChatSubTask, ChatMessage } from '../store';

function step(id: string, subTaskId: string | null, extra: Partial<ChatStep> = {}): ChatStep {
  return {
    id,
    subTaskId,
    queryId: 'q1',
    startedAt: 0,
    text: '',
    toolEvents: [],
    counters: { thinks: 0, fileReads: 0, fileWrites: 0, commands: 0, searches: 0, web: 0, other: 0 },
    done: true,
    ...extra,
  } as ChatStep;
}

function card(id: string, extra: Partial<ChatSubTask> = {}): ChatSubTask {
  return {
    id,
    taskId: 0,
    subTaskId: id.split(':')[1] ?? id,
    content: '阶段 ' + id,
    status: 'completed',
    firstStepId: null,
    startedAt: 0,
    ...extra,
  } as ChatSubTask;
}

function kinds(events: TimelineEvent[]): string[] {
  return events.map((e) => e.kind);
}

describe('R394: a stage with no output is not a stage', () => {
  it('the real shape — one work blob + seven empty stages — renders ONE block', () => {
    // The user's turn: all the work happened before the plan existed,
    // so every step is unattributed (preamble), and the seven cards
    // the plan produced own nothing.
    const steps = [
      step('s1', null, { text: '做了 13392 字的工作' }),
      step('s2', null, { text: '还是做了很多工作' }),
    ];
    const cards = Array.from({ length: 7 }, (_, i) => card(`0:auto-${i}`));

    const events = buildTimeline([], steps, cards, null, false);

    // No stage card at all — the chat is the work, not the plan.
    expect(kinds(events).filter((k) => k === 'subtask')).toHaveLength(0);
    // And the work is still there, in one preamble block.
    const pre = events.filter((e) => e.kind === 'preamble');
    expect(pre).toHaveLength(1);
    expect((pre[0] as { steps: ChatStep[] }).steps).toHaveLength(2);
  });

  it('a stage WITH output is rendered', () => {
    const steps = [step('s1', '0:auto-0', { text: '第一步的产出' })];
    const cards = [card('0:auto-0')];

    const events = buildTimeline([], steps, cards, null, false);

    expect(kinds(events)).toContain('subtask');
    const sub = events.find((e) => e.kind === 'subtask') as { steps: ChatStep[] };
    expect(sub.steps).toHaveLength(1);
  });

  it('a LIVE stage is rendered even before it has output', () => {
    // The streaming cursor lives on the live block. If the live stage
    // had no card, the cursor would have nowhere to sit.
    const cards = [card('0:auto-0', { status: 'in_progress' })];
    const events = buildTimeline([], [], cards, '0:auto-0', true);
    expect(kinds(events)).toContain('subtask');
  });

  it('a FAILED stage is rendered even with no output', () => {
    // A failure the user cannot see is a failure the user cannot act on.
    const cards = [card('0:auto-0', { status: 'failed' })];
    const events = buildTimeline([], [], cards, null, false);
    expect(kinds(events)).toContain('subtask');
  });

  it('a SKIPPED stage stays hidden — R391 already decided that', () => {
    // R391: an item the model dropped out of the plan leaves NO card
    // behind (not a "skipped" ghost card).
    const cards = [card('0:auto-0', { status: 'skipped' })];
    const events = buildTimeline([], [], cards, null, false);
    expect(kinds(events).filter((k) => k === 'subtask')).toHaveLength(0);
  });

  it('the stages reveal themselves AS they produce output', () => {
    // The plan is declared up front; stage 2 has not started yet.
    const cards = [card('0:auto-0'), card('0:auto-1')];
    const steps = [step('s1', '0:auto-0', { text: '第一步的产出' })];

    const events = buildTimeline([], steps, cards, null, false);

    const subs = events.filter((e) => e.kind === 'subtask') as { subTask: ChatSubTask }[];
    expect(subs).toHaveLength(1);
    expect(subs[0].subTask.id).toBe('0:auto-0');
  });

  it('the user bubble and the work are still in the timeline', () => {
    const msg: ChatMessage = {
      id: 'm1',
      role: 'user',
      content: '做这个',
      timestamp: 10,
    } as ChatMessage;
    // The step happened AFTER the prompt — the timeline is sorted by
    // timestamp, so the prompt comes first.
    const steps = [step('s1', null, { text: '产出', startedAt: 20 })];

    const events = buildTimeline([msg], steps, [], null, false);

    expect(kinds(events)).toEqual(['user', 'preamble']);
  });
});
