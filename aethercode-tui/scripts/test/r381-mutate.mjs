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

// R381 mutation battery.
//
// ## What this battery is for
//
// R381 made the panels' key tables GENERATED, measured REACHABILITY,
// and gave the checker the ability to see past an apostrophe. Each of
// those is a property that can silently rot:
//
//   - the scanner can go blind again (an apostrophe in JSX text
//     swallows the code after it — the R379 gates were reading
//     whitespace and did not know it);
//   - a key table can drift away from the handler that binds it
//     (the hint promises a key the panel does not bind, or hides one
//     it does);
//   - a component can go back to hand-writing its hint (a second copy
//     of the truth);
//   - a table can be deleted while the component still calls it (the
//     hint renders EMPTY);
//   - a component can stop being reachable while the registry still
//     claims it;
//   - a prose sentence can name a key nobody binds.
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
//     a hole that cannot fail a build is not reported);
//   - the needle lands in CODE, not in a comment (Lesson 1203 — the
//     file changing is not the behaviour changing).

import { readFileSync, writeFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { recoverIfInterrupted, writeSnapshot } from "./mutation-snapshot.mjs";
import { requireGreenBaseline } from "./mutation-baseline.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

const PATHS = {
  gate: join(here, "r381-panel-keys.test.mjs"),
  keys: join(root, "src", "componentKeys.ts"),
  scan: join(here, "ts-scan.mjs"),
  tokens: join(here, "key-tokens.mjs"),
  reach: join(here, "reachability.mjs"),
  theme: join(root, "src", "components", "ThemePicker.tsx"),
  welcome: join(root, "src", "components", "Welcome.tsx"),
  context: join(root, "src", "components", "ContextMeter.tsx"),
  tui: join(root, "src", "tui.tsx"),
};

// A battery that is KILLED mid-case must not leave a mutation on disk —
// the next test run would then read a deliberately broken product and
// the failures would belong to neither round.
const SNAPSHOT = join(root, "tmp-r381-mutate.snapshot.json");
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
requireGreenBaseline(runGate, "r381 mutation battery");

const CASES = [
  // --- the scanner goes blind again -----------------------------------------
  [
    "the scanner swallows the code after an apostrophe in JSX text",
    [
      [
        "scan",
        // Revert the R381 rule: an unterminated quote reads on until it
        // finds the NEXT quote of the same kind, wherever that is.
        (s) =>
          s.replace(
            '        if (src[j] === "\\n" && quote !== "`") break;',
            "        // R381-mutation: read on.",
          ),
      ],
    ],
  ],

  // --- a key table drifts from its handler ----------------------------------
  [
    "a key table drops a key the handler binds",
    [
      [
        "keys",
        (s) =>
          s.replace(
            '{ id: "nav", label: "↑/↓ (j/k): navigate", tokens: ["up", "down", "j", "k"], via: "handler" },\n      { id: "select", label: "Enter: select", tokens: ["return"], via: "handler" },\n      { id: "close", label: "Esc: close", tokens: ["esc"], via: "handler" },\n    ],\n  },\n  {\n    component: "components/InputBox.tsx"',
            '{ id: "nav", label: "↑/↓ (j/k): navigate", tokens: ["up", "down"], via: "handler" },\n      { id: "select", label: "Enter: select", tokens: ["return"], via: "handler" },\n      { id: "close", label: "Esc: close", tokens: ["esc"], via: "handler" },\n    ],\n  },\n  {\n    component: "components/InputBox.tsx"',
          ),
      ],
    ],
  ],
  [
    "a key table claims a key the handler does not bind",
    [
      [
        "keys",
        (s) =>
          s.replace(
            '{ id: "close", label: "Esc: close", tokens: ["esc"], via: "handler" },\n    ],\n  },\n  {\n    component: "components/InputBox.tsx"',
            '{ id: "close", label: "Esc: close", tokens: ["esc", "z"], via: "handler" },\n    ],\n  },\n  {\n    component: "components/InputBox.tsx"',
          ),
      ],
    ],
  ],
  [
    "the constant-bound hotkeys stop being visible to the checker",
    [
      [
        "tokens",
        // The extractor stops resolving `input === HOTKEY.continue`, so
        // SessionControl's three hotkeys vanish from the measurement
        // and a table could drop them without failing.
        (s) =>
          s.replace(
            '    const obj = constants.get(member[1]);\n    if (obj && typeof obj === "object" && member[2] in obj) return obj[member[2]];',
            "    // R381-mutation: constants are no longer resolved.",
          ),
      ],
    ],
  ],

  // --- a component goes back to hand-writing its hint ------------------------
  [
    "a registered component goes back to a hand-written key hint",
    [
      [
        "theme",
        (s) =>
          s.replace(
            '{hintFor("components/ThemePicker.tsx")}',
            "↑/↓ (j/k): navigate Enter: select Esc: close",
          ),
      ],
    ],
  ],
  [
    "a REACHABLE component that is not registered hand-writes a hint",
    [
      [
        "context",
        // ContextMeter is reachable and has a handler, but no key
        // table — so a hand-written hint in it is a second copy of the
        // truth that nobody registered.
        (s) =>
          s.replace(
            "<Text dimColor>ctx </Text>",
            "<Text dimColor>ctx </Text>\n      <Text dimColor>Esc: close</Text>",
          ),
      ],
    ],
  ],
  [
    "a registered component stops rendering the registry's hint",
    [["theme", (s) => s.replace('{hintFor("components/ThemePicker.tsx")}', "{undefined}")]],
  ],
  [
    "a table is deleted while the component still calls it",
    [
      [
        "keys",
        (s) =>
          s.replace(
            '  {\n    component: "components/ThemePicker.tsx",\n    separator: " ",\n    keys: [\n      { id: "nav", label: "↑/↓ (j/k): navigate", tokens: ["up", "down", "j", "k"], via: "handler" },\n      { id: "select", label: "Enter: select", tokens: ["return"], via: "handler" },\n      { id: "close", label: "Esc: close", tokens: ["esc"], via: "handler" },\n    ],\n  },\n',
            "",
          ),
      ],
    ],
  ],

  // --- reachability ----------------------------------------------------------
  [
    "a component stops being rendered, and the registry still claims it",
    [
      [
        "tui",
        // The sub-agent panel is rendered here. Take the render away
        // and the component becomes unreachable — the pinned
        // not-reachable list must notice.
        (s) => s.replace(/<SubagentPanel\n[\s\S]*?\n\s*\/>/, "null"),
      ],
    ],
  ],
  [
    "the reachability walk stops resolving JSX tags to their source",
    [
      [
        "reach",
        (s) =>
          s.replace(
            "      const target = map.get(tag) ?? (own.has(tag) ? f : null);",
            "      const target = own.has(tag) ? f : null;",
          ),
      ],
    ],
  ],

  // --- the prose -------------------------------------------------------------
  [
    "a prose sentence names a key the component does not bind",
    [
      [
        "keys",
        (s) =>
          s.replace(
            '{ token: "shift", via: "handler" },\n          { token: "up", via: "handler" },',
            '{ token: "shift", via: "handler" },\n          { token: "f9", via: "handler" },',
          ),
      ],
    ],
  ],
  [
    "a global chord is invented that the app does not offer",
    [
      [
        "keys",
        (s) =>
          s.replace(
            '{ id: "close", label: "Esc: close", tokens: ["esc"], via: "global" },\n      { id: "select", label: "Enter: select", tokens: ["return"], via: "global" },',
            '{ id: "close", label: "Esc: close", tokens: ["esc", "ctrl+alt+f12"], via: "global" },\n      { id: "select", label: "Enter: select", tokens: ["return"], via: "global" },',
          ),
      ],
    ],
  ],
  [
    "a welcome tip names a command that does not exist",
    [
      [
        "welcome",
        (s) =>
          s.replace(
            "text: `subagents can run in the background — press ${SUBAGENT_PANEL_LABEL} to see live jobs.`,",
            'text: "subagents can run in the background — press Ctrl-S to see live jobs, or /spawn <intent> to launch one inline.",',
          ),
      ],
    ],
  ],
];

console.log(`r381 mutation battery — ${CASES.length} cases\n`);

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

// The snapshot MUST go when the battery finishes — see the R379 note in
// `r379-mutate.mjs`: a snapshot that outlives a clean run is a landmine.
snapshot.clear();

console.log(
  `\n${holes === 0 ? "ALL MUTATIONS CAUGHT" : `${holes} MUTATION(S) SURVIVED — holes in the gate`}` +
    (noops ? ` (${noops} no-op)` : ""),
);
process.exit(holes === 0 ? 0 : 1);
