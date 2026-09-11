import { useEffect, useState } from 'react';
import { useStore } from '../store';
import './Header.css';

interface HeaderProps {
  onSettingsClick: () => void;
  onToolsClick?: () => void;
  onSessionPickerClick?: () => void;
  /** Toggles the right-side Telemetry panel; redundant with Ctrl+Shift+E. `telemetryActive` drives the icon's pressed state. */
  onTelemetryClick?: () => void;
  telemetryActive?: boolean;
  /** Toggles the session details drawer; redundant with Ctrl+Shift+D. `detailsActive` drives the icon's pressed state. */
  onDetailsClick?: () => void;
  detailsActive?: boolean;
}

/** Top navigation bar.
 *
 *  The status indicator has three parts:
 *   - Thinking timer: shown during streaming until the first chunk arrives, ticking every 100ms;
 *   - Backpressure badge: appears when engine memory exceeds 88%; click to switch the concurrency profile to low;
 *   - Status pill: color-graded by preparing / streaming / stale / idle, readable at a glance. */
export function Header({ onSettingsClick, onToolsClick, onSessionPickerClick, onTelemetryClick, telemetryActive, onDetailsClick, detailsActive }: HeaderProps) {
  const {
    engineState, currentTaskId, tasks,
    isStreaming, isConnected, currentQuery,
    lastChunkTs, engineStats, requestConcurrencyProfile,
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

  // The streaming phase has four states: preparing / streaming / stale (no new chunk for 30s) / idle.
  const now = Date.now();
  const elapsedSinceChunk = lastChunkTs ? now - lastChunkTs : 0;
  const phase: 'preparing' | 'streaming' | 'stale' | null = (() => {
    if (!isStreaming) return null;
    if (!lastChunkTs) return 'preparing';
    if (elapsedSinceChunk > 30_000) return 'stale';
    return 'streaming';
  })();
  // The current run's elapsed time: prefer the query's startedAt, otherwise fall back to the timestamp of the first chunk; if neither is available, treat as 0.
  const runStartedAt = currentQuery?.startedAt ?? lastChunkTs;
  const runElapsedMs = runStartedAt ? now - runStartedAt : 0;

  const statusText = (() => {
    if (!isConnected) return { text: '● Disconnected', color: 'var(--error)' };
    if (phase === 'preparing') {
      return {
        text: `● Thinking ${(runElapsedMs / 1000).toFixed(1)}s`,
        color: 'var(--accent)',
      };
    }
    if (phase === 'streaming') {
      return {
        text: `● Streaming ${(runElapsedMs / 1000).toFixed(1)}s`,
        color: 'var(--accent)',
      };
    }
    if (phase === 'stale') {
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

  return (
    <header className="header">
      <div className="header-left">
        <span className="header-brand">✦ AetherCode</span>
        <span className="header-sep">›</span>
        <span className="header-task-name" title={currentQuery ? 'Current query' : activeLabel}>{activeLabel}</span>
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
        {/* Backpressure badge: appears when engine memory exceeds the threshold; click to switch the concurrency profile to low. The text also shows the current memory percentage so the cause is obvious at a glance. */}
        {engineStats?.backpressured && (
          <button
            className="header-backpressure"
            title="引擎内存超限，新 query 会被拒绝。点击调低并发配置。"
            onClick={() => void requestConcurrencyProfile('low')}
          >
            ⚠ Backpressure · {engineStats.memPct}%
          </button>
        )}
        {!engineStats?.backpressured && engineStats?.throttled && (
          <span
            className="header-throttle"
            title={`内存达到 ${engineStats.memPct}%,引擎进入限流模式`}
          >
            ⏳ 限流中 · {engineStats.memPct}%
          </span>
        )}
        <span className="header-status" style={{ color: statusText.color }}>{statusText.text}</span>
        {engineState?.model && <span className="header-meta" title="Active model">{engineState.model}</span>}
        {/* Project / cwd selector: always visible; on first launch the user can pick a project directly. The icon is fixed to avoid layout shift. */}
        <button
          className="header-icon-btn"
          title={cwd
              ? `当前项目: ${cwd} — 点击更换`
              : '点击选择项目文件夹（首次）'}
          onClick={() => void pickCwd()}
        >📂</button>
        {onToolsClick ? (
          <button className="header-icon-btn" title="Tools & Permission (Ctrl+T)" onClick={onToolsClick}>🔧</button>
        ) : null}
        {onSessionPickerClick ? (
          <button
            className="header-icon-btn"
            title="Session picker (Ctrl/Cmd+Shift+P)"
            onClick={onSessionPickerClick}
          >
            🗂
          </button>
        ) : null}
        {/* Telemetry panel toggle: sits next to the session details; `is-active` reflects the pressed state, with Ctrl+Shift+E as the parallel shortcut. */}
        {onTelemetryClick ? (
          <button
            className={`header-icon-btn${telemetryActive ? ' is-active' : ''}`}
            title="Telemetry panel (Ctrl/Cmd+Shift+E)"
            onClick={onTelemetryClick}
          >
            📊
          </button>
        ) : null}
        {/* Session details drawer toggle: shares the `is-active` pressed state with Telemetry; shortcut is Ctrl+Shift+D. */}
        {onDetailsClick ? (
          <button
            className={`header-icon-btn${detailsActive ? ' is-active' : ''}`}
            title="Session details (Ctrl/Cmd+Shift+D)"
            onClick={onDetailsClick}
            data-testid="header-details-btn"
          >
            📋
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
      </div>
    </header>
  );
}
