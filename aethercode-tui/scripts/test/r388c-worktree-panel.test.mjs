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

// R388c — `/worktree`: the git worktree lifecycle.
//
// ## What this round is really about
//
// A worktree is an isolated git checkout on its own branch. The
// daemon has always had `addWorktree` / `listWorktrees` /
// `removeWorktree` — but a CAPABILITY with no NAME the user can
// type is unreachable (the R371 lesson, one layer up: the daemon
// answered `compact/status` for months and the TUI never asked).
//
// ## The rule this gate exists to hold
//
// ★ THE VERDICT COMES FROM THE DAEMON ★
//
// A worktree row carries an `isolated` flag. When it is false, the
// worktree is a PLAIN DIRECTORY that only looks like isolation —
// the daemon could not create a real `git worktree`, and the row
// says why.
//
// The renderer must NOT derive isolation from the ABSENCE of a
// branch. A detached-HEAD worktree has no branch and IS isolated.
// A stub worktree has no branch and is NOT. Those two are
// indistinguishable from the branch alone, so a renderer that
// infers one way is wrong half the time — and the wrong half is
// the dangerous one (claiming isolation the user does not have).
//
// That is R383's lesson applied to a new surface: the renderer
// displays the daemon's verdict; it does not re-derive it.
//
// ## What this gate pins
//
//   * `/worktree` exists, is advertised, and emits the sentinel;
//   * BOTH renderers claim the sentinel;
//   * the Ink panel reads `isolated` and never infers it;
//   * the line frame reads `isolated` and never infers it;
//   * a degraded daemon says so instead of rendering an empty list;
//   * the panel's key hint comes from the registry, not from prose.

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { codeOf } from "./strip-comments.mjs";
import { javaCode, javaMethod } from "./java-strip.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const repo = join(root, "..");

const commandsSrc = codeOf(readFileSync(join(root, "src", "commands.ts"), "utf-8"), "commands.ts");
const tuiSrc = codeOf(readFileSync(join(root, "src", "tui.tsx"), "utf-8"), "tui.tsx");
const lineSrc = codeOf(readFileSync(join(root, "src", "line.ts"), "utf-8"), "line.ts");
const stateSrc = codeOf(readFileSync(join(root, "src", "state.ts"), "utf-8"), "state.ts");
const intentsSrc = codeOf(
  readFileSync(join(root, "src", "panelIntents.ts"), "utf-8"),
  "panelIntents.ts",
);
const panelSrc = codeOf(
  readFileSync(join(root, "src", "components", "WorktreePanel.tsx"), "utf-8"),
  "WorktreePanel.tsx",
);
const keysSrc = codeOf(
  readFileSync(join(root, "src", "componentKeys.ts"), "utf-8"),
  "componentKeys.ts",
);

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

// =========================================================================
//  the command
// =========================================================================

test("/worktree exists and answers with the worktree sentinel", () => {
  // Before R388c the daemon had the RPCs and the user had no way to
  // reach them: `/worktree` fell through to "unknown slash command".
  assert.match(
    commandsSrc,
    /case\s+"worktree"\s*:\s*return\s*\{\s*local:\s*"__WORKTREE_PANEL__"\s*\}/,
    "/worktree does not answer with the worktree sentinel",
  );
});

test("the sentinel is in the flat command list, so it is reachable", () => {
  // The list is the catalog's PROJECTION. A command in the catalog
  // but missing from the flat array is neither tab-completable nor in
  // the palette (the R380 lesson).
  assert.match(commandsSrc, /name:\s*"worktree"/, "the command catalog has no /worktree row");
});

// =========================================================================
//  both renderers claim it
// =========================================================================

test("the Ink side maps the intent to a mounted panel", () => {
  // The Ink side resolves through PANEL_BY_INTENT, so BOTH facts are
  // needed: the mapping AND the render case. Without the mapping the
  // sentinel resolves to nothing and the panel never opens.
  const m = /const PANEL_BY_INTENT[^=]*=\s*\{([\s\S]*?)\n\};/.exec(tuiSrc);
  assert.ok(m, "PANEL_BY_INTENT literal not found");
  assert.match(
    m[1],
    /"worktree-panel"\s*:\s*"worktrees"/,
    'PANEL_BY_INTENT has no "worktree-panel" entry, so the sentinel resolves to nothing',
  );
  assert.match(tuiSrc, /case "worktrees":/, 'there is no `case "worktrees":` render branch');
  assert.match(
    tuiSrc,
    /from\s+["'][^"']*WorktreePanel\.js["']/,
    "tui.tsx never imports the WorktreePanel component",
  );
});

