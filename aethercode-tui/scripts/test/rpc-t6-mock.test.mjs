// T-6-02: MockRpcServer.
//
// The MockRpcServer is the in-memory JSON-RPC peer used
// by every test in `rpc/`. It mimics the wire shape of
// the Java supervisor (newline-delimited JSON-RPC 2.0
// over a single bidirectional socket) so the test code
// can drive a real `JsonRpcClient` without a real socket.
//
// What we test:
//   1. Handler registration + invocation.
//   2. Session model (lastSeq, state, tokens).
//   3. pushEvent: increments lastSeq, drives the wire
//      through the same peer as the client.
//   4. streamEvents: yields pushed events in order;
//      sinceSeq filters older events out.
//   5. closeSession: future events are dropped.

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
  "dist/rpc/MockRpcServer.js",
  "rpc/MockRpcServer.js",
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
  const tmp = join(root, "tmp-t6-mock");
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
    throw new Error("tsc compile failed for MockRpcServer.ts:\n" + r.stdout + "\n" + r.stderr);
  }
  loaded = await import(pathToFileURL(join(tmp, "rpc", "MockRpcServer.js")).href);
}

const { MockRpcServer, ok, err } = loaded;

// ----- 2. Tests. -------------------------------------------------

test("T-6-02: handler registration + invocation (sync handler)", async () => {
  const mock = new MockRpcServer();
  mock.handle("echo", (params) => params);
  const client = mock.buildClient();
  const out = await client.request("echo", { hi: "there" });
  assert.deepEqual(out, { hi: "there" });
});

test("T-6-02: async handler (returns a promise)", async () => {
  const mock = new MockRpcServer();
  mock.handle("slow", async (params) => {
    await new Promise((r) => setTimeout(r, 10));
    return { ok: true, ms: 10 };
  });
  const client = mock.buildClient();
  const out = await client.request("slow");
  assert.equal(out.ok, true);
  assert.equal(out.ms, 10);
});

test("T-6-02: handler errors are wrapped as -32603 (Internal error)", async () => {
  const mock = new MockRpcServer();
  mock.handle("fail", () => { throw new Error("kaboom"); });
  const client = mock.buildClient();
  let caught = null;
  try {
    await client.request("fail");
  } catch (e) { caught = e; }
  assert.ok(caught, "expected an error");
  assert.equal(caught.code, -32603);
  assert.match(caught.message, /kaboom/);
});

test("T-6-02: pushEvent increments lastSeq and broadcasts on the wire", async () => {
  const mock = new MockRpcServer();
  const client = mock.buildClient();
  const sid = mock.listSessions()[0].id;
  const seen = [];
  client.onNotification = (method, params) => seen.push({ method, params });
  const ev1 = mock.pushEvent(sid, { kind: "todo_update", params: { items: [] } });
  const ev2 = mock.pushEvent(sid, { kind: "todo_update", params: { items: [{ id: "1" }] } });
  await new Promise((r) => setTimeout(r, 10));
  assert.equal(ev1.seq, 1);
  assert.equal(ev2.seq, 2);
  assert.equal(mock.getSession(sid).lastSeq, 2);
  assert.equal(seen.length, 2);
  assert.equal(seen[0].method, "todo_update");
});

test("T-6-02: streamEvents yields events in order; sinceSeq filters older", async () => {
  const mock = new MockRpcServer();
  const sid = "session-stream";
  mock.newSession(sid);
  for (let i = 0; i < 5; i++) {
    mock.pushEvent(sid, { kind: "x", params: { i } });
  }
  const collected = [];
  for await (const ev of mock.streamEvents(sid, -1)) {
    collected.push(ev);
    if (collected.length === 5) break;
  }
  assert.equal(collected.length, 5);
  for (let i = 0; i < 5; i++) {
    assert.equal(collected[i].params.i, i);
  }
  // Replay from seq 3 — only events 4 and 5 should be yielded.
  const replayed = [];
  for await (const ev of mock.streamEvents(sid, 3)) {
    replayed.push(ev);
    if (replayed.length === 2) break;
  }
  assert.equal(replayed.length, 2);
  assert.equal(replayed[0].params.i, 3);
  assert.equal(replayed[1].params.i, 4);
});

test("T-6-02: closeSession drops future events", () => {
  const mock = new MockRpcServer();
  const sid = mock.listSessions()[0].id;
  mock.closeSession(sid);
  assert.throws(() => mock.pushEvent(sid, { kind: "x", params: {} }));
});

test("T-6-02: newSession / getSession / listSessions / ensureSession", () => {
  const mock = new MockRpcServer();
  const a = mock.newSession("a");
  const b = mock.newSession("b");
  assert.equal(a.id, "a");
  assert.equal(b.id, "b");
  assert.equal(mock.getSession("a").id, "a");
  assert.equal(mock.getSession("missing"), undefined);
  const list = mock.listSessions();
  const ids = list.map((s) => s.id).sort();
  // default + a + b
  assert.deepEqual(ids, ["a", "b", mock.listSessions().find((s) => s.id !== "a" && s.id !== "b").id].sort());
  const c = mock.ensureSession("c");
  assert.equal(c.id, "c");
  // ensureSession is idempotent.
  const c2 = mock.ensureSession("c");
  assert.equal(c2.id, "c");
});

test("T-6-02: ok() / err() helpers produce valid envelopes", () => {
  const s = ok(7, { x: 1 });
  assert.equal(s.jsonrpc, "2.0");
  assert.equal(s.id, 7);
  assert.deepEqual(s.result, { x: 1 });
  const e = err(7, -32004, "nope", { detail: "x" });
  assert.equal(e.error.code, -32004);
  assert.equal(e.error.message, "nope");
  assert.deepEqual(e.error.data, { detail: "x" });
});
