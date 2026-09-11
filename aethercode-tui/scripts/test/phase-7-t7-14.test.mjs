// T-7-14 (Phase 7): the other 5 of 10 consent flow tests.
// TS-T1 wrote 5; we (TS-T2) write 5 more focused on:
//   - 10-option flow paths (wildcard options 9/10)
//   - the full 10-option matrix
//   - category-specific appearances
//   - pure-helper contract (no React needed)
//   - source-level contract (file structure)

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// Compile a shim that re-exports the pure helpers (no React
// dep at test time).
const tmp = join(root, "tmp-t7-14-tsc");
const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
if (existsSync(tmp)) {
  spawnSync(process.platform === "win32" ? "cmd" : "rm",
    process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", tmp] : ["-rf", tmp]);
}
mkdirSync(tmp, { recursive: true });
const shimPath = join(root, "src", "components", "consent", "_t7_14_helpers.ts");
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
  "file:///" + join(tmp, "out", "components", "consent", "_t7_14_helpers.js").replace(/\\/g, "/")
);
const { buildOptions, shouldShowWildcard, deriveWildcardSubCategory } = helpers;

// ----- 1. 10-option flow: shell command with sub-category --------------

test("T-7-14: 10-option flow: shell.command.npm install produces 10 options with wildcard sub 'npm'", () => {
  const opts = buildOptions("shell.command.npm");
  assert.equal(opts.length, 10);
  // Option 9 / 10 are the wildcard allow/deny pair.
  assert.equal(opts[8].decision, "allow");
  assert.equal(opts[9].decision, "deny");
  assert.equal(opts[8].wildcardSubCategory, "npm");
  assert.equal(opts[9].wildcardSubCategory, "npm");
  // The wildcard pair's scope is "project" per the spec.
  assert.equal(opts[8].scope, "project");
  assert.equal(opts[9].scope, "project");
  // Label includes the sub-category.
  assert.match(opts[8].label, /npm/);
  assert.match(opts[9].label, /npm/);
});

// ----- 2. 10-option flow: shell command without sub-category ----------

test("T-7-14: 10-option flow: 'shell.command' (no sub) still produces the wildcard pair", () => {
  // The spec says options 9-10 appear for shell.command.* —
  // even an exact 'shell.command' (no sub) is a wildcard-eligible
  // category. The options are present; the sub-category is
  // `undefined` (since the helper couldn't derive one).
  const opts = buildOptions("shell.command");
  assert.equal(opts.length, 10);
  // The wildcard options are present.
  assert.equal(opts[8].decision, "allow");
  assert.equal(opts[9].decision, "deny");
  // But wildcardSubCategory is undefined (no sub extracted).
  assert.equal(opts[8].wildcardSubCategory, undefined);
  assert.equal(opts[9].wildcardSubCategory, undefined);
});

// ----- 3. 10-option flow: complex shell command (multi-word) -----------

test("T-7-14: 10-option flow: 'shell.command.git push --force' carries the full sub", () => {
  const opts = buildOptions("shell.command.git push --force");
  assert.equal(opts.length, 10);
  // The wildcard sub is the full string after 'shell.command.'.
  assert.equal(opts[8].wildcardSubCategory, "git push --force");
  assert.equal(opts[9].wildcardSubCategory, "git push --force");
  // The label interpolates the sub.
  assert.match(opts[8].label, /git push --force/);
});

// ----- 4. 8-option flow: non-shell category suppresses the wildcard ---

test("T-7-14: 8-option flow: non-shell categories (fs.*, search.*, mcp.*) suppress the wildcard", () => {
  for (const cat of [
    "fs.read",
    "fs.write",
    "fs.delete",
    "search.grep",
    "mcp.tool_invocation",
    "code.python_run",
    "agent.delegate",
  ]) {
    const opts = buildOptions(cat);
    assert.equal(opts.length, 8, `expected 8 options for ${cat}, got ${opts.length}`);
    for (const o of opts) {
      assert.equal(o.wildcardSubCategory, undefined, `unexpected wildcard on ${o.index} for ${cat}`);
    }
  }
});

// ----- 5. Pure-helper contract: shouldShowWildcard edge cases ----------

test("T-7-14: shouldShowWildcard edge cases — only shell.command.* qualifies", () => {
  // Falsey.
  assert.equal(shouldShowWildcard(""), false);
  assert.equal(shouldShowWildcard(null), false);
  assert.equal(shouldShowWildcard(undefined), false);
  // Non-shell: false.
  assert.equal(shouldShowWildcard("shell"), false); // missing ".command"
  assert.equal(shouldShowWildcard("command"), false);
  assert.equal(shouldShowWildcard("Shell.command.npm"), true); // case-insensitive
  assert.equal(shouldShowWildcard("  shell.command.npm  "), true); // whitespace-tolerant
  assert.equal(shouldShowWildcard("shell-command-npm"), false); // wrong separator
});
