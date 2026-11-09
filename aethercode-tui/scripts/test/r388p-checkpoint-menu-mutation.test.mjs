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

// R388p — proof that the checkpoint-menu gate can go RED.
//
// ★ WHY THIS FILE EXISTS ★
//
// A gate that has never been seen to fail is not evidence. Every
// mutation below turns one of the round's fixes back off, and the
// gate MUST catch it. If a mutation survives, the gate is blind to
// that defect — and a green gate then means nothing.
//
// ★ THE FOUR RULES THIS FILE FOLLOWS ★ (Lesson 1380, R388o)
//
//   1. ★ READ THE FILE BACK AFTER THE MUTATION ★ The assertion is
//      "the target text is GONE", not "replace() returned a string".
//      A replacement that matched nothing changes nothing, and the
//      mutation then measures the UNMUTATED code — and reports "the
//      fix does not matter", which is a lie.
//
//   2. ★ ANCHOR ON THE CODE LINE, NOT ON A WORD ★ The word
//      `hiddenBefore` appears in a comment too. The anchors here are
//      whole LINES, matched with a whitespace-tolerant regex.
//
//   3. ★ ANCHORS MUST BE UNIQUE ★ An anchor that matches two places
//      mutates one of them and measures the other.
//
//   4. ★ EVERY VARIANT STARTS FROM THE RESTORED BASELINE ★ A
//      mutation that leaks into the next variant makes the next
//      variant measure the wrong thing.
//
// ★ THE COPY GOES TO THE OS TEMP DIR ★ A junction resolves back to
// the original tree, so a copy under `node_modules/` would still read
// the ORIGINAL sources (Lesson 1373). The temp dir is outside the
// repo, and only `node_modules` is junctioned.

