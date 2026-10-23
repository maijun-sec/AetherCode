// R366 — every JSON-RPC method name the TUI sends must be one the
// daemon actually registers.
//
// ## The pattern, three times over
//
//   R364  `setCwd`       — the DESKTOP's Tauri IPC command, sent over
//                         JSON-RPC. Killed every `/cwd-pick`.
//   R366  `model/set`    — DTOs exist (`protocol/rpc/model/ModelSetParams`,
//                         Phase 1.2 T-1-19), no handler was ever wired,
//                         and a T-6-17 comment claimed "the daemon maps
//                         both". Killed every `/model <name>`.
//   R366  `workflow/list`, `workflow/run`
//                       — registered on `SupervisorRpcServer`
//                         (aethercode-tasks), which is only started by
//                         the standalone task CLI. The daemon the TUI
//                         spawns is `DaemonRunner` ->
//                         `AetherCodeMethods`, which registers the FLAT
//                         `listWorkflows` / `runWorkflow`.
//
// All three are invisible to the compiler and identical at runtime:
// `-32601 method not found`. Each was found by READING THE REGISTRATION
// POINT, never by reading the call site.
//
// This test generalises that: it extracts every method name the TUI
// sends and checks it against what `AetherCodeMethods.registerAll` +
// `MemoryMethods` + `PermissionMethods` + `GrantMethods` register and
// what `HttpJsonRpcServer` routes.
//
// ## R367 — registration is not reachability
//
// The first version of this gate would have reported
// `permission/list`, `permission/revoke` and `grants/revoke` as
// KNOWN and been satisfied. They were NOT reachable: the classes
// declared the constants and implemented `registerAll`, and
// `DaemonRunner` never called it. Same failure as `model/set`, one
// layer down — the code exists, the seam is missing.
//
// So the gate now has a second question, and it is the one that
// actually matches what the TUI experiences:
//   1. is the name REGISTERED somewhere?  (class-level)
//   2. is the registering class WIRED into the daemon the TUI spawns?
//      (DaemonRunner.registerAll on its dispatcher)
// Both, or the method is -32601 as far as the user is concerned.
//
// ## The allowlist
//
// Seven TUI call sites name methods the daemon genuinely does not have.
// Those are recorded below with the reason, so the gate is green AND
// the gap stays visible and countable instead of being hidden by
// deleting the assertion.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdirSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { codeOf } from "./strip-comments.mjs";
// R370: the Java scanner moved to its own module so the R370 gate can
// reuse it. A second copy would be a second UNTESTED scanner, and the
// self-test below (`javaCodeHandlesTheApostropheTrap`) is the only
// reason to trust any of them.
import { javaCode } from "./java-strip.mjs";

/**
 * Compile `src/rpcCapabilities.ts` and import the real value out of
 * it. Node cannot import a .ts directly, and a JSON sidecar or a
 * regex re-read of the source would be a SECOND copy of the table —
 * which is the duplication this change exists to remove.
 */
async function loadCapabilities() {
  const tmp = join(tmpdir(), `r366-caps-${process.pid}`);
  mkdirSync(tmp, { recursive: true });
  const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
  const r = spawnSync(`"${tscBin}"`, [
    "--outDir", join(tmp, "out"), "--target", "ES2022", "--module", "ES2022",
    "--moduleResolution", "bundler", "--rootDir", join(root, "src"),
    `"${join(root, "src", "rpcCapabilities.ts")}"`,
  ], { encoding: "utf-8", shell: true });
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
    process.exit(1);
  }
  const mod = await import(pathToFileURL(join(tmp, "out", "rpcCapabilities.js")).href);
  return mod.BACKEND_MISSING;
}

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

/** TUI files that can issue an RPC. */
const TUI_SOURCES = [
  "src/tui.tsx", "src/line.ts", "src/commands.ts", "src/ac-tui.ts",
  "src/memory-rpc.ts", "src/panelIntents.ts", "src/state.ts",
];

const protocolDir = join(root, "..", "aethercode", "aethercode-protocol", "src", "main", "java",
  "org", "aethercode", "protocol");
