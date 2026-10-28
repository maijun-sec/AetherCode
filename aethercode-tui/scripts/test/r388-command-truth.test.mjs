// Copyright (c) 2026 maijun

// Licensed under the MIT License.
// See the LICENSE file in the project root for the full license text.

// R388 — one name, one handler; one list, one description.
//
// ============================================================================
// WHAT THIS GATE MAKES EXECUTABLE
// ============================================================================
//
// Two defects, one family, both found while building 0.2.74 — the
// second one by the BUNDLER, which had been warning on every build:
//
//   1. `/budget` had TWO `case` arms in the same switch. The second one
//      was DEAD CODE (a duplicate label never matches), so:
//        - the daemon's `setPhaseBudget` was UNREACHABLE from the TUI;
//        - the CATALOGUE described the DEAD arm ("set a phase's tool-call
//          / cost cap"), while the LIVE arm sets the local cost bar;
//        - the dead arm carried a second lie: an omitted third argument
//          was documented as "the USD cap stays", but the daemon's
//          convention is 0 = UNLIMITED, and `setBudget` OVERWRITES.
//
//   2. The command palette carried its OWN hand-written table of about
//      TWENTY-FIVE descriptions (`CommandPalette.describeCommand`).
//      Every command outside that table rendered an EMPTY hint, and the
//      ones inside it could disagree with the catalogue — `/budget` said
//      "set cost budget" in the palette and "set a phase's tool-call /
//      cost cap" in the catalogue.
//
// R380 made the catalogue the only list for `/help` and the tab
// completion. The palette was the one consumer left behind: R378's
// Lesson 1197 — a second copy of a truth drifts, and it drifts silently.
//
// The checks:
//
//   1. the router has exactly ONE `case` per command name (the
//      invariant the duplicate violated — an INVERTED one, so it cannot
//      come back quietly);
//   2. EVERY command in the list has a non-empty description;
//   3. the palette's description IS the catalogue's, and the palette
//      carries no second table;
//   4. `/budget` and `/phase-budget` route to what they claim to, with
//      the params the daemon actually requires;
//   5. the daemon serves `setPhaseBudget` on BOTH transports.
//
// ============================================================================

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { codeOf } from "./strip-comments.mjs";
import { loadTsModule } from "./load-ts-module.mjs";

const root = join(import.meta.dirname, "..", "..");
const srcRoot = join(root, "src");
const java = join(root, "..", "aethercode");

const read = (rel) => readFileSync(join(srcRoot, rel), "utf-8");
const src = read("commands.ts");
const code = codeOf(src, "commands.ts");

const mod = await loadTsModule(root, "src/commands.ts");
const { SLASH_COMMANDS, SLASH_COMMANDS_DETAILED, describeCommand, routeSlash } = mod;

const catalogNames = SLASH_COMMANDS_DETAILED.map((c) => c.name);

/** The router's `case` arms — the commands the dispatcher can reach. */
function routedCommands() {
  const start = src.indexOf("export function routeSlash");
  assert.ok(start > 0, "routeSlash not found — re-anchor this gate");
  const end = src.indexOf("\nexport function handleSlash", start);
  const body = src.slice(start, end > 0 ? end : src.length);
  return [...body.matchAll(/case\s+"([^"]+)":/g)].map((m) => m[1]);
}

// =========================================================================
// 1. one name, one handler
// =========================================================================

test("the router has exactly ONE case arm per command name", () => {
  // This is the invariant the duplicate `case "budget"` violated.
  //
  // A duplicate label is not a compile error and it is not a test
  // failure: the second arm is simply never reached. The BUNDLER
  // noticed (esbuild: "This case clause will never be evaluated
  // because it duplicates an earlier case clause") — but the warning
  // was in a build log nobody reads, and the capability behind the
  // dead arm was advertised in the catalogue the whole time.
  const routed = routedCommands();
  const seen = new Map();
  const dupes = [];
  for (const name of routed) {
    if (seen.has(name)) dupes.push(name);
    else seen.set(name, true);
  }
  assert.deepEqual(
    dupes,
    [],
    `the router has a DUPLICATE case arm for: ${dupes.join(", ")} — the second arm is ` +
      "dead code, so the capability behind it is unreachable",
  );
});

test("the duplicate-label warning is not merely absent from the log — the source has none", () => {
  // The same check, stated as an inverted invariant on the source, so
  // it also covers a label that is written with a different quoting or
  // spacing than the extractor above expects.
  const labels = [...code.matchAll(/case\s+"([^"]+)"\s*:/g)].map((m) => m[1]);
  const counts = new Map();
  for (const l of labels) counts.set(l, (counts.get(l) || 0) + 1);
  const dupes = [...counts.entries()].filter(([, n]) => n > 1).map(([l]) => l);
  assert.deepEqual(dupes, [], `duplicate case labels in the command layer: ${dupes.join(", ")}`);
});

// =========================================================================
// 2. every command has a description
// =========================================================================

test("EVERY command in the list has a non-empty description", () => {
  // The palette is the DISCOVERY surface: the user opens it precisely
  // because they do not remember the command names. A row with an empty
  // hint is a row that tells them nothing.
  const empty = SLASH_COMMANDS.filter((n) => describeCommand(n).trim().length === 0);
  assert.deepEqual(
    empty,
    [],
    `these commands have no description, so the palette shows an empty hint: ${empty.join(", ")}`,
  );
});

