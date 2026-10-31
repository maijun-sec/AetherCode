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

// R388 mutation battery.
//
// ## What this battery is for
//
// R388 closed two defects in one family:
//   - the router had TWO `case` arms for `budget`, so the second one was
//     dead code and the daemon's `setPhaseBudget` was unreachable;
//   - the command palette carried its own hand-written description
//     table, so most rows showed an empty hint and the rest could
//     disagree with the catalogue.
//
// Each of those is a property that can silently rot:
//
//   - a second `case` arm can be added for any name;
//   - the derived description can start returning nothing, or a
//     hardcoded string;
//   - the palette can grow a second table again;
//   - the phase-cap command can drop a field the daemon REQUIRES, or
//     make it optional again and silently unlimit a cap;
//   - the catalogue's descriptions can drift back;
//   - the daemon can stop serving `setPhaseBudget`.
//
// Every case below must turn the gate RED. A case that does not is a
// HOLE, and a hole is the most expensive kind of green: it reports like
// a pass.
//
// The rules this battery follows:
//   - the gate must be GREEN before the first mutation (Lesson 1194);
//   - a needle that matches nothing is a NO-OP, reported as a hole;
//   - every needle lands in CODE, not in a comment, and is SCOPED;
//   - a needle that is not UNIQUE lands on the wrong occurrence;
//   - the battery exits NON-ZERO when a hole survives;
//   - the battery restores every file it touched.

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
  gate: join(here, "r388-command-truth.test.mjs"),
  commands: join(root, "src", "commands.ts"),
  palette: join(root, "src", "components", "CommandPalette.tsx"),
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

const SNAPSHOT = join(root, "tmp-r388-mutate.snapshot.json");
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
  return { fail: m ? Number(m[1]) : -1, status: r.status, out };
}

requireGreenBaseline(runGate, "r388 mutation battery");

const CASES = [
  // --- one name, one handler -------------------------------------------------
  [
    "the duplicate /budget arm comes back",
    [
      [
        "commands",
        (s) => mutateInCode(s, /case "phase-budget": \{/, 'case "budget": {', "commands.ts"),
      ],
    ],
  ],
  [
    "a case label is duplicated on an unrelated command",
    [["commands", (s) => mutateInCode(s, /case "stats":/, 'case "history":', "commands.ts")]],
  ],

  // --- one list, one description ---------------------------------------------
  [
    "the derived description starts returning nothing",
    [
      [
        "commands",
        (s) =>
          mutateInCode(s, /return entry \? entry\.description : "";/, 'return "";', "commands.ts"),
      ],
    ],
  ],
  [
    "the derived description starts inventing its own wording",
    [
      [
        "commands",
        (s) =>
          mutateInCode(
            s,
            /return entry \? entry\.description : "";/,
            'return entry ? "do the thing" : "";',
            "commands.ts",
          ),
      ],
    ],
  ],
  [
    "the palette grows a second description table again",
    [
      [
        "palette",
        (s) =>
          mutateInCode(
            s,
            /import \{ SLASH_COMMANDS, describeCommand \} from "\.\.\/commands\.js";/,
            'import { SLASH_COMMANDS } from "../commands.js";\n' +
              "function describeCommand(name: string): string {\n" +
              '  return name === "budget" ? "set cost budget" : "";\n' +
              "}",
            "CommandPalette.tsx",
          ),
      ],
    ],
  ],
  [
    "the palette stops taking the descriptions from the catalogue",
    [
      [
        "palette",
        (s) =>
          mutateInCode(
            s,
            /import \{ SLASH_COMMANDS, describeCommand \} from "\.\.\/commands\.js";/,
            'import { SLASH_COMMANDS } from "../commands.js";\nconst describeCommand = (n: string) => n;',
            "CommandPalette.tsx",
          ),
      ],
    ],
  ],

  // --- the phase cap stops being honest ---------------------------------------
  [
    "the phase cap drops the cost field the daemon REQUIRES",
    [
      [
        "commands",
        (s) =>
          mutateInCode(
            s,
            /rpcParams: \{ phase, maxToolCalls: maxCalls, maxCostUsd: maxCost \},/,
            "rpcParams: { phase, maxToolCalls: maxCalls },",
            "commands.ts",
          ),
      ],
    ],
  ],
  [
    "the cost cap becomes optional again and silently unlimits it",
    [
      [
        "commands",
        (s) =>
          mutateInCode(s, /if \(rest\.length < 3\) \{/, "if (rest.length < 2) {", "commands.ts"),
      ],
    ],
  ],
  [
    "a negative cap reaches the daemon",
    [
      [
        "commands",
        (s) =>
          mutateInCode(
            s,
            /if \(!Number\.isFinite\(maxCost\) \|\| maxCost < 0\) \{/,
            "if (!Number.isFinite(maxCost)) {",
            "commands.ts",
          ),
      ],
    ],
  ],
  [
    "/budget stops setting the local cost bar",
    [
      [
        "commands",
        (s) =>
          mutateInCode(
            s,
            /return \{ local: "__BUDGET__:" \+ usd \};/,
            'return { local: "budget set" };',
            "commands.ts",
          ),
      ],
    ],
  ],

  // --- the catalogue drifts back ------------------------------------------------
  [
    "the catalogue's /budget entry goes back to describing the phase cap",
    [
      [
        "commands",
        (s) =>
          mutateInCode(
            s,
            /description: "show a cost bar against a USD budget \(Ink only\)",/,
            'description: "set a phase\'s tool-call / cost cap",',
            "commands.ts",
          ),
      ],
    ],
  ],
  [
    "the catalogue loses the phase-budget entry",
    [
      [
        "commands",
        (s) =>
          mutateInCode(
            s,
            /name: "phase-budget",\n(\s*)group: "config",/,
            'name: "phase-budget-removed",\n$1group: "config",',
            "commands.ts",
          ),
      ],
    ],
  ],

  // --- the daemon stops serving it -----------------------------------------------
  [
    "the daemon stops registering setPhaseBudget on stdio",
    [
      [
        "methods",
        (s) =>
          mutateInCode(
            s,
            /dispatcher\.register\("setPhaseBudget",\s*this::setPhaseBudget\);/,
            "",
            "AetherCodeMethods.java",
            "java",
          ),
      ],
    ],
  ],
  [
    "the daemon stops ROUTING setPhaseBudget on HTTP",
    [
      [
        "http",
        (s) =>
          mutateInCode(
            s,
            /case "setPhaseBudget"\s*-> methods\.setPhaseBudget\(params\);/,
            "",
            "HttpJsonRpcServer.java",
            "java",
          ),
      ],
    ],
  ],
];

console.log(`r388 mutation battery — ${CASES.length} cases\n`);

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

snapshot.clear();

console.log(
  `\n${holes === 0 ? "ALL MUTATIONS CAUGHT" : `${holes} MUTATION(S) SURVIVED — holes in the gate`}` +
    (noops ? ` (${noops} no-op)` : ""),
);
process.exit(holes === 0 ? 0 : 1);
