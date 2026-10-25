// Mutation harness for r377-capabilities.test.mjs — NOT part of npm test.
// Run:  node scripts/test/r377-mutate.mjs
//
// Every case here breaks ONE thing and the gate must go red. A case
// that leaves the gate green is a hole: the assertion it targets is
// not load-bearing.
//
// The cases come in two families:
//
//   * PRODUCT mutations — the TUI or the daemon is broken.
//
//   * GATE mutations — an assertion is neutered AND a defect that a
//     SECOND assertion can still see is introduced. Those are only
//     meaningful when the defect has two independent derivations, so
//     the gate still catches it with the neutered assertion out of the
//     way. A neutered assertion whose defect only IT could see proves
//     nothing (it proves the assertion was the only one) — those are
//     NOT here.
//
// THIS HARNESS REWRITES PRODUCT SOURCE IN PLACE. It must never run
// concurrently with the test suite (R373 Lesson 1166): a battery that
// edits `src/tui.tsx` while `npm test` reads it produces failures
// that belong to neither.

import { readFileSync, writeFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { recoverIfInterrupted, writeSnapshot } from "./mutation-snapshot.mjs";
// The gate must be GREEN before anything is mutated — otherwise every
// case reports "caught" for the wrong reason and the battery measures
// nothing. See scripts/test/mutation-baseline.mjs.
import { requireGreenBaseline } from "./mutation-baseline.mjs";
// The refusal table is addressed BY KEY, never by its prose: R377
// rewrote every reason and the text-based needles went NO-OP on the
// spot — reporting like a pass while testing nothing.
import { insertEntry } from "./caps-table.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const repo = join(root, "..");

const PATHS = {
  gate: join(here, "r377-capabilities.test.mjs"),
  caps: join(root, "src", "rpcCapabilities.ts"),
  effort: join(root, "src", "effort.ts"),
  tui: join(root, "src", "tui.tsx"),
  line: join(root, "src", "line.ts"),
  commands: join(root, "src", "commands.ts"),
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
const SNAPSHOT = join(root, "tmp-r377-mutate.snapshot.json");
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
  // the mutation broke the TypeScript compile, and the gate exited
  // non-zero. That is still RED, so it counts as caught.
  return { fail: m ? Number(m[1]) : -1, status: r.status, out };
}

// The gate must be green BEFORE the first mutation. A gate that is
// already red stays red under every mutation, so every case would
// report "caught" and the battery would measure nothing. (R377 Lesson
// 1194.)
requireGreenBaseline(runGate, "r377 mutation battery");

const CASES = [
  // --- the daemon stops serving the capability ------------------------------
  [
    "the daemon stops registering the effort method",
    [["methods", (s) => s.replace(/dispatcher\.register\("switchVariant",[^\n]*\n/, "")]],
  ],

  [
    "the HTTP transport stops routing the effort method",
    [["http", (s) => s.replace(/case "switchVariant"[^\n]*\n/, "")]],
  ],

  [
    "the handler answers to the name but never switches the variant",
    [
      [
        "methods",
        // SCOPED to the handler. `AetherCodeMethods` has more than one
        // `engine.setVariant(variantName)` call — `switchProvider`
        // makes one too — and a bare replace() hits the FIRST one, so
        // the mutation landed in a DIFFERENT method and the gate (which
        // looks at the handler) never saw it. A mutation that changes
        // the wrong occurrence is a no-op in disguise.
        (s) =>
          s.replace(
            /(public Object switchVariant\(Object params\)[\s\S]*?)engine\.setVariant\(variantName\);/,
            "$1// R377: switched nothing.",
          ),
      ],
    ],
  ],

  // --- the refusal table starts lying ---------------------------------------
  [
    "the refusal table claims a gap the daemon does not have",
    [
      [
        "caps",
        (s) =>
          s.replace(
            "export const BACKEND_MISSING",
            'export const BACKEND_MISSING = new Map<string, string>([["switchVariant", "pretend"]]);\nconst UNUSED_TABLE =',
          ),
      ],
    ],
  ],

  // R379: the table is EMPTY (the daemon serves every method the TUI
  // names), so `replaceInReason(s, "agent/setDefault", …)` and
  // `setReason(s, "agent/setDefault", …)` had nothing to rewrite and
  // went NO-OP. The needles are re-aimed at the only shape the table
  // can still take — an entry with a reason — and the method name is
  // one the daemon does NOT register, so the mutation isolates the
  // GROUNDING check rather than tripping the stale-allowlist check on
  // the way.
  [
    "a refusal reason quotes something the daemon does not have",
    [
      [
        "caps",
        (s) =>
          insertEntry(
            s,
            "session/rewindAll",
            "the daemon cannot roll a transcript back: `AgentRegistree` only appends",
          ),
      ],
    ],
  ],
  [
    "a refusal reason quotes nothing at all",
    [["caps", (s) => insertEntry(s, "session/rewindAll", "the daemon does not support that")]],
  ],

  // --- the TUI stops sending the daemon's contract --------------------------
  [
    "the TUI sends the wrong param name",
    [["commands", (s) => s.replace("rpcParams: { variant: effort }", "rpcParams: { effort }")]],
  ],

  [
    "the phantom name is back in code",
    [
      [
        "tui",
        (s) =>
          s.replace(
            "const r = (await client.request(EFFORT_METHOD, { variant })) as",
            'const r = (await client.request("effort/set", { variant })) as',
          ),
      ],
    ],
  ],

  // --- the refusal stops guarding a path ------------------------------------
  [
    "the Ctrl-Z path stops consulting the refusal table",
    [
      [
        "tui",
        (s) =>
          s.replace(/const rewindWhy = unsupportedReason\("rewind"\);[\s\S]*?\n      \}\n/, ""),
      ],
    ],
  ],

  // --- the renderers report the request instead of the answer ---------------
  [
    "the Ink panel reports the request instead of the daemon's answer",
    [
      [
        "tui",
        (s) =>
          s.replace(
            "r.activeVariant ? variantLine(r.activeVariant) : (r.variant ?? variant)",
            "r.variant ?? variant",
          ),
      ],
    ],
  ],

  [
    "line mode reports the request instead of the daemon's answer",
    [["line", (s) => s.replace("active ? variantLine(active) : picked", "picked")]],
  ],

  [
    "a renderer grows its own copy of the description helper",
    [
      [
        "line",
        (s) =>
          s.replace(
            "async function pickEffort(",
            "function variantLine() { return ''; }\nasync function pickEffort(",
          ),
      ],
    ],
  ],

  // --- the catalog drifts from the daemon's presets -------------------------
  [
    "the TUI's catalog drifts from the daemon's presets",
    [
      [
        "effort",
        // SCOPED to the catalog. A bare replace of `"xhigh"` hits the
        // first occurrence in the FILE — which is the doc comment —
        // and the catalog keeps its entry. The gate (which reads the
        // compiled EFFORT_LEVELS) never sees a change.
        (s) => s.replace(/(EFFORT_LEVELS\s*=\s*\[[^\]]*?)"xhigh"/, '$1"x-high"'),
      ],
    ],
  ],

  // --- GATE mutations: a defect with a SECOND derivation --------------------
  //
  // The neutering patterns below must be as LAYOUT-INDEPENDENT as the
  // assertions they target — R376's rule, learned the hard way. The
  // first version of the first one expected the assertion to end with
  // `",\n  );`, and prettier collapsed the call onto ONE line, so the
  // needle stopped matching: the case went NO-OP and the battery
  // reported a hole it could not explain. `[^;]*?` cannot cross a
  // statement boundary and the trailing comma is optional, so the
  // pattern survives both the exploded and the collapsed shape.
  [
    "GATE: neuter the TUI-payload check + the daemon renames the param",
    [
      [
        "gate",
        (s) =>
          s.replace(
            /assert\.match\(\s*\w*commandsSrc,[^;]*?"\/effort must send `\{ variant \}`"\s*,?\s*\);/,
            "",
          ),
      ],
      [
        "methods",
        // SCOPED to the handler — `switchProvider` reads a `variant`
        // param too, and renaming THAT one leaves the gate's contract
        // untouched.
        (s) =>
          s.replace(
            /(public Object switchVariant\(Object params\)[\s\S]*?)p\.get\("variant"\)/,
            '$1p.get("effort")',
          ),
      ],
    ],
  ],

  [
    "GATE: neuter the daemon-param check + the TUI sends the wrong param",
    [
      [
        "gate",
        (s) => s.replace(/assert\.match\(\s*\w+,\s*\/p\\\.get\\\("variant"\\\)\/[^;]*?\);/, ""),
      ],
      ["commands", (s) => s.replace("rpcParams: { variant: effort }", "rpcParams: { effort }")],
    ],
  ],
];

console.log(`r377 mutation battery — ${CASES.length} cases\n`);

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
// R377: this battery had NO exit code at all — it printed
// "1 MUTATION(S) SURVIVED" and still exited 0, so a hole was invisible
// to anything that checks the exit status. A battery that cannot fail
// cannot report.
// The other twelve batteries all end with the same line.
process.exit(holes === 0 ? 0 : 1);
