// T-440: TokenChart with provider-aware limits.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// ----- 1. Pure helper tests (T-440) --------------------------------

// Mirror the implementation in TokenChart.tsx.
function effectiveLimit(l) {
  if (!l) return 0;
  const w = l.contextWindow ?? 0;
  const o = l.maxOutputTokens ?? 0;
  return Math.max(w, o);
}

function bucketColor(value, limit, warnAt = 0.5, criticalAt = 0.8) {
  if (limit <= 0) return "cyan";
  const ratio = value / limit;
  if (ratio >= criticalAt) return "red";
  if (ratio >= warnAt) return "yellow";
  return "green";
}

test("T-440: effectiveLimit returns 0 when no limit is set", () => {
  assert.equal(effectiveLimit(null), 0);
  assert.equal(effectiveLimit(undefined), 0);
  assert.equal(effectiveLimit({}), 0);
});

test("T-440: effectiveLimit picks max of contextWindow / maxOutputTokens", () => {
  assert.equal(effectiveLimit({ contextWindow: 200_000, maxOutputTokens: 8_000 }), 200_000);
  assert.equal(effectiveLimit({ contextWindow: 0, maxOutputTokens: 8_000 }), 8_000);
  assert.equal(effectiveLimit({ contextWindow: 128_000 }), 128_000);
});

test("T-440: bucketColor returns cyan when no cap is set", () => {
  assert.equal(bucketColor(1000, 0), "cyan");
  assert.equal(bucketColor(999_999, -1), "cyan");
});

test("T-440: bucketColor returns green below 50% of the cap", () => {
  assert.equal(bucketColor(0,     1000), "green");
  assert.equal(bucketColor(499,   1000), "green");
});

test("T-440: bucketColor returns yellow at 50-80% of the cap", () => {
  assert.equal(bucketColor(500, 1000), "yellow");
  assert.equal(bucketColor(799, 1000), "yellow");
});

test("T-440: bucketColor returns red at >= 80% of the cap", () => {
  assert.equal(bucketColor(800, 1000), "red");
  assert.equal(bucketColor(950, 1000), "red");
  assert.equal(bucketColor(1000, 1000), "red");
});

// ----- 2. Source-code assertions -----------------------------------

const src = readFileSync(join(root, "src", "components", "TokenChart.tsx"), "utf-8");

test("T-440: TokenChart.tsx exports ProviderLimits interface", () => {
  assert.match(src, /export interface ProviderLimits/);
  assert.match(src, /contextWindow\?/);
  assert.match(src, /maxOutputTokens\?/);
  assert.match(src, /warnAt\?/);
  assert.match(src, /criticalAt\?/);
});

test("T-440: TokenChart.tsx exports effectiveLimit helper", () => {
  assert.match(src, /export function effectiveLimit\(/);
});

test("T-440: TokenChart.tsx exports bucketColor helper", () => {
  assert.match(src, /export function bucketColor\(/);
});

test("T-440: TokenChart.tsx props include providerLimits + showCapMarker", () => {
  // The component's Props interface.
  assert.match(src, /providerLimits\?:\s*ProviderLimits\s*\|\s*null/);
  assert.match(src, /showCapMarker\?:\s*boolean/);
});

test("T-440: TokenChart.tsx renders the tail bucket in the cap colour", () => {
  // The "isLast" branch swaps the colour to tailColor.
  assert.match(src, /const isLast = i === buckets\.length - 1/);
  assert.match(src, /const c = isLast && cap > 0 \? tailColor : color/);
});

test("T-440: TokenChart.tsx adds a cap marker when the cap is set", () => {
  assert.match(src, /const renderCapMarker = cap > 0/);
  assert.match(src, /<Text[^>]*>\u2595<\/Text>/);
});

// ----- 3. tsc compile ----------------------------------------------

test("T-440: TypeScript compile of TokenChart.tsx is clean", () => {
  const tmp = join(root, "tmp-t440-tsc");
  if (existsSync(tmp)) {
    spawnSync(process.platform === "win32" ? "cmd" : "rm",
      process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", tmp] : ["-rf", tmp]);
  }
  const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
  const tscArgs = [
    "--outDir", tmp, "--target", "ES2022", "--module", "ES2022",
    "--moduleResolution", "bundler", "--jsx", "react",
    "--esModuleInterop", "true", "--skipLibCheck", "true",
    "--strict", "true",
    "--rootDir", join(root, "src"),
  ];
  const r = spawnSync(`"${tscBin}"`, [
    ...tscArgs,
    '"' + join(root, "src", "components", "TokenChart.tsx") + '"',
  ], { encoding: "utf-8", shell: true });
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
  }
  assert.equal(r.status, 0, "tsc compile failed for T-440 TokenChart");
});
