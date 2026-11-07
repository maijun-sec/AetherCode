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

// R388n — the CHECKPOINT menu.
//
// ## ★ The feature ★
//
// Claude Code's `/rewind` opens a menu that offers SIX operations at
// each point in the conversation:
//
//   restore code and conversation / restore conversation / restore code
//   summarize from here / summarize up to here / never mind
//
// Before R388n the TUI had `/rewind <n>` — a bare number the user had
// to know, which no surface ever displayed — and a blind `Ctrl+Z`.
// TWO of the six operations ("Restore code", "Summarize from here")
// had no backend at all.
//
// ## ★ The defects this gate exists to hold ★
//
// (1) **A menu entry with no backend.** The menu is a list of LABELS
//     unless each entry names the OPERATION it performs. A label list
//     lets "Restore code" and "Restore conversation" drift into two
//     spellings of the same string, and lets an entry survive whose
//     backend was never written.
//
// (2) **A THIRD implementation of "restore both".** "Restore code and
//     conversation" is not a separate operation — it is the code
//     restore AND the conversation restore. If the dispatcher had its
//     own branch for it, a fix to one half would not reach the other,
//     and the user would get code restored in one menu entry and not
//     the other.
//
// (3) **The ordinal and the checkpoint are different addressing.** A
//     checkpoint records a TRANSCRIPT POSITION; an ordinal is
//     re-derived by counting user-role messages — and the engine
//     writes compaction notices, session anchors and controller
//     prompts with `Role.USER`. Counting those shifts a rewind by a
//     message, and the feature would look like it worked while
//     corrupting the conversation.
//
// (4) **A shortcut that eats a keystroke.** Esc already means "close
//     the overlay / stop the run / drop the queue". If the menu
//     grabbed the FIRST Esc, every panel would need two Escs to
//     close.
//
// (5) **One list, two renderers.** The line renderer and the Ink
//     component must describe the SAME six actions. Two copies drift.
//
// → **Lesson 1359 — a menu entry that names an OPERATION can be
// checked; a menu entry that names a LABEL cannot.**
// → **Lesson 1360 — "both" is a FLAG, not a third implementation.**
// → **Lesson 1361 — an ordinal and a position are different
// addresses; a restore that re-derives its own boundary is a restore
// that can land somewhere else.**
// → **Lesson 1362 — a shortcut must not take a meaning away from the
// key it shares.**

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

const TUI = "src";
// The Maven root is a SIBLING of the TUI package, not a child of it:
// `aethercode-tui/` and `aethercode/` sit side by side under the repo
// root.
const JAVA = join("..", "aethercode");

/* ------------------------------------------------------------------ *
 * Load the REAL modules — the gate must not re-implement the rules.
 * ------------------------------------------------------------------ */
