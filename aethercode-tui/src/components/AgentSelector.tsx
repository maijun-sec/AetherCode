/**
 * T-420 (Phase 5 R3): AgentSelector.
 *
 * 1:1 port of deepagents-code's `AgentSelectorScreen` (Java port at
 * `AgentSelectorScreen.java`). Lists every agent the host
 * registered, lets the user type to filter by name, marks the
 * active + default agents, and dispatches `agent/setActive` RPC on
 * Enter.
 *
 * design.md §5.2.1: "lists all available agents, includes a
 * description column, type-to-filter, current + default markers,
 * Ctrl+S to set as default."
 *
 * spec.md §3.10: "the agent picker drives a per-session reload —
 * switching restarts the agent and starts a new thread."
 *
 * The component is prop-driven. The host fetches the agent list
 * (via `agent/list` RPC) and supplies it as `agents`. The
 * component then renders the modal and bubbles the user's pick
 * back to the host. The host is the one that knows how to
 * actually wire `agent/setActive` to the daemon.
 */

import React, { useEffect, useMemo, useState } from "react";
import { Box, Text, useInput } from "ink";
import TextInput from "ink-text-input";

/** A single row in the picker. Mirrors the wire shape of
 *  `agent/list`'s result, but is local to the TUI so the
 *  component is decoupled from the daemon. */
export interface AgentEntry {
  /** Stable id (kebab-case). */
  name: string;
  /** Optional human-readable description (one-line). */
  description?: string;
  /** Whether this is the user's persisted default. */
  isDefault?: boolean;
}

export interface AgentSelectorProps {
  /** All known agents, typically from `agent/list`. */
  agents: ReadonlyArray<AgentEntry>;
  /** The currently active agent name. Marked with (current). */
  current: string | null;
  /** Optional callback when the user picks an agent. */
  onSelect: (name: string) => void;
  /** Optional callback when the user dismisses the picker. */
  onClose: () => void;
  /** Optional callback when the user wants to set the highlighted
   *  agent as their new persisted default (Ctrl+S). The host is
   *  the one that wires the persistence (agent/setDefault RPC). */
  onSetDefault?: (name: string) => void;
  /** Optional title override. */
  title?: string;
  /** Cap visible rows. Default 9. */
  maxVisible?: number;
}

export const AgentSelector: React.FC<AgentSelectorProps> = ({
  agents,
  current,
  onSelect,
  onClose,
  onSetDefault,
  title = "Select Agent",
  maxVisible = 9,
}) => {
  // Filter — type-to-narrow the catalog. Empty query shows all.
  const [query, setQuery] = useState("");
  const filtered = useMemo(() => {
    if (!query.trim()) return agents;
    const q = query.toLowerCase();
    return agents.filter((a) => a.name.toLowerCase().includes(q));
  }, [agents, query]);

  // Highlight starts on the active agent if present, else 0.
  const initial = useMemo(() => {
    if (!current) return 0;
    const idx = filtered.findIndex((a) => a.name === current);
    return idx >= 0 ? idx : 0;
  }, [filtered, current]);
  const [highlight, setHighlight] = useState(initial);

  // Clamp highlight when the filtered list shrinks.
  useEffect(() => {
    if (highlight >= filtered.length) {
      setHighlight(Math.max(0, filtered.length - 1));
    }
  }, [filtered.length, highlight]);

  useInput((input, key) => {
    if (key.escape) {
      onClose();
      return;
    }
    if (key.return) {
      const a = filtered[highlight];
      if (a) onSelect(a.name);
      onClose();
      return;
    }
    if (key.upArrow) {
      setHighlight((h) => (h - 1 + filtered.length) % Math.max(1, filtered.length));
      return;
    }
    if (key.downArrow) {
      setHighlight((h) => (h + 1) % Math.max(1, filtered.length));
      return;
    }
    if (input === "j") {
      setHighlight((h) => (h + 1) % Math.max(1, filtered.length));
      return;
    }
    if (input === "k") {
      setHighlight((h) => (h - 1 + filtered.length) % Math.max(1, filtered.length));
      return;
    }
    if (key.ctrl && input === "s") {
      const a = filtered[highlight];
      if (a && onSetDefault) onSetDefault(a.name);
      return;
    }
  });

  // Scroll window — keep the highlighted row visible.
  const start = Math.max(0, Math.min(highlight - Math.floor(maxVisible / 2), Math.max(0, filtered.length - maxVisible)));
  const end = Math.min(filtered.length, start + maxVisible);
  const visible = filtered.slice(start, end);

  return (
    <Box
      flexDirection="column"
      borderStyle="double"
      borderColor="cyan"
      paddingX={2}
      paddingY={1}
    >
      <Box>
        <Text color="cyan" bold>◆ {title}</Text>
        <Text dimColor>  type to filter  ↑/↓ (j/k): navigate  Enter: select  Ctrl+S: set default  Esc: close</Text>
      </Box>
      <Text> </Text>
      <Box>
        <Text color="yellow">{">"}</Text>
        <Text> </Text>
        <TextInput
          value={query}
          onChange={setQuery}
          placeholder="filter agents…"
        />
      </Box>
      <Text> </Text>
      {agents.length === 0 ? (
        <Text dimColor>  no agents found</Text>
      ) : filtered.length === 0 ? (
        <Text dimColor>  no matches for "{query}"</Text>
      ) : (
        visible.map((a, i) => {
          const realIndex = start + i;
          const isHighlighted = realIndex === highlight;
          const isCurrent = a.name === current;
          return (
            <Box key={a.name} flexDirection="row">
              <Text color={isHighlighted ? "cyan" : undefined}>
                {isHighlighted ? "▶" : " "}{" "}
              </Text>
              <Text color={isCurrent ? "yellowBright" : undefined}>
                {isCurrent ? "●" : " "}{" "}
              </Text>
              <Text>
                <Text bold={isHighlighted}>{a.name.padEnd(20)}</Text>
                {a.isDefault ? <Text color="magenta"> (default)</Text> : null}
                {isCurrent ? <Text color="yellowBright"> (current)</Text> : null}
                {a.description ? <Text dimColor>  — {truncate(a.description, 60)}</Text> : null}
              </Text>
            </Box>
          );
        })
      )}
      {filtered.length > maxVisible ? (
        <Text dimColor>  {start + 1}–{end} of {filtered.length}</Text>
      ) : null}
    </Box>
  );
};

function truncate(s: string, n: number): string {
  if (s.length <= n) return s;
  return s.slice(0, n - 1) + "…";
}

/** Pure helper: format the row label for an agent — used by tests
 *  and any future call sites that need the same string in another
 *  surface. Mirrors `AgentSelectorScreen.formatLabel` in
 *  deepagents-code. */
export function formatAgentLabel(
  name: string,
  current: string | null,
  defaultAgent: string | null,
): string {
  const isCurrent = name === current;
  const isDefault = name === defaultAgent;
  if (isCurrent && isDefault) return `${name} (current, default)`;
  if (isCurrent) return `${name} (current)`;
  if (isDefault) return `${name} (default)`;
  return name;
}
