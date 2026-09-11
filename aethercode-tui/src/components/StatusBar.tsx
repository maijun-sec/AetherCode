/**
 * bottom status bar.
 *
 * Shows the current status (connecting / ready / streaming /
 * running-tool), the in-flight tool queue, and token / cost
 * counters when available. The right side shows the jar name
 * for transparency about which daemon is running.
 *
 * prior round update: the "ready" indicator is now color-coded by
 * the most recent run's stop reason. A normal end is green,
 * a loop-detected stop is red (so the user can never confuse
 * "model stopped because of a loop" with "ready for input"),
 * max-iterations is yellow, and an error is red. While
 * submitting, the bar is yellow with a spinner.
 *
 * the bar is no longer rendered inside a Box with
 * a single-line border. OpenCode's TUI uses a thin
 * horizontal rule (──) above the bar instead — much
 * less visual weight than a full box border, and the
 * status colour now flows through a left-edge accent
 * character. This is the change the user explicitly
 * asked for ("tui 需要参考 opencode, 不输于 opencode"):
 * the box border was the most "old terminal" thing
 * about the design and it disappeared in R167.
 */

import React from "react";
import { Box, Text, useInput } from "ink";
import Spinner from "ink-spinner";
import type { State } from "../state.js";
import { t, formatCost, formatTokens } from "../theme.js";
import { ProgressBar } from "./ProgressBar.js";
import { TokenChart } from "./TokenChart.js";
import { ContextMeter, type ContextInfo } from "./ContextMeter.js";
// T-6-16: SessionControl + re-attach banner. The
// StatusBar gains an optional control row above the
// existing status line; when the parent doesn't wire
// the callbacks, the row is hidden (preserves every
// existing test).
import { SessionControl, type SessionState } from "./session/SessionControl.js";
import { isStale, staleLabel } from "./session/SessionDetailsPanel.js";

interface Props {
  state: State;
  /** T-6-16: optional SessionControl row. When any of
   *  the three callbacks is supplied, the bar renders
   *  the Continue / Pause / Stop buttons above the
   *  status line. The host wires the three callbacks
   *  to `task/resume`, `task/pause`, `task/kill`. */
  onContinue?: () => void;
  onPause?: () => void;
  onStop?: () => void;
  /** T-6-16: when set, the bar renders a re-attach
   *  banner for the given session id (shown when the
   *  session is older than the 30 min stale threshold). */
  staleSession?: { id: string; lastActiveAt: number } | null;
  onReattach?: (id: string) => void;
}

/**
 * stop reason labels. The OLD design used the word "stopped"
 * for every non-end_turn reason, which read as "the model crashed"
 * and made the user type "Continue" / "continue" to issue a new
 * prompt. That's confusing — the model finished its turn
 * normally, the daemon is just waiting for the next user
 * input. The new wording is:
 *   - end_turn*            → "✓ ready · your turn" (the common case)
 *   - anything "abnormal"  → "stopped: <kind>" (red, draws attention)
 * The user can always tell what's going on from the colour and
 * the label without having to read the docs.
 */
const STOP_LABEL: Record<string, string> = {
  end_turn:           "✓ ready",
  end_turn_and_tool:  "✓ ready",
  tool_use_end:       "✓ ready",
  stop:               "✓ ready",
  tool_calls:         "✓ ready",
  max_tokens:         "✓ ready",
  length:             "✓ ready",
  loop_detected:      "stopped: loop",
  same_fingerprint:   "stopped: loop",
  same_error:         "stopped: loop",
  long_output:        "stopped: long output",
  high_risk_repeat:   "stopped: high-risk repeat",
  user_interrupt:     "stopped: user interrupt",
  max_iterations:     "stopped: max turns",
  max_turns:          "stopped: max turns",
  error:              "stopped: error",
  empty_input:        "empty input",
};

