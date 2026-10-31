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

// Shared comment stripper for the source-pin gates.
//
// WHY THIS EXISTS INSTEAD OF A REGEX
//
// Every naive version of this is:
//
//     src.replace(/\/\*[\s\S]*?\*\//g, blank).replace(/\/\/[^\n]*/g, "")
//
// which is wrong the moment a `/*` or `//` appears INSIDE a string or
// template literal. `line.ts` has exactly that, at the line
//
//     reason: "there is no update or delete RPC on memory/* — only append, get and list.",
//
// A regex stripper reads `/*` as a comment opener there and swallows
// everything up to the next `*/` — 916 lines and ~24 KB of REAL CODE,
// silently. Every assertion below that offset then becomes a test of
// whitespace. It cost a real debugging detour during R364: a
// `probe(ctx, "switchProject")` assertion failed for no reason other
// than that the code had been blanked out.
//
// So: a scanner, not a regex. It tracks line comments, block
// comments, single/double-quoted strings, and template literals (with
// `${ }` nesting), and blanks comment bodies while preserving
// newlines and string length so positional assertions stay valid.
//
// REGEX LITERALS are handled with a standard prev-token heuristic. It
// is not perfect JS parsing and does not need to be: a mis-detected
// regex literal only matters if it contains an unbalanced quote or a
// `/*`, and the `assertNotOverStripped` sanity check below catches the
// cases where that goes wrong.

const REGEX_PRECEDERS = new Set([
  "(",
  ",",
  "=",
  ":",
  "[",
  "!",
  "&",
  "|",
  "?",
  "{",
  "}",
  ";",
  "+",
  "-",
  "*",
  "%",
  "<",
  ">",
  "~",
  "^",
  "\n",
]);
const REGEX_KEYWORDS = new Set([
  "return",
  "typeof",
  "instanceof",
  "in",
  "of",
  "new",
  "delete",
  "void",
  "throw",
  "case",
  "do",
  "else",
  "yield",
  "await",
]);

/**
 * Blank out comments, preserving every non-comment character and all
 * newline positions.
 *
 * @param {string} src
 * @returns {string}
 */
export function stripComments(src) {
  let out = "";
  let i = 0;
  const n = src.length;

  /** Last non-whitespace, non-comment char emitted — drives regex detection. */
  let prevSignificant = "";
  let prevWord = "";

  const blank = (text) => text.replace(/[^\n]/g, " ");

  while (i < n) {
    const c = src[i];
    const d = src[i + 1];

    // --- line comment ---
    if (c === "/" && d === "/") {
      let j = i;
      while (j < n && src[j] !== "\n") j++;
      out += blank(src.slice(i, j));
      i = j;
      continue;
    }

    // --- block comment ---
    if (c === "/" && d === "*") {
      let j = i + 2;
      while (j < n && !(src[j] === "*" && src[j + 1] === "/")) j++;
      const end = Math.min(n, j + 2);
      out += blank(src.slice(i, end));
      i = end;
      continue;
    }

    // --- quoted string ---
    if (c === '"' || c === "'") {
      const quote = c;
      let j = i + 1;
      while (j < n) {
        if (src[j] === "\\") {
          j += 2;
          continue;
        }
        if (src[j] === quote) {
          j++;
          break;
        }
        // An unterminated string would mean we lost track of state;
        // bail at the newline rather than swallowing the file.
        if (src[j] === "\n" && quote !== "`") break;
        j++;
      }
      const text = src.slice(i, j);
      out += text;
      prevSignificant = quote;
      prevWord = "";
      i = j;
      continue;
    }

    // --- template literal, with ${ } nesting ---
    if (c === "`") {
      let j = i + 1;
      let depth = 0;
      while (j < n) {
        if (src[j] === "\\") {
          j += 2;
          continue;
        }
        if (src[j] === "$" && src[j + 1] === "{") {
          depth++;
          j += 2;
          continue;
        }
        if (src[j] === "}" && depth > 0) {
          depth--;
          j++;
          continue;
        }
        if (src[j] === "`" && depth === 0) {
          j++;
          break;
        }
        j++;
      }
      const text = src.slice(i, j);
      out += text;
      // Inside a template we may have emitted real code from ${...};
      // approximate the "previous token" conservatively.
      prevSignificant = ")";
      prevWord = "";
      i = j;
      continue;
    }

    // --- regex literal (heuristic) ---
    if (c === "/") {
      const before = prevSignificant;
      const looksLikeRegex =
        before === "" || REGEX_PRECEDERS.has(before) || REGEX_KEYWORDS.has(prevWord);
      if (looksLikeRegex) {
        let j = i + 1;
        let inClass = false;
        let closed = false;
        while (j < n && src[j] !== "\n") {
          if (src[j] === "\\") {
            j += 2;
            continue;
          }
          if (src[j] === "[") inClass = true;
          else if (src[j] === "]") inClass = false;
          else if (src[j] === "/" && !inClass) {
            j++;
            closed = true;
            break;
          }
          j++;
        }
        if (closed) {
          while (j < n && /[a-z]/.test(src[j])) j++; // flags
          out += src.slice(i, j);
          prevSignificant = "/";
          prevWord = "";
          i = j;
          continue;
        }
      }
    }

    // --- ordinary character ---
    out += c;
    if (!/\s/.test(c)) {
      prevSignificant = c;
      if (/[A-Za-z0-9_$]/.test(c)) prevWord += c;
      else prevWord = "";
    }
    i++;
  }

  return out;
}

