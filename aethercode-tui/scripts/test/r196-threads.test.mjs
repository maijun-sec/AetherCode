// R196 (Phase 5 R4): pure-function tests for the ThreadSelector.
//
// We use `node --test` with a runtime compile of just the helper
// files. The helpers are:
//   - chooseCwd       (ThreadSelector.tsx)
//   - formatThreadRow (ThreadSelector.tsx)
//
// The tests are runtime assertions, not source-code grep, so we
// get real coverage of the cwd-switch decision + the row label.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdirSync, writeFileSync, unlinkSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

const tmp = join(root, "tmp-r196-threads-tsc");
const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
if (existsSync(tmp)) {
  spawnSync(process.platform === "win32" ? "cmd" : "rm",
    process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", tmp] : ["-rf", tmp]);
}
mkdirSync(tmp, { recursive: true });
const shimPath = join(root, "src", "components", "_r196_threads_helpers.ts");
const shim = [
  `export { chooseCwd, formatThreadRow } from "./ThreadSelector.js";`,
  "",
].join("\n");
writeFileSync(shimPath, shim, "utf-8");

const tscArgs = [
  "--outDir", join(tmp, "out"), "--target", "ES2022", "--module", "ES2022",
  "--moduleResolution", "bundler", "--jsx", "react",
  "--esModuleInterop", "true", "--skipLibCheck", "true",
  "--rootDir", join(root, "src"),
];
const r = spawnSync(`"${tscBin}"`, [...tscArgs, `"${shimPath}"`], { encoding: "utf-8", shell: true });
if (r.status !== 0) {
  console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
  process.exit(1);
}
const helpers = await import(
  "file:///" + join(tmp, "out", "components", "_r196_threads_helpers.js").replace(/\\/g, "/")
);
const { chooseCwd, formatThreadRow } = helpers;

// ----- 1. chooseCwd ------------------------------------------------

test("R196: chooseCwd — same cwd → stay", () => {
  assert.equal(chooseCwd("/work/proj", "/work/proj"), "stay");
});

test("R196: chooseCwd — different cwd → switch", () => {
  assert.equal(chooseCwd("/work/other", "/work/proj"), "switch");
});

test("R196: chooseCwd — case-sensitive (Windows shares case-insensitive paths but we compare literally)", () => {
  assert.equal(chooseCwd("/Work/Proj", "/work/proj"), "switch");
});

// ----- 2. formatThreadRow ------------------------------------------

test("R196: formatThreadRow — basic row", () => {
  const t = {
    threadId: "t-1234",
    agentName: "code-reviewer",
    messages: 5,
    createdAt: "2026-08-01",
    updatedAt: "2026-08-02",
    gitBranch: undefined,
    prompt: "review this",
    cwd: "/work/proj",
  };
  assert.equal(formatThreadRow(t, false), "  t-1234  code-reviewer  5 msgs");
});

test("R196: formatThreadRow — highlighted row", () => {
  const t = {
    threadId: "t-1234",
    agentName: "code-reviewer",
    messages: 5,
    createdAt: "2026-08-01",
    updatedAt: "2026-08-02",
    gitBranch: undefined,
    prompt: "review this",
    cwd: "/work/proj",
  };
  assert.equal(formatThreadRow(t, true), "▶ t-1234  code-reviewer  5 msgs");
});

test("R196: formatThreadRow — with branch", () => {
  const t = {
    threadId: "t-1234",
    agentName: "code-reviewer",
    messages: 5,
    createdAt: "2026-08-01",
    updatedAt: "2026-08-02",
    gitBranch: "feature/auth",
    prompt: "review this",
    cwd: "/work/proj",
  };
  assert.equal(formatThreadRow(t, true), "▶ t-1234  code-reviewer  5 msgs · feature/auth");
});

try { unlinkSync(shimPath); } catch { /* ignore */ }