import { strict as assert } from "node:assert";
import { test } from "node:test";
import { spawnSync } from "node:child_process";
import {
  cpSync,
  existsSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  symlinkSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const GATE = join(ROOT, "scripts", "test", "r388p-checkpoint-menu.test.mjs");
const MENU = join("src", "components", "CheckpointMenu.tsx");

/**
 * One mutation: a regex to replace, and what it is replaced with.
 *
 * @typedef {{name: string, file: string, from: RegExp, to: string}} Mutation
 */

/** @type {Mutation[]} */
const MUTATIONS = [
  {
    name: "the list stops being windowed (every checkpoint is rendered)",
    file: MENU,
    from: /const visible = checkpoints\.slice\(win\.start, win\.end \+ 1\);/,
    to: "const visible = checkpoints;",
  },
  {
    name: "the selection is allowed to leave the window",
    file: MENU,
    from: /const win = listWindow\(checkpoints\.length, checkpointIdx, maxRows\);/,
    to: "const win = listWindow(checkpoints.length, checkpointIdx, checkpoints.length);",
  },
  {
    name: "the DOWN arrow goes back to clamping (a dead key at the end)",
    file: MENU,
    from: /setCheckpointIdx\(\(i\) => moveSelection\(checkpoints\.length, i, 1\)\);/,
    to: "setCheckpointIdx((i) => Math.min(checkpoints.length - 1, i + 1));",
  },
  {
    name: "the UP arrow goes back to clamping",
    file: MENU,
    from: /setCheckpointIdx\(\(i\) => moveSelection\(checkpoints\.length, i, -1\)\);/,
    to: "setCheckpointIdx((i) => Math.max(0, i - 1));",
  },
  {
    name: "the menu stops saying how many points are hidden",
    file: MENU,
    from: /\{win\.hiddenBefore > 0 \? \(/,
    to: "{false ? (",
  },
  {
    name: "the two summarize entries read identically again",
    file: MENU,
    from: /blurb: "summarize up to AND INCLUDING this point, keep what follows",/,
    to: 'blurb: "summarize everything BEFORE this point, keep what follows",',
  },
  {
    name: "the store's bound is removed",
    file: join(
      "..",
      "aethercode",
      "aethercode-core",
      "src",
      "main",
      "java",
      "org",
      "aethercode",
      "core",
      "transcript",
      "CodeCheckpointStore.java",
    ),
    from: /public static final int MAX_CHECKPOINTS = \d+;/,
    to: "public static final int MAX_CHECKPOINTS = Integer.MAX_VALUE;",
  },
];

/**
 * Copy the package into a temp dir, with `node_modules` junctioned.
 *
 * <p>★ THE JAVA MODULE IS COPIED TOO ★ One of the mutations undoes the
 * daemon's store bound, and that file lives in `../aethercode/` — a
 * different package. Without copying it, the mutation could not be
 * applied at all, and the test failed with "no file at ..." — which
 * looks like a broken mutation rather than a missing copy.
 *
 * @returns {string} the copy's root
 */
function copyTree() {
  const dir = mkdtempSync(join(tmpdir(), "r388p-mut-"));
  cpSync(join(ROOT, "src"), join(dir, "src"), { recursive: true });
  cpSync(join(ROOT, "scripts"), join(dir, "scripts"), { recursive: true });
  cpSync(join(ROOT, "package.json"), join(dir, "package.json"));
  cpSync(join(ROOT, "tsconfig.json"), join(dir, "tsconfig.json"));
  symlinkSync(join(ROOT, "node_modules"), join(dir, "node_modules"), "junction");
  // The daemon's sources, so a mutation there can be applied AND seen
  // by the gate (which reads them from `ROOT/../aethercode`).
  const repo = join(ROOT, "..");
  const javaSrc = join(
    repo,
    "aethercode",
    "aethercode-core",
    "src",
    "main",
    "java",
    "org",
    "aethercode",
    "core",
    "transcript",
  );
  cpSync(
    javaSrc,
    join(
      dir,
      "..",
      "aethercode",
      "aethercode-core",
      "src",
      "main",
      "java",
      "org",
      "aethercode",
      "core",
      "transcript",
    ),
    {
      recursive: true,
    },
  );
  const javaSdk = join(
    repo,
    "aethercode",
    "aethercode-sdk",
    "src",
    "main",
    "java",
    "org",
    "aethercode",
    "sdk",
  );
  cpSync(
    javaSdk,
    join(
      dir,
      "..",
      "aethercode",
      "aethercode-sdk",
      "src",
      "main",
      "java",
      "org",
      "aethercode",
      "sdk",
    ),
    {
      recursive: true,
    },
  );
  return dir;
}

/**
 * Apply a mutation to a file and READ IT BACK.
 *
 * @param {string} file the file to mutate
 * @param {RegExp} from the pattern to replace
 * @param {string} to the replacement
 */
function mutate(file, from, to) {
  const before = readFileSync(file, "utf8");
  const hits = before.match(
    new RegExp(from.source, from.flags.includes("g") ? from.flags : from.flags + "g"),
  );
  assert.equal(
    hits?.length ?? 0,
    1,
    `r388p mutation: the anchor matched ${hits?.length ?? 0} times — an anchor that matches twice mutates one place and measures another.`,
  );
  const after = before.replace(from, to);
  assert.notEqual(after, before, "r388p mutation: the replacement changed nothing.");
  writeFileSync(file, after, "utf8");
  // ★ READ IT BACK ★ The assertion is that the old text is GONE.
  const reread = readFileSync(file, "utf8");
  assert.ok(
    !new RegExp(from.source, from.flags).test(reread),
    `r388p mutation: the target text is STILL THERE after the replacement — the mutation did not apply, so this run measured the UNMUTATED code.`,
  );
}

for (const m of MUTATIONS) {
  test(`R388p mutation: ${m.name} is caught`, () => {
    const dir = copyTree();
    try {
      const target = join(dir, m.file);
      assert.ok(existsSync(target), `r388p mutation: no file at ${m.file}`);
      mutate(target, m.from, m.to);

      // Run the gate against the MUTATED tree. The gate resolves its
      // own ROOT from its own location, so it reads the copy.
      const run = spawnSync(
        process.execPath,
        ["--test", join(dir, "scripts", "test", "r388p-checkpoint-menu.test.mjs")],
        {
          encoding: "utf8",
          timeout: 300_000,
          cwd: dir,
          env: {
            ...process.env,
            // ★ THE MUTATION GATE'S OWN ENV VAR MUST GO ★ Otherwise the
            // child process thinks it is already inside a node test
            // run and exits 0 without running anything (Lesson 1358).
            NODE_TEST_CONTEXT: undefined,
          },
        },
      );
      const out = (run.stdout ?? "") + (run.stderr ?? "");
      const failed = /✖|not ok|fail [1-9]/.test(out);
      assert.ok(
        failed,
        `r388p mutation: the gate stayed GREEN after "${m.name}" was undone. The gate does not cover this defect.\n${out.slice(-2500)}`,
      );
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });
}
