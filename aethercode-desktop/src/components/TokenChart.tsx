// Phase 5 (T-5-04): TokenChart.
//
// A small line + area chart showing token usage over the
// last 24 h, last 7 d, or all-time. The chart reads from
// the per-session `usage` event stream (the daemon emits
// one event per LLM call) and rolls them up into a
// per-bucket series.
//
// Implementation notes:
//   - We don't pull in a heavy chart library. The chart
//     is a plain SVG so the bundle stays small.
//   - The y-axis is auto-scaled to the max value
//     (input + output) so the curve always fills the
//     canvas.
//   - The x-axis has N ticks (default 12); the chart
//     shows the time window's bounds in the header.
//   - Hovering a column surfaces a tooltip with the
//     bucket's date + total tokens.
//
// Time windows (selected via the window prop):
//   - 24h   → 24 buckets of 1 h each
//   - 7d    → 7 buckets of 1 d each
//   - all   → 12 buckets of unknown size (one per
//             order of magnitude the data spans)
//
// The component is self-contained: the parent just
// passes the sessionId and the chart subscribes to the
// event stream.

import { useEffect, useMemo, useState } from 'react';
import { useRpc } from '../rpc/queries';
import { subscribeKind } from '../rpc/events';
import type { RpcEvent } from '../rpc/types';

export type TokenChartWindow = '24h' | '7d' | 'all';

export interface TokenChartProps {
  sessionId: string | null;
  /** Time window. Default `24h`. */
  window?: TokenChartWindow;
  /** Pixel height of the SVG. Default 120. */
  height?: number;
}

interface Bucket {
  ts: number;
  in: number;
  out: number;
}

const WINDOW_BUCKETS: Record<TokenChartWindow, number> = {
  '24h': 24,
  '7d': 7,
  'all': 12,
};
const WINDOW_MS: Record<TokenChartWindow, number> = {
  '24h': 24 * 3_600_000,
  '7d': 7 * 86_400_000,
  'all': 0, // 0 = no upper bound; buckets span the full range
};

function bucketIndex(ts: number, window: TokenChartWindow, rangeStart: number, rangeEnd: number): number {
  if (window === 'all') {
    if (rangeEnd === rangeStart) return 0;
    const n = WINDOW_BUCKETS.all;
    const span = rangeEnd - rangeStart;
    const pos = (ts - rangeStart) / span;
    return Math.max(0, Math.min(n - 1, Math.floor(pos * n)));
  }
  // Fixed-size buckets: index = floor((ts - rangeStart) / bucketMs)
  const bucketMs = WINDOW_MS[window] / WINDOW_BUCKETS[window];
  if (bucketMs <= 0) return 0;
  return Math.max(0, Math.min(WINDOW_BUCKETS[window] - 1, Math.floor((ts - rangeStart) / bucketMs)));
}

function formatBucketLabel(b: Bucket, window: TokenChartWindow): string {
  const d = new Date(b.ts);
  if (window === '24h') {
    return `${d.getUTCHours().toString().padStart(2, '0')}:${d.getUTCMinutes().toString().padStart(2, '0')}`;
  }
  return `${d.getUTCMonth() + 1}/${d.getUTCDate()}`;
}

