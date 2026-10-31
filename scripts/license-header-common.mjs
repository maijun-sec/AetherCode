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
 * license-header-common.mjs — the shared logic of the licence-header tools.
 *
 * WHY A SHARED MODULE
 *
 * "Does this file already have a header?" and "what line ending does this
 * file's body use?" are judgements that MUST be identical in the inserter and
 * the remover. If each script implements them separately, they drift — and the
 * drift is silent, because the symptom of a drifted pair is not "the tool
 * failed", it is "the file is subtly wrong" (a mixed-line-ending file, a
 * header inserted twice, a file's own documentation deleted).
 *
 * That is not hypothetical. The EOL check existed in BOTH scripts as
 * `text.includes("\r\n") ? "\r\n" : "\n"`, and that rule is wrong: a stale
 * CRLF header on an LF body makes the whole file look CRLF, so the next run
 * writes another CRLF header. 108 files ended up with mixed line endings,
 * and every diff of them showed the entire file as rewritten.
 *
 * ONE implementation, TWO consumers.
 */

/** The licence text. The single source of truth for the wording. */
export const AUTHOR = "maijun";
export const YEAR = "2026";

/** The marker that identifies our header. */
export const MARKER = `Copyright (c) ${YEAR} ${AUTHOR}`;

/**
 * The MIT permission text — the standard SPDX "MIT" wording, verbatim.
 *
 * This is the FULL grant, not a paraphrase of it. The reason is legal, not
 * stylistic: MIT's own condition is "The above copyright notice and this
 * permission notice shall be included in all copies or substantial
 * portions of the Software." A header that says only "Licensed under the MIT
 * License" carries the copyright notice but NOT the permission notice — so a
 * file copied out of this repository on its own would carry no grant at all.
 *
 * SPDX's own guidance is explicit on this point: "ensure the LICENSE file
 * and any header comments use the standard license text." A shortened
 * paraphrase ("<p>Licensed under the MIT License. See the LICENSE file...")
 * is not the standard text.
 *
 * The line breaks are the canonical MIT wrapping (79-column body) so the
 * header reads identically in every file and in the LICENSE file itself.
 */
export const MIT_BODY = [
  "Permission is hereby granted, free of charge, to any person obtaining a copy",
  'of this software and associated documentation files (the "Software"), to deal',
  "in the Software without restriction, including without limitation the rights",
  "to use, copy, modify, merge, publish, distribute, sublicense, and/or sell",
  "copies of the Software, and to permit persons to whom the Software is",
  "furnished to do so, subject to the following conditions:",
  "",
  "The above copyright notice and this permission notice shall be included in all",
  "copies or substantial portions of the Software.",
  "",
  'THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR',
  "IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,",
  "FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE",
  "AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER",
  "LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,",
  "OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE",
  "SOFTWARE.",
];

/**
 * The canonical header body: the copyright line, a blank line, then the MIT
 * permission text.
 *
 * This is the ONE source of truth for the wording. Every language style
 * renders from these lines, so the wording can never drift between languages
 * — and the LICENSE file is generated from the same array.
 */
export const HEADER_LINES = [
  `Copyright (c) ${YEAR} ${AUTHOR}`,
  "",
  ...MIT_BODY,
];

/**
 * Render the header as a plain block comment.
 *
 * WHY A PLAIN BLOCK COMMENT AND NOT JAVADOC.
 *
 * The previous form was a Javadoc block (slash-star-star ... slash-star) with
 * a `<p>` tag. That had two costs that a plain block does not:
 *
 *  - google-java-format REFLOWS Javadoc prose to fill 100 columns. A licence
 *    header is not prose, so every line had to be written to survive the
 *    reflow — and `package-info.java` files, which have their OWN Javadoc,
 *    needed the licence and the file's documentation split into two blocks
 *    so the original text was never re-wrapped.
 *
 *  - A Javadoc block is DOCUMENTATION. Attaching the licence to it makes the
 *    licence part of the class's API documentation, which is not what a
 *    licence header is.
 *
 * A plain block comment is preserved byte-for-byte by google-java-format
 * (it does not reflow non-Javadoc block comments), so:
 *
 *  - the header needs no formatter to be canonical, and
 *  - it does not collide with a file's own Javadoc.
 *
 * The lines are prefixed with " * " so the block reads as a comment, and the
 * body lines stay at or under 79 columns.
 */
export function renderBlockHeader() {
  return ["/*", ...HEADER_LINES.map((l) => (l === "" ? " *" : ` * ${l}`)), " */"];
}

/**
 * Render the header as a run of line comments.
 *
 * EVERY line carries the prefix, including the blank ones inside the MIT
 * text. A blank line written as a truly empty line would END the comment
 * run: in Python, in shell, and in YAML an unprefixed blank line is not
 * part of the comment, and a reader (or the removal script) scanning for
 * the end of the run would stop there. Writing the blank as the bare prefix
 * keeps the whole header one contiguous comment block, which is also how
 * every hand-written MIT header in the wild looks.
 *
 * @param {string} prefix the comment prefix, e.g. "// " or "# "
 */
