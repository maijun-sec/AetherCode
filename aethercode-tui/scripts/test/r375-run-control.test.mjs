// Copyright (c) 2026 maijun

// Licensed under the MIT License.
// See the LICENSE file in the project root for the full license text.

// R375 — the run-control surface: three verbs that actually work.
//
// ## What was wrong
//
// The status bar's Continue/Pause/Stop buttons and the `/pause`
// `/stop` `/continue` slash commands all called
//
//     void client.request("task/resume", { id: state.sessionId });
//     void client.request("task/pause",  { id: state.sessionId });
//     void client.request("task/kill",   { id: state.sessionId });
//
// Three separate failures, stacked:
//
//   1. **The methods do not exist on this daemon.** `TaskMethods`
//      (aethercode-protocol) DOES declare `task/resume` and
//      `task/kill` — but it is an adapter that PROXIES them to a
//      `SupervisorClient`, i.e. a separate supervisor process with its
//      own TCP loopback socket, which production code never
//      constructs. `task/pause` is not even in its list: the
//      `TaskPauseParams` DTO exists, the method does not.
//   2. **The parameter was the wrong kind of id.** A task id was
//      expected; a SESSION id was sent.
//   3. **The rejection was swallowed.** There was no `.catch`, so the
//      -32601 became an unhandled rejection and the button silently
//      did nothing.
//
// The refusal table (R369) then told the user "the daemon has no task
// registry, so there is no running task to pause" — false. The daemon
// HAS a task registry (`TaskRegistry`, behind `listTasks` /
// `updateTaskStatus`); what it does not have is a SUPERVISOR, and the
// capability the user actually wanted — stop the run in flight, pause
// this session's automatic continuation — IS on the daemon.
//
// ## The mapping R375 installs
//
//   | action   | daemon method            | what it does                        |
//   |----------|--------------------------|-------------------------------------|
//   | stop     | `cancel {runId}`         | interrupts the run in flight (R721) |
//   | pause    | `setContinuationStopped` | stops this session's auto-continue  |
//   | continue | `setContinuationStopped` | lets the auto-continue happen again |
//
// "Pause" is the industry meaning of the word — VS Code's Copilot Chat
// and Claude Code both have a session-level pause, where the session
// keeps its state and the AUTOMATIC work stops. "Stop" is the
// interrupt of the turn that is running right now, which is exactly
// what Esc already did — so the button and the key now share ONE
// implementation instead of two diverging ones.
//
// ## What this gate holds
//
// The round's real subject is not "do the buttons call the right
// name" — it is "is the thing the user pressed actually wired to
// something that exists". So this gate checks FOUR layers, and a
// defect in any one of them is invisible to the others:
//
//   A. the daemon registers the methods (both transports)
//   B. the shared vocabulary maps the three verbs onto them
//   C. every surface goes through ONE implementation
//   D. the Continue button is reachable at all
//
// Layer A is the one that R366 already taught: read the REGISTRATION
// POINT, never the call site. Layer D is the one nobody was looking
// for: `SessionControl.validActions("running")` is {pause, stop}, so
// Continue was permanently greyed out and the pause/continue pair was
// unreachable from the keyboard too.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdirSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { codeOf } from "./strip-comments.mjs";
import { javaCode } from "./java-strip.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// --- compile the modules under test ----------------------------------------

const tmp = join(root, "tmp-r375-run-control");
const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");

if (existsSync(tmp)) {
  if (process.platform === "win32") spawnSync("cmd", ["/c", "rmdir", "/s", "/q", tmp]);
  else spawnSync("rm", ["-rf", tmp]);
}
mkdirSync(tmp, { recursive: true });

// One invocation for four modules. `SessionControl.tsx` needs the JSX
// flag; the rest are plain TS.
{
  const r = spawnSync(
    `"${tscBin}"`,
    [
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
      `"${join(root, "src", "runControl.ts")}"`,
      `"${join(root, "src", "state.ts")}"`,
      `"${join(root, "src", "commands.ts")}"`,
      `"${join(root, "src", "components", "session", "SessionControl.tsx")}"`,
    ],
    { encoding: "utf-8", shell: true },
  );
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
    process.exit(1);
  }
}

