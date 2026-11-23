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
import { describe, it, expect, beforeEach } from 'vitest';

import { rpc } from '../lib/methods';
import { useStore } from '../store';
import { groupStepsBySubTask } from '../components/MessageList';

// R391 (2026-11-23) — end-to-end over the REAL store.
//
// The source-pin test next door proves the store's handler text
// mentions the right things. This one drives the REAL store with the
// REAL event handler (the `stream_event` subscription the daemon's
// notifications arrive on) and asserts what the chat would actually
// show.
//
// The event sequence below is the shape the daemon really emits for a
// flat todo plan: run_start → think → tool → sub_task_start →
// tool → think → sub_task_start (the plan was re-worded) →
// sub_task_end (the item left the plan).
//
// Pre-R391 this produced ONE giant preamble block and a row of
// title-only cards (measured on the live renderer: 15 696 characters
// in the preamble, seven cards of 17–75 characters each).

/** Fire one daemon stream event into the store's real handler. */
function fire(event: Record<string, unknown>): void {
  const handlers = (rpc as unknown as {
    handlers: Map<string, Set<(params: unknown) => void>>;
  }).handlers;
  const set = handlers.get('stream_event');
  expect(set, 'the store must subscribe to stream_event').toBeTruthy();
  set!.forEach((h) => h({ sessionId: '', event }));
}

beforeEach(() => {
  useStore.setState({
    steps: [],
    subTasks: [],
    messages: [],
    currentStepId: null,
    currentSubTaskId: null,
    currentQuery: 'query-1',
    isStreaming: false,
  } as never);
});

describe('R391: the store attributes a run\'s output to the sub-task that is running', () => {
  it('a step opened after a sub-task was declared belongs to that sub-task', () => {
    fire({ type: 'run_start', runId: 'r1', name: 'agent' });
    fire({ type: 'text_delta', text: '先探路' });
    fire({ type: 'tool_use_start', id: 't0', name: 'bash', input: {} });

    // The model now declares its plan. Nothing has been attributed to
    // a sub-task yet — the plan did not exist when the run started.
    fire({
      type: 'sub_task_start',
      taskId: 0,
      subTaskId: 'auto-0',
      content: '搭建 Maven 项目骨架',
      status: 'in_progress',
    });

    fire({ type: 'tool_use_start', id: 't1', name: 'file_write', input: {} });
    fire({ type: 'text_delta', text: '骨架写完了' });

    const steps = useStore.getState().steps;
    expect(steps.length).toBeGreaterThanOrEqual(2);
    const last = steps[steps.length - 1];
    expect(last.text).toContain('骨架写完了');
    // THE FIX: the step belongs to the sub-task that was running when
    // it was opened. Pre-R391 this was null (inherited from the
    // previous step, which was stamped before the plan existed), so
    // the whole turn rendered as one preamble block.
    expect(last.subTaskId).toBe('0:auto-0');
  });

  it('a later sub-task takes over the attribution', () => {
    fire({ type: 'run_start', runId: 'r1', name: 'agent' });
    fire({ type: 'text_delta', text: '先探路' });
    fire({ type: 'tool_use_start', id: 't0', name: 'bash', input: {} });
    fire({ type: 'sub_task_start', taskId: 0, subTaskId: 'auto-0', content: '骨架', status: 'in_progress' });
    fire({ type: 'tool_use_start', id: 't1', name: 'file_write', input: {} });
    fire({ type: 'text_delta', text: '骨架写完了' });

    fire({ type: 'sub_task_start', taskId: 1, subTaskId: 'auto-1', content: '实现 9 种排序算法', status: 'in_progress' });
    fire({ type: 'tool_use_start', id: 't2', name: 'file_write', input: {} });
    fire({ type: 'text_delta', text: '算法写完了' });

    const steps = useStore.getState().steps;
    const byText = (needle: string) => steps.find((s) => s.text.includes(needle));
    expect(byText('骨架写完了')?.subTaskId).toBe('0:auto-0');
    expect(byText('算法写完了')?.subTaskId).toBe('1:auto-1');
  });
});

describe('R391: the card follows the plan', () => {
  it('a re-worded plan item updates the card\'s title', () => {
    fire({ type: 'run_start', runId: 'r1', name: 'agent' });
    fire({ type: 'sub_task_start', taskId: 0, subTaskId: 'auto-0', content: '实现 9 种排序算法', status: 'pending' });
    expect(useStore.getState().subTasks[0].content).toBe('实现 9 种排序算法');

    // The model re-worded the item (the daemon now re-announces it).
    fire({ type: 'sub_task_start', taskId: 0, subTaskId: 'auto-0', content: '实现 9 种排序算法 + 三类数组适配', status: 'pending' });
    const card = useStore.getState().subTasks[0];
    expect(card.content).toBe('实现 9 种排序算法 + 三类数组适配');
  });

  it('an item the model dropped LEAVES the plan takes its card with it', () => {
    fire({ type: 'run_start', runId: 'r1', name: 'agent' });
    fire({ type: 'sub_task_start', taskId: 0, subTaskId: 'auto-0', content: '会被删掉的项', status: 'pending' });
    fire({ type: 'sub_task_start', taskId: 1, subTaskId: 'auto-1', content: '留下来的项', status: 'pending' });
    expect(useStore.getState().subTasks.length).toBe(2);

    // The engine's wording for "this item is no longer in the plan".
    fire({
      type: 'sub_task_end',
      taskId: 0,
      subTaskId: 'auto-0',
      status: 'skipped',
      summary: 'sub-task removed from todo list',
    });

    const cards = useStore.getState().subTasks;
    // The card is GONE — not painted as "skipped". Pre-R391 the user
    // saw a "结果 skipped" footer on a card whose work was still in
    // the plan under a different wording.
    expect(cards.map((c) => c.id)).toEqual(['1:auto-1']);
    expect(cards.some((c) => c.status === 'skipped')).toBe(false);
  });

  it('the output of a removed card is still visible', () => {
    fire({ type: 'run_start', runId: 'r1', name: 'agent' });
    fire({ type: 'text_delta', text: '先探路' });
    fire({ type: 'tool_use_start', id: 't0', name: 'bash', input: {} });
    fire({ type: 'sub_task_start', taskId: 0, subTaskId: 'auto-0', content: '骨架', status: 'in_progress' });
    fire({ type: 'tool_use_start', id: 't1', name: 'file_write', input: {} });
    fire({ type: 'text_delta', text: '骨架写完了' });

    fire({
      type: 'sub_task_end',
      taskId: 0,
      subTaskId: 'auto-0',
      status: 'skipped',
      summary: 'sub-task removed from todo list',
    });

    const { steps, subTasks } = useStore.getState();
    const { bySub, pre } = groupStepsBySubTask(steps, subTasks);
    // The card is gone, but the step it held must not vanish with it.
    const orphan = steps.find((s) => s.text.includes('骨架写完了'));
    expect(orphan, 'the step must still exist').toBeTruthy();
    expect(pre.some((s) => s.text.includes('骨架写完了'))).toBe(true);
    expect(Object.values(bySub).flat().some((s) => s.text.includes('骨架写完了'))).toBe(false);
  });
});
