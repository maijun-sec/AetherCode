// R370 — `/sessions`: this project's sessions, and a real continuation.
//
// ## The request
//
//   "add a /sessions command that shows the session list for the
//    current cwd, and lets me pick one to keep working"
//
// ## What was actually there
//
// Nothing usable, and two things that LOOKED usable:
//
//   * `/sessions` returned `{ rpcMethod: "listSessions" }`, which the
//     host pretty-printed as the RAW WIRE PAYLOAD — id, lastUsedAt,
//     sizeBytes, trashedAt, messageCount, preview. No filter, no
//     picker, no way to continue anything. `/threads` has been a real
//     picker since T-429; `/sessions` was the wire dump.
//   * `line.ts` sent `loadSession { id: picked }`. The daemon has
//     never accepted that key — `AetherCodeMethods.loadSession`
//     opens with `stringOrThrow(p, "sessionId")` — so every cmd.exe
//     thread switch died on `-32602 invalid params` AFTER printing a
//     table the user was invited to pick from. Only Ink worked,
//     because only Ink sent `{ sessionId }`. Same class as R364's
//     `setCwd` and R366's `workflow/list`: a name copied instead of
//     read off the handler.
//
// This file pins five things, each of which is a way the feature
// silently degrades back into what it was:
//
//   1. `/sessions` opens the picker (not the wire dump), and the
//      picker is the SAME panel as `/threads` — so the fetch, the
//      component and the continuation cannot drift apart.
//   2. The cwd filter is real, is driven by one flag, and is applied
//      at the fetch. `/threads` must keep the unfiltered behaviour.
//   3. The filter uses path equality, not string equality.
//   4. `loadSession` is called with the key the DAEMON reads — and
//      the daemon's own source is read here to derive that key, not
//      a comment copied from it.
//   5. Sessions the daemon cannot attribute to a directory are
//      COUNTED and reported, not silently dropped. An empty panel
//      that says the same sentence as "the daemon has no sessions"
//      is the successful-but-uninformative panel this repo keeps
//      rejecting.

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

// --- sources ------------------------------------------------------------

const commandsSrc = codeOf(readFileSync(join(root, "src", "commands.ts"), "utf-8"), "commands.ts");
const tuiSrc = codeOf(readFileSync(join(root, "src", "tui.tsx"), "utf-8"), "tui.tsx");
const lineSrc = codeOf(readFileSync(join(root, "src", "line.ts"), "utf-8"), "line.ts");
const stateSrc = codeOf(readFileSync(join(root, "src", "state.ts"), "utf-8"), "state.ts");
const intentsSrc = codeOf(readFileSync(join(root, "src", "panelIntents.ts"), "utf-8"), "panelIntents.ts");
const threadSelSrc = codeOf(
  readFileSync(join(root, "src", "components", "ThreadSelector.tsx"), "utf-8"),
  "ThreadSelector.tsx",
);

const methodsJava = join(
  repo, "aethercode", "aethercode-protocol", "src", "main", "java",
  "org", "aethercode", "protocol", "methods", "AetherCodeMethods.java",
);
assert.ok(existsSync(methodsJava), `cannot find the daemon source at ${methodsJava}`);
const methodsCode = javaCode(readFileSync(methodsJava, "utf-8"));

// --- compiled modules ----------------------------------------------------

const tmp = mkdtempSync(join(tmpdir(), "r370-"));
const tsc = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
const compiled = spawnSync(`"${tsc}"`, [
  "--outDir", join(tmp, "out"), "--target", "ES2022", "--module", "ES2022",
  "--moduleResolution", "bundler", "--jsx", "react",
  "--esModuleInterop", "true", "--skipLibCheck", "true",
  "--rootDir", join(root, "src"),
  `"${join(root, "src", "commands.ts")}"`,
  `"${join(root, "src", "state.ts")}"`,
  `"${join(root, "src", "cwdMatch.ts")}"`,
  `"${join(root, "src", "panelIntents.ts")}"`,
], { encoding: "utf-8", shell: true });
if (compiled.status !== 0) {
  console.error("tsc failed:\n" + compiled.stdout + "\n" + compiled.stderr);
  process.exit(1);
}
const imp = (f) => import(pathToFileURL(join(tmp, "out", f)).href);
const { routeSlash } = await imp("commands.js");
const { INITIAL, reducer } = await imp("state.js");
const { sameCwd, cwdBasename, partitionByCwd } = await imp("cwdMatch.js");
const { PANEL_INTENTS, intentForCommand } = await imp("panelIntents.js");

