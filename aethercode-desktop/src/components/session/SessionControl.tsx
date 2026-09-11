// SessionControl action group.
//
// Three buttons — Continue / Pause / Stop — read task state via AppContext
// and dispatch `task/resume`, `task/pause`, `task/kill` mutations through
// `useTaskControl`.
//
// Button enablement rules:
//   Continue — available when paused / completed / failed (lets the user
//              re-run a failed task).
//   Pause    — available when running.
//   Stop     — available when running / paused.
// When no task is selected (taskId is null), the buttons are disabled as a
// whole but remain clickable so the empty-state logic can still fire.

import { useApp } from '../../state/AppContext';
import { useTaskControl } from '../../rpc/mutations';
import type { TaskInfo } from '../../rpc/types';
import './SessionControl.css';

export interface SessionControlProps {
  /** The active task id; null when no task is selected. */
  taskId: string | null;
  /** Live task state. The store's event subscription updates this
   *  so the button enablement reacts in real time. */
  taskState?: TaskInfo['state'] | 'unknown';
  /** Optional override for the button labels. Defaults are
   *  Continue / Pause / Stop. */
  labels?: { continue?: string; pause?: string; stop?: string };
  /** Called after a successful control mutation. The host can
   *  hook this to scroll the transcript to the latest event. */
  onAfterControl?: (op: 'resume' | 'pause' | 'kill') => void;
}

const DEFAULT_LABELS = {
  continue: '▶ Continue',
  pause: '⏸ Pause',
  stop: '■ Stop',
};

export function SessionControl({
  taskId,
  taskState = 'unknown',
  labels,
  onAfterControl,
}: SessionControlProps) {
  const { setCurrentTaskState } = useApp();
  const control = useTaskControl();
  const text = { ...DEFAULT_LABELS, ...labels };

  const state: TaskInfo['state'] | 'unknown' = taskState;
  const canResume = state === 'paused' || state === 'completed' || state === 'failed';
  const canPause = state === 'running';
  const canStop = state === 'running' || state === 'paused';

  const run = (op: 'resume' | 'pause' | 'kill') => {
    if (!taskId) return;
    control.mutate(
      { op, id: taskId },
      {
        onSuccess: () => {
          // Optimistic update: the server's event stream will eventually
          // catch up, so flip the store here to eliminate the round-trip
          // latency.
          if (op === 'resume') setCurrentTaskState('running');
          if (op === 'pause') setCurrentTaskState('paused');
          if (op === 'kill') setCurrentTaskState('cancelled');
          onAfterControl?.(op);
        },
      },
    );
  };

  return (
    <div className="session-control" data-testid="session-control" data-state={state}>
      <button
        type="button"
        data-testid="session-control-continue"
        data-op="resume"
        className="session-control-btn session-control-resume"
        disabled={!taskId || !canResume || control.isPending}
        onClick={() => run('resume')}
        aria-label={text.continue}
      >
        {text.continue}
      </button>
      <button
        type="button"
        data-testid="session-control-pause"
        data-op="pause"
        className="session-control-btn session-control-pause"
        disabled={!taskId || !canPause || control.isPending}
        onClick={() => run('pause')}
        aria-label={text.pause}
      >
        {text.pause}
      </button>
      <button
        type="button"
        data-testid="session-control-stop"
        data-op="kill"
        className="session-control-btn session-control-stop"
        disabled={!taskId || !canStop || control.isPending}
        onClick={() => run('kill')}
        aria-label={text.stop}
      >
        {text.stop}
      </button>
      {control.isError && (
        <span className="session-control-error" data-testid="session-control-error">
          {(control.error as Error).message}
        </span>
      )}
    </div>
  );
}