const loadTs = async (rel) => {
  const { build } = await import("esbuild");
  const { mkdtempSync, rmSync } = await import("node:fs");
  const { tmpdir } = await import("node:os");
  const dir = mkdtempSync(join(tmpdir(), "r388n-checkpoint-"));
  const outfile = join(dir, "mod.mjs");
  try {
    await build({
      entryPoints: [join(root, rel)],
      bundle: true,
      outfile,
      platform: "node",
      format: "esm",
      loader: { ".ts": "ts", ".tsx": "tsx" },
      // The component pulls in `ink` and `react`; the gate only needs
      // its DATA, so the JSX runtime is stubbed rather than resolved.
      external: ["react", "ink", "ink-spinner"],
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

// The component's DATA (the action list and the flags) is what the
// gate checks, and it lives in a module that imports `react`/`ink`.
// esbuild bundles it with those external, so importing it would fail
// at runtime — so the action table is read from the SOURCE.
const menuSrc = read(`${TUI}/components/CheckpointMenu.tsx`);
const menuCode = readCode(`${TUI}/components/CheckpointMenu.tsx`);

const tuiSrc = read(`${TUI}/tui.tsx`);
const tuiCode = readCode(`${TUI}/tui.tsx`);
const lineSrc = read(`${TUI}/line.ts`);
const lineCode = readCode(`${TUI}/line.ts`);
const cmdSrc = read(`${TUI}/commands.ts`);
const cmdCode = readCode(`${TUI}/commands.ts`);
const intentsSrc = read(`${TUI}/panelIntents.ts`);
const stateCode = readCode(`${TUI}/state.ts`);

const methodsSrc = read(
  `${JAVA}/aethercode-protocol/src/main/java/org/aethercode/protocol/methods/AetherCodeMethods.java`,
);
const httpSrc = read(
  `${JAVA}/aethercode-protocol/src/main/java/org/aethercode/protocol/http/HttpJsonRpcServer.java`,
);
const engineSrc = read(
  `${JAVA}/aethercode-sdk/src/main/java/org/aethercode/sdk/AetherCodeEngine.java`,
);
const storeSrc = read(
  `${JAVA}/aethercode-core/src/main/java/org/aethercode/core/transcript/CodeCheckpointStore.java`,
);
const querySrc = read(
  `${JAVA}/aethercode-core/src/main/java/org/aethercode/core/engine/QueryEngine.java`,
);

// ★ The COMMENT-STRIPPED Java sources. ★ A "must contain this call"
// check that reads the raw source is satisfied by a commented-out
// line — the text is still in the file, so the gate passes on a tree
// where the code is gone. (This is not hypothetical: the mutation
// test caught exactly that.)
const engineCode = codeOf(engineSrc);
const queryCode = codeOf(querySrc);
const storeCode = codeOf(storeSrc);

/* ================================================================== *
 * 1. The action list — ONE list, in ONE place.
 * ================================================================== */

test("the six actions are declared once, in the component", () => {
  const decl = menuCode.match(/export const CHECKPOINT_ACTIONS = \[([^\]]*)\]/);
  assert.ok(decl, "CHECKPOINT_ACTIONS must be a literal array");
  const actions = [...decl[1].matchAll(/"([a-z_]+)"/g)].map((m) => m[1]);
  assert.deepEqual(actions, [
    "restore_code_and_conversation",
    "restore_conversation",
    "restore_code",
    "summarize_from_here",
    "summarize_up_to_here",
    "never_mind",
  ]);
});

test("the line renderer IMPORTS the action list — it keeps no copy", () => {
  assert.match(
    lineSrc,
    /import\s*\{[^}]*CHECKPOINT_ACTIONS[^}]*\}\s*from\s*"\.\/components\/CheckpointMenu\.js"/,
    "line.ts must import CHECKPOINT_ACTIONS from the component",
  );
  // A second literal list in the line renderer would be a second truth.
  assert.doesNotMatch(
    lineCode,
    /const\s+CHECKPOINT_ACTIONS\s*=/,
    "line.ts must not declare its own CHECKPOINT_ACTIONS",
  );
  assert.doesNotMatch(
    lineCode,
    /const\s+CHECKPOINT_ACTION_INFO\s*=/,
    "line.ts must not declare its own CHECKPOINT_ACTION_INFO",
  );
});

test("every action has an entry in the action table", () => {
  const decl = menuCode.match(/export const CHECKPOINT_ACTIONS = \[([^\]]*)\]/);
  const actions = [...decl[1].matchAll(/"([a-z_]+)"/g)].map((m) => m[1]);
  const table = menuCode.match(/export const CHECKPOINT_ACTION_INFO[^=]*=\s*\{([\s\S]*?)\n\};/);
  assert.ok(table, "CHECKPOINT_ACTION_INFO must be a literal object");
  for (const a of actions) {
    assert.match(table[1], new RegExp(`\\b${a}:\\s*\\{`), `${a} needs a table entry`);
  }
});

/* ================================================================== *
 * 2. The FLAGS are the contract — "both" is not a third operation.
 * ================================================================== */

