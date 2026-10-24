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
 */

/**
 * Split a source into CODE regions and STRING LITERALS.
 *
 * <p>Comments are neither: a commented-out table entry must not be
 * mistaken for a live one, and a reason that lives inside a comment
 * must not be mistaken for the reason.
 *
 * @param {string} src
 * @returns {{ code: {start: number, end: number}[], strings: {start: number, end: number, quote: string, value: string}[] }}
 */
export function scan(src) {
  const code = [];
  const strings = [];
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
      while (i < n && src[i] !== "\n") i++;
      codeStart = i;
      continue;
    }

    if (c === "/" && src[i + 1] === "*") {
      flush(i);
      const close = src.indexOf("*/", i + 2);
      i = close < 0 ? n : close + 2;
      codeStart = i;
      continue;
    }

    if (c === '"' || c === "'" || c === "`") {
      flush(i);
      const quote = c;
      const start = i;
      i++;
      let value = "";
      while (i < n) {
        if (src[i] === "\\") {
          value += src[i + 1] ?? "";
          i += 2;
          continue;
        }
        if (src[i] === quote) break;
        value += src[i];
        i++;
      }
      i++; // the closing quote
      strings.push({ start, end: i, quote, value });
      codeStart = i;
      continue;
    }

    i++;
  }

  flush(n);
  return { code, strings };
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
 * @param {string} src
 * @returns {string} the same length, with comments and literals blanked
 */
export function blankNonCode(src) {
  const out = src.split("");
  for (const s of scan(src).strings) {
    for (let i = s.start; i < s.end && i < out.length; i++) {
      if (out[i] !== "\n") out[i] = " ";
    }
  }
  // Blank the comments too: `scan` does not report them, so find them
  // with the same rules.
  let i = 0;
  const n = src.length;
  while (i < n) {
    const c = src[i];
    if (c === "/" && src[i + 1] === "/") {
      while (i < n && src[i] !== "\n") {
        if (out[i] !== "\n") out[i] = " ";
        i++;
      }
      continue;
    }
    if (c === "/" && src[i + 1] === "*") {
      const close = src.indexOf("*/", i + 2);
      const end = close < 0 ? n : close + 2;
      for (let k = i; k < end && k < out.length; k++) {
        if (out[k] !== "\n") out[k] = " ";
      }
      i = end;
      continue;
    }
    if (c === '"' || c === "'" || c === "`") {
      // skip the literal
      const q = c;
      i++;
      while (i < n) {
        if (src[i] === "\\") {
          i += 2;
          continue;
        }
        if (src[i] === q) break;
        i++;
      }
      i++;
      continue;
    }
    i++;
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
