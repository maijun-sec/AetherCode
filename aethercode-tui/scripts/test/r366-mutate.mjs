// Copyright (c) 2026 maijun

// Licensed under the MIT License.
// See the LICENSE file in the project root for the full license text.

// Mutation harness for r366-rpc-names.test.mjs — NOT part of npm test.
// Run:  node scripts/test/r366-mutate.mjs
//
// Four kinds of case live here, and the fourth is the one that matters:
//
//   1. put back the exact bugs this round fixed (setCwd, model/set,
//      workflow/*, the asRows envelopes);
//   2. aim at the allowlist itself — a gate with a reasoned allowlist
//      is only trustworthy if the allowlist is under test;
//   3. aim at the R367 seam: un-wire PermissionMethods / GrantMethods
//      from DaemonRunner and confirm the gate notices. These are the
//      pre-R367 state verbatim: classes complete, registerAll never
//      called, every TUI call -32601;
//   4. aim at the DISCRIMINATOR ITSELF. Cases 3 prove the gate is
//      sensitive to the real bug — but only if something other than
//      "the name appears in the Java file" is doing the work. So we
//      combine each bypass with a real mutation and require the
//      gate to go green (i.e. STOP noticing). A gate that goes green
//      under the bypass has a dead discriminator, and that is a hole
//      even though every other case passes.
//
// Case 4 inverts the pass criterion on purpose: for those cases,
// `fail === 0` is the CORRECT outcome and we report what we mean.

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
// every reason and the two allowlist needles below went NO-OP on the
// spot, silently testing nothing. They now address the table BY KEY
// through one shared editor, so a rewrite of the prose cannot blind
// them again.
import { insertEntry } from "./caps-table.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const T = {
  commands: join(root, "src", "commands.ts"),
  line: join(root, "src", "line.ts"),
  tui: join(root, "src", "tui.tsx"),
  // R369: BACKEND_MISSING moved out of this gate and into src/. The two
  // allowlist mutations below used to edit the gate's own copy, and the
  // day it moved they went NO-OP — silently testing nothing. The
  // harness's no-op detector caught it, which is exactly why that
  // detector exists rather than counting failures only.
  caps: join(root, "src", "rpcCapabilities.ts"),
  // R367: the daemon the TUI actually spawns.
  runner: join(
    root,
    "..",
    "aethercode",
    "aethercode-cli",
    "src",
    "main",
    "java",
    "org",
    "aethercode",
    "cli",
    "DaemonRunner.java",
  ),
  // R367: the OTHER transport. Editing the daemon alone leaves the
  // desktop broken and every TUI-observable assertion green.
  http: join(
    root,
    "..",
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
  // R370: the Java comment stripper moved here (out of the gate) so
  // the R370 gate could reuse it instead of keeping a second,
  // untested copy. The two `javaCode` BYPASS cases below used to edit
  // `gate` and went NO-OP on the day it moved — the same lesson the
  // `caps` note above records, the second occurrence. They now edit
  // this file.
  javastrip: join(here, "java-strip.mjs"),
};
const GATE = join(here, "r366-rpc-names.test.mjs");

const PATHS = { ...T, gate: GATE };

// R375: a battery that is KILLED mid-case must not leave a mutation on
// disk — the next test run would then read a deliberately broken product
// and the failures would belong to neither round. See
// scripts/test/mutation-snapshot.mjs.
const SNAPSHOT = join(root, "tmp-r366-mutate.snapshot.json");
recoverIfInterrupted(SNAPSHOT);
const original = Object.fromEntries(
  Object.entries(PATHS).map(([k, p]) => [k, readFileSync(p, "utf-8")]),
);
const snapshot = writeSnapshot(
  Object.entries(PATHS).map(([k, p]) => [p, original[k]]),
  SNAPSHOT,
);

function runGate() {
  const r = spawnSync(process.execPath, ["--test", GATE], { encoding: "utf-8", cwd: root });
  const out = (r.stdout ?? "") + (r.stderr ?? "");
  const m = out.match(/^ℹ fail (\d+)$/m);
  return { fail: m ? Number(m[1]) : -1, out };
}

/**
 * @param name    human label
 * @param edits   [key, mutateFn][] — applied together
 * @param expect  "red"   → the gate must fail (default)
 *                "green" → the gate must pass (bypass proved: the
 *                          mutation is no longer detected)
 */
// The gate must be green BEFORE the first mutation. A gate that is
// already red stays red under every mutation, so every case would
// report "caught" and the battery would measure nothing. (R377 Lesson
// 1194.)
requireGreenBaseline(runGate, "r366 mutation battery");

