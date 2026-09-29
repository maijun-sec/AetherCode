/**
 * R697: minimal Eval panel — shows the latest baseline result path and
 * category breakdown. Fetches via eval.listCategories (catalog metadata)
 * and reads the most recent results/*.json from the project root via
 * Tauri's fs API.
 *
 * <p>For full visualization the harness already produces a self-contained
 * HTML report (tools/eval_report.py); this panel just surfaces the latest
 * file and per-category roll-up inside the desktop.
 */
import React, { useEffect, useState } from 'react';
import './EvalPanel.css';

interface CategoryStat {
  pass: number;
  total: number;
  passRate: number;
  score: number;
  avgCost: number;
}

interface BaselineSummary {
  totalRuns: number;
  passRate: number;
  avgScore: number;
  totalCost: number;
  p50Latency: number;
  p95Latency: number;
  byCategory: Record<string, CategoryStat>;
  jsonPath: string;
  htmlPath: string;
  timestamp: string;
}

interface EvalPanelProps {
  onClose: () => void;
}

const CATEGORY_WEIGHTS: Record<string, number> = {
  A1: 0.10, A2: 0.15, A3: 0.10, A4: 0.05, A5: 0.10, A6: 0.05, A7: 0.05,
  A8: 0.15, A9: 0.05, A10: 0.05, A11: 0.03, A12: 0.05, A13: 0.03, A14: 0.00,
};

const FLOORS: Record<string, number> = {
  A1: 0.85, A2: 0.85, A3: 0.80, A4: 0.80, A5: 0.75, A6: 0.80, A7: 0.80,
  A8: 0.95, A9: 0.85, A10: 0.75, A11: 0.75, A12: 0.75, A13: 0.75, A14: 0.75,
};

export function EvalPanel({ onClose }: EvalPanelProps) {
  const [summary, setSummary] = useState<BaselineSummary | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        // Use Tauri HTTP bridge to query the running daemon's RPC for
        // category metadata; read latest baseline JSON from disk.
        // Path resolution is best-effort — the harness writes to
        // D:\research\ai-agent-eval\results\latest.json in our env.
        // For shipped builds this should be replaced by a "fetch
        // recent baseline" RPC.
        const candidates = [
          'D:/research/ai-agent-eval/results/full_real_daemon_v2.json',
          'D:/research/ai-agent-eval/results/latest.json',
        ];
        let data: any = null;
        let jsonPath = '';
        for (const p of candidates) {
          try {
            // eslint-disable-next-line no-undef
            const text = await (window as any).__TAURI_INTERNALS__
              ? await (await import('@tauri-apps/api/fs')).readTextFile(p)
              : null;
            if (text) {
              data = JSON.parse(text);
              jsonPath = p;
              break;
            }
          } catch (_e) { /* try next */ }
        }
        if (!data) {
          setError('No baseline result found. Run harness first:\n  python harness/runner.py --daemon http://localhost:17888');
          return;
        }
        if (cancelled) return;
        const cats = data.by_category || {};
        setSummary({
          totalRuns: data.summary?.total_runs ?? 0,
          passRate: data.summary?.pass_rate ?? 0,
          avgScore: data.summary?.avg_score ?? 0,
          totalCost: data.summary?.total_cost_usd ?? 0,
          p50Latency: data.summary?.avg_latency_ms ?? 0,
          p95Latency: data.summary?.p95_latency_ms ?? 0,
          byCategory: cats,
          jsonPath,
          htmlPath: jsonPath.replace(/\.json$/, '.html'),
          timestamp: new Date().toISOString(),
        });
      } catch (e: any) {
        setError(e.message ?? String(e));
      } finally {
        setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, []);

  const overallPass = summary && Object.entries(summary.byCategory)
    .filter(([cat]) => FLOORS[cat] !== undefined)
    .every(([cat, s]) => s.score >= (FLOORS[cat] ?? 0));

  return (
    <div className="eval-panel-overlay" data-testid="eval-panel">
      <div className="eval-panel">
        <header className="eval-panel-header">
          <h2>Eval Baseline</h2>
          <button className="eval-close" onClick={onClose} aria-label="Close">×</button>
        </header>

        {loading && <p className="eval-loading">Loading baseline…</p>}
        {error && (
          <div className="eval-error">
            <strong>No baseline loaded</strong>
            <pre>{error}</pre>
            <p className="eval-hint">
              Run the harness to generate results/full_real_daemon_v2.json, then refresh this view.
            </p>
          </div>
        )}

        {summary && (
          <>
            <div className="eval-totals">
              <div><label>Pass rate</label><span>{(summary.passRate * 100).toFixed(1)}%</span></div>
              <div><label>Avg score</label><span>{summary.avgScore.toFixed(2)}</span></div>
              <div><label>Total cost</label><span>${summary.totalCost.toFixed(2)}</span></div>
              <div><label>P50 / P95</label><span>{Math.round(summary.p50Latency/1000)}s / {Math.round(summary.p95Latency/1000)}s</span></div>
              <div><label>Total runs</label><span>{summary.totalRuns}</span></div>
            </div>

            <div className={`eval-gate ${overallPass ? 'pass' : 'fail'}`}>
              {overallPass ? '✓ Deployment gate: PASS' : '✗ Deployment gate: FAIL'}
            </div>

            <table className="eval-cats">
              <thead>
                <tr>
                  <th>Cat</th>
                  <th>Score</th>
                  <th>Floor</th>
                  <th>Pass</th>
                  <th>Weight</th>
                </tr>
              </thead>
              <tbody>
                {Object.entries(summary.byCategory)
                  .sort(([a], [b]) => a.localeCompare(b))
                  .map(([cat, s]) => {
                    const floor = FLOORS[cat] ?? 0;
                    const hit = s.score >= floor;
                    return (
                      <tr key={cat} className={hit ? 'gate-pass' : 'gate-fail'}>
                        <td>{cat}</td>
                        <td>{s.score.toFixed(2)}</td>
                        <td>{floor.toFixed(2)} {hit ? '✓' : '✗'}</td>
                        <td>{s.pass}/{s.total} ({(s.passRate * 100).toFixed(0)}%)</td>
                        <td>{(CATEGORY_WEIGHTS[cat] ?? 0) * 100}%</td>
                      </tr>
                    );
                  })}
              </tbody>
            </table>

            <footer className="eval-footer">
              <div>JSON: <code>{summary.jsonPath}</code></div>
              <div>HTML: <code>{summary.htmlPath}</code></div>
              <div className="eval-hint">Open the HTML file in a browser for the full visual report.</div>
            </footer>
          </>
        )}
      </div>
    </div>
  );
}