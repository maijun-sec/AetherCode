import { useMemo, useEffect, useState } from 'react';
import { useStore } from '../store';
import { useSessionTokens, useRpc } from '../rpc/queries';
import { subscribeKind } from '../rpc/events';
import './TokenUsage.css';

function fmtNum(n?: number): string {
  if (n === undefined || n === null) return '—';
  if (n < 1000) return String(n);
  if (n < 1_000_000) return `${(n / 1000).toFixed(1)}k`;
  return `${(n / 1_000_000).toFixed(2)}M`;
}
function fmtCost(n?: number): string {
  if (n === undefined || n === null) return '—';
  if (n < 0.01) return `$${(n * 1000).toFixed(2)}m`;
  return `$${n.toFixed(4)}`;
}

export interface TokenUsageProps {
  /** Optional session id. When provided, the panel polls
   *  the daemon's `session/tokens` RPC every 5 s for a
   *  fresh snapshot. When omitted, falls back to the
   *  Zustand store (the legacy R83 path). */
  sessionId?: string | null;
}

/**
 * Phase 5 (T-5-03): TokenUsage — now wired to the new
 * `session/tokens` RPC. The TanStack Query hook polls
 * every 5 s, and the `usage` event subscription patches
 * the in-memory counter between polls so the user sees
 * the count tick up the moment a new model call
 * finishes. The `costUsd` total is the canonical dollar
 * figure (input + output at the active model's pricing).
 */
export function TokenUsage({ sessionId }: TokenUsageProps = {}) {
  const { metrics } = useStore();
  const tokensQ = useSessionTokens(sessionId ?? null);
  const client = useRpc();
  const [liveIn, setLiveIn] = useState<number | null>(null);
  const [liveOut, setLiveOut] = useState<number | null>(null);

  // Subscribe to per-session `usage` events. The daemon
  // emits one per LLM call, with the deltas in the
  // params. We accumulate locally so the panel can
  // update between TanStack Query polls.
  useEffect(() => {
    if (!sessionId) return;
    const unsub = subscribeKind(sessionId, 'usage', (ev) => {
      const p = ev.params as { input?: number; output?: number } | undefined;
      if (p?.input) setLiveIn((v) => (v ?? 0) + p.input!);
      if (p?.output) setLiveOut((v) => (v ?? 0) + p.output!);
    }, { client });
    return () => { try { unsub(); } catch {} };
  }, [client, sessionId]);

  // When the polled snapshot changes, reset the live
  // accumulator (the poll is the source of truth).
  const polledIn = tokensQ.data?.tokensIn;
  const polledOut = tokensQ.data?.tokensOut;
  useEffect(() => {
    if (polledIn != null) setLiveIn(null);
    if (polledOut != null) setLiveOut(null);
  }, [polledIn, polledOut]);

  // Pick the freshest source: the polled RPC when we
  // have one, otherwise the live delta on top of the
  // last polled value, otherwise the Zustand store.
  const input = useMemo(() => {
    if (polledIn != null) return polledIn + (liveIn ?? 0);
    if (sessionId == null) return metrics?.inputTokens;
    return polledIn;
  }, [polledIn, liveIn, metrics?.inputTokens, sessionId]);
  const output = useMemo(() => {
    if (polledOut != null) return polledOut + (liveOut ?? 0);
    if (sessionId == null) return metrics?.outputTokens;
    return polledOut;
  }, [polledOut, liveOut, metrics?.outputTokens, sessionId]);
  const total = (input ?? 0) + (output ?? 0) || tokensQ.data?.totalTokens || metrics?.totalTokens;
  const cost = tokensQ.data?.costUsd ?? metrics?.costUsd;
  return (
    <div className="token-usage">
      <div className="section-header">
        <span>Tokens</span>
        <span className="section-meta">{fmtCost(cost)}</span>
      </div>
      <div className="token-grid">
        <div className="token-cell"><div className="token-label">In</div><div className="token-value">{fmtNum(input)}</div></div>
        <div className="token-cell"><div className="token-label">Out</div><div className="token-value">{fmtNum(output)}</div></div>
        <div className="token-cell"><div className="token-label">Σ</div><div className="token-value">{fmtNum(total)}</div></div>
        <div className="token-cell"><div className="token-label">Tools</div><div className="token-value">{metrics?.totalToolCalls ?? '—'}</div></div>
      </div>
    </div>
  );
}
