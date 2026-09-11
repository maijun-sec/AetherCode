// prior round-C: Intelligent Loop Guard banner.
//
// The engine's ProgressLoopDetector used to hard-stop the run
// on the first hit. R101 made it tiered (warn 1 → warn 2 →
// stop) and added a `loopAck` RPC so the user can clear the
// tier from the desktop when they decide "yes, this loop is
// intentional — keep going".
//
// This component is mounted by MessageList (above the
// scrollable message list, below the header). It watches the
// store's `loopWarn` state. When a warn arrives:
//
//   1. The banner fades in with a yellow/orange stripe + a
//      short human description ("tool X repeated 3 times" /
//      "model emitted 4265 characters without a tool call") + two buttons:
//      "Continue (loopAck)" / "Stop (cancel)".
//   2. the banner is now PERSISTENT. legacy-C it
//      auto-dismissed after 8s as a soft "no", but the
//      user-reported loop-stops-mid-task complaint hinged
//      on this auto-dismiss: by the time the user looked up
//      from the transcript, the banner was already gone and
//      the run had ended. The banner now stays until the
//      user explicitly clicks Continue or Stop. prior round also
//      wires Ctrl+L (or Cmd+L) as a hotkey for
//      acknowledge — the user can answer the prompt
//      without taking their hands off the keyboard.
//   3. Clicking "Continue" calls store.acknowledgeLoop() which
//      fires the loopAck RPC and clears the local state.
//   4. Clicking "Stop" calls store.dismissLoopWarn('user-cancel')
//      which clears the state AND calls cancelQuery() — the
//      engine gets a hard stop signal, the run ends.
//
// The banner is intentionally narrow (one row, ~480px) so
// it doesn't push the message list down by much. On
// acknowledge the banner is removed via a 200ms CSS
// transition (see LoopGuardBanner.css).

import { useEffect, useRef, useState } from 'react';
import { useStore } from '../store';
import './LoopGuardBanner.css';

// auto-dismiss is disabled. legacy-C the 8s
// window meant a user who glanced at the transcript for
// more than 8s would return to find the banner already
// gone and the run potentially ended. The banner is
// now persistent — the user MUST click Continue or Stop
// (or hit Ctrl+L). Set this back to a positive number
// (e.g. 8_000) to re-enable the old behaviour without
// any other code change.
const AUTO_DISMISS_MS = 0;

