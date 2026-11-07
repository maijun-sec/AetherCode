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

// R388l — the permission COMMENT reaches the model.
//
// ## The feature
//
// Claude Code lets a user attach a free-text comment to a permission
// answer (`Tab` on the selected option opens a comment field). The
// comment reaches the model: on a denial it IS the reason, on an
// approval it is context next to the tool's output. A boolean gate
// becomes a communication channel.
//
// ## ★ The defect this gate exists to hold ★
//
// The DAEMON already had a `reason` on the wire, and the deny path
// already carried it to the model. But the APPROVAL path dropped it:
// `JsonRpcPermissionPrompter` did `new PermissionResult.Allow(input)`
// and the reason evaporated. A user who wrote "yes, but write to /tmp
// first" would have had their comment reach the daemon and then vanish —
// the silent-degradation shape (the user believes they said something;
// the model never sees it).
//
// The fix has to be checked at BOTH ends of the pipe:
//
//   * the client must actually COLLECT a comment and put it on the wire
//     (and only when there is one — an empty comment must not change
//     the params a pre-R388l daemon sees), and
//   * the daemon must actually CARRY it into the tool_result.
//
// A gate that only checked the client would go green while the daemon
// still dropped the comment.
//
// ## ★ Why the wire shape has TWO fields ★
//
// `reason` is the mechanical audit trail ("TUI picked deny"). `comment`
// is the USER'S OWN WORDS. They are separate because the executor has
// to present the human sentence to the model WITHOUT dressing
// boilerplate up as the user's voice. Collapsing them into one field
// would make "TUI picked deny" indistinguishable from "no — use the
// read-only tool".
//
// ## The benchmark
//
// Claude Code's permission prompt: `Tab` on Yes/No opens a comment
// field; `Enter` submits it; the comment reaches the model.
//
// → **Lesson 1326 — an assertion about one system is an assertion about
// another system's vocabulary; it cannot verify itself.** So the client
// gate and the daemon gate are SEPARATE files, and neither is allowed
// to stand in for the other.

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { codeOf } from "./strip-comments.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

const read = (rel) => readFileSync(join(root, rel), "utf8");
const readCode = (rel) => codeOf(read(rel));

/* ------------------------------------------------------------------ *
 * Load the REAL module.
 *
 * The gate must not re-implement the state machine — a second
 * implementation is a second truth, and it is exactly what drifts
 * (R380's whole subject). So `permissionComment.ts` is compiled and
 * imported, and the REAL functions are called.
 * ------------------------------------------------------------------ */
const loadModule = async () => {
  const { build } = await import("esbuild");
  const { mkdtempSync, rmSync } = await import("node:fs");
  const { tmpdir } = await import("node:os");
  const dir = mkdtempSync(join(tmpdir(), "r388l-comment-"));
  const outfile = join(dir, "permissionComment.mjs");
  try {
    await build({
      entryPoints: [join(root, "src", "permissionComment.ts")],
      bundle: true,
      outfile,
      platform: "node",
      format: "esm",
      loader: { ".ts": "ts" },
    });
    return await import(pathToFileURL(outfile).href);
  } finally {
    try {
      rmSync(outfile, { force: true });
    } catch {
      /* best effort */
    }
  }
};

/* ------------------------------------------------------------------ *
 * Source-level helpers.
 *
 * These read the SOURCE, not a re-implementation, and they are scoped
 * to the enclosing function so a file-level match cannot be satisfied
 * by the WRONG occurrence (R388k Lessons 1318 / 1321 / 1322).
 * ------------------------------------------------------------------ */

/** The body of `pickPermission` in line.ts (up to the next top-level fn). */
function linePickerBody() {
  const src = read("src/line.ts");
  const start = src.indexOf("async function pickPermission(");
  assert.ok(start > 0, "pickPermission must exist in line.ts");
  const next = src.indexOf("\nasync function ", start + 10);
  return src.slice(start, next > 0 ? next : src.length);
}

/** The body of `replyPermission` in tui.tsx. */
function tuiReplyBody() {
  const src = read("src/tui.tsx");
  const start = src.indexOf("function replyPermission(");
  assert.ok(start > 0, "replyPermission must exist in tui.tsx");
  const next = src.indexOf("\nfunction ", start + 10);
  return src.slice(start, next > 0 ? next : src.length);
}

