// T-441: StatusBar embeds ContextMeter.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

const sb = readFileSync(join(root, "src", "components", "StatusBar.tsx"), "utf-8");
const state = readFileSync(join(root, "src", "state.ts"), "utf-8");

test("T-441: StatusBar.tsx imports ContextMeter", () => {
  assert.match(sb, /import\s+\{\s*ContextMeter[^}]*\}\s+from\s+"\.\/ContextMeter\.js"/);
});

test("T-441: StatusBar.tsx builds a ContextInfo from state", () => {
  assert.match(sb, /const ctxInfo:\s*ContextInfo\s*=/);
  assert.match(sb, /inputTokens:\s*state\.inputTokens/);
  assert.match(sb, /maxTokens:\s*state\.contextMaxTokens\s*\?\?\s*200_000/);
  assert.match(sb, /lastCompactTs:\s*state\.lastCompactTs/);
  assert.match(sb, /autoCompactDisabled:\s*state\.autoCompactDisabled/);
});

test("T-441: StatusBar.tsx embeds <ContextMeter> with a small width", () => {
  // The 200 px default would not fit in the bar — the
  // host overrides with a small width (24 chars).
  assert.match(sb, /<ContextMeter/);
  assert.match(sb, /width=\{24\}/);
  assert.match(sb, /sampleIntervalMs=\{ctxPaused\s*\?\s*1_000_000\s*:\s*4_000\}/);
});

test("T-441: state.ts adds the three ContextInfo fields", () => {
  assert.match(state, /contextMaxTokens:\s*number\s*\|\s*null/);
  assert.match(state, /lastCompactTs:\s*number\s*\|\s*null/);
  assert.match(state, /autoCompactDisabled:\s*boolean/);
});

test("T-441: state.ts INITIAL has the three ContextInfo fields", () => {
  assert.match(state, /contextMaxTokens:\s*null/);
  assert.match(state, /lastCompactTs:\s*null/);
  assert.match(state, /autoCompactDisabled:\s*false/);
});

test("T-441: TypeScript compile of StatusBar.tsx is clean", () => {
  const tmp = join(root, "tmp-t441-tsc");
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
    '"' + join(root, "src", "components", "StatusBar.tsx") + '"',
  ], { encoding: "utf-8", shell: true });
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
  }
  assert.equal(r.status, 0, "tsc compile failed for T-441 StatusBar");
});
