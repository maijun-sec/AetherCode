// R383 — the verdict comes from the DAEMON.
//
// ## The defect
//
// The engine DECORATES its stop reasons:
//
//   "error: WebClientResponseException$InternalServerError: 500 from POST https://…"
//   "loop_detected: empty_tool_input (4 consecutive empty tool calls)"
//   "runtime_exception: java.lang.IllegalStateException: boom"
//
// So every client that wants to know "did that run fail?" has to RE-PARSE
// the string. The TUI had TWO such parsers (`state.ts` and `line.ts`)
// and the desktop had a third. All three were wrong, and they were
// wrong in ways that made a dead engine look healthy:
//
//   - the TUI's rule matched `"error_"` / `"_error"` with an UNDERSCORE
//     while the engine emits `"error:"` with a COLON — so the single
//     most common failure of all (an upstream 4xx/5xx) was classified
//     "ok" and the StatusBar showed a GREEN "✓ ready";
//   - the TUI's `same_error_hard_stop` (a death spiral) fell through to
//     "ok" too;
//   - the desktop's list carried the DISPLAY kinds "loop" and
//     "max_turns" — which the engine never emits — and missed
//     "loop_detected" entirely, so a death spiral showed as "● Idle".
//
// The old gate pinned the two TUI copies to EACH OTHER ("state.ts and
// line.ts classifyStopReason are equivalent"). That test could not
// catch the bug: two copies that are wrong TOGETHER agree perfectly.
//
// ## The rule now
//
// The daemon already knows the answer. R750d put `isError` on the
// wire; R383 adds `stopKind` (the canonical bare kind) beside it. So:
//
//   1. the VERDICT comes from the daemon (`isError`);
//   2. the KIND comes from the daemon (`stopKind`) and only refines
//      the WORDING (loop / max turns / empty);
//   3. the local rule in `src/stopKind.ts` is a FALLBACK for an older
//      daemon that sends neither field — and its failure set is pinned
//      against `StreamEvent.isErrorOutcomeOf` by THIS gate, so it
//      cannot drift away from the engine.
//
// A renderer never decides on its own whether a run failed.
//
// ## What this gate pins
//
//   1. the daemon's `run_end` frame carries `stopKind` (always) and
//      `isError` (on failures), and both come from the ENGINE's own
//      taxonomy;
//   2. the TUI has exactly ONE copy of the taxonomy;
//   3. the fallback failure set IS the engine's failure set — compared
//      kind-for-kind, in both directions;
//   4. the real engine stop reasons classify the way a user would
//      expect;
//   5. the renderers consume the wire bits and label by KIND.
//
// Lesson 1225 — a renderer never decides on its own whether a run failed.
// Lesson 1226 — a gate that pins two copies to EACH OTHER is not a gate.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync, readdirSync } from "node:fs";
import { dirname, join, relative } from "node:path";
import { fileURLToPath } from "node:url";
import { javaCode, javaMethod } from "./java-strip.mjs";
import { codeOf } from "./strip-comments.mjs";
import { loadTsModule } from "./load-ts-module.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const repo = join(root, "..");
const java = join(repo, "aethercode");
const srcRoot = join(root, "src");

const read = (rel) => readFileSync(join(srcRoot, rel), "utf-8");

// =========================================================================
// the daemon's side
// =========================================================================

const streamEventPath = join(
  java,
  "aethercode-core",
  "src",
  "main",
  "java",
  "org",
  "aethercode",
  "core",
  "stream",
  "StreamEvent.java",
);
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
const hasDaemon = existsSync(streamEventPath) && existsSync(methodsPath);

/**
 * The engine's FAILURE kinds, extracted from `StreamEvent.isErrorOutcomeOf`.
 *
 * Extracted, not hand-listed: a hand-list here would be a SECOND copy of
 * the engine's taxonomy — the very thing this round is about. The Java
 * body is located by its signature and read by brace matching, so the
 * extraction cannot drift onto a neighbouring method.
 */