/* ================================================================== *
 * 1. The pure logic.
 * ================================================================== */

test("R388l: a fresh comment box is empty and armed to DENY", async () => {
  const m = await loadModule();
  const box = m.newCommentBox();
  assert.equal(box.text, "");
  // The default must be the SAFE direction: a user who opens a comment
  // box is almost always saying "not exactly this", and a silent
  // default that ALLOWS would be the dangerous direction to be wrong in.
  assert.equal(box.armed, "deny");
  assert.equal(m.DEFAULT_ARMED_DECISION, "deny");
});

test("R388l: Ctrl+<key> arms the decision; a plain letter does not", async () => {
  const m = await loadModule();
  let box = m.newCommentBox();
  box = m.armDecision(box, "a");
  assert.equal(box.armed, "allow");
  box = m.armDecision(box, "p");
  assert.equal(box.armed, "always_allow");
  assert.equal(box.scope, "project");
  box = m.armDecision(box, "d");
  assert.equal(box.armed, "deny");
  // A key that is NOT a decision key leaves the box untouched — the
  // armed decision must never change by accident.
  const before = box.armed;
  box = m.armDecision(box, "z");
  assert.equal(box.armed, before);
});

test("R388l: the armed label names the decision the user can see", async () => {
  const m = await loadModule();
  let box = m.newCommentBox();
  assert.equal(m.armedLabel(box), "deny once");
  box = m.armDecision(box, "a");
  assert.equal(m.armedLabel(box), "allow once");
});

test("R388l: the decision keys live in ONE registry, not two parallel lists", async () => {
  const m = await loadModule();
  // ★ The scope used to live in a SECOND list (`DECISION_SCOPE`), and
  // the two had already drifted — the scope list carried a `y` the key
  // list did not. One "list" that is really two lists is Lesson 1207's
  // shape, and every one of them drifts.
  //
  // So: every key the registry knows must resolve to an entry that
  // carries ITS OWN scope. A separate scope map would not be visible
  // here — which is exactly why it has to be pinned.
  for (const entry of m.DECISION_KEYS) {
    const resolved = m.resolveDecisionKey(entry.key);
    assert.ok(resolved, `${entry.key} must resolve`);
    assert.equal(resolved.decision, entry.decision, `${entry.key} decision`);
    assert.equal(resolved.scope, entry.scope, `${entry.key} scope`);
  }
  // And the registry must not have grown a second source of truth.
  assert.equal(
    m.DECISION_SCOPE,
    undefined,
    "there must be NO separate scope map — the scope lives on the key's entry",
  );
  // Every key the TUI's decision keys use must be in the registry, or
  // `Ctrl+<key>` would arm nothing while looking like it worked.
  for (const key of ["a", "t", "p", "u", "y", "d", "n"]) {
    assert.ok(
      m.resolveDecisionKey(key),
      `the decision key "${key}" must be in the registry — ` +
        "Ctrl+<key> would otherwise arm nothing",
    );
  }
});

test("R388l: append / delete edit the comment", async () => {
  const m = await loadModule();
  let box = m.newCommentBox();
  box = m.appendComment(box, "no");
  box = m.appendComment(box, "!");
  assert.equal(box.text, "no!");
  box = m.deleteLastChar(box);
  assert.equal(box.text, "no");
  // Deleting from an empty comment is a no-op, not a crash.
  const empty = m.deleteLastChar(m.newCommentBox());
  assert.equal(empty.text, "");
});

/* ================================================================== *
 * 2. The wire contract.
 * ================================================================== */

test("R388l: a reply WITHOUT a comment is byte-identical to the pre-R388l reply", async () => {
  const m = await loadModule();
  const params = m.permissionResponseParams("perm-1", "deny", undefined, undefined, "TUI");
  // The pre-R388l params were exactly {requestId, decision, reason}.
  // A daemon on the old wire must not see a new key at all.
  assert.deepEqual(Object.keys(params).sort(), ["decision", "reason", "requestId"]);
  assert.equal(params.comment, undefined);
  assert.equal(params.reason, "TUI picked deny");
  // An EMPTY / whitespace-only comment is also omitted — "the user
  // opened the box and typed nothing" must not look like "the user
  // sent an empty comment".
  const blank = m.permissionResponseParams("perm-1", "deny", undefined, "   ", "TUI");
  assert.equal(blank.comment, undefined);
});

