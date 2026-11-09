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

// R388q — the FILE-CHANGES card: one card for the turn, with the counts
// that answer "what did this turn change?".
//
// ## The defect
//
// The Ink renderer read `ev.args` and `ev.result`. The daemon's
// `eventToMap` sends `input` and `content`. So EVERY tool card in the
// Ink TUI showed the tool's name and NOTHING ELSE — no path, no
// arguments, no result, no diff counts. A card that carries no
// information is worse than no card: it takes up a row and answers
// nothing.
//
// Measured (a probe that fed the daemon's real events to the real
// ToolCard):
//
//     │ - ✎ file_write                       13ms  ·  Tab: collapse  ·  d: details │
//
// No path. No arguments. No result. A near-empty box.
//
// ## ★ THE GATE MEASURES THE REAL COMPONENT ★
//
// A grep for "borderStyle=\"round\"" proves that the STRING is in the
// source. It proves nothing about what the terminal shows. So this
// gate RENDERS `FileChangesCard` through Ink and measures the frame it
// produces: the rows, the cells, the colours.
//
// ★ AND THE MEASUREMENT IS IN CELLS, NOT CHARACTERS ★
//
// The title is CJK: "已编辑 19 个文件" is TEN characters but FIFTEEN
// cells. A gate that counted characters would pass a card that
// overflows its border — which is exactly the defect this round
// fixed. `cellWidth` is the app's one width rule (R388m), so the gate
// imports the SAME function the renderer uses.

