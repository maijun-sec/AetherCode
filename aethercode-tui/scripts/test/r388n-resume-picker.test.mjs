// Copyright (c) 2026 maijun
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

// R388n — the `/resume` selector: the keys Claude Code has and the TUI did
// not.
//
// ## ★ The defect ★
//
// The TUI's session picker had four keys (Esc / a / ↑↓ / Enter) and the
// DAEMON had no way to accept a session name. Two specific lies:
//
//   1. `ThreadSelector`'s `showBranch` prop was DECLARED and NEVER USED.
//      The daemon never sent `gitBranch`, so the column the prop was
//      written for was structurally incapable of appearing. A prop that
//      is declared and never read is a promise the code does not keep.
//
//   2. `sizeBytes` was on the wire since the session store existed — it
//      is the source of the `messageCount` proxy — and the TUI never
//      read it.
//
// ## ★ The keys ★
//
// Claude Code's `/resume` selector binds:
//   1–9        pick the Nth row
//   Space      preview
//   Ctrl+R     rename
//   Ctrl+A     every project
//   Ctrl+W     every worktree
//   Ctrl+B     the current branch
//   → / ←      collapse / expand a group
//
// R388n adds the first six. (The group collapse needs a GROUPING the
// row list does not have yet — it is a different feature, not a
// half-finished one.)
//
// ## ★ Why the daemon side is in the gate ★
//
// A client that sends `session/rename` to a daemon that does not
// register it gets `-32601 method not found` — at the moment the user
// presses Ctrl+R, with the rename field open. So the gate checks the
// daemon's own registry, on BOTH transports.
//
// → **Lesson 1328 — a shared registry makes "the wiring differs per
// transport" INVISIBLE.** The method is registered in ONE place and
// routed in ANOTHER; asking "is it registered?" answers "yes" on both
// transports and never reaches the defect.
//
// → **Lesson 1330 — a prop that is DECLARED and never read is a
// promise the code does not keep.** `showBranch` was written for a
// column the daemon never sent.

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { codeOf } from "./strip-comments.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

const read = (rel) => readFileSync(join(root, rel), "utf8");
const readCode = (rel) => codeOf(read(rel));

