import { useEffect, useState } from 'react';
import { useStore } from '../store';
import './SubagentPanel.css';

// a live list of background subagent jobs, paired
// with the TUI's <SubagentPanel> (prior round in the TUI repo).
// Each row shows the job's id, role, status, and elapsed
// time. RUNNING rows have a Cancel button; FAILED /
// CANCELLED rows have a Retry button (R362 round 3);
// COMPLETED rows have an "Insert result" button
// that drops the captured result text into the input box
// (the user can edit before sending).
//
// The panel reads the same `subagent` slice of the store
// that drives the StatusBar indicator. The cancel action
// is dispatched via a new JSON-RPC method (`subagentCancel`)
// added in prior round; the wire path is: button click → store
// action → AetherCodeRpc.subagentCancel(jobId) → engine
// SubagentRegistry.cancel(jobId) → thread interrupt +
// status flip → subagent_event notification → store
// update → row re-renders as CANCELLED. R362 round 3
// adds the Retry button: click → AetherCodeRpc.subagentRetry
// (jobId) → SubagentRegistry.retry(jobId) → status flip
// to RUNNING + fresh worker thread → subagent_event
// notification → row re-renders as RUNNING. The
// cancellation + retry paths are deliberately
// symmetric so the panel's UI is consistent.

const STATUS_LABEL: Record<string, string> = {
  RUNNING: 'running',
  COMPLETED: 'done',
  FAILED: 'failed',
  CANCELLED: 'cancelled',
};

function formatDuration(ms: number): string {
  if (!Number.isFinite(ms) || ms < 0) return '—';
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  const m = Math.floor(ms / 60_000);
  const s = Math.floor((ms % 60_000) / 1000);
  return `${m}m${s.toString().padStart(2, '0')}s`;
}

