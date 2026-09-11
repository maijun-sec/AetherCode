// R46: Welcome v2.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

test("R46: Welcome.tsx shows shortcuts section with at least 5 entries", () => {
  const src = readFileSync(join(root, "src", "components", "Welcome.tsx"), "utf-8");
  // The new welcome has a SHORTCUTS array of at least 5 entries.
  assert.match(src, /const SHORTCUTS/);
  // Count the entries — each has { key, label, desc }.
  const entries = (src.match(/\{ key: "/g) || []).length;
  assert.ok(entries >= 5, `expected >=5 SHORTCUTS entries, got ${entries}`);
});

test("R46: Welcome.tsx shows the model, session, cwd chips", () => {
  const src = readFileSync(join(root, "src", "components", "Welcome.tsx"), "utf-8");
  assert.match(src, /icon\.model/);
  assert.match(src, /icon\.id/);
  assert.match(src, /icon\.path/);
});

test("R46: Welcome.tsx mentions the key shortcuts (Tab, Ctrl-B, Ctrl-F, Ctrl-?)", () => {
  const src = readFileSync(join(root, "src", "components", "Welcome.tsx"), "utf-8");
  assert.match(src, /Tab/);
  assert.match(src, /Ctrl-B/);
  assert.match(src, /Ctrl-F/);
  assert.match(src, /Ctrl-\?/);
});

test("R46: Welcome.tsx still has the round border + brand color (R31 contract preserved)", () => {
  const src = readFileSync(join(root, "src", "components", "Welcome.tsx"), "utf-8");
  assert.match(src, /borderStyle="round"/);
  assert.match(src, /borderColor=\{t\.brand\}/);
});

// ----- Build smoke test ---------------------------------------------

test("R46: TypeScript compile of Welcome.tsx is clean", () => {
  const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
  const tmp = join(root, "tmp-r46-tsc");
  if (existsSync(tmp)) {
    spawnSync(process.platform === "win32" ? "cmd" : "rm",
      process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", tmp] : ["-rf", tmp]);
  }
  const tscArgs = [
    "--outDir", tmp, "--target", "ES2022", "--module", "ES2022",
    "--moduleResolution", "bundler", "--jsx", "react",
    "--esModuleInterop", "true", "--skipLibCheck", "true",
    "--rootDir", join(root, "src"),
  ];
  const r = spawnSync(`"${tscBin}"`, [
    ...tscArgs,
    '"' + join(root, "src", "components", "Welcome.tsx") + '"',
  ], { encoding: "utf-8", shell: true });
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
  }
  assert.equal(r.status, 0, "tsc compile failed for R46 Welcome");
});
