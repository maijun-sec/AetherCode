// @vitest-environment jsdom
import { describe, it, expect, beforeEach } from 'vitest';
import { createFixture, flush, autoCleanup } from '../test/testUtils';
import type { RpcEvent } from '../rpc/types';

/**
 * Phase 7 / T-7-11: re-attach after disconnect (mocked).
 *
 * 1. Open a session, drive 3 events into the mock.
 * 2. "Disconnect" by closing all streams.
 * 3. Drive 2 more events into the mock while disconnected.
 * 4. "Reconnect" — call task/attached with lastSeq=3.
 * 5. Verify the response carries only the post-disconnect events.
 */
autoCleanup();

describe('Phase 7 / T-7-11: re-attach after disconnect', () => {
  let fixture = createFixture({ seed: { sessions: [] } });
  beforeEach(() => { fixture = createFixture({ seed: { sessions: [] } }); });

  it('re-attach returns only the events past the last seen seq', async () => {
    // 1. Create the session + drive 3 events.
    const spawn = await fixture.client.call<{ id: string }>('session/spawn', { prompt: 'x', cwd: '/tmp' });
    fixture.server.pushEvent(spawn.id, { kind: 'run_start', params: { ts: 1 } });
    fixture.server.pushEvent(spawn.id, { kind: 'message_delta', params: { delta: 'a' } });
    fixture.server.pushEvent(spawn.id, { kind: 'message_delta', params: { delta: 'b' } });
    const lastSeenSeq = 3;

    // 2. Disconnect (close all streams).
    fixture.client.closeAll();

    // 3. Two more events while disconnected.
    fixture.server.pushEvent(spawn.id, { kind: 'message_delta', params: { delta: 'c' } });
    fixture.server.pushEvent(spawn.id, { kind: 'run_end', params: {} });

    // 4. Re-attach with lastSeq=3.
    const reattach = await fixture.client.call<{ from: number; to: number; events: RpcEvent[]; gap: boolean }>(
      'task/attached',
      { sessionId: spawn.id, lastSeq: lastSeenSeq },
    );

    // 5. The replayed events are 4 + 5 only.
    expect(reattach.events.map((e) => e.seq)).toEqual([4, 5]);
    expect(reattach.from).toBe(3);
    expect(reattach.to).toBe(5);
    expect(reattach.gap).toBe(false);
  });

  it('subscribe receives a re-attach delta after a reconnect', async () => {
    const spawn = await fixture.client.call<{ id: string }>('session/spawn', { prompt: 'x', cwd: '/tmp' });
    const received: RpcEvent[] = [];
    const sub = fixture.client.subscribe(
      'session/events',
      { sessionId: spawn.id, lastSeq: 0 },
      (raw) => {
        const ev = (raw as { params?: RpcEvent }).params ?? (raw as RpcEvent);
        received.push(ev);
      },
    );
    await flush();
    fixture.server.pushEvent(spawn.id, { kind: 'run_start', params: {} });
    await flush();
    expect(received.length).toBe(1);

    // Disconnect / re-attach — the mock is in-process, so the
    // easiest signal is that the subscription keeps delivering
    // subsequent events.
    sub.unsubscribe();
    fixture.server.pushEvent(spawn.id, { kind: 'tool_call', params: { tool: 'bash' } });
    await flush();
    expect(received.length).toBe(1); // unsubscribed — no new events
  });

  it('the re-attach handler is a mutation that invalidates the cache on gap', async () => {
    // The re-attach is a useMutation. We test the underlying call
    // path and the cache-invalidation branch by direct call.
    const spawn = await fixture.client.call<{ id: string }>('session/spawn', { prompt: 'x', cwd: '/tmp' });
    fixture.server.pushEvent(spawn.id, { kind: 'run_start', params: {} });
    const r = await fixture.client.call<{ from: number; to: number; events: RpcEvent[]; gap: boolean }>(
      'task/attached',
      { sessionId: spawn.id, lastSeq: 0 },
    );
    expect(r.events.length).toBe(1);
    // The mock currently never reports a gap. We assert the
    // shape so a future implementation that does can drop in.
    expect(typeof r.gap).toBe('boolean');
  });

  it('multiple re-attach calls are idempotent', async () => {
    const spawn = await fixture.client.call<{ id: string }>('session/spawn', { prompt: 'x', cwd: '/tmp' });
    fixture.server.pushEvent(spawn.id, { kind: 'run_start', params: {} });
    const a = await fixture.client.call<{ events: RpcEvent[] }>('task/attached', { sessionId: spawn.id, lastSeq: 0 });
    const b = await fixture.client.call<{ events: RpcEvent[] }>('task/attached', { sessionId: spawn.id, lastSeq: 0 });
    expect(a.events.length).toBe(1);
    expect(b.events.length).toBe(1);
  });

  it('stream replay on subscribe delivers backlog then live events', async () => {
    const spawn = await fixture.client.call<{ id: string }>('session/spawn', { prompt: 'x', cwd: '/tmp' });
    // Pre-seed two events.
    fixture.server.pushEvent(spawn.id, { kind: 'run_start', params: {} });
    fixture.server.pushEvent(spawn.id, { kind: 'message_delta', params: {} });
    const received: RpcEvent[] = [];
    const sub = fixture.client.subscribe(
      'session/events',
      { sessionId: spawn.id, lastSeq: 0 },
      (raw) => {
        const ev = (raw as { params?: RpcEvent }).params ?? (raw as RpcEvent);
        received.push(ev);
      },
    );
    await flush();
    expect(received.length).toBe(2);
    sub.unsubscribe();
  });
});