function engineFailureKinds() {
  const body = javaMethod(
    javaCode(readFileSync(streamEventPath, "utf-8")),
    "static boolean isErrorOutcomeOf(String stopReason)",
  );
  const kinds = new Set();
  // A Java switch arm may name SEVERAL labels:
  //   case "no_run_end", "consumer_timeout" -> true;
  // Capturing only the first label of each arm would silently drop
  // half the taxonomy — and then the comparison below would pass with
  // the two sides disagreeing.
  for (const arm of body.matchAll(/case\s+((?:"[^"]+"\s*,?\s*)+)->/g)) {
    for (const label of arm[1].matchAll(/"([^"]+)"/g)) kinds.add(label[1]);
  }
  return kinds;
}

/** The daemon's `run_end` wire branch — the frame every client sees. */
function runEndBranch() {
  const body = javaMethod(
    javaCode(readFileSync(methodsPath, "utf-8")),
    "private static Map<String, Object> eventToMap(StreamEvent ev)",
  );
  const at = body.indexOf('"run_end"');
  if (at < 0) throw new Error("the run_end branch is gone — re-anchor this extraction");
  const end = body.indexOf("} else if", at);
  return body.slice(at, end < 0 ? body.length : end);
}

// =========================================================================
// the TUI's side
// =========================================================================

const stopKindMod = await loadTsModule(root, "src/stopKind.ts");
const { classifyRunEnd, bareKind, ENGINE_ERROR_KINDS, LOOP_KINDS } = stopKindMod;

/** Every .ts / .tsx file under `src/`, relative to `src/`. */
function sourceFiles(dir = srcRoot) {
  const out = [];
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name);
    if (e.isDirectory()) out.push(...sourceFiles(p));
    else if (/\.tsx?$/.test(e.name)) out.push(relative(srcRoot, p).replace(/\\/g, "/"));
  }
  return out;
}

const ALL_SOURCES = sourceFiles();

// =========================================================================
// 1. the wire carries the verdict and the kind
// =========================================================================

test("the daemon's run_end frame carries the canonical kind AND the verdict", () => {
  assert.ok(hasDaemon, "the daemon's Java sources are missing — the gate cannot check anything");
  const branch = runEndBranch();
  assert.match(
    branch,
    /r\.put\(\s*"stopKind"\s*,\s*re\.bareKind\(\)\s*\)/,
    "the run_end frame does not carry the engine's canonical kind — every client has to " +
      "re-parse the decorated stopReason, and every parser that did got it wrong",
  );
  assert.match(
    branch,
    /if\s*\(\s*re\.isErrorOutcome\(\)\s*\)/,
    "the run_end frame must take the failure bit from the engine's taxonomy " +
      "(StreamEvent.isErrorOutcome), not from a hand-rolled startsWith",
  );
  assert.match(
    branch,
    /r\.put\(\s*"isError"\s*,\s*true\s*\)/,
    "the run_end frame does not carry `isError` — the verdict is not on the wire at all",
  );
});

