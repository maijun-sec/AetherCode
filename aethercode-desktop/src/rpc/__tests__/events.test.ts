import { describe, it, expect, beforeEach } from 'vitest';
import { readFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { MockRpcServer } from '../MockRpcServer';
import { JsonRpcClient } from '../client';
import { subscribeEvents, subscribeKind, subscribeKinds } from '../events';
import type { RpcEvent } from '../types';

/**
 * Phase 3 (T-3-06): subscribeEvents — the non-React variant.
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
})();
function read(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

describe('Phase 3 / T-3-06: events.ts (source-level)', () => {
  it('events.ts exists', () => {
    expect(existsSync(join(root, 'src/rpc/events.ts'))).toBe(true);
  });

  it('exports subscribeEvents, subscribeKind, subscribeKinds', () => {
    const src = read('src/rpc/events.ts');
    expect(src).toMatch(/export\s+function\s+subscribeEvents\b/);
    expect(src).toMatch(/export\s+function\s+subscribeKind\b/);
    expect(src).toMatch(/export\s+function\s+subscribeKinds\b/);
  });

  it('throws if sessionId is null/undefined', () => {
    const src = read('src/rpc/events.ts');
    expect(src).toMatch(/throw new Error\(['"]subscribeEvents: sessionId is required/);
    expect(src).toMatch(/throw new Error\(['"]subscribeKind: sessionId is required/);
  });
});

describe('Phase 3 / T-3-06: events.ts behaviour', () => {
  let mock: MockRpcServer;
  let client: JsonRpcClient;
  beforeEach(() => {
    mock = new MockRpcServer({ sessionId: 's-1' });
    client = new JsonRpcClient();
    mock.installInto(client);
  });

  it('subscribeEvents delivers a pushed event to the listener', async () => {
    mock.ensureSession('s-1');
    const got: RpcEvent[] = [];
    const unsub = subscribeEvents('s-1', (ev) => got.push(ev), { client });
    mock.pushEvent('s-1', { kind: 'message_delta', params: { delta: 'a' } });
    await new Promise((r) => setTimeout(r, 0));
    expect(got.length).toBe(1);
    expect(got[0].kind).toBe('message_delta');
    unsub();
  });

  it('subscribeKind filters out other kinds', async () => {
    mock.ensureSession('s-1');
    const got: RpcEvent[] = [];
    const unsub = subscribeKind('s-1', 'todo_update', (ev) => got.push(ev), { client });
    mock.pushEvent('s-1', { kind: 'message_delta', params: {} });
    mock.pushEvent('s-1', { kind: 'todo_update', params: { todos: [] } });
    await new Promise((r) => setTimeout(r, 0));
    expect(got.length).toBe(1);
    expect(got[0].kind).toBe('todo_update');
    unsub();
  });

  it('subscribeKinds accepts an array of allowed kinds', async () => {
    mock.ensureSession('s-1');
    const got: RpcEvent[] = [];
    const unsub = subscribeKinds('s-1', ['run_start', 'run_end'], (ev) => got.push(ev), { client });
    mock.pushEvent('s-1', { kind: 'run_start', params: {} });
    mock.pushEvent('s-1', { kind: 'tool_call', params: {} });
    mock.pushEvent('s-1', { kind: 'run_end', params: {} });
    await new Promise((r) => setTimeout(r, 0));
    expect(got.map((e) => e.kind)).toEqual(['run_start', 'run_end']);
    unsub();
  });

  it('unsubscribe stops further deliveries', async () => {
    mock.ensureSession('s-1');
    const got: RpcEvent[] = [];
    const unsub = subscribeEvents('s-1', (ev) => got.push(ev), { client });
    mock.pushEvent('s-1', { kind: 'tool_call', params: {} });
    await new Promise((r) => setTimeout(r, 0));
    unsub();
    mock.pushEvent('s-1', { kind: 'tool_call', params: {} });
    await new Promise((r) => setTimeout(r, 0));
    expect(got.length).toBe(1);
  });
});