test("each action declares the two flags the dispatcher reads", () => {
  const table = menuCode.match(/export const CHECKPOINT_ACTION_INFO[^=]*=\s*\{([\s\S]*?)\n\};/);
  assert.ok(table);
  const body = table[1];
  // Every entry has BOTH flags.
  const entries = body.split(/\n  [a-z_]+:\s*\{/).slice(1);
  assert.ok(entries.length >= 6, `expected 6 entries, got ${entries.length}`);
  for (const e of entries) {
    assert.match(e, /touchesCode:\s*(true|false)/, "every entry needs touchesCode");
    assert.match(e, /touchesConversation:\s*(true|false)/, "every entry needs touchesConversation");
  }
});

test("the Ink dispatcher reads the FLAGS, not the action names", () => {
  // A branch per action name would let "restore_code_and_conversation"
  // become a THIRD implementation — the drift this gate exists to
  // stop.
  const dispatcher = tuiCode.match(/async function applyCheckpointAction\([\s\S]*?\n  \}/);
  assert.ok(dispatcher, "applyCheckpointAction must exist");
  const body = dispatcher[0];
  assert.match(body, /touchesCode/, "the dispatcher must read touchesCode");
  assert.match(body, /touchesConversation/, "the dispatcher must read touchesConversation");
  assert.doesNotMatch(
    body,
    /action === "restore_code_and_conversation"/,
    'the dispatcher must NOT special-case "restore_code_and_conversation"',
  );
});

test("the dispatcher check reads COMMENT-STRIPPED source", () => {
  // ★ A "must NOT contain" check that reads the RAW source is
  // satisfied by a COMMENT. ★ Commenting out the offending line
  // leaves the text in the file, so the check passes on a tree where
  // the code is gone.
  //
  // Lesson 1215 — a checker that answers a slightly different
  // question than the one you think is worse than no checker.
  assert.doesNotMatch(
    tuiCode,
    /action === "restore_code_and_conversation"/,
    "the stripped source must not contain the special case",
  );
  // And the stripped source must be a SUBSET of the raw source with
  // the comments gone — if `codeOf` were a no-op, the check above
  // would be the raw check again.
  assert.ok(
    tuiCode.length <= tuiSrc.length,
    "the stripped source must not be longer than the raw source",
  );
});

test("the line dispatcher also reads the FLAGS", () => {
  const fn = lineCode.match(/async function panelCheckpoints\([\s\S]*?\n\}/);
  assert.ok(fn, "panelCheckpoints must exist in line.ts");
  const body = fn[0];
  assert.match(body, /info\.touchesCode/, "line.ts must read info.touchesCode");
  assert.match(body, /info\.touchesConversation/, "line.ts must read info.touchesConversation");
});

/* ================================================================== *
 * 3. The menu is reachable — the sentinel, the intent, the panel.
 * ================================================================== */

test("bare /rewind opens the menu instead of demanding an index", () => {
  const arm = cmdCode.match(/case "rewind":\s*\{([\s\S]*?)\n    \}/);
  assert.ok(arm, "the /rewind arm must exist");
  assert.match(arm[1], /__CHECKPOINT_MENU__/, "bare /rewind must emit the checkpoint sentinel");
  // The indexed form is KEPT — it is a working, precise operation.
  assert.match(arm[1], /rpcMethod:\s*"rewind"/, "/rewind <n> must still work");
});

test("the sentinel is claimed by the intent registry", () => {
  const intent = intentsSrc.match(/id:\s*"checkpoint-menu"[\s\S]*?\n  \},/);
  assert.ok(intent, "the checkpoint-menu intent must exist");
  assert.match(intent[0], /__CHECKPOINT_MENU__/, "the intent must own the sentinel");
  assert.match(intent[0], /command:\s*"\/rewind"/, "the intent must name /rewind");
});

