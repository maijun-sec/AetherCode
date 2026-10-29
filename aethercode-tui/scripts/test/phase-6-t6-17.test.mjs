// Copyright (c) 2026 maijun

// Licensed under the MIT License.
// See the LICENSE file in the project root for the full license text.

// T-6-17 (Phase 6.3): the 9 new slash commands.
//
// 6 tests:
//   1. /continue → setContinuationStopped RPC (the session's auto-continue)
//   2. /pause → setContinuationStopped, /stop → cancel
//   3. /todos → __TODO_BOARD__ local token
//   4. /tokens → session/tokens RPC
//   5. /consents + /grants → __GRANTS_MANAGER__ (alias)
//   6. /model <name> → model/set + /workflow [name] → picker or workflow/run
//
//  Plus: tab-completion picks up the new commands.
//
// R375: tests 1 and 2 used to assert a REFUSAL — "/continue is known
// to be broken because `task/resume` does not exist". That is the
// wrong thing for a gate to protect: the /help text still advertised
// the command, and the honest refusal is a worse product than a
// working command. R375 points the two commands at the daemon methods
// that really exist (`setContinuationStopped`, `cancel`), so the tests
// now pin the WORKING shape.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdirSync, writeFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// Compile a shim that re-exports the commands module.
const tmp = join(root, "tmp-t6-17-tsc");
const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
if (existsSync(tmp)) {
  spawnSync(
    process.platform === "win32" ? "cmd" : "rm",
    process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", tmp] : ["-rf", tmp],
  );
}
mkdirSync(tmp, { recursive: true });
const shimPath = join(root, "src", "_t6_17_helpers.ts");
const shim = [
  // R369: routeSlash is the unrouted inner function. The capability
  // filter in handleSlash drops rpcParams, so this is the only thing
  // that can still pin the `? id` argument shape.
  `export { handleSlash, routeSlash, SLASH_COMMANDS, SLASH_HELP, completeSlash } from "./commands.js";`,
  "",
].join("\n");
writeFileSync(shimPath, shim, "utf-8");

const tscArgs = [
  "--outDir",
  join(tmp, "out"),
  "--target",
  "ES2022",
  "--module",
  "ES2022",
  "--moduleResolution",
  "bundler",
  "--jsx",
  "react",
  "--esModuleInterop",
  "true",
  "--skipLibCheck",
  "true",
  "--rootDir",
  join(root, "src"),
];
{
  const r = spawnSync(`"${tscBin}"`, [...tscArgs, `"${shimPath}"`], {
    encoding: "utf-8",
    shell: true,
  });
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
    process.exit(1);
  }
}
const helpers = await import(
  "file:///" + join(tmp, "out", "_t6_17_helpers.js").replace(/\\/g, "/")
);
// R369. This helper asserts a REFUSAL: the command must answer in
// words and never dispatch a method the daemon does not register.
//
// R375 removed three of its callers — `/stop`, `/pause` and
// `/continue` no longer refuse, because they now point at methods
// that really exist. `/tokens` still refuses: there is no per-session
// token RPC on this daemon.
//
// The param shape is asserted on `routeSlash`, the unrouted inner
// function, because the refusal no longer carries rpcParams — and
// losing that check would be a real gap, since it is the only thing
// pinning the `? id` form.
const { handleSlash, routeSlash, SLASH_COMMANDS, SLASH_HELP, completeSlash } = helpers;

/** The method a refusal is refusing, or undefined when it dispatched. */
function refusedMethod(res, expected) {
  assert.equal(
    res.rpcMethod,
    undefined,
    `${expected} must not dispatch — the daemon does not register it`,
  );
  assert.ok(
    res.local && res.local.includes(expected),
    `${expected} refusal should name the method it cannot run, got: ${res.local}`,
  );
}

// ----- 1. /continue → setContinuationStopped ---------------------------

test("T-6-17: /continue → setContinuationStopped {stopped:false}", () => {
  // R375: this test used to assert that `/continue` REFUSES, because
  // it asked for `task/resume` — a method the daemon this TUI spawns
  // does not register (`TaskMethods` proxies to a separate supervisor
  // process that production code never constructs).
  //
  // The real "let it keep going" is the session's auto-continue flag,
  // which the daemon DOES expose. So the command now dispatches
  // `setContinuationStopped {stopped:false}` — the same flag the
  // daemon's own countdown UI toggles.
  const r = handleSlash("/continue");
  assert.equal(r.rpcMethod, "setContinuationStopped");
  assert.deepEqual(r.rpcParams, { stopped: false });

  // /resume is an alias, with the same params.
  const alias = handleSlash("/resume");
  assert.equal(alias.rpcMethod, "setContinuationStopped");
  assert.deepEqual(alias.rpcParams, { stopped: false });

  // The unrouted form carries the same params — the capability filter
  // in handleSlash must not drop them.
  assert.deepEqual(routeSlash("/continue").rpcParams, { stopped: false });
  assert.equal(routeSlash("/continue").rpcMethod, "setContinuationStopped");
});

// ----- 2. /pause + /stop → setContinuationStopped + cancel --------------

test("T-6-17: /pause → setContinuationStopped and /stop → cancel", () => {
  // R375: same story as /continue, inverted. The daemon's own wording
  // for this is "Stop auto-continue" — it stops the session's
  // automatic continuation and cancels the pending countdown.
  const p = handleSlash("/pause");
  assert.equal(p.rpcMethod, "setContinuationStopped");
  assert.deepEqual(p.rpcParams, { stopped: true });

  // /stop is the interrupt of the run in flight — the same `cancel`
  // the Esc key sends (R363). With an argument it is the run id.
  const s = handleSlash("/stop");
  assert.equal(s.rpcMethod, "cancel");
  const s2 = handleSlash("/stop run-7");
  assert.equal(s2.rpcMethod, "cancel");
  assert.deepEqual(s2.rpcParams, { runId: "run-7" });
  // /kill alias
  assert.equal(handleSlash("/kill").rpcMethod, "cancel");
  assert.deepEqual(routeSlash("/kill run-7").rpcParams, { runId: "run-7" });
});

