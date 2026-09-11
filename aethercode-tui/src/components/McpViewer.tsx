/**
 * T-428 (Phase 5 R4): McpViewer.
 *
 * 1:1 port of deepagents-code's `McpViewerScreen` (Java port at
 * `McpViewerScreen.java`). Read-only MCP server + tool viewer
 * modal. Lists every connected MCP server and its tools, with
 * reconnect + sign-in affordances per server.
 *
 * design.md §5.2.3: "Wire: mcp/listServers, mcp/login,
 * mcp/refresh, mcp/listTools."
 *
 * The component is prop-driven. The host fetches the server list
 * (via `mcp/listServers`) and supplies it as `servers`. Enter on
 * a row fires `onSelect`; Ctrl+R fires `onReconnect`; Esc closes
 * via `onClose`.
 */

import React, { useEffect, useMemo, useState } from "react";
import { Box, Text, useInput } from "ink";

/** Server status. Mirrors the Java enum. */
export type ServerStatus =
  | "ok"
  | "unauthenticated"
  | "awaiting-reconnect"
  | "error"
  | "disabled";

/** A tool's public info. */
export interface ToolInfo {
  name: string;
  description?: string;
}

/** A server's public info. */
export interface ServerInfo {
  name: string;
  status: ServerStatus;
  tools: ReadonlyArray<ToolInfo>;
}

/** Dismissal value: a server name, a reconnect sentinel, or
 *  `null` for cancel. Mirrors the Java sealed interface. */
export type DismissValue =
  | { kind: "server"; name: string }
  | { kind: "reconnect" }
  | { kind: "cancel" };

export const RECONNECT_KEY = "ctrl+r";
export const RECONNECT_KEY_LABEL = "Ctrl+R";

export interface McpViewerProps {
  /** All known MCP servers, typically from `mcp/listServers`. */
  servers: ReadonlyArray<ServerInfo>;
  /** Called when the user picks a server (Enter on a row). */
  onSelect: (name: string) => void;
  /** Called when the user presses Ctrl+R to reconnect. */
  onReconnect: () => void;
  /** Called when the user dismisses the viewer. */
  onClose: () => void;
  /** Optional title override. */
  title?: string;
  /** Cap visible rows. Default 9. */
  maxVisible?: number;
}

/** Map a status onto a glyph character. */
export function statusGlyph(status: ServerStatus): string {
  switch (status) {
    case "ok": return "✓";
    case "unauthenticated": return "⚠";
    case "awaiting-reconnect": return "○";
    case "disabled": return "⏸";
    case "error": return "✗";
  }
}

/** Map a status onto a colour name understood by ink. */
export function statusColor(status: ServerStatus): string {
  switch (status) {
    case "ok": return "green";
    case "unauthenticated": return "yellow";
    case "error": return "red";
    case "awaiting-reconnect": return "gray";
    case "disabled": return "gray";
  }
}

export const McpViewer: React.FC<McpViewerProps> = ({
  servers,
  onSelect,
  onReconnect,
  onClose,
  title = "MCP Servers",
  maxVisible = 9,
}) => {
  const [highlight, setHighlight] = useState(0);

  // Clamp highlight when the server list shrinks.
  useEffect(() => {
    if (highlight >= servers.length) {
      setHighlight(Math.max(0, servers.length - 1));
    }
  }, [servers.length, highlight]);

  useInput((input, key) => {
    if (key.escape) {
      onClose();
      return;
    }
    if (key.upArrow || input === "k") {
      setHighlight((h) => (h - 1 + Math.max(1, servers.length)) % Math.max(1, servers.length));
      return;
    }
    if (key.downArrow || input === "j") {
      setHighlight((h) => (h + 1) % Math.max(1, servers.length));
      return;
    }
    if (key.return) {
      const s = servers[highlight];
      if (s) onSelect(s.name);
      return;
    }
    if (key.ctrl && input === "r") {
      onReconnect();
      return;
    }
  });

  // Scroll window — keep the highlighted row visible.
  const start = useMemo(() => {
    if (servers.length <= maxVisible) return 0;
    return Math.max(0, Math.min(highlight - Math.floor(maxVisible / 2), servers.length - maxVisible));
  }, [highlight, servers.length, maxVisible]);
  const end = Math.min(servers.length, start + maxVisible);
  const visible = servers.slice(start, end);

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
        <Text dimColor>  ↑/↓ (j/k): navigate  Enter: sign in  {RECONNECT_KEY_LABEL}: reconnect  Esc: close</Text>
      </Box>
      <Text> </Text>
      {servers.length === 0 ? (
        <Text dimColor>  no MCP servers connected</Text>
      ) : (
        visible.map((s, i) => {
          const realIndex = start + i;
          const isHighlighted = realIndex === highlight;
          const glyph = statusGlyph(s.status);
          const color = statusColor(s.status);
          return (
            <Box key={s.name} flexDirection="column">
              <Box flexDirection="row">
                <Text color={isHighlighted ? "cyan" : undefined}>
                  {isHighlighted ? "▶" : " "}{" "}
                </Text>
                <Text color={color}>{glyph}</Text>
                <Text>  {s.name}</Text>
                <Text dimColor>  ({s.status})</Text>
              </Box>
              {s.tools.map((t) => (
                <Text key={t.name} dimColor>     - {t.name}{t.description ? `: ${truncate(t.description, 50)}` : ""}</Text>
              ))}
            </Box>
          );
        })
      )}
      {servers.length > maxVisible ? (
        <Text dimColor>  {start + 1}–{end} of {servers.length}</Text>
      ) : null}
    </Box>
  );
};

function truncate(s: string, n: number): string {
  if (s.length <= n) return s;
  return s.slice(0, n - 1) + "…";
}

/** Pure helper: build the dismiss value for a server pick. */
export function dismissServer(name: string): DismissValue {
  return { kind: "server", name };
}

/** Pure helper: build the dismiss value for the reconnect key. */
export function dismissReconnect(): DismissValue {
  return { kind: "reconnect" };
}

/** Pure helper: build the dismiss value for a cancel. */
export function dismissCancel(): DismissValue {
  return { kind: "cancel" };
}
