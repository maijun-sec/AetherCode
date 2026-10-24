// R374 — the `/update` panel: honest, and split into the three verbs.
//
// ## What this round actually found
//
// The registry row `update-available` said `blocker: 'not-wired'` for
// three rounds. That blocker was WRONG. The R365 vocabulary is load-
// bearing:
//
//   `not-wired`       - the backend works, nobody wrote the UI.
//                      A task, with a known destination.
//   `backend-missing` - no such RPC exists on the daemon. Do NOT
//                      build the UI; it would be a dead button.
//
// The daemon has NO update RPC: no `update/check`, no
// `update/refreshDeps`, no `update/install`, and no updater module in
// the reactor. So the honest blocker was `backend-missing`, and the
// rule that follows from it is "do not write the UI".
//
// Which is why R374 did NOT mount `UpdateAvailable` /
// `UpdateConfirm` / `UpdateProgress`. Each of those needs a fact this
// daemon cannot produce:
//
//   - `UpdateAvailable` needs a `PendingUpdate` — "an update is
//     available" plus the actions to take on it;
//   - `UpdateConfirm` needs `current` and `latest` to confirm a move
//     between;
//   - `UpdateProgress` needs a download in flight.
//
// Mounting any of them would put a button on screen that cannot do
// anything.
//
// ## What the panel does instead
//
// The facts that DO exist, and the path that DOES work — which is
// what mature updaters do when the mechanism is not available:
//
//   - Electron's `autoUpdater` documents the flow as
//     `checkForUpdates()` -> `update-available` -> `downloadUpdate()`
//     -> `update-downloaded` -> `quitAndInstall()`, and says the
//     downloaded update "will still be applied the next time the
//     application starts". The CHECK is read-only; the INSTALL is the
//     mutating step.
//   - electron-updater is configured in the wild with `autoDownload =
//     false` and `autoInstallOnAppQuit = false`, so nothing happens
//     until the user confirms.
//   - Electron's own docs note there is no built-in auto-updater on
//     Linux and recommend the distribution's package manager — so
//     "update it yourself, like this" is a first-class answer.
//   - Homebrew keeps three verbs apart (`brew update` refreshes the
//     index, `brew upgrade` installs, `brew outdated` reports), and
//     merging them is a UX bug.
//
// So: three verbs, three separate answers, and no button that
// pretends.
//
// ## What this gate pins
//
//   * the three verbs stay three (never one merged sentence);
//   * the gap statement is DERIVED from a probe, never hard-coded;
//   * a version nobody reported is not turned into a version;
//   * the panel has a changelog action and NO install action;
//   * the changelog URL is this project's, not an upstream default;
//   * the TUI version constant agrees with package.json.

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
const repo = join(root, "..");
const tmp = join(root, "tmp-r374-update-panel");

const tuiSrc = codeOf(readFileSync(join(root, "src", "tui.tsx"), "utf-8"), "tui.tsx");
const lineSrc = codeOf(readFileSync(join(root, "src", "line.ts"), "utf-8"), "line.ts");
const stateSrc = codeOf(readFileSync(join(root, "src", "state.ts"), "utf-8"), "state.ts");
const intentsSrc = codeOf(
  readFileSync(join(root, "src", "panelIntents.ts"), "utf-8"),
  "panelIntents.ts",
);
const infoSrc = codeOf(readFileSync(join(root, "src", "updateInfo.ts"), "utf-8"), "updateInfo.ts");
const pickerSrc = codeOf(
  readFileSync(join(root, "src", "components", "UpdatePanel.tsx"), "utf-8"),
  "UpdatePanel.tsx",
);
const availableSrc = codeOf(
  readFileSync(join(root, "src", "components", "UpdateAvailable.tsx"), "utf-8"),
  "UpdateAvailable.tsx",
);
const versionSrc = codeOf(readFileSync(join(root, "src", "version.ts"), "utf-8"), "version.ts");

const methodsPath = join(repo, "aethercode", "aethercode-protocol", "src", "main", "java",
  "org", "aethercode", "protocol", "methods", "AetherCodeMethods.java");
const methodsCode = javaCode(readFileSync(methodsPath, "utf-8"));

