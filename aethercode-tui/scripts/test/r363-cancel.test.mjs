// R363 — Esc interrupts the run in flight.
//
// The 2026-10-21 benchmark found (report section 0.3 / R750a):
//
//   "TUI 不能中断运行中的 run。tui.tsx:1619-1623 的 Esc 只关闭搜索栏和
//    帮助浮层，不停止这一轮。唯一的出口是 Ctrl+C,而它 dispatch
//    {type:'exit'} + exit(),把 daemon 一起拆掉。"
//
// The daemon half of this was ALREADY there: `AetherCodeMethods.cancel`
// (aethercode-protocol .../methods/AetherCodeMethods.java) registers a
// real R721 hard cancel — done.cancel(true) + Thread.interrupt() +
// ProcessHandle.destroyForcibly + a 5s Thread.stop fallback. What was
// missing was every line of plumbing to REACH it:
//
//   1. `cancel` is keyed by runId and the daemon reads `params.runId`
//      unconditionally, so the TUI had to persist an id it was discarding.
//      `query` returns `{runId, accepted}` (AetherCodeMethods.java:4085-4088)
//      — tui.tsx threw that response away with `request<unknown>(...)`.
//   2. Esc had TWO `return`-on-escape branches ahead of any cancel
//      candidate (the decision-card collapse/expand pair), which would have
//      made a live run structurally uncancellable even once (1) landed.
//
// These tests pin the reducer contract (the parts that are cheap to
// regress silently) and the source-level ordering that cannot be unit
// tested without a live daemon.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdirSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { codeOf as stripComments } from "./strip-comments.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const tmp = join(root, "tmp-r363-cancel");

const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
if (existsSync(tmp)) {
  if (process.platform === "win32") spawnSync("cmd", ["/c", "rmdir", "/s", "/q", tmp]);
  else spawnSync("rm", ["-rf", tmp]);
}
mkdirSync(tmp, { recursive: true });

const r = spawnSync(`"${tscBin}"`, [
  "--outDir", join(tmp, "out"), "--target", "ES2022", "--module", "ES2022",
  "--moduleResolution", "bundler", "--jsx", "react",
  "--esModuleInterop", "true", "--skipLibCheck", "true",
  "--rootDir", join(root, "src"),
  `"${join(root, "src", "state.ts")}"`,
], { encoding: "utf-8", shell: true });
if (r.status !== 0) {
  console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
  process.exit(1);
}

const mod = await import(pathToFileURL(join(tmp, "out", "state.js")).href);
// state.ts exports the seed as INITIAL (not initialState) and the reducer as
// a named `reducer`.
const { INITIAL, reducer } = mod;
const initialState = INITIAL;

const src = readFileSync(join(root, "src", "state.ts"), "utf-8");
const tui = readFileSync(join(root, "src", "tui.tsx"), "utf-8");
const line = readFileSync(join(root, "src", "line.ts"), "utf-8");
const lineInput = readFileSync(join(root, "src", "lineInput.ts"), "utf-8");

/**
 * Strip comments before any position analysis.
 *
 * Every R36x block quotes the PRE-fix code verbatim in its rationale
 * (`if (state.submitting) { toast(...); return; }`, the two
 * decision-card `return`s, ...), so a naive regex matches the comment
 * and every ordering assertion silently inverts. Comments are not code.
 */
// R364: the stripper moved to scripts/test/strip-comments.mjs, shared by
// every source-pin gate. The inline regex version that lived here was
// wrong: `line.ts` contains the string "...no update or delete RPC on
// memory/* ...", and a regex stripper reads that `/*` as a comment
// opener and blanks the next 916 lines of REAL CODE. Every assertion
// over that window was silently testing whitespace. The shared version
// is a scanner (it tracks string + template literals) and it throws if
// it ever looks like it has desynced again.

const tuiCode = stripComments(tui);
const lineCode = stripComments(line);
const lineInputCode = stripComments(lineInput);

// --- the state shape -------------------------------------------------------

test("State declares activeRunId + cancelRequested and seeds both to a safe default", () => {
  assert.match(src, /activeRunId:\s*string \| null/);
  assert.match(src, /cancelRequested:\s*boolean/);
  assert.equal(initialState.activeRunId, null,
    "a fresh session has no run it could cancel — Esc must not fire blind");
  assert.equal(initialState.cancelRequested, false,
    "a fresh session has no pending interrupt");
});

test("the three run-id actions exist", () => {
  assert.match(src, /type:\s*"setActiveRunId";\s*runId:\s*string \| null/);
  assert.match(src, /type:\s*"requestCancelRun"/);
  assert.match(src, /type:\s*"setCancelSettled"/);
});

// --- reducer behaviour -----------------------------------------------------

