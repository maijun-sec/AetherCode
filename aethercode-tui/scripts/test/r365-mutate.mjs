// Copyright (c) 2026 maijun

// Licensed under the MIT License.
// See the LICENSE file in the project root for the full license text.

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
import { recoverIfInterrupted, writeSnapshot } from "./mutation-snapshot.mjs";
// The gate must be GREEN before anything is mutated — otherwise every
// case reports "caught" for the wrong reason and the battery measures
// nothing. See scripts/test/mutation-baseline.mjs.
import { requireGreenBaseline } from "./mutation-baseline.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const P = join(root, "src", "panelIntents.ts");

// R375: a battery that is KILLED mid-case must not leave a mutation on
// disk — the next test run would then read a deliberately broken product
// and the failures would belong to neither round. See
// scripts/test/mutation-snapshot.mjs.
const SNAPSHOT = join(root, "tmp-r365-mutate.snapshot.json");
recoverIfInterrupted(SNAPSHOT);
const original = readFileSync(P, "utf-8");
const snapshot = writeSnapshot([[P, original]], SNAPSHOT);

function runGate() {
  const r = spawnSync(process.execPath, ["--test", join(here, "r365-panel-truth.test.mjs")], {
    encoding: "utf-8",
    cwd: root,
  });
  const out = (r.stdout ?? "") + (r.stderr ?? "");
  const m = out.match(/^ℹ fail (\d+)$/m);
  return { fail: m ? Number(m[1]) : -1, out };
}

// The gate must be green BEFORE the first mutation. A gate that is
// already red stays red under every mutation, so every case would
// report "caught" and the battery would measure nothing. (R377 Lesson
// 1194.)
requireGreenBaseline(runGate, "r365 mutation battery");

const CASES = [
  [
    "re-assert the R362 lie: memory-panel unavailable on Ink",
    (s) =>
      s.replace(
        /(id: ["']memory-panel["'],[\s\S]*?)ink: (["'])mounted\2/,
        "$1ink: $2unavailable$2",
      ),
  ],
  [
    "re-assert the R362 lie: thread-selector unavailable on line",
    (s) =>
      s.replace(
        /(id: ["']thread-selector["'],[\s\S]*?)line: (["'])rendered\2/,
        "$1line: $2unavailable$2",
      ),
  ],
  [
    "re-assert the R362 lie: todo-board unavailable on both",
    (s) =>
      s.replace(
        /(id: ["']todo-board["'],[\s\S]*?)ink: (["'])mounted\2,\n\s*line: (["'])rendered\3/,
        "$1ink: $2unavailable$2,\n    line: $3unavailable$3",
      ),
  ],
  [
    "re-assert the R362 lie: grants-manager unavailable (R362's gapNote)",
    (s) =>
      s.replace(
        /(id: ["']grants-manager["'],[\s\S]*?)ink: (["'])mounted\2,\n\s*line: (["'])rendered\3/,
        "$1ink: $2unavailable$2,\n    line: $3unavailable$3",
      ),
  ],
  [
    "re-assert the R362 lie: mcp-viewer unavailable",
    (s) =>
      s.replace(
        /(id: ["']mcp-viewer["'],[\s\S]*?)ink: (["'])mounted\2,\n\s*line: (["'])rendered\3/,
        "$1ink: $2unavailable$2,\n    line: $3unavailable$3",
      ),
  ],
  // R374: this case is DELETED, and it was deleted for a reason worth
  // writing down.
  //
  // Its intent was "drop the blocker from a blocked row". Two things
  // happened:
  //
  //   1. The regex `/(id: 'workflow-picker',[\s\S]*?)\n    blocker:
  //      'not-wired',/` CROSSED ROW BOUNDARIES. `[\s\S]*?` is lazy but
  //      it will happily skip over the end of the `workflow-picker`
  //      row to find the next `\n    blocker: 'not-wired',` anywhere
  //      later in the file — which was the `update-available` row.
  //      So the case was mutating a DIFFERENT row than the one it
  //      named, and the gate went red for the wrong reason.
  //   2. R374 removed that row's blocker (correctly: the daemon has no
  //      update RPC, so the honest classification is
  //      `backend-missing`, and R374 built the surface), which left
  //      the regex with nothing to match. The no-op detector caught
  //      it — which is the only thing that could tell a vacuous case
  //      from a passing one.
  //
  // The registry now has NO row with a `blocker` field at all: every
  // row is `mounted`/`rendered`. So the case is unexpressible against
  // the live table, and the mechanism it wanted to cover — "a
  // placeholder with no command behind it must not be advertised" —
  // is covered by a SYNTHETIC row in r371-context-meter.test.mjs,
  // which constructs the row instead of depending on one happening
  // to ship in that state.
  //
  // Keeping a case that measures nothing is not honest. Deleting it
  // is.
  //
  // "claim a blocker on a fully-mounted row" below is the surviving
  // half of this pair, and it still has a live target.
  [
    "claim a blocker on a fully-mounted row",
    (s) =>
      s.replace(
        /(id: ["']cwd-switcher["'],[\s\S]*?)ink: ["']mounted["'],\s*line: ["']rendered["'],/,
        '$1ink: "mounted",\n    line: "rendered",\n    blocker: "backend-missing",',
      ),
  ],
  [
    "lie about effort-pick to prove the derivation is not hard-coded",
    (s) =>
      s.replace(
        /(id: ["']effort-picker["'],[\s\S]*?)ink: ["']mounted["'],\s*line: ["']rendered["']/,
        '$1ink: "unavailable",\n    line: "rendered"',
      ),
  ],
];

console.log(`r365 mutation battery — ${CASES.length} cases\n`);

let holes = 0;
for (const [name, mutate] of CASES) {
  const after = mutate(original);
  if (after === original) {
    console.log(
      `  ??  ${name}\n      NO-OP — the pattern did not match, so the gate is not testing anything`,
    );
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
console.log(
  `\n${holes === 0 ? "ALL MUTATIONS CAUGHT" : `${holes} MUTATION(S) SURVIVED — holes in the gate`}`,
);
snapshot.clear();
process.exit(holes === 0 ? 0 : 1);
