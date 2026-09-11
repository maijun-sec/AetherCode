// T-6-09 (Phase 6.2): ConsentModal — 10-option modal with
// category-aware wildcard pair.
//
// 8 tests:
//   1. file exists + exports the public API
//   2. pure: buildOptions("shell.command.npm") returns 10 options
//   3. pure: buildOptions("fs.write") returns 8 options (no wildcard)
//   4. pure: shouldShowWildcard matches shell.command.* only
//   5. pure: deriveWildcardSubCategory strips shell.command. prefix
//   6. pure: option 1/2 are allow/deny once; option 7/8 are user scope
//   7. source: ConsentModal renders the 8 or 10 options + uses ↑/↓/Enter/Esc
//   8. tsc: ConsentModal.tsx compiles cleanly with the project tsconfig

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// ----- 1. File exists + public API -------------------------------------

test("T-6-09: ConsentModal.tsx exists and exports the 10-option modal + pure helpers", () => {
  const path = join(root, "src", "components", "consent", "ConsentModal.tsx");
  assert.ok(existsSync(path), `expected ${path}`);
  const src = readFileSync(path, "utf-8");
  assert.match(src, /export const ConsentModal/);
  assert.match(src, /export default ConsentModal/);
  // Pure-helper exports (used by tests + desktop port).
  assert.match(src, /export function buildOptions/);
  assert.match(src, /export function shouldShowWildcard/);
  assert.match(src, /export function deriveWildcardSubCategory/);
  // Types exported.
  assert.match(src, /export interface ConsentOption/);
  assert.match(src, /export type GrantScope/);
  assert.match(src, /export type GrantDecision/);
});

// ----- 2. Pure: buildOptions("shell.command.npm") → 10 -----------------

const tmp = join(root, "tmp-t6-09-tsc");
const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
if (existsSync(tmp)) {
  spawnSync(process.platform === "win32" ? "cmd" : "rm",
    process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", tmp] : ["-rf", tmp]);
}
mkdirSync(tmp, { recursive: true });
const shimPath = join(root, "src", "components", "consent", "_t6_09_helpers.ts");
const shim = [
  `export { buildOptions, shouldShowWildcard, deriveWildcardSubCategory } from "./ConsentModal.js";`,
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
  "file:///" + join(tmp, "out", "components", "consent", "_t6_09_helpers.js").replace(/\\/g, "/")
);
const { buildOptions, shouldShowWildcard, deriveWildcardSubCategory } = helpers;

test("T-6-09: buildOptions('shell.command.npm') returns 10 options (1-8 + wildcard pair)", () => {
  const opts = buildOptions("shell.command.npm");
  assert.equal(opts.length, 10);
  assert.equal(opts[0].index, 1);
  assert.equal(opts[7].index, 8);
  assert.equal(opts[8].index, 9);
  assert.equal(opts[9].index, 10);
  // 9/10 are flagged as wildcard.
  assert.equal(opts[8].wildcardSubCategory, "npm");
  assert.equal(opts[9].wildcardSubCategory, "npm");
  // 9 is allow, 10 is deny.
  assert.equal(opts[8].decision, "allow");
  assert.equal(opts[9].decision, "deny");
});

// ----- 3. Pure: buildOptions("fs.write") → 8 ---------------------------

test("T-6-09: buildOptions('fs.write') returns 8 options (no wildcard for non-shell)", () => {
  const opts = buildOptions("fs.write");
  assert.equal(opts.length, 8);
  for (const o of opts) {
    assert.equal(o.wildcardSubCategory, undefined);
  }
});

// ----- 4. Pure: shouldShowWildcard matches shell.command.* -------------

test("T-6-09: shouldShowWildcard is true for shell.command.* and false otherwise", () => {
  assert.equal(shouldShowWildcard("shell.command"), true);
  assert.equal(shouldShowWildcard("shell.command.npm"), true);
  assert.equal(shouldShowWildcard("shell.command.git push --force"), true);
  assert.equal(shouldShowWildcard("SHELL.COMMAND.NPM"), true);
  assert.equal(shouldShowWildcard("fs.write"), false);
  assert.equal(shouldShowWildcard("fs.read"), false);
  assert.equal(shouldShowWildcard("search.grep"), false);
  assert.equal(shouldShowWildcard(""), false);
});

// ----- 5. Pure: deriveWildcardSubCategory ------------------------------

test("T-6-09: deriveWildcardSubCategory strips the shell.command. prefix", () => {
  assert.equal(deriveWildcardSubCategory("shell.command.npm"), "npm");
  assert.equal(deriveWildcardSubCategory("shell.command.git push --force"), "git push --force");
  // No sub-category → null.
  assert.equal(deriveWildcardSubCategory("shell.command"), null);
  // Non-shell → null.
  assert.equal(deriveWildcardSubCategory("fs.write"), null);
});

// ----- 6. Pure: option shape & scope -----------------------------------

test("T-6-09: options 1/2 are session scope; 5/6 are project; 7/8 are user", () => {
  const opts = buildOptions("shell.command.npm");
  // 1, 2: session
  assert.equal(opts[0].scope, "session");
  assert.equal(opts[1].scope, "session");
  // 3, 4: session (rest of session)
  assert.equal(opts[2].scope, "session");
  assert.equal(opts[3].scope, "session");
  // 5, 6: project
  assert.equal(opts[4].scope, "project");
  assert.equal(opts[5].scope, "project");
  // 7, 8: user
  assert.equal(opts[6].scope, "user");
  assert.equal(opts[7].scope, "user");
  // Decisions: 1,3,5,7 = allow; 2,4,6,8 = deny.
  for (const i of [0, 2, 4, 6]) assert.equal(opts[i].decision, "allow");
  for (const i of [1, 3, 5, 7]) assert.equal(opts[i].decision, "deny");
  // Hotkeys match the index (1-8, 9, 0).
  assert.equal(opts[0].hotkey, "1");
  assert.equal(opts[8].hotkey, "9");
  assert.equal(opts[9].hotkey, "0");
});

// ----- 7. Source: ConsentModal uses ↑/↓/Enter/Esc + 8/10 options -------

test("T-6-09: ConsentModal.tsx wires useInput for ↑/↓/Enter/Esc + numeric quick-select", () => {
  const src = readFileSync(join(root, "src", "components", "consent", "ConsentModal.tsx"), "utf-8");
  assert.match(src, /useInput/);
  assert.match(src, /key\.upArrow/);
  assert.match(src, /key\.downArrow/);
  assert.match(src, /key\.return/);
  assert.match(src, /key\.escape/);
  // 1-0 numeric quick-select.
  assert.match(src, /input >= "1" && input <= "9"/);
  assert.match(src, /input === "0"/);
  // Visual: bordered modal with the risk header.
  assert.match(src, /CONFIRM REQUIRED/);
  assert.match(src, /borderStyle="double"/);
});

// ----- 8. tsc: ConsentModal.tsx compiles cleanly -----------------------

test("T-6-09: TypeScript compile of ConsentModal.tsx is clean", () => {
  const r = spawnSync(`"${tscBin}"`, [
    ...tscArgs,
    '"' + join(root, "src", "components", "consent", "ConsentModal.tsx") + '"',
  ], { encoding: "utf-8", shell: true });
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
  }
  assert.equal(r.status, 0, "tsc compile failed for T-6-09 ConsentModal");
});

try { writeFileSync(shimPath, "", "utf-8"); /* leave a no-op for the next test */ } catch { /* ignore */ }
