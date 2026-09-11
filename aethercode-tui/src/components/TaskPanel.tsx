// R321 (T-370..T-373 / §4.7 design.md): the long-running
// task panel. Lists every supervised child (the supervisor
// is the owner; we call `task/list` to enumerate), lets the
// user attach / kill / resume / retry with the keyboard, and
// shows a live ticker for the focused child by polling
// `task/events` since the last seen event id.
//
// Visual model (mirrors the SubagentPanel):
//   ┌─ Tasks (N) ─────────────────────┐
//   │ ▶ ▶ running    explain the diff  │
//   │ ‖ paused       summarise README  │
//   │ ✓ completed    refactor utils.ts │
//   │ ✗ failed       port layer        │
//   └──────────────────────────────────┘
//
// Wiring (the App holds the orchestrator):
//   - on mount / every 5s: refresh the children list via
//     the supplied `onRefresh` (which the App wires to
//     `task/list`).
//   - when a row is focused: poll `task/events` for that
//     child every 1s and pipe the new event into `onEvent`
//     so the App can update the focused row's preview.
//   - row actions (a/k/r/x/c): emit onAttach / onKill /
//     onResume / onRetry. The App maps these to the
//     `task/attach`, `task/kill`, `task/resume`, `task/retry`
//     RPCs.
//
// Why a polling fallback? The TUI's JSON-RPC client (see
// src/jsonrpc.ts) is line-delimited and request/response;
// a true SSE channel would need a separate wire. The polling
// pattern is what design.md §4.3 calls the "task/events"
// fallback, and it gives us the same UX for the
// single-line protocol the TUI uses today.

import React, { useEffect, useMemo, useState } from "react";
import { Box, Text, useInput } from "ink";
import { t } from "../theme.js";

/** A single supervised child, mirrors `task/list` shape. */
export interface TaskPanelChild {
  id: string;
  status: "QUEUED" | "RUNNING" | "PAUSED" | "COMPLETED" | "FAILED" | "KILLED";
  prompt?: string;
  cwd?: string;
  createdAt?: number;
  startedAt?: number;
  endedAt?: number;
  /** Last error / kill reason, if any. */
  error?: string;
  /** Optional pre-rendered preview (e.g. last model_message). */
  preview?: string;
}

export interface TaskPanelProps {
  /** Live list of children. The App refreshes this via
   *  `task/list` on a 5s timer. */
  children: TaskPanelChild[];
  /** Currently focused row id. The App moves focus with
   *  ↑/↓ and the panel shows a "→" marker. */
  focusId?: string | null;
  /** When the user presses Enter / 'a' on a row. The App
   *  wires this to `task/attach` (T-362) and then opens
   *  the live ticker. */
  onAttach?: (childId: string) => void;
  /** When the user presses 'k' on a row. Wired to
   *  `task/kill` (T-364). */
  onKill?: (childId: string) => void;
  /** When the user presses 'r' on a PAUSED row. Wired to
   *  `task/resume` (T-366). */
  onResume?: (childId: string) => void;
  /** When the user presses 'x' on a row. Wired to
   *  `task/retry` (T-367). */
  onRetry?: (childId: string) => void;
  /** ↑/↓/j/k navigation — the App can also wire this
   *  itself, but exposing the callbacks here keeps the
   *  useInput handler inside the panel so the rest of
   *  the App's keyboard map is unaffected. */
  onFocusPrev?: () => void;
  onFocusNext?: () => void;
  /** Esc to close the panel. */
  onClose?: () => void;
  /** Width in columns (the right column of the TUI, the
   *  same as SubagentPanel). */
  width: number;
  /** Wall-clock ms so row labels can show a live elapsed
   *  for RUNNING rows without each row building its own
   *  interval. */
  now: number;
  /** Optional loading flag (first list call in flight). */
  loading?: boolean;
  /** Optional error string. */
  error?: string | null;
}

const STATUS_ICON: Record<TaskPanelChild["status"], string> = {
  QUEUED:    "○",
  RUNNING:   "▶",
  PAUSED:    "‖",
  COMPLETED: "✓",
  FAILED:    "✗",
  KILLED:    "⊘",
};

const STATUS_COLOR: Record<TaskPanelChild["status"], string> = {
  QUEUED:    t.dim,
  RUNNING:   t.warn,
  PAUSED:    t.warn,
  COMPLETED: t.ok,
  FAILED:    t.err,
  KILLED:    t.dim,
};

const STATUS_ORDER: Record<TaskPanelChild["status"], number> = {
  RUNNING: 0,
  QUEUED: 1,
  PAUSED: 2,
  FAILED: 3,
  COMPLETED: 4,
  KILLED: 5,
};

/**
 * The task panel. Pure renderer + keyboard handler; the App
 * owns the RPC plumbing. Rendered as a side panel (right
 * column) when the user presses Ctrl+T.
 */
