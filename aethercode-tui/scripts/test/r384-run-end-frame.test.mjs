// Copyright (c) 2026 maijun

// Licensed under the MIT License.
// See the LICENSE file in the project root for the full license text.

// R384 — ONE run_end frame, ONE contract.
//
// ## The defect
//
// A `run_end` frame's contract was written down in
// `AetherCodeMethods.eventToMap`, but **three** code paths assembled the
// frame:
//
//   1. `eventToMap` — the engine's own `RunEnd`;
//   2. the R745 synthetic terminal (the consumer ran out of events
//      without a `RunEnd`);
//   3. the `catch (Throwable)` terminal.
//
// The two synthetic ones built the frame BY HAND and **omitted
// `stopKind`** — the field R383 had just put on the wire.
//
// That is not cosmetic. R383 made "the verdict comes from the daemon"
// the RULE, so a client that reads `isError` and expects a `stopKind`
// beside it gets `undefined` on exactly those two paths — and the
// desktop's wire-first path then collapses the precise kind
// (`no_run_end`) into the generic one (`error`). A diagnosis the user
// cannot act on, on exactly the paths where the diagnosis matters most.
//
// ## What this gate pins
//
//   1. the daemon has exactly ONE place that can build a `run_end`
//      frame — a second one is a second contract;
//   2. that builder ALWAYS carries the kind (not inside a conditional);
//   3. the verdict comes from the engine's taxonomy, not a hand-rolled
//      prefix check;
//   4. BOTH synthetic terminals go through the builder;
//   5. the stream-json `result` frame carries the kind too — the same
//      contract on the other wire;
//   6. the TUI's classification does not change when the wire bits
//      arrive — so an old daemon and a new daemon report the same thing.
//
// Lesson 1232 — a contract that is written down in one place and
// honoured in three is not a contract.
// Lesson 1226 — a gate that pins two copies to EACH OTHER is not a gate;
// ask how MANY there are.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync, readdirSync } from "node:fs";
import { dirname, join, relative } from "node:path";
import { fileURLToPath } from "node:url";
import {
  javaCode,
  javaMethod,
  javaMethodContaining,
  javaMethodInfoContaining,
} from "./java-strip.mjs";
import { loadTsModule } from "./load-ts-module.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const repo = join(root, "..");
const java = join(repo, "aethercode");
const srcRoot = join(root, "src");

const read = (rel) => readFileSync(join(srcRoot, rel), "utf-8");

// =========================================================================
// the daemon's sources
// =========================================================================

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
const emitterPath = join(
  java,
  "aethercode-core",
  "src",
  "main",
  "java",
  "org",
  "aethercode",
  "core",
  "stream",
  "ClaudeStreamJsonEmitter.java",
);
const hasDaemon = existsSync(methodsPath) && existsSync(emitterPath);

/** Every main Java source of the daemon, relative to the java root. */
function javaSources(dir = java, out = []) {
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    if (e.name === "target" || e.name === "node_modules") continue;
    const p = join(dir, e.name);
    if (e.isDirectory()) javaSources(p, out);
    else if (e.name.endsWith(".java")) {
      const rel = relative(java, p).replace(/\\/g, "/");
      // Only MAIN sources. A test that builds a frame to assert on it is
      // not a second assembler.
      if (rel.includes("/src/test/")) continue;
      out.push(rel);
    }
  }
  return out;
}

const ALL_JAVA = javaSources();
const methodsCode = hasDaemon ? javaCode(readFileSync(methodsPath, "utf-8")) : "";

/**
 * The ONE method that assembles a `run_end` frame — located by its
 * MARKER, not by its name.
 *
 * A pin that names the builder (`static Map<String, Object>
 * runEndFrame(`) breaks the moment the method is renamed, and a pin
 * that names a LOCATION breaks the moment it moves. R383's gate went
 * red on exactly that: R384 moved the assembly out of `eventToMap`,
 * and the pin on `eventToMap`'s branch failed on a refactor that made
 * the code BETTER.
 *
 * So the gate says what it means — "the method that builds the
 * run_end frame" — and locates it by the marker every assembler must
 * contain. (Lesson 1233: a contract pin must FOLLOW the
 * implementation.)
 */
