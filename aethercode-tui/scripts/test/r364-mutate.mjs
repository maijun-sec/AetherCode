// Copyright (c) 2026 maijun

// Licensed under the MIT License.
// See the LICENSE file in the project root for the full license text.

// Mutation harness for r364-cwd-rpc.test.mjs — NOT part of npm test.
// Run:  node scripts/test/r364-mutate.mjs
//
// Same discipline as r363-mutate.mjs: a gate that has never been seen
// red is not evidence. Each case breaks exactly ONE dimension of R364
// and asserts the gate catches it.
//
// The first two cases are the ones that matter most. R364 started from
// a WRONG CONCLUSION ("the backend has no setCwd RPC"), so the gate
// has to hold the corrected name against a well-meaning refactor that
// renames the working `/cwd` call site back to the broken one.

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
const T = {
  tui: join(root, "src", "tui.tsx"),
  line: join(root, "src", "line.ts"),
  state: join(root, "src", "state.ts"),
  intents: join(root, "src", "panelIntents.ts"),
  commands: join(root, "src", "commands.ts"),
};

// R375: a battery that is KILLED mid-case must not leave a mutation on
// disk — the next test run would then read a deliberately broken product
// and the failures would belong to neither round. See
// scripts/test/mutation-snapshot.mjs.
const SNAPSHOT = join(root, "tmp-r364-mutate.snapshot.json");
recoverIfInterrupted(SNAPSHOT);
const original = Object.fromEntries(
  Object.entries(T).map(([k, p]) => [k, readFileSync(p, "utf-8")]),
);
const snapshot = writeSnapshot(
  Object.entries(T).map(([k, p]) => [p, original[k]]),
  SNAPSHOT,
);

function runGate() {
  const r = spawnSync(process.execPath, ["--test", join(here, "r364-cwd-rpc.test.mjs")], {
    encoding: "utf-8",
    cwd: root,
  });
  const out = (r.stdout ?? "") + (r.stderr ?? "");
  const m = out.match(/^ℹ fail (\d+)$/m);
  return { fail: m ? Number(m[1]) : -1, out };
}

// The gate must be green BEFORE the first mutation. A gate that is
// already red stays red under every mutation, so every case would
// report "caught" and the battery would measure nothing. (R377 Lesson
// 1194.)
requireGreenBaseline(runGate, "r364 mutation battery");

const CASES = [
  [
    "rename the CwdSwitcher call back to the Tauri name",
    "tui",
    (s) =>
      s.replace(
        '.request("switchProject", { cwd: path } as RpcValue)',
        '.request("setCwd", { cwd: path } as RpcValue)',
      ),
  ],
  [
    "rename the ThreadSelector call back to the Tauri name",
    "tui",
    (s) =>
      s.replace(
        '.request("switchProject", { cwd: target.cwd } as RpcValue)',
        '.request("setCwd", { cwd: target.cwd } as RpcValue)',
      ),
  ],
  [
    "a 'consistency' refactor drags /cwd back to the broken name",
    "commands",
    (s) => s.replace('rpcMethod: "switchProject"', 'rpcMethod: "setCwd"'),
  ],
  [
    "drop the cwd_changed handler (Ink)",
    "tui",
    (s) =>
      s.replace(
        /case "cwd_changed": \{[\s\S]*?\n        \}\n(?=        case "stream_event": \{)/,
        'case "stream_event": {',
      ),
  ],
  [
    "drop the cwd_changed handler (line mode)",
    "line",
    (s) =>
      s.replace(
        /\} else if \(method === "cwd_changed"\) \{[\s\S]*?\n    \} else if \(method === "log"\)/,
        '} else if (method === "log")',
      ),
  ],
  [
    "render the launch cwd prop again (Header pill goes stale)",
    "tui",
    (s) =>
      s.replace("<Header state={state} cwd={currentCwd} />", "<Header state={state} cwd={cwd} />"),
  ],
  [
    "send the launch cwd to sdd.status",
    "tui",
    (s) =>
      s.replace(
        /"sdd\.status",\s*\{ slug: sddSlug, cwd: currentCwd \}/,
        '"sdd.status", { slug: sddSlug, cwd }',
      ),
  ],
  [
    "stop reconciling against the daemon at startup (Ink)",
    "tui",
    (s) => s.replace(/"listProjects"/, '"listProjectsDISABLED"'),
  ],
  [
    "stop reconciling against the daemon at startup (line)",
    "line",
    (s) =>
      s.replace(
        'await client.request("listProjects")',
        'await client.request("listProjectsDISABLED")',
      ),
  ],
  [
    "rename the action back to setCwd",
    "state",
    (s) => s.replace('type: "setCurrentCwd"; cwd: string', 'type: "setCwd"; cwd: string'),
  ],
  [
    "let a blank cwd wipe the current one",
    "state",
    (s) =>
      s.replace(
        "if (!next || next === state.cwd) return state;",
        "if (next === state.cwd) return state;",
      ),
  ],
  [
    "re-assert the false 'unavailable' claim for the cwd panel",
    // SCOPED to the cwd-switcher entry. A bare
    // `.replace("ink: 'mounted',\n    line: 'rendered'", ...)` replaces the
    // FIRST match in the file — and after R365 several rows share that
    // exact pair, so the uncoped version silently mutated
    // `memory-panel` instead. Same failure mode as the r364 battery's
    // own `listProjects` case: a mutation that changes the wrong object
    // "passes" because it was never testing the thing it named.
    "intents",
    (s) =>
      s.replace(
        /(id: ["']cwd-switcher["'],[\s\S]*?)ink: (["'])mounted\2,\n(\s*)line: (["'])rendered\4/,
        "$1ink: $2unavailable$2,\n$3line: $4unavailable$4,",
      ),
  ],
];

console.log(`r364 mutation battery — ${CASES.length} cases\n`);

let holes = 0;
for (const [name, key, mutate] of CASES) {
  const file = T[key];
  const before = original[key];
  let after;
  try {
    after = mutate(before);
  } catch (e) {
    console.log(`  ??  ${name}\n      mutation threw: ${e.message}`);
    holes++;
    continue;
  }
  if (after === before) {
    console.log(
      `  ??  ${name}\n      NO-OP — the pattern did not match, so the gate is not testing anything`,
    );
    holes++;
    continue;
  }
  writeFileSync(file, after, "utf-8");
  const { fail } = runGate();
  writeFileSync(file, before, "utf-8");
  const caught = fail > 0;
  if (!caught) holes++;
  console.log(`  ${caught ? "ok  " : "HOLE"}  ${name}  (gate fail=${fail})`);
}

// Leave every source file byte-identical.
for (const [k, p] of Object.entries(T)) {
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