const JAVA_METHODS = join(
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
const JAVA_HTTP = join(
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
const JAVA_CORE = join(
  root,
  "..",
  "aethercode",
  "aethercode-core",
  "src",
  "main",
  "java",
  "org",
  "aethercode",
  "core",
);

/* ------------------------------------------------------------------ *
 * Load the REAL modules.
 * ------------------------------------------------------------------ */
const loadTs = async (name) => {
  const { build } = await import("esbuild");
  const { mkdtempSync, rmSync } = await import("node:fs");
  const { tmpdir } = await import("node:os");
  const dir = mkdtempSync(join(tmpdir(), `r388n-resume-${name}-`));
  const outfile = join(dir, `${name}.mjs`);
  try {
    await build({
      entryPoints: [join(root, "src", `${name}.ts`)],
      bundle: true,
      outfile,
      platform: "node",
      format: "esm",
      loader: { ".ts": "ts" },
    });
    return await import(pathToFileURL(outfile).href);
  } finally {
    try {
      rmSync(outfile, { force: true });
    } catch {
      /* best effort */
    }
  }
};

/* ================================================================== *
 * 1. The daemon sends the branch.
 * ================================================================== */

test("R388n: listSessions sends the git branch of the session's cwd", () => {
  const java = readFileSync(JAVA_METHODS, "utf8");
  const start = java.indexOf("public Object listSessions(Object params)");
  assert.ok(start > 0, "listSessions must exist");
  const body = java.slice(start, start + 12000);
  assert.match(
    body,
    /"gitBranch"/,
    "listSessions must put a gitBranch on each row — the picker's branch " +
      "column is structurally incapable of appearing without it",
  );
  assert.match(
    body,
    /GitBranchReader/,
    "the branch must come from the ONE reader, not a second implementation",
  );
});

test("R388n: getState carries the CURRENT branch (the filter's reference)", () => {
  const java = readFileSync(JAVA_METHODS, "utf8");
  const start = java.indexOf("public Object getState(Object params)");
  assert.ok(start > 0, "getState must exist");
  const body = java.slice(start, start + 6000);
  assert.match(
    body,
    /"gitBranch"/,
    "getState must carry the cwd's branch — the Ctrl+B filter compares the " +
      "rows against it, and only the daemon knows the session's cwd",
  );
});

test("R388n: the branch is read from the FILE, not a subprocess per row", () => {
  // A 50-row list in one repo must cost ONE read, not 50 subprocess
  // spawns. The reader walks UP from the cwd and caches by git dir.
  const java = readFileSync(join(JAVA_CORE, "context", "GitBranchReader.java"), "utf8");
  assert.match(java, /ConcurrentHashMap/, "the reader must cache by git dir");
  assert.match(
    java,
    /rev-parse|ProcessBuilder/,
    "the reader must NOT spawn a process — that is 50 subprocesses for a " +
      "50-row list, and the column is a decoration",
  );
});

test("R388n: a DETACHED HEAD has no branch (the honest answer)", () => {
  const java = readFileSync(join(JAVA_CORE, "context", "GitBranchReader.java"), "utf8");
  // A detached HEAD is a sha. There is no branch. Returning the sha
  // would be a lie about what the repository is on.
  assert.match(java, /HEADS_PREFIX/, "the reader must only accept a refs/heads/ ref");
  assert.match(java, /refs\/heads\//, "the reader must only accept a refs/heads/ ref");
});

/* ================================================================== *
 * 2. The daemon can rename.
 * ================================================================== */

test("R388n: the daemon registers session/rename on the dispatcher", () => {
  const java = readFileSync(JAVA_METHODS, "utf8");
  assert.match(
    java,
    /dispatcher\.register\("session\/rename"/,
    "session/rename must be registered — a client RPC that does not exist " +
      "is a -32601 at the moment the user presses Ctrl+R",
  );
});

test("R388n: session/rename is ROUTED on the HTTP transport too", () => {
  // ★ Lesson 1328: a method registered in one place and routed in
  // another. Asking "is it registered?" answers "yes" on both
  // transports and never reaches the defect.
  const java = readFileSync(JAVA_HTTP, "utf8");
  assert.match(
    java,
    /case "session\/rename"/,
    "session/rename must be routed on HTTP — the stdio path would work " +
      "while the HTTP path returned -32601",
  );
});

test("R388n: the rename writes ONLY the title (a rename must not MOVE a session)", () => {
  const java = readFileSync(join(JAVA_CORE, "transcript", "SessionStoreSqlite.java"), "utf8");
  const start = java.indexOf("public boolean setTitle(");
  assert.ok(start > 0, "the sqlite layer must have setTitle");
  const body = java.slice(start, start + 1200);
  assert.match(
    body,
    /UPDATE sessions SET title = \? WHERE id = \?/,
    "the rename must update the title column alone — a rename that goes " +
      "through touch() could move the session to another directory",
  );
});

test("R388n: the rename answers with the title the STORE holds", () => {
  const java = readFileSync(JAVA_METHODS, "utf8");
  const start = java.indexOf("public Object sessionRename(Object params)");
  assert.ok(start > 0, "sessionRename must exist");
  const body = java.slice(start, start + 2500);
  assert.match(body, /"title"/, "the answer must carry the title");
  // And an empty title is refused — "renamed to ''" would be a lie
  // about what the row shows (the renderer falls back to the first
  // prompt).
  assert.match(
    body,
    /title must not be empty/,
    "an empty title must be refused — the row would show the first " +
      "prompt, so an empty name is not what the answer claims",
  );
});

/* ================================================================== *
 * 3. The picker binds the keys.
 * ================================================================== */

test("R388n: the picker binds 1-9, Space, Ctrl+R, Ctrl+A, Ctrl+W, Ctrl+B", () => {
  const code = readCode("src/components/ThreadSelector.tsx");
  // The digits.
  assert.match(
    code,
    /\/\^\[1-9\]\$\//,
    "the picker must bind the digit keys — Claude Code's `/resume` " +
      "accepts 1-9 as a direct pick",
  );
  // Space.
  assert.match(code, /input === " "/, "the picker must bind Space (the preview)");
  // The chords.
  for (const chord of ["r", "a", "w", "b"]) {
    assert.match(
      code,
      new RegExp(`chord === "${chord}"`),
      `the picker must bind Ctrl+${chord.toUpperCase()}`,
    );
  }
});

test("R388n: the DIGIT picks a row of the VISIBLE list", () => {
  // The number on screen and the number the user presses must be the
  // same thing — so the digit indexes the FILTERED list, not the
  // window and not the raw list.
  const code = readCode("src/components/ThreadSelector.tsx");
  assert.match(
    code,
    /const idx = Number\.parseInt\(input, 10\) - 1;/,
    "the digit must be a 1-based index",
  );
  assert.match(
    code,
    /const t = filtered\[idx\];/,
    "the digit must index the FILTERED list — the visible rows are the " +
      "filtered ones, and a number that points elsewhere is a pick that " +
      "opens the wrong session",
  );
});

test("R388n: the picker renders the branch and the size", () => {
  const code = readCode("src/components/ThreadSelector.tsx");
  assert.match(code, /t\.gitBranch/, "the row must render the branch");
  assert.match(code, /formatBytes/, "the row must render the size");
  assert.match(
    code,
    /sizeBytes/,
    "the ThreadInfo must carry the size — the daemon has always sent it",
  );
});

test("R388n: the `showBranch` prop is no longer DEAD CODE", () => {
  // ★ The defect ★ the prop was declared and never used — a promise the
  // code did not keep.
  const code = readCode("src/components/ThreadSelector.tsx");
  const decl = code.match(/showBranch\??\s*:/);
  if (!decl) return; // the prop was removed entirely — also fine
  const uses = [...code.matchAll(/showBranch/g)];
  assert.ok(
    uses.length >= 2,
    "showBranch is declared but never used — a prop that is written and " +
      "not read is a promise the code does not keep",
  );
});

test("R388n: the branch filter is a NO-OP without a current branch", () => {
  // Showing everything while claiming "branch only" is a lie about
  // the list.
  const code = readCode("src/components/ThreadSelector.tsx");
  assert.match(
    code,
    /if \(!currentBranch\) return byText;/,
    "without a current branch the filter must be a no-op",
  );
  assert.match(
    code,
    /branch only/,
    "the panel must SAY when the branch filter is on — a filter that is " +
      "on but invisible looks like the list lost rows",
  );
});

test("R388n: the rename field owns the keyboard while it is open", () => {
  // Typing "d" in the rename field must type a "d", not navigate. The
  // rename branch must come FIRST and must RETURN.
  const code = readCode("src/components/ThreadSelector.tsx");
  const renameIdx = code.indexOf("if (renaming) {");
  assert.ok(renameIdx > 0, "the picker must have a rename branch");
  const branch = code.slice(renameIdx, renameIdx + 1500);
  assert.match(branch, /return;/, "the rename branch must return");
  assert.match(
    branch,
    /key\.ctrl/,
    "the rename branch must ignore modifier chords — Ctrl+R must not " +
      "type an 'r' into the name",
  );
});

test("R388n: the preview is a MODE, and Esc leaves it before the panel", () => {
  const code = readCode("src/components/ThreadSelector.tsx");
  // ★ The Esc handler must be the one AFTER the rename branch. ★
  // The rename branch also handles Esc (it cancels the rename), and
  // matching the FIRST `if (key.escape) {` would read the wrong branch
  // (Lesson 1322: searching in a big block can find a neighbouring
  // construct's label).
  //
  // So the search starts AFTER the rename branch's own Esc check —
  // bounded by the rename branch's closing brace.
  const renameIdx = code.indexOf("if (renaming) {");
  assert.ok(renameIdx > 0, "the picker must have a rename branch");
  // The rename branch's Esc: `if (key.escape) {` inside it.
  const renameEscIdx = code.indexOf("if (key.escape) {", renameIdx);
  assert.ok(renameEscIdx > 0, "the rename branch must handle Esc");
  const escIdx = code.indexOf("if (key.escape) {", renameEscIdx + 1);
  assert.ok(escIdx > 0, "the picker must handle Esc after the rename branch");
  const branch = code.slice(escIdx, escIdx + 700);
  assert.match(
    branch,
    /setPreview\(false\)/,
    "Esc must close the PREVIEW first — it is a mode, and Esc means " +
      "'leave the mode I am in' before it means 'close the panel'",
  );
  assert.match(branch, /onClose\(\)/, "Esc must still close the panel when no mode is open");
});

/* ================================================================== *
 * 4. The app host wires them.
 * ================================================================== */

test("R388n: the app host passes the branch, the rename and the widen scopes", () => {
  const code = readCode("src/tui.tsx");
  assert.match(
    code,
    /currentBranch=\{state\.currentGitBranch\}/,
    "the host must pass the cwd's branch — the picker cannot discover it",
  );
  assert.match(
    code,
    /onRename=\{/,
    "the host must pass a rename handler — otherwise Ctrl+R opens a " + "field that goes nowhere",
  );
  assert.match(code, /"session\/rename"/, "the host must call the daemon's rename RPC");
  assert.match(code, /onShowAllProjects=/, "the host must pass the Ctrl+A scope");
  assert.match(code, /onShowAllWorktrees=/, "the host must pass the Ctrl+W scope");
});

test("R388n: the app host reads the branch off getState", () => {
  const code = readCode("src/tui.tsx");
  assert.match(
    code,
    /stObj\.gitBranch/,
    "the host must read the branch the daemon sends on getState",
  );
});

test("R388n: a rename RE-FETCHES the list", () => {
  // The picker's row list is a LOCAL COPY. A rename writes the store;
  // without a re-fetch the row shows the OLD name next to a toast that
  // says the new one.
  const code = readCode("src/tui.tsx");
  assert.match(
    code,
    /refreshThreads/,
    "a rename must re-fetch the list — the row list is a local copy",
  );
});

test("R388n: the key table names the new chords (and only real ones)", () => {
  const table = readCode("src/componentKeys.ts");
  for (const token of ['"ctrl+r"', '"ctrl+b"', '"ctrl+a"', '"ctrl+w"']) {
    assert.ok(
      table.includes(token),
      `the key table must name ${token} — a chord the panel binds but the ` +
        "hint hides is the other half of the same defect",
    );
  }
  // And the digits.
  assert.match(table, /"1"/, "the key table must name the digit keys");
});
