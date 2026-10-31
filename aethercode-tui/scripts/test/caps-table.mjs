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

// Shared editor for the refusal table in `src/rpcCapabilities.ts`.
//
// ## Why this file exists
//
// R377 rewrote the table's reasons, and three mutation batteries that
// aim at the table went NO-OP on the spot: their needles were written
// against the table's TEXT (`"no rewind handler; …"`), and a needle that
// matches nothing reports EXACTLY like a needle that was caught — so
// the battery kept printing "ok" while testing nothing. That is R376
// Lesson 1190 in the flesh, and it is the most expensive kind of green.
//
// The fix is two rules:
//
//   1. Address the table BY ITS KEYS, never by its prose.
//   2. Keep ONE implementation of "find the entry for this key".
//      Three batteries, one editor — the same reason `java-strip.mjs`
//      exists (R370: "a second copy would be a second UNTESTED
//      scanner").
//
// ## Layout independence is the point
//
// R376 runs `prettier --write` over this package, so whitespace, quote
// style and line breaks are free to change under us. A needle that dies
// on a reflow is a blind gate (Lesson 1191). Everything below therefore
// matches on structure — a key, a bracket, a string literal — and never
// on a reason's wording.
//
// ## Self-test
//
// `selfTest()` at the bottom is executed by `r377-capabilities.test.mjs`.
// A helper whose only user is a mutation battery is exactly the kind of
// untested code that hides a hole, so it gets its own assertions.

// R378: the scanner moved to `ts-scan.mjs` so the R378 gate can reuse it —
// two copies of a scanner means two scanners, and only one of them gets
// tested (the same reason `java-strip.mjs` exists).
import { matchBracket, scan } from "./ts-scan.mjs";

/**
 * The `[` that opens the element whose key literal is at `litStart`.
 *
 * An element is `[ "key", "reason" ]`, so the `[` is the nearest
 * non-whitespace character before the key. Anything else means this
 * string is not a table key (a bare mention of the method name in
 * code, say) and must be ignored.
 *
 * @param {string} src
 * @param {number} litStart offset of the key literal's opening quote
 * @returns {number} index of the `[`, or -1
 */
function openingBracket(src, litStart) {
  let i = litStart - 1;
  while (i >= 0 && /\s/.test(src[i])) i--;
  return i >= 0 && src[i] === "[" ? i : -1;
}

/**
 * The table element for `key`, located by structure.
 *
 * @param {string} src the whole file
 * @param {string} key e.g. `"rewind"`
 * @returns {{start: number, end: number, text: string} | null}
 */
export function findEntry(src, key) {
  for (const lit of scan(src).strings) {
    if (lit.quote === "`") continue;
    if (lit.value !== key) continue;
    const at = openingBracket(src, lit.start);
    if (at < 0) continue;
    const end = matchBracket(src, at);
    if (end < 0) continue;
    return { start: at, end, text: src.slice(at, end) };
  }
  return null;
}

/**
 * Every entry of the table, as `{key, reason}`.
 *
 * @param {string} src
 * @returns {{key: string, reason: string}[]}
 */
export function readEntries(src) {
  const out = [];
  for (const lit of scan(src).strings) {
    if (lit.quote === "`") continue;
    const at = openingBracket(src, lit.start);
    if (at < 0) continue;
    const end = matchBracket(src, at);
    if (end < 0) continue;
    const literals = scan(src.slice(at, end)).strings;
    const keyIdx = literals.findIndex((l) => l.start === lit.start - at);
    const reasonLit = literals.slice(keyIdx + 1).find((l) => l.quote !== "`");
    out.push({ key: lit.value, reason: reasonLit ? reasonLit.value : "" });
  }
  return out;
}

/**
 * Remove the entry for `key` (and the comma that separated it), so the
 * table literal stays valid TypeScript.
 *
 * @param {string} src
 * @param {string} key
 * @returns {string}
 */
export function deleteEntry(src, key) {
  const e = findEntry(src, key);
  if (!e) return src;
  let end = e.end;
  if (src[end] === ",") end++;
  return src.slice(0, e.start) + src.slice(end);
}

/**
 * Replace the reason of the entry for `key`.
 *
 * @param {string} src
 * @param {string} key
 * @param {string} reason
 * @returns {string}
 */
