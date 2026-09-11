// R56: log viewer.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// ----- 1. Compile state.ts and import --------------------------------
const tmp = join(root, "tmp-r56-tsc");
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

test("R56: INITIAL.logBuffer=[] and logViewerOpen=false", () => {
  assert.deepEqual(INITIAL.logBuffer, []);
  assert.equal(INITIAL.logViewerOpen, false);
});

test("R56: pushLog appends a log entry", () => {
  const s = reducer(INITIAL, { type: "pushLog", level: "info", message: "hello" });
  assert.equal(s.logBuffer.length, 1);
  assert.equal(s.logBuffer[0].message, "hello");
  assert.equal(s.logBuffer[0].level, "info");
});

test("R56: pushLog caps at 200 entries", () => {
  let s = INITIAL;
  for (let i = 0; i < 250; i++) s = reducer(s, { type: "pushLog", level: "info", message: "m" + i });
  assert.equal(s.logBuffer.length, 200);
  assert.equal(s.logBuffer[0].message, "m50");
  assert.equal(s.logBuffer[199].message, "m249");
});

test("R56: setLogViewerOpen toggles the viewer", () => {
  let s = reducer(INITIAL, { type: "setLogViewerOpen", open: true });
  assert.equal(s.logViewerOpen, true);
  s = reducer(s, { type: "setLogViewerOpen", open: false });
  assert.equal(s.logViewerOpen, false);
});

// ----- 2. Source code assertions -------------------------------------

test("R56: LogViewer.tsx exists + exports LogViewer", () => {
  assert.ok(existsSync(join(root, "src", "components", "LogViewer.tsx")));
  const src = readFileSync(join(root, "src", "components", "LogViewer.tsx"), "utf-8");
  assert.match(src, /export const LogViewer/);
  assert.match(src, /export interface LogEntry/);
});

test("R56: state.ts has logBuffer + logViewerOpen + pushLog + setLogViewerOpen", () => {
  const state = readFileSync(join(root, "src", "state.ts"), "utf-8");
  assert.match(state, /logBuffer: Array</);
  assert.match(state, /logViewerOpen: boolean/);
  assert.match(state, /pushLog/);
  assert.match(state, /setLogViewerOpen/);
});

test("R56: tui.tsx has Ctrl-L binding + LogViewer render", () => {
  const tui = readFileSync(join(root, "src", "tui.tsx"), "utf-8");
  assert.match(tui, /import\s+\{[^}]*\bLogViewer\b[^}]*\}\s+from\s+"\.\/components\/LogViewer\.js"/);
  assert.match(tui, /key\.ctrl && \(input === "l" \|\| input === "L"\)/);
  assert.match(tui, /setLogViewerOpen/);
  assert.match(tui, /<LogViewer\s+entries=\{state\.logBuffer\}/);
  // tui.tsx should also dispatch pushLog on `log` notifications.
  assert.match(tui, /pushLog/);
});

// ----- 3. Build smoke test -------------------------------------------

test("R56: TypeScript compile of LogViewer.tsx is clean", () => {
  const tmp2 = join(root, "tmp-r56-tsc2");
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
    '"' + join(root, "src", "components", "LogViewer.tsx") + '"',
  ], { encoding: "utf-8", shell: true });
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
  }
  assert.equal(r.status, 0, "tsc compile failed for R56 LogViewer");
});
