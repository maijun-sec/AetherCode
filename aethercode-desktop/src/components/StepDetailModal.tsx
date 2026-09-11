// step detail modal.
//
// When the user clicks a finished (ok / error) step pill in
// the WorkflowProgressBar, this modal opens with the step's
// full event history. The progress bar's nested list (prior round)
// only shows the most recent 6 events inline; this modal is
// where the user goes when they want the whole story.
//
// What the modal shows:
//   - Step header: id, type, status badge, duration
//     (now - startedAt if running; or last-event-ts - first-
//     event-ts if finished), workflow name + runId for context.
//   - Event timeline: every event in stepEvents, oldest first,
//     with a relative timestamp ("0.4s", "1.2s", …) so the
//     user can see the cadence of the work.
//   - Per-event details: kind icon, name, summary, and a
//     collapsible raw payload (the full executor key=value
//     message) for debugging.
//   - "Copy raw" button — copies the concatenated raw payloads
//     to the clipboard so the user can paste them into a
//     GitHub issue or a session chat.
//
// What the modal does NOT show:
//   - Pending steps (no events yet) — clicking those would
//     just open an empty modal. The pill click is suppressed
//     for pending steps; only running / ok / error pills are
//     clickable.
//   - The full sub-task card body — that lives in MessageList
//     and is a different concern. The modal is for the
//     workflow-level step, not the message-level card.
//
// Layout: same backdrop + centered card pattern as
// WorkflowEditorModal (prior round). Esc closes. The user can't
// save anything from here — it's read-only by design.

import { useEffect, useMemo, useRef, useState } from 'react';
import { useStore } from '../store';
import type { ChildStepEvent } from '../store/types';
import './StepDetailModal.css';

const STATUS_LABEL: Record<string, string> = {
  pending: '○ pending',
  running: '● running',
  ok: '✓ ok',
  error: '✗ error',
};

// same icon column as the WorkflowProgressBar's
// nested row, so a user who learned the legend in the bar
// doesn't have to re-learn it in the modal.
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

const EVENT_KIND_LABEL: Record<ChildStepEvent['kind'], string> = {
  tool_use: 'tool use',
  tool_ok: 'tool ok',
  tool_err: 'tool error',
  text: 'text',
  run_start: 'run start',
  run_end: 'run end',
  note: 'side note',
  unknown: 'unknown',
};

function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  if (ms < 3_600_000) return `${Math.floor(ms / 60_000)}m ${Math.floor((ms % 60_000) / 1000)}s`;
  return `${Math.floor(ms / 3_600_000)}h ${Math.floor((ms % 3_600_000) / 60_000)}m`;
}

function formatRelative(ts: number, anchor: number): string {
  const d = ts - anchor;
  if (d < 1000) return `${d}ms`;
  if (d < 60_000) return `${(d / 1000).toFixed(1)}s`;
  return `${Math.floor(d / 60_000)}m${Math.floor((d % 60_000) / 1000)}s`;
}

function formatWall(ts: number): string {
  const d = new Date(ts);
  return d.toLocaleTimeString(undefined, { hour12: false });
}

