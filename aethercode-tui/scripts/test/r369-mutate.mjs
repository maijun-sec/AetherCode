// Mutation harness for r369-honest-commands.test.mjs — NOT part of npm test.
// Run:  node scripts/test/r369-mutate.mjs
//
// The round this pins: five commands were advertised in `/help` and
// answered -32601. The fix routes every slash command through a
// capability filter in `handleSlash`. These cases attack the three
// ways that can go wrong:
//
//   1. the filter stops running        -> the original bug returns
//   2. the filter over-reaches         -> working commands break while
//                                        the gate stays green
//   3. the filter is per-site instead of at the seam -> fine today,
//      regresses silently on the next command someone adds
//
// Case 2 is the one worth having. A gate that only proves "the broken
// thing is fixed" will happily pass while the fix breaks everything
// else — which is how R365's `helpByGroup()` once advertised a command
// that `handleSlash` had no case for.

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
const PATHS = {
  gate: join(here, "r369-honest-commands.test.mjs"),
  commands: join(root, "src", "commands.ts"),
  caps: join(root, "src", "rpcCapabilities.ts"),
  line: join(root, "src", "line.ts"),
};

// R375: a battery that is KILLED mid-case must not leave a mutation on
// disk — the next test run would then read a deliberately broken product
// and the failures would belong to neither round. See
// scripts/test/mutation-snapshot.mjs.
const SNAPSHOT = join(root, "tmp-r369-mutate.snapshot.json");
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
requireGreenBaseline(runGate, "r369 mutation battery");

const CASES = [
  // --- 1. the filter stops running ---
  [
    "the original bug: /stop answers -32601 again",
    [
      [
        "commands",
        (s) =>
          s.replace(
            "const why = unsupportedReason(result?.rpcMethod);",
            "const why = undefined as string | undefined;",
          ),
      ],
    ],
  ],

  [
    "the filter runs but leaks the raw JSON-RPC error",
    [
      [
        "caps",
        (s) =>
          s.replace(
            "return `${method} is not available on this daemon — ${why}.`;",
            "return `${method} failed: -32601 method not found (${why})`;",
          ),
      ],
    ],
  ],

  [
    "the filter leaks a sentinel token instead of a sentence",
    [
      [
        "caps",
        (s) =>
          s.replace(
            "return `${method} is not available on this daemon — ${why}.`;",
            "return `__UNSUPPORTED__:${method}`;",
          ),
      ],
    ],
  ],

  // --- 2. the filter over-reaches ---
  [
    "the filter swallows a command that DOES work (/model -> setModel)",
    [
      [
        "commands",
        (s) =>
          s.replace(
            "const why = unsupportedReason(result?.rpcMethod);",
            'const why = result?.rpcMethod ? "everything is unavailable" : undefined;',
          ),
      ],
    ],
  ],

  [
    "the filter swallows every sentinel (kills /todos, /memory)",
    [
      [
        "commands",
        (s) =>
          s.replace(
            "const why = unsupportedReason(result?.rpcMethod);",
            'const why = result ? "nothing works" : undefined;',
          ),
      ],
    ],
  ],

  // --- 3. the filter is per-site instead of at the seam ---
  [
    "the filter is deleted from handleSlash and hand-rolled per case",
    [
      [
        "commands",
        (s) =>
          s.replace(
            /export function handleSlash\(input: string\): SlashResult \| null \{[\s\S]*?\n\}/,
            "export function handleSlash(input: string): SlashResult | null {\n  return routeSlash(input);\n}",
          ),
      ],
    ],
  ],

  // R375 removed the three `task/*` entries (the TUI stopped naming
  // those methods at all), so this case is re-aimed at a reason that
  // is still in the table. The PROPERTY is unchanged: a reason is
  // shown to the user verbatim, so it must stand alone — a pointer at
  // another entry ("same as X") is internal shorthand the reader
  // cannot resolve.
  //
  // R377: the needle is now KEY-based (`setReason(s, "rewind", …)`),
  // because R377 rewrote the reasons and a TEXT-based needle went
  // NO-OP — reporting like a pass while testing nothing.
  //
  // R379: the table is EMPTY (the daemon serves every method the TUI
  // names), so `setReason(s, "rewind", …)` has nothing to rewrite and
  // went NO-OP. The needle is re-aimed at the only shape the table can
  // still take: an entry whose reason is internal shorthand. The
  // method name is one the daemon does NOT register, so the mutation
  // isolates the REASON check rather than tripping the stale-allowlist
  // check on the way.
  [
    "a reason is internal shorthand again ('same as rewind')",
    [
      [
        "caps",
        (s) =>
          insertEntry(
            s,
            "session/rewindAll",
            "same as the rewind entry, which the daemon also lacks",
          ),
      ],
    ],
  ],
  // --- line mode's direct probes ---
  [
    "line mode stops consulting the table before probing agent/setDefault",
    [
      [
        "line",
        (s) =>
          s.replace(
            'const agentWhy = unsupportedReason("agent/setDefault");',
            "const agentWhy = undefined as string | undefined;",
          ),
      ],
    ],
  ],

  // R377 DELETED: "line mode stops consulting the table before probing
  // effort/set".
  //
  // The shape it aimed at is GONE, and gone for a good reason: the
  // daemon DOES serve the capability — as `switchVariant` — so there
  // is nothing to consult a table about. A case that measures nothing
  // is not honest; deleting it is.
  //
  // The MECHANISM it covered ("line mode must consult the shared
  // table before probing a method the daemon lacks") is still covered
  // by the `agent/setDefault` case above, and the NEW shape ("the TUI
  // must call the daemon's real method, with the daemon's param
  // name") is covered by `r377-mutate.mjs`.
  //
  // --- the table itself ---
  // R379: the table is EMPTY, so the two "the extraction stops
  // matching" cases above had nothing to aim at: the regex they broke
  // was reading a table with no entries, and breaking it changed
  // nothing. One went NO-OP and the other left a HOLE.
  //
  // The PROPERTY — "the gate must not be blind to the table" — is
  // still real, and it now has a SHAPE the empty table can express:
  // the gate CROSS-CHECKS the source extraction against the compiled
  // table, so an entry the extraction cannot see is a hole, and an
  // entry with a bad reason is a hole.
  [
    "the refusal table gains an entry the source extraction cannot see",
    [
      [
        "caps",
        // The key starts with a capital, so the gate's extraction
        // pattern ([a-z][\w/]*) cannot match it — but the COMPILED
        // table has it. The cross-check must fire.
        (s) =>
          insertEntry(
            s,
            "Session/RewindAll",
            "a reason long enough to be shown to a user on its own, standing alone",
          ),
      ],
    ],
  ],
  [
    "the refusal table gains an entry with a terse reason (the per-reason check must bite)",
    [["caps", (s) => insertEntry(s, "session/rewindAll", "nope")]],
  ],
];

console.log(`r369 mutation battery — ${CASES.length} cases\n`);

let holes = 0;
for (const [name, edits] of CASES) {
  const applied = [];
  let noop = false;
  for (const [key, mutate] of edits) {
    const before = original[key];
    const after = mutate(before);
    if (after === before) {
      console.log(
        `  ??  ${name}\n      NO-OP on ${key} — the pattern did not match, so this case tests nothing`,
      );
      noop = true;
      break;
    }
    applied.push([key, before, after]);
  }
  if (noop) {
    holes++;
    restore(applied);
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
        if (/^\s*AssertionError/.test(lines[k])) {
          console.log(`            ${lines[k].trim().slice(0, 190)}`);
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
snapshot.clear();
process.exit(holes === 0 ? 0 : 1);