export function TokenChart({ sessionId, window: windowProp = '24h', height = 120 }: TokenChartProps) {
  const client = useRpc();
  const [buckets, setBuckets] = useState<Bucket[]>([]);
  const [now, setNow] = useState<number>(() => Date.now());
  const [hover, setHover] = useState<number | null>(null);

  // Tick the `now` clock every minute so the moving
  // window (24h / 7d) drops off old buckets without a
  // page reload. Cheap — setState once per minute.
  useEffect(() => {
    const t = window.setInterval(() => setNow(Date.now()), 60_000);
    return () => window.clearInterval(t);
  }, []);

  // Range bounds: for fixed windows, rangeStart = now -
  // WINDOW_MS; for 'all', rangeStart = oldest seen ts (or
  // now if no data yet).
  const rangeStart = windowProp === 'all' ? (buckets[0]?.ts ?? now) : now - WINDOW_MS[windowProp];
  const rangeEnd = windowProp === 'all' ? now : now;

  // Subscribe to per-session `usage` events. The daemon
  // emits one per LLM call with the input / output deltas
  // in the params.
  useEffect(() => {
    if (!sessionId) return;
    const unsub = subscribeKind(sessionId, 'usage', (ev: RpcEvent) => {
      const p = ev.params as { ts?: number; input?: number; output?: number } | undefined;
      const ts = p?.ts ?? ev.ts;
      if (!ts) return;
      setBuckets((prev) => {
        // Merge: we keep all events inside a small
        // rolling buffer; out-of-window ones are
        // dropped on read (filter below).
        const next = [...prev, { ts, in: p?.input ?? 0, out: p?.output ?? 0 }];
        // Cap the buffer to a sane size so a 10-hour
        // stress test (T-7-07) doesn't unbounded-grow.
        if (next.length > 2_000) next.splice(0, next.length - 2_000);
        return next;
      });
    }, { client });
    return () => { try { unsub(); } catch {} };
  }, [client, sessionId]);

  // Roll the raw events up into buckets. Re-derives on
  // every `now` tick so the moving window drops old
  // events without a new event being pushed.
  const rolled = useMemo(() => {
    const n = WINDOW_BUCKETS[windowProp];
    const out: Bucket[] = new Array(n);
    const start = rangeStart;
    const bucketMs = windowProp === 'all' ? (rangeEnd - start) / n : WINDOW_MS[windowProp] / n;
    for (let i = 0; i < n; i++) {
      const bts = start + i * bucketMs;
      out[i] = { ts: bts, in: 0, out: 0 };
    }
    for (const ev of buckets) {
      if (ev.ts < rangeStart || ev.ts > rangeEnd) continue;
      const idx = bucketIndex(ev.ts, windowProp, rangeStart, rangeEnd);
      out[idx].in += ev.in;
      out[idx].out += ev.out;
    }
    return out;
  }, [buckets, windowProp, rangeStart, rangeEnd]);

  const max = useMemo(() => {
    let m = 0;
    for (const b of rolled) m = Math.max(m, b.in + b.out);
    return m;
  }, [rolled]);

  if (!sessionId) {
    return (
      <div className="token-chart token-chart-empty">
        <div className="token-chart-empty-msg">No session selected.</div>
      </div>
    );
  }

  const W = 320;
  const H = height;
  const PAD = 8;
  const innerW = W - PAD * 2;
  const innerH = H - PAD * 2;
  const colW = innerW / rolled.length;

  return (
    <div className="token-chart" role="region" aria-label="Token usage chart">
      <div className="token-chart-head">
        <span className="token-chart-title">Token burn</span>
        <span className="token-chart-window">{windowProp}</span>
        <span className="token-chart-total">Σ {rolled.reduce((s, b) => s + b.in + b.out, 0).toLocaleString()}</span>
      </div>
      <svg
        className="token-chart-svg"
        viewBox={`0 0 ${W} ${H}`}
        preserveAspectRatio="none"
        width="100%"
        height={H}
        role="img"
        aria-label={`Token usage over ${windowProp}`}
      >
        {rolled.map((b, i) => {
          const total = b.in + b.out;
          const h = max > 0 ? (total / max) * innerH : 0;
          const x = PAD + i * colW;
          const y = PAD + (innerH - h);
          const w = Math.max(1, colW - 1);
          return (
            <g key={i}>
              <rect
                x={x}
                y={y}
                width={w}
                height={h}
                rx={1.5}
                fill={hover === i ? 'var(--accent, #5da9ff)' : 'var(--accent-soft, rgba(93, 169, 255, 0.7))'}
                onMouseEnter={() => setHover(i)}
                onMouseLeave={() => setHover((v) => (v === i ? null : v))}
                role="presentation"
              />
              <title>{`${formatBucketLabel(b, windowProp)} — in ${b.in} out ${b.out}`}</title>
            </g>
          );
        })}
        {/* x-axis ticks (4) */}
        {(() => {
          const step = Math.max(1, Math.floor(rolled.length / 4));
          const ticks: number[] = [];
          for (let i = 0; i < rolled.length; i += step) ticks.push(i);
          return ticks.map((i) => (
            <text
              key={i}
              x={PAD + i * colW + colW / 2}
              y={H - 1}
              className="token-chart-tick"
              textAnchor="middle"
            >
              {formatBucketLabel(rolled[i], windowProp)}
            </text>
          ));
        })()}
      </svg>
      {hover != null && (
        <div className="token-chart-tooltip">
          {formatBucketLabel(rolled[hover], windowProp)} · in {rolled[hover].in.toLocaleString()} · out {rolled[hover].out.toLocaleString()}
        </div>
      )}
    </div>
  );
}
