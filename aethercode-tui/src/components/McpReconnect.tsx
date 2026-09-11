/**
 * T-428 (Phase 5 R4): McpReconnect.
 *
 * 1:1 port of deepagents-code's `McpReconnect.*` (Java port at
 * `McpReconnect.java`). Three confirmation modals:
 *
 *  1. `McpReconnectPromptScreen`        — "✓ Connected to X, reconnect now?"
 *  2. `McpDisableReconnectPromptScreen` — "Apply MCP server changes?"
 *  3. `McpReconnectForceConfirmScreen`  — "Force reconnect?" (no login queued)
 *
 * All three share the same shape: a title, a body, a help line,
 * and Enter / Esc bindings. We model the three as a single
 * `<McpReconnect />` component driven by a `kind` discriminator.
 *
 * design.md §5.2.3: backed by the mcp_providers/ registry.
 *
 * The component is prop-driven — the host supplies the body text
 * and the dismiss callback. The component itself only knows how
 * to render a title + body + help + two keys.
 */

import React from "react";
import { Box, Text, useInput } from "ink";

/** Outcome of a reconnect prompt. */
export type ReconnectChoice = "reconnect" | "later";

/** Force-reconnect cancel result. */
export type ForceConfirmChoice = boolean;

/** Which sub-prompt this is. Drives title + body text. */
export type McpReconnectKind =
  | "post-login"        // ✓ Connected to <name>  →  Reconnect to load new tools.
  | "apply-changes"     // Apply MCP server changes?  →  Reconnect to apply <names>.
  | "force";            // Force reconnect?  →  no login queued.

export interface McpReconnectProps {
  /** Which sub-prompt. */
  kind: McpReconnectKind;
  /** Server name (used by `post-login`). */
  serverName?: string;
  /** Server names (used by `apply-changes`). */
  serverNames?: ReadonlyArray<string>;
  /** Called when the user confirms. */
  onConfirm: () => void;
  /** Called when the user defers / cancels. */
  onCancel: () => void;
  /** Optional title override (otherwise derived from kind). */
  title?: string;
  /** Optional body override. */
  body?: string;
  /** Optional help override. */
  help?: string;
}

/** Derive the default title from the kind. */
function defaultTitle(kind: McpReconnectKind): string {
  switch (kind) {
    case "post-login":
      return "MCP login complete";
    case "apply-changes":
      return "Apply MCP server changes?";
    case "force":
      return "Force reconnect?";
  }
}

/** Derive the default body from the kind. */
function defaultBody(kind: McpReconnectKind, serverName?: string, serverNames?: ReadonlyArray<string>): string {
  switch (kind) {
    case "post-login":
      return `✓ Connected to ${serverName ?? "(unknown)"}\nReconnect to load new tools.`;
    case "apply-changes":
      const list = serverNames && serverNames.length > 0 ? serverNames.join(", ") : "(none)";
      return `Reconnect to apply the changes to ${list}.`;
    case "force":
      return "No MCP login is queued. Restart will drop the current session and reload all servers.";
  }
}

/** Derive the default help line. */
function defaultHelp(kind: McpReconnectKind): string {
  switch (kind) {
    case "post-login":
      return "Enter: reconnect  ·  Esc: defer";
    case "apply-changes":
      return "Enter: apply  ·  Esc: defer";
    case "force":
      return "Enter: restart  ·  Esc: cancel";
  }
}

/** Reconnect prompt. Enter → onConfirm, Esc → onCancel. */
export const McpReconnect: React.FC<McpReconnectProps> = ({
  kind,
  serverName,
  serverNames,
  onConfirm,
  onCancel,
  title,
  body,
  help,
}) => {
  useInput((_input, key) => {
    if (key.escape) {
      onCancel();
      return;
    }
    if (key.return) {
      onConfirm();
      return;
    }
  });

  const t = title ?? defaultTitle(kind);
  const b = body ?? defaultBody(kind, serverName, serverNames);
  const h = help ?? defaultHelp(kind);

  return (
    <Box
      flexDirection="column"
      borderStyle="double"
      borderColor="cyan"
      paddingX={2}
      paddingY={1}
    >
      <Box>
        <Text color={kind === "force" ? "yellow" : "cyan"} bold>◆ {t}</Text>
      </Box>
      <Text> </Text>
      {b.split("\n").map((line, i) => (
        <Text key={i}>{line}</Text>
      ))}
      <Text> </Text>
      <Text dimColor>  {h}</Text>
    </Box>
  );
};

/** Pure helper: produce the right callback for the user's
 *  choice. Returns `"reconnect"` if confirmed, `"later"` if
 *  cancelled. Mirrors `McpReconnect.reconnectChoice` in
 *  deepagents-code. */
export function reconnectChoice(confirmed: boolean): ReconnectChoice {
  return confirmed ? "reconnect" : "later";
}

/** Pure helper: same, but for the force-reconnect prompt.
 *  Returns `true` if the user confirmed, `false` if cancelled. */
export function forceConfirmChoice(confirmed: boolean): ForceConfirmChoice {
  return confirmed;
}
