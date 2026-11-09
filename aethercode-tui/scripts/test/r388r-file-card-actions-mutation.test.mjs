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

// R388r — the MUTATION gate: the file-card-actions gate must be able
// to go RED.
//
// ★ A GATE THAT CANNOT GO RED IS NOT A GATE ★
//
// Every round in this repo has had the same failure: a gate that
// asserts something the code does not actually do, so it is green
// while the product is broken. The only way to know a gate has teeth
// is to BREAK the product on purpose and watch the gate catch it.
//
// ★ THE MUTATION IS APPLIED TO A COPY, NEVER THE WORKING TREE ★
//
// The copy lives in the OS temp directory. (A junction into the
// workspace resolves back to the real tree, so the copy must be a real
// copy of the FILES.)
//
// ★ AND THE MUTATION MUST BE RE-READ ★
//
// A `replace` that matches nothing returns the original string, and a
// mutation that did not apply measures the UNMUTATED code — which
// passes, which looks like "the gate does not have teeth", which is a
// statement about the MUTATION and not about the gate. So every
// mutation is read back and asserted to have taken BEFORE the gate is
// run against it.
//
// ★ AND EVERY VARIANT STARTS FROM THE BASELINE ★
//
// A mutation left in place leaks into the NEXT variant — and the next
// variant then measures a compound defect, so the report says "the
// gate missed this" when it actually measured something else.

