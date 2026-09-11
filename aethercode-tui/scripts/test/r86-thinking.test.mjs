// R86: tests for the thinking-tag parser in the TUI reducer.
//
// The reducer's `streamText` action splits streamed model text
// into "reply" and "thinking" segments based on the
// `<think>` / `</think>` markers. We test:
//
//   1. A delta that is fully inside a <think>…</think> opens a
//      "thinking" turn (role: "thinking").
//   2. A delta that contains a <think> then text opens a
//      thinking turn, then an assistant turn for the reply.
//   3. A </think> mid-delta closes the thinking turn and any
//      following text lands in the assistant turn.
//   4. The tag marker may be split across two deltas — the
//      reducer holds a small lookahead buffer to catch that.
//   5. Multiple open/close cycles produce multiple turns.
//
// The defaultCollapsed behaviour for "thinking" is true (dim
// sub-section) — we test that too so a regression doesn't
// surface thinking content over the actual answer.
//
// Run: node scripts/test/r86-thinking.test.mjs (called by `npm test`).

import { test } from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { existsSync, rmSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const tmp = join(root, "tmp-test-r86");
if (existsSync(tmp)) rmSync(tmp, { recursive: true, force: true });

// Compile state.ts to a tmp JS file we can import.
// (Mirrors state.test.mjs's setup.)
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

const mod = await import("file:///" + join(tmp, "state.js").replace(/\\/g, "/"));
const { reducer, INITIAL } = mod;

function start() {
  return reducer(INITIAL, { type: "streamStart" });
}

function txt(state, s) {
  return reducer(state, { type: "streamText", text: s });
}

function end(state) {
  return reducer(state, { type: "streamEnd", stopReason: "end_turn" });
}

function tails(state, role) {
  return state.turns.filter((t) => t.role === role).map((t) => t.text);
}

test("R86: a delta that opens <think> starts a thinking turn", () => {
  let s = start();
  s = txt(s, "<think>the user wants X");
  // The delta has 8 trailing chars that the parser can't yet
  // prove are NOT the start of a tag. After end() the run is
  // closed and the leftover is flushed into the thinking turn.
  s = end(s);
  assert.equal(s.streamingMode, "reply");
  assert.deepEqual(tails(s, "thinking"), ["the user wants X"]);
  // The assistant turn was created by streamStart but is still empty.
  const assistant = s.turns.filter((t) => t.role === "assistant");
  assert.equal(assistant.length, 1);
  assert.equal(assistant[0].text, "");
});

test("R86: a </think> mid-delta closes the thinking turn and opens an assistant turn for the rest", () => {
  let s = start();
  s = txt(s, "<think>reasoning</think>The answer is 42.");
  s = end(s);
  assert.equal(s.streamingMode, "reply");
  assert.deepEqual(tails(s, "thinking"), ["reasoning"]);
  // The end-of-run flush merges the leftover reply text into
  // the initial empty assistant turn, so we end up with ONE
  // assistant turn (the one with content). This is the desired
  // behaviour — the user doesn't see a redundant empty card at
  // the start of the run.
  assert.deepEqual(tails(s, "assistant"), ["The answer is 42."]);
});

test("R86: split tag across two deltas is caught by the lookahead buffer", () => {
  let s = start();
  // "<th" + "ink>foo</think>bar" — the <think> marker spans the two deltas.
  s = txt(s, "<th");
  assert.equal(s.streamingMode, "reply", "before the tag closes, mode is still reply");
  assert.equal(s.tagLookahead, "<th", "lookahead holds the partial marker");
  s = txt(s, "ink>foo</think>bar");
  s = end(s);
  assert.equal(s.streamingMode, "reply");
  assert.deepEqual(tails(s, "thinking"), ["foo"]);
  // Same merge behaviour as the previous test — the initial
  // empty assistant turn is merged with the trailing "bar".
  assert.deepEqual(tails(s, "assistant"), ["bar"]);
});

test("R86: multiple <think> / </think> cycles produce multiple turns", () => {
  let s = start();
  s = txt(s, "<think>A</think>mid1<think>B</think>final");
  s = end(s);
  assert.equal(s.streamingMode, "reply");
  // Two thinking turns ("A" and "B") are emitted — the parser
  // opens a fresh turn for each new <think>.
  assert.deepEqual(tails(s, "thinking"), ["A", "B"]);
  // The two reply segments ("mid1" and "final") land in the
  // SAME assistant turn because the second <think> closes the
  // first assistant segment, and streamEnd's flush puts "final"
  // back into the same assistant turn (it's the most recent
  // assistant turn in the list).
  const assistantTurns = s.turns.filter((t) => t.role === "assistant");
  // Note: the first assistant turn is empty (created by
  // streamStart). The actual content lives in the SECOND
  // assistant turn. So 2 assistant turns total.
  assert.equal(assistantTurns.length, 2);
  assert.equal(assistantTurns[0].text, "");
  assert.equal(assistantTurns[1].text, "mid1final");
});

test("R86: a single delta with no markers stays in the assistant turn", () => {
  let s = start();
  s = txt(s, "hello world");
  s = txt(s, ", how are you?");
  s = end(s);
  assert.equal(s.streamingMode, "reply");
  const assistantTurns = s.turns.filter((t) => t.role === "assistant");
  assert.equal(assistantTurns.length, 1);
  assert.equal(assistantTurns[0].text, "hello world, how are you?");
});

test("R86: streamStart resets the parser state to (reply, empty lookahead)", () => {
  let s = start();
  s = txt(s, "<think>first run");
  assert.equal(s.streamingMode, "thinking");
  // End the run + start a new one.
  s = end(s);
  s = reducer(s, { type: "streamStart" });
  assert.equal(s.streamingMode, "reply");
  assert.equal(s.tagLookahead, "");
  // New deltas land in the new assistant turn, not the previous thinking.
  s = txt(s, "new run");
  s = end(s);
  const assistantTurns = s.turns.filter((t) => t.role === "assistant");
  assert.equal(assistantTurns.length, 2);
  assert.equal(assistantTurns[1].text, "new run");
});

test("R86: thinking turns default to collapsed so the dim sub-section doesn't push the answer off screen", () => {
  // Default collapse state for the "thinking" role must be true.
  // We test by checking that after creating a thinking turn, the
  // turn's `collapsed` field is true. (Tested via the reducer
  // output, not the render layer.)
  let s = start();
  s = txt(s, "<think>hello</think>");
  const t = s.turns.find((x) => x.role === "thinking");
  assert.ok(t, "thinking turn was created");
  assert.equal(t.collapsed, true, "thinking turn starts collapsed");
});

test("R86: streamEnd flushes the leftover lookahead into the current mode's turn", () => {
  // If the model closes the connection or is cut off mid-stream
  // there may be text in the lookahead that never gets a chance
  // to land. streamEnd must flush it so the user doesn't see a
  // silently dropped tail. (The lookahead isn't bounded to 8
  // chars in this implementation — it's "everything after the
  // last tag we found". The streamEnd action flushes the whole
  // remainder, which is correct for a clean run close.)
  let s = start();
  s = txt(s, "<think>hello world");
  // Mid-stream, "hello world" is in the lookahead (we haven't
  // seen </think> yet).
  assert.equal(s.tagLookahead, "hello world");
  s = end(s);
  const t = s.turns.find((x) => x.role === "thinking");
  assert.equal(t.text, "hello world");
  assert.equal(s.tagLookahead, "");
  // Mode also resets to "reply" so a future streamStart starts
  // from a clean state.
  assert.equal(s.streamingMode, "reply");
});
