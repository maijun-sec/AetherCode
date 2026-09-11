// R52: error display.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// ----- 1. Compile state.ts and import --------------------------------
const tmp = join(root, "tmp-r52-tsc");
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

test("R52: INITIAL.lastError is null", () => {
  assert.equal(INITIAL.lastError, null);
});

test("R52: setLastError captures the error", () => {
  const s = reducer(INITIAL, {
    type: "setLastError",
    error: { message: "rpc timeout", ts: 1000, stack: "Error: rpc timeout\n at <anonymous>" },
  });
  assert.notEqual(s.lastError, null);
  assert.equal(s.lastError.message, "rpc timeout");
  assert.equal(s.lastError.ts, 1000);
  assert.ok(s.lastError.stack);
});

test("R52: setLastError null clears the error", () => {
  let s = reducer(INITIAL, {
    type: "setLastError",
    error: { message: "x", ts: 1 },
  });
  s = reducer(s, { type: "setLastError", error: null });
  assert.equal(s.lastError, null);
});

test("R52: submit clears the lastError", () => {
  let s = reducer(INITIAL, {
    type: "setLastError",
    error: { message: "boom", ts: 1 },
  });
  s = reducer(s, { type: "setInput", text: "next prompt" });
  s = reducer(s, { type: "submit" });
  assert.equal(s.lastError, null);
});

// ----- 2. Source code assertions -------------------------------------

test("R52: state.ts has lastError + setLastError", () => {
  const state = readFileSync(join(root, "src", "state.ts"), "utf-8");
  assert.match(state, /lastError: \{ message: string; ts: number; stack\?: string \} \| null/);
  assert.match(state, /setLastError/);
});

test("R52: Scrollback.tsx renders a dedicated error card", () => {
  const sb = readFileSync(join(root, "src", "components", "Scrollback.tsx"), "utf-8");
  assert.match(sb, /function renderErrorCard/);
  assert.match(sb, /✗ Error/);
  assert.match(sb, /err\.stack/);
  assert.match(sb, /lastError/);
});

test("R52: tui.tsx captures errors via setLastError", () => {
  const tui = readFileSync(join(root, "src", "tui.tsx"), "utf-8");
  assert.match(tui, /setLastError/);
  assert.match(tui, /lastError=\{state\.lastError\}/);
});
