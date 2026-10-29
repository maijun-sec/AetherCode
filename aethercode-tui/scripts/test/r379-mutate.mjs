// Copyright (c) 2026 maijun

// Licensed under the MIT License.
// See the LICENSE file in the project root for the full license text.

// R379 mutation battery.
//
// ## What this battery is for
//
// R379 closed three real capability gaps, surfaced the daemon's
// auto-continuation countdown, and moved the panels' key tables into a
// registry. Each of those is a property that can silently rot:
//
//   - a method can be registered on stdio but NOT routed on the HTTP
//     switch (the `switchVariant` shape — one daemon works, the other
//     answers -32601);
//   - a handler can answer to the right NAME and still not do the
//     thing (a stub that returns ok:true without touching the
//     transcript is a fake success);
//   - a refusal table can start lying;
//   - a notification can stop being handled, and the daemon then
//     starts turns on its own with no warning;
//   - a key table can drift away from the handler that binds it.
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
//   - every needle is SCOPED to the construct it claims to check
//     (Lesson 1195 — a mutation that changes the wrong occurrence is a
//     no-op in disguise);
//   - the battery exits NON-ZERO when a hole survives (Lesson 1196 —
//     a hole that cannot fail a build is not reported).

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
  gate: join(here, "r379-capabilities.test.mjs"),
  caps: join(root, "src", "rpcCapabilities.ts"),
  keys: join(root, "src", "componentKeys.ts"),
  countdown: join(root, "src", "continuationCountdown.ts"),
  reports: join(root, "src", "rpcReports.ts"),
  commands: join(root, "src", "commands.ts"),
  tui: join(root, "src", "tui.tsx"),
  line: join(root, "src", "line.ts"),
  grants: join(root, "src", "components", "permissions", "GrantsManager.tsx"),
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
};

// A battery that is KILLED mid-case must not leave a mutation on disk —
// the next test run would then read a deliberately broken product and
// the failures would belong to neither round.
const SNAPSHOT = join(root, "tmp-r379-mutate.snapshot.json");
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
requireGreenBaseline(runGate, "r379 mutation battery");

