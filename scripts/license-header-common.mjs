/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
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
 * The Javadoc form of the header, for Java.
 *
 * Two differences from the generic form, and both of them are forced by
 * google-java-format:
 *
 *  1. The second paragraph is ONE sentence with a `<p>` tag, not two lines.
 *     google-java-format reflows Javadoc prose to fill 100 columns, so a
 *     two-line sentence gets JOINED into one long line and re-wrapped. A
 *     single short sentence stays exactly as written.
 *
 *  2. The sentence is short enough not to wrap. The generic wording ("See the
 *     LICENSE file in the project root for the full license text.") is 84
 *     characters, which gjf wraps at column 100 once the ` * <p>` prefix is
 *     counted — and a wrapped license line looks like a mistake.
 *
 * The blank line inside the block is written as ` *` (star, then nothing) —
 * that is google-java-format's canonical spelling for an empty Javadoc line,
 * and it is the one thing that would otherwise make gjf want to reformat
 * every single Java file in the repo.
 */
export const JAVADOC_LINES = [
  `Copyright (c) ${YEAR} ${AUTHOR}`,
  "*",
  "<p>Licensed under the MIT License. See the LICENSE file in the project root.",
];

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
  let i = 0;
  // Skip a shebang / XML declaration / Rust inner attribute.
  if (lines[i] && lines[i].trim() !== "" && !markerRe.test(lines[i])) {
    const first = lines[i];
    if (
      first.startsWith("#!") ||
      first.startsWith("<?xml") ||
      first.startsWith("#![")
    ) {
      i++;
    }
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
    let j = i;
    while (j < lines.length && /^\s*(\/\/|#|REM |--)/.test(lines[j])) j++;
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
