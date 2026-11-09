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
 * R388p — the checkpoint list's WINDOW.
 *
 * <p>★ WHY THIS MODULE EXISTS ★
 *
 * <p>The rewind menu rendered EVERY checkpoint. Measured: 40 checkpoints
 * produced a 53-row frame — taller than a 40-row terminal, so the panel
 * ran off the screen and the user could not see the entry they had
 * selected. The list grew without bound, and so did the frame.
 *
 * <p>So the list needs a WINDOW: a slice of the checkpoints that fits the
 * space the panel has, and that FOLLOWS the selection.
 *
 * <p>★ WHY A SEPARATE MODULE ★ A window is arithmetic over a selection
 * and a size — and arithmetic is exactly what a gate should be able to
 * check directly, without rendering anything. If the window lived inside
 * the component, the only way to test it would be to render the
 * component and count rows, which cannot distinguish "the window is
 * right" from "the window happens to be right for this one size".
 *
 * <p>★ WHY A PLAIN .ts FILE ★ Like {@code terminalGeometry.ts}: a gate
 * script must be able to import it. A {@code .tsx} component cannot be
 * imported by a Node gate (the bundle would pull in React/Ink).
 */

/**
 * The window over a list: which entries are visible, and where the
 * selection is inside the window.
 */
export interface ListWindow {
  /** The index of the FIRST visible entry. */
  start: number;
  /** The index of the LAST visible entry (inclusive). */
  end: number;
  /** How many entries there are in total. */
  total: number;
  /** How many entries fit. */
  size: number;
  /** How many entries are NOT visible (before the window). */
  hiddenBefore: number;
  /** How many entries are NOT visible (after the window). */
  hiddenAfter: number;
}

/**
 * The window that shows `selected` inside a list of `total` entries,
 * with at most `size` visible at a time.
 *
 * <p>★ THE SELECTION IS ALWAYS VISIBLE ★ That is the whole point of
 * the window: the user must be able to see the entry they selected. So
 * the window is moved to include the selection, and — when the
 * selection is at an edge — it is moved as little as possible, so the
 * list does not jump around under the cursor.
 *
 * <p>★ THE ORDER MATTERS ★ The window is first positioned so the
 * selection is at the BOTTOM (when moving down) or the TOP (when
 * moving up), and only then clamped to the list's bounds. Computing
 * the clamp first would let the selection slip off the top of the
 * window for the first entries.
 *
 * <p>★ AN EMPTY LIST IS AN EMPTY WINDOW ★ Not a crash and not a window
 * of size 1 pointing at nothing.
 *
 * @param total    how many entries the list has
 * @param selected the index of the selected entry
 * @param size     how many entries fit
 */
export function listWindow(total: number, selected: number, size: number): ListWindow {
  const n = Math.max(0, Math.floor(total));
  const cap = Math.max(1, Math.floor(size));
  if (n === 0) {
    return { start: 0, end: -1, total: 0, size: cap, hiddenBefore: 0, hiddenAfter: 0 };
  }
  // A selection outside the list is a bug in the caller, not a crash
  // here: clamp it, so the window is still coherent.
  const sel = Math.min(n - 1, Math.max(0, Math.floor(selected)));

  // Put the selection at the bottom of the window, then let the window
  // slide back into the list's bounds.
  let start = sel - cap + 1;
  if (start < 0) start = 0;
  // If the list is shorter than the window, show it all.
  if (start + cap > n) start = Math.max(0, n - cap);
  const end = Math.min(n - 1, start + cap - 1);
  return {
    start,
    end,
    total: n,
    size: cap,
    hiddenBefore: start,
    hiddenAfter: Math.max(0, n - 1 - end),
  };
}

/**
 * The index of the selected entry, moved by `delta`, clamped to the
 * list.
 *
 * <p>★ WHY THIS IS HERE AND NOT IN THE COMPONENT ★ The OLD code had
 * two different clamp rules for the two lists: the ACTION list WRAPPED
 * (modulo) and the CHECKPOINT list CLAMPED. And the checkpoint list
 * opened with the selection at the LAST entry — so the very first
 * press of the down arrow did nothing at all: the selection was already
 * at the bottom, and the clamp kept it there.
 *
 * <p>A key that does nothing is worse than a key that is absent: the
 * user concludes the feature is broken and does not try the other
 * direction.
 *
 * <p>★ THE SELECTION WRAPS ★ Wrapping is the behaviour every list
 * menu uses, and it makes the down arrow meaningful from the newest
 * entry (where the menu opens): pressing down at the bottom moves to
 * the OLDEST entry, and pressing up at the top moves to the newest.
 *
 * @param total the number of entries
 * @param current the current index
 * @param delta how far to move (+1 down, -1 up)
 */
export function moveSelection(total: number, current: number, delta: number): number {
  const n = Math.max(0, Math.floor(total));
  if (n === 0) return 0;
  const cur = Math.min(n - 1, Math.max(0, Math.floor(current)));
  const step = Math.trunc(delta);
  if (step === 0) return cur;
  // JavaScript's % keeps the sign of the dividend, so a negative
  // result has to be brought back into range by hand.
  const next = (((cur + step) % n) + n) % n;
  return next;
}
