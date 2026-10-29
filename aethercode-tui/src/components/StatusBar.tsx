// Copyright (c) 2026 maijun

// Licensed under the MIT License.
// See the LICENSE file in the project root for the full license text.

/**
 * bottom status bar — R344 redesign.
 *
 * Claude Code–inspired ONE-LINE status strip. The previous design
 * sprawled across 3–4 rows (status line, ctx meter row, in/out/cost
 * row, history chart row) — heavy visual noise that competed with
 * the scrollback for attention. The new design collapses everything
 * to a single row:
 *
 *   ▌ ✓ ready  ·  in 1.2k · out 0.3k · $0.04 · 2m14s · jar aethercode-0.2.71.jar
 *
 *   ▌ ⠋ thinking…  ·  in 0 · out 0 · 12s · jar aethercode-0.2.71.jar
 *
 *   ▌ ✕ disconnected (3/10) — Ctrl-R to retry  ·  jar aethercode-0.2.71.jar
 *
 * The SessionControl row (Continue/Pause/Stop) and the stale-session
 * re-attach banner are kept but rendered above the status line only
 * when the host wires the corresponding callbacks (preserves every
 * existing test).
 *
 * Subagent / mode-suggestion / skip-confirmation badges are now
 * shown as toasts (or in the right panel) instead of cramming the
 * status line — they were the main reason the bar grew to 470
 * lines in the first place.
 */

import React from "react";
import { Box, Text, useInput } from "ink";
import Spinner from "ink-spinner";
import { hintFor } from "../componentKeys.js";
import type { State } from "../state.js";
import { t, formatCost, formatTokens } from "../theme.js";
// R371: T-441 declared this integration and shipped the component, the
// state fields and six source-pin assertions — but never imported or
// rendered it. The three `T-441: StatusBar.tsx …` cases have been red
// in the full suite ever since, which is the only reason anyone
// noticed. The fields it reads were equally dead: `contextMaxTokens`,
// `lastCompactTs` and `autoCompactDisabled` had no action that wrote
// them, so even a mounted meter would have shown a confident 0% against
// a hard-coded 200_000 window.
import { ContextMeter, type ContextInfo } from "./ContextMeter.js";
import { SessionControl } from "./session/SessionControl.js";
// R375: the session-state mapping lives with the rest of the
// run-control vocabulary, so the status bar, the line renderer and the
// gate read ONE definition of "is this session paused?". A second copy
// here was a second answer to that question — the exact shape of bug
// this round exists to remove.
import { runControlState } from "../runControl.js";
import { isStale, staleLabel } from "./session/SessionDetailsPanel.js";

interface Props {
  state: State;
  onContinue?: () => void;
  onPause?: () => void;
  onStop?: () => void;
  staleSession?: { id: string; lastActiveAt: number } | null;
  onReattach?: (id: string) => void;
}

/** Display kind → user-facing label. THE ONE label table.
 *
 * R383: this used to be TWO tables — `STOP_LABEL` (keyed by the stop
 * reason) and `KIND_LABEL` (keyed by the display kind) — and the
 * renderer consulted the FIRST one. That was a second copy of the
 * classification truth living in the renderer, which is exactly the
 * bug this round removes: the daemon decides the verdict (`isError`)
 * and names the kind (`stopKind`), and the renderer only words it.
 *
 * Two things proved the two tables could not both be right:
 *
 *   1. The reason table could never hit for a decorated reason.
 *      The engine sends `"error: <class>: <msg>"`, so a lookup keyed
 *      by the raw reason missed every failure and the strip showed a
 *      GREEN "✓ ready" for a run that had died with an upstream 500.
 *   2. It disagreed with the classifier. `"same_fingerprint"` was
 *      labelled "stopped: loop" while the classifier called it "ok" —
 *      so the strip rendered the words "stopped: loop" in the colour
 *      for success. (That reason is a progress-loop-detector KIND,
 *      not a stop reason; the engine's stop reason is
 *      `"loop_detected: <tier> (…)"`.)
 *
 * Every live entry of the old table was already reproduced by
 * `KIND_LABEL`, so removing it loses no wording. */
const KIND_LABEL: Record<NonNullable<State["lastStopKind"]>, string> = {
  ok: "✓ ready",
  loop: "stopped: loop",
  max_turns: "stopped: max turns",
  error: "stopped: error",
  empty: "empty input",
};

/** Run state → running label. */
function labelForRunning(s: State["status"]): string {
  switch (s) {
    case "thinking":
      return "thinking…";
    case "streaming":
      return "streaming…";
    case "running-tool":
      return "running tool…";
    case "connecting":
      return "connecting…";
    case "exiting":
      return "exiting…";
    default:
      return "ready";
  }
}

/** Map last stop reason to accent colour. */
function colorForKind(kind: State["lastStopKind"]): string {
  switch (kind) {
    case "ok":
      return t.ok;
    case "loop":
      return t.err;
    case "max_turns":
      return t.warn;
    case "error":
      return t.err;
    default:
      return t.dim;
  }
}

/** Last 2 path segments of a jar path. */
function jarName(p: string): string {
  if (!p) return "—";
  const m = p.split(/[\\/]/);
  return m[m.length - 1] || p;
}