const setRun = (s, runId) => reducer(s, { type: "setActiveRunId", runId });
const askCancel = (s) => reducer(s, { type: "requestCancelRun" });
const settled = (s) => reducer(s, { type: "setCancelSettled" });

test("setActiveRunId stores the id the query response handed us", () => {
  const s = setRun(initialState, "run-7");
  assert.equal(s.activeRunId, "run-7");
});

test("setActiveRunId with the same id is a no-op (no re-render churn)", () => {
  const s = setRun(setRun(initialState, "run-7"), "run-7");
  assert.equal(s.activeRunId, "run-7");
});

test("requestCancelRun latches — a second Esc cannot double-fire", () => {
  let s = setRun(initialState, "run-7");
  s = askCancel(s);
  assert.equal(s.cancelRequested, true);
  const again = askCancel(s);
  assert.equal(again, s,
    "the reducer must return the identical state object, not a copy with the same fields");
});

test("setCancelSettled releases the latch when the cancel RPC itself fails", () => {
  let s = setRun(initialState, "run-7");
  s = askCancel(s);
  s = settled(s);
  assert.equal(s.cancelRequested, false,
    "a failed cancel must not leave the strip claiming 'stopping...' forever");
});

test("setCancelSettled on a clean run is a no-op", () => {
  assert.equal(settled(initialState), initialState);
});

test("submit clears the PREVIOUS run id so a stray Esc cannot cancel a dead run", () => {
  let s = setRun(initialState, "run-7");
  s = { ...s, input: "next prompt", submitting: false };
  s = reducer(s, { type: "submit" });
  assert.equal(s.activeRunId, null);
  assert.equal(s.cancelRequested, false);
});

test("submit re-arms the interrupt for the new turn", () => {
  // The failure mode this guards: cancelRequested stays true after a
  // cancelled turn, so the FIRST Esc of the following turn is a silent
  // no-op and the run looks uncancellable again.
  let s = { ...setRun(initialState, "run-7"), input: "next", submitting: false };
  s = askCancel(s);
  s = { ...s, input: "next", submitting: false };
  s = reducer(s, { type: "submit" });
  assert.equal(s.cancelRequested, false);
  assert.equal(s.activeRunId, null);
});

test("streamEnd releases the run id and the cancel latch", () => {
  let s = { ...initialState, submitting: true, status: "streaming" };
  s = setRun(s, "run-9");
  s = askCancel(s);
  s = reducer(s, { type: "streamEnd", stopReason: "cancelled" });
  assert.equal(s.activeRunId, null,
    "the run is over — its id is no longer cancellable");
  assert.equal(s.cancelRequested, false,
    "a pending cancel is moot once the terminal event lands; leaving it set disarms Esc");
  assert.equal(s.submitting, false);
});

// --- source pins: the id must actually be captured ------------------------

