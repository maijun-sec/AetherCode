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

// R387 — `/clear` must be a REAL clear, in BOTH renderers.
//
// ============================================================================
// WHAT THIS GATE MAKES EXECUTABLE
// ============================================================================
//
// The defect: `/clear` was the only command in the catalogue whose
// description promised one thing and whose two renderers did two other
// things.
//
//   - the catalogue said "clear the scrollback";
//   - the LINE renderer really wiped the screen;
//   - the INK renderer printed "(clear is a no-op in this build; restart
//     for a fresh scrollback)" and did nothing.
//
// R378 deferred it as "another honest gap". It WAS honest, and it was
// still broken: the capability was never missing — the daemon already had
// the primitive — and the TUI's own README claimed the Ink path "redraws",
// which it never did.
//
// And neither half cleared the conversation the MODEL has. The TUI's
// `state.turns` is a MIRROR of the daemon's transcript (R372 replays
// `getTranscript` into it), so a view-only clear would have hidden a
// conversation the model still remembers — and the next session switch
// would have brought the whole history back.
//
// The fix routes BOTH renderers through one shared module
// (`src/clearConversation.ts`), which asks the daemon for a fresh session
// (`createSession`) and only lets the renderer replace the scrollback once
// the daemon has CONFIRMED it.
//
// So this gate asserts:
//
//   1. the shared contract: the method, the params, the id extraction and
//      the outcome sentence — behaviourally, by RUNNING the module;
//   2. `runClear` really issues that method, and never invents a session
//      id the daemon did not give;
//   3. the reducer really empties the scrollback (and drops the previous
//      conversation's error card);
//   4. BOTH renderers route `/clear` through the shared runner, the old
//      no-op sentence is gone from the whole tree, and the method name
//      and the wording exist in exactly ONE place;
//   5. the daemon method really exists — on BOTH transports, which is the
//      Lesson 1022 two-layer rule (a method registered for stdio but not
//      routed by the HTTP switch answers -32601 to the desktop);
//   6. the catalogue describes what the command now does.
//
// ============================================================================

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync, readdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { codeOf } from "./strip-comments.mjs";
import { loadTsModule } from "./load-ts-module.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const srcRoot = join(root, "src");
const repo = join(root, "..");
const java = join(repo, "aethercode");

const read = (rel) => readFileSync(join(srcRoot, rel), "utf-8");

// ===========================================================================
// §1  the shared contract
// ===========================================================================

const clearMod = await loadTsModule(root, "src/clearConversation.ts");
const { CLEAR_METHOD, clearParams, newSessionIdOf, clearOutcome, runClear } = clearMod;

test("the clear names the daemon method that starts a fresh conversation", () => {
  // `createSession` is the daemon's own verb, registered on stdio
  // (`AetherCodeMethods.registerAll`) and routed by the HTTP switch. It
  // mints a session id, writes an EMPTY transcript, and switches the
  // engine onto it — measured against the running daemon in R387.
  assert.equal(CLEAR_METHOD, "createSession");
});

test("the clear carries the cwd so the new session stays in the same project", () => {
  assert.deepEqual(clearParams("D:\\work\\proj"), { cwd: "D:\\work\\proj" });
  // A blank cwd must NOT be sent as an empty string: the daemon would
  // mint a session with no cwd at all, and the cwd-scoped `/sessions`
  // picker (R370) would file it under "unlinked" instead of next to the
  // project the user is working in.
  assert.deepEqual(clearParams("   "), {});
  assert.deepEqual(clearParams(""), {});
});

test("a session id is only read from a result that really carries one", () => {
  assert.equal(newSessionIdOf({ ok: true, sessionId: "s-1" }), "s-1");
  // `ok: false` — the daemon refused. No id.
  assert.equal(newSessionIdOf({ ok: false, sessionId: "s-1" }), null);
  // `ok: true` but no id — a malformed answer. No id.
  assert.equal(newSessionIdOf({ ok: true }), null);
  assert.equal(newSessionIdOf({ ok: true, sessionId: "" }), null);
  assert.equal(newSessionIdOf({ ok: true, sessionId: 7 }), null);
  assert.equal(newSessionIdOf(null), null);
  assert.equal(newSessionIdOf("nonsense"), null);
});

test("the outcome sentence says what HAPPENED, on both sides of the fence", () => {
  const good = clearOutcome(true, { sessionId: "s-2" });
  assert.match(good, /s-2/, "the success sentence does not name the new session");
  assert.doesNotMatch(good, /fail/i, "the success sentence talks about failure");

  const bad = clearOutcome(false, { error: "SessionStore is not wired" });
  assert.match(bad, /clear failed/i, "the failure sentence does not say it failed");
  assert.match(bad, /SessionStore is not wired/, "the failure sentence hides the reason");

  // No reason at all is still a FAILURE, never a vague success.
  const bare = clearOutcome(false, {});
  assert.match(bare, /clear failed/i);
});

