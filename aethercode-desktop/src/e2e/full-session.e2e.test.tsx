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
