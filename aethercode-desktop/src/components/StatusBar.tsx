import { useState } from 'react';
import { useStore, permissionModeLabel } from '../store';
import './StatusBar.css';

function shortenPath(p: string, max: number = 30): string {
  if (p.length <= max) return p;
  return '…' + p.slice(p.length - (max - 1));
}

/** R347: export the current session's chat timeline as Markdown
 *  and copy it to the clipboard. Power-user follow-up to the
 *  TUI's `/export` slash command — here the user is already in
 *  the GUI and `navigator.clipboard.writeText` is the lowest-
 *  friction path. We render a YAML frontmatter (session / model
 *  / cwd / turns / exported_at) so the pasted document carries
 *  enough context to render in a GitHub PR or Obsidian without
 *  losing the session metadata. The success/failure feedback
 *  is rendered inline (passed in via `onResult`) so we don't
 *  need to wire into a global toast system. */
async function exportSessionToClipboard(
  sessionId: string,
  model: string,
  cwd: string,
  onResult: (ok: boolean, msg: string) => void,
): Promise<void> {
  const { messages } = useStore.getState();
  if (!messages || messages.length === 0) {
    onResult(false, 'no messages to export');
    return;
  }
  const fm = [
    '---',
    `session: ${sessionId}`,
    `model: ${model}`,
    `cwd: ${cwd || '(none)'}`,
    `turns: ${messages.length}`,
    `exported_at: ${new Date().toISOString()}`,
    '---',
    '',
  ].join('\n');
  const body = messages
    .map((m: { role: string; content: string; ts?: number }) => {
      const ts = m.ts ? new Date(m.ts).toLocaleString() : '';
      const role = m.role[0]?.toUpperCase() + m.role.slice(1);
      return `## ${role}${ts ? '  ·  ' + ts : ''}\n\n${m.content}\n`;
    })
    .join('\n');
  const md = `# AetherCode session ${sessionId.slice(0, 8)}\n\n${fm}${body}`;
  // Try the modern Clipboard API first (works inside the
  // Tauri webview when the user has granted clipboard
  // permission). Fall back to the legacy `document.execCommand`
  // path so the button still works in restricted contexts
  // (Tauri sandbox, older webview, etc.).
  const tryCopy = async (): Promise<boolean> => {
    if (navigator.clipboard && typeof navigator.clipboard.writeText === 'function') {
      try {
        await navigator.clipboard.writeText(md);
        return true;
      } catch { /* fall through */ }
    }
    const ta = document.createElement('textarea');
    ta.value = md;
    ta.style.position = 'fixed';
    ta.style.left = '-9999px';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    const ok = document.execCommand('copy');
    document.body.removeChild(ta);
    return ok;
  };
  try {
    const ok = await tryCopy();
    if (ok) onResult(true, `copied ${messages.length} messages`);
    else onResult(false, 'copy failed (clipboard permission denied)');
  } catch (e) {
    onResult(false, `copy failed: ${(e as Error).message}`);
  }
}

/**
 * StatusBar now surfaces live engine health. The
 * right side shows a memory badge ({@code 42 MB / 256 MB · 16%})
 * that turns amber when the engine is throttled (memory ≥ 75%)
 * and red when backpressured (memory ≥ 88%). Clicking the
 * memory badge opens the Settings panel; the Settings panel
 * has a "Concurrency profile" dropdown that calls
 * {@code setConcurrencyProfile}.
 */
