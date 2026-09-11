import { describe, it, expect, beforeEach } from 'vitest';
import { readFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { MockRpcServer, ok, err } from '../MockRpcServer';
import { JsonRpcClient } from '../client';
import type { RpcEvent } from '../types';

/**
 * Phase 3 (T-3-02): MockRpcServer — functional tests.
 *
 * Exercises the round-trip:
 *   1. register a handler
 *   2. installInto(client)
 *   3. client.call(method, params) → typed result
 *   4. push a session event, verify the subscriber fires
 *   5. re-attach: ask for events since seq=N, verify the
 *      client sees N+1 onwards
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
})();
function read(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

describe('Phase 3 / T-3-02: MockRpcServer shape', () => {
  it('MockRpcServer.ts exists', () => {
    expect(existsSync(join(root, 'src/rpc/MockRpcServer.ts'))).toBe(true);
  });

  it('exports MockRpcServer class + ok/err helpers', () => {
    const src = read('src/rpc/MockRpcServer.ts');
    expect(src).toMatch(/export\s+class\s+MockRpcServer\b/);
    expect(src).toMatch(/export\s+function\s+ok\b/);
    expect(src).toMatch(/export\s+function\s+err\b/);
  });

  it('exposes handle/remove/reset lifecycle', () => {
    const src = read('src/rpc/MockRpcServer.ts');
    expect(src).toMatch(/handle\s*\(\s*method/);
    expect(src).toMatch(/remove\s*\(\s*method/);
    expect(src).toMatch(/reset\s*\(\s*\)/);
  });

  it('implements pushEvent with monotonic seq', () => {
    const src = read('src/rpc/MockRpcServer.ts');
    expect(src).toMatch(/pushEvent\s*\(/);
    expect(src).toMatch(/lastSeq\s*\+=\s*1/);
  });

  it('implements streamEvents as an AsyncGenerator', () => {
    const src = read('src/rpc/MockRpcServer.ts');
    expect(src).toMatch(/async\s*\*\s*streamEvents\s*\(/);
    expect(src).toMatch(/sinceSeq/);
  });

  it('installs into a JsonRpcClient by overriding fetch + EventSource', () => {
    const src = read('src/rpc/MockRpcServer.ts');
    expect(src).toMatch(/installInto\s*\(\s*client\s*:\s*JsonRpcClient/);
    expect(src).toMatch(/fetchImpl/);
    expect(src).toMatch(/eventSourceImpl/);
  });

  it('ships default handlers for the major RPCs', () => {
    const src = read('src/rpc/MockRpcServer.ts');
    expect(src).toMatch(/this\.handle\(['"]listSessions['"]/);
    expect(src).toMatch(/this\.handle\(['"]session\/show['"]/);
    expect(src).toMatch(/this\.handle\(['"]task\/list['"]/);
    expect(src).toMatch(/this\.handle\(['"]grants\/list['"]/);
    expect(src).toMatch(/this\.handle\(['"]model\/list['"]/);
    expect(src).toMatch(/this\.handle\(['"]workflow\/list['"]/);
    expect(src).toMatch(/this\.handle\(['"]task\/attached['"]/);
  });
});

describe('Phase 3 / T-3-02: MockRpcServer behaviour', () => {
  let mock: MockRpcServer;
  let client: JsonRpcClient;
  beforeEach(() => {
    mock = new MockRpcServer({ sessionId: 's-1' });
    client = new JsonRpcClient();
    mock.installInto(client);
  });

  it('handles a method and returns the typed result', async () => {
    mock.handle('ping', () => ({ ok: true, ts: 1234 }));
    const r = await client.call<{ ok: true; ts: number }>('ping');
    expect(r.ok).toBe(true);
    expect(r.ts).toBe(1234);
  });

  it('returns a JSON-RPC error envelope for an unknown method', async () => {
    let caught: any = null;
    try {
      await client.call('not-a-method');
    } catch (e: any) {
      caught = e;
    }
    expect(caught).toBeTruthy();
    expect(caught.code).toBe(-32601);
    expect(String(caught.message)).toContain('not-a-method');
  });

  it('lets a handler throw and still produces a typed error envelope', async () => {
    mock.handle('boom', () => { throw new Error('kaboom'); });
    let caught: any = null;
    try {
      await client.call('boom');
    } catch (e: any) {
      caught = e;
    }
    expect(caught).toBeTruthy();
    expect(String(caught.message)).toContain('kaboom');
    expect(caught.code).toBe(-32603);
  });

  it('routes sessionId-aware params into the right session', async () => {
    mock.newSession('s-2');
    let seen: string | null = null;
    mock.handle('echo', (_params, ctx) => {
      seen = ctx.session?.id ?? null;
      return ctx.session?.id;
    });
    const r = await client.call<any>('echo', { sessionId: 's-2' });
    expect(seen).toBe('s-2');
    expect(r).toBe('s-2');
  });

  it('pushEvent increments the per-session seq', () => {
    mock.ensureSession('s-3');
    const a = mock.pushEvent('s-3', { kind: 'message_delta', params: { delta: 'a' } });
    const b = mock.pushEvent('s-3', { kind: 'message_delta', params: { delta: 'b' } });
    expect(b.seq).toBe(a.seq + 1);
  });

  it('subscribe delivers a queued event to the listener', async () => {
    mock.ensureSession('s-4');
    const received: RpcEvent[] = [];
    const unsub = mock.subscribe('s-4', (ev) => received.push(ev));
    mock.pushEvent('s-4', { kind: 'todo_update', params: { todos: [] } });
    await new Promise((r) => setTimeout(r, 0));
    expect(received.length).toBe(1);
    expect(received[0].kind).toBe('todo_update');
    unsub();
  });

  it('unsubscribe stops further deliveries', async () => {
    mock.ensureSession('s-5');
    const received: RpcEvent[] = [];
    const unsub = mock.subscribe('s-5', (ev) => received.push(ev));
    mock.pushEvent('s-5', { kind: 'tool_call', params: { tool: 'read_file' } });
    await new Promise((r) => setTimeout(r, 0));
    unsub();
    mock.pushEvent('s-5', { kind: 'tool_call', params: { tool: 'bash' } });
    await new Promise((r) => setTimeout(r, 0));
    expect(received.length).toBe(1);
  });

  it('default listSessions handler returns an empty list when no seed', async () => {
    const r = await client.call<{ sessions: any[]; total: number; hasMore: boolean }>('listSessions', {});
    expect(r.sessions).toEqual([]);
    expect(r.total).toBe(0);
  });

  it('ok/err helpers build the right envelope shape', () => {
    expect(ok(1, { x: 1 })).toEqual({ jsonrpc: '2.0', id: 1, result: { x: 1 } });
    expect(err(2, -1, 'oops', { extra: true })).toEqual({
      jsonrpc: '2.0', id: 2, error: { code: -1, message: 'oops', data: { extra: true } },
    });
  });
});
