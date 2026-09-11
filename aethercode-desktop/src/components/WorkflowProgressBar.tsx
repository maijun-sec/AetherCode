// workflow progress bar.
//
// When the user attaches a workflow to a message, the engine
// emits a `workflow_step` SideNote per declared step (status
// "pending" on run start; R103 will move them to "running" /
// "ok" / "error" as the executor advances). This component
// reads `runningWorkflow` from the store and renders a step
// pill row above the message list so the user can see "the
// engine is on step 3 of 7" without scrolling.
//
// nested progress. When a child session (a
// kind: skill or kind: agent step's body) emits events,
// the executor's wrapper forwards them as
// "child_session_event" SideNotes. The store parses
// each into a typed ChildStepEvent and stores it in
// runningWorkflow.stepEvents[stepId] (capped to
// MAX_STEP_EVENTS = 6). This component reads that
// map and renders a compact nested row under the
// currently-running step pill so the user can see
// "agent X is running Bash: ls -la" without leaving
// the workflow progress bar.
//
// Layout:
//   ┌─ workflow name · step 2/5 ──────────────┐
//   │ [▸ pull] [● unit] [○ lint] [○ review]   │
//   │   ┌─ nested for "unit" (running) ─────┐  │
//   │   │ ◐ Bash: ls -la (running)         │  │
//   │   │ ✓ Bash: grep error.log (ok)      │  │
//   │   │ ✓ Read: src/main.ts (1240 chars) │  │
//   │   └──────────────────────────────────┘  │
//   └─────────────────────────────────────────┘
//
// The nested row only renders for the running step
// (status === 'running'). When a step finishes
// (status moves to ok/error), the row collapses
// back to the bare pill — the events are still in
// the store for a future R-round's "step detail"
// panel, but they don't keep taking vertical
// space on the bar. This keeps the bar compact
// for the common case (3-5 steps) while still
// giving the user real-time visibility into the
// current step's work.
//
// Hover behaviour: hovering a nested row shows the
// raw message payload (the executor's structured
// key=value) in a tooltip. The user can verify what
// the engine actually forwarded without expanding
// anything.
//
// clickable pills. Done (ok / error) step
// pills are now buttons that open the StepDetailModal,
// which shows the full event history (the bar's
// nested list is capped to MAX_STEP_EVENTS = 6; the
// modal shows everything the store has). Running
// pills are also clickable so the user can open the
// modal mid-flight to see "what's been happening so
// far" without waiting for the step to finish.
// Pending pills stay non-clickable — there's no
// history yet, the modal would just be empty.

import { useStore } from '../store';
import type { ChildStepEvent } from '../store';
import './WorkflowProgressBar.css';

const STATUS_LABEL: Record<string, string> = {
  pending: '○',
  running: '●',
  ok: '✓',
  error: '✗',
};

// the icon column in the nested row.
// Match the colour of the parent pill so the
// user can tell at a glance whether a tool
// finished ok (✓ green), errored (✗ red), or
// is still running (◐ blue, animated).
const EVENT_ICON: Record<ChildStepEvent['kind'], string> = {
  tool_use: '◐',
  tool_ok: '✓',
  tool_err: '✗',
  run_start: '▶',
  run_end: '■',
  note: 'ℹ',
  text: '✎',
  unknown: '?',
};