// ----- 3. /todos → __TODO_BOARD__ --------------------------------------

test("T-6-17: /todos → __TODO_BOARD__ local token", () => {
  const r1 = handleSlash("/todos");
  assert.equal(r1.local, "__TODO_BOARD__");
  // /todo singular alias.
  const r2 = handleSlash("/todo");
  assert.equal(r2.local, "__TODO_BOARD__");
});

// ----- 4. /tokens → session/tokens RPC ---------------------------------

test("T-6-17: /tokens → session/tokens RPC", () => {
  // R379: the daemon SERVES `session/tokens` now (per-session token
  // accounting in `CostTracker`), so the command DISPATCHES. R369
  // pinned the refusal; R379 pins the capability.
  assert.equal(handleSlash("/tokens").rpcMethod, "session/tokens");
  // /token singular alias.
  assert.equal(handleSlash("/token").rpcMethod, "session/tokens");
  assert.equal(routeSlash("/tokens").rpcMethod, "session/tokens");
});

// ----- 5. /consents + /grants → __GRANTS_MANAGER__ (alias) -------------

test("T-6-17: /consents and /grants both → __GRANTS_MANAGER__", () => {
  assert.equal(handleSlash("/consents").local, "__GRANTS_MANAGER__");
  assert.equal(handleSlash("/grants").local, "__GRANTS_MANAGER__");
});

// ----- 6. /model + /workflow -------------------------------------------

test("T-6-17: /model <name> → setModel + /workflow [name] → picker or runWorkflow", () => {
  // R366: this test pinned `model/set` and `workflow/run`, and its
  // title advertised them as the contract. **Both names do not exist
  // on the daemon the TUI spawns**, so the test was protecting two
  // broken commands rather than documenting two working ones.
  //
  //   * `model/set` has DTOs (protocol/rpc/model/ModelSetParams,
  //     Phase 1.2 / T-1-19) but no handler was ever registered.
  //   * `workflow/run` is registered on `SupervisorRpcServer`
  //     (aethercode-tasks), a separate CLI the TUI never starts.
  //
  // The real names are `setModel` and `runWorkflow`, both registered in
  // `AetherCodeMethods.registerAll`. Note the param key changes too:
  // `setModel` reads `model`, not `name`.
  // `r366-rpc-names.test.mjs` now verifies the names against the
  // daemon's registration list rather than against this file.
  const r1 = handleSlash("/model claude-opus-4-1");
  assert.equal(r1.rpcMethod, "setModel");
  assert.deepEqual(r1.rpcParams, { model: "claude-opus-4-1" });
  // /model without an arg is still a usage hint.
  const r2 = handleSlash("/model");
  assert.match(r2.local, /usage:/);
  // /workflow with no args → picker.
  const r3 = handleSlash("/workflow");
  assert.equal(r3.local, "__WORKFLOW_PICKER__");
  // /workflow with a name → runWorkflow. (Params happen to match, so
  // the rename alone was the whole fix here.)
  const r4 = handleSlash("/workflow tdd-feature");
  assert.equal(r4.rpcMethod, "runWorkflow");
  assert.deepEqual(r4.rpcParams, { name: "tdd-feature" });
});

// ----- 7. SLASH_COMMANDS + SLASH_HELP registration ---------------------

test("T-6-17: SLASH_COMMANDS + SLASH_HELP contain the 9 new commands", () => {
  for (const c of [
    "continue",
    "pause",
    "stop",
    "todos",
    "tokens",
    "consents",
    "grants",
    "workflow",
  ]) {
    assert.ok(SLASH_COMMANDS.includes(c), `SLASH_COMMANDS missing: ${c}`);
  }
  for (const c of [
    "/continue",
    "/pause",
    "/stop",
    "/todos",
    "/tokens",
    "/consents",
    "/grants",
    "/workflow",
  ]) {
    assert.ok(SLASH_HELP.includes(c), `SLASH_HELP missing: ${c}`);
  }
});

// ----- 8. Tab-completion -----------------------------------------------

test("T-6-17: tab-completion picks up the new commands", () => {
  // /con matches THREE commands — "consents", "context" and
  // "continue" (R371 added `/context`, and this assertion was never
  // updated, so it has been red since that round). The user needs to
  // type more characters.
  const c1 = completeSlash("/con");
  assert.equal(c1.completed, "/con");
  assert.deepEqual(c1.alternatives.sort(), ["consents", "context", "continue"]);
  assert.equal(c1.oneShot, false);
  // /tok → /tokens (unique)
  const c2 = completeSlash("/tok");
  assert.equal(c2.completed, "/tokens ");
  assert.equal(c2.oneShot, true);
  // /work → /workflow (unique)
  const c3 = completeSlash("/work");
  assert.equal(c3.completed, "/workflow ");
  assert.equal(c3.oneShot, true);
  // /continu is unique to /continue.
  const c4 = completeSlash("/continu");
  assert.equal(c4.completed, "/continue ");
  assert.equal(c4.oneShot, true);
  // /grant → /grants (unique — note /consents doesn't match).
  const c5 = completeSlash("/grant");
  assert.equal(c5.completed, "/grants ");
  assert.equal(c5.oneShot, true);
});
