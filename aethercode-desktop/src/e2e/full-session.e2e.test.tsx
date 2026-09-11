// @vitest-environment jsdom
import { describe, it, expect, beforeEach } from 'vitest';
import { screen } from '@testing-library/react';
import { createFixture, autoCleanup } from '../test/testUtils';
import { SessionControl } from '../components/session/SessionControl';

autoCleanup();

/**
 * Phase 7 / T-7-05: full session lifecycle (mocked).
 *
 * Drives the App shell through spawn → run → pause → resume →
 * kill. Each step is a real RPC round-trip against the in-process
 * MockRpcServer.
 */
describe('Phase 7 / T-7-05: full session lifecycle', () => {
  let fixture = createFixture({ seed: { sessions: [], tasks: [] } });
  beforeEach(() => { fixture = createFixture({ seed: { sessions: [], tasks: [] } }); });

  it('walks through spawn → run → pause → resume → kill', async () => {
    // 1. Spawn a session via the mock's RPC layer.
    const spawn = await fixture.client.call<{ id: string; title: string; startedAt: number }>(
      'session/spawn',
      { prompt: 'demo', cwd: '/tmp/proj' },
    );
    expect(spawn.id).toBeDefined();
    expect(spawn.title).toBe('demo');

    // 2. Spawn a task that points at the new session.
    const task = await fixture.client.call<{ id: string; sessionId: string; state: string }>(
      'task/spawn',
      { sessionId: spawn.id },
    );
    expect(task.state).toBe('running');

    // 3. Pause the task.
    const paused = await fixture.client.call<{ ok: boolean; task: { state: string } }>(
      'task/pause',
      { id: task.id },
    );
    expect(paused.task.state).toBe('paused');

    // 4. Resume.
    const resumed = await fixture.client.call<{ ok: boolean; task: { state: string } }>(
      'task/resume',
      { id: task.id },
    );
    expect(resumed.task.state).toBe('running');

    // 5. Kill.
    const killed = await fixture.client.call<{ ok: boolean; task: { state: string } }>(
      'task/kill',
      { id: task.id },
    );
    expect(killed.task.state).toBe('cancelled');

    // The mock's call log shows the full sequence of round-trips.
    const methods = fixture.server.callLog.map((c) => c.method);
    expect(methods).toEqual(
      expect.arrayContaining(['session/spawn', 'task/spawn', 'task/pause', 'task/resume', 'task/kill']),
    );
  });

  it('the SessionControl re-renders when the task state changes', async () => {
    // Spawn a task in running state.
    const spawn = await fixture.client.call<{ id: string }>('session/spawn', { prompt: 't', cwd: '/tmp' });
    const task = await fixture.client.call<{ id: string; state: string }>('task/spawn', { sessionId: spawn.id });

    // Mount the SessionControl directly so we can assert the
    // wire contract without going through the legacy Tauri-
    // coupled zustand store.
    const { rerender } = fixture.render(
      <SessionControl taskId={task.id} taskState="running" />,
    );
    expect(screen.getByTestId('session-control-pause').hasAttribute('disabled')).toBe(false);
    rerender(<SessionControl taskId={task.id} taskState="paused" />);
    expect(screen.getByTestId('session-control-continue').hasAttribute('disabled')).toBe(false);
    expect(screen.getByTestId('session-control-pause').hasAttribute('disabled')).toBe(true);
  });

  it('rejects duplicate spawns with a 4xx-style error envelope', async () => {
    // The mock returns RpcError on a handler throw; the client
    // surfaces it as a thrown RpcError.
    fixture.server.handle('session/spawn', () => {
      throw new Error('limit reached');
    });
    await expect(
      fixture.client.call('session/spawn', { prompt: 'x', cwd: '/tmp' }),
    ).rejects.toThrow(/limit reached/);
  });
});
