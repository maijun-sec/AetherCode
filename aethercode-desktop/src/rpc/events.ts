// Phase 3: non-React event subscription helpers.
//
// These are the imperative versions of `useRpcSubscription`. They
// share the same wire contract; React components use the hook, the
// long-running supervisor and the e2e tests use the function form.

import { defaultRpcClient, type JsonRpcClient } from './client';
import type { RpcEvent, RpcEventKind } from './types';

/** Options carried by every event subscription. */
export interface SubscribeOptions {
  /** Optional client override. Defaults to the defaultRpcClient singleton. */
  client?: JsonRpcClient;
  /** Replay events with seq > this number on subscribe. Default 0. */
  lastSeq?: number;
}

/** Subscribe to every event on `sessionId`. Returns an unsubscribe
 *  function. Throws when `sessionId` is missing so the caller gets
 *  a loud failure instead of a silent no-op. */
export function subscribeEvents(
  sessionId: string,
  listener: (ev: RpcEvent) => void,
  options: SubscribeOptions = {},
): () => void {
  if (!sessionId) {
    throw new Error('subscribeEvents: sessionId is required');
  }
  const client = options.client ?? defaultRpcClient();
  const sub = client.subscribe(
    'session/events',
    { sessionId, lastSeq: options.lastSeq ?? 0 },
    (raw) => listener(coerceEvent(raw)),
  );
  return () => sub.unsubscribe();
}

/** Subscribe to a single event kind. */
export function subscribeKind(
  sessionId: string,
  kind: RpcEventKind,
  listener: (ev: RpcEvent) => void,
  options: SubscribeOptions = {},
): () => void {
  if (!sessionId) {
    throw new Error('subscribeKind: sessionId is required');
  }
  const off = subscribeEvents(sessionId, (ev) => {
    if (ev.kind === kind) listener(ev);
  }, options);
  return off;
}

/** Subscribe to several event kinds. */
export function subscribeKinds(
  sessionId: string,
  kinds: RpcEventKind[],
  listener: (ev: RpcEvent) => void,
  options: SubscribeOptions = {},
): () => void {
  if (!sessionId) {
    throw new Error('subscribeKinds: sessionId is required');
  }
  const set = new Set<RpcEventKind>(kinds);
  return subscribeEvents(sessionId, (ev) => {
    if (set.has(ev.kind)) listener(ev);
  }, options);
}

// --- internals --------------------------------------------------------

function coerceEvent(raw: unknown): RpcEvent {
  if (raw && typeof raw === 'object' && 'kind' in (raw as Record<string, unknown>)) {
    return raw as RpcEvent;
  }
  // The mock's stream wraps the event under `params` — the
  // installInto bridge encodes it as `{ jsonrpc, method, params:
  // ev }`. The payload is the inner RpcEvent.
  if (raw && typeof raw === 'object' && 'params' in (raw as Record<string, unknown>)) {
    return (raw as { params: RpcEvent }).params;
  }
  return {
    seq: 0,
    kind: 'state_change',
    sessionId: '',
    ts: Date.now(),
    params: { raw },
  };
}
