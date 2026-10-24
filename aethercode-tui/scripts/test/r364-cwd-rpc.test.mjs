// R364 — the cwd switch actually switches.
//
// The 2026-10-21 benchmark and the R362 panel audit both concluded
// "/cwd-pick cannot work; the backend has no setCwd RPC". **That
// conclusion was wrong**, and this round is mostly about recording why.
//
// The truth, verified file:line before any edit:
//
//   `setCwd` is the DESKTOP's Tauri IPC command name —
//   aethercode-desktop/src/store/index.ts declares
//   `setCwd: (path: string) => Promise<void>` and calls
//   `rpc.setCwd(path, sid)`, which is a Tauri command the Rust
//   supervisor handles. It is NOT a JSON-RPC method.
//
//   The TUI speaks JSON-RPC straight to the Java daemon, and it had
//   copied that name across. The daemon therefore answered every
//   `/cwd-pick` with -32601 method-not-found.
//
//   The JSON-RPC method is `switchProject`:
//     AetherCodeMethods.java:1701  dispatcher.register("switchProject", ...)
//     AetherCodeMethods.java:2351  the real implementation
//     HttpJsonRpcServer.java:837   case "switchProject" -> ...
//   and `/cwd <path>` (commands.ts) plus line mode's `panelCwdPick`
//   have been calling it correctly all along.
//
// So this is a CLIENT-side naming bug plus three "the UI lies after a
// successful action" bugs, not a missing backend capability.
//
// The failure mode these tests guard is specific and nasty: the fix
// makes the RPC succeed, and without the other three the panel would
// then cheerfully report success while still showing the directory
// the user just left.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdirSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { codeOf as stripComments } from "./strip-comments.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const tmp = join(root, "tmp-r364-cwd");

const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
if (existsSync(tmp)) {
  if (process.platform === "win32") spawnSync("cmd", ["/c", "rmdir", "/s", "/q", tmp]);
  else spawnSync("rm", ["-rf", tmp]);
}
mkdirSync(tmp, { recursive: true });

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
    `"${join(root, "src", "state.ts")}"`,
  ],
  { encoding: "utf-8", shell: true },
);
if (r.status !== 0) {
  console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
  process.exit(1);
}

const mod = await import(pathToFileURL(join(tmp, "out", "state.js")).href);
const { INITIAL, reducer } = mod;

const src = readFileSync(join(root, "src", "state.ts"), "utf-8");
const tui = readFileSync(join(root, "src", "tui.tsx"), "utf-8");
const line = readFileSync(join(root, "src", "line.ts"), "utf-8");
const intents = readFileSync(join(root, "src", "panelIntents.ts"), "utf-8");
const commands = readFileSync(join(root, "src", "commands.ts"), "utf-8");

/**
 * Comments are stripped before any structural assertion, via the
 * SHARED scanner in strip-comments.mjs.
 *
 * Every R36x block quotes the pre-fix code verbatim (`setCwd`), so a
 * naive `includes("setCwd")` would be satisfied by the very comments
 * documenting the bug — see the negative assertion below, which is the
 * one that would silently stop testing anything.
 *
 * And it must be a SCANNER, not a regex. The first version of this file
 * used the inline regex every other gate used, and it blanked 916 lines
 * of real `line.ts` — the file contains the string "...no update or
 * delete RPC on memory/* ...", whose `/*` reads as a comment opener.
 * The `probe(ctx, "switchProject")` assertion below failed for that
 * reason and no other.
 */

const tuiCode = stripComments(tui, "tui.tsx");
const lineCode = stripComments(line, "line.ts");
const intentsCode = stripComments(intents, "panelIntents.ts");

// --- the naming bug --------------------------------------------------------

