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
const capsSrc = codeOf(readFileSync(join(root, "src", "rpcCapabilities.ts"), "utf-8"), "rpcCapabilities.ts");
const lineSrc = codeOf(readFileSync(join(root, "src", "line.ts"), "utf-8"), "line.ts");

/** Compile the two modules under test and import the real values. */
async function load() {
  const tmp = join(tmpdir(), `r369-${process.pid}`);
  mkdirSync(tmp, { recursive: true });
  const tsc = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
  const r = spawnSync(`"${tsc}"`, [
    "--outDir", join(tmp, "out"), "--target", "ES2022", "--module", "ES2022",
    "--moduleResolution", "bundler", "--rootDir", join(root, "src"),
    `"${join(root, "src", "commands.ts")}"`,
  ], { encoding: "utf-8", shell: true });
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
  const entries = [...capsSrc.matchAll(/^\s*\[\s*"[a-z][\w/]*",/gm)];
  assert.ok(entries.length >= 7,
    `only ${entries.length} entries parsed from rpcCapabilities.ts — the table ` +
    "probably stopped parsing, which would make the honesty checks vacuous");

  const reasons = [...capsSrc.matchAll(/\[\s*\n?\s*"([a-z][\w/]*)",\s*\n?\s*"([^"]+)"/g)];
  assert.ok(reasons.length >= 7,
    `only ${reasons.length} reasons parsed — the per-reason check would run ` +
    "zero times and pass on a table it never read");
  for (const m of reasons) {
    assert.ok(m[2].length > 25, `${m[1]}'s reason is too terse to show a user: ${m[2]}`);
  }
});

// --- the behaviour ------------------------------------------------------

test("every backend-missing command answers in words, not with an rpcMethod", () => {
  // The five advertised ones. `/rewind` only reaches its RPC with an
  // argument — with none it returns a usage string, which is already
  // honest, so both forms are asserted.
  const CASES = [
    ["/stop", "task/kill"],
    ["/stop 42", "task/kill"],
    ["/pause", "task/pause"],
    ["/continue", "task/resume"],
    ["/rewind 3", "rewind"],
    ["/tokens", "session/tokens"],
  ];
  for (const [input, method] of CASES) {
    const res = handleSlash(input);
    assert.equal(res?.rpcMethod, undefined,
      `${input} still returns rpcMethod=${res?.rpcMethod} — the capability filter ` +
      "is not running, so this answers -32601");
    assert.ok(res?.local, `${input} returned neither local nor rpcMethod`);
    assert.ok(res.local.includes(method),
      `${input} should name ${method} in its refusal, got: ${res.local}`);
    assert.doesNotMatch(res.local, /__[A-Z0-9_]+__/,
      "a refusal must be a sentence, never a leaked sentinel token");
    assert.doesNotMatch(res.local, /-32601|method not found/i,
      "the refusal must not leak the JSON-RPC error the user cannot act on");
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
      assert.equal(res?.rpcMethod, expectRpc,
        `${input} should still reach ${expectRpc}, got local="${res?.local}"`);
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
  assert.match(commandsSrc, /export function handleSlash[\s\S]*unsupportedReason\(result\?\.rpcMethod\)/,
    "handleSlash must apply the capability filter — otherwise a newly added " +
    "command that names a missing method silently regresses");
  assert.match(commandsSrc, /function routeSlash\(/,
    "handleSlash should delegate to an unrouted inner function and filter its " +
    "result; a filter written INTO the switch cannot see future commands");
});

test("line mode's direct probes consult the same table", () => {
  // `/agent-pick` and `/effort-pick` do not go through handleSlash —
  // they call probe() directly after the user picks. Those two were
  // still returning raw -32601 while the Ink side already warned.
  for (const m of ["agent/setDefault", "effort/set"]) {
    assert.ok(lineSrc.includes(`unsupportedReason("${m}")`),
      `line.ts must check unsupportedReason("${m}") before probing it — the ` +
      "Ink panel already refuses in words and line mode would still emit a raw error");
  }
  assert.match(lineSrc, /import \{ unsupportedReason, unsupportedMessage \} from "\.\/rpcCapabilities\.js"/,
    "line.ts must read the same table handleSlash does, not a local copy");
});