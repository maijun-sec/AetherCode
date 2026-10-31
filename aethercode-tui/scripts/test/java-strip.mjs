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

// Shared Java comment stripper for source-pin gates.
//
// Extracted in R370 because a second gate needed it. The lesson
// behind the extraction is in r366-rpc-names.test.mjs's
// `javaCodeHandlesTheApostropheTrap` self-test: a scanner bug is a
// GATE bug, and the R367 version of this code originally swallowed
// 2.4 KB of real `DaemonRunner` because a `//` comment contained an
// apostrophe. Two copies would mean two scanners, one of which
// nobody tests.
//
// Two properties this must preserve, both asserted by that self-test:
//
//   1. LENGTH IS PRESERVED. Comment bytes become spaces, never "".
//      Any caller that slices by offset into the stripped text is
//      reading the right span of the ORIGINAL file.
//   2. STRING LITERALS ARE COPIED VERBATIM. `"sessionId"` inside a
//      string must still be findable; that is most of what the gates
//      look for.
//
// Java's text blocks (`"""`) are NOT handled. `AetherCodeMethods.java`
// has none, and `javaCodeHandlesBlockComments` in the r366 gate is the
// place to add one if that ever changes — a silent gap in a scanner
// is exactly the failure this file exists to prevent.
//
// R384 added the third property this file must have. The first two
// are above; the third is:
//
//   3. BRACE COUNTING SKIPS STRING LITERALS. `javaCode` copies string
//      literals verbatim (the gates need to find `"sessionId"` inside
//      strings), so a `}` inside a literal is a real `}` in the
//      stripped text. The old naive count closed a method body at
//      that brace and handed the caller a TRUNCATED body — which
//      makes every assertion over it pass VACUOUSLY. Measured on the
//      real daemon sources: 978 literals with a brace in
//      `AetherCodeMethods.java`, and 5 of the methods the gates
//      extract were truncated. None of them bit at the time, which is
//      what makes this class of bug expensive.
//
// Lesson 1212 — a scanner with no self-test has its bugs found by
// whichever gate happens to trip over them, which is not a plan.
// The self-tests for these three live in `java-strip.test.mjs`.

/**
 * @param {string} src raw Java source
 * @returns {string} same length, comments replaced by spaces
 */
export function javaCode(src) {
  let out = "";
  let i = 0;
  const n = src.length;
  while (i < n) {
    const c = src[i];
    if (c === '"' || c === "'") {
      const q = c;
      out += c;
      i++;
      while (i < n) {
        const d = src[i];
        out += d;
        i++;
        if (d === "\\" && i < n) {
          out += src[i];
          i++;
        } else if (d === q) break;
      }
      continue;
    }
    if (c === "/" && src[i + 1] === "/") {
      while (i < n && src[i] !== "\n") {
        out += " ";
        i++;
      }
      continue;
    }
    if (c === "/" && src[i + 1] === "*") {
      const close = src.indexOf("*/", i + 2);
      const end = close < 0 ? n : close + 2;
      for (let k = i; k < end; k++) out += src[k] === "\n" ? "\n" : " ";
      i = end;
      continue;
    }
    out += c;
    i++;
  }
  return out;
}

/**
 * A mask of the STRING / CHARACTER LITERALS in an already stripped
 * text: `mask[i] === 1` means "offset i is inside a literal".
 *
 * `javaCode` BLANKS comments but COPIES string literals verbatim —
 * on purpose, because the gates look for things like `"sessionId"`
 * inside strings. The consequence is that a `{` or `}` inside a
 * string literal is still a brace in the stripped text, and a naive
 * brace count closes the method body early.
 *
 * Measured on the real daemon sources: `AetherCodeMethods.java`
 * carries 978 string literals containing a brace, and 5 of the
 * methods the gates extract were truncated by the naive count. None
 * of the truncations happened to bite at the time, which is exactly
 * what makes this kind of bug expensive — it reports like a pass
 * until the day it doesn't.
 *
 * A MASK rather than a list of ranges: a mask answers "is offset i
 * inside a literal?" in O(1), so the scanning loops below can simply
 * skip masked offsets and never have to reason about where a literal
 * ends. Getting that wrong is how a scanner silently reads the wrong
 * span of the file.
 *
 * @param {string} code output of `javaCode`
 * @returns {Uint8Array} one byte per offset of `code`
 */