const CASES = [
  // --- the daemon stops serving a capability ---------------------------------
  [
    "the daemon stops registering agent/setDefault on stdio",
    [["methods", (s) => s.replace(/dispatcher\.register\("agent\/setDefault"[^\n]*\n/, "")]],
  ],
  [
    "the HTTP transport stops routing rewind",
    [["http", (s) => s.replace(/case "rewind"[^\n]*\n/, "")]],
  ],
  [
    "the HTTP transport stops routing session/tokens",
    [["http", (s) => s.replace(/case "session\/tokens"[^\n]*\n/, "")]],
  ],
  [
    "the rewind handler answers to the name but never rewinds",
    [
      [
        "methods",
        // SCOPED to the handler. A bare replace() would hit the first
        // `engine.rewind(` in the file, which is not necessarily this
        // one (Lesson 1195).
        (s) =>
          s.replace(
            /(public Object rewind\(Object params\)[\s\S]*?)engine\.rewind\(target\);/,
            "$1// R379: rewound nothing.",
          ),
      ],
    ],
  ],
  [
    "the session/tokens handler reads the GLOBAL total instead of the session's",
    [
      [
        "methods",
        (s) =>
          s.replace(
            /(public Object sessionTokens\(Object params\)[\s\S]*?)tracker\.sessionSummary\(sessionId\)/,
            "$1tracker.summary()",
          ),
      ],
    ],
  ],
  [
    "the agent/setDefault handler never installs the agent's instructions",
    [
      [
        "methods",
        (s) =>
          s.replace(
            /(public Object setDefaultAgent\(Object params\)[\s\S]*?)engine\.setDefaultAgent\(/,
            "$1/* R379: installed nothing. */ (",
          ),
      ],
    ],
  ],

  // --- the refusal table starts lying ---------------------------------------
  [
    "the refusal table claims a gap the daemon does not have",
    [
      [
        "caps",
        (s) =>
          s.replace(
            "export const BACKEND_MISSING",
            'export const BACKEND_MISSING = new Map<string, string>([["rewind", "pretend"]]);\nconst UNUSED_TABLE =',
          ),
      ],
    ],
  ],

  // --- the TUI stops dispatching --------------------------------------------
  [
    "/tokens stops dispatching to session/tokens",
    [
      [
        "commands",
        (s) => s.replace('return { rpcMethod: "session/tokens" };', 'return { local: "nope" };'),
      ],
    ],
  ],
  [
    "/rewind stops dispatching to rewind",
    [
      [
        "commands",
        (s) =>
          s.replace(
            'return { rpcMethod: "rewind", rpcParams: { target: n - 1 } };',
            'return { local: "nope" };',
          ),
      ],
    ],
  ],

  // --- the countdown ---------------------------------------------------------
  [
    "the daemon stops publishing the countdown",
    [
      [
        "dispatcher",
        // SCOPED to the CALL. The method name also lives in the
        // constant's declaration, so a needle that only removed the
        // literal would leave the name behind — and the gate would
        // stay green on a daemon that had stopped counting down.
        (s) =>
          mutateInCode(
            s,
            /methods\.notifyCustom\(\s*NOTIFY_TODO_CONTINUATION_COUNTDOWN\s*,\s*payload\s*\);/,
            "// R379: silent.",
            "EngineContinuationDispatcher.java",
            "java",
          ),
      ],
    ],
  ],
  [
    "the Ink renderer stops handling the countdown",
    [
      [
        "tui",
        // The replacement is a STRING LITERAL, so the file still
        // compiles. That matters: a mutation that broke the TypeScript
        // compile would make the gate go red for the wrong reason, and
        // "the gate went red" would prove nothing about the assertion
        // under test.
        (s) =>
          mutateInCode(
            s,
            /case\s+COUNTDOWN_METHOD\s*:/,
            'case "todo_continuation_countdown_DISABLED":',
            "tui.tsx",
          ),
      ],
    ],
  ],
  [
    "the line renderer stops handling the countdown",
    [
      [
        "line",
        (s) =>
          mutateInCode(
            s,
            /method\s*===\s*COUNTDOWN_METHOD/,
            'method === "todo_continuation_countdown_DISABLED"',
            "line.ts",
          ),
      ],
    ],
  ],
  [
    "the countdown's method name drifts from the daemon's",
    [
      [
        "countdown",
        // The TUI and the daemon have to agree on the name. Nothing in
        // the TUI can check the daemon's copy, so the gate pins the
        // name against the daemon's own emission — and this mutation
        // is what that pin is for.
        (s) =>
          mutateInCode(
            s,
            /COUNTDOWN_METHOD\s*=\s*"todo_continuation_countdown"/,
            'COUNTDOWN_METHOD = "todo_continuation_countdown_v2"',
            "continuationCountdown.ts",
          ),
      ],
    ],
  ],
  [
    "the countdown line stops promising the cancel affordance",
    [
      [
        "countdown",
        // SCOPED to the CODE. The first occurrence of "· esc to cancel"
        // in this file is in the JSDoc that QUOTES Claude Code; the
        // template literal that builds the line comes later. The first
        // version of this needle changed the COMMENT — the file
        // changed, the behaviour did not, and the case reported a hole
        // for the right reason by accident.
        (s) => mutateInCode(s, /· esc to cancel/, "· continuing", "continuationCountdown.ts"),
      ],
    ],
  ],
  [
    "the countdown parser invents a countdown from a payload that has none",
    [
      [
        "countdown",
        (s) =>
          s.replace(
            "if (remainingMs === null) return null;",
            "if (remainingMs === null) remainingMs = 0;",
          ),
      ],
    ],
  ],

  // --- the panels' key tables ------------------------------------------------
  //
  // R381: these three cases MOVED to `r381-mutate.mjs`.
  //
  // The property they check — "the key table matches the handler, and
  // the panel renders the table instead of a hand-written hint" — was
  // checked by the R379 gate, and R381 moved that section into the
  // R381 gate (which is a strict superset: it covers every REACHABLE
  // component, not just the ones behind the advertised panel intents,
  // and it resolves constants).
  //
  // Leaving the cases here would have been worse than deleting them:
  // they would mutate `src/componentKeys.ts` and the R379 gate — which
  // no longer looks at the tables — would stay green, and the battery
  // would report three holes for a property it no longer owns.
  //
  // The R381 battery has the same three cases (plus more).
  //
  // (A property, its gate and its mutation cases travel together. A
  // case whose gate moved away is a case that measures nothing.)

  // --- the reports -----------------------------------------------------------
  [
    "the token report turns a failure into a row of zeroes",
    [
      [
        "reports",
        (s) =>
          s.replace(
            'return `session/tokens failed: ${String(r.error ?? "unknown error")}`;',
            "return `tokens · ${total} total`;",
          ),
      ],
    ],
  ],
];

console.log(`r379 mutation battery — ${CASES.length} cases\n`);

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

// R379 — the snapshot MUST go when the battery finishes. A snapshot
// that outlives a clean run is a landmine: the next run cannot tell
// "the last battery was killed" from "the last battery left its
// snapshot behind", and it "recovers" — reverting every edit the
// developer made in between. This battery was the only one of the
// fourteen that never cleared it, and it cost the round its gate
// tightening AND the daemon's Java change.
//
// `mutation-snapshot.mjs` now also deletes a clean-run snapshot by
// itself (it compares the tree against the recorded originals on
// exit), but the explicit call is what the other thirteen batteries
// do, and two mechanisms that agree are better than one that can be
// forgotten.
snapshot.clear();

console.log(
  `\n${holes === 0 ? "ALL MUTATIONS CAUGHT" : `${holes} MUTATION(S) SURVIVED — holes in the gate`}` +
    (noops ? ` (${noops} no-op)` : ""),
);
process.exit(holes === 0 ? 0 : 1);
