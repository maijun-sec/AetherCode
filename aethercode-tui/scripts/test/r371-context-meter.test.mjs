// R371 — `/context`: the row R362 shipped as a placeholder.
//
// ## Three half-rounds, one dead feature
//
// The context meter has been broken in three independent ways, each
// of which looked finished:
//
//   1. **T-441** wrote `ContextMeter.tsx` (251 lines), declared three
//      State fields, and wrote six source-pin assertions — but never
//      imported the component into `StatusBar` and never gave
//      anything a way to WRITE those three fields. Three of its six
//      assertions have been red in the full suite ever since, which
//      is the only reason anyone found out.
//
//   2. **`CompactMethods` was never registered.** It has had a
//      `registerAll` hook since T-193, and
//      `CrossCuttingMethodsT500Test` asserts all four `compact/*`
//      names are on the dispatcher — by calling
//      `new CompactMethods().registerAll(d)` on a dispatcher it built
//      ITSELF. The test constructed the object under test, so the
//      assertion was structurally incapable of failing. No production
//      code had ever instantiated it: `compact/status` answered
//      `-32601` on every daemon and `src/compact-rpc.ts`'s remote
//      mode had been dead since it was written.
//
//   3. **Nothing emitted `__CONTEXT_METER__`.** `/context` fell
//      through to "unknown slash command" while the registry row
//      said `blocker: 'no-command'`, `advertised: false`.
//
// The gate-shape sibling of R1121: the check matched, but it never
// asked whether the wiring happened in PRODUCTION.
//
// ## What this file pins
//
//   * both transports actually construct CompactMethods
//   * the HTTP switch routes all four `compact/*` names
//   * the three State fields have WRITERS, not just declarations
//   * `/context` exists, is advertised, and opens a real panel on BOTH
//     renderers
//   * the panel says what it does not know instead of printing a
//     confident zero

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdirSync, mkdtempSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { codeOf } from "./strip-comments.mjs";
import { javaCode, javaMethod } from "./java-strip.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const repo = join(root, "..");

const commandsSrc = codeOf(readFileSync(join(root, "src", "commands.ts"), "utf-8"), "commands.ts");
const tuiSrc = codeOf(readFileSync(join(root, "src", "tui.tsx"), "utf-8"), "tui.tsx");
const lineSrc = codeOf(readFileSync(join(root, "src", "line.ts"), "utf-8"), "line.ts");
const stateSrc = codeOf(readFileSync(join(root, "src", "state.ts"), "utf-8"), "state.ts");
const intentsSrc = codeOf(
  readFileSync(join(root, "src", "panelIntents.ts"), "utf-8"),
  "panelIntents.ts",
);
const sbSrc = codeOf(
  readFileSync(join(root, "src", "components", "StatusBar.tsx"), "utf-8"),
  "StatusBar.tsx",
);
const meterSrc = codeOf(
  readFileSync(join(root, "src", "components", "ContextMeter.tsx"), "utf-8"),
  "ContextMeter.tsx",
);

const runnerPath = join(
  repo,
  "aethercode",
  "aethercode-cli",
  "src",
  "main",
  "java",
  "org",
  "aethercode",
  "cli",
  "DaemonRunner.java",
);
const httpPath = join(
  repo,
  "aethercode",
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
const compactPath = join(
  repo,
  "aethercode",
  "aethercode-protocol",
  "src",
  "main",
  "java",
  "org",
  "aethercode",
  "protocol",
  "methods",
  "CompactMethods.java",
);
for (const p of [runnerPath, httpPath, compactPath]) {
  assert.ok(existsSync(p), `cannot find ${p}`);
}
const runnerCode = javaCode(readFileSync(runnerPath, "utf-8"));
const httpCode = javaCode(readFileSync(httpPath, "utf-8"));
const compactCode = javaCode(readFileSync(compactPath, "utf-8"));

/** Bounded extraction by BRACE MATCHING. Guessed character windows
 *  fail by going green — Lesson 1138. */
function block(src, header) {
  const at = src.search(header);
  assert.notEqual(at, -1, `block(): header not found: ${header}`);
  const open = src.indexOf("{", at);
  let depth = 0;
  for (let i = open; i < src.length; i++) {
    if (src[i] === "{") depth++;
    else if (src[i] === "}" && --depth === 0) return src.slice(open, i + 1);
  }
  throw new Error(`block(): unbalanced braces after ${header}`);
}

// --- compiled modules ----------------------------------------------------

const tmp = mkdtempSync(join(tmpdir(), "r371-"));
const tsc = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
const compiled = spawnSync(
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
    "--jsx",
    "react",
    "--esModuleInterop",
    "true",
    "--skipLibCheck",
    "true",
    "--rootDir",
    join(root, "src"),
    `"${join(root, "src", "commands.ts")}"`,
    `"${join(root, "src", "state.ts")}"`,
    `"${join(root, "src", "panelIntents.ts")}"`,
  ],
  { encoding: "utf-8", shell: true },
);
if (compiled.status !== 0) {
  console.error("tsc failed:\n" + compiled.stdout + "\n" + compiled.stderr);
  process.exit(1);
}
const imp = (f) => import(pathToFileURL(join(tmp, "out", f)).href);
const { routeSlash } = await imp("commands.js");
const { INITIAL, reducer } = await imp("state.js");
const { PANEL_INTENTS, ADVERTISED_INTENTS, intentForCommand, helpByGroup } =
  await imp("panelIntents.js");