function runEndAssembler() {
  return javaMethodContaining(methodsCode, 'put("type", "run_end")');
}

/**
 * The assembler's own NAME — derived from the implementation, never
 * hard-coded.
 *
 * A hard-coded name is a pin on a location: rename the builder and
 * the gate goes red even though the code is fine. Deriving it means
 * the gate follows the code: a rename keeps it green, and a SECOND
 * assembler (the actual defect) still turns it red.
 */
function runEndAssemblerName() {
  return javaMethodInfoContaining(methodsCode, 'put("type", "run_end")').name;
}

/**
 * The brace depth at an offset inside a method body, skipping string
 * literals.
 *
 * A method body from `javaMethod` starts with `{` at index 0, so a
 * statement at the method's TOP LEVEL has depth 1 and a statement inside
 * an `if { … }` has depth 2. "Is the `stopKind` put conditional?" is
 * exactly the question this answers.
 */
function depthAt(body, index) {
  let depth = 0;
  let i = 0;
  while (i < index && i < body.length) {
    const c = body[i];
    if (c === '"' || c === "'") {
      const q = c;
      i++;
      while (i < body.length) {
        if (body[i] === "\\") {
          i += 2;
          continue;
        }
        if (body[i] === q) {
          i++;
          break;
        }
        i++;
      }
      continue;
    }
    if (c === "{") depth++;
    else if (c === "}") depth--;
    i++;
  }
  return depth;
}

// =========================================================================
// the TUI's side
// =========================================================================

const stopKindMod = await loadTsModule(root, "src/stopKind.ts");
const { classifyRunEnd, bareKind } = stopKindMod;

// =========================================================================
// 1. ONE assembler
// =========================================================================

test("the daemon has exactly ONE place that can build a run_end frame", () => {
  assert.ok(hasDaemon, "the daemon's Java sources are missing — the gate cannot check anything");
  const offenders = [];
  for (const rel of ALL_JAVA) {
    const code = javaCode(readFileSync(join(java, rel), "utf-8"));
    const hits = [...code.matchAll(/put\(\s*"type"\s*,\s*"run_end"\s*\)/g)];
    if (hits.length) offenders.push(`${rel} (${hits.length})`);
  }
  // The CONTRACT is "exactly one", not "exactly one, in THIS file".
  // Pinning the file would make a MOVE (the builder relocating to a
  // sibling class) look like a defect — and a location pin breaks the
  // moment the code is refactored (R384's lesson).
  const total = offenders.reduce((n, o) => n + Number(/\((\d+)\)/.exec(o)[1]), 0);
  assert.equal(
    total,
    1,
    "the run_end frame must be assembled in exactly ONE place — these files build one: " +
      `${offenders.join(", ")}. A second assembler is a second contract, and the two drift.`,
  );
});

test("the builder is a real method the callers use", () => {
  const body = runEndAssembler();
  assert.ok(body.includes('"run_end"'), "the builder does not set the frame type");
  // The builder is referenced by the engine's own RunEnd, the R745
  // synthetic terminal, and the catch(Throwable) terminal. A builder
  // with no callers assembles nothing.
  //
  // The name is DERIVED from the implementation, so renaming the
  // builder keeps this green — what must stay true is the NUMBER of
  // call sites, not the spelling of the method.
  const name = runEndAssemblerName();
  assert.ok(name, "the assembler must be a named method");
  const calls = [...methodsCode.matchAll(new RegExp(`\\b${name}\\s*\\(`, "g"))];
  assert.ok(
    calls.length >= 4,
    `only ${calls.length} references to the assembler (${name}) — the definition plus three ` +
      "call sites were expected; a call site that stopped using the builder would build a " +
      "second contract",
  );
});

// =========================================================================
// 2 + 3. the builder's contract
// =========================================================================

