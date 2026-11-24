import { describe, test, expect, beforeEach, vi } from 'vitest';

/**
 * R401 — the desktop shows the daemon's verdict and can override it.
 *
 * The user's second "fatal" complaint: "意图识别缺失" — a concept
 * question was treated as a code task and the agent produced a pile of
 * unrelated files.
 *
 * The fix lives in the daemon (R401a): it classifies the prompt, picks
 * a posture, and PRUNES the tool pool so a forbidden tool is not
 * callable. The desktop's job is narrower but not optional:
 *
 *   1. SHOW what the daemon decided ("意图：概念搜索 · 档案：researcher"),
 *      because a silent decision is one the user cannot correct;
 *   2. let the user OVERRIDE it with one click — a mis-classified turn
 *      is expensive, and re-prompting is not a correction;
 *   3. never SWALLOW a failed override (the R400 lesson: a silent
 *      failure makes the UI lie about what the daemon is doing).
 *
 * These tests drive the REAL store handlers (through the rpc
 * singleton's notification seam) and the REAL `invoke` boundary.
 */

const { invokeMock } = vi.hoisted(() => ({
  invokeMock: vi.fn(async (_cmd: string, args: any): Promise<any> => {
    const method = args?.method;
    if (method === 'setProfile') {
      return {
        ok: true,
        sessionId: 's1',
        profile: args?.params?.profile === 'auto' ? null : args?.params?.profile,
        profiles: [
          { name: 'builder', label: '写代码', description: '实现需求' },
          { name: 'researcher', label: '查资料', description: '先查后答' },
        ],
      };
    }
    if (method === 'query') return { runId: 'run-7', accepted: true };
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
    profileName: null,
    profileLabel: null,
    profileOverride: null,
    profiles: [],
  });
});

describe('R401 — the daemon verdict reaches the UI', () => {
  test('the run_start frame carries the intent and the profile into the store', () => {
    rpc.__test_dispatchNotification('stream_event', {
      runId: 'run-7',
      sessionId: 's1',
      event: {
        type: 'run_start',
        runId: 'run-7',
        model: 'stub',
        intent: 'research',
        intentLabel: '概念搜索',
        profile: 'researcher',
        profileLabel: '查资料',
      },
    });

    const s = useStore.getState();
    expect(s.intentKind).toBe('research');
    expect(s.intentLabel).toBe('概念搜索');
    expect(s.profileName).toBe('researcher');
    expect(s.profileLabel).toBe('查资料');
  });

  test('a run_start WITHOUT a verdict leaves the previous one alone', () => {
    // A subagent's inner loop also emits run_start, and it carries no
    // verdict. Wiping the user's verdict on it would make the chip
    // flicker away mid-turn.
    rpc.__test_dispatchNotification('stream_event', {
      runId: 'run-7',
      sessionId: 's1',
      event: {
        type: 'run_start', runId: 'run-7', model: 'stub',
        intent: 'research', intentLabel: '概念搜索',
        profile: 'researcher', profileLabel: '查资料',
      },
    });
    rpc.__test_dispatchNotification('stream_event', {
      runId: 'run-7',
      sessionId: 's1',
      event: { type: 'run_start', runId: 'run-7', model: 'stub' },
    });

    const s = useStore.getState();
    expect(s.intentKind).toBe('research');
    expect(s.profileName).toBe('researcher');
  });
});

describe('R401 — the one-click override', () => {
  test('setting a profile issues the RPC and stores the override', async () => {
    await useStore.getState().setProfileOverride('researcher');

    const call = invokeMock.mock.calls.find((c: any[]) => c[1]?.method === 'setProfile');
    expect(call, 'the override must reach the daemon').toBeTruthy();
    expect((call as any[])[1].params).toMatchObject({ profile: 'researcher', sessionId: 's1' });
    expect(useStore.getState().profileOverride).toBe('researcher');
  });

  test('"auto" hands the decision back to the classifier', async () => {
    useStore.setState({ profileOverride: 'researcher' });
    await useStore.getState().setProfileOverride(null);

    const call = invokeMock.mock.calls.find((c: any[]) => c[1]?.method === 'setProfile');
    expect((call as any[])[1].params).toMatchObject({ profile: 'auto' });
    expect(useStore.getState().profileOverride).toBeNull();
  });

  test('the picker renders the DAEMON\'s vocabulary, not a local copy', async () => {
    // The profile names + labels live in ONE place — the daemon's
    // registry. A renderer that kept its own list would drift, and the
    // drift would show up as an override the daemon rejects.
    await useStore.getState().setProfileOverride('researcher');
    const profiles = useStore.getState().profiles;
    expect(profiles.length).toBe(2);
    expect(profiles[1]).toMatchObject({ name: 'researcher', label: '查资料' });
  });

  test('a REJECTED override is visible — never swallowed', async () => {
    // ★ The R400 lesson applied to the new feature. An override that
    // silently did not take leaves the user staring at a
    // mis-classified turn with no way to tell why.
    invokeMock.mockImplementationOnce(async (_cmd: string, args: any) => {
      if (args?.method === 'setProfile') {
        return { ok: false, error: 'unknown profile: reseacher' };
      }
      return {};
    });

    await useStore.getState().setProfileOverride('reseacher');

    const s = useStore.getState();
    const failure = s.messages.find((m) => m.content.includes('档案切换失败'));
    expect(failure, 'a rejected override must surface as a message').toBeTruthy();
    expect(failure!.isError).toBe(true);
    expect(failure!.content).toContain('reseacher');
    // and the override must NOT be applied locally — the daemon is
    // the authority on what is actually in force.
    expect(s.profileOverride).toBeNull();
  });

  test('a thrown call is also visible', async () => {
    invokeMock.mockImplementationOnce(async () => {
      throw new Error('daemon unreachable');
    });

    await useStore.getState().setProfileOverride('researcher');

    const failure = useStore.getState().messages.find((m) => m.content.includes('档案切换失败'));
    expect(failure).toBeTruthy();
    expect(failure!.content).toContain('daemon unreachable');
  });
});