export function StatusBar() {
  const {
    connectionState, daemonInfo, engineState, tools, tasks,
    isStreaming, preWarm, engineStats, subagent, skipStats,
    steps,
    // pull `loopWarn` so the StatusBar can show a
    // persistent "⚠ loop detected" badge when the engine
    // is in an active warn state. The badge is a clickable
    // affordance — clicking it focuses the LoopGuardBanner
    // (the banner is mounted by MessageList; we just scroll
    // it into view via the same anchor mechanism the rest
    // of the App uses for "jump to a specific UI piece").
    // The badge disappears the moment the user clicks
    // Continue / Stop on the banner (the store clears loopWarn
    // and the conditional re-renders to nothing).
    loopWarn,
    // auto-approved counter + toggle. The
    // badge shows the cumulative count when > 0;
    // clicking it calls setAutoApproveLowRisk(!auto)
    // so the user can flip the flag without
    // opening Settings. The tooltip carries the
    // recent list so a power user can see "what
    // did the daemon auto-allow in the last
    // minute" without opening the diagnostic
    // panel.
    autoApproveLowRisk,
    // medium+high-risk toggle. Same
    // shape as the low-risk toggle but the
    // badge uses a different prefix
    // (▲ vs ✓) so the two states are
    // visually distinct — a power user
    // toggling on "headless mode" wants to
    // see at a glance that elevated calls
    // will be auto-approved.
    autoApproveMediumHigh,
    autoApprovedCount,
    // cumulative count of medium /
    // high-risk auto-approvals. Tracked
    // separately from autoApprovedCount so
    // a StatusBar badge can colour-code the
    // two ("✓ auto-allow: 12" vs
    // "▲ auto-allow high: 3").
    autoApprovedElevatedCount,
    recentAutoApproved,
    setAutoApproveLowRisk,
    setAutoApproveMediumHigh,
    // R347: export-session button. Pulls messages + cwd +
    //  sessionId so the 📤 button can render a Markdown
    //  copy of the current session straight to the clipboard.
    messages,
    currentSessionId,
    cwd,
    // R349 (PM P0-2): cost / budget bar. Pulled from
    //  the store so the budget pill renders next to the
    //  memory / version badges in the right cluster.
    //  budgetUsd stays null when the user hasn't set a
    //  cap, in which case the bar hides entirely.
    budgetUsd,
    cumulativeCostUsd,
    setBudget,
  } = useStore();
  // R349 (PM P0-2): step counter + ETA. We compute:
  //   - completed steps = steps.filter(s => s.done).length
  //   - running step = steps.find(s => !s.done && s.startedAt)
  //   - ETA ≥ X min = max(running step so-far / 60_000, 1)
  //     We deliberately use "≥ X min" rather than a precise
  //     estimate — LLM step durations have huge variance and
  //     a wrong "exactly 2 min" reads as broken when it
  //     misses. "≥ 1 min" is honest.
  const completedSteps = steps.filter((s) => s.done).length;
  const runningStep = steps.find((s) => !s.done && s.startedAt);
  const runningStepElapsedMs = runningStep ? Date.now() - runningStep.startedAt : 0;
  const etaMin = runningStepElapsedMs > 0 ? Math.max(1, Math.ceil(runningStepElapsedMs / 60_000)) : 0;
  // compact mode. legacy the status bar always
  // rendered 14+ badges (state / port / model / permission /
  // skip / suggestion / skip-stats / tools / running /
  // streaming / loop-warn / auto-approve / auto-approve+ /
  // pre-warm / subagent / memory / in-flight / version).
  // On a 1280px window the bar became a wall of micro-text
  // the user couldn't scan. We now default to a 4-badge
  // "at-a-glance" view (connection + model + streaming +
  // memory + loop-warn + auto-approve) and hide the rest
  // behind a "···" toggle so power users can still reach
  // the diagnostic badges (skip counter, suggestion,
  // pre-warm, subagent, version, etc.) on demand. The
  // .status-bar-compact CSS class on the <footer> hides
  // every .status-item that doesn't carry the
  // .status-priority class. The flag is per-session
  // (component-local state), not persisted — a fresh
  // window always starts in compact mode.
  const [compact, setCompact] = useState(true);
  // R347: ephemeral export status. Cleared after 2.4s. We
  //  show this inline (no global toast system) so the
  //  "copy session" button gets immediate feedback without
  //  competing with subagent toasts or permission banners.
  const [exportToast, setExportToast] = useState<{ ok: boolean; msg: string } | null>(null);

  const stateLabel = (() => {
    switch (connectionState) {
      case 'connected': return { text: '● Connected', color: 'var(--success)' };
      case 'connecting': return { text: '○ Connecting…', color: 'var(--warning)' };
      case 'reconnecting': return { text: '○ Reconnecting…', color: 'var(--warning)' };
      case 'closed': return { text: '● Disconnected', color: 'var(--error)' };
      case 'error': return { text: '● Error', color: 'var(--error)' };
      default: return { text: '○ Idle', color: 'var(--text-dim)' };
    }
  })();

  const running = tasks.filter((t) => t.status === 'running').length;

  // subagent live indicator. Derive a colour tone
  // from the most recent event's status. We intentionally
  // do NOT clear the indicator when a terminal toast
  // auto-dismisses — the status bar can keep showing
  // "[sag-1] done 1.4s" so the user can confirm the work
  // finished even after the toast is gone.
  const subagentTone = (() => {
    if (subagent.running > 0) return 'running';
    const last = subagent.lastTerminal;
    if (!last) return 'idle';
    if (last.status === 'COMPLETED') return 'success';
    if (last.status === 'FAILED') return 'error';
    return 'muted';
  })();

  // derive the memory badge state. Three tiers:
  //   ok         → green tint, no animation
  //   throttled  → amber, "限流中 (throttled)"
  //   backpressure → red, "Backpressure"
  const memTier = engineStats?.backpressured
    ? 'backpressure'
    : engineStats?.throttled
      ? 'throttled'
      : 'ok';

  return (
    <footer className={`status-bar${compact ? ' status-bar-compact' : ''}`}>
      <div className="status-left">
        <span className="status-item" data-priority="true" style={{ color: stateLabel.color }}>
          {stateLabel.text}
        </span>
        {daemonInfo && (
          <span className="status-item" title={daemonInfo.wsUrl}>
            port {daemonInfo.port}
          </span>
        )}
        {engineState && (
          <span className="status-item" data-priority="true" title="Active model">
            {engineState.model}
          </span>
        )}
        {engineState && (
          // render the permission mode via
          // permissionModeLabel so the 3-tier Chinese
          // labels (主动询问 / ask, 智能授权 / smart, 始终授权 / bypass)
          // appear in the bar. The tooltip carries
          // both the user-friendly label AND the raw
          // canonical enum so a power user can hover
          // and see the exact daemon state. Advanced
          // modes (ACCEPT_TASK, PLAN, AUTO_READ_ONLY)
          // fall through to the raw enum name so the
          // power user knows they're not in one of the
          // 3 tiers.
          <span
            className="status-item"
            data-priority="true"
            title={`Permission mode: ${permissionModeLabel(engineState.permissionMode)} (${engineState.permissionMode})`}
          >
            {permissionModeLabel(engineState.permissionMode)}
          </span>
        )}
        {/* skip-confirmation counter. Renders only when the
            engine is in an active skip state. The badge uses
            the warning colour to draw attention without being
            alarming — the user explicitly opted in. */}
        {engineState && (engineState.skipConfirmationRemaining ?? 0) > 0 && (
          <span
            className={(() => {
              // "low!" badge when the counter is at
              // or below the waterline, or a recent
              // NOTIFY_SKIP_LOW event fired. The recent
              // event takes precedence — even if the
              // counter has since dropped, we want the
              // warning to stay visible for ~5s.
              const recent = engineState.lastSkipLow
                && Date.now() - engineState.lastSkipLow.atMs < 5_000;
              const atWaterline = (engineState.skipConfirmationRemaining ?? 0)
                <= (engineState.skipLowWaterline ?? 5)
                && (engineState.skipLowWaterline ?? 5) > 0;
              return `status-item ${recent || atWaterline ? 'status-skip-low' : 'status-skip'}`;
            })()}
            title={`Skip confirmation: next ${engineState.skipConfirmationRemaining} tool call${engineState.skipConfirmationRemaining === 1 ? '' : 's'} will be auto-allowed`}
          >
            ⏩ skip {engineState.skipConfirmationRemaining}
            {(() => {
              const recent = engineState.lastSkipLow
                && Date.now() - engineState.lastSkipLow.atMs < 5_000;
              const atWaterline = (engineState.skipConfirmationRemaining ?? 0)
                <= (engineState.skipLowWaterline ?? 5)
                && (engineState.skipLowWaterline ?? 5) > 0;
              return (recent || atWaterline) ? ' (low!)' : '';
            })()}
          </span>
        )}
        {/* permission-mode suggestion. Renders when
            the engine has a heuristic suggestion AND the
            suggested mode differs from the user's current
            mode. The user can accept with the /mode
            command in the TUI or by clicking the badge
            in the desktop. */}
        {engineState && engineState.permissionModeSuggestion
          && engineState.permissionModeSuggestion.mode !== engineState.permissionMode ? (
          <span
            className="status-item status-permission-suggestion"
            title={`Suggested by the engine's heuristic: ${engineState.permissionModeSuggestion.mode} (${engineState.permissionModeSuggestion.reasons.join('; ')})`}
          >
            💡 suggested: {engineState.permissionModeSuggestion.mode}
          </span>
        ) : null}
        {/* skip-confirmation adoption. Renders only when
            the user has armed at least one skip. Shows the
            raw "consumed / prompts" count so the user can see
            the rate at a glance. */}
        {skipStats && skipStats.armed > 0 && skipStats.prompts > 0 && (
          <span
            className="status-item status-skip-stats"
            title={`Skip adoption: ${skipStats.consumed} of ${skipStats.prompts} prompts used a skip-round (${Math.round(skipStats.adoption * 100)}%)`}
          >
            skip: {skipStats.consumed}/{skipStats.prompts}
          </span>
        )}
        {tools.length > 0 && (
          <span className="status-item" title="Tool count">
            {tools.length} tools
          </span>
        )}
        {running > 0 && (
          <span className="status-item" title="Running tasks">
            {running} running
          </span>
        )}
        {isStreaming && (
          <span className="status-item streaming" data-priority="true" title="Streaming response">
            ● streaming
          </span>
        )}
        {/* loop detector badge. Renders only when
            the engine has an active loop warn (i.e. the
            LoopGuardBanner is up). The badge is clickable
            so the user can jump to the banner without
            hunting for it. We scroll the banner into view
            via a `loop-guard-banner` element id — the
            banner is a single element rendered by
            MessageList, so document.getElementById is
            reliable even with React's reconciliation.
            The tier number is in the text so the user
            sees "warn 1/2" at a glance; the colour is
            amber for tier 1, red for tier 2 (tier 2 means
            the engine is about to stop the run, so the
            colour change is intentional). */}
        {loopWarn && (
          <button
            data-priority="true"
            className={`status-item status-loop-warn status-loop-tier-${loopWarn.tier}`}
            title="Loop detected — click to jump to LoopGuardBanner (Ctrl+L to continue)"
            onClick={() => {
              const el = document.getElementById('loop-guard-banner');
              if (el) el.scrollIntoView({ behavior: 'smooth', block: 'center' });
            }}
          >
            ⚠ loop {loopWarn.tier}/2
          </button>
        )}
        {/* auto-approved counter + toggle. The
            badge is always visible (even at 0) so the
            user can see whether the daemon is in
            "auto-allow low risk" mode (✓) or
            "prompt for everything" (✗). Clicking
            toggles the flag — the daemon is the
            source of truth, so the optimistic
            update only happens after the RPC
            succeeds. The tooltip carries the
            recent-10 list (tool + relative time)
            so a user who wants to see "what was
            just auto-allowed" can hover. The
            badge is muted (text-dim colour) when
            0, so the bar doesn't visually compete
            with the loop / throttling badges. */}
        <button
          data-priority="true"
          className={`status-item status-auto-approve ${autoApproveLowRisk ? '' : 'is-disabled'}`}
          title={
            recentAutoApproved.length > 0
              ? `Auto-allow low risk: ${autoApproveLowRisk ? 'ON' : 'OFF'}\n` +
                `cumulative: ${autoApprovedCount}\n` +
                `recent: ${recentAutoApproved.slice(0, 5).map((r) => r.tool).join(', ')}`
              : `Auto-allow low risk: ${autoApproveLowRisk ? 'ON' : 'OFF'} (click to toggle)`
          }
          onClick={() => void setAutoApproveLowRisk(!autoApproveLowRisk)}
        >
          {autoApproveLowRisk ? '✓' : '✗'} auto-allow
          {autoApprovedCount > 0 && (
            <span className="status-auto-approve-count"> {autoApprovedCount}</span>
          )}
        </button>
        {/* elevated auto-approve badge.
            Separate button so the user can flip
            the medium+high-risk toggle without
            touching the low-risk one. The
            prefix ▲ is intentional — a
            triangle is the conventional
            "warning" glyph, and toggling this
            on means bash / file_write will
            run without asking. Critical risk
            (rm -rf, sudo) is NEVER
            auto-approved regardless of this
            flag. Hidden when the count is 0
            AND the flag is off (a fresh
            daemon shouldn't show a
            confusing ⚠ triangle). */}
        {(autoApproveMediumHigh || autoApprovedElevatedCount > 0) && (
          <button
            className={`status-item status-auto-approve-elevated ${autoApproveMediumHigh ? '' : 'is-disabled'}`}
            title={
              `Auto-allow medium+high risk: ${autoApproveMediumHigh ? 'ON' : 'OFF'}\n` +
              `cumulative (elevated): ${autoApprovedElevatedCount}\n` +
              `Critical risk (rm -rf, sudo) is NEVER auto-approved.`
            }
            onClick={() => void setAutoApproveMediumHigh(!autoApproveMediumHigh)}
          >
            {autoApproveMediumHigh ? '▲' : '▽'} auto-allow+
            {autoApprovedElevatedCount > 0 && (
              <span className="status-auto-approve-count"> {autoApprovedElevatedCount}</span>
            )}
          </button>
        )}
        {/* R82+ Issue 3: a pre-warmed sibling daemon means a
            cwd switch into its directory is sub-second. Show
            its target in the status bar so the user knows the
            hot spare is armed. */}
        {preWarm && (
          <span
            className="status-item prewarm"
            title={`Pre-warmed daemon on port ${preWarm.info.port} for ${preWarm.cwd}. Switching to this directory is sub-second.`}
          >
            ⚡ pre-warm {shortenPath(preWarm.cwd)}
          </span>
        )}
        {/* live subagent status. Mirrors the TUI
            StatusBar's inline indicator — shows the most
            recent subagent job's transition (running /
            done / failed / cancelled) with a tone that
            matches the event severity. Hidden when no
            subagent has been seen this session. */}
        {subagent.status && (
          <span
            data-priority="true"
            className={`status-item subagent subagent-${subagentTone}`}
            title={subagent.status}
          >
            ⏵ {subagent.status}
            {subagent.running > 1 ? (
              <span className="subagent-count"> ({subagent.running} running)</span>
            ) : null}
          </span>
        )}
      </div>
      <div className="status-right">
        {/* R347: export button. Copies the current session's
         *  timeline as Markdown to the clipboard (the
         *  user can paste into a GitHub PR / Slack / Obsidian
         *  without leaving the chat). The TUI counterpart
         *  `/export <path>` writes to disk; here we go
         *  straight to clipboard because the user is
         *  already in front of the chat and `navigator.clipboard`
         *  is the lowest-friction path. A Tauri-side
         *  "save to file" dialog is the P2 follow-up. */}
        {currentSessionId && messages.length > 0 && (
          <button
            className="status-item status-export"
            title={`Copy this session as Markdown (${messages.length} messages)`}
            onClick={() => {
              void exportSessionToClipboard(
                currentSessionId,
                engineState?.model ?? 'unknown',
                cwd ?? '',
                (ok, msg) => {
                  setExportToast({ ok, msg });
                  setTimeout(() => setExportToast((cur) => (cur === exportToast ? null : cur)), 2400);
                },
              );
            }}
          >
            📤 copy session
          </button>
        )}
        {exportToast && (
          <span
            className={`status-item status-export-toast ${exportToast.ok ? 'is-ok' : 'is-err'}`}
            data-testid="status-export-toast"
          >
            {exportToast.ok ? '✓' : '✗'} {exportToast.msg}
          </span>
        )}
        {/* engine health badge. Surfaces memory,
         *  concurrency profile, in-flight counts. Color
         *  follows the memTier: green / amber / red. */}
        {engineStats && (
          <span
            data-priority="true"
            className={`status-item status-mem status-mem-${memTier}`}
            title={
              memTier === 'backpressure'
                ? `Backpressure: memory at ${engineStats.memPct}%. New queries are rejected.`
                : memTier === 'throttled'
                  ? `Throttled: memory at ${engineStats.memPct}%. New queries run in low-priority mode.`
                  : `Memory ${engineStats.memUsedMb} / ${engineStats.memMaxMb} MB (${engineStats.memPct}%). Profile: ${engineStats.concurrencyProfile}.`
            }
          >
            {memTier === 'backpressure' ? '⚠ ' : memTier === 'throttled' ? '⏳ ' : ''}
            {engineStats.memUsedMb} / {engineStats.memMaxMb} MB · {engineStats.memPct}%
            {engineStats.concurrencyProfile && engineStats.concurrencyProfile !== 'normal' && (
              <span className="status-mem-profile"> · {engineStats.concurrencyProfile}</span>
            )}
          </span>
        )}
        {/* Concurrency in-flight counts — only show when something
         *  is in flight, otherwise the bar stays clean. */}
        {engineStats && (engineStats.queriesInFlight > 0 || engineStats.branchesInFlight > 0) && (
          <span
            data-priority="true"
            className="status-item status-inflight"
            title={`Queries in flight: ${engineStats.queriesInFlight}/${engineStats.maxConcurrentQueries}. Workflow branches: ${engineStats.branchesInFlight}/${engineStats.maxConcurrentBranches}.`}
          >
            {engineStats.queriesInFlight}/{engineStats.maxConcurrentQueries} q
            {engineStats.branchesInFlight > 0 && ` · ${engineStats.branchesInFlight}/${engineStats.maxConcurrentBranches} ↯`}
          </span>
        )}
        {/* R349 (PM P0-2): cost / budget bar. The user sets a
         *  cap via /budget <usd> in the TUI or the Settings
         *  panel here; we mirror it in `budgetUsd`. The bar
         *  shows `cumulativeCostUsd / budgetUsd` with a
         *  8-step fill glyph (matches the ctx-fill glyph
         *  language). Soft cap: when cumulative exceeds
         *  budget, the bar turns red and a toast fires
         *  once, but queries are NOT blocked — the user
         *  can keep working and decide whether to stop.
         *  Hidden entirely when budgetUsd is null. */}
        {budgetUsd != null && budgetUsd > 0 && (
          <button
            className={`status-item status-budget ${cumulativeCostUsd > budgetUsd ? 'is-over' : ''}`}
            title={`Cost: $${cumulativeCostUsd.toFixed(3)} / $${budgetUsd.toFixed(2)} budget. Click to edit.`}
            onClick={() => {
              // R349.4 (PM P0-2): prompt for a new budget.
              // A simple window.prompt keeps this R349
              // self-contained — the Settings panel gets
              // a proper slider later.
              const next = window.prompt('Set cost budget (USD). 0 to disable.', String(budgetUsd));
              if (next == null) return;
              const n = Number(next);
              if (!Number.isFinite(n) || n <= 0) setBudget(null);
              else setBudget(n);
            }}
            data-testid="status-budget-bar"
          >
            ${cumulativeCostUsd.toFixed(3)} / ${budgetUsd.toFixed(2)}
          </button>
        )}
        {/* R349 (PM P0-2): step counter + ETA. Only renders
         *  during an active streaming run. The user wanted
         *  `step 3/8 · ETA ≥ 2 min`; we don't know the total
         *  step count yet (daemon hasn't shipped it), so
         *  we render `step N · ETA ≥ X min` as a partial
         *  signal — N grows as the model progresses, X is
         *  the wall-clock time spent on the current step
         *  rounded up. Renders only when the current step
         *  is older than 4s — earlier than that the ETA is
         *  noisy and doesn't help the user. */}
        {isStreaming && completedSteps > 0 && etaMin > 0 && (
          <span
            className="status-item status-step-eta"
            title={`Step ${completedSteps} done. Current step ${etaMin}min and counting.`}
            data-testid="status-step-eta"
          >
            step {completedSteps} · ≥ {etaMin}m
          </span>
        )}
        <span className="status-item">AetherCode Desktop R107</span>
        {/* compact-mode toggle. Sits at the very right
            of the bar so it doesn't compete with the existing
            "AetherCode Desktop R107" version chip. Clicking
            flips `compact` and adds/removes the
            `.status-bar-compact` class on the footer (see
            StatusBar.css). The label is "···" so the bar
            stays visually tight; the title attribute spells
            out the action for screen readers and on hover. */}
        <button
          className="status-item status-toggle-compact"
          title={compact ? '显示所有 status badge' : '收起非关键 badge，只显示连接/模型/内存/告警'}
          onClick={() => setCompact((v) => !v)}
        >
          {compact ? '···' : '−'}
        </button>
      </div>
    </footer>
  );
}
