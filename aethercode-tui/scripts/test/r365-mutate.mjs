// Mutation harness for r365-panel-truth.test.mjs — NOT part of npm test.
// Run:  node scripts/test/r365-mutate.mjs
//
// This gate exists because a status field was false for a full round
// with the suite green. The only thing that makes it worth having is
// that it goes red when the registry lies again — so each case below
// puts the registry back the way R362 had it, one lie at a time.

import { readFileSync, writeFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const P = join(root, "src", "panelIntents.ts");
const original = readFileSync(P, "utf-8");

function runGate() {
  const r = spawnSync(process.execPath, ["--test", join(here, "r365-panel-truth.test.mjs")],
    { encoding: "utf-8", cwd: root });
  const out = (r.stdout ?? "") + (r.stderr ?? "");
  const m = out.match(/^ℹ fail (\d+)$/m);
  return { fail: m ? Number(m[1]) : -1, out };
}

const CASES = [
  ["re-assert the R362 lie: memory-panel unavailable on Ink",
    (s) => s.replace(/(id: 'memory-panel',[\s\S]*?)ink: 'mounted'/, "$1ink: 'unavailable'")],
  ["re-assert the R362 lie: thread-selector unavailable on line",
    (s) => s.replace(/(id: 'thread-selector',[\s\S]*?)line: 'rendered'/, "$1line: 'unavailable'")],
  ["re-assert the R362 lie: todo-board unavailable on both",
    (s) => s.replace(/(id: 'todo-board',[\s\S]*?)ink: 'mounted',\n\s*line: 'rendered'/,
      "$1ink: 'unavailable',\n    line: 'unavailable'")],
  ["re-assert the R362 lie: grants-manager unavailable (R362's gapNote)",
    (s) => s.replace(/(id: 'grants-manager',[\s\S]*?)ink: 'mounted',\n\s*line: 'rendered'/,
      "$1ink: 'unavailable',\n    line: 'unavailable'")],
  ["re-assert the R362 lie: mcp-viewer unavailable",
    (s) => s.replace(/(id: 'mcp-viewer',[\s\S]*?)ink: 'mounted',\n\s*line: 'rendered'/,
      "$1ink: 'unavailable',\n    line: 'unavailable'")],
  ["re-advertise /context (the live user-facing bug)",
    (s) => s.replace(/\n    advertised: false,/, "")],
  ["drop the blocker from a blocked row (back to prose-only)",
    (s) => s.replace(/\n    blocker: 'no-command',/, "")],
  ["claim a blocker on a fully-mounted row",
    (s) => s.replace(/(id: 'cwd-switcher',[\s\S]*?)ink: 'mounted',\n    line: 'rendered',/,
      "$1ink: 'mounted',\n    line: 'rendered',\n    blocker: 'backend-missing',")],
  ["lie about effort-pick to prove the derivation is not hard-coded",
    (s) => s.replace(/(id: 'effort-picker',[\s\S]*?)ink: 'mounted',\n\s*line: 'rendered'/,
      "$1ink: 'unavailable',\n    line: 'rendered'")],
];

console.log(`r365 mutation battery — ${CASES.length} cases\n`);

let holes = 0;
for (const [name, mutate] of CASES) {
  const after = mutate(original);
  if (after === original) {
    console.log(`  ??  ${name}\n      NO-OP — the pattern did not match, so the gate is not testing anything`);
    holes++;
    continue;
  }
  writeFileSync(P, after, "utf-8");
  const { fail } = runGate();
  writeFileSync(P, original, "utf-8");
  const caught = fail > 0;
  if (!caught) holes++;
  console.log(`  ${caught ? "ok  " : "HOLE"}  ${name}  (gate fail=${fail})`);
}

writeFileSync(P, original, "utf-8");
console.log(`\n${holes === 0 ? "ALL MUTATIONS CAUGHT" : `${holes} MUTATION(S) SURVIVED — holes in the gate`}`);
process.exit(holes === 0 ? 0 : 1);