/** Bounded extraction by BRACE MATCHING — never a character window. */
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

/** A `case` arm owns NO braces of its own, so brace matching walks
 *  straight into the NEXT arm — which is how the first version of
 *  this gate read a `sdd.start` case while asking about `/update`.
 *  Slice to the next `case` at the same indent instead. */
function caseArm(src, header) {
  const at = src.search(header);
  assert.notEqual(at, -1, `caseArm(): header not found: ${header}`);
  const rest = src.slice(at);
  const next = rest.slice(1).search(/\n[ \t]*case\s/);
  return next === -1 ? rest : rest.slice(0, next + 1);
}

/** Blank out the CONTENTS of string literals (keeping the length).
 *  Needed because this row's `gapNote` is PROSE that mentions
 *  `blocker: 'not-wired'` — the gate asks whether the row has a
 *  blocker FIELD, and a prose mention is not a field. */
function blankStrings(src) {
  let out = "";
  let i = 0;
  const n = src.length;
  while (i < n) {
    const c = src[i];
    if (c === '"' || c === "'" || c === "`") {
      let j = i + 1;
      while (j < n) {
        if (src[j] === "\\") { j += 2; continue; }
        if (src[j] === c) { j++; break; }
        if (src[j] === "\n" && c !== "`") break;
        j++;
      }
      out += c + src.slice(i + 1, j).replace(/[^\n]/g, " ");
      i = j;
      continue;
    }
    out += c;
    i++;
  }
  return out;
}

/** The object that CONTAINS the match — see the R373 note on why
 *  "the first `{` after the header" reads the NEXT row when the
 *  header is a field inside an object literal. */
function enclosingObject(src, header) {
  const at = src.search(header);
  assert.notEqual(at, -1, `enclosingObject(): header not found: ${header}`);
  const open = src.lastIndexOf("{", at);
  assert.notEqual(open, -1, `enclosingObject(): no opening brace before ${header}`);
  let depth = 0;
  for (let i = open; i < src.length; i++) {
    if (src[i] === "{") depth++;
    else if (src[i] === "}" && --depth === 0) return src.slice(open, i + 1);
  }
  throw new Error(`enclosingObject(): unbalanced braces after ${header}`);
}

// --- compile the pure module so it can be RUN ------------------------------

if (existsSync(tmp)) {
  if (process.platform === "win32") spawnSync("cmd", ["/c", "rmdir", "/s", "/q", tmp]);
  else spawnSync("rm", ["-rf", tmp]);
}
mkdirSync(tmp, { recursive: true });

const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
const compile = spawnSync(`"${tscBin}"`, [
  "--outDir", join(tmp, "out"), "--target", "ES2022", "--module", "ES2022",
  "--moduleResolution", "bundler", "--jsx", "react",
  "--esModuleInterop", "true", "--skipLibCheck", "true",
  "--rootDir", join(root, "src"),
  `"${join(root, "src", "updateInfo.ts")}"`,
  `"${join(root, "src", "version.ts")}"`,
  `"${join(root, "src", "state.ts")}"`,
  `"${join(root, "src", "commands.ts")}"`,
], { encoding: "utf-8", shell: true });
if (compile.status !== 0) {
  console.error("tsc failed:\n" + compile.stdout + "\n" + compile.stderr);
  process.exit(1);
}

const infoMod = await import(pathToFileURL(join(tmp, "out", "updateInfo.js")).href);
const {
  buildUpdatePanel,
  updateVerbForSentinel,
  updateVerbTitle,
  updateHeadline,
  updateManualPath,
  daemonVersionOf,
  scalarRows,
  CHANGELOG_URL,
  UPDATE_ENDPOINTS,
} = infoMod;

const verMod = await import(pathToFileURL(join(tmp, "out", "version.js")).href);
const { TUI_VERSION } = verMod;

const stateMod = await import(pathToFileURL(join(tmp, "out", "state.js")).href);
const { reducer, INITIAL } = stateMod;

const cmdMod = await import(pathToFileURL(join(tmp, "out", "commands.js")).href);
const { SLASH_COMMANDS_DETAILED, SLASH_HELP } = cmdMod;

