// R78: trace recorder (backend + TUI command).

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// ----- 1. Pure test: format a traces snapshot for the TUI -----------

/** Format a traces snapshot the same way tui.tsx does. The
 *  logic is reproduced here so we can unit-test the shape of the
 *  output without spinning up a full TUI. */
function formatTraces(r) {
  const traces = Array.isArray(r?.traces) ? r.traces : [];
  const inFlight = typeof r?.inFlight === "number" ? r.inFlight : 0;
  const completed = typeof r?.completed === "number" ? r.completed : 0;
  const lines = [`traces (${traces.length} shown, ${completed} total, ${inFlight} in flight)`];
  for (const t of traces) {
    const name = String(t.name ?? "?");
    const status = String(t.status ?? "?");
    const dur = typeof t.durationMs === "number" ? t.durationMs : 0;
    const durStr = dur < 1000 ? `${dur}ms` : `${(dur / 1000).toFixed(2)}s`;
    const icon = status === "ok" ? "✓" : status === "error" ? "✗" : "·";
    lines.push(`  ${icon} ${name.padEnd(20, " ")}  ${durStr.padStart(8, " ")}  ${status}`);
  }
  if (traces.length === 0) {
    lines.push("  (no traces yet — run a query to populate)");
  }
  return lines.join("\n");
}

test("R78: formatTraces prints header with in-flight + completed", () => {
  const out = formatTraces({ inFlight: 1, completed: 7, traces: [] });
  assert.match(out, /traces \(0 shown, 7 total, 1 in flight\)/);
  assert.match(out, /no traces yet/);
});

test("R78: formatTraces shows ok icon for ok spans", () => {
  const out = formatTraces({
    inFlight: 0, completed: 1,
    traces: [{ name: "query", status: "ok", durationMs: 250 }],
  });
  assert.match(out, /✓ query\s+250ms\s+ok/);
});

test("R78: formatTraces shows error icon for error spans", () => {
  const out = formatTraces({
    inFlight: 0, completed: 1,
    traces: [{ name: "tool.bash", status: "error", durationMs: 12 }],
  });
  assert.match(out, /✗ tool\.bash\s+12ms\s+error/);
});

test("R78: formatTraces converts long durations to seconds", () => {
  const out = formatTraces({
    inFlight: 0, completed: 1,
    traces: [{ name: "query", status: "ok", durationMs: 3500 }],
  });
  assert.match(out, /3\.50s/);
});

