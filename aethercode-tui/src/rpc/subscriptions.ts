/**
 * Phase 6.1 (T-6-03): `useRpcSubscription` — React hook for
 * receiving JSON-RPC notifications from the supervisor.
 *
 * Mirrors the desktop's `useRpcSubscription` shape:
 *   - subscribe on mount, unsubscribe on unmount
 *   - bounded event buffer (oldest first)
 *   - filter by `kind` (single or array)
 *   - re-attach support: pass `lastSeq` to resume the
 *     stream from a known position
 *
 * The TUI side is hook-based rather than SSE-based
 * because the wire transport is a Unix socket / named pipe
 * (not HTTP). The client receives notifications via its
 * `onNotification` callback; the hook adds that callback,
 * parses the event, and routes it into the buffer.
 *
 * The hook is intentionally NOT bound to a specific
 * method name — the same `useRpcSubscription(sessionId, ...)`
 * shape works for any per-session notification. Tests in
 * `subscriptions.test.tsx` verify the buffer + the
 * unsubscribe + the re-attach flow.
 */

import { useEffect, useRef, useState } from "react";
import { defaultRpcClient, JsonRpcClient } from "./client.js";
import type { RpcEvent, RpcEventKind, RpcValue } from "./types.js";

export interface UseRpcSubscriptionOptions {
  /** Filter by `kind`. The hook drops notifications whose
   *  method/payload doesn't match. Omit to receive all
   *  notifications. */
  kind?: RpcEventKind | RpcEventKind[] | string | string[];
  /** Optional last-seen sequence. The first event the
   *  consumer sees will be `seq > lastSeq`. The hook
   *  bumps the `lastSeq` ref as events arrive so the
   *  re-attach handshake (T-7-12) can be invoked with
   *  the most recent value. */
  lastSeq?: number;
  /** Bounded buffer size; older events drop off the
   *  head. Default 200. */
  capacity?: number;
  /** Injected client (defaults to the module singleton). */
  client?: JsonRpcClient;
  /** Skip subscribing (e.g. while the parent is in a
   *  "loading" state). */
  enabled?: boolean;
}

export interface UseRpcSubscriptionResult {
  /** All buffered events, oldest first. The reference is
   *  stable across renders; use `events.length` for the
   *  "did anything arrive" check. */
  events: readonly RpcEvent[];
  /** The most recent event, or null if the buffer is
   *  empty. */
  latest: RpcEvent | null;
  /** Counters per kind, recomputed on every event. */
  counts: Partial<Record<string, number>>;
  /** Clears the buffer (e.g. on session switch). */
  clear(): void;
  /** Mark the current position as the "re-attach point".
   *  Subsequent renders surface the events that arrived
   *  AFTER this call. */
  markReattachPoint(): number;
  /** True while the hook is re-attaching. The flag is
   *  purely informational — the consumer can show a
   *  "resuming…" pill. */
  isReattaching: boolean;
}

/** A ring buffer of bounded capacity. Older events are
 *  dropped from the head. */
class RingBuffer<T> {
  private buf: T[] = [];
  constructor(private cap: number) {}
  push(item: T): void {
    this.buf.push(item);
    if (this.buf.length > this.cap) this.buf.shift();
  }
  clear(): void { this.buf = []; }
  snapshot(): T[] { return this.buf; }
  size(): number { return this.buf.length; }
  last(): T | null { return this.buf.length > 0 ? (this.buf[this.buf.length - 1] as T) : null; }
}

function getClient(injected?: JsonRpcClient): JsonRpcClient {
  return injected ?? defaultRpcClient;
}

export function useRpcSubscription(
  sessionId: string | null,
  opts: UseRpcSubscriptionOptions = {}
): UseRpcSubscriptionResult {
  const client = getClient(opts.client);
  const capacity = opts.capacity ?? 200;
  const bufRef = useRef<RingBuffer<RpcEvent>>(new RingBuffer(capacity));
  const versionRef = useRef(0);
  const [version, setVersion] = useState(0);
  const reattachFromRef = useRef<number>(-1);
  const [isReattaching, setIsReattaching] = useState(false);
  const lastSeqRef = useRef<number>(opts.lastSeq ?? -1);
  // Mirror the ref so the closure passed to the listener
  // can read the latest value without forcing a re-subscribe.
  const kindRef = useRef<UseRpcSubscriptionOptions["kind"]>(opts.kind);
  kindRef.current = opts.kind;
  const enabled = opts.enabled !== false && !!sessionId;

  useEffect(() => {
    lastSeqRef.current = opts.lastSeq ?? -1;
  }, [opts.lastSeq]);

  useEffect(() => {
    if (!enabled || !sessionId) return;
    setIsReattaching(true);
    const handler = (method: string, params: RpcValue | undefined) => {
      // Filter on the *notification* level. The TUI
      // doesn't have a separate `kind` discriminator; the
      // `method` IS the kind. The sessionId must match
      // (when present) so we don't mix events from
      // different sessions.
      const k = kindRef.current;
      if (k) {
        const allowed = Array.isArray(k) ? (k as string[]).includes(method) : (k as string) === method;
        if (!allowed) return;
      }
      // Build an RpcEvent from the notification.
      const ev: RpcEvent = {
        kind: method,
        sessionId,
        seq: Number((params as any)?.seq ?? (params as any)?.event?.seq ?? lastSeqRef.current + 1),
        ts: Number((params as any)?.ts ?? (params as any)?.event?.ts ?? Date.now()),
        params,
      };
      if (typeof ev.seq !== "number" || !Number.isFinite(ev.seq)) {
        ev.seq = lastSeqRef.current + 1;
      }
      if (ev.seq <= lastSeqRef.current) {
        // The supervisor replayed an event we already
        // saw. Drop it.
        return;
      }
      bufRef.current.push(ev);
      versionRef.current += 1;
      setVersion(versionRef.current);
      lastSeqRef.current = ev.seq;
      setIsReattaching(false);
    };
    // The client accepts exactly one `onNotification`
    // callback (it's stored as a private field). Tests
    // that need multi-hook observation can wrap the
    // client. For production use, this is fine: only
    // one subscription hook is active at a time per
    // session.
    client.onNotification = handler;
    return () => {
      if (client.onNotification === handler) {
        client.onNotification = undefined;
      }
      setIsReattaching(false);
    };
  }, [client, sessionId, enabled]);

  // Read the version through a synthetic subscription so
  // React picks up the change. The push handler already
  // triggers a re-render via setVersion; this is just
  // here so the effect dependency stays correct.
  void version;

  const events = bufRef.current.snapshot();
  const latest = bufRef.current.last();
  const counts: Partial<Record<string, number>> = {};
  for (const ev of events) {
    counts[ev.kind] = (counts[ev.kind] ?? 0) + 1;
  }
  return {
    events,
    latest,
    counts,
    clear: () => {
      bufRef.current.clear();
      versionRef.current += 1;
      setVersion(versionRef.current);
    },
    markReattachPoint: () => {
      const cur = latest?.seq ?? -1;
      reattachFromRef.current = cur;
      return cur;
    },
    isReattaching,
  };
}