export function SubagentPanel() {
  const subagent = useStore((s) => s.subagent);
  const setCurrentInput = useStore((s) => s.setCurrentInput);
  const dismissSubagentTerminal = useStore((s) => s.dismissSubagentTerminal);
  const currentSessionId = useStore((s) => s.currentSessionId);
  // "insert result" is the only action that needs
  // an RPC call (cancel needs subagentCancel). We don't
  // expose cancel from a top-level selector because the
  // user might not be on the SubagentPanel tab when a
  // job starts — the StatusBar's indicator is the
  // always-visible path. The SubagentPanel's Cancel
  // button is a shortcut.
  // We call the RPC via window.__TAURI__ invoke.
  const [cancelling, setCancelling] = useState<Record<string, boolean>>({});
  // R362 round 3: track in-flight Retry clicks
  // so the button shows "retrying…" while the RPC
  // is in flight. Without this the user can
  // double-click and trigger two retries (the
  // registry's retry() is idempotent, so two
  // retries from a still-FAILED job are harmless,
  // but two retries from a job that already
  // succeeded on attempt 1 would race with each
  // other).
  const [retrying, setRetrying] = useState<Record<string, boolean>>({});
  const [, setTick] = useState(0);
  // tick once per second while a RUNNING job is
  // present so the elapsed column updates without a
  // server round-trip. The tick is paused when nothing
  // is running to keep the panel idle.
  useEffect(() => {
    const hasRunning = Object.values(subagent.jobs).some((j) => j.status === 'RUNNING');
    if (!hasRunning) return;
    const t = setInterval(() => setTick((n) => n + 1), 1000);
    return () => clearInterval(t);
  }, [subagent.jobs]);

  const ids = Object.keys(subagent.jobs);
  if (ids.length === 0) {
    return (
      <div className="subagent-panel subagent-panel-empty">
        <div className="subagent-panel-header">
          <span>Subagents (0)</span>
        </div>
        <div className="subagent-panel-empty-msg">
          No background subagents yet. Ask the model to run one in the background.
        </div>
      </div>
    );
  }

  // Sort: RUNNING first (newest), then terminal (newest first).
  const running = ids
    .filter((id) => subagent.jobs[id].status === 'RUNNING')
    .sort((a, b) => subagent.jobs[b].startedAtMs - subagent.jobs[a].startedAtMs);
  const terminal = ids
    .filter((id) => subagent.jobs[id].status !== 'RUNNING')
    .sort((a, b) => {
      const ea = subagent.jobs[a].endedAtMs ?? subagent.jobs[a].startedAtMs;
      const eb = subagent.jobs[b].endedAtMs ?? subagent.jobs[b].startedAtMs;
      return eb - ea;
    });
  const sorted = [...running, ...terminal];

  const onCancel = async (jobId: string) => {
    if (cancelling[jobId]) return;
    setCancelling((c) => ({ ...c, [jobId]: true }));
    try {
      // The store's prior round subagent_event subscription
      // will deliver the CANCELLED notification; the
      // row re-renders automatically. We don't
      // optimistically flip the status here.
      const { invoke } = await import('@tauri-apps/api/core');
      await invoke('rpc_call', {
        method: 'subagentCancel',
        params: { jobId },
      });
    } catch (e) {
      console.warn('subagentCancel failed', e);
    } finally {
      setCancelling((c) => {
        const { [jobId]: _drop, ...rest } = c;
        return rest;
      });
    }
  };

  // R362 round 3: Retry button handler. Mirrors
  // onCancel — the registry's retry() resets the
  // job to RUNNING and fires a fresh subagent_event
  // notification, which the store picks up and
  // re-renders the row. We don't optimistically
  // flip the status (consistent with onCancel's
  // approach).
  const onRetry = async (jobId: string) => {
    if (retrying[jobId]) return;
    setRetrying((r) => ({ ...r, [jobId]: true }));
    try {
      const { invoke } = await import('@tauri-apps/api/core');
      // The registry reset alone is not enough —
      // we also need the LLM to actually re-run the
      // prompt via subagent_retry tool (which spawns
      // a fresh worker thread). The desktop wires
      // this by triggering the tool through the
      // store's pendingToolInvocation path. For now
      // we just send the RPC; the LLM (running in
      // the background of the session) will see the
      // RUNNING event and respond. A future round
      // can wire the tool-call path directly so
      // retries don't require a live primary session.
      const r: any = await invoke('rpc_call', {
        method: 'subagentRetry',
        params: { jobId },
      });
      if (r && r.ok === false) {
        // The registry refused (e.g. job is
        // COMPLETED, RUNNING, or unknown). Surface
        // a console hint so a dev mode sees it; in
        // production the user sees no visible
        // change (the row stays in its current
        // state because the subagent_event was
        // never fired).
        console.warn('subagentRetry refused:', r.reason);
      }
    } catch (e) {
      console.warn('subagentRetry failed', e);
    } finally {
      setRetrying((r) => {
        const { [jobId]: _drop, ...rest } = r;
        return rest;
      });
    }
  };

  // "View subagent →" link. Switches the active
  // session to the subagent's session so the user can
  // read its transcript inline. Mirrors the TUI's
  // Ctrl+2..9 + 'v' keymap.
  const onView = async (jobId: string) => {
    if (jobId === currentSessionId) return;
    try {
      const { invoke } = await import('@tauri-apps/api/core');
      // 1. Tell the engine to switch to the subagent's session.
      await invoke('rpc_call', { method: 'loadSession', params: { sessionId: jobId } });
      // 2. The store hydrates the new session's transcript
      //    + flips viewingSubagentId so the breadcrumb
      //    can show "viewing <jobId>".
      useStore.getState().setViewingSubagentId?.(jobId);
    } catch (e) {
      console.warn('view subagent failed', e);
    }
  };

  const onInsert = (jobId: string) => {
    const job = subagent.jobs[jobId];
    if (!job) return;
    if (job.status === 'COMPLETED' && job.resultText) {
      setCurrentInput(job.resultText);
    } else if (job.status === 'FAILED') {
      setCurrentInput(`[subagent ${jobId} failed: ${job.summary || 'no detail'}]`);
    } else if (job.status === 'CANCELLED') {
      setCurrentInput(`[subagent ${jobId} was cancelled; result not available]`);
    } else {
      // RUNNING — nothing to insert yet. The StatusBar
      // indicator already shows the live state.
      return;
    }
    // dismiss any pending terminal toast so the
    // user doesn't see two "the subagent finished"
    // notifications at once.
    if (subagent.lastTerminal && subagent.lastTerminal.jobId === jobId) {
      dismissSubagentTerminal();
    }
  };

  return (
    <div className="subagent-panel">
      <div className="subagent-panel-header">
        <span>Subagents ({ids.length})</span>
        {subagent.running > 0 ? (
          <span className="subagent-running-badge">
            {subagent.running} running
          </span>
        ) : null}
      </div>
      <ul className="subagent-panel-list">
        {sorted.map((jobId) => {
          const job = subagent.jobs[jobId];
          const now = Date.now();
          const elapsed = job.status === 'RUNNING'
            ? Math.max(0, now - job.startedAtMs)
            : job.elapsedMs;
          return (
            <li
              key={jobId}
              className={`subagent-panel-row subagent-panel-row-${job.status.toLowerCase()}`}
            >
              <div className="subagent-panel-row-top">
                <div className="subagent-panel-row-id">{jobId}</div>
                <div className="subagent-panel-row-role">{job.role || 'general-purpose'}</div>
                <div className={`subagent-panel-row-status subagent-panel-status-${job.status.toLowerCase()}`}>
                  {STATUS_LABEL[job.status] ?? job.status}
                </div>
                <div className="subagent-panel-row-elapsed">{formatDuration(elapsed)}</div>
              </div>
              {job.summary ? (
                <div className="subagent-panel-row-summary" title={job.summary}>
                  {truncate(job.summary, 80)}
                </div>
              ) : null}
              <div className="subagent-panel-row-actions">
                {job.status === 'RUNNING' ? (
                  <button
                    className="subagent-panel-btn subagent-panel-btn-cancel"
                    disabled={!!cancelling[jobId]}
                    onClick={() => onCancel(jobId)}
                    title="Send subagentCancel RPC; the engine interrupts the worker thread"
                  >
                    {cancelling[jobId] ? 'cancelling…' : 'Cancel'}
                  </button>
                ) : job.status === 'FAILED' || job.status === 'CANCELLED' ? (
                  // R362 round 3: Retry button for
                  // FAILED / CANCELLED rows. Sits in
                  // the same slot as Cancel so the
                  // layout doesn't shift between
                  // states. COMPLETED rows keep the
                  // Insert-result button (the legacy
                  // behaviour); only failed / cancelled
                  // jobs are retryable (re-running a
                  // completed job would change history).
                  <>
                    <button
                      className="subagent-panel-btn subagent-panel-btn-retry"
                      disabled={!!retrying[jobId]}
                      onClick={() => onRetry(jobId)}
                      title="Send subagentRetry RPC; the engine resets the job to RUNNING and spawns a fresh worker thread"
                    >
                      {retrying[jobId] ? 'retrying…' : 'Retry'}
                    </button>
                    <button
                      className="subagent-panel-btn subagent-panel-btn-insert"
                      onClick={() => onInsert(jobId)}
                      title="Insert a short failure note into the input box"
                    >
                      Insert note
                    </button>
                  </>
                ) : (
                  <button
                    className="subagent-panel-btn subagent-panel-btn-insert"
                    onClick={() => onInsert(jobId)}
                    title="Insert the captured result (or a short note) into the input box"
                  >
                    Insert result
                  </button>
                )}
                {/* "View subagent →" link. Sits beside
                    Cancel / Insert so a user who's looking at
                    a completed subagent can jump to its
                    transcript without losing their place. */}
                <button
                  className="subagent-panel-btn subagent-panel-btn-view"
                  onClick={() => onView(jobId)}
                  title="Switch the active session to this subagent's transcript"
                >
                  View subagent →
                </button>
              </div>
            </li>
          );
        })}
      </ul>
    </div>
  );
}

function truncate(s: string, n: number): string {
  if (!s) return '';
  if (s.length <= n) return s;
  return s.slice(0, n - 1) + '…';
}