const load = (rel) => import(pathToFileURL(join(tmp, "out", rel)).href);
const runControl = await load("runControl.js");
const stateMod = await load("state.js");
const commandsMod = await load("commands.js");
const sessionControl = await load("components/session/SessionControl.js");

// --- sources ----------------------------------------------------------------

const readSrc = (rel) => readFileSync(join(root, rel), "utf-8");
const tuiCode = codeOf(readSrc("src/tui.tsx"), "tui.tsx");
const lineCode = codeOf(readSrc("src/line.ts"), "line.ts");
const commandsCode = codeOf(readSrc("src/commands.ts"), "commands.ts");
const runControlCode = codeOf(readSrc("src/runControl.ts"), "runControl.ts");
const statusBarCode = codeOf(readSrc("src/components/StatusBar.tsx"), "StatusBar.tsx");
const capsCode = codeOf(readSrc("src/rpcCapabilities.ts"), "rpcCapabilities.ts");

// --- the java side ----------------------------------------------------------

const javaRoot = join(root, "..", "aethercode");
const methodsPath = join(
  javaRoot,
  "aethercode-protocol",
  "src",
  "main",
  "java",
  "org",
  "aethercode",
  "protocol",
  "methods",
  "AetherCodeMethods.java",
);
const httpPath = join(
  javaRoot,
  "aethercode-protocol",
  "src",
  "main",
  "java",
  "org",
  "aethercode",
  "protocol",
  "http",
  "HttpJsonRpcServer.java",
);
const dispatcherPath = join(
  javaRoot,
  "aethercode-protocol",
  "src",
  "main",
  "java",
  "org",
  "aethercode",
  "protocol",
  "methods",
  "EngineContinuationDispatcher.java",
);
const taskMethodsPath = join(
  javaRoot,
  "aethercode-protocol",
  "src",
  "main",
  "java",
  "org",
  "aethercode",
  "protocol",
  "methods",
  "TaskMethods.java",
);
const runnerPath = join(
  javaRoot,
  "aethercode-cli",
  "src",
  "main",
  "java",
  "org",
  "aethercode",
  "cli",
  "DaemonRunner.java",
);

const JAVA_SOURCES = [methodsPath, httpPath, dispatcherPath, taskMethodsPath, runnerPath];
const hasJava = JAVA_SOURCES.every((p) => existsSync(p));

/**
 * Body of the function whose declaration starts at `decl`.
 *
 * Scoped to the function, so an assertion can never leak onto a
 * same-named token in a neighbouring function — and so it cannot be
 * broken by a comment added somewhere else in the file.
 *
 * `closeMarker` is the closing-brace indent: the TS renderers close a
 * top-level function with two spaces, Java with four. Getting this
 * wrong does not fail loudly — it returns null and the test says
 * "re-anchor this test", which is a misleading message for what is
 * really a wrong indent.
 */
function bodyAfter(code, decl, closeMarker = "\n  }") {
  const start = code.indexOf(decl);
  if (start < 0) return null;
  const end = code.indexOf(closeMarker, start);
  return end > start ? code.slice(start, end) : null;
}

/** The JSX element that opens with `<tag`, up to its `/>`. */
function jsxBlock(code, tag) {
  const start = code.indexOf("<" + tag);
  if (start < 0) return null;
  const end = code.indexOf("/>", start);
  return end > start ? code.slice(start, end) : null;
}

// --- A. the daemon registers the methods ------------------------------------

test("A1: the daemon registers BOTH run-control methods", () => {
  if (!hasJava) return; // skip rather than a false green
  const j = javaCode(readFileSync(methodsPath, "utf-8"));
  for (const m of ["cancel", "setContinuationStopped"]) {
    assert.match(
      j,
      new RegExp(`dispatcher\\.register\\(\\s*"${m}"`),
      `${m} is not registered on the daemon the TUI spawns — every call is -32601`,
    );
  }
});

