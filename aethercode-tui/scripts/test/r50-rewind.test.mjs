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

// R50: edit/redo (rewind to a prior user message).

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// ----- 1. Compile state.ts and import --------------------------------
const tmp = join(root, "tmp-r50-tsc");
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

test("R50: INITIAL.rewindTarget is null", () => {
  assert.equal(INITIAL.rewindTarget, null);
});

test("R50: setRewindTarget sets the target", () => {
  const s = reducer(INITIAL, { type: "setRewindTarget", target: 3 });
  assert.equal(s.rewindTarget, 3);
  const s2 = reducer(s, { type: "setRewindTarget", target: null });
  assert.equal(s2.rewindTarget, null);
});

// ----- 2. Source code assertions -------------------------------------

test("R50: tui.tsx has Ctrl-Z binding that calls rewind RPC", () => {
  const tui = readFileSync(join(root, "src", "tui.tsx"), "utf-8");
  assert.match(tui, /key\.ctrl && \(input === "z" \|\| input === "Z"\)/);
  assert.match(tui, /client\.request\("rewind"/);
  assert.match(tui, /rewound to #/);
});

test("R50: commands.ts has /rewind slash command", () => {
  const cmds = readFileSync(join(root, "src", "commands.ts"), "utf-8");
  assert.match(cmds, /case "rewind":/);
  assert.match(cmds, /rpcMethod: "rewind"/);
  assert.match(cmds, /rpcParams: \{ target: n - 1 \}/);
});

test("R50: state.ts has rewindTarget field + setRewindTarget action", () => {
  const state = readFileSync(join(root, "src", "state.ts"), "utf-8");
  assert.match(state, /rewindTarget: number \| null/);
  assert.match(state, /setRewindTarget/);
});
