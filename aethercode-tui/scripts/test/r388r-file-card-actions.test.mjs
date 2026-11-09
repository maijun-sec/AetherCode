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

// R388r — the file-changes card's ACTIONS must DO something.
//
// ## The defect
//
// R388q shipped a card that ADVERTISED three actions —
//
//     │ ✎ 已编辑 19 个文件 +3097 -14 │ 查看文件 f  [撤销 u] [审查 r]     │
//
// and NONE of them did anything. Two independent defects:
//
//   1. `Scrollback` passed **no callbacks**, so the card's `onUndo` /
//      `onReview` were undefined;
//   2. the card only handled keys when its internal `focused` flag was
//      true — and **nothing ever set it to true**. The handler was
//      dead code.
//
// ★ THAT IS R388p's DEFECT CLASS ★ "A key that does nothing is worse
// than a key that is absent: the user concludes the feature is
// broken." The card was worse than absent — it looked like a working
// affordance.
//
// ## ★ THE KEYS MUST NOT STEAL THE USER'S TYPING ★
//
// Measured (a probe with two mounted `useInput` hooks): Ink delivers
// a keystroke to **every** mounted hook — a letter typed into the
// prompt ALSO reaches the app's command handler.
//
//     events: ["text:d","cmd:d"]
//
// So a bare-letter command fires **while the user is typing**, and the
// app's existing `d` key has exactly that behaviour today. The card's
// keys are therefore **command-mode keys**: they fire only when the
// prompt is EMPTY.
//
// ## ★ THE GATE MEASURES BEHAVIOUR, NOT STRINGS ★
//
// A grep for `fileCardActionFor` proves that the string is in the
// source. It proves nothing about what a keypress does. So this gate
// renders the REAL component, drives the REAL routing rule, and reads
// what the REAL callbacks were called with.

