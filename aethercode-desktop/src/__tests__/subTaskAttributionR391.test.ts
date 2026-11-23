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

import { groupStepsBySubTask } from '../components/MessageList';
import type { ChatStep, ChatSubTask } from '../store';

// R391 (2026-11-23) — "all the steps came out inside the FIRST step".
//
// THE SYMPTOM the user reported, from a real 20-minute session:
//
//   [an assistant block holding 15 696 characters of output]
//   ## 搭建 Maven 项目骨架（pom.xml / 目录 / .gitignore）      ← only a title
//   ## 实现核心抽象层（PrimArray 适配器 + …）                  ← only a title
//   ## 实现 9 种排序算法（int/short/long 全支持）              ← only a title
//   ## 实现 Sorters 注册表/门面                                ← only a title
//   ## 编写完整 UT（契约/边界/极值/注册表/性能冒烟）            ← only a title
//   ## 运行 mvn test 直到全绿并复核 diff                        ← only a title
//   ## 补 README（中文）说明算法清单与用法                      ← only a title
//     ▸ 结果 skipped
//       skipped: sub-task removed from todo list
//
// Three separate defects, all confirmed against the live renderer
// (the DOM of the running desktop showed exactly this shape: one
// 15 696-char preamble block + seven title-only cards).
//
// 1. THE STEPS NEVER BELONGED TO ANY SUB-TASK.
//    The renderer opened a step at `run_start` and stamped it with
//    `currentSubTaskId` — but at `run_start` the model has not
//    declared its plan yet, so the stamp was ALWAYS null. Every later
//    step inherited the previous step's owner, which was also null.
//    The whole turn therefore fell into the preamble bucket and every
//    sub-task card stayed empty.
//
// 2. THE CARD'S TITLE WAS FROZEN AT THE FIRST WORDING.
//    `sub_task_start` updated the STATUS of an existing card but not
//    its CONTENT, so a re-worded plan kept showing the old title.
//
// 3. A DROPPED TODO LEFT A GHOST CARD WITH A "skipped" FOOTER.
//    The engine closes a sub-task the model removed from its plan with
//    the summary "sub-task removed from todo list". The renderer
//    treated that like any other terminal status and painted a
//    "结果 skipped" footer on the card — so the last card of the chat
//    read as a failure that had nothing to do with the last step.
//
// These tests pin the INVARIANTS (Lesson 1233 — a contract pin must
// follow the implementation, not the spelling), plus one behavioural
// test of the grouping rule.

// This file lives in `src/__tests__/`, so the package root is two
// levels up. Never derive it from the ambient cwd — unreliable under
// jsdom multi-suite runs (R748/1079).
const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');