export function TaskPanel(props: TaskPanelProps) {
  const {
    children, focusId,
    onAttach, onKill, onResume, onRetry,
    onFocusPrev, onFocusNext, onClose,
    width, now, loading, error,
  } = props;

  // Stable display order: RUNNING first, then QUEUED, then
  // PAUSED, then by creation time ascending. Terminal
  // children (COMPLETED / FAILED / KILLED) trail in age
  // order. We sort in-place in render so the list is
  // always predictable when the user navigates with ↑/↓.
  const sorted = useMemo(
    () => [...children].sort((a, b) => {
      const sa = STATUS_ORDER[a.status] ?? 99;
      const sb = STATUS_ORDER[b.status] ?? 99;
      if (sa !== sb) return sa - sb;
      return (a.createdAt ?? 0) - (b.createdAt ?? 0);
    }),
    [children],
  );

  // Local focus if the App hasn't picked one yet. We mirror
  // the local state back up via the callbacks so the App
  // can persist the focus across re-renders.
  const [localFocus, setLocalFocus] = useState<string | null>(null);
  const effectiveFocus = focusId ?? localFocus ?? sorted[0]?.id ?? null;

  useInput((input, key) => {
    if (key.escape) {
      if (onClose) onClose();
      return;
    }
    if (key.return) {
      if (effectiveFocus && onAttach) onAttach(effectiveFocus);
      return;
    }
    if (input === "a" && effectiveFocus && onAttach) {
      onAttach(effectiveFocus);
      return;
    }
    if (input === "k" && effectiveFocus && onKill) {
      onKill(effectiveFocus);
      return;
    }
    if (input === "r" && effectiveFocus && onResume) {
      onResume(effectiveFocus);
      return;
    }
    if (input === "x" && effectiveFocus && onRetry) {
      onRetry(effectiveFocus);
      return;
    }
    if (key.upArrow || input === "k") {
      moveFocus(sorted, effectiveFocus, -1, setLocalFocus, onFocusPrev);
      return;
    }
    if (key.downArrow || input === "j") {
      moveFocus(sorted, effectiveFocus, 1, setLocalFocus, onFocusNext);
      return;
    }
  });

  return (
    <Box
      flexDirection="column"
      width={width}
      borderStyle="single"
      borderColor={t.dim}
      paddingX={1}
    >
      <Box>
        <Text bold color={t.brand}>◰ Tasks</Text>
        <Text dimColor>{`  (${sorted.length} — ↑/↓ select, a attach, k kill, r resume, x retry, Esc close)`}</Text>
      </Box>
      {loading ? <Text dimColor>  (loading…)</Text> : null}
      {error ? <Text color={t.err}>{`  error: ${error}`}</Text> : null}
      {!loading && !error && sorted.length === 0 ? (
        <Text dimColor>  (no background tasks yet — start one from the CLI: aethercode task spawn …)</Text>
      ) : null}
      {sorted.map((c) => {
        const focused = c.id === effectiveFocus;
        const icon = STATUS_ICON[c.status] ?? "?";
        const color = STATUS_COLOR[c.status] ?? t.dim;
        const elapsed = formatElapsed(c, now);
        const row = buildRow(c, width - 8, elapsed);
        return (
          <Box key={c.id} flexDirection="column">
            <Box>
              <Text inverse={focused} color={focused ? "black" : color}>
                {focused ? "▶" : " "} {icon}
              </Text>
              <Text {...(focused ? { inverse: true } : {})}>{row}</Text>
              {focused && c.status === "RUNNING" ? (
                <Text dimColor>  [a attach]</Text>
              ) : null}
              {focused && c.status === "PAUSED" ? (
                <Text dimColor>  [r resume]</Text>
              ) : null}
              {focused && c.status !== "KILLED" ? (
                <Text dimColor>  [k kill]</Text>
              ) : null}
              {focused && (c.status === "FAILED" || c.status === "KILLED" || c.status === "COMPLETED") ? (
                <Text dimColor>  [x retry]</Text>
              ) : null}
            </Box>
            {focused && c.preview ? (
              <Text dimColor>{`   ↳ ${truncate(c.preview, Math.max(20, width - 8))}`}</Text>
            ) : null}
            {focused && c.error ? (
              <Text color={t.err}>{`   ↳ error: ${truncate(c.error, Math.max(20, width - 12))}`}</Text>
            ) : null}
          </Box>
        );
      })}
    </Box>
  );
}

function moveFocus(
  sorted: TaskPanelChild[],
  currentId: string | null,
  delta: number,
  setLocal: (id: string | null) => void,
  cb?: () => void,
) {
  if (cb) { cb(); return; }
  if (sorted.length === 0) return;
  const idx = sorted.findIndex((c) => c.id === currentId);
  const next = idx < 0
    ? 0
    : Math.max(0, Math.min(sorted.length - 1, idx + delta));
  setLocal(sorted[next].id);
}

function buildRow(c: TaskPanelChild, maxLen: number, elapsed: string): string {
  const idShort = c.id.length > 10 ? c.id.slice(0, 8) + "…" : c.id;
  const prompt = (c.prompt ?? "").replace(/\s+/g, " ").trim();
  const left = `${idShort} ${elapsed} ${truncate(prompt, Math.max(8, maxLen - 14))}`;
  return truncate(left, maxLen);
}

function truncate(s: string, max: number): string {
  if (s == null) return "";
  if (s.length <= max) return s;
  if (max <= 1) return s.slice(0, max);
  return s.slice(0, max - 1) + "…";
}

function formatElapsed(c: TaskPanelChild, now: number): string {
  // For non-terminal rows we want a live duration. For
  // terminal rows we use endedAt - startedAt (or
  // createdAt fallback). The numbers are kept short so
  // the row label fits in the panel.
  const start = c.startedAt ?? c.createdAt;
  if (!start) return "--";
  if (c.status === "RUNNING" || c.status === "PAUSED" || c.status === "QUEUED") {
    const diff = Math.max(0, now - start);
    return humanDuration(diff);
  }
  const end = c.endedAt ?? now;
  const diff = Math.max(0, end - start);
  return humanDuration(diff);
}

function humanDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  if (ms < 3_600_000) return `${Math.floor(ms / 60_000)}m${Math.floor((ms % 60_000) / 1000)}s`;
  return `${Math.floor(ms / 3_600_000)}h${Math.floor((ms % 3_600_000) / 60_000)}m`;
}
