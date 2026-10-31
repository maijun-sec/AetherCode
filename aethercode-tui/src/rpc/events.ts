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

/**
 * Phase 6.1 (T-6-03): imperative event-subscription helper.
 *
 * Most of the TUI uses the `useRpcSubscription` hook
 * (subscriptions.ts). This file exposes a plain function
 * for non-React code paths (e.g. the TUI's `ac-tui.ts`
 * boot path, where we wire one listener to a session for
 * the lifetime of a re-attach handshake).
 *
 * The shape mirrors the desktop's `events.ts` so a future
 * shared module can drop in cleanly.
 */

import { JsonRpcClient } from "./client.js";
import type { RpcEvent, RpcEventKind, RpcValue, Subscription } from "./types.js";

export interface SubscribeOptions {
  client?: JsonRpcClient;
  /** Filter by method (the wire-level `method` field is
   *  the TUI's stand-in for the desktop's `kind`). */
  kind?: RpcEventKind | RpcEventKind[] | string | string[];
  /** Re-attach point: only events with `seq > lastSeq`
   *  are delivered. Older events are dropped. */
  lastSeq?: number;
}

export function subscribeEvents(
  sessionId: string,
  onEvent: (ev: RpcEvent) => void,
  opts: SubscribeOptions = {},
): Subscription {
  const client = opts.client;
  if (!client) {
    throw new Error("subscribeEvents: client is required");
  }
  const lastSeq = opts.lastSeq ?? -1;
  const k = opts.kind;
  const prev = client.onNotification;
  const allowedSet = k ? new Set(Array.isArray(k) ? (k as string[]) : [k as string]) : null;
  const wrap = (method: string, params: RpcValue | undefined): void => {
    if (allowedSet && !allowedSet.has(method)) return;
    const seq = Number((params as any)?.seq ?? lastSeq + 1);
    if (seq <= lastSeq) return;
    const ev: RpcEvent = {
      kind: method,
      sessionId,
      seq,
      ts: Number((params as any)?.ts ?? Date.now()),
      params,
    };
    try {
      onEvent(ev);
    } catch (e) {
      try {
        process.stderr.write(`[rpc] subscribeEvents handler threw: ${(e as Error).message}\n`);
      } catch {
        /* ignore */
      }
    }
  };
  client.onNotification = wrap;
  return {
    unsubscribe: () => {
      if (client.onNotification === wrap) {
        client.onNotification = prev;
      }
    },
  };
}
