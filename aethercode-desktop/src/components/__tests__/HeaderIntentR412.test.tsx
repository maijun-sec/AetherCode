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
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { Header } from '../Header';
import { useStore } from '../../store';
import { rpc } from '../../lib/methods';

/**
 * R412 — the chip answers "WHY", not only "WHAT".
 *
 * ★ THE GAP ★ the chip said "意图：代码生成 · 档案：写代码". That is the
 * verdict, but not its REASON. A user staring at a mis-classified turn
 * asks "why did it think that?" — and the answer was on the daemon, on
 * the wire, and nowhere on the screen.
 *
 * ★ THE PIN ★ the reason must reach the tooltip, and it must not
 * degrade into an empty "判定依据：" line when there is none (an empty
 * line that LOOKS like a reason is worse than no line).
 */

const { invokeMock } = vi.hoisted(() => ({
  invokeMock: vi.fn(async (_cmd: string, args: any): Promise<any> => {
    if (args?.method === 'listSessions') return { sessions: [] };
    return {};
  }),
}));

vi.mock('@tauri-apps/api/core', () => ({
  invoke: invokeMock,
}));
vi.mock('@tauri-apps/api/event', () => ({
  listen: vi.fn(async () => () => {}),
  emit: vi.fn(),
}));

beforeEach(() => {
  invokeMock.mockClear();
  useStore.setState({
    messages: [],
    isStreaming: false,
    currentSessionId: 's1',
    activeRunId: null,
    notifications: [],
    notificationHistory: [],
    lastFailedPrompt: null,
    lastFailedStopReason: null,
    lastFailedAt: null,
    currentQuery: null,
    currentStepId: null,
    steps: [],
    subTasks: [],
    pendingFollowUp: null,
    currentActivity: null,
    intentKind: null,
    intentLabel: null,
    intentReason: null,
    profileName: null,
    profileLabel: null,
    profileOverride: null,
    profiles: [],
  });
});

function emitRunStart(extra: Record<string, unknown>) {
  rpc.__test_dispatchNotification('stream_event', {
    runId: 'run-7',
    sessionId: 's1',
    event: { type: 'run_start', runId: 'run-7', model: 'stub', ...extra },
  });
}

describe('R412 — the chip shows the WHY', () => {
  it('the tooltip carries the classifier\'s reason', () => {
    emitRunStart({
      intent: 'code_change', intentLabel: '代码生成',
      intentReason: 'implementation verb: create the',
      profile: 'builder', profileLabel: '写代码',
    });
    render(<Header onSettingsClick={() => {}} />);
    const title = screen.getByTestId('header-intent-chip').getAttribute('title') ?? '';
    expect(title, 'the tooltip must say WHY').toContain('implementation verb: create the');
  });

  it('the reason is shown even when the profile is locked', () => {
    // The locked case is exactly when the user is most likely to ask
    // "why" — they overrode the posture and want to know what the
    // classifier thought.
    emitRunStart({
      intent: 'code_change', intentLabel: '代码生成',
      intentReason: 'implementation verb: create the',
      profile: 'builder', profileLabel: '写代码',
    });
    useStore.setState({ profileOverride: 'researcher' });
    render(<Header onSettingsClick={() => {}} />);
    const title = screen.getByTestId('header-intent-chip').getAttribute('title') ?? '';
    expect(title).toContain('implementation verb: create the');
  });

  it('no reason means no empty reason line', () => {
    // ★ AN EMPTY LINE THAT LOOKS LIKE A REASON ★ "判定依据：" with
    // nothing after it reads as "the daemon had no reason", which is a
    // claim. It is worse than not having the line at all.
    emitRunStart({
      intent: 'code_change', intentLabel: '代码生成',
      profile: 'builder', profileLabel: '写代码',
    });
    render(<Header onSettingsClick={() => {}} />);
    const title = screen.getByTestId('header-intent-chip').getAttribute('title') ?? '';
    expect(title).not.toContain('判定依据');
  });
});
