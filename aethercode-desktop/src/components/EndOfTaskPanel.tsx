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
 * the message list.
 *
 * <p>R348 (PM P0-4): the panel now
 * also fires on failed run_ends
 * (stopReason ∈ {error, loop, max_turns})
 * with a ↻ retry button + a confirm
 * step. The button:
 *   1. Shows a brief one-line confirm
 *      ("retry? you already changed X files")
 *   2. On confirm, calls
 *      `retryLastFailedPrompt()` which
 *      re-fires the same prompt without
 *      requiring the user to retype.
 *
 * <p>The panel is short-lived: a 5-min
 * auto-dismiss timer keeps the chat
 * list from accumulating cruft. The
 * user can also click the X to close
 * it manually. A new query resets the
 * timer.
 */
export function EndOfTaskPanel() {
  // The last end-of-task event triggers the
  // panel. We track the most recent RunEnd's
  // stopReason via the cached SessionSummary.state
  // (updated on every RunEnd by the store's
  // refreshSummary call).
  const lastSessionSummary = useStore((s) => s.lastSessionSummary);
  const lastFailedPrompt = useStore((s) => s.lastFailedPrompt);
  const retryLastFailedPrompt = useStore((s) => s.retryLastFailedPrompt);
  // Manual dismiss state — once the user
  // clicks X, the panel stays hidden until
  // the next RunEnd.
  const [dismissed, setDismissed] = useState(false);
  // R348: two-step retry confirmation. When
  // the user clicks ↻ the first time, we
  // flip a local "armed" flag so the button
  // changes label to "confirm retry?" and
  // we render a one-line warning listing
  // the side effects ("already wrote X
  // files"). A second click within 5 s
  // commits the retry; otherwise the arm
  // expires and the user has to click ↻
  // again. This is the cheapest possible
  // confirmation surface that doesn't
  // block the user behind a modal — a
  // power user can hold the button or
  // double-click, a casual user gets a
  // clear "are you sure?" affordance.
  const [retryArmed, setRetryArmed] = useState(false);
  useEffect(() => {
    setRetryArmed(false);
  }, [lastFailedPrompt, lastSessionSummary?.last_activity_at_ms]);
  useEffect(() => {
    if (!retryArmed) return;
    const t = window.setTimeout(() => setRetryArmed(false), 5_000);
    return () => window.clearTimeout(t);
  }, [retryArmed]);
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
  // R348: a failed run (error / loop / max_turns) gets
  // a different panel copy + a ↻ retry button. We
  // surface only when the daemon has populated
  // lastFailedPrompt — the store clears it on the
  // next successful run_end, so the button naturally
  // disappears.
  const isFailed = lastSessionSummary.state !== 'end_turn'
    && lastSessionSummary.state !== 'awaiting_user_decision';
  const showFailedPanel = isFailed && !!lastFailedPrompt;
  if (!showFailedPanel) {
    // The legacy happy-path gate — only render on end_turn
    // with at least one tool call. Same rules as before.
    if (lastSessionSummary.state !== 'end_turn') return null;
    if (lastSessionSummary.total_tool_calls === 0) return null;
  }
  const isFailedRender = showFailedPanel;
  // R348 retry side-effect summary. We list the side
  // effects that already happened so the user can
  // decide whether retry is safe. The fields are
  // sourced from SessionSummary which the daemon
  // populates per turn.
  const sideEffects = isFailedRender
    ? [
        lastSessionSummary.files_written > 0 && `已写 ${lastSessionSummary.files_written} 个文件`,
        lastSessionSummary.shell_calls > 0 && `已执行 ${lastSessionSummary.shell_calls} 个 shell 命令`,
        lastSessionSummary.files_read > 0 && `已读 ${lastSessionSummary.files_read} 个文件`,
      ].filter(Boolean)
    : [];
  return (
    <div
      className={`end-of-task-panel ${isFailedRender ? 'end-of-task-panel-failed' : ''}`}
      role="status"
      aria-live="polite"
    >
      <div
        className={`end-of-task-icon ${isFailedRender ? 'end-of-task-icon-failed' : ''}`}
        aria-hidden="true"
      >
        {isFailedRender ? '✗' : '✓'}
      </div>
      <div className="end-of-task-content">
        <div className="end-of-task-title">
          {isFailedRender ? '本轮失败' : '本轮完成'}
        </div>
        <div className="end-of-task-summary">
          {lastSessionSummary.summary_text}
        </div>
        {isFailedRender && sideEffects.length > 0 && (
          <div className="end-of-task-side-effects">
            <span className="end-of-task-side-effects-label">已执行:</span>
            {sideEffects.map((s, i) => (
              <span key={i} className="end-of-task-side-effects-chip">{s}</span>
            ))}
          </div>
        )}
        {!isFailedRender && Object.keys(lastSessionSummary.by_tool).length > 0 && (
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
        {isFailedRender && (
          <div className="end-of-task-retry-row">
            {retryArmed ? (
              <button
                className="end-of-task-retry-confirm"
                onClick={() => {
                  setRetryArmed(false);
                  void retryLastFailedPrompt();
                }}
                title="再次点击确认重发同一条 query"
              >
                ✓ confirm retry?
              </button>
            ) : (
              <button
                className="end-of-task-retry"
                onClick={() => setRetryArmed(true)}
                title="重发同一条 query (再点击一次确认)"
                data-testid="end-of-task-retry-btn"
              >
                ↻ retry
              </button>
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
