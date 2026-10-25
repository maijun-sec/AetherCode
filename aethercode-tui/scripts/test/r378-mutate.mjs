// Mutation harness for r378-keybindings.test.mjs — NOT part of npm test.
// Run:  node scripts/test/r378-mutate.mjs
//
// Every case here breaks ONE thing and the gate must go red. A case
// that leaves the gate green is a hole: the assertion it targets is
// not load-bearing.
//
// The round this pins: the TUI carried THREE hand-written copies of
// the key-binding list (the Ctrl-? overlay, the welcome screen, the
// `--help` text) and they had already contradicted each other and the
// handler. The fix is ONE registry, with the gate tying it to the
// handler in both directions.
//
// Two families, as in every battery here:
//
//   * PRODUCT mutations — the registry or a consumer is broken.
//   * GATE mutations — an assertion is neutered AND a defect that a
//     SECOND assertion can still see is introduced.
//
// THIS HARNESS REWRITES PRODUCT SOURCE IN PLACE. It must never run
// concurrently with the test suite (R373 Lesson 1166).

import { readFileSync, writeFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { recoverIfInterrupted, writeSnapshot } from "./mutation-snapshot.mjs";
// The gate must be GREEN before anything is mutated — otherwise every
// case reports "caught" for the wrong reason and the battery measures
// nothing. See scripts/test/mutation-baseline.mjs.
import { requireGreenBaseline } from "./mutation-baseline.mjs";
import { mutateInCode } from "./code-needle.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

const PATHS = {
  gate: join(here, "r378-keybindings.test.mjs"),
  registry: join(root, "src", "keybindings.ts"),
  overlay: join(root, "src", "components", "HelpOverlay.tsx"),
  welcome: join(root, "src", "components", "Welcome.tsx"),
  acTui: join(root, "src", "ac-tui.ts"),
  tui: join(root, "src", "tui.tsx"),
};

const SNAPSHOT = join(root, "tmp-r378-mutate.snapshot.json");
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

// The gate must be green BEFORE the first mutation. A gate that is
// already red stays red under every mutation, so every case would
// report "caught" and the battery would measure nothing. (R377
// Lesson 1194.)
requireGreenBaseline(runGate, "r378 mutation battery");

const CASES = [
  // --- the handler grows a chord the help does not know ----------------
  [
    "the handler binds a chord the registry does not document",
    [
      [
        "tui",
        (s) =>
          s.replace(
            '    if (key.ctrl && input === "?") {',
            '    if (key.ctrl && (input === "q" || input === "Q")) {\n' +
              '      dispatch({ type: "log", message: "(q)" });\n' +
              "      return;\n" +
              "    }\n" +
              '    if (key.ctrl && input === "?") {',
          ),
      ],
    ],
  ],

  // --- the registry documents a chord nothing binds --------------------
  [
    "the registry documents a chord nothing binds",
    [
      [
        "registry",
        (s) =>
          s.replace(
            '  {\n    keys: ["ctrl+?"],\n    label: "Ctrl+?",',
            '  {\n    keys: ["ctrl+q"],\n    label: "Ctrl+Q",\n' +
              '    desc: "a chord that does not exist",\n    group: "session",\n  },\n' +
              '  {\n    keys: ["ctrl+?"],\n    label: "Ctrl+?",',
          ),
      ],
    ],
  ],

  [
    "the registry drops the Esc binding (the R363 contract)",
    [["registry", (s) => s.replace(/  \{\n    keys: \["esc"\],[\s\S]*?\n  \},\n/, "")]],
  ],

  // --- a development note reaches the user ------------------------------
  [
    "a development note leaks into the registry's own text",
    [
      [
        "registry",
        (s) => s.replace('desc: "toggle the sidebar",', 'desc: "R37: toggle side panel",'),
      ],
    ],
  ],

  [
    "a development note leaks into a consumer",
    [
      [
        "overlay",
        (s) =>
          s.replace(
            "const SLASH_GROUPS:",
            'const LEFTOVER = "R37: toggle side panel";\nconst SLASH_GROUPS:',
          ),
      ],
    ],
  ],

  // --- a second copy comes back -----------------------------------------
  [
    "the overlay stops importing the registry",
    [
      [
        "overlay",
        // Scoped to the one import: a loose `[\s\S]*?` would swallow
        // the Box/Text and theme imports on the way, and the case would
        // then be "caught" for the wrong reason (a broken component
        // instead of a missing registry).
        (s) => s.replace(/import \{[^}]*\} from "\.\.\/keybindings\.js";\n/, ""),
      ],
    ],
  ],

  [
    "the welcome screen stops importing the registry",
    [["welcome", (s) => s.replace(/import \{[^}]*\} from "\.\.\/keybindings\.js";\n/, "")]],
  ],

  [
    "a consumer grows a hand-written chord table again",
    [
      [
        "overlay",
        (s) =>
          s.replace(
            "const SESSION_SHORTCUTS:",
            'const OLD_TABLE = [{ key: "Ctrl-B", label: "toggle sidebar" }];\nconst SESSION_SHORTCUTS:',
          ),
      ],
    ],
  ],

  [
    "the overlay hand-writes its command list again",
    [
      [
        "overlay",
        (s) =>
          s.replace(
            "const SLASH_GROUPS:",
            'const LEGACY = [{ name: "/legacy", desc: "a command that does not exist" }];\nconst SLASH_GROUPS:',
          ),
      ],
    ],
  ],

  // --- a section silently stops rendering -------------------------------
  [
    "the overlay renders an empty display section",
    [["overlay", (s) => s.replace('bindingsIn("display")', "[] as never[]")]],
  ],

  [
    "the welcome renders an empty grid",
    [
      [
        "welcome",
        // R381: the needle is SCOPED to the grid's own call.
        //
        // It used to be a bare `welcomeBindings()`, which replaced the
        // FIRST occurrence in the file — and R381 added a second one
        // (the sub-agent panel's chord label, read from the same
        // registry). The mutation then landed on the label, the grid
        // still rendered, and the case reported a HOLE.
        //
        // That is Lesson 1195 one level down: a needle that is not
        // unique changes the wrong thing and pretends to have tested
        // something.
        (s) => s.replace("welcomeBindings().map((b) => ({", "([] as never[]).map((b) => ({"),
      ],
    ],
  ],

  // --- the help promises what this daemon cannot do --------------------
  [
    "the help starts apologising for keys the daemon serves",
    [
      [
        "registry",
        // R379 — RETARGETED, not deleted.
        //
        // The old needle deleted the `method: "rewind"` field, which
        // used to make Ctrl-Z drop out of the "unavailable" set —
        // because `rewind` was on the refusal table. R379 gave the
        // daemon that capability, so the table is EMPTY, so both the
        // original and the mutation mark nothing: the case had become
        // air, and the battery reported it as a hole.
        //
        // The property it protected is still real, and it is R377's
        // lesson in its worst form: a help that apologises for a
        // working key tells the user a working feature is dead. So
        // the needle now breaks the DERIVATION itself —
        // `bindingUnavailable` always true marks every chord that has
        // a method, and the gate's "marked === refused" comparison
        // goes red against an empty refusal table.
        (s) =>
          mutateInCode(
            s,
            /return\s+!!b\.method\s*&&\s*BACKEND_MISSING\.has\(b\.method\);/,
            "return true;",
            "keybindings.ts",
          ),
      ],
    ],
  ],

  // R378 DELETED: "GATE: the dev-note check goes blind".
  //
  // The "no development note reaches the user" property has exactly ONE
  // derivation — the DEV_NOTE regex — and neutering it takes the
  // registry check AND the consumer check with it. A neutered assertion
  // whose defect only IT could see proves nothing (it proves the
  // assertion was the only one), so this case is not honest here.
  //
  // (Its first version was worse: it read
  // `const DEV_NOTE = /(?!x)x/ || /…/`, and the `||` kept the ORIGINAL
  // regex — so the "mutation" changed nothing and the case passed for
  // the wrong reason. A needle that does not mutate is a no-op in
  // disguise.)

  // --- the gate's own anchor -------------------------------------------
  [
    "the app-level handler is no longer identifiable",
    [
      [
        "tui",
        // SCOPED to the app-level handler's Ctrl-C branch. `tui.tsx`
        // has TWO `dispatch({ type: "exit" })` calls — this one and the
        // `/exit` slash command — and a bare replace() hits the first,
        // which is not the one the gate anchors on. (R377 Lesson 1195:
        // a mutation that changes the wrong occurrence is a no-op in
        // disguise.)
        (s) =>
          s.replace(
            'if (key.ctrl && input === "c") {\n      dispatch({ type: "exit" });',
            'if (key.ctrl && input === "c") {\n      dispatch({ type: "quit" });',
          ),
      ],
    ],
  ],

  [
    "GATE: the chord extraction stops matching",
    [
      [
        "gate",
        // `key\\.ctr` would STILL match "key.ctrl" — the regex is not
        // anchored, and "key.ctr" is a PREFIX of "key.ctrl". The first
        // version of this needle therefore changed nothing and the
        // case passed for the wrong reason.
        (s) => s.replace("/key\\.ctrl/g", "/key\\.ctrX/g"),
      ],
    ],
  ],
];

console.log(`r378 mutation battery — ${CASES.length} cases\n`);
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

console.log(
  `\n${holes === 0 ? "ALL MUTATIONS CAUGHT" : `${holes} MUTATION(S) SURVIVED — holes in the gate`}`,
);
if (noops) console.log(`${noops} case(s) were no-ops and did not test anything`);
snapshot.clear();
process.exit(holes === 0 ? 0 : 1);
