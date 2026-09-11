// T-194: compact-rpc.ts (typed RPC surface for the TUI).
//
// The compact-rpc module has three concrete classes and a small
// adapter factory. We exercise each here with hand-rolled stubs
// (no real daemon) so the test stays fast and doesn't need a
// JVM.
//
// Coverage:
//   1. `RemoteCompactRpc.run/status/reset/history` — proxy path.
//   2. `LocalCompactRpc` + `buildLocalInvoke` adapter.
//   3. `NullCompactRpc` for the headless boot path.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// ----- 1. Load the TS source via a transpile-on-demand shim. ---

import { pathToFileURL } from "node:url";

const candidates = [
  "dist/compact-rpc.js",
  "compact-rpc.js",
];
let loaded = null;
for (const c of candidates) {
  const p = join(root, c);
  if (existsSync(p)) {
    loaded = await import(pathToFileURL(p).href);
    break;
  }
}
if (!loaded) {
  // Use tsc to compile to a tmp dir, then import the .js.
  const tmp = join(root, "tmp-r194");
  if (existsSync(tmp)) {
    spawnSync(process.platform === "win32" ? "cmd" : "rm",
      process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", tmp] : ["-rf", tmp]);
  }
  const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
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
  loaded = await import(pathToFileURL(join(tmp, "compact-rpc.js")).href);
}

const {
  RemoteCompactRpc,
  LocalCompactRpc,
  NullCompactRpc,
  buildLocalInvoke,
  CompactRpcError,
  RpcCallError,
} = loaded;

// ----- 2. Tests on the proxy / remote path. -------------------

function makeFakeRpcClient(handlers) {
  const calls = [];
  let notificationHandler = null;
  return {
    calls,
    request(method, params) {
      calls.push({ method, params });
      const h = handlers[method];
      if (h) return Promise.resolve(h(params));
      return Promise.reject(new Error("unknown method: " + method));
    },
    setNotificationHandler(fn) { notificationHandler = fn; },
    fire(method, params) {
      if (notificationHandler) notificationHandler(method, params);
    },
  };
}

test("T-194: RemoteCompactRpc proxies compact/run to the daemon", async () => {
  const fake = makeFakeRpcClient({
    "compact/run": (_params) => ({
      layer: 3,
      beforeTokens: 1000,
      afterTokens: 400,
      ms: 50,
      failed: false,
    }),
  });
  const client = new RemoteCompactRpc(fake);
  const out = await client.run({ force: true });
  assert.equal(out.layer, 3);
  assert.equal(out.beforeTokens, 1000);
  assert.equal(out.afterTokens, 400);
  assert.equal(out.ms, 50);
  assert.equal(out.failed, false);
  assert.equal(fake.calls.length, 1);
  assert.equal(fake.calls[0].method, "compact/run");
  assert.deepEqual(fake.calls[0].params, { force: true });
});

test("T-194: RemoteCompactRpc proxies compact/status", async () => {
  const fake = makeFakeRpcClient({
    "compact/status": () => ({
      autoCompactDisabled: false,
      consecutiveCompactFailures: 0,
      lastFailureTs: null,
      lastEvent: { ts: 100, layer: 3, beforeTokens: 1000, afterTokens: 400, elapsedMs: 50, failed: false },
    }),
  });
  const client = new RemoteCompactRpc(fake);
  const out = await client.status();
  assert.equal(out.autoCompactDisabled, false);
  assert.ok(out.lastEvent);
  assert.equal(out.lastEvent.layer, 3);
});

test("T-194: RemoteCompactRpc proxies compact/reset", async () => {
  const fake = makeFakeRpcClient({
    "compact/reset": () => ({ ok: true, clearedFailures: 3 }),
  });
  const client = new RemoteCompactRpc(fake);
  const out = await client.reset();
  assert.equal(out.ok, true);
  assert.equal(out.clearedFailures, 3);
});

test("T-194: RemoteCompactRpc proxies compact/history with a limit", async () => {
  const fake = makeFakeRpcClient({
    "compact/history": (params) => {
      assert.equal(params.limit, 5);
      return [
        { ts: 1, layer: 3, beforeTokens: 100, afterTokens: 50, elapsedMs: 10, failed: false },
        { ts: 2, layer: 1, beforeTokens: 60, afterTokens: 30, elapsedMs: 5, failed: false },
      ];
    },
  });
  const client = new RemoteCompactRpc(fake);
  const out = await client.history({ limit: 5 });
  assert.equal(out.length, 2);
  assert.equal(out[0].ts, 1);
  assert.equal(out[1].layer, 1);
});

test("T-194: RemoteCompactRpc raises CompactRpcError when the daemon returns one", async () => {
  // The JsonRpcClient's request() rejects with RpcCallError when
  // the daemon returns a JSON-RPC error response. Our fake mirrors
  // that: when the handler throws an Error, request() rejects with
  // it. We throw a synthetic RpcCallError so we can assert both
  // the message AND that the wrapper is the right class.
  const fake = makeFakeRpcClient({
    "compact/run": () => { throw new RpcCallError(-32603, "internal", { stack: "x" }); },
  });
  const client = new RemoteCompactRpc(fake);
  await assert.rejects(client.run(), (e) => {
    // The client re-throws whatever request() rejected with, so we
    // expect RpcCallError. CompactRpcError is reserved for our
    // own LocalCompactRpc path.
    assert.ok(e instanceof RpcCallError);
    assert.equal(e.code, -32603);
    return true;
  });
});

test("T-194: RemoteCompactRpc onEvent dispatches compact/event notifications", async () => {
  const fake = makeFakeRpcClient({});
  const client = new RemoteCompactRpc(fake);
  const events = [];
  client.onEvent((e) => events.push(e));
  fake.fire("compact/event", { ts: 100, layer: 3, beforeTokens: 1000, afterTokens: 400, elapsedMs: 50, failed: false });
  fake.fire("not/compact", { ignored: true });
  assert.equal(events.length, 1);
  assert.equal(events[0].ts, 100);
});

test("T-194: RemoteCompactRpc onEvent dispose removes the handler", async () => {
  const fake = makeFakeRpcClient({});
  const client = new RemoteCompactRpc(fake);
  const events = [];
  const dispose = client.onEvent((e) => events.push(e));
  fake.fire("compact/event", { ts: 1, layer: 1, beforeTokens: 1, afterTokens: 1, elapsedMs: 1, failed: false });
  dispose();
  fake.fire("compact/event", { ts: 2, layer: 1, beforeTokens: 1, afterTokens: 1, elapsedMs: 1, failed: false });
  assert.equal(events.length, 1);
  assert.equal(events[0].ts, 1);
});

// ----- 3. Local + adapter path. --------------------------------

function makeLocalInvokeStub(overrides = {}) {
  const listeners = new Set();
  return {
    async run(params) {
      return overrides.run ? overrides.run(params) : { layer: 1, beforeTokens: 100, afterTokens: 50, ms: 10, failed: false };
    },
    async status() {
      return overrides.status ? overrides.status() : { autoCompactDisabled: false, consecutiveCompactFailures: 0, lastFailureTs: null, lastEvent: null };
    },
    async reset() {
      return overrides.reset ? overrides.reset() : { ok: true, clearedFailures: 0 };
    },
    async history(params) {
      return overrides.history ? overrides.history(params) : [];
    },
    onEvent(h) { listeners.add(h); },
    fire(event) { for (const l of listeners) l(event); },
  };
}

test("T-194: LocalCompactRpc surfaces every method", async () => {
  const inv = makeLocalInvokeStub({
    run: (p) => ({ layer: 3, beforeTokens: 1000, afterTokens: 400, ms: 50, failed: p.force === true }),
    status: () => ({ autoCompactDisabled: true, consecutiveCompactFailures: 5, lastFailureTs: 1234, lastEvent: null }),
    reset: () => ({ ok: true, clearedFailures: 5 }),
    history: () => [{ ts: 1, layer: 1, beforeTokens: 1, afterTokens: 1, elapsedMs: 1, failed: false }],
  });
  const client = new LocalCompactRpc(inv);
  const r = await client.run({ force: true });
  assert.equal(r.layer, 3);
  assert.equal(r.failed, true);

  const s = await client.status();
  assert.equal(s.autoCompactDisabled, true);
  assert.equal(s.consecutiveCompactFailures, 5);

  const r2 = await client.reset();
  assert.equal(r2.ok, true);
  assert.equal(r2.clearedFailures, 5);

  const h = await client.history({ limit: 10 });
  assert.equal(h.length, 1);
});

test("T-194: buildLocalInvoke adapts a shim into a LocalInvoke", async () => {
  const shim = {
    run: (p) => ({ result: { layer: 3, beforeTokens: 100, afterTokens: 40, ms: 10, failed: !!p.force } }),
    status: () => ({ result: { autoCompactDisabled: false, consecutiveCompactFailures: 0, lastFailureTs: null, lastEvent: null } }),
    reset: () => ({ result: { ok: true, clearedFailures: 0 } }),
    history: (p) => ({ result: p.limit ? [{ ts: 1, layer: 1, beforeTokens: 1, afterTokens: 1, elapsedMs: 1, failed: false }] : [] }),
  };
  const inv = buildLocalInvoke(shim);
  const client = new LocalCompactRpc(inv);
  const r = await client.run({ force: true });
  assert.equal(r.failed, true);
  const h = await client.history({ limit: 1 });
  assert.equal(h.length, 1);
});

test("T-194: LocalCompactRpc onEvent forwards to the invoke's listeners", async () => {
  const inv = makeLocalInvokeStub();
  const client = new LocalCompactRpc(inv);
  const seen = [];
  client.onEvent((e) => seen.push(e));
  inv.fire({ ts: 1, layer: 1, beforeTokens: 1, afterTokens: 1, elapsedMs: 1, failed: false });
  inv.fire({ ts: 2, layer: 1, beforeTokens: 1, afterTokens: 1, elapsedMs: 1, failed: false });
  assert.equal(seen.length, 2);
});

// ----- 4. NullCompactRpc: headless fallback. -------------------

test("T-194: NullCompactRpc.run returns failed=true with reason=no daemon", async () => {
  const c = new NullCompactRpc();
  const r = await c.run();
  assert.equal(r.failed, true);
  assert.equal(r.failureReason, "no daemon");
});

test("T-194: NullCompactRpc.status/reset/history return benign defaults", async () => {
  const c = new NullCompactRpc();
  const s = await c.status();
  assert.equal(s.autoCompactDisabled, false);
  const r = await c.reset();
  assert.equal(r.ok, true);
  const h = await c.history();
  assert.deepEqual(h, []);
});

test("T-194: NullCompactRpc.onEvent returns a no-op dispose", () => {
  const c = new NullCompactRpc();
  const dispose = c.onEvent(() => { throw new Error("must not be called"); });
  assert.equal(typeof dispose, "function");
  assert.doesNotThrow(() => dispose());
});

// ----- 5. Source-grep tests ------------------------------------

const src = readFileSync(join(root, "src", "compact-rpc.ts"), "utf-8");

test("T-194: compact-rpc.ts exports the four RPC shapes", () => {
  assert.match(src, /export class RemoteCompactRpc/);
  assert.match(src, /export class LocalCompactRpc/);
  assert.match(src, /export class NullCompactRpc/);
  assert.match(src, /export function buildLocalInvoke/);
  assert.match(src, /export class CompactRpcError/);
  assert.match(src, /export type CompactRunParams/);
  assert.match(src, /export type CompactRunResult/);
  assert.match(src, /export type CompactStatusResult/);
  assert.match(src, /export type CompactResetResult/);
  assert.match(src, /export type CompactHistoryParams/);
  assert.match(src, /export type CompactEvent/);
  assert.match(src, /export interface CompactRpcClient/);
});

test("T-194: RemoteCompactRpc talks compact/run, compact/status, compact/reset, compact/history", () => {
  assert.match(src, /"compact\/run"/);
  assert.match(src, /"compact\/status"/);
  assert.match(src, /"compact\/reset"/);
  assert.match(src, /"compact\/history"/);
});

test("T-194: RemoteCompactRpc subscribes to compact/event notifications", () => {
  assert.match(src, /"compact\/event"/);
  assert.match(src, /setNotificationHandler/);
});