export const StatusBar: React.FC<Props> = ({
  state,
  onContinue,
  onPause,
  onStop,
  staleSession,
  onReattach,
}) => {
  // instead of a full box border around a padded Box,
  // the bar is now a single row with a left-edge accent
  // character (▌) coloured by the run state. The horizontal
  // separator uses the same accent colour so the bar reads
  // as "one continuous element" without the visual heaviness
  // of a full box border. This is the change the user
  // explicitly asked for ("tui 需要参考 opencode, 不输于
  // opencode").
  const color = state.submitting ? t.warn : colorForKind(state.lastStopKind);
  const kind = state.lastStopKind;
  const reason = state.lastStopReason ?? "";
  const label = STOP_LABEL[reason] ?? "✓ ready";

  // T-441: build a ContextInfo snapshot for the embedded
  // ContextMeter. We default `maxTokens` to 200_000 (the
  // de-facto "most modern model" floor) when the daemon
  // hasn't published one yet, so the bar still renders
  // meaningfully on first connect. The auto-compact
  // disabled flag is pulled from the same state field the
  // ContextMeter is designed to consume; if the engine
  // hasn't yet reported it, we leave it false.
  const ctxInfo: ContextInfo = {
    inputTokens: state.inputTokens ?? 0,
    maxTokens: state.contextMaxTokens ?? 200_000,
    lastCompactTs: state.lastCompactTs ?? null,
    autoCompactDisabled: state.autoCompactDisabled ?? false,
  };
  // The ContextMeter does its own 2 s polling, but we
  // pause it while a permission ask is pending so the
  // bar's reactivity is predictable when the user is in
  // a decision state.
  const ctxPaused = state.permissionAsk != null;

  // T-6-16: the StatusBar is now a 3-row block:
  //   1. Optional SessionControl (Continue / Pause / Stop).
  //   2. Optional stale-session re-attach banner.
  //   3. The original status line(s).
  // The two optional rows are hidden when the parent
  // doesn't supply the props (preserves every existing
  // test that doesn't render the control).
  const showControl = !!(onContinue || onPause || onStop);
  const showStale =
    !!staleSession &&
    isStale(staleSession.lastActiveAt, Date.now()) &&
    !!onReattach;

  // Map the TUI's stringly-typed state.status to the
  // SessionControl's typed SessionState. The State.status
  // is uppercase; the SessionControl expects lowercase.
  const sessionState: SessionState = (() => {
    const s = String(state.status || "").toLowerCase();
    if (s === "running" || s === "thinking" || s === "streaming" || s === "running-tool" || s === "connecting" || s === "exiting") {
      return "running";
    }
    if (s === "paused") return "paused";
    if (s === "completed" || s === "ok") return "completed";
    if (s === "failed" || s === "error") return "failed";
    if (s === "cancelled") return "cancelled";
    return "running";
  })();

  // While the model is working, the spinner + a yellow
  // "thinking/streaming" label.
  if (state.submitting) {
    return (
      <Box flexDirection="column" paddingX={1}>
        {showControl ? (
          <SessionControl
            state={sessionState}
            onContinue={onContinue}
            onPause={onPause}
            onStop={onStop}
            showHotkeyHint={false}
          />
        ) : null}
        {showStale ? (
          <StaleBanner
            id={staleSession!.id}
            lastActiveAt={staleSession!.lastActiveAt}
            onReattach={onReattach!}
          />
        ) : null}
        <Text color={color}>{"─".repeat(60)}</Text>
        <Box flexDirection="row" justifyContent="space-between">
          <Text>
            <Text color={color}>▌ </Text>
            <Text color={t.spinner}><Spinner type="dots" /> </Text>
            <Text color={color} bold>{labelForRunning(state.status)}</Text>
            {state.pending > 0 ? <Text dimColor>  ·  {state.pending} tool call{state.pending === 1 ? "" : "s"} in flight</Text> : null}
            <Text dimColor>  ·  mode </Text>
            <Text>{state.permissionMode}</Text>
          </Text>
          <Text>
            <Text dimColor>in </Text>
            <Text>{formatTokens(state.inputTokens)}</Text>
            <Text dimColor> · out </Text>
            <Text>{formatTokens(state.outputTokens)}</Text>
            <Text dimColor> · </Text>
            <Text>{formatCost(state.totalCostUsd)}</Text>
            {state.costBudget > 0 ? (
              <>
                <Text dimColor>  ·  </Text>
                <ProgressBar
                  value={state.totalCostUsd ?? 0}
                  max={state.costBudget}
                  width={10}
                  color={costBarColor(state.totalCostUsd ?? 0, state.costBudget)}
                />
              </>
            ) : null}
          </Text>
        </Box>
      </Box>
    );
  }

  // After the run ended. The colour + label depend on the stop kind.
  // (color / kind / reason / label were hoisted above so the
  // submitting branch can share them.)

  return (
    <Box flexDirection="column" paddingX={1}>
      {showControl ? (
        <SessionControl
          state={sessionState}
          onContinue={onContinue}
          onPause={onPause}
          onStop={onStop}
          showHotkeyHint={false}
        />
      ) : null}
      {showStale ? (
        <StaleBanner
          id={staleSession!.id}
          lastActiveAt={staleSession!.lastActiveAt}
          onReattach={onReattach!}
        />
      ) : null}
      <Text color={color}>{"─".repeat(60)}</Text>
      <Box flexDirection="row" justifyContent="space-between">
        <Text>
          <Text color={color}>▌ </Text>
          <Text color={color}>{kind === "ok" || kind === null ? "●" : "■"}</Text>
          <Text color={color} bold> {label}</Text>
        {/* connection-state badge. The four states
            map to four colour-coded glyphs + one-line
            hints so the user always knows whether the
            daemon is alive. legacy the badge was
            implicit ("you can type = daemon is up"); a
            user who saw a frozen UI had no way to know
            whether the daemon had crashed or whether
            the model was still thinking. */}
        {state.connectionState === "reconnecting" ? (
          <Text color={t.warn}>
            {"  ·  "}↻ reconnecting ({state.reconnectAttempt}/{state.maxReconnectAttempts})
            {state.lastStderrTail ? (
              <Text dimColor> — {state.lastStderrTail.replace(/\s+/g, " ").slice(0, 80)}</Text>
            ) : null}
          </Text>
        ) : state.connectionState === "disconnected" ? (
          <Text color="red">
            {"  ·  "}✕ disconnected ({state.reconnectAttempt}/{state.maxReconnectAttempts})
            {state.lastStderrTail ? (
              <Text dimColor> — {state.lastStderrTail.replace(/\s+/g, " ").slice(0, 80)}</Text>
            ) : null}
            <Text dimColor> — Ctrl-R to retry</Text>
          </Text>
        ) : state.connectionState === "connecting" ? (
          <Text color={t.spinner}>
            {"  ·  "}<Spinner type="dots" /> connecting...
          </Text>
        ) : null}
        {kind === "loop" || kind === "max_turns" || kind === "error" ? (
          <Text dimColor>  ·  type a new prompt to continue</Text>
        ) : (
          <Text dimColor>  ·  type a prompt to continue</Text>
        )}
        {/* background subagent indicator. The most
            recent subagent_event notification is rendered
            inline so the user can see "[sag-1] running"
            / "[sag-2] done 1.4s" without having to run
            a subagent_status RPC. Suppressed when no
            subagent has been observed (empty string). */}
        {state.subagentStatus ? (
          <>
            <Text dimColor>  ·  </Text>
            <Text color={t.accent}>{state.subagentStatus}</Text>
            {state.runningSubagents > 1 ? (
              <Text dimColor> ({state.runningSubagents} running)</Text>
            ) : null}
          </>
        ) : null}
        <Text dimColor>  ·  mode </Text>
        <Text>{state.permissionMode}</Text>
        {/* skip-confirmation counter. Renders only when
            the engine is in an active "skip" state. The badge
            uses the warn colour to draw attention without
            being alarming — the user explicitly opted in
            (via "no confirmation needed for next N rounds"
            or by running the /skip command). */}
        {state.skipConfirmationRemaining > 0 ? (
          <>
            <Text dimColor>  ·  </Text>
            {/* when remaining is at or below the
                waterline (or a recent lastSkipLow event
                fired), show the "low!" badge. The recent
                event takes precedence — even if the counter
                has since dropped to 0, we want the user to
                see the warning for ~5s. */}
            {(() => {
              const recent = state.lastSkipLow
                && Date.now() - state.lastSkipLow.atMs < 5_000;
              const atWaterline = state.skipConfirmationRemaining <= state.skipLowWaterline
                && state.skipLowWaterline > 0;
              if (recent || atWaterline) {
                return (
                  <Text color="red">
                    ⏩ skip {state.skipConfirmationRemaining} (low!)
                  </Text>
                );
              }
              return (
                <Text color={t.warn}>⏩ skip {state.skipConfirmationRemaining}</Text>
              );
            })()}
          </>
        ) : null}
        {/* permission-mode suggestion. Renders when
            the engine has a heuristic suggestion AND the
            suggested mode differs from the user's current
            mode. The user can accept with /mode ACCEPT_TASK
            (etc). */}
        {state.permissionModeSuggestion
          && state.permissionModeSuggestion.mode !== state.permissionMode ? (
          <>
            <Text dimColor>  ·  </Text>
            <Text color="cyan">
              💡 suggested: {state.permissionModeSuggestion.mode}
            </Text>
          </>
        ) : null}
        {/* skip-confirmation adoption stats. Renders
            only when at least one prompt has been issued AND
            the user has armed a skip at least once. Empty
            state is intentional — the user doesn't need to
            see "0/0" before they've used the feature. */}
        {state.skipStats.armed > 0 && state.skipStats.prompts > 0 ? (
          <>
            <Text dimColor>  ·  </Text>
            <Text dimColor>
              skip: {state.skipStats.consumed}/{state.skipStats.prompts} prompts
            </Text>
          </>
        ) : null}
      </Text>
      {/* T-441: embed the ContextMeter as a compact
          "ctx [████░░] 42% (84k/200k)" row. We override
          the default 200 px width with 24 chars so the
          meter fits the status bar's column without
          wrapping. The 2 s sample interval is also
          reduced to 4 s here because the bar is meant to
          be a *trend* hint, not a live counter (the user
          already has the inputTokens number right below). */}
      <ContextMeter
        info={ctxInfo}
        width={24}
        sampleIntervalMs={ctxPaused ? 1_000_000 : 4_000}
        showLabel={true}
      />
      <Text>
        <Text dimColor>in </Text>
        <Text>{formatTokens(state.inputTokens)}</Text>
        <Text dimColor> · out </Text>
        <Text>{formatTokens(state.outputTokens)}</Text>
        <Text dimColor> · </Text>
        <Text>{formatCost(state.totalCostUsd)}</Text>
        {state.costBudget > 0 ? (
          <>
            <Text dimColor>  ·  </Text>
            <ProgressBar
              value={state.totalCostUsd ?? 0}
              max={state.costBudget}
              width={10}
              color={costBarColor(state.totalCostUsd ?? 0, state.costBudget)}
            />
          </>
        ) : null}
        <Text dimColor>  ·  jar </Text>
        <Text>{jarName(state.jarPath)}</Text>
        {state.recentCosts.length > 1 ? (
          <>
            <Text dimColor>  ·  </Text>
            <Text dimColor>history </Text>
            <TokenChart values={state.recentCosts} width={12} />
          </>
        ) : null}
      </Text>
      </Box>
    </Box>
  );
};

