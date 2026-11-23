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

// R393 (2026-11-23) — the daemon gives a session a SHORT, model-written
// title; the rail used to show the user's WHOLE first prompt.
//
// The title is computed on the daemon's BACKGROUND thread, so the list
// cannot simply re-read it — the daemon notifies instead. Without this
// handler the short title would only show up on the next full refresh
// (or never), and the user would conclude the feature did not work.
//
// This drives the REAL store through the REAL notification handler.

/** Fire one daemon notification into the store's real handler. */
function fire(method: string, params: Record<string, unknown>): void {
  const handlers = (rpc as unknown as {
    handlers: Map<string, Set<(params: unknown) => void>>;
  }).handlers;
  const set = handlers.get(method);
  expect(set, `the store must subscribe to ${method}`).toBeTruthy();
  set!.forEach((h) => h(params));
}

beforeEach(() => {
  useStore.setState({
    sessions: [
      { id: 's1', title: '在当前项目目录下，创建一个 java maven 项目，支持 至少 5 种排序实现，支持 int、short、long 三类数组，UT 完整。' },
      { id: 's2', title: '另一个会话' },
    ],
    trashedSessions: [{ id: 's3', title: '回收站里的会话' }],
  } as never);
});

describe('R393: the store applies a model-written session title', () => {
  it('the title replaces the long prompt on that session only', () => {
    fire('session_title', { sessionId: 's1', title: '实现排序算法', atMs: 1 });

    const sessions = useStore.getState().sessions;
    expect(sessions.find((s) => s.id === 's1')?.title).toBe('实现排序算法');
    // the other rows are untouched
    expect(sessions.find((s) => s.id === 's2')?.title).toBe('另一个会话');
  });

  it('the trash tab keeps its own list — and is patched too', () => {
    // ★ A session that was summarised while it sat in the trash must
    // show the short title there as well. The trash tab holds a
    // SEPARATE array; patching only `sessions` would leave the trash
    // tab showing the long prompt.
    fire('session_title', { sessionId: 's3', title: '分析项目缺口', atMs: 1 });
    expect(useStore.getState().trashedSessions.find((s) => s.id === 's3')?.title)
      .toBe('分析项目缺口');
  });

  it('a malformed notification changes nothing', () => {
    fire('session_title', { sessionId: 's1' });                       // no title
    fire('session_title', { title: '没有 sessionId' });                // no id
    fire('session_title', { sessionId: 's1', title: '   ' });          // blank
    fire('session_title', { sessionId: 42, title: '类型不对' });        // wrong type

    const sessions = useStore.getState().sessions;
    expect(sessions.find((s) => s.id === 's1')?.title).toContain('在当前项目目录下');
  });

  it('an unknown session id is a no-op, not a crash', () => {
    expect(() => fire('session_title', { sessionId: 'nope', title: '新标题' }))
      .not.toThrow();
    expect(useStore.getState().sessions).toHaveLength(2);
  });
});
