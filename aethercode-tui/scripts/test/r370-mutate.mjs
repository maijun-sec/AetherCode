// Mutation harness for r370-session-picker.test.mjs — NOT part of npm test.
// Run:  node scripts/test/r370-mutate.mjs
//
// A gate that has never been shown to go red is a decoration. Every
// case below is a way `/sessions` could rot back into what it was:
//
//   * the raw `listSessions` wire dump
//   * a picker that ignores the cwd
//   * a picker that works on Ink and dies on cmd.exe (R370's own bug)
//   * a cwd comparison that is string equality in disguise
//   * a session list that quietly loses the unattributed rows
//
// Two cases mutate the GATE rather than the product. That is the
// lesson from R362 (allowlist widened, assertion deleted) and R368
// (`matchAll` with no counted floor): a gate can be made to agree
// with a broken product by editing the gate, and the only defence is
// to prove the gate's own extraction is load-bearing.
//
// Every case reports NO-OP separately. A regex that stops matching is
// a case that tests nothing, and it must never be counted as a pass.

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
  gate: join(here, "r370-session-picker.test.mjs"),
  commands: join(root, "src", "commands.ts"),
  tui: join(root, "src", "tui.tsx"),
  line: join(root, "src", "line.ts"),
  state: join(root, "src", "state.ts"),
  intents: join(root, "src", "panelIntents.ts"),
  cwd: join(root, "src", "cwdMatch.ts"),
  sel: join(root, "src", "components", "ThreadSelector.tsx"),
};

// R375: a battery that is KILLED mid-case must not leave a mutation on
// disk — the next test run would then read a deliberately broken product
// and the failures would belong to neither round. See
// scripts/test/mutation-snapshot.mjs.
const SNAPSHOT = join(root, "tmp-r370-mutate.snapshot.json");
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
requireGreenBaseline(runGate, "r370 mutation battery");

