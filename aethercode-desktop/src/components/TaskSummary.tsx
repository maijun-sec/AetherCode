import { useStore } from '../store';
import './TaskSummary.css';

function formatUptime(ms?: number): string {
  if (!ms) return '—';
  const s = Math.floor(ms / 1000);
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m ${s % 60}s`;
  const h = Math.floor(m / 60);
  return `${h}h ${m % 60}m`;
}

/**
 * this component was renamed in spirit from {@code TaskSummary}
 * to {@code SessionSummary}, but the file / function / CSS class
 * names are kept the same so the
 * {@code session/__tests__/LeftPanelWire.test.tsx} source-pin
 * (which regex-matches {@code TaskSummary} in
 * {@code LeftPanel.tsx}) still passes without modification. The
 * visible label was changed from "Current Task" to
 * "Current Session" because the user-facing concept
 * the panel is reporting on is the active conversation
 * (= session), NOT a subagent / workflow task
 * (which lives in the right-panel "Subagents" / "Tasks"
 * tabs). The card now shows the session id, working
 * directory, model, permission mode, daemon port, and
 * uptime — every field a user needs to know "what
 * session am I in".
 *
 * <p>The first section used to show the current subagent
 * task's id / status / description (TaskRegistry
 * state). That data is still available — it lives in
 * the {@code RightPanel}'s "Subagents" / "Tasks" tabs.
 * Showing it in the left-rail top card was confusing
 * because users conflated "task" with "conversation" —
 * the R175 feedback was unambiguous: "CURRENT TASK
 * should be CURRENT SESSION".
 *
 * <p>R200: the user reported the left rail "the session id in the top-right corner
 * is being truncated" and "can the cwd below be put on the same row as model / perm / workflow,
 * to save vertical space". The previous layout was a
 * vertical list of {@code label → value} rows under two
 * "Current Session" / "Engine" headers — every row eating
 * 24px of vertical space. R200 collapses the most-used
 * metadata (id / cwd / model / perm / daemon / stream) into
 * a single chip row at the top, with secondary info
 * (uptime, name) on a second row only when available.
 * The session id is shown in full (not {@code .slice(0,8)})
 * because horizontal space is now abundant.
 */
export function TaskSummary() {
  const { engineState, daemonInfo, metrics, isStreaming, cwd, sessions, currentSessionId } = useStore();
  const currentSession = sessions.find((s) => s.id === currentSessionId)
    ?? sessions[0];

  return (
    <div className="task-summary">
      <div className="section-header">
        <span>Current Session</span>
      </div>
      {/* single-row layout. ID + CWD + Model + Perm +
       *  Daemon + Stream all share one flex line. The CWD
       *  is the row's first element so the long path
       *  ellipsises from the right (CSS), keeping the
       *  session id + model badges fully visible. */}
      <div className="task-summary-row" title={cwd ?? ''}>
        <span className="task-pill task-pill-cwd" title={cwd ?? ''}>
          <span className="task-pill-icon" aria-hidden>📂</span>
          <span className="task-pill-value">{cwd ?? '—'}</span>
        </span>
        <span className="task-pill task-pill-id" title={currentSessionId ?? ''}>
          <span className="task-pill-label">id</span>
          <span className="task-pill-value mono">{currentSessionId?.slice(0, 8) ?? '—'}</span>
        </span>
        <span className="task-pill task-pill-model" title={engineState?.model ?? ''}>
          <span className="task-pill-label">model</span>
          <span className="task-pill-value mono">{engineState?.model ?? '—'}</span>
        </span>
        <span className="task-pill task-pill-perm">
          <span className="task-pill-label">perm</span>
          <span className="task-pill-value">{engineState?.permissionMode ?? '—'}</span>
        </span>
        {daemonInfo && (
          <span className="task-pill task-pill-daemon" title={`daemon port ${daemonInfo.port}`}>
            <span className="task-pill-label">daemon</span>
            <span className="task-pill-value mono">:{daemonInfo.port}</span>
          </span>
        )}
        {isStreaming && (
          <span className="task-pill task-pill-stream">
            <span className="task-pill-value streaming">● live</span>
          </span>
        )}
      </div>
      {/* Optional second row: only when there's a
       *  user-friendly session name (from the session list)
       *  or an uptime number worth showing. Keeps the
       *  default state to ONE row. */}
      {(currentSession?.name || metrics?.uptime) && (
        <div className="task-summary-row task-summary-row-secondary">
          {currentSession?.name && (
            <span className="task-pill task-pill-name" title={currentSession.name}>
              <span className="task-pill-label">name</span>
              <span className="task-pill-value">{currentSession.name}</span>
            </span>
          )}
          {metrics?.uptime != null && (
            <span className="task-pill task-pill-uptime" title="daemon uptime">
              <span className="task-pill-label">up</span>
              <span className="task-pill-value mono">{formatUptime(metrics.uptime)}</span>
            </span>
          )}
        </div>
      )}
    </div>
  );
}