test("a command that is not in the catalogue has no description", () => {
  // The lookup is derived, so an unknown name yields nothing rather
  // than a plausible-looking invention.
  assert.equal(describeCommand("definitely-not-a-command"), "");
});

// =========================================================================
// 3. the palette has no second table
// =========================================================================

test("the palette's description IS the catalogue's", () => {
  for (const c of SLASH_COMMANDS_DETAILED) {
    assert.equal(
      describeCommand(c.name),
      c.description,
      `the palette would show a different description for /${c.name} than /help does`,
    );
  }
});

test("the palette carries NO hand-written description table", () => {
  // INVERTED invariant (R384, Lesson 1237): the second copy cannot come
  // back by someone adding one more row.
  const palette = codeOf(read("components/CommandPalette.tsx"), "CommandPalette.tsx");
  assert.doesNotMatch(
    palette,
    /function describeCommand\s*\(/,
    "the palette must not define its own describeCommand — the descriptions live in the catalogue",
  );
  assert.match(
    palette,
    /import\s*\{[^}]*\bdescribeCommand\b[^}]*\}\s*from\s*"\.\.\/commands\.js"/,
    "the palette must take the descriptions from the catalogue",
  );
});

// =========================================================================
// 4. the two budget commands do what they say
// =========================================================================

test("/budget sets the local cost bar", () => {
  const r = routeSlash("/budget 1.50");
  assert.equal(r?.local, "__BUDGET__:1.5");
  const off = routeSlash("/budget off");
  assert.equal(off?.local, "__BUDGET__:0");
});

test("/phase-budget reaches the daemon's setPhaseBudget with ALL THREE fields", () => {
  // The daemon requires `phase`, `maxToolCalls` AND `maxCostUsd`
  // (`setPhaseBudget` answers "missing required field" without the
  // third), and its convention is 0 = unlimited.
  const r = routeSlash("/phase-budget implement 80 0.2");
  assert.equal(r?.rpcMethod, "setPhaseBudget");
  assert.deepEqual(r?.rpcParams, {
    phase: "implement",
    maxToolCalls: 80,
    maxCostUsd: 0.2,
  });
});

test("/phase-budget refuses a missing cost cap instead of silently unlimiting it", () => {
  // The old arm documented an omitted third argument as "the USD cap
  // stays". It did not: 0 means UNLIMITED and `setBudget` OVERWRITES.
  // A required argument is honest; a default that removes a cap is a
  // trap.
  const r = routeSlash("/phase-budget implement 80");
  assert.ok(r?.local, "/phase-budget with two arguments must not reach the daemon");
  assert.match(r.local, /usage:/);
  assert.equal(r.rpcMethod, undefined);
});

test("/phase-budget rejects a negative CALL cap before the daemon sees it", () => {
  const calls = routeSlash("/phase-budget implement -1 0.2");
  assert.match(calls?.local ?? "", /non-negative/);
  assert.equal(calls.rpcMethod, undefined);
});

test("/phase-budget rejects a negative COST cap before the daemon sees it", () => {
  // The two caps are checked SEPARATELY. A gate that only exercises the
  // call cap leaves the cost cap unchecked — and the R388 battery
  // caught exactly that: a mutation that removed the cost-cap guard
  // stayed green, because the test only ever passed a negative CALL
  // cap. The code changed; the behaviour did not (Lesson 1269).
  const cost = routeSlash("/phase-budget implement 80 -1");
  assert.match(cost?.local ?? "", /non-negative/);
  assert.equal(cost.rpcMethod, undefined);
});

// =========================================================================
// 5. the catalogue tells the truth, and the daemon serves it
// =========================================================================

test("the catalogue's /budget entry describes the LOCAL cost bar, not the phase cap", () => {
  const entry = SLASH_COMMANDS_DETAILED.find((c) => c.name === "budget");
  assert.ok(entry, "/budget is not in the catalogue");
  assert.doesNotMatch(
    entry.description,
    /phase/i,
    "the /budget description still describes the DEAD phase-cap arm",
  );
  assert.match(entry.description, /cost/i, "the /budget description does not mention cost at all");
});

test("the catalogue has a phase-budget entry, and it is routable", () => {
  const entry = SLASH_COMMANDS_DETAILED.find((c) => c.name === "phase-budget");
  assert.ok(entry, "phase-budget is not in the catalogue — the capability is unreachable again");
  assert.match(entry.description, /phase/i);
  assert.ok(routedCommands().includes("phase-budget"), "phase-budget is not routable");
});

test("the daemon registers setPhaseBudget on the stdio dispatcher", (t) => {
  const p = join(
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
  if (!existsSync(p)) return t.skip("the daemon sources are not in this checkout");
  assert.match(
    readFileSync(p, "utf-8"),
    /dispatcher\.register\(\s*"setPhaseBudget"/,
    "the daemon does not register setPhaseBudget on the stdio dispatcher",
  );
});

test("the daemon ROUTES setPhaseBudget on the HTTP transport", (t) => {
  // Lesson 1022: registered for stdio but missing from the HTTP switch
  // means every HTTP caller gets -32601. Two layers, or it does not
  // exist.
  const p = join(
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
  if (!existsSync(p)) return t.skip("the daemon sources are not in this checkout");
  assert.match(
    readFileSync(p, "utf-8"),
    /case\s+"setPhaseBudget"\s*->/,
    "the HTTP switch does not route setPhaseBudget",
  );
});