// Assertj's `contains(a, b)` is varargs and lies about intent; this is
// node:assert, but the same trap applies to any "did the edit apply"
// check: a mutation that silently no-ops proves nothing. Every
// mutation case below reports NO-OP separately from PASS.

/**
 * Bounded extraction by BRACE MATCHING, not by a character window.
 *
 * <p>The first version of this file located things with
 * `[\s\S]{0,400}?` style windows, and the widths were guesses. Two
 * of the three assertions that used them were wrong for reasons that
 * had nothing to do with the code under test: one window was too
 * narrow for the real distance and one was too narrow for a comment
 * that `codeOf` blanks but does not shorten. A guessed threshold is
 * a threshold that will be wrong again the next time someone adds a
 * line — and it fails by going GREEN, not by complaining.
 *
 * <p>Lesson 1138, applied to a gate instead of a threshold.
 *
 * @param {string} src already comment-stripped source
 * @param {RegExp} header matches the declaration that opens the block
 * @returns {string} the block's braces and everything between them
 */
function block(src, header) {
  const at = src.search(header);
  assert.notEqual(at, -1, `block(): header not found: ${header}`);
  const open = src.indexOf("{", at);
  assert.notEqual(open, -1, `block(): no brace after ${header}`);
  let depth = 0;
  for (let i = open; i < src.length; i++) {
    if (src[i] === "{") depth++;
    else if (src[i] === "}" && --depth === 0) return src.slice(open, i + 1);
  }
  throw new Error(`block(): unbalanced braces after ${header}`);
}

// =========================================================================
test("/sessions opens the picker, not the raw wire dump", () => {
  const r = routeSlash("/sessions");
  assert.equal(r?.local, "__SESSION_PICKER__",
    "/sessions must return the picker sentinel, not an rpcMethod — the old " +
    "shape pretty-printed listSessions' raw rows and offered no way to pick one");
  assert.equal(r?.rpcMethod, undefined,
    "/sessions must not ALSO send an RPC; two producers for one command means " +
    "one of them renders and the other does not");
});

test("/threads keeps its own sentinel, so /sessions did not steal it", () => {
  const r = routeSlash("/threads");
  assert.equal(r?.local, "__THREAD_SELECTOR__");
});

test("the registry declares session-picker and both commands resolve", () => {
  const picker = intentForCommand("/sessions");
  assert.ok(picker, "no panel intent claims /sessions — /help would not list it");
  assert.equal(picker.id, "session-picker");
  assert.deepEqual(picker.sentinels, ["__SESSION_PICKER__"]);
  assert.equal(picker.ink, "mounted");
  assert.equal(picker.line, "rendered");
  assert.ok(PANEL_INTENTS.some((p) => p.command === "/threads"),
    "/threads lost its registry row");
});

// =========================================================================
test("session-picker resolves to the SAME panel id as thread-selector", () => {
  const m = /const PANEL_BY_INTENT[^=]*=\s*\{([\s\S]*?)\n\};/.exec(tuiSrc);
  assert.ok(m, "PANEL_BY_INTENT literal not found in tui.tsx");
  const body = m[1];
  const panelOf = (intent) => {
    const hit = new RegExp(`"${intent}"\\s*:\\s*"([a-z]+)"`).exec(body);
    return hit ? hit[1] : undefined;
  };
  assert.equal(panelOf("session-picker"), "threads");
  assert.equal(panelOf("thread-selector"), "threads",
    "/threads changed panel — the whole point of the flag was to avoid a second PanelId");
});

