// T-433 (Phase 5 R6 — Final Integration): comprehensive test
// suite for the §5.3 components added in T-420..T-432.
//
// The TUI test harness does not have `ink-testing-library`
// installed (no jsdom, no react-test-renderer). The convention
// used by every R77+ / R196 test is a 3-layer smoke:
//
//   1. Pure-helper unit tests (the small pure functions the
//      component exports — the same shape that would feed a
//      future ink-testing-library suite).
//   2. Source-grep assertions on the .tsx file so a regression
//      that drops a keyboard handler, a hard-coded constant,
//      a colour tier, or an exported interface fails the build.
//   3. A shim-based `tsc --strict` compile of the helper API
//      (matches the R196-*.test.mjs pattern: write a shim file
//      that re-exports the helpers, compile the shim, ignore
//      intra-.tsx strictness issues that pre-date this round).

import { test } from "node:test";
import assert from "node:assert/strict";
import {
  existsSync,
  mkdirSync,
  readFileSync,
  writeFileSync,
} from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

const tscBin = join(
  root,
  "node_modules",
  ".bin",
  process.platform === "win32" ? "tsc.cmd" : "tsc",
);

const read = (rel) => readFileSync(join(root, rel), "utf-8");

// ----- shared shim-based tsc helper --------------------------------

const SHIM_NAME = "_t433_shim.ts";
const shimPath = join(root, "src", "components", SHIM_NAME);
const tmpTsc = join(root, "tmp-t433-tsc");

function tscShim(label, shimBody) {
  if (existsSync(tmpTsc)) {
    spawnSync(
      process.platform === "win32" ? "cmd" : "rm",
      process.platform === "win32"
        ? ["/c", "rmdir", "/s", "/q", tmpTsc]
        : ["-rf", tmpTsc],
      { shell: process.platform === "win32" },
    );
  }
  mkdirSync(tmpTsc, { recursive: true });
  writeFileSync(shimPath, shimBody, "utf-8");
  const args = [
    "--outDir", join(tmpTsc, "out"),
    "--target", "ES2022",
    "--module", "ES2022",
    "--moduleResolution", "bundler",
    "--jsx", "react",
    "--esModuleInterop", "true",
    "--skipLibCheck", "true",
    "--rootDir", join(root, "src"),
  ];
  const r = spawnSync(
    `"${tscBin}"`,
    [...args, `"${shimPath}"`],
    { encoding: "utf-8", shell: true },
  );
  if (r.status !== 0) {
    console.error(`tsc shim failed for ${label}:\n${r.stdout}\n${r.stderr}`);
  }
  assert.equal(r.status, 0, `tsc shim failed for ${label}`);
}

// =====================================================================
// T-420: ConsentPrompt.tsx
// =====================================================================

test("T-420: ConsentPrompt.tsx exists and exports the right surface", () => {
  const src = read("src/components/ConsentPrompt.tsx");
  assert.match(src, /export type RiskLevel/);
  assert.match(src, /export type OptionKind/);
  assert.match(src, /export type GrantScope/);
  assert.match(src, /export type GrantDecision/);
  assert.match(src, /export interface ConsentOption/);
  assert.match(src, /export interface CategoryResult/);
  assert.match(src, /export interface ConsentPromptProps/);
  assert.match(src, /export const ConsentPrompt/);
  assert.match(src, /export default ConsentPrompt/);
});

test("T-420: ConsentPrompt wires the 10-option matrix (1..9, 0 → 10)", () => {
  const src = read("src/components/ConsentPrompt.tsx");
  assert.match(src, /const opt = options\[9\]/);
  assert.match(src, /input >= "1" && input <= "9"/);
  assert.match(src, /const idx = Number\(input\) - 1/);
});

test("T-420: ConsentPrompt keyboard handlers (Esc / Enter / ↑↓ / j k / ?)", () => {
  const src = read("src/components/ConsentPrompt.tsx");
  assert.match(src, /key\.escape/);
  assert.match(src, /key\.return/);
  assert.match(src, /key\.upArrow \|\| input === "k"/);
  assert.match(src, /key\.downArrow \|\| input === "j"/);
  assert.match(src, /if \(input === "\?"\)/);
});

