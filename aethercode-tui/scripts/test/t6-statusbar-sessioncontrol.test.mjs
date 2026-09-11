// T-6-16: StatusBar SessionControl + re-attach banner.
//
// 4 tests:
//   1. StatusBar.tsx accepts the new onContinue / onPause
//      / onStop / staleSession / onReattach props.
//   2. The SessionControl is rendered when at least one
//      callback is supplied.
//   3. The stale banner is rendered when staleSession
//      is set AND its lastActiveAt is > 30 min old AND
//      onReattach is supplied.
//   4. tsc --strict compile of StatusBar.tsx is clean.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync, mkdirSync } from "node:fs";
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

test("T-6-16: StatusBar.tsx accepts the new SessionControl + stale props", () => {
  const src = read("src/components/StatusBar.tsx");
  assert.match(src, /onContinue\?:\s*\(\)\s*=>/);
  assert.match(src, /onPause\?:\s*\(\)\s*=>/);
  assert.match(src, /onStop\?:\s*\(\)\s*=>/);
  assert.match(src, /staleSession\?:\s*\{\s*id:\s*string;\s*lastActiveAt:\s*number\s*\}\s*\|\s*null/);
  assert.match(src, /onReattach\?:\s*\(id:\s*string\)\s*=>/);
});

test("T-6-16: StatusBar.tsx renders SessionControl when callbacks are supplied", () => {
  const src = read("src/components/StatusBar.tsx");
  // The flag combines the three optional callbacks.
  assert.match(src, /showControl\s*=\s*!!\(onContinue\s*\|\|\s*onPause\s*\|\|\s*onStop\)/);
  // The JSX is gated on the flag (in both return branches).
  assert.match(src, /showControl\s*\?\s*\(/);
  // And the SessionControl component is imported + rendered.
  assert.match(src, /import\s*\{[^}]*SessionControl[^}]*\}\s*from/);
  assert.match(src, /<SessionControl/);
});

test("T-6-16: StatusBar.tsx renders the stale banner when the session is stale", () => {
  const src = read("src/components/StatusBar.tsx");
  assert.match(src, /isStale\(\s*staleSession\.lastActiveAt/);
  assert.match(src, /showStale\s*\?\s*\(\s*<StaleBanner/);
  // The banner uses staleLabel() to render the duration.
  assert.match(src, /staleLabel\(\s*lastActiveAt\s*\)/);
});

test("T-6-16: tsc --strict compile of StatusBar.tsx is clean", () => {
  // The StatusBar pulls in ContextMeter + TokenChart +
  // session/SessionControl + session/SessionDetailsPanel.
  // We compile just those files so the test isn't held
  // hostage by pre-existing tsc issues in unrelated
  // components. (npx tsc --noEmit on the full project
  // IS the smoke test — phase-7-t7-10 covers that.)
  const tmp = join(root, "tmp-t6-sb-sc-tsc");
  if (existsSync(tmp)) {
    spawnSync(process.platform === "win32" ? "cmd" : "rm",
      process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", tmp] : ["-rf", tmp],
      { shell: process.platform === "win32" });
  }
  mkdirSync(tmp, { recursive: true });
  const r = spawnSync(`"${tscBin}"`, [
    "--outDir", tmp,
    "--target", "ES2022",
    "--module", "ES2022",
    "--moduleResolution", "bundler",
    "--esModuleInterop", "true",
    "--skipLibCheck", "true",
    "--strict", "true",
    "--jsx", "react",
    join(root, "src", "components", "StatusBar.tsx"),
    join(root, "src", "components", "session", "SessionControl.tsx"),
    join(root, "src", "components", "session", "SessionDetailsPanel.tsx"),
  ], { encoding: "utf-8", shell: true });
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
  }
  assert.equal(r.status, 0, "tsc compile failed for StatusBar");
});