test("...and that panel id is one the host actually fetches and renders", () => {
  // Redundant coverage for the assertion above, on purpose.
  //
  // The mutation battery neuters `panelOf` (replacing the anchored
  // lookup with a substring test) and asks whether a wrong PanelId is
  // still caught. With only the assertion above, it is not: a
  // substring lookup answers "threads" for any key the table merely
  // MENTIONS, so `"session-picker": "sessions"` — a PanelId with no
  // fetch, no `case`, and nothing behind it — went green.
  //
  // This assertion derives the same fact a different way: every
  // intent's panel must be a real panel, with a fetch branch and a
  // render `case`. It does not know or care that the answer is
  // "threads", so the two cannot be wrong in the same way.
  const m = /const PANEL_BY_INTENT[^=]*=\s*\{([\s\S]*?)\n\};/.exec(tuiSrc);
  assert.ok(m, "PANEL_BY_INTENT literal not found in tui.tsx");
  const entries = [...m[1].matchAll(/"([^"]+)"\s*:\s*"([a-z]+)"/g)].map((x) => ({
    intent: x[1],
    panel: x[2],
  }));
  assert.ok(entries.length >= 9, `PANEL_BY_INTENT only yielded ${entries.length} entries`);

  for (const { intent, panel } of entries) {
    assert.ok(new RegExp(`case "${panel}":`).test(tuiSrc),
      `intent "${intent}" maps to panel "${panel}", which has no render case`);
  }

  // Only the panels that FETCH need a fetch branch, and the set is
  // derived rather than declared: `todo-board` legitimately has none
  // (TodoBoard is fed by `todo_update` notifications off the event
  // stream, not by an RPC), so "every panel must fetch" is its own
  // false claim — the first version of this assertion failed on
  // exactly that and was wrong about the code, not the code wrong
  // about the test.
  //
  // What must hold is that a panel whose rows come from an RPC HAS
  // that RPC's fetch. `threads` is the one under test.
  assert.ok(/panel === "threads"/.test(tuiSrc) && /"listSessions"/.test(tuiSrc),
    "the threads panel has no listSessions fetch — it would open and show nothing");
});

test("only session-picker is cwd-scoped, and the filter flag rides on openPanel", () => {
  const set = /const CWD_SCOPED_INTENTS\s*=\s*new Set\(\[([^\]]*)\]\)/.exec(tuiSrc);
  assert.ok(set, "CWD_SCOPED_INTENTS not found — the cwd filter has no single owner");
  const members = [...set[1].matchAll(/"([^"]+)"/g)].map((m) => m[1]);
  assert.deepEqual(members, ["session-picker"],
    "the cwd-scoped set drifted: /threads in it would silently filter away the " +
    "cross-project sessions it exists to show, and an unknown member filters nothing");
  assert.ok(/type:\s*"openPanel"[\s\S]{0,200}?cwdOnly:\s*intent\s*\?\s*CWD_SCOPED_INTENTS\.has\(intent\.id\)/.test(tuiSrc),
    "openPanel no longer passes cwdOnly from CWD_SCOPED_INTENTS — the set would be decorative");
});

test("the threads fetch actually branches on the flag", () => {
  const body = block(tuiSrc, /if \(panel === "threads"\)/);
  assert.ok(/panelCwdOnly/.test(body),
    "the fetch never reads panelCwdOnly — the flag is set at openPanel and then ignored, " +
    "so /sessions and /threads render identical lists");
  assert.ok(/partitionByCwd/.test(body),
    "the fetch does not partition by cwd, so there is no filter to apply");
  // The filter must not be "drop the rest silently".
  assert.ok(/other\.length\s*>\s*0/.test(body),
    "unattributed sessions are dropped without being counted; see the module header");
});