test("the desktop's fallback list covers every engine failure kind", () => {
  // The desktop keeps a prefix-match list for OLD daemons. It must not
  // miss an engine failure kind (the R735b defect: "loop_detected" was
  // missing) and it must not carry a kind the engine never emits (the
  // dead "loop" / "max_turns" entries).
  const desktop = join(repo, "aethercode-desktop", "src", "store", "index.ts");
  assert.ok(existsSync(desktop), "the desktop's store is missing — the gate cannot check anything");
  const code = codeOf(readFileSync(desktop, "utf-8"), "desktop store");
  const m = /const\s+failedStopKinds\s*=\s*\[([\s\S]*?)\]/.exec(code);
  assert.ok(m, "the desktop's failedStopKinds list is gone — re-anchor this test");
  const listed = new Set([...m[1].matchAll(/["']([a-z_]+)["']/g)].map((x) => x[1]));

  const engine = engineFailureKinds();
  for (const kind of engine) {
    assert.ok(
      listed.has(kind),
      `the desktop's fallback list is missing the engine failure kind "${kind}" — a run that ` +
        "failed that way would render as a healthy ● Idle",
    );
  }
  // The two documented NON-failures the desktop still surfaces, plus the
  // engine's own kinds. Anything else in the list is a kind the engine
  // never emits — a promise that can never be kept.
  const ALLOWED_EXTRA = new Set(["awaiting_user_decision", "cancelled"]);
  for (const kind of listed) {
    assert.ok(
      engine.has(kind) || ALLOWED_EXTRA.has(kind),
      `the desktop's fallback list carries "${kind}", which the engine never emits as a stop ` +
        "reason — a dead entry is an entry that can never match",
    );
  }
});

// =========================================================================
// 2. ONE copy of the taxonomy
// =========================================================================

const DEFINITIONS = [
  ["classifyRunEnd", /(?:export\s+)?function\s+classifyRunEnd\b/g],
  ["classifyStopReason", /(?:export\s+)?function\s+classifyStopReason\b/g],
  ["ENGINE_ERROR_KINDS", /(?:export\s+)?const\s+ENGINE_ERROR_KINDS\b/g],
  ["LOOP_KINDS", /(?:export\s+)?const\s+LOOP_KINDS\b/g],
];

test("the taxonomy exists in exactly ONE module: src/stopKind.ts", () => {
  const offenders = [];
  for (const rel of ALL_SOURCES) {
    const code = codeOf(read(rel), rel);
    for (const [name, re] of DEFINITIONS) {
      if (new RegExp(re.source, re.flags).test(code)) {
        if (rel !== "stopKind.ts") offenders.push(`${rel} defines ${name}`);
      }
    }
  }
  assert.deepEqual(
    offenders,
    [],
    `these modules carry their own copy of the stop taxonomy: ${offenders.join(", ")} — ` +
      "a second copy of a truth drifts, and it drifts silently",
  );
});

test("both renderers import the shared taxonomy", () => {
  for (const rel of ["state.ts", "line.ts"]) {
    const code = codeOf(read(rel), rel);
    assert.match(
      code,
      /from\s+"\.\/stopKind\.js"/,
      `${rel} does not import src/stopKind.ts — it must not carry its own rules`,
    );
  }
});

test("no renderer re-derives the verdict from the decorated reason", () => {
  // The old rule matched "error_" / "_error" with an UNDERSCORE while
  // the engine emits "error:" with a COLON. Nothing outside the shared
  // module may look for an error pattern in a stop reason again.
  for (const rel of ALL_SOURCES) {
    if (rel === "stopKind.ts") continue;
    const code = codeOf(read(rel), rel);
    assert.doesNotMatch(
      code,
      /startsWith\(\s*["']error_["']\s*\)/,
      `${rel} matches "error_" — the engine emits "error:", so this can never fire`,
    );
    assert.doesNotMatch(
      code,
      /endsWith\(\s*["']_error["']\s*\)/,
      `${rel} matches "_error" — a second hand-rolled failure heuristic`,
    );
  }
});

// =========================================================================
// 3. the fallback set IS the engine's taxonomy
// =========================================================================

test("the TUI's fallback failure set is the ENGINE's failure set, kind for kind", () => {
  const engine = engineFailureKinds();
  assert.ok(
    engine.size >= 5,
    `only ${engine.size} failure kinds were extracted from StreamEvent.isErrorOutcomeOf — ` +
      "the extraction found nothing, so this comparison would pass vacuously",
  );
  const tui = new Set(ENGINE_ERROR_KINDS);
  assert.deepEqual(
    [...tui].sort(),
    [...engine].sort(),
    "the TUI's fallback failure set has drifted from the engine's taxonomy: " +
      `TUI has ${JSON.stringify([...tui].sort())}, the engine has ${JSON.stringify(
        [...engine].sort(),
      )} — a kind the engine added will never be reported, and a kind it dropped will be`,
  );
});

test("every LOOP kind is also a failure kind", () => {
  for (const kind of LOOP_KINDS) {
    assert.ok(
      ENGINE_ERROR_KINDS.includes(kind),
      `"${kind}" gets the loop wording but is not in the engine's failure set — the engine ` +
        "would call that run a success while the TUI calls it a failure",
    );
  }
});

// =========================================================================
// 4. the real reasons classify correctly
// =========================================================================

/**
 * The stop reasons the engine REALLY emits, with the display kind a
 * user would expect AND the verdict the daemon sends for them.
 *
 * Every row cites where the reason comes from. A row without a source
 * is a guess; a guess in this table is a licence to be wrong.
 *
 * The third column is the daemon's `isError` bit: the daemon only
 * writes it when the engine's taxonomy says "failure", so a clean run
 * has NO bit at all (not `false`).
 */
const REAL_REASONS = [
  ["end_turn", "ok", false, "QueryEngine:1992 / 2325 — the default end"],
  ["stop", "ok", false, "SpringAiChatClient:595 — MiniMax's own stop reason"],
  ["tool_calls", "ok", false, "SpringAiChatClient:625 — an intermediate hand-off"],
  ["max_tokens", "ok", false, "the model hit its output cap — a normal completion"],
  ["empty_input", "empty", false, "QueryEngine:1860 — the no-op guard"],
  ["cancelled", "ok", false, "QueryEngine:2142 — the USER asked for it; not a failure"],
  ["awaiting_user_decision", "ok", false, "QueryEngine:2927 — the engine is PAUSED for the user"],
  ["error", "error", true, "SpringAiChatClient:246 / 520 — the bare error form"],
  [
    "error: WebClientResponseException$InternalServerError: 500 from POST https://api.minimaxi.com/v1/chat/completions",
    "error",
    true,
    "the DECORATED form — the bug this round fixes (the old rule matched 'error_')",
  ],
  [
    "runtime_exception: java.lang.IllegalStateException: boom",
    "error",
    true,
    "QueryEngine:2998 (R735a)",
  ],
  ["same_error_hard_stop", "loop", true, "QueryEngine:2862 — the R181/R739 death spiral"],
  [
    "loop_detected: empty_tool_input (4 consecutive empty tool calls)",
    "loop",
    true,
    "QueryEngine:2617 — the empty-tool-input death spiral",
  ],
  [
    "no_run_end: consumer exited without terminal event",
    "error",
    true,
    "EngineContinuationDispatcher:272 — the R745 consumer-side terminal",
  ],
  [
    "consumer_timeout: no stream event for 180000ms (run r-1)",
    "error",
    true,
    "EngineContinuationDispatcher:165 — the R746 consumer-side terminal",
  ],
  // Kinds the taxonomy names even though no producer emits them today.
  // They are in the engine's failure set, so the fallback must know them.
  ["stale_force_end", "error", true, "R750b's stalled first-chunk read"],
];

test("every REAL engine stop reason classifies the way a user expects", () => {
  // The fallback path — an older daemon that sends neither wire field.
  for (const [reason, expected, , why] of REAL_REASONS) {
    assert.equal(
      classifyRunEnd({ stopReason: reason }),
      expected,
      `"${reason}" should classify as "${expected}" — ${why}`,
    );
  }
});

test("the same reasons classify the same way when the daemon sends the wire bits", () => {
  // The daemon's frame carries the bare kind and — for a failure — the
  // verdict. The classification must not change when the wire bits
  // arrive, otherwise the same run would be reported differently
  // depending on the daemon's version.
  for (const [reason, expected, isError, why] of REAL_REASONS) {
    assert.equal(
      classifyRunEnd({ stopReason: reason, stopKind: bareKind(reason), isError }),
      expected,
      `"${reason}" classified differently when the daemon sent stopKind — ${why}`,
    );
  }
});

test("the DAEMON's verdict wins over the raw reason", () => {
  // The daemon says this run failed. Whatever the string says, the run
  // failed — including a reason that looks perfectly clean.
  assert.equal(
    classifyRunEnd({ stopReason: "end_turn", stopKind: "error", isError: true }),
    "error",
  );
  assert.equal(
    classifyRunEnd({
      stopReason: "totally_unknown_kind",
      stopKind: "totally_unknown_kind",
      isError: true,
    }),
    "error",
  );
  // The daemon's KIND wins over the raw reason too.
  assert.equal(classifyRunEnd({ stopReason: "error: boom", stopKind: "loop_detected" }), "loop");
});

test("the specific wording wins over the generic failure", () => {
  // A loop IS a failure, but the user needs to hear "loop", not
  // "error". The verdict is unchanged; only the wording is refined.
  assert.equal(classifyRunEnd({ stopReason: "same_error_hard_stop", isError: true }), "loop");
  assert.equal(
    classifyRunEnd({ stopReason: "loop_detected: empty_tool_input (x)", isError: true }),
    "loop",
  );
});

test("no signal at all means no run has finished", () => {
  assert.equal(classifyRunEnd({}), null);
  assert.equal(classifyRunEnd({ stopReason: null }), null);
  // …and the state before the first run is NOT "ok" — that would claim
  // a clean run that never happened.
  assert.notEqual(classifyRunEnd({}), "ok");
});

test("a daemon that sends the kind but NOT the verdict still reports the failure", () => {
  // The local failure set is the fallback for "the daemon expressed no
  // verdict" — NOT for "the daemon sent no stopKind". A transport that
  // drops `isError`, or a version skew between the two wire fields,
  // would otherwise turn every failure into a success. That is the same
  // green-✓-ready symptom this round exists to remove.
  assert.equal(classifyRunEnd({ stopReason: "error: boom", stopKind: "error" }), "error");
  assert.equal(classifyRunEnd({ stopReason: "boom", stopKind: "runtime_exception" }), "error");
  // A clean kind with no verdict is still clean.
  assert.equal(classifyRunEnd({ stopReason: "end_turn", stopKind: "end_turn" }), "ok");
});

// =========================================================================
// 5. the renderers consume the wire and label by KIND
// =========================================================================

test("the Ink renderer hands the daemon's verdict to the reducer", () => {
  const code = codeOf(read("tui.tsx"), "tui.tsx");
  assert.match(
    code,
    /stopKind\s*:\s*typeof\s+ev\.stopKind\s*===\s*["']string["']\s*\?\s*ev\.stopKind\s*:\s*undefined/,
    "tui.tsx does not forward the daemon's stopKind — the reducer would have to re-derive it",
  );
  assert.match(
    code,
    /isError\s*:\s*ev\.isError\s*===\s*true\s*\?\s*true\s*:\s*undefined/,
    "tui.tsx does not forward the daemon's isError — the verdict would be re-derived locally",
  );
});

test("the line renderer hands the daemon's verdict to the classifier", () => {
  const code = codeOf(read("line.ts"), "line.ts");
  assert.match(
    code,
    /stopKind\s*:\s*typeof\s+ev\.stopKind\s*===\s*["']string["']\s*\?\s*ev\.stopKind\s*:\s*undefined/,
    "line.ts does not forward the daemon's stopKind",
  );
  assert.match(
    code,
    /isError\s*:\s*ev\.isError\s*===\s*true\s*\?\s*true\s*:\s*undefined/,
    "line.ts does not forward the daemon's isError",
  );
});

test("the reducer classifies with the daemon's verdict", () => {
  const code = codeOf(read("state.ts"), "state.ts");
  const at = code.indexOf("classifyRunEnd(");
  assert.ok(at > 0, "the reducer does not call classifyRunEnd — re-anchor this test");
  // The call's argument object must carry all three inputs. Scoped to
  // the call so a later `classifyRunEnd` elsewhere cannot satisfy it.
  //
  // The VALUES matter, not just the keys: `isError: undefined` has the
  // key `isError` and passes a key-presence check while throwing the
  // daemon's verdict away — which is the whole defect.
  const arg = code.slice(at, at + 400);
  assert.match(
    arg,
    /stopReason\s*:\s*action\.stopReason/,
    "the reducer does not pass the stop reason",
  );
  assert.match(
    arg,
    /stopKind\s*:\s*action\.stopKind/,
    "the reducer does not pass the daemon's kind",
  );
  assert.match(
    arg,
    /isError\s*:\s*action\.isError/,
    "the reducer does not pass the daemon's verdict",
  );
});

test("the StatusBar has ONE label table, keyed by the display kind", () => {
  const code = codeOf(read("components/StatusBar.tsx"), "StatusBar.tsx");
  assert.doesNotMatch(
    code,
    /STOP_LABEL/,
    "the StatusBar still has a second label table keyed by the stop reason — that is the " +
      "second copy of the truth this round removes",
  );
  const tables = code.match(/const\s+[A-Z_]*LABEL[A-Z_]*\s*:\s*Record</g) ?? [];
  assert.equal(
    tables.length,
    1,
    `the StatusBar has ${tables.length} label tables (${tables.join(", ")}) — one display kind ` +
      "must have exactly one wording",
  );
  assert.match(
    code,
    /KIND_LABEL\[\s*state\.lastStopKind/,
    "the StatusBar does not label by the display kind",
  );
  assert.doesNotMatch(
    code,
    /state\.lastStopReason/,
    "the StatusBar still consults the raw stopReason — the reason is DECORATED, so a lookup " +
      "on it never hits and every failure renders as a green success",
  );
});

test("the StatusBar's label and colour come from the SAME kind", () => {
  const code = codeOf(read("components/StatusBar.tsx"), "StatusBar.tsx");
  // The colour was ALWAYS taken from `lastStopKind` while the label
  // came from the reason table — two sources for one decision, which
  // is how "stopped: loop" ended up painted in the colour for success.
  assert.match(code, /colorForKind\(\s*state\.lastStopKind\s*\)/);
  assert.match(code, /KIND_LABEL\[\s*state\.lastStopKind/);
});

test("the label table covers EVERY display kind", () => {
  const code = codeOf(read("components/StatusBar.tsx"), "StatusBar.tsx");
  const m = /const\s+KIND_LABEL[^=]*=\s*\{([\s\S]*?)\}/.exec(code);
  assert.ok(m, "the StatusBar's KIND_LABEL table is gone — re-anchor this test");
  const keys = [...m[1].matchAll(/^\s*([a-z_]+)\s*:/gm)].map((x) => x[1]);
  assert.deepEqual(
    keys.sort(),
    ["empty", "error", "loop", "max_turns", "ok"],
    "the label table must cover every display kind — a kind with no entry renders `undefined`",
  );
});
