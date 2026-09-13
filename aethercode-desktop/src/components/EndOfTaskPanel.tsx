import { useEffect, useState } from 'react';
import { useStore } from '../store';
import './EndOfTaskPanel.css';

/**
 * end-of-task summary panel.
 *
 * <p>The user explicitly asked for "a
 * summary regardless of whether the
 * task ended correctly" — so every
 * time the engine emits a RunEnd, we
 * surface the session summary as a
 * dismissable panel at the bottom of
 * the message list. The panel covers
 * the {@code end_turn} case (the
 * "happy path" — the assistant
 * finished without running out of
 * turns or tripping a loop detector);
 * the {@code loop_*} case is already
 * handled by the {@link LoopGuardBanner}.
 *
 * <p>The panel is short-lived: a 12s
 * auto-dismiss timer keeps the chat
 * list from accumulating cruft. The
 * user can also click the X to close
 * it manually. A new query resets the
 * timer.
 */
export function EndOfTaskPanel() {
  // The last non-loop, non-error end-of-task
  // event triggers the panel. We track the
  // most recent RunEnd's stopReason via
  // the cached SessionSummary.state
  // (updated on every RunEnd by the store's
  // refreshSummary call).
  const lastSessionSummary = useStore((s) => s.lastSessionSummary);
  // Manual dismiss state — once the user
  // clicks X, the panel stays hidden until
  // the next RunEnd.
  const [dismissed, setDismissed] = useState(false);
  // Auto-dismiss after 5 minutes (was 12s — the user reported
  // "汇总结果 disappears before I can read it" at the 12s
  // default). The panel is dismissable via the X button for
  // users who want it gone sooner; the 5-minute window keeps
  // the chat from accumulating cruft for the user who doesn't
  // care, while giving the rest of us enough time to actually
  // read the summary.
  useEffect(() => {
    if (dismissed) return;
    const t = window.setTimeout(() => setDismissed(true), 5 * 60_000);
    return () => window.clearTimeout(t);
  }, [dismissed, lastSessionSummary?.state, lastSessionSummary?.last_activity_at_ms]);
  // Reset dismiss state on every fresh
  // summary (so a new run shows the panel
  // again).
  useEffect(() => {
    if (lastSessionSummary?.last_activity_at_ms) {
      setDismissed(false);
    }
  }, [lastSessionSummary?.last_activity_at_ms]);
  if (dismissed) return null;
  if (!lastSessionSummary) return null;
  // Only show on end_turn. Other stop
  // reasons (loop_*, max_iterations,
  // error_*) are handled by
  // LoopGuardBanner or the error
  // transcript line.
  if (lastSessionSummary.state !== 'end_turn') return null;
  // Skip the panel when there's no work
  // recorded (e.g. a query that hit
  // end_turn without a single tool call)
  // — the panel would just say "No work
  // recorded" which is noise.
  if (
    lastSessionSummary.files_written === 0 &&
    lastSessionSummary.files_read === 0 &&
    lastSessionSummary.shell_calls === 0 &&
    lastSessionSummary.total_tool_calls === 0
  ) {
    return null;
  }
  // We also need at least 1 tool call
  // to make the panel worthwhile. A
  // pure-text Q&A round-trip doesn't
  // need a summary banner.
  if (lastSessionSummary.total_tool_calls === 0) return null;
  return (
    <div className="end-of-task-panel" role="status" aria-live="polite">
      <div className="end-of-task-icon" aria-hidden="true">✓</div>
      <div className="end-of-task-content">
        <div className="end-of-task-title">本轮完成</div>
        <div className="end-of-task-summary">
          {lastSessionSummary.summary_text}
        </div>
        {Object.keys(lastSessionSummary.by_tool).length > 0 && (
          <div className="end-of-task-bytool">
            {Object.entries(lastSessionSummary.by_tool)
              .slice(0, 4)
              .map(([tool, count]) => (
                <span key={tool} className="end-of-task-tool-chip">
                  {tool} × {count}
                </span>
              ))}
            {Object.keys(lastSessionSummary.by_tool).length > 4 && (
              <span className="end-of-task-tool-chip">
                +{Object.keys(lastSessionSummary.by_tool).length - 4} more
              </span>
            )}
          </div>
        )}
      </div>
      <button
        className="end-of-task-close"
        onClick={() => setDismissed(true)}
        aria-label="关闭"
        title="关闭总结"
      >
        ×
      </button>
    </div>
  );
}
