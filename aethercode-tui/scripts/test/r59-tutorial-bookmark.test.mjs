// R59 (tutorial) + R60 (bookmark) — combined test since both
// touch the same set of state and tui.tsx paths.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// ----- 1. Compile state.ts and import --------------------------------
const tmp = join(root, "tmp-r59-tsc");
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

test("R59: INITIAL.tutorialOpen is false", () => {
  assert.equal(INITIAL.tutorialOpen, false);
});

test("R59: setTutorialOpen toggles the overlay", () => {
  let s = reducer(INITIAL, { type: "setTutorialOpen", open: true });
  assert.equal(s.tutorialOpen, true);
  s = reducer(s, { type: "setTutorialOpen", open: false });
  assert.equal(s.tutorialOpen, false);
});

test("R60: INITIAL.bookmarks is empty", () => {
  assert.deepEqual(INITIAL.bookmarks, []);
});

test("R60: toggleBookmark adds an id", () => {
  const s = reducer(INITIAL, { type: "toggleBookmark", id: 42 });
  assert.deepEqual(s.bookmarks, [42]);
});

test("R60: toggleBookmark removes an existing id", () => {
  let s = reducer(INITIAL, { type: "toggleBookmark", id: 42 });
  s = reducer(s, { type: "toggleBookmark", id: 42 });
  assert.deepEqual(s.bookmarks, []);
});

test("R60: toggleBookmark preserves other ids", () => {
  let s = reducer(INITIAL, { type: "toggleBookmark", id: 1 });
  s = reducer(s, { type: "toggleBookmark", id: 2 });
  s = reducer(s, { type: "toggleBookmark", id: 3 });
  s = reducer(s, { type: "toggleBookmark", id: 2 });
  assert.deepEqual(s.bookmarks, [1, 3]);
});

// ----- 2. Source code assertions -------------------------------------

test("R59: state.ts has tutorialOpen + setTutorialOpen", () => {
  const state = readFileSync(join(root, "src", "state.ts"), "utf-8");
  assert.match(state, /tutorialOpen: boolean/);
  assert.match(state, /setTutorialOpen/);
});

test("R60: state.ts has bookmarks + toggleBookmark", () => {
  const state = readFileSync(join(root, "src", "state.ts"), "utf-8");
  assert.match(state, /bookmarks: number\[\]/);
  assert.match(state, /toggleBookmark/);
});

test("R59+R60: commands.ts has /tutorial + /bookmark", () => {
  const cmds = readFileSync(join(root, "src", "commands.ts"), "utf-8");
  assert.match(cmds, /case "tutorial":/);
  assert.match(cmds, /case "bookmark":/);
  assert.match(cmds, /__TUTORIAL__/);
  assert.match(cmds, /__BOOKMARK__/);
});

test("R59: tui.tsx has Ctrl-T binding + tutorial overlay render", () => {
  const tui = readFileSync(join(root, "src", "tui.tsx"), "utf-8");
  assert.match(tui, /key\.ctrl && \(input === "t" \|\| input === "T"\)/);
  assert.match(tui, /setTutorialOpen/);
  assert.match(tui, /state\.tutorialOpen/);
  assert.match(tui, /<Welcome/);
});

test("R60: tui.tsx has 'b' key for bookmark + __BOOKMARK__ handler", () => {
  const tui = readFileSync(join(root, "src", "tui.tsx"), "utf-8");
  assert.match(tui, /toggleBookmark/);
  assert.match(tui, /__BOOKMARK__/);
  assert.match(tui, /__BOOKMARK_LAST__/);
});
