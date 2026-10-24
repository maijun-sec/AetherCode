// R369 — a command `/help` advertises must not answer -32601.
//
// ## What was wrong
//
// Five commands were in `SLASH_COMMANDS_DETAILED` (which feeds the
// autocomplete catalog AND the `/help` groups), and every one of them
// targeted a method the TUI's daemon does not register:
//
//   /stop      -> task/kill        /rewind  -> rewind
//   /pause     -> task/pause       /tokens  -> session/tokens
//   /continue  -> task/resume
//
// So the user typed something `/help` told them existed and got a raw
// JSON-RPC error code. That is the same failure as R365's `/context`
// (advertising a command that cannot work) and R366's `setCwd`.
//
// ## R375 — three of the five were the WRONG KIND OF BROKEN
//
// A refusal is the honest answer to "this method does not exist". But
// `/stop`, `/pause` and `/continue` were not asking for a method that
// happens to be missing — they were asking for a method that does
// not describe the capability AT ALL. The daemon has no "task" the
// user could pause; it has a RUN in flight and a session's automatic
// continuation. So refusing them was wrong twice over: the buttons
// still fired the request (and swallowed the -32601), and the refusal
// text described a task registry the daemon does have.
//
// R375 points those three at methods that really exist
// (`setContinuationStopped`, `cancel`), so this gate now covers the
// two that are still genuinely missing (`rewind`, `session/tokens`)
// AND asserts that the TUI no longer NAMES a method it cannot serve.
//
// ## Why the filter is not in the five `case` bodies
//
// The refusal lives in `handleSlash`, which is the single entry point
// both renderers call. Per-site discipline is exactly what R364 and
// R366 had to repair one bug at a time. A command added next month
// that names a missing method is honest for free.
//
// The table moved from this repo's test file into
// `src/rpcCapabilities.ts` so the UI and the gate read ONE copy. When
// it lived only in the gate, the UI had nowhere to read the reasons
// from — which is why this defect survived the round that discovered
// every one of these method names.

import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdirSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { codeOf } from "./strip-comments.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

const commandsSrc = codeOf(readFileSync(join(root, "src", "commands.ts"), "utf-8"), "commands.ts");
const capsSrc = codeOf(
  readFileSync(join(root, "src", "rpcCapabilities.ts"), "utf-8"),
  "rpcCapabilities.ts",
);
const lineSrc = codeOf(readFileSync(join(root, "src", "line.ts"), "utf-8"), "line.ts");

/** Compile the two modules under test and import the real values. */
async function load() {
  const tmp = join(tmpdir(), `r369-${process.pid}`);
  mkdirSync(tmp, { recursive: true });
  const tsc = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
  const r = spawnSync(
    `"${tsc}"`,
    [
      "--outDir",
      join(tmp, "out"),
      "--target",
      "ES2022",
      "--module",
      "ES2022",
      "--moduleResolution",
      "bundler",
      "--rootDir",
      join(root, "src"),
      `"${join(root, "src", "commands.ts")}"`,
    ],
    { encoding: "utf-8", shell: true },
  );
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
    process.exit(1);
  }
  return import(pathToFileURL(join(tmp, "out", "commands.js")).href);
}

const { handleSlash } = await load();

// --- the table ----------------------------------------------------------

