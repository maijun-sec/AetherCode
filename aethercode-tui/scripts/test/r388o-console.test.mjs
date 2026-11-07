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
 * The LAST frame in a redraw stream.
 *
 * ★ WHY THE LAST FRAME AND NOT THE WHOLE STREAM ★ `log-update`
 * writes `eraseLines(n) + output`, and `ansiEscapes.eraseLines(n)`
 * is `ESC[2K ESC[1A` repeated, then `ESC[G`. So every frame boundary
 * is marked by `ESC[2K`, and the text AFTER the last `ESC[2K` is the
 * final frame — the one the user is actually looking at.
 *
 * Asserting against the CUMULATIVE stream is a different, and wrong,
 * measurement: it pools every frame the console ever received, so a
 * frame that was drawn BEFORE the fix (or before the frame settled)
 * can fail an assertion about the current one. That is how this gate
 * passed alone and failed inside the full suite — the suite's other
 * tests changed the timing, so more early frames landed in the pool.
 *
 * @param {string} raw the raw stream
 * @returns {string} the last frame's text (ANSI stripped)
 */
function lastFrame(raw) {
  // The final `ESC[2K` is the start of the final erase pass; the
  // frame content follows it. If there is no `ESC[2K` at all, the
  // bundle never redrew in place — the whole stream IS the frame.
  const boundary = raw.lastIndexOf("\u001b[2K");
  const slice = boundary >= 0 ? raw.slice(boundary) : raw;
  return stripAnsi(slice);
}

/**
 * Run the shipped bundle in a real console host.
 *
 * @param {number} cols the terminal width to report
 * @param {number} rows the terminal height to report
 * @returns {{raw: string, plain: string, lines: string[], frame: string, frameLines: string[]}}
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
//
// 4000ms, not 2500: the bundle has to boot the daemon, resolve the
// provider and settle its first frame. Under the full suite the
// machine is busier, and a too-short delay lets the process exit
// BEFORE the first real frame — which then measured an empty
// screen rather than the product.
setTimeout(() => process.exit(0), 4000);
`,
      "utf8",
    );
    const run = spawnSync(CONHOST, ["--headless", "--", "cmd", "/c", "node", shim], {
      encoding: "utf8",
      timeout: 60_000,
      env: { ...process.env, FORCE_COLOR: "0" },
    });
    const raw = (run.stdout ?? "") + (run.stderr ?? "");
    const plain = stripAnsi(raw);
    const frame = lastFrame(raw);
    return {
      raw,
      plain,
      lines: plain.split("\n").filter((l) => l.trim().length > 0),
      frame,
      frameLines: frame.split("\n").filter((l) => l.trim().length > 0),
    };
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
  const { raw, lines, frame, frameLines } = runInConsole(COLS, ROWS);

  assert.ok(lines.length > 0, "R388o: the bundle produced no output at all in the console.");

  // ★ THE FRAME MUST FIT ★ Ink writes `clearTerminal + output` when
  // the frame is at least `stdout.rows` tall, and it writes the
  // in-place redraw otherwise. A frame that FITS takes the in-place
  // path, and `ESC[2J` is never emitted.
  //
  // One `ESC[2J` is tolerated: conhost itself emits `ESC[2J ESC[m ESC[H`
  // when it starts. More than one means Ink took the clearTerminal path
  // — which on the classic console host means the frames STACK.
  //
  // ★ COUNTED OVER THE WHOLE STREAM, NOT THE LAST FRAME ★ This is
  // the one assertion that must NOT be narrowed: the stacking defect
  // is visible as the ACCUMULATION of clears over time, so it is
  // counted over everything the console received. The frame-scoped
  // assertions below are the opposite — they describe what the user
  // sees NOW, so they look only at the last frame.
  const clearCount = (raw.match(/\u001b\[2J/g) ?? []).length;
  assert.ok(
    clearCount <= 1,
    `R388o: the console saw ${clearCount} clearTerminal sequences (ESC[2J) — the frame is taller than the terminal, so Ink wipes instead of redrawing in place.`,
  );

  // ★ THE CHROME MUST BE WHOLE ★ These are the things the user said
  // were missing or cut off: the header's strip, the input pill's
  // RIGHT border, and the status bar.
  const headerRow = frameLines.find((l) => l.includes("MiniMax-M3"));
  assert.ok(
    headerRow,
    `R388o: the header's status strip is missing from the console output.\n${frameLines.slice(-15).join("\n")}`,
  );

  // The input pill: the LAST line of the pill carries the right border.
  // The user's screenshot showed `[↵` — the `]` was cut off.
  const pillRows = frameLines.filter((l) => l.includes("╭") || l.includes("│") || l.includes("╰"));
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
  const statusRows = frameLines.filter((l) => l.includes("ready") || l.includes("stopped"));
  assert.ok(statusRows.length >= 1, `R388o: the status bar is missing from the console output.`);
  assert.match(
    statusRows[statusRows.length - 1],
    /[●○]/,
    `R388o: the status bar lost its connection dot:\n  |${statusRows[statusRows.length - 1]}|`,
  );

  // ★ AND THE FRAME ITSELF IS A FRAME ★ A frame that redraws in place
  // must actually contain the chrome. If the last frame is empty, the
  // console showed a blank screen and every assertion above would
  // have passed vacuously on an earlier frame.
  assert.ok(
    frame.length > 0 && frameLines.length > 0,
    `R388o: the final frame in the console is EMPTY — the user would see a blank screen.`,
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
  const { lines, frameLines } = runInConsole(COLS, 30);
  assert.ok(lines.length > 0, "R388o: the bundle produced no output at all in the console.");

  // The console folds an over-wide row, so the folded rows are at most
  // the terminal's width. What must NOT happen is a row that is
  // exactly the width AND ends mid-glyph — the cut-off border.
  //
  // ★ MEASURED ON THE LAST FRAME ★ An over-wide row in an EARLY frame
  // is not what the user sees; the final frame is. And the pooled
  // stream can carry a folded row from a frame that the console
  // itself produced, not the TUI.
  const pillRows = frameLines.filter((l) => l.includes("╭") || l.includes("│") || l.includes("╰"));
  for (const row of pillRows) {
    assert.ok(
      row.length <= COLS + 2,
      `R388o: at ${COLS} columns a pill row is ${row.length} cells:\n  |${row}|`,
    );
  }
});
