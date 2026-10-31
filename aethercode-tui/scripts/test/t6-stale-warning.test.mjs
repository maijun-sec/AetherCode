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

// T-6-18: StaleWarning (re-attach prompt).
//
// Pure-helper + source-grep + tsc compile.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
const read = (rel) => readFileSync(join(root, rel), "utf-8");

// ----- 1. Pure-helper tests ------------------------------------

test("T-6-18: formatStaleDuration renders h/m/s/d", () => {
  function formatStaleDuration(ms) {
    if (!Number.isFinite(ms) || ms <= 0) return "0s";
    const total = Math.floor(ms / 1000);
    if (total < 60) return `${total}s`;
    const m = Math.floor(total / 60);
    if (m < 60) return `${m}m`;
    const h = Math.floor(m / 60);
    const remM = m % 60;
    if (h < 24) return remM === 0 ? `${h}h` : `${h}h ${remM}m`;
    const d = Math.floor(h / 24);
    const remH = h % 24;
    return remH === 0 ? `${d}d` : `${d}d ${remH}h`;
  }
  assert.equal(formatStaleDuration(0), "0s");
  assert.equal(formatStaleDuration(-100), "0s");
  assert.equal(formatStaleDuration(NaN), "0s");
  assert.equal(formatStaleDuration(45_000), "45s");
  assert.equal(formatStaleDuration(5 * 60_000), "5m");
  assert.equal(formatStaleDuration(2 * 60 * 60_000), "2h");
  assert.equal(formatStaleDuration(2 * 60 * 60_000 + 13 * 60_000), "2h 13m");
  assert.equal(formatStaleDuration(2 * 24 * 60 * 60_000), "2d");
  assert.equal(formatStaleDuration(2 * 24 * 60 * 60_000 + 3 * 60 * 60_000), "2d 3h");
});

test("T-6-18: shouldWarnStale is true after 30 min, false before", () => {
  function shouldWarnStale(lastActiveAt, now = Date.now(), thresholdMs = 30 * 60 * 1000) {
    if (!Number.isFinite(lastActiveAt) || lastActiveAt <= 0) return false;
    if (!Number.isFinite(now)) return false;
    return now - lastActiveAt > thresholdMs;
  }
  const now = 10_000_000;
  assert.equal(shouldWarnStale(now - 1_000, now), false);
  assert.equal(shouldWarnStale(now - 30 * 60_000, now), false);
  // Strict `> 30 min` — 30 min + 1 ms triggers.
  assert.equal(shouldWarnStale(now - 30 * 60_000 - 1, now), true);
  assert.equal(shouldWarnStale(now - 60 * 60_000, now), true);
  // Invalid inputs.
  assert.equal(shouldWarnStale(0, now), false);
  assert.equal(shouldWarnStale(NaN, now), false);
  assert.equal(shouldWarnStale(now - 1_000, NaN), false);
  // Custom threshold.
  assert.equal(shouldWarnStale(now - 5_000, now, 1_000), true);
  assert.equal(shouldWarnStale(now - 5_000, now, 60_000), false);
});

// ----- 2. Source-grep assertions ------------------------------

test("T-6-18: StaleWarning.tsx exists and exports the component", () => {
  const path = join(root, "src/components/StaleWarning.tsx");
  assert.ok(existsSync(path));
  const src = read("src/components/StaleWarning.tsx");
  assert.match(src, /export\s+const\s+StaleWarning\b/);
});

test("T-6-18: re-attach / cancel callbacks are wired", () => {
  const src = read("src/components/StaleWarning.tsx");
  assert.match(src, /onReattach:\s*\(\)\s*=>\s*void/);
  assert.match(src, /onCancel:\s*\(\)\s*=>\s*void/);
  assert.match(src, /onReattach\(\)/);
  assert.match(src, /onCancel\(\)/);
});

test("T-6-18: useInput handles Enter / y / n / Esc / Tab", () => {
  const src = read("src/components/StaleWarning.tsx");
  assert.match(src, /useInput\(/);
  assert.match(src, /key\.return/);
  assert.match(src, /input\s*===\s*"y"/);
  assert.match(src, /input\s*===\s*"n"/);
  assert.match(src, /key\.escape/);
  assert.match(src, /key\.tab/);
});

test("T-6-18: STALE_THRESHOLD_MS constant is exported (= 30 min)", () => {
  const src = read("src/components/StaleWarning.tsx");
  assert.match(src, /export\s+const\s+STALE_THRESHOLD_MS\s*=\s*30\s*\*\s*60\s*\*\s*1000/);
});

test("T-6-18: focus swaps between Re-attach and Cancel via Tab", () => {
  const src = read("src/components/StaleWarning.tsx");
  assert.match(src, /useState<"reattach"\s*\|\s*"cancel">/);
  assert.match(
    src,
    /setFocus\(\(f\)\s*=>\s*\(f\s*===\s*"reattach"\s*\?\s*"cancel"\s*:\s*"reattach"\)/,
  );
});

test("T-6-18: warning uses double border + warn colour", () => {
  const src = read("src/components/StaleWarning.tsx");
  assert.match(src, /borderStyle="double"/);
  assert.match(src, /borderColor=\{isStale\s*\?\s*t\.warn\s*:\s*t\.dim\}/);
});

test("T-6-18: formatStaleDuration + shouldWarnStale are exported", () => {
  const src = read("src/components/StaleWarning.tsx");
  assert.match(src, /export\s+function\s+formatStaleDuration\b/);
  assert.match(src, /export\s+function\s+shouldWarnStale\b/);
});

// ----- 3. tsc --strict compile of the .tsx -------------------

test("T-6-18: tsc --strict compile of StaleWarning.tsx is clean", () => {
  const tmp = join(root, "tmp-t6-sw-tsc");
  if (existsSync(tmp)) {
    spawnSync(
      process.platform === "win32" ? "cmd" : "rm",
      process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", tmp] : ["-rf", tmp],
      { shell: process.platform === "win32" },
    );
  }
  const r = spawnSync(
    `"${tscBin}"`,
    [
      "--outDir",
      tmp,
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
      "--strict",
      "true",
      "--noUncheckedIndexedAccess",
      "true",
      "--rootDir",
      join(root, "src"),
      join(root, "src", "components", "StaleWarning.tsx"),
    ],
    { encoding: "utf-8", shell: true },
  );
  if (r.status !== 0) {
    console.error(r.stdout);
    console.error(r.stderr);
  }
  assert.equal(r.status, 0, "tsc compile failed for StaleWarning");
});