test("tui.tsx reads runId out of the query response instead of discarding it", () => {
  assert.match(tuiCode, /request<[^>]*>\(\s*"query"/,
    "the query call must be typed so the response is readable");
  assert.doesNotMatch(tuiCode, /request<unknown>\(\s*"query"/,
    "request<unknown>(\"query\") is the pre-R363 shape — the runId it returns is discarded");
  // Specific to sendPrompt, not just "setActiveRunId appears somewhere".
  // A whole-tree `setActiveRunId` match is satisfied by the two
  // notification fallbacks alone, so deleting the query-response
  // capture entirely would still pass — which is exactly the mutation
  // that must fail.
  const start = tuiCode.indexOf("async function sendPrompt");
  assert.ok(start > -1, "sendPrompt not found — the test needs re-anchoring");
  const body = tuiCode.slice(start, tuiCode.indexOf("\n  }", start));
  assert.match(body, /resp\.runId/,
    "sendPrompt is the ONLY reliable source of the runId; it must read resp.runId");
  assert.match(body, /busyRunId/,
    "a session-lock refusal returns busyRunId, not runId — adopting only the " +
    "latter leaves Esc unable to stop the run that is actually blocking us");
  assert.match(body, /setActiveRunId/,
    "the captured id must reach the reducer");
});

test("a daemon refusal does not leave the TUI in a permanent streaming state", () => {
  const start = tuiCode.indexOf("async function sendPrompt");
  const body = tuiCode.slice(start, tuiCode.indexOf("\n  }", start));
  assert.match(body, /accepted === false/,
    "a refused query has no run to watch — the TUI must notice and stop pretending");
  assert.match(body, /streamEnd/,
    "leaving submitting=true on a refused query hangs the queue drain forever");
});

/**
 * Body of the top-level function whose declaration starts at `decl`.
 * Scoped to the function, so an assertion can never leak onto a
 * same-named token in a neighbouring function — and so it cannot be
 * broken by a comment added somewhere else in the file.
 */
function bodyAfter(src, decl) {
  const start = src.indexOf(decl);
  if (start < 0) return null;
  const end = src.indexOf("\n  }", start);
  return end > start ? src.slice(start, end) : null;
}

test("tui.tsx has a stopActiveRun that guards on a known runId", () => {
  // R375: the function was renamed from `cancelActiveRun` to
  // `stopActiveRun` when the status bar's Stop button and the `/stop`
  // command were wired to it as well — one implementation of "stop"
  // instead of three diverging ones.
  //
  // The RPC name is no longer a literal here: it comes from the shared
  // `RUN_STOP_METHOD` constant, so this gate pins that the call goes
  // through the shared vocabulary, and `r375-run-control.test.mjs`
  // pins that the constant IS `cancel`. Splitting it that way keeps
  // each gate's claim small enough to stay true.
  const body = bodyAfter(tuiCode, "async function stopActiveRun");
  assert.ok(body, "stopActiveRun not found (re-anchor this test)");
  assert.match(body, /request\(\s*RUN_STOP_METHOD/,
    "stopActiveRun must actually call the shared stop RPC");
  assert.match(body, /!runId[\s\S]*?pushToast/,
    "a cancel with no runId must degrade to a human-readable notice, not a doomed RPC");
});

test("tui.tsx captures the runId from BOTH the query response and the notifications", () => {
  // Scoped to each notification's own `case` block. A whole-file
  // proximity match would be satisfied by any one of the three sources,
  // which is how a gate can pass with a fallback deleted.
  const cases = [...tuiCode.matchAll(/case\s*"([a-z_]+)":\s*\{/g)];
  const bodyOf = (name) => {
    const m = cases.find((c) => c[1] === name);
    if (!m) return null;
    const end = tuiCode.indexOf("\n        }", m.index);
    return tuiCode.slice(m.index, end > m.index ? end : undefined);
  };
  const stream = bodyOf("stream_event");
  assert.ok(stream, "stream_event case not found (re-anchor this test)");
  assert.match(stream, /setActiveRunId/,
    "stream_event must be able to restore the runId if the query reply was lost");

  const perm = bodyOf("permission_request");
  assert.ok(perm, "permission_request case not found (re-anchor this test)");
  assert.match(perm, /setActiveRunId/,
    "a permission ask proves a live run and carries the id — the last chance before the first token");
});

// --- source pins: the Esc ladder ------------------------------------------

test("the Esc-cancel block sits ABOVE the decision-card branches", () => {
  const cancelIdx = tuiCode.indexOf("void stopActiveRun()");
  // Anchor on the `key.ctrl && input === "l"` branch, which sits below
  // the whole permission / decision-card region. Anchoring on
  // `setDecisionCardExpanded` itself is wrong: the FIRST occurrence is
  // the collapse branch, so "cancel is before the card branches" would
  // still hold with the block physically below them.
  const downstreamIdx = tuiCode.indexOf('if (key.ctrl && input === "l")');
  const cardIdx = tuiCode.indexOf("setDecisionCardExpanded");
  assert.ok(cancelIdx > -1, "no Esc→cancel wiring found in tui.tsx");
  assert.ok(cardIdx > -1, "decision-card branch not found (the test needs re-anchoring)");
  assert.ok(downstreamIdx > -1, "downstream anchor not found (the test needs re-anchoring)");
  assert.ok(cardIdx < downstreamIdx, "the anchors are in the wrong order — re-anchor the test");
  assert.ok(cancelIdx < cardIdx,
    "the decision-card branches `return` on Esc; if they run first, a live run is " +
    "structurally uncancellable no matter what the reducer says");
});

test("Esc closes overlays before it stops a run (close-what-you-opened wins)", () => {
  // Index arithmetic, not `idx - N`. See the sibling test for why a
  // length-anchored slice is a time bomb.
  const start = tuiCode.indexOf("if (key.escape) {");
  const next = tuiCode.indexOf("if (key.escape) {", start + 1);
  assert.ok(start > -1 && next > start, "Esc branches not found — re-anchor this test");
  const block = tuiCode.slice(start, next);
  assert.match(block, /showSearch/,
    "an open search bar must swallow Esc instead of cancelling the run");
  assert.match(block, /helpVisible/,
    "an open help overlay must swallow Esc instead of cancelling the run");
  assert.ok(block.indexOf("showSearch") < block.indexOf("void stopActiveRun()"),
    "the overlay checks must come first, or Esc both closes the overlay " +
    "and cancels the run on the same keypress");
  assert.ok(block.indexOf("helpVisible") < block.indexOf("void stopActiveRun()"),
    "same for the help overlay");
});

test("Esc stops a live run BEFORE it drops the queue", () => {
  // The invariant is ORDER, not adjacency. Between the cancel branch and
  // the queue-drop branch live the permission / decision-card branches —
  // and that is correct: the cancel branch `return`s while a run is in
  // flight, so those can never pre-empt it. (The sibling test pins
  // cancel-before-card, which is the part that actually matters.)
  //
  // A previous version of this test demanded the queue-drop be in the
  // IMMEDIATELY following block, which was never the design.
  //
  // No character windows: enumerate the block starts and compare
  // indices, so an unrelated comment cannot move the answer.
  const starts = [];
  for (let i = tuiCode.indexOf("if (key.escape) {"); i >= 0;
       i = tuiCode.indexOf("if (key.escape) {", i + 1)) {
    starts.push(i);
  }
  assert.ok(starts.length >= 2,
    `expected several Esc branches in the key handler; found ${starts.length}`);

  const cancelIdx = starts.find((i) =>
    tuiCode.slice(i, i + 2000).includes("void stopActiveRun()"));
  assert.ok(cancelIdx !== undefined,
    "no Esc branch that cancels the run — re-anchor this test");

  const queueIdx = starts.find((i) =>
    tuiCode.slice(i, i + 2000).includes("clearPendingPrompts"));
  assert.ok(queueIdx !== undefined,
    "no Esc branch that drops the queue — re-anchor this test");

  assert.ok(cancelIdx < queueIdx,
    "the cancel branch must precede the queue-drop: with a run in flight " +
    "Esc must stop the run, not silently discard what the user queued");
});

test("the interrupt affordance is rendered, not hidden in the help screen", () => {
  assert.match(tuiCode, /esc to interrupt/,
    "a stop control that exists only in /help is not a stop control");
  assert.match(tuiCode, /stopping\.\.\. \(interrupt sent\)/,
    "the async cancel needs its own in-flight state, or the strip lies for up to 5s");
});

// --- line mode parity -----------------------------------------------------

test("LineInput fires onEscape on a BARE Esc only, not on a CSI sequence", () => {
  assert.match(lineInputCode, /onEscape\?:\s*\(\)\s*=>\s*void/,
    "the callback must be optional so existing hosts keep compiling");
  const idx = lineInputCode.indexOf("onEscape()");
  assert.ok(idx > -1, "LineInput never calls onEscape");
  // The `if (text[i] === "[")` guard must come FIRST and `continue`
  // past it — otherwise every arrow keypress also fires the interrupt.
  const bare = lineInputCode.lastIndexOf("if (this.cbs.onEscape)", idx);
  const csi = lineInputCode.lastIndexOf('if (text[i] === "[")', idx);
  assert.ok(csi > -1 && bare > csi,
    "the CSI consume must precede the bare-Esc dispatch, or arrow keys cancel the run");
});

test("line mode captures the runId and calls the same cancel RPC", () => {
  assert.match(lineCode, /activeRunId/);
  assert.match(lineCode, /resp\.runId/,
    "line mode must capture the query response's runId like the Ink TUI does");
  const body = bodyAfter(lineCode, "async function stopActiveRun");
  assert.ok(body, "line mode's stopActiveRun not found (re-anchor this test)");
  assert.match(body, /request\(\s*RUN_STOP_METHOD/);
});

test("line mode resets both the id and the latch on run_end", () => {
  // Anchor on the arrow function's own closing brace rather than a
  // char count, so the assertion cannot drift onto a neighbouring
  // function when the body grows.
  const start = lineCode.indexOf("const onRunEnd");
  assert.ok(start > -1, "onRunEnd not found (re-anchor this test)");
  const end = lineCode.indexOf("\n  };", start);
  assert.ok(end > start, "onRunEnd's closing brace not found (re-anchor this test)");
  const body = lineCode.slice(start, end);
  assert.match(body, /activeRunId\s*=\s*null/,
    "a stale runId means Esc cancels a run that already finished");
  assert.match(body, /cancelSent\s*=\s*false/,
    "a stuck latch means Esc is dead for the rest of the session");
});

test("line mode's LineInput gets the onEscape wiring", () => {
  assert.match(lineCode, /onEscape:\s*\(\)\s*=>/,
    "line mode must wire Esc to the interrupt, or the parity claim is false");
  assert.match(lineCode, /onEscape:[\s\S]{0,200}stopActiveRun/);
});

// --- the daemon contract we depend on -------------------------------------

test("the daemon really does key cancel by runId (our guard is not paranoia)", () => {
  const java = join(root, "..", "aethercode", "aethercode-protocol", "src", "main", "java",
    "org", "aethercode", "protocol", "methods", "AetherCodeMethods.java");
  if (!existsSync(java)) {
    // The protocol module is not checked out next to the TUI in every
    // layout. Skipping beats a false red.
    return;
  }
  const j = readFileSync(java, "utf-8");
  assert.match(j, /runId/,
    "cancel's contract changed — re-check the TUI's runId guard");
});