test("...and the flag reaches the row list, not just a local variable", () => {
  // The first version of the assertion above only checked that the
  // fetch READS `panelCwdOnly` — which it did, into a local named
  // `scoped`, and then the mutation `threads: all` passed it while
  // `/sessions` and `/threads` rendered identical lists. "The flag is
  // read" and "the flag is used" are different claims.
  const body = block(tuiSrc, /if \(panel === "threads"\)/);
  // TWO setThreads dispatches live in this block: the error path
  // (`threads: []`) and the success path. Taking the first match read
  // the ERROR one, so a mutation of the success line passed — the
  // sixth occurrence of the same shape (R362's regex, R368's
  // `matchAll`, R1114's unreachable branch): an extraction that
  // silently resolves to a different instance of the thing it is
  // looking for. Hence `matchAll` plus an explicit floor, never a
  // bare `.exec`.
  const dispatches = [...body.matchAll(
    /dispatch\(\{\s*type:\s*"setThreads"\s*,\s*threads:\s*([^}]*?)\s*\}\)/g,
  )].map((x) => x[1]);
  assert.ok(dispatches.length >= 2,
    `expected the error path AND the success path to dispatch setThreads, found ` +
    `${dispatches.length} — this extraction is reading the wrong block`);
  const scopedDispatch = dispatches.filter((d) => /\bscoped\b/.test(d));
  assert.ok(scopedDispatch.length >= 1,
    `no setThreads dispatch consults the scope flag (saw: ${JSON.stringify(dispatches)}) — ` +
    `/sessions shows whatever /threads shows`);
  assert.ok(scopedDispatch.some((d) => /\bmine\b/.test(d)),
    `the flag reaches setThreads but not the partitioned rows ` +
    `(saw: ${JSON.stringify(scopedDispatch)}) — the cwd filter is computed and thrown away`);
});

test("panelCwdOnly is reset, so it cannot leak into the next panel", () => {
  assert.ok(/activePanel:\s*null,[\s\S]{0,120}?panelCwdOnly:\s*false/.test(stateSrc),
    "closePanel no longer clears panelCwdOnly — the next panel opened would inherit " +
    "a filter that has nothing to do with it");
  assert.ok(/panelCwdOnly:\s*action\.cwdOnly\s*===\s*true/.test(stateSrc),
    "openPanel no longer writes the flag from the action");
  assert.ok(/panelCwdOnly:\s*false,/.test(stateSrc),
    "INITIAL has no panelCwdOnly — the field is required on State, so this is a type error " +
    "at best and `undefined` (falsy, so accidentally right) at worst");
});

test("closePanel still returns the draft and clears the pickers", () => {
  // Regression guard for the edit that added panelCwdOnly next to it.
  assert.ok(/input:\s*state\.panelInputBefore/.test(stateSrc),
    "closePanel lost the prompt-draft restore");
});

// =========================================================================
test("adoptSession moves the TUI onto the picked session and drops the old scrollback", () => {
  const before = {
    ...INITIAL,
    sessionId: "old-session",
    turns: [{ id: 1, role: "user", text: "hi", ts: 1 }],
    todos: [{ id: "t1", title: "x", status: "pending" }],
    viewingSubagentId: "sub-1",
    viewHistory: [{ kind: "primary" }],
    lastStopReason: "error",
  };
  const after = reducer(before, { type: "adoptSession", sessionId: "new-session" });
  assert.equal(after.sessionId, "new-session");
  assert.equal(after.turns.length, 0,
    "the old session's turns stay on screen while the engine answers in the new one — " +
    "there is no transcript hydration in this renderer, so keeping them is a lie");
  assert.equal(after.todos.length, 0);
  assert.equal(after.viewingSubagentId, null);
  assert.deepEqual(after.viewHistory, []);
  assert.equal(after.lastStopReason, null);
  // ...and nothing else moved.
  assert.equal(after.model, before.model);
  assert.equal(after.permissionMode, before.permissionMode);
  assert.equal(after.input, before.input);
});

