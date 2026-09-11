// a side panel that lists the user's recent
// background subagents. Mirrors the desktop's
// SubagentPanel (added in the same round). The panel is
// toggled with Ctrl+S, focuses a row via ↑/↓, and
// exposes per-row actions:
//
//   RUNNING row    → press c to cancel (calls subagentCancel RPC)
//   COMPLETED row  → press Enter to insert the result into the input box
//   FAILED row     → press Enter to insert the error message
//   CANCELLED row  → press Enter to insert a "(cancelled)" note
//   Esc            → close the panel
//
// The panel renders inline on the right side of the
// main scrollback. When open, the main input box is
// still active (we don't block chat — the panel is a
// transient overlay, not a modal). The TUI's main
// useInput handler dispatches openSubagentPanel /
// closeSubagentPanel / subagentPanelFocusNext/Prev on
// Ctrl+S / Esc / ↑/↓ only when the panel is open; the
// per-row action keys (c, Enter) are also gated on
// panel-open so they don't fire while the user is
// typing in the input box.

import React from "react";
import { Box, Text } from "ink";
import {
  type State,
  subagentJobIdsInOrder,
  formatSubagentPanelRow,
  type SubagentJobView,
} from "../state.js";

export interface SubagentPanelProps {
  state: State;
  /** Wall-clock ms; passed in so the row labels can
   *  show a live elapsed for RUNNING jobs without each
   *  row building its own interval. Defaults to
   *  `Date.now()` — the App component does NOT pass a
   *  fresh value on every render (that would force the
   *  whole SubagentPanel subtree to re-render and Ink
   *  to re-paint the row, which the user perceives as
   *  the layout "jumping"). The panel reads Date.now()
   *  internally for its duration counters; that only
   *  re-runs when something else (a subagent_event
   *  notification, the panel's own setInterval) ticks. */
  now?: number;
  /** Width of the panel in characters. The App
   *  container reads the terminal width and passes
   *  half of it (the right column). */
  width: number;
  /** Optional: called when the user presses c on a
   *  RUNNING row. The App wires this to the
   *  subagentCancel RPC + a follow-up dispatch to
   *  openSubagentPanel (so the panel stays open and
   *  shows the now-CANCELLED row). */
  onCancel?: (jobId: string) => void;
  /** Optional: called when the user presses Enter on
   *  a terminal row. The App wires this to
   *  setCurrentInput so the result text (or a short
   *  note for CANCELLED / FAILED) lands in the input
   *  box ready to be sent. */
  onInsert?: (jobId: string) => void;
  /** called when the user presses 'v' on a
   *  focused row. The App wires this to loadSession
   *  (so the transcript shows the subagent's turns)
   *  + dispatch({ type: "viewSubagent", jobId }).
   *  Mirrors the desktop's "View subagent →" link
   *  inside SubagentPanel. */
  onView?: (jobId: string) => void;
}

const STATUS_ICON: Record<SubagentJobView["status"], string> = {
  RUNNING:   "▶",
  COMPLETED: "✓",
  FAILED:    "✗",
  CANCELLED: "⊘",
};

const STATUS_COLOR: Record<SubagentJobView["status"], string> = {
  RUNNING:   "cyan",
  COMPLETED: "green",
  FAILED:    "red",
  CANCELLED: "gray",
};

