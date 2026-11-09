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

// R388s — the MUTATION gate: the preview gate must be able to go RED.
//
// ★ A GATE THAT CANNOT GO RED IS NOT A GATE ★
//
// ★ AND THE RED MUST BE FOR THE RIGHT REASON ★ A gate that fails
// because the probe could not BUILD proves nothing about the
// assertion it was supposed to make — and it would fail on ANY
// change, including a correct one. So each mutation declares the
// assertion it is expected to break, and the gate checks that the
// output names THAT assertion.
//
// ★ THE MUTATION IS APPLIED TO A COPY, NEVER THE WORKING TREE ★
//
// The copy lives in the OS temp directory. (A junction into the
// workspace resolves back to the real tree, so the copy must be a
// real copy of the FILES.)
//
// ★ AND THE MUTATION MUST BE RE-READ ★
//
// A `replace` that matches nothing returns the original string, and a
// mutation that did not apply measures the UNMUTATED code — which
// passes, which looks like "the gate does not have teeth", which is a
// statement about the MUTATION and not about the gate.

import { strict as assert } from "node:assert";
import { test } from "node:test";
import { spawnSync } from "node:child_process";
import { cpSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(fileURLToPath(new URL("../..", import.meta.url)));
const GATE = join(ROOT, "scripts", "test", "r388s-checkpoint-preview.test.mjs");

/**
 * Make a pristine copy of the sources the gate reads, in a temp tree.
 *
 * <p>★ THE SANDBOX MUST MIRROR THE REAL LAYOUT ★ The gate computes
 * its ROOT as `../..` from its own file — `scripts/test/` → the TUI
 * root. So the copy must keep that shape, or the gate reads a
 * different tree than the one it was given.
 *
 * <p>★ AND THE SIBLING JAVA TREE — AT THE PATH THE GATE LOOKS AT ★
 * The gate computes its STORE path as `join(ROOT, "..", "aethercode",
 * "aethercode-core", ...)` — one level ABOVE the TUI root. In the
 * sandbox, ROOT is the temp directory, so `ROOT/..` is the TEMP
 * directory — NOT the workspace.
 *
 * <p>So the copy has to land at `dir/../aethercode/aethercode-core`,
 * which is a SIBLING of the sandbox root — and that is exactly what
 * the gate will read. (Copying it to `dir/aethercode/...` would put
 * it somewhere the gate never looks, and the assertion would die on
 * a missing file — red for the WRONG reason.)
 *
 * <p>★ AND THE SIBLING IS PER-SANDBOX ★ The sibling directory is
 * named after THIS sandbox's temp name, so two sandboxes never share
 * it and a leftover from a crashed run cannot become the next one's
 * input. (A shared `Temp/aethercode` would be one sandbox's leftover
 * poisoning the next.)
 *
 * @returns {string} the sandbox's root
 */
function makeSandbox() {
  const dir = mkdtempSync(join(tmpdir(), "r388s-mut-"));
  // The whole src/ tree — the menu imports ../theme.js, ../cellWidth.js
  // and ../checkpointPreview.js, so copying only the mutable files
  // leaves the component's own imports unresolvable, and the probe
  // then dies on a BUILD error — which is red for the WRONG reason.
  cpSync(join(ROOT, "src"), join(dir, "src"), { recursive: true });
  const sibling = join(dir, "..", "aethercode");
  mkdirSync(sibling, { recursive: true });
  cpSync(join(ROOT, "..", "aethercode", "aethercode-core"), join(sibling, "aethercode-core"), {
    recursive: true,
  });
  // The gate itself must run from the copy, at the same relative path.
  const testDir = join(dir, "scripts", "test");
  mkdirSync(testDir, { recursive: true });
  cpSync(GATE, join(testDir, "r388s-checkpoint-preview.test.mjs"));
  return dir;
}

/**
 * Remove the sandbox AND the sibling tree it created.
 *
 * @param {string} dir the sandbox
 */
function cleanup(dir) {
  rmSync(dir, { recursive: true, force: true });
  rmSync(join(dir, "..", "aethercode"), { recursive: true, force: true });
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
  // ★ nodePaths POINTS AT THE REAL node_modules ★ The sandbox has no
  // `node_modules` — a junction would resolve back to the real tree,
  // and the whole point of the sandbox is that the mutation is NOT in
  // the real tree. So the resolution path is borrowed, not copied.
  env.R388Q_NODE_PATHS = join(ROOT, "node_modules");
  // ★ AND SO DOES ESBUILD ITSELF ★ The gate's probe BUILDS the menu,
  // and the builder lives in the real tree's `node_modules`. Without
  // this the probe cannot build — and a gate that is red because it
  // could not build is red on ANY change, including a correct one.
  //
  // ★ THIS IS THE SAME DEFECT R388r FOUND ONE ROUND EARLIER ★ in a
  // different file: the sandbox has no `node_modules`, and the gate
  // hardcoded the sandbox's path — so every mutation went red on a
  // BUILD failure. A gate red on a build error is red on any change.
  env.R388Q_ESBUILD_DIR = join(ROOT, "node_modules", "esbuild");
  const run = spawnSync(
    process.execPath,
    ["--test", join(dir, "scripts", "test", "r388s-checkpoint-preview.test.mjs")],
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
 * <p>★ THE ANCHOR IS THE "MUST BE GONE" STRING ★ So the anchor IS the
 * check — and it must be UNIQUE in the file, or the mutation lands on
 * the wrong occurrence.
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
    "R388s: the anchor for " +
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
    "R388s: could not apply the mutation to " +
      parts.join("/") +
      " — the anchor moved. A mutation that does not apply measures the UNMUTATED code.",
  );
  writeFileSync(file, out, "utf8");
  // ★ RE-READ: the mutation must really be in the file. ★
  const after = readFileSync(file, "utf8");
  assert.ok(
    !after.includes(anchor),
    "R388s: the mutation to " + parts.join("/") + " left the original text behind.",
  );
  if (replace) {
    assert.ok(
      after.includes(replace),
      "R388s: the mutation to " +
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

test("R388s: the gate is GREEN against the pristine sources", () => {
  const dir = makeSandbox();
  try {
    const { red, output } = runGate(dir);
    assert.ok(
      !red,
      "R388s: the gate is RED against the pristine sources — a mutation test needs a green baseline. Output:\n" +
        output.slice(-4000),
    );
  } finally {
    cleanup(dir);
  }
});

// ═══════════════════════════════════════════════════════════════════
// EVERY WAY THE PREVIEW CAN LIE MUST BE CAUGHT
// ═══════════════════════════════════════════════════════════════════

/**
 * Each mutation BREAKS one way the preview can lie, and the gate must
 * go red.
 *
 * @type {{name: string, apply: (dir: string) => void, expect: RegExp}[]}
 */
const MUTATIONS = [
  {
    name: "the footprint is dropped from the row (the preview is invisible again)",
    apply: (dir) =>
      mutate(
        dir,
        ["src", "components", "CheckpointMenu.tsx"],
        "            const preview = previewSummaryLine(c.preview ?? null);",
        "            const preview = null as string | null;",
      ),
    expect: /EVERY checkpoint shows its own footprint|does not show point/,
  },
  {
    name: "the ABSENT preview becomes a ZERO (a number the daemon never sent)",
    apply: (dir) =>
      mutate(
        dir,
        ["src", "checkpointPreview.ts"],
        "  if (!preview) return null;",
        '  if (!preview) return "0 files";',
      ),
    expect: /shows a zero|NO preview says so/,
  },
  {
    name: "the UNKNOWN case becomes 'unchanged' (a claim the daemon cannot make)",
    apply: (dir) =>
      mutate(dir, ["src", "checkpointPreview.ts"], "  if (!file.known) {", "  if (false) {"),
    expect: /UNKNOWN|never 'unchanged'/,
  },
  {
    name: "the 'no change' case hides the incompleteness",
    apply: (dir) =>
      mutate(
        dir,
        ["src", "checkpointPreview.ts"],
        '    return preview.complete ? "no change" : "no change (some files unknown)";',
        '    return "no change";',
      ),
    expect: /INCOMPLETE preview says so|some files unknown/,
  },
  {
    name: "the daemon's line-counting rule loses its trailing-newline adjustment",
    apply: (dir) =>
      mutate(
        dir,
        [
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
        ],
        'return normalised.split("\\n", -1).length - (normalised.endsWith("\\n") ? 1 : 0);',
        'return normalised.split("\\n", -1).length;',
      ),
    expect: /DAEMON count lines the SAME way|trailing-newline adjustment/,
  },
  {
    name: "the TUI's line-counting rule loses its trailing-newline adjustment",
    apply: (dir) => mutate(dir, ["src", "fileChanges.ts"], 's.endsWith("\\n") ? 1 : 0', "0"),
    expect: /DAEMON count lines the SAME way|trailing-newline adjustment/,
  },
  {
    name: "the CREATED file is reported as an ordinary change (not a delete)",
    apply: (dir) =>
      mutate(dir, ["src", "checkpointPreview.ts"], "  if (file.created) {", "  if (false) {"),
    expect: /CREATED file says it would be DELETED|would be deleted/,
  },
];

for (const m of MUTATIONS) {
  test("R388s: the gate goes RED when " + m.name, () => {
    const dir = makeSandbox();
    try {
      m.apply(dir);
      const { red, output } = runGate(dir);
      assert.ok(
        red,
        "R388s: the gate stayed GREEN after the mutation — " +
          m.name +
          ". The gate has no teeth.\n" +
          output.slice(-4000),
      );
      // ★ AND FOR THE RIGHT REASON ★ A gate that fails because the
      // probe could not BUILD proves nothing about the assertion it
      // was supposed to make.
      assert.match(
        output,
        m.expect,
        "R388s: the gate went RED for the WRONG reason after: " +
          m.name +
          ". It must be the assertion that this mutation breaks. Output:\n" +
          output.slice(-4000),
      );
    } finally {
      cleanup(dir);
    }
  });
}

// ═══════════════════════════════════════════════════════════════════
// THE GATE MUST NOT BE RED FOR THE WRONG REASON
// ═══════════════════════════════════════════════════════════════════

test("R388s: the gate is red for the RIGHT reason — the assertion, not a crash", () => {
  // ★ A RED GATE IS NOT ENOUGH ★ A gate that fails because the probe
  // crashed (the component did not compile) proves nothing about the
  // assertion it was supposed to make. So the output must name the
  // assertion that the mutation broke — and must NOT mention a build
  // failure.
  const dir = makeSandbox();
  try {
    mutate(
      dir,
      ["src", "checkpointPreview.ts"],
      "  if (!preview) return null;",
      '  if (!preview) return "0 files";',
    );
    const { red, output } = runGate(dir);
    assert.ok(red, "R388s: the mutation did not make the gate red at all.");
    assert.match(
      output,
      /shows a zero|NO preview says so/,
      "R388s: the gate went red for the WRONG reason — it must be the zero-assertion that failed, not a crash. Output:\n" +
        output.slice(-4000),
    );
    assert.ok(
      !/could not build the menu probe|Could not resolve/.test(output),
      "R388s: the gate went red on a BUILD failure — that is not an assertion failure, and a gate that is red on a build error would be red on ANY change. Output:\n" +
        output.slice(-4000),
    );
  } finally {
    cleanup(dir);
  }
});
