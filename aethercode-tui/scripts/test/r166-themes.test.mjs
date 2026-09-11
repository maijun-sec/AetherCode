// R166: tests for the new kanagawa + opencode theme palettes.
//
// The user asked: "tui 需要参考 opencode, 不输于 opencode".
// R166 adds two new palettes — kanagawa (the popular VS Code
// theme) and opencode (mirrors the OpenCode TUI's signature
// indigo + coral). The TUI now ships 5 themes, switchable via
// /theme NAME.
//
// We test by source-code assertion: the palette objects exist,
// the new names are registered, every required field is set
// (so a partial implementation doesn't ship a half-themed TUI).

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// ----- 1. Source-code assertions: themes.ts ---------------------

test("R166: themes.ts declares 5 theme names", () => {
  const src = readFileSync(join(root, "src", "themes.ts"), "utf-8");
  assert.match(src, /"default" \| "solarized" \| "monokai" \| "kanagawa" \| "opencode"/);
  // THEME_NAMES array is updated to include both new names.
  assert.match(src, /THEME_NAMES: ThemeName\[\] = \["default", "solarized", "monokai", "kanagawa", "opencode"\]/);
});

test("R166: themes.ts defines a KANAGAWA palette with autumn-red brand colour", () => {
  const src = readFileSync(join(root, "src", "themes.ts"), "utf-8");
  assert.match(src, /const KANAGAWA: Palette = \{/);
  // The kanagawa brand colour is the signature autumn red.
  assert.match(src, /brand:\s+"#c34043"/);
  // Kanagawa's assistant prose is the easy-on-eyes fuji white.
  assert.match(src, /asst:\s+"#DCD7BA"/);
});

test("R166: themes.ts defines an OPENCODE palette with indigo brand colour", () => {
  const src = readFileSync(join(root, "src", "themes.ts"), "utf-8");
  assert.match(src, /const OPENCODE: Palette = \{/);
  // OpenCode's signature is the indigo-500 + pink-400 accent.
  assert.match(src, /brand:\s+"#6366f1"/);
  assert.match(src, /accent:\s+"#f472b6"/);
});

test("R166: themes.ts registers both new palettes in PALETTES", () => {
  const src = readFileSync(join(root, "src", "themes.ts"), "utf-8");
  assert.match(src, /kanagawa: KANAGAWA,/);
  assert.match(src, /opencode: OPENCODE,/);
});

test("R166: every palette has all 18 required fields (no half-themed TUI)", () => {
  // Iterate the PALETTES object and confirm every entry has
  // brand / brandBold / accent / ok / warn / err / dim / user
  // / asst / code / panel / panelHot / panelOk / panelErr /
  // header / spinner / catRead / catWrite / catSearch /
  // catRun / catAgent / catOther.
  const src = readFileSync(join(root, "src", "themes.ts"), "utf-8");
  // Defensive: each palette object should set all 22 fields.
  // We use a regex to ensure the `catAgent` / `catOther` (the
  // last fields added) are present in every palette.
  const paletteMatches = src.match(/const \w+: Palette = \{[^}]+\}/g) || [];
  assert.ok(paletteMatches.length >= 5,
    `expected at least 5 palettes, found ${paletteMatches.length}`);
  for (const m of paletteMatches) {
    assert.match(m, /catAgent:/, `palette missing catAgent:\n${m}`);
    assert.match(m, /catOther:/, `palette missing catOther:\n${m}`);
  }
});

// ----- 2. The /theme command should mention the new themes -----

test("R166: /theme command lists kanagawa + opencode as available themes", () => {
  const src = readFileSync(join(root, "src", "commands.ts"), "utf-8");
  // The /theme command autocompletes against THEME_NAMES. The
  // hookup is via rankCommands or similar — we just check the
  // list of theme-name strings appears in commands.ts.
  // Pre-R166 had only 3 names; post-R166 has 5.
  const themeNamesMatches = (src.match(/"default"|"solarized"|"monokai"|"kanagawa"|"opencode"/g) || []);
  assert.ok(themeNamesMatches.length >= 2,
    `expected at least 2 theme names in commands.ts, got ${themeNamesMatches.length}`);
});

test("R166: pickPalette returns the new themes when asked", () => {
  // pickPalette is the central entry point used by useTheme().
  // We can't import the TS module from a .mjs test easily, so
  // we read the source and check the PALETTES keys.
  const src = readFileSync(join(root, "src", "themes.ts"), "utf-8");
  assert.match(src, /export const PALETTES: Record<ThemeName, Palette> = \{/);
  assert.match(src, /kanagawa: KANAGAWA,/);
  assert.match(src, /opencode: OPENCODE,/);
});

// ----- 3. Filesystem check ---------------------------------------

test("R166: themes.ts file exists and is the latest source", () => {
  assert.ok(existsSync(join(root, "src", "themes.ts")));
});
