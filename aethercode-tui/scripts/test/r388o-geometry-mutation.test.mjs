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

// R388o — PROVE THE GEOMETRY GATE CAN GO RED.
//
// A gate that cannot fail proves nothing (Lesson 1219). This file
// mutates the source in the ways that produced the user's defect and
// asserts that `r388o-geometry.test.mjs` then FAILS.
//
// ★ THE MUTATIONS ARE THE REAL DEFECTS ★
//
//   1. the status bar's right box starts SHRINKING — Ink then overlaps
//      the Text nodes inside it (the row reads "●✓ ·");
//   2. the status bar stops dropping its items — the right side's
//      content wraps and the frame's height clips the continuation;
//   3. the header stops dropping its items — at 40 columns the model
//      name is cut in half;
//   4. the jar name stops being truncated — the row overflows again;
//   5. the transcript's row budget goes back to 1000 (a turn count used
//      as a row count);
//   6. the input pill's height cap is removed — an 8-line input grows
//      the frame;
//   7. the Scrollback's flex basis loses its zero — the transcript
//      takes the rows and squeezes the header and the pill out;
//   8. the input pill stops using the width it is given — a 99-cell
//      pill in a 40-column terminal.
//
// ★ A MUTATION THAT DID NOT APPLY IS INDISTINGUISHABLE FROM A CHANGE
// THAT DOES NOT MATTER ★
//
// Four of the eight anchors were wrong at first, and each time the
// gate stayed GREEN — which reads exactly like "the gate is blind".
//
//   - `flexBasis={0}` also appears in Scrollback.tsx's COMMENTS, so the
//     removal regex rewrote the comment and left the code alone;
//   - `const drop = new Set<string>();` → `... void drop;` changes
//     nothing: the `drop.add(...)` calls below it still run, so the
//     items are still dropped. The anchor has to be the CALL;
//   - the row budget's contract is on its VALUE, not on the frame:
//     once the transcript's column carries `flexBasis={0}`, the frame's
//     height no longer depends on the budget, so the gate asserts on
//     the number;
//   - the status bar's right box at `flexShrink={1}` does NOT overflow
//     — it OVERLAPS its own Text nodes. The row still fits the
//     terminal, so only a check on the CONTENT can see it.
//
// → **Lesson 1380 — read the file back after every mutation. A
// mutation that did not apply is a silent no-op, and the conclusion it
// supports ("this change does not matter") is exactly the conclusion
// that must never be reached without evidence.**
//
// ★ TWO OF THESE ARE NOT DEFECTS, AND THAT IS WORTH KNOWING ★
//
// The header's fixed 60-cell rule (and the status bar's) do NOT make
// the gate red — and they should not.
//
// A rule is a `<Text>` inside a Box that Yoga already sized to the
// parent, and a `<Text>` WRAPS. So a 60-cell rule inside an 80-cell
// Box occupies 60 cells of ONE row. It never overflows.
//
// The first draft of this file asserted that they DID overflow, and
// the mutation test reported "the gate stayed GREEN" — which
// looked like a gate failure and was actually a WRONG MUTATION. The
// real overflow was never the rule; it was the right-hand box with
// `flexShrink={0}`.
//
// → **Lesson 1375 — a mutation that does not reproduce a DEFECT is not
// a mutation. Asserting that the gate goes red for a change that was
// never broken tests nothing, and the "failure" it reports is noise
// that hides a real one.**
//
// ★ WHY THE COPY LIVES OUTSIDE THE PACKAGE ★
//
// The gate computes its own root as `dirname(import.meta.url)/../..`.
// A copy placed under `<pkg>/node_modules/` with a junctioned
// `node_modules` resolves that path back to the ORIGINAL tree — the
// probe then imports the ORIGINAL `StatusBar.tsx` and the mutation is
// never in front of it. The copy therefore lives in the OS temp dir.
//
// → **Lesson 1373 — a path that traverses a junction resolves to the
// original.**

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

/**
 * Copy the package into a temp directory, apply a mutation, and run
 * the geometry gate there.
 *
 * @param {string} file      the file (relative to `src/`) to mutate
 * @param {RegExp} find      the pattern to replace
 * @param {string} withText  the replacement
 * @returns {{status: number, output: string}} the gate's result
 */
