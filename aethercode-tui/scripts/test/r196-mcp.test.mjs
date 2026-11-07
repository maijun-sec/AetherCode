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

// R196 (Phase 5 R4): pure-function tests for the MCP components.
//
// We use `node --test` with a runtime compile of just the helper
// files (no React/Ink deps needed for these pure functions). The
// helpers are:
//   - formatLoginState         (McpLogin.tsx)
//   - reconnectChoice          (McpReconnect.tsx)
//   - forceConfirmChoice       (McpReconnect.tsx)
//   - statusGlyph / statusColor (McpViewer.tsx)
//   - dismissServer / dismissReconnect / dismissCancel (McpViewer.tsx)
//
// The tests are runtime assertions, not source-code grep, so we
// get real coverage of the rules + the edge cases.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdirSync, writeFileSync, unlinkSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { compileShim, importCompiled } from "./shim-compile.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// ----- Compile the helpers to plain ESM -----------------------------
// R388d: the shim is compiled in a TEMP MIRROR of `src/`, not
// in the checked-in tree. See `./shim-compile.mjs` — fifteen gates
// used to overwrite a TRACKED source file with a generated one-liner
// and never restored it, so every run of this suite silently
// stripped the licence header from it.
const shimRel = ["components", "_r196_helpers.ts"];
const shim = [
  `export { formatLoginState } from "./McpLogin.js";`,
  `export { reconnectChoice, forceConfirmChoice } from "./McpReconnect.js";`,
  `export { statusGlyph, statusColor, dismissServer, dismissReconnect, dismissCancel } from "./McpViewer.js";`,
  "",
].join("\n");
const compiledShim = compileShim({
  root,
  tmp: join(root, "tmp-r196-tsc"),
  shimRel: shimRel.join("/"),
  body: shim,
  extraArgs: [],
});
const helpers = await importCompiled(compiledShim);

const {
  formatLoginState,
  reconnectChoice,
  forceConfirmChoice,
  statusGlyph,
  statusColor,
  dismissServer,
  dismissReconnect,
  dismissCancel,
} = helpers;

// ----- 1. formatLoginState -----------------------------------------

test("R196: formatLoginState — starting", () => {
  assert.equal(formatLoginState("starting", "github"), "mcp login github: starting…");
});

test("R196: formatLoginState — awaiting-authorize", () => {
  assert.equal(
    formatLoginState("awaiting-authorize", "github"),
    "mcp login github: waiting for callback",
  );
});

test("R196: formatLoginState — device-code", () => {
  assert.equal(formatLoginState("device-code", "github"), "mcp login github: device code");
});

test("R196: formatLoginState — success", () => {
  assert.equal(formatLoginState("success", "github"), "mcp login github: success");
});

test("R196: formatLoginState — error", () => {
  assert.equal(formatLoginState("error", "github"), "mcp login github: failed");
});

// ----- 2. reconnectChoice ------------------------------------------

test("R196: reconnectChoice — confirmed → reconnect", () => {
  assert.equal(reconnectChoice(true), "reconnect");
});

test("R196: reconnectChoice — cancelled → later", () => {
  assert.equal(reconnectChoice(false), "later");
});

// ----- 3. forceConfirmChoice ---------------------------------------

test("R196: forceConfirmChoice — confirmed → true", () => {
  assert.equal(forceConfirmChoice(true), true);
});

test("R196: forceConfirmChoice — cancelled → false", () => {
  assert.equal(forceConfirmChoice(false), false);
});

// ----- 4. statusGlyph -----------------------------------------------

test("R196: statusGlyph — ok → ✓", () => {
  assert.equal(statusGlyph("ok"), "✓");
});

test("R196: statusGlyph — unauthenticated → ⚠", () => {
  assert.equal(statusGlyph("unauthenticated"), "⚠");
});

test("R196: statusGlyph — awaiting-reconnect → ○", () => {
  assert.equal(statusGlyph("awaiting-reconnect"), "○");
});

test("R196: statusGlyph — disabled → ⏸", () => {
  assert.equal(statusGlyph("disabled"), "⏸");
});

test("R196: statusGlyph — error → ✗", () => {
  assert.equal(statusGlyph("error"), "✗");
});

// ----- 5. statusColor -----------------------------------------------

test("R196: statusColor — ok → green", () => {
  assert.equal(statusColor("ok"), "green");
});

test("R196: statusColor — unauthenticated → yellow", () => {
  assert.equal(statusColor("unauthenticated"), "yellow");
});

test("R196: statusColor — error → red", () => {
  assert.equal(statusColor("error"), "red");
});

test("R196: statusColor — awaiting-reconnect → gray", () => {
  assert.equal(statusColor("awaiting-reconnect"), "gray");
});

test("R196: statusColor — disabled → gray", () => {
  assert.equal(statusColor("disabled"), "gray");
});

// ----- 6. dismiss helpers ------------------------------------------

test("R196: dismissServer — builds server dismissal", () => {
  const d = dismissServer("github");
  assert.equal(d.kind, "server");
  assert.equal(d.name, "github");
});

test("R196: dismissReconnect — builds reconnect dismissal", () => {
  const d = dismissReconnect();
  assert.equal(d.kind, "reconnect");
});

test("R196: dismissCancel — builds cancel dismissal", () => {
  const d = dismissCancel();
  assert.equal(d.kind, "cancel");
});

// ----- Cleanup -----------------------------------------------
try {
  unlinkSync(shimPath);
} catch {
  /* ignore */
}