export function StepDetailModal() {
  const selected = useStore((s) => s.selectedStepDetail);
  const running = useStore((s) => s.runningWorkflow);
  const closeStepDetail = useStore((s) => s.closeStepDetail);
  const [copied, setCopied] = useState(false);
  const copyTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Esc closes the modal. The pill click sets the field;
  // we listen for keydown globally. Bail when not open so
  // the keybinding doesn't fight other modals.
  useEffect(() => {
    if (!selected) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault();
        closeStepDetail();
      }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [selected, closeStepDetail]);

  // Clear the "Copied!" badge after 1.5s. We track the
  // timer in a ref so re-renders don't reset it.
  useEffect(() => () => {
    if (copyTimer.current) clearTimeout(copyTimer.current);
  }, []);

  // Compute everything we need to render in one memo so
  // the JSX stays readable. The `selected.runId` guard
  // keeps a stale click from a previous run from re-opening
  // a finished run's modal.
  const view = useMemo(() => {
    if (!selected || !running) return null;
    if (running.runId !== selected.runId) return null;
    const step = running.steps.find((s) => s.id === selected.stepId);
    if (!step) return null;
    const status = running.stepStatus[step.id] ?? 'pending';
    const events = (running.stepEvents ?? {})[step.id] ?? [];
    // Sort oldest-first; the store appends in arrival order
    // but prior round's cap keeps only the last MAX_STEP_EVENTS,
    // so the order we read is already chronological.
    const sorted = events.slice().sort((a, b) => a.ts - b.ts);
    const anchor = sorted[0]?.ts ?? running.startedAt;
    const lastTs = sorted[sorted.length - 1]?.ts ?? anchor;
    const duration = status === 'running'
      ? Date.now() - anchor
      : lastTs - anchor;
    return { step, status, events: sorted, anchor, duration, running };
  }, [selected, running]);

  if (!selected) return null;
  if (!view) {
    // The selected step is gone (workflow ended, or the
    // user navigated away). Auto-close after render so the
    // backdrop doesn't linger.
    setTimeout(closeStepDetail, 0);
    return null;
  }

  const { step, status, events, anchor, duration, running: wf } = view;

  const handleCopyRaw = async () => {
    const text = events.map((e) => e.raw).join('\n');
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      if (copyTimer.current) clearTimeout(copyTimer.current);
      copyTimer.current = setTimeout(() => setCopied(false), 1500);
    } catch {
      // Fallback: select a hidden textarea and execCommand
      // (some Tauri WebViews still lack clipboard API).
      const ta = document.createElement('textarea');
      ta.value = text;
      ta.style.position = 'fixed';
      ta.style.opacity = '0';
      document.body.appendChild(ta);
      ta.select();
      try { document.execCommand('copy'); setCopied(true); }
      catch { /* noop */ }
      document.body.removeChild(ta);
    }
  };

  return (
    <div className="step-detail-backdrop" role="dialog" aria-modal="true">
      <div className="step-detail-modal">
        <div className="step-detail-head">
          <div className="step-detail-title">
            <span className="step-detail-icon">{STATUS_LABEL[status]?.split(' ')[0] ?? '○'}</span>
            <span className="step-detail-name">{step.id}</span>
            <span className={`step-detail-badge step-detail-badge-${status}`}>
              {STATUS_LABEL[status] ?? status}
            </span>
            <span className="step-detail-type">{step.type}</span>
          </div>
          <button
            className="step-detail-close"
            onClick={closeStepDetail}
            title="关闭 (Esc)"
          >×</button>
        </div>
        <div className="step-detail-meta">
          <div className="step-detail-meta-row">
            <span className="step-detail-meta-label">workflow</span>
            <span className="step-detail-meta-value">{wf.name}</span>
            <span className="step-detail-meta-dim">·</span>
            <span className="step-detail-meta-dim">run {wf.runId.slice(0, 8)}</span>
          </div>
          <div className="step-detail-meta-row">
            <span className="step-detail-meta-label">duration</span>
            <span className="step-detail-meta-value">{formatDuration(duration)}</span>
            <span className="step-detail-meta-dim">·</span>
            <span className="step-detail-meta-label">events</span>
            <span className="step-detail-meta-value">
              {events.length}{events.length >= 6 ? '+ (capped)' : ''}
            </span>
            <span className="step-detail-meta-dim">·</span>
            <span className="step-detail-meta-label">started</span>
            <span className="step-detail-meta-value">{formatWall(anchor)}</span>
          </div>
        </div>
        <div className="step-detail-body">
          {events.length === 0 ? (
            <div className="step-detail-empty">
              这个 step 还没有事件。{status === 'running' ? '它正在运行中，等待子会话的第一个事件…' : '运行已结束但子会话没有产出任何事件。'}
            </div>
          ) : (
            <ol className="step-detail-timeline">
              {events.map((ev, j) => (
                <li
                  key={`${ev.ts}-${j}`}
                  className={`step-detail-event step-detail-event-${ev.kind}`}
                >
                  <div className="step-detail-event-line">
                    <span className="step-detail-event-icon">{EVENT_ICON[ev.kind] ?? '?'}</span>
                    <span className="step-detail-event-kind">{EVENT_KIND_LABEL[ev.kind] ?? ev.kind}</span>
                    {ev.name && <span className="step-detail-event-name">{ev.name}</span>}
                    {ev.summary && (
                      <span className="step-detail-event-summary">
                        {ev.summary}{ev.truncated ? '…' : ''}
                      </span>
                    )}
                    <span className="step-detail-event-time">
                      <span className="step-detail-event-rel">+{formatRelative(ev.ts, anchor)}</span>
                      <span className="step-detail-event-wall">{formatWall(ev.ts)}</span>
                    </span>
                  </div>
                  <details className="step-detail-event-raw">
                    <summary>raw payload</summary>
                    <pre>{ev.raw}</pre>
                  </details>
                </li>
              ))}
            </ol>
          )}
        </div>
        <div className="step-detail-foot">
          <span className="step-detail-foot-hint">Esc 关闭</span>
          <button
            className="step-detail-btn step-detail-btn-copy"
            onClick={handleCopyRaw}
            disabled={events.length === 0}
            title="把所有事件的 raw payload 复制到剪贴板"
          >{copied ? '✓ 已复制' : 'Copy raw'}</button>
        </div>
      </div>
    </div>
  );
}