/** The facts a daemon with no update surface produces. */
const MISSING_FACTS = {
  tuiVersion: "0.2.1",
  daemonVersion: null,
  endpointMissing: true,
  reason: "rpc error -32601: method not found",
  reported: [],
};

/** The facts a daemon that DOES answer produce. */
const PRESENT_FACTS = {
  tuiVersion: "0.2.1",
  daemonVersion: "0.3.0",
  endpointMissing: false,
  reason: null,
  reported: [["latest", "0.3.1"]],
};

// --- the three verbs --------------------------------------------------------

test("the three sentinels map to three different verbs", () => {
  assert.equal(updateVerbForSentinel("__UPDATE_AVAILABLE__"), "check");
  assert.equal(updateVerbForSentinel("__UPDATE_INSTALL__"), "install");
  assert.equal(updateVerbForSentinel("__UPDATE_DEPS__"), "deps");
});

test("an unknown sentinel falls back to the SAFE verb", () => {
  // The fallback must be `check` — the read-only one. An unknown
  // sentinel must never turn into an install.
  assert.equal(updateVerbForSentinel("__SOMETHING_ELSE__"), "check");
  assert.equal(updateVerbForSentinel(""), "check");
});

test("the verb a sentinel maps to changes the ANSWER the user gets", () => {
  // The mapping is not a label — it SELECTS the sentence. If
  // `/update install` mapped back to "check", the user who asked to
  // install would be told there is no update-check operation, which
  // is exactly the "three verbs collapse into one" bug this round
  // found in the line renderer.
  const heads = ["__UPDATE_AVAILABLE__", "__UPDATE_INSTALL__", "__UPDATE_DEPS__"]
    .map((s) => updateHeadline(updateVerbForSentinel(s), true));
  assert.equal(new Set(heads).size, 3,
    "the three sentinels do not reach three different answers:\n" + heads.join("\n"));
});

test("each verb probes its OWN endpoint", () => {
  assert.equal(UPDATE_ENDPOINTS.check, "update/check");
  assert.equal(UPDATE_ENDPOINTS.install, "update/install");
  assert.equal(UPDATE_ENDPOINTS.deps, "update/refreshDeps");
  // Three verbs, three endpoints — not one endpoint reused.
  assert.equal(new Set(Object.values(UPDATE_ENDPOINTS)).size, 3);
});

test("the three verbs have three DIFFERENT titles", () => {
  // A panel titled "Check for an update" while the user asked to
  // install is a real UX bug — the title is what the user reads
  // first.
  const titles = ["check", "install", "deps"].map((v) => updateVerbTitle(v));
  assert.equal(new Set(titles).size, 3,
    "the three verbs share a title:\n" + titles.join("\n"));
});

test("the three verbs answer with three DIFFERENT sentences", () => {
  // "Merging them into one modal is a UX bug" — the AGENTS.md note on
  // /update vs /update-deps. This is the rule behind that note.
  const heads = ["check", "install", "deps"].map((v) =>
    updateHeadline(v, true),
  );
  assert.equal(new Set(heads).size, 3,
    "the three verbs share a sentence:\n" + heads.join("\n"));
});

test("the three verbs offer three DIFFERENT manual paths", () => {
  const paths = ["check", "install", "deps"].map((v) =>
    updateManualPath(v).join("\n"),
  );
  assert.equal(new Set(paths).size, 3,
    "the three verbs share a manual path:\n" + paths.join("\n"));
});

// --- honest gaps ------------------------------------------------------------

test("a missing endpoint says what did NOT happen", () => {
  for (const verb of ["check", "install", "deps"]) {
    const head = updateHeadline(verb, true);
    assert.ok(head.length > 40,
      `${verb}: the gap sentence is too short to stand alone: ${head}`);
    assert.ok(/nothing|not|cannot|whether/i.test(head),
      `${verb}: the gap sentence does not say what did not happen: ${head}`);
  }
});

test("the install verb says nothing was written", () => {
  const head = updateHeadline("install", true);
  assert.ok(/nothing was downloaded|nothing was replaced|nothing was written/.test(head),
    "the install gap does not say that nothing was written:\n" + head);
});

