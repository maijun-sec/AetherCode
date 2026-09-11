import { useEffect, useState } from 'react';
import { useStore } from '../store';
import './TaskList.css';

export function TaskList() {
  const { tasks, currentTaskId, selectTask, cancelTask, refreshTasks, currentQuery } = useStore();
  const [filter, setFilter] = useState<'all' | 'running' | 'done' | 'failed'>('all');

  useEffect(() => { refreshTasks(); }, [refreshTasks]);

  // the user's current query is a synthetic running task we
  // inject at the top so the panel never says "no tasks" while
  // the model is actively working. It uses an id-prefix the rest
  // of the code (cancelTask, selectTask) knows nothing about, so
  // it's a view-only thing — the cancel button cancels the
  // engine, not the synthetic row.
  const synth = currentQuery
    ? [{
        id: currentQuery.id,
        description: currentQuery.prompt,
        status: 'running' as const,
        createdAt: currentQuery.startedAt,
        synthetic: true,
        stepCount: currentQuery.stepCount,
        toolCount: currentQuery.toolCount,
      }]
    : [];

  const visible = [...synth, ...tasks.filter((t) => {
    if (filter === 'all') return true;
    if (filter === 'running') return t.status === 'running' || t.status === 'pending';
    return t.status === filter;
  })];

  return (
    <div className="task-list">
      <div className="section-header">
        <span>Tasks</span>
        <div className="task-filters">
          {(['all', 'running', 'done', 'failed'] as const).map((f) => (
            <button key={f} className={`filter-chip ${filter === f ? 'active' : ''}`} onClick={() => setFilter(f)}>{f}</button>
          ))}
        </div>
      </div>
      {visible.length === 0 ? (
        <div className="task-empty">No tasks</div>
      ) : (
        <ul className="task-items">
          {visible.map((t: any) => (
            <li key={t.id} className={`task-item ${t.id === currentTaskId ? 'active' : ''} ${t.synthetic ? 'task-synthetic' : ''}`} onClick={() => !t.synthetic && selectTask(t.id)}>
              <span className={`task-icon task-status-${t.status}`}>●</span>
              <div className="task-info">
                <div className="task-desc">{t.description}</div>
                <div className="task-meta">
                  {t.synthetic
                    ? <>
                        <span className="task-step">step {t.stepCount}</span>
                        <span className="task-step-sep">·</span>
                        <span className="task-tool">{t.toolCount} tool{t.toolCount === 1 ? '' : 's'}</span>
                      </>
                    : <>
                        <span className="task-id">{t.id}</span>
                        <span className={`task-status-label task-status-${t.status}`}>{t.status}</span>
                      </>}
                </div>
              </div>
              {(t.status === 'running' || t.status === 'pending') && (
                <button className="task-stop" onClick={(e) => { e.stopPropagation(); cancelTask(t.id); }} title="Stop">⏹</button>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
