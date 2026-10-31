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

// R196 (Phase 5 R4): pure-function tests for the ThreadSelector.
//
// We use `node --test` with a runtime compile of just the helper
// files. The helpers are:
//   - chooseCwd       (ThreadSelector.tsx)
//   - formatThreadRow (ThreadSelector.tsx)
//
// The tests are runtime assertions, not source-code grep, so we
// get real coverage of the cwd-switch decision + the row label.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdirSync, writeFileSync, unlinkSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

const tmp = join(root, "tmp-r196-threads-tsc");
const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
if (existsSync(tmp)) {
  spawnSync(
    process.platform === "win32" ? "cmd" : "rm",
    process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", tmp] : ["-rf", tmp],
  );
}
mkdirSync(tmp, { recursive: true });
// R363: the shim lives in `tmp/`, never in `src/`. Writing it into
// `src/components/` made the full-project `tsc --noEmit` in
// phase-7-t7-10.test.mjs race us (TS6053 in the write→unlink window);
// see the identical note in r196-notifications.test.mjs.
const shimPath = join(tmp, "_r196_threads_helpers.ts");
const shim = [
  `export { chooseCwd, formatThreadRow } from "../src/components/ThreadSelector.js";`,
  "",
].join("\n");
writeFileSync(shimPath, shim, "utf-8");

const tscArgs = [
  "--outDir",
  join(tmp, "out"),
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
  root,
];
const r = spawnSync(`"${tscBin}"`, [...tscArgs, `"${shimPath}"`], {
  encoding: "utf-8",
  shell: true,
});
if (r.status !== 0) {
  console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
  process.exit(1);
}
const helpers = await import(
  "file:///" +
    join(tmp, "out", "tmp-r196-threads-tsc", "_r196_threads_helpers.js").replace(/\\/g, "/")
);
const { chooseCwd, formatThreadRow } = helpers;

// ----- 1. chooseCwd ------------------------------------------------

test("R196: chooseCwd — same cwd → stay", () => {
  assert.equal(chooseCwd("/work/proj", "/work/proj"), "stay");
});

test("R196: chooseCwd — different cwd → switch", () => {
  assert.equal(chooseCwd("/work/other", "/work/proj"), "switch");
});

test("R196/R370: chooseCwd folds case on Windows, compares literally elsewhere", () => {
  // R370 changed this. The test used to be:
  //
  //   test("chooseCwd — case-sensitive (Windows shares case-insensitive
  //         paths but we compare literally)", () => {
  //     assert.equal(chooseCwd("/Work/Proj", "/work/proj"), "switch");
  //   });
  //
  // i.e. it PINNED THE BUG and named the bug in its own title. On
  // Windows `/Work/Proj` and `/work/proj` are the same directory, so
  // answering "switch" told the user their project was about to move
  // when it was not — and the host, which gated `switchProject` on
  // `!sameCwd(...)`, then correctly declined to move it. The label and
  // the action disagreed.
  //
  // `chooseCwd` now reads `sameCwd` from `src/cwdMatch.ts`, which
  // case-folds on win32 only. A POSIX filesystem is case-sensitive, so
  // the literal comparison is still correct there and this assertion
  // must stay platform-aware rather than simply being deleted.
  const expected = process.platform === "win32" ? "stay" : "switch";
  assert.equal(chooseCwd("/Work/Proj", "/work/proj"), expected);

  // Different directories must differ on every platform — the fix must
  // not have turned case folding into "everything matches".
  assert.equal(chooseCwd("/work/other", "/work/proj"), "switch");
});

test("R370: chooseCwd ignores a trailing separator", () => {
  assert.equal(chooseCwd("/work/proj/", "/work/proj"), "stay");
  assert.equal(chooseCwd("/work/proj", "/work/proj/"), "stay");
});

// ----- 2. formatThreadRow ------------------------------------------

test("R196: formatThreadRow — basic row", () => {
  const t = {
    threadId: "t-1234",
    agentName: "code-reviewer",
    messages: 5,
    createdAt: "2026-08-01",
    updatedAt: "2026-08-02",
    gitBranch: undefined,
    prompt: "review this",
    cwd: "/work/proj",
  };
  assert.equal(formatThreadRow(t, false), "  t-1234  code-reviewer  5 msgs");
});

test("R196: formatThreadRow — highlighted row", () => {
  const t = {
    threadId: "t-1234",
    agentName: "code-reviewer",
    messages: 5,
    createdAt: "2026-08-01",
    updatedAt: "2026-08-02",
    gitBranch: undefined,
    prompt: "review this",
    cwd: "/work/proj",
  };
  assert.equal(formatThreadRow(t, true), "▶ t-1234  code-reviewer  5 msgs");
});

test("R196: formatThreadRow — with branch", () => {
  const t = {
    threadId: "t-1234",
    agentName: "code-reviewer",
    messages: 5,
    createdAt: "2026-08-01",
    updatedAt: "2026-08-02",
    gitBranch: "feature/auth",
    prompt: "review this",
    cwd: "/work/proj",
  };
  assert.equal(formatThreadRow(t, true), "▶ t-1234  code-reviewer  5 msgs · feature/auth");
});

try {
  unlinkSync(shimPath);
} catch {
  /* ignore */
}