const methodsPath = join(protocolDir, "methods", "AetherCodeMethods.java");
const memoryPath = join(protocolDir, "methods", "MemoryMethods.java");
const permissionPath = join(protocolDir, "methods", "PermissionMethods.java");
const grantPath = join(protocolDir, "methods", "GrantMethods.java");
const httpPath = join(protocolDir, "http", "HttpJsonRpcServer.java");
// R367: the seam. The TUI spawns `aethercode.jar --daemon`, which is
// DaemonRunner — NOT SupervisorProcess. A method class nobody wires in
// here is dead no matter how complete its implementation is.
const daemonRunnerPath = join(root, "..", "aethercode", "aethercode-cli", "src", "main", "java",
  "org", "aethercode", "cli", "DaemonRunner.java");
// R367: grants/list is NOT answered by AetherCodeMethods — GrantMethods
// delegates to GrantHandlers, which wraps the row list in an envelope.
const grantHandlersPath = join(root, "..", "aethercode", "aethercode-tasks", "src", "main", "java",
  "org", "aethercode", "tasks", "rpc", "GrantHandlers.java");

const REQUIRED_PROTOCOL_SOURCES = [methodsPath, memoryPath, permissionPath, grantPath, httpPath, daemonRunnerPath, grantHandlersPath];
const hasProtocol = REQUIRED_PROTOCOL_SOURCES.every((p) => existsSync(p));

// --- what the daemon registers ---------------------------------------------

const methodsSrc = readFileSync(methodsPath, "utf-8");
const memorySrc = readFileSync(memoryPath, "utf-8");
const permissionSrc = readFileSync(permissionPath, "utf-8");
const grantSrc = readFileSync(grantPath, "utf-8");
const httpSrc = readFileSync(httpPath, "utf-8");
const daemonRunnerSrc = readFileSync(daemonRunnerPath, "utf-8");
const grantHandlersSrc = readFileSync(grantHandlersPath, "utf-8");

/** registered name -> the java method it routes to */
const dispatcher = new Map();
for (const m of methodsSrc.matchAll(/dispatcher\.register\(\s*"([^"]+)"\s*,\s*this::(\w+)/g)) {
  dispatcher.set(m[1], m[2]);
}
for (const m of memorySrc.matchAll(/METHOD_[A-Z_]+\s*=\s*"([^"]+)"/g)) {
  dispatcher.set(m[1], m[1]);
}

/**
 * Method classes that live OUTSIDE AetherCodeMethods and therefore have
 * to be wired into DaemonRunner explicitly. Each entry is parsed for
 * its METHOD_* constants AND checked for a live `registerAll` call, so
 * "the class exists" can never be mistaken for "the method works".
 *
 * `owner` is the Java type; `src` is its source; `wireVar` is the local
 * name DaemonRunner assigns it (re-derived below, not hard-coded).
 */
const EXTRA_METHOD_CLASSES = [
  { type: "PermissionMethods", src: permissionSrc, wiredVar: "permRpcs" },
  { type: "GrantMethods", src: grantSrc, wiredVar: "grantRpcs" },
];

/** names -> owning class, for every method class that IS wired in. */
const wiredDispatcher = new Map(dispatcher);
const declaredButUnwired = [];

/**
 * Blank out Java comments, preserving offsets and string/char
 * literals.
 *
 * ORDER MATTERS and the order is not arbitrary. `//` is checked
 * before `/*` is considered, and both are checked before a `"` or
 * `'` is treated as opening a literal — because a literal opener
 * inside a comment must never be seen. DaemonRunner.java contains
 *
 *     // MemoryMethods.registerAll(); the HTTP path needs the
 *     // LayeredMemoryStore attached (the stdio daemon's
 *
 * and that apostrophe in "daemon's" is the trap. A scanner that
 * reaches it as a char-literal opener swallows every subsequent
 * real code line until it finds another apostrophe — ~2.4 KB of
 * DaemonRunner, including the http.setPermissionMethods call the
 * gate reads. The R366 bypass mutation turned this exact failure
 * on: see `javaCodeHandlesTheApostropheTrap` below.
 *
 * R370 moved the body to `./java-strip.mjs`; the self-test below
 * stays here, because a scanner with no self-test is the thing this
 * comment has been warning about since R364.
 */
