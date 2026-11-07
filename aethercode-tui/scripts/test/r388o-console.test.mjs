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

// R388o — the REAL shipped bundle, in a REAL Windows console.
//
// ★ WHY THIS IS A SEPARATE GATE ★
//
// `r388o-geometry.test.mjs` renders the components through Ink into a
// Writable it controls. That measures the LAYOUT. It cannot measure the
// thing the user actually sees, because the user's terminal is a
// Windows console host and the whole bug chain ends in a console
// detail: Ink switches to its `clearTerminal` path (`ESC[2J ESC[3J
// ESC[H`) when the frame is taller than `stdout.rows`, and the classic
// console host does not honour `ESC[2J` — so every render APPENDS
// instead of replacing, and the screen fills with repeated headers.
//
// So this gate runs the SHIPPED BUNDLE — the same bytes the user gets —
// inside `conhost.exe --headless`, which is a real TTY (stdin is a
// TTY, `setRawMode` works, Ink takes its Ink path), and asserts on
// what the console received.
//
// ★ A PROBE MUST NOT MIRROR THE PRODUCT ★ (Lesson 1376) This one runs
// the product itself. There is nothing to mirror and nothing to
// re-derive: the bundle IS the thing under test.
//
// The gate is SKIPPED (not failed) when the bundle has not been built
// or `conhost.exe` is not where it is expected — a source checkout
// that has not run `npm run bundle` is not a defect.

import { strict as assert } from "node:assert";
import { test } from "node:test";
import { spawnSync } from "node:child_process";
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const BUNDLE = join(ROOT, "dist", "ac-tui.js");
const CONHOST = "C:\\Windows\\System32\\conhost.exe";

/**
 * Strip the ANSI escape sequences from a byte stream, keeping the text.
 *
 * @param {string} s the raw stream
 * @returns {string} the plain text
 */
function stripAnsi(s) {
  let plain = "";
  let i = 0;
  while (i < s.length) {
    if (s[i] === "\u001b") {
      if (s[i + 1] === "[") {
        let j = i + 2;
        while (j < s.length && !/[A-Za-z]/.test(s[j])) j += 1;
        i = j + 1;
        continue;
      }
      if (s[i + 1] === "]") {
        let j = i + 2;
        while (j < s.length && s[j] !== "\u0007") j += 1;
        i = j + 1;
        continue;
      }
      i += 2;
      continue;
    }
    plain += s[i];
    i += 1;
  }
  return plain;
}

/**
 * Run the shipped bundle in a real console host.
 *
 * @param {number} cols the terminal width to report
 * @param {number} rows the terminal height to report
 * @returns {{raw: string, plain: string, lines: string[]}}
 */
