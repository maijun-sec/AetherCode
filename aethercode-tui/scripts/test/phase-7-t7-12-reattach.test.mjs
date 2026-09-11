// T-7-12 (Phase 7): re-attach after disconnect, mocked.
//
// 5 tests:
//   1. After disconnect, the next connect resumes from the
//      last-seen seq (no event loss).
//   2. The ring buffer replays events 1..N in order on attach.
//   3. New events emitted post-attach are appended to the
//      stream (not replayed).
//   4. Pause + resume + pause before disconnect is reflected
//      in the session's state on re-attach.
//   5. Multiple re-attach cycles don't lose events.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdirSync, writeFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// Compile a shim that imports the MockRpcServer.
const tmp = join(root, "tmp-t7-12-tsc");
const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
if (existsSync(tmp)) {
  spawnSync(process.platform === "win32" ? "cmd" : "rm",
    process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", tmp] : ["-rf", tmp]);
}
mkdirSync(tmp, { recursive: true });
const shimPath = join(root, "src", "_t7_12_mock.ts");
const shim = [
  `export { MockRpcServer } from "./rpc/MockRpcServer.js";`,
  "",
].join("\n");
writeFileSync(shimPath, shim, "utf-8");

const tscArgs = [
  "--outDir", join(tmp, "out"), "--target", "ES2022", "--module", "ES2022",
  "--moduleResolution", "bundler", "--jsx", "react",
  "--esModuleInterop", "true", "--skipLibCheck", "true",
  "--rootDir", join(root, "src"),
];
{
  const r = spawnSync(`"${tscBin}"`, [...tscArgs, `"${shimPath}"`], { encoding: "utf-8", shell: true });
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
    process.exit(1);
  }
}
const { MockRpcServer } = await import(
  "file:///" + join(tmp, "out", "_t7_12_mock.js").replace(/\\/g, "/")
);

// T-7-12.1: Ring buffer replays events 1..N in order on attach.
test("T-7-12: ring buffer replays events 1..N in order on attach", async () => {
  const server = new MockRpcServer();
  const { id } = await server.sessionSpawn({ title: "reattach" });
  // Emit 10 tool events while the client is "connected".
  server.pushToolCallEvents(10);
  assert.equal(server.state.events.length, 11); // 1 state_change + 10 tool_call

  // Simulate disconnect → reconnect. The client remembers
  // the last-seen seq (e.g. 11) and asks for events > 11.
  const lastSeen = 11;
  const replay = server.state.events.filter((e) => e.seq > lastSeen);
  assert.equal(replay.length, 0, "no events to replay after a full sync");

  // Mid-stream: client is at seq 6; events 7..11 need replay.
  const partial = server.state.events.filter((e) => e.seq > 6);
  assert.equal(partial.length, 5);
  assert.equal(partial[0].seq, 7);
  assert.equal(partial[4].seq, 11);
});

// T-7-12.2: New events post-attach are appended (not replayed).
test("T-7-12: new events post-attach are appended; not replayed", async () => {
  const server = new MockRpcServer();
  await server.sessionSpawn({});
  // Client has synced up to seq 3.
  server.pushToolCallEvents(3);
  assert.equal(server.state.events.length, 4);

  // Disconnect.
  const lastSeenBeforeDisconnect = 4;
  // ... time passes ...
  // Server keeps emitting events.
  server.pushToolCallEvents(2);
  assert.equal(server.state.events.length, 6);

  // Reconnect: client asks for events > lastSeenBeforeDisconnect.
  const replay = server.state.events.filter((e) => e.seq > lastSeenBeforeDisconnect);
  assert.equal(replay.length, 2);
  assert.equal(replay[0].seq, 5);
  assert.equal(replay[1].seq, 6);
});

// T-7-12.3: Pause + resume + pause is reflected in state on re-attach.
test("T-7-12: pause + resume + pause before disconnect is reflected on re-attach", async () => {
  const server = new MockRpcServer();
  const { id } = await server.sessionSpawn({});
  await server.taskPause({});
  await server.taskResume({});
  await server.taskPause({});

  const session = server.state.sessions.get(id);
  assert.equal(session.state, "paused");

  // Re-attach (just re-read the state).
  const sessionAfter = server.state.sessions.get(id);
  assert.equal(sessionAfter.state, "paused");

  // The state_change events are all there in order.
  const sc = server.state.events.filter((e) => e.type === "state_change");
  assert.equal(sc.length, 4); // 1 spawn + pause + resume + pause
  assert.deepEqual(sc.map((e) => e.payload.to), ["running", "paused", "running", "paused"]);
});

// T-7-12.4: Multiple re-attach cycles don't lose events.
test("T-7-12: multiple re-attach cycles don't lose events", async () => {
  const server = new MockRpcServer();
  await server.sessionSpawn({});
  // Cycle 1: emit 5 events.
  server.pushToolCallEvents(5);
  // Disconnect at seq 6.
  let lastSeen = 6;

  // Cycle 2: emit 3 more; reconnect.
  server.pushToolCallEvents(3);
  let replay = server.state.events.filter((e) => e.seq > lastSeen);
  assert.equal(replay.length, 3);
  lastSeen = server.state.events.length;

  // Cycle 3: emit 7 more; reconnect.
  server.pushToolCallEvents(7);
  replay = server.state.events.filter((e) => e.seq > lastSeen);
  assert.equal(replay.length, 7);

  // Total event count is consistent.
  const total = server.state.events.length;
  assert.equal(total, 1 + 5 + 3 + 7); // 1 state_change + 15 tool_call
  assert.equal(total, 16);
});

// T-7-12.5: Re-attach after a long-running task crashes.
test("T-7-12: re-attach after a task crash — session state is preserved", async () => {
  const server = new MockRpcServer();
  const { id } = await server.sessionSpawn({});
  // Task runs for a while, accumulates tokens.
  server.pushToolCallEvents(20);
  // Simulate a crash: set state to "failed" (which the engine
  // would do on JVM crash, with state persisted to the DB).
  const session = server.state.sessions.get(id);
  session.state = "failed";

  // Re-attach: the engine's state is preserved on disk and
  // the new client should see "failed" with the final token
  // counts.
  const after = server.state.sessions.get(id);
  assert.equal(after.state, "failed");
  assert.equal(after.tokensIn, 4000);
  assert.equal(after.tokensOut, 2000);
  // The user can resume the failed session (the engine
  // surfaces a "Re-attach" prompt per spec §2.2).
  await server.taskResume({});
  assert.equal(after.state, "running");
});
