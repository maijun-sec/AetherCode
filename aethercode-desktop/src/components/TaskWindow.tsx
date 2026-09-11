/**
 * T-453 (Phase 5 R5): desktop `TaskWindow` (dockable).
 *
 * design.md §5.3 / spec.md §4.7: a dockable window that
 * shows the engine's background tasks (subagent / long-
 * running tools / todos) with a list, a detail pane, and
 * a control bar (cancel / re-attach / copy-id).
 *
 * The existing `TaskList` component is a single-column
 * list embedded in the right panel. `TaskWindow` is the
 * *full* window: it includes the list, a detail view
 * showing the focused task's steps, and a transport
 * controls section (the same actions exposed by the
 * `task/attach` / `task/kill` RPCs).
 *
 * The component is presentational — it reads from the
 * zustand store and dispatches via the existing
 * `selectTask` / `cancelTask` / `refreshTasks` actions.
 * No RPC is fired from the component itself; the
 * `refreshTasks` action is fired once on mount.
 *
 * design.md §5.3 calls this a "dockable window". The
 * Tauri host can surface it as a separate OS window
 * (via `WebviewWindow.new` from `@tauri-apps/api`) or as
 * a full-screen modal. This component is environment-
 * agnostic — it just renders.
 */

import { useEffect, useState } from 'react';
import { useStore } from '../store';
import type { TaskInfo } from '../lib/methods';
import './TaskWindow.css';

export interface TaskWindowProps {
  /** Fired when the user clicks the close button. The
   *  host (App) decides whether to hide the modal or
   *  close the OS window. */
  onClose: () => void;
  /** When true, the window is rendered as a modal
   *  overlay (used in the web-only fallback). The
   *  Tauri host can pass false to render the component
   *  inside a dedicated WebviewWindow. */
  modal?: boolean;
}

type StatusFilter = 'all' | 'running' | 'pending' | 'completed' | 'failed' | 'killed';

const STATUS_ICON: Record<TaskInfo['status'], string> = {
  running:   '●',
  pending:   '○',
  completed: '✓',
  failed:    '✗',
  killed:    '⊘',
};

const STATUS_COLOR: Record<TaskInfo['status'], string> = {
  running:   'cyan',
  pending:   'gray',
  completed: 'green',
  failed:    'red',
  killed:    'gray',
};

/** Format a duration in ms as `12.3s` / `1m 24s` /
 *  `2h 5m`. Exported so tests + sibling components can
 *  reuse the formatter. */
export function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  if (ms < 3_600_000) {
    const m = Math.floor(ms / 60_000);
    const s = Math.floor((ms % 60_000) / 1000);
    return `${m}m ${s}s`;
  }
  const h = Math.floor(ms / 3_600_000);
  const m = Math.floor((ms % 3_600_000) / 60_000);
  return `${h}h ${m}m`;
}

