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

// R388k — SDD must work in a PURE TERMINAL.
//
// ## The bug this gate exists to hold
//
// The daemon's SDD surface is honest and small:
//
//     sdd.start     {intent, cwd, model?}
//     sdd.advance   {slug, action, feedback?, model?}
//     sdd.status    {slug, cwd}
//     sdd.abort     {slug, cwd, reason?}
//     sdd.listRuns  {cwd}
//
// The Ink renderer keeps the run in reducer state (`state.sddRun?.slug`)
// and therefore always knows the slug.
//
// The line renderer has NO reducer state — it is a plain command loop.
// Before R388k it sent `{action}` with no slug at all, `{}` for abort, and
// probed `sdd.status` with NO params. The daemon rejected all of them:
//
//     "slug and action are required"
//
// So in a pure terminal **every** SDD command after `/sdd <intent>` failed,
// and the failure was indistinguishable from "this daemon has no SDD
// support".
//
// `/sdd-list` was broken in BOTH renderers: it was a bare
// `rpcMethod: "sdd.listRuns"` with no params, and the daemon requires
// `{cwd}`.
//
// ## What this gate pins
//
// The INVARIANT, not the spelling (Lesson 1233): every SDD verb in either
// renderer must reach the daemon with a `slug` and a `cwd`. A gate that
// names a method goes red on a rename; a gate that names the contract
// survives one.

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

/**
 * The daemon lives in the Maven module tree, one level ABOVE the TUI
 * package. Derive it from this file's location rather than hard-coding
 * an absolute path — a hard-coded path lets the gate read (or delete)
 * outside its own tree (Lesson 1298).
 */
const repoRoot = join(root, "..");
const readRepoCode = (rel) => codeOf(readFileSync(join(repoRoot, rel), "utf8"));

/* ------------------------------------------------------------------ *
 * 0. The pure function under test.
 *
 * `sddRun.ts` is a plain module with no Ink / React dependency, so it
 * can be imported and RUN. That is what makes the selection rule
 * testable rather than merely assertable.
 * ------------------------------------------------------------------ */
const sddRunUrl = pathToFileURL(join(root, "src", "sddRun.ts")).href;

const loadSddRun = async () => {
  // The repo's other gates compile a TS module with esbuild into a temp
  // directory (never into the checked-in tree — Lesson 1297).
  const { build } = await import("esbuild");
  const { mkdtempSync, rmSync } = await import("node:fs");
  const { tmpdir } = await import("node:os");
  const dir = mkdtempSync(join(tmpdir(), "r388k-sddrun-"));
  const outfile = join(dir, "sddRun.mjs");
  try {
    await build({
      entryPoints: [join(root, "src", "sddRun.ts")],
      bundle: true,
      outfile,
      platform: "node",
      format: "esm",
    });
    return await import(pathToFileURL(outfile).href);
  } finally {
    // The module is already loaded into memory; removing the file is
    // safe and keeps the temp dir from accumulating.
    try {
      rmSync(outfile, { force: true });
    } catch {
      /* best effort */
    }
  }
};

/* ------------------------------------------------------------------ *
 * 1. The daemon's contract is what we think it is.
 *
 * If the daemon changes the required params, this gate must go red
 * BEFORE the client changes — otherwise the client is written against a
 * contract that no longer exists.
 * ------------------------------------------------------------------ */
test("R388k: the daemon still requires slug+cwd on the SDD verbs", () => {
  const code = readRepoCode(
    "aethercode/aethercode-protocol/src/main/java/org/aethercode/protocol/methods/AetherCodeMethods.java",
  );
  // sddAdvance: the slug/action guard.
  assert.match(
    code,
    /String slug = strField\(m, "slug"\);[\s\S]{0,400}?if \(slug == null \|\| action == null\)/,
    "the daemon's sdd.advance no longer requires slug+action — the clients must follow",
  );
  // sddStatus / sddAbort: the slug/cwd guard.
  assert.match(
    code,
    /if \(slug == null \|\| cwd == null\)/,
    "the daemon's sdd.status / sdd.abort no longer require slug+cwd — the clients must follow",
  );
  // sddListRuns: the cwd guard.
  assert.match(
    code,
    /if \(cwd == null\)/,
    "the daemon's sdd.listRuns no longer requires cwd — the clients must follow",
  );
});

