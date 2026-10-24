// R362 — real prompt queue.
//
// The 2026-10-21 benchmark found (report section 0.2):
//
//   "排队是假的: tui.tsx:798-805 在 state.submitting 为 true 时弹一个
//    toast 说 'model is still working - prompt queued', 然后 return ——
//    用户的输入被直接丢弃。state.ts 里只有 submitting: boolean, 没有任何
//    pendingPrompt / queuedInput / 队列数组。"
//
// Two defects in one place, and the second one was hiding the first:
//
//   1. `if (state.submitting) { toast; return; }` sat ABOVE the slash
//      handling, so despite the comment claiming "Slash commands stay local
//      to the TUI... so we still run them even if the model is busy", a
//      streaming run silently swallowed every slash command too.
//   2. prompts were discarded after a toast claimed they were queued.
//
// A toast that asserts a state the code does not implement is worse than no
// toast — the user believes their prompt is safe. These tests pin the real
// queue and the real ordering so neither can come back.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdirSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { codeOf as stripComments } from "./strip-comments.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const tmp = join(root, "tmp-r362-queue");

const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
if (existsSync(tmp)) {
  if (process.platform === "win32") spawnSync("cmd", ["/c", "rmdir", "/s", "/q", tmp]);
  else spawnSync("rm", ["-rf", tmp]);
}
mkdirSync(tmp, { recursive: true });

const r = spawnSync(
  `"${tscBin}"`,
  [
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
    join(root, "src"),
    `"${join(root, "src", "state.ts")}"`,
  ],
  { encoding: "utf-8", shell: true },
);
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

