// Copyright (c) 2026 maijun

// Licensed under the MIT License.
// See the LICENSE file in the project root for the full license text.

/**
 * A small scanner for TypeScript source: comments, string literals, and
 * bracket matching.
 *
 * ## Why it is shared
 *
 * R377 needed all three in `caps-table.mjs` (to find a table entry
 * without tripping over a `[` inside a reason string). R378 needs them
 * again, to extract the app-level key handler's body from `tui.tsx` and
 * count its chords.
 *
 * Two copies would be two scanners, and only one of them would be
 * tested — the same lesson that produced `java-strip.mjs` (R370) and
 * `load-ts-module.mjs` (R377).
 *
 * ## What it understands
 *
 * - `//` line comments and `/* *\/` block comments (blanked, not
 *   dropped, so offsets stay valid);
 * - `"`, `'` and backtick string literals (copied verbatim, so a
 *   bracket inside one is not counted);
 * - `\` escapes inside literals.
 *
 * It does NOT understand template-literal `${…}` interpolation: a
 * template with a nested `}` is treated as one literal until its
 * closing backtick. Every file this is used on is checked for that
 * case, and a scanner with a silent gap is exactly what this file
 * exists to prevent.
 *
 * ## R381: an apostrophe in prose is not a string
 *
 * A `'` or `"` with no partner on its own line cannot be a literal —
 * TypeScript string literals do not span lines — so it is PROSE. The
 * scanner used to read on until it found the NEXT quote of the same
 * kind, wherever that was, and `src/tui.tsx`'s "the daemon's estimate"
 * cost it 47 lines: the render of the whole grants panel, and every
 * assertion over that region, was reading whitespace.
 *
 * The rule now matches `strip-comments.mjs`, the other scanner in this
 * directory. Two scanners with two rules is two truths, and the one
 * that disagreed was the one nobody tested.
 */

/**
 * Split a source into CODE regions, STRING LITERALS and COMMENTS.
 *
 * <p>Comments are neither: a commented-out table entry must not be
 * mistaken for a live one, and a reason that lives inside a comment
 * must not be mistaken for the reason.
 *
 * <p>R381: the scanner also reports COMMENTS, so `blankNonCode` does
 * not need a second pass with its own idea of where a literal ends.
 * Two passes with two rules is how the apostrophe bug survived: this
 * scanner read past a newline and `strip-comments.mjs` stopped at one,
 * and the two disagreed about the same source.
 *
 * @param {string} src
 * @returns {{ code: {start: number, end: number}[], strings: {start: number, end: number, quote: string, value: string, terminated: boolean}[], comments: {start: number, end: number, kind: string}[] }}
 */
export function scan(src) {
  const code = [];
  const strings = [];
  const comments = [];
  let i = 0;
  const n = src.length;
  let codeStart = 0;

  const flush = (end) => {
    if (end > codeStart) code.push({ start: codeStart, end });
  };

  while (i < n) {
    const c = src[i];

    if (c === "/" && src[i + 1] === "/") {
      flush(i);
      const start = i;
      while (i < n && src[i] !== "\n") i++;
      comments.push({ start, end: i, kind: "line" });
      codeStart = i;
      continue;
    }

    if (c === "/" && src[i + 1] === "*") {
      flush(i);
      const start = i;
      const close = src.indexOf("*/", i + 2);
      i = close < 0 ? n : close + 2;
      comments.push({ start, end: i, kind: "block" });
      codeStart = i;
      continue;
    }

    if (c === '"' || c === "'" || c === "`") {
      const quote = c;
      const start = i;
      let j = start + 1;
      let value = "";
      let terminated = false;
      while (j < n) {
        if (src[j] === "\\") {
          value += src[j + 1] ?? "";
          j += 2;
          continue;
        }
        if (src[j] === quote) {
          terminated = true;
          break;
        }
        // R381: a single- or double-quoted literal CANNOT span lines in
        // TypeScript. So a quote with no partner on its own line is not
        // a string — it is PROSE (an apostrophe in JSX text).
        //
        // This scanner used to read on until it found the NEXT apostrophe,
        // wherever that was. `src/tui.tsx` says "the daemon's estimate",
        // and the scanner swallowed 47 lines — including the render of
        // the whole grants panel. Every gate that scans that file was
        // reading whitespace past the apostrophe without knowing it.
        //
        // The rule now matches `strip-comments.mjs` (the other scanner):
        // an unterminated quote ends at the newline.
        if (src[j] === "\n" && quote !== "`") break;
        value += src[j];
        j++;
      }

      if (!terminated && quote !== "`") {
        // Not a literal — an apostrophe in prose. Leave the text ALONE
        // and carry on from the next character.
        //
        // Blanking it would be worse than leaving it: the region is JSX
        // text, and the gates look for hand-written key hints INSIDE
        // that text. Blanking it would hide the very thing they hunt.
        i = start + 1;
        continue;
      }

      flush(start);
      const end = terminated ? j + 1 : Math.min(n, j);
      strings.push({ start, end, quote, value, terminated });
      i = end;
      codeStart = end;
      continue;
    }

    i++;
  }

  flush(n);
  return { code, strings, comments };
}

/**
 * Blank out everything that is not CODE — comments and string literals
 * — replacing it with spaces of the SAME LENGTH, so offsets stay
 * valid.
 *
 * <p>This is what makes bracket matching safe. The classic trap (the
 * one R366 recorded for Java, and this file's Java twin
 * `java-strip.mjs` has a self-test for) is an apostrophe inside a
 * comment: a scanner that treats `'` as a string opener swallows every
 * subsequent real line until it finds another apostrophe. `InputBox.tsx`
 * has such a comment, and the first version of `matchBrace` returned
 * -1 for its handler because of it.
 *
 * <p>R381: the same trap exists in JSX TEXT, where an apostrophe is
 * perfectly legal prose ("the daemon's estimate") and no partner quote
 * is coming. This function now blanks exactly the regions `scan()`
 * reports — one rule, one scanner.
 *
 * @param {string} src
 * @returns {string} the same length, with comments and literals blanked
 */
export function blankNonCode(src) {
  const out = src.split("");
  const { strings, comments } = scan(src);
  for (const region of [...strings, ...comments]) {
    for (let i = region.start; i < region.end && i < out.length; i++) {
      if (out[i] !== "\n") out[i] = " ";
    }
  }
  return out.join("");
}

/**
 * The index just past the `]` that closes the `[` at `open`.
 *
 * <p>String literals and comments are skipped, so a reason that
 * contains a bracket (or a backtick-quoted identifier) cannot end the
 * scan early.
 *
 * @param {string} src
 * @param {number} open index of the opening `[`
 * @returns {number} index just past the matching `]`, or -1
 */
export function matchBracket(src, open) {
  return matchPair(src, open, "[", "]");
}

/**
 * The index just past the `}` that closes the `{` at `open`.
 *
 * @param {string} src
 * @param {number} open index of the opening `{`
 * @returns {number} index just past the matching `}`, or -1
 */
export function matchBrace(src, open) {
  return matchPair(src, open, "{", "}");
}

/**
 * The shared bracket matcher.
 *
 * @param {string} src
 * @param {number} open index of the opening bracket
 * @param {string} o open bracket character
 * @param {string} c close bracket character
 * @returns {number} index just past the matching close bracket, or -1
 */
function matchPair(src, open, o, c) {
  const code = blankNonCode(src);
  let depth = 0;
  for (let i = open; i < code.length; i++) {
    if (code[i] === o) depth++;
    else if (code[i] === c) {
      depth--;
      if (depth === 0) return i + 1;
    }
  }
  return -1;
}
