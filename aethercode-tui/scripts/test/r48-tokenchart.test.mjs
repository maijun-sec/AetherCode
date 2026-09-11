// R48: token chart (sparkline).

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// ----- 1. Compile state.ts and import --------------------------------
const tmp = join(root, "tmp-r48-tsc");
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

test("R48: INITIAL.recentCosts is empty", () => {
  assert.deepEqual(INITIAL.recentCosts, []);
});

test("R48: pushCost appends to recentCosts", () => {
  let s = reducer(INITIAL, { type: "pushCost", cost: 0.01 });
  s = reducer(s, { type: "pushCost", cost: 0.02 });
  s = reducer(s, { type: "pushCost", cost: 0.03 });
  assert.deepEqual(s.recentCosts, [0.01, 0.02, 0.03]);
});

test("R48: pushCost caps at 64 entries", () => {
  let s = INITIAL;
  for (let i = 0; i < 80; i++) s = reducer(s, { type: "pushCost", cost: i * 0.001 });
  assert.equal(s.recentCosts.length, 64);
  // The first 16 entries are dropped; the last 64 are kept.
  assert.equal(s.recentCosts[0], 16 * 0.001);
  assert.equal(s.recentCosts[63], 79 * 0.001);
});

// ----- 2. Source code assertions -------------------------------------

test("R48: TokenChart.tsx exists + exports TokenChart", () => {
  assert.ok(existsSync(join(root, "src", "components", "TokenChart.tsx")));
  const src = readFileSync(join(root, "src", "components", "TokenChart.tsx"), "utf-8");
  assert.match(src, /export const TokenChart/);
  // The 8 bar characters.
  assert.match(src, /▁/);
  assert.match(src, /█/);
});

test("R48: state.ts has recentCosts + pushCost", () => {
  const state = readFileSync(join(root, "src", "state.ts"), "utf-8");
  assert.match(state, /recentCosts: number\[\]/);
  assert.match(state, /pushCost/);
});

test("R48: StatusBar renders TokenChart when recentCosts has > 1 entries", () => {
  const sb = readFileSync(join(root, "src", "components", "StatusBar.tsx"), "utf-8");
  assert.match(sb, /import\s+\{[^}]*\bTokenChart\b[^}]*\}\s+from\s+"\.\/TokenChart\.js"/);
  assert.match(sb, /state\.recentCosts\.length > 1/);
  assert.match(sb, /<TokenChart\s+values=\{state\.recentCosts\}/);
});

test("R48: tui.tsx dispatches pushCost on run_end", () => {
  const tui = readFileSync(join(root, "src", "tui.tsx"), "utf-8");
  assert.match(tui, /pushCost/);
  assert.match(tui, /costUsd/);
});

// ----- 3. Build smoke test -------------------------------------------

test("R48: TypeScript compile of TokenChart.tsx is clean", () => {
  const tmp2 = join(root, "tmp-r48-tsc2");
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
    '"' + join(root, "src", "components", "TokenChart.tsx") + '"',
  ], { encoding: "utf-8", shell: true });
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
  }
  assert.equal(r.status, 0, "tsc compile failed for R48 TokenChart");
});