const CASES = [
  [
    "re-introduce the R364-era setCwd (Tauri name over JSON-RPC)",
    "red",
    [
      [
        "tui",
        (s) =>
          s.replace(
            '.request("switchProject", { cwd: path } as RpcValue)',
            '.request("setCwd", { cwd: path } as RpcValue)',
          ),
      ],
    ],
  ],
  [
    "re-introduce setCwd in line mode",
    "red",
    [
      [
        "line",
        (s) =>
          s.replace(
            'probe(ctx, "switchProject", { cwd: picked } as RpcValue)',
            'probe(ctx, "setCwd", { cwd: picked } as RpcValue)',
          ),
      ],
    ],
  ],
  [
    "re-introduce model/set (DTOs exist, handler never wired)",
    "red",
    [
      [
        "commands",
        (s) =>
          s.replace(
            'rpcMethod: "setModel", rpcParams: { model: String(rest[0]) }',
            'rpcMethod: "model/set", rpcParams: { name: String(rest[0]) }',
          ),
      ],
    ],
  ],
  [
    "fix the NAME but leave the wrong param key on setModel",
    "red",
    [
      [
        "commands",
        (s) =>
          s.replace(
            'rpcMethod: "setModel", rpcParams: { model: String(rest[0]) }',
            'rpcMethod: "setModel", rpcParams: { name: String(rest[0]) }',
          ),
      ],
    ],
  ],
  [
    "re-introduce workflow/run (SupervisorRpcServer name)",
    "red",
    [
      [
        "line",
        (s) =>
          s.replace(
            'probe(ctx, "runWorkflow", { name: picked }',
            'probe(ctx, "workflow/run", { name: picked }',
          ),
      ],
    ],
  ],
  [
    "re-introduce workflow/list in the picker",
    "red",
    [["line", (s) => s.replace('probe(ctx, "listWorkflows")', 'probe(ctx, "workflow/list")')]],
  ],
  [
    "drop `workflows` back out of the asRows envelope list",
    "red",
    [
      [
        "line",
        (s) =>
          s.replace(
            /"todos",\s*"data",\s*"projects",\s*"workflows"/,
            '"todos", "data", "projects"',
          ),
      ],
    ],
  ],
  [
    "drop `projects` back out of the asRows envelope list",
    "red",
    [
      [
        "line",
        (s) =>
          s.replace(
            /"todos",\s*"data",\s*"projects",\s*"workflows",\s*"grants"/,
            '"todos", "data", "workflows", "grants"',
          ),
      ],
    ],
  ],
  [
    "R367: drop `grants` back out of the asRows envelope list",
    "red",
    // The bug the R367 wiring would have SHIPPED: endpoint reachable,
    // panel confident and empty. Wiring is only half a feature.
    [["line", (s) => s.replace(/"workflows",\s*"grants"/, '"workflows"')]],
  ],

  // --- aimed at the allowlist itself ---
  //
  // R379: the table is EMPTY — the daemon serves every method the TUI
  // names — so "delete an entry" and "blank a reason" have nothing to
  // aim at and went NO-OP (a NO-OP reports like a pass). The PROPERTIES
  // are still real, so the needles are re-aimed at the only shape the
  // table can still take: an entry that is WRONG.
  //
  //   - an entry naming a method the daemon HAS → the stale-allowlist
  //     gate must bite;
  //   - an entry with no reason → the reason gate must bite.
  //
  // The method names used here are ones the daemon does NOT register,
  // so the mutation isolates the gate it is aimed at: the "stale
  // entry" case names `rewind` (which the daemon DOES serve, since
  // R379) and the "no reason" case names a method nobody has.
  [
    "the allowlist regains an entry for a method the daemon HAS (the stale-allowlist gate must bite)",
    "red",
    [
      [
        "caps",
        (s) =>
          insertEntry(
            s,
            "rewind",
            "the daemon cannot roll a transcript back: `AppState` only appends",
          ),
      ],
    ],
  ],
  [
    "the allowlist gains an entry with no reason (the reason gate must bite)",
    "red",
    [["caps", (s) => insertEntry(s, "session/rewindAll", "")]],
  ],
  [
    "make the extraction pattern stop matching (must fail the floor)",
    "red",
    // Break the character class the four extraction patterns share, so every
    // one of them stops matching method names. If the `sent.size >= 40` floor
    // is real this must go red — otherwise the gate's main assertion was
    // passing because it matched nothing.
    [["gate", (s) => s.split("[\\w/.]").join("[zzz]")]],
  ],

  // --- R367: the seam ---
  [
    "R367: comment out PermissionMethods.registerAll in DaemonRunner",
    "red",
    [
      [
        "runner",
        (s) =>
          s.replace(
            "        permRpcs.registerAll(server.dispatcher());",
            "        // permRpcs.registerAll(server.dispatcher());",
          ),
      ],
    ],
  ],
  [
    "R367: comment out GrantMethods.registerAll in DaemonRunner",
    "red",
    [
      [
        "runner",
        (s) =>
          s.replace(
            "        grantRpcs.registerAll(server.dispatcher());",
            "        // grantRpcs.registerAll(server.dispatcher());",
          ),
      ],
    ],
  ],
  [
    "R367: delete the GrantMethods entry from EXTRA_METHOD_CLASSES",
    "red",
    [["gate", (s) => s.replace(/\n\s*\{ type: "GrantMethods"[^\n]*\n/, "\n")]],
  ],
  [
    "R367: delete the PermissionMethods entry from EXTRA_METHOD_CLASSES",
    "red",
    [["gate", (s) => s.replace(/\n\s*\{ type: "PermissionMethods"[^\n]*\n/, "\n")]],
  ],
  // The stdio-only fix: everything the TUI can observe stays green and
  // only the desktop breaks. These mutate the JAVA, never the gate —
  // deleting an assertion from the gate proves nothing about the gate.
  [
    "R367: drop the HTTP `grants/revoke` case arm",
    "red",
    [["http", (s) => s.replace(/\n\s*case "grants\/revoke"[^\n]*\n/, "\n")]],
  ],
  [
    "R367: drop the HTTP `permission/list` case arm",
    "red",
    [["http", (s) => s.replace(/\n\s*case "permission\/list"[^\n]*\n/, "\n")]],
  ],
  [
    "R367: drop http.setGrantMethods from DaemonRunner",
    "red",
    [["runner", (s) => s.replace(/\n\s*http\.setGrantMethods\([^)]*\);/, "")]],
  ],
  [
    "R367: drop http.setPermissionMethods from DaemonRunner",
    "red",
    [["runner", (s) => s.replace(/\n\s*http\.setPermissionMethods\([^)]*\);/, "")]],
  ],
  [
    "R367: delete setPermissionMethods from HttpJsonRpcServer",
    "red",
    [
      [
        "http",
        (s) =>
          s.replace(
            /public void setPermissionMethods\(\s*[\r\n][\s\S]*?\)\s*\{\s*\r?\n\s*this\.permissionMethods = pm;\s*\r?\n\s*\}/,
            "",
          ),
      ],
    ],
  ],

  // --- the discriminators themselves: these MUST go green ---
  [
    "BYPASS: isWired := true, then un-wire PermissionMethods",
    "green",
    [
      [
        "gate",
        (s) =>
          s.replace(
            "const isWired = !!varName && daemonRunnerCode.includes(`${varName}.registerAll(`);",
            "const isWired = true;",
          ),
      ],
      [
        "runner",
        (s) =>
          s.replace(
            "        permRpcs.registerAll(server.dispatcher());",
            "        // permRpcs.registerAll(server.dispatcher());",
          ),
      ],
    ],
  ],
  [
    "BYPASS: javaCode stops blanking block comments",
    "red",
    // R364 Lesson 1112, in Java. A comment-stripper that stops
    // understanding comments does not merely stop blanking them —
    // it misreads them. The apostrophe test is the one that bites:
    // "the stdio daemon's" opens a char literal that never closes and
    // swallows the next 2.4 KB of real code, including the
    // http.setPermissionMethods call this gate reads. Requiring RED
    // here because a broken scanner that still returns plausible
    // output is the most dangerous failure this gate has.
    [["javastrip", (s) => s.replace('if (c === "/" && src[i + 1] === "*") {', "if (false) {")]],
  ],
  [
    "BYPASS: javaCode blanks string literals along with comments",
    "red",
    [
      [
        "javastrip",
        (s) =>
          s.replace(
            "      const q = c;\n      out += c;",
            '      const q = c;\n      for (let z = i; z < n && src[z] !== "\\n"; z++) out += " ";\n      out += c;',
          ),
      ],
    ],
  ],
  [
    "BYPASS: the R367 regression test is deleted",
    "green",
    // If removing the assertion entirely leaves the gate green, then the
    // R367 case was never actually load-bearing — it was decoration.
    [
      [
        "gate",
        (s) =>
          s.replace(
            /test\("REGRESSION R367: the permission\/grant surfaces the TUI uses are live"[\s\S]*?\n\}\);\n/,
            "",
          ),
      ],
    ],
  ],
];

