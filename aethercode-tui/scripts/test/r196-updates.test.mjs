// R196 (Phase 5 R4): pure-function tests for the Update screens.
//
// We use `node --test` with a runtime compile of just the helper
// files. The helpers are:
//   - dismissAction / dismissChangelog / dismissUpdateCancel (UpdateAvailable.tsx)
//   - updateConfirmResult / chooseUpdateKind                (UpdateConfirm.tsx)
//   - appendLineTail / markSuccessStatus / markFailureStatus / markWarningStatus (UpdateProgress.tsx)
//
// The tests are runtime assertions, not source-code grep.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdirSync, writeFileSync, unlinkSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

const tmp = join(root, "tmp-r196-updates-tsc");
const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
if (existsSync(tmp)) {
  spawnSync(process.platform === "win32" ? "cmd" : "rm",
    process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", tmp] : ["-rf", tmp]);
}
mkdirSync(tmp, { recursive: true });
const shimPath = join(root, "src", "components", "_r196_updates_helpers.ts");
const shim = [
  `export { dismissAction, dismissChangelog, dismissUpdateCancel } from "./UpdateAvailable.js";`,
  `export { updateConfirmResult, chooseUpdateKind } from "./UpdateConfirm.js";`,
  `export { appendLineTail, markSuccessStatus, markFailureStatus, markWarningStatus } from "./UpdateProgress.js";`,
  "",
].join("\n");
writeFileSync(shimPath, shim, "utf-8");

const tscArgs = [
  "--outDir", join(tmp, "out"), "--target", "ES2022", "--module", "ES2022",
  "--moduleResolution", "bundler", "--jsx", "react",
  "--esModuleInterop", "true", "--skipLibCheck", "true",
  "--rootDir", join(root, "src"),
];
const r = spawnSync(`"${tscBin}"`, [...tscArgs, `"${shimPath}"`], { encoding: "utf-8", shell: true });
if (r.status !== 0) {
  console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
  process.exit(1);
}
const helpers = await import(
  "file:///" + join(tmp, "out", "components", "_r196_updates_helpers.js").replace(/\\/g, "/")
);
const { dismissAction, dismissChangelog, dismissUpdateCancel,
  updateConfirmResult, chooseUpdateKind,
  appendLineTail, markSuccessStatus, markFailureStatus, markWarningStatus } = helpers;

// ----- 1. dismissAction / dismissChangelog / dismissUpdateCancel ----

test("R196: dismissAction — builds action dismissal", () => {
  const d = dismissAction("install");
  assert.equal(d.kind, "action");
  assert.equal(d.actionId, "install");
});

test("R196: dismissChangelog — builds changelog dismissal", () => {
  const d = dismissChangelog();
  assert.equal(d.kind, "changelog");
});

test("R196: dismissUpdateCancel — builds cancel dismissal", () => {
  const d = dismissUpdateCancel();
  assert.equal(d.kind, "cancel");
});

// ----- 2. updateConfirmResult / chooseUpdateKind -------------------

test("R196: updateConfirmResult — confirmed → true", () => {
  assert.equal(updateConfirmResult(true), true);
});

test("R196: updateConfirmResult — cancelled → false", () => {
  assert.equal(updateConfirmResult(false), false);
});

test("R196: chooseUpdateKind — both flags → before-deps", () => {
  assert.equal(chooseUpdateKind(true, true), "before-deps");
});

test("R196: chooseUpdateKind — only app update → refresh-deps (default)", () => {
  assert.equal(chooseUpdateKind(true, false), "refresh-deps");
});

test("R196: chooseUpdateKind — only dep refreshes → refresh-deps", () => {
  assert.equal(chooseUpdateKind(false, true), "refresh-deps");
});

test("R196: chooseUpdateKind — neither → refresh-deps", () => {
  assert.equal(chooseUpdateKind(false, false), "refresh-deps");
});

// ----- 3. appendLineTail -------------------------------------------

test("R196: appendLineTail — below cap → keep all", () => {
  const tail = appendLineTail(["a", "b"], "c", 5);
  assert.deepEqual(tail, ["a", "b", "c"]);
});

test("R196: appendLineTail — at cap → keep last N", () => {
  const tail = appendLineTail(["a", "b", "c"], "d", 3);
  assert.deepEqual(tail, ["b", "c", "d"]);
});

test("R196: appendLineTail — over cap → trim", () => {
  const tail = appendLineTail(["a", "b", "c", "d", "e"], "f", 3);
  assert.deepEqual(tail, ["d", "e", "f"]);
});

test("R196: appendLineTail — limit=0 → use default 30", () => {
  const tail = appendLineTail([], "x", 0);
  assert.equal(tail.length, 1);
});

// ----- 4. markSuccessStatus / markFailureStatus / markWarningStatus

test("R196: markSuccessStatus — includes version", () => {
  const s = markSuccessStatus("0.2.5");
  assert.match(s, /v0\.2\.5/);
});

test("R196: markFailureStatus — includes command", () => {
  const s = markFailureStatus("npm i -g aethercode");
  assert.match(s, /npm i -g aethercode/);
});

test("R196: markWarningStatus — passes through warning", () => {
  const s = markWarningStatus("needs sudo");
  assert.equal(s, "needs sudo");
});

try { unlinkSync(shimPath); } catch { /* ignore */ }
