// Phase 4.2 (T-4-17) + Phase 7 (T-7-02): TranscriptEnricher.
//
// The brief: every assistant turn must end with a `## Summary`
// block (spec §12.3). The runtime guarantees this via a
// `PostTurnSummaryHook` that fires when a turn ends without
// a summary. The desktop's job is to:
//
//   1. Detect missing summaries (the daemon emits a
//      `task/event kind=summary_missing` event).
//   2. Trigger a back-channel RPC to ask the model to
//      produce a 1-3 line summary.
//   3. Patch the assistant message in place so the
//      `SummaryFooter` re-renders with the new block.
//   4. Surface a "pending" / "failed" badge in the meantime.
//
// The actual RPC payload is intentionally small — we
// don't want a full model call; the daemon runs a cheap
// "summarise the last 5 messages" prompt.
//
// The enricher is a hook (not a component) so the page
// composes it with the existing `useStore` Zustand
// wiring. The hook signature is intentionally narrow:
//
//   useTranscriptEnricher({ sessionId, lastAssistantBody,
//     onSummaryPatched })
//
// The hook:
//   - subscribes to `task/event` (kind=summary_missing)
//     for the given session
//   - on each event, calls `enricher/summarise` with the
//     session id + last few message ids
//   - on success, calls the `onSummaryPatched` callback
//     with the new `## Summary` block
//   - on failure, sets a local `state` to "failed" so the
//     page can render the "⚠ auto-summary failed" badge
//
// Note: the hook is wired through the existing
// JsonRpcClient (T-3-01). The default `defaultRpcClient()`
// singleton is used; tests pass a mock-instrumented client
// via the RpcProvider.

import { useEffect, useRef, useState, useCallback } from 'react';
import { subscribeKind } from '../../rpc/events';
import { defaultRpcClient, type JsonRpcClient } from '../../rpc/client';
import { useRpc } from '../../rpc/queries';

export type SummaryState = 'idle' | 'pending' | 'failed';

export interface UseTranscriptEnricherOptions {
  sessionId: string | null;
  /** Last assistant message body. Used to detect "missing
   *  summary" via the cheap regex check — saves a round
   *  trip to the daemon. */
  lastAssistantBody?: string;
  /** True when the body is still streaming in. The
   *  enricher pauses while a turn is in flight. */
  isStreaming?: boolean;
  /** When true, skip the subscription (e.g. on a
   *  pre-summary test render). */
  enabled?: boolean;
  /** Called with the patched body (the assistant's
   *  original text + the appended `## Summary` block)
   *  after a successful auto-summary. The parent
   *  patches its store. */
  onSummaryPatched?: (patchedBody: string) => void;
  /** Optional client override. Defaults to the
   *  module singleton (or the test-injected client
   *  when running under RpcProvider). */
  client?: JsonRpcClient;
}

export interface UseTranscriptEnricherResult {
  /** Current state. `idle` when no enrichment is in
   *  flight. */
  state: SummaryState;
  /** Last error message (when state == 'failed'). */
  error: string | null;
  /** Manually trigger an enrichment. Useful for
   *  re-summarising a stale transcript. */
  trigger(): Promise<void>;
  /** True if the last assistant message has a
   *  `## Summary` block already. */
  hasSummary: boolean;
}

interface EnrichResult {
  ok: boolean;
  summary?: string;
  reason?: string;
}

/** Cheap regex check for the `## Summary` block. */
export function bodyHasSummary(body: string | undefined | null): boolean {
  if (!body) return false;
  return /^#{1,6}\s*summary[^\n]*$/im.test(body);
}

/** Append a `## Summary` block to the end of an assistant
 *  body. The newline before the heading is preserved so
 *  the body ends cleanly. */
export function appendSummary(body: string, summary: string): string {
  const trimmedBody = body.replace(/\s+$/, '');
  const trimmedSummary = summary.replace(/^\s+|\s+$/g, '');
  return `${trimmedBody}\n\n## Summary\n${trimmedSummary}\n`;
}

export function useTranscriptEnricher({
  sessionId,
  lastAssistantBody,
  isStreaming = false,
  enabled = true,
  onSummaryPatched,
  client: clientProp,
}: UseTranscriptEnricherOptions): UseTranscriptEnricherResult {
  // The `useRpc` hook reads the client from the
  // RpcProvider context (when running in a test or under
  // a custom provider). When no provider is present, it
  // falls through to the default singleton. The optional
  // `client` prop overrides both.
  const ctxClient = useRpc();
  const client = clientProp ?? ctxClient ?? defaultRpcClient();

  const [state, setState] = useState<SummaryState>('idle');
  const [error, setError] = useState<string | null>(null);
  const onPatchedRef = useRef(onSummaryPatched);
  onPatchedRef.current = onSummaryPatched;
  const lastBodyRef = useRef<string | undefined>(lastAssistantBody);
  lastBodyRef.current = lastAssistantBody;

  const hasSummary = bodyHasSummary(lastAssistantBody);

  const trigger = useCallback(async () => {
    if (!sessionId) return;
    setState('pending');
    setError(null);
    try {
      const r = await client.call<EnrichResult>('enricher/summarise', {
        sessionId,
        lastMessageId: null,
      });
      if (!r?.ok || !r.summary) {
        setState('failed');
        setError(r?.reason ?? 'auto-summary failed');
        return;
      }
      const body = lastBodyRef.current;
      if (body != null) {
        const patched = appendSummary(body, r.summary);
        onPatchedRef.current?.(patched);
      }
      setState('idle');
    } catch (e: any) {
      setState('failed');
      setError(e?.message ?? String(e));
    }
  }, [client, sessionId]);

  // Subscribe to the daemon's `summary_missing` events
  // for the active session. When the daemon emits one
  // (the LLM forgot the `## Summary` block), we fire
  // the enricher.
  useEffect(() => {
    if (!enabled || !sessionId) return;
    if (isStreaming) return; // wait for the turn to finish
    const unsub = subscribeKind(sessionId, 'summary_missing', () => {
      // The daemon already validated the missing case;
      // skip the local check and go straight to the RPC.
      void trigger();
    }, { client });
    return () => { try { unsub(); } catch {} };
  }, [client, sessionId, enabled, isStreaming, trigger]);

  // When the parent's `lastAssistantBody` updates and
  // we still have a missing summary, fire the enricher
  // once per assistant turn. The counter ref guards
  // against re-firing on every body change (the parent
  // streams the body char-by-char).
  const lastFiredRef = useRef<string | null>(null);
  useEffect(() => {
    if (!enabled || !sessionId) return;
    if (isStreaming) return;
    if (!lastAssistantBody) return;
    if (bodyHasSummary(lastAssistantBody)) {
      lastFiredRef.current = lastAssistantBody;
      return;
    }
    if (lastFiredRef.current === lastAssistantBody) return;
    // Wait one tick so we don't race with the streaming
    // event — the body might still be growing.
    const t = window.setTimeout(() => {
      if (bodyHasSummary(lastAssistantBody)) {
        lastFiredRef.current = lastAssistantBody;
        return;
      }
      lastFiredRef.current = lastAssistantBody;
      void trigger();
    }, 200);
    return () => window.clearTimeout(t);
  }, [enabled, sessionId, isStreaming, lastAssistantBody, trigger]);

  return { state, error, trigger, hasSummary };
}
