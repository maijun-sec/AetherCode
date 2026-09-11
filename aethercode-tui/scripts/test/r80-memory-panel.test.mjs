// T-080 ~ T-086: MemoryPanel.tsx source-level tests.
//
// The aethercode-tui test infrastructure uses node --test on
// scripts/test/*.test.mjs files. The established pattern (see
// r37-sidebar.test.mjs, r321-task-panel.test.mjs) is to assert
// on the source file directly + run a minimal compile that
// proves the file is syntactically valid TS/TSX. ink-testing-
// library is not on the classpath here, so we lean on source
// assertions + the compile smoke test. The component itself
// is presentational + keyboard-driven; behaviour is covered
// end-to-end by the Java memory-rpc tests.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const panelPath = join(root, "src", "components", "MemoryPanel.tsx");
const cmdsPath = join(root, "src", "commands.ts");
const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");

// ----- 1. Source-level contract --------------------------------------

test("T-080: MemoryPanel.tsx exists at the design-specified path", () => {
  assert.ok(existsSync(panelPath),
    `MemoryPanel.tsx must exist at ${panelPath}`);
});

test("T-080: MemoryPanel exports MemoryPanel function and core types", () => {
  const src = readFileSync(panelPath, "utf-8");
  assert.match(src, /export function MemoryPanel/);
  assert.match(src, /export interface MemoryPanelProps/);
  assert.match(src, /export interface MemoryPanelEntry/);
  assert.match(src, /export interface MemoryPanelState/);
  assert.match(src, /export type MemoryScope/);
  assert.match(src, /export type CompactProgress/);
});

test("T-081: MemoryPanel renders the three tabs (Global / Project / Session)", () => {
  const src = readFileSync(panelPath, "utf-8");
  // Tabs literal in the file.
  assert.match(src, /global/);
  assert.match(src, /project/);
  assert.match(src, /session/);
  // TAB_ORDER must contain all three scopes.
  assert.match(src, /TAB_ORDER/);
  // Tab labels must include the three friendly names.
  assert.match(src, /Global/);
  assert.match(src, /Project/);
  assert.match(src, /Session/);
});

test("T-081: MemoryPanel exposes a way for the App to drive the active tab", () => {
  const src = readFileSync(panelPath, "utf-8");
  // activeTab is optional in the props; the App can drive it
  // OR the panel keeps local state. We accept both.
  assert.match(src, /activeTab\?:\s*MemoryScope/);
  assert.match(src, /onTabChange\?:\s*\(scope:\s*MemoryScope\)\s*=>\s*void/);
});

test("T-082: MemoryPanel has a read-only list view of entries", () => {
  const src = readFileSync(panelPath, "utf-8");
  // List rendering must use the entries prop.
  assert.match(src, /current\.entries\.map/);
  // Each row should be a Box+Text pair (read-only — no TextInput on the row).
  assert.match(src, /<Box/);
  assert.match(src, /<Text/);
  // No TextInput on individual rows (the list itself is read-only).
  const linesWithRowTextInput = src.split("\n").filter((l) =>
    /<TextInput/.test(l) && !/tab/.test(l) && !/focus/.test(l));
  assert.equal(linesWithRowTextInput.length, 0,
    "MemoryPanel rows should be read-only (no <TextInput> on a row)");
});

test("T-083: MemoryPanel has an edit dialog action (Enter / 'e' on a row)", () => {
  const src = readFileSync(panelPath, "utf-8");
  // Double-click is implemented as Enter (TUI doesn't have real
  // double-click events; the "double-click" in the design
  // means "press Enter on the focused row").
  assert.match(src, /onEditFact\?:\s*\(scope:\s*MemoryScope,\s*entry:\s*MemoryPanelEntry\)\s*=>\s*void/);
  assert.match(src, /key\.return/);
  // The 'e' shortcut is documented as a secondary trigger.
  assert.match(src, /input === "e"/);
});

test("T-084: MemoryPanel has a 'Compact now' button + progress", () => {
  const src = readFileSync(panelPath, "utf-8");
  // The button must be present in the footer.
  assert.match(src, /Compact now/);
  // onCompact is the integration callback.
  assert.match(src, /onCompact\?:\s*\(\)\s*=>\s*void/);
  // CompactProgress covers all 5 states (idle / running / ok / skipped / error).
  assert.match(src, /state:\s*"idle"/);
  assert.match(src, /state:\s*"running"/);
  assert.match(src, /state:\s*"ok"/);
  assert.match(src, /state:\s*"skipped"/);
  assert.match(src, /state:\s*"error"/);
  // "c" key fires onCompact.
  assert.match(src, /input === "c"/);
});

test("T-082: MemoryPanel uses the ink primitives (Box, Text, useInput)", () => {
  const src = readFileSync(panelPath, "utf-8");
  assert.match(src, /from "ink"/);
  assert.match(src, /<Box/);
  assert.match(src, /<Text/);
  assert.match(src, /useInput/);
});

test("T-085: commands.ts exposes /memory, /memory-edit, /memory-compact", () => {
  const cmds = readFileSync(cmdsPath, "utf-8");
  // /memory is in SLASH_COMMANDS.
  assert.match(cmds, /case "memory":/);
  // The dispatch token is the local seam the App's useInput matches on.
  assert.match(cmds, /__MEMORY_PANEL__:/);
  // /memory-edit dispatches the edit dialog.
  assert.match(cmds, /case "memory-edit":/);
  assert.match(cmds, /__MEMORY_EDIT__/);
  // /memory-compact fires the RPC.
  assert.match(cmds, /case "memory-compact":/);
  assert.match(cmds, /"memory\/compact"/);
  assert.match(cmds, /force:\s*true/);
});

test("T-085: SLASH_HELP documents the new /memory commands", () => {
  const cmds = readFileSync(cmdsPath, "utf-8");
  assert.match(cmds, /\/memory\s+T-080: open the MemoryPanel/);
  assert.match(cmds, /\/memory-compact\s+T-084/);
});

test("T-085: SLASH_COMMANDS includes the new /memory commands for tab-completion", () => {
  const cmds = readFileSync(cmdsPath, "utf-8");
  // Must be registered so the Tab-completion logic in
  // completeSlash() picks them up.
  assert.match(cmds, /"memory",\s*\/\/ T-080/);
  assert.match(cmds, /"memory-edit",\s*\/\/ T-083/);
  assert.match(cmds, /"memory-compact",\s*\/\/ T-084/);
});

// ----- 2. Compile smoke test -----------------------------------------

test("T-080: MemoryPanel.tsx compiles without TS errors", () => {
  const tmp = join(root, "tmp-r80-tsc");
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
  const output = (r.stdout || "") + (r.stderr || "");
  const hasFileError = /MemoryPanel\.tsx.*error/i.test(output);
  assert.equal(hasFileError, false,
    `MemoryPanel.tsx should compile cleanly; got: ${output}`);
});

// ----- 3. Docstring contract -----------------------------------------

test("T-080: MemoryPanel docstring lists the RPCs it depends on", () => {
  const src = readFileSync(panelPath, "utf-8");
  // The docstring above the component enumerates the RPCs
  // the App must wire. A future contributor who renames
  // or drops one will see this test fail and update the
  // docstring or the App wiring accordingly.
  assert.match(src, /memory\/list/);
  assert.match(src, /memory\/compact/);
});
