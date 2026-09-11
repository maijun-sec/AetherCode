import { useEffect, useState } from 'react';
import { useStore } from '../store';
import { ProgressBar } from './ProgressBar';
import { TokenUsage } from './TokenUsage';
import { ContextMeter } from './ContextMeter';
import { TraceList } from './TraceList';
import { PermissionList } from './PermissionList';
import { MemoryPanel } from './MemoryPanel';
import { PromptHistory } from './PromptHistory';
import { KanbanPanel } from './KanbanPanel';
import { AgentsPanel } from './AgentsPanel';
import { SubagentPanel } from './SubagentPanel';
import { AgentTasksPanel } from './AgentTasksPanel';
import './RightPanel.css';

interface RightPanelProps {
  /**
   * parent-supplied close callback. The RightPanel now
   * renders an explicit ✕ button in its header so the user can
   * dismiss the panel without reaching for the keyboard
   * shortcut. legacy the panel was always-mounted and
   * unclosable, which forced every screen to reserve a
   * permanent 320px column.
   */
  onClose?: () => void;
}

export function RightPanel({ onClose }: RightPanelProps = {}) {
  const { refreshMetrics, refreshTraces, refreshEngineStats, isConnected } = useStore();
  // tab toggle between the existing engine
  // telemetry (default), the Kanban board, the
  // Agents tab (prior round), and the Subagents tab
  // (prior round). The telemetry view is what an active
  // user wants during a live query; the Kanban is
  // for project planning; the Agents tab manages
  // the static agent registry; the Subagents tab
  // shows the live list of background subagent
  // jobs (a sibling of the StatusBar's indicator,
  // but with per-row Cancel / Insert actions).
  const [tab, setTab] = useState<'telemetry' | 'plan' | 'kanban' | 'agents' | 'subagents'>('telemetry');
  // badge on the Subagents tab so the user
  // notices a running job even when they're on
  // another tab. The badge shows the count of
  // RUNNING jobs; we read the subagent slice to
  // keep it in sync with the store.
  const subagentRunning = useStore((s) => s.subagent.running);

  useEffect(() => {
    if (!isConnected) return;
    refreshMetrics();
    refreshTraces();
    // also poll engine stats. The StatusBar reads
    // engineStats for the memory / throttle pills; piggy-
    // backing on the RightPanel's existing 5s timer avoids
    // a second setInterval.
    void refreshEngineStats();
    const t = setInterval(() => {
      refreshMetrics();
      refreshTraces();
      void refreshEngineStats();
    }, 5000);
    return () => clearInterval(t);
  }, [isConnected, refreshMetrics, refreshTraces, refreshEngineStats]);

  return (
    <aside className="right-panel">
      <div className="right-tabs">
        {/* explicit close button so the user can dismiss
            the telemetry panel without learning the Ctrl+Shift+E
            shortcut. The button sits at the right edge of the
            tab strip and is `pointer-events: auto` even though
            the tab row is `flex: 0 0 auto` (so the close button
            inherits the natural row height). */}
        <button
          className={`right-tab ${tab === 'telemetry' ? 'active' : ''}`}
          onClick={() => setTab('telemetry')}
        >Telemetry</button>
        <button
          className={`right-tab ${tab === 'plan' ? 'active' : ''}`}
          onClick={() => setTab('plan')}
        >Plan</button>
        <button
          className={`right-tab ${tab === 'kanban' ? 'active' : ''}`}
          onClick={() => setTab('kanban')}
        >Tasks</button>
        <button
          className={`right-tab ${tab === 'subagents' ? 'active' : ''}`}
          onClick={() => setTab('subagents')}
        >
          Subagents
          {/* live badge — the user is on another
              tab when a background subagent starts, the
              badge pulses so they can switch over and
              cancel / read the result. Empty when no
              job is running. */}
          {subagentRunning > 0 ? (
            <span className="right-tab-badge">{subagentRunning}</span>
          ) : null}
        </button>
        <button
          className={`right-tab ${tab === 'agents' ? 'active' : ''}`}
          onClick={() => setTab('agents')}
        >Agents</button>
        {onClose ? (
          <button
            className="right-tab right-tab-close"
            title="Close telemetry panel (Ctrl/Cmd+Shift+E)"
            onClick={onClose}
          >✕</button>
        ) : null}
      </div>
      {tab === 'telemetry' ? (
        <>
          <section className="right-section right-progress"><ProgressBar /></section>
          <section className="right-section right-context"><ContextMeter /></section>
          <section className="right-section right-tokens"><TokenUsage /></section>
          <section className="right-section right-traces"><TraceList /></section>
          <section className="right-section right-perms"><PermissionList /></section>
          <section className="right-section right-memory"><MemoryPanel /></section>
          {/* prompt history + templates. Sits at the very bottom
              because the user only interacts with it occasionally —
              the engine telemetry above is the primary right-panel
              focus during a live query. localStorage-backed, so the
              history survives a Tauri reload. */}
          <section className="right-section right-prompts"><PromptHistory /></section>
        </>
      ) : tab === 'kanban' ? (
        <section className="right-section right-kanban"><KanbanPanel /></section>
      ) : tab === 'plan' ? (
        // the "Plan" tab shows the live todo list the
        // model is currently working through. The store
        // captures `todo_write` tool calls verbatim; subtask
        // status is cross-referenced with the runtime
        // sub_task_start / sub_task_end events.
        <section className="right-section right-plan"><AgentTasksPanel /></section>
      ) : tab === 'subagents' ? (
        // live list of background subagent
        // jobs. The panel reads the same `subagent`
        // slice the StatusBar pill uses, so the
        // data is always in sync. Per-row actions
        // (Cancel / Insert) call the store + the
        // subagentCancel RPC.
        <section className="right-section right-subagents"><SubagentPanel /></section>
      ) : (
        // agents tab. The AgentsPanel
        // handles the list + the AgentEditor
        // modal internally; we just mount the
        // section.
        <section className="right-section right-agents"><AgentsPanel /></section>
      )}
    </aside>
  );
}