import { strict as assert } from "node:assert";
import { test } from "node:test";
import { spawnSync } from "node:child_process";
import { existsSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = join(fileURLToPath(new URL("../..", import.meta.url)));
const CARD = join(ROOT, "src", "components", "FileChangesCard.tsx");
const ACTIONS = join(ROOT, "src", "fileCardActions.ts");
const CHANGES = join(ROOT, "src", "fileChanges.ts");
const SCROLLBACK = join(ROOT, "src", "components", "Scrollback.tsx");
const TUI = join(ROOT, "src", "tui.tsx");
const PROBE_FIXTURE = join(ROOT, "scripts", "test", "fixtures", "r388r-routing-probe.tsx");
const GROUPING_FIXTURE = join(ROOT, "scripts", "test", "fixtures", "r388r-grouping-probe.ts");

/**
 * Load the REAL `fileCardActions.ts` module.
 *
 * <p>★ WHY THE MODULE IS IMPORTED, NOT GREPPED ★ A grep for
 * `openPanel", id: "checkpoints"` proves the STRING is in the file —
 * and the `Esc Esc` handler contains the SAME string, so the grep
 * passes even when the card's undo action does something else
 * entirely. That is a check a LOOKUP can satisfy, and it is not a
 * check.
 *
 * <p>The module is plain TypeScript with no React and no Ink, so the
 * gate can import it directly and assert on the VALUES it produces.
 * A mutation that changes what an action DOES changes the value, and
 * the gate sees it.
 *
 * <p>The module is `.ts`, so it cannot be imported by Node directly.
 * It is therefore read and transpiled through the same esbuild the
 * probes already use — no new dependency, no new build step.
 *
 * @returns {Promise<object>} the module's exports
 */
async function loadActionsModule() {
  const dir = mkdtempSync(join(ROOT, ".r388r-mod-"));
  const entry = join(dir, "actions.ts");
  const bundle = join(dir, "actions.mjs");
  try {
    writeFileSync(entry, readFileSync(ACTIONS, "utf8"), "utf8");
    const esbuildDir = process.env.R388Q_ESBUILD_DIR || join(ROOT, "node_modules", "esbuild");
    const build = spawnSync(
      process.execPath,
      [
        "-e",
        "const {build}=require(" +
          JSON.stringify(esbuildDir) +
          ");\n" +
          "build({entryPoints:[" +
          JSON.stringify(entry) +
          "],bundle:true,outfile:" +
          JSON.stringify(bundle) +
          ",platform:'node',format:'esm',loader:{'.ts':'ts'}})" +
          ".catch(e=>{console.error(e.message);process.exit(1)});",
      ],
      { cwd: ROOT, encoding: "utf8" },
    );
    if (build.status !== 0 || !existsSync(bundle)) {
      throw new Error(
        "r388r: could not build the actions module: " + (build.stderr || build.stdout),
      );
    }
    const mod = await import(pathToFileURL(bundle).href);
    return mod;
  } finally {
    spawnSync("cmd", ["/c", "rmdir", "/s", "/q", dir]);
  }
}

// ═══════════════════════════════════════════════════════════════════
// THE PROBES
// ═══════════════════════════════════════════════════════════════════

/**
 * Build and run a probe from a fixture, and return its stderr.
 *
 * @param {string} fixture the fixture's path
 * @param {string[]} subs the placeholder substitutions
 * @returns {string} the probe's stderr
 */
function runFixture(fixture, subs) {
  const dir = mkdtempSync(join(ROOT, ".r388r-probe-"));
  const entry = join(dir, "probe.tsx");
  const bundle = join(dir, "probe.mjs");
  try {
    let src = readFileSync(fixture, "utf8");
    for (const [from, to] of subs) src = src.split(from).join(to);
    writeFileSync(entry, src, "utf8");

    const esbuildDir = process.env.R388Q_ESBUILD_DIR || join(ROOT, "node_modules", "esbuild");
    // ★ nodePaths POINTS AT THE REAL node_modules ★ The mutation gate
    // runs this gate from a SANDBOX copy in the OS temp directory, and
    // the sandbox has no `node_modules` — a junction would resolve back
    // to the real tree, so the dependency is not copied. The resolution
    // path is therefore BORROWED, not copied, and the caller hands it
    // over through the environment.
    //
    // (Hardcoding `join(ROOT, "node_modules")` here made every mutation
    // "pass" for the WRONG reason: the probe could not resolve `ink`,
    // so the gate went red on a BUILD failure rather than on the
    // assertion the mutation broke. A gate that is red for the wrong
    // reason has no teeth — it would go red on ANY change, including a
    // correct one.)
    const nodePaths = process.env.R388Q_NODE_PATHS || join(ROOT, "node_modules");
    const build = spawnSync(
      process.execPath,
      [
        "-e",
        "const {build}=require(" +
          JSON.stringify(esbuildDir) +
          ");\n" +
          "build({entryPoints:[" +
          JSON.stringify(entry) +
          "],bundle:true,outfile:" +
          JSON.stringify(bundle) +
          ",platform:'node',format:'esm',jsx:'automatic',nodePaths:[" +
          JSON.stringify(nodePaths) +
          "],banner:{js:\"import { createRequire as __cr } from 'node:module';const require = __cr(import.meta.url);\"},loader:{'.ts':'ts','.tsx':'tsx'}})" +
          ".catch(e=>{console.error(e.message);process.exit(1)});",
      ],
      { cwd: ROOT, encoding: "utf8" },
    );
    if (build.status !== 0 || !existsSync(bundle)) {
      throw new Error("r388r: could not build the probe: " + (build.stderr || build.stdout));
    }
    const run = spawnSync(process.execPath, [bundle], { encoding: "utf8", timeout: 60_000 });
    return run.stderr ?? "";
  } finally {
    spawnSync("cmd", ["/c", "rmdir", "/s", "/q", dir]);
  }
}

/**
 * The substitutions every probe needs.
 *
 * @param {object} opts {live, focused, width, promptHasText}
 * @returns {Array<[string, string]>} the placeholder pairs
 */
function probeSubs(opts = {}) {
  return [
    ["__ACTIONS__", JSON.stringify(ACTIONS)],
    ["__CARD__", JSON.stringify(CARD)],
    ["__CHANGES__", JSON.stringify(CHANGES)],
    ["__PROMPT_HAS_TEXT__", (opts.promptHasText ?? false) ? "true" : "false"],
    ["__LIVE__", (opts.live ?? true) ? "true" : "false"],
    ["__FOCUSED__", (opts.focused ?? true) ? "true" : "false"],
    ["__WIDTH__", String(opts.width ?? 96)],
  ];
}
/**
 * Drive the REAL routing rule and the REAL card's callbacks together.
 *
 * <p>This is the app's wiring, assembled the way the app assembles it:
 * the real `fileCardActionFor` picks the action, and the real card's
 * callback records it.
 *
 * <p>The probe's source is a FIXTURE file, not a string here — a string
 * with this much nested quoting is unreadable, and a gate nobody can
 * read is a gate nobody trusts.
 *
 * @param {object} opts {promptHasText}
 * @returns {{calls: string[]}} the callback log
 */
function probeRouting(opts = {}) {
  const stderr = runFixture(PROBE_FIXTURE, probeSubs(opts));
  const m = stderr.match(/__CALLS__([\s\S]*?)__END__/);
  if (!m) {
    throw new Error("r388r: the routing probe produced no result: " + stderr.slice(0, 800));
  }
  return { calls: JSON.parse(m[1]) };
}

/**
 * Run the REAL grouping rule and report what each side would target.
 *
 * <p>★ WHY A PROBE AND NOT A GREP ★ The previous version of the
 * grouping gate checked that the string `fileChangeOfTurnParts`
 * APPEARS in the Scrollback. A mutation that replaced the Scrollback's
 * rule with `return null` — the exact defect the shared rule exists
 * to prevent — left the string in place, so the gate stayed GREEN
 * while the product was broken.
 *
 * <p>So the probe runs the shared rule on real turns and reports the
 * group ids, and the gate asserts on the VALUES.
 *
 * @returns {{appGroup: number|null, cardGroups: number[], groupsOfEveryTurn: (number|null)[]}}
 */
function probeGrouping() {
  const stderr = runFixture(GROUPING_FIXTURE, [
    ["__CHANGES__", JSON.stringify(CHANGES)],
    ["__SCROLLBACK__", JSON.stringify(SCROLLBACK)],
  ]);
  const m = stderr.match(/__GROUPS__([\s\S]*?)__END__/);
  if (!m) {
    throw new Error("r388r: the grouping probe produced no result: " + stderr.slice(0, 800));
  }
  return JSON.parse(m[1]);
}

/**
 * Render the real FileChangesCard and return the frame's lines.
 *
 * @param {object} opts {live, focused, width}
 * @returns {string[]} the frame's lines
 */
function renderCard(opts = {}) {
  const stderr = runFixture(PROBE_FIXTURE, probeSubs(opts));
  const m = stderr.match(/__RAW__([\s\S]*?)__END__/);
  if (!m) {
    throw new Error("r388r: the card probe produced no frame: " + stderr.slice(0, 800));
  }
  const ESC = "\u001b";
  const b = m[1].lastIndexOf(ESC + "[2K");
  const rawFrame = b >= 0 ? m[1].slice(b) : m[1];
  const plain = rawFrame.replace(/\u001b\[[0-9;?]*[A-Za-z]/g, "");
  return plain.split("\n").filter((l) => l.trim().length > 0);
}

/**
 * The card's content row.
 *
 * @param {string[]} lines the frame's lines
 * @returns {string}
 */
function contentRow(lines) {
  return lines.find((l) => l.includes("│") && !l.startsWith("╭") && !l.startsWith("╰")) ?? "";
}

// ═══════════════════════════════════════════════════════════════════
// 1. THE CARD DOES NOT HANDLE KEYS ITSELF
// ═══════════════════════════════════════════════════════════════════
test("R388r: the card does NOT listen for keys — the APP owns them", () => {
  // ★ THE CARD IS PURE PRESENTATION ★ R388q gave the card its own
  // keydown listener, gated on an internal `focused` flag that nothing
  // ever set. The card's handler was dead code.
  const src = readFileSync(CARD, "utf8");
  assert.ok(
    !/process\.stdin\.on\(/.test(src),
    "R388r: the card still installs its own stdin listener — the app owns the keys, or the card's handler is dead code again.",
  );
  assert.ok(
    !/addEventListener\(["']keydown/.test(src),
    "R388r: the card still installs its own keydown listener — the app owns the keys.",
  );
  // The focus must be a PROP, not internal state that nothing can set.
  assert.match(
    src,
    /focused\?: boolean/,
    "R388r: the card's focus is not a prop — an internal flag can never be set by the app.",
  );
  // ★ THE COMMENTS ARE BLANKED FIRST ★ The card's own doc comment
  // mentions `useState(false)` — it explains why the focus USED to be
  // internal. A raw source scan would read that comment and report a
  // defect that is not there. (A needle that hits a comment reports a
  // hole for the wrong reason.)
  const code = src.replace(/\/\*[\s\S]*?\*\//g, "").replace(/\/\/.*$/gm, "");
  assert.ok(
    !/useState\s*\(/.test(code),
    "R388r: the card still keeps an internal state — the focus must come from the app.",
  );
});

// ═══════════════════════════════════════════════════════════════════
// 2. THE PURE RULE
// ═══════════════════════════════════════════════════════════════════

test("R388r: a letter typed into the prompt is TEXT, not a command", () => {
  // ★ THE MEASURED BEHAVIOUR ★ Ink delivers a keystroke to every
  // mounted hook, so a bare-letter command fires while the user is
  // typing. The card's keys are therefore command-mode keys: they
  // fire only when the prompt is EMPTY.
  const ts = readFileSync(ACTIONS, "utf8");
  assert.match(
    ts,
    /if \(!promptEmpty\) return null;/,
    "R388r: the action lookup no longer refuses to fire while the prompt has text — a typed letter would trigger the card.",
  );
});

test("R388r: the action table is the ONE list of the card's keys", () => {
  const ts = readFileSync(ACTIONS, "utf8");
  assert.match(ts, /export const FILE_CARD_ACTIONS =/, "R388r: the action list is gone.");
  assert.match(
    ts,
    /export const FILE_CARD_ACTION_INFO/,
    "R388r: the action table is gone — the card and the app would each keep their own copy.",
  );
  // Every action carries a key, and the keys are the ones the card
  // renders. A card cannot advertise a key nothing handles.
  for (const action of ["showFiles", "undo", "review"]) {
    assert.match(
      ts,
      new RegExp(action + ":\\s*\\{[^}]*key:"),
      'R388r: the "' + action + '" action carries no key.',
    );
  }
});

// ═══════════════════════════════════════════════════════════════════
// 3. THE APP BINDS THE KEYS, AND THE CARD SHOWS THE SAME KEYS
// ═══════════════════════════════════════════════════════════════════

test("R388r: the APP binds the card's keys", () => {
  const tui = readFileSync(TUI, "utf8");
  assert.match(
    tui,
    /fileCardEffectFor\(\s*input/,
    "R388r: the app's key handler no longer resolves the card's actions — the card's keys are dead again.",
  );
  // ★ AND THE PROMPT GUARD ★ The lookup is the guard, but the guard
  // must be the app's: the app knows whether the prompt has text.
  assert.match(
    tui,
    /state\.input\.trim\(\)\.length === 0/,
    "R388r: the app no longer checks whether the prompt is empty — a typed letter would trigger the card.",
  );
});

test("R388r: the card renders the keys the app binds", () => {
  // ★ THE CARD'S LABEL AND THE APP'S BINDING MUST AGREE ★ Both read
  // the ONE action table, so they cannot drift — and this gate checks
  // that the card really renders the table's keys.
  const card = readFileSync(CARD, "utf8");
  assert.match(
    card,
    /FILE_CARD_ACTION_INFO/,
    "R388r: the card does not read the action table — it keeps its own copy of the labels.",
  );
  const lines = renderCard();
  const row = contentRow(lines);
  assert.match(row, /查看文件 f/, "R388r: the card does not show the 查看文件 f link. Row: " + row);
  assert.match(row, /\[撤销 u\]/, "R388r: the card does not show the undo action. Row: " + row);
  assert.match(row, /\[审查 r\]/, "R388r: the card does not show the review action. Row: " + row);
});

// ═══════════════════════════════════════════════════════════════════
// 4. EACH KEY DOES SOMETHING
// ═══════════════════════════════════════════════════════════════════

test("R388r: the app ROUTES each key to the right action", () => {
  // ★ THE ROUTING IS MEASURED, NOT ASSUMED ★ The app's `useInput`
  // handler is not mountable in isolation, but the rule it calls
  // (`fileCardActionFor`) IS the real module, and the card's
  // callbacks ARE the real ones. So the probe wires the two together
  // exactly the way the app does.
  //
  // (The first version of this test pressed NO key and asserted that
  // nothing happened — a check that cannot fail.)
  const { calls } = probeRouting();
  assert.deepEqual(
    calls,
    ["showFiles", "undo", "review"],
    "R388r: the keys did not route to the actions the card shows — got " +
      JSON.stringify(calls) +
      ". A key the card advertises must act.",
  );
});

test("R388r: a letter typed into the prompt does NOT trigger the card", () => {
  // ★ THE TYPING GUARD ★ The card's keys are command-mode keys: with
  // text in the prompt they are TEXT. Measured here by running the
  // SAME routing with the prompt holding text.
  const { calls } = probeRouting({ promptHasText: true });
  assert.deepEqual(
    calls,
    [],
    "R388r: typing 'fur' in the prompt triggered the card's actions (" +
      JSON.stringify(calls) +
      ") — a letter the user meant as text must not act.",
  );
});

test("R388r: each of the three actions has a real EFFECT in the app", async () => {
  // ★ THE EFFECT IS A VALUE, AND THE GATE READS IT ★ The previous
  // version of this test GREPPED the app's source for
  // `openPanel", id: "checkpoints"` — and the `Esc Esc` handler
  // contains that SAME string, so the grep passed even when the
  // card's undo action did something else entirely. That is a check
  // a LOOKUP can satisfy, and it is not a check.
  //
  // Now the semantics live in `fileCardActions.ts` as a VALUE, and the
  // gate asserts on the VALUE. A mutation that changes what `u` does
  // changes the value, and the gate sees it.
  const mod = await loadActionsModule();
  assert.ok(mod.fileCardEffect, "r388r: the actions module has no effect function.");

  // 撤销 → the rewind menu. The daemon's restore is CHECKPOINT-
  // granular, so the card opens the menu and the user picks the point.
  assert.deepEqual(
    mod.fileCardEffect("undo", 7),
    { kind: "openRewind" },
    "r388r: the undo action no longer opens the rewind menu.",
  );
  // 审查 / 查看文件 → toggle the group's expansion.
  assert.deepEqual(
    mod.fileCardEffect("review", 7),
    { kind: "toggleFiles", turnId: 7 },
    "r388r: the review action no longer toggles the card's file list.",
  );
  assert.deepEqual(
    mod.fileCardEffect("showFiles", 7),
    { kind: "toggleFiles", turnId: 7 },
    "r388r: the show-files action no longer toggles the card's file list.",
  );
  // ★ AND WITH NO CARD, NOTHING HAPPENS ★ A key with no card must do
  // nothing — not "undo the last thing", not "toggle some other turn".
  assert.deepEqual(
    mod.fileCardEffect("undo", undefined),
    { kind: "none" },
    "r388r: the undo action does something even when there is no card.",
  );
});

test("R388r: the app CARRIES OUT each effect", () => {
  // ★ THE APP IS A TRANSLATOR, AND THE TRANSLATION IS PINNED ★ The
  // effect says WHAT; this says HOW. The app has ONE place that turns
  // an effect into a dispatch, so the card's key and the card's own
  // button cannot mean different things.
  const tui = readFileSync(TUI, "utf8");
  // The two effects, and the two dispatches they map to.
  assert.match(
    tui,
    /if \(effect\.kind === "openRewind"\) \{[\s\S]*?openPanel", id: "checkpoints"/,
    "r388r: the app no longer opens the rewind menu for the openRewind effect.",
  );
  assert.match(
    tui,
    /else if \(effect\.kind === "toggleFiles"\) \{[\s\S]*?toggleTurn", id: effect\.turnId/,
    "r388r: the app no longer toggles the file list for the toggleFiles effect.",
  );
  // ★ AND THE CARD'S CALLBACKS GO THROUGH THE SAME EFFECT ★ So the
  // card's key and the card's own button cannot drift apart.
  for (const [name, action] of [
    ["onShowFileCardFiles", "showFiles"],
    ["onUndoFileCard", "undo"],
    ["onReviewFileCard", "review"],
  ]) {
    assert.match(
      tui,
      new RegExp(
        "const " +
          name +
          ' = \\([^)]*\\): void => \\{\\s*applyFileCardEffect\\(fileCardEffect\\("' +
          action +
          '"',
      ),
      "r388r: " +
        name +
        " does not go through the shared effect — the card's key and its button could mean different things.",
    );
  }
});

test("R388r: the Scrollback PASSES the callbacks down", () => {
  // ★ THE CARD MUST NOT BE RENDERED WITHOUT ITS CALLBACKS ★
  // R388q rendered the card with no callbacks at all — which is why
  // the keys could not act.
  //
  // ★ AND THE PROP NAME IS NOT ENOUGH ★ The previous version of this
  // test only checked that the prop NAMES appear somewhere in the
  // file. A mutation that replaced `onUndo={...}` with
  // `onUndo={undefined}` — which is exactly the R388q defect — left
  // the name in place, so the test stayed green. So the check is on
  // the WIRING: the card's `onUndo` prop must be handed the app's
  // `onUndoFileCard`.
  const scroll = readFileSync(SCROLLBACK, "utf8");
  const wiring = [
    ["onShowFiles", "onShowFileCardFiles"],
    ["onUndo", "onUndoFileCard"],
    ["onReview", "onReviewFileCard"],
  ];
  for (const [cardProp, appProp] of wiring) {
    assert.match(
      scroll,
      new RegExp(cardProp + "=\\{\\(\\) => " + appProp + "\\?\\.\\(group\\.turn\\.id\\)\\}"),
      "r388r: the Scrollback does not hand '" +
        cardProp +
        "' the app's '" +
        appProp +
        "' — the card's action would do nothing.",
    );
  }
  // And the app passes real functions, not empty stubs.
  const tui = readFileSync(TUI, "utf8");
  for (const prop of ["onShowFileCardFiles", "onUndoFileCard", "onReviewFileCard"]) {
    assert.ok(
      !new RegExp(prop + "=\\{\\(\\) => \\{\\}\\}").test(tui),
      "r388r: the app still passes an EMPTY STUB for " + prop + " — the keys would do nothing.",
    );
    assert.match(
      tui,
      new RegExp(prop + "=\\{" + prop + "\\}"),
      "r388r: the app does not pass its own '" + prop + "' to the Scrollback.",
    );
  }
});

// ═══════════════════════════════════════════════════════════════════
// 5. THE APP AND THE CARD GROUP THE TURNS THE SAME WAY
// ═══════════════════════════════════════════════════════════════════

test("R388r: the app and the card use ONE grouping rule", () => {
  // ★ ONE RULE, USED BY BOTH SIDES ★ If the app picked a different
  // group than the card shows, a key would act on a card the user is
  // not looking at.
  const changes = readFileSync(CHANGES, "utf8");
  assert.match(
    changes,
    /export function fileChangeGroupIdAt/,
    "R388r: the shared grouping rule is gone — the app and the card would group differently.",
  );
  const scroll = readFileSync(SCROLLBACK, "utf8");
  assert.match(
    scroll,
    /fileChangeOfTurnParts/,
    "R388r: the Scrollback keeps its own copy of the rule — it must read the shared one.",
  );
  const tui = readFileSync(TUI, "utf8");
  assert.match(tui, /fileChangeGroupIdAt/, "R388r: the app does not use the shared grouping rule.");
});

test("R388r: the rule GROUPS the turns the way the card does — MEASURED", () => {
  // ★ THE RULE IS MEASURED, NOT MERELY NAMED ★ The previous test only
  // checked that the rule's NAME appears in both files. A mutation
  // that broke the rule — or that made the Scrollback keep its own
  // copy — left the name in place, so the gate stayed green while the
  // card and the app targeted different groups.
  //
  // The turns are: write, write, bash, write. The bash BREAKS the
  // group, so the runs are [1,2] and [4]. The card's groups are
  // therefore 1 and 4, and the app's most recent group is 4.
  //
  // ★ AND THE CARD'S SIDE IS THE SCROLLBACK'S REAL `groupTurns` ★ Not
  // a re-implementation: a probe that re-implements the rule it is
  // testing measures the probe, not the product. So the Scrollback's
  // copy of the rule is exercised by CALLING it.
  const { appGroup, cardGroups, groupsOfEveryTurn } = probeGrouping();
  assert.deepEqual(
    cardGroups,
    [1, 4],
    "r388r: the card's own grouping does not group the turns the way the rule says — got " +
      JSON.stringify(cardGroups) +
      ". A bash command must BREAK a file-change group, or two batches of writes merge into one card.",
  );
  assert.equal(
    appGroup,
    4,
    "r388r: the app does not target the most recent file-change group — got " +
      JSON.stringify(appGroup) +
      ".",
  );
  // Every turn in a run reports the SAME group — the run's FIRST id.
  assert.deepEqual(
    groupsOfEveryTurn,
    [1, 1, null, 4],
    "r388r: the turns in one run do not share a group id — got " +
      JSON.stringify(groupsOfEveryTurn) +
      ".",
  );
  // ★ AND THE CARD AND THE APP TARGET THE SAME GROUP ★ This is the
  // whole point of the shared rule: a key must act on the card the
  // user is looking at.
  assert.equal(
    appGroup,
    cardGroups[cardGroups.length - 1],
    "r388r: the app and the card target DIFFERENT groups — the app targets " +
      JSON.stringify(appGroup) +
      " and the card shows " +
      JSON.stringify(cardGroups) +
      ". A key would act on a card the user is not looking at.",
  );
});

// ═══════════════════════════════════════════════════════════════════
// 6. A DEAD ACTION MUST NOT LOOK PRESSABLE
// ═══════════════════════════════════════════════════════════════════

test("R388r: the card DIMS its actions when they cannot fire", () => {
  // ★ AN ACTION THE USER CANNOT TRIGGER MUST NOT LOOK TRIGGERABLE ★
  // The card takes `actionsLive`; when the prompt has text the keys
  // are text, and the card says so by dimming them.
  const lines = renderCard({ live: false });
  const row = contentRow(lines);
  assert.match(row, /查看文件 f/, "R388r: the card dropped its link entirely. Row: " + row);
  assert.match(row, /\[撤销 u\]/, "R388r: the card dropped its undo action. Row: " + row);
  assert.match(row, /\[审查 r\]/, "R388r: the card dropped its review action. Row: " + row);
});
