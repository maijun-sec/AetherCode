import { useStore } from '../store';
import './ProgressBar.css';

export function ProgressBar() {
  const { tasks, currentTaskId, isStreaming, currentQuery, awaitingUserDecision, subTasks, currentSubTaskId } = useStore();
  const runningSubagent = tasks.find((t) => t.status === 'running');
  // the current user query is the source of truth for the
  // progress bar. We show the query's step count and tool count
  // (filled by the MessageList step-grouper) so the user can see
  // "we're on step 3, used 5 tools so far" while a query is active.
  const query = currentQuery;
  const active = query ?? runningSubagent ?? (currentTaskId ? tasks.find((t) => t.id === currentTaskId) : null);
  // per-sub-task threshold (15) is the source of truth for
  // the adaptive control. We surface the current sub-task's step
  // count against this threshold so the user can see "how close
  // to needing a human decision" we are.
  const SUB_SOFT = 15;
  const activeSubTask = currentSubTaskId
    ? subTasks.find((st) => st.id === currentSubTaskId)
    : null;
  // Pick the most informative step count: prefer sub-task-specific
  // when available, fall back to the query's overall count.
  const stepCount = activeSubTask
    ? (subTasks.indexOf(activeSubTask) === subTasks.length - 1
        ? query?.stepCount ?? 0
        : 0)
    : query?.stepCount ?? 0;
  const subTaskSteps = (() => {
    // For a sub-task in progress, the engine emits the steps as
    // they happen. We don't have a direct count; we approximate
    // with the engine's bump count: TodoRunController's
    // soft threshold is bumped each time we hit it. The
    // `awaitingUserDecision` event fires when max bumps is hit.
    if (activeSubTask) {
      return stepCount;
    }
    return 0;
  })();
  // We don't have a real "total" yet, so the bar shows a
  // step-based fraction. 0–30 steps is the soft-threshold
  // window — we map that to 0–100% so the fill grows as the
  // model works. Anything beyond fills to 100% with a slightly
  // different color (the "bumped" state).
  const STEP_SOFT = 30;
  const pct = query
    ? Math.min(100, Math.round((query.stepCount / STEP_SOFT) * 100))
    : (active && isStreaming ? 60 : 0);

  // The active row is either a Subagent task (has .description)
  // or the current query (no .description). For the query case
  // we use the synthetic label "Current query".
  const activeLabel = query
    ? `Step ${query.stepCount} · ${query.toolCount} tool${query.toolCount === 1 ? '' : 's'}`
    : (active
        ? ('description' in (active as any) ? (active as any).description : 'Current query')
        : (isStreaming ? 'Streaming…' : 'No active task'));

  // adaptive control indicator. Shows the sub-task's
  // progress against the soft threshold and a "next bump" hint
  // if the user is close to triggering an awaiting-decision.
  const tuning = (() => {
    if (awaitingUserDecision) {
      return { kind: 'paused' as const, label: '已暂停,等待你的决定', pct: 100 };
    }
    if (activeSubTask && subTaskSteps > 0) {
      const p = Math.min(100, Math.round((subTaskSteps / SUB_SOFT) * 100));
      const remaining = Math.max(0, SUB_SOFT - subTaskSteps);
      return {
        kind: 'subtask' as const,
        label: `子任务: ${subTaskSteps}/${SUB_SOFT} 步 (剩 ${remaining} 步后引擎会自动 bump 阈值)`,
        pct: p,
      };
    }
    if (query) {
      const p = Math.min(100, Math.round((query.stepCount / SUB_SOFT) * 100));
      return {
        kind: 'query' as const,
        label: `本次查询: ${query.stepCount} 步 / 子任务阈值 ${SUB_SOFT}`,
        pct: p,
      };
    }
    return null;
  })();

  return (
    <div className="progress-bar">
      <div className="section-header">
        <span>Progress</span>
        <span className="section-meta">{pct}%</span>
      </div>
      <div className="progress-track">
        <div
          className={`progress-fill ${isStreaming ? 'streaming' : ''} ${query && pct >= 100 ? 'bumped' : ''}`}
          style={{ width: `${pct}%` }}
        />
      </div>
      <div className="progress-detail">{activeLabel}</div>
      {tuning && (
        <div className={`progress-tuning progress-tuning-${tuning.kind}`}>
          <div className="progress-tuning-bar">
            <div
              className="progress-tuning-fill"
              style={{ width: `${tuning.pct}%` }}
            />
          </div>
          <div className="progress-tuning-label">{tuning.label}</div>
        </div>
      )}
    </div>
  );
}