export function javaLiteralMask(code) {
  const mask = new Uint8Array(code.length);
  let i = 0;
  const n = code.length;
  while (i < n) {
    const c = code[i];
    if (c === '"' || c === "'") {
      mask[i] = 1;
      i++;
      while (i < n) {
        mask[i] = 1;
        if (code[i] === "\\") {
          if (i + 1 < n) mask[i + 1] = 1;
          i += 2;
          continue;
        }
        if (code[i] === c) {
          i++;
          break;
        }
        i++;
      }
      continue;
    }
    i++;
  }
  return mask;
}

/**
 * Extract one method body by brace matching on the ALREADY STRIPPED
 * text, SKIPPING string and character literals.
 *
 * The literal-skipping is not optional. `javaCode` copies string
 * literals verbatim, so `"a } brace"` is a real `}` in this text; a
 * naive count closes the method body at that brace and hands the
 * caller a TRUNCATED body. A truncated body makes every
 * `assert.match` on it pass vacuously — the gate reports green
 * while reading half a method.
 *
 * @param {string} code output of `javaCode`
 * @param {string} signature a substring unique to the method's decl
 * @param {number} occurrence which match to take, 1-based. Java
 *   overloads share a tail of their signature, so a signature that
 *   looks unique often is not: `DaemonRunner.runHttp` has three
 *   overloads and two of them are one-line delegates into the third.
 *   `indexOf` returns the DELEGATE, whose body contains none of the
 *   wiring the caller is looking for — the extraction succeeds and
 *   reports a defect that does not exist. See the R371 gate, which
 *   failed this way twice before taking `occurrence: 2`.
 * @returns {string} the method body including its braces
 */
export function javaMethod(code, signature, occurrence = 1) {
  let at = -1;
  for (let k = 0; k < occurrence; k++) {
    at = code.indexOf(signature, at + 1);
    if (at < 0) {
      throw new Error(
        `javaMethod: signature #${occurrence} not found: ${signature}` +
          (k > 0 ? ` (found ${k})` : ""),
      );
    }
  }
  const mask = javaLiteralMask(code);
  let open = code.indexOf("{", at);
  while (open >= 0 && mask[open]) open = code.indexOf("{", open + 1);
  if (open < 0) throw new Error(`javaMethod: no body after ${signature}`);
  let depth = 0;
  let started = false;
  for (let i = open; i < code.length; i++) {
    if (mask[i]) continue;
    if (code[i] === "{") {
      depth++;
      started = true;
    } else if (code[i] === "}") {
      depth--;
      if (started && depth === 0) return code.slice(open, i + 1);
    }
  }
  throw new Error(`javaMethod: unbalanced braces after ${signature}`);
}

/**
 * The Java keywords that can be followed by `(` and then a `{` — a
 * control-flow block, never a method body.
 */
const CONTROL_KEYWORDS = new Set([
  "if",
  "for",
  "while",
  "switch",
  "catch",
  "synchronized",
  "do",
  "try",
  "else",
  "finally",
  "return",
  "new",
  "assert",
  "throw",
  "case",
  "default",
]);

/**
 * The word immediately before `from` (skipping whitespace), or "" if
 * there is none.
 */
function wordBefore(code, mask, from) {
  let i = from - 1;
  while (i >= 0 && /\s/.test(code[i])) i--;
  const end = i + 1;
  while (i >= 0 && /[\w$]/.test(code[i])) i--;
  return code.slice(i + 1, end);
}

/**
 * If the `{` at `brace` opens a METHOD body, return that method's
 * name; otherwise return "" (a control-flow block, an anonymous class,
 * a lambda, or a static initializer).
 *
 * The discriminator is the text just before the `{`:
 *
 *   - a method's `{` is preceded by `)` — the parameter list;
 *   - a control block's `(` is preceded by a KEYWORD (`if`, `for`,
 *     `catch`, …), never by a method name;
 *   - an anonymous class's `{` is preceded by `new Foo()`;
 *   - a lambda's `{` is preceded by `->`, and a static
 *     initializer's by `static` — neither ends in `)`.
 *
 * @param {string} code output of `javaCode`
 * @param {Uint8Array} mask from `javaLiteralMask`
 * @param {number} brace the offset of the `{`
 * @returns {string} the method name, or "" if this is not a method
 */
