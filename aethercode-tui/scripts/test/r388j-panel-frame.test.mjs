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

// R388j — ONE frame, ONE width contract.
//
// ## The bug this gate exists to hold
//
// `PanelShell` draws the panel's frame. Six panel components ALSO
// drew a frame of their own (`borderStyle="double"`), so the screen
// showed two nested boxes:
//
//     ╭────────────────────────────────╮
//     │ ◆ Threads          esc         │
//     │ ╔════════════════════════════╗ │
//     │ ║║                          ║ │   ← the border cells DOUBLED
//     │ ║  ◆ Sessions in AetherCode  ║ │   ← the title ran INTO it
//     │ ╚════════════════════════════╝ │
//     ╰────────────────────────────────╯
//
// Four visible defects, one cause. The fix was structural: the
// shell owns the frame, the panel owns the content.
//
// ## ★ THE MEASUREMENT WAS WRONG FOR A LONG TIME ★
//
// The panel LOOKED ragged — its right border landed on a different
// column on every line — and several rounds of work tried to fix a
// rendering bug that did not exist.
//
// The reason: Ink writes a line's trailing padding as
// `ESC[nX` (erase n characters) + `ESC[nC` (cursor forward n
// columns) instead of literal spaces. A measurement that strips ALL
// ANSI sequences DELETES those two, so each line appears 74 cells
// short and the border appears to wander.
//
// The real screen — reconstructed by honouring the cursor movements —
// has the border on column 100 of EVERY line.
//
// → **Lesson 1311 — a measurement that deletes the thing it
// measures cannot see the thing it measures.** An ANSI stripper is
// fine for text; it is NOT a terminal.
//
// This gate therefore renders the real components and reconstructs
// the screen the way a terminal does.
//
// ## What this gate pins
//
//   * no panel body draws a frame of its own;
//   * the shell's content width is derived once, not guessed;
//   * every panel row is fitted to the content width;
//   * the RENDERED frame's right border is on ONE column;
//   * `fitRow` truncates rather than overflows.

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { codeOf } from "./strip-comments.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

/* ------------------------------------------------------------------ *
 * The panels that live inside PanelShell.
 *
 * A panel body renders CONTENT. A component that is a CARD (a tool
 * card, a permission card, a stop banner) may draw its own box —
 * that is the point of a card. These are the bodies.
 *
 * `TodoBoard` and `WorktreePanel` are bodies too, but they lay
 * their rows out differently (a progress board, a git verdict
 * table), so they do not go through `PanelBody`. The FRAME rule
 * still applies to them; the `PanelBody` rule does not.
 * ------------------------------------------------------------------ */
const PANEL_BODIES = [
  "src/components/MemoryPanel.tsx",
  "src/components/ThreadSelector.tsx",
  "src/components/todo/TodoBoard.tsx",
  "src/components/AgentSelector.tsx",
  "src/components/EffortPicker.tsx",
  "src/components/ModePicker.tsx",
  "src/components/CwdSwitcher.tsx",
  "src/components/WorktreePanel.tsx",
];

/**
 * The bodies that lay their rows out through the shared `PanelBody`,
 * and therefore carry the width contract.
 */
const PANEL_BODY_USERS = [
  "src/components/MemoryPanel.tsx",
  "src/components/ThreadSelector.tsx",
  "src/components/AgentSelector.tsx",
  "src/components/EffortPicker.tsx",
  "src/components/ModePicker.tsx",
  "src/components/CwdSwitcher.tsx",
];

const read = (rel) => readFileSync(join(root, rel), "utf8");
const readCode = (rel) => codeOf(read(rel));

/* ------------------------------------------------------------------ *
 * 1. No panel body draws a frame of its own.
 * ------------------------------------------------------------------ */
test("R388j: no panel body draws a frame of its own", () => {
  for (const rel of PANEL_BODIES) {
    const code = readCode(rel);
    // `borderStyle` on a panel body would draw a SECOND frame inside
    // the shell's. The shell's own `borderStyle` is not in these
    // files, so any occurrence here is a second frame.
    assert.doesNotMatch(
      code,
      /borderStyle\s*=/,
      `${rel} draws its own border — the shell owns the frame (R388j)`,
    );
  }
});

