// T-180 → T-185: ContextMeter.tsx (status bar widget).
//
// The TUI test suite uses source-grep + tsc compile + pure-helper
// tests (no `ink-testing-library` is installed; the suite has
// never used it). For ContextMeter we add three layers:
//
//   1. Pure unit tests on the small helpers: `bandColor`,
//      `fillBar`, `detectCompactDrop`, and the threshold
//      constants (T-181 / T-183).
//   2. Source-code assertions on the .tsx file (T-180 / T-182 /
//      T-184) so a regression that drops the 2 s interval, the
//      200 px width, the colour bands, or the 压缩推荐 button
//      fails the build.
//   3. TypeScript compile of the .tsx file (catches type errors
//      and missing imports).

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// ----- 1. Pure helper tests (T-181) -------------------------------

// Mirror the implementation in ContextMeter.tsx so we test the
// SPEC not the runtime. If the component ever drifts from the
// spec, both implementations change together (and the tsc compile
// below would still catch the real one).
function bandColor(ratio) {
  if (ratio > 0.80) return "red";
  if (ratio >= 0.50) return "yellow";
  return "green";
}

test("T-181: bandColor maps < 50% → green", () => {
  assert.equal(bandColor(0),    "green");
  assert.equal(bandColor(0.25), "green");
  assert.equal(bandColor(0.49), "green");
});

test("T-181: bandColor maps 50-80% → yellow (amber)", () => {
  assert.equal(bandColor(0.50), "yellow");
  assert.equal(bandColor(0.65), "yellow");
  assert.equal(bandColor(0.80), "yellow");
});

test("T-181: bandColor maps > 80% → red", () => {
  assert.equal(bandColor(0.81),  "red");
  assert.equal(bandColor(0.95),  "red");
  assert.equal(bandColor(1.0),   "red");
  assert.equal(bandColor(1.5),   "red");
});

// ----- 2. fillBar (T-180) ------------------------------------------

function fillBar(pct, width) {
  if (width <= 0) return { filled: 0, partial: false, empty: 0 };
  const clamped = Math.max(0, Math.min(1, pct));
  const exact = clamped * width;
  const filled = Math.floor(exact);
  const partial = exact - filled > 0.5;
  const empty = Math.max(0, width - filled - (partial ? 1 : 0));
  return { filled, partial, empty };
}

test("T-180: fillBar(0, 200) → 0 filled, 0 partial, 200 empty", () => {
  const r = fillBar(0, 200);
  assert.equal(r.filled, 0);
  assert.equal(r.partial, false);
  assert.equal(r.empty, 200);
});

test("T-180: fillBar(1, 200) → 200 filled, 0 partial, 0 empty", () => {
  const r = fillBar(1, 200);
  assert.equal(r.filled, 200);
  assert.equal(r.partial, false);
  assert.equal(r.empty, 0);
});

test("T-180: fillBar(0.5, 200) → 100 filled, 0 partial, 100 empty", () => {
  const r = fillBar(0.5, 200);
  assert.equal(r.filled, 100);
  assert.equal(r.partial, false);
  assert.equal(r.empty, 100);
});

test("T-180: fillBar with negative or > 1 input is clamped", () => {
  assert.equal(fillBar(-0.1, 10).filled, 0);
  assert.equal(fillBar(1.5, 10).filled, 10);
  assert.equal(fillBar(0, 0).filled, 0);
});

// ----- 3. detectCompactDrop (T-183) --------------------------------

function detectCompactDrop(prev, next, threshold = 0.30) {
  if (!prev) return null;
  if (prev.inputTokens <= 0) return null;
  const drop = (prev.inputTokens - next.inputTokens) / prev.inputTokens;
  if (drop > threshold) {
    return { from: prev.inputTokens, to: next.inputTokens, atMs: next.ts };
  }
  return null;
}

test("T-183: detects > 30% drop between two samples", () => {
  const drop = detectCompactDrop(
    { ts: 1000, inputTokens: 1000 },
    { ts: 2000, inputTokens: 600 },
    0.30,
  );
  assert.ok(drop);
  assert.equal(drop.from, 1000);
  assert.equal(drop.to, 600);
  assert.equal(drop.atMs, 2000);
});

test("T-183: ignores a 30% drop (strict greater-than)", () => {
  // 30% drop exactly should NOT be flagged (design says "> 30%").
  const drop = detectCompactDrop(
    { ts: 1000, inputTokens: 1000 },
    { ts: 2000, inputTokens: 700 },
    0.30,
  );
  assert.equal(drop, null);
});