test("A2: BOTH transports route the run-control methods", () => {
  // Two transports, two registries, one bug class. The stdio daemon
  // uses `JsonRpcDispatcher` + registerAll; the HTTP+WS daemon uses a
  // hand-rolled `case "..." ->` switch. Adding a method to one does
  // not add it to the other (Lesson 1022 — "two-layer fix").
  if (!hasJava) return;
  const j = javaCode(readFileSync(httpPath, "utf-8"));
  for (const m of ["cancel", "setContinuationStopped"]) {
    assert.match(
      j,
      new RegExp(`case\\s+"${m}"\\s*->`),
      `${m} is not routed by HttpJsonRpcServer's switch — the desktop gets -32601`,
    );
  }
});

test("A3: the daemon is the ONLY thing that can answer — task/* is not wired", () => {
  // The heart of the round: the old buttons named a method that
  // belongs to a service this daemon does not run.
  if (!hasJava) return;
  const runner = javaCode(readFileSync(runnerPath, "utf-8"));
  assert.doesNotMatch(
    runner,
    /TaskMethods/,
    "DaemonRunner now wires TaskMethods — re-check whether task/* is really " +
      "unreachable before trusting this test's premise",
  );
  const task = javaCode(readFileSync(taskMethodsPath, "utf-8"));
  assert.doesNotMatch(
    task,
    /"task\/pause"/,
    "TaskMethods now has a task/pause — the old table's claim that it does not " +
      "exist has changed, so this gate's premise needs re-deriving",
  );
  // It is a SUPERVISOR proxy, which is why production code never
  // constructs it.
  assert.match(
    task,
    /SupervisorClient/,
    "TaskMethods is no longer a supervisor proxy — re-derive why the TUI " + "cannot reach it",
  );
});

test("A4: setContinuationStopped defaults the session and the flag the way the TUI assumes", () => {
  // Two load-bearing defaults:
  //
  //   * a blank sessionId falls back to the engine's CURRENT session
  //     — which is why the TUI deliberately sends NO session id (the
  //     line renderer holds the placeholder "—" until the daemon's
  //     first getState lands, and a blank check would not catch it);
  //   * a MISSING `stopped` defaults to TRUE — so the "continue"
  //     verb MUST send `stopped:false` explicitly. Sending nothing
  //     would PAUSE, not resume.
  if (!hasJava) return;
  const j = javaCode(readFileSync(methodsPath, "utf-8"));
  const body = bodyAfter(j, "public Object setContinuationStopped(Object params)", "\n    }");
  assert.ok(body, "setContinuationStopped handler not found (re-anchor this test)");
  assert.match(
    body,
    /sessionId\.isBlank\(\)/,
    "a blank sessionId must fall back to the engine's current session",
  );
  assert.match(
    body,
    /engine\.appState\(\)\.sessionId\(\)/,
    "the fallback must be the engine's CURRENT session",
  );
  assert.match(
    body,
    /:\s*true;/,
    "a missing `stopped` must default to true — the TUI's continue verb " +
      "depends on sending `stopped:false` explicitly",
  );
});

