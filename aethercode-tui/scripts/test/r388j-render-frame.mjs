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

// R388j — render a panel frame and RECONSTRUCT THE SCREEN.
//
// ## Why this file exists
//
// Ink writes a line's trailing padding as `ESC[nX` (erase n
// characters) + `ESC[nC` (cursor forward n columns), not as literal
// spaces. A measurement that strips every ANSI sequence DELETES
// those two — so each line looks 74 cells short and the frame's
// right border appears on a different column per line.
//
// That is not a rendering defect. That is a MEASUREMENT defect, and
// it cost several rounds of work chasing a bug that was not there.
//
// → **Lesson 1311 — a measurement that deletes the thing it
// measures cannot see the thing it measures.** An ANSI stripper is
// for text; a terminal is not a text file.
//
// So this module does what a terminal does: it interprets the cursor
// movements, and it renders the result as a grid of cells.
//
// ## The screen model
//
// A terminal is a grid. `ESC[<n>X` erases n cells at the cursor
// WITHOUT moving it; `ESC[<n>C` moves the cursor forward n columns;
// `ESC[K` erases to the end of the line; `ESC[2J` clears the
// screen. Ink's `log-update` also uses `ESC[<n>A` (cursor up) and
// `ESC[<n>G` (cursor to column) to redraw in place.
//
// The reconstruction below implements exactly those.

