// T-6-11 (Phase 6.2): PresetSelector — 3 preset cards.
//
// 4 tests:
//   1. file exists + exports the public API
//   2. pure: PRESETS catalog has permissive/cautious/strict
//   3. pure: isValidPreset accepts only the 3 known IDs
//   4. source: PresetSelector renders 3 cards + onApply + Enter/1/2/3
//   5. tsc: PresetSelector.tsx compiles cleanly

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// ----- 1. File exists + public API -------------------------------------

test("T-6-11: PresetSelector.tsx exists and exports the picker + PRESETS + isValidPreset", () => {
  const path = join(root, "src", "components", "permissions", "PresetSelector.tsx");
  assert.ok(existsSync(path), `expected ${path}`);
  const src = readFileSync(path, "utf-8");
  assert.match(src, /export const PresetSelector/);
  assert.match(src, /export default PresetSelector/);
  // Catalog + validator.
  assert.match(src, /export const PRESETS/);
  assert.match(src, /export function isValidPreset/);
  assert.match(src, /export function formatPresetLine/);
  assert.match(src, /export type PresetId/);
});

// ----- 2. Pure: PRESETS catalog ----------------------------------------

const tmp = join(root, "tmp-t6-11-tsc");
const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
if (existsSync(tmp)) {
  spawnSync(process.platform === "win32" ? "cmd" : "rm",
    process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", tmp] : ["-rf", tmp]);
}
mkdirSync(tmp, { recursive: true });
const shimPath = join(root, "src", "components", "permissions", "_t6_11_helpers.ts");
const shim = [
  `export { PRESETS, isValidPreset, formatPresetLine } from "./PresetSelector.js";`,
  "",
].join("\n");
writeFileSync(shimPath, shim, "utf-8");

const tscArgs = [
  "--outDir", join(tmp, "out"), "--target", "ES2022", "--module", "ES2022",
  "--moduleResolution", "bundler", "--jsx", "react",
  "--esModuleInterop", "true", "--skipLibCheck", "true",
  "--rootDir", join(root, "src"),
];
{
  const r = spawnSync(`"${tscBin}"`, [...tscArgs, `"${shimPath}"`], { encoding: "utf-8", shell: true });
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
    process.exit(1);
  }
}
const helpers = await import(
  "file:///" + join(tmp, "out", "components", "permissions", "_t6_11_helpers.js").replace(/\\/g, "/")
);
const { PRESETS, isValidPreset, formatPresetLine } = helpers;

test("T-6-11: PRESETS catalog has permissive/cautious/strict with tool categories", () => {
  const ids = PRESETS.map((p) => p.id);
  assert.deepEqual(ids, ["permissive", "cautious", "strict"]);
  for (const p of PRESETS) {
    assert.ok(p.name && p.description && p.summary);
    assert.ok(Array.isArray(p.toolCategories) && p.toolCategories.length > 0);
  }
  // Permissive covers read tools.
  assert.ok(PRESETS[0].toolCategories.some((c) => c.includes("read") || c.includes("glob")));
  // Strict mentions shell + write.
  assert.ok(PRESETS[2].toolCategories.some((c) => c.includes("shell")));
  assert.ok(PRESETS[2].toolCategories.some((c) => c.includes("write")));
});

// ----- 3. Pure: isValidPreset + formatPresetLine -----------------------

test("T-6-11: isValidPreset accepts only the 3 known IDs", () => {
  assert.equal(isValidPreset("permissive"), true);
  assert.equal(isValidPreset("cautious"), true);
  assert.equal(isValidPreset("strict"), true);
  assert.equal(isValidPreset(""), false);
  assert.equal(isValidPreset("nope"), false);
  assert.equal(isValidPreset("PERMISSIVE"), false);
  assert.equal(formatPresetLine(PRESETS[0]), "permissive — allow read-only, prompt for the rest");
});

// ----- 4. Source: PresetSelector wiring -------------------------------

test("T-6-11: PresetSelector.tsx renders 3 cards + onApply + Enter/1/2/3", () => {
  const src = readFileSync(join(root, "src", "components", "permissions", "PresetSelector.tsx"), "utf-8");
  // 3 cards.
  assert.match(src, /PRESETS\.map/);
  // Active marker.
  assert.match(src, /\(current\)/);
  // onApply.
  assert.match(src, /onApply/);
  // Keyboard.
  assert.match(src, /useInput/);
  assert.match(src, /key\.return/);
  assert.match(src, /key\.leftArrow/);
  assert.match(src, /key\.rightArrow/);
  // 1/2/3 quick-select.
  assert.match(src, /input === "1"/);
  assert.match(src, /input === "2"/);
  assert.match(src, /input === "3"/);
  // compact prop for the GrantsManager integration.
  assert.match(src, /compact\?/);
});

// ----- 5. tsc: PresetSelector.tsx compiles cleanly ---------------------

test("T-6-11: TypeScript compile of PresetSelector.tsx is clean", () => {
  const r = spawnSync(`"${tscBin}"`, [
    ...tscArgs,
    '"' + join(root, "src", "components", "permissions", "PresetSelector.tsx") + '"',
  ], { encoding: "utf-8", shell: true });
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
  }
  assert.equal(r.status, 0, "tsc compile failed for T-6-11 PresetSelector");
});
