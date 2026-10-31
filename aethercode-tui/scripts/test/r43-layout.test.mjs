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

// R43: layout presets.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// ----- 1. Compile state.ts and import --------------------------------
const tmp = join(root, "tmp-r43-tsc");
const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
{
  if (existsSync(tmp)) {
    spawnSync(
      process.platform === "win32" ? "cmd" : "rm",
      process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", tmp] : ["-rf", tmp],
    );
  }
  const tscArgs = [
    "--outDir",
    tmp,
    "--target",
    "ES2022",
    "--module",
    "ES2022",
    "--moduleResolution",
    "bundler",
    "--esModuleInterop",
    "true",
    "--skipLibCheck",
    "true",
    "--rootDir",
    join(root, "src"),
  ];
  const r = spawnSync(`"${tscBin}"`, [...tscArgs, `"${join(root, "src", "state.ts")}"`], {
    encoding: "utf-8",
    shell: true,
  });
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
    process.exit(1);
  }
}
const stateMod = await import("file:///" + join(tmp, "state.js").replace(/\\/g, "/"));
const { reducer, INITIAL } = stateMod;

test("R43: INITIAL.layout is 'full'", () => {
  assert.equal(INITIAL.layout, "full");
});

test("R43: setLayout switches layout", () => {
  let s = reducer(INITIAL, { type: "setLayout", layout: "minimal" });
  assert.equal(s.layout, "minimal");
  s = reducer(s, { type: "setLayout", layout: "focus" });
  assert.equal(s.layout, "focus");
  s = reducer(s, { type: "setLayout", layout: "full" });
  assert.equal(s.layout, "full");
});

// ----- 2. Source code assertions -------------------------------------

test("R43: state.ts has layout field + setLayout action", () => {
  const state = readFileSync(join(root, "src", "state.ts"), "utf-8");
  assert.match(state, /layout: "full" \| "minimal" \| "focus"/);
  assert.match(state, /setLayout/);
});

test("R43: tui.tsx has __LAYOUT__ handler + conditional Header/StatusBar", () => {
  const tui = readFileSync(join(root, "src", "tui.tsx"), "utf-8");
  assert.match(tui, /__LAYOUT__:/);
  assert.match(tui, /setLayout/);
  // R43: header is hidden in "focus" and "minimal" layouts.
  assert.match(tui, /state\.layout !== "focus" && state\.layout !== "minimal" \? \(/);
  // R43: statusbar is only shown in "full" layout.
  assert.match(tui, /state\.layout === "full" \?[\s(]*<StatusBar/);
});

test("R43: commands.ts has /layout slash command", () => {
  const cmds = readFileSync(join(root, "src", "commands.ts"), "utf-8");
  assert.match(cmds, /case "layout":/);
  assert.match(cmds, /__LAYOUT__:/);
});

// ----- 3. Build smoke test -------------------------------------------

test("R43: TypeScript compile of new code is clean", () => {
  const tmp2 = join(root, "tmp-r43-tsc2");
  if (existsSync(tmp2)) {
    spawnSync(
      process.platform === "win32" ? "cmd" : "rm",
      process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", tmp2] : ["-rf", tmp2],
    );
  }
  const tscArgs = [
    "--outDir",
    tmp2,
    "--target",
    "ES2022",
    "--module",
    "ES2022",
    "--moduleResolution",
    "bundler",
    "--jsx",
    "react",
    "--esModuleInterop",
    "true",
    "--skipLibCheck",
    "true",
    "--rootDir",
    join(root, "src"),
  ];
  const r = spawnSync(`"${tscBin}"`, [...tscArgs, '"' + join(root, "src", "tui.tsx") + '"'], {
    encoding: "utf-8",
    shell: true,
  });
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
  }
  assert.equal(r.status, 0, "tsc compile failed for R43 tui");
});
