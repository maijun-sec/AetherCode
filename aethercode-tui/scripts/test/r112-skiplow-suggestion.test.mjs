// R112: tests for the skip-low + permissionModeSuggestion
// reducer paths and the StatusBar wiring.
//
// We test:
//   1. INITIAL has skipLowWaterline: 5, lastSkipLow: null,
//      permissionModeSuggestion: null
//   2. setSkipLowWaterline action updates the field
//   3. setLastSkipLow action populates the snapshot
//   4. clearLastSkipLow action nulls the snapshot
//   5. setPermissionModeSuggestion action populates the
//      suggestion
//   6. StatusBar source contains the "low!" badge logic
//      AND the "💡 suggested" rendering

import { test } from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { existsSync, rmSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const tmp = join(root, "tmp-test-r112");
if (existsSync(tmp)) rmSync(tmp, { recursive: true, force: true });

const tscBinAbs = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
const tscArgs = [
  "--outDir", tmp,
  "--target", "ES2022",
  "--module", "ES2022",
  "--moduleResolution", "bundler",
  "--esModuleInterop", "true",
  "--skipLibCheck", "true",
  "--rootDir", join(root, "src"),
];
const tsc = spawnSync('"' + tscBinAbs + '"', [...tscArgs, '"' + join(root, "src", "state.ts") + '"'], {
  encoding: "utf-8",
  shell: true,
});
if (tsc.status !== 0) {
  console.error("tsc failed (status=" + tsc.status + "):\nSTDOUT:\n" + tsc.stdout + "\nSTDERR:\n" + tsc.stderr);
  process.exit(1);
}
if (!existsSync(join(tmp, "state.js"))) {
  console.error("tsc exited 0 but state.js was not produced");
  process.exit(1);
}

const mod = await import("file:///" + join(tmp, "state.js").replace(/\\/g, "/"));
const { reducer, INITIAL } = mod;

// ----- 1. INITIAL values --------------------------------------------------

test("INITIAL: skipLowWaterline defaults to 5", () => {
  assert.equal(INITIAL.skipLowWaterline, 5);
});

test("INITIAL: lastSkipLow is null", () => {
  assert.equal(INITIAL.lastSkipLow, null);
});

test("INITIAL: permissionModeSuggestion is null", () => {
  assert.equal(INITIAL.permissionModeSuggestion, null);
});

// ----- 2. setSkipLowWaterline -------------------------------------------

test("reducer: setSkipLowWaterline updates the field", () => {
  const s = reducer(INITIAL, { type: "setSkipLowWaterline", waterline: 7 });
  assert.equal(s.skipLowWaterline, 7);
});

test("reducer: setSkipLowWaterline to 0 (disabled)", () => {
  const s = reducer(INITIAL, { type: "setSkipLowWaterline", waterline: 0 });
  assert.equal(s.skipLowWaterline, 0);
});

// ----- 3. setLastSkipLow -------------------------------------------------

test("reducer: setLastSkipLow populates the snapshot", () => {
  const snap = { sessionId: "s1", remaining: 3, atMs: 1234 };
  const s = reducer(INITIAL, { type: "setLastSkipLow", snap });
  assert.deepEqual(s.lastSkipLow, snap);
});

test("reducer: setLastSkipLow replaces an existing snapshot", () => {
  let s = reducer(INITIAL, { type: "setLastSkipLow", snap: { sessionId: "s1", remaining: 3, atMs: 1 } });
  s = reducer(s, { type: "setLastSkipLow", snap: { sessionId: "s1", remaining: 2, atMs: 2 } });
  assert.deepEqual(s.lastSkipLow, { sessionId: "s1", remaining: 2, atMs: 2 });
});

// ----- 4. clearLastSkipLow -----------------------------------------------

test("reducer: clearLastSkipLow nulls the snapshot", () => {
  let s = reducer(INITIAL, { type: "setLastSkipLow", snap: { sessionId: "s1", remaining: 3, atMs: 1 } });
  assert.notEqual(s.lastSkipLow, null);
  s = reducer(s, { type: "clearLastSkipLow" });
  assert.equal(s.lastSkipLow, null);
});

// ----- 5. setPermissionModeSuggestion -----------------------------------

test("reducer: setPermissionModeSuggestion populates the field", () => {
  const suggestion = { mode: "ACCEPT_TASK", reasons: ["has CI config"] };
  const s = reducer(INITIAL, { type: "setPermissionModeSuggestion", suggestion });
  assert.deepEqual(s.permissionModeSuggestion, suggestion);
});

test("reducer: setPermissionModeSuggestion to null clears it", () => {
  let s = reducer(INITIAL, { type: "setPermissionModeSuggestion", suggestion: { mode: "ACCEPT_TASK", reasons: [] } });
  s = reducer(s, { type: "setPermissionModeSuggestion", suggestion: null });
  assert.equal(s.permissionModeSuggestion, null);
});

// ----- 6. StatusBar source has the "low!" badge + "💡 suggested" -------

const statusBarSrc = readFileSync(
  join(root, "src", "components", "StatusBar.tsx"),
  "utf-8"
);

test("StatusBar: contains the (low!) badge logic", () => {
  assert.match(statusBarSrc, /\(low!\)/);
});

test("StatusBar: contains the waterline comparison", () => {
  assert.match(statusBarSrc, /skipLowWaterline/);
});

test("StatusBar: contains the 💡 suggested rendering", () => {
  assert.match(statusBarSrc, /💡 suggested/);
});

test("StatusBar: shows suggestion only when mode differs from current", () => {
  // The conditional should compare permissionModeSuggestion.mode
  // to state.permissionMode.
  assert.match(statusBarSrc, /permissionModeSuggestion\.mode\s*!==\s*state\.permissionMode/);
});