export function renderLineHeader(prefix) {
  return HEADER_LINES.map((l) => (l === "" ? prefix.trimEnd() : `${prefix}${l}`));
}

/**
 * Detect the line ending a file's CONTENT uses.
 *
 * The naive rule — "if the text contains \r\n anywhere, it is a CRLF file" —
 * is WRONG, and it was silently corrupting 108 files.
 *
 * Here is how: a file is LF-bodied, but a previous run inserted a CRLF header
 * into it. On the NEXT run, the text now contains "\r\n" (in the header), so
 * the naive rule classifies the whole file as CRLF and writes a CRLF header.
 * The file is then MIXED: a CRLF header on an LF body. Every diff shows the
 * entire file as rewritten, and only `git diff --ignore-all-space` reveals
 * that five lines were actually added.
 *
 * The correct question is not "does this text contain a CRLF" but "what line
 * ending does the file's BODY use". So the header — if there is one — is
 * removed first, and the BODY's ending decides.
 *
 * If the body is itself mixed, we keep the file's own dominant ending rather
 * than normalising it: rewriting a file's line endings is not this tool's
 * job, and doing it would produce a diff nobody asked for.
 */
export function detectEol(text) {
  const stripped = stripLeadingHeaderForEol(text);
  const crlf = (stripped.match(/\r\n/g) || []).length;
  const lf = (stripped.match(/\n/g) || []).length - crlf;
  if (crlf === 0 && lf === 0) return text.includes("\r\n") ? "\r\n" : "\n";
  // CRLF wins a tie; otherwise the majority ending wins.
  if (crlf > lf) return "\r\n";
  if (lf > crlf) return "\n";
  return text.includes("\r\n") ? "\r\n" : "\n";
}

/**
 * True when a line is one of the DIRECTIVES that may precede the header.
 *
 * These are language constructs, not licence text:
 *   - a shebang (`#!`)
 *   - an XML declaration (`<?xml ... ?>`)
 *   - a Rust inner attribute (`#![...]`)
 *   - a Python encoding declaration (`# -*- coding: utf-8 -*-`)
 */
function isDirectiveLine(line) {
  const t = line.trim();
  if (t.startsWith("#!")) return true;
  if (t.startsWith("<?xml")) return true;
  if (/^#!\[/.test(t)) return true;
  if (/^[ \t\f]*#.*coding[:=][ \t]*[-\w.]+/.test(line)) return true;
  return false;
}

/**
 * Remove a leading licence header from `text`, for the sole purpose of
 * measuring the body's line ending.
 *
 * Handles the comment forms this tool emits: a line-comment run and a block
 * comment. It is deliberately permissive — it only needs to remove the header
 * that WE might have inserted, and a false "not a header" just falls back to
 * measuring the whole text.
 */
export function stripLeadingHeaderForEol(text) {
  const lines = text.split("\n");
  if (lines.length < 2) return text;

  const markerRe = /Copyright \(c\) \d{4} \S/;
  // Skip the leading DIRECTIVES: a shebang, an XML declaration, a Rust inner
  // attribute, a Python encoding declaration, or a module docstring. The
  // header lands AFTER them, and the EOL of the header must not be confused
  // with the EOL of a directive.
  //
  // The loop runs until it stops making progress, so a Python file with a
  // shebang AND an encoding declaration AND a docstring is handled.
  let i = 0;
  for (;;) {
    if (i >= lines.length) break;
    const line = lines[i];
    if (line.trim() === "") {
      // A blank line: it may separate the directives from the header.
      // Look ahead — if the next non-blank is a directive, keep going.
      let j = i;
      while (j < lines.length && lines[j].trim() === "") j++;
      if (j < lines.length && isDirectiveLine(lines[j])) {
        i = j;
        continue;
      }
      break;
    }
    if (isDirectiveLine(line)) {
      i++;
      continue;
    }
    break;
  }

  // A block-comment header.
  if (lines[i] && lines[i].trim().startsWith("/*")) {
    let j = i;
    while (j < lines.length && !lines[j].trim().endsWith("*/")) j++;
    if (j < lines.length && j - i < 12) {
      const block = lines.slice(i, j + 1).join("\n");
      if (markerRe.test(block)) return lines.slice(j + 1).join("\n");
    }
  }

  // A line-comment header.
  if (lines[i] && markerRe.test(lines[i])) {
    const isComment = (l) => /^\s*(\/\/|#|REM |--)/.test(l);
    let j = i;
    while (j < lines.length) {
      if (isComment(lines[j])) {
        j++;
        continue;
      }
      if (lines[j].trim() === "") {
        // A blank line: it belongs to the run only if a comment follows.
        let k = j;
        while (k < lines.length && lines[k].trim() === "") k++;
        if (k < lines.length && isComment(lines[k])) {
          j = k;
          continue;
        }
      }
      break;
    }
    return lines.slice(j).join("\n");
  }

  return text;
}

/**
 * True when the file already carries our header.
 *
 * The header is normally in the first few lines, BUT for a Python file with
 * a long module docstring the header lands BELOW the docstring — which can
 * be 100+ lines down. So we scan the whole file for the marker.
 */
export function alreadyHasHeader(content) {
  return content.includes(MARKER);
}
