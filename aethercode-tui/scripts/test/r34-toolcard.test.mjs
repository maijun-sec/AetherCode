// R34: tests for tool card evolution (duration / category / expand).
//
// We test:
//   1. categorizeTool maps tool names to the right category
//   2. formatDuration renders ms / s / m+s / h+m correctly
//   3. Reducer: streamToolStart sets toolStartedAt + toolCategory
//   4. Reducer: streamToolEnd sets toolEndedAt
//   5. Reducer: toggleToolExpand flips the toolExpanded flag
//   6. Source: ToolCard.tsx imports formatDuration + uses cat icon/color
//   7. Source: tui.tsx has a tick effect + 'd' handler
//   8. Compile: TypeScript compile of the new code is clean

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// ----- Compile state.ts so we can import the helpers -------------------
const tmp = join(root, "tmp-r34-tsc");
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
const { reducer, INITIAL, categorizeTool, formatDuration } = stateMod;

// ----- 1. categorizeTool ---------------------------------------------

test("R34: categorizeTool maps file_read / glob / grep to 'read' / 'search'", () => {
  assert.equal(categorizeTool("file_read"), "read");
  assert.equal(categorizeTool("read"), "read");
  assert.equal(categorizeTool("cat"), "read");
  assert.equal(categorizeTool("glob"), "search");
  assert.equal(categorizeTool("grep"), "search");
  assert.equal(categorizeTool("search"), "search");
  assert.equal(categorizeTool("find"), "search");
});

test("R34: categorizeTool maps file_write / file_edit to 'write'", () => {
  assert.equal(categorizeTool("file_write"), "write");
  assert.equal(categorizeTool("file_edit"), "write");
  assert.equal(categorizeTool("file_create"), "write");
  assert.equal(categorizeTool("edit_doc"), "write");
});

test("R34: categorizeTool maps bash / shell / exec / test to 'run'", () => {
  assert.equal(categorizeTool("bash"), "run");
  assert.equal(categorizeTool("shell"), "run");
  assert.equal(categorizeTool("exec"), "run");
  assert.equal(categorizeTool("run_command"), "run");
  assert.equal(categorizeTool("test"), "run");
  assert.equal(categorizeTool("build"), "run");
});

test("R34: categorizeTool maps agent / task / delegate to 'agent'", () => {
  assert.equal(categorizeTool("agent"), "agent");
  assert.equal(categorizeTool("task"), "agent");
  assert.equal(categorizeTool("delegate"), "agent");
  assert.equal(categorizeTool("agent_run"), "agent");
});

test("R34: categorizeTool returns 'other' for unknown / null names", () => {
  assert.equal(categorizeTool("xyzzy"), "other");
  assert.equal(categorizeTool(""), "other");
  assert.equal(categorizeTool(null), "other");
  assert.equal(categorizeTool(undefined), "other");
});

test("R34: categorizeTool is case-insensitive", () => {
  assert.equal(categorizeTool("FILE_READ"), "read");
  assert.equal(categorizeTool("Bash"), "run");
  assert.equal(categorizeTool("Grep"), "search");
});

// ----- 2. formatDuration ---------------------------------------------

test("R34: formatDuration renders sub-second as ms", () => {
  assert.equal(formatDuration(0), "0ms");
  assert.equal(formatDuration(123), "123ms");
  assert.equal(formatDuration(999), "999ms");
});

test("R34: formatDuration renders sub-minute as N.Ns", () => {
  assert.equal(formatDuration(1000), "1.0s");
  assert.equal(formatDuration(1500), "1.5s");
  assert.equal(formatDuration(59_999), "60.0s");
});

test("R34: formatDuration renders sub-hour as XmYYs", () => {
  assert.equal(formatDuration(60_000), "1m00s");
  assert.equal(formatDuration(65_000), "1m05s");
  assert.equal(formatDuration(125_000), "2m05s");
});

test("R34: formatDuration renders hours as XhYYm", () => {
  assert.equal(formatDuration(3_600_000), "1h00m");
  assert.equal(formatDuration(7_200_000), "2h00m");
  assert.equal(formatDuration(7_500_000), "2h05m");
});

test("R34: formatDuration handles invalid input", () => {
  assert.equal(formatDuration(NaN), "—");
  assert.equal(formatDuration(-1), "—");
  assert.equal(formatDuration(Infinity), "—");
});

// ----- 3-5. Reducer tests --------------------------------------------

test("R34: streamToolStart sets toolStartedAt + toolCategory", () => {
  const before = INITIAL.turns.length;
  const s = reducer(INITIAL, { type: "streamToolStart", name: "file_read", args: '{"path":"x.txt"}' });
  assert.equal(s.turns.length, before + 1);
  const t = s.turns[s.turns.length - 1];
  assert.equal(t.toolName, "file_read");
  assert.equal(t.toolCategory, "read");
  assert.equal(t.toolStatus, "running");
  assert.ok(typeof t.toolStartedAt === "number" && t.toolStartedAt > 0, "toolStartedAt not set");
  assert.equal(t.toolEndedAt, undefined, "toolEndedAt should be undefined while running");
});

