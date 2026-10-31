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

// Probe: which files have a literal that SPANS LINES? A single- or
// double-quoted literal cannot span lines in TypeScript, so a multi-line
// "literal" is the scanner swallowing real code — almost always an
// apostrophe in JSX text.
//
// Usage: node scripts/test/probe-swallowed-literals.mjs [tui-root]
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { scan } from "./ts-scan.mjs";

const ROOT = path.resolve(
  process.argv[2] ?? path.join(path.dirname(fileURLToPath(import.meta.url)), "..", ".."),
);

function walk(d, out = []) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (/\.tsx?$/.test(e.name)) out.push(p);
  }
  return out;
}

let files = 0;
let spans = 0;
for (const f of walk(path.join(ROOT, "src"))) {
  const src = fs.readFileSync(f, "utf-8");
  const line = (off) => src.slice(0, off).split("\n").length;
  const bad = scan(src).strings.filter((s) => {
    if (s.quote === "`") return false; // template literals MAY span lines
    return line(s.end) > line(s.start);
  });
  if (bad.length === 0) continue;
  files++;
  spans += bad.length;
  console.log(`${path.relative(ROOT, f).replace(/\\/g, "/")}`);
  for (const s of bad) {
    console.log(
      `   ${JSON.stringify(s.quote)} lines ${line(s.start)}..${line(s.end)}  ${JSON.stringify(s.value.slice(0, 70))}`,
    );
  }
}
console.log(`\n${files} file(s), ${spans} multi-line literal(s)`);
