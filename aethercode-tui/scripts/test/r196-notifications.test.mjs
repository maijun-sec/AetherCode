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

// R196 (Phase 5 R4): pure-function tests for the Notification screens.
//
// We use `node --test` with a runtime compile of just the helper
// files. The helpers are:
//   - buildActionResult / pendingCount                  (NotificationCenter.tsx)
//   - buildDetailResult / formatDetailTitle             (NotificationDetail.tsx)
//   - labelFor / flipToggle / defaultToggleState / countEnabled (NotificationSettings.tsx)
//
// The tests are runtime assertions, not source-code grep.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdirSync, writeFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

const tmp = join(root, "tmp-r196-notif-tsc");
const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
if (existsSync(tmp)) {
  spawnSync(
    process.platform === "win32" ? "cmd" : "rm",
    process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", tmp] : ["-rf", tmp],
  );
}
mkdirSync(tmp, { recursive: true });
//
// R363 — the shim lives in the TEMP dir, not in `src/`.
//
// It used to be written to `src/components/_r196_notif_helpers.ts` and
// `unlinkSync`'d at the bottom of this file. That made the full-project
// `tsc --noEmit` in `phase-7-t7-10.test.mjs` race us: `node --test`
// runs files in parallel, so that check globbed `src/` and hit
// TS6053 "File ... not found" in the window between our write and our
// delete. A test that mutates the directory another test compiles is a
// test-ordering landmine — the fix is to stop mutating `src/` at all.
//
// `--rootDir` therefore moves up to the package root so the shim can
// import `./src/components/...` by relative path.
const shimPath = join(tmp, "_r196_notif_helpers.ts");
const shim = [
  `export { buildActionResult, pendingCount } from "../src/components/NotificationCenter.js";`,
  `export { buildDetailResult, formatDetailTitle } from "../src/components/NotificationDetail.js";`,
  `export { labelFor, flipToggle, defaultToggleState, countEnabled, WARNING_TOGGLES } from "../src/components/NotificationSettings.js";`,
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
  "file:///" + join(tmp, "out", "tmp-r196-notif-tsc", "_r196_notif_helpers.js").replace(/\\/g, "/")
);
const {
  buildActionResult,
  pendingCount,
  buildDetailResult,
  formatDetailTitle,
  labelFor,
  flipToggle,
  defaultToggleState,
  countEnabled,
  WARNING_TOGGLES,
} = helpers;

// ----- 1. buildActionResult ----------------------------------------

test("R196: buildActionResult — picks first action by default", () => {
  const entry = {
    key: "n1",
    title: "Welcome",
    body: "hi",
    actions: [
      { actionId: { value: "a" }, label: "Open", primary: true },
      { actionId: { value: "b" }, label: "Dismiss", primary: false },
    ],
  };
  const r = buildActionResult(entry);
  assert.ok(r);
  assert.equal(r.key, "n1");
  assert.equal(r.actionId.value, "a");
});

test("R196: buildActionResult — picks action at index", () => {
  const entry = {
    key: "n1",
    title: "Welcome",
    actions: [
      { actionId: { value: "a" }, label: "Open", primary: true },
      { actionId: { value: "b" }, label: "Dismiss", primary: false },
    ],
  };
  const r = buildActionResult(entry, 1);
  assert.ok(r);
  assert.equal(r.actionId.value, "b");
});

test("R196: buildActionResult — no actions → null", () => {
  const entry = { key: "n1", title: "x", actions: [] };
  assert.equal(buildActionResult(entry), null);
});

// ----- 2. pendingCount ---------------------------------------------

test("R196: pendingCount — empty", () => {
  assert.equal(pendingCount([]), 0);
});

test("R196: pendingCount — non-empty", () => {
  const entries = [
    { key: "a", title: "x", actions: [] },
    { key: "b", title: "y", actions: [] },
    { key: "c", title: "z", actions: [] },
  ];
  assert.equal(pendingCount(entries), 3);
});

// ----- 3. buildDetailResult / formatDetailTitle --------------------

test("R196: buildDetailResult — passthrough", () => {
  assert.equal(buildDetailResult("install"), "install");
  assert.equal(buildDetailResult(null), null);
});

test("R196: formatDetailTitle — without body", () => {
  assert.equal(formatDetailTitle("Welcome"), "Welcome");
});

test("R196: formatDetailTitle — with body", () => {
  const s = formatDetailTitle(
    "Welcome",
    "this is a long welcome body that should get truncated for display",
  );
  assert.match(s, /Welcome/);
  assert.match(s, /…$/);
});

// ----- 4. labelFor / flipToggle / defaultToggleState / countEnabled

test("R196: labelFor — known key returns label", () => {
  assert.equal(labelFor("yolo_mode"), "Warn when YOLO mode is active (no approval review)");
  assert.equal(labelFor("ripgrep"), "Warn when ripgrep is not installed");
});

test("R196: labelFor — unknown key returns key", () => {
  assert.equal(labelFor("nonexistent"), "nonexistent");
});

test("R196: flipToggle — enable → disable", () => {
  const r = flipToggle({ yolo_mode: true }, "yolo_mode");
  assert.equal(r.yolo_mode, false);
});

test("R196: flipToggle — missing key → enable (default true)", () => {
  const r = flipToggle({}, "yolo_mode");
  assert.equal(r.yolo_mode, false); // flip default true → false
});

test("R196: defaultToggleState — all enabled by default", () => {
  const s = defaultToggleState();
  for (const t of WARNING_TOGGLES) {
    assert.equal(s[t.warningKey], true);
  }
});

test("R196: countEnabled — all enabled", () => {
  const s = defaultToggleState();
  assert.equal(countEnabled(s), WARNING_TOGGLES.length);
});

test("R196: countEnabled — one disabled", () => {
  const s = { ...defaultToggleState(), yolo_mode: false };
  assert.equal(countEnabled(s), WARNING_TOGGLES.length - 1);
});

test("R196: countEnabled — missing keys default to enabled", () => {
  assert.equal(countEnabled({}), WARNING_TOGGLES.length);
});

// R363: no unlinkSync here. The shim is inside `tmp/`, which is already
// wiped at the top of this file on the next run, and deleting it is no
// longer load-bearing for anything outside this test.