test("adoptSession REPLACES the scrollback atomically when a transcript is supplied", () => {
  // R372: the replay arrives with the switch, and the two land in ONE
  // action. Two actions (clear, then fill) would leave a window where
  // the scrollback is empty and the header names the new session — a
  // user who types in that window is talking to a conversation they
  // cannot see.
  const before = {
    ...INITIAL,
    sessionId: "old-session",
    turns: [{ id: 1, role: "user", text: "hi", ts: 1 }],
  };
  const replayed = [{ id: 90, role: "assistant", text: "from the other session", ts: 5, collapsed: false, previewChars: 80 }];
  const after = reducer(before, { type: "adoptSession", sessionId: "new-session", turns: replayed });
  assert.equal(after.sessionId, "new-session");
  assert.deepEqual(after.turns, replayed,
    "the replayed transcript was not adopted — the scrollback still shows the " +
    "session we just left");
  assert.equal(after.turns.some((t) => t.text === "hi"), false,
    "the old session's turns survived the switch");
});

test("adoptSession is a no-op when nothing would change", () => {
  const s = { ...INITIAL, sessionId: "same" };
  assert.equal(reducer(s, { type: "adoptSession", sessionId: "same" }), s,
    "a no-op switch re-rendered the whole transcript tree");
});

test("both renderers dispatch adoptSession after a successful loadSession", () => {
  const inkPick = block(tuiSrc, /case "threads":/);
  assert.ok(/loadSession/.test(inkPick), "the threads panel no longer calls loadSession");
  assert.ok(/type:\s*"adoptSession"/.test(inkPick),
    "Ink never adopts the new session id — the Header pill and /export keep naming the old one");

  const linePick = block(lineSrc, /async function panelThreads\(/);
  assert.ok(/loadSession/.test(linePick), "panelThreads no longer calls loadSession");
  assert.ok(/ctx\.sessionId\s*=\s*back/.test(linePick),
    "line mode never adopts the new session id — the status line keeps naming the old one");
});

test("the FAILED path adopts the session too, not just the successful one", () => {
  // R372 split the switch into two outcomes: the replay worked (the
  // transcript is adopted with the turns) and the replay failed (the
  // session is adopted WITHOUT them). Both have to adopt — the
  // daemon really did switch, and a toast that says "now working in
  // X" while the engine still answers in the old session is a lie.
  //
  // The mutation battery found this by deleting the catch-path
  // dispatch: the only assertion was "adoptSession appears in the
  // block", and the OTHER one satisfied it. "Appears somewhere" and
  // "appears on both paths" are different claims.
  const inkPick = block(tuiSrc, /case "threads":/);
  const catches = [...inkPick.matchAll(/catch\s*(?:\([^)]*\))?\s*(?:=>\s*)?\{/g)];
  assert.ok(catches.length >= 1, "the Ink picker has no catch at all");
  const catchText = inkPick.slice(catches[0].index);
  assert.ok(/type:\s*"adoptSession"/.test(catchText),
    "the failure path never adopts the session — the toast would claim the " +
    "user is in the new session while the engine still answers in the old one");
});

// =========================================================================
test("the loadSession key is the one the DAEMON reads, not one we remembered", () => {
  // Derived from the handler, not from a comment describing it.
  const body = javaMethod(methodsCode, 'public Object loadSession(');
  const key = /stringOrThrow\(\s*p\s*,\s*"([^"]+)"\s*\)/.exec(body);
  assert.ok(key, "loadSession no longer reads a required string param by name — " +
    "this gate cannot derive the contract and needs updating");
  assert.equal(key[1], "sessionId", "the daemon's required key changed; update both renderers");

  // Scope each extraction to the SESSION PICKER. The first version of
  // this loop scanned the whole file and took the first match, which is
  // `viewPrimary`'s `loadSession { sessionId: state.sessionId }` at
  // tui.tsx:2398 — 600 lines before the picker. Reverting the PICKER to
  // `{ id }` therefore left the gate green, which is the precise shape
  // of the bug it exists to catch.
  const inkPick = block(tuiSrc, /case "threads":/);
  const linePick = block(lineSrc, /async function panelThreads\(/);
  for (const [name, src] of [["tui.tsx", inkPick], ["line.ts", linePick]]) {
    const call = /request\(\s*"loadSession"\s*,\s*\{([^}]*)\}/.exec(src)
      ?? /probe\(\s*ctx\s*,\s*"loadSession"\s*,\s*\{([^}]*)\}/.exec(src);
    assert.ok(call, `${name}'s session picker has no loadSession params object to check`);
    assert.ok(/\bsessionId\s*:/.test(call[1]),
      `${name} calls loadSession without a sessionId key — the daemon answers -32602`);
    assert.ok(!/\bid\s*:/.test(call[1]),
      `${name} still sends \`id:\` to loadSession. That is the exact R370 bug: ` +
      `the daemon reads "sessionId" and rejects everything else.`);
  }
});

test("both renderers read lastUsedAt, the key listSessions actually sends", () => {
  const body = javaMethod(methodsCode, 'public Object listSessions(');
  assert.ok(/m\.put\(\s*"lastUsedAt"/.test(body),
    "listSessions stopped sending lastUsedAt — the age column would be empty again");
  assert.ok(/m\.put\(\s*"cwd"/.test(body), "listSessions stopped sending cwd");

  assert.ok(/lastUsedAt/.test(tuiSrc),
    "tui.tsx no longer reads lastUsedAt");
  assert.ok(/r\.updatedAt\s*\?\?\s*r\.lastActiveAt\s*\?\?\s*""/.test(tuiSrc) === false,
    "tui.tsx is back to reading keys listSessions never sends");
  assert.ok(/lastUsedAt/.test(lineSrc),
    "line.ts no longer reads lastUsedAt — its \"last seen\" column is blank on every row");
});

// =========================================================================
test("sameCwd is path equality, and the four ways `===` gets it wrong are covered", () => {
  // These are the shapes that actually occur: the daemon writes
  // Path.toString(), a user types a path by hand, and Windows folds
  // case.
  if (process.platform === "win32") {
    assert.ok(sameCwd("D:\\work\\AetherCode", "d:\\work\\aethercode"), "case");
    assert.ok(sameCwd("D:/work/AetherCode", "D:\\work\\AetherCode"), "separator style");
  }
  assert.ok(sameCwd("/srv/app/", "/srv/app"), "trailing separator");
  assert.ok(sameCwd("/srv/app", "/srv/app/"), "trailing separator, reversed");
  assert.ok(sameCwd("/srv/app", "/srv/other") === false, "different dirs must differ");
  assert.ok(sameCwd("", "/srv/app") === false, "unknown cwd must not match a real one");
  assert.ok(sameCwd("", "") === false,
    "two unknowns must NOT compare equal, or a filter over unattributed rows passes them all");
  assert.ok(sameCwd("   ", "/srv/app") === false, "whitespace is not a path");
});

test("a bare drive root does not collapse into a drive-relative path", () => {
  // `C:\` -> strip trailing sep -> `C:` -> which Windows reads as
  // "current directory on drive C". Restoring the separator is the
  // difference between "the root" and "somewhere on C:".
  if (process.platform === "win32") {
    assert.ok(!sameCwd("C:\\", "C:"), "C: and C:\\ are different directories");
    assert.ok(sameCwd("C:\\", "C:\\"), "C:\\ must still match itself");
    assert.ok(sameCwd("C:\\", "C:/"), "C:/ and C:\\ are the same root");
  }
});

test("cwdBasename survives both separators and does not eat the root", () => {
  assert.equal(cwdBasename("D:\\work\\AetherCode"), "AetherCode");
  assert.equal(cwdBasename("/srv/aethercode/"), "aethercode");
  if (process.platform === "win32") {
    assert.equal(cwdBasename("C:\\"), "C:\\", "the root has no basename to strip");
  }
});

test("partitionByCwd splits, and keeps the unattributed rows visible", () => {
  const rows = [
    { id: "a", cwd: "/p" },
    { id: "b", cwd: "/other" },
    { id: "c", cwd: "" },
    { id: "d", cwd: undefined },
  ];
  const { mine, other } = partitionByCwd(rows, (r) => r.cwd, "/p");
  assert.deepEqual(mine.map((r) => r.id), ["a"]);
  assert.deepEqual(other.map((r) => r.id), ["b", "c", "d"],
    "rows with no recorded cwd must land in `other`, not vanish — the panel counts them");
  assert.equal(mine.length + other.length, rows.length, "a row was dropped");
});

// =========================================================================
test("ThreadSelector renders what it was always handed", () => {
  assert.ok(/emptyLabel\?:/.test(threadSelSrc) && /\{emptyLabel\}/.test(threadSelSrc),
    "the picker's empty state is hard-coded again — /sessions with 0 attributed " +
    "sessions would print /threads' sentence");
  assert.ok(/subtitle\?:/.test(threadSelSrc) && /\{subtitle \?/.test(threadSelSrc),
    "no subtitle — /sessions shows a leaf name with no path, and two checkouts can share one");
  assert.ok(/t\.updatedAt \?/.test(threadSelSrc),
    "updatedAt is carried on every row and rendered nowhere; which session you left is unanswerable");
  assert.ok(!/t\.cwd\s*!==\s*currentCwd/.test(threadSelSrc),
    "the cwd badge is back to string equality — it flags the daemon's own directory as different");
  assert.ok(/sameCwd/.test(threadSelSrc), "ThreadSelector does not use the shared comparator");
});

test("the switch toast says what actually happened", () => {
  // R370 pinned a SENTENCE: "history is not replayed here". That was
  // true then and false from R372 on, when the transcript is actually
  // replayed. A test that names the wording of a message stops
  // measuring the message and starts measuring the wording — and the
  // fix is to go back to the property, not to update the string.
  //
  // The property: the toast must name the session the user is now in
  // (so they can tell the switch happened) and must not claim
  // something the code did not do.
  const ink = block(tuiSrc, /case "threads":/);
  const toasts = [...ink.matchAll(/text:\s*`([^`]*)`/g)].map((m) => m[1]);
  assert.ok(toasts.length >= 1, "the picker shows no toast at all");
  const switchToast = toasts.find((t) => /now working in/.test(t));
  assert.ok(switchToast,
    `no toast names the session the user is now in — the switch is silent ` +
    `(${JSON.stringify(toasts)})`);
  assert.ok(/adopted|replayed|history could not be read/.test(switchToast),
    `the switch toast claims nothing about what happened: ${switchToast}`);
  assert.ok(!/history is not replayed here/.test(switchToast),
    "the switch toast still says the history is not replayed — R372 replays it");
});

// =========================================================================
test("line mode claims the sentinel with a real arm, passing the filter flag", () => {
  const arm = /case\s+"__SESSION_PICKER__"\s*:\s*([\s\S]*?)\n\s*return true;/.exec(lineSrc);
  assert.ok(arm, "line.ts has no __SESSION_PICKER__ arm — cmd.exe users get the raw token, " +
    "which is the R362 defect in its sixth form");
  assert.ok(/panelThreads\(ctx,\s*true\)/.test(arm[1]),
    "the /sessions arm does not scope the list to the current cwd");
  const threadArm = /case\s+"__THREAD_SELECTOR__"\s*:\s*([\s\S]*?)\n\s*return true;/.exec(lineSrc);
  assert.ok(threadArm && /panelThreads\(ctx,\s*false\)/.test(threadArm[1]),
    "/threads is no longer the unfiltered list — the fix swallowed the command it replaced");
});

// =========================================================================
test("the picker filter is not the only cwd comparison left on these paths", () => {
  // `ThreadSelector`'s `chooseCwd` drives the choice; if it used `===`
  // while the host used `sameCwd`, a case-differing path would be
  // labelled "switch" and then correctly ignored — a harmless-looking
  // disagreement, but it is the disagreement that produced R364.
  assert.ok(/sameCwd\(/.test(block(threadSelSrc, /function chooseCwd\(/)),
    "chooseCwd went back to string equality");
});