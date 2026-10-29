#!/usr/bin/env node
// Copyright (c) 2026 maijun

// Licensed under the MIT License.
// See the LICENSE file in the project root for the full license text.

/**
 * check-jsdoc-in-comments.mjs — find block-comment terminators that would
 * silently break a .mjs file.
 *
 * A block-comment terminator (star followed by slash) inside a doc comment
 * closes it early. Everything after that point is parsed as CODE, and the file
 * either fails to parse or — worse — parses into something subtly different.
 *
 * This bit twice while writing the Java Javadoc header: a doc comment that
 * showed the shape of a Javadoc block contained a literal terminator, which
 * closed the comment and turned the rest of the file into a syntax error 60
 * lines away from the cause.
 *
 * THE TOOL ITSELF HAD THE BUG it was written to detect. Its own doc comment
 * spelled out a Javadoc block inline, so the checker could not even be RUN —
 * `node scripts/check-jsdoc-in-comments.mjs` died with a regex SyntaxError
 * before it looked at a single file.
 *
 * That is the lesson the tool exists to encode: a checker that cannot run
 * detects nothing, and it looks exactly like a checker that found nothing.
 *
 * The rule: inside a doc comment, never write the two characters star and
 * slash next to each other. Write them separately, or describe them in words.
 *
 * Usage: node scripts/check-jsdoc-in-comments.mjs <file>...
 */
import { readFileSync } from "node:fs";

const STAR = "*";
const SLASH = "/";
const TERMINATOR = STAR + SLASH;

/**
 * Strip the parts of a line that are NOT code: string literals, template
 * literals and line comments.
 *
 * Without this, the checker reports every line that merely MENTIONS a
 * terminator inside a string — for example a comparison against a two-character
 * string of star and slash — as a stray terminator. Those are false positives,
 * and a gate that cries wolf gets ignored, which is worse than no gate at all.
 *
 * The return value keeps the line's LENGTH intact (replaced characters
 * become spaces) so column positions still point at the right place.
 */
function blankNonCode(line) {
  let out = "";
  let i = 0;
  while (i < line.length) {
    const ch = line[i];
    const next = line[i + 1];
    if (ch === '"' || ch === "'" || ch === "`") {
      const quote = ch;
      out += " ";
      i++;
      while (i < line.length) {
        if (line[i] === "\\") {
          out += "  ";
          i += 2;
          continue;
        }
        if (line[i] === quote) {
          out += " ";
          i++;
          break;
        }
        out += " ";
        i++;
      }
      continue;
    }
    if (ch === SLASH && next === SLASH) {
      // A line comment runs to the end of the line.
      out += " ".repeat(line.length - i);
      break;
    }
    out += ch;
    i++;
  }
  return out;
}

let bad = 0;

for (const file of process.argv.slice(2)) {
  const text = readFileSync(file, "utf8");
  const lines = text.split(/\r?\n/);

  let inBlock = false;

  for (let i = 0; i < lines.length; i++) {
    const raw = lines[i];
    // Scan the CODE form of the line, not its prose form.
    const line = blankNonCode(raw);
    const trimmed = line.trim();

    if (inBlock) {
      // A complete one-line block: opener and terminator on the same line.
      if (trimmed.startsWith("/*") && trimmed.endsWith(TERMINATOR)) {
        inBlock = false;
        continue;
      }
      // The terminator on its own line closes the comment — that is the
      // NORMAL end of a doc comment, not a defect.
      if (trimmed === TERMINATOR) {
        inBlock = false;
        continue;
      }
      // A terminator in the MIDDLE of a doc-comment line is the defect:
      // it closes the comment early and everything after it becomes code.
      const idx = trimmed.indexOf(TERMINATOR);
      if (idx >= 0) {
        console.log(
          `${file}:${i + 1}: terminator inside a doc comment -> ${raw.trim()}`,
        );
        bad++;
        inBlock = false;
      }
      continue;
    }

    // Not in a block comment.
    if (trimmed.startsWith("/*")) {
      if (trimmed.endsWith(TERMINATOR) && trimmed.length > 4) continue; // one-liner
      inBlock = true;
      continue;
    }

    // A terminator outside any block comment is a stray terminator — the
    // exact symptom of a doc comment that closed early somewhere above.
    if (line.includes(TERMINATOR) && !trimmed.startsWith(STAR)) {
      console.log(`${file}:${i + 1}: stray block terminator -> ${raw.trim()}`);
      bad++;
    }
  }
}

if (bad > 0) {
  console.log(
    `\n${bad} stray terminator(s). A doc comment closed early — everything after it was parsed as code.`,
  );
  process.exit(1);
}
console.log("OK: no stray block-comment terminators.");
