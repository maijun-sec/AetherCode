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

// R383 mutation battery.
//
// ## What this battery is for
//
// R383 moved the verdict to the wire: the daemon says whether a run
// failed (`isError`) and names the kind (`stopKind`), and the renderers
// only word it. Each of those is a property that can silently rot:
//
//   - the wire can stop carrying the kind (or the verdict);
//   - the verdict can start coming from a hand-rolled prefix check
//     again;
//   - the engine's taxonomy can gain or lose a kind while the TUI's
//     fallback copy does not;
//   - a renderer can grow its own copy of the rules;
//   - the StatusBar can grow a second label table — or go back to
//     looking the label up by the RAW reason, which never matches a
//     decorated one;
//   - the desktop's fallback list can lose an engine kind (the R735b
//     defect) or gain a kind the engine never emits.
//
// Every case below must turn the gate RED. A case that does not is a
// HOLE, and a hole is the most expensive kind of green: it reports
// like a pass.
//
// ## The rules this battery follows
//
//   - the gate must be GREEN before the first mutation (Lesson 1194 —
//     a red gate stays red under every mutation, so every case would
//     "pass" while measuring nothing);
//   - a needle that matches nothing is a NO-OP, and a NO-OP is
//     reported as a hole, not as a pass;
//   - every needle lands in CODE, not in a comment (Lesson 1203) and is
//     SCOPED to the construct it claims to check (Lesson 1195 / 1212 —
//     a needle that is not unique lands on the wrong occurrence);
//   - the battery exits NON-ZERO when a hole survives (Lesson 1196);
//   - the battery never runs concurrently with the test suite, and it
//     restores every file it touched.