function opensMethodBody(code, mask, brace) {
  // The last non-whitespace character before the `{`.
  let i = brace - 1;
  while (i >= 0 && /\s/.test(code[i])) i--;
  if (i < 0 || code[i] !== ")") return "";

  // The matching `(` — scan backwards, skipping literals.
  let depth = 1;
  let open = -1;
  for (let k = i - 1; k >= 0; k--) {
    if (mask[k]) continue;
    if (code[k] === ")") depth++;
    else if (code[k] === "(") {
      depth--;
      if (depth === 0) {
        open = k;
        break;
      }
    }
  }
  if (open < 0) return "";

  const name = wordBefore(code, mask, open);
  if (!name) return "";
  if (CONTROL_KEYWORDS.has(name)) return "";
  // `new Foo() {` — an anonymous class body, not a method.
  if (wordBefore(code, mask, open - name.length) === "new") return "";
  return name;
}

/**
 * Locate the METHOD that contains a marker, and return its body AND
 * its name.
 *
 * The NAME matters: a gate that has to refer to the method (to count
 * its call sites, say) should DERIVE the name from the implementation
 * rather than hard-code it. A hard-coded name is a pin on a location,
 * and a location pin breaks the moment the code is refactored — which
 * is how R383's gate went red on R384's improvement.
 *
 * @param {string} code output of `javaCode`
 * @param {string} marker a substring that appears inside the method
 * @returns {{ body: string, name: string }}
 */
export function javaMethodInfoContaining(code, marker) {
  const at = code.indexOf(marker);
  if (at < 0) {
    throw new Error(`javaMethodInfoContaining: marker not found: ${marker}`);
  }
  const mask = javaLiteralMask(code);

  // Forward scan with a stack of open braces. Each frame remembers the
  // method-open offset that was in scope when it was pushed, so
  // popping restores the enclosing method.
  const stack = [];
  let methodOpen = -1;
  let methodName = "";
  for (let i = 0; i <= at; i++) {
    if (mask[i]) continue;
    const c = code[i];
    if (c === "{") {
      const name = opensMethodBody(code, mask, i);
      stack.push({ prevMethodOpen: methodOpen, prevMethodName: methodName });
      if (name) {
        methodOpen = i;
        methodName = name;
      }
    } else if (c === "}") {
      const frame = stack.pop();
      if (frame) {
        methodOpen = frame.prevMethodOpen;
        methodName = frame.prevMethodName;
      }
    }
  }
  if (methodOpen < 0) {
    throw new Error(`javaMethodInfoContaining: no enclosing method for ${marker}`);
  }

  // The method's matching `}` — forward from its `{`, literal-aware.
  let depth = 0;
  let started = false;
  for (let i = methodOpen; i < code.length; i++) {
    if (mask[i]) continue;
    if (code[i] === "{") {
      depth++;
      started = true;
    } else if (code[i] === "}") {
      depth--;
      if (started && depth === 0) {
        return { body: code.slice(methodOpen, i + 1), name: methodName };
      }
    }
  }
  throw new Error(`javaMethodInfoContaining: unbalanced braces around ${marker}`);
}

/**
 * Extract the body of the METHOD that CONTAINS a given marker.
 *
 * Why this exists: a gate that pins a method BY NAME breaks the
 * moment the code is refactored — the R383 gate pinned
 * `eventToMap`'s `run_end` branch, R384 moved the frame assembly
 * into a builder, and the pin went red on a refactor that made the
 * code BETTER. A contract pin must FOLLOW the implementation.
 *
 * So the gate says what it means: "the method that builds the
 * run_end frame must carry the kind". The marker locates the method;
 * the assertions then run against whatever that method turned out to
 * be.
 *
 * The METHOD, not the innermost block: a marker inside an `if` still
 * belongs to the method, and a contract assertion over the method
 * (e.g. "the `stopKind` put is not inside a conditional") needs the
 * whole method to be true. Extracting the innermost block would
 * answer a different question — and answer it wrongly.
 *
 * @param {string} code output of `javaCode`
 * @param {string} marker a substring that appears inside the method
 *   body (e.g. `put("type", "run_end")`)
 * @returns {string} the enclosing method's body, including its braces
 */
export function javaMethodContaining(code, marker) {
  return javaMethodInfoContaining(code, marker).body;
}
