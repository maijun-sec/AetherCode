// T-6-12 (Phase 6.2): ModelSelector — grouped by provider + tier
// + pricing + capability badges.
//
// 5 tests:
//   1. file exists + exports the public API
//   2. pure: MODELS catalog has multiple providers; formatPrice / formatContext / deriveTier
//   3. pure: groupModelsByProvider sorts and groups
//   4. source: ModelSelector groups by provider + Enter/↑/↓/Esc + capability badges
//   5. tsc: ModelSelector.tsx compiles cleanly

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// ----- 1. File exists + public API -------------------------------------

test("T-6-12: ModelSelector.tsx exists and exports the picker + pure helpers + MODELS", () => {
  const path = join(root, "src", "components", "models", "ModelSelector.tsx");
  assert.ok(existsSync(path), `expected ${path}`);
  const src = readFileSync(path, "utf-8");
  assert.match(src, /export const ModelSelector/);
  assert.match(src, /export default ModelSelector/);
  // Pure-helper exports.
  assert.match(src, /export const MODELS/);
  assert.match(src, /export function groupModelsByProvider/);
  assert.match(src, /export function formatPrice/);
  assert.match(src, /export function formatContext/);
  assert.match(src, /export function deriveTier/);
  // Type exports.
  assert.match(src, /export interface ModelProfile/);
  assert.match(src, /export interface ModelCapabilities/);
});

// ----- 2. Pure: formatters + catalog -----------------------------------

const tmp = join(root, "tmp-t6-12-tsc");
const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
if (existsSync(tmp)) {
  spawnSync(process.platform === "win32" ? "cmd" : "rm",
    process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", tmp] : ["-rf", tmp]);
}
mkdirSync(tmp, { recursive: true });
const shimPath = join(root, "src", "components", "models", "_t6_12_helpers.ts");
const shim = [
  `export {`,
  `  MODELS,`,
  `  groupModelsByProvider,`,
  `  formatPrice,`,
  `  formatContext,`,
  `  formatMaxOutput,`,
  `  deriveTier,`,
  `} from "./ModelSelector.js";`,
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
  "file:///" + join(tmp, "out", "components", "models", "_t6_12_helpers.js").replace(/\\/g, "/")
);
const { MODELS, groupModelsByProvider, formatPrice, formatContext, formatMaxOutput, deriveTier } = helpers;

test("T-6-12: MODELS catalog spans multiple providers; formatters are sane", () => {
  // At least 2 providers in the shipped catalog.
  const providers = new Set(MODELS.map((m) => m.provider));
  assert.ok(providers.size >= 2, "expected >= 2 providers in MODELS");
  for (const m of MODELS) {
    assert.ok(m.name && m.provider);
    assert.ok(m.contextWindow > 0);
    assert.ok(m.maxOutput > 0);
    assert.ok(m.priceIn >= 0 && m.priceOut >= 0 && m.priceCached >= 0);
    assert.ok(m.capabilities);
  }
  // formatPrice
  assert.equal(formatPrice(0), "—");
  assert.equal(formatPrice(-1), "—");
  assert.equal(formatPrice(0.5), "$0.50");
  assert.equal(formatPrice(3), "$3.0");
  assert.equal(formatPrice(75), "$75");
  // formatContext
  assert.equal(formatContext(200_000), "200k");
  assert.equal(formatContext(1_000_000), "1.0M");
  assert.equal(formatContext(0), "—");
  // formatMaxOutput
  assert.equal(formatMaxOutput(8_192), "8k");
  assert.equal(formatMaxOutput(16_384), "16k");
  // deriveTier
  assert.equal(deriveTier("claude-haiku-4-5"), "haiku");
  assert.equal(deriveTier("claude-sonnet-4-5"), "sonnet");
  assert.equal(deriveTier("claude-opus-4-1"), "opus");
  assert.equal(deriveTier("gpt-5-mini"), "mini");
});

// ----- 3. Pure: groupModelsByProvider ---------------------------------

test("T-6-12: groupModelsByProvider sorts by provider and by name within", () => {
  const grouped = groupModelsByProvider(MODELS);
  // Providers sorted alphabetically.
  const providers = grouped.map((g) => g.provider);
  const sorted = [...providers].sort();
  assert.deepEqual(providers, sorted);
  for (const g of grouped) {
    const names = g.items.map((m) => m.name);
    const sortedNames = [...names].sort();
    assert.deepEqual(names, sortedNames);
  }
  // All models accounted for.
  const total = grouped.reduce((s, g) => s + g.items.length, 0);
  assert.equal(total, MODELS.length);
});

// ----- 4. Source: ModelSelector wiring --------------------------------

test("T-6-12: ModelSelector.tsx groups by provider + keyboard + badges", () => {
  const src = readFileSync(join(root, "src", "components", "models", "ModelSelector.tsx"), "utf-8");
  // Grouping.
  assert.match(src, /groupModelsByProvider/);
  // Keyboard.
  assert.match(src, /useInput/);
  assert.match(src, /key\.upArrow/);
  assert.match(src, /key\.downArrow/);
  assert.match(src, /key\.return/);
  assert.match(src, /key\.escape/);
  // onSelect + onCancel callbacks.
  assert.match(src, /onSelect/);
  assert.match(src, /onCancel/);
  // Badges.
  assert.match(src, /CapabilityBadges/);
  assert.match(src, /vision/);
  assert.match(src, /tools/);
  assert.match(src, /json/);
  assert.match(src, /reasoning/);
  // Active + last-used markers.
  assert.match(src, /\[current\]/);
  assert.match(src, /\[last used\]/);
  // Pricing fields.
  assert.match(src, /priceIn/);
  assert.match(src, /priceOut/);
  assert.match(src, /priceCached/);
});

// ----- 5. tsc: ModelSelector.tsx compiles cleanly ---------------------

test("T-6-12: TypeScript compile of ModelSelector.tsx is clean", () => {
  const r = spawnSync(`"${tscBin}"`, [
    ...tscArgs,
    '"' + join(root, "src", "components", "models", "ModelSelector.tsx") + '"',
  ], { encoding: "utf-8", shell: true });
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
  }
  assert.equal(r.status, 0, "tsc compile failed for T-6-12 ModelSelector");
});