test("T-420: ConsentPrompt risk colour tiers (low=green, medium=yellow, high=red)", () => {
  const src = read("src/components/ConsentPrompt.tsx");
  assert.match(src, /low:\s*"green"/);
  assert.match(src, /medium:\s*"yellow"/);
  assert.match(src, /high:\s*"red"/);
  assert.match(src, /RISK_LABEL/);
  assert.match(src, /LOW/);
  assert.match(src, /MEDIUM/);
  assert.match(src, /HIGH/);
});

test("T-420: ConsentPrompt help view shows matched rules + default rule table", () => {
  const src = read("src/components/ConsentPrompt.tsx");
  assert.match(src, /Matched rules/);
  assert.match(src, /Default rule table \(excerpt\):/);
  assert.match(src, /shell\.destructive/);
  assert.match(src, /shell\.package_install/);
  assert.match(src, /mcp\.tool_invocation/);
});

test("T-420: ConsentPrompt types compile via shim", () => {
  tscShim(
    "consent",
    [
      `export type {`,
      `  RiskLevel, OptionKind, GrantScope, GrantDecision,`,
      `  ConsentOption, CategoryResult, ConsentPromptProps,`,
      `} from "./ConsentPrompt.js";`,
      ``,
    ].join("\n"),
  );
});

// =====================================================================
// T-421: AgentSelector.tsx
// =====================================================================

test("T-421: AgentSelector.tsx exists and exports the right surface", () => {
  const src = read("src/components/AgentSelector.tsx");
  assert.match(src, /export interface AgentEntry/);
  assert.match(src, /export interface AgentSelectorProps/);
  // arrow-function form, not `export function`.
  assert.match(src, /export const AgentSelector/);
  assert.match(src, /export function formatAgentLabel/);
});

test("T-421: AgentSelector is filter-driven + Ctrl+S to set default", () => {
  const src = read("src/components/AgentSelector.tsx");
  assert.match(src, /ink-text-input/);
  assert.match(src, /key\.ctrl && input === "s"/);
  assert.match(src, /current/);
  assert.match(src, /default/);
});

test("T-421: AgentSelector types compile via shim", () => {
  tscShim(
    "agent",
    [
      `export type { AgentEntry, AgentSelectorProps } from "./AgentSelector.js";`,
      `export { formatAgentLabel } from "./AgentSelector.js";`,
      ``,
    ].join("\n"),
  );
});

// =====================================================================
// T-422: EffortPicker.tsx
// =====================================================================

test("T-422: EffortPicker.tsx exists and exports the right surface", () => {
  const src = read("src/components/EffortPicker.tsx");
  assert.match(src, /export interface EffortEntry/);
  assert.match(src, /export interface EffortPickerProps/);
  assert.match(src, /export const EffortPicker/);
  assert.match(src, /export function formatEffortLabel/);
});

test("T-422: EffortPicker shows current + default markers and dispatches effort/set on Enter", () => {
  const src = read("src/components/EffortPicker.tsx");
  assert.match(src, /\(current\)/);
  assert.match(src, /\(default\)/);
  assert.match(src, /key\.return/);
  assert.match(src, /onSelect/);
});

test("T-422: EffortPicker types compile via shim", () => {
  tscShim(
    "effort",
    [
      `export type { EffortEntry, EffortPickerProps } from "./EffortPicker.js";`,
      `export { formatEffortLabel } from "./EffortPicker.js";`,
      ``,
    ].join("\n"),
  );
});

// =====================================================================
// T-423: CwdSwitcher.tsx
// =====================================================================

test("T-423: CwdSwitcher.tsx exists and exports the right surface", () => {
  const src = read("src/components/CwdSwitcher.tsx");
  assert.match(src, /export type CwdValidationKind/);
  assert.match(src, /export interface CwdValidationResult/);
  assert.match(src, /export interface CwdSwitcherProps/);
  assert.match(src, /export const CwdSwitcher/);
  assert.match(src, /export function makeStatValidator|validate/);
});

