// R91-D: tests for the subagent lifecycle reducer + helpers.
//
// The test setup mirrors state.test.mjs — we compile state.ts
// to a tmp JS file via the locally-installed tsc binary, then
// import it. The "transport" (aethercode-protocol daemon) is
// out of scope here; we test only the pure reducer logic.
//
// We test:
//   1. INITIAL state has empty subagentStatus + 0 runningSubagents
//   2. subagentEvent with RUNNING status: bumps runningSubagents
//      and sets subagentStatus to "[id] running"
//   3. subagentEvent with COMPLETED: drops runningSubagents, sets
//      status to "[id] done 1.4s"
//   4. subagentEvent with FAILED: drops runningSubagents, sets
//      status to "[id] failed"
//   5. subagentEvent with CANCELLED: drops runningSubagents, sets
//      status to "[id] cancelled"
//   6. formatSubagentStatus helper renders the expected strings
//   7. A pair of RUNNING + COMPLETED events: count goes 0→1→0

import { test } from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { existsSync, rmSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const tmp = join(root, "tmp-test-r91d");
if (existsSync(tmp)) rmSync(tmp, { recursive: true, force: true });

// Compile state.ts to JS.
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
const { reducer, INITIAL, formatSubagentStatus } = mod;

// ----- 1. INITIAL defaults ----------------------------------------------

test("INITIAL.subagentStatus is empty", () => {
  assert.equal(INITIAL.subagentStatus, "");
});

test("INITIAL.runningSubagents is 0", () => {
  assert.equal(INITIAL.runningSubagents, 0);
});

// ----- 2. RUNNING transition --------------------------------------------

test("reducer: subagentEvent RUNNING bumps count + sets status", () => {
  const s1 = reducer(INITIAL, {
    type: "subagentEvent",
    jobId: "sag-1",
    role: "explore",
    status: "RUNNING",
    elapsedMs: 0,
    atMs: 1000,
    summary: "starting",
  });
  assert.equal(s1.runningSubagents, 1);
  assert.equal(s1.subagentStatus, "[sag-1] running");
});

// ----- 3. COMPLETED transition ------------------------------------------

test("reducer: subagentEvent COMPLETED drops count + sets done", () => {
  const running = reducer(INITIAL, {
    type: "subagentEvent",
    jobId: "sag-1",
    role: "explore",
    status: "RUNNING",
    elapsedMs: 0,
    atMs: 1000,
    summary: "",
  });
  const done = reducer(running, {
    type: "subagentEvent",
    jobId: "sag-1",
    role: "explore",
    status: "COMPLETED",
    elapsedMs: 1400,
    atMs: 2400,
    summary: "ok",
  });
  assert.equal(done.runningSubagents, 0);
  assert.equal(done.subagentStatus, "[sag-1] done 1.4s");
});

// ----- 4. FAILED transition ---------------------------------------------

test("reducer: subagentEvent FAILED drops count + sets failed", () => {
  const running = reducer(INITIAL, {
    type: "subagentEvent",
    jobId: "sag-2",
    role: "explore",
    status: "RUNNING",
    elapsedMs: 0,
    atMs: 1000,
    summary: "",
  });
  const failed = reducer(running, {
    type: "subagentEvent",
    jobId: "sag-2",
    role: "explore",
    status: "FAILED",
    elapsedMs: 800,
    atMs: 1800,
    summary: "boom",
  });
  assert.equal(failed.runningSubagents, 0);
  assert.equal(failed.subagentStatus, "[sag-2] failed");
});

// ----- 5. CANCELLED transition ------------------------------------------

test("reducer: subagentEvent CANCELLED drops count + sets cancelled", () => {
  const running = reducer(INITIAL, {
    type: "subagentEvent",
    jobId: "sag-3",
    role: "explore",
    status: "RUNNING",
    elapsedMs: 0,
    atMs: 1000,
    summary: "",
  });
  const cancelled = reducer(running, {
    type: "subagentEvent",
    jobId: "sag-3",
    role: "explore",
    status: "CANCELLED",
    elapsedMs: 0,
    atMs: 2000,
    summary: "",
  });
  assert.equal(cancelled.runningSubagents, 0);
  assert.equal(cancelled.subagentStatus, "[sag-3] cancelled");
});

// ----- 6. formatSubagentStatus helper -----------------------------------

test("formatSubagentStatus: running", () => {
  assert.equal(formatSubagentStatus("sag-1", "RUNNING", 0), "[sag-1] running");
});

test("formatSubagentStatus: completed with elapsed", () => {
  assert.equal(formatSubagentStatus("sag-1", "COMPLETED", 1500), "[sag-1] done 1.5s");
});

test("formatSubagentStatus: failed", () => {
  assert.equal(formatSubagentStatus("sag-1", "FAILED", 0), "[sag-1] failed");
});

test("formatSubagentStatus: cancelled", () => {
  assert.equal(formatSubagentStatus("sag-1", "CANCELLED", 0), "[sag-1] cancelled");
});

test("formatSubagentStatus: empty jobId falls back to '?'", () => {
  assert.equal(formatSubagentStatus("", "RUNNING", 0), "[?] running");
});

// ----- 7. count goes 0 → 1 → 0 (lifecycle) -------------------------------

test("reducer: full lifecycle keeps count consistent", () => {
  let s = INITIAL;
  assert.equal(s.runningSubagents, 0);

  s = reducer(s, { type: "subagentEvent", jobId: "sag-1", role: "r", status: "RUNNING",   elapsedMs: 0,    atMs: 1, summary: "" });
  assert.equal(s.runningSubagents, 1);

  s = reducer(s, { type: "subagentEvent", jobId: "sag-2", role: "r", status: "RUNNING",   elapsedMs: 0,    atMs: 2, summary: "" });
  assert.equal(s.runningSubagents, 2);

  s = reducer(s, { type: "subagentEvent", jobId: "sag-1", role: "r", status: "COMPLETED", elapsedMs: 800,  atMs: 3, summary: "" });
  assert.equal(s.runningSubagents, 1);
  // Most recent event wins (sag-2 still running but the
  // status bar shows the latest activity).
  assert.match(s.subagentStatus, /^\[sag-1\]/);

  s = reducer(s, { type: "subagentEvent", jobId: "sag-2", role: "r", status: "FAILED",    elapsedMs: 1500, atMs: 4, summary: "boom" });
  assert.equal(s.runningSubagents, 0);
  assert.equal(s.subagentStatus, "[sag-2] failed");
});

// ----- 8. R92-D: per-session filter ------------------------------------
// The TUI's getState() sets state.sessionId. Subagent
// events from other sessions must be dropped so the
// StatusBar / toast don't show noise from a sibling
// session in a multi-session daemon. Pre-R92-D events
// carry no sessionId — those are accepted as ours when
// state.sessionId is still the "—" placeholder (a fresh
// TUI before getState returns) so the user doesn't lose
// events they fired before the session id loaded.

test("reducer: foreign session event is dropped (no state change)", () => {
  const sessA = { ...INITIAL, sessionId: "sess-A" };
  const r = reducer(sessA, {
    type: "subagentEvent",
    jobId: "sag-other", role: "explore", status: "RUNNING",
    elapsedMs: 0, atMs: 1000, summary: "",
    sessionId: "sess-B",
  });
  // No state change at all — same reference, same fields.
  assert.equal(r, sessA, "reducer should return the same state when the event is from another session");
  assert.equal(r.runningSubagents, 0);
  assert.equal(r.subagentStatus, "");
});

test("reducer: matching session event updates normally", () => {
  const sessA = { ...INITIAL, sessionId: "sess-A" };
  const r = reducer(sessA, {
    type: "subagentEvent",
    jobId: "sag-1", role: "explore", status: "RUNNING",
    elapsedMs: 0, atMs: 1000, summary: "",
    sessionId: "sess-A",
  });
  assert.equal(r.runningSubagents, 1);
  assert.equal(r.subagentStatus, "[sag-1] running");
});

test("reducer: empty event sessionId is accepted (legacy / pre-init)", () => {
  // Legacy daemon (no sessionId on the payload) and
  // a TUI whose state.sessionId is still "—" — both
  // should see the event. The reducer only filters
  // when BOTH sides have a non-empty sessionId.
  const preInit = { ...INITIAL, sessionId: "—" };
  const r = reducer(preInit, {
    type: "subagentEvent",
    jobId: "sag-legacy", role: "explore", status: "RUNNING",
    elapsedMs: 0, atMs: 1, summary: "",
  });
  assert.equal(r.runningSubagents, 1);
  assert.equal(r.subagentStatus, "[sag-legacy] running");
});