export function WorkflowProgressBar() {
  const running = useStore((s) => s.runningWorkflow);
  const openStepDetail = useStore((s) => s.openStepDetail);
  if (!running) return null;
  const total = running.steps.length;
  const done = running.steps.filter(
    (st) => running.stepStatus[st.id] === 'ok',
  ).length;
  const failed = running.steps.filter(
    (st) => running.stepStatus[st.id] === 'error',
  ).length;
  // The "current" step is the first non-ok, non-running one —
  // or the last running one if we have one. Used for the
  // headline counter ("step 2/5").
  const currentIdx = (() => {
    for (let i = 0; i < running.steps.length; i++) {
      const s = running.stepStatus[running.steps[i].id];
      if (s === 'running' || s === 'pending') return i + 1;
    }
    return running.steps.length;
  })();
  const stateLabel = failed > 0
    ? `failed ${failed}`
    : done === total
      ? 'all done'
      : `step ${currentIdx}/${total}`;
  // collect per-step events. We render
  // the nested row only for the *currently running*
  // step; older done steps keep their events in
  // the store but don't show inline. The renderer
  // iterates stepEvents in the step's natural order
  // (most recent at the bottom) so the latest
  // tool result is always visible without scroll.
  const eventsByStep: Record<string, ChildStepEvent[]> = running.stepEvents ?? {};
  // The running step's id (if any) is the one we
  // expand inline.
  const runningStepId = (() => {
    for (const st of running.steps) {
      if (running.stepStatus[st.id] === 'running') return st.id;
    }
    return null;
  })();
  return (
    <div
      className="workflow-progress-bar"
      role="status"
      aria-live="polite"
      aria-label={`workflow ${running.name} ${stateLabel}`}
    >
      <div className="workflow-progress-head">
        <span className="workflow-progress-name">⚡ {running.name}</span>
        <span className="workflow-progress-state">{stateLabel}</span>
      </div>
      <ol className="workflow-progress-steps">
        {running.steps.map((step, i) => {
          const status = running.stepStatus[step.id] ?? 'pending';
          const events = eventsByStep[step.id] ?? [];
          const isRunning = step.id === runningStepId;
          // pills for finished (ok / error) and
          // running steps are clickable. Pending steps
          // stay non-interactive (no history yet — the
          // modal would just be empty). The pill itself
          // becomes a <button> for proper keyboard /
          // screen-reader semantics; the nested <ul>
          // stays as a child so its absolute positioning
          // is unaffected.
          const isClickable = status !== 'pending';
          const handleClick = () => {
            if (!isClickable) return;
            openStepDetail(running.runId, step.id);
          };
          return (
            <li
              key={step.id}
              className={`workflow-progress-pill workflow-progress-${status} ${isClickable ? 'workflow-progress-pill-clickable' : ''}`}
              title={isClickable
                ? `${step.id} (${step.type}) — ${status} · 点击查看详情`
                : `${step.id} (${step.type}) — ${status}`}
              onClick={isClickable ? handleClick : undefined}
              role={isClickable ? 'button' : undefined}
              tabIndex={isClickable ? 0 : undefined}
              onKeyDown={isClickable ? (e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault();
                  handleClick();
                }
              } : undefined}
            >
              <span className="workflow-progress-icon">
                {STATUS_LABEL[status] ?? '○'}
              </span>
              <span className="workflow-progress-label">{i + 1}. {step.id}</span>
              <span className="workflow-progress-type">{step.type}</span>
              {/* nested activity list under
                  the running step pill. Renders
                  only when:
                  - the step is currently running, AND
                  - there's at least one event to show.
                  Done/pending/error steps don't
                  expand (the bar stays compact).
                  The list scrolls internally if it
                  exceeds the MAX_STEP_EVENTS cap
                  (currently 6 — the renderer doesn't
                  scroll, but the store caps to the
                  last 6 so the rendered list is
                  always 6 or fewer rows). */}
              {isRunning && events.length > 0 ? (
                <ul
                  className="workflow-progress-nested"
                  aria-label={`nested events for ${step.id}`}
                >
                  {events.map((ev, j) => (
                    <li
                      key={`${ev.ts}-${j}`}
                      className={`workflow-progress-nested-row workflow-progress-nested-${ev.kind}`}
                      title={ev.raw}
                    >
                      <span className="workflow-progress-nested-icon">
                        {EVENT_ICON[ev.kind] ?? '?'}
                      </span>
                      <span className="workflow-progress-nested-name">
                        {ev.name || ev.kind}
                      </span>
                      {ev.summary ? (
                        <span className="workflow-progress-nested-summary">
                          {ev.summary}
                          {ev.truncated ? '…' : ''}
                        </span>
                      ) : null}
                    </li>
                  ))}
                </ul>
              ) : null}
            </li>
          );
        })}
      </ol>
    </div>
  );
}
