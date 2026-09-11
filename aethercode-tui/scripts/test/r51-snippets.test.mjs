// R51: snippets.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// ----- 1. Compile state.ts and import --------------------------------
const tmp = join(root, "tmp-r51-tsc");
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

test("R51: INITIAL.snippets is empty", () => {
  assert.deepEqual(INITIAL.snippets, {});
});

test("R51: saveSnippet adds a snippet", () => {
  const s = reducer(INITIAL, { type: "saveSnippet", name: "fix", body: "fix the bug" });
  assert.equal(s.snippets["fix"], "fix the bug");
});

test("R51: saveSnippet overwrites an existing snippet", () => {
  let s = reducer(INITIAL, { type: "saveSnippet", name: "fix", body: "fix v1" });
  s = reducer(s, { type: "saveSnippet", name: "fix", body: "fix v2" });
  assert.equal(s.snippets["fix"], "fix v2");
  assert.equal(Object.keys(s.snippets).length, 1);
});

test("R51: deleteSnippet removes a snippet", () => {
  let s = reducer(INITIAL, { type: "saveSnippet", name: "a", body: "x" });
  s = reducer(s, { type: "saveSnippet", name: "b", body: "y" });
  assert.equal(Object.keys(s.snippets).length, 2);
  s = reducer(s, { type: "deleteSnippet", name: "a" });
  assert.equal(Object.keys(s.snippets).length, 1);
  assert.equal(s.snippets["b"], "y");
});

test("R51: deleteSnippet on missing name is a no-op", () => {
  let s = reducer(INITIAL, { type: "saveSnippet", name: "a", body: "x" });
  s = reducer(s, { type: "deleteSnippet", name: "nonexistent" });
  assert.equal(Object.keys(s.snippets).length, 1);
});

// ----- 2. Source code assertions -------------------------------------

test("R51: commands.ts has /snippet with save/load/list/delete", () => {
  const cmds = readFileSync(join(root, "src", "commands.ts"), "utf-8");
  assert.match(cmds, /case "snippet":/);
  assert.match(cmds, /__SNIPPET_SAVE__/);
  assert.match(cmds, /__SNIPPET_LOAD__/);
  assert.match(cmds, /__SNIPPET_LIST__/);
  assert.match(cmds, /__SNIPPET_DELETE__/);
});

test("R51: tui.tsx wires all 4 snippet subcommands", () => {
  const tui = readFileSync(join(root, "src", "tui.tsx"), "utf-8");
  assert.match(tui, /__SNIPPET_SAVE__/);
  assert.match(tui, /__SNIPPET_LOAD__/);
  assert.match(tui, /__SNIPPET_LIST__/);
  assert.match(tui, /__SNIPPET_DELETE__/);
  // The "load" subcommand sets the input to the snippet body.
  // The "save" subcommand dispatches saveSnippet; the
  // "delete" subcommand dispatches deleteSnippet.
  assert.match(tui, /saveSnippet/);
  assert.match(tui, /deleteSnippet/);
  assert.match(tui, /setInput/);
});

test("R51: state.ts has snippets + saveSnippet + deleteSnippet", () => {
  const state = readFileSync(join(root, "src", "state.ts"), "utf-8");
  assert.match(state, /snippets: Record<string, string>/);
  assert.match(state, /saveSnippet/);
  assert.match(state, /deleteSnippet/);
});