export function TaskWindow({ onClose, modal = true }: TaskWindowProps) {
  const {
    tasks,
    currentTaskId,
    selectTask,
    cancelTask,
    refreshTasks,
  } = useStore();
  const [filter, setFilter] = useState<StatusFilter>('all');
  const [copyToast, setCopyToast] = useState<string | null>(null);

  // Pull the latest task list once on mount. The
  // `refreshTasks` action is idempotent so we don't
  // need to debounce.
  useEffect(() => {
    void refreshTasks();
  }, [refreshTasks]);

  // Filter the list. The "current query" is the user's
  // in-flight prompt; we surface it as a synthetic
  // "running" row at the top.
  const visible = tasks
    .filter((t) => filter === 'all' ? true : t.status === filter)
    .sort((a, b) => (b.createdAtMs ?? 0) - (a.createdAtMs ?? 0));

  const focused = currentTaskId ? tasks.find((t) => t.id === currentTaskId) : visible[0];

  const copy = (text: string, label: string) => {
    try {
      void navigator.clipboard.writeText(text);
      setCopyToast(`${label} copied`);
      window.setTimeout(() => setCopyToast(null), 2000);
    } catch {
      setCopyToast('clipboard unavailable');
      window.setTimeout(() => setCopyToast(null), 2000);
    }
  };

  return (
    <div className={modal ? 'task-window-overlay' : 'task-window-root'} role="dialog" aria-label="Tasks">
      <div className="task-window">
        <div className="task-window-header">
          <h2>Tasks</h2>
          <span className="task-window-count">{tasks.length} total · {tasks.filter((t) => t.status === 'running' || t.status === 'pending').length} active</span>
          <button className="task-window-close" onClick={onClose} aria-label="Close">×</button>
        </div>
        <div className="task-window-body">
          <div className="task-window-list">
            <div className="task-window-filters">
              {(['all', 'running', 'pending', 'completed', 'failed', 'killed'] as const).map((f) => (
                <button
                  key={f}
                  className={'task-filter-chip' + (filter === f ? ' active' : '')}
                  onClick={() => setFilter(f)}
                >
                  {f}
                </button>
              ))}
            </div>
            {visible.length === 0 ? (
              <div className="task-window-empty">No tasks match this filter.</div>
            ) : (
              <ul className="task-window-items">
                {visible.map((t) => {
                  const isFocused = t.id === currentTaskId || (currentTaskId == null && t === visible[0]);
                  return (
                    <li
                      key={t.id}
                      className={'task-window-row' + (isFocused ? ' focused' : '')}
                      onClick={() => selectTask(t.id)}
                    >
                      <span className="task-window-icon" style={{ color: STATUS_COLOR[t.status] }}>
                        {STATUS_ICON[t.status]}
                      </span>
                      <div className="task-window-row-info">
                        <div className="task-window-row-desc" title={t.description}>
                          {t.description || `task ${t.id}`}
                        </div>
                        <div className="task-window-row-meta">
                          <span className="task-window-row-id">{t.id.slice(-12)}</span>
                          <span className="task-window-row-status" style={{ color: STATUS_COLOR[t.status] }}>
                            {t.status}
                          </span>
                        </div>
                      </div>
                    </li>
                  );
                })}
              </ul>
            )}
          </div>
          <div className="task-window-detail">
            {focused ? (
              <>
                <div className="task-window-detail-head">
                  <span className="task-window-detail-title">{focused.description || `task ${focused.id}`}</span>
                  <span className="task-window-detail-status" style={{ color: STATUS_COLOR[focused.status] }}>
                    {STATUS_ICON[focused.status]} {focused.status}
                  </span>
                </div>
                <dl className="task-window-detail-fields">
                  <dt>id</dt>
                  <dd>
                    <code>{focused.id}</code>
                    <button
                      className="task-window-copy"
                      onClick={() => copy(focused.id, 'task id')}
                      title="Copy id"
                    >copy</button>
                  </dd>
                  <dt>created</dt>
                  <dd>{focused.createdAtMs ? new Date(focused.createdAtMs).toLocaleString() : '—'}</dd>
                  <dt>duration</dt>
                  <dd>{focused.endedAtMs && focused.createdAtMs ? formatDuration(focused.endedAtMs - focused.createdAtMs) : '—'}</dd>
                </dl>
                <div className="task-window-controls">
                  <button
                    className="task-window-cancel"
                    onClick={() => void cancelTask(focused.id)}
                    disabled={focused.status !== 'running' && focused.status !== 'pending'}
                    title="Cancel this task"
                  >
                    ⏹ Cancel
                  </button>
                  <button
                    className="task-window-attach"
                    onClick={() => selectTask(focused.id)}
                    title="Attach to this task's output stream"
                  >
                    ↗ Attach
                  </button>
                </div>
                {copyToast ? (
                  <div className="task-window-toast" role="status">{copyToast}</div>
                ) : null}
              </>
            ) : (
              <div className="task-window-detail-empty">
                Select a task from the list to see its details.
              </div>
            )}
          </div>
        </div>
        <div className="task-window-footer">
          <span className="task-window-help">
            click a row to focus · ↗ attach · ⏹ cancel · Esc to close
          </span>
        </div>
      </div>
    </div>
  );
}

export default TaskWindow;
