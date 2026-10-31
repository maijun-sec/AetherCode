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

// R384 mutation battery.
//
// ## What this battery is for
//
// R384 made the `run_end` frame ONE frame with ONE contract. Each of
// those is a property that can silently rot:
//
//   - the daemon can grow a SECOND assembler (a second contract);
//   - the builder can stop carrying the kind, or make it conditional;
//   - the verdict can start coming from a hand-rolled prefix check;
//   - either synthetic terminal can go back to building its frame by
//     hand (that is how the hole opened in the first place);
//   - the stream-json `result` frame can stop carrying the kind — the
//     same defect on the other wire;
//   - the TUI can start consulting its local failure set only when the
//     wire kind is missing (so the fallback and the wire disagree);
//   - the desktop can collapse the wire's kind back into "error".
//
// Every case below must turn the gate RED. A case that does not is a
// HOLE, and a hole is the most expensive kind of green: it reports
// like a pass.
//
// ## The rules this battery follows
//
//   - the gate must be GREEN before the first mutation (Lesson 1194);
//   - a needle that matches nothing is a NO-OP, and a NO-OP is
//     reported as a hole, not as a pass;
//   - every needle lands in CODE, not in a comment (Lesson 1203) and is
//     SCOPED to the construct it claims to check (Lesson 1195 / 1212);
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
  gate: join(here, "r384-run-end-frame.test.mjs"),
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
  dispatcher: join(
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
    "EngineContinuationDispatcher.java",
  ),
  emitter: join(
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
    "ClaudeStreamJsonEmitter.java",
  ),
  kind: join(root, "src", "stopKind.ts"),
  desktop: join(repo, "aethercode-desktop", "src", "store", "index.ts"),
};

// A battery that is KILLED mid-case must not leave a mutation on disk.
const SNAPSHOT = join(root, "tmp-r384-mutate.snapshot.json");
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
  // `fail === -1` means the gate never got as far as reporting.
  return { fail: m ? Number(m[1]) : -1, status: r.status, out };
}

// The gate must be green BEFORE the first mutation. (R377 Lesson 1194.)
requireGreenBaseline(runGate, "r384 mutation battery");

const CASES = [
  // --- the builder stops carrying the kind -----------------------------------
  [
    "the builder stops carrying the canonical kind",
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
    "the kind becomes conditional — absent on a clean run",
    [
      [
        "methods",
        (s) =>
          mutateInCode(
            s,
            /r\.put\(\s*"stopKind"\s*,\s*re\.bareKind\(\)\s*\)\s*;/,
            'if (re.isErrorOutcome()) r.put("stopKind", re.bareKind());',
            "AetherCodeMethods.java",
            "java",
          ),
      ],
    ],
  ],

  // --- the verdict goes back to a hand-rolled check --------------------------
  [
    "the verdict comes from a hand-rolled prefix check again",
    [
      [
        "methods",
        (s) =>
          mutateInCode(
            s,
            /re\.isErrorOutcome\(\)/,
            're.stopReason() != null && re.stopReason().startsWith("error")',
            "AetherCodeMethods.java",
            "java",
          ),
      ],
    ],
  ],
  [
    "the verdict is written unconditionally — a clean run is called a failure",
    [
      [
        "methods",
        (s) =>
          mutateInCode(
            s,
            /if\s*\(\s*re\.isErrorOutcome\(\)\s*\)\s*\{/,
            "if (true) {",
            "AetherCodeMethods.java",
            "java",
          ),
      ],
    ],
  ],

  // --- a second assembler appears -------------------------------------------
  [
    "a SECOND place builds a run_end frame",
    [
      [
        "dispatcher",
        (s) =>
          mutateInCode(
            s,
            /evWrap\.put\("event", AetherCodeMethods\.eventToMapPublic\(ev\)\);/,
            "Map<String, Object> hand = new LinkedHashMap<>();\n" +
              '            hand.put("type", "run_end");\n' +
              '            hand.put("stopReason", "hand_built: a second assembler");\n' +
              '            evWrap.put("event", hand);',
            "EngineContinuationDispatcher.java",
            "java",
          ),
      ],
    ],
  ],

  // --- a synthetic terminal goes back to building its own frame --------------
  [
    "the R745 synthetic terminal builds its frame by hand again",
    [
      [
        "methods",
        (s) =>
          mutateInCode(
            s,
            /endWrap\.put\("event", runEndFrame\(\s*"no_run_end: consumer exited without terminal event",\s*"stream exhausted without a RunEnd event \(R745\)"\)\);/,
            "Map<String, Object> hand = new LinkedHashMap<>();\n" +
              '                        hand.put("type", "run_end");\n' +
              '                        hand.put("stopReason", "no_run_end: consumer exited without terminal event");\n' +
              '                        hand.put("isError", true);\n' +
              '                        endWrap.put("event", hand);',
            "AetherCodeMethods.java",
            "java",
          ),
      ],
    ],
  ],
  [
    "the catch(Throwable) terminal builds its frame by hand again",
    [
      [
        "methods",
        (s) =>
          mutateInCode(
            s,
            /endWrap\.put\("event", runEndFrame\(\s*th\.getMessage\(\)[\s\S]*?getName\(\)\)\);/,
            "Map<String, Object> hand = new LinkedHashMap<>();\n" +
              '                hand.put("type", "run_end");\n' +
              '                hand.put("stopReason", "error: boom");\n' +
              '                hand.put("isError", true);\n' +
              '                endWrap.put("event", hand);',
            "AetherCodeMethods.java",
            "java",
          ),
      ],
    ],
  ],

  // --- the other wire loses the kind ----------------------------------------
  [
    "the stream-json result frame stops carrying the canonical kind",
    [
      [
        "emitter",
        (s) =>
          mutateInCode(
            s,
            /frame\.put\("aethercode_stop_kind", StreamEvent\.bareKindOf\(stopReason\)\);/,
            "",
            "ClaudeStreamJsonEmitter.java",
            "java",
          ),
      ],
    ],
  ],

  // --- the TUI's fallback and the wire start to disagree ---------------------
  [
    "the TUI consults its local failure set only when the wire kind is missing",
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

  // --- the desktop collapses the wire's kind --------------------------------
  [
    "the desktop collapses the wire's kind back into 'error'",
    [
      [
        "desktop",
        (s) =>
          mutateInCode(
            s,
            /failedKind\s*=\s*wireFailed\s*\?\?\s*matchedKind\s*\?\?\s*\(\s*wireIsError\s*\?\s*'error'\s*:\s*undefined\s*\)/,
            "failedKind = wireFailed ?? (wireIsError ? 'error' : undefined)",
            "desktop store",
          ),
      ],
    ],
  ],
];

console.log(`r384 mutation battery — ${CASES.length} cases\n`);

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
