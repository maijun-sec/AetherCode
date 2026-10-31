// Copyright (c) 2026 maijun
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

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
  /** R703 (UX-P2-1): jump-to-tab callbacks. The user
   *  used to be able to open the right-side panel via the
   *  now-removed telemetry / kanban / tools buttons; R347
   *  trimmed them all to a single Ctrl/Cmd+Shift+E hotkey.
   *  The new Kanban / Subagents buttons in the Header
   *  re-introduce the affordance: clicking the button
   *  flips the right panel open and jumps to the requested
   *  tab. `kanbanBadge` / `subagentsBadge` show live counts
   *  on the buttons (active task count / running subagent
   *  count) so the user can see "something is happening"
   *  without having to open the panel first. */
  onOpenKanban?: () => void;
  onOpenSubagents?: () => void;
  kanbanActive?: boolean;
  subagentsActive?: boolean;
  kanbanBadge?: number;
  subagentsBadge?: number;
}

/** Top navigation bar.
 *
 *  R347: The right-side icon cluster was trimmed from 8 buttons
 *  down to 4 (`📂` project, `🗂` session picker, `☾/☀` theme,
 *  `⚙` settings) plus the status pill. R703 re-introduces two
 *  jump-to-tab buttons (`🗒` Kanban, `🤖` Subagents) so the user
 *  has a one-click path to the panels they use most during an
 *  active session — the four-button baseline was too quiet for
 *  the "what's running right now" use case. The two new buttons
 *  carry live badges (active task count / running subagent count)
 *  so the user can see activity without opening the panel.
 *
 *  The status indicator has three parts:
 *   - Thinking timer: shown during streaming until the first chunk arrives, ticking every 100ms;
 *   - Status pill: color-graded by preparing / streaming / stale / idle, readable at a glance. */
