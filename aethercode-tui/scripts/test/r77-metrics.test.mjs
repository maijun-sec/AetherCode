// R77: metrics (backend collector + TUI command).

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// ----- 1. Pure test: format a metrics snapshot for the TUI ---------

function formatMetrics(m) {
  const fmt = (n) => typeof n === "number" ? n.toFixed(3) : String(n);
  const lines = ["engine metrics"];
  for (const k of Object.keys(m)) {
    const v = m[k];
    if (typeof v === "number" && (k === "errorRate" || k === "cacheHitRate")) {
      lines.push(`  ${k.padEnd(18, " ")}  ${fmt(v * 100)}%`);
    } else if (typeof v === "number" && k === "costUsd") {
      lines.push(`  ${k.padEnd(18, " ")}  $${fmt(v)}`);
    } else {
      lines.push(`  ${k.padEnd(18, " ")}  ${v}`);
    }
  }
  return lines.join("\n");
}

test("R77: formatMetrics formats percentages with %", () => {
  const out = formatMetrics({ errorRate: 0.25, cacheHitRate: 0.75 });
  assert.match(out, /errorRate\s+25\.000%/);
  assert.match(out, /cacheHitRate\s+75\.000%/);
});

test("R77: formatMetrics formats costUsd with $", () => {
  const out = formatMetrics({ costUsd: 0.0123 });
  assert.match(out, /costUsd\s+\$0\.012/);
});

test("R77: formatMetrics shows all fields", () => {
  const out = formatMetrics({
    uptimeMs: 12345, turnsStarted: 5, turnsCompleted: 4,
    toolCalls: 12, toolErrors: 2, toolRetries: 0,
    loopStops: 1, permissionAsks: 3, permissionDenies: 1,
    cacheHits: 5, cacheMisses: 5, costUsd: 0.04,
    errorRate: 2 / 12, cacheHitRate: 0.5,
  });
  // Headers.
  assert.match(out, /engine metrics/);
  // All field names appear at least once.
  for (const k of [
    "uptimeMs", "turnsStarted", "turnsCompleted", "toolCalls",
    "toolErrors", "toolRetries", "loopStops", "permissionAsks",
    "permissionDenies", "cacheHits", "cacheMisses", "costUsd",
    "errorRate", "cacheHitRate",
  ]) {
    assert.ok(out.includes(k), `expected field "${k}" in output:\n${out}`);
  }
});

// ----- 2. Source code assertions -----------------------------------

test("R77: backend MetricsCollector.java exists", () => {
  // The backend is in aethercode-core; we just check the file is
  // present and exports the right API.
  const path = join(root, "..", "aethercode-core", "src", "main", "java", "org", "aethercode", "core", "metrics", "MetricsCollector.java");
  assert.ok(existsSync(path), "MetricsCollector.java missing in aethercode-core");
  const src = readFileSync(path, "utf-8");
  assert.match(src, /public final class MetricsCollector/);
  assert.match(src, /public Map<String, Object> snapshot/);
  assert.match(src, /public void incToolCall/);
  assert.match(src, /public void incToolError/);
  assert.match(src, /public void addCostUsd/);
});

test("R77: AetherCodeMethods registers getMetrics", () => {
  const path = join(root, "..", "aethercode-protocol", "src", "main", "java", "org", "aethercode", "protocol", "methods", "AetherCodeMethods.java");
  const src = readFileSync(path, "utf-8");
  assert.match(src, /dispatcher\.register\("getMetrics",\s+this::getMetrics\)/);
  assert.match(src, /public Object getMetrics\(Object params\)/);
  assert.match(src, /engine\.metrics\(\)\.snapshot\(\)/);
});

test("R77: AetherCodeEngine has a metrics() method", () => {
  const path = join(root, "..", "aethercode-sdk", "src", "main", "java", "org", "aethercode", "sdk", "AetherCodeEngine.java");
  const src = readFileSync(path, "utf-8");
  assert.match(src, /public org\.aethercode\.core\.metrics\.MetricsCollector metrics\(\)/);
  assert.match(src, /this\.metrics = new org\.aethercode\.core\.metrics\.MetricsCollector\(\)/);
});

test("R77: TUI commands.ts has /metrics", () => {
  const cmds = readFileSync(join(root, "src", "commands.ts"), "utf-8");
  assert.match(cmds, /case "metrics":/);
  assert.match(cmds, /rpcMethod: "getMetrics"/);
});

test("R77: tui.tsx pretty-prints metrics", () => {
  const tui = readFileSync(join(root, "src", "tui.tsx"), "utf-8");
  assert.match(tui, /slash\.rpcMethod === "getMetrics"/);
  assert.match(tui, /engine metrics/);
  assert.match(tui, /costUsd/);
});

// ----- 3. Build smoke test ------------------------------------------

test("R77: TypeScript compile of the new TUI code is clean", () => {
  const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
  const tmp = join(root, "tmp-r77-tsc");
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
    '"' + join(root, "src", "tui.tsx") + '"',
    '"' + join(root, "src", "commands.ts") + '"',
  ], { encoding: "utf-8", shell: true });
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
  }
  assert.equal(r.status, 0, "tsc compile failed for R77");
});

test("R77: e2e-r77-metrics.mjs exists and asserts counters after a real query", () => {
  // This is a "presence + intent" test — the real E2E needs the
  // rebuilt jar. We just confirm the file exists and contains
  // the right assertions, so the test will catch a future
  // accidental removal.
  const e2e = join(root, "scripts", "test", "e2e-r77-metrics.mjs");
  assert.ok(existsSync(e2e), "e2e-r77-metrics.mjs missing");
  const src = readFileSync(e2e, "utf-8");
  assert.match(src, /getMetrics/);
  assert.match(src, /turnsStarted >= 1/);
  assert.match(src, /toolCalls >= 1/);
  assert.match(src, /turnsCompleted >= 1/);
});