/**
 * Strip comments before any position analysis.
 *
 * The R362 comment block in handleSubmit quotes the OLD code verbatim
 * (`if (state.submitting) { toast(...); return; }`), so a naive regex
 * finds that first and every ordering assertion inverts. Comments are not
 * code; they must not satisfy or break a structural assertion.
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

// --- the state shape -------------------------------------------------------

test("State declares pendingPrompts and it is seeded empty", () => {
  assert.match(src, /pendingPrompts:\s*string\[\]/);
  assert.equal(
    initialState.pendingPrompts.length,
    0,
    "the queue must start empty on a fresh session",
  );
});

test("enqueue / dequeue / clear actions exist", () => {
  assert.match(src, /type:\s*"enqueuePrompt";\s*text:\s*string/);
  assert.match(src, /type:\s*"dequeuePrompt"/);
  assert.match(src, /type:\s*"clearPendingPrompts"/);
});

// --- behaviour -------------------------------------------------------------

const enq = (s, text) => reducer(s, { type: "enqueuePrompt", text });
const deq = (s) => reducer(s, { type: "dequeuePrompt" });
const clr = (s) => reducer(s, { type: "clearPendingPrompts" });

test("enqueuePrompt appends and preserves order", () => {
  let s = initialState;
  s = enq(s, "first");
  s = enq(s, "second");
  s = enq(s, "third");
  assert.deepEqual(
    s.pendingPrompts,
    ["first", "second", "third"],
    "the queue must be FIFO — the user's order is their intent",
  );
});

test("enqueuePrompt ignores blank input", () => {
  const s = enq(initialState, "   \n  ");
  assert.deepEqual(s.pendingPrompts, [], "whitespace-only input is not a prompt");
});

test("enqueuePrompt drops an immediate duplicate (double-tap of Enter)", () => {
  let s = enq(initialState, "same");
  s = enq(s, "same");
  assert.deepEqual(
    s.pendingPrompts,
    ["same"],
    "a double-tap of Enter must not queue the same line twice",
  );
  // but a non-adjacent duplicate is legitimate (the user may repeat a prompt)
  s = enq(s, "other");
  s = enq(s, "same");
  assert.deepEqual(s.pendingPrompts, ["same", "other", "same"]);
});

test("the queue is capped so a wedged daemon cannot grow it without bound", () => {
  let s = initialState;
  for (let i = 0; i < 40; i++) s = enq(s, `prompt-${i}`);
  assert.ok(s.pendingPrompts.length <= 20, `queue must be capped, got ${s.pendingPrompts.length}`);
  // the cap keeps the NEWEST entries — the user's most recent intent wins
  assert.equal(s.pendingPrompts[s.pendingPrompts.length - 1], "prompt-39");
});

test("enqueuePrompt records the prompt in history", () => {
  const s = enq(initialState, "remember me");
  assert.ok(
    s.history.includes("remember me"),
    "a queued prompt must survive into history so ↑ recalls it",
  );
});

test("dequeuePrompt is FIFO", () => {
  let s = enq(initialState, "a");
  s = enq(s, "b");
  s = deq(s);
  assert.deepEqual(s.pendingPrompts, ["b"]);
  s = deq(s);
  assert.deepEqual(s.pendingPrompts, []);
});

test("dequeue / clear on an empty queue are no-ops (same state identity)", () => {
  assert.equal(deq(initialState), initialState);
  assert.equal(clr(initialState), initialState);
});

// --- the two bugs that actually shipped ------------------------------------

test("BUG 1 — slash commands are no longer gated behind `state.submitting`", () => {
  // The old code was:
  //     if (state.submitting) { dispatch(toast); return; }
  //     const slash = handleSlash(text);
  // i.e. the streaming guard came FIRST, so /help /memory /model were all
  // swallowed mid-run despite the comment saying otherwise.
  const body = tuiCode.slice(tuiCode.indexOf("async function handleSubmit"));
  const slashAt = body.indexOf("handleSlash(text)");
  const guardAt = body.indexOf("if (state.submitting)");
  assert.ok(slashAt > 0, "handleSubmit must still call handleSlash");
  assert.ok(guardAt > 0, "handleSubmit must still gate prompts while streaming");
  assert.ok(
    slashAt < guardAt,
    "handleSlash must run BEFORE the submitting guard, or slash commands " +
      "get swallowed while a run is in flight (the R362 defect)",
  );
});

test("BUG 2 — a prompt typed while streaming is queued, not dropped", () => {
  const body = tuiCode.slice(tuiCode.indexOf("async function handleSubmit"));
  const guardAt = body.indexOf("if (state.submitting)");
  const guard = body.slice(guardAt, guardAt + 400);
  assert.match(guard, /enqueuePrompt/, "the streaming path must enqueue, not silently return");
  assert.match(
    tuiCode,
    /sends when this run ends/,
    "the toast must state when the prompt will actually be sent",
  );
});

test("the fake-queue toast string is gone", () => {
  assert.doesNotMatch(
    tui,
    /model is still working — prompt queued/,
    "the original string claimed a queue that did not exist; it must not " + "come back verbatim",
  );
});

test("there is a real drain path", () => {
  // An effect (not a reducer side effect) sends the next prompt once the run
  // ends, reusing the same sendPrompt the live submit uses.
  assert.match(tuiCode, /pendingPrompts\[0\]/, "the drain must read the head of the queue");
  assert.match(tuiCode, /dequeuePrompt/);
  assert.match(
    tuiCode,
    /void sendPrompt\(next\)/,
    "the drain must reuse sendPrompt so queued and live prompts behave identically",
  );
});

test("queued and live prompts share one send path", () => {
  // If they diverged, a queued prompt would produce a different turn entry,
  // status transition or error card than a directly-typed one.
  assert.match(tuiCode, /async function sendPrompt\(text: string\)/);
  assert.match(tuiCode, /void sendPrompt\(text\);/, "handleSubmit must route through sendPrompt");
  // exactly one query RPC call site.
  //
  // R363: this used to count `client.request<unknown>("query"` literally.
  // R363 had to type that call (`request<{runId, accepted...}>`) to read
  // the runId the cancel RPC needs, which zeroed the count and turned this
  // into a false red. The invariant is "one call site", not "one
  // particular type argument" — match the call, not its generic.
  const sites = tuiCode.match(/client\.request(?:<[^>]*>)?\(\s*"query"/g) || [];
  assert.equal(
    sites.length,
    1,
    "there must be exactly one `query` RPC call site; more means the queued " +
      `path has its own copy that can drift (found ${sites.length})`,
  );
});

test("the queue is visible, not invisible", () => {
  // Claude Code's contract: "lists the queued entries in the conversation
  // until it sends them". A queue the user cannot see is one they will
  // assume was lost and retype.
  assert.match(tuiCode, /pendingPrompts\.length > 0/);
  assert.match(tuiCode, /queued next/);
  assert.match(tuiCode, /function truncateForQueue/);
});

test("the user can cancel the queue (Esc)", () => {
  // Otherwise a queued prompt can only ever be sent, never dropped — the
  // same "no user control" defect the PM review flagged on the stale banner.
  assert.match(tuiCode, /clearPendingPrompts/);
  assert.match(
    tuiCode,
    /esc drops the queue/,
    "the strip advertises Esc, so Esc must actually drop the queue",
  );
});
