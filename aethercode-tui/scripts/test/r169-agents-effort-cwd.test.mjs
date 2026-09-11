// R169 (Phase 5 R3): tests for AgentSelector, EffortPicker, CwdSwitcher,
// ThemePicker enhancements, and the corresponding /agent-pick,
// /effort-pick, /cwd-pick slash commands.
//
// The TUI tests use source-code assertions (the same pattern as
// r42-themes.test.mjs / r166-themes.test.mjs) — the components
// are React/Ink modules that need ink-testing-library to render,
// and the existing test infra is `node --test`. Source assertions
// catch regressions in the *contract* (props, state, commands)
// without pulling ink-testing-library into the harness.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// ----- 1. Source existence + shape ---------------------------------

test("R169: AgentSelector.tsx exists and exports the picker + formatAgentLabel", () => {
  const path = join(root, "src", "components", "AgentSelector.tsx");
  assert.ok(existsSync(path), `expected ${path}`);
  const src = readFileSync(path, "utf-8");
  assert.match(src, /export interface AgentEntry/);
  assert.match(src, /export interface AgentSelectorProps/);
  assert.match(src, /export const AgentSelector/);
  assert.match(src, /export function formatAgentLabel/);
  // 1:1 port of deepagents-code's AgentSelectorScreen.java — the
  // current + default markers must be present.
  assert.match(src, /\(current\)/);
  assert.match(src, /\(default\)/);
  // Type-to-filter UX.
  assert.match(src, /TextInput/);
  // Ctrl+S to set default.
  assert.match(src, /ctrl.*s|ctrl \+ s/);
});

test("R169: EffortPicker.tsx exists and exports the picker + formatEffortLabel", () => {
  const path = join(root, "src", "components", "EffortPicker.tsx");
  assert.ok(existsSync(path));
  const src = readFileSync(path, "utf-8");
  assert.match(src, /export interface EffortEntry/);
  assert.match(src, /export interface EffortPickerProps/);
  assert.match(src, /export const EffortPicker/);
  assert.match(src, /export function formatEffortLabel/);
  // 1:1 port of EffortSelectorScreen.java — current + default markers.
  assert.match(src, /\(current\)/);
  assert.match(src, /\(default\)/);
  // 3-char padding for the label column matches the JTable column width.
  assert.match(src, /padEnd\(12\)/);
});

test("R169: CwdSwitcher.tsx exists and exports the picker + makeStatValidator", () => {
  const path = join(root, "src", "components", "CwdSwitcher.tsx");
  assert.ok(existsSync(path));
  const src = readFileSync(path, "utf-8");
  assert.match(src, /export interface CwdSwitcherProps/);
  assert.match(src, /export const CwdSwitcher/);
  assert.match(src, /export function makeStatValidator/);
  // Pure-function validator with injectable `stat` so tests don't
  // touch the filesystem.
  assert.match(src, /stat: \(p: string\)/);
  // Path-input UX (TextInput) + reload notice.
  assert.match(src, /TextInput/);
  assert.match(src, /reloads project-specific config/);
});

// ----- 2. ThemePicker enhancements ---------------------------------

test("R169: ThemePicker.tsx adds formatThemeLabel + filterThemes helpers", () => {
  const path = join(root, "src", "components", "ThemePicker.tsx");
  const src = readFileSync(path, "utf-8");
  assert.match(src, /export function formatThemeLabel/);
  assert.match(src, /export function filterThemes/);
  // Live preview hook stays — the (Round 2) onPreview prop.
  assert.match(src, /onPreview\?/);
  assert.match(src, /onPreview\(t\.name\)/);
});

// ----- 3. /agent-pick, /effort-pick, /cwd-pick in commands.ts -----

test("R169: commands.ts exposes agent-pick / effort-pick / cwd-pick", () => {
  const src = readFileSync(join(root, "src", "commands.ts"), "utf-8");
  // SLASH_COMMANDS list (line-shifted from r166, but the entries
  // are still in there).
  assert.match(src, /"agent-pick"/);
  assert.match(src, /"effort-pick"/);
  assert.match(src, /"cwd-pick"/);
  // SLASH_HELP strings.
  assert.match(src, /\/agent-pick/);
  assert.match(src, /\/effort-pick/);
  assert.match(src, /\/cwd-pick/);
  // The cases themselves.
  assert.match(src, /case "agent-pick":/);
  assert.match(src, /case "effort-pick":/);
  assert.match(src, /case "cwd-pick":/);
});

test("R169: commands.ts wires agent-pick / effort / cwd-pick to RPCs", () => {
  const src = readFileSync(join(root, "src", "commands.ts"), "utf-8");
  // /agent-pick-set-default forwards to agent/setDefault RPC.
  assert.match(src, /case "agent-pick-set-default":/);
  assert.match(src, /rpcMethod:\s+"agent\/setDefault"/);
  // /effort forwards to effort/set RPC.
  assert.match(src, /case "effort":/);
  assert.match(src, /rpcMethod:\s+"effort\/set"/);
  // /agent-pick /effort-pick /cwd-pick use the local token pattern
  // (the App mounts the modal).
  assert.match(src, /__AGENT_PICK__/);
  assert.match(src, /__EFFORT_PICK__/);
  assert.match(src, /__CWD_PICK__/);
});

// ----- 4. State integration ----------------------------------------

test("R169: state.ts adds agentPickerOpen / effortPickerOpen / cwdSwitcherOpen", () => {
  const src = readFileSync(join(root, "src", "state.ts"), "utf-8");
  // New state fields.
  assert.match(src, /agentPickerOpen: boolean/);
  assert.match(src, /effortPickerOpen: boolean/);
  assert.match(src, /cwdSwitcherOpen: boolean/);
  // New action types.
  assert.match(src, /setAgentPickerOpen/);
  assert.match(src, /setEffortPickerOpen/);
  assert.match(src, /setCwdSwitcherOpen/);
  // INITIAL defaults.
  assert.match(src, /agentPickerOpen: false/);
  assert.match(src, /effortPickerOpen: false/);
  assert.match(src, /cwdSwitcherOpen: false/);
  // Catalog + default-agent + default-effort fields.
  assert.match(src, /agents: Array<\{ name: string/);
  assert.match(src, /efforts: Array<\{ label: string/);
  assert.match(src, /defaultAgent: string \| null/);
  assert.match(src, /defaultEffort: string \| null/);
});

// ----- 5. Compile smoke -----------------------------------------

test("R169: TypeScript compile of the new components is clean", () => {
  const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
  const tmp = join(root, "tmp-r169-tsc");
  if (existsSync(tmp)) {
    spawnSync(process.platform === "win32" ? "cmd" : "rm",
      process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", tmp] : ["-rf", tmp]);
  }
  const tscArgs = [
    "--outDir", tmp, "--target", "ES2022", "--module", "ES2022",
    "--moduleResolution", "bundler", "--jsx", "react",
    "--esModuleInterop", "true", "--skipLibCheck", "true",
    "--rootDir", join(root, "src"),
  ];
  const files = [
    "components/AgentSelector.tsx",
    "components/EffortPicker.tsx",
    "components/CwdSwitcher.tsx",
    "components/ThemePicker.tsx",
    "commands.ts",
    "state.ts",
  ];
  const r = spawnSync(`"${tscBin}"`, [...tscArgs, ...files.map((f) => '"' + join(root, "src", f) + '"')], {
    encoding: "utf-8", shell: true,
  });
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
  }
  assert.equal(r.status, 0, "tsc compile failed for R169");
});
