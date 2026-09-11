import { useMemo } from 'react';
import { useStore, TodoItem, TodoSubTask, ChatSubTask } from '../store';
import './AgentTasksPanel.css';

// the "Plan" tab in the RightPanel. Renders the live
// todo list the model is currently working through. The
// aethercode engine emits a `todo_write` tool call whenever the
// plan changes; the desktop store captures the full list
// verbatim (top-level + subtasks) and we render it here.
//
// source-priority for the list itself is `daemonTodos`
// (daemon-pushed via `task_state kind=todo_update`, the
// engine's canonical state) over `currentTodos` (R228 quick
// fix from the tool_use_start event's input). Both are
// populated when available; we pick the daemon one when it
// has content, falling back to the wire-event one for the
// sub-second window before the notification round-trips.
//
// Source of truth precedence (within the chosen list):
//   1. subTasks[] (sub_task_start / sub_task_end events) — the
//      runtime status, wins when available
//   2. todos[].subtasks[].status — the model's declared
//      status, used as the default
//
// Top-level status comes from todos[].status only (the
// engine does not emit top-level start/end events).

const TOP_STATUS_ICON: Record<TodoItem['status'], string> = {
  pending: '○',
  in_progress: '▶',
  completed: '✓',
  cancelled: '⊘',
  skipped: '–',
};
const SUB_STATUS_ICON: Record<TodoSubTask['status'], string> = {
  pending: '○',
  in_progress: '▶',
  completed: '✓',
  failed: '✗',
  skipped: '–',
};

function topStatusClass(s: TodoItem['status']): string {
  return `agent-task-status agent-task-status-${s.replace('_', '-')}`;
}
function subStatusClass(s: TodoSubTask['status']): string {
  return `agent-subtask-status agent-subtask-status-${s.replace('_', '-')}`;
}

function resolveSubStatus(
  topIndex: number,
  sub: TodoSubTask,
  runtimeSubs: ChatSubTask[],
): TodoSubTask['status'] {
  // the engine emits SubTaskStart/SubTaskEnd events keyed
  // by `taskId:subTaskId`. When we find a match, the runtime
  // status wins (it's the most up-to-date signal — even after
  // the model has stopped calling todo_write, the engine can
  // still mark a sub-task as completed via sub_todo_write).
  const match = runtimeSubs.find(
    (rs) => rs.taskId === topIndex && rs.subTaskId === sub.id,
  );
  return match ? match.status : sub.status;
}

interface SummaryStats {
  total: number;
  completed: number;
  inProgress: number;
  pending: number;
  cancelled: number;
  skipped: number;
  failed: number;
  pct: number;
}

function summarize(todos: TodoItem[], runtimeSubs: ChatSubTask[]): SummaryStats {
  let total = 0, completed = 0, inProgress = 0, pending = 0, cancelled = 0, skipped = 0, failed = 0;
  for (const t of todos) {
    total++;
    switch (t.status) {
      case 'completed': completed++; break;
      case 'in_progress': inProgress++; break;
      case 'pending': pending++; break;
      case 'cancelled': cancelled++; break;
      case 'skipped': skipped++; break;
    }
    for (const st of t.subtasks) {
      const s = resolveSubStatus(t.index, st, runtimeSubs);
      if (s === 'completed') completed++;
      else if (s === 'in_progress') inProgress++;
      else if (s === 'pending') pending++;
      else if (s === 'failed') failed++;
      else if (s === 'skipped') skipped++;
    }
  }
  const pct = total > 0 ? Math.round((completed / (total)) * 100) : 0;
  return { total, completed, inProgress, pending, cancelled, skipped, failed, pct };
}

