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

// Measure: which components have a useInput handler AND render a
// hand-written key hint? That pair is the drift risk (R378's lesson,
// one level down).
//
// A MEASUREMENT TOOL, not a gate. It prints; it does not assert, and it
// is not a `*.test.mjs`, so `npm test` never runs it.
//
// Usage:  node scripts/test/probe-component-keys.mjs [tui-root]
//
// The root defaults to this package, so the numbers quoted in
// docs/R379-*.md can be reproduced by anyone:
//
//     node scripts/test/probe-component-keys.mjs
//
// R379 measured: 31 components with a useInput handler, 17 of them
// rendering a hand-written hint, of which 9 are reachable from the two
// entry points. The R379 gate enforces the registry for the 10 panels
// the TUI's own panel registry advertises; the remainder is recorded
// in the doc as the measured next round.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { scan, matchBrace, blankNonCode } from "./ts-scan.mjs";

const ROOT = path.resolve(
  process.argv[2] ?? path.join(path.dirname(fileURLToPath(import.meta.url)), "..", ".."),
);
const compDir = path.join(ROOT, "src", "components");

function walk(d, out = []) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (e.name.endsWith(".tsx")) out.push(p);
  }
  return out;
}

/** The `useInput((input, key) => { … })` body of a file, if any. */
function handlerBody(src) {
  const code = blankNonCode(src);
  const at = code.indexOf("useInput(");
  if (at < 0) return null;
  const braceAt = code.indexOf("{", at);
  if (braceAt < 0) return null;
  const end = matchBrace(code, braceAt);
  if (end < 0) return null;
  return src.slice(braceAt, end);
}

const KEY_PROP = /key\.([A-Za-z]+)/g;
const INPUT_EQ = /input\s*===\s*"([^"])"/g;
const INPUT_IN = /input\s*===\s*'([^']+)'/g;

const rows = [];
for (const file of walk(compDir)) {
  const rel = path.relative(path.join(ROOT, "src"), file).replace(/\\/g, "/");
  const src = fs.readFileSync(file, "utf-8");
  const body = handlerBody(src);
  if (!body) continue;
  const props = new Set();
  for (const m of body.matchAll(KEY_PROP)) props.add(m[1]);
  const chars = new Set();
  for (const m of body.matchAll(INPUT_EQ)) chars.add(m[1]);
  for (const m of body.matchAll(INPUT_IN)) chars.add(m[1]);
  // A hand-written hint: a Text node that mentions a key word.
  const hints = [
    ...src.matchAll(/<Text[^>]*>([^<]*(?:Esc|Enter|Ctrl|↑|↓|navigate|close|select)[^<]*)<\/Text>/g),
  ]
    .map((m) => m[1].trim())
    .filter((s) => s.length > 3);
  rows.push({ rel, props: [...props].sort(), chars: [...chars].sort(), hints });
}

console.log(`components with a useInput handler: ${rows.length}\n`);
for (const r of rows) {
  console.log(`${r.rel}`);
  console.log(`   key props : ${r.props.join(", ") || "(none)"}`);
  console.log(`   input === : ${r.chars.join(", ") || "(none)"}`);
  for (const h of r.hints) console.log(`   hint      : ${h}`);
  console.log("");
}