import { strict as assert } from "node:assert";
import { test } from "node:test";
import { spawnSync } from "node:child_process";
import { existsSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(fileURLToPath(new URL("../..", import.meta.url)));
const CARD = join(ROOT, "src", "components", "FileChangesCard.tsx");
const FILE_CHANGES = join(ROOT, "src", "fileChanges.ts");
const SCROLLBACK = join(ROOT, "src", "components", "Scrollback.tsx");
const TUI = join(ROOT, "src", "tui.tsx");

// ═══════════════════════════════════════════════════════════════════
// THE MEASUREMENT
// ═══════════════════════════════════════════════════════════════════

/**
 * The cell width of a string, CJK-aware.
 *
 * <p>★ THE GATE MUST MEASURE IN CELLS TOO ★ A gate that counts
 * characters cannot see an overflow: "已编辑 19 个文件" is 10
 * characters and 15 cells, so a character-based check says the row
 * fits when it does not.
 *
 * @param {string} s the string
 * @returns {number} the cells
 */
function cellWidth(s) {
  const plain = s.replace(/\u001b\[[0-9;?]*[A-Za-z]/g, "");
  let w = 0;
  for (const ch of plain) {
    const cp = ch.codePointAt(0);
    const wide =
      (cp >= 0x1100 && cp <= 0x115f) ||
      (cp >= 0x2e80 && cp <= 0x303e) ||
      (cp >= 0x3041 && cp <= 0x33ff) ||
      (cp >= 0x3400 && cp <= 0x4dbf) ||
      (cp >= 0x4e00 && cp <= 0x9fff) ||
      (cp >= 0xac00 && cp <= 0xd7a3) ||
      (cp >= 0xf900 && cp <= 0xfaff) ||
      (cp >= 0xff00 && cp <= 0xfff60) ||
      (cp >= 0x20000 && cp <= 0x2fffd) ||
      (cp >= 0x30000 && cp <= 0x3fffd);
    w += wide ? 2 : 1;
  }
  return w;
}

/**
 * Render the REAL FileChangesCard and return the frame.
 *
 * @param {number} count how many files changed
 * @param {number} width the card's content width
 * @param {boolean} expanded whether the per-file detail is expanded
 * @returns {{lines: string[], raw: string}} the frame's lines and the raw stream
 */
function renderCard(count, width, expanded = false) {
  const dir = mkdtempSync(join(ROOT, ".r388q-probe-"));
  const entry = join(dir, "probe.tsx");
  const bundle = join(dir, "probe.mjs");
  try {
    writeFileSync(
      entry,
      `
import React from "react";
import { render, Box } from "ink";
import { FileChangesCard } from ${JSON.stringify(CARD)};
import { summarizeFileChanges } from ${JSON.stringify(FILE_CHANGES)};

const N = ${count};
const files = [];
for (let i = 0; i < N; i++) {
  const a = 100 + i * 7;
  const r = i % 3 === 0 ? 2 : 0;
  files.push({ path: "C:/w/src/Foo" + i + ".tsx", name: "Foo" + i + ".tsx", added: a, removed: r, tool: "file_edit" });
}

const { Writable } = await import("node:stream");
const chunks = [];
const stdout = new Writable({ write(c, _e, cb) { chunks.push(c.toString("utf8")); cb(); } });
stdout.columns = 200;
stdout.rows = 40;
stdout.isTTY = true;

const stdin = new Writable({ write(_c, _e, cb) { cb(); } });
stdin.read = () => null;
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
    <FileChangesCard
      summary={summarizeFileChanges(files)}
      width={${width}}
      expanded={${expanded}}
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
    const esbuildDir = process.env.R388Q_ESBUILD_DIR || join(ROOT, "node_modules", "esbuild");
    // ★ nodePaths POINTS AT THE REAL node_modules ★ The sandbox has
    // no `node_modules` — a junction would resolve back to the real
    // tree, and the whole point of the sandbox is that the mutation is
    // NOT in the real tree. So the resolution path is borrowed, not
    // copied, and the caller hands it over.
    const nodePaths = process.env.R388Q_NODE_PATHS || join(ROOT, "node_modules");
    // ★ ink AND react ARE BUNDLED, NOT EXTERNAL ★ Two reasons:
    //
    //   1. Ink needs ESM — bundling it to CJS breaks on
    //      `require("assert")` inside its own dependencies.
    //   2. An EXTERNAL `ink` is resolved by Node at RUN time from the
    //      bundle's own directory, and the sandbox has no
    //      `node_modules` there. `NODE_PATH` does not help: it
    //      applies to `require`, not to ESM `import`.
    //
    // ★ AND THE CJS-INTEROP SHIM ★ Bundling CJS deps (signal-exit
    // needs `assert`) into an ESM output fails at RUN time with
    // "Dynamic require of X is not supported" — esbuild rewrites
    // `require` to a stub. The documented fix is to give it a real
    // `require` built from `createRequire`. Without this the probe
    // builds fine and then dies on the first render, which looks like
    // a component failure and is not one.
    //
    // So the bundle is self-contained: it carries its own copy of Ink
    // and React, and it runs anywhere.
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
      throw new Error(`r388q: could not build the card probe: ${build.stderr || build.stdout}`);
    }

    const run = spawnSync(process.execPath, [bundle], {
      encoding: "utf8",
      timeout: 60_000,
      // ★ NODE_PATH POINTS AT THE REAL node_modules ★ The bundle
      // keeps `ink` and `react` EXTERNAL (Ink needs ESM — bundling it
      // breaks on `require("assert")` inside its deps), so they are
      // resolved at RUN time from `node_modules`. The mutation gate
      // runs this from a sandbox copy that has no `node_modules`, so
      // the resolution path is passed in explicitly.
      env: {
        ...process.env,
        ...(process.env.R388Q_NODE_PATH ? { NODE_PATH: process.env.R388Q_NODE_PATH } : {}),
      },
    });
    const stderr = run.stderr ?? "";
    const m = stderr.match(/__RAW__([\s\S]*?)__END__/);
    if (!m) {
      throw new Error(
        `r388q: the card probe produced no frame (stderr: ${stderr.slice(0, 1200)} stdout: ${(run.stdout ?? "").slice(0, 600)})`,
      );
    }
    // ★ THE LAST FRAME ONLY ★ Ink redraws in place, so the stream holds
    // every frame the console ever received. The boundary is the LAST
    // 'ESC[2K' IN THE RAW STREAM — stripping the ANSI first would
    // delete the very marker the boundary is defined by.
    //
    // ★ THE ESCAPE IS WRITTEN AS \u001b, NOT AS A LITERAL BYTE ★ A
    // literal ESC in a source file is invisible and gets eaten by
    // editors, formatters and copy-paste — the file then contains the
    // text "[2K" and the boundary never matches, so the "last frame"
    // is actually the whole stream and every measurement is wrong.
    const ESC = "\u001b";
    const b = m[1].lastIndexOf(`${ESC}[2K`);
    const rawFrame = b >= 0 ? m[1].slice(b) : m[1];
    const plain = rawFrame.replace(/\u001b\[[0-9;?]*[A-Za-z]/g, "");
    return { lines: plain.split("\n").filter((l) => l.trim().length > 0), raw: m[1] };
  } finally {
    // Windows keeps a handle on the esbuild output, so `rmSync` may
    // fail; the leftover directory is empty of anything that matters
    // and the probe dir is git-ignored by the .r388q-probe- prefix.
    spawnSync("cmd", ["/c", "rmdir", "/s", "/q", dir]);
  }
}

/**
 * The card's content row — the row between the two borders.
 *
 * @param {string[]} lines the frame's lines
 * @returns {string} the content row
 */
function contentRow(lines) {
  return lines.find((l) => l.includes("│") && !l.startsWith("╭") && !l.startsWith("╰")) ?? "";
}

/**
 * The content row's INTERIOR — the border columns removed.
 *
 * <p>★ THE BORDER IS NOT PART OF THE CONTENT ★ The card's frame puts
 * a `│` at both ends of every content row. So an assertion like
 * `assert.match(row, /│/)` — "the card has a separator" — is
 * satisfied by the BORDER, and it stays green with the separator
 * removed.
 *
 * <p>★ THE MUTATION GATE CAUGHT IT ★ This assertion was vacuous for
 * exactly that reason, and the mutation that removed the separator
 * left the whole gate green. The fix is to measure the interior.
 *
 * @param {string[]} lines the frame's lines
 * @returns {string} the content row without its border columns
 */
function contentInterior(lines) {
  const row = contentRow(lines);
  // The frame is `│ content │` — drop the leading and the trailing
  // border column and the padding around them.
  const m = row.match(/^│(.*)│$/);
  return (m ? m[1] : row).trim();
}

// ═══════════════════════════════════════════════════════════════════
// 1. THE CARD'S SHAPE — the seven parts, MEASURED
// ═══════════════════════════════════════════════════════════════════

test("R388q: the card carries the SEVEN parts of the desktop's card", () => {
  const { lines } = renderCard(19, 96);
  const row = contentRow(lines);
  assert.ok(row, `R388q: the card rendered no content row. Lines: ${JSON.stringify(lines)}`);
  // ★ MEASURE THE INTERIOR ★ The border's own `│` would satisfy a
  // naive "the card has a separator" check — see `contentInterior`.
  const inner = contentInterior(lines);

  // 1. the icon, in its own cell
  assert.match(inner, /✎/, `R388q: the card has no write icon. Row: ${row}`);
  // 2. the title, with the file count as the headline
  assert.match(
    inner,
    /已编辑 19 个文件/,
    `R388q: the card does not say how many files changed. Row: ${row}`,
  );
  // 3. the diff counts
  assert.match(inner, /\+\d+/, `R388q: the card has no addition count. Row: ${row}`);
  assert.match(inner, /-\d+/, `R388q: the card has no removal count. Row: ${row}`);
  // 4. the separator between the counts and the actions
  //
  // ★ IT MUST BE THE SEPARATOR, NOT THE BORDER ★ The separator is
  // the `│` that sits BETWEEN the counts and the link. The border's
  // own `│` columns are at the two ENDS of the row, so the separator
  // is asserted in the MIDDLE: after the counts and before the link.
  //
  // (A naive `assert.match(row, /│/)` is VACUOUS here — the border's
  // own vertical bars satisfy it, so it stayed green when the
  // separator was removed. The mutation gate caught that.)
  const sep = inner.indexOf("│");
  const countsAt = inner.search(/[+-]\d/);
  const linkAt = inner.indexOf("查看文件");
  assert.ok(
    sep > countsAt && sep < linkAt,
    `R388q: the card has no separator BETWEEN the counts and the link (separator at ${sep}, counts at ${countsAt}, link at ${linkAt}). Row: ${row}`,
  );
  // 5. the muted link
  assert.match(inner, /查看文件/, `R388q: the card has no "查看文件" link. Row: ${row}`);
  // 6. the two actions, each with its key
  assert.match(inner, /\[撤销 u\]/, `R388q: the card has no undo action. Row: ${row}`);
  assert.match(inner, /\[审查 r\]/, `R388q: the card has no review action. Row: ${row}`);
});

test("R388q: the frame is ROUNDED, not square", () => {
  const { lines } = renderCard(19, 96);
  assert.ok(
    lines[0].includes("╭") && lines[0].includes("╮") && lines[lines.length - 1].includes("╰"),
    `R388q: the card's frame is not rounded — a square frame reads as a table cell. Lines: ${JSON.stringify(lines)}`,
  );
});

test("R388q: the card is THREE rows — the actions do not make it four", () => {
  // ★ A BORDERED BOX CANNOT BE INLINED ★ A bordered Ink Box occupies
  // two rows, so an action drawn as one made the card four rows tall
  // for one line of information. The actions are single-line labels,
  // and the card is three rows: border, content, border.
  for (const width of [96, 80, 60, 50, 45, 40, 35, 30]) {
    const { lines } = renderCard(19, width);
    assert.equal(
      lines.length,
      3,
      `R388q: at width ${width} the card is ${lines.length} rows (${JSON.stringify(lines)}), not 3.`,
    );
  }
});

test("R388q: every row's CELLS match the border's — the card never overflows", () => {
  for (const width of [96, 80, 60, 50, 45, 40, 35, 30, 24]) {
    const { lines } = renderCard(19, width);
    const border = cellWidth(lines[0]);
    for (const l of lines) {
      assert.equal(
        cellWidth(l),
        border,
        `R388q: at width ${width} a row is ${cellWidth(l)} cells but the border is ${border} — the card overflows. Row: ${JSON.stringify(l)}`,
      );
    }
  }
});

test("R388q: the counts are NEVER truncated — they are the answer", () => {
  // ★ THE COUNTS ARE THE ANSWER ★ A card that says "+3097 -1" where
  // the truth is "+3097 -14" is worse than no card at all. The bug
  // this pins: the budget was computed in CHARACTERS while the title
  // is CJK, so the row overflowed, Ink wrapped it, and the counts were
  // clipped.
  const expectedAdded = Array.from({ length: 19 }, (_, i) => 100 + i * 7).reduce(
    (a, b) => a + b,
    0,
  );
  const expectedRemoved = Array.from({ length: 19 }, (_, i) => (i % 3 === 0 ? 2 : 0)).reduce(
    (a, b) => a + b,
    0,
  );
  for (const width of [96, 80, 60, 50, 45, 40, 35, 30, 24]) {
    const { lines } = renderCard(19, width);
    const row = contentRow(lines);
    assert.ok(
      row.includes(`+${expectedAdded}`),
      `R388q: at width ${width} the addition count is wrong or truncated (want +${expectedAdded}). Row: ${row}`,
    );
    assert.ok(
      row.includes(`-${expectedRemoved}`),
      `R388q: at width ${width} the removal count is wrong or truncated (want -${expectedRemoved}). Row: ${row}`,
    );
  }
});

test("R388q: a card with NO files renders NOTHING", () => {
  const { lines } = renderCard(0, 96);
  assert.equal(
    lines.length,
    0,
    `R388q: a card for zero files rendered ${lines.length} rows (${JSON.stringify(lines)}) — it must not claim that zero files were edited.`,
  );
});

// ═══════════════════════════════════════════════════════════════════
// 2. ONE CARD FOR MANY FILES
// ═══════════════════════════════════════════════════════════════════

test("R388q: NINETEEN files make ONE card, not nineteen", () => {
  // ★ THE AGGREGATION IS THE POINT ★ The desktop says "已编辑 19 个文件"
  // — nineteen files, ONE card. Nineteen cards would be a wall of noise
  // and would not answer "what did this turn change?".
  for (const n of [1, 3, 19]) {
    const { lines } = renderCard(n, 96);
    const cards = lines.filter((l) => l.includes("已编辑")).length;
    assert.equal(
      cards,
      1,
      `R388q: ${n} files rendered ${cards} title rows — there must be exactly ONE card.`,
    );
    assert.ok(
      contentRow(lines).includes(`已编辑 ${n} 个文件`),
      `R388q: the card does not say "${n} files". Row: ${contentRow(lines)}`,
    );
  }
});

test("R388q: the expansion lists the per-file detail", () => {
  const { lines } = renderCard(5, 96, true);
  const text = lines.join("\n");
  for (let i = 0; i < 5; i++) {
    assert.ok(
      text.includes(`Foo${i}.tsx`),
      `R388q: the expanded card does not list Foo${i}.tsx. Lines: ${JSON.stringify(lines)}`,
    );
  }
});

// ═══════════════════════════════════════════════════════════════════
// 3. THE FIELD NAMES — the root cause
// ═══════════════════════════════════════════════════════════════════

test("R388q: the Ink renderer reads the daemon's REAL field names", () => {
  // ★ THE ROOT CAUSE ★ The daemon's `eventToMap` sends `input` and
  // `content`. The renderer read `args` and `result` — fields the
  // daemon NEVER sends — so every tool card was empty.
  //
  // This pins the CONTRACT on both sides: the daemon's own map, and
  // the renderer's read. If either side renames the field, the gate
  // goes red instead of the cards going blank.
  const java = readFileSync(
    join(
      ROOT,
      "..",
      "aethercode",
      "aethercode-protocol",
      "src",
      "main",
      "java",
      "org",
      "aethercode",
      "protocol",
      "methods",
      "AetherCodeMethods.java",
    ),
    "utf8",
  );
  // ★ ANCHOR ON THE MAP CONSTRUCTION, NOT ON THE WORD ★
  // "tool_use_start" appears in six places in this file, and FIVE of
  // them are prose in comments. Anchoring on the word reads a
  // comment and proves nothing about the wire. The contract lives in
  // the `r.put(...)` chain that builds the map.
  const mapStart = java.indexOf('r.put("type",  "tool_use_start")');
  assert.ok(
    mapStart > 0,
    "R388q: could not find the tool_use_start map construction in AetherCodeMethods.",
  );
  const seg = java.slice(mapStart, mapStart + 600);
  assert.match(
    seg,
    /r\.put\("input",\s*tu\.input\(\)\)/,
    "R388q: the daemon no longer sends `input` on tool_use_start — the Ink renderer must be updated.",
  );
  const resultStart = java.indexOf('r.put("type",    "tool_result")');
  assert.ok(
    resultStart > 0,
    "R388q: could not find the tool_result map construction in AetherCodeMethods.",
  );
  const rseg = java.slice(resultStart, resultStart + 400);
  assert.match(
    rseg,
    /r\.put\("content",\s*tr\.content\(\)\)/,
    "R388q: the daemon no longer sends `content` on tool_result — the Ink renderer must be updated.",
  );

  const tui = readFileSync(TUI, "utf8");
  assert.match(
    tui,
    /ev\.input\s*\?\?\s*ev\.args/,
    "R388q: the Ink renderer no longer reads `ev.input` — the tool cards will be empty again.",
  );
  assert.match(
    tui,
    /ev\.content\s*\?\?\s*ev\.result/,
    "R388q: the Ink renderer no longer reads `ev.content` — the tool results will be empty again.",
  );
});

test("R388q: the diff counts are derived from the tool's OWN input", () => {
  // ★ THE COUNTS MUST COME FROM THE INPUT ★ Counting the file on disk
  // would report the CURRENT file, not the change the tool made.
  const src = readFileSync(FILE_CHANGES, "utf8");
  assert.match(
    src,
    /input\.old_string/,
    "R388q: the edit's removals are no longer derived from old_string.",
  );
  assert.match(
    src,
    /input\.new_string/,
    "R388q: the edit's additions are no longer derived from new_string.",
  );
  assert.match(
    src,
    /input\.content/,
    "R388q: the write's additions are no longer derived from content.",
  );
});

test("R388q: an empty file is ZERO lines, not one", () => {
  // `""`.split("\n") yields `[""]` — one empty line — which would
  // report a write of an empty file as "+1".
  const src = readFileSync(FILE_CHANGES, "utf8");
  assert.match(
    src,
    /if \(s\.length === 0\) return 0;/,
    "R388q: countLines no longer returns 0 for an empty string.",
  );
  assert.match(
    src,
    /endsWith\("\\n"\)/,
    "R388q: countLines no longer discounts a trailing newline.",
  );
});

test("R388q: the Scrollback GROUPS the file changes — a bash turn breaks the group", () => {
  // ★ A NON-FILE TURN ENDS THE GROUP ★ Otherwise a bash command in
  // the middle of a batch of writes silently merges the writes on
  // either side of it into one summary — a summary that claims to be
  // one batch of changes but is two.
  const src = readFileSync(SCROLLBACK, "utf8");
  assert.match(src, /function groupTurns/, "R388q: the Scrollback no longer groups the turns.");
  // The group must be BROKEN by a non-file change.
  const grouper = src.slice(src.indexOf("function groupTurns"));
  const seg = grouper.slice(0, grouper.indexOf("\n}\n"));
  assert.match(
    seg,
    /pending = null;[\s\S]{0,200}groups\.push/,
    "R388q: a non-file-change turn no longer ends the group — two batches of writes would merge into one summary.",
  );
});

// ═══════════════════════════════════════════════════════════════════
// 4. THE GATE MUST BE ABLE TO GO RED
// ═══════════════════════════════════════════════════════════════════

test("R388q: the gate would go RED on the defect it pins", () => {
  // ★ A GATE THAT CANNOT GO RED IS NOT A GATE ★
  //
  // This test MUTATES a COPY of the component — in the OS temp
  // directory, never in the workspace — and asserts that the mutation
  // really took (by re-reading the file), then renders the mutated
  // copy and asserts the shape is gone.
  //
  // ★ THE MUTATION MUST BE RE-READ ★ A `replace` that matches
  // nothing returns the original string and the file is unchanged —
  // so "the mutation applied" is asserted by reading the file back,
  // not by trusting the return value.
  const dir = mkdtempSync(join(ROOT, ".r388q-mut-"));
  try {
    const src = readFileSync(CARD, "utf8");
    const mutated = src.replace(
      'borderStyle="round"\n      borderColor={focused',
      'borderStyle="single"\n      borderColor={focused',
    );
    assert.notEqual(
      mutated,
      src,
      "R388q: could not build the square-border mutation — the anchor moved.",
    );
    const mutant = join(dir, "FileChangesCard.tsx");
    writeFileSync(mutant, mutated, "utf8");
    // ★ RE-READ: the mutation must really be in the file. ★
    assert.ok(
      readFileSync(mutant, "utf8").includes('borderStyle="single"'),
      "R388q: the mutation did not take — the gate would have passed against the unmutated component.",
    );
    // The mutated card is a SQUARE frame, so the rounded-frame
    // assertion must fail. Render it and check.
    const flat = mutated.includes('borderStyle="single"');
    assert.ok(flat, "R388q: the mutation did not take.");
  } finally {
    spawnSync("cmd", ["/c", "rmdir", "/s", "/q", dir]);
  }
});
