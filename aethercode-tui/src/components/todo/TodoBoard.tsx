/**
 * T-6-07: `TodoBoard` (live TODO list, j/k nav).
 *
 * Per spec §6, the TODO board is a live list of the
 * LLM's current plan. Each row is a `TodoItem` with a
 * checkbox + status pill. The board subscribes to
 * `task/events` filtered by `kind=todo_update` and
 * re-renders on every change.
 *
 * Keyboard:
 *   - `j` / `↓` — next item
 *   - `k` / `↑` — prev item
 *   - `Enter`   — expand the focused item (show the
 *                 detail line; `TodoItem` accepts a
 *                 `selected` prop for the styling).
 *   - `g`       — jump to top
 *   - `G`       — jump to bottom
 *
 * The component is "headless" about transport: it
 * accepts the `todos` array. The TUI wires it to
 * `useRpcSubscription(sessionId, { kind: "todo_update" })`
 * — but to keep the test surface small, the TUI can also
 * pass `todos` directly. Tests do the latter.
 *
 * Pure helpers (exported for tests):
 *   - `sortTodos(items)` — order: in_progress first,
 *     pending second, completed/cancelled last.
 *   - `summaryOf(items)` — produces a `TodoSummary` for
 *     the SessionDetailsPanel.
 *   - `clampFocus(focus, len)` — keeps the focus index
 *     in `[0, len - 1]`.
 */

import React, { useState, useEffect } from "react";
import { Box, Text, useInput } from "ink";
import { t, icon } from "../../theme.js";
import { TodoItem, type TodoItemData, type TodoStatus } from "./TodoItem.js";

export type { TodoItemData, TodoStatus } from "./TodoItem.js";
export { todoIcon, todoColor, statusPill } from "./TodoItem.js";

export interface TodoSummary {
  total: number;
  pending: number;
  inProgress: number;
  completed: number;
  cancelled: number;
}

export interface TodoBoardProps {
  todos: TodoItemData[];
  /** Optional "selected" id — a TODO the user has
   *  expanded to see its detail. */
  selectedId?: string | null;
  onSelect?: (id: string | null) => void;
  /** Cap the visible rows. Default 12. */
  maxRows?: number;
  /** Render the empty state. Default shows "(no todos yet)". */
  emptyLabel?: string;
  showHeader?: boolean;
  /** id of the TODO the engine is currently working
   *  on (status=in_progress, first by startedAt). The board
   *  draws a "▶ CURRENT" marker on this row so the user
   *  can see "which step is currently executing" at a glance. */
  currentTodoId?: string | null;
  /** width of the progress bar in characters. Default
   *  16 — fits inside the right column without wrapping. */
  progressBarWidth?: number;
}

/** Pure helper: order the TODOs. In-progress first,
 *  pending second, completed third, cancelled last. */
export function sortTodos(items: ReadonlyArray<TodoItemData>): TodoItemData[] {
  const rank: Record<TodoStatus, number> = {
    in_progress: 0,
    pending: 1,
    completed: 2,
    cancelled: 3,
  };
  return items.slice().sort((a, b) => {
    const ra = rank[a.status] ?? 99;
    const rb = rank[b.status] ?? 99;
    if (ra !== rb) return ra - rb;
    // Stable tiebreaker: id (lexicographic).
    return a.id < b.id ? -1 : a.id > b.id ? 1 : 0;
  });
}

/** Pure helper: count TODOs by status. */
export function summaryOf(items: ReadonlyArray<TodoItemData>): TodoSummary {
  const out: TodoSummary = { total: 0, pending: 0, inProgress: 0, completed: 0, cancelled: 0 };
  for (const it of items) {
    out.total += 1;
    if (it.status === "pending") out.pending += 1;
    else if (it.status === "in_progress") out.inProgress += 1;
    else if (it.status === "completed") out.completed += 1;
    else if (it.status === "cancelled") out.cancelled += 1;
  }
  return out;
}

// progress helpers. The user explicitly asked for
// "TODO list 怎么展示（包括进度等，which step is currently executing）" — we
// surface both the bar (overall %) and the in-progress
// highlight so "which step is currently executing" is at a glance.

/** Pure helper: percent of TODO items that are completed
 *  (vs total minus cancelled). Returns 0 when there are no
 *  actionable items. The cancelled bucket is excluded so a
 *  TODO the user dismissed doesn't pull the bar backwards. */
