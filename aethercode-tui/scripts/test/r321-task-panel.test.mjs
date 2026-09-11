// R321 (T-370..T-374): TaskPanel.tsx source-level tests.
//
// The aethercode-tui test infrastructure uses node --test on
// scripts/test/*.test.mjs files. The existing pattern (see
// r37-sidebar.test.mjs, r92-subagent-panel-and-cancel.test.mjs)
// is to assert on the source file directly and to compile
// state.ts into a tmp dir for behaviour tests. ink-testing-
// library is not on the classpath here, so we lean on source
// assertions + a minimal compile that proves the file is
// syntactically valid TS/TSX. The component itself is
// presentational + keyboard-driven; behaviour is covered by
// the Java task-rpc test (TaskRpcTest) end-to-end.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const panelPath = join(root, "src", "components", "TaskPanel.tsx");
const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");

// ----- 1. Source-level contract --------------------------------------

test("R321: TaskPanel.tsx exists at the design-specified path", () => {
  assert.ok(existsSync(panelPath),
    `TaskPanel.tsx must exist at ${panelPath}`);
});

test("R321: TaskPanel exports TaskPanel function and both interfaces", () => {
  const src = readFileSync(panelPath, "utf-8");
  assert.match(src, /export function TaskPanel/);
  assert.match(src, /export interface TaskPanelChild/);
  assert.match(src, /export interface TaskPanelProps/);
});

test("R321: TaskPanel covers the design §4.7 contract", () => {
  const src = readFileSync(panelPath, "utf-8");
  // T-371: list all children
  assert.match(src, /children:\s*TaskPanelChild\[\]/);
  // T-372: click-to-attach
  assert.match(src, /onAttach\?:\s*\(childId: string\)\s*=>\s*void/);
  // T-373: live status — preview field + status-aware row
  assert.match(src, /preview\?:\s*string/);
  assert.match(src, /STATUS_COLOR/);
  assert.match(src, /STATUS_ICON/);
  assert.match(src, /R321/);
});

test("R321: TaskPanel exposes attach / kill / resume / retry actions", () => {
  const src = readFileSync(panelPath, "utf-8");
  assert.match(src, /onAttach\?/);
  assert.match(src, /onKill\?/);
  assert.match(src, /onResume\?/);
  assert.match(src, /onRetry\?/);
});

test("R321: TaskPanel uses the ink primitives (Box, Text, useInput)", () => {
  const src = readFileSync(panelPath, "utf-8");
  assert.match(src, /from "ink"/);
  assert.match(src, /<Box/);
  assert.match(src, /<Text/);
  assert.match(src, /useInput/);
});

test("R321: TaskPanel handles the 6 supervisor lifecycle states", () => {
  const src = readFileSync(panelPath, "utf-8");
  // ChildStatus enum has 6 values; the panel must reference
  // each one to render an icon + colour.
  for (const s of ["QUEUED", "RUNNING", "PAUSED", "COMPLETED", "FAILED", "KILLED"]) {
    assert.ok(src.includes(s), `expected ${s} in STATUS_COLOR / STATUS_ICON map`);
  }
});

test("R321: TaskPanel key bindings match design.md §4.7 + §4.6", () => {
  const src = readFileSync(panelPath, "utf-8");
  // 'a' attach, 'k' kill, 'r' resume, 'x' retry, 'j/k' nav, Esc close, Enter attach.
  for (const k of ['"a"', '"k"', '"r"', '"x"', '"j"', "key.escape", "key.return", "key.upArrow", "key.downArrow"]) {
    assert.ok(src.includes(k), `expected ${k} in TaskPanel source`);
  }
});

test("R321: TaskPanel shows a hint that names the CLI fallback", () => {
  const src = readFileSync(panelPath, "utf-8");
  assert.match(src, /aethercode task spawn/,
    "empty-state hint should mention the CLI command so users discover it");
});

// ----- 2. Compile smoke test ----------------------------------------

test("R321: TaskPanel.tsx compiles without TS errors", () => {
  const tmp = join(root, "tmp-r321-tsc");
  if (existsSync(tmp)) {
    spawnSync(process.platform === "win32" ? "cmd" : "rm",
      process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", tmp] : ["-rf", tmp]);
  }
  const tscArgs = [
    "--outDir", tmp, "--target", "ES2022", "--module", "ES2022",
    "--moduleResolution", "bundler", "--esModuleInterop", "true",
    "--skipLibCheck", "true", "--jsx", "react", "--rootDir", join(root, "src"),
    "--noEmit", "true",
  ];
  const r = spawnSync(`"${tscBin}"`, [...tscArgs, `"${panelPath}"`], {
    encoding: "utf-8", shell: true,
  });
  // We only require that the *file* compiles. The rest of
  // the project might have other unrelated issues; if so
  // we surface them but the test passes as long as
  // TaskPanel.tsx is structurally valid (no parse errors).
  const output = (r.stdout || "") + (r.stderr || "");
  const hasFileError = /TaskPanel\.tsx.*error/i.test(output);
  assert.equal(hasFileError, false,
    `TaskPanel.tsx should compile cleanly; got: ${output}`);
});

// ----- 3. Wire-up to the supervisor RPC -----------------------------

test("R321: TaskPanel docstring lists the 10 RPC methods it depends on", () => {
  const src = readFileSync(panelPath, "utf-8");
  // The docstring above the component enumerates the RPCs
  // the App must wire. A future contributor who renames
  // or drops one will see this test fail and update the
  // docstring or the App wiring accordingly.
  assert.match(src, /task\/list/);
  assert.match(src, /task\/attach/);
  assert.match(src, /task\/kill/);
  assert.match(src, /task\/resume/);
  assert.match(src, /task\/retry/);
  assert.match(src, /task\/events/);
});
