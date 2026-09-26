import { useEffect, useRef, useState } from 'react';
import { useStore } from '../store';
import './Header.css';

interface HeaderProps {
  onSettingsClick: () => void;
  onSessionPickerClick?: () => void;
  /** R347: Telemetry / Session details moved to SettingsPanel — Header no longer carries their buttons. The two props remain optional for callers that still want to wire the right-side drawers, but Header itself does not render them. */
  onTelemetryClick?: () => void;
  telemetryActive?: boolean;
  onDetailsClick?: () => void;
  detailsActive?: boolean;
  onToolsClick?: () => void;
}

/** Top navigation bar.
 *
 *  R347: The right-side icon cluster has been trimmed from 8 buttons to
 *  4 (`📂` project, `🗂` session picker, `☾/☀` theme, `⚙` settings)
 *  plus the status pill. `🔧` tools / `📊` telemetry / `📋` details
 *  used to live here as well; their hotkeys (Ctrl+T / Ctrl+Shift+E /
 *  Ctrl+Shift+D) are unchanged, the buttons just moved to the
 *  SettingsPanel first row. Backpressure / throttle indicators also
 *  moved to the StatusBar where they belong (see StatusBar.tsx).
 *
 *  The status indicator has three parts:
 *   - Thinking timer: shown during streaming until the first chunk arrives, ticking every 100ms;
 *   - Status pill: color-graded by preparing / streaming / stale / idle, readable at a glance. */
