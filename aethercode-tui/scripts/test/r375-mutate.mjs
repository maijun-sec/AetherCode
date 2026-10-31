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

// Mutation harness for r375-run-control.test.mjs — NOT part of npm test.
// Run:  node scripts/test/r375-mutate.mjs
//
// Every case here breaks ONE thing and the gate must go red. A case
// that leaves the gate green is a hole: the assertion it targets is
// not load-bearing.
//
// Two families:
//
//   * PRODUCT mutations — the TUI or the daemon is broken. Most of
//     them are behavioural, because `runControl.ts` is a pure module
//     the gate COMPILES AND RUNS: "stop maps to the wrong method" is
//     a value, not a line of source.
//
//   * GATE mutations — an assertion is neutered and a real defect is
//     introduced. These are only meaningful when the defect has a
//     SECOND independent derivation, so the gate still catches it
//     with the neutered assertion out of the way. A neutered
//     assertion whose defect only IT could see proves nothing (it
//     proves the assertion was the only one) — those are deleted,
//     not shipped.
//
// THIS HARNESS REWRITES PRODUCT SOURCE IN PLACE. It must never run
// concurrently with the test suite (R373 Lesson 1166): a battery that
// edits `src/tui.tsx` while `npm test` reads it produces failures
// that belong to neither.

