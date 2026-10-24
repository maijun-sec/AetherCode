// Mutation harness for r363-cancel.test.mjs — NOT part of npm test.
// Run:  node scripts/test/r363-mutate.mjs
//
// A green gate proves nothing until you have watched it go red. Each case
// below breaks exactly ONE dimension of R363 and asserts the gate catches it.
// A mutation that survives is a hole in the gate, not a pass.

import { readFileSync, writeFileSync, copyFileSync, existsSync } from "node:fs";
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
const target = join(root, "src", "tui.tsx");
const state = join(root, "src", "state.ts");
const line = join(root, "src", "line.ts");
const lineInput = join(root, "src", "lineInput.ts");
const bak = join(root, "tmp-r363-cancel.bak");

// R375: a battery that is KILLED mid-case must not leave a mutation on
// disk — the next test run would then read a deliberately broken product
// and the failures would belong to neither round. See
// scripts/test/mutation-snapshot.mjs.
const SNAPSHOT = join(root, "tmp-r363-mutate.snapshot.json");
recoverIfInterrupted(SNAPSHOT);
const original = {
  [target]: readFileSync(target, "utf-8"),
  [state]: readFileSync(state, "utf-8"),
  [line]: readFileSync(line, "utf-8"),
  [lineInput]: readFileSync(lineInput, "utf-8"),
};
const snapshot = writeSnapshot(Object.entries(original), SNAPSHOT);

function runGate() {
  const r = spawnSync(process.execPath, ["--test", join(here, "r363-cancel.test.mjs")], {
    encoding: "utf-8",
    cwd: root,
  });
  const out = (r.stdout ?? "") + (r.stderr ?? "");
  const m = out.match(/^ℹ fail (\d+)$/m);
  return { fail: m ? Number(m[1]) : -1, out };
}

/** [name, file, mutate, expectGateToCatch] */
// The gate must be green BEFORE the first mutation. A gate that is
// already red stays red under every mutation, so every case would
// report "caught" and the battery would measure nothing. (R377 Lesson
// 1194.)
requireGreenBaseline(runGate, "r363 mutation battery");

const CASES = [
  [
    "drop the query-response runId capture",
    target,
    (s) => s.replace("const runId = resp.runId ?? resp.busyRunId ?? null;", ""),
    true,
  ],
  [
    'revert to the pre-R363 request<unknown>("query")',
    target,
    (s) =>
      s.replace(
        /const resp\s*=\s*\(await client\.request<\{[\s\S]*?\}>\("query", \{ prompt: text \}\)\) \?\? \{\};/,
        'const resp = (await client.request<unknown>("query", { prompt: text })) as never;',
      ),
    true,
  ],
  [
    "move the Esc-cancel block BELOW the decision-card branches",
    target,
    (s) => {
      // Cut the cancel block and re-insert it AFTER both decision-card
      // branches. The anchor must be something definitively downstream —
      // anchoring on `setDecisionCardExpanded` inserts BEFORE the card
      // branches, which is the correct order and makes the mutation a
      // no-op that silently "passes".
      // R375 renamed `cancelActiveRun` to `stopActiveRun` (the status
      // bar's Stop button and the `/stop` command now share Esc's
      // implementation), so the anchor follows the rename. The
      // PROPERTY under test — the Esc block must sit above the
      // decision-card branches — is unchanged.
      const m = s.match(
        /\n    if \(key\.escape\) \{[\s\S]*?void stopActiveRun\(\);[\s\S]*?\n    \}/,
      );
      if (!m) throw new Error("cancel block not found — re-anchor this mutation");
      const cut = s.replace(m[0], "");
      // The help toggle sits below the whole permission / decision-card
      // region.
      //
      // R378: this used to anchor on `key.ctrl && input === "l"` — the
      // dead placeholder branch that R378 deleted (it shadowed the real
      // Ctrl-L, the log viewer). Same re-aim as the gate's own
      // downstream anchor: a dead branch used as a position anchor is a
      // time bomb, and the day the dead code is cleaned up, the needle
      // that leaned on it stops mutating anything.
      const anchor = cut.indexOf('if (key.ctrl && input === "?")');
      if (anchor < 0) throw new Error("downstream anchor not found");
      return cut.slice(0, anchor) + m[0] + "\n" + cut.slice(anchor);
    },
    true,
  ],
  [
    "remove the interrupt affordance from the input strip",
    target,
    (s) => s.replace(/esc to interrupt/g, "interrupt"),
    true,
  ],
  [
    "make requestCancelRun non-idempotent (remove the latch)",
    state,
    (s) =>
      s.replace(
        "if (state.cancelRequested) return state;\n      return { ...state, cancelRequested: true",
        "return { ...state, cancelRequested: true",
      ),
    true,
  ],
  [
    "stop clearing cancelRequested on submit (Esc dead next turn)",
    state,
    (s) =>
      s.replace(
        /\n        activeRunId: null,\n        cancelRequested: false,\n/,
        "\n        activeRunId: null,\n",
      ),
    true,
  ],
  [
    "stop clearing the run id + latch on streamEnd",
    state,
    (s) =>
      s.replace(
        /\n        activeRunId: null,\n        cancelRequested: false,\n        \/\/ drop the lookahead/,
        "\n        // drop the lookahead",
      ),
    true,
  ],
  [
    "fire onEscape for CSI sequences too (arrow keys cancel the run)",
    lineInput,
    (s) =>
      s.replace(
        /if \(code === 0x1[bB]\) \{[\s\S]*?if \(text\[i\] === "\["\) \{[\s\S]*?continue;\s*\n\s*\}/,
        "if (code === 0x1b) {\n        i++;",
      ),
    true,
  ],
  [
    "stop resetting the latch on line-mode run_end",
    line,
    (s) => s.replace(/\n    cancelSent = false;/, ""),
    true,
  ],
  [
    "unwire onEscape in line mode (parity claim becomes false)",
    line,
    (s) =>
      s.replace(
        /onEscape: \(\) => \{\n        if \(inFlight > 0\) void stopActiveRun\(\);\n      \},/,
        "onEscape: undefined,",
      ),
    true,
  ],
];

console.log(`r363 mutation battery — ${CASES.length} cases\n`);

let survived = 0;
for (const [name, file, mutate, expectCatch] of CASES) {
  const before = readFileSync(file, "utf-8");
  let after;
  try {
    after = mutate(before);
  } catch (e) {
    console.log(`  ??  ${name}\n      mutation failed to apply: ${e.message}`);
    survived++;
    continue;
  }
  if (after === before) {
    console.log(
      `  ??  ${name}\n      mutation was a NO-OP (pattern did not match) — the gate is not testing anything`,
    );
    survived++;
    continue;
  }
  writeFileSync(file, after, "utf-8");
  const { fail } = runGate();
  writeFileSync(file, before, "utf-8");
  const caught = fail > 0;
  const verdict = caught === expectCatch ? "ok  " : "HOLE";
  if (!caught) survived++;
  console.log(`  ${verdict}  ${name}  (gate fail=${fail})`);
}

// Leave every file byte-identical to how we found it.
for (const [f, content] of Object.entries(original)) {
  if (readFileSync(f, "utf-8") !== content) {
    copyFileSync(bak, f);
    writeFileSync(f, content, "utf-8");
    console.log(`  !! restored ${f}`);
  }
}
if (existsSync(bak)) writeFileSync(bak, "", "utf-8");

console.log(
  `\n${survived === 0 ? "ALL MUTATIONS CAUGHT" : `${survived} MUTATION(S) SURVIVED — holes in the gate`}`,
);
snapshot.clear();
process.exit(survived === 0 ? 0 : 1);
