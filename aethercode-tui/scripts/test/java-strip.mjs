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
 * Extract one method body by brace matching on the ALREADY STRIPPED
 * text. Braces inside string literals would break naive counting —
 * `"{}"` appears in plenty of Java — so this runs after `javaCode`,
 * never on raw source.
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
  let depth = 0;
  let started = false;
  for (let i = code.indexOf("{", at); i < code.length; i++) {
    if (code[i] === "{") {
      depth++;
      started = true;
    } else if (code[i] === "}") {
      depth--;
      if (started && depth === 0) return code.slice(code.indexOf("{", at), i + 1);
    }
  }
  throw new Error(`javaMethod: unbalanced braces after ${signature}`);
}
