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

// R372 — replay a session's transcript into the scrollback.
//
// ## What was missing
//
// R370 made `/sessions` a real picker and the daemon genuinely
// continues the picked session (the TUI sends `query` with no
// sessionId, so the daemon's ACTIVE session answers). What the user
// got was an empty scrollback and the sentence "history is not
// replayed here".
//
// That sentence was honest and it was also the symptom: the renderer
// had no messages→turns converter at all. The daemon had the data the
// whole time — `getTranscript` answers `{ok, sessionId, messages}` and
// is routed on HTTP — and nothing read it.
//
// ## The three things the converter must not invent
//
//   1. **Durations.** A replayed tool call has no start and no end.
//      `toolStartedAt` stays undefined and ToolCard renders "—". A
//      made-up duration is a fabricated measurement, which is exactly
//      what R371's "no usage reported yet" refused to be.
//   2. **Results for calls that have none.** A `tool_use` with no
//      matching `tool_result` is an interrupted run, not a success.
//   3. **A complete history.** Long sessions hold thousands of
//      messages; only the tail is replayed, and the count left out is
//      reported.
//
// The converter is a PURE function, so most of this file is
// behavioural rather than a source pin. A source pin can only check
// that the code mentions something; these check what it does.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdirSync, mkdtempSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { codeOf } from "./strip-comments.mjs";
import { javaCode, javaMethod } from "./java-strip.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const repo = join(root, "..");

const tuiSrc = codeOf(readFileSync(join(root, "src", "tui.tsx"), "utf-8"), "tui.tsx");
const lineSrc = codeOf(readFileSync(join(root, "src", "line.ts"), "utf-8"), "line.ts");
const stateSrc = codeOf(readFileSync(join(root, "src", "state.ts"), "utf-8"), "state.ts");

const methodsPath = join(
  repo,
  "aethercode",
  "aethercode-protocol",
  "src",
  "main",
  "java",
  "org",
  "aethercode",
  "protocol",
  "methods",
  "AetherCodeMethods.java",
);
const httpPath = join(
  repo,
  "aethercode",
  "aethercode-protocol",
  "src",
  "main",
  "java",
  "org",
  "aethercode",
  "protocol",
  "http",
  "HttpJsonRpcServer.java",
);
const methodsCode = javaCode(readFileSync(methodsPath, "utf-8"));
const httpCode = javaCode(readFileSync(httpPath, "utf-8"));

/** Bounded extraction by BRACE MATCHING. */
function block(src, header) {
  const at = src.search(header);
  assert.notEqual(at, -1, `block(): header not found: ${header}`);
  const open = src.indexOf("{", at);
  let depth = 0;
  for (let i = open; i < src.length; i++) {
    if (src[i] === "{") depth++;
    else if (src[i] === "}" && --depth === 0) return src.slice(open, i + 1);
  }
  throw new Error(`block(): unbalanced braces after ${header}`);
}

/**
 * The bodies of every `catch { … }` in a source.
 *
 * A character window would be the obvious way to ask "is the failure
 * reported inside the catch?" — and it is a GUESS. The first draft
 * used `catch[\s\S]{0,400}?…` and failed, because the three comment
 * lines above the toast are 180 characters that `codeOf` blanks but
 * does not shorten. A guessed window fails by going green, and the
 * fix is to stop guessing: match the braces.
 */
function catchBodies(src) {
  const out = [];
  const re = /catch\s*(?:\([^)]*\))?\s*(?:=>\s*)?\{/g;
  let m;
  while ((m = re.exec(src)) !== null) {
    const open = src.indexOf("{", m.index);
    let depth = 0;
    for (let i = open; i < src.length; i++) {
      if (src[i] === "{") depth++;
      else if (src[i] === "}" && --depth === 0) {
        out.push(src.slice(open + 1, i));
        break;
      }
    }
  }
  return out;
}

// --- compiled modules ----------------------------------------------------

const tmp = mkdtempSync(join(tmpdir(), "r372-"));
const tsc = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
const compiled = spawnSync(
  `"${tsc}"`,
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
    `"${join(root, "src", "transcriptHydrate.ts")}"`,
    `"${join(root, "src", "state.ts")}"`,
  ],
  { encoding: "utf-8", shell: true },
);
if (compiled.status !== 0) {
  console.error("tsc failed:\n" + compiled.stdout + "\n" + compiled.stderr);
  process.exit(1);
}
const imp = (f) => import(pathToFileURL(join(tmp, "out", f)).href);
const { replayTranscript, toolResultText, extractMessages, DEFAULT_REPLAY_LIMIT } =
  await imp("transcriptHydrate.js");
const { reducer, INITIAL } = await imp("state.js");

