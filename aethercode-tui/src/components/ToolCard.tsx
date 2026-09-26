/**
 * tool call card.
 * collapsible — Tab / Ctrl-E toggles the most recent.
 * evolution —
 *   - duration timer (live when running, fixed when done)
 *   - tool-category icon + colour (read/write/search/run/agent/other)
 *   - per-card 'd' to expand full args + full result (toggleToolExpand)
 *   - status trail ("running 1.4s" → "1.4s ok" → with timings)
 *
 * The card has 3 visual forms (from most collapsed to most expanded):
 *
 *   ◐ glob  pattern=**.java     1.4s
 *
 *   ◐ glob  pattern=**.java     1.4s   [Tab to expand]
 *   │ result-preview (truncated to 400 chars)
 *
 *   ◐ glob  pattern=**.java     1.4s   [d: details | Tab: collapse]
 *   ┌─ args (full) ─────────────┐
 *   │ {"pattern": "**.java"}   │
 *   └────────────────────────────┘
 *   ┌─ result (full) ────────────┐
 *   │ README.md                  │
 *   │ ...                        │
 *   └────────────────────────────┘
 *
 * The category icon + color is the key "sensory" signal: a row of
 * tool cards in the scrollback now reads like a color-coded log
 * of the model's actions, not a wall of grey text.
 */

import React from "react";
import { Box, Text } from "ink";
import Spinner from "ink-spinner";
import type { SpinnerName } from "cli-spinners";
import type { Turn, ToolStatus, ToolCategory } from "../state.js";
import { formatDuration } from "../state.js";
import { t, icon } from "../theme.js";
import { Markdown } from "./Markdown.js";

/** per-category spinner type. Each tool *type* gets a
 *  spinner variant that feels right for the operation:
 *    - read   → "dots"   (steady, the model is "looking")
 *    - write  → "line"   (linear, the model is "writing")
 *    - search → "arc"    (circular scan)
 *    - run    → "triangle" (the model is "executing")
 *    - agent  → "star"   (the model is "delegating")
 *    - other  → "dots2"  (default)
 *
 *  The point is sensory differentiation: a row of running tool
 *  cards now shows a *different* spinner for each tool type,
 *  so the user can identify what the model is doing without
 *  reading the tool name. */
const CATEGORY_SPINNER: Record<ToolCategory, SpinnerName> = {
  read:   "dots",
  write:  "line",
  search: "arc",
  run:    "triangle",
  agent:  "star",
  other:  "dots2",
};

function truncate(s: string, n: number): string {
  if (s.length <= n) return s;
  return s.slice(0, Math.max(0, n - 1)) + "…";
}

const STATUS_COLOR: Record<ToolStatus, string> = {
  running: t.panelHot,
  ok:      t.panelOk,
  error:   t.panelErr,
};

const STATUS_ICON: Record<ToolStatus, string> = {
  running: icon.running,
  ok:      icon.ok,
  error:   icon.err,
};

const CATEGORY_ICON: Record<ToolCategory, string> = {
  read:   icon.catRead,
  write:  icon.catWrite,
  search: icon.catSearch,
  run:    icon.catRun,
  agent:  icon.catAgent,
  other:  icon.catOther,
};

const CATEGORY_COLOR: Record<ToolCategory, string> = {
  read:   t.catRead,
  write:  t.catWrite,
  search: t.catSearch,
  run:    t.catRun,
  agent:  t.catAgent,
  other:  t.catOther,
};

function formatArgs(args?: string): string {
  if (!args) return "";
  // The daemon sends args as a JSON-like string. We strip outer
  // braces and newlines for the header.
  return truncate(args.replace(/\s+/g, " ").replace(/^\{/, "").replace(/\}$/, ""), 80);
}

function formatFullArgs(args?: string): string {
  if (!args) return "(no arguments)";
  // Pretty-print JSON if possible; fall back to the raw string.
  try {
    const parsed = JSON.parse(args);
    return JSON.stringify(parsed, null, 2);
  } catch {
    return args;
  }
}

interface Props {
  turn: Turn;
  showResult: boolean;
  /** when true, the card renders in collapsed form. */
  collapsed: boolean;
}