/** pick a color for the cost-budget progress bar based on
 *  the percentage used. Green = plenty of headroom; yellow =
 *  50% used; red = 80%+ used. */
function costBarColor(value: number, max: number): string {
  if (max <= 0) return "green";
  const pct = value / max;
  if (pct >= 0.8) return "red";
  if (pct >= 0.5) return "yellow";
  return "green";
}

function labelForRunning(s: State["status"]): string {
  switch (s) {
    case "thinking":     return "thinking…";
    case "streaming":    return "streaming…";
    case "running-tool": return "running tool…";
    case "connecting":   return "connecting…";
    case "exiting":      return "exiting…";
    default:             return "ready";
  }
}

function colorForKind(kind: State["lastStopKind"]): string {
  switch (kind) {
    case "ok":        return t.ok;
    case "loop":      return t.err;
    case "max_turns": return t.warn;
    case "error":     return t.err;
    case "empty":     return t.dim;
    case null:        return t.dim;
    default:          return t.dim;
  }
}

function jarName(p: string): string {
  if (!p) return "—";
  const m = p.split(/[\\/]/);
  return m[m.length - 1] || p;
}

// T-6-16: the re-attach banner shown when the session
// has been inactive for > 30 minutes. Inline banner —
// the full modal lives in `StaleWarning.tsx`. We use
// `useInput` so pressing Enter while the bar has
// focus triggers the re-attach.
const StaleBanner: React.FC<{
  id: string;
  lastActiveAt: number;
  onReattach: (id: string) => void;
}> = ({ id, lastActiveAt, onReattach }) => {
  useInput((_input, key) => {
    if (key.return) onReattach(id);
  });
  return (
    <Box flexDirection="row">
      <Text color={t.warn} bold>· {staleLabel(lastActiveAt)}</Text>
      <Text dimColor>  ·  </Text>
      <Text color={t.accent} underline>
        {id.slice(0, 12)}
      </Text>
      <Text>  </Text>
      <Text color="green">[re-attach]</Text>
      <Text dimColor>  </Text>
      <Text color="cyan" underline>
        (Enter)
      </Text>
    </Box>
  );
};
