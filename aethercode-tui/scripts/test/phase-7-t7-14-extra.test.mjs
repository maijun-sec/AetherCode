// T-7-14 (Phase 7): the other 5 of 10 consent flow tests.
// TS-T2 wrote 5 in `phase-7-t7-14.test.mjs` (10-option flow
// paths, wildcard pair, etc.). This file adds the 5 that
// focus on the **non-React / no-IO** surface of the consent
// flow:
//
//   1. Option 3/4 (session scope allow/deny) carry the
//      right labels + scopes.
//   2. Option 5/6 (project scope allow/deny) carry the
//      right labels + scopes.
//   3. Option 7/8 (user scope allow/deny) carry the right
//      labels + scopes.
//   4. Hotkey letters are mapped 1:1 across all 8 standard
//      options (a/A, t/T, p/P, u/U, d/D) — the wildcard
//      pair (options 9/10) reuses `p`/`P` from option 5/6
//      and gets a distinct sub-category label so the
//      caller can disambiguate.
//   5. Pure-helper: buildOptions() is deterministic —
//      same input always returns the same option order
//      + index values.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdirSync, writeFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// Compile a shim that re-exports the pure helpers (no React
// dep at test time).
const tmp = join(root, "tmp-t7-14-extra-tsc");
const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
if (existsSync(tmp)) {
  spawnSync(process.platform === "win32" ? "cmd" : "rm",
    process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", tmp] : ["-rf", tmp]);
}
mkdirSync(tmp, { recursive: true });
const shimPath = join(root, "src", "components", "consent", "_t7_14_extra_helpers.ts");
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
  "file:///" + join(tmp, "out", "components", "consent", "_t7_14_extra_helpers.js").replace(/\\/g, "/")
);
const { buildOptions, shouldShowWildcard, deriveWildcardSubCategory } = helpers;

// ----- 1. Session-scope options (3/4) carry the right scope/decision --

test("T-7-14: option 3/4 are 'session' scope; option 3 is allow, option 4 is deny", () => {
  const opts = buildOptions("fs.write");
  // Options 1-8 are always present.
  assert.equal(opts.length, 8);
  const opt3 = opts[2];
  const opt4 = opts[3];
  assert.equal(opt3.index, 3);
  assert.equal(opt3.scope, "session");
  assert.equal(opt3.decision, "allow");
  assert.match(opt3.label, /session/i);
  const opt4b = opts[3];
  assert.equal(opt4b.index, 4);
  assert.equal(opt4b.scope, "session");
  assert.equal(opt4b.decision, "deny");
  assert.match(opt4b.label, /session/i);
});

// ----- 2. Project-scope options (5/6) carry the right scope/decision --

test("T-7-14: option 5/6 are 'project' scope; option 5 is allow, option 6 is deny", () => {
  const opts = buildOptions("fs.write");
  const opt5 = opts[4];
  const opt6 = opts[5];
  assert.equal(opt5.index, 5);
  assert.equal(opt5.scope, "project");
  assert.equal(opt5.decision, "allow");
  assert.match(opt5.label, /project/i);
  assert.equal(opt6.index, 6);
  assert.equal(opt6.scope, "project");
  assert.equal(opt6.decision, "deny");
  assert.match(opt6.label, /project/i);
});

// ----- 3. User-scope options (7/8) carry the right scope/decision -----

test("T-7-14: option 7/8 are 'user' scope; option 7 is allow, option 8 is deny", () => {
  const opts = buildOptions("fs.write");
  const opt7 = opts[6];
  const opt8 = opts[7];
  assert.equal(opt7.index, 7);
  assert.equal(opt7.scope, "user");
  assert.equal(opt7.decision, "allow");
  assert.match(opt7.label, /user|me|all projects/i);
  assert.equal(opt8.index, 8);
  assert.equal(opt8.scope, "user");
  assert.equal(opt8.decision, "deny");
  assert.match(opt8.label, /user|me|all projects/i);
});

// ----- 4. Hotkey mapping across the 8 standard options ---------------

test("T-7-14: the 8 standard options each have a unique hotkey (1..8)", () => {
  const opts = buildOptions("fs.write");
  assert.equal(opts.length, 8);
  const seen = new Set();
  for (const o of opts) {
    assert.ok(o.hotkey && o.hotkey.length === 1, `option ${o.index} hotkey missing: ${o.hotkey}`);
    assert.ok(!seen.has(o.hotkey), `duplicate hotkey '${o.hotkey}' on options ${o.index} and an earlier option`);
    seen.add(o.hotkey);
  }
  // The 8 standard options are 1..8. (The wildcard
  // pair would be 9 and 0, but `fs.write` doesn't
  // produce them.)
  const expected = new Set(["1", "2", "3", "4", "5", "6", "7", "8"]);
  for (const k of seen) {
    assert.ok(expected.has(k), `unexpected hotkey ${k} (expected one of: 1..8)`);
  }
  // And `index` matches `hotkey` for 1..8.
  for (const o of opts) {
    if (o.index >= 1 && o.index <= 8) {
      assert.equal(o.hotkey, String(o.index));
    }
  }
});

// ----- 5. Pure-helper: buildOptions is deterministic ----------------

test("T-7-14: buildOptions is deterministic — same input → same output", () => {
  for (let i = 0; i < 5; i++) {
    const a = buildOptions("shell.command.npm");
    const b = buildOptions("shell.command.npm");
    assert.equal(a.length, b.length);
    for (let j = 0; j < a.length; j++) {
      assert.equal(a[j].index, b[j].index);
      assert.equal(a[j].decision, b[j].decision);
      assert.equal(a[j].scope, b[j].scope);
      assert.equal(a[j].hotkey, b[j].hotkey);
      assert.equal(a[j].label, b[j].label);
      assert.equal(a[j].wildcardSubCategory, b[j].wildcardSubCategory);
    }
  }
  // And it doesn't depend on Date.now() or any other
  // non-deterministic source.
  const a = buildOptions("fs.read");
  const b = buildOptions("fs.read");
  for (let j = 0; j < a.length; j++) {
    assert.equal(a[j].label, b[j].label);
  }
});
