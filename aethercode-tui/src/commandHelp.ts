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
 * R388k — the command help list's DATA, without the React.
 *
 * ## Why this is a separate module
 *
 * The rows the `/help` panel shows are a PROJECTION of the one command
 * catalog (`src/commands.ts`). The projection and the filter are pure
 * functions, and they are the part that can be WRONG — so they have to
 * be testable WITHOUT a renderer.
 *
 * Putting them in the component made them untestable: the gate had to
 * bundle a `.tsx` file, which pulls in `react` and `ink`, and a bundle
 * written to a temp directory cannot resolve them.
 *
 * → **Lesson 1323 — pure logic inside a component is logic you can
 * only test through the component.** Splitting the data out of the view
 * is what makes the view's correctness checkable at all.
 *
 * ## The list is not written here
 *
 * The rows come from `src/commands.ts` (`SLASH_COMMANDS_DETAILED`).
 * Writing the command names into this file would make a SECOND copy of
 * a list that already had one — and R380's whole subject is that a
 * second copy drifts silently.
 */

import { SLASH_COMMANDS_DETAILED } from "./commands.js";

/**
 * One row of the help list.
 *
 * A row is a command the user can RUN. Aliases are included — the user
 * may type them — but they are marked, so the list never implies that
 * `/theme-pick` and `/theme` are two different features.
 */
export interface HelpRow {
  name: string;
  args?: string;
  description: string;
  group: string;
  isAlias: boolean;
}

/**
 * Project the catalog into the rows the help panel shows.
 *
 * The projection is DERIVED, never hand-written — the same rule the
 * `/help` TEXT already follows (R380).
 */
export function buildHelpRows(
  catalog: typeof SLASH_COMMANDS_DETAILED = SLASH_COMMANDS_DETAILED,
): HelpRow[] {
  return catalog.map((c) => ({
    name: c.name,
    args: c.args,
    description: c.description,
    group: c.group,
    isAlias: Boolean(c.aliasOf),
  }));
}

/**
 * Filter the rows by a typed substring.
 *
 * The match is over the command name AND the description, so "sdd"
 * finds `/sdd-approve` and "approve the current phase" both. An empty
 * filter matches everything; a filter that matches nothing yields
 * nothing — the panel says so rather than pretending the list is
 * complete.
 */
export function filterHelpRows(rows: HelpRow[], needle: string): HelpRow[] {
  const q = needle.trim().toLowerCase();
  if (q === "") return rows;
  return rows.filter(
    (r) => r.name.toLowerCase().includes(q) || (r.description ?? "").toLowerCase().includes(q),
  );
}

/**
 * The scroll window for a list of `total` rows, with `highlight`
 * selected and `maxVisible` rows visible.
 *
 * The window follows the highlight, so the selected row is always
 * visible — and it never runs past either end of the list, so the
 * counter the user reads is honest.
 */
export function helpWindow(
  total: number,
  highlight: number,
  maxVisible: number,
): { start: number; end: number } {
  if (total <= 0) return { start: 0, end: 0 };
  const visible = Math.max(1, maxVisible);
  const h = Math.max(0, Math.min(highlight, total - 1));
  const start = Math.max(0, Math.min(h - Math.floor(visible / 2), Math.max(0, total - visible)));
  const end = Math.min(total, start + visible);
  return { start, end };
}