export const StatusBar: React.FC<Props> = ({
  state,
  onContinue,
  onPause,
  onStop,
  staleSession,
  onReattach,
}) => {
  const color = state.submitting ? t.warn : colorForKind(state.lastStopKind);
  // R383: the label comes from the DISPLAY KIND, which the daemon's own
  // verdict produced — never from a lookup on the raw stopReason. The
  // raw reason is decorated ("error: <class>: <msg>"), so a lookup on
  // it never hit and every failure rendered as the green "✓ ready".
  const label = state.submitting
    ? labelForRunning(state.status)
    : KIND_LABEL[state.lastStopKind ?? "ok"];

  const showControl = !!(onContinue || onPause || onStop);
  const showStale =
    !!staleSession && isStale(staleSession.lastActiveAt, Date.now()) && !!onReattach;

  // ONE-LINE status strip — left to right:
  //   ▌ <kind-glyph> <label>  ·  in 1.2k · out 0.3k · $0.04 · <elapsed> · jar <name>
  // The connection-state badge replaces the label when not connected.
  // The skip-confirmation counter is shown inline when > 0 (small
  // addition; users opt into this feature explicitly).
  const elapsed = state.uptimeStartedAt
    ? Math.max(0, Math.floor((Date.now() - state.uptimeStartedAt) / 1000))
    : 0;
  const elapsedLabel =
    elapsed > 0
      ? elapsed < 60
        ? `${elapsed}s`
        : `${Math.floor(elapsed / 60)}m${elapsed % 60 ? (elapsed % 60).toString().padStart(2, "0") : ""}`
      : "—";
  const connGlyph =
    state.connectionState === "connecting" ? (
      <Text color={t.spinner}>
        <Spinner type="dots" />{" "}
      </Text>
    ) : state.connectionState === "reconnecting" ? (
      <Text color={t.warn}>↻</Text>
    ) : state.connectionState === "disconnected" ? (
      <Text color={t.err}>✕</Text>
    ) : (
      <Text color={color}>●</Text>
    );
  const labelNode =
    state.connectionState === "disconnected" ? (
      <Text color={t.err} bold>
        disconnected ({state.reconnectAttempt}/{state.maxReconnectAttempts})
      </Text>
    ) : state.connectionState === "reconnecting" ? (
      <Text color={t.warn} bold>
        reconnecting ({state.reconnectAttempt}/{state.maxReconnectAttempts})
      </Text>
    ) : state.connectionState === "connecting" ? (
      <Text color={color} bold>
        connecting…
      </Text>
    ) : (
      <Text color={color} bold>
        {label}
      </Text>
    );
  const skipNode =
    state.skipConfirmationRemaining > 0 ? (
      // R362: R350 collapsed the bar to one line and shortened this badge to
      // `⏩ 5`, which dropped the word "skip" — a user glancing at the status
      // strip had no way to tell what the counter meant. Put the label back.
      // It costs 5 columns and makes the badge self-describing.
      <Text color={t.warn}> · ⏩ skip {state.skipConfirmationRemaining}</Text>
    ) : null;

  // R371 — T-441's ContextInfo, finally wired.
  //
  // `inputTokens` is passed through NULLABLE, not coerced. It is the
  // live prompt cost off the last usage event, so before the first
  // turn of a session there is no measurement at all — and
  // `state.contextMaxTokens` is null for the same window, meaning
  // `maxTokens` is the 200_000 fallback rather than the model's real
  // window. Rendering `0 / 200000 = 0%` there produces a bar that
  // looks measured and is invented, so the meter shows `—` instead
  // (ContextInfo.inputTokens became `number | null` for this).
  const ctxInfo: ContextInfo = {
    inputTokens: state.inputTokens,
    maxTokens: state.contextMaxTokens ?? 200_000,
    lastCompactTs: state.lastCompactTs,
    autoCompactDisabled: state.autoCompactDisabled,
  };
  // No point sampling a bar whose input cannot change while a run is
  // live — and a 4 s timer that keeps firing behind a modal is a
  // re-render nobody asked for. 1e6 ms parks it until the bar unmounts.
  const ctxPaused = state.submitting;

  return (
    <Box flexDirection="column" paddingX={1}>
      {showControl ? (
        <SessionControl
          state={runControlState(state.status, state.continuationPaused)}
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
        <Box>
          <Text color={color}>▌ </Text>
          {connGlyph}
          <Text> </Text>
          {labelNode}
          {state.pending > 0 && state.submitting ? (
            <Text dimColor>
              {" "}
              · {state.pending} tool call{state.pending === 1 ? "" : "s"} in flight
            </Text>
          ) : null}
          {skipNode}
        </Box>
        <Box>
          <Text dimColor>in </Text>
          <Text>{formatTokens(state.inputTokens)}</Text>
          <Text dimColor> · out </Text>
          <Text>{formatTokens(state.outputTokens)}</Text>
          <Text dimColor> · </Text>
          <Text>{formatCost(state.totalCostUsd)}</Text>
          <Text dimColor> · {elapsedLabel}</Text>
          <Text dimColor> · jar </Text>
          <Text>{jarName(state.jarPath)}</Text>
        </Box>
      </Box>
      {/* R371: the one-line strip stays one line; the meter gets its
          own row rather than 24 columns inside the strip, which would
          have squeezed the token counters off an 80-column terminal. */}
      <ContextMeter info={ctxInfo} width={24} sampleIntervalMs={ctxPaused ? 1_000_000 : 4_000} />
    </Box>
  );
};

/** Stale-session re-attach banner. Inline; Enter triggers re-attach. */
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
      <Text color={t.warn} bold>
        · {staleLabel(lastActiveAt)}
      </Text>
      <Text dimColor> · </Text>
      <Text color={t.accent} underline>
        {id.slice(0, 12)}
      </Text>
      <Text> </Text>
      <Text color="green">[re-attach]</Text>
      <Text dimColor> </Text>
      <Text color="cyan" underline>
        ({hintFor("components/StatusBar.tsx")})
      </Text>
    </Box>
  );
};