console.log(`r366 mutation battery — ${CASES.length} cases\n`);

let holes = 0;
for (const [name, expect, edits] of CASES) {
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

  // "red"   → fail > 0 is correct
  // "green" → fail === 0 is correct (and it means the bypass is real)
  const ok = expect === "red" ? fail > 0 : fail === 0;
  if (!ok) holes++;
  const tag = ok ? "ok  " : "HOLE";
  const why =
    expect === "red" ? "gate must go red" : "gate must go green (bypass proven load-bearing)";
  console.log(`  ${tag}  ${name}\n      gate fail=${fail}  — ${why}`);
  if (!ok) {
    // Say WHICH assertion moved AND why. A count is not a diagnosis:
    // `gate fail=1` sent me off theorising about the wrong test once
    // already this round, and theorising is how you "fix" the wrong
    // thing convincingly.
    const lines = out.split(/\r?\n/);
    lines.forEach((l, i) => {
      const m = /^✖ (.+?) \(/.exec(l);
      if (!m) return;
      console.log(`        ✖ ${m[1]}`);
      for (let k = i + 1; k < Math.min(i + 5, lines.length); k++) {
        if (/^\s*AssertionError/.test(lines[k])) {
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
snapshot.clear();
process.exit(holes === 0 ? 0 : 1);
