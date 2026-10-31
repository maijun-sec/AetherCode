#!/usr/bin/env node
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
function blankNonCode(line, inComment) {
  // INSIDE A BLOCK COMMENT NOTHING IS CODE.
  //
  // This is the correction that a naive regex-literal rule gets wrong, and
  // it was caught by mutation-testing the checker: a doc comment whose line
  // ended with a block terminator was reported as CLEAN.
  //
  // The reason is obvious in hindsight. Prose that ends with the terminator
  // looks, to a position-based regex detector, like "an expression, then a
  // colon, then a regex literal opening" -- so the detector consumed the
  // terminator as regex body and blanked it. But the line is inside a
  // COMMENT. There is no expression, no colon operator, and no regex. The
  // scanner was inventing code that does not exist.
  //
  // So `inComment` is passed in, and when it is true the line is returned
  // UNTOUCHED: a block comment's contents are prose, and prose mentions
  // terminators without terminating anything.
  if (inComment) return line;

  let out = "";
  let i = 0;
  // Tracks whether a `/` may begin a regex literal. A slash starts a regex
  // only where an expression may start -- never right after an identifier,
  // a number, a literal, or a closing paren.
  let prevMeaningful = "";
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
    if (ch === SLASH && next === STAR) {
      // A BLOCK-comment opener, never a regex literal.
      //
      // This one bit the checker hard, and it is worth spelling out. The
      // first version of the regex-literal rule tested only "can a `/` start
      // a regex here?", and at the start of a line the answer was YES. So
      // the opener "/**" was scanned as a regex: the slash opened it, the
      // body was the star, and the scanner ran to the end of the line
      // looking for a closing slash. The result was that the checker
      // BLANKED THE OPENER of every doc comment it was meant to read --
      // so `inBlock` never became true, and the comment's real terminator
      // was then judged as a stray one.
      //
      // In JavaScript a `/` followed by `*` is always the block-comment
      // opener. There is no regex literal that starts with `/*`: an empty
      // regex is `//`, which is a line comment, and a regex whose body
      // starts with a star needs the star escaped, which is precisely the
      // case the checker must SEE.
      //
      // The opener is KEPT, not blanked: the state machine needs to see it
      // to know it is inside a comment.
      out += ch + next;
      i += 2;
      prevMeaningful = "literal";
      continue;
    }
    if (ch === SLASH && canStartRegex(prevMeaningful)) {
      // A regex literal: blank its body, but KEEP the delimiters so the
      // scanner's position bookkeeping stays right. The body is what may
      // contain a star-slash pair.
      out += ch;
      i++;
      let inClass = false;
      while (i < line.length) {
        const c = line[i];
        if (c === "\\") {
          out += "  ";
          i += 2;
          continue;
        }
        if (c === "[") inClass = true;
        else if (c === "]") inClass = false;
        else if (c === "/" && !inClass) {
          out += " ";
          i++;
          break;
        }
        out += " ";
        i++;
      }
      while (i < line.length && /[a-z]/.test(line[i])) {
        out += " ";
        i++;
      }
      prevMeaningful = "literal";
      continue;
    }
    out += ch;
    if (!/\s/.test(ch)) prevMeaningful = ch;
    i++;
  }
  return out;
}

/**
 * Can a `/` at this position begin a REGEX literal?
 *
 * The rule is about what PRECEDES the slash. A regex may start where an
 * expression may start, which is after an operator, a bracket, a comma, a
 * colon, or at the very beginning of the line. It may NOT start after an
 * identifier, a number, a literal, or a closing paren/bracket -- those make
 * the slash a DIVISION operator.
 */
function canStartRegex(prevMeaningful) {
  if (prevMeaningful === "") return true;
  if (prevMeaningful === "literal") return false;
  if (/[)\]]/.test(prevMeaningful)) return false;
  if (/[a-zA-Z0-9_$]/.test(prevMeaningful)) return false;
  return true;
}

/**
 * Strip a leading UTF-8 BOM from the text.
 *
 * WHY THIS MATTERS HERE, and it is not a detail.
 *
 * A BOM is METADATA, not content -- `readFileSync(file, "utf8")` keeps it, so
 * the first line of a BOM'd file is "\ufeff/**", not "/**". The checker's
 * opener test is `trimmed.startsWith("/*")`, and `trim()` does NOT remove
 * U+FEFF (it is not whitespace). So a BOM'd file's first block comment was
 * INVISIBLE to the checker: the opener was not recognised, `inBlock` stayed
 * false, and every terminator inside that first comment was then reported as
 * a STRAY terminator -- or, worse, a genuine early close was reported as
 * clean.
 *
 * The mutation test that exposed this: a BOM'd file whose doc comment closed
 * early was reported CLEAN, because the checker never entered comment mode.
 */
function stripBom(text) {
  return text.charCodeAt(0) === 0xfeff ? text.slice(1) : text;
}

let bad = 0;

for (const file of process.argv.slice(2)) {
  const text = stripBom(readFileSync(file, "utf8"));
  const lines = text.split(/\r?\n/);

  let inBlock = false;

  for (let i = 0; i < lines.length; i++) {
    const raw = lines[i];
    // Scan the CODE form of the line, not its prose form.
    const line = blankNonCode(raw, inBlock);
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
    //
    // There used to be a guard here: `&& !trimmed.startsWith(STAR)`. It was
    // meant to spare a line that begins with a star, but it spared EXACTLY
    // the case that matters -- a bare terminator on its own line, which is
    // what an early-closed doc comment leaves behind. The mutation that
    // proved it: a file with a lone terminator between two statements was
    // reported CLEAN.
    //
    // The guard was protecting nothing. A JSDoc continuation line (" * text")
    // contains no terminator, so the `includes` test already exempts it; and
    // a line that both starts with a star and contains a terminator is a
    // defect in every case.
    if (line.includes(TERMINATOR)) {
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
