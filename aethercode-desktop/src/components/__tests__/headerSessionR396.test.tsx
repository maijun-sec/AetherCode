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
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { render } from '@testing-library/react';

import { Header } from '../Header';
import { useStore } from '../../store';
import type { SessionInfo } from '../../lib/methods';

/**
 * R396 (2026-11-23) — "which conversation am I in?"
 *
 * The user asked why a TEST turn had ended up inside their own
 * conversation. Two facts were involved:
 *
 *   1. my repro script typed into the session that happened to be
 *      ACTIVE — my workflow error, now a rule in AGENTS.md;
 *   2. the app never showed the session name ANYWHERE in the chrome.
 *      The header carried the TASK name, the input bar carried the
 *      CWD — and the user's 7 sessions all shared ONE cwd, so the
 *      cwd said nothing about which conversation a message would
 *      land in. That is the product gap: the answer was available,
 *      but not where the question is asked.
 *
 * WHERE THE NAME LIVES, AND WHY NOT IN THE INPUT BAR. The first
 * implementation put the session chip in the input's config bar,
 * next to the cwd. Measurement killed it: that strip is FULL
 * (971px of content in a 1110px box, but the items' flex BASES
 * are their max-content widths, so adding a group squeezes the
 * others until "🧠 思考" and "0.7 / 32k" wrap onto two lines and
 * the bar grows from 26px to 42px). The header has ~790px of free
 * width (measured), and "app › session › task" is the real
 * hierarchy anyway.
 *
 * The pins below are INVARIANTS, not spellings — per R384's
 * Lesson 1233, a pin must tolerate a rename of the class or the
 * label text while still catching the real defect (a wrong or
 * missing session identity).
 */

function session(id: string, title?: string): SessionInfo {
  return { id, title, cwd: 'D:\\tmp\\abc_1' } as SessionInfo;
}

const baseState = {
  isStreaming: false,
  isConnected: false,
  currentQuery: null,
  lastChunkTs: 0,
  streamingStartedAt: 0,
  engineState: { state: 'idle' },
  tasks: [],
  currentTaskId: null,
  lastFailedAt: null,
  lastFailedStopReason: null,
  theme: 'dark',
  cwd: 'D:\\tmp\\abc_1',
  sessions: [] as SessionInfo[],
  currentSessionId: null as string | null,
} as any;

beforeEach(() => {
  useStore.setState(baseState);
});

afterEach(() => {
  useStore.setState(baseState);
});

function renderHeader(state: {
  sessions: SessionInfo[];
  currentSessionId: string | null;
}) {
  useStore.setState({
    ...baseState,
    sessions: state.sessions,
    currentSessionId: state.currentSessionId,
  } as never);
  return render(<Header onSettingsClick={() => {}} />);
}

/** The element that carries the session identity, if it is rendered. */
function sessionEl(container: HTMLElement): HTMLElement | null {
  return container.querySelector('.header-session-name');
}

/**
 * The NAME the element shows — NOT the element's whole text.
 *
 * The element also carries a static "🗂" glyph, so reading the
 * element's `textContent` would pass even when the name itself is
 * empty — a vacuous assertion that measures the glyph and calls it
 * a measurement.
 */
function sessionName(container: HTMLElement): string {
  const el = sessionEl(container);
  return (el?.textContent ?? '').replace(/🗂/u, '').trim();
}

describe('R396: the header says WHICH conversation this window is showing', () => {
  it('the session identity lives in the header', () => {
    const { container } = renderHeader({
      sessions: [session('sess-1', '新增排序方向')],
      currentSessionId: 'sess-1',
    });
    expect(
      container.querySelector('.header .header-session-name'),
      'the session identity must be in the header',
    ).toBeTruthy();
    expect(sessionName(container)).toContain('新增排序方向');
  });

  it('the name follows the ACTIVE session, not the first row of the list', () => {
    const { container } = renderHeader({
      sessions: [session('sess-1', '第一个会话'), session('sess-2', '第二个会话')],
      currentSessionId: 'sess-2',
    });
    const name = sessionName(container);
    expect(name).toContain('第二个会话');
    expect(name, 'the header must not name a session the user is not in').not.toContain('第一个会话');
  });

  it('a session the list has not caught up with shows ITS OWN id — never another session\'s name', () => {
    // The rail refreshes asynchronously; between "the user switched
    // sessions" and "the list caught up", `currentSessionId` can name
    // a row that is not in `sessions` yet. Showing the WRONG title
    // here is worse than showing no title: the user would believe
    // their message goes somewhere it does not.
    const { container } = renderHeader({
      sessions: [session('sess-1', '别人的会话')],
      currentSessionId: 'sess-zz',
    });
    const name = sessionName(container);
    expect(name, 'must not borrow another session\'s title').not.toContain('别人的会话');
    expect(name, 'must still identify the session somehow').toContain('sess-zz');
  });

  it('an untitled session still shows an identity instead of a blank', () => {
    const { container } = renderHeader({
      sessions: [session('sess-1')],
      currentSessionId: 'sess-1',
    });
    const name = sessionName(container);
    expect(name.length, 'a blank name is a silent failure').toBeGreaterThan(0);
    expect(name).toContain('sess-1');
  });

  it('with no session at all the name is absent, and the rest of the header survives', () => {
    const { container } = renderHeader({ sessions: [], currentSessionId: null });
    expect(sessionEl(container), 'there is nothing to name').toBeNull();
    expect(container.querySelector('.header-brand'), 'the brand must survive').toBeTruthy();
    expect(container.querySelector('.header-task-name'), 'the task name must survive').toBeTruthy();
  });

  it('the full name AND the id are in the tooltip, so a truncated name is still identifiable', () => {
    const { container } = renderHeader({
      sessions: [session('sess-1', '新增排序方向与字节数组支持')],
      currentSessionId: 'sess-1',
    });
    const tip = sessionEl(container)?.getAttribute('title') ?? '';
    expect(tip).toContain('新增排序方向与字节数组支持');
    expect(tip).toContain('sess-1');
  });

  it('the session name comes BEFORE the task name — app › session › task', () => {
    // The hierarchy is the reason this lives here: the task name
    // changes on every query, the session does not, so the stable
    // one goes first.
    const { container } = renderHeader({
      sessions: [session('sess-1', '新增排序方向')],
      currentSessionId: 'sess-1',
    });
    const name = sessionEl(container)!;
    const task = container.querySelector('.header-task-name')!;
    expect(name, 'the session name must be rendered').toBeTruthy();
    expect(task, 'the task name must be rendered').toBeTruthy();
    // compareDocumentPosition: DOCUMENT_POSITION_FOLLOWING === 4
    const rel = name.compareDocumentPosition(task);
    expect(
      rel & Node.DOCUMENT_POSITION_FOLLOWING,
      'the session name must precede the task name in DOM order',
    ).toBeTruthy();
  });

  it('the task name is still shown — the session name did not replace it', () => {
    const { container } = renderHeader({
      sessions: [session('sess-1', '新增排序方向')],
      currentSessionId: 'sess-1',
    });
    const task = container.querySelector('.header-task-name');
    expect(task, 'the task name must still be rendered').toBeTruthy();
    expect((task!.textContent ?? '').trim().length).toBeGreaterThan(0);
  });
});