/** A wire message, as `Message.toMap()` emits it. */
const msg = (role, content, ts = 1000) => ({ id: `m${ts}`, role, content, timestamp: ts });

// =========================================================================
test("a text message becomes one turn per text block, in order", () => {
  const r = replayTranscript(
    [
      msg("user", [{ type: "text", text: "hello" }]),
      msg("assistant", [{ type: "text", text: "hi there" }], 2000),
    ],
    1,
  );
  assert.equal(r.turns.length, 2);
  assert.equal(r.turns[0].role, "user");
  assert.equal(r.turns[0].text, "hello");
  assert.equal(r.turns[1].role, "assistant");
  assert.equal(r.turns[1].text, "hi there");
  // ids must be unique and start where the caller asked
  assert.equal(r.turns[0].id, 1);
  assert.equal(r.turns[1].id, 2);
  // the wire timestamp is preserved — the replayed timeline is the real one
  assert.equal(r.turns[0].ts, 1000);
  assert.equal(r.turns[1].ts, 2000);
});

test("the answer's envelope shape is accepted as well as a bare array", () => {
  // `getTranscript` answers `{ok, sessionId, messages}`; other
  // surfaces have answered a bare array. Accept both, or the replay
  // silently produces nothing on one of them.
  const bare = replayTranscript([msg("user", [{ type: "text", text: "x" }])], 1);
  const wrapped = replayTranscript(
    { ok: true, sessionId: "s", messages: [msg("user", [{ type: "text", text: "x" }])] },
    1,
  );
  assert.equal(bare.turns.length, 1);
  assert.equal(wrapped.turns.length, 1);
  assert.deepEqual(extractMessages({ messages: [1, 2] }), [1, 2]);
  assert.deepEqual(extractMessages([3]), [3]);
  assert.deepEqual(extractMessages(null), []);
});

test("a tool_use and its tool_result become ONE turn, not two", () => {
  // The result lives in the NEXT message (the protocol's synthetic
  // user message). If the converter emitted a separate turn for it,
  // the user would see a second, unlabelled blob after every tool
  // card.
  const r = replayTranscript(
    [
      msg("assistant", [
        { type: "tool_use", id: "call_1", name: "file_read", input: { file_path: "a.ts" } },
      ]),
      msg("tool_result", [
        { type: "tool_result", toolUseId: "call_1", content: "the file contents", isError: false },
      ]),
    ],
    1,
  );
  assert.equal(r.turns.length, 1, "the tool result was rendered as its own turn");
  const t = r.turns[0];
  assert.equal(t.role, "tool");
  assert.equal(t.toolName, "file_read");
  assert.equal(t.toolStatus, "ok");
  assert.equal(t.toolResult, "the file contents");
  assert.ok(t.toolArgs.includes("a.ts"), "the tool arguments were lost");
  assert.equal(t.toolCategory, "read", "the category drives the icon and colour");
});

test("an errored tool is marked as an error", () => {
  const r = replayTranscript(
    [
      msg("assistant", [{ type: "tool_use", id: "call_1", name: "bash", input: {} }]),
      msg("tool_result", [
        { type: "tool_result", toolUseId: "call_1", content: "boom", isError: true },
      ]),
    ],
    1,
  );
  assert.equal(r.turns[0].toolStatus, "error");
  assert.equal(r.turns[0].toolResult, "boom");
});

test("a tool_result with no matching tool_use is dropped, not rendered as a turn", () => {
  // A result for a call we never saw is noise; rendering it would
  // put an unlabelled blob in the scrollback.
  const r = replayTranscript(
    [
      msg("tool_result", [
        { type: "tool_result", toolUseId: "call_ghost", content: "orphan", isError: false },
      ]),
    ],
    1,
  );
  assert.equal(r.turns.length, 0, "an orphan tool_result produced a turn");
});

test("an orphan tool_result does not CONTAMINATE the call before it", () => {
  // The stronger half of the same rule, and the one a naive
  // "attach to the last turn" implementation gets wrong: the orphan
  // is not merely invisible, it must not overwrite the result of a
  // call that DID get one.
  const r = replayTranscript(
    [
      msg("assistant", [{ type: "tool_use", id: "call_1", name: "file_read", input: {} }]),
      msg("tool_result", [
        { type: "tool_result", toolUseId: "call_1", content: "the real result", isError: false },
      ]),
      msg("tool_result", [
        { type: "tool_result", toolUseId: "call_ghost", content: "ORPHAN", isError: false },
      ]),
    ],
    1,
  );
  assert.equal(r.turns.length, 1, "the orphan produced a turn of its own");
  assert.equal(
    r.turns[0].toolResult,
    "the real result",
    `the orphan overwrote a real result: ${JSON.stringify(r.turns[0].toolResult)}`,
  );
  assert.ok(
    !/ORPHAN/.test(r.turns[0].toolResult ?? ""),
    "an orphan result was attached to a call that did not ask for it",
  );
});

