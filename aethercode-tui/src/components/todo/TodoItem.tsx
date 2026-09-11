/**
 * T-6-08: `TodoItem` (single TODO card).
 *
 * A single line in the `TodoBoard` (T-6-07). The card
 * shows:
 *   - the checkbox (☐ / ◐ / ☑ / ⊘)
 *   - the title (truncated to `width`)
 *   - the status pill (color-coded)
 *   - the "who is working on it" badge (parent /
 *     subagent) — optional
 *   - the started_at / completed_at timestamps
 *     (relative; only shown when set)
 *
 * Pure helper functions:
 *   - `todoIcon(status)` returns the checkbox glyph.
 *   - `todoColor(status)` returns the colour tier.
 *   - `truncate(s, n)` truncates with an ellipsis.
 *
 * The component is "headless" about the TODO source.
 * `TodoBoard` constructs a `TodoItem` for each row.
 */

import React from "react";
import { Box, Text } from "ink";
import { t, formatRelative } from "../../theme.js";

export type TodoStatus = "pending" | "in_progress" | "completed" | "cancelled";

export interface TodoItemData {
  id: string;
  title: string;
  status: TodoStatus;
  /** Who is working on it: "parent" (the main session)
   *  or "subagent:<id>" (a delegated worker). */
  owner?: string | null;
  startedAt?: number | null;
  completedAt?: number | null;
  /** A short description / detail. The board shows the
   *  first line; the full detail is shown on expand
   *  (in this round the board doesn't yet have an
   *  expand mode — that's T-7's scope). */
  detail?: string | null;
}

export interface TodoItemProps {
  item: TodoItemData;
  /** Render the row as the focused/active one. */
  focused?: boolean;
  /** Render the row as the selected one (a different
   *  style from focused — typically the user pressed
   *  Enter to "select" the TODO for detail view). */
  selected?: boolean;
  /** Truncate the title to N chars. Default 60. */
  width?: number;
  /** Wall-clock for relative time formatting. Tests
   *  inject a fixed value. */
  now?: number;
}

/** Map a status to a checkbox glyph. Pure helper. */
export function todoIcon(status: TodoStatus): string {
  switch (status) {
    case "pending":     return "☐";
    case "in_progress": return "◐";
    case "completed":   return "☑";
    case "cancelled":   return "⊘";
    default:            return "·";
  }
}

/** Map a status to a colour tier. Pure helper. */
export function todoColor(status: TodoStatus): string {
  switch (status) {
    case "pending":     return t.dim;
    case "in_progress": return t.warn;
    case "completed":   return t.ok;
    case "cancelled":   return t.dim;
    default:            return t.dim;
  }
}

/** Truncate a string with an ellipsis. */
export function truncate(s: string, n: number): string {
  if (!s) return "";
  if (s.length <= n) return s;
  return s.slice(0, Math.max(0, n - 1)) + "…";
}

/** A short status pill (e.g. "in progress", "done"). */
export function statusPill(status: TodoStatus): string {
  switch (status) {
    case "pending":     return "pending";
    case "in_progress": return "in progress";
    case "completed":   return "done";
    case "cancelled":   return "cancelled";
    default:            return status;
  }
}

/** Format the "owner" badge. Pure helper. */
export function ownerLabel(owner: string | null | undefined): string {
  if (!owner) return "";
  if (owner === "parent") return "self";
  if (owner.startsWith("subagent:")) return owner.slice("subagent:".length);
  return owner;
}

export const TodoItem: React.FC<TodoItemProps> = ({
  item,
  focused = false,
  selected = false,
  width = 60,
  now = Date.now(),
}) => {
  const color = todoColor(item.status);
  const icon = todoIcon(item.status);
  const title = truncate(item.title, width);
  const isDone = item.status === "completed" || item.status === "cancelled";

  return (
    <Box flexDirection="row" alignItems="center">
      {focused ? <Text color="cyan">▶ </Text> : <Text>  </Text>}
      <Text color={color}>{icon} </Text>
      <Text
        color={focused ? "cyan" : color}
        strikethrough={item.status === "cancelled"}
        dimColor={item.status === "cancelled"}
        bold={selected}
      >
        {title}
      </Text>
      {item.owner ? (
        <Text dimColor>  · {ownerLabel(item.owner)}</Text>
      ) : null}
      {item.completedAt ? (
        <Text dimColor>  · done {formatRelative(now - item.completedAt, 0)} ago</Text>
      ) : item.startedAt ? (
        <Text dimColor>  · {formatRelative(now - item.startedAt, 0)}</Text>
      ) : null}
      {isDone ? null : (
        <Text color={color} dimColor>  [{statusPill(item.status)}]</Text>
      )}
    </Box>
  );
};

export default TodoItem;
