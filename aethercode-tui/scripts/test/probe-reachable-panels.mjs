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

// Measure: which React components are REACHABLE — imported (transitively)
// from an entry point AND actually rendered as a JSX tag.
//
// A MEASUREMENT TOOL, not a gate. It prints; it does not assert, and it
// is not a `*.test.mjs`, so `npm test` never runs it.
//
// Usage:  node scripts/test/probe-reachable-panels.mjs [tui-root]
//
// The ANALYSIS lives in `scripts/test/reachability.mjs`, so the probe
// and the R381 gate measure the same thing. Two copies of a reachability
// walk would be two truths, and they would drift — which is how R379's
// "nine reachable components" turned out to be seven plus two dead
// ones.
//
// Why this exists (R381):
//
//   R379 claimed "9 reachable components still hand-write a key hint"
//   and listed them. The list was WRONG: `NotificationSettings` and
//   `NotificationDetail` are exported but never rendered —
//   NotificationCenter imports only the toggle REGISTRY from
//   NotificationSettings, and NotificationDetail is imported by nobody
//   at all.
//
//   An unreachable component's key hint is not user-visible drift; it is
//   DEAD CODE. Fixing it would be work on a surface nobody can reach,
//   and adding it to the key registry would put a lie in the registry.
//
//   So "reachable" has to be MEASURED, not asserted in prose.
import path from "node:path";
import { fileURLToPath } from "node:url";
import { analyzeReachability } from "./reachability.mjs";

const ROOT = path.resolve(
  process.argv[2] ?? path.join(path.dirname(fileURLToPath(import.meta.url)), "..", ".."),
);

const r = analyzeReachability(ROOT);

console.log(`tsx components with exports: ${r.components.length}`);
console.log(`reachable by import       : ${r.components.filter((c) => c.imported).length}`);
console.log(`reachable by RENDER       : ${r.reachableComponents.length}`);
console.log(`\nimported but NEVER rendered (dead UI):\n`);
for (const c of r.deadComponents) console.log(`  ${c.rel}  [${c.exported.join(", ")}]`);
console.log(`\nnever imported at all:\n`);
for (const c of r.orphanedComponents) console.log(`  ${c.rel}  [${c.exported.join(", ")}]`);
console.log(`\nRENDERED (the reachable surface):\n`);
for (const c of r.reachableComponents) console.log(`  ${c.rel}  <- ${c.renderedIn.join(", ")}`);