test("a tool_use with NO result is labelled as interrupted, not as a success", () => {
  // The protocol says every tool_use gets a tool_result. A missing
  // one means the run was interrupted. Reporting "ok" with an empty
  // result would be a fabricated success — the exact failure mode
  // this round exists to prevent.
  const r = replayTranscript(
    [msg("assistant", [{ type: "tool_use", id: "call_1", name: "bash", input: {} }])],
    1,
  );
  assert.equal(r.turns.length, 1);
  assert.ok(
    /no recorded result/.test(r.turns[0].toolResult ?? ""),
    `an interrupted tool call was rendered as ${JSON.stringify(r.turns[0].toolResult)}`,
  );
  assert.equal(
    r.turns[0].toolStatus,
    "ok",
    "the status is not a lie, but the text must say what happened",
  );
});

test("no replayed tool call invents a duration", () => {
  // `ToolCard.computeDuration` returns "—" when toolStartedAt is
  // null, so leaving it undefined is the honest rendering. Setting it
  // would draw a made-up number.
  const r = replayTranscript(
    [
      msg("assistant", [{ type: "tool_use", id: "call_1", name: "bash", input: {} }]),
      msg("tool_result", [
        { type: "tool_result", toolUseId: "call_1", content: "ok", isError: false },
      ]),
    ],
    1,
  );
  assert.equal(r.turns[0].toolStartedAt, undefined, "a replayed tool call claims a start time");
  assert.equal(r.turns[0].toolEndedAt, undefined, "a replayed tool call claims an end time");
});

test("the tool_result content shapes that actually occur are all read", () => {
  // The field is typed `Object` on the wire. These are the shapes
  // that show up: a plain string, a single text object, and the
  // Anthropic content-block array.
  assert.equal(toolResultText("plain"), "plain");
  assert.equal(toolResultText({ type: "text", text: "obj" }), "obj");
  assert.equal(
    toolResultText([
      { type: "text", text: "a" },
      { type: "text", text: "b" },
    ]),
    "a\nb",
  );
  assert.equal(toolResultText(["raw", { type: "text", text: "b" }]), "raw\nb");
  assert.equal(toolResultText(null), "");
  assert.equal(toolResultText(undefined), "");
  // Something unrecognised is stringified, not dropped: a result the
  // user cannot see is worse than an ugly one.
  assert.ok(
    toolResultText({ weird: 1 }).includes("weird"),
    "an unrecognised result shape was silently dropped",
  );
});

test("an empty or whitespace-only text block produces no turn", () => {
  const r = replayTranscript(
    [
      msg("assistant", [
        { type: "text", text: "" },
        { type: "text", text: "   " },
      ]),
    ],
    1,
  );
  assert.equal(r.turns.length, 0, "blank text produced empty turns");
});

test("only the tail is replayed, and the count left out is reported", () => {
  const many = Array.from({ length: 500 }, (_, i) =>
    msg(i % 2 === 0 ? "user" : "assistant", [{ type: "text", text: `m${i}` }], 1000 + i),
  );
  const r = replayTranscript(many, 1);
  assert.equal(r.totalMessages, 500);
  assert.equal(r.replayedMessages, DEFAULT_REPLAY_LIMIT);
  assert.equal(r.turns.length, DEFAULT_REPLAY_LIMIT);
  // The TAIL, not the head: the newest messages are the ones the
  // conversation is about.
  assert.ok(
    r.turns[r.turns.length - 1].text.includes("m499"),
    "the replay kept the OLDEST messages instead of the newest",
  );
  assert.ok(!r.turns[0].text.includes("m0"), "the replay kept the head of the transcript");
});

test("the replay limit is honoured when the caller passes one", () => {
  const many = Array.from({ length: 20 }, (_, i) =>
    msg("user", [{ type: "text", text: `m${i}` }], 1000 + i),
  );
  const r = replayTranscript(many, 1, 5);
  assert.equal(r.replayedMessages, 5);
  assert.equal(r.totalMessages, 20);
  assert.ok(r.turns[4].text.includes("m19"), "the tail was not the tail");
});

test("a malformed message does not take the whole replay down", () => {
  const r = replayTranscript(
    [
      null,
      "not an object",
      { role: "user" },
      { role: "user", content: "not an array" },
      { role: "user", content: [null, "x", { type: "text", text: "kept" }] },
    ],
    1,
  );
  assert.equal(r.turns.length, 1, "malformed input dropped the good messages too");
  assert.equal(r.turns[0].text, "kept");
});

