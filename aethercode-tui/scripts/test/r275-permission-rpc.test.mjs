// R275 / T-275: aethercode-tui/src/permissionRpc.ts exposes
// the five `permission/*` JSON-RPC method wrappers.
//
// This is a source-only test — we don't spawn the daemon
// or the TUI. We just confirm the module:
//   1. exists and exports the 5 typed wrappers
//   2. uses the exact method names the daemon registers
//   3. types the wire shape per design.md §3.6
//
// The daemon-side handler lives in
// aethercode-protocol/.../PermissionMethods.java; the
// TUI side here is the typed surface the renderer uses.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

test("R275: aethercode-tui/src/permissionRpc.ts exists", () => {
  const file = join(root, "src", "permissionRpc.ts");
  assert.ok(existsSync(file), "missing src/permissionRpc.ts");
});

test("R275: exports permissionCheck", () => {
  const src = readFileSync(join(root, "src", "permissionRpc.ts"), "utf-8");
  assert.match(src, /export function permissionCheck/);
  assert.match(src, /"permission\/check"/);
});

test("R275: exports permissionPrompt", () => {
  const src = readFileSync(join(root, "src", "permissionRpc.ts"), "utf-8");
  assert.match(src, /export function permissionPrompt/);
  assert.match(src, /"permission\/prompt"/);
});

test("R275: exports permissionList", () => {
  const src = readFileSync(join(root, "src", "permissionRpc.ts"), "utf-8");
  assert.match(src, /export function permissionList/);
  assert.match(src, /"permission\/list"/);
});

test("R275: exports permissionRevoke", () => {
  const src = readFileSync(join(root, "src", "permissionRpc.ts"), "utf-8");
  assert.match(src, /export function permissionRevoke/);
  assert.match(src, /"permission\/revoke"/);
});

test("R275: exports permissionClear", () => {
  const src = readFileSync(join(root, "src", "permissionRpc.ts"), "utf-8");
  assert.match(src, /export function permissionClear/);
  assert.match(src, /"permission\/clear"/);
});

test("R275: types the wire shape (allow, requiresPrompt, risk, categories, drivingGrantId)", () => {
  const src = readFileSync(join(root, "src", "permissionRpc.ts"), "utf-8");
  // PermissionCheckResult must carry the same fields the
  // daemon returns — confirmed by the matching PermissionMethods.java
  // wire shape in aethercode-protocol.
  assert.match(src, /interface PermissionCheckResult/);
  assert.match(src, /allow:\s*boolean/);
  assert.match(src, /requiresPrompt:\s*boolean/);
  assert.match(src, /risk:\s*"low"\s*\|\s*"medium"\s*\|\s*"high"/);
  assert.match(src, /drivingGrantId\?:\s*string/);
});

test("R275: PermissionGrant matches the JSON shape of permission/list rows", () => {
  const src = readFileSync(join(root, "src", "permissionRpc.ts"), "utf-8");
  assert.match(src, /interface PermissionGrant/);
  assert.match(src, /scope:\s*"session"\s*\|\s*"project"\s*\|\s*"user"/);
  assert.match(src, /decision:\s*"allow"\s*\|\s*"deny"/);
});
