// Phase 3: JSON-RPC client.
//
// Wraps `fetch` for one-shot calls and `EventSource` for live
// event streams. In tests the `MockRpcServer.installInto(client)`
// helper replaces both implementations with in-process fakes so
// no real HTTP is exercised.
//
// The client is intentionally minimal — TanStack Query sits on top
// of it for caching and invalidation. This file is the boundary
// between the React layer and the I/O layer.

import { ok as buildOk, err as buildErr, RpcError, type RpcRequest, type RpcResponse } from './types';

export type FetchImpl = (input: string, init?: RequestInit) => Promise<{
  ok: boolean;
  status: number;
  statusText: string;
  text(): Promise<string>;
  json(): Promise<unknown>;
}>;

export type EventSourceImpl = (
  url: string,
  init?: { withCredentials?: boolean },
) => EventSourceLike;

export interface EventSourceLike {
  url: string;
  readyState: number;
  onmessage: ((ev: { data: string }) => void) | null;
  onerror: ((ev: Event) => void) | null;
  onopen: ((ev: Event) => void) | null;
  close(): void;
}

/** Per-call subscription handle. The unsubscribe tears down the
 *  EventSource. */
export interface Subscription {
  unsubscribe(): void;
}

/** The shape the {@link MockRpcServer.installInto} overrides. */
export interface RpcClientDeps {
  fetchImpl?: FetchImpl;
  eventSourceImpl?: EventSourceImpl;
  defaultHeaders?: Record<string, string>;
  defaultTimeoutMs?: number;
}

/** Default global fetch. Returns `undefined` if `fetch` is not on
 *  the global scope (older Node, jsdom in some configurations) so
 *  the constructor's `call()` throws with a clean message instead
 *  of a cryptic `undefined is not a function`. */
function pickDefaultFetch(): FetchImpl | undefined {
  if (typeof globalThis.fetch === 'function') {
    return globalThis.fetch as unknown as FetchImpl;
  }
  return undefined;
}

function pickDefaultEventSource(): EventSourceImpl | undefined {
  // Node has no native EventSource. We only fall back to a stub
  // when the runtime provides one (jsdom in some configs).
  if (typeof (globalThis as { EventSource?: unknown }).EventSource === 'function') {
    const Ctor = (globalThis as { EventSource: new (url: string, init?: { withCredentials?: boolean }) => EventSourceLike }).EventSource;
    return (url, init) => new Ctor(url, init);
  }
  return undefined;
}

export class JsonRpcClient {
  private idCounter = 1;
  private fetchImpl: FetchImpl | undefined;
  private eventSourceImpl: EventSourceImpl | undefined;
  private endpoint: string;
  private defaultHeaders: Record<string, string>;
  private timeoutMs: number;
  private streams: Set<EventSourceLike> = new Set();

  constructor(deps: RpcClientDeps & { endpoint?: string } = {}) {
    this.fetchImpl = deps.fetchImpl;
    this.eventSourceImpl = deps.eventSourceImpl;
    this.endpoint = deps.endpoint ?? '/api/rpc';
    this.defaultHeaders = deps.defaultHeaders ?? {};
    this.timeoutMs = deps.defaultTimeoutMs ?? 30_000;
  }

  /** Build a JSON-RPC envelope and POST it. The mock installs its
   *  own `fetchImpl` so the wire shape stays identical. */
  async call<T = unknown>(method: string, params?: unknown, signal?: AbortSignal): Promise<T> {
    const id = this.idCounter++;
    const body: RpcRequest = { jsonrpc: '2.0', id, method, params };
    const fetchImpl = this.fetchImpl ?? pickDefaultFetch();
    if (!fetchImpl) {
      throw new RpcError(method, -32000, 'No fetch implementation available');
    }
    const ac = new AbortController();
    const timer = setTimeout(() => ac.abort(), this.timeoutMs);
    signal?.addEventListener('abort', () => ac.abort(), { once: true });
    let res: Awaited<ReturnType<FetchImpl>>;
    try {
      res = await fetchImpl(this.endpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', ...this.defaultHeaders },
        body: JSON.stringify(body),
        signal: ac.signal,
      });
    } catch (e: any) {
      clearTimeout(timer);
      throw new RpcError(method, -32000, e?.message ?? String(e));
    }
    clearTimeout(timer);
    if (!res.ok) {
      throw new RpcError(method, res.status, `HTTP ${res.status} ${res.statusText}`);
    }
    const text = await res.text();
    let parsed: RpcResponse<T>;
    try { parsed = JSON.parse(text) as RpcResponse<T>; }
    catch (e: any) {
      throw new RpcError(method, -32700, `Parse error: ${e?.message ?? String(e)}`);
    }
    if ('error' in parsed && parsed.error) {
      throw new RpcError(method, parsed.error.code, parsed.error.message, parsed.error.data);
    }
    return parsed.result as T;
  }

  /** Subscribe to a live event stream. Returns a Subscription
   *  whose `unsubscribe()` closes the EventSource.
   *
   *  The query string carries `lastSeq` so the server can replay
   *  events since the caller's last seen position — the re-attach
   *  handshake. */
  subscribe(method: string, params: Record<string, unknown>, onMessage: (raw: unknown) => void): Subscription {
    const esImpl = this.eventSourceImpl ?? pickDefaultEventSource();
    if (!esImpl) {
      // No EventSource in the runtime. The mock installs a
      // replacement via `installInto` so the production code
      // still works in tests. If neither path provided one, the
      // caller gets a no-op subscription rather than a crash.
      return { unsubscribe() { /* push-only mode */ } };
    }
    const qs = new URLSearchParams({ method, ...stringifyParams(params), since: String(params.lastSeq ?? 0) });
    const url = `${this.endpoint}/stream?${qs.toString()}`;
    const es = esImpl(url, { withCredentials: false });
    this.streams.add(es);
    es.onmessage = (ev) => {
      try { onMessage(JSON.parse(ev.data)); } catch { onMessage(ev.data); }
    };
    es.onerror = () => { /* no-op; closeAll tears down on teardown */ };
    return {
      unsubscribe: () => {
        es.close();
        this.streams.delete(es);
      },
    };
  }

  /** Tear down every open stream. Called on app exit. */
  closeAll(): void {
    for (const es of this.streams) es.close();
    this.streams.clear();
  }
}

/** Coerce params to URLSearchParams-compatible strings. Numbers /
 *  strings pass through; everything else is JSON-encoded so a
 *  complex `params` object survives the trip. */
function stringifyParams(p: Record<string, unknown>): Record<string, string> {
  const out: Record<string, string> = {};
  for (const [k, v] of Object.entries(p)) {
    if (v === undefined || v === null) continue;
    if (typeof v === 'string' || typeof v === 'number' || typeof v === 'boolean') {
      out[k] = String(v);
    } else {
      out[k] = JSON.stringify(v);
    }
  }
  return out;
}

// Re-export the ok/err helpers so the tests' `import { ok, err } from
// '../MockRpcServer'` keeps working if a future refactor moves them.
export { buildOk, buildErr };
export { RpcError };

/** Singleton — the production app uses this everywhere. */
export const defaultRpcClient = (() => {
  let _default: JsonRpcClient | null = null;
  return (): JsonRpcClient => {
    if (!_default) _default = new JsonRpcClient();
    return _default;
  };
})();