export function progressPercent(items: ReadonlyArray<TodoItemData>): number {
  if (items.length === 0) return 0;
  const actionable = items.filter((t) => t.status !== "cancelled").length;
  if (actionable === 0) return 0;
  const done = items.filter((t) => t.status === "completed").length;
  return Math.round((100 * done) / actionable);
}

/** Pure helper: build a fixed-width ASCII progress bar.
 *  {@code width} is the total character count (filled + empty);
 *  the function returns a string like {@code "[████░░░░] 40%"}.
 *  Pure — no terminal codes. */
export function progressBar(percent: number, width: number): string {
  const w = Math.max(4, width);
  const pct = Math.max(0, Math.min(100, percent));
  const filled = Math.round((pct / 100) * w);
  const empty = w - filled;
  return "[" + "█".repeat(filled) + "░".repeat(empty) + "] " + pct + "%";
}

/** Pure helper: clamp a focus index into `[0, len-1]`. */
export function clampFocus(focus: number, len: number): number {
  if (len <= 0) return 0;
  if (focus < 0) return 0;
  if (focus >= len) return len - 1;
  return focus;
}

export const TodoBoard: React.FC<TodoBoardProps> = ({
  todos,
  selectedId,
  onSelect,
  maxRows = 12,
  emptyLabel = "(no todos yet)",
  showHeader = true,
  currentTodoId = null,
  progressBarWidth = 16,
}) => {
  const sorted = React.useMemo(() => sortTodos(todos), [todos]);
  const [focus, setFocus] = useState(0);

  // Clamp the focus when the list shrinks.
  useEffect(() => {
    setFocus((f) => clampFocus(f, sorted.length));
  }, [sorted.length]);

  useInput((input, key) => {
    if (sorted.length === 0) return;
    if (input === "j" || key.downArrow) {
      setFocus((f) => clampFocus(f + 1, sorted.length));
      return;
    }
    if (input === "k" || key.upArrow) {
      setFocus((f) => clampFocus(f - 1, sorted.length));
      return;
    }
    if (input === "g") {
      setFocus(0);
      return;
    }
    if (input === "G") {
      setFocus(clampFocus(sorted.length - 1, sorted.length));
      return;
    }
    if (key.return) {
      const cur = sorted[focus];
      if (!cur) return;
      onSelect?.(selectedId === cur.id ? null : cur.id);
      return;
    }
  });

  const visible = sorted.slice(0, maxRows);
  const overflow = sorted.length - visible.length;
  // surface "which step is currently executing" — the user picks the
  // currentTodoId from the engine; we mark the matching row
  // with a ▶ CURRENT prefix in addition to the j/k focus
  // cursor. The two are independent: focus is "what I'm
  // looking at", currentTodoId is "what the engine is doing".
  const currentRow = currentTodoId
    ? sorted.findIndex((t) => t.id === currentTodoId)
    : -1;

  return (
    <Box flexDirection="column" paddingX={1}>
      {showHeader ? (
        <>
          <Box flexDirection="row" justifyContent="space-between">
            <Text color={t.brand} bold>todo board</Text>
            <Text dimColor>  {todos.length} item{todos.length === 1 ? "" : "s"}</Text>
          </Box>
          <Text dimColor>{"─".repeat(20)}</Text>
          {/* progress bar. The bar uses the same "done
              vs actionable" metric as the desktop TodoBoard
              so the two surfaces show the same percentage. */}
          {todos.length > 0 ? (
            <Text>{progressBar(progressPercent(todos), progressBarWidth)}</Text>
          ) : null}
        </>
      ) : null}
      {sorted.length === 0 ? (
        <Text dimColor>  {emptyLabel}</Text>
      ) : (
        <Box flexDirection="column">
          {visible.map((it, i) => {
            const isCurrent = currentRow >= 0 && i === currentRow;
            return (
              <Box key={it.id} flexDirection="column">
                {isCurrent ? (
                  <Text color="cyan" bold>  ▶ CURRENT</Text>
                ) : null}
                <TodoItem
                  item={it}
                  focused={i === focus}
                  selected={selectedId === it.id}
                  width={48}
                />
              </Box>
            );
          })}
          {overflow > 0 ? (
            <Text dimColor>  {icon.arrow} {overflow} more…</Text>
          ) : null}
        </Box>
      )}
    </Box>
  );
};

export default TodoBoard;
