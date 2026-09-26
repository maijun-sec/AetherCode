/**
 * header bar — R344 redesign.
 *
 * Claude Code–inspired one-row status strip. No border: a thin `─`
 * rule under the row separates header from scrollback (matching the
 * StatusBar's visual language). Information is grouped into three
 * bands:
 *
 *   left:   model · provider (when present) · mode
 *   middle: ctx fill glyph + percent (only when we know the window)
 *   right:  session (short) · connection dot · elapsed
 *
 * Cwd is intentionally NOT in the header — Claude Code dropped it
 * from the status strip in their redesign (it lives on the welcome
 * banner + footer only). The brand mark + version sits on its own
 * sub-row above the status strip when the viewport is wide enough.
 *
 * Pill colors:
 *  - model: accent (cyan) — primary identity
 *  - mode:   yellowBright (DEFAULT) / cyan (AUTO) / red (DENY) / …
 *  - ctx:    fill glyph colours by ratio (green ≤50%, yellow ≤80%, red >80%)
 *  - live:   green dot; offline: red ring
 */

import React from "react";
import { Box, Text } from "ink";
import type { State } from "../state.js";
import { t, icon, wordmark, formatRelative } from "../theme.js";

interface Props {
  state: State;
  cwd: string;
}

/** Map a permission mode name to a foreground+background pair. */
function modeStyle(mode: string): { fg: string; bg: string } {
  switch (mode.toUpperCase()) {
    case "DEFAULT":   return { fg: "black",       bg: "yellowBright" };
    case "AUTO":      return { fg: "black",       bg: "cyan" };
    case "PLAN":      return { fg: "black",       bg: "magenta" };
    case "ASK":       return { fg: "black",       bg: "yellowBright" };
    case "ASK_BEFORE_TOOL": return { fg: "black", bg: "yellowBright" };
    case "ACCEPT_EDITS":    return { fg: "black", bg: "green" };
    case "ACCEPT_TASK":     return { fg: "black", bg: "green" };
    case "DENY":      return { fg: "white",       bg: "red" };
    case "BYPASS":    return { fg: "white",       bg: "red" };
    case "BYPASS_PERMISSIONS": return { fg: "white", bg: "red" };
    default:          return { fg: "black",       bg: "gray" };
  }
}

/** Map a permission mode name to a short UI label. */
function modeLabel(mode: string): string {
  switch (mode.toUpperCase()) {
    case "ACCEPT_TASK":        return "TASK";
    case "ACCEPT_EDITS":       return "EDITS";
    case "ASK_BEFORE_TOOL":    return "ASK";
    case "BYPASS_PERMISSIONS": return "BYPASS";
    case "AUTO_READ_ONLY":     return "READ";
    default:                   return mode.toUpperCase();
  }
}

/** 8-step progress bar using block characters. */
function ctxGlyph(ratio: number, width: number = 8): { glyph: string; color: string } {
  const clamped = Math.max(0, Math.min(1, ratio));
  const filled = Math.round(clamped * width);
  const empty = width - filled;
  let color: string = t.ok;
  if (clamped > 0.5) color = t.warn;
  if (clamped > 0.8) color = t.err;
  return {
    glyph: "▆".repeat(filled) + "░".repeat(empty),
    color,
  };
}

/** Shorten a path to the last 2 segments, capped to 28 chars. */
function shortCwd(p: string, maxLen: number = 28): string {
  if (!p) return "—";
  if (p.length <= maxLen) return p;
  const parts = p.split(/[\\/]/);
  if (parts.length <= 2) return p;
  const tail = parts.slice(-2).join("/");
  return tail.length > maxLen ? `.../${tail.slice(-(maxLen - 4))}` : `.../${tail}`;
}

export const Header: React.FC<Props> = ({ state, cwd }) => {
  // Connection pill (filled green when live, red ring when offline).
  const connPill = state.connected
    ? <Text color={t.ok}>●</Text>
    : <Text color={t.err}>○</Text>;

  // Mode pill (filled coloured badge — bg gives the pill look).
  const ms = modeStyle(state.permissionMode);
  const modeShort = modeLabel(state.permissionMode);

  // ctx fill. contextWindow may be 0 (unknown); hide the glyph in that case.
  const totalTokens = (state.inputTokens ?? 0) + (state.outputTokens ?? 0);
  const hasCtx = state.contextWindow > 0;
  const ratio = hasCtx ? totalTokens / state.contextWindow : 0;
  const ctx = hasCtx ? ctxGlyph(ratio) : null;

  // session id — short form, dim
  const shortSession = state.sessionId === "—" ? "—" : state.sessionId.slice(0, 8);

  // uptime (best-effort — we don't have a daemon-side uptime field
  // today, so use the elapsed-since-mount approximation).
  // We approximate by tracking the last "init" event time. For now
  // we display "—" until that becomes a real daemon field.
  const elapsed = state.uptimeStartedAt
    ? formatRelative(state.uptimeStartedAt)
    : "—";

  return (
    <Box flexDirection="column">
      {/* brand row: wordmark + version. only when viewport is wide enough. */}
      <Box paddingX={1}>
        <Text color={t.brand} bold>{wordmark}</Text>
        <Text dimColor> v0.2.1</Text>
      </Box>
      {/* main status strip — single row, no border, just a rule below. */}
      <Box flexDirection="row" justifyContent="space-between" paddingX={1}>
        <Box flexDirection="row">
          {/* model */}
          <Text color={t.accent} bold>{icon.model} </Text>
          <Text>{state.model}</Text>
          {/* provider (when known — desktop surfaces this since R341; daemon
              hasn't shipped it on the wire yet, but the field is reserved). */}
          {state.provider ? (
            <>
              <Text dimColor>  ·  </Text>
              <Text color={t.brand}>{icon.provider} </Text>
              <Text>{state.provider}</Text>
            </>
          ) : null}
          {/* mode badge */}
          <Text dimColor>  ·  </Text>
          <Text color={ms.fg} bold backgroundColor={ms.bg}> {modeShort} </Text>
        </Box>
        <Box flexDirection="row">
          {/* ctx fill (only when known) */}
          {ctx ? (
            <>
              <Text color={ctx.color}>{ctx.glyph}</Text>
              <Text dimColor> {Math.round(ratio * 100)}%</Text>
              <Text dimColor>  ·  </Text>
            </>
          ) : null}
          {/* session id */}
          <Text dimColor>{shortSession}</Text>
          <Text dimColor>  ·  </Text>
          {/* connection dot */}
          {connPill}
          <Text dimColor>  ·  </Text>
          {/* elapsed */}
          <Text dimColor>{elapsed}</Text>
        </Box>
      </Box>
      {/* thin separator under the header — matches the StatusBar's rule. */}
      <Text dimColor>{"─".repeat(Math.max(60, (cwd?.length ?? 0) + 12))}</Text>
      {/* cwd as a small caption below the rule (dim, max ~28 chars). */}
      <Box paddingX={1}>
        <Text dimColor>{icon.path} {shortCwd(cwd)}</Text>
      </Box>
    </Box>
  );
};