export function AgentTasksPanel() {
  // source-priority. `daemonTodos` is the daemon-pushed
  // canonical list (task_state kind=todo_update notification).
  // `currentTodos` is the R228 fast-path from the tool_use_start
  // event's input — used as fallback until the daemon's
  // notification arrives (typically <50ms in practice but the
  // fallback keeps the panel responsive during the gap).
  const daemonTodos = useStore((s) => s.daemonTodos);
  const wireTodos = useStore((s) => s.currentTodos);
  const todos = daemonTodos.length > 0 ? daemonTodos : wireTodos;
  const runtimeSubs = useStore((s) => s.subTasks);
  const stats = useMemo(() => summarize(todos, runtimeSubs), [todos, runtimeSubs]);

  if (todos.length === 0) {
    return (
      <div className="agent-tasks-panel">
        <div className="agent-tasks-header">
          <h3>Plan</h3>
        </div>
        <div className="agent-tasks-empty">
          <div className="agent-tasks-empty-icon">📋</div>
          <div className="agent-tasks-empty-text">
            Plan not started yet.
            <br />
            The model will emit a <code>todo_write</code> when it
            begins a non-trivial task.
          </div>
        </div>
      </div>
    );
  }

  const currentTop = todos.find((t) => t.status === 'in_progress');
  const summaryLine =
    `${stats.completed}/${stats.total} done` +
    (stats.inProgress > 0 ? ` · ▶ ${stats.inProgress} running` : '') +
    (stats.failed > 0 ? ` · ✗ ${stats.failed} failed` : '') +
    (stats.cancelled > 0 ? ` · ⊘ ${stats.cancelled} cancelled` : '');

  return (
    <div className="agent-tasks-panel">
      <div className="agent-tasks-header">
        <h3>Plan</h3>
        <div className="agent-tasks-summary">{summaryLine}</div>
      </div>
      <div className="agent-tasks-progress">
        <div className="agent-tasks-progress-bar" role="progressbar"
             aria-valuenow={stats.pct} aria-valuemin={0} aria-valuemax={100}>
          <div className="agent-tasks-progress-fill" style={{ width: `${stats.pct}%` }} />
        </div>
        <div className="agent-tasks-progress-pct">{stats.pct}%</div>
      </div>
      {currentTop ? (
        <div className="agent-tasks-current">
          <span className="agent-tasks-current-label">Currently working on</span>
          <span className="agent-tasks-current-content">
            {currentTop.activeForm || currentTop.content}
          </span>
        </div>
      ) : null}
      <ol className="agent-tasks-list">
        {todos.map((t) => (
          <TaskRow
            key={t.index}
            task={t}
            runtimeSubs={runtimeSubs}
            isCurrent={t === currentTop}
          />
        ))}
      </ol>
    </div>
  );
}

function TaskRow({
  task,
  runtimeSubs,
  isCurrent,
}: {
  task: TodoItem;
  runtimeSubs: ChatSubTask[];
  isCurrent: boolean;
}) {
  const subStats = useMemo(() => {
    let done = 0;
    for (const st of task.subtasks) {
      if (resolveSubStatus(task.index, st, runtimeSubs) === 'completed') done++;
    }
    return { done, total: task.subtasks.length };
  }, [task, runtimeSubs]);
  return (
    <li className={`agent-task-row ${isCurrent ? 'is-current' : ''}`}>
      <div className="agent-task-head">
        <span className={topStatusClass(task.status)} aria-label={task.status}>
          {TOP_STATUS_ICON[task.status]}
        </span>
        <span className="agent-task-content">
          {task.activeForm && task.status === 'in_progress'
            ? task.activeForm
            : task.content}
        </span>
        {subStats.total > 0 ? (
          <span className="agent-task-substats">
            {subStats.done}/{subStats.total}
          </span>
        ) : null}
      </div>
      {task.subtasks.length > 0 ? (
        <ol className="agent-subtasks">
          {task.subtasks.map((st) => {
            const status = resolveSubStatus(task.index, st, runtimeSubs);
            return (
              <li key={st.id} className="agent-subtask-row">
                <span className={subStatusClass(status)} aria-label={status}>
                  {SUB_STATUS_ICON[status]}
                </span>
                <span className="agent-subtask-content">{st.content}</span>
                {st.summary && status === 'completed' ? (
                  <span className="agent-subtask-summary" title={st.summary}>
                    {st.summary}
                  </span>
                ) : null}
              </li>
            );
          })}
        </ol>
      ) : null}
    </li>
  );
}
