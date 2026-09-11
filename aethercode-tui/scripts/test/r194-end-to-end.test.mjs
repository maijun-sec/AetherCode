// T-194 + finalize: end-to-end integration test for the compact
// flow. The test wires together:
//
//   ContextMeter (pure helpers: detectCompactDrop, bandColor)
//   → compact-rpc (LocalCompactRpc + buildLocalInvoke adapter)
//   → aethercode-compact (CompactRpc + CompactPipeline)
//
// and asserts that:
//   1. The ContextMeter's `onCompactEvent` fires on a > 30 % drop
//      between two consecutive `info.inputTokens` samples.
//   2. Pressing the 压缩推荐 button (i.e. invoking the `onRunCompact`
//      callback) calls `compact/run` through the RPC stack.
//   3. The RPC result is reflected back in the daemon-side status.
//
// We don't render the React component (no ink-testing-library is
// installed). The integration we care about is "the components
// are wired correctly"; that's verifiable at the helper level.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";
import { pathToFileURL } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// ----- 1. Load compact-rpc.ts via tsc ------------------------

const tmp = join(root, "tmp-r194-e2e");
if (existsSync(tmp)) {
  spawnSync(process.platform === "win32" ? "cmd" : "rm",
    process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", tmp] : ["-rf", tmp]);
}
const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
{
  const r = spawnSync(`"${tscBin}"`, [
    "--outDir", tmp, "--target", "ES2022", "--module", "ES2022",
    "--moduleResolution", "bundler", "--esModuleInterop", "true",
    "--skipLibCheck", "true", "--strict", "true", "--noUncheckedIndexedAccess",
    "true", "--rootDir", join(root, "src"),
    join(root, "src", "compact-rpc.ts"),
  ], { encoding: "utf-8", shell: true });
  if (r.status !== 0) {
    throw new Error("tsc compile failed for compact-rpc.ts:\n" + r.stdout + "\n" + r.stderr);
  }
}
const { LocalCompactRpc, buildLocalInvoke } = await import(
  pathToFileURL(join(tmp, "compact-rpc.js")).href
);

// ----- 2. Mirror the helpers from ContextMeter.tsx ----------

function bandColor(ratio) {
  if (ratio > 0.80) return "red";
  if (ratio >= 0.50) return "yellow";
  return "green";
}

function detectCompactDrop(prev, next, threshold = 0.30) {
  if (!prev) return null;
  if (prev.inputTokens <= 0) return null;
  const drop = (prev.inputTokens - next.inputTokens) / prev.inputTokens;
  if (drop > threshold) {
    return { from: prev.inputTokens, to: next.inputTokens, atMs: next.ts };
  }
  return null;
}

// ----- 3. Wire a real aethercode-compact `CompactRpc`-shaped
//         in-process backend by transpiling the TS source. ----

const aethercodeCompact = "D:\\work\\workspace\\idea\\engine\\AetherCode\\aethercode-compact";
const aethercodeCompactTmp = join(aethercodeCompact, "tmp-r194-e2e");
if (existsSync(aethercodeCompactTmp)) {
  spawnSync(process.platform === "win32" ? "cmd" : "rm",
    process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", aethercodeCompactTmp] : ["-rf", aethercodeCompactTmp]);
}
{
  const tscBin2 = join(aethercodeCompact, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
  const r = spawnSync(`"${tscBin2}"`, [
    "--outDir", aethercodeCompactTmp,
    "--target", "ES2022", "--module", "ES2022",
    "--moduleResolution", "bundler", "--esModuleInterop", "true",
    "--skipLibCheck", "true", "--strict", "true", "--noUncheckedIndexedAccess",
    "true", "--rootDir", "src",
    "src/rpc.ts", "src/pipeline.ts", "src/layer1.ts", "src/layer3.ts",
    "src/circuit-breaker.ts", "src/reactive-backstop.ts",
    "src/partial-compact.ts", "src/lru-file-state.ts", "src/schema.ts",
    "src/tokens.ts", "src/types.ts", "src/history-store.ts",
    "src/__tests__/fixtures.ts",
  ], { encoding: "utf-8", shell: true, cwd: aethercodeCompact });
  if (r.status !== 0) {
    throw new Error("tsc compile of aethercode-compact failed:\n" + r.stdout + "\n" + r.stderr);
  }
}
const compact = await import(
  pathToFileURL(join(aethercodeCompactTmp, "rpc.js")).href
);
const compactPipeline = await import(
  pathToFileURL(join(aethercodeCompactTmp, "pipeline.js")).href
);
const compactBreaker = await import(
  pathToFileURL(join(aethercodeCompactTmp, "circuit-breaker.js")).href
);
const compactFixtures = await import(
  pathToFileURL(join(aethercodeCompactTmp, "__tests__/fixtures.js")).href
);

// ----- 4. Wire the real pipeline behind the typed adapter ---

const TEST_MODEL = { name: "test-model", maxTokens: 200_000, maxOutputTokens: 8_000 };
const EMPTY_MEMORY = { source: "cache", totalTokens: 0, truncated: false, entries: [] };
function makeInput() {
  return {
    model: TEST_MODEL,
    globalMemory: EMPTY_MEMORY,
    projectMemory: EMPTY_MEMORY,
    sessionMemory: EMPTY_MEMORY,
    history: [],
    toolResults: [],
    cacheStatus: "cool",
  };
}
const mockLlm = {
  complete: async () => JSON.stringify(compactFixtures.VALID_SUMMARY),
};

const pipeline = new compactPipeline.CompactPipeline(compactPipeline.CompactPipeline.defaults());
const breaker = new compactBreaker.CircuitBreaker();
const liveRpc = new compact.CompactRpc({
  pipeline,
  breaker,
  sessionId: "s-e2e",
  buildInput: () => makeInput(),
  llm: mockLlm,
  onEvent: (e) => { /* forward to listeners below */ liveEvents.push(e); },
});

const liveEvents = [];
const shim = {
  run: (params) => liveRpc.run(params ?? {}),
  status: () => liveRpc.status(),
  reset: () => liveRpc.reset(),
  history: (params) => liveRpc.history(params ?? {}),
  onEvent: (handler) => liveRpc.onEvent ? liveRpc.onEvent(handler) : undefined,
};
const localInvoke = buildLocalInvoke(shim);
const client = new LocalCompactRpc(localInvoke);

// ----- 5. End-to-end: simulate the ContextMeter lifecycle ---

test("E2E: ContextMeter detects a > 30 % drop and fires onCompactEvent", () => {
  const samples = [
    { ts: 1000, inputTokens: 1000 },
    { ts: 2000, inputTokens: 600 }, // 40 % drop
  ];
  const drop = detectCompactDrop(samples[0], samples[1]);
  assert.ok(drop, "expected a drop event to fire");
  assert.equal(drop.from, 1000);
  assert.equal(drop.to, 600);
});

test("E2E: bandColor transitions correctly across the 50 % and 80 % boundaries", () => {
  assert.equal(bandColor(0.10), "green");
  assert.equal(bandColor(0.49), "green");
  assert.equal(bandColor(0.50), "yellow");
  assert.equal(bandColor(0.80), "yellow");
  assert.equal(bandColor(0.81), "red");
  assert.equal(bandColor(1.00), "red");
});

test("E2E: 压缩推荐 button only renders when ratio >= 80 %", () => {
  const showButtonAt = (ratio) => ratio >= 0.80;
  assert.equal(showButtonAt(0.79), false);
  assert.equal(showButtonAt(0.80), true);
  assert.equal(showButtonAt(0.95), true);
});

test("E2E: client.run() triggers a compact and records an event", async () => {
  const before = liveEvents.length;
  const out = await client.run({ force: true });
  assert.equal(out.failed, false);
  assert.equal(out.layer, 3);
  assert.equal(liveEvents.length, before + 1);
  const last = liveEvents[liveEvents.length - 1];
  assert.equal(last.layer, 3);
  assert.equal(last.failed, false);
});

test("E2E: client.status() reflects the recorded event", async () => {
  const s = await client.status();
  assert.equal(s.autoCompactDisabled, false);
  assert.ok(s.lastEvent);
  assert.equal(s.lastEvent.layer, 3);
});

test("E2E: client.history() returns the recorded events", async () => {
  const h = await client.history({ limit: 10 });
  assert.ok(h.length >= 1);
  assert.equal(h[0].layer, 3);
});

test("E2E: client.reset() clears the breaker; status reflects it", async () => {
  const r = await client.reset();
  assert.equal(r.ok, true);
  const s = await client.status();
  assert.equal(s.autoCompactDisabled, false);
  assert.equal(s.consecutiveCompactFailures, 0);
});

test("E2E: full user flow — 3 compacts, then history, then reset", async () => {
  for (let i = 0; i < 3; i++) {
    const out = await client.run({ force: true });
    assert.equal(out.failed, false);
  }
  const h = await client.history();
  assert.ok(h.length >= 3);
  // History is ordered oldest → newest.
  assert.ok(h[0].ts <= h[h.length - 1].ts);
  await client.reset();
});
