/**
 * side panel.
 *
 * Optional left rail showing:
 *   - current project (cwd)
 *   - recent tasks (from listTasks RPC)
 *   - recent queries (from local history)
 *
 * Toggle with Ctrl+B. When hidden, the panel takes 0 columns and
 * the rest of the layout stretches to fill the terminal. The
 * panel never scrolls; it just truncates the most recent items
 * to fit the available height.
 *
 * Design notes:
 *   - The panel is a "summary at a glance", not a control surface.
 *     Clicking an item in the panel does NOT navigate to it (no
 *     click support in this round). It's purely informational.
 *   - The panel updates when:
 *     (a) the daemon emits a task_state notification (new task),
 *     (b) the user submits a new query (added to history),
 *     (c) every 30s we re-fetch the task list.
 *   - The current project is always the cwd the daemon was started
 *     in. The listProjects RPC may return more, but for now we
 *     only show the active one.
 *
 * The panel is intentionally dim. It should not compete with the
 * scrollback for attention. Color is reserved for the *current*
 * item and the count badges.
 */

import React from "react";
import { Box, Text } from "ink";
import { t, icon } from "../theme.js";

export interface SidebarTask {
  id: string;
  name: string;
  status: string;
  ts: number;
}

export interface SidebarProps {
  width: number;                    // R37: panel width in columns (default 26)
  cwd: string;                      // current working directory
  sessionId: string;                // current session id
  recentQueries: string[];          // last N user prompts (from state.history)
  tasks: SidebarTask[];             // recent tasks (from listTasks RPC)
  /** time-to-live for the "now" badge. The user can ignore
   *  this — it's a small detail. */
  now?: number;
}

const STATUS_ICON: Record<string, string> = {
  pending:   "○",
  running:   "◐",
  done:      "✓",
  ok:        "✓",
  completed: "✓",
  error:     "✗",
  failed:    "✗",
  cancelled: "⊘",
  paused:    "‖",
};

const STATUS_COLOR: Record<string, string> = {
  pending:   t.dim,
  running:   t.warn,
  done:      t.ok,
  ok:        t.ok,
  completed: t.ok,
  error:     t.err,
  failed:    t.err,
  cancelled: t.dim,
  paused:    t.dim,
};

export const Sidebar: React.FC<SidebarProps> = ({
  width, cwd, sessionId, recentQueries, tasks, now = Date.now(),
}) => {
  // Cap each section so the panel doesn't grow beyond the terminal.
  const maxTasks = 5;
  const maxQueries = 5;
  const shortCwd = shortenPath(cwd, width - 2);
  const shortSession = sessionId === "—" ? "—" : sessionId.slice(0, 8);

  return (
    <Box
      flexDirection="column"
      width={width}
      borderStyle="single"
      borderColor={t.dim}
      paddingX={1}
    >
      {/* Header: panel title */}
      <Box>
        <Text color={t.brand} bold>{"◰"} panel</Text>
      </Box>
      <Text dimColor>{"─".repeat(Math.max(4, width - 2))}</Text>

      {/* Section: project */}
      <Section label="project" count={1}>
        <Box flexDirection="column">
          <Text>
            <Text color={t.brand}>{icon.path} </Text>
            <Text color={t.asst}>{shortCwd}</Text>
          </Text>
          <Text>
            <Text dimColor>  session </Text>
            <Text color={t.ok}>{shortSession}</Text>
          </Text>
        </Box>
      </Section>

      <Text dimColor> </Text>

      {/* Section: recent tasks */}
      <Section label="tasks" count={tasks.length}>
        {tasks.length === 0 ? (
          <Text dimColor>  (none yet)</Text>
        ) : (
          <Box flexDirection="column">
            {tasks.slice(0, maxTasks).map((task) => {
              const sym = STATUS_ICON[task.status] ?? icon.ring;
              const c = STATUS_COLOR[task.status] ?? t.dim;
              const age = formatRelative(task.ts, now);
              return (
                <Text key={task.id}>
                  <Text color={c}>{sym} </Text>
                  <Text color={t.asst}>{truncate(task.name, width - 12)}</Text>
                  <Text dimColor>  {age}</Text>
                </Text>
              );
            })}
            {tasks.length > maxTasks ? (
              <Text dimColor>  +{tasks.length - maxTasks} more…</Text>
            ) : null}
          </Box>
        )}
      </Section>

      <Text dimColor> </Text>

      {/* Section: recent queries (from state.history) */}
      <Section label="queries" count={recentQueries.length}>
        {recentQueries.length === 0 ? (
          <Text dimColor>  (none yet)</Text>
        ) : (
          <Box flexDirection="column">
            {recentQueries.slice(-maxQueries).reverse().map((q, i) => (
              <Text key={i}>
                <Text color={t.accent}>{icon.arrow} </Text>
                <Text color={t.asst}>{truncate(q, width - 4)}</Text>
              </Text>
            ))}
          </Box>
        )}
      </Section>

      <Box flexGrow={1} />
      <Text dimColor> </Text>
      <Text dimColor>Ctrl+B hide</Text>
    </Box>
  );
};

const Section: React.FC<{ label: string; count: number; children: React.ReactNode }> = ({ label, count, children }) => (
  <Box flexDirection="column" marginY={0}>
    <Text>
      <Text color={t.accent} bold>{label}</Text>
      <Text dimColor> ({count})</Text>
    </Text>
    {children}
  </Box>
);

function truncate(s: string, n: number): string {
  if (s.length <= n) return s;
  return s.slice(0, Math.max(0, n - 1)) + "…";
}

function shortenPath(p: string, maxLen: number): string {
  if (!p) return "—";
  if (p.length <= maxLen) return p;
  const parts = p.split(/[\\/]/);
  if (parts.length <= 2) return p;
  const tail = parts.slice(-2).join("/");
  return ".../" + tail;
}

function formatRelative(ts: number, now: number): string {
  const diff = Math.max(0, now - ts);
  if (diff < 1000) return "now";
  if (diff < 60_000) return `${Math.floor(diff / 1000)}s`;
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m`;
  return `${Math.floor(diff / 3_600_000)}h`;
}