test("the capability table is not empty and every reason is user-facing", () => {
  // Two SEPARATE extractions, each with its own floor.
  //
  // The floor is there because a `for...of matchAll(...)` loop is
  // structurally blind to its own failure to match: if the regex
  // drifts, the body never runs, no assertion fires, and the gate is
  // green on a table it never read. That is the R368 hole for the
  // fifth time — an unchecked extraction. So neither loop is allowed
  // to be unmeasured.
  //
  // The floor was 7 while the table still carried the three `task/*`
  // entries. R375 removed them (the TUI stopped naming those methods
  // altogether) and R377 removed `effort/set` (the daemon serves that
  // capability as `switchVariant`), so the measured count is 3 as of
  // 2026-10-24.
  const entries = [...capsSrc.matchAll(/^\s*\[\s*"[a-z][\w/]*",/gm)];
  assert.ok(
    entries.length >= 3,
    `only ${entries.length} entries parsed from rpcCapabilities.ts — the table ` +
      "probably stopped parsing, which would make the honesty checks vacuous",
  );

  const reasons = [...capsSrc.matchAll(/\[\s*\n?\s*"([a-z][\w/]*)",\s*\n?\s*"([^"]+)"/g)];
  assert.ok(
    reasons.length >= 3,
    `only ${reasons.length} reasons parsed — the per-reason check would run ` +
      "zero times and pass on a table it never read",
  );
  for (const m of reasons) {
    assert.ok(m[2].length > 25, `${m[1]}'s reason is too terse to show a user: ${m[2]}`);
  }
});

// --- the behaviour ------------------------------------------------------

test("every backend-missing command answers in words, not with an rpcMethod", () => {
  // The advertised ones that are STILL missing. `/rewind` only reaches
  // its RPC with an argument — with none it returns a usage string,
  // which is already honest, so both forms are asserted.
  //
  // R375 removed `/stop`, `/pause` and `/continue` from this list:
  // they no longer name a missing method, so there is nothing to
  // refuse. They are asserted to WORK in `phase-6-t6-17.test.mjs`.
  const CASES = [
    ["/rewind 3", "rewind"],
    ["/tokens", "session/tokens"],
  ];
  for (const [input, method] of CASES) {
    const res = handleSlash(input);
    assert.equal(
      res?.rpcMethod,
      undefined,
      `${input} still returns rpcMethod=${res?.rpcMethod} — the capability filter ` +
        "is not running, so this answers -32601",
    );
    assert.ok(res?.local, `${input} returned neither local nor rpcMethod`);
    assert.ok(
      res.local.includes(method),
      `${input} should name ${method} in its refusal, got: ${res.local}`,
    );
    assert.doesNotMatch(
      res.local,
      /__[A-Z0-9_]+__/,
      "a refusal must be a sentence, never a leaked sentinel token",
    );
    assert.doesNotMatch(
      res.local,
      /-32601|method not found/i,
      "the refusal must not leak the JSON-RPC error the user cannot act on",
    );
  }
});

test("the filter does not touch commands that DO work", () => {
  // A filter that rewrites everything is as broken as no filter. These
  // are the R366 fixes; if the filter swallowed them, the gate would
  // still be green while the product regressed.
  const OK = [
    ["/model gpt", "setModel"],
    ["/help", null],
    ["/todos", null],
    ["/memory", null],
    ["/consents", null],
  ];
  for (const [input, expectRpc] of OK) {
    const res = handleSlash(input);
    if (expectRpc) {
      assert.equal(
        res?.rpcMethod,
        expectRpc,
        `${input} should still reach ${expectRpc}, got local="${res?.local}"`,
      );
    } else {
      assert.equal(res?.rpcMethod, undefined, `${input} unexpectedly grew an rpcMethod`);
      assert.ok(res?.local, `${input} should still answer locally`);
    }
  }
  // Sentinels must survive untouched — the R362 gate owns those and
  // would (correctly) fail if a refusal had replaced one with a
  // sentence.
  assert.equal(handleSlash("/todos")?.local, "__TODO_BOARD__");
  assert.equal(handleSlash("/memory")?.local, "__MEMORY_PANEL__:project");
});

// --- the seam -----------------------------------------------------------

test("the filter lives in handleSlash, not in the five case bodies", () => {
  // If somebody "fixes" this later by editing each case instead, this
  // goes red: the next command naming a missing method would go
  // straight back to -32601 with nothing to catch it.
  assert.match(
    commandsSrc,
    /export function handleSlash[\s\S]*unsupportedReason\(result\?\.rpcMethod\)/,
    "handleSlash must apply the capability filter — otherwise a newly added " +
      "command that names a missing method silently regresses",
  );
  assert.match(
    commandsSrc,
    /function routeSlash\(/,
    "handleSlash should delegate to an unrouted inner function and filter its " +
      "result; a filter written INTO the switch cannot see future commands",
  );
});

test("line mode's direct probes consult the same table", () => {
  // `/agent-pick` does not go through handleSlash — it calls probe()
  // directly after the user picks. That one was still returning raw
  // -32601 while the Ink side already warned.
  //
  // R377: `/effort-pick` LEFT this list too. The daemon serves the
  // capability (`switchVariant`), so there is nothing to refuse —
  // line mode now calls the real method, exactly like Ink.
  for (const m of ["agent/setDefault"]) {
    assert.ok(
      lineSrc.includes(`unsupportedReason("${m}")`),
      `line.ts must check unsupportedReason("${m}") before probing it — the ` +
        "Ink panel already refuses in words and line mode would still emit a raw error",
    );
  }
  assert.match(
    lineSrc,
    /probe\(ctx, EFFORT_METHOD, \{ variant: picked \}/,
    "line mode must call the daemon's real method for the effort capability",
  );
  assert.match(
    lineSrc,
    /import \{[^}]*unsupportedReason[^}]*\} from "\.\/rpcCapabilities\.js"/,
    "line.ts must read the same table handleSlash does, not a local copy",
  );
});

// --- R375: naming a method the daemon cannot serve -------------------------

test("REGRESSION R375: the TUI names no method the daemon cannot serve", () => {
  // The three `task/*` methods used to sit in the refusal table, and
  // the TUI SENT them: the status bar's Continue/Pause/Stop buttons
  // called `task/resume` / `task/pause` / `task/kill` with a SESSION
  // id, and swallowed the -32601 — so the buttons did nothing at all.
  //
  // A refusal table is the wrong place to park that. The table says
  // "this method does not exist"; it cannot say "this button is
  // broken", and it certainly cannot make the button work. R375
  // points the buttons at methods that exist, so the invariant is now
  // structural: no RPC-capable source may NAME one of these methods
  // any more — not in a call, not in the refusal table.
  //
  // `codeOf` strips comments first, so the R375 rationale (which
  // mentions the old names in prose) does not trip the scan. That is
  // deliberate: prose about a removed method is not a call site.
  const SOURCES = [
    "src/tui.tsx",
    "src/line.ts",
    "src/commands.ts",
    "src/ac-tui.ts",
    "src/memory-rpc.ts",
    "src/panelIntents.ts",
    "src/state.ts",
    "src/runControl.ts",
    "src/rpcCapabilities.ts",
  ];
  const offenders = [];
  for (const rel of SOURCES) {
    const src = codeOf(readFileSync(join(root, rel), "utf-8"), rel);
    for (const m of src.matchAll(/["'](task\/[a-zA-Z]+)["']/g)) {
      offenders.push(`${m[1]}  <- ${rel}`);
    }
  }
  assert.deepEqual(
    offenders,
    [],
    "the TUI names task/* methods, which this daemon does not register — every\n" +
      "  call site is a -32601. Before R375 the status bar's buttons did exactly\n" +
      "  that and swallowed the rejection, so they silently did nothing.\n" +
      "  Use `cancel` (stop the run in flight) and `setContinuationStopped`\n" +
      "  (pause/resume this session's automatic continuation) instead:\n" +
      "  " +
      offenders.join("\n  "),
  );
});