test("R388l: a reply WITH a comment carries it in its own field", async () => {
  const m = await loadModule();
  const params = m.permissionResponseParams(
    "perm-1",
    "deny",
    undefined,
    "use the read-only tool instead",
    "TUI",
  );
  assert.equal(params.comment, "use the read-only tool instead");
  // The comment is SEPARATE from the reason. `reason` is the
  // mechanical audit trail; collapsing them would let boilerplate be
  // presented to the model as the user's own words.
  assert.equal(params.reason, "TUI picked deny");
  assert.notEqual(params.reason, params.comment);
});

test("R388l: the comment is trimmed — the wire carries the user's words, not their padding", async () => {
  const m = await loadModule();
  const params = m.permissionResponseParams("p", "deny", undefined, "  spaced  ", "TUI");
  assert.equal(params.comment, "spaced");
});

/* ================================================================== *
 * 3. The Ink client — checked WHERE the contract is DELIVERED.
 * ================================================================== */

test("R388l: the Ink app opens the comment box with Tab while an ask is pending", () => {
  const code = readCode("src/tui.tsx");
  // The Tab key must be handled INSIDE the `if (state.permissionAsk)`
  // branch — a Tab handler anywhere else would open the comment box
  // when there is no ask to comment on.
  const branchStart = code.indexOf("if (state.permissionAsk) {");
  assert.ok(branchStart > 0, "tui.tsx must have a permissionAsk branch");
  const branch = code.slice(branchStart, branchStart + 4000);
  assert.match(branch, /key\.tab/, "the permissionAsk branch must open the comment box on Tab");
  // ★ `key.tab` ALONE, not `key.tab || input === "\t"`. The keybinding
  // registry scans the app handler for `input === "<literal>"` and turns
  // each literal into a chord token — so the `"\t"` literal becomes a
  // SECOND chord (`\t`) beside the real one (`tab`), and the help then
  // owes the user a binding nobody documented. The registry's own
  // `tab` entry is the documentation; a literal would be a phantom.
  assert.doesNotMatch(
    branch,
    /input\s*===\s*"\\t"/,
    'the Tab check must be `key.tab` alone — a literal "\\t" reads as a ' +
      "second, undocumented chord to the key-binding registry",
  );
  assert.match(branch, /openCommentBox/, "the permissionAsk branch must dispatch openCommentBox");
});

test("R388l: while the comment box is open the decision keys are SUSPENDED", () => {
  const code = readCode("src/tui.tsx");
  // The comment branch must come BEFORE the decision-key branch and
  // must RETURN — otherwise "d" would deny instead of typing the letter
  // d, which is the whole point of the mode.
  //
  // ★ The decision branch is located by its DECISION KEY, not by the
  // string `if (state.permissionAsk) {`: the comment branch contains
  // an INNER `if (state.permissionAsk)` of its own (the guard that
  // makes sure the ask is still there when Enter is pressed), and
  // matching that one truncates the branch before the reply — which
  // would make the "Enter must send" assertion pass vacuously.
  //
  // ★ R388n: the anchor moved from `replyPermission(client, ask,
  // "allow"` to `resolveDecisionKey(`. ★
  //
  // The old anchor named a SPELLING — a literal call with a literal
  // decision. R388n replaced the hand-written if-chain with a registry
  // lookup, so the literal `"allow"` call no longer exists, and the
  // pin went red on an IMPROVEMENT.
  //
  // → **Lesson 1233 — a CONTRACT pin must FOLLOW the implementation. A
  // pin that names a method goes red on a RENAME (which is not a
  // defect), and a pin that names a LOCATION goes red on a REFACTOR
  // (which may make the code better). Pin the INVARIANT, not the
  // spelling.**
  //
  // The invariant is unchanged: the decision branch is the one that
  // RESOLVES a decision key, and it must come after the comment branch.
  const commentStart = code.indexOf("if (state.commentBox) {");
  const decisionStart = code.indexOf("resolveDecisionKey(");
  assert.ok(commentStart > 0, "tui.tsx must have a commentBox branch");
  assert.ok(decisionStart > 0, "tui.tsx must have a decision-key branch");
  assert.ok(
    commentStart < decisionStart,
    "the commentBox branch must come BEFORE the decision-key branch, " +
      "otherwise the decision keys fire while the user is typing a comment",
  );
  const branch = code.slice(commentStart, decisionStart);
  assert.match(
    branch,
    /return;/,
    "the comment branch must return so the decision keys do not fire",
  );
});