test("a daemon that ANSWERS gets a different sentence", () => {
  const missing = updateHeadline("check", true);
  const present = updateHeadline("check", false);
  assert.notEqual(missing, present,
    "the panel says the same thing whether the daemon answered or not");
  assert.ok(!/no update-check operation/.test(present),
    "the panel claims a gap on a daemon that answered");
});

test("the check verb does not blame the user for the daemon's gap", () => {
  const head = updateHeadline("check", true);
  assert.ok(!/you have no|you need to|you should/.test(head),
    "the gap sentence blames the user:\n" + head);
});

// --- versions ---------------------------------------------------------------

test("a version nobody reported is not turned into a version", () => {
  // The daemon's `ping` answers `version` from the jar manifest's
  // `Implementation-Version`, which a plain `mvn package` does not
  // write — so `null` is the NORMAL case, and it must not become
  // "0.0.0" or a made-up number.
  assert.equal(daemonVersionOf({ version: null }), null);
  assert.equal(daemonVersionOf({}), null);
  assert.equal(daemonVersionOf({ version: "" }), null);
  assert.equal(daemonVersionOf(null), null);
  assert.equal(daemonVersionOf({ version: "0.3.0" }), "0.3.0");
});

test("the panel renders an unreported version as unreported", () => {
  const model = buildUpdatePanel("check", MISSING_FACTS);
  const daemonRow = model.rows.find(([k]) => k === "daemon version");
  assert.ok(daemonRow, "the panel does not show the daemon version");
  assert.ok(/not reported/.test(daemonRow[1]),
    "an unreported version is rendered as a version: " + daemonRow[1]);
  assert.ok(!/0\.0\.0/.test(daemonRow[1]),
    "an unreported version became 0.0.0");
});

test("the panel shows the TUI and daemon versions side by side", () => {
  // The "current vs available" shape every mature updater uses.
  const model = buildUpdatePanel("check", PRESENT_FACTS);
  const tuiRow = model.rows.find(([k]) => k === "tui version");
  const daemonRow = model.rows.find(([k]) => k === "daemon version");
  assert.ok(tuiRow && daemonRow, "the panel does not show both versions");
  assert.equal(tuiRow[1], "0.2.1");
  assert.equal(daemonRow[1], "0.3.0");
});

test("the daemon's own report is shown when it answered", () => {
  const model = buildUpdatePanel("check", PRESENT_FACTS);
  assert.ok(model.rows.some(([k, v]) => k === "latest" && v === "0.3.1"),
    "the daemon's report is dropped:\n" + JSON.stringify(model.rows));
});

test("the daemon's own words are shown when it refused", () => {
  const model = buildUpdatePanel("check", MISSING_FACTS);
  assert.ok(model.rows.some(([k]) => k === "daemon said"),
    "the daemon's reason is dropped");
});

test("nested values do not break the flat report", () => {
  const rows = scalarRows({ a: 1, b: "x", c: null, d: { e: 1 }, f: [1] });
  assert.deepEqual(rows, [["a", "1"], ["b", "x"], ["c", "null"]]);
});

// --- the changelog ----------------------------------------------------------

test("the changelog URL is THIS project's", () => {
  assert.ok(/github\.com\/maijun-sec\/AetherCode/.test(CHANGELOG_URL),
    "the changelog URL does not point at AetherCode: " + CHANGELOG_URL);
  assert.ok(!/deepagents|langchain/i.test(CHANGELOG_URL),
    "the changelog URL points at an upstream project: " + CHANGELOG_URL);
});

test("the unmounted UpdateAvailable component no longer points at another project", () => {
  // It is unmounted, but its default would be the first thing a
  // future mount would use — and it was an upstream default that was
  // never repointed.
  assert.ok(!/deepagents|langchain/i.test(availableSrc),
    "UpdateAvailable still carries the upstream changelog URL");
});

test("the panel has a changelog action and NO install action", () => {
  // A panel that cannot act must not offer an action that pretends
  // to. There is nothing to install, so there is no install row.
  assert.ok(/onChangelog/.test(pickerSrc), "the panel has no changelog action");
  assert.ok(!/onInstall/.test(pickerSrc),
    "the panel grew an install action — there is nothing to install");
});

