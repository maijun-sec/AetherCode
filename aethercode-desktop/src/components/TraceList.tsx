import { useState, useMemo } from 'react';
import { useStore } from '../store';
import { TraceSummary } from '../lib/methods';
import './TraceList.css';

// R82+ Issue 2: turn-grouped execution log. The previous
// TraceList showed flat JSON — every span was a row and the only
// way to understand a run was to open the <pre> detail. Now we
// group spans by `runId` (read from `attrs.runId`, which the
// engine stashes on every span), show each turn as a card with
// its query + tool steps + outcome, and hide the raw JSON
// behind a "show details" toggle for debugging.

interface Turn {
  runId: string;
  startedAt: number;
  query?: TraceSummary;
  toolSteps: TraceSummary[];
  endMs?: number;
  status: 'running' | 'ok' | 'error';
  promptLen?: number;
}

function fmtDuration(start: number, end?: number): string {
  if (!end) return `${Date.now() - start}ms`;
  const ms = end - start;
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  return `${Math.floor(ms / 60_000)}m${Math.floor((ms % 60_000) / 1000)}s`;
}

function fmtTime(ts: number): string {
  return new Date(ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

function describeTool(t: TraceSummary): string {
  const tool = t.name.replace(/^tool\./, '');
  const input = t.attrs?.inputSummary;
  if (typeof input === 'string' && input) return `${tool}  ${input}`;
  return tool;
}

function groupByRun(spans: TraceSummary[]): Turn[] {
  // Newest runs first
  const byRun = new Map<string, Turn>();
  for (const t of spans) {
    const runId = (t.attrs?.runId as string) ?? t.traceId;
    let turn = byRun.get(runId);
    if (!turn) {
      turn = {
        runId,
        startedAt: t.startMs,
        toolSteps: [],
        status: 'running',
      };
      byRun.set(runId, turn);
    }
    turn.startedAt = Math.min(turn.startedAt, t.startMs);
    if (t.endMs) turn.endMs = Math.max(turn.endMs ?? 0, t.endMs);
    if (t.status === 'error') turn.status = 'error';
    else if (t.status === 'ok' && turn.status !== 'error') turn.status = 'ok';
    if (t.name === 'query') {
      turn.query = t;
      const len = t.attrs?.promptLen;
      if (typeof len === 'number') turn.promptLen = len;
    } else if (t.name.startsWith('tool.')) {
      turn.toolSteps.push(t);
    }
  }
  return Array.from(byRun.values()).sort((a, b) => b.startedAt - a.startedAt);
}

export function TraceList() {
  const { traces } = useStore();
  const [expanded, setExpanded] = useState<string | null>(null);
  const [showDebug, setShowDebug] = useState(false);

  const turns = useMemo(() => groupByRun(traces), [traces]);

  return (
    <div className="trace-list">
      <div className="section-header">
        <span>Execution log</span>
        <span className="section-meta">
          {turns.length} {turns.length === 1 ? 'turn' : 'turns'}
        </span>
      </div>
      {turns.length === 0 ? (
        <div className="trace-empty">No execution history yet</div>
      ) : (
        <ul className="trace-items">
          {turns.map((turn) => {
            const isOpen = expanded === turn.runId;
            const totalDur = turn.endMs ? turn.endMs - turn.startedAt : undefined;
            const toolCount = turn.toolSteps.length;
            return (
              <li
                key={turn.runId}
                className={`turn turn-status-${turn.status}`}
                onClick={() => setExpanded(isOpen ? null : turn.runId)}
              >
                <div className="turn-row">
                  <span className="turn-icon" aria-hidden="true">
                    {turn.status === 'ok' ? '✓' : turn.status === 'error' ? '✗' : '·'}
                  </span>
                  <div className="turn-summary">
                    <div className="turn-title">
                      {turn.query ? `Query · ${turn.promptLen ?? '?'} chars` : `Run ${turn.runId.slice(0, 8)}`}
                    </div>
                    <div className="turn-sub">
                      {toolCount > 0
                        ? `${toolCount} ${toolCount === 1 ? 'tool' : 'tools'}`
                        : 'no tool calls'}
                      {turn.query?.attrs?.stopReason
                        ? ` · ${turn.query.attrs.stopReason}`
                        : ''}
                    </div>
                  </div>
                  <span className="turn-dur">{fmtDuration(turn.startedAt, turn.endMs)}</span>
                </div>
                {isOpen && (
                  <div className="turn-detail" onClick={(e) => e.stopPropagation()}>
                    <div className="turn-time">started {fmtTime(turn.startedAt)}{totalDur ? ` · ran ${fmtDuration(0, totalDur)}` : ''}</div>
                    {turn.toolSteps.length === 0 ? (
                      <div className="turn-empty">No tool calls in this run.</div>
                    ) : (
                      <ol className="turn-steps">
                        {turn.toolSteps.map((s) => (
                          <li key={s.traceId} className={`turn-step step-status-${s.status}`}>
                            <span className="step-icon" aria-hidden="true">
                              {s.status === 'ok' ? '✓' : s.status === 'error' ? '✗' : '·'}
                            </span>
                            <span className="step-label">{describeTool(s)}</span>
                            <span className="step-dur">{fmtDuration(s.startMs, s.endMs)}</span>
                          </li>
                        ))}
                      </ol>
                    )}
                    <div className="turn-debug-row">
                      <button
                        className="turn-debug-btn"
                        onClick={() => setShowDebug((d) => !d)}
                      >
                        {showDebug ? '▾ hide raw' : '▸ show raw'}
                      </button>
                    </div>
                    {showDebug && (
                      <pre className="trace-detail mono">
                        {JSON.stringify(turn, null, 2)}
                      </pre>
                    )}
                  </div>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
