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
 * R388m — ONE width rule.
 *
 * <p>A terminal is a grid of CELLS, not characters. A CJK glyph and most
 * emoji occupy TWO cells; everything else occupies one.
 *
 * <p>The product had **two** width rules:
 *
 * <ul>
 *   <li>{@code lineInput.ts} counted CELLS (it had to — the cursor has to
 *       land in the right place while the user types Chinese);</li>
 *   <li>{@code PanelBody.fitRow} counted CHARACTERS (it had to fit a row
 *       to a panel's width).</li>
 * </ul>
 *
 * <p>So a panel whose title or path is Chinese was laid out at HALF its
 * intended width: a 30-character Chinese title is 60 cells, and
 * {@code fitRow} thought it was 30. The row ran **past the frame's right
 * border** — which is exactly the "ragged / broken dialog box" the user
 * sees. The frame's edge is a staircase and the text overlaps it.
 *
 * <p>This module is the ONE rule. It lives in its own file (pure, no React)
 * so a gate script can import it and so both the panel helpers and the line
 * input use it — a second copy is a second truth, and R380's whole subject
 * is what that costs.
 */

/** Strip ANSI escape sequences so visible-cell math isn't fooled by colour. */
export function stripAnsi(s: string): string {
  return s.replace(/\x1b\[[0-9;]*m/g, "");
}

/**
 * Is this code point WIDE — i.e. does it occupy two terminal cells?
 *
 * <p>The ranges match the East Asian Width property that terminals use.
 */
export function isWide(cp: number): boolean {
  return (
    (cp >= 0x1100 && cp <= 0x115f) ||
    (cp >= 0x2329 && cp <= 0x232a) ||
    (cp >= 0x2e80 && cp <= 0x303e) ||
    (cp >= 0x3041 && cp <= 0x33ff) ||
    (cp >= 0x3400 && cp <= 0x4dbf) ||
    (cp >= 0x4e00 && cp <= 0x9fff) ||
    (cp >= 0xa000 && cp <= 0xa4cf) ||
    (cp >= 0xac00 && cp <= 0xd7a3) ||
    (cp >= 0xf900 && cp <= 0xfaff) ||
    (cp >= 0xfe30 && cp <= 0xfe4f) ||
    (cp >= 0xff00 && cp <= 0xff60) ||
    (cp >= 0xffe0 && cp <= 0xffe6) ||
    (cp >= 0x1f300 && cp <= 0x1f64f) ||
    (cp >= 0x1f900 && cp <= 0x1f9ff) ||
    (cp >= 0x20000 && cp <= 0x2fffd) ||
    (cp >= 0x30000 && cp <= 0x3fffd)
  );
}

/**
 * The visible cell count of a string.
 *
 * <p>ANSI is stripped first: a coloured string occupies the same cells as
 * an uncoloured one, and the escape bytes would otherwise be counted.
 */
export function cellWidth(s: string): number {
  const plain = stripAnsi(s);
  let w = 0;
  for (let i = 0; i < plain.length; i++) {
    const cp = plain.codePointAt(i)!;
    w += isWide(cp) ? 2 : 1;
    if (cp > 0xffff) i++; // surrogate pair advances by 2
  }
  return w;
}

/**
 * Truncate a string to at most `width` CELLS.
 *
 * <p>★ This is the function that has to be right. ★ A character-count
 * truncation puts a Chinese title's second half ON TOP of the frame's
 * border; a cell-count truncation ends with an ellipsis inside the frame.
 *
 * <p>The result is padded with spaces to exactly `width` cells when the
 * text is shorter — a row that ends short of the border makes the panel's
 * edge a staircase (R388j).
 *
 * @param text     the text to fit
 * @param width    the available width in CELLS
 * @param ellipsis what to show when the text does not fit
 */
export function fitCells(text: string, width: number, ellipsis = "…"): string {
  const w = Math.max(1, Math.floor(width));
  const plain = stripAnsi(text);
  // ★ The PADDING branch must pad by CELLS, not by characters. ★
  // `plain.padEnd(w)` would pad a 2-character CJK string to 12
  // CHARACTERS = 14 cells — a row 2 cells wider than the frame, which
  // pushes the border out. This is the very defect this module
  // exists to remove, and the first version of it had it in the
  // padding branch (the truncation branch was already cell-aware).
  if (cellWidth(plain) <= w) return padCells(plain, w);

  // Walk the string, accumulating cells, and stop one cell short for the
  // ellipsis.
  const budget = w - 1;
  if (budget <= 0) return ellipsis;
  let out = "";
  let used = 0;
  for (const ch of plain) {
    const cw = cellWidth(ch);
    if (used + cw > budget) break;
    out += ch;
    used += cw;
  }
  // Pad the ellipsis's own width so the row still fills the frame.
  const pad = Math.max(0, w - used - cellWidth(ellipsis));
  return out + ellipsis + " ".repeat(pad);
}

/**
 * The number of cells a string occupies, CJK-aware.
 *
 * <p>A thin alias for {@link cellWidth}, named for the question the panel
 * helpers actually ask.
 */
export function textCells(s: string): number {
  return cellWidth(s);
}

/**
 * Pad a string to EXACTLY `width` cells — never truncate.
 *
 * <p>★ This is the column-layout counterpart of {@link fitCells}. ★
 *
 * <p>A table's column width is computed from its content, and the
 * content is padded so the columns line up. `String.padEnd` pads by
 * CHARACTER COUNT, so a column containing a Chinese cell is one
 * character wider than the header claims and the divider drifts right
 * by one cell per CJK row.
 *
 * <p>Used by every place that lays out columns:
 *
 * <ul>
 *   <li>`"名字".padEnd(10)` → 8 characters, but 10 CELLS of text.
 *       `padEnd` adds 2 spaces → 12 cells. The column is now 2 cells
 *       wider than its header.</li>
 * </ul>
 *
 * <p>So the padding has to be computed in cells: pad to the cell
 * width, and if the text is ALREADY wider, return it unchanged (the
 * caller's width computation was wrong, and truncating here would
 * silently hide data — the column's own width computation should be
 * fixed instead).
 *
 * <p>ANSI is preserved: the escape bytes are stripped for the
 * measurement and the original string is what gets padded.
 */
export function padCells(text: string, width: number): string {
  const w = Math.max(0, Math.floor(width));
  const used = cellWidth(text);
  if (used >= w) return text;
  return text + " ".repeat(w - used);
}

/**
 * The cell width of the widest of several strings.
 *
 * <p>The column-width computation that must be cell-aware: a column
 * whose widest cell is Chinese is twice as wide as its character count
 * says.
 */
export function maxCells(values: readonly string[]): number {
  let max = 0;
  for (const v of values) {
    const w = cellWidth(v);
    if (w > max) max = w;
  }
  return max;
}
