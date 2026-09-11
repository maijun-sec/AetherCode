// T-443: WelcomeBanner replaces Welcome with rich info.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// ----- 1. Pure helper tests (T-443) --------------------------------

// Mirror the WelcomeBanner.tsx helpers.
function homePrefixed(cwd, home) {
  if (!cwd) return "";
  const h = home ?? "";
  if (!h) return cwd;
  if (cwd === h) return "~";
  if (cwd.startsWith(h + "/") || cwd.startsWith(h + "\\")) {
    return "~/" + cwd.substring(h.length + 1);
  }
  return cwd;
}

test("T-443: homePrefixed returns empty for empty input", () => {
  assert.equal(homePrefixed("", null), "");
});

test("T-443: homePrefixed returns cwd when no home is known", () => {
  assert.equal(homePrefixed("/etc", null), "/etc");
});

test("T-443: homePrefixed returns ~ for the home dir itself", () => {
  assert.equal(homePrefixed("/home/user", "/home/user"), "~");
});

test("T-443: homePrefixed uses ~/ when the cwd sits under home", () => {
  assert.equal(homePrefixed("/home/user/projects", "/home/user"), "~/projects");
  // Windows-style paths.
  assert.equal(homePrefixed("C:\\Users\\me\\foo", "C:\\Users\\me"), "~/foo");
});

test("T-443: homePrefixed leaves the cwd unchanged when it's outside home", () => {
  assert.equal(homePrefixed("/var/log", "/home/user"), "/var/log");
});

// ----- 2. Source-code assertions -----------------------------------

const src = readFileSync(join(root, "src", "components", "WelcomeBanner.tsx"), "utf-8");
const welcome = readFileSync(join(root, "src", "components", "Welcome.tsx"), "utf-8");

test("T-443: WelcomeBanner.tsx exports the WelcomeBanner component", () => {
  assert.match(src, /export const WelcomeBanner/);
});

test("T-443: WelcomeBanner.tsx exports the WelcomeBannerInfo interface", () => {
  assert.match(src, /export interface WelcomeBannerInfo/);
  // All the deepagents-code fields are present.
  for (const f of [
    "modelProvider", "modelName", "cwd", "version",
    "threadId", "projectName", "replicaProject", "projectUrls",
    "mcpToolCount", "mcpUnauthenticated", "mcpErrored", "mcpAwaitingReconnect",
    "showModel", "showCwd", "hideCwd", "hideVersion",
    "showThreadId", "debugEnabled", "experimentalEnabled", "editableInstall",
  ]) {
    assert.ok(src.includes(f), `WelcomeBannerInfo missing field: ${f}`);
  }
});

test("T-443: WelcomeBanner.tsx exports the homePrefixed helper", () => {
  assert.match(src, /export function homePrefixed\(/);
});

test("T-443: WelcomeBanner.tsx exports the LANGSMITH_UTM_SOURCE constant", () => {
  assert.match(src, /export const LANGSMITH_UTM_SOURCE/);
});

test("T-443: WelcomeBanner.tsx exports the ANSI_THEMES set", () => {
  assert.match(src, /export const ANSI_THEMES/);
  assert.match(src, /ansi-dark/);
  assert.match(src, /ansi-light/);
});

test("T-443: WelcomeBanner.tsx renders one row per deepagents-code field", () => {
  for (const label of [
    "model:", "directory:", "tracing:", "replica:",
    "thread:", "mcp tools:", "mcp login:", "mcp errors:", "mcp reconnect:",
  ]) {
    assert.ok(src.includes(label), `WelcomeBanner missing label: ${label}`);
  }
});

test("T-443: Welcome.tsx still exists (kept for backward compat) and adds SHORTCUTS", () => {
  assert.match(welcome, /export const Welcome\b/);
  assert.match(welcome, /const SHORTCUTS/);
  // At least 5 entries.
  const entries = (welcome.match(/\{\s*key:\s*"/g) || []).length;
  assert.ok(entries >= 5, `expected >= 5 SHORTCUTS, got ${entries}`);
  // Mentions the new shortcuts.
  assert.match(welcome, /Tab/);
  assert.match(welcome, /Ctrl-B/);
  assert.match(welcome, /Ctrl-F/);
  assert.match(welcome, /Ctrl-\?/);
});

// ----- 3. tsc compile ----------------------------------------------

test("T-443: TypeScript compile of WelcomeBanner.tsx is clean", () => {
  const tmp = join(root, "tmp-t443-tsc");
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
    '"' + join(root, "src", "components", "WelcomeBanner.tsx") + '"',
    '"' + join(root, "src", "components", "Welcome.tsx") + '"',
  ], { encoding: "utf-8", shell: true });
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
  }
  assert.equal(r.status, 0, "tsc compile failed for T-443 WelcomeBanner");
});
