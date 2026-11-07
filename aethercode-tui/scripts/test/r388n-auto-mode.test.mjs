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

// R388n — "Yes, and switch to auto mode".
//
// ## ★ The feature ★
//
// Claude Code's permission prompt offers, as ONE option, "yes, and switch
// to auto mode": the call is approved AND the session stops asking for
// this kind of call. Two presses (approve, then `/mode`) reach the same
// state — but the user has to know the second step exists, and has to
// remember it.
//
// ## ★ The defect this gate exists to hold ★
//
// The TUI's permission card offered six answers (A / T / P / U / D / N)
// and NONE of them changed the session's permission mode.
//
// The trap: `P` and `U` LOOK like they do. They install a rule for the
// TOOL/TARGET — a `permissionPolicyOverride` — and they do NOT touch
// `PermissionMode`. So a user who reads "allow for this project" and
// presses `P` believes the session has stopped asking, and it has not:
// the next call of a DIFFERENT tool still asks.
//
// That is the silent-degradation shape (the user believes they did
// something; the daemon did something else).
//
// ## ★ Why the registry, and why ONE registry ★
//
// The decision keys already had a home: `DECISION_KEYS` in
// `src/permissionComment.ts` (R388l). But the Ink app's key handler was
// a HAND-WRITTEN IF-CHAIN — a SECOND list of the same keys.
//
// Two lists of the same keys is Lesson 1207's shape, and R388n makes it
// FATAL: the new `M` key would live in the registry (so the card could
// advertise it) while the if-chain did not know about it (so pressing it
// would do NOTHING). The key would look alive and be dead.
//
// So the if-chain is now a LOOKUP through the registry. The gate pins
// that: the Ink handler must NOT contain a hand-written per-key branch.
//
// ## ★ Why the mode travels on the wire ★
//
// The reply and the mode change are TWO different concerns:
// `permissionResponse` answers the ask, `setPermissionMode` changes the
// session's posture. The reply carries the mode as its OWN field so the
// two cannot be confused — the same reasoning as R388l's
// `reason` vs `comment`.
//
// And the mode change is issued as a SEPARATE RPC AFTER the reply, not
// chained onto it: the reply is what unblocks the model, and a failing
// mode change must not delay or fail the approval.
//
// ## ★ The registry's label lookup ★
//
// `armedLabel` used to find the entry by DECISION alone. Two keys now
// share the `allow` decision (`a` and `m`), so that lookup would show
// "allow once" for a pick that switches the mode — the header would
// describe a DIFFERENT answer from the one about to be sent.
//
// → **Lesson 1355 — two answers that produce the SAME decision are two
// answers.** A lookup keyed on the outcome finds the wrong one.
//
// → **Lesson 1356 — a key that is advertised but not wired is worse than
// a key that is absent.** It costs the user a keystroke and teaches them
// that the UI lies.
//
// → **Lesson 1357 — an APPROVAL must not depend on a cosmetic follow-up.**
// Chain the reply and the mode change and a failing mode change delays
// (or fails) the tool call the user already approved.

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
 * Load the REAL modules — the gate must not re-implement the rules.
 * ------------------------------------------------------------------ */