import { readFileSync, writeFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { recoverIfInterrupted, writeSnapshot } from "./mutation-snapshot.mjs";
import { requireGreenBaseline } from "./mutation-baseline.mjs";
import { mutateInCode } from "./code-needle.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const repo = join(root, "..");

const PATHS = {
  gate: join(here, "r383-stop-kind.test.mjs"),
  kind: join(root, "src", "stopKind.ts"),
  state: join(root, "src", "state.ts"),
  line: join(root, "src", "line.ts"),
  tui: join(root, "src", "tui.tsx"),
  bar: join(root, "src", "components", "StatusBar.tsx"),
  methods: join(
    repo,
    "aethercode",
    "aethercode-protocol",
    "src",
    "main",
    "java",
    "org",
    "aethercode",
    "protocol",
    "methods",
    "AetherCodeMethods.java",
  ),
  stream: join(
    repo,
    "aethercode",
    "aethercode-core",
    "src",
    "main",
    "java",
    "org",
    "aethercode",
    "core",
    "stream",
    "StreamEvent.java",
  ),
  desktop: join(repo, "aethercode-desktop", "src", "store", "index.ts"),
};

// A battery that is KILLED mid-case must not leave a mutation on disk.
const SNAPSHOT = join(root, "tmp-r383-mutate.snapshot.json");
recoverIfInterrupted(SNAPSHOT);
const original = Object.fromEntries(
  Object.entries(PATHS).map(([k, p]) => [k, readFileSync(p, "utf-8")]),
);
const snapshot = writeSnapshot(
  Object.entries(PATHS).map(([k, p]) => [p, original[k]]),
  SNAPSHOT,
);

function runGate() {
  const r = spawnSync(process.execPath, ["--test", PATHS.gate], { encoding: "utf-8", cwd: root });
  const out = (r.stdout ?? "") + (r.stderr ?? "");
  const m = out.match(/^ℹ fail (\d+)$/m);
  // `fail === -1` means the gate never got as far as reporting — e.g.
  // the mutation broke the TypeScript compile. That is still RED.
  return { fail: m ? Number(m[1]) : -1, status: r.status, out };
}

// The gate must be green BEFORE the first mutation. (R377 Lesson 1194.)
requireGreenBaseline(runGate, "r383 mutation battery");

const CASES = [
  // --- the wire stops carrying the verdict ---------------------------------
  [
    "the daemon stops putting the canonical kind on the wire",
    [
      [
        "methods",
        (s) =>
          mutateInCode(
            s,
            /r\.put\(\s*"stopKind"\s*,\s*re\.bareKind\(\)\s*\)\s*;/,
            "",
            "AetherCodeMethods.java",
            "java",
          ),
      ],
    ],
  ],
  [
    "the daemon's verdict comes from a hand-rolled prefix check again",
    [
      [
        "methods",
        (s) =>
          mutateInCode(
            s,
            /if\s*\(\s*re\.isErrorOutcome\(\)\s*\)/,
            'if (re.stopReason() != null && re.stopReason().startsWith("error"))',
            "AetherCodeMethods.java",
            "java",
          ),
      ],
    ],
  ],

  // --- the engine's taxonomy moves under the TUI ----------------------------
  [
    "the engine drops a failure kind and the TUI's fallback does not",
    [
      [
        "stream",
        (s) =>
          mutateInCode(
            s,
            /case\s+"stale_force_end"\s*->\s*true\s*;/,
            "",
            "StreamEvent.java",
            "java",
          ),
      ],
    ],
  ],
  [
    "the TUI's fallback failure set drifts from the engine's taxonomy",
    [["kind", (s) => mutateInCode(s, /\n\s*"stale_force_end",/, "", "stopKind.ts")]],
  ],

  // --- the classifier stops consulting the fallback -------------------------
  [
    "the local failure set is only consulted when the wire kind is missing",
    [
      [
        "kind",
        (s) =>
          mutateInCode(
            s,
            /!hasVerdict\s*&&\s*ENGINE_ERROR_KINDS\.includes/,
            "hasWireKind && ENGINE_ERROR_KINDS.includes",
            "stopKind.ts",
          ),
      ],
    ],
  ],
  [
    "the local failure set is never consulted at all",
    [
      [
        "kind",
        (s) =>
          mutateInCode(
            s,
            /!hasVerdict\s*&&\s*ENGINE_ERROR_KINDS\.includes/,
            "false && ENGINE_ERROR_KINDS.includes",
            "stopKind.ts",
          ),
      ],
    ],
  ],
  [
    "the loop wording stops winning over the generic failure",
    [
      [
        "kind",
        (s) =>
          mutateInCode(
            s,
            /if\s*\(\s*LOOP_KINDS\.includes\(.*?\)\s*\)\s*return\s*"loop"\s*;/,
            'if (LOOP_KINDS.length < 0) return "loop";',
            "stopKind.ts",
          ),
      ],
    ],
  ],
  [
    "the loop set loses a member",
    [
      [
        "kind",
        (s) =>
          mutateInCode(
            s,
            /export const LOOP_KINDS = \[[\s\S]*?\] as const;/,
            'export const LOOP_KINDS = ["loop_detected"] as const;',
            "stopKind.ts",
          ),
      ],
    ],
  ],
  [
    "the bare kind stops being cut at the colon",
    [
      [
        "kind",
        (s) =>
          mutateInCode(
            s,
            /c === ":" \|\| c === " " \|\| c === "\("/,
            'c === " " || c === "("',
            "stopKind.ts",
          ),
      ],
    ],
  ],

  // --- a renderer grows its own copy ----------------------------------------
  [
    "the line renderer grows its own copy of the failure set",
    [
      [
        "line",
        (s) =>
          mutateInCode(
            s,
            /import \{ classifyRunEnd, type StopKind \} from "\.\/stopKind\.js";/,
            'import { classifyRunEnd, type StopKind } from "./stopKind.js";\n' +
              'const ENGINE_ERROR_KINDS = ["error", "runtime_exception"];',
            "line.ts",
          ),
      ],
    ],
  ],

  // --- the renderers stop consuming the wire --------------------------------
  [
    "the Ink renderer stops forwarding the daemon's verdict",
    [
      [
        "tui",
        (s) =>
          mutateInCode(
            s,
            /isError:\s*ev\.isError === true \? true : undefined,/,
            "isError: undefined,",
            "tui.tsx",
          ),
      ],
    ],
  ],
  [
    "the line renderer stops forwarding the daemon's kind",
    [
      [
        "line",
        (s) =>
          mutateInCode(
            s,
            /stopKind:\s*typeof ev\.stopKind === "string" \? ev\.stopKind : undefined,/,
            "stopKind: undefined,",
            "line.ts",
          ),
      ],
    ],
  ],
  [
    "the reducer stops passing the daemon's verdict to the classifier",
    [
      [
        "state",
        (s) => mutateInCode(s, /isError:\s*action\.isError,/, "isError: undefined,", "state.ts"),
      ],
    ],
  ],

  // --- the StatusBar's label table ------------------------------------------
  [
    "the StatusBar grows a second label table",
    [
      [
        "bar",
        (s) =>
          mutateInCode(
            s,
            /const KIND_LABEL/,
            'const STOP_LABEL: Record<string, string> = { error: "boom" };\nconst KIND_LABEL',
            "StatusBar.tsx",
          ),
      ],
    ],
  ],
  [
    "the StatusBar looks the label up by the RAW reason again",
    [
      [
        "bar",
        (s) =>
          mutateInCode(
            s,
            /KIND_LABEL\[\s*state\.lastStopKind\s*\?\?\s*"ok"\s*\]/,
            'KIND_LABEL[(state.lastStopReason ?? "ok") as NonNullable<State["lastStopKind"]>]',
            "StatusBar.tsx",
          ),
      ],
    ],
  ],
  [
    "the StatusBar's label table loses a display kind",
    [["bar", (s) => mutateInCode(s, /\n\s*empty:\s*"empty input",/, "", "StatusBar.tsx")]],
  ],

  // --- the desktop's fallback list ------------------------------------------
  [
    "the desktop's fallback list loses an engine failure kind",
    [["desktop", (s) => mutateInCode(s, /\n\s*'loop_detected',/, "", "desktop store")]],
  ],
  [
    "the desktop's fallback list gains a kind the engine never emits",
    [
      [
        "desktop",
        (s) =>
          mutateInCode(
            s,
            /\n\s*'cancelled',/,
            "\n          'cancelled',\n          'max_turns',",
            "desktop store",
          ),
      ],
    ],
  ],
];

console.log(`r383 mutation battery — ${CASES.length} cases\n`);

let holes = 0;
let noops = 0;

for (const [name, edits] of CASES) {
  const applied = [];
  let broken = null;
  for (const [key, mutate] of edits) {
    const before = original[key];
    let after;
    try {
      after = mutate(before);
    } catch (err) {
      // A needle that does not land is a NO-OP, and the message says
      // WHY: "not in the file at all" and "only in a comment" are
      // different mistakes with different fixes.
      broken = `${key} — ${err.message}`;
      break;
    }
    if (after === before) {
      broken = `${key} — the needle matched but changed nothing`;
      break;
    }
    applied.push([key, before, after]);
  }
  if (broken) {
    holes++;
    noops++;
    console.log(`  ??  ${name}\n      NO-OP on ${broken} — this case tests nothing`);
    continue;
  }

  for (const [key, , after] of applied) writeFileSync(PATHS[key], after, "utf-8");
  const { fail, status, out } = runGate();
  restore(applied);

  const ok = fail > 0 || status !== 0;
  if (!ok) holes++;
  console.log(
    `  ${ok ? "ok  " : "HOLE"}  ${name}\n      gate fail=${fail} status=${status}  — gate must go red`,
  );
  if (!ok) {
    const lines = out.split(/\r?\n/);
    lines.forEach((l, i) => {
      const m = /^✖ (.+?) \(/.exec(l);
      if (!m) return;
      console.log(`        ✖ ${m[1]}`);
      for (let k = i + 1; k < Math.min(i + 5, lines.length); k++) {
        if (/^\s*(AssertionError|Error):/.test(lines[k])) {
          console.log(`            ${lines[k].trim().slice(0, 220)}`);
          break;
        }
      }
    });
  }
}

function restore(applied) {
  for (const [key, before] of applied) writeFileSync(PATHS[key], before, "utf-8");
}

for (const [k, p] of Object.entries(PATHS)) {
  if (readFileSync(p, "utf-8") !== original[k]) {
    writeFileSync(p, original[k], "utf-8");
    console.log(`  !! restored ${p}`);
  }
}

// The snapshot MUST go when the battery finishes — a snapshot that
// outlives a clean run is a landmine.
snapshot.clear();

console.log(
  `\n${holes === 0 ? "ALL MUTATIONS CAUGHT" : `${holes} MUTATION(S) SURVIVED — holes in the gate`}` +
    (noops ? ` (${noops} no-op)` : ""),
);
process.exit(holes === 0 ? 0 : 1);
