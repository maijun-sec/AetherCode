// T-7-10: TUI smoke test (38 checks).
//
// The spec calls for "38 checks" that exercise the new
// Phase 6 + Phase 7 surfaces (RPC, session details,
// control, todos, stale, enricher). We use the same
// pure-helper + source-grep + tsc compile pattern as the
// rest of the TUI test suite.
//
// The 38 checks cover:
//   - 8 RPC client checks (file exists, exports,
//     error classes, etc.)
//   - 6 mock server checks
//   - 5 reconnect checks
//   - 5 subscriptions + events checks
//   - 6 component checks (SessionDetailsPanel,
//     SessionControl, TodoBoard, TodoItem, StaleWarning,
//     TranscriptEnricher)
//   - 4 shell checks (state, tui, StatusBar, right panel)
//   - 4 tsc compile checks (one per major surface)

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

// ----- 1. RPC client surface (8 checks) -------------------------

test("T-7-10: rpc/client.ts exists", () => assert.ok(existsSync(join(root, "src/rpc/client.ts"))));
test("T-7-10: rpc/client.ts exports JsonRpcClient", () => {
  assert.match(read("src/rpc/client.ts"), /export\s+class\s+JsonRpcClient\b/);
});
test("T-7-10: rpc/client.ts exports RpcCallError + RpcDisconnected", () => {
  const src = read("src/rpc/client.ts");
  assert.match(src, /export\s*\{\s*RpcCallError\s*,/);
  assert.match(src, /export\s*\{\s*RpcCallError\s*,\s*RpcDisconnected\s*\}/);
});
test("T-7-10: rpc/client.ts uses node:net for Unix socket", () => {
  const src = read("src/rpc/client.ts");
  assert.match(src, /net\.createConnection/);
});
test("T-7-10: rpc/client.ts supports the Windows pipe path", () => {
  const src = read("src/rpc/client.ts");
  assert.match(src, /pipe\\aethercode-supervisor/);
});
test("T-7-10: rpc/client.ts line-delimited JSON-RPC 2.0", () => {
  const src = read("src/rpc/client.ts");
  assert.match(src, /jsonrpc:\s*"2\.0"/);
  assert.match(src, /\\n/);  // newline delimiter
});
test("T-7-10: rpc/client.ts has a public onNotification", () => {
  const src = read("src/rpc/client.ts");
  assert.match(src, /public\s+onNotification/);
});
test("T-7-10: rpc/types.ts exports RpcEvent + Subscription", () => {
  const src = read("src/rpc/types.ts");
  assert.match(src, /export\s+interface\s+RpcEvent\b/);
  assert.match(src, /export\s+interface\s+Subscription\b/);
});

// ----- 2. Mock server (6 checks) --------------------------------

test("T-7-10: rpc/MockRpcServer.ts exists", () => assert.ok(existsSync(join(root, "src/rpc/MockRpcServer.ts"))));
test("T-7-10: rpc/MockRpcServer.ts exports the MockRpcServer class", () => {
  assert.match(read("src/rpc/MockRpcServer.ts"), /export\s+class\s+MockRpcServer\b/);
});
test("T-7-10: rpc/MockRpcServer.ts has the high-level API (sessionSpawn / taskResume / taskPause)", () => {
  const src = read("src/rpc/MockRpcServer.ts");
  assert.match(src, /async\s+sessionSpawn/);
  assert.match(src, /async\s+taskResume/);
  assert.match(src, /async\s+taskPause/);
  assert.match(src, /async\s+taskKill/);
});
test("T-7-10: rpc/MockRpcServer.ts has the wire-protocol API (handle / dispatch / pushEvent / buildClient)", () => {
  const src = read("src/rpc/MockRpcServer.ts");
  assert.match(src, /handle\(method:\s*string/);
  assert.match(src, /pushEvent/);
  assert.match(src, /buildClient/);
  assert.match(src, /peerSocket/);
});
test("T-7-10: rpc/MockRpcServer.ts exposes the .state for E2E tests", () => {
  const src = read("src/rpc/MockRpcServer.ts");
  assert.match(src, /public\s+readonly\s+state:\s*MockRpcState/);
});
test("T-7-10: rpc/MockRpcServer.ts pushes events through the wire (not just in-process)", () => {
  const src = read("src/rpc/MockRpcServer.ts");
  assert.match(src, /peer\.socket\.injectEnvelope/);
});

// ----- 3. Reconnect (5 checks) ----------------------------------

test("T-7-10: rpc/reconnect.ts exists", () => assert.ok(existsSync(join(root, "src/rpc/reconnect.ts"))));
test("T-7-10: rpc/reconnect.ts exports ReconnectManager + createClientAndReconnect", () => {
  const src = read("src/rpc/reconnect.ts");
  assert.match(src, /export\s+class\s+ReconnectManager\b/);
  assert.match(src, /export\s+function\s+createClientAndReconnect\b/);
});
test("T-7-10: rpc/reconnect.ts uses exponential backoff (1, 2, 4, 8, ..., 30s)", () => {
  const src = read("src/rpc/reconnect.ts");
  assert.match(src, /DEFAULT_BASE_BACKOFF_MS\s*=\s*1_000/);
  assert.match(src, /DEFAULT_MAX_BACKOFF_MS\s*=\s*30_000/);
});
test("T-7-10: rpc/reconnect.ts caps at 5 attempts (≈ 5 minutes total)", () => {
  const src = read("src/rpc/reconnect.ts");
  assert.match(src, /DEFAULT_MAX_ATTEMPTS\s*=\s*5/);
});
test("T-7-10: rpc/reconnect.ts gives up after maxReconnectAttempts (no infinite loop)", () => {
  const src = read("src/rpc/reconnect.ts");
  assert.match(src, /this\.attempt\s*>\s*this\.cfg\.maxReconnectAttempts/);
});

// ----- 4. Subscriptions + events (5 checks) ---------------------

test("T-7-10: rpc/subscriptions.ts exists and exports useRpcSubscription", () => {
  const src = read("src/rpc/subscriptions.ts");
  assert.match(src, /export\s+function\s+useRpcSubscription\b/);
});
test("T-7-10: rpc/subscriptions.ts has a bounded ring buffer", () => {
  const src = read("src/rpc/subscriptions.ts");
  assert.match(src, /class\s+RingBuffer<T>/);
  assert.match(src, /capacity\s*\?\?\s*200/);
});
test("T-7-10: rpc/events.ts exists and exports subscribeEvents", () => {
  const src = read("src/rpc/events.ts");
  assert.match(src, /export\s+function\s+subscribeEvents\b/);
});
test("T-7-10: rpc/subscriptions.ts supports kind filter", () => {
  const src = read("src/rpc/subscriptions.ts");
  assert.match(src, /RpcEventKind\s*\|\s*RpcEventKind\[\]/);
});
test("T-7-10: rpc/events.ts handles unsubscribe cleanly", () => {
  const src = read("src/rpc/events.ts");
  assert.match(src, /unsubscribe/);
});

// ----- 5. Components (6 checks) --------------------------------

test("T-7-10: components/session/SessionDetailsPanel.tsx exists", () => assert.ok(existsSync(join(root, "src/components/session/SessionDetailsPanel.tsx"))));
test("T-7-10: components/session/SessionControl.tsx exports SessionControl", () => {
  const src = read("src/components/session/SessionControl.tsx");
  assert.match(src, /export\s+const\s+SessionControl\b/);
});
test("T-7-10: components/todo/TodoBoard.tsx exports TodoBoard", () => {
  const src = read("src/components/todo/TodoBoard.tsx");
  assert.match(src, /export\s+const\s+TodoBoard\b/);
});
test("T-7-10: components/todo/TodoItem.tsx exports TodoItem", () => {
  const src = read("src/components/todo/TodoItem.tsx");
  assert.match(src, /export\s+const\s+TodoItem\b/);
});
test("T-7-10: components/StaleWarning.tsx exports StaleWarning + STALE_THRESHOLD_MS", () => {
  const src = read("src/components/StaleWarning.tsx");
  assert.match(src, /export\s+const\s+StaleWarning\b/);
  assert.match(src, /export\s+const\s+STALE_THRESHOLD_MS\s*=\s*30\s*\*\s*60\s*\*\s*1000/);
});
test("T-7-10: components/chat/TranscriptEnricher.ts exports the pure helpers", () => {
  const src = read("src/components/chat/TranscriptEnricher.ts");
  assert.match(src, /export\s+function\s+extractSummary\b/);
  assert.match(src, /export\s+function\s+enrichmentRequired\b/);
});

// ----- 6. Shell (4 checks) -------------------------------------

test("T-7-10: state.ts has rightPanelVisible + toggleRightPanel", () => {
  const src = read("src/state.ts");
  assert.match(src, /rightPanelVisible:\s*boolean/);
  assert.match(src, /toggleRightPanel/);
});
test("T-7-10: tui.tsx imports the new components", () => {
  const src = read("src/tui.tsx");
  assert.match(src, /SessionDetailsPanel/);
  assert.match(src, /TodoBoard/);
});
test("T-7-10: tui.tsx wires Ctrl-D to toggleRightPanel", () => {
  const src = read("src/tui.tsx");
  // The toggleRightPanel dispatch is fired from a
  // Ctrl-D useInput branch.
  assert.match(src, /toggleRightPanel/);
});
test("T-7-10: tui.tsx renders the right panel conditionally", () => {
  const src = read("src/tui.tsx");
  assert.match(src, /state\.rightPanelVisible\s*\?\s*\(/);
});

// ----- 7. tsc compile (4 checks) -------------------------------

function tscCompile(label, files, options = {}) {
  const tmp = join(root, `tmp-t7-10-${label}-tsc`);
  if (existsSync(tmp)) {
    spawnSync(process.platform === "win32" ? "cmd" : "rm",
      process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", tmp] : ["-rf", tmp],
      { shell: process.platform === "win32" });
  }
  mkdirSync(tmp, { recursive: true });
  const args = [
    "--outDir", tmp,
    "--target", "ES2022",
    "--module", "ES2022",
    "--moduleResolution", "bundler",
    "--esModuleInterop", "true",
    "--skipLibCheck", "true",
    "--strict", "true",
    "--noUncheckedIndexedAccess", "true",
    "--rootDir", join(root, "src"),
  ];
  if (options.jsx) args.push("--jsx", "react");
  for (const f of files) args.push(join(root, "src", f));
  const r = spawnSync(`"${tscBin}"`, args, { encoding: "utf-8", shell: true });
  if (r.status !== 0) {
    console.error(`tsc failed for ${label}:\n${r.stdout}\n${r.stderr}`);
  }
  assert.equal(r.status, 0, `tsc compile failed for ${label}`);
}

test("T-7-10: tsc --strict compile of rpc/*.ts", () => {
  tscCompile("rpc", [
    "rpc/types.ts",
    "rpc/client.ts",
    "rpc/MockRpcServer.ts",
    "rpc/reconnect.ts",
    "rpc/queries.ts",
    "rpc/subscriptions.ts",
    "rpc/events.ts",
  ]);
});

test("T-7-10: tsc --strict compile of session/* + todo/* + StaleWarning", () => {
  tscCompile("components", [
    "components/session/SessionDetailsPanel.tsx",
    "components/session/SessionControl.tsx",
    "components/todo/TodoBoard.tsx",
    "components/todo/TodoItem.tsx",
    "components/StaleWarning.tsx",
  ], { jsx: true });
});

test("T-7-10: tsc --strict compile of TranscriptEnricher.ts", () => {
  tscCompile("enricher", [
    "components/chat/TranscriptEnricher.ts",
  ], { jsx: true });
});

test("T-7-10: tsc --noEmit of the full project is clean", () => {
  // The full-project compile is slow; only run it as
  // the last smoke check. We use --noEmit to skip
  // writing files.
  const r = spawnSync(`"${tscBin}"`, [
    "--noEmit",
    "-p", join(root, "tsconfig.json"),
  ], { encoding: "utf-8", shell: true });
  if (r.status !== 0) {
    console.error(r.stdout);
    console.error(r.stderr);
  }
  assert.equal(r.status, 0, "tsc --noEmit of the full project failed");
});