test("R388l: Ctrl+<key> arms the decision in the Ink app", () => {
  const code = readCode("src/tui.tsx");
  const commentStart = code.indexOf("if (state.commentBox) {");
  const decisionStart = code.indexOf("resolveDecisionKey(");
  const branch = code.slice(commentStart, decisionStart);
  assert.match(branch, /key\.ctrl/, "the comment branch must handle the Ctrl modifier");
  assert.match(branch, /armDecision/, "the comment branch must arm the decision");
});

test("R388l: Enter sends the decision AND the comment; Esc cancels without answering", () => {
  const code = readCode("src/tui.tsx");
  const commentStart = code.indexOf("if (state.commentBox) {");
  const decisionStart = code.indexOf("resolveDecisionKey(");
  const branch = code.slice(commentStart, decisionStart);
  assert.match(branch, /key\.return/, "the comment branch must handle Enter");
  assert.match(branch, /key\.escape/, "the comment branch must handle Esc");
  // Enter must SEND the comment (not merely close the box). The comment
  // has to reach replyPermission.
  assert.match(branch, /replyPermission\(/, "Enter must send the answer with the comment");
  assert.match(branch, /comment/, "the sent answer must carry the comment");
  // Esc must close the box WITHOUT answering. If Esc also replied, a
  // cancelled comment would be indistinguishable from an answer.
  const escIdx = branch.indexOf("key.escape");
  const escTail = branch.slice(escIdx, escIdx + 400);
  assert.match(escTail, /closeCommentBox/, "Esc must close the comment box");
  assert.doesNotMatch(
    escTail.slice(0, 300),
    /replyPermission\(/,
    "Esc must NOT send an answer — cancelling must not look like answering",
  );
});

test("R388l: the Ink reply path carries the comment onto the wire", () => {
  const body = tuiReplyBody();
  assert.match(
    body,
    /permissionResponseParams\(/,
    "replyPermission must build its params with permissionResponseParams " +
      "(one wire shape, not two hand-rolled ones)",
  );
  assert.match(body, /comment/, "replyPermission must forward the comment to the wire builder");
});

test("R388l: the InputBox collects characters into the comment, not the prompt", () => {
  const code = readCode("src/components/InputBox.tsx");
  assert.match(code, /onCommentChange/, "the InputBox must have a comment-change path");
  // The comment branch must come BEFORE the `decisionPending` early
  // return — otherwise typing a comment would be swallowed (the input
  // box is disabled while an ask is pending).
  const commentIdx = code.indexOf("if (commentBox) {");
  const pendingIdx = code.indexOf("if (decisionPending) return;");
  assert.ok(commentIdx > 0, "the InputBox must branch on commentBox");
  assert.ok(pendingIdx > 0, "the InputBox must have the decisionPending guard");
  assert.ok(
    commentIdx < pendingIdx,
    "the comment branch must come BEFORE the decisionPending guard, " +
      "otherwise the comment text can never be typed",
  );
});

test("R388l: a MODIFIER key is not typed into the comment", () => {
  const code = readCode("src/components/InputBox.tsx");
  const commentIdx = code.indexOf("if (commentBox) {");
  const pendingIdx = code.indexOf("if (decisionPending) return;");
  const branch = code.slice(commentIdx, pendingIdx);
  // ★ Ink's `useInput` is NOT focus-scoped, so BOTH the App's handler
  // and the InputBox's handler see every keystroke. And Ink passes the
  // CHARACTER for Ctrl+A as input="a" with key.ctrl=true — so without
  // this guard, arming the decision would ALSO type the letter "a"
  // into the comment.
  //
  // This is invisible to a "does the comment work?" test: the comment
  // works, it just also grows a stray "a" every time you arm.
  assert.match(
    branch,
    /key\.ctrl/,
    "the comment branch must ignore modifier chords — Ctrl+A arms the " +
      "decision in the App and must NOT type an 'a' into the comment",
  );
});

/* ================================================================== *
 * 4. The line client — the SAME contract, one wire shape.
 * ================================================================== */

test("R388l: the line picker opens a comment field with Tab", () => {
  const body = linePickerBody();
  assert.match(body, /commentMode/, "the line picker must have a comment mode");
  // ★ The Tab handler that OPENS the comment field must be found by
  // what it DOES (`commentMode = true`), and it must be the one
  // OUTSIDE the comment-mode block.
  //
  // There are TWO Tab handlers in the picker: the one that opens the
  // comment field, and the one INSIDE the comment mode (where Tab is a
  // no-op so the user cannot get stuck). An unbounded search for the
  // literal `c === "\t"` finds the no-op one — and a mutation that
  // deletes the OPENING handler survives, because the no-op one still
  // matches. (Lesson 1322: "in a big block, searching for a label can
  // find a neighbouring construct's label".)
  //
  // So: the opening handler must live in the part of the body AFTER
  // the comment-mode block (the comment-mode block handles the keys
  // while the field is open; the option list is what you come BACK
  // to, and Tab there is what opens the field).
  const commentBlockStart = body.indexOf("if (commentMode) {");
  assert.ok(commentBlockStart > 0, "the line picker must have a comment-mode block");
  const optionList = body.slice(commentBlockStart);
  assert.match(
    optionList,
    /if \(c === "\\t"\) \{[\s\S]{0,200}?commentMode = true;/,
    "the option list must open the comment field on Tab " +
      "(the handler that sets commentMode = true)",
  );
});

test("R388l: the line picker sends the decision AND the comment", () => {
  const body = linePickerBody();
  assert.match(body, /resolvePromise\(/, "the line picker must resolve with the picked answer");
  assert.match(body, /comment:/, "the line picker must resolve with the comment");
});

test("R388l: the line renderer sends the same wire shape as the Ink renderer", () => {
  const code = readCode("src/line.ts");
  assert.match(
    code,
    /permissionResponseParams\(/,
    "line.ts must build its reply params with permissionResponseParams — " +
      "two renderers must not hand-roll two wire shapes",
  );
});

/* ================================================================== *
 * 5. The DAEMON — the other end of the pipe.
 *
 * The client gate cannot stand in for this one: a client that sends a
 * comment the daemon drops is a comment that reaches the model NEVER.
 * ================================================================== */

const daemonRoot = join(root, "..", "aethercode");

test("R388l: the daemon carries the comment into the approval's note", () => {
  const src = readFileSync(
    join(
      daemonRoot,
      "aethercode-protocol",
      "src",
      "main",
      "java",
      "org",
      "aethercode",
      "protocol",
      "permissions",
      "JsonRpcPermissionPrompter.java",
    ),
    "utf8",
  );
  // The approval branch must NOT drop the comment.
  assert.match(
    src,
    /new PermissionResult\.Allow\(input,\s*decision\.comment\(\)\)/,
    "the approval branch must carry the comment as the note — " +
      "dropping it here is the exact silent-degradation defect",
  );
});

test("R388l: the daemon reads the comment off the wire", () => {
  const src = readFileSync(
    join(
      daemonRoot,
      "aethercode-protocol",
      "src",
      "main",
      "java",
      "org",
      "aethercode",
      "protocol",
      "methods",
      "AetherCodeMethods.java",
    ),
    "utf8",
  );
  assert.match(
    src,
    /p\.get\("comment"\)/,
    "permissionResponse must read the optional comment field",
  );
  assert.match(
    src,
    /new PermissionDecision\(decision,\s*reason,\s*comment\)/,
    "permissionResponse must complete the future with the comment",
  );
});

test("R388l: the executor appends the note to the tool_result the model reads", () => {
  const src = readFileSync(
    join(
      daemonRoot,
      "aethercode-core",
      "src",
      "main",
      "java",
      "org",
      "aethercode",
      "core",
      "engine",
      "StreamingToolExecutor.java",
    ),
    "utf8",
  );
  assert.match(src, /appendApprovalNote/, "the executor must have the note-appending helper");
  // Both execution paths (the queue-based one and the sink-based one)
  // must use it — a comment that reaches one path but not the other is
  // a comment that reaches the model SOMETIMES.
  const uses = src.match(/appendApprovalNote\(/g) ?? [];
  // 1 = the definition, 2 = the two call sites.
  assert.equal(uses.length, 3, `expected the helper + 2 call sites, found ${uses.length}`);
  assert.match(
    src,
    /static Object appendApprovalNote\(Object output, String note\)/,
    "the helper must be a plain static so both paths share it",
  );
});
