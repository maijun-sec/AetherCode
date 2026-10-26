// Mutation harness for r362-panel-intents.test.mjs — NOT part of npm test.
// Run:  node scripts/test/r362-mutate.mjs
//
// R362 never had one, and that is why a hole shipped inside a gate I
// wrote that round: `INLINE_OK` was a bare regex, checked nowhere.
// Disabling the `__HISTORY__` handler in tui.tsx left all 22 assertions
// green. Three cases below, each of which is a way that exact hole — or
// the subtler one hiding behind it — could come back.
//
// Case 3 is the one worth reading. Fixing the INLINE_OK allowlist
// exposed a second gap one level down: `emittedSentinels()` used two
// patterns that both required a closing quote right after the token, so
// every PARAMETERISED sentinel (`__BUDGET__:0`, `__SNIPPET_SAVE__:path`,
// `__MCP_LOGIN__:name`, `__BANK_RECALL__:kind:n`, `__LAYOUT__:name`,
// `__THEME__:name`, `__BOOKMARK__:n`, `__MCP_RECONNECT__:target`,
// `__MEMORY_PANEL__:project`) was invisible to the entire file. Nine
// sentinels a user can type, zero coverage. Reverting the capture must
// therefore turn the gate red — otherwise the widening is decorative.

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
const PATHS = {
  gate: join(here, "r362-panel-intents.test.mjs"),
  line: join(root, "src", "line.ts"),
  tui: join(root, "src", "tui.tsx"),
};

// R375: a battery that is KILLED mid-case must not leave a mutation on
// disk — the next test run would then read a deliberately broken product
// and the failures would belong to neither round. See
// scripts/test/mutation-snapshot.mjs.
const SNAPSHOT = join(root, "tmp-r362-mutate.snapshot.json");
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
requireGreenBaseline(runGate, "r362 mutation battery");

const CASES = [
  [
    "delete the __HISTORY__ handler from BOTH renderers",
    [
      ["line", (s) => s.split("__HISTORY__").join("__REMOVED__")],
      ["tui", (s) => s.split("__HISTORY__").join("__REMOVED__")],
    ],
  ],

  [
    "revert emittedSentinels to the two narrow regexes",
    // The gate had `local:\s*[`'"](__[A-Z_0-9]+__)[`'"]` (needs a closing
    // quote) plus a backtick-only variant. Between them they miss every
    // `local: "__X__:value"` form — 9 sentinels the user can trigger.
    [
      [
        "gate",
        (s) =>
          s.replace(
            /for \(const m of commandsSrc\.matchAll\(\/local:\\s\*\[`'"\]\(__\[A-Z_0-9\]\+__\)\/g\)\) emitted\.add\(m\[1\]\);/,
            "for (const m of commandsSrc.matchAll(/local:\\s*[`'\"](__[A-Z_0-9]+__)[`'\"]/g)) emitted.add(m[1]);\n  for (const m of commandsSrc.matchAll(/local:\\s*`(__[A-Z_0-9]+__)/g)) emitted.add(m[1]);",
          ),
      ],
    ],
  ],

  [
    "add a bogus family to INLINE_FAMILIES",
    // An allowlist entry for a sentinel nobody emits is suppression
    // waiting to happen. The `unused families` assertion is the only
    // thing that notices.
    //
    // R385: the needle used to pin `"PICK_MODE"` as the last entry.
    // That entry left the list this round (it is a panel, not an
    // inline action), so the needle now pins the array's closing
    // bracket instead — a needle that names a VALUE dies with the
    // value, and a needle that names a SHAPE survives the round.
    [
      [
        "gate",
        (s) =>
          s.replace(
            /"SDD",\s*"MEMORY_AUDIT",\s*\]/,
            '"SDD", "MEMORY_AUDIT", "NEVER_EMITTED_XYZ",\n  ]',
          ),
      ],
    ],
  ],

  [
    "drop the 'unused families' assertion entirely",
    [["gate", (s) => s.replace(/const unused = INLINE_FAMILIES\.filter\([^;]*\);\n/, "")]],
  ],
];

console.log(`r362 mutation battery — ${CASES.length} cases\n`);

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
          console.log(`            ${lines[k].trim().slice(0, 200)}`);
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
