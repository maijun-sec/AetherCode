// Copyright (c) 2026 maijun

// Licensed under the MIT License.
// See the LICENSE file in the project root for the full license text.

// R380 mutation battery.
//
// ## What this battery is for
//
// R380 collapsed three lists of the command set into one. Each of the
// ways that can go wrong is a property that can rot silently:
//
//   - the flat name list becomes a hand-maintained array again (and
//     drifts from the catalog);
//   - a second group table comes back (and a command ends up in the
//     catalog but not in /help);
//   - an entry loses its group, or a group is split in two;
//   - the router serves a command the catalog never heard of (the
//     R380 /effort shape);
//   - the catalog advertises a command the router does not handle (a
//     promise with nothing behind it);
//   - a command stops rendering a /help row;
//   - the alias mention is hand-written again (a second copy of the
//     alias set).
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
//     reported as a hole, not as a pass (Lesson 1195/1203 — the
//     needle must land in CODE, not in a comment);
//   - every needle is SCOPED to the construct it claims to check;
//   - the battery exits NON-ZERO when a hole survives (Lesson 1196).

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
  gate: join(here, "r380-command-catalog.test.mjs"),
  commands: join(root, "src", "commands.ts"),
};

const SNAPSHOT = join(root, "tmp-r380-mutate.snapshot.json");
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
requireGreenBaseline(runGate, "r380 mutation battery");

const CASES = [
  // --- the flat list is maintained by hand again -------------------------
  [
    "the flat name list becomes a hand-maintained array again",
    [
      [
        "commands",
        (s) =>
          mutateInCode(
            s,
            /export const SLASH_COMMANDS: string\[\] = SLASH_COMMANDS_DETAILED\.map\(\(c\) => c\.name\);/,
            'export const SLASH_COMMANDS: string[] = ["help", "demo"];',
            "commands.ts",
          ),
      ],
    ],
  ],

  // --- the second group table comes back ---------------------------------
  [
    "a second group table comes back",
    [
      [
        "commands",
        (s) =>
          mutateInCode(
            s,
            /export const SLASH_COMMANDS: string\[\] = SLASH_COMMANDS_DETAILED\.map/,
            "const SLASH_COMMAND_GROUPS: ReadonlyArray<readonly [string, readonly string[]]> = " +
              '[["session", ["help"]]];\nexport const SLASH_COMMANDS: string[] = SLASH_COMMANDS_DETAILED.map',
            "commands.ts",
          ),
      ],
    ],
  ],

  // --- an entry loses its group ------------------------------------------
  [
    "a command loses its group",
    [
      [
        "commands",
        (s) =>
          mutateInCode(
            s,
            /\{ name: "demo", group: "session"/,
            '{ name: "demo", group: ""',
            "commands.ts",
          ),
      ],
    ],
  ],

  // --- a group is split in two --------------------------------------------
  [
    "a group is split into two runs",
    [
      [
        "commands",
        // A plausible edit: someone moves /threads into the
        // diagnostics group. /threads sits in the MIDDLE of the session
        // group, so the group now appears in two runs — and /help
        // prints the same header twice, which reads as a mistake to
        // anyone scanning it.
        //
        // (The first version of this case moved /demo, which is the
        // LAST entry of its group — so the group merely grew, no
        // split, and the case was measuring nothing. The second moved
        // /sessions, whose entry is multi-line, so the needle never
        // matched — the no-op detector caught it.)
        (s) =>
          mutateInCode(
            s,
            /\{ name: "threads", group: "session"/,
            '{ name: "threads", group: "diagnostics"',
            "commands.ts",
          ),
      ],
    ],
  ],

  // --- the router serves a command the catalog does not know --------------
  [
    "the router serves a command the catalog never declared",
    [
      [
        "commands",
        (s) =>
          mutateInCode(
            s,
            /case "demo":/,
            'case "demo":\n    case "secret-command":',
            "commands.ts",
          ),
      ],
    ],
  ],

  // --- the catalog advertises a command nothing handles -------------------
  [
    "the catalog advertises a command the router does not handle",
    [
      [
        "commands",
        (s) =>
          mutateInCode(
            s,
            /\{ name: "demo", group: "session"/,
            '{ name: "ghost", group: "session", description: "does nothing" },\n  ' +
              '{ name: "demo", group: "session"',
            "commands.ts",
          ),
      ],
    ],
  ],

  // --- a command stops rendering a row -----------------------------------
  [
    "a command stops rendering a row in /help",
    [
      [
        "commands",
        // Turning a real command into an "alias" gives it no row of its
        // own — so it silently vanishes from /help.
        (s) =>
          mutateInCode(
            s,
            /\{ name: "clear", group: "session"/,
            '{ name: "clear", group: "session", aliasOf: "help"',
            "commands.ts",
          ),
      ],
    ],
  ],

  // --- the alias mention is hand-written again ----------------------------
  [
    "the alias mention is hand-written in a description again",
    [
      [
        "commands",
        (s) =>
          mutateInCode(
            s,
            /\{ name: "help", group: "session", description: "show the shortcut \+ command list"/,
            '{ name: "help", group: "session", description: "show the shortcut + command list (alias: /?)"',
            "commands.ts",
          ),
      ],
    ],
  ],

  // --- the two invisible features go back to being invisible ---------------
  [
    "/effort goes back to being invisible",
    [
      [
        "commands",
        // The needle spans the whole entry, so it does not care whether
        // prettier kept it on one line or wrapped it. The first version
        // of this case assumed one line — and prettier wrapped the entry
        // (the description is the longest in the catalog), so the needle
        // matched nothing and the case measured nothing. The no-op
        // detector caught it.
        (s) => mutateInCode(s, /\{\s*name: "effort",[\s\S]*?\},/, "", "commands.ts"),
      ],
    ],
  ],

  // --- an alias starts shadowing its command ------------------------------
  [
    "an alias shadows the command it stands for again",
    [
      [
        "commands",
        // The shadowing filter is removed: "/tok" now matches both
        // "/token" and "/tokens", so the one-keystroke completion is
        // gone and the user has to type another letter.
        (s) =>
          mutateInCode(s, /return !primary\.startsWith\(cmdPart\);/, "return true;", "commands.ts"),
      ],
    ],
  ],

  // --- a duplicate creeps back into the name list -------------------------
  [
    "a duplicate name creeps back into the catalog",
    [
      [
        "commands",
        (s) =>
          mutateInCode(
            s,
            /\{ name: "demo", group: "session"/,
            '{ name: "demo", group: "session" },\n  { name: "demo", group: "session"',
            "commands.ts",
          ),
      ],
    ],
  ],
];

console.log(`r380 mutation battery — ${CASES.length} cases\n`);

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

// R380: the snapshot MUST go when the battery finishes. A snapshot that
// outlives a clean run is a landmine — the next run cannot tell "the
// last battery was killed" from "the last battery left its snapshot
// behind", and it "recovers", reverting every edit made in between.
snapshot.clear();

console.log(
  `\n${holes === 0 ? "ALL MUTATIONS CAUGHT" : `${holes} MUTATION(S) SURVIVED — holes in the gate`}` +
    (noops ? ` (${noops} no-op)` : ""),
);
process.exit(holes === 0 ? 0 : 1);
