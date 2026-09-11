// Phase 3: React hook for live event subscription.
//
// The hook owns a bounded RingBuffer (default 200 entries), a
// per-kind counter, and a `lastSeq` cursor so re-attach works the
// same way as the long-running supervisor's SSE. React state is
// fed through `useSyncExternalStore` so the consumer sees a stable
// snapshot reference per tick.

import { useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';
import { defaultRpcClient } from './client';
import type { JsonRpcClient } from './client';
import type { RpcEvent, RpcEventKind } from './types';

/** Bounded ring buffer for event snapshots. Older events are
 *  evicted when the buffer overflows so the UI doesn't grow
 *  unbounded during a long session. */
class RingBuffer<T> {
  private buf: T[] = [];
  private capacity: number;
  constructor(capacity: number = 200) { this.capacity = capacity; }
  push(item: T): void {
    this.buf.push(item);
    if (this.buf.length > this.capacity) {
      this.buf.shift();
    }
  }
  values(): T[] { return this.buf; }
  size(): number { return this.buf.length; }
  clear(): void { this.buf.length = 0; }
}

export interface UseRpcSubscriptionOptions {
  /** Optional filter. Single kind, or array. */
  kind?: RpcEventKind | RpcEventKind[];
  /** Don't open the underlying stream until enabled. */
  enabled?: boolean;
  /** Ring buffer capacity. Default 200. */
  capacity?: number;
  /** Optional client override. */
  client?: JsonRpcClient;
}

export interface UseRpcSubscriptionResult {
  events: RpcEvent[];
  counts: Partial<Record<RpcEventKind, number>>;
  lastSeq: number;
  isReattaching: boolean;
  reset: () => void;
}

/** Subscribe to a session's event stream. The hook updates on
 *  every pushed event so consumers can render live (transcript
 *  cards, token charts, todo board). */
export function useRpcSubscription(
  sessionId: string | null,
  options: UseRpcSubscriptionOptions = {},
): UseRpcSubscriptionResult {
  const { kind, enabled = true, capacity: capacityOpt, client } = options;
  const capacity = capacityOpt ?? 200;
  const allowed = useMemo(() => {
    if (!kind) return null;
    if (Array.isArray(kind)) return new Set<RpcEventKind>(kind);
    const k: RpcEventKind | RpcEventKind[] = kind;
    return new Set<RpcEventKind>(Array.isArray(k) ? k : [k as RpcEventKind]);
  }, [kind]);

  // The ring buffer + counts are stable across renders; React's
  // useSyncExternalStore is the contract that lets a state change
  // not produce a new reference for unchanged events.
  const bufferRef = useRef<RingBuffer<RpcEvent>>(new RingBuffer(capacity));
  const countsRef = useRef<Partial<Record<RpcEventKind, number>>>({});
  const lastSeqRef = useRef(0);
  const snapshotRef = useRef<{
    events: RpcEvent[];
    counts: Partial<Record<RpcEventKind, number>>;
    lastSeq: number;
  }>({ events: [], counts: {}, lastSeq: 0 });
  const listenersRef = useRef<Set<() => void>>(new Set());
  const [isReattaching, setIsReattaching] = useState(true);
  // Bumped on every push so the useSyncExternalStore snapshot
  // identity changes.
  const [tick, setTick] = useState(0);

  useEffect(() => {
    bufferRef.current.clear();
    countsRef.current = {};
    lastSeqRef.current = 0;
    snapshotRef.current = { events: [], counts: {}, lastSeq: 0 };
    if (!sessionId || !enabled) {
      // No session id (or disabled) — nothing to do.
      setIsReattaching(false);
      return;
    }
    setIsReattaching(true);
    const c = client ?? defaultRpcClient();
    const sub = c.subscribe(
      'session/events',
      { sessionId, lastSeq: 0 },
      (raw) => {
        const ev = coerceEvent(raw, sessionId);
        if (allowed && !allowed.has(ev.kind)) return;
        if (ev.seq <= lastSeqRef.current) return;
        lastSeqRef.current = ev.seq;
        bufferRef.current.push(ev);
        // counts: Partial<Record<RpcEventKind, number>>. Bump
        // the bucket the pushed event belongs to.
        countsRef.current[ev.kind] = (countsRef.current[ev.kind] ?? 0) + 1;
        snapshotRef.current = {
          events: bufferRef.current.values().slice(),
          counts: { ...countsRef.current },
          lastSeq: lastSeqRef.current,
        };
        setTick((t) => t + 1);
        if (isReattaching) setIsReattaching(false);
      },
    );
    return () => {
      sub.unsubscribe();
    };
  }, [sessionId, allowed, enabled, client, isReattaching]);

  // Stable getter for useSyncExternalStore.
  const getSnapshot = useMemo(() => () => snapshotRef.current, []);

  const state = useSyncExternalStore(
    (cb) => {
      listenersRef.current.add(cb);
      return () => listenersRef.current.delete(cb);
    },
    getSnapshot,
    getSnapshot,
  );

  // When the tick changes, notify every listener so React re-reads
  // the snapshot.
  useEffect(() => {
    for (const cb of listenersRef.current) cb();
  }, [tick]);

  const reset = () => {
    bufferRef.current.clear();
    countsRef.current = {};
    lastSeqRef.current = 0;
    snapshotRef.current = { events: [], counts: {}, lastSeq: 0 };
    setTick((t) => t + 1);
  };

  return {
    events: state.events,
    counts: state.counts,
    lastSeq: state.lastSeq,
    isReattaching,
    reset,
  };
}

function coerceEvent(raw: unknown, sessionId: string | null): RpcEvent {
  if (raw && typeof raw === 'object' && 'kind' in (raw as Record<string, unknown>)) {
    const r = raw as RpcEvent;
    return { ...r, sessionId: r.sessionId || sessionId || '' };
  }
  if (raw && typeof raw === 'object' && 'params' in (raw as Record<string, unknown>)) {
    return coerceEvent((raw as { params: unknown }).params, sessionId);
  }
  return {
    seq: 0,
    kind: 'state_change',
    sessionId: sessionId ?? '',
    ts: Date.now(),
    params: { raw },
  };
}