export function LoopGuardBanner() {
  const loopWarn = useStore((s) => s.loopWarn);
  const acknowledgeLoop = useStore((s) => s.acknowledgeLoop);
  const dismissLoopWarn = useStore((s) => s.dismissLoopWarn);
  // pull the cached session
  // summary so the banner can show
  // "Wrote 4 files, ran 2 shell commands
  // before the loop" — the user
  // explicitly asked for "a summary
  // regardless of whether the task
  // ended correctly" and a loop-detected
  // is the prototypical "not correctly
  // ended" case.
  const lastSessionSummary = useStore((s) => s.lastSessionSummary);
  const refreshSummary = useStore((s) => s.refreshSummary);
  // Local "closing" state so the CSS transition has a chance
  // to play before the store clears the warn. prior round: a
  // 200ms fade-out feels intentional; instant removal feels
  // like a glitch.
  const [closing, setClosing] = useState(false);
  const closingRef = useRef(false);
  // Stable ref so the auto-dismiss timer can read the latest
  // handlers without re-firing the effect on every render.
  const handlersRef = useRef({ acknowledgeLoop, dismissLoopWarn });
  handlersRef.current = { acknowledgeLoop, dismissLoopWarn };

  useEffect(() => {
    if (!loopWarn) {
      closingRef.current = false;
      setClosing(false);
      return;
    }
    // when a loop fires, fetch
    // the latest session summary so the
    // banner can show "Wrote 4 files,
    // ran 2 shell commands before the
    // loop" — the user explicitly asked
    // for a summary regardless of
    // pass/fail. Best-effort: a failed
    // refreshSummary leaves the previous
    // cached value in place.
    void refreshSummary().catch(() => {});
    // auto-dismiss is gated on AUTO_DISMISS_MS > 0.
    // The default constant is 0 (disabled) — legacy-C the
    // 8s window caused the user to miss the banner while
    // they were reading the transcript. The prior round behaviour
    // is: the banner stays until the user explicitly clicks
    // Continue / Stop or hits Ctrl+L. If a future round wants
    // to bring back the auto-dismiss (e.g. for an
    // unattended-agent mode), bumping AUTO_DISMISS_MS to a
    // positive number is the only change needed — the
    // setTimeout body below still does the right thing.
    if (AUTO_DISMISS_MS <= 0) return;

    // Start the auto-dismiss timer. The effect re-runs on
    // every loopWarn change, so a re-render that swaps to a
    // higher tier (or a different kind) resets the
    // AUTO_DISMISS_MS timer automatically. We don't need the
    // timestamp itself; the effect dependency on `loopWarn`
    // is enough to invalidate a stale timer.
    const timer = setTimeout(() => {
      if (closingRef.current) return;
      // Auto-dismiss: the user did nothing for
      // AUTO_DISMISS_MS. Treat as "let the detector keep
      // climbing" (no RPC, no cancel). The system message
      // in the transcript remains as a record.
      closingRef.current = true;
      setClosing(true);
      setTimeout(() => {
        handlersRef.current.dismissLoopWarn('auto');
      }, 200);
    }, AUTO_DISMISS_MS);
    return () => clearTimeout(timer);
  }, [loopWarn]);

  // Ctrl+L / Cmd+L hotkey for acknowledge. Mounted
  // for the lifetime of the banner — listener is added
  // when a warn is active and removed when it isn't, so the
  // keypress doesn't interfere with text input elsewhere
  // (e.g. the message box uses plain Ctrl+L as a browser
  // shortcut for "go to address bar", but our listener is
  // only mounted while the banner is up, so the user's
  // text input is unaffected). Cmd+L is for macOS users
  // who don't have a Ctrl key. The handler is a no-op
  // when the banner is already closing (closingRef) so a
  // double-tap doesn't fire two loopAck RPCs.
  useEffect(() => {
    if (!loopWarn) return;
    const onKey = (e: KeyboardEvent) => {
      // Use the e.code path so the layout doesn't matter
      // (e.g. AZERTY users still get L on the L position).
      if (e.key.toLowerCase() === 'l' && (e.ctrlKey || e.metaKey) && !e.shiftKey && !e.altKey) {
        e.preventDefault();
        if (closingRef.current) return;
        closingRef.current = true;
        setClosing(true);
        setTimeout(() => {
          void handlersRef.current.acknowledgeLoop(loopWarn.kind);
        }, 180);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [loopWarn]);

  if (!loopWarn) return null;

  const { tier, message, kind } = loopWarn;
  // Human label for the underlying kind. The engine emits
  // a raw kind like "loop-warn-1" wrapped around the actual
  // cause kind (e.g. "long_output", "same_fingerprint");
  // we surface the cause as a tag in the banner.
  const causeLabel = (() => {
    // The full message looks like
    //   "warn 1/2 after 4 turns — model produced 4265 chars
    //    of text and only repeats one tool call (long output
    //    + repeated tool call (4265 chars))"
    // We surface the raw description (after the em dash) as
    // the headline. A 200-char cap keeps the banner compact.
    const sep = message.indexOf('—');
    const body = sep >= 0 ? message.slice(sep + 1).trim() : message;
    return body.length > 200 ? body.slice(0, 197) + '…' : body;
  })();

  const handleContinue = () => {
    if (closingRef.current) return;
    closingRef.current = true;
    setClosing(true);
    setTimeout(() => {
      void handlersRef.current.acknowledgeLoop(kind);
    }, 180);
  };
  const handleStop = () => {
    if (closingRef.current) return;
    closingRef.current = true;
    setClosing(true);
    setTimeout(() => {
      void handlersRef.current.dismissLoopWarn('user-cancel');
    }, 180);
  };

  return (
    <div
      // id="loop-guard-banner" lets the
      // StatusBar's "click to jump" button scroll the
      // banner into view. The id is a stable string
      // (not the kind/tier) so the lookup is independent
      // of the current warn state.
      id="loop-guard-banner"
      className={`loop-guard-banner loop-guard-tier-${tier} ${closing ? 'loop-guard-closing' : ''}`}
      role="alertdialog"
      aria-live="polite"
      aria-labelledby="loop-guard-title"
      aria-describedby="loop-guard-body"
    >
      <div className="loop-guard-icon" aria-hidden="true">⚠</div>
      <div className="loop-guard-content">
        <div id="loop-guard-title" className="loop-guard-title">
          检测到可能的循环 ({tier}/2)
        </div>
        <div id="loop-guard-body" className="loop-guard-body">
          {causeLabel}
        </div>
        <div className="loop-guard-hint">
          {/* hint text updated to match the new
              persistent-banner behaviour. The old 8s note
              is gone; the new hint points at the Ctrl+L
              hotkey and reiterates the two-button
              decision. The copy (文案) stays in Chinese to match
              the rest of the banner. */}
          持续显示,直到你点「继续」或「停止」。继续: <kbd>Ctrl</kbd>+<kbd>L</kbd>(重置检测器,本 session 不再触发) · 停止: 取消当前任务
        </div>
        {/* surface the cached session
            summary as a one-liner. The user
            asked for "a summary regardless of
            pass/fail" — a loop-detected banner
            is the prototypical "fail" case,
            so showing the work done so far
            is the closing-the-loop. The line
            is hidden when the summary is
            still loading (refreshSummary in
            flight) so we don't show
            "No work recorded" for a session
            that just started. */}
        {lastSessionSummary &&
         (lastSessionSummary.files_written > 0 ||
          lastSessionSummary.files_read > 0 ||
          lastSessionSummary.shell_calls > 0 ||
          lastSessionSummary.total_tool_calls > 0) && (
          <div className="loop-guard-summary">
            <span className="loop-guard-summary-label">本 session 已完成:</span>
            <span className="loop-guard-summary-text">
              {lastSessionSummary.summary_text}
            </span>
          </div>
        )}
      </div>
      <div className="loop-guard-actions">
        <button
          className="loop-guard-btn loop-guard-btn-continue"
          onClick={handleContinue}
          title="调用 loopAck 重置 loop detector 层级"
        >
          继续 (loopAck)
        </button>
        <button
          className="loop-guard-btn loop-guard-btn-stop"
          onClick={handleStop}
          title="调用 cancel 取消当前任务"
        >
          停止
        </button>
      </div>
    </div>
  );
}