test("the line side has a real arm", () => {
  // Without this arm the sentinel is claimed by nothing in line mode
  // and cmd.exe users would see the raw `__WORKTREE_PANEL__` token.
  assert.match(
    lineSrc,
    /case\s+"__WORKTREE_PANEL__"\s*:\s*[\s\S]{0,400}?panelWorktrees\(ctx\)/,
    "line.ts has no __WORKTREE_PANEL__ arm that renders the panel",
  );
});

// =========================================================================
//  ★ THE VERDICT COMES FROM THE DAEMON ★
// =========================================================================

test("the Ink panel READS the daemon's `isolated` flag", () => {
  assert.match(panelSrc, /w\.isolated/, "the panel never reads the daemon's `isolated` flag");
});

test("neither renderer INFERS isolation from the branch", () => {
  // This is the whole point. `branch == null` is true for BOTH a
  // detached-HEAD worktree (which IS isolated) and a stub (which is
  // NOT), so inferring from it is wrong half the time — and the
  // wrong half claims isolation the user does not have.
  for (const [name, src] of [
    ["the Ink panel", panelSrc],
    ["the line frame", lineSrc],
  ]) {
    assert.ok(
      !/isolated\s*[:=]\s*[^;\n]*branch/.test(src),
      `${name} derives the isolation verdict from the branch — the daemon owns that verdict`,
    );
    assert.ok(
      !/branch\s*===\s*null\s*\?\s*(true|false)\s*:\s*/.test(src),
      `${name} derives isolation from a missing branch`,
    );
  }
});

test("the line frame renders the daemon's verdict as a distinct column", () => {
  // "git" and "plain dir" are DIFFERENT facts and must not look the
  // same. A blank column reads like "no information", which is the
  // R383 failure in a new place.
  assert.match(
    lineSrc,
    /isolated\s*\?\s*"git"\s*:\s*"plain dir"/,
    "the line frame does not render the daemon's verdict as its own column",
  );
});

test("a degraded daemon says so instead of rendering an empty list", () => {
  // An empty list reads like "you have no worktrees". The truth is
  // "this daemon cannot make worktrees" — a completely different
  // fact, and one the user can act on.
  assert.match(
    lineSrc,
    /isolation unavailable/,
    "the line frame never says that isolation is unavailable",
  );
  assert.match(
    lineSrc,
    /--worktree-source-repo/,
    "the line frame does not tell the user how to enable isolation",
  );
  assert.match(
    panelSrc,
    /--worktree-source-repo/,
    "the Ink panel does not tell the user how to enable isolation",
  );
});

// =========================================================================
//  the daemon's wire contract
// =========================================================================

test("the daemon publishes `isolated` and `reason` on every worktree row", () => {
  // "The key is present" is not "the value is used" (R383's
  // lesson). The field must be published UNCONDITIONALLY — a client
  // that has to infer its absence is a client that will guess.
  //
  // The snapshot is built by `WorktreeState.toWireSnapshot()`, so the
  // fields live in the SDK, not in `AetherCodeMethods`. Asking
  // `AetherCodeMethods` for them would be a check of the wrong file
  // (the R384 lesson about pinning the spelling instead of the
  // property).
  const sdkPath = join(
    repo,
    "aethercode",
    "aethercode-sdk",
    "src",
    "main",
    "java",
    "org",
    "aethercode",
    "sdk",
    "WorktreeManager.java",
  );
  const sdkCode = javaCode(readFileSync(sdkPath, "utf-8"));
  // Occurrence 2, not 1: `MergeResult` also has a
  // `toWireSnapshot()`, and it is declared FIRST. Asking for
  // occurrence 1 reads the MERGE result's snapshot — which has
  // neither `isolated` nor `reason` — and reports a defect that is
  // not there. (The R371 gate's own note on `runHttp`: an extraction
  // that succeeds while reading the WRONG THING is worse than one
  // that fails loudly.)
  const snap = javaMethod(sdkCode, "public Map<String, Object> toWireSnapshot(", 2);
  assert.match(snap, /isolated/, "toWireSnapshot never publishes `isolated`");
  assert.match(snap, /reason/, "toWireSnapshot never publishes `reason`");
  // And `listWorktrees` really routes through that snapshot.
  const body = javaMethod(methodsCode, "public Object listWorktrees(");
  assert.match(
    body,
    /toWireSnapshot\(\)/,
    "listWorktrees does not build its rows through toWireSnapshot — the verdict would be missing",
  );
});

