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

// R417 — the TUI must be able to bound a turn, and must SHOW when one hit
// its bound.
//
// ## What this round is about
//
// R416's live matrix measured a turn whose work was DONE and which kept
// running for another nine minutes (104 model calls, every one of them
// different). The turn-COUNT budget cannot catch that, and the handover
// found the second half of the problem: the daemon published `maxTurns`
// READ-ONLY, and the TUI — like every other client — had no way to set
// any budget at all.
//
// ⇒ R417 adds the daemon's `setTurnBudget` (two axes: the wall-clock
// time and the turn count) and the TUI's `/turn-budget`.
//
// ## Why these checks matter
//
//  - the command must call a method the daemon REALLY serves, on BOTH
//    transports (the `switchVariant` shape: a method that exists on one
//    transport and not the other is a method half the clients cannot
//    reach);
//  - `0` must mean UNBOUNDED on both axes — the daemon's convention —
//    and a value in minutes must reach the wire in SECONDS;
//  - the verdict that says "this turn hit its budget" must reach the
//    user in the ac-tui renderer too, which had arms for exactly two
//    kinds.

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { codeOf } from "./strip-comments.mjs";
import { loadTsModule } from "./load-ts-module.mjs";

const root = join(import.meta.dirname, "..", "..");
const javaRoot = join(root, "..", "aethercode");
const mod = await loadTsModule(root, "src/commands.ts");
const { routeSlash, SLASH_COMMANDS_DETAILED } = mod;

const methodsSrc = codeOf(
  readFileSync(
    join(
      javaRoot,
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
    "utf-8",
  ),
  "AetherCodeMethods.java",
);
const httpSrc = codeOf(
  readFileSync(
    join(
      javaRoot,
      "aethercode-protocol",
      "src",
      "main",
      "java",
      "org",
      "aethercode",
      "protocol",
      "http",
      "HttpJsonRpcServer.java",
    ),
    "utf-8",
  ),
  "HttpJsonRpcServer.java",
);
const acTuiSrc = codeOf(readFileSync(join(root, "src", "ac-tui.ts"), "utf-8"), "ac-tui.ts");

// =========================================================================
// 1. the command exists
// =========================================================================

test("the catalog declares /turn-budget, with a group", () => {
  const entry = SLASH_COMMANDS_DETAILED.find((c) => c.name === "turn-budget");
  assert.ok(
    entry,
    "/turn-budget is not in the catalog — it would be neither routable nor in /help",
  );
  assert.ok(entry.group, "the entry must carry a group, or /help cannot place it");
  assert.ok(entry.description, "the entry must carry a description");
});

test("/turn-budget is NOT the cost budget", () => {
  // ★ TWO BUDGETS, TWO NAMES ★ `/budget` is the USD spend limit (a local
  // progress-bar setting). A user who typed `/budget 20` meaning
  // "twenty minutes" would get twenty dollars.
  assert.ok(
    SLASH_COMMANDS_DETAILED.some((c) => c.name === "budget"),
    "the cost budget command must still exist",
  );
  assert.notEqual(
    SLASH_COMMANDS_DETAILED.find((c) => c.name === "turn-budget")?.name,
    "budget",
    "/turn-budget and /budget are different budgets and must have different names",
  );
});

// =========================================================================
// 2. the routing shapes (BEHAVIOUR — the real router, not a source pin)
// =========================================================================

test("/turn-budget <minutes> sets the time budget, in SECONDS", () => {
  const r = routeSlash("/turn-budget 20");
  assert.equal(r?.rpcMethod, "setTurnBudget");
  assert.equal(r?.rpcParams?.maxTurnSeconds, 1200, "the wire speaks seconds, not minutes");
});

test("/turn-budget off means UNBOUNDED", () => {
  const r = routeSlash("/turn-budget off");
  assert.equal(r?.rpcMethod, "setTurnBudget");
  assert.equal(r?.rpcParams?.maxTurnSeconds, 0, "0 is the daemon's 'unbounded' on this axis");
});

test("/turn-budget turns <N> sets the turn count, and leaves the time axis alone", () => {
  const r = routeSlash("/turn-budget turns 7");
  assert.equal(r?.rpcMethod, "setTurnBudget");
  assert.equal(r?.rpcParams?.maxTurns, 7);
  assert.equal(
    r?.rpcParams?.maxTurnSeconds,
    undefined,
    "an absent field means 'leave this axis alone' — sending it would overwrite it",
  );
});

test("/turn-budget turns off means UNBOUNDED on the count", () => {
  const r = routeSlash("/turn-budget turns off");
  assert.equal(r?.rpcMethod, "setTurnBudget");
  assert.equal(r?.rpcParams?.maxTurns, 0);
});

test("bare /turn-budget SHOWS the budget instead of guessing it", () => {
  const r = routeSlash("/turn-budget");
  assert.equal(
    r?.local,
    "__TURN_BUDGET_SHOW__",
    "the bare command must ask the daemon what the budget is — the TUI never guesses it",
  );
  assert.equal(r?.rpcMethod, undefined, "the bare command must not set anything");
});

test("a nonsense argument is refused with a usage line, not sent to the daemon", () => {
  for (const bad of ["/turn-budget -3", "/turn-budget abc", "/turn-budget turns 0"]) {
    const r = routeSlash(bad);
    assert.ok(
      typeof r?.local === "string" && r.local.startsWith("usage:"),
      `${bad} must be refused locally with a usage line, got: ${JSON.stringify(r)}`,
    );
    assert.equal(r?.rpcMethod, undefined, `${bad} must not reach the daemon`);
  }
});

// =========================================================================
// 3. the daemon REALLY serves it, on BOTH transports
// =========================================================================

test("the daemon registers setTurnBudget on the stdio dispatcher", () => {
  assert.match(
    methodsSrc,
    /dispatcher\.register\(\s*"setTurnBudget"/,
    "the stdio transport must serve setTurnBudget",
  );
});

test("the daemon routes setTurnBudget on the HTTP switch", () => {
  assert.match(
    httpSrc,
    /case\s+"setTurnBudget"\s*->\s*methods\.setTurnBudget\(/,
    "the HTTP transport must serve setTurnBudget — a method that exists on one transport and " +
      "not the other is a method half the clients cannot reach",
  );
});

// =========================================================================
// 4. the verdict reaches the user in the ac-tui renderer
// =========================================================================

test("ac-tui has an explicit max_duration arm", () => {
  assert.match(
    acTuiSrc,
    /ev\.kind\s*===\s*"max_duration"/,
    "the ac-tui renderer must name the max_duration kind — without an arm the verdict is " +
      "computed, put on the wire, and dropped",
  );
  assert.match(
    acTuiSrc,
    /\[max_duration\]/,
    "the arm must WRITE the message — an arm that only matches the kind explains nothing",
  );
});
