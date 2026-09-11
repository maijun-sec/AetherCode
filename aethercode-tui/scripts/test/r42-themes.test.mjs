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
    spawnSync(process.platform === "win32" ? "cmd" : "rm",
      process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", tmp] : ["-rf", tmp]);
  }
  const tscArgs = [
    "--outDir", tmp, "--target", "ES2022", "--module", "ES2022",
    "--moduleResolution", "bundler", "--esModuleInterop", "true",
    "--skipLibCheck", "true", "--rootDir", join(root, "src"),
  ];
  const r = spawnSync(`"${tscBin}"`, [...tscArgs, `"${join(root, "src", "state.ts")}"`], {
    encoding: "utf-8", shell: true,
  });
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
    process.exit(1);
  }
}
const stateMod = await import("file:///" + join(tmp, "state.js").replace(/\\/g, "/"));
const { reducer, INITIAL } = stateMod;

test("R42: INITIAL.themeName is 'default'", () => {
  assert.equal(INITIAL.themeName, "default");
});

test("R42: setTheme switches themeName", () => {
  let s = reducer(INITIAL, { type: "setTheme", theme: "solarized" });
  assert.equal(s.themeName, "solarized");
  s = reducer(s, { type: "setTheme", theme: "monokai" });
  assert.equal(s.themeName, "monokai");
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
  assert.match(state, /themeName: "default" \| "solarized" \| "monokai"/);
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
  assert.match(cmds, /solarized/);
  assert.match(cmds, /monokai/);
});

// ----- 3. Build smoke test -------------------------------------------

test("R42: TypeScript compile of themes.ts + ThemeContext.tsx is clean", () => {
  const tmp2 = join(root, "tmp-r42-tsc2");
  if (existsSync(tmp2)) {
    spawnSync(process.platform === "win32" ? "cmd" : "rm",
      process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", tmp2] : ["-rf", tmp2]);
  }
  const tscArgs = [
    "--outDir", tmp2, "--target", "ES2022", "--module", "ES2022",
    "--moduleResolution", "bundler", "--jsx", "react",
    "--esModuleInterop", "true", "--skipLibCheck", "true",
    "--rootDir", join(root, "src"),
  ];
  const r = spawnSync(`"${tscBin}"`, [
    ...tscArgs,
    '"' + join(root, "src", "themes.ts") + '"',
    '"' + join(root, "src", "ThemeContext.tsx") + '"',
  ], { encoding: "utf-8", shell: true });
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
  }
  assert.equal(r.status, 0, "tsc compile failed for R42");
});
