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
// R388o: the bar must FIT the terminal. Before this, the right-hand
// box carried `flexShrink={0}` and never shrank, so on an 80-column
// terminal it overflowed by 13 cells; the terminal wrapped the row,
// the frame grew a row, and that is what made the page "move".
//
// The width comes from ONE source (`terminalGeometry`), and the jar
// name is truncated to whatever is left. Truncating the jar name is
// the right trade: it is the least informative item in the bar, and a
// bar that does not fit is a bar that breaks the whole layout.
import { cellWidth, fitCells, terminalSize } from "../terminalGeometry.js";

interface Props {
  state: State;
  onContinue?: () => void;
  onPause?: () => void;
  onStop?: () => void;
  staleSession?: { id: string; lastActiveAt: number } | null;
  onReattach?: (id: string) => void;
  /** R388o: the terminal width to lay out for. When omitted the bar
   *  asks the terminal itself. The caller passes it so the bar and the
   *  rest of the frame agree on ONE width. */
  width?: number;
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
  width,
}) => {
  const color = state.submitting ? t.warn : colorForKind(state.lastStopKind);
  // R383: the label comes from the DISPLAY KIND, which the daemon's own
  // verdict produced — never from a lookup on the raw stopReason. The
  // raw reason is decorated ("error: <class>: <msg>"), so a lookup on
  // it never hit and every failure rendered as the green "✓ ready".
  const label = state.submitting
    ? labelForRunning(state.status)
    : KIND_LABEL[state.lastStopKind ?? "ok"];

  // R388h: the run-control row appears only when there is a run to
  // control. It used to render unconditionally, so an IDLE TUI spent
  // a whole line on "Continue [c] Pause [p] Stop [s]" — three
  // buttons for actions that would all do nothing. The desktop's
  // centre panel shows its run controls only while a run is live.
  //
  // So: while a run is in flight, or while the auto-continuation is
  // paused (the only state where "Continue" means anything), the row
  // is there. Otherwise the bottom of the screen is the input pill
  // and ONE status line — which is the shape the user asked for.
  const showControl =
    !!(onContinue || onPause || onStop) && (state.submitting || state.continuationPaused);
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

  // R388o: THE BAR MUST FIT.
  //
  // Two things made it not fit:
  //
  //   1. the rule above the bar was a hard-coded 60 characters, so
  //      on a 40-column terminal it overflowed by 20 cells;
  //   2. the right-hand box carried `flexShrink={0}` and never
  //      shrank, so on an 80-column terminal the whole row was 93
  //      cells wide — 13 over. The terminal WRAPS an over-wide row,
  //      which adds a row to the frame, which is what made the page
  //      "change size" and "move".
  //
  // ★ DO NOT FIX IT WITH `flexShrink` ★ Letting the right-hand box
  // shrink does NOT truncate its text: Ink shrinks the BOX, and the
  // Text nodes inside it overlap each other instead of being cut
  // (measured: "jaaethercode-cli-0.1.0-SNAP"). A mangled row is
  // worse than a truncated one.
  //
  // So the jar name is TRUNCATED UP FRONT, to a budget computed from
  // the bar's own width, and the box keeps its natural size. The jar
  // path is diagnostic detail; the token counters are the number the
  // user reads — so the jar yields first.
  const barWidth = Math.max(20, width ?? terminalSize().cols);
  // The bar's own horizontal padding (paddingX={1} on the wrapper).
  const innerWidth = Math.max(10, barWidth - 2);
  // ★ THE JAR BUDGET IS MEASURED, NOT ESTIMATED ★
  //
  // An estimate of the right side's fixed width was WRONG by eight
  // cells (the row came out 88 cells wide in an 80-column terminal),
  // and an estimate that is wrong is worse than no estimate: the row
  // still overflowed, and it did so silently.
  //
  // So the fixed part is MEASURED, with the same cell rule the
  // renderer uses (`cellWidth`), and the jar gets what is left.
  //
  // The left half ("▌ ● ✓ ready") is measured too — it is the other
  // thing on this row.
  const leftFixedCells = cellWidth(
    `▌ ${state.connected ? "●" : "○"} ${label}` +
      (state.pending > 0 && state.submitting
        ? ` · ${state.pending} tool call${state.pending === 1 ? "" : "s"} in flight`
        : ""),
  );
  const rightFixedCells =
    cellWidth(" · ") +
    cellWidth("ctx ") +
    12 + // the meter's bar, in brackets
    cellWidth(" · in ") +
    cellWidth(formatTokens(state.inputTokens)) +
    cellWidth(" · out ") +
    cellWidth(formatTokens(state.outputTokens)) +
    cellWidth(" · ") +
    cellWidth(formatCost(state.totalCostUsd)) +
    cellWidth(` · ${elapsedLabel}`) +
    cellWidth(" · jar ");
  // ★ NARROW TERMINALS DROP ITEMS, THEY DO NOT SQUEEZE THEM ★
  //
  // At 40 columns the fixed part of the right side alone was 64
  // cells — 24 over. The jar was already dropped, and the row STILL
  // overflowed, because the fixed part was never measured against the
  // bar's width; it was only measured against the jar's budget.
  //
  // So the right side's items are dropped IN PRIORITY ORDER, exactly
  // like the header's: the cost first, then the token counters, then
  // the context meter. What is left is the state label and the
  // connection dot — the two things that say "is this session alive".
  //
  // The order is: jar → cost → output tokens → input tokens → the
  // context meter. The jar is diagnostic detail; the counters are the
  // numbers the user reads; the meter is a gauge. The connection dot
  // and the state label are never dropped.
  const ctxCells = cellWidth("ctx ") + 12;
  const inCells = cellWidth(" · in ") + cellWidth(formatTokens(state.inputTokens));
  const outCells = cellWidth(" · out ") + cellWidth(formatTokens(state.outputTokens));
  const costCells = cellWidth(" · ") + cellWidth(formatCost(state.totalCostUsd));
  const elapsedCells = cellWidth(` · ${elapsedLabel}`);
  const jarLabelCells = cellWidth(" · jar ");

  // The right side's items, most-droppable first.
  const rightParts: { key: string; cells: number }[] = [
    { key: "jar", cells: jarLabelCells },
    { key: "cost", cells: costCells },
    { key: "out", cells: outCells },
    { key: "in", cells: inCells },
    { key: "ctx", cells: ctxCells },
    { key: "elapsed", cells: elapsedCells },
  ];
  // Drop from the front (the jar first) until the rest fits. The
  // separator before the meter (" · ") is only rendered when the
  // meter survives.
  const drop = new Set<string>();
  let used = leftFixedCells;
  for (const part of rightParts) {
    if (used + part.cells <= innerWidth) {
      used += part.cells;
      continue;
    }
    drop.add(part.key);
  }
  const showMeter = !drop.has("ctx");
  // The jar NAME gets whatever is left AFTER everything else — and the
  // budget covers the NAME only, because the " · jar " label is
  // already counted in `jarLabelCells`.
  //
  // ★ THE LABEL IS PART OF THE JAR ★ The label and the name are
  // rendered together, so they must be budgeted together. Budgeting
  // the name alone left the row 2 cells over at EVERY width (the
  // " · jar " separator is 6 cells and the name was budgeted as if
  // the separator were not there).
  const jarBudget = innerWidth - used - jarLabelCells;
  const jar = !drop.has("jar") && jarBudget >= 4 ? fitCells(jarName(state.jarPath), jarBudget) : "";

  return (
    <Box flexDirection="column" paddingX={1} flexShrink={0}>
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
      {/* R388o: the rule is the TERMINAL's width, not a fixed 60.
          A fixed 60 overflowed every terminal narrower than 60 and
          left a gap on every wider one. */}
      <Text color={color}>{"─".repeat(innerWidth)}</Text>
      {/* R388h: ONE status row. The context meter used to be a whole
          second line under the strip (and a THIRD line for the
          "no usage reported yet" note). It is now a compact
          `ctx ▓░░ 12%` chip on the LEFT of the same row, and the
          right side keeps the token counters — the two are
          different numbers, so they can share a line without
          competing. */}
      <Box flexDirection="row" justifyContent="space-between">
        {/* R388o: the LEFT half keeps its natural size too. It carries
            the state label — "ready", "stopped: loop" — which is the
            one thing in the bar the user reads at a glance. A shrunk
            box overlapped the glyph and the label ("●✓ ·"). */}
        <Box flexDirection="row" flexShrink={0}>
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
        {/* R388h: an explicit gap. `justifyContent="space-between"`
            only inserts space while the row still has room — with a
            long left half ("running tool… · 1 tool call in flight")
            the two boxes butted up against each other and read as
            "in flightctx[…]". A separator can never be squeezed
            out. */}
        {/* R388o: `flexShrink={0}` is what made this row overflow.
            The right-hand side keeps its NATURAL size, and the one item
            that can be shortened without losing a NUMBER — the jar
            name — is truncated UP FRONT (see `jarBudget` above). A
            shrunk box makes Ink overlap the Text nodes inside it,
            which mangles the row instead of shortening it. */}
        <Box flexDirection="row" flexShrink={0}>
          {showMeter ? (
            <>
              <Text dimColor> · </Text>
              <ContextMeter
                info={ctxInfo}
                width={10}
                sampleIntervalMs={ctxPaused ? 1_000_000 : 4_000}
                showLabel={false}
              />
            </>
          ) : null}
          {!drop.has("in") ? (
            <>
              <Text dimColor> · in </Text>
              <Text>{formatTokens(state.inputTokens)}</Text>
            </>
          ) : null}
          {!drop.has("out") ? (
            <>
              <Text dimColor> · out </Text>
              <Text>{formatTokens(state.outputTokens)}</Text>
            </>
          ) : null}
          {!drop.has("cost") ? (
            <>
              <Text dimColor> · </Text>
              <Text>{formatCost(state.totalCostUsd)}</Text>
            </>
          ) : null}
          {!drop.has("elapsed") ? <Text dimColor> · {elapsedLabel}</Text> : null}
          {jar ? (
            <>
              <Text dimColor> · jar </Text>
              <Text>{jar}</Text>
            </>
          ) : null}
        </Box>
      </Box>
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