test("BOTH renderers claim the sentinel", () => {
  assert.match(
    tuiCode,
    /"checkpoint-menu":\s*"checkpoints"/,
    "the Ink panel map must route the intent",
  );
  assert.match(
    lineCode,
    /case "__CHECKPOINT_MENU__":/,
    "the line renderer must claim the sentinel",
  );
});

test("the Ink panel is MOUNTED, not a placeholder", () => {
  assert.match(
    tuiCode,
    /case "checkpoints":\s*\n?\s*return \(\s*\n\s*<CheckpointMenu/,
    "the checkpoint panel must render the CheckpointMenu component",
  );
});

/* ================================================================== *
 * 4. Esc Esc — two presses, and the FIRST one still means something.
 * ================================================================== */

test("the menu opens on the SECOND Esc, not the first", () => {
  const esc = tuiCode.match(/if \(key\.escape\) \{([\s\S]*?)\n    \}/);
  assert.ok(esc, "the Esc ladder must exist");
  const body = esc[0];
  assert.match(body, /escEscRef\.current/, "the Esc ladder must consult the double-press ref");
  assert.match(body, /ESC_ESC_WINDOW_MS/, "the second press must be within a time window");
  // The opening is INSIDE the window check, so the first press falls
  // through to the rest of the ladder.
  const openAt = body.indexOf('type: "openPanel"');
  const windowAt = body.indexOf("ESC_ESC_WINDOW_MS");
  assert.ok(openAt > 0 && windowAt > 0);
  assert.ok(
    windowAt < openAt,
    "the window check must come BEFORE the panel opens — otherwise the first Esc opens it",
  );
});

test("the panel's open state is the ACTIVE PANEL — there is no second flag", () => {
  // Two holders of one fact is two truths. `checkpointMenuOpen` was
  // declared and never read — the Lesson 1330 shape.
  assert.doesNotMatch(
    stateCode,
    /checkpointMenuOpen/,
    "state.ts must not carry a second 'the menu is open' flag",
  );
});

/* ================================================================== *
 * 5. The daemon: the RPCs, on BOTH transports.
 * ================================================================== */

test("the three checkpoint RPCs are REGISTERED on stdio", () => {
  for (const m of ["checkpoints/list", "checkpoints/restore", "checkpoints/summarize"]) {
    assert.match(
      methodsSrc,
      new RegExp(`dispatcher\\.register\\("${m.replace("/", "\\/")}"`),
      `${m} must be registered`,
    );
  }
});

test("the three checkpoint RPCs are ROUTED on HTTP", () => {
  for (const m of ["checkpoints/list", "checkpoints/restore", "checkpoints/summarize"]) {
    assert.match(
      httpSrc,
      new RegExp(`case "${m.replace("/", "\\/")}"\\s*->`),
      `${m} must be routed on HTTP`,
    );
  }
});

test("registration and routing are BOTH checked — a method on one transport only is invisible", () => {
  // Lesson 1328: a shared registry makes "is it registered?" answer
  // yes on both transports, so the only question that reaches the
  // defect is "does THIS transport route it?".
  const registered = [...methodsSrc.matchAll(/dispatcher\.register\("([^"]+)"/g)].map((m) => m[1]);
  const routed = [...httpSrc.matchAll(/case "([^"]+)"\s*->/g)].map((m) => m[1]);
  for (const m of ["checkpoints/list", "checkpoints/restore", "checkpoints/summarize"]) {
    assert.ok(registered.includes(m), `${m} must be registered`);
    assert.ok(routed.includes(m), `${m} must be routed`);
  }
});

/* ================================================================== *
 * 6. The ordinal rule is SHARED — one counting rule, one place.
 * ================================================================== */

test("the ordinal rule lives in ONE helper", () => {
  assert.match(
    storeCode,
    /public static List<Integer> userTurnIndices\(/,
    "the shared counting rule must exist",
  );
  assert.match(
    storeCode,
    /public static boolean isRealUserTurn\(/,
    "the shared turn test must exist",
  );
});

test("the engine's checkpoint producer uses the SHARED rule", () => {
  const save = engineCode.match(
    /public org\.aethercode\.core\.transcript\.CodeCheckpointStore\.Checkpoint\s*\n?\s*saveCodeCheckpoint\([\s\S]*?\n    \}/,
  );
  assert.ok(save, "saveCodeCheckpoint must exist");
  assert.match(
    save[0],
    /userTurnIndices/,
    "the checkpoint producer must resolve the ordinal with the shared rule",
  );
  // The old shape derived the transcript index from the ordinal
  // directly — which is only right when every USER message is a turn.
  assert.doesNotMatch(
    save[0],
    /Math\.min\(userMessageIndex,\s*t\.size\(\)\)/,
    "the transcript index must not be derived from the ordinal directly",
  );
});

test("the summarise path uses the SHARED rule too", () => {
  const sum = engineCode.match(
    /public java\.util\.Map<String, Object> summarizeFrom\([\s\S]*?\n    \}/,
  );
  assert.ok(sum, "summarizeFrom must exist");
  assert.match(
    sum[0],
    /userTurnIndices/,
    "summarizeFrom must resolve the boundary with the shared rule",
  );
});

test("the checkpoint-anchored restore uses the checkpoint's OWN position", () => {
  const restore = engineCode.match(
    /public java\.util\.Map<String, Object> restoreConversationTo\([\s\S]*?\n    \}/,
  );
  assert.ok(restore, "restoreConversationTo must exist");
  assert.match(
    restore[0],
    /cp\.transcriptIndex\(\)/,
    "the restore must use the checkpoint's recorded position, not a re-derived ordinal",
  );
});

/* ================================================================== *
 * 7. The checkpoints are actually PRODUCED.
 * ================================================================== */

test("a checkpoint is taken at the TURN BOUNDARY", () => {
  // The snapshot has to be taken BEFORE the turn's tools run — a
  // checkpoint taken afterwards captures the "after" state and restores
  // nothing.
  //
  // ★ The check reads the COMMENT-STRIPPED source. ★ A raw-source
  // check is satisfied by a commented-out line: the text is still in
  // the file, so the gate passes on a tree where the code is gone.
  assert.match(queryCode, /setOnTurnBoundary/, "QueryEngine must expose a turn-boundary hook");
  assert.match(
    queryCode,
    /onTurnBoundary\.accept\(userInput\)/,
    "the turn-boundary hook must fire on the user prompt",
  );
  assert.match(engineCode, /setOnTurnBoundary\(/, "the engine must install the turn-boundary hook");
  assert.match(engineCode, /saveCodeCheckpoint\(ordinal/, "the hook must take a checkpoint");
});

test("the daemon's restore takes a SCOPE, and 'both' is one request", () => {
  // Two requests would mean the code restore could succeed while the
  // conversation restore failed, and the user would be told "done"
  // with half the work undone.
  const restore = methodsSrc.match(
    /public Object checkpointsRestore\(Object params\) \{[\s\S]*?\n    \}/,
  );
  assert.ok(restore, "checkpointsRestore must exist");
  assert.match(restore[0], /scope/, "the restore must read a scope");
  assert.match(restore[0], /"both"/, "the scope vocabulary must include 'both'");
});

test("the summarise RPC prefers the CHECKPOINT over the ordinal", () => {
  const sum = methodsSrc.match(
    /public Object checkpointsSummarize\(Object params\) \{[\s\S]*?\n    \}/,
  );
  assert.ok(sum, "checkpointsSummarize must exist");
  assert.match(sum[0], /checkpointId/, "the RPC must accept a checkpointId");
  const cpAt = sum[0].indexOf("checkpointId");
  const targetAt = sum[0].indexOf('p.get("target")');
  assert.ok(cpAt >= 0 && targetAt >= 0, "the RPC must accept both a checkpointId and a target");
  assert.ok(
    cpAt < targetAt,
    "the checkpoint path must come FIRST — it addresses a recorded position",
  );
});