test("a message with no timestamp replays without inventing one", () => {
  const r = replayTranscript([{ role: "user", content: [{ type: "text", text: "x" }] }], 1);
  assert.equal(r.turns.length, 1);
  assert.equal(r.turns[0].ts, 0, "a missing timestamp became a plausible-looking number");
});

// =========================================================================
test("the daemon really serves the transcript on both transports", () => {
  // `getTranscript` is registered on the stdio dispatcher and routed
  // on HTTP. The TUI speaks HTTP, so the HTTP arm is the one that
  // matters — and a stdio-only registration is the R720/R367/R371
  // shape all over again.
  assert.ok(
    /dispatcher\.register\("getTranscript"/.test(methodsCode),
    "getTranscript is not on the stdio dispatcher",
  );
  const http = javaMethod(httpCode, "private Object dispatch(");
  assert.ok(
    /case "getTranscript"/.test(http),
    "HttpJsonRpcServer does not route getTranscript — the TUI would get -32601",
  );
});

test("both renderers read the transcript after a successful loadSession", () => {
  // Scoped to the picker, not the whole file: `viewPrimary` also
  // calls loadSession, 600 lines earlier, and a whole-file scan
  // reads THAT one. (R370's lesson, twice.)
  const ink = block(tuiSrc, /case "threads":/);
  assert.ok(/getTranscript/.test(ink), "the Ink picker never reads the transcript");
  assert.ok(/replayTranscript/.test(ink), "the Ink picker does not replay it");

  const line = block(lineSrc, /async function panelThreads\(/);
  assert.ok(/getTranscript/.test(line), "line mode never reads the transcript");
  assert.ok(/replayTranscript/.test(line), "line mode does not replay it");
});

test("the switch and the replay land in ONE action", () => {
  // Two dispatches (clear, then fill) would leave a window where the
  // scrollback is empty and the header names the new session.
  const ink = block(tuiSrc, /case "threads":/);
  const adopt = /dispatch\(\{\s*type:\s*"adoptSession"[^}]*\}\)/.exec(ink);
  assert.ok(adopt, "the Ink picker never dispatches adoptSession");
  assert.ok(
    /turns:\s*replay\.turns/.test(adopt[0]),
    `adoptSession is dispatched WITHOUT the replay — the scrollback would be ` +
      `cleared and never refilled (${adopt[0]})`,
  );
  assert.ok(
    !/type:\s*"adoptSession"[^}]*\}\);\s*dispatch\(\{\s*type:\s*"adoptSession"/.test(ink),
    "the session is adopted twice — one of the two dispatches is a window " +
      "with an empty scrollback",
  );
});

test("adoptSession with a transcript replaces the scrollback atomically", () => {
  const before = {
    ...INITIAL,
    sessionId: "old",
    turns: [{ id: 1, role: "user", text: "hi", ts: 1 }],
  };
  const replayed = [
    {
      id: 50,
      role: "assistant",
      text: "old session's answer",
      ts: 9,
      collapsed: false,
      previewChars: 80,
    },
  ];
  const after = reducer(before, { type: "adoptSession", sessionId: "new", turns: replayed });
  assert.equal(after.sessionId, "new");
  assert.deepEqual(after.turns, replayed);
});

test("adoptSession WITHOUT a transcript still clears — the honest fallback", () => {
  // The switch really happened; only the replay failed. An empty
  // scrollback plus a toast that says so is the honest state.
  const before = {
    ...INITIAL,
    sessionId: "old",
    turns: [{ id: 1, role: "user", text: "hi", ts: 1 }],
  };
  const after = reducer(before, { type: "adoptSession", sessionId: "new" });
  assert.equal(after.sessionId, "new");
  assert.equal(after.turns.length, 0);
});

test("the toast no longer claims the history is absent", () => {
  // R370's toast said "history is not replayed here" — true then,
  // false now, and a toast that lies is the R369 class of defect one
  // layer up.
  assert.ok(
    !/history is not replayed here/.test(tuiSrc),
    "the Ink toast still says the history is not replayed — it is now",
  );
  assert.ok(
    !/history is not replayed here/.test(lineSrc),
    "the line-mode toast still says the history is not replayed — it is now",
  );
});

test("a failed replay is reported as a failed replay", () => {
  // The switch happened; the replay did not. Two different facts and
  // the user is told which.
  const ink = block(tuiSrc, /case "threads":/);
  const catches = catchBodies(ink);
  assert.ok(catches.length >= 1, "the Ink picker has no catch at all");
  assert.ok(
    catches.some((c) => /history could not be read/.test(c)),
    "a failed replay is not reported — the user sees an empty scrollback " +
      "and concludes the session was empty",
  );
});
