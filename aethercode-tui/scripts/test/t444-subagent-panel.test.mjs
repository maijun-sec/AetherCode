// T-444: SubagentPanel matches deepagents-code richness (model + error fields).

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

const panel = readFileSync(join(root, "src", "components", "SubagentPanel.tsx"), "utf-8");
const state = readFileSync(join(root, "src", "state.ts"), "utf-8");

test("T-444: state.ts SubagentJobView adds model + error fields", () => {
  assert.match(state, /export interface SubagentJobView/);
  // The new fields are present and optional.
  assert.match(state, /model\?:\s*string/);
  assert.match(state, /error\?:\s*string/);
});

test("T-444: state.ts reducer preserves model + error across transitions", () => {
  // The reducer's subagentEvent action keeps the
  // `model` field sticky via prev?.model.
  assert.match(state, /model:\s*\(action as \{\s*model\?:\s*string\s*\}\)\.model/);
  assert.match(state, /error:\s*action\.status === "FAILED"/);
});

test("T-444: SubagentPanel renders the model on the focused row", () => {
  assert.match(panel, /focused\s*&&\s*j\.model/);
  // Truncates the model to fit the panel width.
  assert.match(panel, /truncate\(j\.model/);
});

test("T-444: SubagentPanel surfaces FAILED/CANCELLED action hints", () => {
  // The new "insert error" / "insert note" hints are
  // gated on j.status === "FAILED" / "CANCELLED".
  assert.match(panel, /j\.status === "FAILED"/);
  assert.match(panel, /insert error/);
  assert.match(panel, /j\.status === "CANCELLED"/);
  assert.match(panel, /insert note/);
});

test("T-444: SubagentPanel renders the error on FAILED rows", () => {
  assert.match(panel, /j\.status === "FAILED"\s*&&\s*j\.error/);
  assert.match(panel, /truncate\(j\.error/);
});

// ----- 3. tsc compile ----------------------------------------------

test("T-444: TypeScript compile of SubagentPanel.tsx is clean", () => {
  const tmp = join(root, "tmp-t444-tsc");
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
    '"' + join(root, "src", "components", "SubagentPanel.tsx") + '"',
  ], { encoding: "utf-8", shell: true });
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
  }
  assert.equal(r.status, 0, "tsc compile failed for T-444 SubagentPanel");
});