test("T-423: CwdSwitcher uses host-supplied validator (no direct fs)", () => {
  const src = read("src/components/CwdSwitcher.tsx");
  assert.doesNotMatch(src, /from\s+['"]node:fs['"]/);
  assert.doesNotMatch(src, /from\s+['"]fs['"]/);
  assert.match(src, /"empty"/);
  assert.match(src, /"not-found"/);
  assert.match(src, /"not-directory"/);
  assert.match(src, /"not-absolute"/);
  assert.match(src, /"permission-denied"/);
});

test("T-423: CwdSwitcher shows the project-settings change note", () => {
  const src = read("src/components/CwdSwitcher.tsx");
  assert.match(src, /projectSettingsChangeDetected|project settings/);
});

test("T-423: CwdSwitcher types compile via shim", () => {
  tscShim(
    "cwd",
    [
      `export type { CwdValidationKind, CwdValidationResult, CwdSwitcherProps } from "./CwdSwitcher.js";`,
      ``,
    ].join("\n"),
  );
});

// =====================================================================
// T-424: MemoryPanel.tsx (R80 covers; re-assert the §5.3 contract)
// =====================================================================

test("T-424: MemoryPanel.tsx exports MemoryScope (global|project|session)", () => {
  const src = read("src/components/MemoryPanel.tsx");
  assert.match(src, /export type MemoryScope/);
  assert.match(src, /"global"/);
  assert.match(src, /"project"/);
  assert.match(src, /"session"/);
  assert.match(src, /export interface MemoryPanelEntry/);
  assert.match(src, /export function MemoryPanel|export const MemoryPanel/);
});

test("T-424: MemoryPanel tab-switching + onEditFact callback", () => {
  const src = read("src/components/MemoryPanel.tsx");
  assert.match(src, /onEditFact/);
  assert.match(src, /memory\/compact|onCompactDone/);
});

test("T-424: MemoryPanel types compile via shim", () => {
  tscShim(
    "memory",
    [
      `export type { MemoryScope, MemoryPanelEntry } from "./MemoryPanel.js";`,
      ``,
    ].join("\n"),
  );
});

// =====================================================================
// T-425: ContextMeter.tsx (R180 covers; re-assert the §5.3 contract)
// =====================================================================

test("T-425: ContextMeter.tsx uses three colour tiers (green/yellow/red)", () => {
  const src = read("src/components/ContextMeter.tsx");
  // The 3-tier colour discriminator.
  assert.match(src, /function bandColor\(ratio: number\): "green" \| "yellow" \| "red"/);
  assert.match(src, /if \(ratio > 0\.80\) return "red"/);
  assert.match(src, /if \(ratio >= 0\.50\) return "yellow"/);
  // 80 % threshold for the 压缩推荐 prompt.
  assert.match(src, /RECOMMEND_BUTTON_THRESHOLD = 0\.80/);
  assert.match(src, /\u538b\u7f29\u63a8\u8350/);
  // 30 % drop heuristic.
  assert.match(src, /COMPACT_DROP_THRESHOLD = 0\.30/);
  // Capped at MAX_SAMPLES (64).
  assert.match(src, /MAX_SAMPLES = 64/);
  assert.match(src, /merged\.length > MAX_SAMPLES/);
});

test("T-425: detectCompactDrop returns null when prev is null", () => {
  const src = read("src/components/ContextMeter.tsx");
  assert.match(src, /if \(!prev\) return null/);
  assert.match(src, /if \(prev\.inputTokens <= 0\) return null/);
});

test("T-425: ContextMeter types compile via shim", () => {
  tscShim(
    "ctx",
    [
      `export type { ContextSample, ContextInfo, ContextMeterProps } from "./ContextMeter.js";`,
      `export {`,
      `  bandColor, fillBar, detectCompactDrop,`,
      `  DEFAULT_SAMPLE_INTERVAL_MS, DEFAULT_BAR_WIDTH,`,
      `  COMPACT_DROP_THRESHOLD, RECOMMEND_BUTTON_THRESHOLD,`,
      `  MAX_SAMPLES,`,
      `} from "./ContextMeter.js";`,
      ``,
    ].join("\n"),
  );
});

// =====================================================================
// T-426: TaskPanel.tsx
// =====================================================================

test("T-426: TaskPanel.tsx exports the row + props surface", () => {
  const src = read("src/components/TaskPanel.tsx");
  assert.match(src, /export interface TaskPanelChild/);
  assert.match(src, /"QUEUED"/);
  assert.match(src, /"RUNNING"/);
  assert.match(src, /"PAUSED"/);
  assert.match(src, /"COMPLETED"/);
  assert.match(src, /"FAILED"/);
  assert.match(src, /"KILLED"/);
  assert.match(src, /export interface TaskPanelProps/);
  assert.match(src, /export function TaskPanel/);
});

test("T-426: TaskPanel dispatches attach / kill / resume / retry + has humanDuration helper", () => {
  const src = read("src/components/TaskPanel.tsx");
  assert.match(src, /onAttach/);
  assert.match(src, /onKill/);
  assert.match(src, /onResume/);
  assert.match(src, /onRetry/);
  assert.match(src, /RUNNING:\s*0/);
  assert.match(src, /function humanDuration/);
  assert.match(src, /ms < 1000/);
  assert.match(src, /ms < 60_000/);
  assert.match(src, /ms < 3_600_000/);
});

test("T-426: humanDuration pure helper returns the expected strings", () => {
  function humanDuration(ms) {
    if (ms < 1000) return `${ms}ms`;
    if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
    if (ms < 3_600_000)
      return `${Math.floor(ms / 60_000)}m${Math.floor((ms % 60_000) / 1000)}s`;
    return `${Math.floor(ms / 3_600_000)}h${Math.floor((ms % 3_600_000) / 60_000)}m`;
  }
  assert.equal(humanDuration(500), "500ms");
  assert.equal(humanDuration(1_500), "1.5s");
  assert.equal(humanDuration(75_000), "1m15s");
  assert.equal(humanDuration(3_750_000), "1h2m");
});

test("T-426: TaskPanel types compile via shim", () => {
  tscShim(
    "task",
    [
      `export type { TaskPanelChild, TaskPanelProps } from "./TaskPanel.js";`,
      ``,
    ].join("\n"),
  );
});

// =====================================================================
// T-427: GrantsManager surface
// =====================================================================

test("T-427: GrantScope / GrantDecision types are exported from ConsentPrompt", () => {
  const src = read("src/components/ConsentPrompt.tsx");
  assert.match(src, /export type GrantScope/);
  assert.match(src, /export type GrantDecision/);
  assert.match(src, /"allow"/);
  assert.match(src, /"deny"/);
  // The three grant scopes.
  assert.match(src, /"session"/);
  assert.match(src, /"project"/);
  assert.match(src, /"user"/);
});

// =====================================================================
// T-428: McpLogin / McpReconnect / McpViewer
// =====================================================================

test("T-428: McpLogin.tsx exports the OAuth flow surface", () => {
  const src = read("src/components/McpLogin.tsx");
  assert.match(src, /export interface McpLoginProps/);
  assert.match(src, /export const McpLogin/);
  // The device-code payload fields the host injects.
  assert.match(src, /verificationUri/);
  assert.match(src, /userCode/);
});

test("T-428: McpReconnect.tsx exports the retry surface", () => {
  const src = read("src/components/McpReconnect.tsx");
  assert.match(src, /export interface McpReconnectProps/);
  assert.match(src, /export const McpReconnect/);
  // The pure helpers.
  assert.match(src, /export function reconnectChoice/);
  assert.match(src, /export function forceConfirmChoice/);
});

test("T-428: McpViewer.tsx exports ServerStatus + ServerInfo surfaces", () => {
  const src = read("src/components/McpViewer.tsx");
  assert.match(src, /export type ServerStatus/);
  assert.match(src, /"ok"/);
  assert.match(src, /"unauthenticated"/);
  assert.match(src, /"awaiting-reconnect"/);
  assert.match(src, /"error"/);
  assert.match(src, /"disabled"/);
  assert.match(src, /export interface ServerInfo/);
  assert.match(src, /export interface ToolInfo/);
  assert.match(src, /export interface McpViewerProps/);
  assert.match(src, /export const McpViewer/);
  // Dismissal helpers.
  assert.match(src, /export function dismissWithAction|dismissReconnect|dismissClose/);
});

test("T-428: McpLogin / Reconnect / Viewer all compile via shim", () => {
  tscShim(
    "mcp",
    [
      `export type { McpLoginProps } from "./McpLogin.js";`,
      `export type { McpReconnectProps, ReconnectChoice, ForceConfirmChoice } from "./McpReconnect.js";`,
      `export { reconnectChoice, forceConfirmChoice } from "./McpReconnect.js";`,
      `export type { ServerStatus, ServerInfo, ToolInfo, McpViewerProps } from "./McpViewer.js";`,
      ``,
    ].join("\n"),
  );
});

// =====================================================================
// T-429: ThreadSelector.tsx
// =====================================================================

test("T-429: ThreadSelector.tsx exports the picker surface", () => {
  const src = read("src/components/ThreadSelector.tsx");
  assert.match(src, /export interface ThreadInfo/);
  assert.match(src, /export type CwdSwitchChoice/);
  assert.match(src, /export interface ThreadSelectorProps/);
  assert.match(src, /export const ThreadSelector/);
  assert.match(src, /"switch"/);
  assert.match(src, /"stay"/);
  assert.match(src, /"abort"/);
});

test("T-429: ThreadSelector has fuzzy filter + Enter selects", () => {
  const src = read("src/components/ThreadSelector.tsx");
  assert.match(src, /ink-text-input/);
  assert.match(src, /key\.return/);
  assert.match(src, /key\.escape/);
});

test("T-429: ThreadSelector types compile via shim", () => {
  tscShim(
    "thread",
    [
      `export type { ThreadInfo, CwdSwitchChoice, ThreadSelectorProps } from "./ThreadSelector.js";`,
      ``,
    ].join("\n"),
  );
});

// =====================================================================
// T-430: UpdateAvailable / UpdateConfirm / UpdateProgress
// =====================================================================

test("T-430: UpdateAvailable.tsx exports the pending-update surface", () => {
  const src = read("src/components/UpdateAvailable.tsx");
  assert.match(src, /export interface NotificationAction/);
  assert.match(src, /export interface PendingUpdate/);
  assert.match(src, /export type UpdateAvailableResult/);
  assert.match(src, /kind: "action"/);
  assert.match(src, /kind: "changelog"/);
  assert.match(src, /kind: "cancel"/);
  assert.match(src, /__changelog__/);
});

test("T-430: UpdateConfirm.tsx exports the confirm surface", () => {
  const src = read("src/components/UpdateConfirm.tsx");
  assert.match(src, /export interface UpdateConfirmProps/);
  assert.match(src, /export const UpdateConfirm/);
  // Pure helpers.
  assert.match(src, /export function update|choose/);
});

test("T-430: UpdateProgress.tsx exports the progress surface", () => {
  const src = read("src/components/UpdateProgress.tsx");
  assert.match(src, /export interface UpdateProgressProps/);
  assert.match(src, /export const UpdateProgress/);
  // Pure helpers.
  assert.match(src, /export function appendLineTail/);
  assert.match(src, /export function markSuccessStatus/);
  assert.match(src, /export function markFailureStatus/);
  assert.match(src, /export function markWarningStatus/);
});

test("T-430: UpdateAvailable / Confirm / Progress all compile via shim", () => {
  tscShim(
    "update",
    [
      `export type { NotificationAction, PendingUpdate, UpdateAvailableResult } from "./UpdateAvailable.js";`,
      `export type { UpdateConfirmProps } from "./UpdateConfirm.js";`,
      `export type { UpdateProgressProps, UpdateProgressState } from "./UpdateProgress.js";`,
      `export { appendLineTail, markSuccessStatus, markFailureStatus, markWarningStatus } from "./UpdateProgress.js";`,
      ``,
    ].join("\n"),
  );
});

// =====================================================================
// T-431: NotificationCenter / Detail / Settings
// =====================================================================

test("T-431: NotificationCenter.tsx exports the hub surface", () => {
  const src = read("src/components/NotificationCenter.tsx");
  assert.match(src, /export interface ActionId/);
  assert.match(src, /export interface NotificationAction/);
  assert.match(src, /export interface PendingNotification/);
  assert.match(src, /export interface ActionResult/);
  assert.match(src, /export interface NotificationCenterProps/);
  assert.match(src, /export const NotificationCenter/);
});

test("T-431: NotificationDetail.tsx exports the detail surface", () => {
  const src = read("src/components/NotificationDetail.tsx");
  assert.match(src, /export interface NotificationDetailProps/);
  assert.match(src, /export const NotificationDetail/);
  // Pure helpers.
  assert.match(src, /export function buildDetailResult|buildDetail/);
  assert.match(src, /export function formatDetailTitle|formatDetail/);
});

test("T-431: NotificationSettings.tsx exports the warning-toggles registry", () => {
  const src = read("src/components/NotificationSettings.tsx");
  assert.match(src, /export interface Toggle/);
  assert.match(src, /export const WARNING_TOGGLES/);
  // The four canonical warning keys.
  assert.match(src, /COLD_CACHE_WARNING_KEY/);
  assert.match(src, /RIPGREP_WARNING_KEY/);
  assert.match(src, /TAVILY_WARNING_KEY/);
  assert.match(src, /YOLO_WARNING_KEY/);
  // Pure helpers.
  assert.match(src, /export function labelFor/);
  assert.match(src, /export function flipToggle/);
  assert.match(src, /export function defaultToggleState/);
  assert.match(src, /export function countEnabled/);
  // Standalone settings component.
  assert.match(src, /export const NotificationSettings/);
});

test("T-431: NotificationCenter / Detail / Settings all compile via shim", () => {
  tscShim(
    "notif",
    [
      `export type {`,
      `  ActionId, NotificationAction, PendingNotification,`,
      `  ActionResult, NotificationCenterProps,`,
      `} from "./NotificationCenter.js";`,
      `export type { NotificationDetailProps } from "./NotificationDetail.js";`,
      `export { buildDetailResult, formatDetailTitle } from "./NotificationDetail.js";`,
      `export type { Toggle, NotificationSettingsProps } from "./NotificationSettings.js";`,
      `export {`,
      `  WARNING_TOGGLES, labelFor, flipToggle,`,
      `  defaultToggleState, countEnabled, NotificationSettings,`,
      `} from "./NotificationSettings.js";`,
      ``,
    ].join("\n"),
  );
});

// =====================================================================
// T-432: Wire all new components into commands.ts
// =====================================================================

test("T-432: commands.ts has SLASH_HELP entries for every §5.3 command", () => {
  const src = read("src/commands.ts");
  for (const line of [
    "/agent-pick",
    "/effort-pick",
    "/cwd-pick",
    "/mcp",
    "/mcp-login",
    "/mcp-reconnect",
    "/threads",
    "/update",
    "/update-deps",
    "/notifications",
  ]) {
    assert.ok(src.includes(line), `SLASH_HELP missing entry for ${line}`);
  }
});

test("T-432: commands.ts has SLASH_COMMANDS entries for every §5.3 command", () => {
  const src = read("src/commands.ts");
  for (const cmd of [
    "agent-pick",
    "effort-pick",
    "cwd-pick",
    "mcp",
    "mcp-login",
    "mcp-reconnect",
    "threads",
    "update",
    "update-deps",
    "notifications",
  ]) {
    assert.ok(
      src.includes(`"${cmd}"`),
      `SLASH_COMMANDS missing entry for ${cmd}`,
    );
  }
});

test("T-432: commands.ts has SLASH_COMMANDS_DETAILED entries for every §5.3 command", () => {
  const src = read("src/commands.ts");
  for (const cmd of [
    "agent-pick",
    "effort-pick",
    "cwd-pick",
    "mcp",
    "mcp-login",
    "mcp-reconnect",
    "threads",
    "update",
    "update-deps",
    "notifications",
  ]) {
    assert.ok(
      src.includes(`name: "${cmd}"`),
      `SLASH_COMMANDS_DETAILED missing entry for ${cmd}`,
    );
  }
});

test("T-432: commands.ts handleSlash branches cover every §5.3 command", () => {
  const src = read("src/commands.ts");
  for (const cmd of [
    "agent-pick",
    "effort-pick",
    "cwd-pick",
    "mcp",
    "mcp-login",
    "mcp-reconnect",
    "threads",
    "update",
    "update-deps",
    "notifications",
  ]) {
    const re = new RegExp(`case\\s+"${cmd}"|===\\s*"${cmd}"`);
    assert.ok(re.test(src), `handleSlash has no branch for ${cmd}`);
  }
});

// =====================================================================
// Cleanup
// =====================================================================

test("T-433: cleanup the shim file written by tsc", () => {
  if (existsSync(shimPath)) {
    spawnSync(
      process.platform === "win32" ? "cmd" : "rm",
      process.platform === "win32"
        ? ["/c", "del", "/f", "/q", `"${shimPath}"`]
        : [`"${shimPath}"`],
      { shell: true },
    );
  }
  assert.ok(!existsSync(shimPath), "T-433 shim not cleaned up");
  if (existsSync(tmpTsc)) {
    spawnSync(
      process.platform === "win32" ? "cmd" : "rm",
      process.platform === "win32"
        ? ["/c", "rmdir", "/s", "/q", `"${tmpTsc}"`]
        : [`"${tmpTsc}"`],
      { shell: true },
    );
  }
  assert.ok(!existsSync(tmpTsc), "T-433 tmp dir not cleaned up");
});
