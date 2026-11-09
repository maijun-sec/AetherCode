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

// R388s — the checkpoint PREVIEW: what would restoring this point do?
//
// ## The defect
//
// The rewind menu showed the file NAMES of the SELECTED point, at the
// BOTTOM of the panel, after the action list. So:
//
//   * the preview was only visible AFTER the user had moved the
//     selection onto a point — but the preview is what helps them
//     CHOOSE a point;
//   * and what it showed was "which files the session touched", not
//     "what a restore of this point would DO".
//
// ## ★ THE NUMBERS COME FROM THE DAEMON ★
//
// The daemon holds BOTH sides of the comparison — the snapshot it took
// at that point, and the file as it is now. The renderer never had the
// snapshot, so it CANNOT compute this. So the daemon computes it and
// puts it on the wire.
//
// ★ AND THE COUNTS ARE LINE COUNTS, NOT A DIFF ★ A full diff needs a
// line-matching algorithm; a second implementation of one in a second
// language is a second truth that will drift. So the preview reports
// the honest, computable fact: how many LINES differ.
//
// ★ AND "UNKNOWN" IS NOT ZERO ★ A binary file, an unreadable path, a
// file that is now a directory — the restore SKIPS those. The menu must
// say "unknown", not "0", because "0" is a number the user may act on.
//
// ## ★ THE GATE RENDERS THE REAL COMPONENT ★
//
// A grep for "linesDelta" proves the string is in the source. It
// proves nothing about what the terminal shows. So this gate renders
// the REAL menu and measures the frame.

import { strict as assert } from "node:assert";
import { test } from "node:test";
import { spawnSync } from "node:child_process";
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(fileURLToPath(new URL("../..", import.meta.url)));
const MENU = join(ROOT, "src", "components", "CheckpointMenu.tsx");
const PREVIEW = join(ROOT, "src", "checkpointPreview.ts");
const CHANGES = join(ROOT, "src", "fileChanges.ts");
// ★ THE DAEMON'S OWN SOURCE ★ The preview's NUMBERS are computed by
// the daemon, so the gate reads the daemon's rule too — a renderer
// that disagrees with the daemon about how many lines a string spans
// would show the user two different numbers for the same file.
const STORE = join(
  ROOT,
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
);

/**
 * Render the REAL CheckpointMenu with the given checkpoints and return
 * the LAST frame's lines.
 *
 * <p>★ THE PROBE IS A FIXTURE-FREE INLINE SCRIPT HERE ★ It is short
 * enough to read inline, and it inherits R388p's proven shape: Ink
 * reads its input on the 'readable' event, and `read()` must be
 * overridden AFTER construction (the `Writable` prototype shadows an
 * object-literal method).
 *
 * @param {object[]} checkpoints the checkpoint rows to render
 * @param {object} opts {width, maxRows, evicted}
 * @returns {string[]} the frame's lines
 */