function runInConsole(cols, rows) {
  // The bundle asks the terminal for its size. conhost's headless mode
  // cannot be told a size, so a tiny shim reports the size we want and
  // then hands over to the real bundle.
  const dir = mkdtempSync(join(tmpdir(), "r388o-console-"));
  try {
    const shim = join(dir, "shim.mjs");
    writeFileSync(
      shim,
      `
process.stdout.columns = ${cols};
process.stdout.rows = ${rows};
await import("file:///" + ${JSON.stringify(BUNDLE)}.replace(/\\\\/g, "/"));
// ★ EXIT AFTER THE FRAME IS DRAWN ★ The bundle is a TUI: it renders
// and then WAITS FOR INPUT, forever. Without this the console gate
// would sit on \`spawnSync\`'s timeout and leave the whole conhost
// tree orphaned — which is how a test turns into a process leak.
setTimeout(() => process.exit(0), 2500);
`,
      "utf8",
    );
    const run = spawnSync(CONHOST, ["--headless", "--", "cmd", "/c", "node", shim], {
      encoding: "utf8",
      timeout: 30_000,
      env: { ...process.env, FORCE_COLOR: "0" },
    });
    const raw = (run.stdout ?? "") + (run.stderr ?? "");
    const plain = stripAnsi(raw);
    return { raw, plain, lines: plain.split("\n").filter((l) => l.trim().length > 0) };
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

test("R388o: the shipped bundle fits a real Windows console", (t) => {
  if (!existsSync(BUNDLE)) {
    t.skip(`no bundle at ${BUNDLE} — run \`npm run bundle\` first`);
    return;
  }
  if (!existsSync(CONHOST)) {
    t.skip(`no conhost.exe at ${CONHOST}`);
    return;
  }

  const COLS = 120;
  const ROWS = 30;
  const { raw, lines } = runInConsole(COLS, ROWS);

  assert.ok(lines.length > 0, "R388o: the bundle produced no output at all in the console.");

  // ★ THE FRAME MUST FIT ★ Ink writes `clearTerminal + output` when
  // the frame is at least `stdout.rows` tall, and it writes the
  // in-place redraw otherwise. A frame that FITS takes the in-place
  // path, and `ESC[2J` is never emitted.
  //
  // One `ESC[2J` is tolerated: conhost itself emits `ESC[2J ESC[m ESC[H`
  // when it starts. More than one means Ink took the clearTerminal path
  // — which on the classic console host means the frames STACK.
  const clearCount = (raw.match(/\u001b\[2J/g) ?? []).length;
  assert.ok(
    clearCount <= 1,
    `R388o: the console saw ${clearCount} clearTerminal sequences (ESC[2J) — the frame is taller than the terminal, so Ink wipes instead of redrawing in place.`,
  );

  // ★ THE CHROME MUST BE WHOLE ★ These are the things the user said
  // were missing or cut off: the header's strip, the input pill's
  // RIGHT border, and the status bar.
  const headerRow = lines.find((l) => l.includes("MiniMax-M3"));
  assert.ok(
    headerRow,
    `R388o: the header's status strip is missing from the console output.\n${lines.slice(-15).join("\n")}`,
  );

  // The input pill: the LAST line of the pill carries the right border.
  // The user's screenshot showed `[↵` — the `]` was cut off.
  const pillRows = lines.filter((l) => l.includes("╭") || l.includes("│") || l.includes("╰"));
  assert.ok(pillRows.length >= 3, `R388o: the input pill is not drawn in the console output.`);
  const pillBottom = pillRows.find((l) => l.trimStart().startsWith("╰"));
  assert.ok(pillBottom, `R388o: the input pill has no bottom border in the console output.`);
  assert.match(
    pillBottom.trimEnd(),
    /╯\s*$/,
    `R388o: the input pill's bottom border is cut off:\n  |${pillBottom}|`,
  );
  // The row with the send affordance must carry the RIGHT border too.
  const sendRow = pillRows.find((l) => l.includes("↵"));
  assert.ok(sendRow, `R388o: the input pill has no send affordance row.`);
  assert.match(
    sendRow.trimEnd(),
    /↵\s*│\s*$/,
    `R388o: the input pill's right border is cut off (the user saw "[↵" instead of "↵]"):\n  |${sendRow}|`,
  );

  // The status bar: ONE row, with the state label.
  const statusRows = lines.filter((l) => l.includes("ready") || l.includes("stopped"));
  assert.ok(statusRows.length >= 1, `R388o: the status bar is missing from the console output.`);
  assert.match(
    statusRows[statusRows.length - 1],
    /[●○]/,
    `R388o: the status bar lost its connection dot:\n  |${statusRows[statusRows.length - 1]}|`,
  );
});

test("R388o: the shipped bundle's frame is not wider than a narrow console", (t) => {
  if (!existsSync(BUNDLE)) {
    t.skip(`no bundle at ${BUNDLE} — run \`npm run bundle\` first`);
    return;
  }
  if (!existsSync(CONHOST)) {
    t.skip(`no conhost.exe at ${CONHOST}`);
    return;
  }

  // 40 columns is the TUI's own floor (`terminalGeometry.MIN_COLS`).
  const COLS = 40;
  const { lines } = runInConsole(COLS, 30);
  assert.ok(lines.length > 0, "R388o: the bundle produced no output at all in the console.");

  // The console folds an over-wide row, so the folded rows are at most
  // the terminal's width. What must NOT happen is a row that is
  // exactly the width AND ends mid-glyph — the cut-off border.
  const pillRows = lines.filter((l) => l.includes("╭") || l.includes("│") || l.includes("╰"));
  for (const row of pillRows) {
    assert.ok(
      row.length <= COLS + 2,
      `R388o: at ${COLS} columns a pill row is ${row.length} cells:\n  |${row}|`,
    );
  }
});
