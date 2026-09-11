// T-7-06 (Phase 7): E2E test — full session lifecycle.
//
// One long test, mocked (uses the in-process MockRpcServer):
//   spawn → run → pause → resume → kill
//
// Verifies the full state-machine wiring of the long-running
// task machinery: each transition fires the right RPC, the
// session's state reflects each transition, and the event
// stream emits the corresponding state_change events.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdirSync, writeFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// Compile a shim that imports the MockRpcServer. We keep this
// in src/ so the relative import (`./rpc/MockRpcServer.js`)
// works through tsc.
const tmp = join(root, "tmp-t7-06-tsc");
const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
if (existsSync(tmp)) {
  spawnSync(process.platform === "win32" ? "cmd" : "rm",
    process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", tmp] : ["-rf", tmp]);
}
mkdirSync(tmp, { recursive: true });
const shimPath = join(root, "src", "_t7_06_mock.ts");
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
  "file:///" + join(tmp, "out", "_t7_06_mock.js").replace(/\\/g, "/")
);

// The actual E2E.
test("T-7-06: full session lifecycle — spawn → run → pause → resume → kill", async () => {
  const server = new MockRpcServer();
  const stateChanges = [];
  server.on("event", (ev) => {
    if (ev.type === "state_change") {
      stateChanges.push({
        from: ev.payload.from ?? null,
        to: ev.payload.to,
      });
    }
  });

  // 1. Spawn.
  const { id } = await server.sessionSpawn({ title: "T-7-06", model: "claude-sonnet-4-5" });
  assert.ok(id, "expected a session id");
  const session = server.state.sessions.get(id);
  assert.ok(session, "session should be in the map");
  assert.equal(session.state, "running");
  assert.equal(server.state.modelName, "claude-sonnet-4-5");
  assert.equal(server.state.currentSessionId, id);
  assert.equal(stateChanges.length, 1);
  assert.deepEqual(stateChanges[0], { from: null, to: "running" });

  // 2. Run — push some tool events; verify the session
  //    accumulates tokens and stays running.
  server.pushToolCallEvents(5);
  assert.equal(session.tokensIn, 1000);
  assert.equal(session.tokensOut, 500);
  assert.ok(session.totalCostUsd > 0);

  // 3. Pause.
  await server.taskPause({});
  assert.equal(session.state, "paused");
  assert.equal(stateChanges.length, 2);
  assert.deepEqual(stateChanges[1], { from: "running", to: "paused" });

  // 4. Resume.
  await server.taskResume({});
  assert.equal(session.state, "running");
  assert.equal(stateChanges.length, 3);
  assert.deepEqual(stateChanges[2], { from: "paused", to: "running" });

  // 5. Push more events while running.
  server.pushToolCallEvents(3);
  assert.equal(session.tokensIn, 1600);
  assert.equal(session.tokensOut, 800);

  // 6. Kill.
  await server.taskKill({});
  assert.equal(session.state, "cancelled");
  assert.equal(stateChanges.length, 4);
  assert.deepEqual(stateChanges[3], { from: "running", to: "cancelled" });

  // 7. session/tokens reflects the final state.
  const tokens = await server.sessionTokens({});
  assert.equal(tokens.input, 1600);
  assert.equal(tokens.output, 800);
  assert.equal(tokens.total, 2400);
});
