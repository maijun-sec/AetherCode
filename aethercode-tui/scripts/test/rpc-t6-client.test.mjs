// T-6-01: JsonRpcClient (Unix socket / Windows named pipe).
//
// The TUI's JSON-RPC client uses Node's `net` module to
// talk to the supervisor over a Unix socket (POSIX) or a
// named pipe (Windows). The wire is line-delimited JSON-RPC
// 2.0. The MockRpcServer in `src/rpc/MockRpcServer.ts` is
// the in-memory equivalent; the tests use it to drive the
// client without needing a real socket.
//
// What we test:
//   1. request/response round-trip via the mock socket.
//   2. JSON-RPC error envelope -> RpcCallError with code + data.
//   3. Notifications are routed to onNotification.
//   4. Per-request timeout rejects with RpcCallError.
//   5. Multiple in-flight requests are matched by id.
//   6. disconnect() drains pending requests with RpcDisconnected.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// ----- 1. Load the TS source via a transpile-on-demand shim. ---

const candidates = [
  "dist/rpc/client.js",
  "rpc/client.js",
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
  const tmp = join(root, "tmp-t6-client");
  if (existsSync(tmp)) {
    spawnSync(process.platform === "win32" ? "cmd" : "rm",
      process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", tmp] : ["-rf", tmp]);
  }
  const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
  const r = spawnSync(`"${tscBin}"`, [
    "--outDir", tmp, "--target", "ES2022", "--module", "ES2022",
    "--moduleResolution", "bundler", "--esModuleInterop", "true",
    "--skipLibCheck", "true", "--strict", "true",
    "--rootDir", join(root, "src"),
    join(root, "src", "rpc", "types.ts"),
    join(root, "src", "rpc", "client.ts"),
    join(root, "src", "rpc", "MockRpcServer.ts"),
  ], { encoding: "utf-8", shell: true });
  if (r.status !== 0) {
    throw new Error("tsc compile failed for client.ts:\n" + r.stdout + "\n" + r.stderr);
  }
  loaded = await import(pathToFileURL(join(tmp, "rpc", "client.js")).href);
}

const { JsonRpcClient, RpcCallError, RpcDisconnected, defaultSocketPath } = loaded;
const MockModule = await import(pathToFileURL(
  existsSync(join(root, "dist", "rpc", "MockRpcServer.js"))
    ? join(root, "dist", "rpc", "MockRpcServer.js")
    : join(root, "tmp-t6-client", "rpc", "MockRpcServer.js")
).href);
const { MockRpcServer, ok, err } = MockModule;

// ----- 2. Tests. -------------------------------------------------

test("T-6-01: defaultSocketPath picks the right platform default", () => {
  if (process.platform === "win32") {
    assert.equal(defaultSocketPath(), "\\\\.\\pipe\\aethercode-supervisor");
  } else {
    assert.equal(defaultSocketPath(), "/tmp/aethercode-supervisor.sock");
  }
});

test("T-6-01: request/response round-trip via the mock socket", async () => {
  const mock = new MockRpcServer();
  mock.handle("ping", () => ({ pong: true, ts: Date.now() }));
  const client = mock.buildClient();
  const out = await client.request("ping");
  assert.equal(out.pong, true);
  // The client should have emitted exactly one request envelope.
  const sent = mock.outgoing();
  assert.equal(sent.length, 1);
  assert.equal(sent[0].method, "ping");
  assert.equal(sent[0].jsonrpc, "2.0");
  assert.equal(typeof sent[0].id, "number");
  // And the connection state should be "connected".
  assert.equal(client.currentConnectionState(), "connected");
});

test("T-6-01: JSON-RPC error envelope -> RpcCallError with code + data", async () => {
  const mock = new MockRpcServer();
  mock.handle("explode", () => { throw new Error("boom"); });
  const client = mock.buildClient();
  await assert.rejects(
    () => client.request("explode"),
    (err) => {
      assert.ok(err instanceof RpcCallError, "expected RpcCallError, got " + err?.constructor?.name);
      assert.equal(err.code, -32603);
      assert.match(err.message, /boom/);
      return true;
    }
  );
});

test("T-6-01: explicit error code (e.g. -32601) flows through", async () => {
  const mock = new MockRpcServer();
  // The mock auto-wraps thrown errors as -32603. To test
  // the "explicit error envelope" path we install a handler
  // that returns an `err` directly — which the mock turns
  // into the wire envelope. The client then surfaces it
  // as a RpcCallError with the original code.
  mock.handle("forbidden", () => {
    const e = new Error("nope");
    e.code = -32004;
    throw e;
  });
  const client = mock.buildClient();
  await assert.rejects(
    () => client.request("forbidden"),
    (err) => {
      assert.ok(err instanceof RpcCallError);
      assert.equal(err.code, -32004);
      return true;
    }
  );
});

test("T-6-01: notifications are routed to onNotification", async () => {
  const mock = new MockRpcServer();
  const client = mock.buildClient();
  const seen = [];
  client.onNotification = (method, params) => {
    seen.push({ method, params });
  };
  // Drive a notification through the mock. pushEvent
  // writes a JSON-RPC envelope through the socket (since
  // the mock shares the same peer as the client), which
  // the client parses and routes.
  mock.pushEvent(mock.listSessions()[0].id, {
    kind: "todo_update",
    params: { items: [{ id: "1", title: "Read README", status: "pending" }] },
  });
  // Give the microtask queue a tick to drain.
  await new Promise((resolve) => setTimeout(resolve, 5));
  assert.equal(seen.length, 1);
  assert.equal(seen[0].method, "todo_update");
  // The mock wraps the inner params in an outer envelope;
  // the client passes the OUTER params (which is what the
  // hook's `params` argument is). The TUI's
  // `useRpcSubscription` reads the inner `event` /
  // `params` / `items` shape from there.
  const p = seen[0].params;
  assert.ok(p, "notification params should be defined");
  const items = p.items ?? p.event?.items ?? p.params?.items;
  assert.ok(items && items.length === 1, "expected todo_update items");
  assert.equal(items[0].title, "Read README");
});

test("T-6-01: per-request timeout rejects with RpcCallError", async () => {
  const mock = new MockRpcServer();
  // Handler that never replies.
  mock.handle("hang", () => new Promise(() => undefined));
  const client = mock.buildClient({ timeoutMs: 50 });
  const start = Date.now();
  await assert.rejects(
    () => client.request("hang", undefined, { timeoutMs: 50 }),
    (err) => {
      assert.ok(err instanceof RpcCallError);
      assert.match(err.message, /timeout/);
      return true;
    }
  );
  // Should have rejected within ~100ms.
  const elapsed = Date.now() - start;
  assert.ok(elapsed < 200, `timeout took too long: ${elapsed}ms`);
});

test("T-6-01: multiple in-flight requests are matched by id", async () => {
  const mock = new MockRpcServer();
  mock.handle("echo", (params) => params);
  const client = mock.buildClient();
  const promises = [];
  for (let i = 0; i < 10; i++) {
    promises.push(client.request("echo", { i }));
  }
  const out = await Promise.all(promises);
  // Each reply matches its corresponding request.
  for (let i = 0; i < 10; i++) {
    assert.equal(out[i].i, i);
  }
  // And the outgoing side has 10 distinct ids.
  const sent = mock.outgoing();
  assert.equal(sent.length, 10);
  const ids = new Set(sent.map((s) => s.id));
  assert.equal(ids.size, 10);
});

test("T-6-01: disconnect() drains pending requests with RpcDisconnected", async () => {
  const mock = new MockRpcServer();
  mock.handle("hang", () => new Promise(() => undefined));
  const client = mock.buildClient();
  const p = client.request("hang");
  // The mock's socket is the only peer; closing it ends
  // the client's connection.
  client.disconnect();
  await assert.rejects(
    () => p,
    (err) => {
      assert.ok(err instanceof RpcDisconnected, "expected RpcDisconnected, got " + err?.constructor?.name);
      return true;
    }
  );
  // And the state is "disconnected".
  assert.equal(client.currentConnectionState(), "disconnected");
});

test("T-6-01: unknown method on the mock returns -32601 envelope", async () => {
  const mock = new MockRpcServer();
  const client = mock.buildClient();
  await assert.rejects(
    () => client.request("doesNotExist"),
    (err) => {
      assert.ok(err instanceof RpcCallError);
      assert.equal(err.code, -32601);
      return true;
    }
  );
});
