// R379 — the three gaps the TUI used to REFUSE are closed, the
// auto-continuation countdown is surfaced, and the panels' key tables
// are GENERATED instead of hand-written.
//
// ## 1. The three capabilities
//
// Until R379 the TUI's refusal table carried three entries. Each was a
// REAL gap in the daemon (not a name mismatch like `effort/set` was),
// so the honest fix was to build the capability:
//
//   - `agent/setDefault` — `AgentRegistry` owns a default agent and the
//     daemon installs its body into the system prompt, so the marker is
//     a behaviour and not a label nobody reads;
//   - `rewind` — `AppState.truncateTranscript` + `Transcript.truncateTo`
//     roll the conversation back, in memory AND on disk;
//   - `session/tokens` — `CostTracker` keeps a per-session bucket and
//     `QueryEngine` books usage against the live session.
//
// The gate pins the whole chain: the daemon REGISTERS the method on
// stdio, the HTTP switch ROUTES it, the TUI DISPATCHES it (the refusal
// table is empty), and the daemon really DOES the thing.
//
// ## 2. The countdown
//
// The daemon schedules an automatic turn and says so
// (`todo_continuation_countdown`). Neither renderer had a case for the
// notification, so a turn began on its own with no warning at all.
// Claude Code says "Continuing automatically at 3:45pm · esc to cancel";
// the TUI says the same, and the "esc" half is wired to the RPC that
// actually stops the continuation.
//
// ## 3. The panels' key tables
//
// R378 made the app-level help generated. The panels were left out,
// and they have the same defect: every panel owns a `useInput` handler
// AND renders a hand-written key hint. The two drift — `GrantsManager`
// binds j/k but does not mention them, `MemoryPanel` binds Tab and does
// not mention it, `CwdSwitcher` promises an Enter its handler does not
// bind.
//
// The fix is R378's: the key table lives in ONE place
// (`src/componentKeys.ts`), the panel renders the hint from it, and
// THIS gate cross-checks the table against the handler in both
// directions.
//
// Lesson 1197 — a second copy of a truth drifts, and it drifts
// silently. The cure is not "be careful when editing": it is that
// there is no second copy.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { javaCode, javaMethod } from "./java-strip.mjs";
import { codeOf } from "./strip-comments.mjs";
import { loadTsModule } from "./load-ts-module.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const repo = join(root, "..");
const java = join(repo, "aethercode");

// --- the daemon's RPC surface ---------------------------------------------

