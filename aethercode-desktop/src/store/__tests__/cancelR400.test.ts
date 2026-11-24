import { describe, test, expect, beforeEach, vi } from 'vitest';

/**
 * R400 — the stop button must actually stop things.
 *
 * The user reported: "我点右下角的图标，明明已经停止了，就是其搜索有点儿天马行空…
 * 结果隔了一会儿，自己直接又继续跑了，好像没办法停，或者停了很快又拉起来了".
 *
 * The desktop's `rpc.cancel()` sent NO parameters. The daemon's cancel
 * handler REQUIRED a runId and answered `-32602 missing required
 * string field: runId` — and the desktop swallowed that error with an
 * empty `catch {}`. So the UI flipped `isStreaming` to false (it LOOKED
 * stopped) while the daemon kept streaming, and because the run never
 * ended with the stop reason `cancelled`, the boulder hook's auto-continue
 * was still allowed to fire two seconds later.
 *
 * These tests drive the REAL store handlers (through the rpc
 * singleton's notification seam) and the REAL `invoke` boundary, so
 * they pin the shipping behaviour rather than a copy of it.
 */

// `vi.mock` factories are hoisted above the module body's top-level
// statements, so the mock has to be created through `vi.hoisted` — a
// plain `const` here would be in the TDZ when the factory runs.
const { invokeMock } = vi.hoisted(() => ({
  invokeMock: vi.fn(async (_cmd: string, args: any): Promise<any> => {
    const method = args?.method;
    if (method === 'cancel') return { cancelled: true, runId: 'run-7', continuationStopped: true };
    if (method === 'query') return { runId: 'run-7', accepted: true };
    if (method === 'setContinuationStopped') return { sessionId: 's1', stopped: true };
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
  });
});

describe('R400 — cancelQuery carries the run id', () => {
  test('the cancel RPC is called WITH the run id the daemon handed us', async () => {
    // The daemon's `query` answers `{runId, accepted}`. Pre-R400 the
    // desktop threw that away and could only send an empty cancel.
    useStore.setState({ activeRunId: 'run-7' });

    await useStore.getState().cancelQuery();

    const cancelCall = invokeMock.mock.calls.find(
      (c: any[]) => c[1]?.method === 'cancel',
    );
    expect(cancelCall, 'cancelQuery must issue a cancel RPC').toBeTruthy();
    expect((cancelCall as any[])[1].params).toMatchObject({
      runId: 'run-7',
      sessionId: 's1',
    });
  });

  test('a refused cancel is VISIBLE — never swallowed', async () => {
    // Pre-R400 the failure was eaten by `catch {}`. The user pressed
    // stop, the UI flipped, and nothing said the run was still going.
    invokeMock.mockImplementationOnce(async (_cmd: string, args: any) => {
      if (args?.method === 'cancel') return { cancelled: false, reason: 'no such runId' };
      return {};
    });

    await useStore.getState().cancelQuery();

    const s = useStore.getState();
    const failure = s.messages.find((m) => m.content.includes('停止失败'));
    expect(
      failure,
      'a refused cancel must surface as a message — a silent failure is what made '
        + 'the stop button look broken for so long',
    ).toBeTruthy();
    expect(failure!.isError).toBe(true);
    expect(failure!.content).toContain('no such runId');
  });

  test('a thrown cancel call is also visible', async () => {
    invokeMock.mockImplementationOnce(async () => {
      throw new Error('daemon unreachable');
    });

    await useStore.getState().cancelQuery();

    const failure = useStore.getState().messages.find((m) => m.content.includes('停止失败'));
    expect(failure).toBeTruthy();
    expect(failure!.content).toContain('daemon unreachable');
  });
});

describe('R400 — a cancelled run is not a failure', () => {
  test('stopReason "cancelled" does not open the retry slot', () => {
    // The daemon's own taxonomy says a cancelled run is NOT an error
    // (StreamEvent.isErrorOutcomeOf: "cancelled — the user asked for
    // it; not a failure"), and it puts `isError: false` on the wire.
    // The desktop's local fallback list used to carry 'cancelled'
    // anyway, and because the local list was consulted even when the
    // daemon HAD expressed a verdict, every user-initiated stop was
    // rendered as "● Failed (cancelled)" with a ↻ retry button.
    //
    // That was invisible while the cancel never worked. Now that it
    // does, it would fire on every single stop.
    rpc.__test_dispatchNotification('stream_event', {
      runId: 'run-7',
      sessionId: 's1',
      event: { type: 'run_end', stopReason: 'cancelled', isError: false, stopKind: 'cancelled' },
    });

    const s = useStore.getState();
    expect(s.lastFailedStopReason, 'a cancelled run must not be classified as a failure').toBeNull();
    expect(s.lastFailedPrompt).toBeNull();
    expect(s.isStreaming).toBe(false);
    expect(s.currentActivity?.label).toBe('⏹ 已停止');
  });

  test('a REAL failure still opens the retry slot', () => {
    // The converse must stay true — the gate must not have been
    // weakened into "nothing is a failure any more".
    rpc.__test_dispatchNotification('stream_event', {
      runId: 'run-8',
      sessionId: 's1',
      event: { type: 'run_end', stopReason: 'error: upstream 500', isError: true, stopKind: 'error' },
    });

    const s = useStore.getState();
    expect(s.lastFailedStopReason).toBe('error');
  });

  test('the run id is released when the run ends', () => {
    // The run id has to be SET first. An earlier version of this
    // test never did, so `activeRunId` was already null and the
    // assertion below passed whether or not the run_end handler
    // released it — the mutation check caught that (removing the
    // release left the test green).
    useStore.setState({ activeRunId: 'run-7' });
    rpc.__test_dispatchNotification('stream_event', {
      runId: 'run-7',
      sessionId: 's1',
      event: { type: 'run_end', stopReason: 'end_turn', isError: false, stopKind: 'end_turn' },
    });
    expect(useStore.getState().activeRunId, 'a finished run must not stay cancelable').toBeNull();
  });
});

describe('R400 — the auto-continue countdown is visible and stoppable', () => {
  test('the countdown notification reaches the toast layer', () => {
    // Pre-R400 the desktop had ZERO references to
    // `todo_continuation_countdown`, so the user saw the output simply
    // resume with no explanation and no way to stop it.
    rpc.__test_dispatchNotification('todo_continuation_countdown', {
      sessionId: 's1',
      runId: 'run-7',
      incompleteCount: 3,
      totalCount: 5,
      remainingMs: 2000,
      kind: 'countdown-start',
    });

    const notes = useStore.getState().notifications;
    const countdown = notes.find((n) => n.source === 'continuation-countdown');
    expect(countdown, 'the countdown must reach the user').toBeTruthy();
    expect(countdown!.message).toContain('3');
    expect(countdown!.actions?.length ?? 0).toBeGreaterThan(0);
  });

  test('the stop button calls setContinuationStopped', () => {
    rpc.__test_dispatchNotification('todo_continuation_countdown', {
      sessionId: 's1',
      runId: 'run-7',
      incompleteCount: 3,
      totalCount: 5,
      remainingMs: 2000,
      kind: 'countdown-start',
    });

    const countdown = useStore.getState().notifications.find(
      (n) => n.source === 'continuation-countdown',
    )!;
    countdown.actions![0].onClick();

    const stopCall = invokeMock.mock.calls.find(
      (c: any[]) => c[1]?.method === 'setContinuationStopped',
    );
    expect(stopCall, 'the stop button must disarm the auto-continue').toBeTruthy();
    expect((stopCall as any[])[1].params).toMatchObject({ stopped: true, sessionId: 's1' });
    expect(
      useStore.getState().notifications.some((n) => n.source === 'continuation-countdown'),
      'the countdown toast must disappear once it is stopped',
    ).toBe(false);
  });

  test('a new run clears the countdown toast', () => {
    rpc.__test_dispatchNotification('todo_continuation_countdown', {
      sessionId: 's1',
      runId: 'run-7',
      incompleteCount: 3,
      totalCount: 5,
      remainingMs: 2000,
      kind: 'countdown-start',
    });
    expect(
      useStore.getState().notifications.some((n) => n.source === 'continuation-countdown'),
    ).toBe(true);

    rpc.__test_dispatchNotification('stream_event', {
      runId: 'cont-1',
      sessionId: 's1',
      event: { type: 'run_start', runId: 'cont-1', model: 'stub' },
    });

    expect(
      useStore.getState().notifications.some((n) => n.source === 'continuation-countdown'),
      'the countdown is over once the run starts',
    ).toBe(false);
  });
});
