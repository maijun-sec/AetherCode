// Copyright (c) 2026 maijun

// Licensed under the MIT License.
// See the LICENSE file in the project root for the full license text.

// Mutation harness for r373-workflow-picker.test.mjs — NOT part of npm test.
// Run:  node scripts/test/r373-mutate.mjs
//
// Every case here breaks ONE thing and the gate must go red. A case
// that leaves the gate green is a hole: the assertion it targets is
// not load-bearing.
//
// Two families:
//
//   * PRODUCT mutations — the TUI or the daemon is broken. Most of
//     them are behavioural, because `workflowRows.ts` is a pure
//     module the gate COMPILES AND RUNS: "the id is the display name
//     again" is a value, not a line of source.
//   * GATE mutations — an assertion is neutered and a real defect is
//     introduced. These are only meaningful when the defect has a
//     SECOND independent derivation, so the gate still catches it
//     with the neutered assertion out of the way. A neutered
//     assertion whose defect only IT could see proves nothing (it
//     proves the assertion was the only one) — those are deleted,
//     not shipped.
//
// The r373 gate deliberately has TWO derivations of "the right method
// name": a source pin on the TUI, and a cross-layer check that the
// daemon actually registers every name the TUI calls. The GATE cases
// below lean on the second one.

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
const repo = join(root, "..");

const PATHS = {
  gate: join(here, "r373-workflow-picker.test.mjs"),
  rows: join(root, "src", "workflowRows.ts"),
  tui: join(root, "src", "tui.tsx"),
  line: join(root, "src", "line.ts"),
  state: join(root, "src", "state.ts"),
  picker: join(root, "src", "components", "WorkflowPicker.tsx"),
  intents: join(root, "src", "panelIntents.ts"),
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
};

// R375: a battery that is KILLED mid-case must not leave a mutation on
// disk — the next test run would then read a deliberately broken product
// and the failures would belong to neither round. See
// scripts/test/mutation-snapshot.mjs.
const SNAPSHOT = join(root, "tmp-r373-mutate.snapshot.json");
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
  return { fail: m ? Number(m[1]) : -1, out };
}

// The gate must be green BEFORE the first mutation. A gate that is
// already red stays red under every mutation, so every case would
// report "caught" and the battery would measure nothing. (R377 Lesson
// 1194.)
requireGreenBaseline(runGate, "r373 mutation battery");