function renderMenu(checkpoints, opts = {}) {
  const dir = mkdtempSync(join(ROOT, ".r388s-probe-"));
  const entry = join(dir, "probe.tsx");
  const bundle = join(dir, "probe.mjs");
  try {
    const width = opts.width ?? 60;
    const maxRows = opts.maxRows ?? 8;
    const evicted = opts.evicted ?? 0;
    writeFileSync(
      entry,
      `
import React from "react";
import { render, Box } from "ink";
import { CheckpointMenu } from ${JSON.stringify(MENU)};

const checkpoints = ${JSON.stringify(checkpoints)};

const { Writable } = await import("node:stream");
const chunks = [];
const stdout = new Writable({ write(c, _e, cb) { chunks.push(c.toString("utf8")); cb(); } });
stdout.columns = 80;
stdout.rows = 40;
stdout.isTTY = true;

let buffer = "";
const stdin = new Writable({ write(_c, _e, cb) { cb(); } });
stdin.read = () => {
  if (buffer.length === 0) return null;
  const chunk = buffer;
  buffer = "";
  return chunk;
};
stdin.isTTY = true;
stdin.setRawMode = () => stdin;
stdin.setEncoding = () => stdin;
stdin.ref = () => stdin;
stdin.unref = () => stdin;
stdin.resume = () => stdin;
stdin.pause = () => stdin;
stdin.readableLength = 0;

const { unmount } = render(
  <Box flexDirection="column">
    <CheckpointMenu
      checkpoints={checkpoints}
      onChoose={() => {}}
      onClose={() => {}}
      width={${width}}
      maxRows={${maxRows}}
      evicted={${evicted}}
    />
  </Box>,
  { stdout, stdin, exitOnCtrlC: false, patchConsole: false },
);

setTimeout(() => {
  process.stderr.write("__RAW__" + chunks.join("") + "__END__");
  unmount();
  process.exit(0);
}, 400);
`,
      "utf8",
    );

    // ★ ESBUILD'S LOCATION CAN BE OVERRIDDEN ★ The mutation gate
    // runs this gate from a SANDBOX copy in the OS temp directory, and
    // the sandbox has no `node_modules` — a junction would resolve
    // back to the real tree, so the dependency is not copied. The
    // builder is therefore pointed at the real one by the caller.
    //
    // ★ AND nodePaths POINTS AT THE REAL node_modules ★ The sandbox
    // has no `node_modules` — a junction would resolve back to the
    // real tree, and the whole point of the sandbox is that the
    // mutation is NOT in the real tree. So the resolution path is
    // borrowed, not copied, and the caller hands it over.
    //
    // (Hardcoding the sandbox's path made EVERY mutation go red on a
    // BUILD failure — the probe could not resolve esbuild — so the
    // gate would have been red on ANY change, including a correct
    // one. It had a tripwire, not teeth.)
    const esbuildDir = process.env.R388Q_ESBUILD_DIR || join(ROOT, "node_modules", "esbuild");
    const nodePaths = process.env.R388Q_NODE_PATHS || join(ROOT, "node_modules");
    // ★ INK AND REACT ARE BUNDLED, NOT EXTERNAL ★ Two reasons:
    //
    //   1. Ink needs ESM — bundling it to CJS breaks on
    //      `require("assert")` inside its own dependencies.
    //   2. ★ An EXTERNAL `ink` is resolved by Node at RUN time from
    //      the bundle's own directory, and the sandbox has no
    //      `node_modules` there. `nodePaths` does not help: it applies
    //      to BUNDLING, not to a runtime ESM import. So the bundle
    //      must be SELF-CONTAINED — it carries its own copy of Ink and
    //      React, and it runs anywhere.
    //
    // (The first version of this file externalised them, and the
    // probe died with "Cannot find package 'ink'" — a BUILD failure
    // that turned every mutation into a red for the wrong reason.)
    //
    // ★ AND THE INTEROP SHIM ★ esbuild rewrites `require` to a stub in
    // an ESM output, and Ink's dependencies call it — so the bundle
    // needs the official one-liner that gives it a real `require`.
    const build = spawnSync(
      process.execPath,
      [
        "-e",
        `const {build}=require(${JSON.stringify(esbuildDir)});\n` +
          `build({entryPoints:[${JSON.stringify(entry)}],bundle:true,outfile:${JSON.stringify(bundle)},platform:'node',format:'esm',jsx:'automatic',nodePaths:[${JSON.stringify(nodePaths)}],banner:{js:"import { createRequire as __cr } from 'node:module';const require = __cr(import.meta.url);"},loader:{'.ts':'ts','.tsx':'tsx'}}).catch(e=>{console.error(e.message);process.exit(1)});`,
      ],
      { cwd: ROOT, encoding: "utf8" },
    );
    if (build.status !== 0 || !existsSync(bundle)) {
      throw new Error(`r388s: could not build the menu probe: ${build.stderr || build.stdout}`);
    }

    const run = spawnSync(process.execPath, [bundle], { encoding: "utf8", timeout: 60_000 });
    const stderr = run.stderr ?? "";
    const m = stderr.match(/__RAW__([\s\S]*?)__END__/);
    if (!m) {
      throw new Error(`r388s: the menu probe produced no frame (stderr: ${stderr.slice(0, 1200)})`);
    }
    // ★ THE LAST FRAME ONLY ★ Ink redraws in place, so the stream holds
    // every frame. The boundary is the LAST 'ESC[2K' in the RAW stream —
    // stripping the ANSI first would delete the marker the boundary is
    // defined by.
    const b = m[1].lastIndexOf("[2K");
    const rawFrame = b >= 0 ? m[1].slice(b) : m[1];
    const plain = rawFrame.replace(/\[[0-9;?]*[A-Za-z]/g, "");
    return plain.split("\n").filter((l) => l.trim().length > 0);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

/**
 * A checkpoint row with a preview.
 *
 * @param {number} i the index
 * @param {object} preview the preview, or null for none
 * @returns {object}
 */
function cp(i, preview) {
  return {
    id: "cp-" + i,
    userMessageIndex: i,
    summary: "turn " + i,
    files: [
      { path: "C:/w/f" + i + ".ts", name: "f" + i + ".ts", restorable: true, created: false },
    ],
    preview,
  };
}

// ═══════════════════════════════════════════════════════════════════
// 1. EVERY POINT SHOWS ITS OWN FOOTPRINT — BEFORE ANY SELECTION
// ═══════════════════════════════════════════════════════════════════

test("R388s: EVERY checkpoint shows its own footprint, not just the selected one", () => {
  // ★ THE POINT OF THE ROUND ★ The preview is what helps the user
  // CHOOSE a point. So it must be on every row — a preview that only
  // appears after the selection cannot help the choice.
  //
  // ★ AND IT IS MEASURED IN THE FRAME ★ Not grepped.
  const lines = renderMenu([
    cp(0, { files: [], changedFiles: 3, changedLines: 12, complete: true }),
    cp(1, { files: [], changedFiles: 1, changedLines: 2, complete: true }),
    cp(2, { files: [], changedFiles: 7, changedLines: 340, complete: true }),
  ]);
  const text = lines.join("\n");
  // Each point's own numbers — the OTHER two points' numbers must not
  // be standing in for it.
  assert.match(
    text,
    /3 files · ~12 lines/,
    "the frame does not show point 0's footprint. Frame:\n" + text,
  );
  assert.match(
    text,
    /1 file · ~2 lines/,
    "the frame does not show point 1's footprint. Frame:\n" + text,
  );
  assert.match(
    text,
    /7 files · ~340 lines/,
    "the frame does not show point 2's footprint. Frame:\n" + text,
  );
});

test("R388s: a point with NO preview says so — it does not show a zero", () => {
  // ★ ABSENT IS NOT ZERO ★ A checkpoint with no preview (an older
  // daemon) is a point we know NOTHING about. Showing "0 files" would
  // be a measurement the daemon never made.
  const lines = renderMenu([
    cp(0, { files: [], changedFiles: 2, changedLines: 8, complete: true }),
    cp(1, null),
  ]);
  const text = lines.join("\n");
  assert.match(
    text,
    /2 files · ~8 lines/,
    "the previewed point lost its footprint. Frame:\n" + text,
  );
  // And the un-previewed point shows NO footprint line at all — not a
  // "0 files" line.
  const zeroFootprint = /0 files/.test(text);
  assert.ok(
    !zeroFootprint,
    "a point with no preview showed a zero — that is a number the daemon never sent. Frame:\n" +
      text,
  );
});

test("R388s: a point where NOTHING would change says so in words", () => {
  // ★ "NO CHANGE" IS ITS OWN WORDS ★ "0 files · 0 lines" reads like a
  // measurement of nothing rather than a statement about the point.
  const lines = renderMenu([
    cp(0, { files: [], changedFiles: 0, changedLines: 0, complete: true }),
  ]);
  const text = lines.join("\n");
  assert.match(
    text,
    /no change/,
    "the frame does not say that nothing would change. Frame:\n" + text,
  );
});

test("R388s: an INCOMPLETE preview says so — 'no change' must not hide what we cannot tell", () => {
  // ★ THE INCOMPLETENESS MUST REACH THE USER ★ A point with an
  // unknown file says "no change (some files unknown)" — because
  // "no change" alone would be a claim the daemon cannot make.
  const lines = renderMenu([
    cp(0, { files: [], changedFiles: 0, changedLines: 0, complete: false }),
  ]);
  const text = lines.join("\n");
  assert.match(
    text,
    /no change \(some files unknown\)/,
    "the frame claims 'no change' while files are unknown. Frame:\n" + text,
  );
});

// ═══════════════════════════════════════════════════════════════════
// 2. THE DETAIL PANE SAYS WHAT A RESTORE WOULD DO
// ═══════════════════════════════════════════════════════════════════

test("R388s: the detail pane says what restoring the point would DO to each file", () => {
  // ★ THE OLD PANE SAID "WHICH FILES", NOT "WHAT WOULD HAPPEN" ★ The
  // file names are still there, but the preview's answer is the one
  // the user needs before choosing.
  const lines = renderMenu([
    cp(0, {
      files: [
        { name: "a.ts", linesNow: 40, linesDelta: 12, created: false, known: true, note: null },
        { name: "b.ts", linesNow: 5, linesDelta: 0, created: false, known: true, note: null },
      ],
      changedFiles: 1,
      changedLines: 12,
      complete: true,
    }),
  ]);
  const text = lines.join("\n");
  assert.match(
    text,
    /a\.ts — ~12 lines differ \(now 40\)/,
    "the changed file is not described. Frame:\n" + text,
  );
  assert.match(text, /b\.ts — unchanged/, "the unchanged file is not described. Frame:\n" + text);
});

test("R388s: a file the daemon could NOT read says UNKNOWN — never 'unchanged'", () => {
  // ★ THE UNKNOWN CASE ★ A binary file, an unreadable path. The
  // restore SKIPS it, so the menu must not claim it is unchanged —
  // "unchanged" is a claim, and it would be wrong.
  const lines = renderMenu([
    cp(0, {
      files: [
        {
          name: "logo.png",
          linesNow: -1,
          linesDelta: -1,
          created: false,
          known: false,
          note: "not restorable",
        },
      ],
      changedFiles: 0,
      changedLines: 0,
      complete: false,
    }),
  ]);
  const text = lines.join("\n");
  assert.match(
    text,
    /logo\.png — unknown \(not restorable\)/,
    "the unknown file is not marked. Frame:\n" + text,
  );
  assert.ok(
    !/logo\.png — unchanged/.test(text),
    "an unknown file was reported as unchanged — that is a claim the daemon cannot make. Frame:\n" +
      text,
  );
});

test("R388s: a file the session CREATED says it would be DELETED", () => {
  // ★ THE CREATED CASE IS A DELETE ★ Restoring a point before the
  // session created the file DELETES it. Saying "would be deleted" is
  // the fact; showing a line count without it would be misleading.
  const lines = renderMenu([
    cp(0, {
      files: [
        { name: "new.ts", linesNow: 30, linesDelta: 30, created: true, known: true, note: null },
      ],
      changedFiles: 1,
      changedLines: 30,
      complete: true,
    }),
  ]);
  const text = lines.join("\n");
  assert.match(
    text,
    /new\.ts — would be deleted \(30 lines\)/,
    "the created file is not described as a delete. Frame:\n" + text,
  );
});

// ═══════════════════════════════════════════════════════════════════
// 3. THE RENDERER AND THE DAEMON AGREE ON HOW TO COUNT LINES
// ═══════════════════════════════════════════════════════════════════

test("R388s: the renderer and the DAEMON count lines the SAME way", () => {
  // ★ THE RULE LIVES IN TWO LANGUAGES ★ The daemon computes the
  // preview's line counts and the renderer displays them — so the
  // COUNTING rule has to agree, or the user sees two different
  // numbers for the same file.
  //
  // The rule: an empty string is ZERO lines, and a trailing newline
  // does not start a new one — so "a\n" is ONE line, not two.
  const changes = readFileSync(CHANGES, "utf8");
  assert.match(
    changes,
    /export function countLines/,
    "the TUI's line-counting rule is gone — the renderer cannot agree with the daemon.",
  );
  // The rule's shape: the trailing-newline adjustment.
  assert.match(
    changes,
    /endsWith\("\\n"\) \? 1 : 0/,
    'the TUI\'s line-counting rule lost its trailing-newline adjustment — "a\\n" would count as two lines.',
  );

  // ★ AND THE DAEMON'S COPY ★ The daemon computes the numbers, so its
  // rule has to be the same rule — not a similar one.
  const store = readFileSync(STORE, "utf8");
  assert.match(
    store,
    /private static int countLines\(String s\)/,
    "the daemon's line-counting rule is gone — the preview's numbers have no defined meaning.",
  );
  assert.match(
    store,
    /normalised\.endsWith\("\\n"\) \? 1 : 0/,
    "the daemon's line-counting rule lost its trailing-newline adjustment — it would disagree with the TUI's rule.",
  );
  // And the daemon normalises CRLF, like the TUI does.
  assert.match(
    store,
    /replace\("\\r\\n", "\\n"\)/,
    "the daemon's line-counting rule does not normalise CRLF — a Windows file would count differently from the TUI's count.",
  );
});

// ═══════════════════════════════════════════════════════════════════
// 4. THE GATE IS NOT VACUOUS
// ═══════════════════════════════════════════════════════════════════

test("R388s: the gate is not vacuous — the preview really reaches the frame", () => {
  // ★ A GATE THAT MEASURES NOTHING IS NOT A GATE ★ This renders the
  // menu with NO preview at all and asserts that the footprint
  // numbers are ABSENT. So the "shows the footprint" assertions
  // above are measuring a real difference, not a constant.
  const lines = renderMenu([cp(0, null)]);
  const text = lines.join("\n");
  assert.ok(
    !/· ~\d+ lines/.test(text),
    "the frame showed a footprint even with no preview — so the footprint assertions above prove nothing. Frame:\n" +
      text,
  );
});