export const ToolCard: React.FC<Props> = ({ turn, showResult, collapsed }) => {
  const status: ToolStatus = turn.toolStatus ?? "running";
  const borderColor = STATUS_COLOR[status];
  const sym = STATUS_ICON[status];
  const argsLine = formatArgs(turn.toolArgs);
  const cat = turn.toolCategory ?? "other";
  const catIcon = CATEGORY_ICON[cat];
  const catColor = CATEGORY_COLOR[cat];

  // duration. While running, show the live elapsed time
  // (computed at render time). When done, show the fixed duration.
  // R34 forces a re-render every second while the tool is running
  // (see tui.tsx ticker), so the timer ticks smoothly.
  const duration = computeDuration(turn);

  // 1-line collapsed form: status + category + name + args + duration.
  // R344: thinner border (single instead of round) and tighter
  // padding so multiple tool cards stack visually like log lines.
  if (collapsed) {
    const longRunning = status === "running" && longRunningMs(turn) > 30_000;
    const veryLong = status === "running" && longRunningMs(turn) > 90_000;
    return (
      <Box
        borderStyle="single"
        borderColor={veryLong ? t.warn : t.dim}
        flexDirection="column"
        paddingX={0}
        marginY={0}
      >
        <Box flexDirection="row" paddingX={1} justifyContent="space-between">
          <Text>
            <Text color={catColor}>{catIcon} </Text>
            <Text dimColor>{turn.toolName ?? "tool"}</Text>
            {argsLine ? <Text dimColor>  {truncate(argsLine, 60)}</Text> : null}
          </Text>
          <Text dimColor>
            {status === "running" ? (
              <Text color={catColor}><Spinner type={CATEGORY_SPINNER[cat]} /> </Text>
            ) : (
              <Text color={borderColor}>{sym} </Text>
            )}
            <Text color={veryLong ? t.warn : undefined}>{status === "running" ? `${duration}…` : `${duration} ${status}`}</Text>
            {longRunning ? <Text dimColor>  ·  still running</Text> : null}
          </Text>
        </Box>
      </Box>
    );
  }

  // Expanded (default) form: status + category + name + args + duration
  // and the result preview (truncated to 400 chars).
  if (!turn.toolExpanded) {
    return (
      <Box
        borderStyle="single"
        borderColor={borderColor}
        flexDirection="column"
        paddingX={0}
        marginY={0}
      >
        <Box flexDirection="row" paddingX={1} justifyContent="space-between">
          <Text>
            {status === "running" ? (
              <Text color={catColor}><Spinner type={CATEGORY_SPINNER[cat]} /> </Text>
            ) : (
              <Text color={borderColor}>{sym} </Text>
            )}
            <Text color={catColor}>{catIcon} </Text>
            <Text bold>{turn.toolName ?? "tool"}</Text>
            {argsLine ? <Text dimColor>  {argsLine}</Text> : null}
          </Text>
          <Text dimColor>
            {duration}
            {"  ·  Tab: collapse  ·  d: details"}
          </Text>
        </Box>
        {showResult && turn.toolResult ? (
          <Box marginTop={0} paddingX={1}>
            <Text dimColor>{truncate(turn.toolResult, 400)}</Text>
          </Box>
        ) : null}
      </Box>
    );
  }

  // Fully-expanded "details" form (prior round): full args + full result
  // in monospace blocks. Press 'd' again to collapse details.
  return (
    <Box
      borderStyle="single"
      borderColor={borderColor}
      flexDirection="column"
      paddingX={0}
      marginY={0}
    >
      <Box flexDirection="row" paddingX={1} justifyContent="space-between">
        <Text>
          {status === "running" ? (
            <Text color={catColor}><Spinner type={CATEGORY_SPINNER[cat]} /> </Text>
          ) : (
            <Text color={borderColor}>{sym} </Text>
          )}
          <Text color={catColor}>{catIcon} </Text>
          <Text bold>{turn.toolName ?? "tool"}</Text>
        </Text>
        <Text dimColor>
          {duration}
          {"  ·  Tab: collapse  ·  d: collapse"}
        </Text>
      </Box>
      <Box flexDirection="column" marginTop={1} marginLeft={2}>
        <Text dimColor>{icon.detail} args:</Text>
        <Text color={t.code}>{formatFullArgs(turn.toolArgs)}</Text>
      </Box>
      {showResult && turn.toolResult ? (
        <Box flexDirection="column" marginTop={1} marginLeft={2}>
          <Text dimColor>{icon.detail} result:</Text>
          <Box marginTop={0} marginLeft={1}>
            <Markdown text={turn.toolResult} />
          </Box>
        </Box>
      ) : null}
    </Box>
  );
};

function computeDuration(turn: Turn): string {
  if (turn.toolStartedAt == null) return "—";
  const end = turn.toolEndedAt ?? Date.now();
  return formatDuration(end - turn.toolStartedAt);
}

/** milliseconds since the tool started — used to decide whether
 *  to show the "still running" hint in the collapsed card. The card's
 *  `toolStartedAt` is set by the reducer on `streamToolStart`; the
 *  duration ticker in tui.tsx re-renders this card every 500ms while
 *  a tool is in flight so the threshold crossing is visible without
 *  the user having to tab into the card. */
function longRunningMs(turn: Turn): number {
  if (turn.toolStartedAt == null) return 0;
  return Date.now() - turn.toolStartedAt;
}