import { readFileSync, writeFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { recoverIfInterrupted, writeSnapshot } from "./mutation-snapshot.mjs";
// The gate must be GREEN before anything is mutated — otherwise every
// case reports "caught" for the wrong reason and the battery measures
// nothing. See scripts/test/mutation-baseline.mjs.
import { requireGreenBaseline } from "./mutation-baseline.mjs";
// R377: the table's REASON TEXT is not a stable anchor — R377 rewrote
// every reason and the needle below went NO-OP on the spot, silently
// testing nothing. It now addresses the table BY KEY through one
// shared editor, so a rewrite of the prose cannot blind it again.
import { insertEntry } from "./caps-table.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const repo = join(root, "..");

const PATHS = {
  gate: join(here, "r375-run-control.test.mjs"),
  control: join(root, "src", "runControl.ts"),
  tui: join(root, "src", "tui.tsx"),
  line: join(root, "src", "line.ts"),
  state: join(root, "src", "state.ts"),
  commands: join(root, "src", "commands.ts"),
  caps: join(root, "src", "rpcCapabilities.ts"),
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
  http: join(
    repo,
    "aethercode",
    "aethercode-protocol",
    "src",
    "main",
    "java",
    "org",
    "aethercode",
    "protocol",
    "http",
    "HttpJsonRpcServer.java",
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
  runner: join(
    repo,
    "aethercode",
    "aethercode-cli",
    "src",
    "main",
    "java",
    "org",
    "aethercode",
    "cli",
    "DaemonRunner.java",
  ),
};

// Read the originals ONCE, in memory. `git checkout --` would restore
// to HEAD and swallow every uncommitted fix in the working tree
// (R373: Lesson 1171 has bitten this repo four times).

// R375: a battery that is KILLED mid-case must not leave a mutation on
// disk — the next test run would then read a deliberately broken product
// and the failures would belong to neither round. See
// scripts/test/mutation-snapshot.mjs.
const SNAPSHOT = join(root, "tmp-r375-mutate.snapshot.json");
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
  // the mutation broke the TypeScript compile, and the gate exited
  // non-zero. That is still RED, so it counts as caught.
  return { fail: m ? Number(m[1]) : -1, status: r.status, out };
}

// The gate must be green BEFORE the first mutation. A gate that is
// already red stays red under every mutation, so every case would
// report "caught" and the battery would measure nothing. (R377 Lesson
// 1194.)
requireGreenBaseline(runGate, "r375 mutation battery");

const CASES = [
  // --- the method names ------------------------------------------------------
  [
    "the stop verb points at a method the daemon does not have",
    [
      [
        "control",
        (s) =>
          s.replace(
            'export const RUN_STOP_METHOD = "cancel";',
            'export const RUN_STOP_METHOD = "task/kill";',
          ),
      ],
    ],
  ],

  [
    "the pause verb points at a method the daemon does not have",
    [
      [
        "control",
        (s) =>
          s.replace(
            'export const RUN_PAUSE_METHOD = "setContinuationStopped";',
            'export const RUN_PAUSE_METHOD = "task/pause";',
          ),
      ],
    ],
  ],

  [
    "every verb collapses onto the pause method",
    [
      [
        "control",
        (s) =>
          s.replace(
            '  return action === "stop" ? RUN_STOP_METHOD : RUN_PAUSE_METHOD;',
            "  return RUN_PAUSE_METHOD;",
          ),
      ],
    ],
  ],

  // --- the params ------------------------------------------------------------
  [
    "the stop verb sends no run id",
    [
      [
        "control",
        (s) =>
          s.replace(
            '    return { runId: ctx.runId ?? "" };',
            "    return {} as unknown as RpcValue;",
          ),
      ],
    ],
  ],

  [
    "the pause verb sends the flag inverted",
    [
      [
        "control",
        (s) =>
          s.replace(
            '  return { stopped: action === "pause" } as unknown as RpcValue;',
            '  return { stopped: action !== "pause" } as unknown as RpcValue;',
          ),
      ],
    ],
  ],

  [
    "the pause verb sends a session id the daemon cannot blank-check",
    [
      [
        "control",
        (s) =>
          s.replace(
            '  return { stopped: action === "pause" } as unknown as RpcValue;',
            '  return { stopped: action === "pause", sessionId: "—" } as unknown as RpcValue;',
          ),
      ],
    ],
  ],

  // --- the copy --------------------------------------------------------------
  [
    "a failed action reports success",
    [["control", (s) => s.replace("  if (!ok) {", "  if (ok) {")]],
  ],

  [
    "the stop success copy claims the stop already happened",
    [
      [
        "control",
        (s) =>
          s.replace(
            "    return ctx.runId ? `stopping run ${ctx.runId}",
            "    return ctx.runId ? `stopped run ${ctx.runId}",
          ),
      ],
    ],
  ],

  [
    "pause stops being able to say 'already paused'",
    [
      [
        "control",
        (s) =>
          s.replace(
            '  if (action === "pause") return "auto-continue is already paused for this session";',
            '  if (action === "pause") return null;',
          ),
      ],
    ],
  ],

  [
    "'nothing is in flight' is reported as a failure",
    [
      [
        "control",
        (s) =>
          s.replace(
            '  return "no run is in flight (the daemon did not report a run id)";',
            '  return "stop failed: no run is in flight";',
          ),
      ],
    ],
  ],

  // --- the session-state mapping --------------------------------------------
  [
    "a paused session no longer maps to the paused state",
    [
      [
        "control",
        (s) => s.replace('  if (paused) return "paused";', '  if (paused) return "running";'),
      ],
    ],
  ],

  // --- the mirror ------------------------------------------------------------
  [
    "the reducer stops recording the paused flag",
    [
      [
        "state",
        (s) =>
          s.replace(
            "        : { ...state, continuationPaused: action.paused };",
            "        : { ...state, continuationPaused: false };",
          ),
      ],
    ],
  ],

  [
    "a new prompt no longer clears the mirror",
    [
      [
        "state",
        (s) =>
          s.replace(
            "        continuationPaused: false,\n        turns: [",
            "        continuationPaused: state.continuationPaused,\n        turns: [",
          ),
      ],
    ],
  ],

  [
    "the mirror survives a session switch",
    [
      [
        "state",
        (s) => s.replace("        continuationPaused: false,\n      };\n    }", "      };\n    }"),
      ],
    ],
  ],

  // --- the Ink renderer ------------------------------------------------------
  [
    "the Continue button goes back to the supervisor proxy",
    [
      [
        "tui",
        (s) =>
          s.replace(
            "                    void pauseAutoContinue(false);",
            '                    void client.request("task/resume", { id: state.sessionId });',
          ),
      ],
    ],
  ],

  [
    "the Pause button goes back to the supervisor proxy",
    [
      [
        "tui",
        (s) =>
          s.replace(
            "                    void pauseAutoContinue(true);",
            '                    void client.request("task/pause", { id: state.sessionId });',
          ),
      ],
    ],
  ],

  [
    "the Stop button goes back to the supervisor proxy",
    [
      [
        "tui",
        (s) =>
          s.replace(
            "                    void stopActiveRun();",
            '                    void client.request("task/kill", { id: state.sessionId });',
          ),
      ],
    ],
  ],

  [
    "a failed stop is reported as an ordinary notice",
    [
      [
        "tui",
        (s) =>
          s.replace(
            '        kind: "err",\n        text: runOutcome("stop", false, { error: (e as Error).message }),',
            '        kind: "info",\n        text: runOutcome("stop", false, { error: (e as Error).message }),',
          ),
      ],
    ],
  ],

  [
    "a failed pause is reported as a success",
    [
      [
        "tui",
        (s) =>
          s.replace(
            "        text: runOutcome(action, false, { error: (e as Error).message }),",
            "        text: runOutcome(action, true),",
          ),
      ],
    ],
  ],

  // --- the line renderer -----------------------------------------------------
  [
    "line mode sends the stop to the supervisor proxy",
    [
      [
        "line",
        (s) =>
          s.replace(
            '      await client.request(RUN_STOP_METHOD, runParams("stop", { runId: target }));',
            '      await client.request("task/kill", runParams("stop", { runId: target }));',
          ),
      ],
    ],
  ],

  [
    "line mode swallows a failed pause",
    [
      [
        "line",
        (s) =>
          s.replace(
            "runOutcome(action, false, { error: (e as Error).message })",
            "runOutcome(action, true)",
          ),
      ],
    ],
  ],

  // --- the command table -----------------------------------------------------
  [
    "the /continue command goes back to the supervisor proxy",
    [
      [
        "commands",
        (s) =>
          s.replace(
            '      return { rpcMethod: "setContinuationStopped", rpcParams: { stopped: false } };',
            '      return { rpcMethod: "task/resume" };',
          ),
      ],
    ],
  ],

  [
    "the /pause command goes back to the supervisor proxy",
    [
      [
        "commands",
        (s) =>
          s.replace(
            '      return { rpcMethod: "setContinuationStopped", rpcParams: { stopped: true } };',
            '      return { rpcMethod: "task/pause" };',
          ),
      ],
    ],
  ],

  [
    "the /stop command goes back to the supervisor proxy",
    [
      [
        "commands",
        (s) =>
          s.replace(
            '        return { rpcMethod: "cancel" };',
            '        return { rpcMethod: "task/kill" };',
          ),
      ],
    ],
  ],

  // --- the refusal table -----------------------------------------------------
  [
    "the refusal table regains a task/* entry",
    [
      [
        "caps",
        (s) =>
          insertEntry(
            s,
            "task/kill",
            "a method this daemon does not register, listed here once more",
          ),
      ],
    ],
  ],

  // --- the daemon ------------------------------------------------------------
  [
    "the daemon stops registering setContinuationStopped",
    [
      [
        "methods",
        (s) =>
          s.replace(
            'dispatcher.register("setContinuationStopped", this::setContinuationStopped);',
            'dispatcher.register("setContinuationStoppedXXX", this::setContinuationStopped);',
          ),
      ],
    ],
  ],

  [
    "the HTTP transport stops routing setContinuationStopped",
    [
      [
        "http",
        (s) => s.replace('case "setContinuationStopped"', 'case "setContinuationStoppedXXX"'),
      ],
    ],
  ],

  [
    "the daemon defaults the missing flag to false instead of true",
    [["methods", (s) => s.replace("? b.booleanValue() : true;", "? b.booleanValue() : false;")]],
  ],

  [
    "the daemon stops falling back to the current session",
    [
      [
        "methods",
        (s) =>
          s.replace(
            "            sessionId = engine.appState().sessionId();",
            '            sessionId = "unknown";',
          ),
      ],
    ],
  ],

  [
    "pausing no longer cancels the pending countdown",
    [
      [
        "dispatcher",
        (s) =>
          s.replace(
            "            methods.cancelPendingContinuationCountdown(sessionId);",
            "            int ignored = 0;",
          ),
      ],
    ],
  ],

  [
    "a fresh prompt no longer clears the daemon's own flag",
    [
      [
        "methods",
        (s) =>
          s.replace(
            "            continuationDispatcher.setContinuationStopped(_targetSid, false);",
            "            int ignored = 0;",
          ),
      ],
    ],
  ],

  [
    "the daemon starts wiring the supervisor proxy",
    // The inserted line must be CODE, not a comment: the gate reads
    // DaemonRunner through `javaCode()`, which blanks comments — so a
    // comment mentioning TaskMethods is invisible to it, and rightly
    // so (a comment is not wiring).
    [
      [
        "runner",
        (s) =>
          s.replace(
            "final class DaemonRunner {",
            "final class DaemonRunner {\n    TaskMethods taskMethods = new TaskMethods();",
          ),
      ],
    ],
  ],

  // --- GATE mutations: does an edit here mask a real defect? --------------
  // Each of these neuters ONE assertion and introduces a defect that a
  // SECOND assertion can still see. If the gate goes green, the
  // property had only one derivation and the neutered assertion was
  // load-bearing after all.
  [
    "GATE: neuter the method-mapping test + point stop at task/kill",
    [
      [
        "gate",
        (s) =>
          s.replace(
            '  assert.equal(runControl.runMethod("stop"), "cancel");',
            '  assert.ok(true, "checked elsewhere");',
          ),
      ],
      [
        "control",
        (s) =>
          s.replace(
            'export const RUN_STOP_METHOD = "cancel";',
            'export const RUN_STOP_METHOD = "task/kill";',
          ),
      ],
    ],
  ],

  [
    "GATE: neuter the unservable-method scan + point stop at task/kill",
    [
      [
        "gate",
        (s) =>
          s.replace(
            "for (const m of code.matchAll(/[\"'](task\\/[a-zA-Z]+)[\"']/g)) {",
            "for (const m of code.matchAll(/(?!x)x/g)) {",
          ),
      ],
      [
        "control",
        (s) =>
          s.replace(
            'export const RUN_STOP_METHOD = "cancel";',
            'export const RUN_STOP_METHOD = "task/kill";',
          ),
      ],
    ],
  ],

  [
    "GATE: neuter the paused-state test + drop the paused branch",
    [
      [
        "gate",
        (s) =>
          s.replace(
            '  assert.equal(runControl.runControlState("running", true), "paused");',
            '  assert.ok(true, "checked elsewhere");',
          ),
      ],
      [
        "control",
        (s) => s.replace('  if (paused) return "paused";', '  if (paused) return "running";'),
      ],
    ],
  ],

  [
    "GATE: neuter the Continue-reachability test + drop the paused branch",
    [
      ["gate", (s) => s.replace(/assert\.ok\(\s*valid\.has\("continue"\),/, "  assert.ok(true,")],
      [
        "control",
        (s) => s.replace('  if (paused) return "paused";', '  if (paused) return "running";'),
      ],
    ],
  ],

  [
    "GATE: neuter the button-wiring test + point the Pause button at task/pause",
    [
      [
        "gate",
        (s) =>
          s.replace(
            /assert\.match\(\s*sb,\s*\/onPause=[^,]*\/,\s*"the Pause button must pause[^"]*",?\s*\);/,
            '  assert.ok(true, "checked elsewhere");',
          ),
      ],
      [
        "tui",
        (s) =>
          s.replace(
            "                    void pauseAutoContinue(true);",
            '                    void client.request("task/pause", { id: state.sessionId });',
          ),
      ],
    ],
  ],

  [
    "GATE: neuter the behavioural mirror check + stop clearing the mirror",
    [
      [
        "gate",
        (s) =>
          s.replace(
            /assert\.equal\(\s*submitted\.continuationPaused,\s*false,/,
            '  assert.ok(true, "checked elsewhere");',
          ),
      ],
      [
        "state",
        (s) =>
          s.replace(
            "        continuationPaused: false,\n        turns: [",
            "        continuationPaused: state.continuationPaused,\n        turns: [",
          ),
      ],
    ],
  ],
];

console.log(`r375 mutation battery — ${CASES.length} cases\n`);

let holes = 0;
let noops = 0;
for (const [name, edits] of CASES) {
  const applied = [];
  let broken = null;
  for (const [key, mutate] of edits) {
    const before = original[key];
    const after = mutate(before);
    if (after === before) {
      broken = key;
      break;
    }
    applied.push([key, before, after]);
  }
  if (broken) {
    holes++;
    noops++;
    console.log(
      `  ??  ${name}\n      NO-OP on ${broken} — the pattern did not match, so this case tests nothing`,
    );
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

console.log(
  `\n${holes === 0 ? "ALL MUTATIONS CAUGHT" : `${holes} MUTATION(S) SURVIVED — holes in the gate`}`,
);
if (noops) console.log(`${noops} case(s) were no-ops and did not test anything`);
snapshot.clear();
process.exit(holes === 0 ? 0 : 1);