test("the daemon publishes an isolation verdict on addWorktree too", () => {
  // The create path is where the user is most likely to be misled:
  // they asked for isolation and the daemon quietly made a plain
  // directory. The answer must carry the verdict.
  const body = javaMethod(methodsCode, "public Object addWorktree(");
  assert.match(body, /isolated/, "addWorktree never publishes `isolated`");
  assert.match(body, /reason/, "addWorktree never publishes `reason`");
});

test("the daemon has a worktreeStatus RPC that answers whether isolation is possible", () => {
  // The client asks the daemon whether it CAN isolate. It does not
  // work it out from a missing field.
  assert.match(methodsCode, /worktreeStatus/, "the daemon has no worktreeStatus RPC");
  assert.match(
    httpCode,
    /case\s+"worktreeStatus"/,
    "the HTTP transport does not route worktreeStatus",
  );
});

test("both transports route the worktree RPCs", () => {
  // Registering on stdio and forgetting the hand-rolled HTTP switch
  // is how memory/* shipped broken in R720 (the R367 lesson).
  for (const name of [
    "addWorktree",
    "listWorktrees",
    "removeWorktree",
    "mergeWorktree",
    "worktreeStatus",
  ]) {
    assert.match(methodsCode, new RegExp(`"${name}"`), `the daemon never registers ${name}`);
    assert.match(
      httpCode,
      new RegExp(`case\\s+"${name}"`),
      `the HTTP transport does not route ${name} — stdio works and HTTP answers -32601`,
    );
  }
});

// =========================================================================
//  the registry
// =========================================================================

test("the registry row is mounted on BOTH renderers with no blocker", () => {
  const at = intentsSrc.search(/id:\s*["']worktree-panel["']/);
  assert.notEqual(at, -1, "the registry has no worktree-panel row");
  const open = intentsSrc.lastIndexOf("{", at);
  let depth = 0;
  let end = -1;
  for (let i = open; i < intentsSrc.length; i++) {
    if (intentsSrc[i] === "{") depth++;
    else if (intentsSrc[i] === "}" && --depth === 0) {
      end = i;
      break;
    }
  }
  assert.notEqual(end, -1, "the worktree-panel row has no closing brace");
  const row = intentsSrc.slice(open, end + 1);
  assert.match(row, /ink:\s*["']mounted["']/, "the row does not claim Ink is mounted");
  assert.match(row, /line:\s*["']rendered["']/, "the row does not claim line mode is rendered");
  assert.match(row, /command:\s*["']\/worktree["']/, "the row's command is not /worktree");
  assert.match(
    row,
    /sentinels:\s*\[[^\]]*"__WORKTREE_PANEL__"/,
    "the row does not declare the sentinel",
  );
});

test("the panel's key hint comes from the registry", () => {
  // R381: a hand-written key hint is a SECOND COPY of the truth, and
  // it drifts. The panel's hint is rendered from
  // `src/componentKeys.ts`.
  assert.match(
    panelSrc,
    /hintFor\(\s*"components\/WorktreePanel\.tsx"\s*\)/,
    "the panel does not render the registry's key hint",
  );
  assert.match(
    keysSrc,
    /component:\s*["']components\/WorktreePanel\.tsx["']/,
    "the panel has no key table in the registry",
  );
});

test("the panel's action keys are really bound by its handler", () => {
  // The table promises n / m / d. If the handler does not bind them,
  // the hint is a promise the panel does not keep.
  //
  // The RAW source is read here, not `codeOf`'d: `codeOf` blanks the
  // STRING LITERALS (so the comments survive a scan), and the key
  // comparisons ARE string literals — reading the stripped text would
  // look for `input === ` with nothing on the right and never match.
  const raw = readFileSync(join(root, "src", "components", "WorktreePanel.tsx"), "utf-8");
  for (const key of ["n", "m", "d"]) {
    assert.match(
      raw,
      new RegExp(`input\\s*===\\s*"${key}"`),
      `the panel's handler does not bind "${key}", but its hint promises it`,
    );
  }
});

// =========================================================================
//  the state
// =========================================================================

test("the reducer has writers for the worktree state", () => {
  // A DECLARATION and a default value look identical to a source
  // pin — only a reducer case that ASSIGNS the fields proves they
  // can move (the R371 lesson).
  assert.match(stateSrc, /worktrees\s*:/, "the state has no worktrees field");
  assert.match(stateSrc, /worktreeStatus\s*:/, "the state has no worktreeStatus field");
  assert.match(stateSrc, /selectedWorktree\s*:/, "the state has no selectedWorktree field");
  assert.match(stateSrc, /case\s+"setWorktrees"/, "no reducer case writes the worktree list");
  assert.match(stateSrc, /case\s+"selectWorktree"/, "no reducer case writes the selected worktree");
});
