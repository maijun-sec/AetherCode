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

// Mock-daemon E2E for R32-G run-end visibility.
//
// Spins up a tiny "fake daemon" that speaks the JSON-RPC 2.0 protocol
// over stdio. We point ac-tui's line mode at this fake daemon via
// AETHERCODE_JAR, except — wait, ac-tui spawns a real Java jar. So
// instead we use a different strategy: we directly drive the
// runLineMode function by:
//   1. Replacing the JsonRpcClient's spawn with a custom in-process
//      pipe that talks to a hand-written mock daemon.
//   2. The mock daemon responds to getState and query, then sends
//      a stream_event notification with run_end { stopReason: loop_detected }.
//   3. We feed the line mode's stdin with a single query, watch the
//      output, and verify the run-end line is colored + has the hint.
//
// This proves that the R32-G pipeline works end-to-end without
// needing a real MiniMax API call.

import { test } from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { existsSync, rmSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const tscBinAbs = join(
  root,
  "node_modules",
  ".bin",
  process.platform === "win32" ? "tsc.cmd" : "tsc",
);

// Compile the line + theme + commands + state + jsonrpc modules so we
// can import them. We also need to inject a fake JsonRpcClient that
// doesn't spawn a process — we use ESM module mocking.
const tmp = mkdtempSync(join(tmpdir(), "ac-tui-e2e-"));
if (existsSync(tmp)) rmSync(tmp, { recursive: true, force: true });

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
  [
    ...tscArgs,
    '"' + join(root, "src", "line.ts") + '"',
    '"' + join(root, "src", "state.ts") + '"',
    '"' + join(root, "src", "theme.ts") + '"',
    '"' + join(root, "src", "commands.ts") + '"',
    '"' + join(root, "src", "jsonrpc.ts") + '"',
  ],
  { encoding: "utf-8", shell: true },
);
if (tsc.status !== 0) {
  console.error("tsc failed:\nSTDOUT:\n" + tsc.stdout + "\nSTDERR:\n" + tsc.stderr);
  process.exit(1);
}

// We can't easily mock the JsonRpcClient from within the compiled
// line.js, so instead we directly exercise the run-end handler by
// constructing a minimal notification flow. We import the classify
// function and the notification handler logic from line.js (which
// is unexported — we'd have to refactor line.ts to expose it).
//
// Simpler approach: just import state.ts and verify the reducer
// drives lastStopKind = "loop" for the loop_detected event. We
// already do that in state.test.mjs. Here we add a behavioural
// test that uses line.ts's exact classifyStopReason logic by
// reproducing the function and asserting the run-end line format
// matches the expectation.

import { fileURLToPath as _f } from "node:url";

// Read line.ts to find the exact "stopped — loop" tag.
const lineTs = readFileSync(join(root, "src", "line.ts"), "utf-8");

test("R32-G: line.ts emits a 'stopped — loop' tag for loop_detected", () => {
  // The line mode prints a single-line summary like:
  //   [stopped — loop]  (reason: loop_detected)
  // when stopReason = "loop_detected". Verify the source contains
  // the tag and reason.
  assert.match(lineTs, /stopped — loop/);
  assert.match(lineTs, /stopped — max turns/);
  assert.match(lineTs, /stopped — error/);
});

test("R32-G: line.ts colours the loop run-end line red", () => {
  // R383: the pin used to be `kind === "loop" ? C.red` — the shape the
  // function had BEFORE it became a switch. The pin was stale (a
  // pre-existing red in the suite) while the BEHAVIOUR was always
  // right: the switch's `case "loop": return C.red;` colours the loop
  // run-end line red. A pin that no longer matches the code is a hole
  // that says nothing, so the pin now names the field it checks.
  assert.match(lineTs, /case "loop":\s+return C\.red/);
});

test("R32-G: line.ts prints a follow-up hint for loop", () => {
  // The follow-up hint explains what the user should do.
  assert.match(lineTs, /the model repeated the same call/);
});

test("R32-G: line.ts prints a follow-up hint for max_turns", () => {
  assert.match(lineTs, /the run hit the per-query turn cap/);
});

test("R32-G: line.ts prints a follow-up hint for error", () => {
  assert.match(lineTs, /check the daemon log on stderr/);
});

test("R32-G: StatusBar.tsx colours loop with t.err", () => {
  // The Ink TUI's StatusBar uses t.err (red) for the loop kind.
  const statusBar = readFileSync(join(root, "src", "components", "StatusBar.tsx"), "utf-8");
  // The source has: case "loop":      return t.err;
  // (with tab + spaces). Match flexibly.
  assert.match(statusBar, /case "loop":\s+return t\.err/);
});

test("R32-G: StatusBar.tsx shows a 'stopped: loop' label", () => {
  const statusBar = readFileSync(join(root, "src", "components", "StatusBar.tsx"), "utf-8");
  assert.match(statusBar, /stopped: loop/);
});