// R347: telemetry / details / tools props are kept in the type
// for legacy callers that still want to wire a right-side
// drawer; the Header itself does not render those buttons.
// Backpressure / throttle moved to StatusBar, so the
// `engineStats` and `requestConcurrencyProfile` are no longer
// referenced here either.
export function Header({ onSettingsClick, onSessionPickerClick }: HeaderProps) {
  const {
    engineState, currentTaskId, tasks,
    isStreaming, isConnected, currentQuery,
    lastChunkTs,
    pickCwd, cwd,
    // Theme state; setTheme() mirrors the value onto <html data-theme="...">, with the preference persisted to prefs.theme.
    theme, setTheme,
  } = useStore();
  const currentTask = tasks.find((t) => t.id === currentTaskId);
  const activeSubagent = currentTask ?? tasks.find((t) => t.status === 'running');
  // The current query takes precedence over the Subagent as the "active" indicator; the two aren't mutually exclusive (a Subagent can run inside a query), and the query is more prominent in the header.
  const active = currentQuery ?? activeSubagent;

  // Thinking timer: during streaming, trigger a re-render every 100ms to update the elapsed seconds. The hook is called unconditionally to satisfy React's rules of hooks.
  const [, setTick] = useState(0);
  useEffect(() => {
    if (!isStreaming) return;
    const id = setInterval(() => setTick((t) => (t + 1) % 1000000), 100);
    return () => clearInterval(id);
  }, [isStreaming]);

  // The streaming phase has four states: preparing / streaming / stale (no new chunk for 60s) / idle.
  // R349: stale threshold raised from 30s → 60s. The user
  // reported the 30s default was triggering during legitimate
  // 60-120s LLM thinking phases — every long thought read as
  // "stalled", which destroyed trust. 60s is the new floor and
  // still trips on actual stalls within a minute.
  //
  // R350: renamed `phase` → `streamingPhase` to avoid the
  // name collision with the new `fadePhase` variable
  // introduced by the session-fade transition (UX P1-3).
  const STALE_THRESHOLD_MS = 60_000;
  const now = Date.now();
  const elapsedSinceChunk = lastChunkTs ? now - lastChunkTs : 0;
  const streamingPhase: 'preparing' | 'streaming' | 'stale' | null = (() => {
    if (!isStreaming) return null;
    if (!lastChunkTs) return 'preparing';
    if (elapsedSinceChunk > STALE_THRESHOLD_MS) return 'stale';
    return 'streaming';
  })();
  // The current run's elapsed time: prefer the query's startedAt, otherwise fall back to the timestamp of the first chunk; if neither is available, treat as 0.
  const runStartedAt = currentQuery?.startedAt ?? lastChunkTs;
  const runElapsedMs = runStartedAt ? now - runStartedAt : 0;

  const statusText = (() => {
    if (!isConnected) return { text: '● Disconnected', color: 'var(--error)' };
    if (streamingPhase === 'preparing') {
      return {
        text: `● Thinking ${(runElapsedMs / 1000).toFixed(1)}s`,
        color: 'var(--accent)',
      };
    }
    if (streamingPhase === 'streaming') {
      return {
        text: `● Streaming ${(runElapsedMs / 1000).toFixed(1)}s`,
        color: 'var(--accent)',
      };
    }
    if (streamingPhase === 'stale') {
      return {
        text: `● Stalled ${Math.floor(elapsedSinceChunk / 1000)}s`,
        color: 'var(--warning)',
      };
    }
    if (active) return { text: '● Running', color: 'var(--accent)' };
    return { text: '● Idle', color: 'var(--success)' };
  })();

  // Short label for the active task: the user query's prompt, or the Subagent's task description.
  const activeLabel = currentQuery
    ? currentQuery.prompt
    : (activeSubagent?.description ?? 'No active task');
  const activeModel = engineState?.model ?? '';

  // R350 (UX P1-3): Header fade transition on session / model
  // change. The previous design yanked the label out and
  // inserted a new string with no animation, which read as a
  // glitch to power users switching sessions rapidly. We
  // keep the previous label visible for 300ms (fade-out + 2px
  // slide-up), then swap in the new label with a 200ms fade-in
  // + 2px slide-down. The transitions only fire when the
  // value actually changes.
  //
  // The transition is local state (displayLabel/phase) — we
  // never mutate the store. The hook always renders the
  // displayLabel; activeLabel is only the trigger.
  type FadePhase = 'idle' | 'leaving' | 'entering';
  const [displayLabel, setDisplayLabel] = useState(activeLabel);
  const [displayModel, setDisplayModel] = useState(activeModel);
  const [phase, setPhase] = useState<FadePhase>('idle');
  // Phase applied to the model pill; we keep a separate phase
  // because label and model can switch on different ticks
  // (e.g. user switches session, model takes 200ms more to
  // arrive from the daemon).
  const [modelPhase, setModelPhase] = useState<FadePhase>('idle');
  // Two separate timer refs so a fast label switch doesn't
  // stomp on a still-in-flight model transition (or vice
  // versa). Each effect owns its own queue.
  const labelTimers = useRef<ReturnType<typeof setTimeout>[]>([]);
  const modelTimers = useRef<ReturnType<typeof setTimeout>[]>([]);

  useEffect(() => {
    // Clear any in-flight timers so a fast double-switch
    // doesn't leave us stuck on a stale label.
    labelTimers.current.forEach(clearTimeout);
    labelTimers.current = [];
    if (activeLabel === displayLabel) return;
    setPhase('leaving');
    const t1 = setTimeout(() => {
      setDisplayLabel(activeLabel);
      setPhase('entering');
      const t2 = setTimeout(() => setPhase('idle'), 220);
      labelTimers.current.push(t2);
    }, 300);
    labelTimers.current.push(t1);
    return () => {
      labelTimers.current.forEach(clearTimeout);
      labelTimers.current = [];
    };
  }, [activeLabel, displayLabel]);

  useEffect(() => {
    modelTimers.current.forEach(clearTimeout);
    modelTimers.current = [];
    if (activeModel === displayModel) return;
    setModelPhase('leaving');
    const t1 = setTimeout(() => {
      setDisplayModel(activeModel);
      setModelPhase('entering');
      const t2 = setTimeout(() => setModelPhase('idle'), 220);
      modelTimers.current.push(t2);
    }, 300);
    modelTimers.current.push(t1);
    return () => {
      modelTimers.current.forEach(clearTimeout);
      modelTimers.current = [];
    };
  }, [activeModel, displayModel]);

  return (
    <header className="header">
      <div className="header-left">
        <span className="header-brand">✦ AetherCode</span>
        <span className="header-sep">›</span>
        <span
          className={`header-task-name header-fade header-fade-${phase}`}
          title={currentQuery ? 'Current query' : activeLabel}
          // `key` is the stable identity; React reuses the DOM
          // node and re-runs the CSS animation when the class
          // flips from `entering` to `idle`.
          data-fade={phase}
        >
          {displayLabel}
        </span>
        {currentQuery && (
          <span className="header-task-status task-status-running">running</span>
        )}
        {!currentQuery && activeSubagent && (
          <span className={`header-task-status task-status-${activeSubagent.status}`}>
            {activeSubagent.status}
          </span>
        )}
      </div>
      <div className="header-right">
        <span className="header-status" style={{ color: statusText.color }}>{statusText.text}</span>
        {/* R350: model pill now uses the same fade transition
            as the active label. Two independent phases so a
            fast session switch + late model update don't get
            clobbered into a single animation. */}
        {activeModel && (
          <span
            className={`header-meta header-fade header-fade-${modelPhase}`}
            title="Active model"
            data-fade={modelPhase}
          >
            {displayModel}
          </span>
        )}
        {/* Project / cwd selector: always visible; on first launch the user can pick a project directly. The icon is fixed to avoid layout shift. */}
        <button
          className="header-icon-btn"
          title={cwd
              ? `当前项目: ${cwd} — 点击更换`
              : '点击选择项目文件夹（首次）'}
          onClick={() => void pickCwd()}
        >📂</button>
        {onSessionPickerClick ? (
          <button
            className="header-icon-btn"
            title="Session picker (Ctrl/Cmd+Shift+P)"
            onClick={onSessionPickerClick}
          >
            🗂
          </button>
        ) : null}
        {/* Theme switch: the icon flips with the current theme, but the actual theming is driven by [data-theme="light"] CSS variable overrides. */}
        <button
          className="header-icon-btn header-theme-toggle"
          title={theme === 'light' ? 'Switch to dark theme' : 'Switch to light theme'}
          aria-label={theme === 'light' ? 'Switch to dark theme' : 'Switch to light theme'}
          onClick={() => setTheme(theme === 'light' ? 'dark' : 'light')}
        >
          {theme === 'light' ? '☀' : '☾'}
        </button>
        <button className="header-icon-btn" title="Settings" onClick={onSettingsClick}>⚙</button>
        {/* R347: The Telemetry / Session details / Tools icon buttons
         *  moved to SettingsPanel — the Header now only carries the
         *  five elements above. Their shortcuts (Ctrl+Shift+E /
         *  Ctrl+Shift+D / Ctrl+T) are unchanged. */}
      </div>
    </header>
  );
}
