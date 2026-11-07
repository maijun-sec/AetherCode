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
 * R388j — the shared panel BODY.
 *
 * ## The bug this exists to fix
 *
 * `PanelShell` (tui.tsx) draws the panel's frame. Six panel components
 * ALSO drew a frame of their own (`borderStyle="double"`), so the
 * screen showed two nested boxes:
 *
 *     ╭────────────────────────────────╮
 *     │ ◆ Threads          esc         │
 *     │ ╔════════════════════════════╗ │
 *     │ ║║                          ║ │
 *     │ ║  ◆ Sessions in AetherCode  ║ │   ← title ran INTO the border
 *     │ ╚════════════════════════════╝ │
 *     ╰────────────────────────────────╯
 *
 * Four visible defects, one cause:
 *
 *   1. the inner frame's border cells were DOUBLED (`║║`);
 *   2. the inner frame was wider than its container, so its right
 *      border was pushed off the edge;
 *   3. the title overflowed the inner frame;
 *   4. the notice line wrapped PAST the shell's border.
 *
 * ## The rule
 *
 * ★ THE SHELL OWNS THE FRAME. THE PANEL OWNS THE CONTENT. ★
 *
 * A component that is a *panel body* renders content only. A
 * component that is a *card* (a tool card, a permission card, a
 * stop banner) may still draw its own box — that is the point of a
 * card, and it is why a card reads as an exception to the flat
 * transcript.
 *
 * ## The width contract
 *
 * `PanelBody` publishes the CONTENT width to its children: the
 * number of cells between the shell's two border columns, after the
 * shell's own padding. A row that lays itself out to this number
 * cannot push the border off the edge.
 *
 * A panel body that needs a title of its own (because the shell's
 * title is generic — "Threads" — while the body knows the scope —
 * "Sessions in AetherCode") passes `title`, and `PanelBody` renders
 * it with the SAME truncation discipline as the shell, so a long
 * scope name cannot overflow.
 */

import React from "react";
import { Box, Text } from "ink";
import TextInput from "ink-text-input";
import { t } from "../theme.js";
// R388m: ONE width rule. The panel helpers measured CHARACTERS while the
// line input measured CELLS, so a Chinese title or path was laid out at
// half the intended width and ran past the frame's right border.
import { cellWidth, fitCells } from "../cellWidth.js";

export interface PanelBodyProps {
  /**
   * The content width in cells — the number of columns available
   * between the shell's border columns. Every row the body renders
   * must fit inside it.
   */
  width: number;
  /**
   * An optional scope title, rendered above the content. Use it
   * when the shell's title is generic and the body knows the
   * specific scope ("Sessions in AetherCode", not "Threads").
   */
  title?: string;
  /**
   * An optional second line under the title — the absolute path, a
   * count, or a hint. Rendered dim, one line, truncated.
   */
  subtitle?: string;
  /** The body itself. */
  children?: React.ReactNode;
}

/**
 * A frameless panel body.
 *
 * <p>No border of its own — the shell draws that. The optional
 * title uses `wrap="truncate-end"`, the same discipline the shell
 * uses, so a long title degrades to an ellipsis instead of
 * overlapping the frame.
 *
 * <p>★ EVERY LINE IS PADDED TO THE FULL WIDTH. ★
 *
 * <p>Ink does NOT pad a `<Box>` to its declared `width` — the
 * box's width is a CONSTRAINT on its children, not a canvas they
 * are painted onto. A short line therefore leaves a gap before the
 * frame's right border, and the panel's right edge reads as a
 * ragged staircase:
 *
 *     │ ◆ Threads                esc │
 *     │ ───────────────────────────── │
 *     │ Sessions in AetherCode│          ← short: the border moved
 *     │ ───────────────────────────── │
 *
 * <p>The fix is to paint each line's background out to the full
 * content width. Ink's `<Box>` has no background painting of its
 * own, so the padding has to be in the TEXT: every line the body
 * renders is `fitRow`'d (padded or truncated) to exactly `width`
 * cells.
 *
 * <p>That is why `PanelBody` does not just pass `width` down — it
 * also renders its own lines through `fitRow`, and the components
 * it hosts do the same for theirs.
 */