// ===========================================================================
// §2  runClear really issues the method
// ===========================================================================

test("runClear asks the daemon for a new session, with the cwd", async () => {
  const calls = [];
  const r = await runClear(
    async (method, params) => {
      calls.push({ method, params });
      return { ok: true, sessionId: "s-9", cwd: "D:\\p", messageCount: 0 };
    },
    { cwd: "D:\\p" },
  );

  assert.equal(calls.length, 1, "the clear did not issue exactly one request");
  assert.equal(calls[0].method, "createSession");
  assert.deepEqual(calls[0].params, { cwd: "D:\\p" });
  assert.equal(r.ok, true);
  assert.equal(r.sessionId, "s-9");
  assert.match(r.message, /s-9/);
});

test("a daemon that throws is a FAILURE, and the reason survives", async () => {
  const r = await runClear(
    async () => {
      throw new Error("connection reset");
    },
    { cwd: "D:\\p" },
  );

  assert.equal(r.ok, false);
  assert.equal(r.sessionId, null, "a failed clear invented a session id");
  assert.match(r.message, /clear failed/i);
  assert.match(r.message, /connection reset/);
});

test("a daemon that refuses is a FAILURE, and it names the reason", async () => {
  const r = await runClear(async () => ({ ok: false, reason: "SessionStore is not wired" }), {
    cwd: "D:\\p",
  });

  assert.equal(r.ok, false);
  assert.equal(r.sessionId, null);
  assert.match(r.message, /SessionStore is not wired/);
});

test("a result with no session id is a FAILURE — the clear never guesses", async () => {
  // `ok: true` but no id is a malformed answer. Adopting a session id
  // that does not exist would put the TUI on a session the daemon has
  // never heard of, and the next query would go nowhere.
  const r = await runClear(async () => ({ ok: true }), { cwd: "D:\\p" });
  assert.equal(r.ok, false);
  assert.equal(r.sessionId, null);
  assert.match(r.message, /clear failed/i);
});

// ===========================================================================
// §3  the reducer really empties the scrollback
// ===========================================================================

const stateMod = await loadTsModule(root, "src/state.ts");
const { reducer } = stateMod;

test("adopting a session with an empty transcript empties the scrollback", () => {
  const before = {
    sessionId: "old",
    connected: true,
    status: "ready",
    turns: [{ id: 1, role: "user", text: "hi", ts: 1 }],
    todos: [{ id: 1, text: "t", status: "pending" }],
    lastError: { message: "boom", ts: 1 },
    lastStopReason: "error: boom",
    lastStopKind: "error",
    viewingSubagentId: "job-1",
    viewHistory: [{ kind: "subagent", jobId: "job-1" }],
    continuationPaused: true,
  };

  const after = reducer(before, { type: "adoptSession", sessionId: "new", turns: [] });

  assert.deepEqual(after.turns, [], "the scrollback still shows the old conversation");
  assert.equal(after.sessionId, "new");
  // The error card describes the last run of the conversation we just
  // left. Leaving it up would show the user an error from a session that
  // is no longer on screen.
  assert.equal(after.lastError, null, "the previous conversation's error card survived");
  assert.equal(after.lastStopReason, null);
  assert.equal(after.lastStopKind, null);
  assert.deepEqual(after.todos, []);
  assert.deepEqual(after.viewHistory, []);
  assert.equal(after.viewingSubagentId, null);
  assert.equal(after.continuationPaused, false);
});

// ===========================================================================
// §4  both renderers route the clear through the shared runner
// ===========================================================================

const tuiCode = codeOf(read("tui.tsx"), "tui.tsx");
const lineCode = codeOf(read("line.ts"), "line.ts");

/** Every source file under src/, comment-stripped. */
function srcCode() {
  const out = new Map();
  (function walk(dir) {
    for (const e of readdirSync(dir, { withFileTypes: true })) {
      const p = join(dir, e.name);
      if (e.isDirectory()) walk(p);
      else if (/\.tsx?$/.test(e.name))
        out.set(p.slice(srcRoot.length + 1), codeOf(readFileSync(p, "utf-8"), p));
    }
  })(srcRoot);
  return out;
}

test("BOTH renderers clear through the shared module", () => {
  // The INVARIANT is "the renderer decides what a clear means by asking
  // the shared module", NOT "the renderer calls a function with this
  // name". A pin on the local symbol name goes red on a RENAME, which
  // is not a defect (R384, Lesson 1233) — so the gate resolves the
  // binding the renderer IMPORTS and checks that it is actually CALLED.
  for (const [file, code] of [
    ["tui.tsx", tuiCode],
    ["line.ts", lineCode],
  ]) {
    const m = /import\s*\{\s*([A-Za-z_$][\w$]*)\s*\}\s*from\s*"\.\/clearConversation\.js"/.exec(
      code,
    );
    assert.ok(
      m,
      `${file} does not import the shared clear module — /clear can answer differently in the two renderers`,
    );
    assert.match(
      code,
      new RegExp(`\\b${m[1]}\\s*\\(`),
      `${file} imports the shared clear module but never calls it`,
    );
  }
});

