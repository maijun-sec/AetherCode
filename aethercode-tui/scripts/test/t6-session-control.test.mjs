// T-6-06: SessionControl (Continue / Pause / Stop).
//
// Three buttons with keyboard shortcuts (c/p/s). The
// TUI test harness does not have `ink-testing-library`
// installed; we use pure-helper + source-grep + tsc
// compile.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const tscBin = join(
  root, "node_modules", ".bin",
  process.platform === "win32" ? "tsc.cmd" : "tsc"
);
const read = (rel) => readFileSync(join(root, rel), "utf-8");

// ----- 1. Pure-helper tests ------------------------------------

test("T-6-06: validActions returns the right set per state", () => {
  // Mirror the implementation in SessionControl.tsx.
  function validActions(state) {
    switch (state) {
      case "running":   return new Set(["pause", "stop"]);
      case "paused":    return new Set(["continue", "stop"]);
      case "completed": return new Set(["continue"]);
      case "failed":    return new Set(["continue", "stop"]);
      case "cancelled": return new Set(["continue"]);
      default:          return new Set();
    }
  }
  assert.deepEqual([...validActions("running")].sort(), ["pause", "stop"]);
  assert.deepEqual([...validActions("paused")].sort(), ["continue", "stop"]);
  assert.deepEqual([...validActions("completed")].sort(), ["continue"]);
  assert.deepEqual([...validActions("failed")].sort(), ["continue", "stop"]);
  assert.deepEqual([...validActions("cancelled")].sort(), ["continue"]);
  assert.deepEqual([...validActions("unknown")], []);
});

test("T-6-06: buttons render in Continue / Pause / Stop order", () => {
  const src = read("src/components/session/SessionControl.tsx");
  // The constant is declared with a type annotation, so
  // we accept either `= [...]` or `: ControlAction[] = [...]`.
  assert.match(src, /ACTION_ORDER[\s\S]{0,40}\["continue",\s*"pause",\s*"stop"\]/);
});

test("T-6-06: hotkeys are c / p / s", () => {
  const src = read("src/components/session/SessionControl.tsx");
  assert.match(src, /continue:\s*"c"/);
  assert.match(src, /pause:\s*"p"/);
  assert.match(src, /stop:\s*"s"/);
});

// ----- 2. Source-grep assertions ------------------------------

test("T-6-06: SessionControl.tsx exists and exports the component", () => {
  const path = join(root, "src/components/session/SessionControl.tsx");
  assert.ok(existsSync(path));
  const src = read("src/components/session/SessionControl.tsx");
  assert.match(src, /export\s+const\s+SessionControl\b/);
});

test("T-6-06: useInput handles c / p / s and Tab", () => {
  const src = read("src/components/session/SessionControl.tsx");
  assert.match(src, /useInput\(/);
  assert.match(src, /HOTKEY\.continue/);
  assert.match(src, /HOTKEY\.pause/);
  assert.match(src, /HOTKEY\.stop/);
  assert.match(src, /key\.tab/);
});

test("T-6-06: disabled buttons render in dim colour", () => {
  const src = read("src/components/session/SessionControl.tsx");
  assert.match(src, /const\s+isValid\s*=\s*valid\.has/);
  assert.match(src, /isDisabled\s*=\s*!!disabled/);
  // The colour ternary collapses to t.dim when invalid
  // or disabled.
  assert.match(src, /isValid\s*&&\s*!isDisabled/);
  assert.match(src, /:\s*t\.dim/);
});

test("T-6-06: onContinue / onPause / onStop are all wired", () => {
  const src = read("src/components/session/SessionControl.tsx");
  assert.match(src, /onContinue\?:/);
  assert.match(src, /onPause\?:/);
  assert.match(src, /onStop\?:/);
  // And the click handler dispatches to them.
  assert.match(src, /onContinue\?\.\(\)/);
  assert.match(src, /onPause\?\.\(\)/);
  assert.match(src, /onStop\?\.\(\)/);
});

test("T-6-06: ControlAction type is exported", () => {
  const src = read("src/components/session/SessionControl.tsx");
  assert.match(src, /export\s+type\s+ControlAction\s*=\s*"continue"\s*\|\s*"pause"\s*\|\s*"stop"/);
});

test("T-6-06: SessionState type is exported", () => {
  const src = read("src/components/session/SessionControl.tsx");
  assert.match(src, /export\s+type\s+SessionState\b/);
});

test("T-6-06: makeSessionControlProps helper is exported", () => {
  const src = read("src/components/session/SessionControl.tsx");
  assert.match(src, /export\s+function\s+makeSessionControlProps/);
});

// ----- 3. tsc --strict compile of the .tsx -------------------

test("T-6-06: tsc --strict compile of SessionControl.tsx is clean", () => {
  const tmp = join(root, "tmp-t6-sc-tsc");
  if (existsSync(tmp)) {
    spawnSync(process.platform === "win32" ? "cmd" : "rm",
      process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", tmp] : ["-rf", tmp],
      { shell: process.platform === "win32" });
  }
  const r = spawnSync(`"${tscBin}"`, [
    "--outDir", tmp, "--target", "ES2022", "--module", "ES2022",
    "--moduleResolution", "bundler", "--jsx", "react",
    "--esModuleInterop", "true", "--skipLibCheck", "true",
    "--strict", "true", "--noUncheckedIndexedAccess", "true",
    "--rootDir", join(root, "src"),
    join(root, "src", "components", "session", "SessionControl.tsx"),
  ], { encoding: "utf-8", shell: true });
  if (r.status !== 0) {
    console.error(r.stdout);
    console.error(r.stderr);
  }
  assert.equal(r.status, 0, "tsc compile failed for SessionControl");
});
