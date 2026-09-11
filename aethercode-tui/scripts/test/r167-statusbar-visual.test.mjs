// R167: tests for the StatusBar visual upgrade.
//
// The user asked: "tui 需要参考 opencode, 不输于 opencode".
// R166 added the new themes; R167 changes the *layout* of the
// StatusBar to match the OpenCode visual language — no full
// box border, just a left-edge accent + a thin horizontal
// rule above the bar. This is the change that finally makes
// the bar feel "modern" rather than "old terminal emulator".
//
// We test by source-code assertion: borderStyle="single" is
// gone from the StatusBar, replaced by a left-edge ▌ accent
// and a horizontal rule (─).

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// ----- 1. Source-code assertions: StatusBar.tsx ---------------------

test("R167: StatusBar no longer uses borderStyle=\"single\" (no box border)", () => {
  const src = readFileSync(join(root, "src", "components", "StatusBar.tsx"), "utf-8");
  // The pre-R167 implementation rendered the bar inside a
  // <Box borderStyle="single" borderColor={...}> ... </Box>.
  // R167 strips the border entirely. We assert that the
  // StatusBar component's BODY (from the function declaration
  // to its closing brace) no longer uses borderStyle. The
  // comment block above the function mentions the old
  // borderStyle="single" but that's documentation, not code.
  // We slice from after the JSDoc closer (` */`) to the
  // first `};` at column 0 (the function's closing brace).
  const jsdocEnd = src.indexOf("*/\n");
  assert.ok(jsdocEnd > 0, "could not find JSDoc closer");
  const afterJsdoc = src.slice(jsdocEnd);
  const fnStart = afterJsdoc.indexOf("=> {");
  assert.ok(fnStart > 0, "could not find arrow function start");
  const fnEnd = afterJsdoc.indexOf("\n};", fnStart);
  assert.ok(fnEnd > 0, "could not find function end");
  const body = afterJsdoc.slice(fnStart, fnEnd);
  assert.doesNotMatch(body, /borderStyle=/,
    "StatusBar body still uses borderStyle — R167 should remove it");
});

test("R167: StatusBar uses a left-edge accent character (▌)", () => {
  const src = readFileSync(join(root, "src", "components", "StatusBar.tsx"), "utf-8");
  const jsdocEnd = src.indexOf("*/\n");
  const afterJsdoc = src.slice(jsdocEnd);
  const fnStart = afterJsdoc.indexOf("=> {");
  const fnEnd = afterJsdoc.indexOf("\n};", fnStart);
  const body = afterJsdoc.slice(fnStart, fnEnd);
  // The accent character should be ▌ (U+258C, left half block).
  // Pre-R167 had a leading "●" or "■" — R167 prefixes that with
  // the left-edge bar for an OpenCode-style "ribbon" look.
  assert.match(body, /▌/);
});

test("R167: StatusBar renders a horizontal rule (─) above the bar", () => {
  const src = readFileSync(join(root, "src", "components", "StatusBar.tsx"), "utf-8");
  const jsdocEnd = src.indexOf("*/\n");
  const afterJsdoc = src.slice(jsdocEnd);
  const fnStart = afterJsdoc.indexOf("=> {");
  const fnEnd = afterJsdoc.indexOf("\n};", fnStart);
  const body = afterJsdoc.slice(fnStart, fnEnd);
  // The "─".repeat(60) line is the new horizontal rule.
  assert.match(body, /["']─["']\.repeat\(/);
});

test("R167: StatusBar uses flexDirection=\"column\" (rule + content)", () => {
  const src = readFileSync(join(root, "src", "components", "StatusBar.tsx"), "utf-8");
  const jsdocEnd = src.indexOf("*/\n");
  const afterJsdoc = src.slice(jsdocEnd);
  const fnStart = afterJsdoc.indexOf("=> {");
  const fnEnd = afterJsdoc.indexOf("\n};", fnStart);
  const body = afterJsdoc.slice(fnStart, fnEnd);
  // R167 wraps the rule + content in a column Box so the
  // rule renders on its own line above the status row.
  assert.match(body, /flexDirection="column"/);
});

test("R167: StatusBar still has all the original status fields (no regression)", () => {
  const src = readFileSync(join(root, "src", "components", "StatusBar.tsx"), "utf-8");
  const jsdocEnd = src.indexOf("*/\n");
  const afterJsdoc = src.slice(jsdocEnd);
  const fnStart = afterJsdoc.indexOf("=> {");
  const fnEnd = afterJsdoc.indexOf("\n};", fnStart);
  const body = afterJsdoc.slice(fnStart, fnEnd);
  // The status bar must still surface: model, mode, in/out
  // tokens, cost, jar name, connection state (R165), skip
  // counter (R99), permission suggestion (R109). We don't
  // assert every individual Text element — we just confirm
  // the reducer-relevant state fields are still read.
  assert.match(body, /formatTokens\(state\.inputTokens\)/);
  assert.match(body, /formatTokens\(state\.outputTokens\)/);
  assert.match(body, /formatCost\(state\.totalCostUsd\)/);
  assert.match(body, /state\.jarPath/);
  assert.match(body, /state\.connectionState/);
  assert.match(body, /state\.permissionMode/);
});

// ----- 2. Filesystem check ----------------------------------------

test("R167: StatusBar.tsx file exists", () => {
  assert.ok(existsSync(join(root, "src", "components", "StatusBar.tsx")));
});