// --- the registry -----------------------------------------------------------

test("the update row is mounted on both renderers with no renderer blocker", () => {
  const row = enclosingObject(intentsSrc, /id:\s*'update-available'/);
  assert.ok(/ink:\s*'mounted'/.test(row),
    "the update row does not claim Ink is mounted:\n" + row);
  assert.ok(/line:\s*'rendered'/.test(row),
    "the update row does not claim line mode is rendered:\n" + row);
  // The check is on the row's own FIELDS, so the prose inside
  // `gapNote` (which quotes `blocker: 'not-wired'` to explain why the
  // old blocker was wrong) cannot be mistaken for a field.
  assert.ok(!/\bblocker\s*:/.test(blankStrings(row)),
    "a fully-mounted row must not carry a blocker:\n" + row);
  assert.ok(/gapNote:/.test(row),
    "the standing gap must stay in the audit trail:\n" + row);
});

test("the update row still cites the round that found the gap", () => {
  // The gap is real — typing `/update` does not update anything — and
  // the audit trail belongs in the row, not only in a doc.
  //
  // The note must OPEN with the round that established it. A looser
  // "contains some R-number" check is satisfied by the note's own
  // aside about R365 (the round whose blocker was wrong), so it
  // cannot tell "the note names its round" from "the note mentions a
  // round somewhere".
  const row = enclosingObject(intentsSrc, /id:\s*'update-available'/);
  const note = /gapNote:\s*'([^']*)'/.exec(row);
  assert.ok(note, "the row has no gapNote:\n" + row);
  assert.ok(/^R\d{3}:/.test(note[1]),
    "the gapNote does not open with the round that established it:\n" + note[1]);
});

// --- reachability -----------------------------------------------------------

test("both renderers claim the update sentinels", () => {
  for (const s of ["__UPDATE_AVAILABLE__", "__UPDATE_INSTALL__", "__UPDATE_DEPS__"]) {
    assert.ok(new RegExp(`case\\s+"${s}":`).test(lineSrc),
      `line mode has no arm for ${s}`);
  }
  assert.ok(/"update-available":\s*"update"/.test(tuiSrc),
    "PANEL_BY_INTENT does not map update-available to a panel");
  assert.ok(/case\s+"update":/.test(tuiSrc),
    "tui.tsx has no case for the update panel");
});