test("R388j: the panel bodies render through PanelBody", () => {
  for (const rel of PANEL_BODY_USERS) {
    const code = readCode(rel);
    assert.match(
      code,
      /from\s+"\.\/PanelBody\.js"/,
      `${rel} does not import PanelBody — the shared body is what makes the width contract possible (R388j)`,
    );
    assert.match(code, /<PanelBody\b/, `${rel} does not render a PanelBody (R388j)`);
  }
});

/* ------------------------------------------------------------------ *
 * 2. The shell's content width is derived ONCE.
 * ------------------------------------------------------------------ */
test("R388j: the content width is derived once, in the host", () => {
  const code = readCode("src/tui.tsx");
  const derived = code.match(/const\s+panelContentWidth\s*=\s*([^;]+);/);
  assert.ok(derived, "tui.tsx must derive the panel content width in one place (R388j)");
  // The derivation is a function of the panel WIDTH, not a literal:
  // a literal would be right exactly until the terminal is resized.
  assert.match(
    derived[1],
    /panelWidth/,
    "the content width must be derived from the panel width, not hard-coded (R388j)",
  );

  // Every panel mount receives it. A panel that guesses its own
  // width is a panel that overflows when the terminal changes.
  const mounts = code.match(/width=\{panelContentWidth\}/g) ?? [];
  assert.ok(
    mounts.length >= 6,
    `expected every panel mount to take the derived width; found ${mounts.length} (R388j)`,
  );
});

test("R388j: the shell computes its content width from the frame", () => {
  const code = readCode("src/tui.tsx");
  assert.match(
    code,
    /const\s+inner\s*=\s*Math\.max\(\s*20\s*,\s*width\s*-\s*4\s*\)/,
    "PanelShell must derive the content width from the frame width (R388j)",
  );
});

/* ------------------------------------------------------------------ *
 * 3. fitRow: the row-fitting primitive.
 * ------------------------------------------------------------------ */
test("R388j: fitRow pads to the width and truncates past it", async () => {
  const mod = await import(
    pathToFileURL(join(root, "scripts", "test", "r388j-render-frame.mjs")).href
  );
  const fitRow = await mod.fitRowOf(root);

  assert.equal(typeof fitRow, "function", "fitRowOf must return the real fitRow (R388j)");
  assert.equal(fitRow("abc", 10).length, 10, "fitRow must pad to the width (R388j)");
  assert.equal(fitRow("abc", 10).trimEnd(), "abc", "fitRow must not alter the text (R388j)");
  const long = fitRow("x".repeat(50), 10);
  assert.equal(long.length, 10, "fitRow must truncate to the width (R388j)");
  assert.ok(long.endsWith("…"), "a truncated row must end with an ellipsis (R388j)");
});

/* ------------------------------------------------------------------ *
 * 4. ★ THE RENDERED FRAME MUST HAVE ONE RIGHT BORDER COLUMN ★
 *
 * This is the assertion the broken measurement could not make. It
 * renders the real shell + a real panel body through Ink and
 * reconstructs the SCREEN the way a terminal does — honouring
 * `ESC[nX` (erase n chars) and `ESC[nC` (cursor forward n cols).
 * ------------------------------------------------------------------ */
test("R388j: the rendered panel has ONE right border column", async () => {
  const { renderPanelFrame } = await import(
    pathToFileURL(join(root, "scripts", "test", "r388j-render-frame.mjs")).href
  );
  const frame = renderPanelFrame({ root });

  assert.ok(frame.length >= 8, `expected a rendered frame, got ${frame.length} lines (R388j)`);

  const columns = frame.filter((l) => l.includes("│")).map((l) => l.lastIndexOf("│"));

  assert.ok(columns.length >= 8, `expected content lines, got ${columns.length} (R388j)`);
  const distinct = [...new Set(columns)];
  assert.equal(
    distinct.length,
    1,
    `the frame's right border must be on ONE column; found ${distinct.join(", ")} (R388j)`,
  );
});