export function Header({
  onSettingsClick,
  onSessionPickerClick,
  onOpenKanban,
  onOpenSubagents,
  kanbanActive,
  subagentsActive,
  kanbanBadge,
  subagentsBadge,
}: HeaderProps) {
  // R703 (UX-P2-3): notification-history popover state.
  // The popover is anchored to the 🔔 button and opens
  // on click. We use a small piece of local state rather
  // than the store because the popover's open / closed
  // state is purely UI — no other component needs to
  // react to it. The popover renders outside the button
  // (absolute-positioned) so it doesn't push the Header
  // layout when open.
  const notificationHistory = useStore((s) => s.notificationHistory);
  const clearNotificationHistory = useStore((s) => s.clearNotificationHistory);
  const [historyOpen, setHistoryOpen] = useState(false);
  // ref to the bell button so outside-click can close
  // the popover without closing when the user clicks
  // inside the popover body itself.
  const bellBtnRef = useRef<HTMLButtonElement | null>(null);
  const historyRef = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    if (!historyOpen) return;
    const onDown = (e: MouseEvent) => {
      const t = e.target as Node | null;
      if (!t) return;
      if (historyRef.current?.contains(t)) return;
      if (bellBtnRef.current?.contains(t)) return;
      setHistoryOpen(false);
    };
    window.addEventListener('mousedown', onDown);
    return () => window.removeEventListener('mousedown', onDown);
  }, [historyOpen]);
  // format a "Xm ago" relative timestamp for the
  // history entries. The store stores `dismissedAt`
  // so the relative-time is consistent regardless of
  // when the popover is opened.
  const formatAgo = (ts: number) => {
    const ms = Date.now() - ts;
    if (ms < 60_000) return `${Math.max(1, Math.round(ms / 1000))}s ago`;
    if (ms < 3_600_000) return `${Math.round(ms / 60_000)}m ago`;
    return `${Math.round(ms / 3_600_000)}h ago`;
  };
  const {
    engineState, currentTaskId, tasks,
    isStreaming, isConnected, currentQuery,
    lastChunkTs,
    // R390: the stable elapsed-timer anchor. Set once per
    // streaming burst; NOT refreshed per chunk.
    streamingStartedAt,
    // R735 (2026-10-20): read the failed-run fields so the
    // status pill can show "● Failed (same_error_hard_stop)"
    // instead of a generic green ● Idle when the engine dies
    // mid-run. lastFailedAt drives the fade-out window — the
    // pill auto-clears 60s after the failure so the user is
    // not nagged forever.
    lastFailedStopReason, lastFailedAt,
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
  // R735 (2026-10-20): failed-pill ticker. When the engine
  //  has populated lastFailedAt we want the "● Failed
  //  (reason) · Ns ago" pill to count up live, AND we want
  //  the pill to fall back to ● Idle after 60s. The streaming
  //  setTick above only runs while isStreaming, so it does
  //  not cover the post-failure idle period. We start a
  //  separate 1-second ticker for the 60-second window after
  //  the failure. Cleaning up the timer is critical: a leak
  //  here would leave a setInterval firing forever, ticking
  //  the header once a second indefinitely.
  useEffect(() => {
    if (!lastFailedAt) return;
    const FAILED_PILL_WINDOW_MS = 60_000;
    const ageMs = Date.now() - lastFailedAt;
    if (ageMs >= FAILED_PILL_WINDOW_MS) return;
    const id = setInterval(() => {
      setTick((t) => (t + 1) % 1000000);
      // Re-check: if we've crossed the 60s window, stop
      // ticking. Reading lastFailedAt from getState() avoids
      // re-creating the interval every time the timestamp
      // updates.
      const cur = useStore.getState().lastFailedAt;
      if (!cur || (Date.now() - cur) >= FAILED_PILL_WINDOW_MS) {
        clearInterval(id);
      }
    }, 1000);
    return () => clearInterval(id);
  }, [lastFailedAt]);

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
  // The current run's elapsed time.
  //
  // R390 (2026-10-29): this used to be
  //   `currentQuery?.startedAt ?? lastChunkTs`
  // and that fallback was the bug. `lastChunkTs` is refreshed on
  // EVERY chunk (it is the "has the stream gone quiet?" signal),
  // so using it as the elapsed-timer anchor made the display reset
  // to ~0 on each event: the pill bounced between "● Streaming 0.0s"
  // and "● Streaming 0.1s" (the 100ms ticker crossing the
  // tenth-second boundary between two chunks).
  //
  // The state that exposed it is real: `run_end` clears
  // `currentQuery` while a SUB-TASK keeps streaming, so
  // `currentQuery` is null while `isStreaming` is true — and the
  // `?? lastChunkTs` fallback is exactly what took over.
  //
  // The anchor now lives in the store as `streamingStartedAt`: it
  // is stamped once when streaming transitions false→true and is
  // never touched by individual chunks.
  //
  // `lastChunkTs` is still used below — for the "no response for
  // 60s" (stale) check and the sub-task-stuck detector. That is
  // what it measures; it just cannot measure elapsed run time.
  const runStartedAt = currentQuery?.startedAt ?? streamingStartedAt;
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
    // R735 (2026-10-20): sub-task stuck detector. The user
    //  reported a sub-task that had been "running" for 12
    //  minutes with only 1 message — the engine had hard-
    //  stopped on `same_error_hard_stop` mid-flight, the
    //  run_end was never emitted, and `currentQuery` was
    //  still set. The status pill showed "● Running" (or,
    //  after the user poked around, "● Idle") but the
    //  sub-task row in the Kanban kept ticking up. We
    //  catch this exact shape: `currentQuery` is set AND
    //  `runElapsedMs` is large (3 min+) AND `lastChunkTs`
    //  has not advanced recently (30s+). The "● Sub-task
    //  stuck" pill is the early-warning before the stale
    //  watchdog (90s default) force-ends.
    if (active && runElapsedMs > 180_000
        && (!lastChunkTs || elapsedSinceChunk > 30_000)) {
      const stuckSec = Math.floor(runElapsedMs / 1000);
      return {
        text: `● Sub-task stuck · ${stuckSec}s (no response in ${Math.floor(elapsedSinceChunk / 1000)}s)`,
        color: 'var(--warning)',
      };
    }
    if (active) return { text: '● Running', color: 'var(--accent)' };
    // R735 (2026-10-20): fourth status bucket. When the engine
    //  hard-stopped a run (same_error_hard_stop, max_turns, error,
    //  loop, awaiting_user_decision) the run_end handler
    //  populated lastFailedStopReason + lastFailedAt. The pill
    //  shows the reason for 60s so the user has a real
    //  diagnostic instead of an empty green ● Idle. After 60s
    //  the pill falls back to ● Idle — the failure is still
    //  visible via the ↻ retry button on the EndOfTaskPanel
    //  and via the error message in the chat scrollback.
    if (lastFailedStopReason && lastFailedAt
        && (now - lastFailedAt) < 60_000) {
      const ago = Math.max(0, Math.round((now - lastFailedAt) / 1000));
      return {
        text: `● Failed (${lastFailedStopReason}) · ${ago}s ago`,
        color: 'var(--error)',
      };
    }
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
        {/* R703 (UX-P2-1): jump-to-tab buttons. The user
            can open the right-side panel via Ctrl/Cmd+Shift+E
            but the hotkey was the only path after R347 trimmed
            the Header buttons. Re-introducing two targeted
            shortcuts here so the user has a visible "go look
            at tasks / subagents" affordance. Both buttons
            flip the panel open + jump to the requested tab;
            the `is-active` class lights up when the panel is
            already showing that tab. The badges render live
            counts so the user can see "something is happening"
            without having to open the panel first. */}
        {onOpenKanban ? (
          <button
            className={`header-icon-btn header-icon-btn-with-badge ${kanbanActive ? 'is-active' : ''}`}
            title="Open task board (Kanban) — Ctrl/Cmd+Shift+K"
            onClick={onOpenKanban}
            data-testid="header-kanban-btn"
          >
            🗒
            {(kanbanBadge ?? 0) > 0 ? (
              <span className="header-icon-badge" aria-label={`${kanbanBadge} active tasks`}>
                {kanbanBadge}
              </span>
            ) : null}
          </button>
        ) : null}
        {onOpenSubagents ? (
          <button
            className={`header-icon-btn header-icon-btn-with-badge ${subagentsActive ? 'is-active' : ''}`}
            title="Open subagents panel — Ctrl/Cmd+Shift+B"
            onClick={onOpenSubagents}
            data-testid="header-subagents-btn"
          >
            🤖
            {(subagentsBadge ?? 0) > 0 ? (
              <span className={`header-icon-badge header-icon-badge-pulse ${(subagentsBadge ?? 0) > 0 ? 'is-running' : ''}`} aria-label={`${subagentsBadge} running subagents`}>
                {subagentsBadge}
              </span>
            ) : null}
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
        {/* R703 (UX-P2-3): notification-history bell.
            Renders only when there's at least one
            dismissed / expired notification. The badge
            shows the count so the user can see at a
            glance "there are N things I might have
            missed". Clicking opens a popover listing
            the most-recent dismissed notifications
            with title + message + relative time +
            optional re-trigger of any action buttons.
            Outside-click closes the popover. The
            "清除历史" button at the bottom clears the
            whole history in one click. Hidden when
            history is empty so the button doesn't
            take up space when there's nothing to
            show. */}
        {notificationHistory.length > 0 ? (
          <div className="header-icon-history-wrap">
            <button
              ref={bellBtnRef}
              className={`header-icon-btn header-icon-btn-with-badge ${historyOpen ? 'is-active' : ''}`}
              title={`Notification history (${notificationHistory.length})`}
              onClick={() => setHistoryOpen((v) => !v)}
              data-testid="header-notif-history-btn"
            >
              🔔
              <span className="header-icon-badge" aria-label={`${notificationHistory.length} dismissed notifications`}>
                {notificationHistory.length}
              </span>
            </button>
            {historyOpen ? (
              <div ref={historyRef} className="header-notif-history" role="dialog" aria-label="Notification history">
                <div className="header-notif-history-header">
                  <span>Notification history</span>
                  <button
                    className="header-notif-history-clear"
                    onClick={() => clearNotificationHistory()}
                    title="Clear all dismissed notifications"
                  >
                    清除历史
                  </button>
                </div>
                <ul className="header-notif-history-list">
                  {notificationHistory.slice().reverse().map((n) => (
                    <li key={`${n.id}:${n.dismissedAt}`} className={`header-notif-history-item header-notif-history-item-${n.level}`}>
                      <div className="header-notif-history-item-row">
                        <span className="header-notif-history-item-title">{n.title}</span>
                        <span className="header-notif-history-item-ago">{formatAgo(n.dismissedAt)}</span>
                      </div>
                      {n.message ? <div className="header-notif-history-item-msg">{n.message}</div> : null}
                    </li>
                  ))}
                </ul>
              </div>
            ) : null}
          </div>
        ) : null}
        {/* R347: The Telemetry / Session details / Tools icon buttons
         *  moved to SettingsPanel — the Header now only carries the
         *  five elements above. Their shortcuts (Ctrl+Shift+E /
         *  Ctrl+Shift+D / Ctrl+T) are unchanged. */}
      </div>
    </header>
  );
}