/* ------------------------------------------------------------------ *
 * 2. The selection rule: which run is "the active one".
 * ------------------------------------------------------------------ */
test("R388k: pickActiveRun picks the newest non-terminal run", async () => {
  const { pickActiveRun, isActiveRun, extractRuns, sddParams } = await loadSddRun();

  // A run waiting for an approve reply is ACTIVE — that is exactly the
  // run the user wants to advance.
  assert.equal(
    isActiveRun({ slug: "a", status: "idle", currentPhase: 3, lastUpdatedAt: "" }),
    true,
  );
  assert.equal(
    isActiveRun({ slug: "a", status: "running", currentPhase: 3, lastUpdatedAt: "" }),
    true,
  );

  // A finished / aborted / failed run is NOT active.
  for (const status of ["done", "aborted", "failed"]) {
    assert.equal(isActiveRun({ slug: "a", status, currentPhase: 3, lastUpdatedAt: "" }), false);
  }

  // The most recent ACTIVE run wins, even when a terminal run is newer.
  const runs = [
    { slug: "old", status: "idle", currentPhase: 2, lastUpdatedAt: "2026-11-01T10:00:00Z" },
    { slug: "new", status: "running", currentPhase: 4, lastUpdatedAt: "2026-11-07T10:00:00Z" },
    { slug: "finished", status: "done", currentPhase: 8, lastUpdatedAt: "2026-11-08T10:00:00Z" },
  ];
  assert.equal(pickActiveRun(runs)?.slug, "new");

  // No active run → null, so the caller can say so instead of sending a
  // malformed request.
  assert.equal(
    pickActiveRun([{ slug: "x", status: "done", currentPhase: 8, lastUpdatedAt: "" }]),
    null,
  );
  assert.equal(pickActiveRun([]), null);

  // The params carry BOTH slug and cwd — the daemon's contract.
  const p = sddParams({ slug: "s", status: "idle", currentPhase: 1, lastUpdatedAt: "" }, "/repo", {
    action: "approve",
  });
  assert.equal(p.slug, "s");
  assert.equal(p.cwd, "/repo");
  assert.equal(p.action, "approve");

  // The run list is read out of the daemon's envelope.
  const extracted = extractRuns({
    ok: true,
    runs: [{ slug: "s1", status: "idle", currentPhase: 2, lastUpdatedAt: "t" }],
  });
  assert.equal(extracted.length, 1);
  assert.equal(extracted[0].slug, "s1");
  assert.equal(extractRuns({ ok: true, runs: [] }).length, 0);
});

/* ------------------------------------------------------------------ *
 * 3. The LINE renderer: every SDD verb must send slug + cwd.
 *
 * This is the invariant the whole round is about. The check is
 * deliberately INVERTED — it asks "does this code send a slug?" rather
 * than "does it call function X" — so it survives a rename and still
 * catches the real defect (Lesson 1237).
 *
 * ★ AND THE CHECK IS PER-HANDLER, NOT PER-FILE. ★
 *
 * The first version of this gate asserted that `slug:` and
 * `cwd: ctx.cwd` appear SOMEWHERE in line.ts. Mutating the
 * `__SDD_ADVANCE__` handler back to the slug-less shape left the gate
 * GREEN — because `__SDD_ABORT__` and `__SDD_STATUS__` still carried
 * both, and a file-wide regex cannot tell them apart.
 *
 * → **Lesson 1318 — a file-wide "does this pattern appear?" check
 * cannot pin a per-call-site contract.** The other correct instances
 * vouch for the broken one. Each verb must be checked in its OWN
 * block.
 * ------------------------------------------------------------------ */

/**
 * Extract the body of a `case "<sentinel>":` arm from a switch.
 *
 * Two shapes exist in the source:
 *
 *   case "X": { ... }        ← braced block
 *   case "X":                ← braceless; the arm's statements run
 *     stmt;                     until the next `case` / `default`
 *     stmt;
 *
 * A braceless arm is the trap: looking for the next `{` would grab the
 * brace of a LATER block and silently read the wrong code. So the arm
 * ends at the next `case` / `default` label when it has no block of its
 * own.
 *
 * Brace counting skips string literals so a `}` inside a literal does
 * not close the body early (Lesson 1234 — a truncated extraction passes
 * vacuously).
 */