function readSrc(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

/** Extract the body of one `case 'xxx': {` arm of the big
 *  `rpc.on('stream_event', …)` switch. The arm is identified by the
 *  brace on the same line, so the humanize helpers (which have no
 *  brace) are not matched. */
function streamEventBody(src: string, name: string): string {
  const sig = `case '${name}': {`;
  let from = src.indexOf(sig);
  if (from === -1) return '';
  let depth = 0;
  let started = false;
  let i = from;
  while (i < src.length) {
    const ch = src.charAt(i);
    if (ch === '{') { depth++; started = true; }
    else if (ch === '}') {
      depth--;
      if (started && depth === 0) { i++; break; }
    }
    i++;
  }
  return src.slice(from, i);
}

describe('R391 #1: a step belongs to the sub-task that is active NOW', () => {
  it('the text_delta handler takes the CURRENT sub-task, not only the previous step\'s owner', () => {
    const src = readSrc('src/store/index.ts');
    const body = streamEventBody(src, 'text_delta');
    expect(body.length, 'text_delta handler must exist').toBeGreaterThan(0);
    // The owner of a NEW step must come from `s.currentSubTaskId`
    // FIRST, with the previous step's owner only as a fallback. If it
    // only inherits `prev?.subTaskId`, the stamp taken at run_start
    // (always null — no plan exists yet) propagates to every step and
    // the whole turn renders as preamble.
    expect(
      body,
      'the new step must prefer the CURRENT sub-task over the previous step\'s owner',
    ).toMatch(/parentSubTaskId\s*=\s*s\.currentSubTaskId\s*\?\?\s*prev\?\.subTaskId\s*\?\?\s*null/);
  });

  it('the run_start handler still stamps the step it opens', () => {
    const src = readSrc('src/store/index.ts');
    const body = streamEventBody(src, 'run_start');
    expect(body.length, 'run_start handler must exist').toBeGreaterThan(0);
    expect(body).toMatch(/subTaskId:\s*parentSubTaskId/);
  });
});

describe('R391 #2: a card\'s title follows the plan', () => {
  it('sub_task_start updates the CONTENT of an existing card, not only its status', () => {
    const src = readSrc('src/store/index.ts');
    const body = streamEventBody(src, 'sub_task_start');
    expect(body.length, 'sub_task_start handler must exist').toBeGreaterThan(0);
    // The existing-card branch must carry `content` through, or the
    // title stays frozen at the first wording while the plan moves on.
    expect(
      body,
      'an existing card must take the latest content — a frozen title is how the user ended up ' +
        'reading "运行 mvn test 直到全绿" on a card whose work was "mvn clean test 从零全量验证"',
    ).toMatch(/content:\s*content\s*\|\|\s*st\.content/);
  });
});

describe('R391 #3: a dropped todo must not leave a "skipped" ghost card', () => {
  it('sub_task_end DROPS the card when the summary says the item left the plan', () => {
    const src = readSrc('src/store/index.ts');
    const body = streamEventBody(src, 'sub_task_end');
    expect(body.length, 'sub_task_end handler must exist').toBeGreaterThan(0);
    // The engine's own wording is the signal. The card must be
    // filtered OUT, not mapped to a "skipped" status.
    expect(
      body,
      'the handler must recognise the engine\'s "removed from todo list" summary',
    ).toMatch(/sub-task removed from todo list/);
    expect(
      body,
      'a dropped item must be REMOVED from the card list (filter), not re-stated as "skipped"',
    ).toMatch(/s\.subTasks\.filter\(/);
  });
});

describe('R391 #4: the grouping rule is testable and keeps orphaned steps visible', () => {
  function step(id: string, subTaskId: string | null): ChatStep {
    return {
      id,
      subTaskId,
      queryId: 'q1',
      startedAt: 0,
      text: '',
      toolEvents: [],
      counters: { thinks: 1, fileReads: 0, fileWrites: 0, commands: 0, searches: 0, web: 0, other: 0 },
      done: true,
    };
  }
  function card(id: string): ChatSubTask {
    return {
      id,
      taskId: 0,
      subTaskId: id.split(':')[1] ?? id,
      content: 'x',
      status: 'completed',
      firstStepId: null,
      startedAt: 0,
    };
  }

  it('puts a step under the sub-task that exists', () => {
    const { bySub, pre } = groupStepsBySubTask(
      [step('s1', '0:auto-0'), step('s2', null)],
      [card('0:auto-0')],
    );
    expect(bySub['0:auto-0']?.map((s) => s.id)).toEqual(['s1']);
    expect(pre.map((s) => s.id)).toEqual(['s2']);
  });

  it('keeps a step whose sub-task was dropped VISIBLE (falls back to the preamble)', () => {
    // The card was removed (the model dropped the todo), but its step
    // still holds the output. Dropping the step on the floor would
    // make the output disappear from the chat.
    const { bySub, pre } = groupStepsBySubTask(
      [step('s1', '1:auto-1')],
      [],
    );
    expect(Object.keys(bySub)).toEqual([]);
    expect(pre.map((s) => s.id)).toEqual(['s1']);
  });
});
