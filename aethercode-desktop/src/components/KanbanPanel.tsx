import { useEffect, useState } from 'react';
import { useStore, TaskInfo } from '../store';
import './KanbanPanel.css';

// a Kanban-style task board. Tasks are
// grouped by status (Pending / Running / Completed /
// Failed / Killed) and the user can drag-drop a task
// card to a new column to transition its status. The
// engine's TaskRegistry is the source of truth; the
// store's `task_event` subscription keeps the board
// in sync without polling. prior round ships the
// infrastructure + minimal UI; the rest of the
// multica-protocol parity (issue body, assignee,
// labels, etc.) is a follow-up R-round.
const COLUMNS: { status: TaskInfo['status']; label: string; icon: string }[] = [
  { status: 'pending',   label: 'Pending',   icon: '○' },
  { status: 'running',   label: 'Running',   icon: '▶' },
  { status: 'completed', label: 'Completed', icon: '✓' },
  { status: 'failed',    label: 'Failed',    icon: '✗' },
  { status: 'killed',    label: 'Killed',    icon: '⊘' },
];

export function KanbanPanel() {
  const { tasks, refreshTasks, createTask, updateTaskStatus } = useStore();
  const [newDescription, setNewDescription] = useState('');
  const [draggingId, setDraggingId] = useState<string | null>(null);

  useEffect(() => { refreshTasks(); }, [refreshTasks]);

  const handleCreate = async () => {
    const desc = newDescription.trim();
    if (!desc) return;
    await createTask({ description: desc, type: 'user' });
    setNewDescription('');
  };

  const handleDrop = async (e: React.DragEvent, targetStatus: TaskInfo['status']) => {
    e.preventDefault();
    const id = e.dataTransfer.getData('text/aethercode-task-id') || draggingId;
    setDraggingId(null);
    if (!id) return;
    await updateTaskStatus(id, targetStatus);
  };

  return (
    <div className="kanban-panel">
      <div className="kanban-header">
        <h3>Tasks</h3>
        <div className="kanban-add">
          <input
            type="text"
            placeholder="新任务…"
            value={newDescription}
            onChange={(e) => setNewDescription(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') handleCreate(); }}
          />
          <button
            className="kanban-add-btn"
            disabled={!newDescription.trim()}
            onClick={handleCreate}
            title="Create task (Enter)"
          >+</button>
        </div>
      </div>
      <div className="kanban-board">
        {COLUMNS.map((col) => {
          const items = tasks.filter((t) => t.status === col.status);
          return (
            <div
              key={col.status}
              className={`kanban-col kanban-col-${col.status}`}
              onDragOver={(e) => e.preventDefault()}
              onDrop={(e) => handleDrop(e, col.status)}
            >
              <div className="kanban-col-header">
                <span className="kanban-col-icon">{col.icon}</span>
                <span className="kanban-col-label">{col.label}</span>
                <span className="kanban-col-count">{items.length}</span>
              </div>
              <div className="kanban-col-items">
                {items.length === 0 ? (
                  <div className="kanban-col-empty">—</div>
                ) : (
                  items.map((t) => (
                    <div
                      key={t.id}
                      className={`kanban-card ${draggingId === t.id ? 'dragging' : ''}`}
                      draggable
                      onDragStart={(e) => {
                        e.dataTransfer.setData('text/aethercode-task-id', t.id);
                        e.dataTransfer.effectAllowed = 'move';
                        setDraggingId(t.id);
                      }}
                      onDragEnd={() => setDraggingId(null)}
                      title={t.description}
                    >
                      <div className="kanban-card-desc">{t.description}</div>
                      <div className="kanban-card-meta">
                        <span className="kanban-card-type">{t.type}</span>
                        {t.endedAtMs > 0 ? (
                          <span className="kanban-card-time">
                            {Math.round((t.endedAtMs - t.createdAtMs) / 100) / 10}s
                          </span>
                        ) : t.status === 'running' ? (
                          <span className="kanban-card-time">
                            {Math.round((Date.now() - t.createdAtMs) / 100) / 10}s
                          </span>
                        ) : null}
                      </div>
                    </div>
                  ))
                )}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
