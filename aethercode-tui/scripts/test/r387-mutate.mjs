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

// R387 mutation battery.
//
// ## What this battery is for
//
// R387 made `/clear` a REAL clear: the command starts a fresh
// conversation on the daemon (`createSession`) and only then replaces the
// scrollback, and BOTH renderers do it through ONE shared module.
//
// Each of those is a property that can silently rot:
//
//   - the shared module can start naming a method the daemon does not
//     serve;
//   - the cwd can stop being carried (the new session lands outside the
//     project the user is working in);
//   - the id extraction can start accepting a REFUSED result, or a
//     malformed one — the clear would then adopt a session that does not
//     exist;
//   - the outcome sentence can start calling a failure a success;
//   - `runClear` can start swallowing the daemon's refusal;
//   - the reducer can stop emptying the scrollback, or keep the previous
//     conversation's error card;
//   - a renderer can stop using the shared module and grow a private
//     copy — then the two renderers answer the same command differently;
//   - the no-op notice can come back;
//   - the method name or the outcome wording can be written in a second
//     file — the vocabulary starts drifting;
//   - the daemon can stop REGISTERING the method (stdio) or stop
//     ROUTING it (HTTP) — and then the clear answers -32601;
//   - the catalogue's description can go back to promising only the
//     view.
//
// Every case below must turn the gate RED. A case that does not is a
// HOLE, and a hole is the most expensive kind of green: it reports like
// a pass.
//
// ## The rules this battery follows
//
//   - the gate must be GREEN before the first mutation (Lesson 1194);
//   - a needle that matches nothing is a NO-OP, and a NO-OP is reported
//     as a hole, not as a pass;
//   - every needle lands in CODE, not in a comment (Lesson 1203) and is
//     SCOPED to the construct it claims to check (Lesson 1195 / 1212);
//   - a needle that is not UNIQUE lands on the wrong occurrence
//     (Lesson 1212) — every needle below is checked for that;
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
const javaRoot = join(root, "..", "aethercode");

