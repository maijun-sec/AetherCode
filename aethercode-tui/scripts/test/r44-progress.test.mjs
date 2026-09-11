// R44: progress bars.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// ----- 1. Pure tests on ratio() -------------------------------------

function ratio(value, max) {
  if (max <= 0) return 0;
  return Math.max(0, Math.min(1, value / max));
}

test("R44: ratio clamps to [0, 1]", () => {
  assert.equal(ratio(0, 100), 0);
  assert.equal(ratio(50, 100), 0.5);
  assert.equal(ratio(100, 100), 1);
  assert.equal(ratio(-5, 100), 0);
  assert.equal(ratio(150, 100), 1);
  assert.equal(ratio(50, 0), 0);
});

// ----- 2. Compile state.ts and import --------------------------------
const tmp = join(root, "tmp-r44-tsc");
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

test("R44: INITIAL.costBudget is 0 (no budget)", () => {
  assert.equal(INITIAL.costBudget, 0);
});

test("R44: setCostBudget sets the budget", () => {
  let s = reducer(INITIAL, { type: "setCostBudget", usd: 1.5 });
  assert.equal(s.costBudget, 1.5);
  s = reducer(s, { type: "setCostBudget", usd: 0 });
  assert.equal(s.costBudget, 0);
});

test("R44: setCostBudget clamps negative to 0", () => {
  const s = reducer(INITIAL, { type: "setCostBudget", usd: -1 });
  assert.equal(s.costBudget, 0);
});

// ----- 3. Source code assertions -------------------------------------

test("R44: ProgressBar.tsx exists + exports ProgressBar + ratio", () => {
  assert.ok(existsSync(join(root, "src", "components", "ProgressBar.tsx")));
  const src = readFileSync(join(root, "src", "components", "ProgressBar.tsx"), "utf-8");
  assert.match(src, /export const ProgressBar/);
  assert.match(src, /export function ratio/);
});

test("R44: StatusBar.tsx imports ProgressBar + renders it conditionally on costBudget", () => {
  const sb = readFileSync(join(root, "src", "components", "StatusBar.tsx"), "utf-8");
  assert.match(sb, /import\s+\{[^}]*\bProgressBar\b[^}]*\}\s+from\s+"\.\/ProgressBar\.js"/);
  assert.match(sb, /state\.costBudget > 0 \? \(/);
});

test("R44: state.ts has costBudget + setCostBudget", () => {
  const state = readFileSync(join(root, "src", "state.ts"), "utf-8");
  assert.match(state, /costBudget: number/);
  assert.match(state, /setCostBudget/);
});

test("R44: commands.ts has /budget slash command", () => {
  const cmds = readFileSync(join(root, "src", "commands.ts"), "utf-8");
  assert.match(cmds, /case "budget":/);
  assert.match(cmds, /__BUDGET__:/);
});

// ----- 4. Build smoke test -------------------------------------------

test("R44: TypeScript compile of ProgressBar.tsx is clean", () => {
  const tmp2 = join(root, "tmp-r44-tsc2");
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
    '"' + join(root, "src", "components", "ProgressBar.tsx") + '"',
  ], { encoding: "utf-8", shell: true });
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
  }
  assert.equal(r.status, 0, "tsc compile failed for R44 ProgressBar");
});