test("A5: pausing stops the countdown immediately; resuming only lifts the flag", () => {
  // `setContinuationStopped(true)` must ALSO cancel the pending
  // countdown, or "pause" would take effect only after the 2s window
  // — i.e. the button would look broken for two seconds every time.
  if (!hasJava) return;
  const j = javaCode(readFileSync(dispatcherPath, "utf-8"));
  const body = bodyAfter(
    j,
    "public void setContinuationStopped(String sessionId, boolean stopped)",
    "\n    }",
  );
  assert.ok(body, "the dispatcher's setContinuationStopped not found (re-anchor this test)");
  assert.match(body, /if \(stopped\) \{[\s\S]*?stoppedBySession\.put\(/, "pause must SET the flag");
  assert.match(
    body,
    /cancelPendingContinuationCountdown\(/,
    "pause must cancel the pending countdown, or it takes effect only after the window",
  );
  assert.match(body, /\} else \{[\s\S]*?stoppedBySession\.remove\(/, "resume must REMOVE the flag");
});

test("A6: a fresh user prompt clears the daemon's own flag", () => {
  // The TUI clears its MIRROR of the flag when the user submits a
  // prompt. That is only honest if the daemon does the same — and
  // it does, in the query handler.
  if (!hasJava) return;
  const j = javaCode(readFileSync(methodsPath, "utf-8"));
  assert.match(
    j,
    /setContinuationStopped\(_targetSid, false\)/,
    "the query handler no longer clears the auto-continue flag — the TUI's " +
      "mirror would drift from the daemon's state",
  );
});

// --- B. the shared vocabulary ------------------------------------------------

test("B1: the three verbs map onto the two real methods", () => {
  assert.equal(runControl.runMethod("stop"), "cancel");
  assert.equal(runControl.runMethod("pause"), "setContinuationStopped");
  assert.equal(runControl.runMethod("continue"), "setContinuationStopped");
  assert.equal(runControl.RUN_STOP_METHOD, "cancel");
  assert.equal(runControl.RUN_PAUSE_METHOD, "setContinuationStopped");
});

test("B2: stop sends the run id; pause and continue send the flag and NOTHING else", () => {
  assert.deepEqual(runControl.runParams("stop", { runId: "run-7" }), { runId: "run-7" });
  assert.deepEqual(runControl.runParams("pause"), { stopped: true });
  assert.deepEqual(runControl.runParams("continue"), { stopped: false });

  // A missing run id must still produce a well-formed params object —
  // `{runId: undefined}` serialises to `{}` on the wire, which the
  // daemon would read as "no run id" with no clue why.
  const noRun = runControl.runParams("stop", {});
  assert.ok(noRun && typeof noRun === "object", "stop must always send an object");
  assert.ok("runId" in noRun, "stop must always carry a runId key");

  // The pause verbs must NOT carry a session id: the daemon's blank
  // check would not catch the line renderer's "—" placeholder, so it
  // would happily set the flag for a session named "—".
  for (const action of ["pause", "continue"]) {
    const p = runControl.runParams(action);
    assert.ok(
      !("sessionId" in p),
      `${action} must not send a sessionId — the daemon would take the "—" ` +
        "placeholder literally",
    );
  }
});

test("B3: every outcome says what HAPPENED, and a failure names the reason", () => {
  // A success must not claim more than the daemon has confirmed: the
  // run's own run_end is what confirms the stop, so the copy says
  // "stopping", not "stopped".
  const stop = runControl.runOutcome("stop", true, { runId: "run-7" });
  assert.match(stop, /stopping/i);
  assert.doesNotMatch(
    stop,
    /\bstopped\b/i,
    "the success copy must not claim the stop already happened",
  );

  assert.match(runControl.runOutcome("pause", true), /paused/i);
  assert.match(runControl.runOutcome("continue", true), /resumed/i);

  for (const action of ["stop", "pause", "continue"]) {
    const failed = runControl.runOutcome(action, false, { error: "boom" });
    assert.match(
      failed,
      /boom/,
      `${action}'s failure copy must name the reason the user cannot act on`,
    );
    assert.ok(failed.length > 10, `${action}'s failure copy is too terse: ${failed}`);
  }
});

test("B4: an idempotent action says so instead of firing a pointless request", () => {
  assert.ok(runControl.runAlreadyInState("pause"), "pause must be able to say 'already paused'");
  assert.ok(runControl.runAlreadyInState("continue"), "continue must be able to say 'not paused'");
  assert.equal(
    runControl.runAlreadyInState("stop"),
    null,
    "stop is always worth doing — 'nothing is running' is a useful answer",
  );
});

test("B5: 'there is nothing to stop' is a sentence, not a failure", () => {
  const s = runControl.runNothingInFlight();
  assert.ok(s.length > 20, `too terse to be a user-facing sentence: ${s}`);
  assert.doesNotMatch(s, /failed/i, "nothing broke — there was simply no run to stop");
});

test("B6: a paused session maps to the paused state", () => {
  assert.equal(runControl.runControlState("running", true), "paused");
  assert.equal(runControl.runControlState("running", false), "running");
  assert.equal(runControl.runControlState("completed", false), "completed");
  assert.equal(runControl.runControlState("failed", false), "failed");
  assert.equal(runControl.runControlState(undefined, false), "running");
});

// --- C. one implementation, three surfaces -----------------------------------

test("C1: the Ink renderer has exactly ONE stop and ONE pause implementation", () => {
  const stops = tuiCode.match(/async function stopActiveRun/g) || [];
  const pauses = tuiCode.match(/async function pauseAutoContinue/g) || [];
  assert.equal(
    stops.length,
    1,
    `expected one stopActiveRun in tui.tsx, found ${stops.length} — two ` +
      "implementations of one verb is how they drift apart",
  );
  assert.equal(
    pauses.length,
    1,
    `expected one pauseAutoContinue in tui.tsx, found ${pauses.length}`,
  );
});

test("C2: the line renderer has exactly ONE stop and ONE pause implementation", () => {
  const stops = lineCode.match(/async function stopActiveRun/g) || [];
  const pauses = lineCode.match(/async function pauseAutoContinue/g) || [];
  assert.equal(stops.length, 1, `expected one stopActiveRun in line.ts, found ${stops.length}`);
  assert.equal(
    pauses.length,
    1,
    `expected one pauseAutoContinue in line.ts, found ${pauses.length}`,
  );
});

test("C3: the status bar's three buttons call the shared implementations", () => {
  // Not "the names appear somewhere" — the PROPS of the mounted
  // element. The old wiring called `client.request("task/pause", …)`
  // right here, with the rejection swallowed.
  const sb = jsxBlock(tuiCode, "StatusBar");
  assert.ok(sb, "the StatusBar mount not found in tui.tsx (re-anchor this test)");
  assert.match(
    sb,
    /onContinue=\{\(\)\s*=>\s*\{[\s\S]*?pauseAutoContinue\(false\)/,
    "the Continue button must resume the session's automatic continuation",
  );
  assert.match(
    sb,
    /onPause=\{\(\)\s*=>\s*\{[\s\S]*?pauseAutoContinue\(true\)/,
    "the Pause button must pause the session's automatic continuation",
  );
  assert.match(
    sb,
    /onStop=\{\(\)\s*=>\s*\{[\s\S]*?stopActiveRun\(\)/,
    "the Stop button must use the same stop implementation as Esc",
  );
  assert.doesNotMatch(
    sb,
    /task\//,
    "the status bar still names a task/* method — this daemon does not register it",
  );
});

test("C4: the slash commands reach the same implementations", () => {
  // The command table may only NAME a method. The run id is filled in
  // by the host, which knows the active run.
  const stop = commandsMod.routeSlash("/stop");
  assert.equal(stop.rpcMethod, "cancel");
  const withRun = commandsMod.routeSlash("/stop run-7");
  assert.equal(withRun.rpcMethod, "cancel");
  assert.deepEqual(withRun.rpcParams, { runId: "run-7" });
  assert.equal(commandsMod.routeSlash("/pause").rpcMethod, "setContinuationStopped");
  assert.equal(commandsMod.routeSlash("/continue").rpcMethod, "setContinuationStopped");
  assert.equal(commandsMod.routeSlash("/resume").rpcMethod, "setContinuationStopped");
  assert.equal(commandsMod.routeSlash("/kill").rpcMethod, "cancel");
});

test("C5: the commands are NOT refused — the capability filter lets them through", () => {
  // R369 refused these three because they named methods that do not
  // exist. Now that they name methods that DO, the filter must let
  // them dispatch — a refusal here would be a new lie.
  for (const input of ["/stop", "/pause", "/continue", "/resume", "/kill"]) {
    const res = commandsMod.handleSlash(input);
    assert.ok(res?.rpcMethod, `${input} no longer dispatches — it was refused again`);
    assert.ok(
      !res?.local || !/not available|cannot|unable/i.test(res.local),
      `${input} was refused in words: ${res?.local}`,
    );
  }
});

test("C6: the three surfaces share the vocabulary module", () => {
  for (const [name, code] of [
    ["tui.tsx", tuiCode],
    ["line.ts", lineCode],
  ]) {
    assert.match(
      code,
      /from "\.\/runControl\.js"/,
      `${name} must read the run-control vocabulary from src/runControl.ts`,
    );
  }
  assert.match(
    statusBarCode,
    /from "\.\.\/runControl\.js"/,
    "StatusBar.tsx must read the session-state mapping from src/runControl.ts",
  );
  assert.doesNotMatch(
    statusBarCode,
    /function mapSessionState/,
    "StatusBar.tsx grew a second copy of the session-state mapping — one " +
      "definition, or the two will disagree",
  );
});

test("C7: nothing in the TUI names a method the daemon cannot serve", () => {
  const SOURCES = [
    ["src/tui.tsx", tuiCode],
    ["src/line.ts", lineCode],
    ["src/commands.ts", commandsCode],
    ["src/runControl.ts", runControlCode],
    ["src/rpcCapabilities.ts", capsCode],
  ];
  const offenders = [];
  for (const [rel, code] of SOURCES) {
    for (const m of code.matchAll(/["'](task\/[a-zA-Z]+)["']/g)) {
      offenders.push(`${m[1]}  <- ${rel}`);
    }
  }
  assert.deepEqual(
    offenders,
    [],
    "the TUI names task/* methods, which this daemon does not register:\n  " +
      offenders.join("\n  "),
  );
});

// --- D. the Continue button is reachable -------------------------------------

test("D1: a paused session makes Continue a VALID action", () => {
  // The dead-UI defect: `validActions("running")` is {pause, stop}, so
  // Continue was greyed out — and there was no way to reach the
  // paused state, because pause was wired to a method that does not
  // exist. The chain has to hold end to end.
  const state = runControl.runControlState("running", true);
  const valid = sessionControl.validActions(state);
  assert.ok(
    valid.has("continue"),
    `a paused session must offer Continue — validActions(${state}) = ` + `${[...valid].join(",")}`,
  );
});

test("D2: a running session offers Pause and Stop but not Continue", () => {
  // The converse, so D1 cannot be satisfied by making everything
  // valid.
  const valid = sessionControl.validActions(runControl.runControlState("running", false));
  assert.ok(valid.has("pause"), "a running session must offer Pause");
  assert.ok(valid.has("stop"), "a running session must offer Stop");
  assert.ok(!valid.has("continue"), "Continue must be unavailable while the session is not paused");
});

// --- the mirror the TUI keeps -----------------------------------------------

test("E1: the TUI mirrors the daemon's auto-continue flag", () => {
  const { INITIAL, reducer } = stateMod;
  assert.equal(INITIAL.continuationPaused, false, "a fresh session is not paused");
  const paused = reducer(INITIAL, { type: "setContinuationPaused", paused: true });
  assert.equal(paused.continuationPaused, true);
  // Idempotent: the same value must not churn the state object.
  assert.equal(
    reducer(paused, { type: "setContinuationPaused", paused: true }),
    paused,
    "setting the same value must return the identical state object",
  );
});

test("E2: a new prompt clears the mirror, because the daemon clears its flag", () => {
  const { INITIAL, reducer } = stateMod;
  const paused = reducer(INITIAL, { type: "setContinuationPaused", paused: true });
  const submitted = reducer({ ...paused, input: "next", submitting: false }, { type: "submit" });
  assert.equal(
    submitted.continuationPaused,
    false,
    "the daemon clears the flag on the next prompt (A6) — a mirror that " +
      "survives it would make the status bar lie",
  );

  // A SECOND, independent derivation of the same property: the
  // reducer's `submit` case must NAME the field it resets. One
  // derivation is one that can be silently neutered (R374 Lesson
  // 1177) — this one is a source pin over the compiled-away
  // comments, so it survives an edit to the behavioural assertion.
  const stateCode = codeOf(readSrc("src/state.ts"), "state.ts");
  const submitCase = stateCode.slice(stateCode.indexOf('case "submit":'));
  assert.ok(submitCase.length > 0, "the submit case not found (re-anchor this test)");
  assert.match(
    submitCase,
    /continuationPaused:\s*false/,
    "the submit case must reset the mirror explicitly",
  );
});

test("E3: switching sessions resets the mirror — the flag belongs to a session", () => {
  const { INITIAL, reducer } = stateMod;
  const paused = reducer(INITIAL, { type: "setContinuationPaused", paused: true });
  const adopted = reducer(paused, {
    type: "adoptSession",
    sessionId: "session-b",
    turns: [],
  });
  assert.equal(
    adopted.continuationPaused,
    false,
    "the auto-continue flag is per-session; carrying the mirror across a " +
      "session switch makes the status bar lie about the new session",
  );
});

// --- failures are visible ---------------------------------------------------

test("F1: a failed stop is reported, not swallowed", () => {
  const body = bodyAfter(tuiCode, "async function stopActiveRun");
  assert.ok(body, "stopActiveRun not found (re-anchor this test)");
  assert.match(body, /catch\s*\(/, "a failed stop must be caught");
  assert.match(
    body,
    /kind:\s*"err"/,
    "a failed stop must be shown as an error — the old wiring had no " +
      "catch at all, so the -32601 vanished",
  );
  assert.match(
    body,
    /runOutcome\(\s*"stop"\s*,\s*false/,
    "the failure copy must come from the shared vocabulary",
  );
});

test("F2: a failed pause is reported, not swallowed", () => {
  const body = bodyAfter(tuiCode, "async function pauseAutoContinue");
  assert.ok(body, "pauseAutoContinue not found (re-anchor this test)");
  assert.match(body, /catch\s*\(/, "a failed pause must be caught");
  assert.match(body, /kind:\s*"err"/, "a failed pause must be shown as an error");
  assert.match(
    body,
    /runOutcome\(\s*action\s*,\s*false/,
    "the failure copy must come from the shared vocabulary",
  );
});

test("F3: the line renderer reports failures too", () => {
  const body = bodyAfter(lineCode, "async function pauseAutoContinue");
  assert.ok(body, "line mode's pauseAutoContinue not found (re-anchor this test)");
  assert.match(body, /catch\s*\(/, "line mode must catch a failed pause");
  assert.match(
    body,
    /runOutcome\(\s*action\s*,\s*false/,
    "the failure copy must come from the shared vocabulary",
  );
});

// --- the inventory ----------------------------------------------------------

test("G1: the refusal table is EMPTY — the daemon serves every method the TUI names", () => {
  // A stale entry is invisible to the R366 gate: that gate asks
  // "is every method the TUI sends registered?", and an entry for a
  // method the TUI no longer sends trips neither question. So the
  // inventory is pinned here.
  //
  // R379 closed the last three gaps on the daemon:
  // `agent/setDefault` (the agent registry owns a default and the
  // daemon installs its body into the system prompt), `rewind`
  // (`AppState.truncateTranscript` + `Transcript.truncateTo`) and
  // `session/tokens` (`CostTracker` keeps a per-session bucket).
  // A refusal for a method the daemon serves is a LIE — R377's lesson.
  const names = [...capsCode.matchAll(/\[\s*\n?\s*"([a-z][\w/]*)",\s*\n?\s*"([^"]+)"/g)].map(
    (m) => m[1],
  );
  assert.deepEqual(
    names.sort(),
    [],
    "the refusal table must be empty: the daemon serves every method the TUI " +
      "names. A refusal for a method that EXISTS tells the user a working " +
      "feature is impossible — the exact defect R377 found with effort/set.",
  );
});

test("G2: the refusal table still refuses what it names", () => {
  // The table is only honest if the filter really consults it. (The
  // r369 gate owns the per-command behaviour; this pins the seam.)
  assert.match(
    commandsCode,
    /unsupportedReason\(result\?\.rpcMethod\)/,
    "handleSlash must consult the shared table",
  );
  // And the two commands that used to be refused now DISPATCH: the
  // daemon serves them since R379. A filter that outlived its table
  // would keep swallowing them.
  for (const [input, method] of [
    ["/rewind 3", "rewind"],
    ["/tokens", "session/tokens"],
  ]) {
    const res = commandsMod.handleSlash(input);
    assert.equal(
      res?.rpcMethod,
      method,
      `${input} must dispatch to ${method} — the daemon serves it since R379`,
    );
  }
});