const PATHS = {
  gate: join(here, "r387-clear-conversation.test.mjs"),
  clear: join(root, "src", "clearConversation.ts"),
  tui: join(root, "src", "tui.tsx"),
  line: join(root, "src", "line.ts"),
  state: join(root, "src", "state.ts"),
  commands: join(root, "src", "commands.ts"),
  methods: join(
    javaRoot,
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
    javaRoot,
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
};

// A battery that is KILLED mid-case must not leave a mutation on disk.
const SNAPSHOT = join(root, "tmp-r387-mutate.snapshot.json");
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
requireGreenBaseline(runGate, "r387 mutation battery");

const CASES = [
  // --- the shared contract stops being a contract ---------------------------
  [
    "the clear names a method the daemon does not serve",
    [
      [
        "clear",
        (s) =>
          mutateInCode(
            s,
            /CLEAR_METHOD = "createSession";/,
            'CLEAR_METHOD = "clearTranscript";',
            "clearConversation.ts",
          ),
      ],
    ],
  ],
  [
    "the clear stops carrying the cwd",
    [
      [
        "clear",
        (s) =>
          mutateInCode(
            s,
            /return trimmed\.length > 0 \? \{ cwd: trimmed \} : \{\};/,
            "return {};",
            "clearConversation.ts",
          ),
      ],
    ],
  ],
  [
    "a REFUSED result yields a session id anyway",
    [
      [
        "clear",
        (s) => mutateInCode(s, /if \(r\.ok !== true\) return null;/, "", "clearConversation.ts"),
      ],
    ],
  ],
  [
    "a malformed result yields a session id anyway",
    [
      [
        "clear",
        (s) =>
          mutateInCode(
            s,
            /const id = r\.sessionId;/,
            'const id = String(r.sessionId ?? "");',
            "clearConversation.ts",
          ),
      ],
    ],
  ],
  [
    "the failure sentence stops naming the reason",
    [
      [
        "clear",
        (s) =>
          mutateInCode(
            s,
            /const why = ctx\.error && ctx\.error\.length > 0 \? ctx\.error : "the daemon refused";/,
            'const why = "the daemon refused";',
            "clearConversation.ts",
          ),
      ],
    ],
  ],
  [
    "a failure is reported as a success",
    [
      [
        "clear",
        (s) =>
          mutateInCode(
            s,
            /return `clear failed: \$\{why\}`;/,
            "return `cleared - ${why}`;",
            "clearConversation.ts",
          ),
      ],
    ],
  ],

  // --- runClear stops running the clear --------------------------------------
  [
    "runClear asks for a different method",
    [
      [
        "clear",
        (s) =>
          mutateInCode(
            s,
            /raw = await request\(CLEAR_METHOD, clearParams\(ctx\.cwd\)\);/,
            'raw = await request("clearTranscript", clearParams(ctx.cwd));',
            "clearConversation.ts",
          ),
      ],
    ],
  ],
  [
    "runClear swallows the daemon's exception and reports success",
    [
      [
        "clear",
        (s) =>
          mutateInCode(
            s,
            /return \{ ok: false, sessionId: null, message: clearOutcome\(false, \{ error: why \}\) \};/,
            'return { ok: true, sessionId: "s-fake", message: clearOutcome(true, { sessionId: "s-fake" }) };',
            "clearConversation.ts",
          ),
      ],
    ],
  ],
  [
    "runClear invents a session id when the daemon refuses",
    [
      [
        "clear",
        (s) =>
          mutateInCode(
            s,
            /return \{ ok: false, sessionId: null, message: clearOutcome\(false, \{ error: reason \}\) \};/,
            'return { ok: true, sessionId: "s-guess", message: clearOutcome(true, { sessionId: "s-guess" }) };',
            "clearConversation.ts",
          ),
      ],
    ],
  ],

  // --- the reducer stops emptying the scrollback ------------------------------
  [
    "adopting a session keeps the old turns",
    [["state", (s) => mutateInCode(s, /turns: nextTurns,/, "turns: state.turns,", "state.ts")]],
  ],
  [
    "the previous conversation's error card survives the switch",
    [
      [
        "state",
        (s) =>
          mutateInCode(
            s,
            /viewHistory: \[\],\n\s*lastStopReason: null,\n\s*lastStopKind: null,\n\s*lastError: null,/,
            "viewHistory: [],\n        lastStopReason: null,\n        lastStopKind: null,",
            "state.ts",
          ),
      ],
    ],
  ],

  // --- a renderer grows a private copy -----------------------------------------
  [
    "the Ink renderer stops using the shared clear module",
    [
      [
        "tui",
        (s) =>
          mutateInCode(
            s,
            /import \{ runClear \} from "\.\/clearConversation\.js";/,
            'const runClear = async (_r: unknown, _c: unknown) => ({ ok: false, sessionId: null, message: "clear failed" });',
            "tui.tsx",
          ),
      ],
    ],
  ],
  [
    "the line renderer stops using the shared clear module",
    [
      [
        "line",
        (s) =>
          mutateInCode(
            s,
            /import \{ runClear \} from "\.\/clearConversation\.js";/,
            'const runClear = async (_r: unknown, _c: unknown) => ({ ok: false, sessionId: null, message: "clear failed" });',
            "line.ts",
          ),
      ],
    ],
  ],
  [
    "the Ink renderer adopts the new session but keeps the old turns",
    [
      [
        "tui",
        (s) =>
          mutateInCode(
            s,
            /dispatch\(\{ type: "adoptSession", sessionId: r\.sessionId, turns: \[\] \}\);/,
            'dispatch({ type: "adoptSession", sessionId: r.sessionId, turns: state.turns });',
            "tui.tsx",
          ),
      ],
    ],
  ],
  [
    "the line renderer keeps the turns of the conversation it left",
    [["line", (s) => mutateInCode(s, /turnsBuffer\.length = 0;/, "", "line.ts")]],
  ],

  // --- the vocabulary drifts ---------------------------------------------------
  [
    "the old no-op notice comes back",
    [
      [
        "tui",
        (s) =>
          mutateInCode(
            s,
            /if \(slash\?\.local === "__CLEAR__"\) \{/,
            'if (slash?.local === "__CLEAR__") {\n      dispatch({ type: "log", message: "(clear is a no-op in this build)" });',
            "tui.tsx",
          ),
      ],
    ],
  ],
  [
    "a renderer writes the daemon method name itself",
    [
      [
        "line",
        (s) =>
          mutateInCode(
            s,
            /if \(slash\?\.local === "__CLEAR__"\) \{/,
            'if (slash?.local === "__CLEAR__") {\n      const method = "createSession";\n      void method;',
            "line.ts",
          ),
      ],
    ],
  ],
  [
    "a renderer writes the outcome wording itself",
    [
      [
        "tui",
        (s) =>
          mutateInCode(
            s,
            /if \(slash\?\.local === "__CLEAR__"\) \{/,
            'if (slash?.local === "__CLEAR__") {\n      const wording = "cleared — done";\n      void wording;',
            "tui.tsx",
          ),
      ],
    ],
  ],

  // --- the daemon stops serving the method -------------------------------------
  [
    "the daemon stops registering the clear's method on stdio",
    [
      [
        "methods",
        (s) =>
          mutateInCode(
            s,
            /dispatcher\.register\("createSession",\s*this::createSession\);/,
            "",
            "AetherCodeMethods.java",
            "java",
          ),
      ],
    ],
  ],
  [
    "the daemon stops ROUTING the clear's method on HTTP",
    [
      [
        "http",
        (s) =>
          mutateInCode(
            s,
            /case "createSession" -> methods\.createSession\(params\);/,
            "",
            "HttpJsonRpcServer.java",
            "java",
          ),
      ],
    ],
  ],

  // --- the catalogue stops telling the truth ------------------------------------
  [
    "the catalogue's /clear description goes back to promising only the view",
    [
      [
        "commands",
        (s) =>
          mutateInCode(
            s,
            /description: "clear the scrollback and start a new session",/,
            'description: "clear the scrollback",',
            "commands.ts",
          ),
      ],
    ],
  ],
];

console.log(`r387 mutation battery — ${CASES.length} cases\n`);

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
