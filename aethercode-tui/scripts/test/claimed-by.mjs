// Copyright (c) 2026 maijun

// Licensed under the MIT License.
// See the LICENSE file in the project root for the full license text.

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