// =========================================================================
test("CompactMethods is constructed in PRODUCTION, not only in its own test", () => {
  // The assertion CrossCuttingMethodsT500Test made — "all four are
  // registered" — is real, and it is also structurally incapable of
  // failing, because it registers them on a dispatcher it built. So
  // the check that matters is UPSTREAM of it: does anything outside
  // the tests construct the object at all?
  //
  // It is tempting to look for `new CompactMethods(` inside
  // CompactMethods.java. That file DEFINES the class; it can never
  // construct itself, so the search is guaranteed to fail and an
  // assertion that reads like a real check is decoration. Search the
  // CALLERS.
  assert.ok(
    /class\s+CompactMethods\b/.test(compactCode),
    "CompactMethods.java no longer declares the class — re-anchor this test",
  );

  // ...and that something builds it. Both transports, because the TUI
  // speaks HTTP and the desktop speaks stdio, and a stdio-only
  // registration is the R720/R367 shape all over again.
  //
  // `runHttp` has THREE overloads and TWO of them are one-line
  // delegates into the four-arg one. A plain `indexOf` finds the
  // 2-arg delegate, whose 72-char body contains no wiring at all, so
  // this assertion reported "runHttp never constructs CompactMethods"
  // twice while the code was correct.
  //
  // Same lesson as R370's `viewPrimary` sitting 600 lines above the
  // picker, and as R368's `matchAll` with no floor: an extraction that
  // succeeds while reading the WRONG THING is worse than one that
  // fails loudly. Hence `occurrence`, not a longer guess at a
  // signature.
  const RUN_HTTP_IMPL = "EngineFactoryWithSpec specFactory) {";
  const runInternal = javaMethod(runnerCode, "private static int runInternal(");
  const runHttp = javaMethod(runnerCode, RUN_HTTP_IMPL, 2);
  const NEW_CM = /new\s+(?:org\.aethercode\.protocol\.methods\.)?CompactMethods\s*\(/;
  assert.ok(
    NEW_CM.test(runInternal),
    "DaemonRunner.runInternal never constructs CompactMethods — `compact/*` " +
      "would be -32601 on the stdio daemon",
  );
  assert.ok(
    NEW_CM.test(runHttp),
    "DaemonRunner.runHttp never constructs CompactMethods — `compact/*` " +
      "would be -32601 on the HTTP daemon the TUI actually talks to",
  );
  // Both must be handed a compactor, or the stdio and HTTP
  // views of "auto-compact" disagree and the panel reports one of them.
  //
  // `.compactor()` not `sdk.compactor()`: the two transports name the
  // pattern variable differently (`sdk` vs `sdkHttp`) for no reason
  // the contract cares about, and a test that hard-codes one spelling
  // fails on a rename that changed no behaviour. Seventh appearance
  // of "the assertion is pinned to a spelling rather than to the
  // property" — R364's regex, R368's floor, R370's window, R370's
  // `panelOf`, R371's overload, and now this.
  assert.ok(
    /\.compactor\(\)/.test(runInternal) && /\.compactor\(\)/.test(runHttp),
    "one transport hands CompactMethods a null compactor while the other " +
      "hands it a real one — the two would disagree about whether auto-compact is on",
  );
});

test("the stdio dispatcher is told to register all four", () => {
  const runInternal = javaMethod(runnerCode, "private static int runInternal(");
  assert.ok(
    /compactRpcs\s*\.\s*registerAll\s*\(\s*server\s*\.\s*dispatcher\s*\(\s*\)\s*\)/.test(
      runInternal,
    ),
    "CompactMethods is constructed but never registered on the stdio dispatcher",
  );
});

test("the HTTP transport is TOLD about the same instance", () => {
  // The R367 lesson, verbatim. Registering on stdio and forgetting the
  // hand-rolled HTTP switch is how memory/* shipped broken in R720 and
  // permission/*+grants/* in R367.
  assert.ok(
    /setCompactMethods/.test(httpCode),
    "HttpJsonRpcServer has no setCompactMethods — the daemon has no way to " +
      "hand it the instance",
  );
  const runHttp = javaMethod(runnerCode, "EngineFactoryWithSpec specFactory) {", 2);
  assert.ok(
    /http\s*\.\s*setCompactMethods\s*\(/.test(runHttp),
    "DaemonRunner.runHttp never calls http.setCompactMethods — every " +
      "compact/* over HTTP would answer -32601",
  );
});

test("all four compact/* names are routed by the HTTP switch", () => {
  // Two derivations, on purpose. The first enumerates the four names
  // as literals; the second asks which names `CompactMethods` itself
  // registers and requires every one of them to be routed.
  //
  // The mutation battery deleted the literal loop and the paired
  // defect went green, because that loop was the ONLY thing checking
  // the arms. Deriving from `registerAll` is the independent path: it
  // cannot be silently emptied, and it also catches the case where a
  // FIFTH method is added to CompactMethods and forgotten on HTTP.
  for (const name of ["compact/run", "compact/status", "compact/reset", "compact/history"]) {
    const arm = new RegExp(`case\\s+"${name.replace("/", "\\/")}"\\s*->\\s*routeCompact\\(`);
    assert.ok(
      arm.test(httpCode),
      `HttpJsonRpcServer does not route ${name} through routeCompact — the ` +
        `method is registered on the daemon and unreachable over HTTP`,
    );
  }

  const registered = new Set(
    [
      ...javaMethod(compactCode, "public void registerAll(").matchAll(
        /dispatcher\s*\.\s*register\s*\(\s*METHOD_([A-Z_]+)/g,
      ),
    ].map((m) => `compact/${m[1].toLowerCase().replace("_", "/")}`),
  );
  assert.ok(
    registered.size >= 4,
    `registerAll only exposed ${registered.size} method(s) — re-anchor this ` +
      `test against CompactMethods.METHOD_*`,
  );
  for (const name of registered) {
    assert.ok(
      new RegExp(`case\\s+"${name.replace("/", "\\/")}"\\s*->\\s*routeCompact\\(`).test(httpCode),
      `CompactMethods.registerAll exposes ${name} but HttpJsonRpcServer does ` +
        `not route it — stdio works and HTTP answers -32601`,
    );
  }
});

test("routeCompact fails loudly when unwired, not with a quiet envelope", () => {
  // `{ok:false}` here would let the TUI render a confident
  // "auto-compact is on" that nothing verified — the same
  // list-shaped-lie argument that made routePermission throw.
  //
  // The assertion is POSITIONAL, not "the file mentions throw". A
  // plain `/throw/` check is satisfied by dead code:
  //
  //     if (false) { throw new JsonRpcProtocolException(...); }
  //
  // keeps the token and passes, while the unwired daemon answers with
  // a null the TUI reads as a real status. A source pin cannot see a
  // dead branch; what it CAN see is the guard having disappeared.
  // So: the null check must still exist, and the throw must be close
  // behind it.
  const body = javaMethod(httpCode, "private <T> T routeCompact(");
  const guard = body.indexOf("cm == null");
  assert.notEqual(
    guard,
    -1,
    "routeCompact no longer checks for an unwired CompactMethods — an " +
      "unconfigured daemon would fall through instead of failing loudly",
  );
  assert.ok(
    /throw\s+new\s+JsonRpcProtocolException/.test(body.slice(guard, guard + 400)),
    "the null guard does not throw within reach — an unwired daemon answers " +
      "with a value the TUI cannot distinguish from a real one",
  );
});

// =========================================================================
test("the three State fields have WRITERS, not just declarations", () => {
  // T-441 declared `contextMaxTokens`, `lastCompactTs` and
  // `autoCompactDisabled` and never wrote any of them. A declaration
  // and a default value look identical to a source pin — only a
  // reducer case that ASSIGNS them proves the field can move.
  const setCtx = block(stateSrc, /case "setContextInfo":/);
  assert.ok(/contextInfo:\s*info/.test(setCtx), "setContextInfo never stores the snapshot");
  assert.ok(
    /contextMaxTokens:\s*info\.contextWindow/.test(setCtx),
    "setContextInfo never writes contextMaxTokens — the meter stays on its fallback forever",
  );

  const setCompact = block(stateSrc, /case "setCompactStatus":/);
  assert.ok(
    /autoCompactDisabled:\s*action\.autoCompactDisabled/.test(setCompact),
    "setCompactStatus never writes autoCompactDisabled",
  );
  assert.ok(/lastCompactTs/.test(setCompact), "setCompactStatus never writes lastCompactTs");
});

test("lastCompactTs only moves forward", () => {
  const body = block(stateSrc, /case "setCompactStatus":/);
  assert.ok(
    /next\s*>\s*state\.lastCompactTs/.test(body),
    "lastCompactTs can be rewound — a status poll that fails or answers " +
      "with an older event would re-pin the meter's '↓ compacted' hint",
  );
});

test("setContextInfo rejects a zero window rather than dividing by it", () => {
  // A daemon answering `ok:true, contextWindow:0` would leave the
  // ratio undefined and the bar reading a confident 0%. That is the
  // invented-measurement failure one layer below the one the "—"
  // label fixes.
  const eff = block(
    tuiSrc,
    /useEffect\(\(\) => \{\n\s*let cancelled = false;\n\s*const refresh = async/,
  );
  assert.ok(
    /if\s*\(window\s*<=\s*0\)\s*return;/.test(eff),
    "tui.tsx adopts a zero contextWindow — the meter would divide by it and " +
      "show an invented 0%",
  );
});

test("a failed context poll leaves the last good answer in place", () => {
  // Scoped by the effect's own anchor. The first draft used
  // `block(tuiSrc, /const refresh = async/)`, and `block()` returns
  // the FIRST brace after the header — which is the `{` of the arrow
  // function's own parameter destructuring... no, of its body, one
  // character later than intended, and the test then reported a
  // missing catch on a body that had two.
  const eff = block(tuiSrc, /const refresh = async \(\) => \{/);
  const catches = [...eff.matchAll(/catch\s*\{([\s\S]*?)\}/g)];
  assert.ok(
    catches.length >= 2,
    `expected the poll to guard BOTH getContextInfo and compact/status, ` +
      `found ${catches.length} catch blocks — one of the two failures would ` +
      `reject the whole refresh and skip the other read`,
  );
  // The catch bodies are read through `codeOf`, which BLANKS COMMENT
  // TEXT. An assertion on the words "best-effort" therefore can never
  // match, no matter what the developer writes — it is a check of the
  // comment, not of the code, and it would pass or fail for reasons
  // that have nothing to do with correctness.
  //
  // So assert the BEHAVIOUR: an empty catch. A populated one means the
  // error escapes `refresh()`, the interval callback rejects, and the
  // node process takes an unhandled rejection.
  for (const c of catches) {
    assert.equal(
      c[1].trim(),
      "",
      "a catch block in the context poll is not empty — the error escapes " +
        "the interval callback as an unhandled rejection",
    );
  }
  assert.ok(!/throw\s/.test(eff), "the poll rethrows");
  assert.ok(
    !/fail\(/.test(eff),
    "the poll calls the panel's fail() helper, which would overwrite the " +
      "panel status on every 15 s tick",
  );
  assert.ok(
    !/dispatch\(\{\s*type:\s*"setPanelStatus"/.test(eff),
    "the poll writes panel status — that is the OPEN PANEL's status line, " +
      "and a background refresh must not claim it",
  );
});

// =========================================================================
test("/context exists and answers with the meter sentinel", () => {
  const r = routeSlash("/context");
  assert.equal(
    r?.local,
    "__CONTEXT_METER__",
    "/context must open the meter — it previously fell through to " +
      '"unknown slash command" while the registry advertised a panel for it',
  );
  assert.equal(routeSlash("/ctx")?.local, "__CONTEXT_METER__", "the /ctx alias drifted");
});

test("the registry row is a real surface now, and /help lists it", () => {
  const p = intentForCommand("/context");
  assert.ok(p, "no registry row claims /context");
  assert.equal(p.id, "context-meter");
  assert.equal(p.ink, "mounted");
  assert.equal(p.line, "rendered");
  assert.equal(
    p.blocker,
    undefined,
    "the row still declares a blocker — there is no longer a gap to declare",
  );
  assert.notEqual(p.advertised, false, "/context is reachable, so /help must list it");
  const cmds = helpByGroup().flatMap((g) => g.entries.map((e) => e.command));
  assert.ok(cmds.includes("/context"), "/help does not list /context");
});

test("both renderers claim the sentinel with a real arm", () => {
  // The Ink side resolves through PANEL_BY_INTENT, so both facts are
  // needed: the mapping AND the render case.
  const m = /const PANEL_BY_INTENT[^=]*=\s*\{([\s\S]*?)\n\};/.exec(tuiSrc);
  assert.ok(m, "PANEL_BY_INTENT literal not found");
  assert.ok(
    /"context-meter"\s*:\s*"context"/.test(m[1]),
    'PANEL_BY_INTENT has no "context-meter" entry, so the sentinel resolves to nothing',
  );
  assert.ok(/case "context":/.test(tuiSrc), 'there is no `case "context":` render branch');

  const arm = /case\s+"__CONTEXT_METER__"\s*:\s*([\s\S]*?)\n\s*return true;/.exec(lineSrc);
  assert.ok(arm, "line.ts has no __CONTEXT_METER__ arm — cmd.exe users get the raw token");
  assert.ok(/panelContext\(/.test(arm[1]), "the arm does not call panelContext");
});

// =========================================================================
test("the panel renders the anchors, not just a percentage", () => {
  const ink = block(tuiSrc, /case "context":/);
  // A bar with nothing to anchor it is the "successful but empty
  // panel" shape. Model, window and the usage estimate all have to be
  // visible, because the number is meaningless without them.
  for (const needle of [
    "info.model",
    "info.contextWindow",
    "info.estimatedTokens",
    "info.maxOutputTokens",
  ]) {
    assert.ok(ink.includes(needle), `the /context panel never renders ${needle}`);
  }
  assert.ok(
    /no usage reported yet/.test(meterSrc),
    "ContextMeter lost its 'no measurement' label — an empty bar now reads as 0%",
  );
  assert.ok(
    /no usage reported yet|the daemon has not reported a context window/.test(ink),
    "the /context panel has no unknown-state branch",
  );
});

test("the panel admits the token count is an estimate", () => {
  // `getContextInfo` computes `estimatedTokens = transcriptChars / 4`
  // and its own comment calls that "rough". Rendering it as a precise
  // token count is the panel's first lie.
  assert.ok(/estimate/i.test(tuiSrc), "the Ink panel never says the count is an estimate");
  assert.ok(/estimate/i.test(lineSrc), "the line panel never says the count is an estimate");
});

test("StatusBar embeds the meter with the width the terminal can afford", () => {
  assert.ok(
    /import\s*\{[^}]*ContextMeter[^}]*\}\s*from\s*"\.\/ContextMeter\.js"/.test(sbSrc),
    "StatusBar does not import ContextMeter — the T-441 integration is gone again",
  );
  assert.ok(/<ContextMeter/.test(sbSrc), "StatusBar renders no ContextMeter");
  assert.ok(
    /width=\{24\}/.test(sbSrc),
    "the 200-char default would push the token counters off an 80-column terminal",
  );

  // The nullable pass-through. `?? 0` here is the invented
  // measurement this round exists to remove: it renders a confident
  // `0%` against the 200_000 fallback for the whole window before the
  // daemon has reported anything, and the user reads it as a
  // measurement. The mutation battery caught its absence here.
  assert.ok(
    /inputTokens:\s*state\.inputTokens\s*,/.test(sbSrc),
    "StatusBar must pass state.inputTokens through UNCHANGED — coercing it " +
      "to 0 makes an empty bar look like a reading",
  );
  assert.ok(
    !/inputTokens:\s*state\.inputTokens\s*\?\?/.test(sbSrc),
    "StatusBar coerces a null inputTokens to 0 — 'unknown' and 'zero tokens " +
      "used' are different facts",
  );
});

// =========================================================================
test("the reducer moves the fields the meter reads", () => {
  const s = reducer(
    { ...INITIAL, contextMaxTokens: null },
    {
      type: "setContextInfo",
      info: {
        provider: "minimax",
        model: "m2",
        contextWindow: 128_000,
        maxOutputTokens: 8_000,
        transcriptChars: 40_000,
        estimatedTokens: 10_000,
        usagePercent: 8,
      },
    },
  );
  assert.equal(s.contextMaxTokens, 128_000, "contextMaxTokens did not take the daemon's window");
  assert.equal(s.contextInfo?.model, "m2");

  const t = reducer(
    { ...s, lastCompactTs: 5_000 },
    { type: "setCompactStatus", autoCompactDisabled: true, lastEventTs: 4_000 },
  );
  assert.equal(t.autoCompactDisabled, true);
  assert.equal(
    t.lastCompactTs,
    5_000,
    "an older compact event rewound lastCompactTs — the hint would re-pin itself",
  );

  const u = reducer(t, {
    type: "setCompactStatus",
    autoCompactDisabled: false,
    lastEventTs: 9_000,
  });
  assert.equal(u.lastCompactTs, 9_000);
  assert.equal(u.autoCompactDisabled, false);
});

// =========================================================================
test("a no-command placeholder is kept out of /help — proven with a synthetic row", () => {
  // The last real `no-command` row was `/context`, and R371 closed it.
  // Its mutation case ("remove `advertised: false`") therefore became
  // unexpressible: there is nothing left to advertise that has no
  // command behind it. Both a gate assertion and a mutation that
  // depend on "some row happens to be in this state" die the moment
  // the codebase is healthy — the r365 battery's case went NO-OP for
  // exactly this reason.
  //
  // So the rule is checked against a row we construct, not one we
  // happen to ship. It cannot rot with the table.
  const rows = ADVERTISED_INTENTS;
  const synthetic = {
    id: "synthetic-no-command",
    sentinels: ["__SYNTHETIC_NO_COMMAND__"],
    command: "/synthetic-no-command",
    title: "synthetic",
    group: "system",
    ink: "unavailable",
    line: "unavailable",
    blocker: "no-command",
    gapNote: "synthetic",
    advertised: false,
  };
  const all = PANEL_INTENTS.concat([synthetic]);
  const advertised = all.filter((p) => p.advertised !== false);

  assert.ok(
    !advertised.some((p) => p.id === synthetic.id),
    "a `no-command` placeholder reached /help — typing it answers " +
      '"unknown slash command", which is the R362/R365 defect class"',
  );
  // Control: the SAME row without the flag IS advertised, so the
  // assertion above measures the flag and not the input.
  const { advertised: _drop, ...withoutFlag } = synthetic;
  assert.ok(
    all
      .concat([withoutFlag])
      .filter((p) => p.advertised !== false)
      .some((p) => p.id === synthetic.id),
    "an un-flagged row is ALSO excluded — the probe was not a fair test",
  );
  // And the real table is still consistent with itself.
  assert.equal(advertised.length, rows.length);
});

// =========================================================================
test("the /help contract is now stated positively", () => {
  // R365 pinned "helpByGroup excludes non-advertised rows" by naming
  // `/context` — a general rule written as a special case, which died
  // the moment the special case changed. This is the positive form and
  // it has to keep holding for every future row.
  for (const p of ADVERTISED_INTENTS) {
    const r = routeSlash(p.command);
    assert.ok(
      r !== null && r !== undefined,
      `${p.command} is advertised in /help but handleSlash does not recognise it`,
    );
    assert.ok(
      !(typeof r.local === "string" && /^unknown slash command/.test(r.local)),
      `${p.command} is advertised but answers "unknown slash command"`,
    );
  }
  for (const p of PANEL_INTENTS) {
    if (p.blocker === "no-command") {
      assert.notEqual(
        p.advertised,
        true,
        `${p.command} is a no-command placeholder and must stay out of /help`,
      );
    }
  }
});