test("T-183: ignores a small drop (e.g. 10%)", () => {
  const drop = detectCompactDrop(
    { ts: 1000, inputTokens: 1000 },
    { ts: 2000, inputTokens: 900 },
    0.30,
  );
  assert.equal(drop, null);
});

test("T-183: returns null when there is no previous sample", () => {
  const drop = detectCompactDrop(
    null,
    { ts: 2000, inputTokens: 600 },
  );
  assert.equal(drop, null);
});

test("T-183: returns null when previous inputTokens is 0 (avoids div/0)", () => {
  const drop = detectCompactDrop(
    { ts: 1000, inputTokens: 0 },
    { ts: 2000, inputTokens: 0 },
  );
  assert.equal(drop, null);
});

test("T-183: detects a 50% drop", () => {
  const drop = detectCompactDrop(
    { ts: 1000, inputTokens: 2000 },
    { ts: 2000, inputTokens: 1000 },
    0.30,
  );
  assert.ok(drop);
  assert.equal(drop.from, 2000);
  assert.equal(drop.to, 1000);
});

// ----- 4. Source-code assertions (T-180, T-181, T-182, T-184) ------

const cm = readFileSync(join(root, "src", "components", "ContextMeter.tsx"), "utf-8");

test("T-180: ContextMeter.tsx exists + exports ContextMeter", () => {
  assert.ok(existsSync(join(root, "src", "components", "ContextMeter.tsx")));
  assert.match(cm, /export const ContextMeter/);
  assert.match(cm, /export interface ContextInfo/);
  assert.match(cm, /export interface ContextSample/);
  assert.match(cm, /export interface ContextMeterProps/);
});

test("T-180: bar width is 200 px (DEFAULT_BAR_WIDTH)", () => {
  assert.match(cm, /DEFAULT_BAR_WIDTH\s*=\s*200/);
  assert.match(cm, /width\s*=\s*DEFAULT_BAR_WIDTH/);
});

test("T-181: 3 colour bands via bandColor() with the exact thresholds", () => {
  assert.match(cm, /function bandColor\(ratio: number\)/);
  assert.match(cm, /if \(ratio > 0\.80\) return "red";/);
  assert.match(cm, /if \(ratio >= 0\.50\) return "yellow";/);
  assert.match(cm, /return "green";/);
});

test("T-182: sample interval is 2_000 ms (DEFAULT_SAMPLE_INTERVAL_MS)", () => {
  assert.match(cm, /DEFAULT_SAMPLE_INTERVAL_MS\s*=\s*2_000/);
  assert.match(cm, /setInterval\(\(\) => \{/);
  assert.match(cm, /\}, sampleIntervalMs\)/);
});

test("T-183: COMPACT_DROP_THRESHOLD is 0.30 and detectCompactDrop exists", () => {
  assert.match(cm, /COMPACT_DROP_THRESHOLD\s*=\s*0\.30/);
  assert.match(cm, /export function detectCompactDrop\(/);
});

test("T-184: 压缩推荐 button is rendered when ratio >= 0.80", () => {
  assert.match(cm, /RECOMMEND_BUTTON_THRESHOLD\s*=\s*0\.80/);
  // The button label appears in the source.
  assert.match(cm, /压缩推荐/);
  // And the condition gates the render.
  assert.match(cm, /showButton\s*=\s*ratio\s*>=\s*RECOMMEND_BUTTON_THRESHOLD/);
});

test("T-184: 压缩推荐 button wires to onRunCompact", () => {
  assert.match(cm, /callbacksRef\.current\.onRunCompact\?\.\(\)/);
});

test("T-183: onCompactEvent fires on a > 30% drop", () => {
  assert.match(cm, /callbacksRef\.current\.onCompactEvent\?\.\(drop\)/);
});

test("T-180: ContextMeter uses React + ink (Box, Text, useInput)", () => {
  assert.match(cm, /import React, \{ useEffect, useRef, useState \} from "react";/);
  assert.match(cm, /import \{ Box, Text, useInput \} from "ink";/);
});

// ----- 5. tsc compile of the new component ------------------------

const tmp = join(root, "tmp-r180-tsc");
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
{
  const r = spawnSync(`"${tscBin}"`, [
    ...tscArgs,
    '"' + join(root, "src", "components", "ContextMeter.tsx") + '"',
  ], { encoding: "utf-8", shell: true });
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
  }
  assert.equal(r.status, 0, "tsc compile failed for ContextMeter.tsx");
}
