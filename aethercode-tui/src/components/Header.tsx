// Copyright (c) 2026 maijun
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

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
 *  - mode:   by the catalog's tone (ask / allow / read / plan / danger)
 *  - ctx:    fill glyph colours by ratio (green ≤50%, yellow ≤80%, red >80%)
 *  - live:   green dot; offline: red ring
 */

import React from "react";
import { Box, Text } from "ink";
import type { State } from "../state.js";
import { t, icon, wordmark, formatRelative } from "../theme.js";
// R385: the mode badge and its colour come from the ONE catalog. This
// file used to carry TWO hand-written switches over the mode names —
// and they disagreed with the cmd.exe renderer's own switch about
// what `DEFAULT` looks like (here: the raw name; there: "ASK"). It
// also carried four names the daemon cannot produce (`AUTO`, `ASK`,
// `DENY`, `BYPASS` — the old `Action` enum).
import {
  permissionModeEntry,
  permissionModeIndicator,
  type PermissionModeTone,
} from "../permissionModes.js";
// R388o: the terminal's width, from ONE source.
import { cellWidth, terminalSize } from "../terminalGeometry.js";

interface Props {
  state: State;
  cwd: string;
  /** R388o: the terminal width to lay out for. When omitted the header
   *  asks the terminal itself. The caller passes it so the header and
   *  the rest of the frame agree on ONE width. */
  width?: number;
}

/** The background colour for each catalog tone. Ink's palette names. */
const TONE_BG: Record<PermissionModeTone, string> = {
  ask: "yellowBright",
  allow: "green",
  read: "cyan",
  plan: "magenta",
  danger: "red",
  unknown: "gray",
};

/** The foreground that reads against each background. */
const TONE_FG: Record<PermissionModeTone, string> = {
  ask: "black",
  allow: "black",
  read: "black",
  plan: "black",
  danger: "white",
  unknown: "black",
};

/** Map a permission mode name to a foreground+background pair. */
function modeStyle(mode: string): { fg: string; bg: string } {
  const entry = permissionModeEntry(mode);
  const tone = entry?.tone ?? "unknown";
  return { fg: TONE_FG[tone], bg: TONE_BG[tone] };
}