export function SubagentPanel({ state, now = Date.now(), width, onCancel, onInsert, onView }: SubagentPanelProps) {
  const ids = subagentJobIdsInOrder(state.subagentJobs);
  if (ids.length === 0) {
    return (
      <Box flexDirection="column" width={width} borderStyle="single" borderColor="gray" paddingX={1}>
        <Text dimColor>Subagents</Text>
        <Text dimColor>{`  (no background subagents yet — ask the model to run one in the background)`}</Text>
      </Box>
    );
  }
  return (
    <Box flexDirection="column" width={width} borderStyle="single" borderColor="gray" paddingX={1}>
      <Box>
        <Text bold>Subagents</Text>
        <Text dimColor>{`  (${ids.length} recent — ↑/↓ select, Enter insert, c cancel, Esc close)`}</Text>
      </Box>
      {ids.map((id) => {
        const j = state.subagentJobs[id];
        const focused = state.subagentPanelFocus === id;
        const row = formatSubagentPanelRow(j, now);
        const icon = STATUS_ICON[j.status];
        const color = STATUS_COLOR[j.status];
        const focusedStyle = focused ? "inverse" : undefined;
        // surface the streaming partial result
        // for RUNNING rows that have one. Render the
        // last line (or first N chars) on a second
        // visual row, dimmed, so the user sees the
        // worker is actually making progress without
        // waiting for the terminal event. The preview
        // is only shown for the focused row to keep
        // the panel quiet when nothing is selected.
        const partial = j.status === "RUNNING" && j.partialResult
          ? lastLine(j.partialResult, Math.max(20, width - 12))
          : null;
        return (
          <Box key={id} flexDirection="column">
            <Box>
              <Text inverse={focused} color={focused ? "black" : color}>{focused ? "▶" : " "} {icon} </Text>
              <Text {...(focusedStyle ? { inverse: true } : {})}>{truncate(row, width - 8)}</Text>
              {/* T-444: show the model on the same row
                  when the focused job has one. We render
                  a tiny `· model:provider/name` badge
                  dimmed so the user can tell which
                  model produced the partial / final
                  result. Fits inline as long as the
                  panel is wide enough. */}
              {focused && j.model ? (
                <Text dimColor>  · {truncate(j.model, Math.max(8, width - row.length - 14))}</Text>
              ) : null}
              {focused && j.status === "RUNNING" ? (
                <Text dimColor>  [c cancel]</Text>
              ) : null}
              {focused && (j.status === "RUNNING" || j.status === "COMPLETED" || j.status === "FAILED" || j.status === "CANCELLED") ? (
                // 'v' switches the active transcript
                // to this subagent. Mirrors the desktop
                // SubagentPanel's "View subagent →" link.
                <Text dimColor>  [v view]</Text>
              ) : null}
              {focused && j.status === "COMPLETED" ? (
                <Text dimColor>  [⏎ insert]</Text>
              ) : null}
              {focused && j.status === "FAILED" ? (
                <Text dimColor>  [⏎ insert error]</Text>
              ) : null}
              {focused && j.status === "CANCELLED" ? (
                <Text dimColor>  [⏎ insert note]</Text>
              ) : null}
            </Box>
            {partial && focused ? (
              <Text dimColor>{`   ↳ ${partial}`}</Text>
            ) : null}
            {/* T-444: show the error inline on FAILED
                rows. The deepagents-code SubagentRecord
                keeps the error string; the panel surfaces
                the first 80 chars dimmed so the user can
                read WHY the worker died without scrolling. */}
            {focused && j.status === "FAILED" && j.error ? (
              <Text color="red">{`   ! ${truncate(j.error, Math.max(20, width - 8))}`}</Text>
            ) : null}
          </Box>
        );
      })}
      {/* Hidden hint to the App's useInput handler:
          the actual key wiring lives in tui.tsx so the
          panel stays a presentational component. The
          `onCancel` / `onInsert` callbacks are the
          integration seam. */}
      {state.subagentPanelFocus ? (
        <Text dimColor>{`focus: ${state.subagentPanelFocus}`}</Text>
      ) : null}
    </Box>
  );
}

function truncate(s: string, max: number): string {
  if (s.length <= max) return s;
  if (max <= 1) return s.slice(0, max);
  return s.slice(0, max - 1) + "…";
}

/** extract the last non-empty line of {@code s},
 *  truncated to {@code max} characters. Used for the
 *  partial-result preview so the user sees the most
 *  recent line of the streaming output rather than a
 *  arbitrary window that starts in the middle of a
 *  line. */
function lastLine(s: string, max: number): string {
  if (s == null) return "";
  const lines = s.split("\n");
  for (let i = lines.length - 1; i >= 0; i--) {
    const t = lines[i].trim();
    if (t.length > 0) return truncate(t, max);
  }
  return truncate(s, max);
}
