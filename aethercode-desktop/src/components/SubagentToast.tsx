import { useEffect, useRef } from 'react';
import { useStore } from '../store';
import './SubagentToast.css';

// prior round: a persistent toast that surfaces a
// background subagent's terminal transition (COMPLETED
// / FAILED / CANCELLED). Mounted once at the App root.
// Reads `subagent.lastTerminal` from the store; on every
// change, the toast mounts in place. The user dismisses
// it by clicking the toast body / × button, pressing
// Esc, or — once another subagent terminal event
// arrives — letting the new toast replace the old.
//
// We intentionally do NOT show a toast for RUNNING events
// (the StatusBar pill covers that). The toast is for the
// "the background job you kicked off just finished"
// moment — a single-line acknowledgement with the
// engine's own elapsed time + summary so the user knows
// whether the result is worth clicking through (e.g. a
// failed subagent with "boom" in the summary deserves
// attention).
//
// removed the legacy 4s auto-dismiss. The
// prior round lesson applied: auto-dismiss is user-hostile
// for state-changing notifications. A user who kicked
// off a subagent and switched focus to another window
// would return to find the toast already gone and the
// StatusBar pill pointing at "subagent done" with no
// way to see *what* was done. The toast is now
// persistent; the user explicitly dismisses it. The
// R119+ escape hatch is the new "Dismiss" button + Esc
// hotkey — the affordance is visible (the × icon and
// the explicit "Dismiss" label), so the user knows how
// to clear it. The StatusBar indicator continues to
// show the most-recent terminal state even after the
// toast is dismissed, so dismissing the toast is not
// the same as losing the information.

export function SubagentToast() {
  const { subagent, dismissSubagentTerminal } = useStore();
  // Track the last-terminal at-key we showed a toast for so
  // a duplicate dispatch (e.g. a re-subscribe replay) does
  // not restart the toast or flash the UI.
  const lastKeyRef = useRef<string | null>(null);

  // Esc hotkey to dismiss the toast. The
  // listener is only mounted while the toast is
  // visible (i.e. when subagent.lastTerminal is
  // non-null) so a user typing in the message
  // box isn't intercepted. The handler also
  // swallows the Esc when the toast is gone (no
  // other component should be capturing Esc for
  // a non-existent toast).
  useEffect(() => {
    if (!subagent.lastTerminal) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault();
        dismissSubagentTerminal();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [subagent.lastTerminal, dismissSubagentTerminal]);

  const t = subagent.lastTerminal;
  if (!t) return null;

  const key = `${t.jobId}:${t.status}:${t.atMs}`;
  if (lastKeyRef.current !== key) {
    // New terminal event landed. Reset the key
    // so the next render paints the new toast.
    lastKeyRef.current = key;
  }

  const tone =
    t.status === 'COMPLETED' ? 'success' :
    t.status === 'FAILED'    ? 'error'   :
    'muted';
  const icon =
    t.status === 'COMPLETED' ? '✓' :
    t.status === 'FAILED'    ? '✗' :
    '⊘';
  const elapsedTxt = t.elapsedMs > 0
    ? ` (${formatElapsed(t.elapsedMs)})`
    : '';
  const summaryTxt = t.summary ? ` — ${t.summary}` : '';

  return (
    <div
      className={`subagent-toast subagent-toast-${tone}`}
      role="status"
      aria-live="polite"
      // clicking the body dismisses (same
      // affordance as the legacy toast, but no
      // more racing the 4s auto-dismiss). The
      // explicit Dismiss button below gives a
      // visible "how do I make this go away" hint
      // for users who don't know to click the body.
      onClick={() => dismissSubagentTerminal()}
      title="Click to dismiss (or press Esc)"
    >
      <span className="subagent-toast-icon">{icon}</span>
      <span className="subagent-toast-body">
        <span className="subagent-toast-id">subagent {t.jobId}</span>
        {' '}
        <span className="subagent-toast-status">{t.status.toLowerCase()}</span>
        {elapsedTxt}
        {summaryTxt ? <span className="subagent-toast-summary">{summaryTxt}</span> : null}
      </span>
      {/* explicit Dismiss button. The legacy
          toast had a × glyph as a visual hint but no
          actual <button> — keyboard / screen-reader
          users couldn't reach it. The new <button>
          is focusable, has aria-label, and stops
          click propagation so the body-click
          handler doesn't fire twice. */}
      <button
        className="subagent-toast-dismiss"
        aria-label="Dismiss notification"
        title="Dismiss (Esc)"
        onClick={(e) => {
          e.stopPropagation();
          dismissSubagentTerminal();
        }}
      >
        ×
      </button>
    </div>
  );
}

function formatElapsed(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  const sec = ms / 1000;
  if (sec < 10) return `${sec.toFixed(1)}s`;
  return `${Math.round(sec)}s`;
}
