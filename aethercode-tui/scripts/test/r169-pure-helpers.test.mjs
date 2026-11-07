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

// R169-b (Phase 5 R3): pure-function tests for the new component helpers.
//
// We use `node --test` with a runtime compile of just the helper files
// (no React/Ink deps needed for these pure functions). The helpers are:
//   - formatAgentLabel   (AgentSelector.tsx)
//   - formatEffortLabel  (EffortPicker.tsx)
//   - makeStatValidator  (CwdSwitcher.tsx)
//   - formatThemeLabel   (ThemePicker.tsx)
//   - filterThemes       (ThemePicker.tsx)
//
// The tests are runtime assertions, not source-code grep, so we get
// real coverage of the label rules + the validator's edge cases.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdirSync, writeFileSync } from "node:fs";
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
const shimRel = ["components", "_r169_helpers.ts"];
const shim = [
  `export { formatAgentLabel } from "./AgentSelector.js";`,
  `export { formatEffortLabel } from "./EffortPicker.js";`,
  `export { makeStatValidator } from "./CwdSwitcher.js";`,
  `export { formatThemeLabel, filterThemes } from "./ThemePicker.js";`,
  "",
].join("\n");
const compiledShim = compileShim({
  root,
  tmp: join(root, "tmp-r169b-tsc"),
  shimRel: shimRel.join("/"),
  body: shim,
  extraArgs: [],
});
const helpers = await importCompiled(compiledShim);

const { formatAgentLabel, formatEffortLabel, makeStatValidator, formatThemeLabel, filterThemes } =
  helpers;

// ----- 1. formatAgentLabel ----------------------------------------

test("R169-b: formatAgentLabel — plain (no current / no default)", () => {
  assert.equal(formatAgentLabel("code-reviewer", null, null), "code-reviewer");
});

test("R169-b: formatAgentLabel — current only", () => {
  assert.equal(formatAgentLabel("code-reviewer", "code-reviewer", null), "code-reviewer (current)");
});

test("R169-b: formatAgentLabel — default only", () => {
  assert.equal(formatAgentLabel("code-reviewer", null, "code-reviewer"), "code-reviewer (default)");
});

test("R169-b: formatAgentLabel — both current + default", () => {
  assert.equal(
    formatAgentLabel("default-agent", "default-agent", "default-agent"),
    "default-agent (current, default)",
  );
});

// ----- 2. formatEffortLabel ---------------------------------------

test("R169-b: formatEffortLabel — plain", () => {
  assert.equal(formatEffortLabel("low", null, null), "low");
});

test("R169-b: formatEffortLabel — current + default together", () => {
  assert.equal(formatEffortLabel("high", "high", "high"), "high (current, default)");
});

test("R169-b: formatEffortLabel — default only", () => {
  assert.equal(formatEffortLabel("medium", null, "medium"), "medium (default)");
});

// ----- 3. makeStatValidator ---------------------------------------

test("R169-b: makeStatValidator — empty path", () => {
  const v = makeStatValidator(() => null);
  const r = v("");
  assert.equal(r.ok, false);
  assert.equal(r.kind, "empty");
});

test("R169-b: makeStatValidator — non-absolute path", () => {
  const v = makeStatValidator(() => null);
  const r = v("relative/dir");
  assert.equal(r.ok, false);
  assert.equal(r.kind, "not-absolute");
});

test("R169-b: makeStatValidator — path does not exist", () => {
  const v = makeStatValidator(() => null);
  const r = v("/no/such/dir");
  assert.equal(r.ok, false);
  assert.equal(r.kind, "not-found");
});

test("R169-b: makeStatValidator — path is a file, not a directory", () => {
  const v = makeStatValidator(() => ({ isDirectory: () => false, isFile: () => true }));
  const r = v("/etc/hosts");
  assert.equal(r.ok, false);
  assert.equal(r.kind, "not-directory");
});

test("R169-b: makeStatValidator — happy path", () => {
  const v = makeStatValidator(() => ({ isDirectory: () => true, isFile: () => false }));
  const r = v("/work");
  assert.equal(r.ok, true);
  assert.equal(r.kind, undefined);
});

test("R169-b: makeStatValidator — Windows absolute path", () => {
  const v = makeStatValidator(() => ({ isDirectory: () => true, isFile: () => false }));
  const r = v("C:\\work");
  assert.equal(r.ok, true);
});

test("R169-b: makeStatValidator — Windows relative path rejected", () => {
  const v = makeStatValidator(() => null);
  const r = v("work\\proj");
  assert.equal(r.ok, false);
  assert.equal(r.kind, "not-absolute");
});

// ----- 4. formatThemeLabel ----------------------------------------

test("R169-b: formatThemeLabel — active theme gets (current) tag", () => {
  const s = formatThemeLabel("dark", "dark", true, "builtin");
  assert.match(s, /\(current/);
  assert.match(s, /dark/);
});

test("R169-b: formatThemeLabel — non-active gets no (current)", () => {
  const s = formatThemeLabel("light", "dark", false, "builtin");
  assert.ok(!s.includes("(current"));
});

test("R169-b: formatThemeLabel — user origin shows 'user'", () => {
  const s = formatThemeLabel("my-theme", "my-theme", false, "user");
  assert.match(s, /user/);
});

test("R169-b: formatThemeLabel — builtin origin shows 'built-in'", () => {
  const s = formatThemeLabel("light", "light", false, "builtin");
  assert.match(s, /built-in/);
});

// ----- 5. filterThemes --------------------------------------------

test("R169-b: filterThemes — empty query returns all", () => {
  const themes = [
    { name: "light", isDark: false, origin: "builtin", colors: {} },
    { name: "dark", isDark: true, origin: "builtin", colors: {} },
  ];
  assert.equal(filterThemes(themes, "").length, 2);
  assert.equal(filterThemes(themes, "  ").length, 2);
});

test("R169-b: filterThemes — substring match on name", () => {
  const themes = [
    { name: "solarized-light", isDark: false, origin: "builtin", colors: {} },
    { name: "solarized-dark", isDark: true, origin: "builtin", colors: {} },
    { name: "light", isDark: false, origin: "builtin", colors: {} },
  ];
  const out = filterThemes(themes, "solarized");
  assert.equal(out.length, 2);
  assert.ok(out.every((t) => t.name.startsWith("solarized")));
});

test("R169-b: filterThemes — case-insensitive", () => {
  const themes = [{ name: "Light", isDark: false, origin: "builtin", colors: {} }];
  assert.equal(filterThemes(themes, "light").length, 1);
  assert.equal(filterThemes(themes, "LIGHT").length, 1);
});

test("R169-b: filterThemes — origin match", () => {
  const themes = [
    { name: "light", isDark: false, origin: "builtin", colors: {} },
    { name: "mine", isDark: false, origin: "user", colors: {} },
  ];
  assert.equal(filterThemes(themes, "user").length, 1);
  assert.equal(filterThemes(themes, "user")[0].name, "mine");
});

test("R169-b: filterThemes — no match returns empty", () => {
  const themes = [{ name: "light", isDark: false, origin: "builtin", colors: {} }];
  assert.equal(filterThemes(themes, "no-match").length, 0);
});

// ----- Cleanup -----------------------------------------------
// Remove the temporary shim file so it doesn't ship in the src tree.
import { unlinkSync } from "node:fs";
try {
  unlinkSync(shimPath);
} catch {
  /* ignore */
}
