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

import { describe, test, expect, beforeEach, vi } from 'vitest';

/**
 * R412 — the verdict is readable AFTER the run, not only during it.
 *
 * ★ THE GAP ★ until R412 the daemon's verdict travelled only on the
 * `run_start` event. That made it visible for exactly as long as a run
 * was streaming — and invisible in the three moments a user actually
 * asks "why did it do that?": after a reload, after a reconnect, and
 * after a session switch.
 *
 * R401 promised a one-click correction for a mis-classified turn. A
 * correction needs a PREMISE: the user must SEE what the agent
 * decided. The premise now comes from the POLLED state — the RPC the
 * desktop already calls every 15 s.
 *
 * ★ THE RULES THIS PINS ★
 *   1. the polled state fills the chip (a reload restores it);
 *   2. a null verdict does NOT erase a verdict the event just set
 *      (a poll must not make the chip flicker);
 *   3. `profileOverride: null` IS an answer ("auto") and is applied;
 *   4. the vocabulary travels with the state (the picker survives a
 *      reload — otherwise "one click to correct" has nothing to click);
 *   5. switching sessions CLEARS the verdict (it is per-session — a
 *      verdict from session A must never be shown over session B).
 */

const { invokeMock } = vi.hoisted(() => ({
  invokeMock: vi.fn(async (_cmd: string, args: any): Promise<any> => {
    const method = args?.method;
    if (method === 'getState') {
      return {
        sessionId: 's1',
        model: 'stub',
        permissionMode: 'DEFAULT',
        intent: 'code_change',
        intentLabel: '代码生成',
        intentReason: 'implementation verb: create the',
        profile: 'builder',
        profileLabel: '写代码',
        profileOverride: null,
        profiles: [
          { name: 'builder', label: '写代码', description: '实现需求' },
          { name: 'researcher', label: '查资料', description: '先查后答' },
        ],
      };
    }
    if (method === 'listSessions') return { sessions: [] };
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

import { useStore } from '../index';
import { rpc } from '../../lib/methods';

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
    engineState: null,
  });
});

describe('R412 — the verdict is read back from the polled state', () => {
  test('a poll fills the chip even though no run_start ever arrived', async () => {
    // ★ THIS IS THE RELOAD CASE ★ the user re-opens the app: no
    // run_start frame will ever arrive again for the turn that is
    // already in the transcript, so the chip can only be filled from
    // the state.
    await useStore.getState().refreshEngineState();

    const s = useStore.getState();
    expect(s.intentKind).toBe('code_change');
    expect(s.intentLabel).toBe('代码生成');
    expect(s.intentReason).toBe('implementation verb: create the');
    expect(s.profileName).toBe('builder');
    expect(s.profileLabel).toBe('写代码');
  });

  test('the picker vocabulary survives the reload', async () => {
    await useStore.getState().refreshEngineState();

    const s = useStore.getState();
    // ★ WITHOUT THIS THE PICKER IS EMPTY ★ the chip would render and
    // its dropdown would offer nothing — "one click to correct" with
    // nothing to click.
    expect(s.profiles.length).toBe(2);
    expect(s.profiles[0]).toMatchObject({ name: 'builder', label: '写代码' });
  });

  test('a null verdict does NOT erase the one the event just set', async () => {
    // A session that has not run yet reports nulls. Wiping the header
    // on every poll would make the chip flicker, and would also throw
    // away a verdict the run_start frame delivered a moment ago.
    rpc.__test_dispatchNotification('stream_event', {
      runId: 'run-7',
      sessionId: 's1',
      event: {
        type: 'run_start',
        runId: 'run-7',
        model: 'stub',
        intent: 'research',
        intentLabel: '概念搜索',
        intentReason: 'failure symptom',
        profile: 'researcher',
        profileLabel: '查资料',
      },
    });

    invokeMock.mockImplementationOnce(async () => ({
      sessionId: 's1',
      model: 'stub',
      permissionMode: 'DEFAULT',
      intent: null,
      intentLabel: null,
      intentReason: null,
      profile: null,
      profileLabel: null,
      profileOverride: null,
      profiles: [],
    }));
    await useStore.getState().refreshEngineState();

    const s = useStore.getState();
    expect(s.intentKind, 'the event verdict must survive a null poll').toBe('research');
    expect(s.intentLabel).toBe('概念搜索');
    expect(s.intentReason).toBe('failure symptom');
    expect(s.profileName).toBe('researcher');
  });

  test('an explicit "auto" override IS applied — null is an answer there', async () => {
    useStore.setState({ profileOverride: 'researcher' });

    invokeMock.mockImplementationOnce(async () => ({
      sessionId: 's1',
      model: 'stub',
      permissionMode: 'DEFAULT',
      intent: 'code_change',
      intentLabel: '代码生成',
      profile: 'builder',
      profileLabel: '写代码',
      profileOverride: null,
      profiles: [],
    }));
    await useStore.getState().refreshEngineState();

    // ★ THE LOCK ICON MUST NOT LIE ★ the daemon says "auto", so the
    // client may have been showing a lock that is not in force.
    expect(useStore.getState().profileOverride).toBeNull();
  });

  test('a daemon that says nothing about overrides does not erase the local one', async () => {
    // An OLDER daemon (the desktop can talk to a daemon it was not
    // built against during a hot update) sends no profileOverride key
    // at all. "Absent" is not "auto" — it is "I do not know".
    useStore.setState({ profileOverride: 'researcher' });

    invokeMock.mockImplementationOnce(async () => ({
      sessionId: 's1',
      model: 'stub',
      permissionMode: 'DEFAULT',
      intent: 'code_change',
      intentLabel: '代码生成',
      profile: 'builder',
      profileLabel: '写代码',
    }));
    await useStore.getState().refreshEngineState();

    expect(useStore.getState().profileOverride).toBe('researcher');
  });
});

describe('R412 — the verdict is per-session', () => {
  test('switching sessions clears the verdict', async () => {
    await useStore.getState().refreshEngineState();
    expect(useStore.getState().intentKind).toBe('code_change');

    await useStore.getState().switchSession('s2');

    const s = useStore.getState();
    // ★ NO CROSS-SESSION LEAK ★ the chip is the one thing the user
    // trusts to describe the CURRENT turn. Showing session A's
    // verdict over session B is a lie the user cannot detect.
    expect(s.intentKind).toBeNull();
    expect(s.intentLabel).toBeNull();
    expect(s.intentReason).toBeNull();
    expect(s.profileName).toBeNull();
    expect(s.profileLabel).toBeNull();
  });

  test('the run_start of the NEW session fills the chip again', async () => {
    await useStore.getState().switchSession('s2');

    rpc.__test_dispatchNotification('stream_event', {
      runId: 'run-9',
      sessionId: 's2',
      event: {
        type: 'run_start',
        runId: 'run-9',
        model: 'stub',
        intent: 'answer',
        intentLabel: '知识问答',
        intentReason: 'no implementation verb',
        profile: 'analyst',
        profileLabel: '知识问答',
      },
    });

    const s = useStore.getState();
    expect(s.intentKind).toBe('answer');
    expect(s.intentReason).toBe('no implementation verb');
    expect(s.profileName).toBe('analyst');
  });
});