export const PanelBody: React.FC<PanelBodyProps> = ({ width, title, subtitle, children }) => {
  const inner = Math.max(20, width);
  return (
    <Box flexDirection="column" width={inner}>
      {title ? (
        <Text color={t.accent} bold>
          {fitRow(title, inner)}
        </Text>
      ) : null}
      {subtitle ? <Text dimColor>{fitRow(subtitle, inner)}</Text> : null}
      {title || subtitle ? <Text>{" ".repeat(inner)}</Text> : null}
      {children}
    </Box>
  );
};

/**
 * A blank line that still fills the frame's width.
 *
 * <p>A bare `<Text> </Text>` is ONE cell wide, so the frame's right
 * border moves in by one column on that line — the panel's edge
 * becomes a staircase. Every blank line inside a panel has to be
 * `width` cells wide.
 */
export function blank(width: number): string {
  return " ".repeat(Math.max(1, width));
}

/**
 * The width a filter row's INPUT actually occupies.
 *
 * <p>`TextInput` renders its own content and nothing else: the
 * placeholder when the field is empty, the value plus a cursor
 * otherwise. So the row is as wide as the TEXT, not as wide as the
 * panel — and the frame's right border moves in by the difference.
 *
 * <p>The panel has to know the input's width to pad the row, and
 * the input's width depends on the value. So the two are computed
 * together, here, from the same numbers the input will use.
 *
 * <p>R388m: measured in CELLS. A Chinese filter value is twice as wide
 * as its character count, and a character count would pad the row short
 * by exactly the width of the text — the frame's edge moves in.
 *
 * @param value      the current filter text
 * @param placeholder the placeholder shown when the value is empty
 */
export function filterInputWidth(value: string, placeholder: string): number {
  return Math.max(1, value.length > 0 ? cellWidth(value) + 1 : cellWidth(placeholder));
}

/**
 * A filter row: the prompt, the input, and the padding that makes
 * the row fill the frame.
 *
 * <p>Shared by every panel with a filter box, so the row's geometry
 * is decided once instead of once per panel.
 */
export function filterRow(opts: {
  width: number;
  value: string;
  placeholder: string;
  onChange: (v: string) => void;
  /** the prompt character, default `>` */
  prompt?: string;
}): React.ReactNode {
  const { width, value, placeholder, onChange } = opts;
  const prompt = opts.prompt ?? ">";
  const used = prompt.length + 1 + filterInputWidth(value, placeholder);
  // R388j: the row is a `<Box>` with THREE children (prompt, input,
  // padding). Ink's flexbox distributes the width between them and
  // shrinks the first — so the padding was discarded and the row
  // came out short. The prompt and the padding are therefore
  // SIBLING TEXTS and the input is the only flex child; the
  // padding's width is computed so the three together add up.
  return (
    <Box width={width}>
      <Text color="yellow">{prompt}</Text>
      <Text> </Text>
      <Box flexGrow={0}>
        <TextInput value={value} onChange={onChange} placeholder={placeholder} />
      </Box>
      <Text>{blank(Math.max(0, width - used))}</Text>
    </Box>
  );
}

/**
 * A panel row that is guaranteed to fit the frame.
 *
 * <p>Ink does not clip a row that is too wide — it lets it run past
 * the border, which is how the notice line ended up outside the
 * frame. A row that must not overflow is built from this, which
 * pads/truncates the text to the exact width.
 *
 * @param text    the row's text
 * @param width   the content width
 * @param pad     trailing spaces to pad the row to `width` (a row
 *                that ends short of the border looks ragged)
 * @param ellipsis what to show when the text is too long
 */
export function fitRow(text: string, width: number, ellipsis = "…"): string {
  return fitCells(text, width, ellipsis);
}