/**
 * How much of `original` was blanked, as a fraction of its non-newline
 * characters.
 *
 * NOTE: do NOT measure this as `original.length - stripped.length`.
 * `blank()` preserves length by design (that is what keeps positional
 * assertions valid), so that subtraction is ALWAYS ZERO and any guard
 * built on it is decorative — which is exactly how the first version
 * of this function shipped. Compare actual character content.
 *
 * @param {string} original
 * @param {string} stripped
 */
export function strippedRatio(original, stripped) {
  const n = Math.min(original.length, stripped.length);
  let blanked = 0;
  let total = 0;
  for (let i = 0; i < n; i++) {
    if (original[i] === "\n") continue;
    total++;
    if (stripped[i] !== original[i]) blanked++;
  }
  return total === 0 ? 0 : blanked / total;
}

/**
 * Guard against the stripper itself eating real code.
 *
 * A healthy strip of a TS/TSX source file blanks at most ~65% of its
 * non-newline characters; the median in this repo is 26% and the
 * densest real file (`src/rpc.ts`) is 64%. The old regex stripper,
 * by contrast, reported 87-90% on every file it touched.
 *
 * The threshold sits at 85% — well clear of the real maximum, low
 * enough that a desync (which swallows a contiguous multi-KB region)
 * trips it. This is a BACKSTOP for future bugs in this scanner, not
 * the primary defence: the scanner itself is what handles `/*`
 * inside a string literal, and a ratio check cannot tell a
 * legitimately comment-dense file from a desync, only a catastrophic
 * one from either.
 *
 * When this fires, every assertion over the result is reading
 * whitespace, so it throws rather than warning.
 *
 * @param {string} original
 * @param {string} stripped
 * @param {string} label
 */
export function assertNotOverStripped(original, stripped, label) {
  const ratio = strippedRatio(original, stripped);
  if (ratio > 0.85) {
    throw new Error(
      `stripComments blanked ${(ratio * 100).toFixed(1)}% of ${label} ` +
        `(${original.length} chars in). That means the scanner desynced — every ` +
        `assertion over the result would be reading whitespace. Look for an ` +
        `unbalanced quote or a regex literal it mis-classified.`,
    );
  }
  return ratio;
}

/** Convenience: strip + sanity-check in one call. */
export function codeOf(src, label) {
  const stripped = stripComments(src);
  assertNotOverStripped(src, stripped, label);
  return stripped;
}
