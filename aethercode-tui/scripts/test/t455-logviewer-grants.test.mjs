// T-455: extend TUI LogViewer for grants log.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

const lv = readFileSync(join(root, "src", "components", "LogViewer.tsx"), "utf-8");

test("T-455: LogViewer.tsx exports the GrantEntry interface", () => {
  assert.match(lv, /export interface GrantEntry/);
  // Mirrors the deepagents-code Grant shape.
  assert.match(lv, /scope:\s*"session"\s*\|\s*"project"\s*\|\s*"user"/);
  assert.match(lv, /decision:\s*"allow"\s*\|\s*"deny"/);
  assert.match(lv, /expiresAt\?:\s*number\s*\|\s*null/);
});

test("T-455: LogViewer.tsx accepts a grants prop + initialMode", () => {
  assert.match(lv, /grants\?:\s*GrantEntry\[\]/);
  assert.match(lv, /initialMode\?:\s*"log"\s*\|\s*"grants"/);
});

test("T-455: LogViewer.tsx renders the grants-log tab when mode === 'grants'", () => {
  assert.match(lv, /mode === "grants"\s*&&\s*hasGrants/);
  // The grants header label.
  assert.match(lv, /grants log/);
  // The scope / decision glyphs.
  assert.match(lv, /SCOPE_ICON/);
  assert.match(lv, /DECISION_ICON/);
});

test("T-455: LogViewer.tsx renders the 'expired' badge for past-due grants", () => {
  assert.match(lv, /expired\s*=\s*g\.expiresAt\s*!=\s*null\s*&&\s*g\.expiresAt\s*<\s*Date\.now\(\)/);
  assert.match(lv, /\u26a0 expired/);
});

test("T-455: LogViewer.tsx supports [/] tab switching", () => {
  assert.match(lv, /input === "\["/);
  assert.match(lv, /input === "\]"/);
});

test("T-455: TypeScript compile of LogViewer.tsx is clean", () => {
  const tmp = join(root, "tmp-t455-tsc");
  if (existsSync(tmp)) {
    spawnSync(process.platform === "win32" ? "cmd" : "rm",
      process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", tmp] : ["-rf", tmp]);
  }
  const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
  const tscArgs = [
    "--outDir", tmp, "--target", "ES2022", "--module", "ES2022",
    "--moduleResolution", "bundler", "--jsx", "react",
    "--esModuleInterop", "true", "--skipLibCheck", "true",
    "--strict", "true",
    "--rootDir", join(root, "src"),
  ];
  const r = spawnSync(`"${tscBin}"`, [
    ...tscArgs,
    '"' + join(root, "src", "components", "LogViewer.tsx") + '"',
  ], { encoding: "utf-8", shell: true });
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
  }
  assert.equal(r.status, 0, "tsc compile failed for T-455 LogViewer");
});
