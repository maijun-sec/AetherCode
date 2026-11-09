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

// R388q — the MUTATION gate: the file-changes gate must be able to go RED.
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
// passes, which looks like "the gate does not have teeth", which is
// a statement about the MUTATION and not about the gate. So every
// mutation is read back and asserted to have taken BEFORE the gate is
// run against it.
//
// ★ AND EVERY VARIANT STARTS FROM THE BASELINE ★
//
// A mutation left in place leaks into the NEXT variant — and the
// next variant then measures a compound defect, so the report says
// "the gate missed this" when it actually measured something else.
// Every variant restores the pristine files first.

import { strict as assert } from "node:assert";
import { test } from "node:test";
import { spawnSync } from "node:child_process";
import {
  cpSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(fileURLToPath(new URL("../..", import.meta.url)));
const GATE = join(ROOT, "scripts", "test", "r388q-file-changes.test.mjs");

/**
 * The files the gate reads.
 *
 * <p>★ THE MUTATION MUST COVER EVERY FILE THE GATE READS ★ The gate
 * reads the card, the diff module, the Scrollback, the Ink renderer
 * and the daemon's Java map. A mutation that only patches the card
 * proves nothing about the other four.
 *
 * <p>(The whole `src/` tree is copied into the sandbox — the card
 * imports `../theme.js` and `../cellWidth.js`, so copying only these
 * four files leaves the component's own imports unresolvable and the
 * probe dies on a BUILD error, which is red for the WRONG reason.)
 */
const MUTABLE = [
  ["src", "components", "FileChangesCard.tsx"],
  ["src", "fileChanges.ts"],
  ["src", "components", "Scrollback.tsx"],
  ["src", "tui.tsx"],
];

/**
 * Make a pristine copy of the sources the gate reads, in a temp tree.
 *
 * <p>★ THE SANDBOX MUST MIRROR THE REAL LAYOUT ★ The gate computes
 * its ROOT as `../..` from its own file — `scripts/test/` → the TUI
 * root. So the copy must keep that shape, or the gate reads a
 * different tree than the one it was given. (This bit once: the
 * sandbox was flat, so ROOT became the temp DIRECTORY's parent and
 * the gate read `C:\Users\maijun\AppData\Local\src\...`.)
 *
 * <p>★ AND THE SIBLING JAVA TREE MUST COME ALONG ★ The gate reads the
 * daemon's own `AetherCodeMethods.java` to pin the wire contract, and
 * it reaches it at `../aethercode/...` — one level ABOVE the TUI root.
 * So the sandbox has to carry that sibling too, or the contract
 * assertion dies on a missing file — and a gate that dies on a
 * missing file is red for the WRONG reason.
 *
 * <p>★ AND THE PROBE NEEDS esbuild ★ The gate bundles the component
 * with esbuild from the TUI's `node_modules`. A sandbox without it
 * cannot build the probe. A junction would resolve back to the real
 * tree (and the mutation would land on the real file), so the
 * dependency is resolved from the REAL tree at run time instead — the
 * gate's probe builder is pointed at the real `node_modules`.
 *
 * @returns {string} the sandbox's root
 */
function makeSandbox() {
  const dir = mkdtempSync(join(tmpdir(), "r388q-mut-"));
  // ★ THE WHOLE src/ TREE COMES ALONG ★ The card imports
  // `../theme.js` and `../cellWidth.js`, so copying only the four
  // mutable files leaves the component's own imports unresolvable —
  // and the probe then dies on a BUILD error, which is red for the
  // WRONG reason (a build failure, not an assertion failure).
  cpSync(join(ROOT, "src"), join(dir, "src"), { recursive: true });
  // The sibling Java tree — the daemon's wire contract.
  const javaSrc = join(ROOT, "..", "aethercode", "aethercode-protocol");
  const javaDst = join(dir, "..", "aethercode", "aethercode-protocol");
  mkdirSync(join(dir, "..", "aethercode"), { recursive: true });
  cpSync(javaSrc, javaDst, { recursive: true });
  // The gate itself must run from the copy, at the same relative path,
  // so its ROOT resolves to the sandbox.
  const testDir = join(dir, "scripts", "test");
  mkdirSync(testDir, { recursive: true });
  cpSync(GATE, join(testDir, "r388q-file-changes.test.mjs"));
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
  // ★ THE SANDBOX HAS NO node_modules ★ The gate's probe BUNDLES ink
  // and react (Ink needs ESM), so the bundle is self-contained and
  // runs anywhere. Only esbuild itself — the builder — is borrowed
  // from the real tree, and its location is passed in explicitly.
  env.R388Q_ESBUILD_DIR = join(ROOT, "node_modules", "esbuild");
  env.R388Q_NODE_PATHS = join(ROOT, "node_modules");
  const run = spawnSync(
    process.execPath,
    ["--test", join(dir, "scripts", "test", "r388q-file-changes.test.mjs")],
    {
      cwd: dir,
      encoding: "utf8",
      timeout: 300_000,
      env,
    },
  );
  const output = `${run.stdout ?? ""}\n${run.stderr ?? ""}`;
  const red = run.status !== 0;
  return { red, output };
}

/**
 * Apply a mutation to a sandbox file and PROVE it took.
 *
 * <p>★ THE ANCHOR IS THE "MUST BE GONE" STRING ★ A separate
 * "mustBeGone" string was tried and it was wrong twice over: it was a
 * LOOSER substring that also occurs in the file's own comments (so the
 * check failed on text the mutation never touched), and for a
 * DELETING mutation it was the text being deleted.
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
    `R388q: the anchor for ${parts.join("/")} occurs ${hits} times, not once — a mutation that lands on the wrong occurrence proves nothing. Anchor: ${JSON.stringify(anchor)}`,
  );
  const out = src.replace(anchor, replace);
  assert.notEqual(
    out,
    src,
    `R388q: could not apply the mutation to ${parts.join("/")} — the anchor moved. A mutation that does not apply measures the UNMUTATED code.`,
  );
  writeFileSync(file, out, "utf8");
  // ★ RE-READ: the mutation must really be in the file. ★
  const after = readFileSync(file, "utf8");
  assert.ok(
    !after.includes(anchor),
    `R388q: the mutation to ${parts.join("/")} left the original text behind.`,
  );
  if (replace) {
    assert.ok(
      after.includes(replace),
      `R388q: the mutation to ${parts.join("/")} did not take (expected ${JSON.stringify(replace)} in the result).`,
    );
  }
}

// ═══════════════════════════════════════════════════════════════════
// THE BASELINE MUST BE GREEN
// ═══════════════════════════════════════════════════════════════════

test("R388q: the gate is GREEN against the pristine sources", () => {
  const dir = makeSandbox();
  try {
    const { red, output } = runGate(dir);
    assert.ok(
      !red,
      `R388q: the gate is RED against the pristine sources — a mutation test needs a green baseline. Output:\n${output.slice(-4000)}`,
    );
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

// ═══════════════════════════════════════════════════════════════════
// EVERY DEFECT MUST BE CAUGHT
// ═══════════════════════════════════════════════════════════════════

/**
 * Each mutation BREAKS one defect, and the gate must go red.
 *
 * @type {{name: string, apply: (dir: string) => void}[]}
 */
const MUTATIONS = [
  {
    name: "the frame becomes SQUARE (the rounded-frame assertion must catch it)",
    apply: (dir) =>
      mutate(
        dir,
        ["src", "components", "FileChangesCard.tsx"],
        'borderStyle="round"\n      borderColor={focused',
        'borderStyle="single"\n      borderColor={focused',
      ),
  },
  {
    name: "the REMOVAL count is dropped (the counts assertion must catch it)",
    apply: (dir) =>
      mutate(
        dir,
        ["src", "components", "FileChangesCard.tsx"],
        "{removedText ? <Text color={t.err}>{removedText}</Text> : null}",
        "{null}",
      ),
  },
  {
    name: "the ADDITION count is dropped (the counts assertion must catch it)",
    apply: (dir) =>
      mutate(
        dir,
        ["src", "components", "FileChangesCard.tsx"],
        "{addedText ? <Text color={t.ok}>{addedText}</Text> : null}",
        "{null}",
      ),
  },
  {
    name: "the LINK is removed (the seven-parts assertion must catch it)",
    apply: (dir) =>
      mutate(
        dir,
        ["src", "components", "FileChangesCard.tsx"],
        "{showLink ? <Text dimColor> 查看文件 f </Text> : null}",
        "{null}",
      ),
  },
  {
    name: "the undo ACTION is removed (the seven-parts assertion must catch it)",
    apply: (dir) =>
      mutate(
        dir,
        ["src", "components", "FileChangesCard.tsx"],
        '<CardAction label="撤销" keyHint="u" active={focused} />',
        "{null}",
      ),
  },
  {
    name: "the review ACTION is removed (the seven-parts assertion must catch it)",
    apply: (dir) =>
      mutate(
        dir,
        ["src", "components", "FileChangesCard.tsx"],
        '<CardAction label="审查" keyHint="r" active={focused} />',
        "{null}",
      ),
  },
  {
    name: "the SEPARATOR is removed (the seven-parts assertion must catch it)",
    apply: (dir) =>
      mutate(
        dir,
        ["src", "components", "FileChangesCard.tsx"],
        "{showSeparator ? <Text dimColor> │ </Text> : null}",
        "{null}",
      ),
  },
  {
    name: "the TITLE is dropped (the seven-parts assertion must catch it)",
    apply: (dir) =>
      mutate(
        dir,
        ["src", "components", "FileChangesCard.tsx"],
        "<Text bold>{shownTitle}</Text>",
        "{null}",
      ),
  },
  {
    name: "the budget is measured in CHARACTERS again (the overflow assertion must catch it)",
    apply: (dir) =>
      mutate(
        dir,
        ["src", "components", "FileChangesCard.tsx"],
        "const titleW = cellWidth(title);",
        "const titleW = title.length;",
      ),
  },
  {
    name: "the renderer reads ev.args again (the field-name assertion must catch it)",
    apply: (dir) => mutate(dir, ["src", "tui.tsx"], "ev.input ?? ev.args", "ev.args"),
  },
  {
    name: "an EMPTY file counts as ONE line again (the countLines assertion must catch it)",
    apply: (dir) =>
      mutate(
        dir,
        ["src", "fileChanges.ts"],
        "if (s.length === 0) return 0;",
        "if (s.length === 0) return 1;",
      ),
  },
  {
    name: "the group is NOT broken by a non-file turn (the grouping assertion must catch it)",
    apply: (dir) =>
      mutate(
        dir,
        ["src", "components", "Scrollback.tsx"],
        "pending = null;\n    groups.push({ turn, changes: [], count: 1 });",
        "groups.push({ turn, changes: [], count: 1 });",
      ),
  },
];

for (const m of MUTATIONS) {
  test(`R388q: the gate goes RED when ${m.name}`, () => {
    const dir = makeSandbox();
    try {
      m.apply(dir);
      const { red, output } = runGate(dir);
      assert.ok(
        red,
        `R388q: the gate stayed GREEN after the mutation — ${m.name}. The gate has no teeth.\n${output.slice(-4000)}`,
      );
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });
}

// ═══════════════════════════════════════════════════════════════════
// THE GATE MUST NOT BE RED FOR THE WRONG REASON
// ═══════════════════════════════════════════════════════════════════

test("R388q: the gate is red for the RIGHT reason — the shape assertion, not a crash", () => {
  // ★ A RED GATE IS NOT ENOUGH ★ A gate that fails because the probe
  // crashed (the component did not compile) proves nothing about the
  // assertion it was supposed to make. So the output must mention the
  // assertion that the mutation broke.
  const dir = makeSandbox();
  try {
    mutate(
      dir,
      ["src", "components", "FileChangesCard.tsx"],
      'borderStyle="round"\n      borderColor={focused',
      'borderStyle="single"\n      borderColor={focused',
    );
    const { red, output } = runGate(dir);
    assert.ok(red, "R388q: the mutation did not make the gate red at all.");
    assert.match(
      output,
      /ROUNDED|rounded/,
      `R388q: the gate went red for the WRONG reason — it must be the rounded-frame assertion that failed, not a crash. Output:\n${output.slice(-4000)}`,
    );
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