test("the builder ALWAYS carries the kind — it is not conditional", () => {
  const body = runEndAssembler();
  const at = body.search(/put\(\s*"stopKind"/);
  assert.ok(
    at >= 0,
    "the builder does not put a `stopKind` at all — every run_end frame must carry the engine's " +
      "canonical kind, so a client never has to re-parse the reason",
  );
  // A brace-depth check would NOT be enough: `if (cond) r.put("stopKind", …);`
  // is at the same depth as an unguarded statement. So the question is
  // asked directly — is there ANY conditional in front of the put?
  const before = body.slice(0, at);
  assert.doesNotMatch(
    before,
    /\bif\b/,
    "the `stopKind` put is guarded by a conditional — the kind must be present on EVERY frame, " +
      "including the clean ones, so a client can tell 'the run finished cleanly' from 'the frame " +
      "did not say'",
  );
});

test("the builder's verdict comes from the engine's taxonomy", () => {
  const body = runEndAssembler();
  assert.match(
    body,
    /isErrorOutcome\(\)/,
    "the builder must take the failure bit from StreamEvent's taxonomy, not from a hand-rolled " +
      "prefix check (R743's lesson)",
  );
  assert.doesNotMatch(
    body,
    /startsWith\(/,
    "the builder re-derives the outcome with a startsWith — that is the exact defect R383 " +
      "removed from the clients",
  );
  // The verdict field is written INSIDE the taxonomy guard, so a clean
  // run carries no verdict at all (absent ≠ false).
  const guard = body.search(/if\s*\(\s*\w+\.isErrorOutcome\(\)\s*\)/);
  assert.ok(guard >= 0, "the builder has no taxonomy guard");
  const at = body.search(/put\(\s*"isError"/);
  assert.ok(at >= 0, "the builder never writes an `isError` field");
  assert.equal(
    depthAt(body, at),
    2,
    "the `isError` put must sit INSIDE the taxonomy guard — a clean run must carry no verdict",
  );
});

// =========================================================================
// 4. both synthetic terminals go through the builder
// =========================================================================

/**
 * The method that holds the SYNTHETIC terminals — the R745 no-RunEnd
 * guard and the catch(Throwable) terminal.
 *
 * The contract is not "these call a method with this NAME" — a name
 * pin breaks on a rename, and a rename is not a defect. The contract
 * is: **these terminals do not assemble a run_end frame by hand.**
 * A hand-built frame is the defect this round exists to remove: it
 * is what let the two synthetic terminals drift away from the
 * contract and drop `stopKind`.
 *
 * So the check is inverted and implementation-independent: the
 * terminals' own method must contain NO `put("type", "run_end")` —
 * because the ONE assembler (pinned above) is the only place in the
 * daemon that may write that type.
 */
function syntheticTerminalsMethod() {
  return javaMethodContaining(methodsCode, '"no_run_end: consumer exited without terminal event"');
}

test("the synthetic terminals do NOT assemble a run_end frame by hand", () => {
  const body = syntheticTerminalsMethod();
  const handBuilt = body.match(/put\(\s*"type"\s*,\s*"run_end"\s*\)/g) ?? [];
  assert.deepEqual(
    handBuilt,
    [],
    "the synthetic terminals (the R745 no-RunEnd guard and the catch(Throwable) terminal) " +
      "assemble a run_end frame BY HAND — they must use the one builder, or they will drift " +
      "away from the contract again (that is exactly how they lost `stopKind`)",
  );
});

test("the synthetic terminals still EMIT a terminal event", () => {
  // The inverse of the check above: a terminal that emits nothing is
  // as broken as one that emits a hand-built frame. The R735/R745
  // contract is "a dead run still gets a terminal event".
  const body = syntheticTerminalsMethod();
  assert.match(
    body,
    /endWrap\.put\(\s*"event"\s*,/,
    "the synthetic terminals must put a terminal `event` into the notification wrapper — " +
      "without one the renderer's isStreaming stays true forever (R735's dead-engine-looks-" +
      "like-Idle)",
  );
  assert.match(
    body,
    /catch\s*\(\s*Throwable\s+\w+\s*\)/,
    "the outer catch (Throwable) must survive — it is what converts a throw escaping the " +
      "stream into a terminal event instead of a dead run",
  );
});

// =========================================================================
// 5. the other wire carries the kind too
// =========================================================================

test("the stream-json result frame carries the canonical kind", () => {
  assert.ok(hasDaemon, "the daemon's Java sources are missing — the gate cannot check anything");
  const code = javaCode(readFileSync(emitterPath, "utf-8"));
  assert.match(
    code,
    /put\(\s*"aethercode_stop_kind"\s*,\s*StreamEvent\.bareKindOf\(/,
    "the stream-json `result` frame carries only the DECORATED reason — a consumer of that " +
      "protocol would have to re-parse it, and every parser that did got it wrong",
  );
});

// =========================================================================
// 6. the TUI: the wire does not change the answer
// =========================================================================

test("the TUI's classification does not change when the wire bits arrive", () => {
  // The local failure set is a FALLBACK for a daemon that expresses no
  // verdict. If the fallback and the wire ever disagree, then the same
  // run is reported differently depending on the daemon's version —
  // which is the defect R383 was about, one layer down.
  const reasons = [
    "end_turn",
    "stop",
    "tool_calls",
    "error",
    "error: WebClientResponseException$InternalServerError: 500 from POST https://x",
    "runtime_exception: java.lang.IllegalStateException: boom",
    "same_error_hard_stop",
    "loop_detected: empty_tool_input (4 consecutive empty tool calls)",
    "no_run_end: consumer exited without terminal event",
    "consumer_timeout: no stream event for 180000ms (run r-1)",
    "cancelled",
    "awaiting_user_decision",
    "empty_input",
  ];
  for (const reason of reasons) {
    const fallback = classifyRunEnd({ stopReason: reason });
    const withKind = classifyRunEnd({ stopReason: reason, stopKind: bareKind(reason) });
    assert.equal(
      withKind,
      fallback,
      `"${reason}" classifies as "${fallback}" without the wire but as "${withKind}" with the ` +
        "daemon's kind — the fallback and the wire must never disagree",
    );
  }
});

test("the two SYNTHETIC terminals classify as failures with the wire's verdict", () => {
  // These are the exact frames R384 fixed. With the daemon's verdict on
  // the wire they are failures; and the kind is what the daemon says.
  for (const [reason, kind] of [
    ["no_run_end: consumer exited without terminal event", "no_run_end"],
    ["error: boom", "error"],
  ]) {
    assert.equal(
      classifyRunEnd({ stopReason: reason, stopKind: kind, isError: true }),
      "error",
      `${reason} must classify as a failure`,
    );
  }
});

// =========================================================================
// 7. the desktop keeps the PRECISE kind
// =========================================================================

test("the desktop's order is: the wire's kind, then the prefix match, then the generic", () => {
  // The daemon (R384) always sends `stopKind`, so the precise kind
  // reaches the pill. But a R750d..R382 daemon sends `isError` and NO
  // `stopKind` — and defaulting the wire path to the generic "error"
  // there hid "no_run_end" / "consumer_timeout". So the generic has to
  // be the LAST resort, after the prefix match.
  const store = join(repo, "aethercode-desktop", "src", "store", "index.ts");
  assert.ok(existsSync(store), "the desktop's store is missing — the gate cannot check anything");
  const code = readFileSync(store, "utf-8");
  assert.match(
    code,
    /failedKind\s*=\s*wireFailed\s*\?\?\s*matchedKind\s*\?\?\s*\(\s*wireIsError\s*\?\s*'error'\s*:\s*undefined\s*\)/,
    "the desktop must prefer the wire's kind, then the local prefix match, and only then the " +
      "generic 'error' — a verdict without a kind must not discard the precision the local " +
      "list already has",
  );
  assert.doesNotMatch(
    code,
    /isError === true \? \(wireKind \?\? 'error'\)/,
    "the desktop defaults the wire path to 'error' — a R750d..R382 daemon sends `isError` with " +
      "no `stopKind`, and every such frame lost its precise diagnosis",
  );
});