const methodsPath = join(
  java,
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
  java,
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
const hasDaemon = existsSync(methodsPath) && existsSync(httpPath);
const methodsSrc = hasDaemon ? readFileSync(methodsPath, "utf-8") : "";
const httpSrc = hasDaemon ? readFileSync(httpPath, "utf-8") : "";

const stdioMethods = new Set();
for (const m of methodsSrc.matchAll(/dispatcher\.register\(\s*"([^"]+)"/g)) stdioMethods.add(m[1]);
const httpMethods = new Set();
for (const m of httpSrc.matchAll(/case\s+"([^"]+)"\s*->/g)) httpMethods.add(m[1]);

// --- the TUI ---------------------------------------------------------------

const capsMod = await loadTsModule(root, "src/rpcCapabilities.ts");
const BACKEND_MISSING = capsMod.BACKEND_MISSING;

const cmdMod = await loadTsModule(root, "src/commands.ts");
const keysMod = await loadTsModule(root, "src/componentKeys.ts");
const countdownMod = await loadTsModule(root, "src/continuationCountdown.ts");
const reportsMod = await loadTsModule(root, "src/rpcReports.ts");

const tuiSrc = readFileSync(join(root, "src", "tui.tsx"), "utf-8");
const lineSrc = readFileSync(join(root, "src", "line.ts"), "utf-8");
const commandsSrc = readFileSync(join(root, "src", "commands.ts"), "utf-8");

const THE_THREE = ["agent/setDefault", "rewind", "session/tokens"];

// =========================================================================
// 1. the three capabilities
// =========================================================================

test("the daemon registers and routes all three, on BOTH transports", () => {
  assert.ok(hasDaemon, "the daemon's Java sources are missing — the gate cannot check anything");
  for (const name of THE_THREE) {
    assert.ok(
      stdioMethods.has(name),
      `the daemon does not register "${name}" on the stdio dispatcher — the TUI would get -32601`,
    );
    assert.ok(
      httpMethods.has(name),
      `HttpJsonRpcServer does not route "${name}" — the desktop would get -32601 for a ` +
        "capability the engine really has",
    );
  }
});

test("the refusal table is EMPTY — the daemon serves everything the TUI names", () => {
  // R377's lesson, in its strongest form: a refusal for a method the
  // daemon SERVES is a lie, and it is the worst kind of lie — it
  // tells the user a working feature is impossible.
  assert.equal(
    BACKEND_MISSING.size,
    0,
    `the refusal table still carries ${BACKEND_MISSING.size} entr(y|ies) — the daemon serves ` +
      `those methods now, so a refusal would be a lie: ${[...BACKEND_MISSING.keys()].join(", ")}`,
  );
});

test("the TUI dispatches all three instead of refusing them", () => {
  const cases = [
    ["/rewind 3", "rewind"],
    ["/tokens", "session/tokens"],
    ["/agent-pick-set-default alpha", "agent/setDefault"],
  ];
  for (const [input, method] of cases) {
    const res = cmdMod.handleSlash(input);
    assert.equal(
      res?.rpcMethod,
      method,
      `${input} must dispatch to ${method}, got: ${res?.rpcMethod ?? res?.local}`,
    );
    assert.equal(res?.local, undefined, `${input} answered in words instead of dispatching`);
  }
});

test("the daemon's rewind really ROLLS THE TRANSCRIPT BACK", () => {
  // The name is not the behaviour. A handler that registers `rewind`
  // and returns ok:true without touching the transcript would pass
  // every check above — and the user would be told their conversation
  // went back when it did not.
  const body = javaMethod(javaCode(methodsSrc), "public Object rewind(Object params)");
  assert.ok(body, "the daemon's rewind handler could not be extracted — re-anchor this test");
  assert.match(
    body,
    /engine\.rewind\(/,
    "the rewind handler must hand the target to the engine — a stub that answers ok:true " +
      "without touching the transcript would be a fake success",
  );
});

test("the daemon's session/tokens really READS A PER-SESSION BUCKET", () => {
  const body = javaMethod(javaCode(methodsSrc), "public Object sessionTokens(Object params)");
  assert.ok(body, "the daemon's sessionTokens handler could not be extracted — re-anchor");
  assert.match(
    body,
    /sessionSummary\(/,
    "session/tokens must read the PER-SESSION summary — a handler that returned the " +
      "process-wide total would be the pre-R379 shape",
  );
});

test("the daemon's agent/setDefault really INSTALLS the agent's instructions", () => {
  const body = javaMethod(javaCode(methodsSrc), "public Object setDefaultAgent(Object params)");
  assert.ok(body, "the daemon's setDefaultAgent handler could not be extracted — re-anchor");
  assert.match(
    body,
    /engine\.setDefaultAgent\(/,
    "the handler must go through the engine — the engine is what installs the agent's " +
      "body into the system prompt",
  );
});

// =========================================================================
// 2. the countdown
// =========================================================================

test("the daemon emits the countdown the TUI listens for", () => {
  const dispatcher = join(
    java,
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
  assert.ok(existsSync(dispatcher), "EngineContinuationDispatcher.java is missing — re-anchor");
  const src = readFileSync(dispatcher, "utf-8");
  const code = javaCode(src);

  // R379 — the method name must live in ONE place in the daemon.
  //
  // It used to live in TWO (the constant's declaration and the call),
  // and that is exactly how this assertion first went blind: the
  // mutation deleted the EMISSION, the literal survived in the
  // constant, and the gate stayed green on a daemon that had stopped
  // counting down. Two copies of a truth drift silently — and here
  // they drifted in the direction that hides the bug (Lesson 1197).
  const literals = [...code.matchAll(/"todo_continuation_countdown"/g)].length;
  assert.equal(
    literals,
    1,
    `the countdown's method name appears ${literals} times in EngineContinuationDispatcher.java — ` +
      "it must live in exactly ONE place (the constant), so that deleting the emission really " +
      "deletes the name",
  );

  // And the constant must really be EMITTED. A constant nobody
  // notifies is a marker nobody reads — R379's lesson, on the daemon
  // side this time.
  assert.match(
    code,
    /notifyCustom\(\s*NOTIFY_TODO_CONTINUATION_COUNTDOWN\b/,
    "the daemon must EMIT the countdown — a constant that is only declared is a marker nobody reads",
  );

  // The payload the TUI parses has to be the payload the daemon sends.
  // Read from the CODE, not from the raw file: a field name in a
  // comment is not a payload field.
  for (const field of ["sessionId", "runId", "incompleteCount", "totalCount", "remainingMs"]) {
    assert.ok(
      code.includes(`"${field}"`),
      `the daemon's countdown payload has no "${field}" — the TUI's parser expects it`,
    );
  }
});

test("BOTH renderers handle the countdown", () => {
  const method = countdownMod.COUNTDOWN_METHOD;
  assert.equal(method, "todo_continuation_countdown", "the method name is the contract");

  // R379 — the WORD BOUNDARY is load-bearing.
  //
  // The first version of this assertion was `code.includes("COUNTDOWN_METHOD")`,
  // which also matches `COUNTDOWN_METHOD_DISABLED` — so a mutation that
  // renamed the case arm (and so stopped handling the notification)
  // sailed through, and the battery reported a hole. A prefix match is
  // not a match: `_` is a word character, so there is no boundary
  // between `COUNTDOWN_METHOD` and `_DISABLED`.
  const COUNTDOWN_ARM = {
    // The Ink renderer SWITCHES on the method name.
    "tui.tsx": /case\s+COUNTDOWN_METHOD\b\s*:/,
    // The line renderer COMPARES against it.
    "line.ts": /method\s*===\s*COUNTDOWN_METHOD\b/,
  };

  for (const [label, src] of [
    ["tui.tsx", tuiSrc],
    ["line.ts", lineSrc],
  ]) {
    const code = codeOf(src, label);
    // The renderer names the SHARED constant, not the literal — so the
    // method name lives in one place. The constant's VALUE is pinned
    // above and against the daemon's own emission below.
    assert.match(
      code,
      COUNTDOWN_ARM[label],
      `${label} does not handle the countdown notification — the daemon would start a turn ` +
        "on its own with no warning. (If the renderer changed idiom, re-anchor this test.)",
    );
    // A floor under the idiom check: the constant must be USED, not
    // merely imported. An import alone is a declaration, not a
    // handler.
    const withoutImports = code.replace(/^\s*import\s+[^;]*;.*$/gm, "");
    assert.match(
      withoutImports,
      /\bCOUNTDOWN_METHOD\b/,
      `${label} imports the countdown constant but never uses it — the notification would ` +
        "be handled by nothing",
    );
    assert.ok(
      code.includes("parseCountdown("),
      `${label} must PARSE the countdown payload, not just name the method`,
    );
  }
});

test("the countdown says what Claude Code says, and esc really cancels it", () => {
  // "Continuing automatically at 3:45pm · esc to cancel" — the shape
  // the user already knows from Claude Code.
  const c = {
    sessionId: "s",
    runId: "r",
    incompleteCount: 2,
    totalCount: 5,
    remainingMs: 2000,
    at: new Date(2026, 0, 1, 15, 45, 0).getTime(),
  };
  const line = countdownMod.countdownLine(c);
  assert.match(line, /^Continuing automatically at /, "the line's shape: " + line);
  assert.match(line, /3:45pm/, "the clock time: " + line);
  assert.match(line, /esc to cancel/, "the cancel affordance: " + line);
  // And the affordance is REAL: Esc must reach the RPC that stops the
  // continuation. A promise the UI does not keep is worse than no
  // promise.
  const code = codeOf(tuiSrc, "tui.tsx");
  assert.ok(
    code.includes("continuationCountdown"),
    "the Ink renderer must keep the pending countdown in its state",
  );
  assert.ok(
    /continuationCountdown[\s\S]{0,600}pauseAutoContinue\(true\)/.test(code),
    "Esc must cancel the pending continuation through the same RPC /pause uses",
  );
});

test("the countdown payload parser refuses a payload it does not understand", () => {
  // "null is not 0" in another shape: an event the TUI does not
  // understand must produce NO countdown, not a countdown of zeroes.
  assert.equal(countdownMod.parseCountdown(null), null);
  assert.equal(countdownMod.parseCountdown({}), null);
  assert.equal(countdownMod.parseCountdown({ remainingMs: "soon" }), null);
  const good = countdownMod.parseCountdown({ remainingMs: 2000, sessionId: "s" }, 1000);
  assert.ok(good, "a payload with remainingMs is a countdown");
  assert.equal(good.at, 3000, "the continuation fires 2000ms from now");
});

// =========================================================================
// 4. the reports
// =========================================================================

test("the three RPCs get a REPORT, not a raw JSON dump", () => {
  // "session/tokens → {"ok":true,"input":123,...}" is the absence of a
  // report, not a report.
  for (const [label, src] of [
    ["tui.tsx", tuiSrc],
    ["line.ts", lineSrc],
  ]) {
    const code = codeOf(src, label);
    assert.ok(
      code.includes("formatSessionTokens("),
      `${label} must render the token usage through the shared formatter`,
    );
    assert.ok(code.includes("formatRewind("), `${label} must render the rewind outcome`);
  }
  // And the formatter reports what the daemon RESOLVED, and refuses to
  // turn a failure into a row of zeroes.
  const tokens = reportsMod.formatSessionTokens({
    ok: true,
    sessionId: "s",
    input: 10,
    output: 2,
    total: 12,
    costUsd: 0.5,
  });
  assert.match(tokens, /12 total/, "the total: " + tokens);
  assert.match(tokens, /10 in/, "the input: " + tokens);
  const failed = reportsMod.formatSessionTokens({ ok: false, error: "no cost tracker wired" });
  assert.match(failed, /failed/, "a refusal must be reported as a refusal: " + failed);
  assert.doesNotMatch(failed, /\b0\b/, "a failure must not be dressed up as zeroes: " + failed);
  const rewind = reportsMod.formatRewind({ ok: false, reason: "no user message at index 9" });
  assert.match(rewind, /failed/, "a refused rewind must say so: " + rewind);
});

test("the gate is not vacuous", () => {
  assert.ok(hasDaemon, "the daemon's Java sources are missing — this gate checked nothing");
  assert.ok(
    stdioMethods.size > 50 && httpMethods.size > 50,
    `the daemon's method registries look empty (${stdioMethods.size} / ${httpMethods.size})`,
  );
  assert.ok(
    stdioMethods.size > 50 && httpMethods.size > 50,
    "the daemon registries look empty — re-anchor",
  );
  assert.ok(
    keysMod.COMPONENT_KEYS.length >= 8,
    "the key registry looks empty — this gate checked nothing",
  );
});