import { strict as assert } from "node:assert";
import { test } from "node:test";
import { spawnSync } from "node:child_process";
import { cpSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(fileURLToPath(new URL("../..", import.meta.url)));
const GATE = join(ROOT, "scripts", "test", "r388r-file-card-actions.test.mjs");
const FIXTURE = join(ROOT, "scripts", "test", "fixtures", "r388r-routing-probe.tsx");
const GROUPING_FIXTURE = join(ROOT, "scripts", "test", "fixtures", "r388r-grouping-probe.ts");

/**
 * Make a pristine copy of the sources the gate reads, in a temp tree.
 *
 * <p>★ THE SANDBOX MUST MIRROR THE REAL LAYOUT ★ The gate computes
 * its ROOT as `../..` from its own file — `scripts/test/` → the TUI
 * root. So the copy must keep that shape, or the gate reads a
 * different tree than the one it was given.
 *
 * @returns {string} the sandbox's root
 */
function makeSandbox() {
  const dir = mkdtempSync(join(tmpdir(), "r388r-mut-"));
  // The whole src/ tree — the card imports ../theme.js and
  // ../cellWidth.js, so copying only the mutable files leaves the
  // component's own imports unresolvable, and the probe then dies on
  // a BUILD error — which is red for the WRONG reason.
  cpSync(join(ROOT, "src"), join(dir, "src"), { recursive: true });
  const testDir = join(dir, "scripts", "test");
  mkdirSync(join(testDir, "fixtures"), { recursive: true });
  cpSync(GATE, join(testDir, "r388r-file-card-actions.test.mjs"));
  cpSync(FIXTURE, join(testDir, "fixtures", "r388r-routing-probe.tsx"));
  cpSync(GROUPING_FIXTURE, join(testDir, "fixtures", "r388r-grouping-probe.ts"));
  return dir;
}

/**
 * Run the gate against a sandbox and report whether it went red.
 *
 * @param {string} dir the sandbox
 * @returns {{red: boolean, output: string}}
 */
function runGate(dir) {
  // ★ DELETE NODE_TEST_CONTEXT ★ The runner sets it so a nested
  // `node --test` inherits the parent's context and refuses to run.
  const env = { ...process.env };
  delete env.NODE_TEST_CONTEXT;
  // The sandbox has no node_modules — a junction would resolve back
  // to the real tree, and the whole point of the sandbox is that the
  // mutation is NOT in the real tree. So the resolution path is
  // borrowed, not copied, and the caller hands it over.
  env.R388Q_ESBUILD_DIR = join(ROOT, "node_modules", "esbuild");
  env.R388Q_NODE_PATHS = join(ROOT, "node_modules");
  const run = spawnSync(
    process.execPath,
    ["--test", join(dir, "scripts", "test", "r388r-file-card-actions.test.mjs")],
    {
      cwd: dir,
      encoding: "utf8",
      timeout: 300_000,
      env,
    },
  );
  const output = (run.stdout ?? "") + "\n" + (run.stderr ?? "");
  return { red: run.status !== 0, output };
}

/**
 * Apply a mutation to a sandbox file and PROVE it took.
 *
 * <p>★ THE ANCHOR IS THE "MUST BE GONE" STRING ★ A separate
 * "mustBeGone" string was tried and it was wrong twice over: it was a
 * LOOSER substring that also occurs in the file's own comments (so the
 * check failed on text the mutation never touched), and for a DELETING
 * mutation it was the text being deleted.
 *
 * <p>So the anchor IS the check — and it must be UNIQUE in the file, or
 * the mutation lands on the wrong occurrence.
 *
 * @param {string} dir the sandbox
 * @param {string[]} parts the file's path parts
 * @param {string} anchor the exact text to replace (unique in the file)
 * @param {string} replace the replacement
 */
function mutate(dir, parts, anchor, replace) {
  const file = join(dir, ...parts);
  const src = readFileSync(file, "utf8");
  const hits = src.split(anchor).length - 1;
  assert.equal(
    hits,
    1,
    "R388r: the anchor for " +
      parts.join("/") +
      " occurs " +
      hits +
      " times, not once — a mutation that lands on the wrong occurrence proves nothing. Anchor: " +
      JSON.stringify(anchor),
  );
  const out = src.replace(anchor, replace);
  assert.notEqual(
    out,
    src,
    "R388r: could not apply the mutation to " +
      parts.join("/") +
      " — the anchor moved. A mutation that does not apply measures the UNMUTATED code.",
  );
  writeFileSync(file, out, "utf8");
  // ★ RE-READ: the mutation must really be in the file. ★
  const after = readFileSync(file, "utf8");
  assert.ok(
    !after.includes(anchor),
    "R388r: the mutation to " + parts.join("/") + " left the original text behind.",
  );
  if (replace) {
    assert.ok(
      after.includes(replace),
      "R388r: the mutation to " +
        parts.join("/") +
        " did not take (expected " +
        JSON.stringify(replace) +
        " in the result).",
    );
  }
}

// ═══════════════════════════════════════════════════════════════════
// THE BASELINE MUST BE GREEN
// ═══════════════════════════════════════════════════════════════════

test("R388r: the gate is GREEN against the pristine sources", () => {
  const dir = makeSandbox();
  try {
    const { red, output } = runGate(dir);
    assert.ok(
      !red,
      "R388r: the gate is RED against the pristine sources — a mutation test needs a green baseline. Output:\n" +
        output.slice(-4000),
    );
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

// ═══════════════════════════════════════════════════════════════════
// EVERY DEAD KEY MUST BE CAUGHT
// ═══════════════════════════════════════════════════════════════════

/**
 * Each mutation BREAKS one dead-key defect, and the gate must go red.
 *
 * <p>★ AND THE RED MUST BE FOR THE RIGHT REASON ★ A gate that fails
 * because the probe could not BUILD proves nothing about the
 * assertion it was supposed to make — and it would fail on ANY
 * change, including a correct one. So each mutation declares the
 * assertion it is expected to break, and the gate checks that the
 * output names THAT assertion.
 *
 * <p>★ THIS LIST WAS WRONG AT FIRST ★ The first version of this file
 * asserted only "the gate went red", and every mutation passed —
 * because the sandbox had no `node_modules`, so the probe died on a
 * BUILD error ("Could not resolve ink"). Every mutation "proved" the
 * gate had teeth, and the gate had none. The fix was in the gate
 * (`nodePaths` now points at the real tree), and the mutations below
 * were rewritten to target the real defects.
 *
 * @type {{name: string, apply: (dir: string) => void, expect: RegExp}[]}
 */
const MUTATIONS = [
  {
    name: "the card installs its own key listener again (the app must own the keys)",
    apply: (dir) =>
      mutate(
        dir,
        ["src", "components", "FileChangesCard.tsx"],
        "}) => {\n  // ★ THE CARD HANDLES NO KEYS ITSELF ★",
        '}) => {\n  // R388q regression: the card listens for itself again.\n  React.useEffect(() => {\n    process.stdin.on("keydown", () => {});\n    return () => process.stdin.off("keydown", () => {});\n  }, []);\n  // ★ THE CARD HANDLES NO KEYS ITSELF ★',
      ),
    expect: /does NOT listen for keys/,
  },
  {
    name: "the app stops resolving the card's actions (the keys go dead)",
    apply: (dir) =>
      mutate(
        dir,
        ["src", "tui.tsx"],
        "const effect = fileCardEffectFor(",
        "const effect = null as ReturnType<typeof fileCardEffectFor>; void (",
      ),
    expect: /APP binds the card's keys/,
  },
  {
    name: "the prompt guard is removed (a typed letter triggers the card)",
    apply: (dir) =>
      mutate(dir, ["src", "fileCardActions.ts"], "  if (!promptEmpty) return null;", ""),
    expect: /typed into the prompt is TEXT/,
  },
  {
    name: "the Scrollback stops passing the undo callback (the key does nothing)",
    apply: (dir) =>
      mutate(
        dir,
        ["src", "components", "Scrollback.tsx"],
        "onUndo={() => onUndoFileCard?.(group.turn.id)}",
        "onUndo={undefined}",
      ),
    expect: /Scrollback PASSES the callbacks down/,
  },
  {
    name: "the app passes an EMPTY STUB for the undo callback (the key does nothing)",
    apply: (dir) =>
      mutate(
        dir,
        ["src", "tui.tsx"],
        "onUndoFileCard={onUndoFileCard}",
        "onUndoFileCard={() => {}}",
      ),
    expect: /Scrollback PASSES the callbacks down/,
  },
  {
    name: "the undo action stops opening the rewind menu",
    apply: (dir) =>
      mutate(
        dir,
        ["src", "fileCardActions.ts"],
        '  if (action === "undo") return { kind: "openRewind" };',
        '  if (action === "undo") return { kind: "toggleFiles", turnId: turnId as number };',
      ),
    expect: /undo action no longer opens the rewind menu/,
  },
  {
    name: "the review action stops toggling the file list",
    apply: (dir) =>
      mutate(
        dir,
        ["src", "fileCardActions.ts"],
        '  return { kind: "toggleFiles", turnId };',
        '  return { kind: "openRewind" };',
      ),
    expect: /review action no longer toggles/,
  },
  {
    name: "the app's openRewind effect stops opening the rewind menu",
    apply: (dir) =>
      mutate(
        dir,
        ["src", "tui.tsx"],
        '    if (effect.kind === "openRewind") {\n      dispatch({ type: "openPanel", id: "checkpoints" });',
        '    if (effect.kind === "openRewind") {\n      dispatch({ type: "pushToast", kind: "info", text: "nope" });',
      ),
    expect: /no longer opens the rewind menu for the openRewind effect/,
  },
  {
    name: "the card keeps its OWN copy of the labels (the card and the app can drift)",
    apply: (dir) =>
      mutate(
        dir,
        ["src", "components", "FileChangesCard.tsx"],
        "const undo = FILE_CARD_ACTION_INFO.undo;",
        'const undo = { label: "撤销", key: "z", desc: "local" };',
      ),
    expect: /card renders the keys the app binds/,
  },
  {
    name: "the Scrollback keeps its own copy of the grouping rule",
    apply: (dir) =>
      mutate(
        dir,
        ["src", "components", "Scrollback.tsx"],
        "  return fileChangeOfTurnParts(turn.role, turn.toolName, turn.toolArgs);",
        "  return null;",
      ),
    // The card's OWN grouping is measured now, so the card's copy of
    // the rule is exercised by CALLING it — not by grepping for its
    // name.
    expect: /card's own grouping does not group the turns|Scrollback PASSES the callbacks down/,
  },
  {
    name: "the shared grouping rule stops breaking a group at a non-file turn",
    apply: (dir) =>
      mutate(
        dir,
        ["src", "fileChanges.ts"],
        "    if (!fileChangeOfTurnParts(prev.role, prev.toolName, prev.toolArgs)) break;",
        "    // mutated: the run never breaks",
      ),
    // The measured test asserts several things about the same rule, so
    // any of them naming the grouping is the right reason.
    expect:
      /does not group the turns the way the card does|does not target the most recent file-change group|do not share a group id/,
  },
];

for (const m of MUTATIONS) {
  test("R388r: the gate goes RED when " + m.name, () => {
    const dir = makeSandbox();
    try {
      m.apply(dir);
      const { red, output } = runGate(dir);
      assert.ok(
        red,
        "R388r: the gate stayed GREEN after the mutation — " +
          m.name +
          ". The gate has no teeth.\n" +
          output.slice(-4000),
      );
      // ★ AND FOR THE RIGHT REASON ★ A gate that fails because the
      // probe could not BUILD proves nothing about the assertion it
      // was supposed to make. So the output must name the assertion
      // this mutation broke.
      assert.match(
        output,
        m.expect,
        "R388r: the gate went RED for the WRONG reason after: " +
          m.name +
          ". It must be the assertion that this mutation breaks. Output:\n" +
          output.slice(-4000),
      );
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });
}

// ═══════════════════════════════════════════════════════════════════
// THE GATE MUST NOT BE RED FOR THE WRONG REASON
// ═══════════════════════════════════════════════════════════════════

test("R388r: the gate is red for the RIGHT reason — the assertion, not a crash", () => {
  // ★ A RED GATE IS NOT ENOUGH ★ A gate that fails because the probe
  // crashed (the component did not compile) proves nothing about the
  // assertion it was supposed to make. So the output must name the
  // assertion that the mutation broke.
  //
  // ★ AND THE BUILD MUST SUCCEED ★ This is the failure that made the
  // FIRST version of this file meaningless: the sandbox had no
  // `node_modules`, so the probe could not resolve `ink` and every
  // mutation "passed" on a BUILD error. So the output must NOT
  // mention a build failure.
  const dir = makeSandbox();
  try {
    mutate(
      dir,
      ["src", "fileCardActions.ts"],
      '  if (action === "undo") return { kind: "openRewind" };',
      '  if (action === "undo") return { kind: "toggleFiles", turnId: turnId as number };',
    );
    const { red, output } = runGate(dir);
    assert.ok(red, "R388r: the mutation did not make the gate red at all.");
    assert.match(
      output,
      /undo action no longer opens the rewind menu/,
      "R388r: the gate went red for the WRONG reason — it must be the effect assertion that failed, not a crash. Output:\n" +
        output.slice(-4000),
    );
    assert.ok(
      !/could not build the probe|Could not resolve/.test(output),
      "R388r: the gate went red on a BUILD failure — that is not an assertion failure, and a gate that is red on a build error would be red on ANY change. Output:\n" +
        output.slice(-4000),
    );
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
