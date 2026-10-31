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
const tscBinAbs = join(
  root,
  "node_modules",
  ".bin",
  process.platform === "win32" ? "tsc.cmd" : "tsc",
);
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
const tsc = spawnSync(
  '"' + tscBinAbs + '"',
  [...tscArgs, '"' + join(root, "src", "state.ts") + '"'],
  {
    encoding: "utf-8",
    shell: true,
  },
);
if (tsc.status !== 0) {
  console.error(
    "tsc failed (status=" + tsc.status + "):\nSTDOUT:\n" + tsc.stdout + "\nSTDERR:\n" + tsc.stderr,
  );
  process.exit(1);
}
if (!existsSync(join(tmp, "state.js"))) {
  console.error("tsc exited 0 but state.js was not produced at " + join(tmp, "state.js"));
  process.exit(1);
}

const mod = await import("file:///" + join(tmp, "state.js").replace(/\\/g, "/"));
const { reducer, INITIAL, nextTurnId } = mod;

// ----- 1. the shared taxonomy (src/stopKind.ts) -------------------------
//
// R383: the classification rules moved out of state.ts into
// src/stopKind.ts — the ONE place that knows how a finished run is
// classified. The verdict comes from the daemon (`isError` on the
// wire) and the canonical kind from `stopKind`.

const stopKindMod = await import("file:///" + join(tmp, "stopKind.js").replace(/\\/g, "/"));
const { classifyRunEnd, bareKind } = stopKindMod;
/** Classify a bare stop reason, the way an OLD daemon's event would. */
const classify = (reason) => classifyRunEnd({ stopReason: reason });

test("the taxonomy: end_turn → ok", () => {
  assert.equal(classify("end_turn"), "ok");
  assert.equal(classify("end_turn_and_tool"), "ok");
});

test("the taxonomy: loop_detected → loop", () => {
  assert.equal(classify("loop_detected"), "loop");
});

test("the taxonomy: max_iterations → max_turns", () => {
  assert.equal(classify("max_iterations"), "max_turns");
  assert.equal(classify("max_turns"), "max_turns");
});

test("the taxonomy: error → error", () => {
  assert.equal(classify("error"), "error");
});

test("R383: the taxonomy: a DECORATED error → error (the bug this round fixes)", () => {
  // The engine decorates its failures: "error: <class>: <msg>". The old
  // rule matched "error_" / "_error" with an UNDERSCORE, so this fell
  // through to "ok" and the StatusBar showed a green "✓ ready" for a
  // run that had died with an upstream 500.
  assert.equal(
    classify(
      "error: WebClientResponseException$InternalServerError: 500 from POST https://api.minimaxi.com/v1/chat/completions",
    ),
    "error",
  );
  assert.equal(classify("runtime_exception: java.lang.IllegalStateException: boom"), "error");
  assert.equal(classify("same_error_hard_stop"), "loop");
  assert.equal(classify("stale_force_end"), "error");
  assert.equal(
    classify("loop_detected: empty_tool_input (4 consecutive empty tool calls)"),
    "loop",
  );
});

test("R383: the daemon's isError / stopKind win over the raw reason", () => {
  // The wire is the authority: a run the daemon calls a failure is a
  // failure, whatever the string says.
  assert.equal(
    classifyRunEnd({ stopReason: "error: boom", stopKind: "error", isError: true }),
    "error",
  );
  // A kind the local rules know nothing about still classifies.
  assert.equal(classifyRunEnd({ stopReason: "weird_new_kind", stopKind: "weird_new_kind" }), "ok");
  // …and a kind the daemon says is a failure is a failure.
  assert.equal(
    classifyRunEnd({ stopReason: "weird_new_kind", stopKind: "weird_new_kind", isError: true }),
    "error",
  );
});

test("the taxonomy: empty_input → empty", () => {
  assert.equal(classify("empty_input"), "empty");
});

test("the taxonomy: no signal → null (no run has finished)", () => {
  assert.equal(classify(null), null);
  assert.equal(classify(undefined), null);
  assert.equal(classifyRunEnd({}), null);
});

test("the taxonomy: MiniMax 'stop' / 'tool_calls' / 'max_tokens' → ok", () => {
  // R32-G: MiniMax's API uses "stop" and "tool_calls" as stop
  // reasons. Both should classify as ok.
  assert.equal(classify("stop"), "ok");
  assert.equal(classify("tool_calls"), "ok");
  // "max_tokens" is the model hitting its output cap — not an
  // error, not a loop; it's a normal completion.
  assert.equal(classify("max_tokens"), "ok");
  assert.equal(classify("length"), "ok");
});

test("bareKind: the prefix before the first ':', ' ' or '('", () => {
  assert.equal(bareKind("error: WebClientResponseException: 500"), "error");
  assert.equal(bareKind("loop_detected: empty_tool_input (4 calls)"), "loop_detected");
  assert.equal(bareKind("end_turn"), "end_turn");
  assert.equal(bareKind(null), "");
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

test("R383: the reducer takes the verdict from the daemon, not from the string", () => {
  // The daemon says this run failed. The reducer must believe it.
  let s = reducer(INITIAL, {
    type: "streamEnd",
    stopReason: "error: WebClientResponseException$InternalServerError: 500 from POST https://x",
    stopKind: "error",
    isError: true,
  });
  assert.equal(s.lastStopKind, "error");
  // The raw reason is still kept for display.
  assert.match(s.lastStopReason, /^error:/);
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