const loadTs = async (name) => {
  const { build } = await import("esbuild");
  const { mkdtempSync, rmSync } = await import("node:fs");
  const { tmpdir } = await import("node:os");
  const dir = mkdtempSync(join(tmpdir(), `r388n-auto-${name}-`));
  const outfile = join(dir, `${name}.mjs`);
  try {
    await build({
      entryPoints: [join(root, "src", `${name}.ts`)],
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

const comment = await loadTs("permissionComment");
const modes = await loadTs("permissionModes");

/* ------------------------------------------------------------------ *
 * Source helpers — bounded to the enclosing function.
 *
 * Lesson 1322: an unbounded search for a label can find a NEIGHBOURING
 * construct's label. `pickFromList` and `pickPermission` have the same
 * numeric-hotkey shape; a text search over the whole file finds the
 * wrong one.
 * ------------------------------------------------------------------ */

/** The body of `pickPermission` in line.ts. */
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
 * 1. The registry — the mode lives ON the key's entry.
 * ================================================================== */

test("R388n: a decision key can carry the mode it switches the session to", () => {
  const m = comment.DECISION_KEYS.find((e) => e.key === "m");
  assert.ok(m, "the registry must carry the 'm' key — the card advertises it");
  assert.equal(m.decision, "allow", "'m' approves the call");
  assert.ok(m.mode, "'m' must carry a mode — otherwise the key approves without switching");
});

test("R388n: the mode the 'm' key switches to is the daemon's ACCEPT_TASK", () => {
  const m = comment.DECISION_KEYS.find((e) => e.key === "m");
  assert.equal(
    m.mode,
    modes.MODE.ACCEPT_TASK,
    "the 'm' key must switch to ACCEPT_TASK — the mode the user agreed to",
  );
  // And that mode must be a REAL daemon mode (not an invented word).
  assert.ok(modes.permissionModeEntry(m.mode), `${m.mode} is not in the daemon's mode catalog`);
});

test("R388n: the registry is the ONLY source of the mode — no second list", () => {
  // The mode must be reachable THROUGH the registry, not through a
  // parallel table of "keys that switch modes".
  for (const entry of comment.DECISION_KEYS) {
    const resolved = comment.resolveDecisionKey(entry.key);
    assert.ok(resolved, `${entry.key} must resolve`);
    assert.equal(resolved.mode, entry.mode, `${entry.key}'s mode must match its entry`);
  }
  assert.equal(
    comment.DECISION_MODE_KEYS,
    undefined,
    "there must be NO separate mode-key list — the mode lives on the entry",
  );
});

test("R388n: resolving a decision key returns the mode with it", () => {
  const resolved = comment.resolveDecisionKey("m");
  assert.ok(resolved);
  assert.equal(resolved.decision, "allow");
  assert.equal(resolved.mode, modes.MODE.ACCEPT_TASK);
  // A key with no mode resolves to no mode — not to the DEFAULT's mode.
  assert.equal(comment.resolveDecisionKey("a").mode, undefined);
  assert.equal(comment.resolveDecisionKey("d").mode, undefined);
});

test("R388n: arming 'm' records the mode on the box state", () => {
  const box = comment.armDecision(comment.newCommentBox(), "m");
  assert.equal(box.armed, "allow");
  assert.equal(box.mode, modes.MODE.ACCEPT_TASK);
});

/* ================================================================== *
 * 2. The label lookup — two answers, one decision.
 * ================================================================== */

test("R388n: the armed label names the MODE-switching answer, not the plain allow", () => {
  // ★ THE DEFECT ★ `a` and `m` both produce "allow". A lookup keyed on
  // the decision alone finds the first one, so the comment box header
  // would say "allow once" for an answer that ALSO switches the mode.
  // The user reads the header, believes no mode will change, and
  // presses Enter.
  const plain = comment.armDecision(comment.newCommentBox(), "a");
  const auto = comment.armDecision(comment.newCommentBox(), "m");
  assert.equal(comment.armedLabel(plain), "allow once");
  assert.notEqual(
    comment.armedLabel(auto),
    comment.armedLabel(plain),
    "arming 'm' must NOT show the same label as arming 'a' — the two " +
      "answers differ and the header must say so",
  );
  assert.match(
    comment.armedLabel(auto),
    /auto mode/i,
    "the 'm' answer's label must name what it switches to",
  );
});

/* ================================================================== *
 * 3. The wire — the mode travels on the reply.
 * ================================================================== */

test("R388n: a reply WITHOUT a mode is byte-identical to the pre-R388n reply", () => {
  const params = comment.permissionResponseParams("perm-1", "allow", undefined, undefined, "TUI");
  assert.deepEqual(
    Object.keys(params).sort(),
    ["decision", "reason", "requestId"],
    "a plain allow must not add a field — an old daemon must see nothing new",
  );
  assert.equal(params.mode, undefined);
});

test("R388n: a reply WITH a mode carries it in its own field", () => {
  const params = comment.permissionResponseParams(
    "perm-1",
    "allow",
    undefined,
    undefined,
    "TUI",
    undefined,
    modes.MODE.ACCEPT_TASK,
  );
  assert.equal(params.mode, modes.MODE.ACCEPT_TASK);
  // The mode is SEPARATE from the reason — the reason is the audit
  // trail, the mode is a state change.
  assert.equal(params.reason, "TUI picked allow");
  assert.notEqual(params.reason, params.mode);
});

/* ================================================================== *
 * 4. The Ink app — the key handler is a LOOKUP, not an if-chain.
 * ================================================================== */

test("R388n: the Ink decision keys are resolved through the registry", () => {
  const code = readCode("src/tui.tsx");
  const branchStart = code.indexOf("if (state.permissionAsk) {");
  assert.ok(branchStart > 0, "tui.tsx must have a permissionAsk branch");
  // The branch is bounded by the NEXT `else {` that closes it — the
  // hand-written chain is gone, so what remains must be a lookup.
  const branch = code.slice(branchStart, branchStart + 4000);
  assert.match(
    branch,
    /resolveDecisionKey\(/,
    "the decision keys must be resolved through the ONE registry",
  );
  assert.match(
    branch,
    /DECISION_KEYS/,
    "the numbered aliases must be the registry's projection, not a second numbering",
  );
});

test("R388n: the Ink app has NO hand-written per-decision-key if-chain", () => {
  // ★ THE DEFECT ★ the chain was a SECOND list of the decision keys.
  // The new 'm' key would be in the registry (so the card could
  // advertise it) and missing from the chain (so pressing it would do
  // nothing) — a key that looks alive and is dead.
  const code = readCode("src/tui.tsx");
  const branchStart = code.indexOf("if (state.permissionAsk) {");
  assert.ok(branchStart > 0, "tui.tsx must have a permissionAsk branch");
  const branch = code.slice(branchStart, branchStart + 4000);
  // No `input === "<letter>"` comparisons for the decision keys.
  const handWritten = [...branch.matchAll(/input\s*===\s*"([a-zA-Z])"/g)].map((m) => m[1]);
  assert.deepEqual(
    handWritten,
    [],
    `the decision branch still compares raw letters (${handWritten.join(", ")}) — ` +
      `the keys must come from the registry, or the registry and the handler ` +
      `can drift and a key can be advertised but dead`,
  );
});

test("R388n: the Ink reply path forwards the mode to the wire", () => {
  const body = tuiReplyBody();
  assert.match(
    body,
    /permissionResponseParams\(/,
    "replyPermission must build its params with permissionResponseParams",
  );
  // The mode argument must reach the params builder. It is the LAST
  // positional argument, so the call must carry it.
  assert.match(
    body,
    /permissionResponseParams\([\s\S]{0,400}?mode/,
    "replyPermission must forward the mode to the wire builder",
  );
});

test("R388n: the Ink app issues setPermissionMode when the answer carries a mode", () => {
  const body = tuiReplyBody();
  assert.match(
    body,
    /client\s*\.\s*request\(\s*"setPermissionMode"/,
    "the mode change must be issued — a mode on the wire that nothing " +
      "applies is a field nobody reads (Lesson 1230)",
  );
  // And it must be GUARDED by the mode, not sent unconditionally.
  assert.match(
    body,
    /if \(mode\)/,
    "setPermissionMode must be guarded by the mode — a reply without a " +
      "mode must not change the session's mode",
  );
});

test("R388n: the mode change is NOT chained onto the reply", () => {
  // ★ THE DEFECT ★ chaining the two means a failing `setPermissionMode`
  // delays or fails the APPROVAL — the user said yes and the tool did
  // not run.
  //
  // ★ The check is STRUCTURAL, not a regex over a window. ★
  //
  // The discriminator is: the mode RPC must not live INSIDE a promise
  // chain step's ARGUMENT LIST. A chained call looks like
  //   .then(() => client.request("setPermissionMode", ...))
  // — the RPC is textually inside the `.then(` argument. A separate
  // call is
  //   if (mode) { void client.request("setPermissionMode", ...) }
  // — the RPC is inside an `if` block, in its own statement.
  //
  // A window-based regex (`between` / `[\s\S]{0,N}`) is what this
  // project has been bitten by: it false-greens on a refactor and
  // false-reds on a rename (Lesson 1233 / 1346). So the extraction is
  // bracket-matched.
  const body = tuiReplyBody();

  // Every `.then(` / `.catch(` / `.finally(` argument list in the body.
  const chainArgs = [];
  const chainRe = /\.(?:then|catch|finally)\s*\(/g;
  for (const m of body.matchAll(chainRe)) {
    const open = m.index + m[0].length - 1;
    let depth = 0;
    for (let i = open; i < body.length; i++) {
      const ch = body[i];
      if (ch === "(") depth++;
      else if (ch === ")") {
        depth--;
        if (depth === 0) {
          chainArgs.push(body.slice(open + 1, i));
          break;
        }
      }
    }
  }
  assert.ok(
    chainArgs.length > 0,
    "the reply must have at least one promise chain step (the failure handler)",
  );
  for (const arg of chainArgs) {
    assert.doesNotMatch(
      arg,
      /request\(\s*"setPermissionMode"/,
      "setPermissionMode must NOT be chained onto the permissionResponse — " +
        "the approval must not depend on the mode change",
    );
  }
});

test("R388n: the Ink app reports the mode the DAEMON resolved", () => {
  // R377's rule: the daemon's answer is the only claim about what is in
  // force. `PermissionMode.valueOf` resolution means the request can be
  // refused or normalised — echoing the request would be a claim the
  // daemon never made.
  const body = tuiReplyBody();
  assert.match(
    body,
    /r\?\.mode\s*\?\?\s*mode/,
    "the toast must report the daemon's resolved mode, falling back to the request",
  );
});

/* ================================================================== *
 * 5. The permission card — the option must be VISIBLE.
 * ================================================================== */

test("R388n: the Ink permission card offers the mode-switching answer", () => {
  const card = readCode("src/components/Scrollback.tsx");
  // The card must render an [M] row.
  assert.match(
    card,
    /\[M\]/,
    "the expanded card must offer the 'M' answer — a feature the user " +
      "cannot see is a feature that does not exist",
  );
  // And the collapsed pill must name it too — the collapsed card is
  // what the user sees most of the time.
  assert.match(card, /M=auto/, "the collapsed pill must name the 'M' answer");
});

test("R388n: the card's 'M' row must NAME the mode it switches to", () => {
  const card = readCode("src/components/Scrollback.tsx");
  const mIdx = card.indexOf("[M]");
  assert.ok(mIdx > 0, "the card must have an [M] row");
  const row = card.slice(mIdx, mIdx + 300);
  // The row must name the mode, not just say "auto". The mode's own
  // name is the daemon's word and the user can change it afterwards.
  assert.match(
    row,
    /ACCEPT_TASK/,
    "the 'M' row must name the mode it switches to — 'auto' alone is " +
      "an acronym, and R388n exists because acronyms cannot carry the fact",
  );
});

/* ================================================================== *
 * 6. The line renderer — the SAME answer, the SAME wire.
 * ================================================================== */

test("R388n: the line permission picker offers the mode-switching answer", () => {
  const body = linePickerBody();
  assert.match(
    body,
    /MODE\.ACCEPT_TASK/,
    "the line picker must offer the same mode the Ink card offers",
  );
  assert.match(body, /\[5\] M/, "the line picker's fifth option must be the mode-switching answer");
});

test("R388n: the line picker's LETTER hotkey reaches the option it labels", () => {
  // ★ THE DEFECT ★ every option's label showed a letter ("[1] A",
  // "[5] M") but only the NUMBER was pressable — the letter was a
  // decoration the user would try and find dead.
  const body = linePickerBody();
  // The letter must come from the option's OWN field, so the hotkey
  // and the label cannot drift.
  assert.match(
    body,
    /letter:\s*"m"/,
    "the mode-switching option must carry its letter on the option itself",
  );
  assert.match(
    body,
    /options\.findIndex\(\s*\(o\)\s*=>\s*o\.letter/,
    "the letter hotkey must be looked up on the option's own field",
  );
});

test("R388n: the line picker returns the mode with the decision", () => {
  const body = linePickerBody();
  assert.match(body, /mode\?/, "the picker's result must carry the mode");
  // And decide() must put it on the resolved object. The window is the
  // WHOLE function — a fixed-size window would truncate the very
  // expression it is looking for (Lesson 1234: a truncated extraction
  // is the most expensive kind of green).
  const decideIdx = body.indexOf("function decide(");
  assert.ok(decideIdx > 0, "the picker must have a decide()");
  const nextFn = body.indexOf("\n    function ", decideIdx + 10);
  const decide = body.slice(decideIdx, nextFn > 0 ? nextFn : body.length);
  assert.match(
    decide,
    /options\[idx\]\.mode/,
    "decide() must resolve with the mode — an answer that switches the " +
      "mode but does not say so is an answer nobody can act on",
  );
});

test("R388n: the line renderer applies the mode over the wire", () => {
  const code = readCode("src/line.ts");
  const replyIdx = code.indexOf('"permissionResponse"');
  assert.ok(replyIdx > 0, "the line renderer must reply to the permission");
  const tail = code.slice(replyIdx, replyIdx + 3000);
  // ★ The RPC name must be matched as a WHOLE STRING, not as a
  // substring. `/setPermissionMode/` also matches
  // `setPermissionModeXXX` — so a typo'd RPC name (one the daemon does
  // not register) would satisfy the check, and the mode change would
  // fail with -32601 at the moment the user presses the key.
  assert.match(
    tail,
    /client\.request\(\s*"setPermissionMode"/,
    "the line renderer must issue setPermissionMode — the same answer, " +
      "the same wire, both renderers",
  );
});

test("R388n: the line renderer's mode RPC is NOT chained onto the reply", () => {
  // Same structural discriminator as the Ink app: the mode RPC must not
  // live inside a promise chain step's argument list.
  const code = readCode("src/line.ts");
  const replyIdx = code.indexOf('"permissionResponse"');
  const modeIdx = code.indexOf('"setPermissionMode"', replyIdx);
  assert.ok(replyIdx > 0 && modeIdx > replyIdx, "the mode RPC must follow the reply");

  const chainArgs = [];
  const chainRe = /\.(?:then|catch|finally)\s*\(/g;
  for (const m of code.matchAll(chainRe)) {
    const open = m.index + m[0].length - 1;
    let depth = 0;
    for (let i = open; i < code.length; i++) {
      const ch = code[i];
      if (ch === "(") depth++;
      else if (ch === ")") {
        depth--;
        if (depth === 0) {
          chainArgs.push(code.slice(open + 1, i));
          break;
        }
      }
    }
  }
  for (const arg of chainArgs) {
    assert.doesNotMatch(
      arg,
      /request\(\s*"setPermissionMode"/,
      "the mode RPC must not be chained onto the reply's promise chain — " +
        "the approval must not depend on the mode change",
    );
  }
});

/* ================================================================== *
 * 7. The daemon — the RPC the client calls must EXIST.
 * ================================================================== */

test("R388n: the daemon registers setPermissionMode on the dispatcher", () => {
  const java = read(
    "../aethercode/aethercode-protocol/src/main/java/org/aethercode/protocol/methods/AetherCodeMethods.java",
  );
  assert.match(
    java,
    /dispatcher\.register\("setPermissionMode"/,
    "the daemon must register setPermissionMode — a client RPC that does " +
      "not exist is a -32601 at the moment the user presses the key",
  );
});

test("R388n: the daemon's setPermissionMode answers with the mode it APPLIED", () => {
  const java = read(
    "../aethercode/aethercode-protocol/src/main/java/org/aethercode/protocol/methods/AetherCodeMethods.java",
  );
  // The answer must carry the resolved mode — the client's toast
  // reports THIS, not the request (R377).
  const start = java.indexOf("public Object setPermissionMode(Object params)");
  assert.ok(start > 0, "setPermissionMode must exist");
  const body = java.slice(start, start + 2500);
  assert.match(
    body,
    /"mode"\s*,\s*mode\.name\(\)/,
    "the answer must carry the resolved mode name — the client's report " +
      "must be the daemon's word, not the request",
  );
});