const CASES = [
  [
    "put /sessions back on the raw listSessions wire dump",
    [
      [
        "commands",
        (s) =>
          s.replace(
            'return { local: "__SESSION_PICKER__" };',
            'return { rpcMethod: "listSessions" };',
          ),
      ],
    ],
  ],

  [
    "drop session-picker from PANEL_BY_INTENT (dead sentinel)",
    [["tui", (s) => s.replace('"session-picker": "threads",', "")]],
  ],

  [
    "point session-picker at its own PanelId instead of reusing threads",
    [["tui", (s) => s.replace('"session-picker": "threads",', '"session-picker": "sessions",')]],
  ],

  [
    "remove session-picker from CWD_SCOPED_INTENTS (filter silently off)",
    [["tui", (s) => s.replace('new Set(["session-picker"])', "new Set([])")]],
  ],

  [
    "stop passing cwdOnly at openPanel (the set becomes decorative)",
    [
      [
        "tui",
        (s) => s.replace(/cwdOnly: intent \? CWD_SCOPED_INTENTS\.has\(intent\.id\) : false,/, ""),
      ],
    ],
  ],

  [
    "the fetch ignores panelCwdOnly (both commands list everything)",
    [
      [
        "tui",
        (s) =>
          s.replace(
            /dispatch\(\{ type: "setThreads", threads: scoped \? mine : all \}\);/,
            'dispatch({ type: "setThreads", threads: all });',
          ),
      ],
    ],
  ],

  [
    "drop the unattributed count, so /sessions can report 0 of 20 silently",
    [["tui", (s) => s.replace(/if \(scoped && other\.length > 0\) \{/, "if (false) {")]],
  ],

  [
    "revert loadSession to the `{ id }` key line mode always sent",
    // The exact R370 bug. Ink kept working, cmd.exe died on -32602
    // after printing the table it invited the user to pick from.
    [
      [
        "line",
        (s) =>
          s.replace(
            'probe(ctx, "loadSession", { sessionId: picked } as RpcValue)',
            'probe(ctx, "loadSession", { id: picked } as RpcValue)',
          ),
      ],
    ],
  ],

  [
    "revert loadSession in Ink too",
    [
      [
        "tui",
        (s) =>
          s.replace(
            '.request("loadSession", { sessionId: threadId } as RpcValue)',
            '.request("loadSession", { id: threadId } as RpcValue)',
          ),
      ],
    ],
  ],

  // R372 reshaped this: the reducer now reads `action.turns ?? []`
  // and the case body no longer contains the literal `turns: [],`.
  // The old target stopped matching and the harness correctly called it
  // a NO-OP. Aim it at the new shape.
  [
    "adoptSession stops clearing the old scrollback",
    [
      [
        "state",
        (s) =>
          s.replace(
            "      const nextTurns = action.turns ?? [];",
            "      const nextTurns: Turn[] = action.turns ?? state.turns;",
          ),
      ],
    ],
  ],

  [
    "Ink stops adopting the picked session id",
    [["tui", (s) => s.replace('dispatch({ type: "adoptSession", sessionId: adopted });', "")]],
  ],

  [
    "line mode stops adopting the picked session id",
    [
      [
        "line",
        (s) =>
          s.replace(
            /if \(typeof back === "string" && back\.length > 0\) ctx\.sessionId = back;/,
            "void back;",
          ),
      ],
    ],
  ],

  [
    "delete the line-mode __SESSION_PICKER__ arm (cmd.exe prints the token)",
    [["line", (s) => s.replace(/case "__SESSION_PICKER__":[\s\S]*?return true;\n/, "")]],
  ],

  [
    "/sessions' line arm stops scoping to the cwd",
    [
      [
        "line",
        (s) =>
          s.replace(
            /case "__SESSION_PICKER__":\s*\n\s*await panelThreads\(ctx, true\);/,
            'case "__SESSION_PICKER__":\n      await panelThreads(ctx, false);',
          ),
      ],
    ],
  ],

  [
    "/threads silently adopts the cwd filter (the fix swallowed the old command)",
    [
      [
        "line",
        (s) =>
          s.replace(
            /case "__THREAD_SELECTOR__":\s*\n\s*await panelThreads\(ctx, false\);/,
            'case "__THREAD_SELECTOR__":\n      await panelThreads(ctx, true);',
          ),
      ],
    ],
  ],

  [
    "sameCwd degrades to string equality (the case-folding the whole point)",
    [
      [
        "cwd",
        (s) =>
          s.replace(
            /function canonical\(p: string\): string \{[\s\S]*?\n\}/,
            'function canonical(p: string): string {\n  return (p ?? "").trim();\n}',
          ),
      ],
    ],
  ],

  [
    "sameCwd starts treating two unknowns as equal",
    [
      [
        "cwd",
        (s) =>
          s.replace(
            /if \(ca\.length === 0 \|\| cb\.length === 0\) return false;/,
            "if (ca.length === 0 && cb.length === 0) return true;",
          ),
      ],
    ],
  ],

  [
    "canonical collapses C:\\ into the drive-relative C:",
    [["cwd", (s) => s.replace("&& !/^[a-zA-Z]:[\\\\/]$/.test(s)", "")]],
  ],

  [
    "ThreadSelector's cwd badge goes back to string equality",
    [
      [
        "sel",
        (s) =>
          s.replace(
            /const cwdMismatch = !cwdUnknown && !sameCwd\(t\.cwd, currentCwd\);/,
            "const cwdMismatch = t.cwd !== currentCwd;",
          ),
      ],
    ],
  ],

  [
    "chooseCwd goes back to string equality",
    [
      [
        "sel",
        (s) =>
          s.replace(
            'if (sameCwd(threadCwd, currentCwd)) return "stay";',
            'if (threadCwd === currentCwd) return "stay";',
          ),
      ],
    ],
  ],

  [
    "the picker stops rendering updatedAt",
    [["sel", (s) => s.replace(/\{t\.updatedAt \? <Text dimColor>[^\n]*\n/, "")]],
  ],

  [
    "the picker's empty state is hard-coded again",
    [
      [
        "sel",
        (s) =>
          s.replace(
            "<Text dimColor> {emptyLabel}</Text>",
            "<Text dimColor>  no threads found</Text>",
          ),
      ],
    ],
  ],

  [
    "panelCwdOnly is not cleared on close (leaks into the next panel)",
    [
      [
        "state",
        (s) =>
          s.replace(
            /activePanel: null,\n\s*\/\/ R370: cleared with the panel[^\n]*\n\s*panelCwdOnly: false,/,
            "activePanel: null,",
          ),
      ],
    ],
  ],

  [
    "the registry loses the session-picker row (so /help drops the command)",
    [["intents", (s) => s.replace(/  \{\n    id: ["']session-picker["'],[\s\S]*?\n  \},\n/, "")]],
  ],

  // --- gate mutations ------------------------------------------------
  //
  // These edit the GATE and pair it with a REAL product defect. The
  // case passes when the defect is STILL caught by some other
  // assertion — i.e. no single assertion is load-bearing.
  //
  // They must not be written as "edit the gate alone and expect red".
  // That is unsatisfiable by construction: weakening a check and then
  // asking the weakened check to complain is a category error, and it
  // is why the first draft of this file reported three of these as
  // holes when the gate was fine.
  //
  // The question each one answers is the R362 question: can an edit to
  // the gate make a real defect invisible?  — Lesson 1135.

  [
    "GATE: neuter panelOf (substring lookup) + wrong PanelId for session-picker",
    [
      [
        "gate",
        (s) =>
          s.replace(
            /const panelOf = \(intent\) => \{[\s\S]*?\n  \};/,
            'const panelOf = (intent) => (body.includes(`"${intent}"`) ? "threads" : undefined);',
          ),
      ],
      ["tui", (s) => s.replace('"session-picker": "threads",', '"session-picker": "sessions",')],
    ],
  ],

  [
    "GATE: drop the Java cross-check + line.ts back to `{ id }`",
    // The renderer-side assertion is the braces; the Java read is the
    // belt. Neutering the belt must not open a hole for cmd.exe.
    [
      [
        "gate",
        (s) =>
          s.replace(
            /const body = javaMethod\(methodsCode, ["']public Object loadSession\("\);[\s\S]*?assert\.equal\(\s*key\[1\],\s*"sessionId",[\s\S]*?\n/,
            "",
          ),
      ],
      [
        "line",
        (s) =>
          s.replace(
            'probe(ctx, "loadSession", { sessionId: picked } as RpcValue)',
            'probe(ctx, "loadSession", { id: picked } as RpcValue)',
          ),
      ],
    ],
  ],

  // The third case was originally "neuter the renderers' params-key
  // assertion + revert Ink to `{ id }`", which reported a hole. It was
  // not a hole: that assertion is the ONLY thing in the file that asks
  // "do WE send the daemon's key", and the Java cross-check answers a
  // different question ("what does the daemon want"). Demanding
  // redundancy there is demanding a second copy of the same question,
  // which is the duplication this repo keeps refusing.
  //
  // So the third case targets an assertion that genuinely has an
  // independent derivation: `panelCwdOnly` appears in the threads
  // fetch block, and the flag's EFFECT (scoped ? mine : all reaching
  // setThreads) is checked separately.
  [
    "GATE: neuter the 'fetch reads panelCwdOnly' assertion + fetch ignores the flag",
    [
      [
        "gate",
        (s) =>
          s.replace(
            /assert\.ok\(\s*\/panelCwdOnly\/\.test\(body\),[\s\S]*?render identical lists",?\s*\);/,
            "assert.ok(true);",
          ),
      ],
      [
        "tui",
        (s) =>
          s.replace(
            /dispatch\(\{ type: "setThreads", threads: scoped \? mine : all \}\);/,
            'dispatch({ type: "setThreads", threads: all });',
          ),
      ],
    ],
  ],
];

console.log(`r370 mutation battery — ${CASES.length} cases\n`);

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

// Byte-level check that every source came back. "the script says
// restored" is not evidence — Lesson from R1113, where two mutation
// scripts each restored from their own snapshot and the second one
// wrote the first one's mutant back to disk.
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