test("no TUI code sends the desktop's Tauri IPC name over JSON-RPC", () => {
  // The single most important assertion in this file: `setCwd` is not
  // a JSON-RPC method, and sending it fails every time. Comments are
  // stripped first, so this cannot be satisfied by prose about the bug.
  const calls = tuiCode.match(/request(?:<[^>]*>)?\(\s*"setCwd"/g) || [];
  assert.equal(
    calls.length,
    0,
    `"setCwd" is a Tauri IPC command (aethercode-desktop rpc.setCwd), not a ` +
      `JSON-RPC method — the daemon answers -32601. Use "switchProject".`,
  );
  const lineCalls = lineCode.match(/request(?:<[^>]*>)?\(\s*"setCwd"/g) || [];
  assert.equal(lineCalls.length, 0, "same rule applies to line mode");
});

test("both call sites use switchProject", () => {
  const n = (tuiCode.match(/request(?:<[^>]*>)?\(\s*"switchProject"/g) || []).length;
  assert.ok(
    n >= 2,
    `expected the CwdSwitcher AND the ThreadSelector cross-cwd switch to call ` +
      `switchProject; found ${n}`,
  );
});

test("the /cwd slash command and line mode already used the right name (unchanged)", () => {
  // Guard against a well-meaning "consistency" refactor that renames the
  // working call site to match the broken one.
  assert.match(
    stripComments(commands, "commands.ts"),
    /rpcMethod:\s*"switchProject"/,
    "/cwd <path> is the reference implementation — it was always right",
  );
  assert.match(lineCode, /probe\(ctx,\s*"switchProject"/);
});

// --- the three "UI lies after success" bugs -------------------------------

test("State carries a cwd, and the reducer adopts the daemon's value", () => {
  assert.match(src, /^\s*cwd:\s*string;/m);
  const s = reducer(INITIAL, { type: "setCurrentCwd", cwd: "D:/proj" });
  assert.equal(s.cwd, "D:/proj");
});

test("setCurrentCwd ignores blank and no-op updates (no render churn)", () => {
  // The blank case has to be exercised against an ESTABLISHED cwd.
  // `INITIAL.cwd` is `""`, so `setCurrentCwd("")` is a no-op even
  // without the blank guard — the original version of this test passed
  // against a reducer that had no blank check at all, because
  // `next === state.cwd` was trivially true. Set a real value first.
  const live = reducer(INITIAL, { type: "setCurrentCwd", cwd: "D:/proj" });
  assert.equal(live.cwd, "D:/proj");

  assert.equal(
    reducer(live, { type: "setCurrentCwd", cwd: "   " }),
    live,
    "a blank cwd must not wipe an established one",
  );
  assert.equal(
    reducer(live, { type: "setCurrentCwd", cwd: "D:/proj" }),
    live,
    "the daemon re-notifying the same directory must be a no-op",
  );
});

test("the action is named setCurrentCwd, not setCwd", () => {
  // If the action ever gets called `setCwd` again, the next person
  // will wire it to a Tauri-style call and we are back to -32601.
  assert.match(src, /type:\s*"setCurrentCwd";\s*cwd:\s*string/);
  assert.doesNotMatch(
    src,
    /type:\s*"setCwd"/,
    "an action named setCwd invites the exact confusion R364 is about",
  );
});

test("the Ink TUI handles the cwd_changed notification", () => {
  assert.match(
    tuiCode,
    /case\s*"cwd_changed"/,
    "the daemon emits NOTIFY_CWD_CHANGED on every cwd change; dropping it " +
      "leaves every cwd display stale",
  );
  const idx = tuiCode.indexOf('case "cwd_changed"');
  const body = tuiCode.slice(idx, tuiCode.indexOf("break;", idx));
  assert.match(body, /setCurrentCwd/, "the notification must actually move the displayed cwd");
});

test("line mode handles the cwd_changed notification too (parity)", () => {
  assert.match(lineCode, /method === "cwd_changed"/);
  const idx = lineCode.indexOf('method === "cwd_changed"');
  const body = lineCode.slice(idx, lineCode.indexOf("} else if", idx + 10));
  assert.match(
    body,
    /displayState\.cwd\s*=/,
    "line mode's status-bar cwd comes from displayState.cwd — if the " +
      "notification does not write it, /cwd-pick succeeds and the pill lies",
  );
  assert.match(body, /redraw\(\)/, "the status line is only repainted when something redraws");
});

test("both renderers reconcile against the daemon at startup, not just the launch dir", () => {
  // `getState` does NOT return a cwd (verified: AetherCodeMethods
  // getState puts sessionId/isRunning/liveRunIds/model/... and no
  // cwd). `listProjects` does, with `active: true` on the current
  // binding. Without this the TUI shows where it was LAUNCHED for the
  // whole session, and `sdd.*` sends that wrong path to the daemon.
  //
  // Match the QUOTED method name. A bare `listProjects` substring
  // matches `"listProjectsDISABLED"` — the first version of this
  // assertion passed against exactly that mutation, because renaming
  // the method leaves the old name as a prefix of the new one.
  assert.match(
    tuiCode,
    /request\(\s*"listProjects"\s*\)[\s\S]{0,700}setCurrentCwd/,
    "Ink must ask the daemon where it actually is",
  );
  assert.match(
    lineCode,
    /request\(\s*"listProjects"\s*\)[\s\S]{0,700}displayState\.cwd\s*=/,
    "line mode must do the same",
  );
});

test("nothing renders the launch-time cwd prop directly any more", () => {
  // `cwd` the prop is now only the bootstrap fallback. Every render
  // site and every RPC payload must read the derived `currentCwd`.
  assert.match(
    tuiCode,
    /const currentCwd = state\.cwd \|\| cwd;/,
    "there must be exactly one derived value, or they will drift again",
  );
  const direct = tuiCode.match(/cwd=\{cwd\}/g) || [];
  assert.equal(
    direct.length,
    0,
    `these renders still show the launch directory: ${direct.length} site(s)`,
  );
  // SDD sends cwd to the daemon — a stale value there is a real
  // correctness bug, not a cosmetic one.
  for (const rpc of ["sdd.start", "sdd.advance", "sdd.abort", "sdd.status"]) {
    const idx = tuiCode.indexOf(`"${rpc}"`);
    if (idx < 0) continue;
    // Anchor on the end of the request call, not a fixed char count.
    const end = tuiCode.indexOf("as RpcValue)", idx);
    assert.ok(end > idx, `${rpc} call not found — re-anchor this test`);
    const window = tuiCode.slice(idx, end);
    assert.match(
      window,
      /cwd: currentCwd/,
      `${rpc} must send the daemon-owned cwd, not the launch one`,
    );
  }
});

// --- the capability contract we corrected ---------------------------------

test("panelIntents no longer claims the cwd panel is unavailable in either renderer", () => {
  const idx = intentsCode.indexOf("cwd-switcher");
  assert.ok(idx > -1, "cwd-switcher intent not found (re-anchor this test)");
  // Anchor on the start of the NEXT registry entry rather than a char
  // count — a long gapNote would otherwise bleed the following panel's
  // fields into this one.
  const end = intentsCode.indexOf("\n  {", idx);
  assert.ok(end > idx, "next panel entry not found (re-anchor this test)");
  const entry = intentsCode.slice(idx, end);
  assert.match(
    entry,
    /ink:\s*['"]mounted['"]/,
    "the Ink render branch exists and now calls a real RPC",
  );
  assert.match(
    entry,
    /line:\s*['"]rendered['"]/,
    "line mode has been wired to switchProject since R362 — the old 'unavailable' was false",
  );
});

// --- the daemon contract we depend on -------------------------------------

test("the daemon really does register switchProject (our rename is correct)", () => {
  const java = join(
    root,
    "..",
    "aethercode",
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
  const http = join(
    root,
    "..",
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
  if (!existsSync(java) || !existsSync(http)) return;
  const j = readFileSync(java, "utf-8");
  const h = readFileSync(http, "utf-8");
  assert.match(
    j,
    /dispatcher\.register\(\s*"switchProject"/,
    "switchProject stopped being registered — re-check the TUI's rename",
  );
  assert.match(
    h,
    /case\s*"switchProject"/,
    "the HTTP transport lost its switchProject arm — the desktop would break too",
  );
});
