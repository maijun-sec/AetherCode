// T-6-10 (Phase 6.2): GrantsManager — 3-column layout +
// revoke + preset integration.
//
// 6 tests:
//   1. file exists + exports the public API
//   2. pure: filterGrants respects scope and category
//   3. pure: groupGrantsByScope orders session/project/user
//   4. pure: formatGrantLine produces "<scope> · <cat> · <DEC>"
//   5. source: GrantsManager wires onRevoke + onApplyPreset + 3 columns
//   6. tsc: GrantsManager.tsx compiles cleanly

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// ----- 1. File exists + public API -------------------------------------

test("T-6-10: GrantsManager.tsx exists and exports the manager + pure helpers", () => {
  const path = join(root, "src", "components", "permissions", "GrantsManager.tsx");
  assert.ok(existsSync(path), `expected ${path}`);
  const src = readFileSync(path, "utf-8");
  assert.match(src, /export const GrantsManager/);
  assert.match(src, /export default GrantsManager/);
  // Pure-helper exports.
  assert.match(src, /export function filterGrants/);
  assert.match(src, /export function groupGrantsByScope/);
  assert.match(src, /export function formatGrantLine/);
  // Type exports.
  assert.match(src, /export interface Grant\b/);
  assert.match(src, /export interface GrantsManagerProps/);
  // Calls into the PresetSelector.
  assert.match(src, /PresetSelector/);
});

// ----- 2. Pure: filterGrants -------------------------------------------

const tmp = join(root, "tmp-t6-10-tsc");
const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
if (existsSync(tmp)) {
  spawnSync(process.platform === "win32" ? "cmd" : "rm",
    process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", tmp] : ["-rf", tmp]);
}
mkdirSync(tmp, { recursive: true });
const shimPath = join(root, "src", "components", "permissions", "_t6_10_helpers.ts");
const shim = [
  `export { filterGrants, groupGrantsByScope, formatGrantLine } from "./GrantsManager.js";`,
  "",
].join("\n");
writeFileSync(shimPath, shim, "utf-8");

const tscArgs = [
  "--outDir", join(tmp, "out"), "--target", "ES2022", "--module", "ES2022",
  "--moduleResolution", "bundler", "--jsx", "react",
  "--esModuleInterop", "true", "--skipLibCheck", "true",
  "--rootDir", join(root, "src"),
];
{
  const r = spawnSync(`"${tscBin}"`, [...tscArgs, `"${shimPath}"`], { encoding: "utf-8", shell: true });
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
    process.exit(1);
  }
}
const helpers = await import(
  "file:///" + join(tmp, "out", "components", "permissions", "_t6_10_helpers.js").replace(/\\/g, "/")
);
const { filterGrants, groupGrantsByScope, formatGrantLine } = helpers;

const SAMPLE = [
  { id: "g1", scope: "session", scopeId: "ses-1", category: "fs.read",   decision: "allow", reason: "ok", createdAt: 3000 },
  { id: "g2", scope: "project", scopeId: "/x",   category: "shell.command.npm", decision: "allow", reason: "ok", createdAt: 2000 },
  { id: "g3", scope: "user",    scopeId: "u",    category: "shell.command.git", decision: "deny",  reason: "ok", createdAt: 1000 },
  { id: "g4", scope: "project", scopeId: "/x",   category: "fs.write",  decision: "deny",  reason: "ok", createdAt: 4000 },
];

test("T-6-10: filterGrants respects scope and category", () => {
  // scope=project (input order)
  assert.equal(filterGrants(SAMPLE, "project", "").map((g) => g.id).join(","), "g2,g4");
  // scope=user
  assert.equal(filterGrants(SAMPLE, "user", "").map((g) => g.id).join(","), "g3");
  // scope=session
  assert.equal(filterGrants(SAMPLE, "session", "").map((g) => g.id).join(","), "g1");
  // category substring (case-insensitive)
  assert.equal(filterGrants(SAMPLE, "all", "shell").map((g) => g.id).join(","), "g2,g3");
  assert.equal(filterGrants(SAMPLE, "project", "shell").map((g) => g.id).join(","), "g2");
  // No matches
  assert.equal(filterGrants(SAMPLE, "all", "nosuch").length, 0);
});

// ----- 3. Pure: groupGrantsByScope -------------------------------------

test("T-6-10: groupGrantsByScope orders session/project/user and sorts by createdAt desc", () => {
  const grouped = groupGrantsByScope(SAMPLE);
  const scopes = grouped.map((g) => g.scope);
  assert.deepEqual(scopes, ["session", "project", "user"]);
  // Project: g4 (4000) before g2 (2000)
  const projectGroup = grouped.find((g) => g.scope === "project");
  assert.equal(projectGroup.items[0].id, "g4");
  assert.equal(projectGroup.items[1].id, "g2");
});

// ----- 4. Pure: formatGrantLine ----------------------------------------

test("T-6-10: formatGrantLine is '<scope> · <cat> · <DEC>'", () => {
  assert.equal(formatGrantLine(SAMPLE[0]), "session · fs.read · ALLOW");
  assert.equal(formatGrantLine(SAMPLE[2]), "user · shell.command.git · DENY");
  assert.equal(formatGrantLine({ ...SAMPLE[0], category: "" }), "session · (uncategorized) · ALLOW");
});

// ----- 5. Source: GrantsManager wiring --------------------------------

test("T-6-10: GrantsManager.tsx wires onRevoke + onApplyPreset + 3 columns + keyboard", () => {
  const src = readFileSync(join(root, "src", "components", "permissions", "GrantsManager.tsx"), "utf-8");
  // 3 columns — list | details | filter+preset.
  assert.match(src, /list/);
  assert.match(src, /details/);
  assert.match(src, /preset/);
  // onRevoke + onApplyPreset callbacks.
  assert.match(src, /onRevoke/);
  assert.match(src, /onApplyPreset/);
  // Keyboard: r=revoke, ↑/↓=navigate, 1-4=scope filter.
  assert.match(src, /useInput/);
  assert.match(src, /key\.upArrow/);
  assert.match(src, /key\.downArrow/);
  // 'r' hotkey for revoke.
  assert.match(src, /input === "r"/);
  // Scope filter hotkeys 1-4.
  assert.match(src, /input === "1"/);
  assert.match(src, /input === "2"/);
  assert.match(src, /input === "3"/);
  assert.match(src, /input === "4"/);
});

// ----- 6. tsc: GrantsManager.tsx compiles cleanly ----------------------

test("T-6-10: TypeScript compile of GrantsManager.tsx is clean", () => {
  const r = spawnSync(`"${tscBin}"`, [
    ...tscArgs,
    '"' + join(root, "src", "components", "permissions", "GrantsManager.tsx") + '"',
  ], { encoding: "utf-8", shell: true });
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
  }
  assert.equal(r.status, 0, "tsc compile failed for T-6-10 GrantsManager");
});