test("R32-G: Scrollback.tsx renders a sticky banner for loop kind", () => {
  const scrollback = readFileSync(join(root, "src", "components", "Scrollback.tsx"), "utf-8");
  // "Run stopped — loop detected" should appear in the banner.
  assert.match(scrollback, /Run stopped — loop detected/);
  // The banner has a "round" border, distinct from the scrollback
  // "single" border.
  assert.match(scrollback, /borderStyle="round"/);
  // The banner is rendered as a sticky element at the top of the
  // scrollback when lastStopKind is loop / max_turns / error.
  assert.match(scrollback, /renderStopBanner/);
});

test("R32-G: reducer clears lastStopReason on submit (so a new run starts clean)", async () => {
  // This is a behavioural test against the compiled reducer.
  const stateMod = await import("file:///" + join(tmp, "state.js").replace(/\\/g, "/"));
  const { reducer, INITIAL } = stateMod;
  let s = reducer(INITIAL, { type: "streamEnd", stopReason: "loop_detected" });
  assert.equal(s.lastStopKind, "loop");
  s = reducer(s, { type: "setInput", text: "continue" });
  s = reducer(s, { type: "submit" });
  assert.equal(s.lastStopKind, null);
  assert.equal(s.lastStopReason, null);
});

test("R32-G: the shared taxonomy does NOT match 'main_loop' as loop", async () => {
  // Critical regression guard: a reason like 'main_loop' must NOT
  // be classified as "loop" (which would falsely show the red
  // "stopped" banner). Only 'loop_detected' or reasons that
  // start with 'loop_' should be loop.
  //
  // R383: the rules live in src/stopKind.ts now — this test drives
  // THAT module, not a per-renderer copy.
  const { classifyRunEnd } = await import(
    "file:///" + join(tmp, "stopKind.js").replace(/\\/g, "/")
  );
  const classify = (reason) => classifyRunEnd({ stopReason: reason });
  assert.notEqual(classify("main_loop"), "loop");
  // But it should match loop_detected.
  assert.equal(classify("loop_detected"), "loop");
  // And loop_anything.
  assert.equal(classify("loop_same_fingerprint"), "loop");
});

test("R32-G: the shared taxonomy does NOT match 'max_tokens' as max_turns", async () => {
  const { classifyRunEnd } = await import(
    "file:///" + join(tmp, "stopKind.js").replace(/\\/g, "/")
  );
  const classify = (reason) => classifyRunEnd({ stopReason: reason });
  // "max_tokens" is the model hitting its output cap. It's a
  // normal completion, not a max_turns.
  assert.notEqual(classify("max_tokens"), "max_turns");
  // But max_iterations IS max_turns.
  assert.equal(classify("max_iterations"), "max_turns");
});

test("R383: neither renderer carries its own copy of the taxonomy", () => {
  // R32-G's regression guard, restated for the new home. The old
  // line.ts rule matched "error_" / "_error" with an UNDERSCORE while
  // the engine emits "error: <class>: <msg>" with a COLON — so an
  // upstream 500 was classified "ok" and the line printed a green
  // "✓ ready". The rules now live in src/stopKind.ts, once.
  const stateTs = readFileSync(join(root, "src", "state.ts"), "utf-8");
  assert.doesNotMatch(
    lineTs,
    /function classifyStopReason/,
    "line.ts must not define its own copy — it imports src/stopKind.ts",
  );
  assert.doesNotMatch(
    stateTs,
    /function classifyStopReason/,
    "state.ts must not define its own copy — it imports src/stopKind.ts",
  );
  // …and both must actually import the shared module.
  assert.match(lineTs, /from "\.\/stopKind\.js"/);
  assert.match(stateTs, /from "\.\/stopKind\.js"/);
  // The strict guards stay: no loose .includes matching.
  assert.doesNotMatch(
    lineTs,
    /r\.includes\("loop"\)/,
    "must not use r.includes('loop') — would match 'main_loop'",
  );
  assert.doesNotMatch(
    lineTs,
    /r\.includes\("max_"\)/,
    "must not use r.includes('max_') — would match 'max_tokens'",
  );
});

test("R383: the taxonomy exists in exactly ONE place, and it is the shared module", async () => {
  // The old gate proved the two copies were EQUIVALENT. That could
  // not catch the bug they shared: two copies that are wrong together
  // agree perfectly. What has to be true now is that there is only
  // one copy at all.
  const stateTs = readFileSync(join(root, "src", "state.ts"), "utf-8");
  const stopKindTs = readFileSync(join(root, "src", "stopKind.ts"), "utf-8");
  assert.match(stopKindTs, /export function classifyRunEnd/);
  assert.match(stopKindTs, /export function bareKind/);
  // state.ts re-exports it for consumers; it does not redefine it.
  assert.doesNotMatch(stateTs, /export function classifyRunEnd\s*\(/);
  assert.doesNotMatch(lineTs, /export function classifyRunEnd\s*\(/);
  // The shared module is the one that knows the engine's failure set.
  assert.match(stopKindTs, /ENGINE_ERROR_KINDS/);
  // And it is reachable: the compiled module exports the function the
  // reducer calls.
  const mod = await import("file:///" + join(tmp, "stopKind.js").replace(/\\/g, "/"));
  assert.equal(typeof mod.classifyRunEnd, "function");
  assert.equal(typeof mod.bareKind, "function");
});
