// R196 (Phase 5 R4): handleSlash tests for the new R4 commands.
//
// We exercise the dispatcher with the new R4 commands:
//   /mcp, /mcp-login <name>, /mcp-reconnect [name]
//   /threads
//   /update [install], /update-deps
//   /notifications
//
// We also assert the commands are listed in SLASH_COMMANDS so
// tab-completion picks them up.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdirSync, writeFileSync, unlinkSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

const tmp = join(root, "tmp-r196-cmds-tsc");
const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
if (existsSync(tmp)) {
  spawnSync(process.platform === "win32" ? "cmd" : "rm",
    process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", tmp] : ["-rf", tmp]);
}
mkdirSync(tmp, { recursive: true });
const shimPath = join(root, "src", "_r196_cmds_helpers.ts");
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
const r = spawnSync(`"${tscBin}"`, [...tscArgs, `"${shimPath}"`], { encoding: "utf-8", shell: true });
if (r.status !== 0) {
  console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
  process.exit(1);
}
const helpers = await import(
  "file:///" + join(tmp, "out", "_r196_cmds_helpers.js").replace(/\\/g, "/")
);
const { handleSlash, SLASH_COMMANDS, SLASH_HELP, completeSlash } = helpers;

// ----- 1. /mcp ------------------------------------------------------

test("R196: /mcp → __MCP_VIEWER__", () => {
  const r = handleSlash("/mcp");
  assert.equal(r.local, "__MCP_VIEWER__");
});

// ----- 2. /mcp-login -----------------------------------------------

test("R196: /mcp-login github → __MCP_LOGIN__:github", () => {
  const r = handleSlash("/mcp-login github");
  assert.equal(r.local, "__MCP_LOGIN__:github");
});

test("R196: /mcp-login (no arg) → usage hint", () => {
  const r = handleSlash("/mcp-login");
  assert.match(r.local, /usage:/);
});

// ----- 3. /mcp-reconnect -------------------------------------------

test("R196: /mcp-reconnect → __MCP_RECONNECT__", () => {
  const r = handleSlash("/mcp-reconnect");
  assert.equal(r.local, "__MCP_RECONNECT__");
});

test("R196: /mcp-reconnect github → __MCP_RECONNECT__:github", () => {
  const r = handleSlash("/mcp-reconnect github");
  assert.equal(r.local, "__MCP_RECONNECT__:github");
});

// ----- 4. /threads -------------------------------------------------

test("R196: /threads → __THREAD_SELECTOR__", () => {
  const r = handleSlash("/threads");
  assert.equal(r.local, "__THREAD_SELECTOR__");
});

// ----- 5. /update --------------------------------------------------

test("R196: /update → __UPDATE_AVAILABLE__", () => {
  const r = handleSlash("/update");
  assert.equal(r.local, "__UPDATE_AVAILABLE__");
});

test("R196: /update install → __UPDATE_INSTALL__", () => {
  const r = handleSlash("/update install");
  assert.equal(r.local, "__UPDATE_INSTALL__");
});

// ----- 6. /update-deps ---------------------------------------------

test("R196: /update-deps → __UPDATE_DEPS__", () => {
  const r = handleSlash("/update-deps");
  assert.equal(r.local, "__UPDATE_DEPS__");
});

// ----- 7. /notifications -------------------------------------------

test("R196: /notifications → __NOTIFICATION_CENTER__", () => {
  const r = handleSlash("/notifications");
  assert.equal(r.local, "__NOTIFICATION_CENTER__");
});

// ----- 8. SLASH_COMMANDS registration ------------------------------

test("R196: SLASH_COMMANDS contains the new R4 commands", () => {
  for (const c of ["mcp", "mcp-login", "mcp-reconnect", "threads", "update", "update-deps", "notifications"]) {
    assert.ok(SLASH_COMMANDS.includes(c), `missing command: ${c}`);
  }
});

// ----- 9. SLASH_HELP mentions the new commands ---------------------

test("R196: SLASH_HELP mentions the new R4 commands", () => {
  for (const c of ["/mcp", "/threads", "/update", "/notifications"]) {
    assert.ok(SLASH_HELP.includes(c), `SLASH_HELP missing: ${c}`);
  }
});

// ----- 10. Tab completion ------------------------------------------

test("R196: tab-completion picks up /threads", () => {
  const c = completeSlash("/thr");
  assert.equal(c.completed, "/threads ");
  assert.equal(c.oneShot, true);
});

test("R196: tab-completion picks up /notifications", () => {
  const c = completeSlash("/notif");
  assert.equal(c.completed, "/notifications ");
  assert.equal(c.oneShot, true);
});

test("R196: tab-completion /up offers update + update-deps", () => {
  const c = completeSlash("/up");
  assert.ok(c.alternatives.includes("update"));
  assert.ok(c.alternatives.includes("update-deps"));
});

test("R196: tab-completion /mcp offers mcp + mcp-login + mcp-reconnect", () => {
  const c = completeSlash("/mcp");
  assert.ok(c.alternatives.includes("mcp"));
  assert.ok(c.alternatives.includes("mcp-login"));
  assert.ok(c.alternatives.includes("mcp-reconnect"));
});

try { unlinkSync(shimPath); } catch { /* ignore */ }
