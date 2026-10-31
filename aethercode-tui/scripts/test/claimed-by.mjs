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

// Does a renderer CLAIM a sentinel, rather than merely mention it?
//
// ## Why this is shared
//
// R362 wrote it, R385 wrote it again, and R386 would have written it a
// THIRD time. Two copies of a scanner is one copy too many (R381:
// "a second copy of a truth drifts, and it drifts silently"), and
// three copies of a *definition* is worse: the definition says what
// "claims a sentinel" MEANS, and three definitions can mean three
// things.
//
// The two copies that existed were byte-identical, which is exactly
// the situation where a drift goes unnoticed until it matters.
//
// ## What "claims" means
//
// A bare mention in a comment or a help string is not a claim, and a
// short-circuited branch (`false && local === "__X__"`) is invisible
// to a source pin by construction. The four forms a renderer actually
// routes on are:
//
//   - `case "__X__":`      — a switch arm
//   - `=== "__X__"`       — an equality test
//   - `=== '__X__'`       — the single-quoted spelling
//   - `.startsWith("__X__")` — a parameterised sentinel
//
// The caller is responsible for scanning the COMMENT-STRIPPED text
// (see `strip-comments.mjs`): a mention inside a comment is prose,
// and prose that happens to contain a sentinel is not a claim.
//
// ## R386: the closing quote is REQUIRED — and its absence was a hole
//
// The first version of this pattern ended at the sentinel:
//
//     (?:case\s+['"`]__X__['"`]|===\s*['"`]__X__|\.startsWith\(\s*['"`]__X__)
//
// and the `===` alternative had no closing quote, so it matched the
// PREFIX of `=== "__X__X"`. A renderer that renamed its sentinel
// still counted as claiming it.
//
// The R386 mutation battery found it: mutating
// `slash?.local === "__THEMES_PICK__"` to `=== "__THEMES_PICK__X"`
// left the gate GREEN. The `case` and `===` forms carry a complete
// string literal, so they must require the closing quote.
//
// The `startsWith` form is different on purpose: a parameterised
// sentinel is written `startsWith("__THEME__:")` — the sentinel is a
// PREFIX of the literal there, by design. So that alternative stays
// unanchored, and the two kinds are not interchangeable.

/**
 * @param {string} src the COMMENT-STRIPPED source of a renderer
 * @param {string} sentinel e.g. `__THEMES_PICK__`
 * @returns {boolean} true when the renderer routes on the sentinel
 */
export function claimedBy(src, sentinel) {
  const esc = sentinel.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  return new RegExp(
    `(?:case\\s+['"\`]${esc}['"\`]|===\\s*['"\`]${esc}['"\`]|\\.startsWith\\(\\s*['"\`]${esc})`,
  ).test(src);
}
