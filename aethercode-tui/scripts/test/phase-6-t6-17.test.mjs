// T-6-17 (Phase 6.3): the 9 new slash commands.
//
// 6 tests:
//   1. /continue → task/resume RPC (no args = active task)
//   2. /pause, /stop → task/pause, task/kill
//   3. /todos → __TODO_BOARD__ local token
//   4. /tokens → session/tokens RPC
//   5. /consents + /grants → __GRANTS_MANAGER__ (alias)
//   6. /model <name> → model/set + /workflow [name] → picker or workflow/run
//
//  Plus: tab-completion picks up the new commands.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdirSync, writeFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// Compile a shim that re-exports the commands module.
const tmp = join(root, "tmp-t6-17-tsc");
const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
if (existsSync(tmp)) {
  spawnSync(process.platform === "win32" ? "cmd" : "rm",
    process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", tmp] : ["-rf", tmp]);
}
mkdirSync(tmp, { recursive: true });
const shimPath = join(root, "src", "_t6_17_helpers.ts");
const shim = [
  `export { handleSlash, SLASH_COMMANDS, SLASH_HELP, completeSlash } from "./commands.js";`,
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
  "file:///" + join(tmp, "out", "_t6_17_helpers.js").replace(/\\/g, "/")
);
const { handleSlash, SLASH_COMMANDS, SLASH_HELP, completeSlash } = helpers;

// ----- 1. /continue → task/resume --------------------------------------

test("T-6-17: /continue → task/resume (no args = active task)", () => {
  const r1 = handleSlash("/continue");
  assert.equal(r1.rpcMethod, "task/resume");
  assert.equal(r1.rpcParams, undefined);
  // /continue with an explicit id passes it as a param.
  const r2 = handleSlash("/continue abc-123");
  assert.equal(r2.rpcMethod, "task/resume");
  assert.deepEqual(r2.rpcParams, { id: "abc-123" });
  // /resume is an alias.
  const r3 = handleSlash("/resume");
  assert.equal(r3.rpcMethod, "task/resume");
});

// ----- 2. /pause + /stop → task/pause + task/kill -----------------------

test("T-6-17: /pause → task/pause and /stop → task/kill", () => {
  const r1 = handleSlash("/pause");
  assert.equal(r1.rpcMethod, "task/pause");
  const r2 = handleSlash("/stop");
  assert.equal(r2.rpcMethod, "task/kill");
  // /kill alias
  const r3 = handleSlash("/kill");
  assert.equal(r3.rpcMethod, "task/kill");
  // With explicit id.
  const r4 = handleSlash("/pause task-9");
  assert.deepEqual(r4.rpcParams, { id: "task-9" });
  const r5 = handleSlash("/stop task-9");
  assert.deepEqual(r5.rpcParams, { id: "task-9" });
});

// ----- 3. /todos → __TODO_BOARD__ --------------------------------------

test("T-6-17: /todos → __TODO_BOARD__ local token", () => {
  const r1 = handleSlash("/todos");
  assert.equal(r1.local, "__TODO_BOARD__");
  // /todo singular alias.
  const r2 = handleSlash("/todo");
  assert.equal(r2.local, "__TODO_BOARD__");
});

// ----- 4. /tokens → session/tokens RPC ---------------------------------

test("T-6-17: /tokens → session/tokens RPC", () => {
  const r = handleSlash("/tokens");
  assert.equal(r.rpcMethod, "session/tokens");
  // /token singular alias.
  const r2 = handleSlash("/token");
  assert.equal(r2.rpcMethod, "session/tokens");
});

// ----- 5. /consents + /grants → __GRANTS_MANAGER__ (alias) -------------

test("T-6-17: /consents and /grants both → __GRANTS_MANAGER__", () => {
  assert.equal(handleSlash("/consents").local, "__GRANTS_MANAGER__");
  assert.equal(handleSlash("/grants").local, "__GRANTS_MANAGER__");
});

// ----- 6. /model + /workflow -------------------------------------------

test("T-6-17: /model <name> → model/set + /workflow [name] → picker or workflow/run", () => {
  // /model uses the new model/set RPC.
  const r1 = handleSlash("/model claude-opus-4-1");
  assert.equal(r1.rpcMethod, "model/set");
  assert.deepEqual(r1.rpcParams, { name: "claude-opus-4-1" });
  // /model without an arg is still a usage hint.
  const r2 = handleSlash("/model");
  assert.match(r2.local, /usage:/);
  // /workflow with no args → picker.
  const r3 = handleSlash("/workflow");
  assert.equal(r3.local, "__WORKFLOW_PICKER__");
  // /workflow with a name → workflow/run.
  const r4 = handleSlash("/workflow tdd-feature");
  assert.equal(r4.rpcMethod, "workflow/run");
  assert.deepEqual(r4.rpcParams, { name: "tdd-feature" });
});

// ----- 7. SLASH_COMMANDS + SLASH_HELP registration ---------------------

test("T-6-17: SLASH_COMMANDS + SLASH_HELP contain the 9 new commands", () => {
  for (const c of [
    "continue", "pause", "stop", "todos", "tokens", "consents", "grants", "workflow",
  ]) {
    assert.ok(SLASH_COMMANDS.includes(c), `SLASH_COMMANDS missing: ${c}`);
  }
  for (const c of [
    "/continue", "/pause", "/stop", "/todos", "/tokens", "/consents", "/grants", "/workflow",
  ]) {
    assert.ok(SLASH_HELP.includes(c), `SLASH_HELP missing: ${c}`);
  }
});

// ----- 8. Tab-completion -----------------------------------------------

test("T-6-17: tab-completion picks up the new commands", () => {
  // /con matches BOTH "continue" and "consents" — LCP is
  // "con" (3 chars: both have c/o/n, but "continue" has
  // 't' at position 3 where "consents" has 's'). The user
  // needs to type more characters.
  const c1 = completeSlash("/con");
  assert.equal(c1.completed, "/con");
  assert.deepEqual(c1.alternatives.sort(), ["consents", "continue"]);
  assert.equal(c1.oneShot, false);
  // /tok → /tokens (unique)
  const c2 = completeSlash("/tok");
  assert.equal(c2.completed, "/tokens ");
  assert.equal(c2.oneShot, true);
  // /work → /workflow (unique)
  const c3 = completeSlash("/work");
  assert.equal(c3.completed, "/workflow ");
  assert.equal(c3.oneShot, true);
  // /continu is unique to /continue.
  const c4 = completeSlash("/continu");
  assert.equal(c4.completed, "/continue ");
  assert.equal(c4.oneShot, true);
  // /grant → /grants (unique — note /consents doesn't match).
  const c5 = completeSlash("/grant");
  assert.equal(c5.completed, "/grants ");
  assert.equal(c5.oneShot, true);
});
