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
import { describe, it, expect, afterEach } from 'vitest';
import { render, cleanup } from '@testing-library/react';

import { MessageList } from '../MessageList';
import { useStore } from '../../store';
import type { ChatStep, ChatSubTask } from '../../store';

/**
 * R394 (2026-11-23) — the real render of the user's report.
 *
 * The user ran a task, then MODIFIED it, and the chat came back as one
 * blob of work followed by SEVEN title-only stage cards — the whole
 * plan painted as headings, with nothing under any of them.
 *
 * The cause was the ORDER: the model declared the plan at the END of
 * the turn, so every work step was opened before the plan existed and
 * nothing could be attributed — the work stayed in the preamble bucket
 * and the seven cards had no output.
 *
 * The fix: a stage card stands for the WORK of that stage. A stage
 * with no output is a plan item, and the plan has its own home (the
 * right panel's "Plan" tab), so the chat must not repeat it as empty
 * headings.
 *
 * This suite RENDERS the real component with the real store and reads
 * the real DOM — the shape is copied from the live run (1 preamble
 * block with two steps + 7 empty cards).
 */
function step(id: string, subTaskId: string | null, text: string, startedAt: number): ChatStep {
  return {
    id,
    subTaskId,
    queryId: 'q1',
    startedAt,
    text,
    toolEvents: [],
    counters: { thinks: 0, fileReads: 0, fileWrites: 0, commands: 0, searches: 0, web: 0, other: 0 },
    done: true,
  } as ChatStep;
}

function card(id: string, content: string): ChatSubTask {
  return {
    id,
    taskId: 0,
    subTaskId: id.split(':')[1] ?? id,
    content,
    status: 'completed',
    firstStepId: null,
    startedAt: 0,
  } as ChatSubTask;
}

const SEVEN = [
  '实现 SortOrder 枚举与排序方向参数',
  '新增 ByteArrayView / DescendingArrayView 视图',
  'ArraySorter / SortAlgorithm / Sorters 接入四类数组 × 升降序重载',
  '扩展测试支撑：PrimitiveOps.ByteOps、Views.of(byte[])',
  '新增 SortOrderContractTest 覆盖升序/降序契约',
  '更新 README（概览、快速开始、目录树、测试与实现要点）',
  '全量测试与 JaCoCo 覆盖率复核',
];

describe('R394: the rendered chat does not paint the plan as empty cards', () => {
  afterEach(() => cleanup());

  it('the user\'s shape renders ONE block, not eight', () => {
    useStore.setState({
      messages: [],
      isStreaming: false,
      steps: [
        step('s1', null, '做完了 README 的同步与验收', 10),
        step('s2', null, '未验证项：未做 mvn package', 20),
      ],
      subTasks: SEVEN.map((c, i) => card(`0:auto-${i}`, c)),
      currentSubTaskId: null,
      currentSessionId: 'sess-1',
    } as never);

    const { container } = render(<MessageList />);

    // No stage card at all.
    expect(container.querySelectorAll('.agent-message.has-subtask')).toHaveLength(0);
    // The work is still there — one preamble block.
    expect(container.querySelectorAll('.agent-message.preamble')).toHaveLength(1);
    // And none of the seven plan headings is painted.
    const text = container.textContent ?? '';
    for (const heading of SEVEN) {
      expect(text, `the plan heading "${heading}" must not be painted`).not.toContain(heading);
    }
  });

  it('a stage WITH output is still rendered as a card', () => {
    useStore.setState({
      messages: [],
      isStreaming: false,
      steps: [
        step('s1', null, '开始干活', 10),
        step('s2', '0:auto-0', '第一步的产出', 20),
      ],
      subTasks: [card('0:auto-0', '实现 SortOrder 枚举与排序方向参数')],
      currentSubTaskId: null,
      currentSessionId: 'sess-1',
    } as never);

    const { container } = render(<MessageList />);

    expect(container.querySelectorAll('.agent-message.has-subtask')).toHaveLength(1);
    expect(container.textContent ?? '').toContain('实现 SortOrder 枚举与排序方向参数');
  });

  it('a FAILED stage is still visible', () => {
    useStore.setState({
      messages: [],
      isStreaming: false,
      steps: [],
      subTasks: [{ ...card('0:auto-0', '失败的阶段'), status: 'failed' }],
      currentSubTaskId: null,
      currentSessionId: 'sess-1',
    } as never);

    const { container } = render(<MessageList />);

    expect(container.querySelectorAll('.agent-message.has-subtask')).toHaveLength(1);
  });
});
