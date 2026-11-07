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
 * R388o — the terminal geometry: ONE answer for "how big is the screen".
 *
 * <p>★ WHY THIS MODULE EXISTS ★
 *
 * <p>The user reported: *"the whole page renders badly — the box size
 * changes, the position moves around, and as the input changes the whole
 * page seems to move."* The screenshot showed a band of ~12 REPEATED
 * header rows, an input pill whose right border was CUT OFF, and a
 * status bar that WRAPPED onto a second line.
 *
 * <p>The measurement (see {@code docs/R388o-terminal-geometry.md}) found
 * the cause, and it was NOT the one the symptoms suggested:
 *
 * <ul>
 *   <li>The frame's HEIGHT was not the problem — Ink's redraw is sound
 *       (probe: a frame whose height grew from 2 to 4 rows redrew in
 *       place, and the header appeared exactly once);</li>
 *   <li>The frame's WIDTH was: the status bar's right-hand box carried
 *       {@code flexShrink={0}}, so it never shrank, and it overflowed the
 *       terminal. The overflow made the terminal WRAP the row, which
 *       added a row to the frame;</li>
 *   <li>A frame taller than {@code stdout.rows} makes Ink switch to its
 *       {@code clearTerminal} path ({@code ESC[2J ESC[3J ESC[H}); on a
 *       console that does not honour {@code ESC[2J} every render APPENDS
 *       instead of replacing — which is the stack of repeated headers;</li>
 *   <li>★ AND THE DEFECT THAT WAS NOT VISIBLE TO ANY OF THAT ★ Yoga
 *       gives a flex item a BASE SIZE equal to its CONTENT, and a flex
 *       item only shrinks when the container overflows. So a transcript
 *       taller than the terminal does not clip ITSELF — it takes the
 *       rows and SQUEEZES the chrome. Measured (a Yoga tree dump at
 *       100×30 with a 12-turn transcript): the header's outer box
 *       computed to ONE row, the brand row and the cwd caption both
 *       computed to height 0, and the input pill showed only its top
 *       border. The fix is the flex-basis chain — {@code flexBasis={0}}
 *       and {@code minHeight={0}} on the transcript's columns,
 *       {@code flexShrink={0}} on the chrome. Measured by turning each
 *       change off in turn: the Scrollback's own {@code flexBasis={0}}
 *       is the ONE that carries the fix; the rest are cheap
 *       guarantees.</li>
 * </ul>
 *
 * <p>So the contract has TWO halves, and both are needed:
 * <b>the frame MUST fit the terminal</b>, and <b>the chrome must keep
 * its rows</b> — a frame that fits by squeezing the header into one row
 * is not a frame that fits.
 *
 * <p>★ ONE SOURCE OF TRUTH ★
 *
 * <p>Before this module the TUI asked the terminal for its size in FOUR
 * places, with FOUR different fallbacks ({@code 120}, {@code 80}, the
 * raw {@code process.stdout.columns}, or "whatever the caller passed").
 * A component that guessed 120 while the terminal was 80 produced the
 * overflow this round is about.
 *
 * <p>Now every component asks THIS module, and this module asks the
 * terminal ONCE per render.
 *
 * <p>★ WHY A PLAIN .ts FILE ★
 *
 * <p>Like {@code cellWidth.ts} and {@code permissionComment.ts}: a gate
 * script must be able to import it. A {@code .tsx} component cannot be
 * imported by a Node gate (the bundle would pull in React/Ink).
 *
 * <p>★ NO DUPLICATED WIDE-CHAR TABLE ★
 *
 * <p>The first draft of this file carried its own copy of the
 * East-Asian-Wide table. That is precisely the "two copies of a truth"
 * defect (R380's subject) — the two copies already disagreed (the
 * draft was missing {@code 0x2329}, {@code 0x2fffd} and
 * {@code 0x30000}). The table now lives in {@code cellWidth.ts} and
 * this module IMPORTS it. {@code r388o-geometry.test.mjs} pins that
 * there is exactly ONE wide-char table in the TUI.
 */

import { cellWidth, fitCells as fitCellsByCells } from "./cellWidth.js";

export { fitCellsByCells };

/** The width the TUI assumes when the terminal will not say. */
export const DEFAULT_COLS = 100;

/** The height the TUI assumes when the terminal will not say. */
export const DEFAULT_ROWS = 30;

/** The narrowest terminal the TUI claims to support. */
export const MIN_COLS = 40;

/** The shortest terminal the TUI claims to support. */
export const MIN_ROWS = 10;

/**
 * Read the terminal's size, with honest fallbacks.
 *
 * <p>★ THE FALLBACK IS NOT A GUESS ★
 *
 * <p>A terminal that does not report a size is a terminal we cannot lay
 * out for. The old code guessed 120 columns in one place and 80 in
 * another — and 120 is wider than most terminals, so the "safe" guess
 * was the one that produced the overflow. {@link DEFAULT_COLS} is 100:
 * narrow enough that an 80-column terminal's content does not wrap,
 * wide enough to read prose.
 *
 * @param out the stream to ask (defaults to {@code process.stdout})
 * @returns the terminal's cell width and height
 */
export function terminalSize(out?: { columns?: number; rows?: number }): {
  cols: number;
  rows: number;
} {
  const stream = out ?? process.stdout;
  const rawCols = typeof stream.columns === "number" && stream.columns > 0 ? stream.columns : 0;
  const rawRows = typeof stream.rows === "number" && stream.rows > 0 ? stream.rows : 0;
  return {
    cols: rawCols > 0 ? Math.max(MIN_COLS, rawCols) : DEFAULT_COLS,
    rows: rawRows > 0 ? Math.max(MIN_ROWS, rawRows) : DEFAULT_ROWS,
  };
}

/**
 * How many rows the CHROME (everything that is not the transcript)
 * occupies.
 *
 * <p>★ MEASURED, NOT GUESSED ★ The chrome's height depends on the
 * WIDTH, because a status bar that fits on one row becomes two rows
 * when it wraps. After R388o the chrome does not wrap (the status bar
 * is fitted to the width), so this is a constant — but it is derived
 * from the components, and the gate pins it against the rendered
 * frame.
 */
export function chromeRows(width: number): number {
  // header: brand row + status strip + rule + cwd caption  = 4
  // the rule above the status bar                            = 1
  // the status bar row                                        = 1
  // the input pill: top border + content + bottom border      = 3
  return 4 + 1 + 1 + 3;
}

/**
 * The rows the INPUT PILL occupies: a top border, its content line,
 * and a bottom border.
 *
 * <p>The pill grows with the text, but its HEIGHT is not part of this
 * constant — the pill is inside the transcript's flex column, so the
 * root's height constraint clips it. This is the pill's MINIMUM.
 */
export const INPUT_PILL_ROWS = 3;

/**
 * How many lines of text the input shows at once.
 *
 * <p>★ WHY THE INPUT IS CAPPED ★ The input is the one surface whose
 * height depends on the CONTENT (a shift+Enter newline adds a line;
 * a long line wraps). An uncapped input therefore changes the frame's
 * height as the user types — which is precisely the "as the input
 * changes, the whole page moves" symptom.
 *
 * <p>Three lines is enough to see a multi-line prompt; past that the
 * text scrolls within the pill.
 */
export const INPUT_MAX_LINES = 3;

/**
 * The number of transcript rows that fit between the chrome and the
 * bottom of the terminal.
 *
 * <p>★ NEVER NEGATIVE ★ A short terminal (10 rows) with a tall chrome
 * would otherwise produce a negative height, and a negative height
 * passed to a slice is a silent bug: it returns an empty array and the
 * transcript disappears without a word.
 *
 * @param totalRows the terminal's height
 * @param chrome    the chrome's height in rows
 */
export function transcriptRows(totalRows: number, chrome: number): number {
  return Math.max(1, totalRows - chrome);
}

/**
 * Fit a string into a cell budget, with an ellipsis when it does not
 * fit.
 *
 * <p>★ CELLS, NOT CHARACTERS ★ A CJK glyph is two cells wide. The
 * truncation must count cells or a Chinese jar name is cut in half and
 * the row overflows again — the very defect this round fixes.
 *
 * <p>Delegates to {@code cellWidth.fitCells} — ONE implementation.
 *
 * @param s        the text
 * @param cells    the budget in terminal cells
 * @param ellipsis the marker for a truncated string
 */
export function fitCells(s: string, cells: number, ellipsis = "…"): string {
  if (cells <= 0) return "";
  // `cellWidth.fitCells` PADS to the full width. For a truncation used
  // inline (the jar name in a row of other items) the padding would
  // push the row over the budget, so we take the text and drop the
  // padding.
  return fitCellsByCells(s, cells, ellipsis).trimEnd();
}

/**
 * The visible cell count of a string.
 *
 * <p>Re-exported so a component that needs it does not have to know
 * which of the two geometry modules owns it.
 */
export { cellWidth };
