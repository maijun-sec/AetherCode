// Copyright (c) 2026 maijun
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

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
import { compileShim, importCompiled } from "./shim-compile.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// Compile a shim that imports the MockRpcServer. We keep this
// in src/ so the relative import (`./rpc/MockRpcServer.js`)
// works through tsc.
// R388d: the shim is compiled in a TEMP MIRROR of `src/`, not
// in the checked-in tree. See `./shim-compile.mjs` — fifteen gates
// used to overwrite a TRACKED source file with a generated one-liner
// and never restored it, so every run of this suite silently
// stripped the licence header from it.
const shimRel = ["_t7_06_mock.ts"];
const shim = [`export { MockRpcServer } from "./rpc/MockRpcServer.js";`, ""].join("\n");
const compiledShim = compileShim({
  root,
  tmp: join(root, "tmp-t7-06-tsc"),
  shimRel: shimRel.join("/"),
  body: shim,
  extraArgs: [],
});
const helpers = await importCompiled(compiledShim);
const { MockRpcServer } = helpers;

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
