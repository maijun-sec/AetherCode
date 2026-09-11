/**
 * log viewer.
 *
 * A modal that opens with Ctrl-L. Shows the most recent daemon
 * log lines (state.logBuffer). Each line is prefixed with a
 * timestamp + a level icon. The viewer is read-only — the user
 * can scroll with the terminal's own scroll, or close with Esc.
 *
 * Why this matters: the daemon's stderr is swallowed by the
 * JsonRpcClient. The user has no way to see internal daemon
 * activity. The log notification (forwarded by the daemon to
 * the TUI) gives us a way to surface the important parts.
 *
 * We don't show all 200 buffered lines by default — that would
 * flood the screen. We show the most recent 20 and a count of
 * hidden lines.
 *
 * T-455 (Phase 5 R5): grants log extension. The viewer now
 * also accepts a `GrantEntry[]` (the per-grant audit trail
 * produced by the consent system). Two modes:
 *
 *   - mode = "log"  → original R56 behaviour (daemon log)
 *   - mode = "grants" → grants log view (scope / category
 *     / decision / created_at / expires_at)
 *
 * The host passes `entries` and (optionally) `grants`. When
 * both are present, the viewer shows a one-line "tab bar"
 * so the user can pick which log to read. The default mode
 * is "log" for backward compatibility.
 */

import React, { useState } from "react";
import { Box, Text, useInput } from "ink";
import { t, icon } from "../theme.js";

export interface LogEntry {
  ts: number;
  level: "info" | "warn" | "err";
  message: string;
}

/** T-455: a single grant audit record. Mirrors the
 *  `Grant` record the permission system publishes on
 *  every `permission/grant` RPC. The viewer shows the
 *  most recent first. */
export interface GrantEntry {
  ts: number;
  scope: "session" | "project" | "user";
  category: string;
  tool: string;
  decision: "allow" | "deny";
  /** Optional expiry (epoch ms). Grants with
   *  expiresAt < now render with a `⚠ expired` badge. */
  expiresAt?: number | null;
  /** Optional one-line rationale. */
  reason?: string;
}

interface Props {
  entries: LogEntry[];
  onClose: () => void;
  /** T-455: optional grants log. When omitted, the
   *  viewer is the original R56 daemon-log modal. */
  grants?: GrantEntry[];
  /** T-455: starting mode. Defaults to "log". The
   *  host can pre-select "grants" if the user opened
   *  the modal from the grants panel. */
  initialMode?: "log" | "grants";
}

const LEVEL_ICON: Record<LogEntry["level"], string> = {
  info: icon.note,
  warn: icon.warn,
  err:  icon.err,
};

const LEVEL_COLOR: Record<LogEntry["level"], string> = {
  info: t.dim,
  warn: t.warn,
  err:  t.err,
};

const SCOPE_ICON: Record<GrantEntry["scope"], string> = {
  session: "●",
  project: "◆",
  user:    "★",
};

const SCOPE_COLOR: Record<GrantEntry["scope"], string> = {
  session: "cyan",
  project: "yellow",
  user:    "magenta",
};

const DECISION_ICON: Record<GrantEntry["decision"], string> = {
  allow: "✓",
  deny:  "✗",
};

const DECISION_COLOR: Record<GrantEntry["decision"], string> = {
  allow: "green",
  deny:  "red",
};

export const LogViewer: React.FC<Props> = ({
  entries,
  onClose,
  grants = [],
  initialMode = "log",
}) => {
  // T-455: tab state. When both logs are present, the
  // user can flip with [ and ] (or click in the desktop
  // variant). When only one is present, the tab bar is
  // hidden and the mode is fixed.
  const hasGrants = grants.length > 0;
  const [mode, setMode] = useState<"log" | "grants">(initialMode);

  useInput((input, key) => {
    if (key.escape) {
      onClose();
      return;
    }
    if (hasGrants && input === "[") {
      setMode("log");
      return;
    }
    if (hasGrants && input === "]") {
      setMode("grants");
      return;
    }
  });

  // T-455: when the grants log is the active tab, the
  // log count and the header label both flip to the
  // grants-specific copy. The daemon-log tab keeps the
  // original R56 wording.
  if (mode === "grants" && hasGrants) {
    const shown = grants.slice(-20);
    const hidden = Math.max(0, grants.length - shown.length);
    return (
      <Box
        flexDirection="column"
        borderStyle="double"
        borderColor={t.accent}
        paddingX={2}
        paddingY={1}
      >
        <Box>
          <Text color={t.accent} bold>{icon.note} grants log</Text>
          <Text dimColor>  ·  {grants.length} grants  ·  </Text>
          <Text dimColor>[log]</Text>
          <Text color="cyan">  ▶grants  </Text>
          <Text dimColor>· Esc: close</Text>
        </Box>
        <Text> </Text>
        {shown.length === 0 ? (
          <Text dimColor>  (no grants yet)</Text>
        ) : (
          shown.map((g, i) => {
            const expired = g.expiresAt != null && g.expiresAt < Date.now();
            return (
              <Box key={i} flexDirection="row">
                <Text dimColor>  {new Date(g.ts).toLocaleTimeString()} </Text>
                <Text color={SCOPE_COLOR[g.scope]}>{SCOPE_ICON[g.scope]} </Text>
                <Text color={DECISION_COLOR[g.decision]}>{DECISION_ICON[g.decision]} </Text>
                <Text color={t.asst}>{g.category}/{g.tool}</Text>
                <Text dimColor>  scope={g.scope}</Text>
                {expired ? <Text color="red">  ⚠ expired</Text> : null}
                {g.reason ? <Text dimColor>  · {g.reason.slice(0, 40)}</Text> : null}
              </Box>
            );
          })
        )}
        {hidden > 0 ? (
          <Text dimColor>  … {hidden} earlier grants hidden</Text>
        ) : null}
        <Text> </Text>
        <Text dimColor>  [/] switch tab · Esc to close.</Text>
      </Box>
    );
  }

  const shown = entries.slice(-20);
  const hidden = Math.max(0, entries.length - shown.length);
  return (
    <Box
      flexDirection="column"
      borderStyle="double"
      borderColor={t.accent}
      paddingX={2}
      paddingY={1}
    >
      <Box>
        <Text color="cyan" bold>{icon.note} ▶log  </Text>
        <Text dimColor>[grants]</Text>
        <Text dimColor>  ·  daemon log  ·  {entries.length} entries  ·  Esc: close</Text>
      </Box>
      <Text> </Text>
      {shown.length === 0 ? (
        <Text dimColor>  (no log entries yet)</Text>
      ) : (
        shown.map((e, i) => (
          <Box key={i} flexDirection="row">
            <Text dimColor>  {new Date(e.ts).toLocaleTimeString()} </Text>
            <Text color={LEVEL_COLOR[e.level]}>{LEVEL_ICON[e.level]} </Text>
            <Text color={LEVEL_COLOR[e.level]}>{e.message}</Text>
          </Box>
        ))
      )}
      {hidden > 0 ? (
        <Text dimColor>  … {hidden} earlier entries hidden</Text>
      ) : null}
      <Text> </Text>
      <Text dimColor>  {hasGrants ? "[/] switch tab · " : ""}Press Esc to close.</Text>
    </Box>
  );
};