test("the Ink renderer replaces the scrollback with an EMPTY turn list", () => {
  // The clear has to carry the new session id AND drop the turns — a
  // dispatch that only renames the session would leave the old
  // conversation on screen under a new session's name.
  assert.match(
    tuiCode,
    /adoptSession[^}]*turns:\s*\[\s*\]/s,
    "the Ink renderer does not adopt the new session with an empty scrollback",
  );
});

test("the line renderer drops the turns of the conversation it left", () => {
  assert.match(
    lineCode,
    /turnsBuffer\.length\s*=\s*0/,
    "the line renderer keeps the old turns after a clear",
  );
});

test("the old no-op sentence is gone from the WHOLE tree", () => {
  // INVERTED invariant (R384, Lesson 1237): the defect cannot come back
  // by someone re-adding a friendly notice. Comments are stripped, so
  // the round's own history in a comment is not a violation.
  const offenders = [];
  for (const [rel, code] of srcCode()) {
    if (/clear is a no-op/i.test(code)) offenders.push(rel);
  }
  assert.deepEqual(
    offenders,
    [],
    `the /clear no-op notice is back in: ${offenders.join(", ")} — one command cannot have two answers`,
  );
});

test("the daemon method name is written in exactly ONE file", () => {
  // The vocabulary rule from R385/R386: the method name lives in the
  // shared module, so a renderer cannot name a method the other one
  // does not.
  const holders = [];
  for (const [rel, code] of srcCode()) {
    if (/"createSession"/.test(code)) holders.push(rel);
  }
  assert.deepEqual(
    holders,
    ["clearConversation.ts"],
    `the clear's method name is written in ${holders.join(", ")} — it must be written once`,
  );
});

test("the outcome wording is written in exactly ONE file", () => {
  // Same rule for the SENTENCE: the two renderers must not be able to
  // tell the user two different things about one command.
  const holders = [];
  for (const [rel, code] of srcCode()) {
    if (/cleared\s*—/.test(code)) holders.push(rel);
  }
  assert.deepEqual(
    holders,
    ["clearConversation.ts"],
    `the clear's outcome wording is written in ${holders.join(", ")} — it must be written once`,
  );
});

// ===========================================================================
// §5  the daemon method really exists, on BOTH transports
// ===========================================================================

const methodsPath = join(
  java,
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
  java,
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

test("the daemon registers the clear's method on the stdio dispatcher", (t) => {
  if (!existsSync(methodsPath)) return t.skip("the daemon sources are not in this checkout");
  const src = readFileSync(methodsPath, "utf-8");
  assert.match(
    src,
    /dispatcher\.register\(\s*"createSession"/,
    "the daemon does not register createSession on the stdio dispatcher",
  );
});

test("the daemon ROUTES the clear's method on the HTTP transport", (t) => {
  // Lesson 1022: a method registered for stdio but missing from the
  // HTTP switch answers -32601 to the desktop and to anyone using the
  // HTTP RPC. Two layers, or the method does not exist.
  if (!existsSync(httpPath)) return t.skip("the daemon sources are not in this checkout");
  const src = readFileSync(httpPath, "utf-8");
  assert.match(
    src,
    /case\s+"createSession"\s*->/,
    "the HTTP switch does not route createSession — every HTTP caller gets -32601",
  );
});

// ===========================================================================
// §6  the catalogue tells the truth
// ===========================================================================

const commandsMod = await loadTsModule(root, "src/commands.ts");
const { SLASH_COMMANDS_DETAILED, SLASH_HELP } = commandsMod;

test("the catalogue describes what /clear now does", () => {
  const entry = SLASH_COMMANDS_DETAILED.find((c) => c.name === "clear");
  assert.ok(entry, "/clear is not in the catalogue");
  // The command does TWO things now: it empties the scrollback AND it
  // starts a new session (so the model's context matches the empty
  // screen). A description that names only the first half would hide
  // the fact that the session id CHANGES.
  assert.match(
    entry.description,
    /session/i,
    "the /clear description does not mention the new session",
  );
  assert.doesNotMatch(
    entry.description,
    /^clear the scrollback$/,
    "the /clear description is the old one — it promises only the view",
  );
});

test("/help shows the same description the dropdown shows", () => {
  const entry = SLASH_COMMANDS_DETAILED.find((c) => c.name === "clear");
  assert.ok(SLASH_HELP.includes(entry.description), "/help does not carry the /clear description");
});