test("R78: formatTraces handles missing fields gracefully", () => {
  // Defensive: even with weird / partial input, we render without throwing.
  const out = formatTraces({});
  assert.match(out, /traces \(0 shown/);
  assert.match(out, /no traces yet/);
  // Empty array.
  const out2 = formatTraces({ inFlight: 0, completed: 0, traces: [] });
  assert.match(out2, /no traces yet/);
});

// ----- 2. Source code assertions -----------------------------------

test("R78: backend TraceRecorder.java exists", () => {
  const path = join(root, "..", "aethercode-core", "src", "main", "java", "org", "aethercode", "core", "trace", "TraceRecorder.java");
  assert.ok(existsSync(path), "TraceRecorder.java missing in aethercode-core");
  const src = readFileSync(path, "utf-8");
  assert.match(src, /public final class TraceRecorder/);
  assert.match(src, /public String startSpan/);
  assert.match(src, /public synchronized void endSpan/);
  assert.match(src, /public Map<String, Object> snapshot/);
  // FIFO cap is part of the contract — make sure it's still there.
  assert.match(src, /CAPACITY = 256/);
});

test("R78: AetherCodeMethods registers getTraces", () => {
  const path = join(root, "..", "aethercode-protocol", "src", "main", "java", "org", "aethercode", "protocol", "methods", "AetherCodeMethods.java");
  const src = readFileSync(path, "utf-8");
  assert.match(src, /dispatcher\.register\("getTraces",\s+this::getTraces\)/);
  assert.match(src, /public Object getTraces\(Object params\)/);
  assert.match(src, /engine\.traces\(\)\.snapshot\(limit\)/);
});

test("R78: AetherCodeMethods.query() opens and closes spans", () => {
  const path = join(root, "..", "aethercode-protocol", "src", "main", "java", "org", "aethercode", "protocol", "methods", "AetherCodeMethods.java");
  const src = readFileSync(path, "utf-8");
  // The root span is opened at the top of the worker thread and
  // closed on RunEnd (or in the catch block). R79: the tool
  // spans are now opened with startChildSpan(queryTraceId, ...)
  // so the parent linkage is recorded.
  assert.match(src, /engine\.traces\(\)\.startSpan\("query"/);
  assert.match(src, /engine\.traces\(\)\.startChildSpan\(\s*queryTraceId/);
  assert.match(src, /engine\.traces\(\)\.endSpan\(toolTraceId/);
  assert.match(src, /engine\.traces\(\)\.endSpan\(queryTraceId/);
});

test("R78: AetherCodeEngine has a traces() method", () => {
  const path = join(root, "..", "aethercode-sdk", "src", "main", "java", "org", "aethercode", "sdk", "AetherCodeEngine.java");
  const src = readFileSync(path, "utf-8");
  assert.match(src, /public org\.aethercode\.core\.trace\.TraceRecorder traces\(\)/);
  assert.match(src, /this\.traces = new org\.aethercode\.core\.trace\.TraceRecorder\(\)/);
});

test("R78: TUI commands.ts has /trace", () => {
  const cmds = readFileSync(join(root, "src", "commands.ts"), "utf-8");
  assert.match(cmds, /case "trace":/);
  assert.match(cmds, /rpcMethod: "getTraces"/);
  // /trace <N> should pass the limit through.
  assert.match(cmds, /Math\.min\(256, Math\.floor\(n\)\)/);
});

test("R78: tui.tsx pretty-prints traces and dispatches setRecentTraces", () => {
  const tui = readFileSync(join(root, "src", "tui.tsx"), "utf-8");
  assert.match(tui, /slash\.rpcMethod === "getTraces"/);
  assert.match(tui, /type: "setRecentTraces"/);
  assert.match(tui, /traces \(/);
  // Status icon for ok.
  assert.match(tui, /status === "ok" \? "✓"/);
  // Status icon for error.
  assert.match(tui, /status === "error" \? "✗"/);
});

test("R78: TUI state.ts has TraceSummary type and setRecentTraces action", () => {
  const state = readFileSync(join(root, "src", "state.ts"), "utf-8");
  assert.match(state, /export interface TraceSummary/);
  assert.match(state, /type: "setRecentTraces"/);
  assert.match(state, /recentTraces: TraceSummary\[\]/);
  assert.match(state, /tracesInFlight: number/);
  assert.match(state, /tracesCompleted: number/);
});

test("R78: SLASH_COMMANDS includes 'trace'", () => {
  const cmds = readFileSync(join(root, "src", "commands.ts"), "utf-8");
  // The list is a flat string[] — make sure "trace" is one of the entries.
  // The simplest check: it appears as a literal in the SLASH_COMMANDS array.
  const lines = cmds.split("\n").map((l) => l.trim());
  const slashLine = lines.find((l) => l.startsWith("\"trace\","));
  assert.ok(slashLine !== undefined, "expected \"trace\", as a SLASH_COMMANDS entry");
});

// ----- 3. Build smoke test ------------------------------------------

test("R78: TypeScript compile of the new TUI code is clean", () => {
  const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
  const tmp = join(root, "tmp-r78-tsc");
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
  assert.equal(r.status, 0, "tsc compile failed for R78");
});

// ----- 4. E2E presence test -----------------------------------------

test("R78: e2e-r78-traces.mjs exists and asserts traces after a real query", () => {
  // Same intent test as R77: confirm the E2E file is present and
  // asserts what we expect, so a future accidental removal is
  // caught by the unit test suite.
  const e2e = join(root, "scripts", "test", "e2e-r78-traces.mjs");
  assert.ok(existsSync(e2e), "e2e-r78-traces.mjs missing");
  const src = readFileSync(e2e, "utf-8");
  assert.match(src, /getTraces/);
  // At least one of the post-query assertions is required.
  assert.match(src, /traces\.length|completed\s*>=\s*1|inFlight/);
});
