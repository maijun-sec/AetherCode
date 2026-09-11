import { useEffect, useState } from 'react';
import { useStore } from '../store';
import './ContextMeter.css';

// ContextMeter — shows how much of the model's context window
// has been consumed by the current session. Derived from
// `metrics.inputTokens` (cumulative) and `engineState.contextWindow`.
//
// Colour cues:
//   • <  50% — green, "comfortable"
//   • 50-80% — amber, "consider compressing"
//   • >  80% — red, "compress now recommended"
//
// The meter is purely informational; it does NOT trigger
// compaction itself (the engine decides when to compact based on
// its own thresholds). A small "history" expander shows recent
// compaction snapshots (currently stub; the engine doesn't yet
// expose the per-section keep/drop list, so we just show
// inputTokens deltas).

const DEFAULT_WINDOW = 200_000;

function fmtTokens(n: number): string {
  if (n < 1000) return String(n);
  if (n < 1_000_000) return `${(n / 1000).toFixed(1)}k`;
  return `${(n / 1_000_000).toFixed(2)}M`;
}

/** snap the inputTokens metric into a "compaction event"
 *  when the value drops by >30% (heuristic for "we just lost a
 *  big chunk"). The current UI shows the latest few as
 *  "compressed: kept N / lost M" rows. */
function detectCompactions(samples: { ts: number; tokens: number }[]): { ts: number; saved: number }[] {
  if (samples.length < 2) return [];
  const out: { ts: number; saved: number }[] = [];
  for (let i = 1; i < samples.length; i++) {
    const prev = samples[i - 1].tokens;
    const cur = samples[i].tokens;
    if (prev > 1000 && cur < prev * 0.7) {
      out.push({ ts: samples[i].ts, saved: prev - cur });
    }
  }
  return out.slice(-5); // keep last 5
}

export function ContextMeter() {
  const { metrics, engineState } = useStore();
  // Poll a sample every 2s so the meter ticks without re-rendering
  // the whole RightPanel on every text_delta.
  const [samples, setSamples] = useState<{ ts: number; tokens: number }[]>([]);
  useEffect(() => {
    if (metrics?.inputTokens == null) return;
    setSamples((prev) => {
      const last = prev[prev.length - 1];
      // Only push if the value changed (avoid duplicate samples)
      if (last && last.tokens === metrics.inputTokens) return prev;
      const next = [...prev, { ts: Date.now(), tokens: metrics.inputTokens! }];
      // Keep the last 60 samples (~2 min of history at 2s/poll)
      return next.length > 60 ? next.slice(next.length - 60) : next;
    });
  }, [metrics?.inputTokens]);

  const used = metrics?.inputTokens ?? 0;
  const window = engineState?.contextWindow && engineState.contextWindow > 0
    ? engineState.contextWindow
    : DEFAULT_WINDOW;
  const pct = Math.min(100, Math.round((used / window) * 100));
  const tier = pct < 50 ? 'green' : pct < 80 ? 'amber' : 'red';
  const compactions = detectCompactions(samples);
  const [showHistory, setShowHistory] = useState(false);

  return (
    <div className="context-meter">
      <div className="section-header">
        <span>Context</span>
        <span className="section-meta">
          {fmtTokens(used)} / {fmtTokens(window)} ({pct}%)
        </span>
      </div>
      <div className={`context-track context-${tier}`}>
        <div className="context-fill" style={{ width: `${pct}%` }} />
        {pct >= 80 && <span className="context-warn">压缩推荐</span>}
      </div>
      <div className="context-detail">
        <span className="context-tier-label">{tier === 'green' ? '舒适' : tier === 'amber' ? '注意' : '即将满'}</span>
        {compactions.length > 0 && (
          <button
            className="context-history-btn"
            onClick={() => setShowHistory((v) => !v)}
          >
            {showHistory ? '▾' : '▸'} {compactions.length} 次压缩
          </button>
        )}
      </div>
      {showHistory && compactions.length > 0 && (
        <ul className="context-history">
          {compactions.slice().reverse().map((c, i) => (
            <li key={i} className="context-history-row">
              <span className="context-history-time">
                {new Date(c.ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
              </span>
              <span className="context-history-saved">−{fmtTokens(c.saved)} tokens</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