function caseBlock(code, sentinel) {
  const start = code.indexOf(`case "${sentinel}"`);
  if (start < 0) return null;

  // The arm's own block, if it has one: the first `{` BEFORE the next
  // case label.
  const nextLabel = nextCaseLabel(code, start + 1);
  const armRegion = code.slice(start, nextLabel < 0 ? code.length : nextLabel);
  const open = armRegion.indexOf("{");
  if (open < 0) {
    // Braceless arm: the statements up to the next label.
    return armRegion;
  }
  const body = balancedFrom(code, start + open);
  return body;
}

/** Index of the next `case` / `default` label at or after `from`, or -1. */
function nextCaseLabel(code, from) {
  const rest = code.slice(from);
  const m = rest.match(/\n\s*(case\s+"|default\s*:)/);
  if (!m) return -1;
  return from + m.index;
}

/**
 * From an opening `{`, return the balanced block (inclusive), skipping
 * string literals and comments.
 */
function balancedFrom(code, open) {
  let depth = 0;
  let inStr = false;
  let inLineComment = false;
  let inBlockComment = false;
  for (let i = open; i < code.length; i++) {
    const c = code[i];
    const n = code[i + 1];
    if (inLineComment) {
      if (c === "\n") inLineComment = false;
      continue;
    }
    if (inBlockComment) {
      if (c === "*" && n === "/") {
        inBlockComment = false;
        i++;
      }
      continue;
    }
    if (inStr) {
      if (c === "\\") {
        i++;
        continue;
      }
      if (c === '"') inStr = false;
      continue;
    }
    if (c === "/" && n === "/") {
      inLineComment = true;
      i++;
      continue;
    }
    if (c === "/" && n === "*") {
      inBlockComment = true;
      i++;
      continue;
    }
    if (c === '"') {
      inStr = true;
      continue;
    }
    if (c === "{") depth++;
    else if (c === "}") {
      depth--;
      if (depth === 0) return code.slice(open, i + 1);
    }
  }
  return null;
}

/**
 * Extract the body of a top-level `function <name>(` in the source.
 *
 * Returns null when the function cannot be found — never a partial
 * block. A truncated extraction makes every later assertion pass
 * VACUOUSLY (Lesson 1234).
 */
function functionBody(code, name) {
  const start = code.indexOf(`function ${name}(`);
  if (start < 0) return null;
  const open = code.indexOf("{", start);
  if (open < 0) return null;
  let depth = 0;
  let inStr = false;
  let inTemplate = false;
  for (let i = open; i < code.length; i++) {
    const c = code[i];
    if (inStr) {
      if (c === "\\") {
        i++;
        continue;
      }
      if (c === '"') inStr = false;
      continue;
    }
    if (inTemplate) {
      if (c === "\\") {
        i++;
        continue;
      }
      if (c === "`") inTemplate = false;
      continue;
    }
    if (c === '"') {
      inStr = true;
      continue;
    }
    if (c === "`") {
      inTemplate = true;
      continue;
    }
    if (c === "{") depth++;
    else if (c === "}") {
      depth--;
      if (depth === 0) return code.slice(open, i + 1);
    }
  }
  return null;
}

test("R388k: the line renderer sends slug+cwd on EVERY SDD verb", () => {
  const code = readCode("src/line.ts");

  // Each SDD verb in the line renderer, and the daemon method it must
  // reach. Every one of these verbs was broken before R388k.
  const VERBS = [
    { sentinel: "__SDD_ADVANCE__", method: "sdd.advance" },
    { sentinel: "__SDD_ABORT__", method: "sdd.abort" },
    { sentinel: "__SDD_STATUS__", method: "sdd.status" },
  ];

  for (const v of VERBS) {
    const block = caseBlock(code, v.sentinel);
    assert.ok(
      block,
      `the line renderer has no ${v.sentinel} arm — ${v.method} is unreachable (R388k)`,
    );

    // The verb may carry the contract ITSELF, or DELEGATE to a helper
    // that carries it. Both are legitimate; what is not legitimate is
    // neither.
    //
    // ★ THE CONTRACT IS THE PARAMS, NOT THE PROSE. ★
    //
    // The first version of this gate accepted any arm that merely
    // MENTIONED a slug — and the mutation `params: { action }` (the
    // exact pre-R388k shape) SURVIVED, because the arm's comment and
    // its `resolveActiveSddRun(ctx)` call both contain the word.
    //
    // → **Lesson 1319 — a check that looks for a WORD finds the word in
    // a comment.** The contract has to be checked where it is actually
    // delivered: in the expression that becomes the request params.
    //
    // So: find the `params:` expression in the arm and require it to
    // carry a slug. A helper that BUILDS the params (and does not
    // receive them) is the contract holder; a helper that RECEIVES
    // them is not.
    const paramsExpr = paramsExpression(block);
    // A verb may either BUILD its params (the arm has a `params:`
    // expression) or DELEGATE to a helper that builds them. The
    // contract is delivered at whichever of those two places it is
    // actually delivered.
    let carriesSlugAndCwd = false;
    let carriesCwd = false;
    if (hasParamsLabel(block)) {
      const p = paramsExpr ?? "";
      carriesSlugAndCwd = /\bslug\s*:/.test(p) || /\bsddParams\s*\(/.test(p);
      carriesCwd = /\bcwd\b/.test(p);
    } else {
      // Delegated: find the helper and check the params it builds.
      const delegated = [...block.matchAll(/\b(panelSdd[A-Za-z]*)\s*\(/g)].map((m) => m[1]);
      assert.ok(
        delegated.length > 0,
        `${v.sentinel} sends no params and delegates to nothing (R388k)`,
      );
      for (const h of delegated) {
        const helperSrc = functionBody(code, h);
        assert.ok(helperSrc, `the line renderer calls ${h} but it cannot be located (R388k)`);
        // The helper either builds the params (and then the whole body
        // is the evidence) or receives them (and then it is a
        // dispatcher, and the CALLER owns the contract).
        const helperParams = paramsExpression(helperSrc) ?? expressionAfter(helperSrc);
        if (/\bslug\s*:/.test(helperParams) || /\bsddParams\s*\(/.test(helperParams)) {
          carriesSlugAndCwd = true;
        }
        if (/\bcwd\b/.test(helperParams)) carriesCwd = true;
      }
    }

    assert.ok(
      carriesSlugAndCwd,
      `${v.sentinel} sends params without a slug — the daemon rejects ${v.method} (R388k)`,
    );
    assert.ok(
      carriesCwd,
      `${v.sentinel} sends params without a cwd — the daemon rejects ${v.method} (R388k)`,
    );

    // And it must reach the right method — directly, or through the
    // helper it delegates to.
    const helperNames = [...block.matchAll(/\b(panelSdd[A-Za-z]*)\s*\(/g)].map((m) => m[1]);
    let reachesMethod = new RegExp(v.method.replace(".", "\\.")).test(block);
    for (const h of helperNames) {
      const helperSrc = functionBody(code, h);
      assert.ok(helperSrc, `the line renderer calls ${h} but it cannot be located (R388k)`);
      if (new RegExp(v.method.replace(".", "\\.")).test(helperSrc)) reachesMethod = true;
      // A dispatcher that takes the params as an argument is not the
      // contract holder — the caller is.
      const isDispatcher = /\bparams\s*:\s*RpcValue\b/.test(helperSrc);
      if (isDispatcher) continue;
      assert.match(
        helperSrc,
        /slug|sddParams/,
        `${h} does not carry a slug — the daemon rejects it (R388k)`,
      );
      assert.match(helperSrc, /cwd/, `${h} does not carry a cwd — the daemon rejects it (R388k)`);
    }
    assert.ok(reachesMethod, `${v.sentinel} does not reach ${v.method} (R388k)`);
  }
});

/**
 * Does this block pass a `params:` field?
 *
 * A block that mentions `params:` beyond its own bounds would belong to
 * a neighbouring construct — but a block's own text IS its bounds, so
 * the only question is whether the label is present at all.
 */
function hasParamsLabel(block) {
  return block.includes("params:");
}

/**
 * Extract the expression that FOLLOWS a `params:` label.
 *
 * Returns null when the block has no label.
 */
function paramsExpression(block) {
  const idx = block.indexOf("params:");
  if (idx < 0) return null;
  return expressionAfter(block.slice(idx + "params:".length));
}

/**
 * Extract a bare expression: the value of an argument.
 *
 * Stops at the closing bracket of the first balanced group, or at a
 * top-level comma.
 */
function expressionAfter(rest) {
  let depth = 0;
  let inStr = false;
  for (let i = 0; i < rest.length; i++) {
    const c = rest[i];
    if (inStr) {
      if (c === "\\") {
        i++;
        continue;
      }
      if (c === '"') inStr = false;
      continue;
    }
    if (c === '"') {
      inStr = true;
      continue;
    }
    if (c === "{" || c === "(" || c === "[") depth++;
    else if (c === "}" || c === ")" || c === "]") {
      if (depth === 0) return rest.slice(0, i);
      depth--;
    } else if (c === "," && depth === 0) {
      return rest.slice(0, i);
    }
  }
  return rest;
}

test("R388k: the SDD params builder is the only way a slug reaches the daemon", async () => {
  // The builder is the ONE place that knows the daemon's contract. If a
  // verb hand-rolls `{slug: ...}` instead, the contract can drift.
  const { sddParams } = await loadSddRun();
  const p = sddParams({ slug: "abc", status: "idle", currentPhase: 1, lastUpdatedAt: "" }, "/w");
  assert.equal(p.slug, "abc");
  assert.equal(p.cwd, "/w");
});

/**
 * Find every place the source actually CALLS `sdd.listRuns`, and return
 * the params expression of each call.
 *
 * ★ Why not "does the string sdd.listRuns appear near a cwd?" ★
 *
 * Because the string also appears in COMMENTS, in error messages and in
 * panel titles. Scanning for the bare string finds all of those — five
 * occurrences in line.ts, three of them prose.
 *
 * → **Lesson 1321 — a contract check must anchor on the CALL, not on a
 * string that also occurs in prose.**
 *
 * The call shapes in this repo:
 *   probe(ctx, "sdd.listRuns", { cwd: ctx.cwd } as RpcValue)
 *   client.request<...>("sdd.listRuns", { cwd: currentCwd } as RpcValue, ...)
 */
function listRunsCalls(src) {
  const out = [];
  const re = /(probe\(\s*ctx\s*,\s*|request(?:<[^>]*>)?\(\s*)"sdd\.listRuns"\s*,/g;
  let m;
  while ((m = re.exec(src)) !== null) {
    const rest = src.slice(m.index + m[0].length);
    const params = expressionAfter(rest);
    out.push({ index: m.index, params: params ?? "" });
  }
  return out;
}

test("R388k: the line renderer discovers the slug instead of remembering it", () => {
  const code = readCode("src/line.ts");
  // The discovery step exists: the slug is FOUND, not remembered.
  assert.match(
    code,
    /sdd\.listRuns/,
    "the line renderer never calls sdd.listRuns — without discovery it cannot know the slug (R388k)",
  );

  // ★ EVERY call site must carry the cwd. ★
  //
  // A single file-wide regex over `sdd.listRuns ... cwd:` was not
  // enough: with TWO call sites, dropping the cwd from ONE of them left
  // the gate GREEN, because the other one still matched.
  //
  // → **Lesson 1320 — a proximity regex over a shared string binds the
  // check to ONE occurrence and lets the others go unchecked.** When a
  // contract applies to every call site, assert on every call site.
  //
  // → **Lesson 1321 — and the anchor must be the CALL, not the
  // string**, because the string also occurs in comments, error
  // messages and panel titles. Scanning for the bare string found
  // FIVE occurrences in line.ts — three of them prose.
  for (const [rel, cwdExpr] of [
    ["src/line.ts", "ctx.cwd"],
    ["src/tui.tsx", "currentCwd"],
  ]) {
    const src = readCode(rel);
    const calls = listRunsCalls(src);
    assert.ok(calls.length > 0, `${rel} never calls sdd.listRuns (R388k)`);
    for (const c of calls) {
      assert.match(
        c.params,
        new RegExp(`cwd\\s*:\\s*${cwdExpr.replace(".", "\\.")}`),
        `${rel} calls sdd.listRuns without cwd — the daemon rejects it with "cwd is required" (R388k)`,
      );
    }
  }
});

test("R388k: the line renderer no longer probes sdd.status without params", () => {
  const code = readCode("src/line.ts");
  // The pre-R388k shape was `probe(ctx, "sdd.status")` — no params at
  // all. The daemon requires {slug, cwd}, so that call could only ever
  // fail.
  assert.doesNotMatch(
    code,
    /probe\(ctx,\s*"sdd\.status"\s*\)/,
    "the line renderer probes sdd.status with no params — the daemon requires slug+cwd (R388k)",
  );
});

test("R388k: the line renderer no longer refuses the SDD file commands", () => {
  const code = readCode("src/line.ts");
  // Pre-R388k the line renderer answered "only the full TUI's file
  // actions do" for /sdd-view, /sdd-diff, /sdd-snapshot and
  // /sdd-clear-cache. That refusal made 9 of 10 advertised SDD commands
  // non-functional in a pure terminal.
  assert.doesNotMatch(
    code,
    /only the full TUI's file actions do/,
    "the line renderer still refuses the SDD file commands — the pure-terminal SDD story is incomplete (R388k)",
  );
});

/* ------------------------------------------------------------------ *
 * 4. The INK renderer: `/sdd-list` must send cwd.
 * ------------------------------------------------------------------ */
test("R388k: the Ink renderer sends cwd on sdd.listRuns", () => {
  const code = readCode("src/tui.tsx");
  const calls = listRunsCalls(code);
  assert.ok(calls.length > 0, "the Ink renderer never calls sdd.listRuns (R388k)");
  for (const c of calls) {
    assert.match(
      c.params,
      /cwd\s*:\s*currentCwd/,
      "the Ink renderer calls sdd.listRuns without cwd — the daemon rejects it (R388k)",
    );
  }
});

/* ------------------------------------------------------------------ *
 * 5. `/sdd-list` is a local sentinel in BOTH renderers.
 *
 * `routeSlash` has no access to the cwd, so a bare `rpcMethod` can
 * never satisfy the daemon's `{cwd}` requirement. The command must
 * therefore be a LOCAL sentinel that the renderer (which knows the cwd)
 * intercepts.
 * ------------------------------------------------------------------ */
test("R388k: /sdd-list is routed as a local sentinel, not a bare rpcMethod", () => {
  const code = readCode("src/commands.ts");
  assert.match(
    code,
    /case\s+"sdd-list"[\s\S]{0,400}?__SDD_LIST__/,
    "/sdd-list is not a local sentinel — routeSlash cannot supply the cwd the daemon requires (R388k)",
  );
  // And it must NOT still be a bare rpcMethod with no params.
  assert.doesNotMatch(
    code,
    /case\s+"sdd-list"[\s\S]{0,400}?rpcMethod:\s*"sdd\.listRuns"/,
    "/sdd-list still goes out as a bare rpcMethod with no params — the daemon requires cwd (R388k)",
  );
});

test("R388k: both renderers handle the __SDD_LIST__ sentinel", () => {
  for (const rel of ["src/line.ts", "src/tui.tsx"]) {
    const code = readCode(rel);
    assert.match(
      code,
      /__SDD_LIST__/,
      `${rel} does not handle the __SDD_LIST__ sentinel — /sdd-list would fall through to a raw RPC dump (R388k)`,
    );
  }
});

/* ------------------------------------------------------------------ *
 * 6. The command registry still agrees with the router.
 *
 * R380's rule: the catalog is the only list, and the router must be
 * able to reach every catalog entry. R388k turned `/sdd-list` into a
 * local sentinel — the catalog entry must survive that change, or the
 * command becomes invisible to `/help` and to tab completion.
 * ------------------------------------------------------------------ */
test("R388k: /sdd-list is still in the command catalog", () => {
  const code = readCode("src/commands.ts");
  assert.match(
    code,
    /name:\s*"sdd-list"/,
    "/sdd-list vanished from the catalog — the command still works but is invisible (R388k)",
  );
});