const daemonRunnerCode = javaCode(daemonRunnerSrc);

for (const cls of EXTRA_METHOD_CLASSES) {
  // Derive the local DaemonRunner assigns this class to. Hard-coding it
  // would make this a rename detector instead of a wiring detector.
  const assignRe = new RegExp(`${cls.type}\\s+([A-Za-z_$][\\w$]*)\\s*=`);
  const assigned = assignRe.exec(daemonRunnerCode);
  const varName = assigned ? assigned[1] : null;
  const isWired = !!varName && daemonRunnerCode.includes(`${varName}.registerAll(`);

  for (const m of cls.src.matchAll(/METHOD_[A-Z_]+\s*=\s*"([^"]+)"/g)) {
    if (isWired) wiredDispatcher.set(m[1], m[1]);
    else declaredButUnwired.push(`${cls.type} declares ${m[1]} but DaemonRunner never calls ${varName ?? "?"}.registerAll(...)`);
  }
}

/** The HTTP transport is a SECOND hand-rolled switch; both must route it. */
const httpCases = new Set();
for (const m of httpSrc.matchAll(/case\s+"([^"]+)"\s*->/g)) httpCases.add(m[1]);

const KNOWN_METHODS = new Set([...wiredDispatcher.keys(), ...httpCases]);

// --- what the TUI sends -----------------------------------------------------

