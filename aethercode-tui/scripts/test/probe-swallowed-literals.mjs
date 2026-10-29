// Copyright (c) 2026 maijun

// Licensed under the MIT License.
// See the LICENSE file in the project root for the full license text.

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