export function setReason(src, key, reason) {
  const e = findEntry(src, key);
  if (!e) return src;
  const literals = scan(e.text).strings;
  const keyIdx = literals.findIndex((l) => l.value === key);
  if (keyIdx < 0) return src;
  const reasonLit = literals.slice(keyIdx + 1).find((l) => l.quote !== "`");
  if (!reasonLit) return src;
  const literal = JSON.stringify(reason);
  const text = e.text.slice(0, reasonLit.start) + literal + e.text.slice(reasonLit.end);
  return src.slice(0, e.start) + text + src.slice(e.end);
}

/**
 * Replace a fragment inside the reason of the entry for `key`.
 *
 * @param {string} src
 * @param {string} key
 * @param {string} from the fragment to replace (a plain string)
 * @param {string} to the replacement
 * @returns {string}
 */
export function replaceInReason(src, key, from, to) {
  const e = findEntry(src, key);
  if (!e) return src;
  if (!e.text.includes(from)) return src;
  const text = e.text.replace(from, to);
  return src.slice(0, e.start) + text + src.slice(e.end);
}

/**
 * Add a new entry at the head of the table.
 *
 * @param {string} src
 * @param {string} key
 * @param {string} reason
 * @returns {string}
 */
export function insertEntry(src, key, reason) {
  const decl = src.search(/BACKEND_MISSING[^\n]*=\s*new\s+Map</);
  if (decl < 0) return src;
  const open = src.indexOf("[", decl);
  if (open < 0) return src;
  const at = open + 1;
  const entry = "\n  [" + JSON.stringify(key) + ", " + JSON.stringify(reason) + "],";
  return src.slice(0, at) + entry + src.slice(at);
}

/**
 * The editor's own assertions.
 *
 * Run by `r377-capabilities.test.mjs`. If this file silently stopped
 * working, every battery that uses it would report NO-OP — and a
 * NO-OP reports like a pass.
 *
 * @returns {{ok: boolean, failures: string[]}}
 */
export function selfTest() {
  const failures = [];
  const check = (name, cond) => {
    if (!cond) failures.push(name);
  };

  const sample = [
    '// a comment that mentions "rewind" and ["fake", "entry"]',
    "export const BACKEND_MISSING: ReadonlyMap<string, string> = new Map<string, string>([",
    "  [",
    '    "rewind",',
    '    "the daemon cannot roll a transcript back: `AppState` only appends",',
    "  ],",
    "  [",
    '    "session/tokens",',
    '    "the daemon accounts tokens by MODEL: `CostTracker` sums",',
    "  ],",
    "]);",
    "",
  ].join("\n");

  const entries = readEntries(sample);
  check("readEntries finds both entries", entries.length === 2);
  check("readEntries reads the key", entries[0]?.key === "rewind");
  check(
    "readEntries reads the reason",
    (entries[0]?.reason ?? "").startsWith("the daemon cannot roll"),
  );
  check("a commented-out entry is not an entry", !entries.some((e) => e.key === "fake"));

  const deleted = deleteEntry(sample, "rewind");
  check("deleteEntry removes the entry", !readEntries(deleted).some((e) => e.key === "rewind"));
  check("deleteEntry keeps the other entry", deleted.includes('"session/tokens"'));
  check("deleteEntry keeps the table valid", readEntries(deleted).length === 1);

  const blanked = setReason(sample, "rewind", "");
  check("setReason empties the reason", readEntries(blanked)[0]?.reason === "");
  check("setReason keeps the key", readEntries(blanked)[0]?.key === "rewind");

  const rewritten = setReason(sample, "rewind", "same as rewind");
  check("setReason rewrites the reason", readEntries(rewritten)[0]?.reason === "same as rewind");

  const inserted = insertEntry(sample, "task/kill", "listed here once more");
  check("insertEntry adds the entry", readEntries(inserted).length === 3);
  check("insertEntry adds it at the head", readEntries(inserted)[0]?.key === "task/kill");

  const patched = replaceInReason(sample, "rewind", "`AppState`", "`AppStatte`");
  check("replaceInReason patches the reason", patched.includes("`AppStatte`"));
  check("replaceInReason leaves the key alone", readEntries(patched)[0]?.key === "rewind");

  return { ok: failures.length === 0, failures };
}
