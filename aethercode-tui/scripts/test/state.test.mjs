// R32-G: tests for the TUI reducer's run-end handling.
//
// We don't have a node:test framework wired up for the TUI source
// yet, so we test by importing the compiled JS. The bundle's
// dist/ac-tui.js is the production artefact, but for unit tests
// we want the un-bundled state.ts compiled to JS. We use
// `tsc --noEmit false --outDir` to emit JS, then import the
// module under test.
//
// Run: node scripts/test/state.test.mjs (called by `npm test`).
//
// We test:
//   1. classifyStopReason maps daemon reasons to UI kinds
//   2. reducer: streamEnd action sets lastStopReason + lastStopKind
//   3. reducer: submit action clears lastStopReason + lastStopKind
//   4. The default state has lastStopReason: null

import { test } from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { existsSync, rmSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const tmp = join(root, "tmp-test");
if (existsSync(tmp)) rmSync(tmp, { recursive: true, force: true });

// Compile state.ts to a tmp JS file we can import.
// We use the locally-installed TypeScript binary (./node_modules/.bin/tsc)
// rather than `npx tsc` to avoid PowerShell's npx.ps1 wrapper. We
// pass `shell: true` so spawnSync can run .cmd shims on Windows.
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
  console.error("tsc exited 0 but state.js was not produced at " + join(tmp, "state.js"));
  process.exit(1);
}

const mod = await import("file:///" + join(tmp, "state.js").replace(/\\/g, "/"));
const { reducer, INITIAL, classifyStopReason, nextTurnId } = mod;

// ----- 1. classifyStopReason --------------------------------------------

test("classifyStopReason: end_turn → ok", () => {
  assert.equal(classifyStopReason("end_turn"), "ok");
  assert.equal(classifyStopReason("end_turn_and_tool"), "ok");
});

test("classifyStopReason: loop_detected → loop", () => {
  assert.equal(classifyStopReason("loop_detected"), "loop");
});

test("classifyStopReason: max_iterations → max_turns", () => {
  assert.equal(classifyStopReason("max_iterations"), "max_turns");
  assert.equal(classifyStopReason("max_turns"), "max_turns");
});

test("classifyStopReason: error → error", () => {
  assert.equal(classifyStopReason("error"), "error");
  assert.equal(classifyStopReason("rpc_error"), "error");
});

test("classifyStopReason: empty_input → empty", () => {
  assert.equal(classifyStopReason("empty_input"), "empty");
});

test("classifyStopReason: null → null", () => {
  assert.equal(classifyStopReason(null), null);
  assert.equal(classifyStopReason(undefined), null);
});

test("classifyStopReason: MiniMax 'stop' / 'tool_calls' / 'max_tokens' → ok", () => {
  // R32-G: MiniMax's API uses "stop" and "tool_calls" as stop
  // reasons. Both should classify as ok.
  assert.equal(classifyStopReason("stop"), "ok");
  assert.equal(classifyStopReason("tool_calls"), "ok");
  // "max_tokens" is the model hitting its output cap — not an
  // error, not a loop; it's a normal completion.
  assert.equal(classifyStopReason("max_tokens"), "ok");
  assert.equal(classifyStopReason("length"), "ok");
});

// ----- 2. reducer: streamEnd sets lastStopReason + lastStopKind --------

test("reducer: streamEnd with loop_detected sets kind=loop", () => {
  let s = reducer(INITIAL, { type: "streamEnd", stopReason: "loop_detected" });
  assert.equal(s.status, "ready");
  assert.equal(s.submitting, false);
  assert.equal(s.lastStopReason, "loop_detected");
  assert.equal(s.lastStopKind, "loop");
});

test("reducer: streamEnd with end_turn sets kind=ok", () => {
  let s = reducer(INITIAL, { type: "streamEnd", stopReason: "end_turn" });
  assert.equal(s.lastStopKind, "ok");
  assert.equal(s.lastStopReason, "end_turn");
});

test("reducer: streamEnd with max_iterations sets kind=max_turns", () => {
  let s = reducer(INITIAL, { type: "streamEnd", stopReason: "max_iterations" });
  assert.equal(s.lastStopKind, "max_turns");
});

test("reducer: streamEnd captures usage", () => {
  let s = reducer(INITIAL, {
    type: "streamEnd",
    stopReason: "end_turn",
    usage: { input: 100, output: 200, costUsd: 0.001 },
  });
  assert.equal(s.inputTokens, 100);
  assert.equal(s.outputTokens, 200);
  assert.equal(s.totalCostUsd, 0.001);
});

// ----- 3. reducer: submit clears lastStopReason -------------------------

test("reducer: submit clears lastStopReason and lastStopKind", () => {
  // Start in a "post-loop" state.
  let s = reducer(INITIAL, { type: "streamEnd", stopReason: "loop_detected" });
  assert.equal(s.lastStopKind, "loop");
  // User submits a new query — the run-end indicator should clear.
  s = reducer(s, { type: "setInput", text: "continue" });
  s = reducer(s, { type: "submit" });
  assert.equal(s.lastStopReason, null);
  assert.equal(s.lastStopKind, null);
  assert.equal(s.submitting, true);
  assert.equal(s.status, "thinking");
});

// ----- 4. default state ------------------------------------------------

test("INITIAL: lastStopReason is null, lastStopKind is null", () => {
  assert.equal(INITIAL.lastStopReason, null);
  assert.equal(INITIAL.lastStopKind, null);
});
