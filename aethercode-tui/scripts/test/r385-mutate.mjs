// R385 mutation battery.
//
// ## What this battery is for
//
// R385 gave the TUI a real `/mode` picker on Ink, moved the
// permission-mode vocabulary into ONE file, and added a gate that
// requires every emitted sentinel to have an owner on BOTH renderers.
//
// Each of those is a property that can silently rot:
//
//   - the `mode-picker` registry row can disappear (the sentinel goes
//     back to falling through to the "no panel for that command"
//     fallback — the exact defect R385 fixed);
//   - the row can survive while the panel it names stops rendering;
//   - the row can survive while `PANEL_BY_INTENT` forgets it;
//   - the row can survive while the component stops being imported;
//   - the catalog can lose a mode the daemon has, or gain one it
//     does not;
//   - a file can start writing the vocabulary again;
//   - the argument normalisation can stop;
//   - line mode can stop claiming the sentinel;
//   - the command layer can emit a sentinel nobody owns.
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

const PATHS = {
  gate: join(here, "r385-mode-surface.test.mjs"),
  catalog: join(root, "src", "permissionModes.ts"),
  intents: join(root, "src", "panelIntents.ts"),
  tui: join(root, "src", "tui.tsx"),
  line: join(root, "src", "line.ts"),
  commands: join(root, "src", "commands.ts"),
};

// A battery that is KILLED mid-case must not leave a mutation on disk.
const SNAPSHOT = join(root, "tmp-r385-mutate.snapshot.json");
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
requireGreenBaseline(runGate, "r385 mutation battery");

const CASES = [
  // --- the /mode surface disappears again ------------------------------------
  [
    "the /mode registry row stops declaring its sentinel",
    [
      [
        "intents",
        (s) =>
          mutateInCode(
            s,
            /sentinels: \["__PICK_MODE__"\],/,
            'sentinels: ["__PICK_MODE__X"],',
            "panelIntents.ts",
          ),
      ],
    ],
  ],
  [
    "the panel the /mode row names stops rendering",
    [["tui", (s) => mutateInCode(s, /case "mode":/, 'case "mode_DISABLED":', "tui.tsx")]],
  ],
  [
    "PANEL_BY_INTENT forgets the /mode row",
    [
      [
        "tui",
        (s) => mutateInCode(s, /"mode-picker": "mode",/, '"mode-picker-x": "mode",', "tui.tsx"),
      ],
    ],
  ],
  [
    "the ModePicker stops being imported by the Ink renderer",
    [
      [
        "tui",
        (s) =>
          mutateInCode(
            s,
            /import \{ ModePicker \} from "\.\/components\/ModePicker\.js";/,
            "",
            "tui.tsx",
          ),
      ],
    ],
  ],
  [
    "line mode stops claiming __PICK_MODE__",
    [
      [
        "line",
        (s) => mutateInCode(s, /case "__PICK_MODE__":/, 'case "__PICK_MODE__X":', "line.ts"),
      ],
    ],
  ],

  // --- the catalog drifts from the daemon's enum ------------------------------
  [
    "the catalog drops a mode the daemon has",
    [
      [
        "catalog",
        (s) => mutateInCode(s, /  ASK_BEFORE_TOOL: "ASK_BEFORE_TOOL",\n/, "", "permissionModes.ts"),
      ],
    ],
  ],
  [
    "the catalog invents a mode the daemon does not have",
    [
      [
        "catalog",
        (s) =>
          mutateInCode(
            s,
            /  AUTO_READ_ONLY: "AUTO_READ_ONLY",/,
            '  AUTO_READ_ONLY: "AUTO_READ_ONLY",\n  IMAGINARY: "IMAGINARY",',
            "permissionModes.ts",
          ),
      ],
    ],
  ],
  [
    "a mode row loses its description",
    [
      [
        "catalog",
        (s) =>
          mutateInCode(
            s,
            /blurb: "every tool call asks before running",/,
            'blurb: "",',
            "permissionModes.ts",
          ),
      ],
    ],
  ],

  // --- the vocabulary is written a second time --------------------------------
  [
    "a renderer starts writing a mode name again",
    [["line", (s) => mutateInCode(s, /MODE\.DEFAULT/, '"DEFAULT"', "line.ts")]],
  ],
  [
    "the Ink renderer stops reading the shared catalog",
    [
      [
        "tui",
        (s) =>
          mutateInCode(
            s,
            /import \{ MODE, permissionModeBlurb \} from "\.\/permissionModes\.js";/,
            'import { permissionModeBlurb } from "./permissionModes.js";',
            "tui.tsx",
          ),
      ],
    ],
  ],

  // --- the argument stops being normalised ------------------------------------
  [
    "/mode <name> stops normalising its argument",
    [
      [
        "commands",
        (s) =>
          mutateInCode(
            s,
            /normalisePermissionMode\(String\(rest\[0\]\)\)/,
            "String(rest[0])",
            "commands.ts",
          ),
      ],
    ],
  ],

  // --- a sentinel nobody owns --------------------------------------------------
  [
    "the command layer emits a sentinel nobody claims",
    [
      [
        "commands",
        (s) =>
          mutateInCode(
            s,
            /return \{ local: "usage: \/model <name>" \};/,
            'return { local: "__R385_ORPHAN__" };',
            "commands.ts",
          ),
      ],
    ],
  ],
];

console.log(`r385 mutation battery — ${CASES.length} cases\n`);

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