test("the Ink panel probes the verb's endpoint, not a hard-coded one", () => {
  // The gap statement must be DERIVED from a probe. A panel that
  // hard-codes "this daemon has no update RPC" would keep saying it
  // after the backend lands.
  const fetch = block(tuiSrc, /if \(panel === "update"\)/);
  assert.ok(/UPDATE_ENDPOINTS\[verb\]/.test(fetch),
    "the panel does not probe the verb's endpoint:\n" + fetch);
  assert.ok(/isMissingMethod/.test(fetch),
    "the panel does not detect a missing method:\n" + fetch);
  assert.ok(/request\("ping"/.test(fetch),
    "the panel never asks the daemon for its version:\n" + fetch);
});

test("the missing-method test checks the CODE, not the message", () => {
  // -32601 is JSON-RPC's METHOD_NOT_FOUND. A message-substring test
  // would also match a timeout whose text happened to contain
  // "method not found".
  assert.ok(/RpcCallError/.test(tuiSrc) && /-32601/.test(tuiSrc),
    "the missing-method check does not use the JSON-RPC error code");
});

test("the line frame uses the same shared copy", () => {
  // Pin the CALL, not the identifier: an `import { buildUpdatePanel }`
  // line satisfies a bare name match, so a renderer that stopped
  // calling the shared builder would still pass (R373 Lesson 1170).
  assert.ok(/buildUpdatePanel\(/.test(lineSrc),
    "line mode does not CALL the shared update builder");
  assert.ok(/buildUpdatePanel\(/.test(tuiSrc),
    "the Ink panel does not CALL the shared update builder");
});

test("line mode gives the three sentinels three different verbs", () => {
  // The bug this round found: `__UPDATE_INSTALL__` shared the check
  // verb, so "install it" and "is there one" said the same thing.
  const avail = caseArm(lineSrc, /case "__UPDATE_AVAILABLE__":/);
  const inst = caseArm(lineSrc, /case "__UPDATE_INSTALL__":/);
  const deps = caseArm(lineSrc, /case "__UPDATE_DEPS__":/);
  assert.ok(/panelUpdate\(ctx, "check"\)/.test(avail),
    "the check sentinel does not ask for the check verb:\n" + avail);
  assert.ok(/panelUpdate\(ctx, "install"\)/.test(inst),
    "the install sentinel does not ask for the install verb:\n" + inst);
  assert.ok(/panelUpdate\(ctx, "deps"\)/.test(deps),
    "the deps sentinel does not ask for the deps verb:\n" + deps);
});

// --- the version constant ---------------------------------------------------

test("the TUI version constant agrees with package.json", () => {
  const pkg = JSON.parse(readFileSync(join(root, "package.json"), "utf-8"));
  assert.equal(TUI_VERSION, pkg.version,
    "src/version.ts and package.json disagree — one of them was not bumped");
  assert.ok(/export const TUI_VERSION = "/.test(versionSrc),
    "the version constant is not exported from version.ts");
});

test("the CLI banner and the panel read the SAME constant", () => {
  const acSrc = codeOf(readFileSync(join(root, "src", "ac-tui.ts"), "utf-8"), "ac-tui.ts");
  assert.ok(/from "\.\/version\.js"/.test(acSrc),
    "ac-tui.ts does not import the shared version");
  assert.ok(!/const VERSION = "/.test(acSrc),
    "ac-tui.ts still holds its own version string");
});

// --- the third verb must be discoverable ----------------------------------

test("the third verb is advertised, not just reachable", () => {
  // `/update install` has existed since T-430, but the catalog
  // advertised one shape for a command that has two — so the install
  // verb was reachable and INVISIBLE. The catalog's `args` field is
  // what renders the hint in `/help`.
  const update = SLASH_COMMANDS_DETAILED.find((c) => c.name === "update");
  assert.ok(update, "the catalog has no `update` entry");
  assert.ok(/install/.test(update.args || ""),
    "the catalog does not advertise the install verb:\n" + JSON.stringify(update));
  assert.ok(SLASH_HELP.includes("/update [install]"),
    "/help does not show the optional install verb");
});

test("the catalog does not promise an update the daemon cannot have", () => {
  // T-430's descriptions were "review an available update" and
  // "confirm a dependency refresh". There is no available update to
  // review and nothing to confirm: the daemon has no update RPC at
  // all, so a description that promises one is a lie the user reads
  // BEFORE the panel can correct it.
  for (const name of ["update", "update-deps"]) {
    const entry = SLASH_COMMANDS_DETAILED.find((c) => c.name === name);
    assert.ok(entry, `the catalog has no ${name} entry`);
    assert.ok(!/available update/i.test(entry.description),
      `/${name} promises an available update:\n${entry.description}`);
    assert.ok(!/confirm a dependency refresh/i.test(entry.description),
      `/${name} promises a confirmation the daemon cannot do:\n${entry.description}`);
  }
});

// --- the daemon dependency --------------------------------------------------

test("the daemon's ping really answers a version", () => {
  // The panel depends on this field. If the daemon drops it, the
  // panel silently shows "not reported" forever.
  const ping = block(methodsCode, /public Object ping\(/);
  assert.ok(/r\.put\("version"/.test(ping),
    "ping does not answer a version:\n" + ping);
});

// --- the reducer ------------------------------------------------------------

test("the reducer keeps the verb and the facts", () => {
  const facts = { ...MISSING_FACTS };
  const after = reducer(INITIAL, { type: "setUpdateFacts", facts });
  assert.deepEqual(after.updateFacts, facts);
});

test("opening the panel writes the verb, closing it clears the facts", () => {
  const opened = reducer(INITIAL, { type: "openPanel", id: "update", updateVerb: "deps" });
  assert.equal(opened.panelUpdateVerb, "deps",
    "the verb the panel was opened with was not recorded");
  const closed = reducer(opened, { type: "closePanel" });
  assert.equal(closed.panelUpdateVerb, "check",
    "the verb outlived the panel it described");
});