import { spawnSync } from "node:child_process";
import { existsSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

/**
 * Reconstruct a screen from a raw terminal byte stream.
 *
 * @param {string} raw the bytes the program wrote to the terminal
 * @param {number} [cols] the terminal's width. When given, a row that
 *   is WIDER than the terminal WRAPS onto the next row — which is
 *   what a real terminal does, and what makes an over-wide frame
 *   visible.
 *
 *   ★ WITHOUT THE WIDTH, THE MODEL DELETES THE OVERFLOW ★ An
 *   unbounded row swallows the characters past the terminal's edge, so
 *   an over-wide frame looks FINE in the model and is broken in the
 *   terminal. That is a measurement that deletes the thing it
 *   measures (Lesson 1311), and it hid a real defect for three
 *   rounds: the status bar's 60-cell rule overflowed a 40-column
 *   terminal, the terminal wrapped it into an extra row, and the
 *   model quietly clipped it back.
 *
 * @returns {string[]} one string per screen row
 */
export function reconstructScreen(raw, cols = 0) {
  const rows = [];
  let cy = 0;
  let cx = 0;

  const rowAt = (y) => {
    while (rows.length <= y) rows.push([]);
    return rows[y];
  };
  const put = (ch) => {
    // ★ A REAL TERMINAL WRAPS ★
    // Writing past the last column moves to the next row and
    // continues there. Without this the overflow vanishes.
    if (cols > 0 && cx >= cols) {
      cx = 0;
      cy += 1;
    }
    rowAt(cy)[cx] = ch;
    cx += 1;
  };

  let i = 0;
  while (i < raw.length) {
    const c = raw[i];
    if (c === "\u001b") {
      if (raw[i + 1] === "[") {
        let j = i + 2;
        while (j < raw.length && !/[A-Za-z]/.test(raw[j])) j += 1;
        const params = raw.slice(i + 2, j);
        const final = raw[j];
        const nums = params
          .split(";")
          .filter((s) => s.length)
          .map(Number);
        switch (final) {
          case "A":
            cy = Math.max(0, cy - (nums[0] || 1));
            break;
          case "B":
            cy += nums[0] || 1;
            break;
          case "C":
            cx += nums[0] || 1;
            break;
          case "D":
            cx = Math.max(0, cx - (nums[0] || 1));
            break;
          case "E":
            cy += nums[0] || 1;
            cx = 0;
            break;
          case "F":
            cy = Math.max(0, cy - (nums[0] || 1));
            cx = 0;
            break;
          case "G":
            cx = (nums[0] || 1) - 1;
            break;
          case "H":
          case "f":
            cy = (nums[0] || 1) - 1;
            cx = (nums[1] || 1) - 1;
            break;
          case "J":
            if ((nums[0] || 0) === 2) {
              rows.length = 0;
              cy = 0;
              cx = 0;
            }
            break;
          case "K": {
            const r = rowAt(cy);
            if ((nums[0] || 0) === 2) r.length = 0;
            else if ((nums[0] || 0) === 0) r.length = cx;
            break;
          }
          case "X": {
            // erase n characters — does NOT move the cursor
            const n = nums[0] || 1;
            const r = rowAt(cy);
            for (let k = 0; k < n; k += 1) r[cx + k] = " ";
            break;
          }
          default:
            break;
        }
        i = j + 1;
        continue;
      }
      if (raw[i + 1] === "]") {
        let j = i + 2;
        while (j < raw.length && raw[j] !== "\u0007") j += 1;
        i = j + 1;
        continue;
      }
      i += 2;
      continue;
    }
    if (c === "\r") {
      cx = 0;
      i += 1;
      continue;
    }
    if (c === "\n") {
      cy += 1;
      cx = 0;
      i += 1;
      continue;
    }
    if (c === "\u0007") {
      i += 1;
      continue;
    }
    put(c);
    i += 1;
  }

  return rows.map((r) => r.join("").replace(/\s+$/, ""));
}

/**
 * A temp directory INSIDE the package.
 *
 * ★ THE BUNDLE MUST RESOLVE `react` AND `ink`. ★
 *
 * The probe bundle is ESM with `react`/`ink` marked external, so
 * Node has to find them at RUN time. A temp directory under the
 * OS temp dir has no `node_modules` above it, so the import fails
 * with `ERR_MODULE_NOT_FOUND` — and the gate would then report a
 * broken probe instead of a broken panel.
 *
 * The directory is inside the package, so Node walks up to the
 * package's own `node_modules`. It is removed afterwards.
 */
function makeProbeDir(root) {
  return mkdtempSync(join(root, "dist", ".r388j-probe-"));
}

/**
 * Compile the REAL `PanelBody.tsx` and hand back its `fitRow`.
 *
 * The gate tests the SHIPPED helper, not a re-implementation of it —
 * a gate that tests its own copy of the truth proves nothing.
 *
 * @param {string} root the TUI package root
 * @returns {Promise<(text: string, width: number, ellipsis?: string) => string>}
 */
export async function fitRowOf(root = defaultRoot()) {
  const dir = makeProbeDir(root);
  const entry = join(dir, "probe.ts");
  const bundle = join(dir, "probe.mjs");

  try {
    writeFileSync(
      entry,
      `export { fitRow, blank } from ${JSON.stringify(join(root, "src", "components", "PanelBody.tsx"))};\n`,
      "utf8",
    );

    const build = spawnSync(
      process.execPath,
      [
        "-e",
        `const {build}=require(${JSON.stringify(join(root, "node_modules", "esbuild"))});
build({entryPoints:[${JSON.stringify(entry)}],bundle:true,outfile:${JSON.stringify(bundle)},platform:'node',format:'esm',external:['ink','react'],jsx:'automatic',loader:{'.ts':'ts','.tsx':'tsx'}}).catch(e=>{console.error(e.message);process.exit(1)});`,
      ],
      { cwd: root, encoding: "utf8" },
    );
    if (build.status !== 0 || !existsSync(bundle)) {
      throw new Error(`r388j: could not build the fitRow probe: ${build.stderr || build.stdout}`);
    }
    const mod = await import(pathToFileURL(bundle).href);
    return mod.fitRow;
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

function defaultRoot() {
  return join(dirname(fileURLToPath(import.meta.url)), "..", "..");
}

/**
 * Render the REAL panel shell with a REAL panel body and return the
 * reconstructed frame.
 *
 * The render happens under a ConPTY (a real TTY), because Ink's
 * output differs off-TTY: `process.stdout.columns` is undefined
 * there and Ink falls back to 80 columns, which is a DIFFERENT
 * layout and would test the wrong thing.
 *
 * @param {{root: string}} opts
 * @returns {string[]} the frame's lines
 */
export function renderPanelFrame({ root }) {
  const dir = makeProbeDir(root);
  const entry = join(dir, "frame.tsx");
  const bundle = join(dir, "frame.mjs");

  try {
    writeFileSync(
      entry,
      `import React from "react";
import { render } from "ink";
import { Box, Text } from "ink";
import { PanelBody, fitRow, blank } from ${JSON.stringify(join(root, "src", "components", "PanelBody.js"))};

const width = 100;
const inner = Math.max(20, width - 4);
const rule = (n: number): string => "─".repeat(Math.max(4, n));

const Shell = () => (
  <Box flexDirection="column" width={width} borderStyle="round" paddingX={1}>
    <Text>
      <Text color="cyan" bold>{fitRow("◆ Threads", inner - 4)}</Text>
      <Text dimColor> esc</Text>
    </Text>
    <Text>{rule(inner)}</Text>
    <Box flexDirection="column" width={inner}>
      <PanelBody width={inner} title="Sessions in AetherCode" subtitle="D:\\\\work\\\\engine">
        <Text>{fitRow("  no sessions recorded under AetherCode", inner)}</Text>
        <Text>{blank(inner)}</Text>
        <Text color="cyan">{fitRow("a  show every session (not just this directory)", inner)}</Text>
      </PanelBody>
    </Box>
    <Text>{rule(inner)}</Text>
    <Text>{fitRow("! 0 of 50 sessions are recorded under AetherCode", inner)}</Text>
  </Box>
);

const { unmount } = render(<Shell />);
setTimeout(() => unmount(), 250);
`,
      "utf8",
    );

    const build = spawnSync(
      process.execPath,
      [
        "-e",
        `const {build}=require(${JSON.stringify(join(root, "node_modules", "esbuild"))});
build({entryPoints:[${JSON.stringify(entry)}],bundle:true,outfile:${JSON.stringify(bundle)},platform:'node',format:'esm',external:['ink','react'],jsx:'automatic',loader:{'.ts':'ts','.tsx':'tsx'}}).catch(e=>{console.error(e.message);process.exit(1)});`,
      ],
      { cwd: root, encoding: "utf8" },
    );
    if (build.status !== 0 || !existsSync(bundle)) {
      throw new Error(`r388j: could not build the frame probe: ${build.stderr || build.stdout}`);
    }

    const run = spawnSync("conhost.exe", ["--headless", "--", "cmd", "/c", "node", bundle], {
      encoding: "utf8",
      timeout: 20_000,
    });
    const raw = (run.stdout ?? "") + "";
    const screen = reconstructScreen(raw);

    const frames = [];
    let cur = [];
    for (const line of screen) {
      if (line.includes("│") || line.includes("╭") || line.includes("╰")) cur.push(line);
      else {
        if (cur.length > 4) frames.push(cur);
        cur = [];
      }
    }
    if (cur.length > 4) frames.push(cur);
    return frames[frames.length - 1] ?? [];
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}