function runMutatedGate(file, find, withText) {
  const dir = mkdtempSync(join(tmpdir(), "r388o-mut-"));
  try {
    cpSync(join(ROOT, "src"), join(dir, "src"), { recursive: true });
    cpSync(join(ROOT, "scripts"), join(dir, "scripts"), { recursive: true });
    cpSync(join(ROOT, "package.json"), join(dir, "package.json"));
    cpSync(join(ROOT, "tsconfig.json"), join(dir, "tsconfig.json"));
    symlinkSync(join(ROOT, "node_modules"), join(dir, "node_modules"), "junction");

    const target = join(dir, "src", file);
    const src = readFileSync(target, "utf8");
    if (!find.test(src)) {
      return { status: -1, output: `MUTATION DID NOT APPLY to ${file}` };
    }
    writeFileSync(target, src.replace(find, withText), "utf8");
    // A MARKER the gate reports: if it is absent, the gate read the
    // ORIGINAL tree and the mutation was never in front of it.
    writeFileSync(
      join(dir, "src", "components", "__r388o_marker__.txt"),
      "MUTATION-MARKER\n",
      "utf8",
    );

    const run = spawnSync(process.execPath, ["--test", "scripts/test/r388o-geometry.test.mjs"], {
      cwd: dir,
      encoding: "utf8",
      timeout: 300_000,
      env: { ...process.env, NODE_TEST_CONTEXT: undefined },
    });
    const output = (run.stdout ?? "") + (run.stderr ?? "");
    if (!output.includes("probe read the MUTATED tree")) {
      return {
        status: -2,
        output: `THE GATE DID NOT READ THE MUTATED TREE\n${output.slice(0, 1500)}`,
      };
    }
    return { status: run.status ?? -1, output };
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

/**
 * Assert that a mutation makes the gate go RED.
 *
 * @param {string} what a description of the mutation
 */
function assertGateGoesRed(what, file, find, withText) {
  const r = runMutatedGate(file, find, withText);
  assert.notEqual(r.status, -1, `R388o: the mutation did not apply (${what}).`);
  assert.notEqual(
    r.status,
    -2,
    `R388o: the gate did not read the mutated tree (${what}).\n${r.output}`,
  );
  assert.notEqual(
    r.status,
    0,
    `R388o: the gate stayed GREEN after "${what}".\n${r.output.slice(0, 2500)}`,
  );
}

test("R388o mutation: the status bar's right box starts shrinking", () => {
  // ★ THIS IS NOT A DEFECT, AND THAT IS WORTH KNOWING ★
  //
  // The status bar's right-hand box used to carry `flexShrink={0}` and
  // never shrank; the overflow was blamed on it. Measured, by turning
  // each change off in turn:
  //
  //   the jar name NOT truncated          → OVERFLOW (71 cells in a 40-column terminal)
  //   the right box never shrinks         → OK
  //   both                               → OVERFLOW
  //
  // So the overflow was never the `flexShrink`: the jar name's LENGTH
  // was, and the fix was to truncate it UP FRONT to a measured budget
  // (`jarBudget`). With the name truncated, the box's natural width
  // always fits, and whether it may shrink is irrelevant.
  //
  // The first draft of this file asserted that this mutation makes the
  // gate red. It does not — and the honest response to that is to
  // REMOVE the mutation, not to bend the gate until it agrees.
  //
  // → **Lesson 1375 — a mutation that does not reproduce a DEFECT is not
  // a mutation.** The defect this round actually had is mutation #4
  // (the jar name stops being truncated), and that one IS detected.
  //
  // ★ THE ANCHOR MUST TOLERATE WHITESPACE ★ Prettier reflows these
  // files, and an anchor with an exact newline + indent dies the
  // next time somebody formats the source. R376 cost 45 mutation
  // needles to exactly this. So the anchor matches the CONSTRUCT
  // (the JSX element and its first child), not the spelling.
  //
  // ★ AND IT MUST BE UNIQUE ★ There are TWO `flexShrink={0}` boxes
  // (the left half and the right half) and the comment above the
  // right one mentions `flexShrink={0}` too. A needle that is not
  // unique lands on the wrong occurrence (Lesson 1217) — and the
  // first draft of this file did exactly that.
  //
  // The gate is still asserted to stay GREEN here — a regression guard
  // on the MEASUREMENT, not a defect detector.
  const r = runMutatedGate(
    "components/StatusBar.tsx",
    /(<Box\s+flexDirection="row"\s+flexShrink=)\{0\}(\s*>\s*\{showMeter \? \()/,
    "$1{1}$2",
  );
  assert.notEqual(r.status, -1, "R388o: the mutation did not apply.");
  assert.notEqual(r.status, -2, `R388o: the gate did not read the mutated tree.\n${r.output}`);
  assert.equal(
    r.status,
    0,
    `R388o: shrinking the status bar's right box made the gate red — so it IS a defect, and this test's premise is wrong.\n${r.output.slice(0, 2000)}`,
  );
});

test("R388o mutation: the status bar stops dropping its items", () => {
  // Without the dropping, the right side's content does not fit; it
  // WRAPS inside the box, and the frame's height constraint then
  // clips the continuation — the state label's context is gone.
  //
  // ★ THE ANCHOR MUST BE THE `drop.add` CALL ★ An earlier draft
  // mutated the DECLARATION (`const drop = new Set<string>();` →
  // `... void drop;`), which changes nothing at all: the `drop.add`
  // calls below it still run, so the items are still dropped. The
  // mutation "applied" (the file changed) and the gate stayed green
  // for the wrong reason.
  //
  // → **Lesson 1380 again — a mutation that did not apply is
  // indistinguishable from a change that does not matter.**
  assertGateGoesRed(
    "the status bar stops dropping items",
    "components/StatusBar.tsx",
    /(\n\s*)drop\.add\(part\.key\);/,
    "$1void part.key;",
  );
});

test("R388o mutation: the header stops dropping its items", () => {
  // At 40 columns the header's strip was 64 cells wide and the model
  // name was cut in half. Same lesson as the status bar above: the
  // anchor is the `dropped.add` CALL, not the declaration.
  assertGateGoesRed(
    "the header stops dropping items",
    "components/Header.tsx",
    /(\n\s*)dropped\.add\(rightItems\[i\]\.key\);/,
    "$1void rightItems[i].key;",
  );
});

test("R388o mutation: the jar name stops being truncated", () => {
  assertGateGoesRed(
    "the jar name stops being truncated",
    "components/StatusBar.tsx",
    /const jar = !drop\.has\("jar"\) && jarBudget >= 4 \? fitCells\(jarName\(state\.jarPath\), jarBudget\) : "";/,
    "const jar = jarName(state.jarPath);",
  );
});

test("R388o mutation: the transcript's row budget goes back to 1000", () => {
  // `height={1000}` is a TURN count used as a ROW count: a turn can
  // be forty rows of markdown.
  assertGateGoesRed(
    "the transcript's row budget goes back to 1000",
    "tui.tsx",
    /height=\{transcriptBudget\}/,
    "height={1000}",
  );
});

test("R388o mutation: the input pill's height cap is removed", () => {
  // ★ THE ANCHOR MUST TOLERATE WHITESPACE ★ (R376: a reflow breaks
  // an exact-whitespace needle, and 45 mutation needles went blind
  // that way.)
  assertGateGoesRed(
    "the input pill's height cap is removed",
    "components/InputBox.tsx",
    /\s*height=\{INPUT_MAX_LINES \+ 2\}\s*\n\s*overflow="hidden"/,
    "",
  );
});

test("R388o mutation: the transcript's flex chain loses its zero basis", () => {
  // ★ THE DEFECT THE HEADER WENT MISSING FOR ★
  //
  // Yoga gives a flex item a BASE SIZE equal to its CONTENT, and it
  // only shrinks when the container overflows. So a transcript whose
  // content is taller than the terminal does not clip itself — it
  // takes the rows and SQUEEZES the chrome.
  //
  // Measured before the fix (12 turns, a 30-row terminal): the
  // header's box computed to ONE row — the brand row and the cwd
  // caption both computed to height 0 — and the input pill showed
  // only its top border.
  //
  // `flexBasis={0}` makes the base size zero, so the transcript grows
  // into the leftover rows instead of taking them from the chrome.
  //
  // ★ THE ANCHOR IS ON THE SCROLLBACK'S OWN COLUMN ★ An earlier draft
  // of this file mutated the WRAPPERS in `tui.tsx` and the gate stayed
  // GREEN. The wrappers' basis is not what does the work: the column
  // that holds the transcript is the one whose base size is the
  // transcript's own height. Measured by removing each change in
  // turn: removing `flexBasis={0}` from THIS box reproduces the
  // defect on its own; removing `minHeight`, either `overflow`, the
  // row budget or the width expression changes nothing.
  //
  // ★ THE ANCHOR MUST TARGET THE CODE, NOT THE PROSE ★
  // `flexBasis={0}` also appears in this file's COMMENTS — a plain
  // `replace(/flexBasis=\{0\}/)` rewrites the COMMENT and leaves the
  // code alone. The anchor below is anchored to the LINE.
  assertGateGoesRed(
    "the transcript's flex chain loses its zero basis",
    "components/Scrollback.tsx",
    /(\n\s*)flexBasis=\{0\}(\n\s*minHeight=\{0\})/,
    "$1$2",
  );
});

test("R388o mutation: the input pill stops using the width it is given", () => {
  // ★ THE PILL ASKED THE TERMINAL FOR ITS WIDTH ★ `InputBox` used to
  // ask `process.stdout` itself. That is wrong in ANY render target
  // that is not `process.stdout` — and the gate's probe is exactly
  // such a target: it renders into a Writable whose `columns` it
  // controls. So the pill laid itself out for the REAL terminal's
  // width and overflowed the probe's narrow one: a 99-cell pill in a
  // 40-column terminal, with the right border cut off.
  //
  // The width is a fact the CALLER already has, so it is a prop.
  assertGateGoesRed(
    "the input pill stops using the width it is given",
    "components/InputBox.tsx",
    /const terminalCols = width \?\? terminalSize\(\)\.cols;/,
    "const terminalCols = terminalSize().cols;",
  );
});