/** Extract every method name the TUI could put on the wire. */
function sentMethods() {
  const found = new Map(); // name -> Set(files)
  for (const rel of TUI_SOURCES) {
    const src = codeOf(readFileSync(join(root, rel), "utf-8"), rel);
    const patterns = [
      /\.request(?:<[^>]*>)?\(\s*["']([a-zA-Z][\w/.]*)["']/g,
      /rpcMethod:\s*["']([a-zA-Z][\w/.]*)["']/g,
      /probe\(\s*ctx,\s*["']([a-zA-Z][\w/.]*)["']/g,
      /request\(\s*["']([a-zA-Z][\w/.]*)["']\s*,/g,
    ];
    for (const re of patterns) {
      for (const m of src.matchAll(re)) {
        if (!found.has(m[1])) found.set(m[1], new Set());
        found.get(m[1]).add(rel);
      }
    }
  }
  return found;
}

/**
 * Methods the TUI sends that the daemon genuinely does not have.
 *
 * R369: this used to be declared HERE, which made the test file the
 * only place the reasons were written down — so the UI, which needs
 * the same knowledge to answer honestly instead of firing a doomed
 * request, had nowhere to read them from. It now lives in
 * `src/rpcCapabilities.ts` and is COMPILED AND IMPORTED, so the gate
 * and the UI read one table and cannot drift. `handleSlash` consults
 * that same table at dispatch time.
 *
 * Every entry still needs a reason. This is NOT a suppression list for
 * "the gate is inconvenient" — it is the count of known-broken
 * surfaces, and it grows if someone adds a call site for a method
 * that does not exist.
 */
const BACKEND_MISSING = await loadCapabilities();

test("the protocol sources are locatable (skip rather than false-green)", () => {
  const missing = REQUIRED_PROTOCOL_SOURCES.filter((p) => !existsSync(p));
  assert.ok(hasProtocol,
    `could not find: ${missing.join(", ")} — the daemon contract cannot be verified. ` +
    "This test must not silently pass when it cannot check anything.");
});

test("extraction is non-trivial in BOTH directions", () => {
  // A regex that stops matching (renamed call style, new helper) makes
  // every assertion below vacuously true. Three floors now, because
  // the wired-model added a second parse that can fail silently:
  const sent = sentMethods();
  assert.ok(sent.size >= 40,
    `only ${sent.size} method names extracted from the TUI — the patterns probably stopped matching`);
  assert.ok(dispatcher.size >= 100,
    `only ${dispatcher.size} registrations parsed — the register() pattern probably drifted`);
  const perms = (permissionSrc.match(/METHOD_[A-Z_]+\s*=\s*"permission\/[a-z]+"/g) || []).length;
  assert.ok(perms === 5,
    `expected 5 permission/* METHOD_ constants, parsed ${perms} — re-anchor this test`);
  const grants = (grantSrc.match(/METHOD_[A-Z_]+\s*=\s*"grants\/[a-zA-Z]+"/g) || []).length;
  assert.ok(grants === 4,
    `expected 4 grants/* METHOD_ constants, parsed ${grants} — re-anchor this test`);
});

test("REGRESSION R367: every extra method class is actually wired into DaemonRunner", () => {
  // The whole point of the round. `PermissionMethods` and
  // `GrantMethods` declared their METHOD_* constants and implemented
  // `registerAll` for the entire life of the codebase, and
  // `DaemonRunner` never called it — so `/consents` opened, called
  // `permission/list`, got -32601, and rendered an empty list under a
  // heading promising "Review and revoke stored tool grants".
  //
  // A gate that only asks "is the name in the class" is green for
  // exactly that state. This one asks the question the user
  // experiences: is it in the daemon the TUI spawns?
  assert.deepEqual(declaredButUnwired, [],
    "these methods are DECLARED but DaemonRunner never registers them — every " +
    "call from the TUI is -32601. Add the `registerAll(...)` call (R367) or " +
    "take them out of KNOWN_METHODS and back onto BACKEND_MISSING.");
});

test("REGRESSION R367: the permission/grant surfaces the TUI uses are live", () => {
  // Named explicitly so that removing one of these from the panel is a
  // deliberate act with a red test attached, rather than a silent
  // break discovered by a user clicking a dead button.
  for (const name of ["permission/list", "permission/revoke", "grants/revoke"]) {
    assert.ok(KNOWN_METHODS.has(name),
      `${name} must be reachable from the TUI's daemon — it is no longer on the backend-missing list`);
  }
});

test("javaCode handles the apostrophe trap that DaemonRunner actually contains", () => {
  // The gate reads DaemonRunner THROUGH javaCode, so a scanner bug is
  // a gate bug. This is the R364 Lesson 1112 disaster rebuilt in a
  // different language: the old TS stripper ate 916 lines of line.ts
  // because a string literal contained `/*`. Here the equivalent trap
  // is real code in the real file — see the comment on javaCode.
  //
  // The synthetic sample carries every trap the real file has, plus
  // the two the real file does not yet have, so the guard does not
  // depend on a future edit to DaemonRunner keeping a comment around.
  const SAMPLE = [
    '        // the stdio daemon\'s dispatcher picks it up',
    '        // "quoted" and \'apostrophe\' inside a line comment',
    '        String url = "http://example.com/* not a comment */";',
    '        char q = \'"\';',
    '        /* block with " and \' inside */',
    '        http.setPermissionMethods(realCode);',
  ].join("\n");
  const out = javaCode(SAMPLE);

  assert.equal(out.length, SAMPLE.length, "offsets must be preserved");
  assert.ok(out.includes("http.setPermissionMethods(realCode);"),
    "real code after the comments was swallowed — the scanner entered literal " +
    "state on an apostrophe inside a comment and never left it");
  assert.ok(out.includes('"http://example.com/* not a comment */";'),
    "a `//` or `/*` inside a string literal was treated as a comment opener");
  assert.ok(!out.includes("daemon's"), "the line comment was not blanked");
  assert.ok(!out.includes("block with"), "the block comment was not blanked");
  assert.ok(out.includes('char q = \'"\';'), "a char literal was mangled");
});

test("the extra-method-class list is itself complete", () => {
  // An allowlist is "don't know" written down as "known". If someone
  // deletes an entry from EXTRA_METHOD_CLASSES, the wiring check
  // silently stops covering that class — and the gate stays green,
  // because `declaredButUnwired` is built BY iterating the list. Both
  // mutation cases for this round proved exactly that.
  const declared = EXTRA_METHOD_CLASSES.map((c) => c.type);
  assert.deepEqual(declared.slice().sort(), ["GrantMethods", "PermissionMethods"],
    "every method class with its own registerAll must be listed here — a new " +
    "one that is not listed is a class the wiring check does not cover");
  for (const cls of EXTRA_METHOD_CLASSES) {
    assert.ok(cls.wiredVar, `${cls.type} has no wiredVar — re-anchor this test`);
    assert.ok(cls.src.includes("public void registerAll("),
      `${cls.type} no longer has a registerAll(JsonRpcDispatcher) hook — re-anchor this test`);
  }
});

test("REGRESSION R367: BOTH transports route permission/* and grants/*", () => {
  // Two transports, two registries, one bug class. The stdio daemon
  // uses `JsonRpcDispatcher` + registerAll; the HTTP+WS daemon uses a
  // hand-rolled `case "..." ->` switch in HttpJsonRpcServer. Adding a
  // method to one does not add it to the other — that is the R720
  // memory/* gap (Lesson 1022: "two-layer fix (cli + protocol)") and
  // it is invisible to the TUI, which only ever exercises stdio.
  //
  // The desktop is the one that pays here, and it is not covered by
  // any test in this repo. This is the cheapest honest guard we have.
  const NAMES = [
    "permission/check", "permission/prompt", "permission/list",
    "permission/revoke", "permission/clear",
    "grants/list", "grants/revoke", "grants/clear", "grants/setPreset",
  ];
  const missingHttp = NAMES.filter((n) => !httpCases.has(n));
  assert.deepEqual(missingHttp, [],
    "these are registered on the stdio dispatcher but NOT routed by " +
    "HttpJsonRpcServer's switch — the desktop gets -32601 for them: " +
    missingHttp.join(", "));

  // And the setters DaemonRunner calls must actually exist. A case arm
  // that routes to a field nobody assigns is the same dead code as no
  // case arm at all.
  for (const setter of ["setPermissionMethods", "setGrantMethods"]) {
    assert.ok(httpSrc.includes(setter),
      `HttpJsonRpcServer has no ${setter} — the R367 route helpers have nothing to read`);
  }
  const runner = javaCode(daemonRunnerSrc);
  for (const setter of ["http.setPermissionMethods", "http.setGrantMethods"]) {
    assert.ok(runner.includes(setter),
      `DaemonRunner never calls ${setter} — the HTTP switch would answer ` +
      "\"not configured\" for every permission/* and grants/* call");
  }
});

test("every method the TUI sends is registered by the daemon it talks to", () => {
  const sent = sentMethods();
  const unknown = [];
  for (const [name, files] of sent) {
    if (KNOWN_METHODS.has(name)) continue;
    if (BACKEND_MISSING.has(name)) continue;
    unknown.push(`${name}  <- ${[...files].join(", ")}`);
  }
  assert.deepEqual(unknown, [],
    "the TUI sends method names this daemon does not have (-32601).\n" +
    "  If one genuinely does not exist, add it to BACKEND_MISSING with a reason;\n" +
    "  otherwise fix the call site. Check the registration point, not the call\n" +
    "  site: AetherCodeMethods.registerAll + HttpJsonRpcServer's switch.");
});

test("the allowlist has not gone stale", () => {
  // If one of these ever gets implemented, this test names it rather
  // than leaving it to be discovered as a broken button.
  const nowReal = [];
  for (const [name, why] of BACKEND_MISSING) {
    if (KNOWN_METHODS.has(name)) nowReal.push(`${name} (${why})`);
  }
  assert.deepEqual(nowReal, [],
    "these are on the backend-missing list but ARE now registered — remove them " +
    "from BACKEND_MISSING and fix the call sites");
});

test("every allowlist entry carries a reason", () => {
  for (const [name, why] of BACKEND_MISSING) {
    assert.ok(why && why.length > 15, `${name} needs a real reason, got: ${why}`);
  }
});

// --- the specific regressions this round fixed -----------------------------

/**
 * Every RPC a panel calls, paired with the array key its RESULT
 * carries. `asRows` in line.ts must know each key or the panel
 * renders empty-with-no-error. Four are answered by
 * AetherCodeMethods; `grants/list` is answered by GrantHandlers in a
 * different module.
 */
const ENVELOPES = [
  ["listSessions", "sessions"],
  ["listAgents", "agents"],
  ["listProjects", "projects"],
  ["listWorkflows", "workflows"],
  ["grants/list", "grants"],
];

test("REGRESSION: /model uses setModel with the `model` param key", () => {
  // Not just the method name. `setModel` reads
  // `stringOrThrow(p, "model")`; the dead `model/*` DTOs used `name`.
  // A name-only fix would still have thrown -32602.
  const commands = codeOf(readFileSync(join(root, "src", "commands.ts"), "utf-8"), "commands.ts");
  assert.doesNotMatch(commands, /rpcMethod:\s*"model\/set"/,
    "model/set has DTOs but no handler — every /model <name> died on -32601");
  assert.match(commands, /rpcMethod:\s*"setModel",\s*rpcParams:\s*\{\s*model:/,
    "setModel reads `model`, not `name`");
});

test("REGRESSION: workflow calls use the flat daemon names", () => {
  const commands = codeOf(readFileSync(join(root, "src", "commands.ts"), "utf-8"), "commands.ts");
  const line = codeOf(readFileSync(join(root, "src", "line.ts"), "utf-8"), "line.ts");
  assert.doesNotMatch(commands, /rpcMethod:\s*"workflow\/run"/,
    "workflow/run lives on SupervisorRpcServer, a CLI the TUI never starts");
  assert.match(commands, /rpcMethod:\s*"runWorkflow"/);
  assert.doesNotMatch(line, /probe\(ctx,\s*"workflow\/list"/);
  assert.match(line, /probe\(ctx,\s*"listWorkflows"/);
});

test("REGRESSION: asRows can unwrap every envelope the daemon returns", () => {
  // `asRows` returning [] on a SUCCESSFUL call produces an empty panel
  // and no error at all — the worst shape this codebase has. Three
  // panels were silently empty: `projects` and `workflows` (R366), then
  // `grants` (R367).
  const line = codeOf(readFileSync(join(root, "src", "line.ts"), "utf-8"), "line.ts");
  const start = line.indexOf("function asRows");
  assert.ok(start > -1, "asRows not found (re-anchor this test)");
  const body = line.slice(start, line.indexOf("\n}", start));

  for (const [rpc, key] of ENVELOPES) {
    assert.ok(body.includes(`"${key}"`),
      `asRows does not know the envelope key "${key}" (${rpc} returns it), ` +
      "so every panel reading that RPC renders an empty list with no error");
  }
});

test("every envelope key above is one the daemon ACTUALLY returns", () => {
  // Ties the asRows list to the daemon rather than to a guess. Four of
  // these live in AetherCodeMethods; `grants/list` does not — it is
  // answered by GrantMethods -> GrantHandlers, in a DIFFERENT module,
  // and it wraps the rows in `{grants: [...]}`. Reading the wrong file
  // is how "grants" would have stayed off the list while the endpoint
  // was being made reachable.
  for (const [javaMethod, key] of ENVELOPES) {
    if (javaMethod === "grants/list") {
      const start = grantHandlersSrc.indexOf("public Map<String, Object> list(");
      assert.ok(start > -1, "GrantHandlers.list not found — re-anchor this test");
      const next = grantHandlersSrc.indexOf("public Map<String, Object> revoke(", start);
      const body = grantHandlersSrc.slice(start, next < 0 ? undefined : next);
      assert.ok(body.includes(`"${key}"`),
        `GrantHandlers.list no longer returns a "${key}" array — update asRows and this test`);
      assert.ok(!/return\s+list\s*;/.test(body),
        "GrantHandlers.list used to answer a bare List; if it does again, " +
        "asRows still works via Array.isArray but this envelope pin is now wrong");
      continue;
    }
    const start = methodsSrc.indexOf(`public Object ${javaMethod}(`);
    assert.ok(start > -1, `${javaMethod} not found in AetherCodeMethods — re-anchor this test`);
    const next = methodsSrc.indexOf("\n    public Object ", start + 10);
    const body = methodsSrc.slice(start, next < 0 ? undefined : next);
    assert.ok(new RegExp(`put\\(\\s*"${key}"|"${key}"`).test(body),
      `${javaMethod} no longer returns a "${key}" array — update asRows and this test`);
  }
});