test("R388j: every rendered row fills the frame's interior", async () => {
  const { renderPanelFrame } = await import(
    pathToFileURL(join(root, "scripts", "test", "r388j-render-frame.mjs")).href
  );
  const frame = renderPanelFrame({ root });

  // The frame's right border is drawn by the BOX, so a short row does
  // NOT move it — the border column is fixed by the layout, not by
  // the text. (This is why the earlier "ragged edge" was a MEASUREMENT
  // defect and not a rendering one.)
  //
  // What a short row DOES do is leave a GAP inside the frame: the
  // panel reads as a ragged block of text floating in a box instead
  // of a filled panel. So the invariant is: every content row's
  // visible width equals the frame's width.
  const content = frame.filter((l) => l.startsWith("│") && l.endsWith("│"));
  assert.ok(content.length >= 6, `expected content rows, got ${content.length} (R388j)`);

  const widths = [...new Set(content.map((l) => l.length))];
  assert.equal(
    widths.length,
    1,
    `every row must fill the frame's interior; found widths ${widths.join(", ")} (R388j)`,
  );
});

/* ------------------------------------------------------------------ *
 * 5. The session panel offers a way OUT of an empty scope.
 * ------------------------------------------------------------------ */
test("R388j: the session panel widens its own scope", () => {
  const code = readCode("src/components/ThreadSelector.tsx");
  // An empty scope has to offer a KEYPRESS, not a sentence that
  // tells the user to go and remember another command's name.
  assert.match(code, /onShowAll/, "the session panel must accept a widen-scope callback (R388j)");
  assert.match(code, /input\s*===\s*"a"/, "the `a` key must widen the scope (R388j)");

  const host = readCode("src/tui.tsx");
  assert.match(host, /widenPanelScope/, "the host must dispatch the widen-scope action (R388j)");
});

test("R388j: the widen key does not leak into the filter", () => {
  const code = readCode("src/components/ThreadSelector.tsx");

  // ★ INK'S `useInput` IS NOT FOCUS-SCOPED. ★
  //
  // The filter row's `TextInput` has its OWN `useInput`, and it
  // inserts any printable character into the filter — including the
  // `a` that widens the scope. Returning from the panel's handler
  // does NOT stop that: both handlers run.
  //
  // The symptom is visible: the filter reads `a` while the panel has
  // widened to "All sessions", so the list the user just asked for
  // is filtered by a character that was never meant to be a filter.
  //
  // The fix is a latch that swallows exactly that one character,
  // whichever order the two handlers run in.
  assert.match(
    code,
    /swallowNextFilterChar/,
    "the widen key must be swallowed by the filter row (R388j)",
  );
  assert.match(code, /setFilter\(""\)/, "the filter must be cleared when the scope widens (R388j)");
  assert.doesNotMatch(
    code,
    /onChange:\s*setFilter\s*,/,
    "the filter's onChange must go through the swallow latch (R388j)",
  );
});

test("R388j: an open panel owns the keyboard", () => {
  const code = readCode("src/components/InputBox.tsx");

  // The App-level handler already returns early when a panel is open
  // (so ↑/↓ do not drive the prompt history). But the input box has
  // its OWN `useInput` — so the panel's keystrokes were ALSO typed
  // into the prompt. Pressing `a` in the session panel widened the
  // scope AND left `a` in the prompt box.
  assert.match(
    code,
    /const\s+panelOpen\s*=\s*state\.activePanel\s*!=\s*null/,
    "the input box must know whether a panel is open (R388j)",
  );
  assert.match(
    code,
    /if\s*\(\s*panelOpen\s*\)\s*return;/,
    "an open panel must own the keyboard (R388j)",
  );
});

test("R388j: Enter on a complete command RUNS it", () => {
  const input = readCode("src/components/InputBox.tsx");
  const commands = readCode("src/commands.ts");

  // The autocomplete dropdown owns Enter while it is open: it accepts
  // the suggestion and stops. So `/sessions` + Enter filled the input
  // with `/sessions ` and did nothing — the user had to press Enter a
  // SECOND time to run the command.
  //
  // Two Enters for one command is a trap, and it is exactly the shape
  // a user hits when they type a command they know by heart.
  assert.match(
    commands,
    /export\s+function\s+isCompleteSlashCommand\b/,
    "the command vocabulary must own the 'is this a complete command' question (R388j)",
  );
  assert.match(input, /isCompleteSlashCommand/, "the input box must use it (R388j)");
  assert.match(
    input,
    /submitText/,
    "the canonical text must be threaded to the submit branch (R388j)",
  );
});
