/**
 * header bar — pill-based status.
 *
 * Each item in the header is a colored "pill" (chip) with an
 * icon, a label, and a distinct color. The user can identify
 * the state of the daemon at a glance:
 *
 *   [⌬ MiniMax-M3] · [⚙ DEFAULT] · [↳ /work/proj] · [# 5e3b9a] · [● live]
 *
 * R31 used a flat text header with dim separators. R33 replaces
 * it with pills because:
 *   1. **Sensory differentiation**: each item has its own color
 *      and icon, so the user doesn't have to read text to know
 *      which is which.
 *   2. **State is in the color**: a green pill = healthy, red
 *      = error, yellow = warning, dim = de-emphasised. The
 *      user picks this up in <100ms.
 *   3. **Compact**: a 6-item header in pill form takes the same
 *      space as the old flat header.
 *
 * The "filled" pill style (color foreground + background) is
 * used for the *primary* status (connection). Secondary items
 * (session, cwd) use the outlined style. The result is a
 * visual hierarchy without explicit sizing.
 */

import React from "react";
import { Box, Text } from "ink";
import type { State } from "../state.js";
import { t, icon, wordmark } from "../theme.js";
import { Pill, PillSep } from "./Pill.js";

export const Header: React.FC<{ state: State; cwd: string }> = ({ state, cwd }) => {
  // Connection pill: filled green when live, filled red when dead.
  const connPill = state.connected
    ? <Pill icon={icon.live} label="live" color="white" bg={t.ok} />
    : <Pill icon={icon.dead} label="offline" color="white" bg={t.err} />;

  // Mode pill: color by mode name. DEFAULT=yellow, AUTO=cyan, etc.
  const modeColor = modeToColor(state.permissionMode);
  const modeBg = modeToBg(state.permissionMode);

  // Session id: short form, outlined.
  const shortSession = state.sessionId === "—" ? "—" : state.sessionId.slice(0, 8);

  // CWD: short form, outlined. Truncate to last 2 segments.
  const shortCwd = shortenPath(cwd);

  return (
    <Box
      borderStyle="round"
      borderColor={t.brand}
      paddingX={1}
      flexDirection="row"
      justifyContent="space-between"
    >
      <Box>
        <Text color={t.brand} bold>{wordmark}</Text>
        <PillSep />
        <Pill icon={icon.model} label={state.model} color={t.accent} />
        <PillSep />
        <Pill icon={icon.mode} label={state.permissionMode} color={modeColor} bg={modeBg} />
        <PillSep />
        <Pill icon={icon.path} label={shortCwd} color={t.dim} dim />
      </Box>
      <Box>
        <Pill icon={icon.id} label={shortSession} color={t.dim} dim />
        <PillSep />
        {connPill}
      </Box>
    </Box>
  );
};

/** Map permission-mode name to a foreground color. */
function modeToColor(mode: string): string {
  switch (mode.toUpperCase()) {
    case "DEFAULT":   return "black";
    case "AUTO":      return "black";
    case "PLAN":      return "black";
    case "ASK":       return "black";
    case "DENY":      return "white";
    case "BYPASS":    return "white";
    default:          return "black";
  }
}

/** Map permission-mode name to a background color. The bg gives
 *  the pill its "filled" look — picked from the existing theme. */
function modeToBg(mode: string): string {
  switch (mode.toUpperCase()) {
    case "DEFAULT":   return "yellow";
    case "AUTO":      return "cyan";
    case "PLAN":      return "magenta";
    case "ASK":       return "yellowBright";
    case "DENY":      return "red";
    case "BYPASS":    return "gray";
    default:          return "gray";
  }
}

/** Shorten a long cwd to the last 2 path segments for display. */
function shortenPath(p: string, maxLen: number = 28): string {
  if (!p) return "—";
  if (p.length <= maxLen) return p;
  // Split on either \ or / and keep the last 2 segments.
  const parts = p.split(/[\\/]/);
  if (parts.length <= 2) return p;
  const tail = parts.slice(-2).join("/");
  return ".../" + tail;
}
