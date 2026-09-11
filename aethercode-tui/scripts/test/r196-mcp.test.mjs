// R196 (Phase 5 R4): pure-function tests for the MCP components.
//
// We use `node --test` with a runtime compile of just the helper
// files (no React/Ink deps needed for these pure functions). The
// helpers are:
//   - formatLoginState         (McpLogin.tsx)
//   - reconnectChoice          (McpReconnect.tsx)
//   - forceConfirmChoice       (McpReconnect.tsx)
//   - statusGlyph / statusColor (McpViewer.tsx)
//   - dismissServer / dismissReconnect / dismissCancel (McpViewer.tsx)
//
// The tests are runtime assertions, not source-code grep, so we
// get real coverage of the rules + the edge cases.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdirSync, writeFileSync, unlinkSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// ----- Compile the helpers to plain ESM -----------------------------
const tmp = join(root, "tmp-r196-tsc");
const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
if (existsSync(tmp)) {
  spawnSync(process.platform === "win32" ? "cmd" : "rm",
    process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", tmp] : ["-rf", tmp]);
}
mkdirSync(tmp, { recursive: true });
const shimPath = join(root, "src", "components", "_r196_helpers.ts");
const shim = [
  `export { formatLoginState } from "./McpLogin.js";`,
  `export { reconnectChoice, forceConfirmChoice } from "./McpReconnect.js";`,
  `export { statusGlyph, statusColor, dismissServer, dismissReconnect, dismissCancel } from "./McpViewer.js";`,
  "",
].join("\n");
writeFileSync(shimPath, shim, "utf-8");

const tscArgs = [
  "--outDir", join(tmp, "out"), "--target", "ES2022", "--module", "ES2022",
  "--moduleResolution", "bundler", "--jsx", "react",
  "--esModuleInterop", "true", "--skipLibCheck", "true",
  "--rootDir", join(root, "src"),
];
const r = spawnSync(`"${tscBin}"`, [...tscArgs, `"${shimPath}"`], { encoding: "utf-8", shell: true });
if (r.status !== 0) {
  console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
  process.exit(1);
}
const helpers = await import(
  "file:///" + join(tmp, "out", "components", "_r196_helpers.js").replace(/\\/g, "/")
);
const { formatLoginState, reconnectChoice, forceConfirmChoice,
  statusGlyph, statusColor, dismissServer, dismissReconnect, dismissCancel } = helpers;

// ----- 1. formatLoginState -----------------------------------------

test("R196: formatLoginState — starting", () => {
  assert.equal(formatLoginState("starting", "github"), "mcp login github: starting…");
});

test("R196: formatLoginState — awaiting-authorize", () => {
  assert.equal(formatLoginState("awaiting-authorize", "github"), "mcp login github: waiting for callback");
});

test("R196: formatLoginState — device-code", () => {
  assert.equal(formatLoginState("device-code", "github"), "mcp login github: device code");
});

test("R196: formatLoginState — success", () => {
  assert.equal(formatLoginState("success", "github"), "mcp login github: success");
});

test("R196: formatLoginState — error", () => {
  assert.equal(formatLoginState("error", "github"), "mcp login github: failed");
});

// ----- 2. reconnectChoice ------------------------------------------

test("R196: reconnectChoice — confirmed → reconnect", () => {
  assert.equal(reconnectChoice(true), "reconnect");
});

test("R196: reconnectChoice — cancelled → later", () => {
  assert.equal(reconnectChoice(false), "later");
});

// ----- 3. forceConfirmChoice ---------------------------------------

test("R196: forceConfirmChoice — confirmed → true", () => {
  assert.equal(forceConfirmChoice(true), true);
});

test("R196: forceConfirmChoice — cancelled → false", () => {
  assert.equal(forceConfirmChoice(false), false);
});

// ----- 4. statusGlyph -----------------------------------------------

test("R196: statusGlyph — ok → ✓", () => {
  assert.equal(statusGlyph("ok"), "✓");
});

test("R196: statusGlyph — unauthenticated → ⚠", () => {
  assert.equal(statusGlyph("unauthenticated"), "⚠");
});

test("R196: statusGlyph — awaiting-reconnect → ○", () => {
  assert.equal(statusGlyph("awaiting-reconnect"), "○");
});

test("R196: statusGlyph — disabled → ⏸", () => {
  assert.equal(statusGlyph("disabled"), "⏸");
});

test("R196: statusGlyph — error → ✗", () => {
  assert.equal(statusGlyph("error"), "✗");
});

// ----- 5. statusColor -----------------------------------------------

test("R196: statusColor — ok → green", () => {
  assert.equal(statusColor("ok"), "green");
});

test("R196: statusColor — unauthenticated → yellow", () => {
  assert.equal(statusColor("unauthenticated"), "yellow");
});

test("R196: statusColor — error → red", () => {
  assert.equal(statusColor("error"), "red");
});

test("R196: statusColor — awaiting-reconnect → gray", () => {
  assert.equal(statusColor("awaiting-reconnect"), "gray");
});

test("R196: statusColor — disabled → gray", () => {
  assert.equal(statusColor("disabled"), "gray");
});

// ----- 6. dismiss helpers ------------------------------------------

test("R196: dismissServer — builds server dismissal", () => {
  const d = dismissServer("github");
  assert.equal(d.kind, "server");
  assert.equal(d.name, "github");
});

test("R196: dismissReconnect — builds reconnect dismissal", () => {
  const d = dismissReconnect();
  assert.equal(d.kind, "reconnect");
});

test("R196: dismissCancel — builds cancel dismissal", () => {
  const d = dismissCancel();
  assert.equal(d.kind, "cancel");
});

// ----- Cleanup -----------------------------------------------
try { unlinkSync(shimPath); } catch { /* ignore */ }
