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

// Shared "the needle must hit CODE" helper for the mutation batteries.
//
// WHY THIS EXISTS
//
// R379's battery had a case named "the countdown line stops promising
// the cancel affordance". The needle was
//
//     s.replace("· esc to cancel", "· continuing")
//
// and the file it patched was `src/continuationCountdown.ts`, whose
// FIRST occurrence of that text is in the JSDoc that QUOTES Claude
// Code:
//
//     * Claude Code says: `Continuing automatically at 3:45pm · esc to cancel`.
//
// The template literal that actually BUILDS the line comes later in
// the file. So the mutation changed a comment, the behaviour was
// identical, and the battery reported a hole — for the right reason,
// but only by accident.
//
// That is Lesson 1195 one level down: a mutation that changes the wrong
// occurrence is a no-op in disguise, and "the file changed" is not
// evidence that "the behaviour changed".
//
// THE FIX
//
// The comment strippers blank comments with spaces and PRESERVE
// LENGTH, so an offset found in the stripped text is the same offset
// in the original file. A needle that does not occur in CODE is
// therefore detectable, and this helper reports it as such instead of
// quietly returning the source unchanged.
//
// A needle that is not found at all is a different mistake (a typo, or
// a shape that moved) — it gets its own message, because "your needle
// is wrong" and "your needle hit a comment" need different fixes.

import { stripComments } from "./strip-comments.mjs";
import { javaCode } from "./java-strip.mjs";

/**
 * Replace the first occurrence of `matcher` IN CODE.
 *
 * @param {string} src the original source
 * @param {RegExp} matcher a NON-global regex, matched against the
 *   comment-stripped text. Non-global on purpose: `exec` on a global
 *   regex carries `lastIndex` between calls, so a shared regex object
 *   would hit a different place on the second call.
 * @param {string} replacement the replacement text — literal, never a
 *   `$`-pattern (this is a slice, not a `String.replace`).
 * @param {string} label the file name, for the error messages
 * @param {"ts"|"java"} kind which stripper to use
 * @returns {string} the mutated source
 * @throws {Error} when the needle is absent, or present only in a comment
 */
export function mutateInCode(src, matcher, replacement, label, kind = "ts") {
  const code = kind === "java" ? javaCode(src) : stripComments(src);
  const re = new RegExp(matcher.source, matcher.flags.replace(/g/g, ""));
  const m = re.exec(code);
  if (m) {
    const start = m.index;
    const end = start + m[0].length;
    return src.slice(0, start) + replacement + src.slice(end);
  }
  if (re.exec(src)) {
    throw new Error(
      `mutateInCode: the needle only occurs in a COMMENT in ${label} — a mutation there ` +
        "changes no behaviour. Scope the needle to the code (Lesson 1195).",
    );
  }
  throw new Error(
    `mutateInCode: the needle does not occur in ${label} at all — the shape moved, or the ` +
      "needle has a typo.",
  );
}