/** Map a permission mode name to a short UI label. */
function modeLabel(mode: string): string {
  const entry = permissionModeEntry(mode);
  // An unknown mode shows its own name — the catalog has nothing to
  // call it, and inventing a label would be a claim.
  return entry ? entry.badge : mode.toUpperCase();
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

export const Header: React.FC<Props> = ({ state, cwd, width }) => {
  // R388o: the header asks the terminal ONCE for its width, and every
  // row it draws is fitted to it. The rule used to be a hard-coded
  // `Math.max(60, cwd.length + 12)` — which overflowed every terminal
  // narrower than 60 cells and left a gap on every wider one.
  const cols = Math.max(20, width ?? terminalSize().cols);
  // Connection pill (filled green when live, red ring when offline).
  const connPillText = state.connected ? "●" : "○";
  const connPill = <Text color={state.connected ? t.ok : t.err}>{connPillText}</Text>;

  // Mode pill (filled coloured badge — bg gives the pill look).
  const ms = modeStyle(state.permissionMode);
  const modeShort = modeLabel(state.permissionMode);
  // R388n: the literal phrase. From the ONE catalog, so the pill and the
  // phrase cannot describe the same mode differently.
  const modeIndicator = permissionModeIndicator(state.permissionMode);

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
  const elapsed = state.uptimeStartedAt ? formatRelative(state.uptimeStartedAt) : "—";

  // R388o: THE HEADER MUST FIT TOO.
  //
  // The status strip carries a lot: the model, the provider, the mode
  // badge, the mode PHRASE, the context meter, the session id, the
  // connection dot and the elapsed time. On a narrow terminal that is
  // more than the terminal has, and the row WRAPS — which adds a row
  // to the frame, which is what made the page "move".
  //
  // So the right half's items are dropped IN PRIORITY ORDER when the
  // width runs out: first the elapsed time, then the session id, then
  // the context meter. The LEFT half (the model and the mode) is never
  // dropped — that is the identity of the session.
  //
  // ★ DROPPING IS NOT HIDING ★ An item that is dropped is not shown
  // at all; an item that is squeezed is shown WRONG (the measured
  // result of a shrunk box was "⌬MiniM · AS⏸ manual", the model name
  // cut in half and overlapping the badge).
  const stripWidth = Math.max(10, cols - 2);
  // The left half's natural width: the model, the badge, the phrase.
  const leftCells =
    cellWidth(`${icon.model} ${state.model}`) +
    (state.provider ? cellWidth(` · ${icon.provider} ${state.provider}`) : 0) +
    cellWidth(` · ${modeShort} `) +
    cellWidth(modeIndicator);
  // The right half's items, most-droppable first.
  const rightItems: { key: string; cells: number }[] = [];
  if (ctx)
    rightItems.push({
      key: "ctx",
      cells: cellWidth(ctx.glyph) + cellWidth(` ${Math.round(ratio * 100)}% · `),
    });
  rightItems.push({ key: "session", cells: cellWidth(shortSession) + cellWidth(" · ") });
  rightItems.push({ key: "conn", cells: cellWidth(connPillText) + cellWidth(" · ") });
  rightItems.push({ key: "elapsed", cells: cellWidth(elapsed) });
  // Drop from the END (elapsed first) until the rest fits.
  let rightCells = rightItems.reduce((n, it) => n + it.cells, 0);
  const dropped = new Set<string>();
  for (let i = rightItems.length - 1; i >= 0; i--) {
    if (leftCells + rightCells <= stripWidth) break;
    rightCells -= rightItems[i].cells;
    dropped.add(rightItems[i].key);
  }

  return (
    <Box flexDirection="column" flexShrink={0}>
      {/* brand row: wordmark + version. only when viewport is wide enough. */}
      <Box paddingX={1}>
        <Text color={t.brand} bold>
          {wordmark}
        </Text>
        <Text dimColor> v0.2.1</Text>
      </Box>
      {/* main status strip — single row, no border, just a rule below. */}
      <Box flexDirection="row" justifyContent="space-between" paddingX={1}>
        <Box flexDirection="row">
          {/* model */}
          <Text color={t.accent} bold>
            {icon.model}{" "}
          </Text>
          <Text>{state.model}</Text>
          {/* provider (when known — desktop surfaces this since R341; daemon
              hasn't shipped it on the wire yet, but the field is reserved). */}
          {state.provider ? (
            <>
              <Text dimColor> · </Text>
              <Text color={t.brand}>{icon.provider} </Text>
              <Text>{state.provider}</Text>
            </>
          ) : null}
          {/* mode indicator.
              R388n: a LITERAL PHRASE, not an acronym.
              The badge (`ASK`, `TASK`, `EDITS`) could not say the two
              things the user needs: `DEFAULT` and `ASK_BEFORE_TOOL` both
              rendered as `ASK` — two distinct daemon modes that looked
              IDENTICAL — and an acronym never says whether the mode
              still interrupts you. The phrase's leading glyph does:
              `⏸` = still asks, `⏵⏵` = does not ask. */}
          <Text dimColor> · </Text>
          <Text color={ms.fg} bold backgroundColor={ms.bg}>
            {" "}
            {modeShort}{" "}
          </Text>
          <Text color={ms.fg}>{modeIndicator}</Text>
        </Box>
        {/* R388o: the right half drops its items in priority order
            when the terminal is too narrow. See the note above. */}
        <Box flexDirection="row">
          {/* ctx fill (only when known) */}
          {ctx && !dropped.has("ctx") ? (
            <>
              <Text color={ctx.color}>{ctx.glyph}</Text>
              <Text dimColor> {Math.round(ratio * 100)}%</Text>
              <Text dimColor> · </Text>
            </>
          ) : null}
          {/* session id */}
          {!dropped.has("session") ? (
            <>
              <Text dimColor>{shortSession}</Text>
              <Text dimColor> · </Text>
            </>
          ) : null}
          {/* connection dot */}
          {!dropped.has("conn") ? (
            <>
              {connPill}
              <Text dimColor> · </Text>
            </>
          ) : null}
          {/* elapsed */}
          {!dropped.has("elapsed") ? <Text dimColor>{elapsed}</Text> : null}
        </Box>
      </Box>
      {/* thin separator under the header — matches the StatusBar's rule. */}
      {/* R388o: the rule is the TERMINAL's width. A fixed 60 was a
          guess that overflowed narrow terminals and under-filled wide
          ones — and an over-wide row is WRAPPED by the terminal, which
          adds a row to the frame and makes the page "move". */}
      <Text dimColor>{"─".repeat(cols)}</Text>
      {/* cwd as a small caption below the rule (dim, max ~28 chars). */}
      <Box paddingX={1}>
        <Text dimColor>
          {icon.path} {shortCwd(cwd)}
        </Text>
      </Box>
    </Box>
  );
};