test("R34: streamToolEnd sets toolEndedAt", async () => {
  let s = reducer(INITIAL, { type: "streamToolStart", name: "bash", args: "{}" });
  const before = s.turns.length;
  // Sleep 5ms so toolEndedAt - toolStartedAt > 0.
  await new Promise((r) => setTimeout(r, 5));
  s = reducer(s, { type: "streamToolEnd", name: "bash", isError: false, result: "ok" });
  assert.equal(s.turns.length, before);
  const t = s.turns[s.turns.length - 1];
  assert.equal(t.toolStatus, "ok");
  assert.ok(typeof t.toolEndedAt === "number", "toolEndedAt not set");
  assert.ok(t.toolEndedAt >= t.toolStartedAt, "toolEndedAt < toolStartedAt");
});

test("R34: streamToolEnd marks tool as error on isError=true", () => {
  let s = reducer(INITIAL, { type: "streamToolStart", name: "bash", args: "{}" });
  s = reducer(s, { type: "streamToolEnd", name: "bash", isError: true, result: "permission denied" });
  const t = s.turns[s.turns.length - 1];
  assert.equal(t.toolStatus, "error");
  assert.equal(t.toolResult, "permission denied");
});

test("R34: toggleToolExpand flips toolExpanded on tool turns only", () => {
  let s = reducer(INITIAL, { type: "streamToolStart", name: "glob", args: "{}" });
  const toolId = s.turns[s.turns.length - 1].id;
  assert.equal(s.turns[s.turns.length - 1].toolExpanded, undefined);
  s = reducer(s, { type: "toggleToolExpand", id: toolId });
  assert.equal(s.turns[s.turns.length - 1].toolExpanded, true);
  s = reducer(s, { type: "toggleToolExpand", id: toolId });
  assert.equal(s.turns[s.turns.length - 1].toolExpanded, false);
});

test("R34: toggleToolExpand is no-op on non-tool turns", () => {
  let s = reducer(INITIAL, { type: "setInput", text: "hello" });
  s = reducer(s, { type: "submit" });
  const userId = s.turns[s.turns.length - 1].id;
  // user turn — toggleToolExpand should not crash and should not change anything.
  const after = reducer(s, { type: "toggleToolExpand", id: userId });
  assert.deepEqual(after, s);
});

// ----- 6-7. Source code assertions -----------------------------------

test("R34: ToolCard.tsx imports formatDuration and uses cat icon / color", () => {
  const tc = readFileSync(join(root, "src", "components", "ToolCard.tsx"), "utf-8");
  assert.match(tc, /import\s+\{[^}]*formatDuration[^}]*\}\s+from\s+"\.\.\/state\.js"/);
  assert.match(tc, /CATEGORY_ICON/);
  assert.match(tc, /CATEGORY_COLOR/);
  assert.match(tc, /toolExpanded/);
  assert.match(tc, /toolStartedAt/);
  assert.match(tc, /toolEndedAt/);
});

test("R34: tui.tsx has a tick effect for live duration + 'd' key for expand", () => {
  const tui = readFileSync(join(root, "src", "tui.tsx"), "utf-8");
  assert.match(tui, /useState\(0\)/, "tick state missing");
  assert.match(tui, /setInterval/, "tick setInterval missing");
  assert.match(tui, /toggleToolExpand/, "'d' handler missing");
  assert.match(tui, /pending === 0/, "tick pause-on-no-pending missing");
});

test("R34: state.ts exports categorizeTool + formatDuration", () => {
  const src = readFileSync(join(root, "src", "state.ts"), "utf-8");
  assert.match(src, /export function categorizeTool/);
  assert.match(src, /export function formatDuration/);
  assert.match(src, /type ToolCategory = "read" \| "write" \| "search" \| "run" \| "agent" \| "other"/);
});

test("R34: theme.ts has tool-category icons + colors", () => {
  const theme = readFileSync(join(root, "src", "theme.ts"), "utf-8");
  for (const k of ["catRead", "catWrite", "catSearch", "catRun", "catAgent", "catOther", "detail", "timer"]) {
    assert.match(theme, new RegExp(`\\b${k}:`), `theme.ts missing icon.${k}`);
  }
  for (const k of ["catRead", "catWrite", "catSearch", "catRun", "catAgent", "catOther"]) {
    assert.match(theme, new RegExp(`\\b${k}:\\s*"`), `theme.ts missing color t.${k}`);
  }
});
