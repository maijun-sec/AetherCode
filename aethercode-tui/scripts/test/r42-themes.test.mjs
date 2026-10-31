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

// R42: theme palettes.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// ----- 1. Compile state.ts and import --------------------------------
const tmp = join(root, "tmp-r42-tsc");
const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
{
  if (existsSync(tmp)) {
    spawnSync(
      process.platform === "win32" ? "cmd" : "rm",
      process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", tmp] : ["-rf", tmp],
    );
  }
  const tscArgs = [
    "--outDir",
    tmp,
    "--target",
    "ES2022",
    "--module",
    "ES2022",
    "--moduleResolution",
    "bundler",
    "--esModuleInterop",
    "true",
    "--skipLibCheck",
    "true",
    "--rootDir",
    join(root, "src"),
  ];
  const r = spawnSync(
    `"${tscBin}"`,
    [...tscArgs, `"${join(root, "src", "state.ts")}"`, `"${join(root, "src", "commands.ts")}"`],
    {
      encoding: "utf-8",
      shell: true,
    },
  );
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
    process.exit(1);
  }
}
const stateMod = await import("file:///" + join(tmp, "state.js").replace(/\\/g, "/"));
// R386: state.ts imports the theme registry, so tsc compiled it into
// the same output directory. The registry is the ONE list of theme
// names, and the assertions below are checked against IT rather than
// against a copy of it.
const themesMod = await import("file:///" + join(tmp, "themes.js").replace(/\\/g, "/"));
const commandsMod = await import("file:///" + join(tmp, "commands.js").replace(/\\/g, "/"));
const { reducer, INITIAL } = stateMod;
const { THEME_NAMES, DEFAULT_THEME_NAME } = themesMod;
const { routeSlash } = commandsMod;

test("R42: INITIAL.themeName is the registry's default", () => {
  assert.equal(INITIAL.themeName, "default");
  assert.equal(INITIAL.themeName, DEFAULT_THEME_NAME);
});

test("R42: setTheme switches themeName", () => {
  let s = reducer(INITIAL, { type: "setTheme", theme: "solarized" });
  assert.equal(s.themeName, "solarized");
  s = reducer(s, { type: "setTheme", theme: "monokai" });
  assert.equal(s.themeName, "monokai");
});

test("R42: setTheme accepts EVERY theme the registry ships", () => {
  // R386: this is the assertion the old union pin stood in for, and
  // it is stronger. The union `"default" | "solarized" | "monokai"`
  // said three; the product ships five — and two of them could not
  // be selected. Pinning the reducer against the REGISTRY means a
  // sixth palette is reachable the day it is added, with no edit to
  // this file.
  assert.ok(THEME_NAMES.length >= 5, "the registry lost themes — re-anchor this test");
  for (const name of THEME_NAMES) {
    const s = reducer(INITIAL, { type: "setTheme", theme: name });
    assert.equal(s.themeName, name, `setTheme cannot select "${name}"`);
  }
});

// ----- 2. Source code assertions -------------------------------------

test("R42: themes.ts exists with 3 palettes", () => {
  assert.ok(existsSync(join(root, "src", "themes.ts")));
  const src = readFileSync(join(root, "src", "themes.ts"), "utf-8");
  assert.match(src, /export type ThemeName/);
  assert.match(src, /export const PALETTES/);
  assert.match(src, /export function pickPalette/);
  assert.match(src, /default: DEFAULT/);
  assert.match(src, /solarized: SOLARIZED/);
  assert.match(src, /monokai: MONOKAI/);
});

test("R42: ThemeContext.tsx exists with ThemeProvider + useTheme", () => {
  assert.ok(existsSync(join(root, "src", "ThemeContext.tsx")));
  const src = readFileSync(join(root, "src", "ThemeContext.tsx"), "utf-8");
  assert.match(src, /export const ThemeProvider/);
  assert.match(src, /export function useTheme/);
});

test("R42: state.ts has themeName + setTheme action", () => {
  const state = readFileSync(join(root, "src", "state.ts"), "utf-8");
  // R386: this used to pin `themeName: "default" | "solarized" |
  // "monokai"` — and that pin was the DEFECT. The product ships five
  // palettes, the state type knew about three of them, and the two
  // it did not know about could not be selected. The pin became an
  // INVERTED invariant: the state must not spell the vocabulary out;
  // it must take the registry's type. (An inverted invariant survives
  // a rename and still catches the real defect — R384, Lesson 1237.)
  assert.doesNotMatch(
    state,
    /themeName:\s*"[^"]*"\s*\|/,
    "state.ts spells the theme vocabulary out — take the registry's ThemeName instead",
  );
  assert.match(state, /setTheme/);
});

test("R42: tui.tsx has ThemeProvider wrap + __THEME__ handler", () => {
  const tui = readFileSync(join(root, "src", "tui.tsx"), "utf-8");
  assert.match(tui, /import\s+\{[^}]*\bThemeProvider\b[^}]*\}\s+from\s+"\.\/ThemeContext\.js"/);
  assert.match(tui, /<ThemeProvider\s+themeName=\{state\.themeName\}>/);
  assert.match(tui, /__THEME__:/);
  assert.match(tui, /setTheme/);
});

test("R42: commands.ts has /theme slash command", () => {
  const cmds = readFileSync(join(root, "src", "commands.ts"), "utf-8");
  assert.match(cmds, /case "theme":/);
  assert.match(cmds, /__THEME__:/);
});

test("R42: /theme <name> routes EVERY theme the registry ships", () => {
  // R386: this used to assert that the strings "solarized" and
  // "monokai" APPEARED in commands.ts. That is the shape of the
  // defect, not the absence of it: the names appeared in a hand-
  // written list that had already fallen behind the registry, and
  // the assertion stayed green while `/theme kanagawa` was rejected
  // with "unknown theme".
  //
  // "the key is present" is not "the value is used" (R383, Lesson
  // 1230). The assertion below asks what the command actually does.
  for (const name of THEME_NAMES) {
    const r = routeSlash(`/theme ${name}`);
    assert.equal(r.local, `__THEME__:${name}`, `/theme ${name} does not reach the renderer`);
  }
});

// ----- 3. Build smoke test -------------------------------------------

test("R42: TypeScript compile of themes.ts + ThemeContext.tsx is clean", () => {
  const tmp2 = join(root, "tmp-r42-tsc2");
  if (existsSync(tmp2)) {
    spawnSync(
      process.platform === "win32" ? "cmd" : "rm",
      process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", tmp2] : ["-rf", tmp2],
    );
  }
  const tscArgs = [
    "--outDir",
    tmp2,
    "--target",
    "ES2022",
    "--module",
    "ES2022",
    "--moduleResolution",
    "bundler",
    "--jsx",
    "react",
    "--esModuleInterop",
    "true",
    "--skipLibCheck",
    "true",
    "--rootDir",
    join(root, "src"),
  ];
  const r = spawnSync(
    `"${tscBin}"`,
    [
      ...tscArgs,
      '"' + join(root, "src", "themes.ts") + '"',
      '"' + join(root, "src", "ThemeContext.tsx") + '"',
    ],
    { encoding: "utf-8", shell: true },
  );
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
  }
  assert.equal(r.status, 0, "tsc compile failed for R42");
});