const CASES = [
  // --- the Ink panel is not wired ----------------------------------------
  [
    "the Ink panel is not mounted (the intent maps to nothing)",
    [["tui", (s) => s.replace('  "workflow-picker": "workflows",\n', "")]],
  ],

  [
    "the Ink panel has no render case",
    [["tui", (s) => s.replace('      case "workflows": {', '      case "workflowsXXX": {')]],
  ],

  [
    "the Ink panel is not reachable through the sentinel",
    [["tui", (s) => s.replace("intentForSentinel(bare)", "undefined")]],
  ],

  // --- the wrong method names --------------------------------------------
  [
    "the Ink panel calls `workflow/list` — registered on the wrong server",
    [
      [
        "tui",
        (s) =>
          s.replace(
            '.request("listWorkflows", {} as RpcValue)',
            '.request("workflow/list", {} as RpcValue)',
          ),
      ],
    ],
  ],

  [
    "the Ink run calls `workflow/run` — registered on the wrong server",
    [
      [
        "tui",
        (s) =>
          s.replace(
            '.request("runWorkflow", { name: id } as RpcValue)',
            '.request("workflow/run", { name: id } as RpcValue)',
          ),
      ],
    ],
  ],

  [
    "line mode calls `workflow/list`",
    [
      [
        "line",
        (s) => s.replace('await probe(ctx, "listWorkflows")', 'await probe(ctx, "workflow/list")'),
      ],
    ],
  ],

  [
    "line mode calls `workflow/run`",
    [
      [
        "line",
        (s) =>
          s.replace(
            'await probe(ctx, "runWorkflow", { name: picked } as RpcValue)',
            'await probe(ctx, "workflow/run", { name: picked } as RpcValue)',
          ),
      ],
    ],
  ],

  [
    "the daemon stops registering listWorkflows on stdio",
    [
      [
        "methods",
        (s) => s.replace('dispatcher.register("listWorkflows",   this::listWorkflows);', ""),
      ],
    ],
  ],

  [
    "the daemon stops routing listWorkflows over HTTP",
    [["http", (s) => s.replace('case "listWorkflows"  -> methods.listWorkflows(params);', "")]],
  ],

  // --- the addressable id --------------------------------------------------
  [
    "the Ink run sends the DISPLAY name, which does not resolve a file",
    [
      [
        "tui",
        (s) =>
          s.replace(
            '.request("runWorkflow", { name: id } as RpcValue)',
            '.request("runWorkflow", { name: id.split(".")[0] } as RpcValue)',
          ),
      ],
    ],
  ],

  [
    "the picker hands the display name to onRun",
    [["picker", (s) => s.replace("onRun(w.id);", "onRun(w.name);")]],
  ],

  [
    "line mode sends the display name to runWorkflow",
    [
      [
        "line",
        (s) =>
          s.replace(
            "options: flows.map((w) => ({ label: nameOf(w), value: idOf(w) })),",
            "options: flows.map((w) => ({ label: nameOf(w), value: nameOf(w) })),",
          ),
      ],
    ],
  ],

  [
    "the daemon stops publishing the addressable id",
    [["methods", (s) => s.replace('            m.put("id", e.fileName());', "")]],
  ],

  [
    "the normaliser drops the id and uses the display name",
    [
      [
        "rows",
        (s) =>
          s.replace(/const id = String\(r\.id \?\? r\.file \?\? name\);/, "  const id = name;"),
      ],
    ],
  ],

  [
    "the normaliser invents an id when the daemon published none",
    [
      [
        "rows",
        (s) =>
          s.replace(
            /const id = String\(r\.id \?\? r\.file \?\? name\);/,
            "  const id = String(r.id ?? r.file ?? `workflow-${i}`);",
          ),
      ],
    ],
  ],

  // --- honest emptiness ----------------------------------------------------
  [
    "the daemon stops publishing the files it could not read",
    [["methods", (s) => s.replace('        r.put("skipped", listing.skipped());', "")]],
  ],

  [
    "the client drops the skipped files from the envelope",
    [
      [
        "rows",
        (s) =>
          s.replace(
            "    skipped: Array.isArray(env.skipped) ? env.skipped.map((s) => String(s)) : [],",
            "    skipped: [],",
          ),
      ],
    ],
  ],

  [
    "the empty state says the directory is empty even when files were skipped",
    [["rows", (s) => s.replace(/if \(skipped\.length > 0\) \{/, "  if (false) {")]],
  ],

  [
    "the empty state does not name the unreadable files",
    [
      [
        "rows",
        (s) => s.replace("    for (const f of skipped.slice(0, 5)) lines.push(`  ${f}`);", ""),
      ],
    ],
  ],

  [
    "the empty state invents a directory it was never told",
    [
      [
        "rows",
        (s) =>
          s.replace(
            '  if (dir) {\n    return {\n      tone: "info",\n      lines: ["no workflows defined", `drop a .yaml file under ${dir}`],\n    };\n  }',
            '  if (dir) {\n    return {\n      tone: "info",\n      lines: ["no workflows defined", "drop a .yaml file under C:/somewhere/else"],\n    };\n  }',
          ),
      ],
    ],
  ],

  [
    "the empty state treats an unreadable file as a non-problem",
    [
      [
        "rows",
        (s) =>
          s.replace('    return { tone: "warn", lines };', '    return { tone: "info", lines };'),
      ],
    ],
  ],

  [
    "the reducer drops the skipped list",
    [
      [
        "state",
        (s) =>
          s.replace("        workflowSkipped: action.skipped,", "        workflowSkipped: [],"),
      ],
    ],
  ],

  [
    "the reducer keeps a stale skipped list across a re-fetch",
    [
      [
        "state",
        (s) =>
          s.replace(
            "        workflowSkipped: action.skipped,",
            "        workflowSkipped: state.workflowSkipped,",
          ),
      ],
    ],
  ],

  // --- the counts ----------------------------------------------------------
  [
    "the normaliser invents a step count",
    [
      [
        "rows",
        (s) =>
          s.replace(
            /stepCount:\s*steps\.length > 0 \? steps\.length : Number\.isFinite\(declared\) && declared > 0 \? declared : 0,/,
            "    stepCount: 3,",
          ),
      ],
    ],
  ],

  [
    "the normaliser trusts a non-numeric step count",
    [
      [
        "rows",
        (s) =>
          s.replace(/Number\.isFinite\(declared\) && declared > 0 \? declared : 0,/, "declared,"),
      ],
    ],
  ],

  // --- the report ----------------------------------------------------------
  [
    "the toast claims the workflow finished",
    [
      [
        "tui",
        (s) =>
          s.replace(
            "text: `workflow ${id} accepted (run ${runId})`,",
            "text: `workflow ${id} finished (run ${runId})`,",
          ),
      ],
    ],
  ],

  [
    "the toast claims the workflow started",
    [
      [
        "tui",
        (s) =>
          s.replace(
            "text: `workflow ${id} accepted (run ${runId})`,",
            "text: `workflow ${id} started (run ${runId})`,",
          ),
      ],
    ],
  ],

  // --- the registry --------------------------------------------------------
  [
    "the registry row goes back to unavailable on Ink",
    [
      [
        "intents",
        (s) =>
          s.replace(
            /(component: "components\/WorkflowPicker\.tsx",[\s\S]*?ink: )"mounted",\n(\s*)line: "rendered",/,
            '$1"unavailable",\n$2line: "rendered",\n$2blocker: "not-wired",\n$2gapNote: "nope",',
          ),
      ],
    ],
  ],

  [
    "the registry row keeps a blocker it no longer has",
    [
      [
        "intents",
        (s) =>
          s.replace(
            /component: "components\/WorkflowPicker\.tsx",/,
            'component: "components/WorkflowPicker.tsx",\n    blocker: "not-wired",',
          ),
      ],
    ],
  ],

  // --- one definition ------------------------------------------------------
  [
    "state.ts declares its own WorkflowRow again",
    [
      [
        "state",
        (s) =>
          s.replace(
            'export type { WorkflowRow } from "./workflowRows.js";',
            "export interface WorkflowRow { name: string; id: string; description: string; stepCount: number; inputs: string[]; }",
          ),
      ],
    ],
  ],

  [
    "the Ink panel stops using the shared empty state",
    [
      [
        "picker",
        (s) =>
          s.replace(
            "workflowEmptyState(workflows, skipped, dir)",
            '{ tone: "info", lines: ["nothing here"] }',
          ),
      ],
    ],
  ],

  [
    "line mode stops using the shared empty state",
    [
      [
        "line",
        (s) =>
          s.replace("workflowEmptyCopy(skipped, dir)", '{ tone: "info", lines: ["nothing here"] }'),
      ],
    ],
  ],

  // --- GATE mutations: does an edit here mask a real defect? --------------
  // Each of these neuters ONE assertion and introduces a defect that a
  // SECOND assertion can still see. If the gate goes green, the
  // property had only one derivation and the neutered assertion was
  // load-bearing after all.
  [
    "GATE: neuter the cross-layer method check + call a method the daemon lacks",
    [
      [
        "gate",
        (s) =>
          s.replace(
            /for \(const m of called\) \{\s*assert\.ok\(\s*new RegExp\(`dispatcher\\\\\.register\\\\\("\$\{m\}"`\)\.test\(methodsCode\),/,
            "for (const m of called) {\n    assert.ok(\n      true,",
          ),
      ],
      [
        "tui",
        (s) =>
          s.replace(
            '.request("runWorkflow", { name: id } as RpcValue)',
            '.request("workflow/run", { name: id } as RpcValue)',
          ),
      ],
    ],
  ],

  [
    'GATE: neuter the "unreadable files are not an empty directory" test + ignore skipped',
    [
      [
        "gate",
        (s) =>
          s.replace(
            '  assert.equal(st.tone, "warn", "unreadable files are a problem the user can fix");',
            '  assert.ok(true, "checked elsewhere");',
          ),
        (s) =>
          s.replace(
            '  assert.ok(!st.lines.some((l) => l.includes("no workflows defined")),',
            '  assert.ok(true, "checked elsewhere");',
          ),
      ],
      ["rows", (s) => s.replace(/if \(skipped\.length > 0\) \{/, "  if (false) {")],
    ],
  ],

  [
    'GATE: neuter the "sends the id" assertion + send the display name',
    [
      [
        "gate",
        (s) =>
          s.replace(
            /request\\\("runWorkflow",\\s\*\\\{\\s\*name:\\s\*id\\s\*\\\}\/\.test\(runBlock\)/,
            "true",
          ),
        (s) =>
          s.replace(
            /request\\\("runWorkflow",\\s\*\\\{\\s\*name:\\s\*name\\s\*\\\}\/\.test\(runBlock\)/,
            "true",
          ),
      ],
      [
        "tui",
        (s) =>
          s.replace(
            '.request("runWorkflow", { name: id } as RpcValue)',
            '.request("runWorkflow", { name: id + "-display" } as RpcValue)',
          ),
      ],
    ],
  ],
];

console.log(`r373 mutation battery — ${CASES.length} cases\n`);

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
  const { fail, out } = runGate();
  restore(applied);

  const ok = fail > 0;
  if (!ok) holes++;
  console.log(`  ${ok ? "ok  " : "HOLE"}  ${name}\n      gate fail=${fail}  — gate must go red`);
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
