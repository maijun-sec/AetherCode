// R37: side panel.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// ----- 1. State tests (compile state.ts and import) -------------------

const tmp = join(root, "tmp-r37-tsc");
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

test("R37: INITIAL has sidebarVisible=false, recentTasks=[]", () => {
  assert.equal(INITIAL.sidebarVisible, false);
  assert.deepEqual(INITIAL.recentTasks, []);
});

test("R37: toggleSidebar flips sidebarVisible", () => {
  let s = reducer(INITIAL, { type: "toggleSidebar" });
  assert.equal(s.sidebarVisible, true);
  s = reducer(s, { type: "toggleSidebar" });
  assert.equal(s.sidebarVisible, false);
});

test("R37: setRecentTasks replaces and caps at 20", () => {
  const many = Array.from({ length: 50 }, (_, i) => ({
    id: "t" + i, name: "task-" + i, status: "done", ts: 1000 + i,
  }));
  const s = reducer(INITIAL, { type: "setRecentTasks", tasks: many });
  assert.equal(s.recentTasks.length, 20);
  assert.equal(s.recentTasks[0].id, "t0");
  assert.equal(s.recentTasks[19].id, "t19");
});

// ----- 2. Source code assertions -------------------------------------

test("R37: Sidebar.tsx exists and exports Sidebar", () => {
  assert.ok(existsSync(join(root, "src", "components", "Sidebar.tsx")));
  const src = readFileSync(join(root, "src", "components", "Sidebar.tsx"), "utf-8");
  assert.match(src, /export const Sidebar/);
  assert.match(src, /export interface SidebarProps/);
  assert.match(src, /export interface SidebarTask/);
});

test("R37: Sidebar renders 3 sections (project / tasks / queries)", () => {
  const src = readFileSync(join(root, "src", "components", "Sidebar.tsx"), "utf-8");
  assert.match(src, /label="project"/);
  assert.match(src, /label="tasks"/);
  assert.match(src, /label="queries"/);
});

test("R37: state.ts has sidebarVisible + recentTasks fields", () => {
  const src = readFileSync(join(root, "src", "state.ts"), "utf-8");
  assert.match(src, /sidebarVisible: boolean/);
  assert.match(src, /recentTasks: Array</);
  assert.match(src, /toggleSidebar/);
  assert.match(src, /setRecentTasks/);
});

test("R37: tui.tsx renders Sidebar when state.sidebarVisible, with Ctrl+B toggle", () => {
  const tui = readFileSync(join(root, "src", "tui.tsx"), "utf-8");
  assert.match(tui, /import\s+\{[^}]*\bSidebar\b[^}]*\}\s+from\s+"\.\/components\/Sidebar\.js"/);
  assert.match(tui, /state\.sidebarVisible \? \(/);
  assert.match(tui, /<Sidebar\s+width=\{26\}/);
  assert.match(tui, /key\.ctrl && \(input === "b" \|\| input === "B"\)/);
  assert.match(tui, /dispatch\(\{ type: "toggleSidebar" \}\)/);
});

test("R37: tui.tsx fetches listTasks on sidebar open and on task_state", () => {
  const tui = readFileSync(join(root, "src", "tui.tsx"), "utf-8");
  assert.match(tui, /client\.request\("listTasks"/);
  assert.match(tui, /dispatch\(\{ type: "setRecentTasks", tasks: list \}\)/);
});

// ----- 3. Build smoke test -------------------------------------------

test("R37: TypeScript compile of new Sidebar.tsx is clean", () => {
  const tmp2 = join(root, "tmp-r37-tsc2");
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
    '"' + join(root, "src", "components", "Sidebar.tsx") + '"',
  ], { encoding: "utf-8", shell: true });
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
  }
  assert.equal(r.status, 0, "tsc compile failed for R37 Sidebar");
});
