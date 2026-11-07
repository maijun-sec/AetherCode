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

// R388n — the checkpoint gate's MUTATION test.
//
// ## ★ Why this file exists ★
//
// A gate that cannot go red proves nothing (Lesson 1219). This file
// MUTATES the source the gate reads and asserts the gate notices.
//
// ## ★ The mechanism ★
//
// Each mutation:
//   1. copies the repo's source files into a temp tree,
//   2. applies ONE edit to the copy,
//   3. runs the gate against the copy,
//   4. asserts the gate FAILED.
//
// The gate reads its sources through `root`, so pointing `root` at the
// copy is enough — no rewrite of the gate itself.
//
// ★ `NODE_TEST_CONTEXT` is DELETED from the child's environment. ★
// Lesson 1358: with it set, the child runner exits 0 whatever the
// child test does, and every mutation "survives".

import { test } from "node:test";
import assert from "node:assert/strict";
import { cpSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";

const here = dirname(fileURLToPath(import.meta.url));
const tuiRoot = join(here, "..", "..");

/** The gate under test. */
const GATE = join(here, "r388n-checkpoint-menu.test.mjs");

/**
 * Copy the sources the gate reads into a temp tree.
 *
 * <p>The TUI's `src/` plus the Maven root's Java packages. The Java
 * tree is large, so only the packages the gate opens are copied.
 */
function makeTree() {
  const dir = mkdtempSync(join(tmpdir(), "r388n-mut-"));
  const tuiSrc = join(dir, "aethercode-tui", "src");
  cpSync(join(tuiRoot, "src"), tuiSrc, { recursive: true });

  // The Maven root is a SIBLING of the TUI package:
  // `aethercode-tui/` and `aethercode/` sit side by side under the
  // repo root.
  const mavenRoot = join(tuiRoot, "..", "aethercode");
  const javaPkgs = [
    ["aethercode-protocol", "org", "aethercode", "protocol"],
    ["aethercode-sdk", "org", "aethercode", "sdk"],
    ["aethercode-core", "org", "aethercode", "core"],
  ];
  for (const [mod, ...rest] of javaPkgs) {
    const from = join(mavenRoot, mod, "src", "main", "java", ...rest);
    const to = join(dir, "aethercode", mod, "src", "main", "java", ...rest);
    cpSync(from, to, { recursive: true });
  }

  // The gate's own directory (it imports helpers from there).
  cpSync(join(here), join(dir, "aethercode-tui", "scripts", "test"), {
    recursive: true,
    filter: (src) => !src.endsWith("r388n-checkpoint-menu-mutation.test.mjs"),
  });
  return dir;
}

/**
 * Edit one file in the tree, by REGEX.
 *
 * <p>★ The anchor is a REGEX, and the match is newline-agnostic. ★
 * Two reasons:
 *
 * <ul>
 *   <li>The Java sources use CRLF and the TypeScript sources use LF, so
 *       a literal `\n` would not match a CRLF file.</li>
 *   <li>★ A formatter reflow can silently blind a gate. ★ (R376) An
 *       anchor written with exact spaces and newlines stops matching
 *       the moment prettier reflows the file — and the mutation then
 *       changes NOTHING, which reads as "the gate survived" rather
 *       than as "the anchor is stale". `\s+` where whitespace varies
 *       keeps the anchor alive across a reflow.</li>
 * </ul>
 */
function editFile(tree, rel, pattern, replacement) {
  const p = join(tree, rel);
  const before = readFileSync(p, "utf8");
  const flat = before.replace(/\r\n/g, "\n");
  const re = new RegExp(pattern.source, pattern.flags.replace("g", ""));
  const m = re.exec(flat);
  if (!m) {
    throw new Error(`mutation anchor not found in ${rel}: ${JSON.stringify(pattern.source)}`);
  }
  const updated =
    flat.slice(0, m.index) + m[0].replace(re, replacement) + flat.slice(m.index + m[0].length);
  writeFileSync(p, updated, "utf8");
}

/**
 * Run the gate against a mutated tree.
 *
 * <p>★ The gate that runs is the COPY inside the mutated tree. ★
 * Running the ORIGINAL would read the ORIGINAL sources — the
 * mutation would be invisible and every case would report "survived".
 *
 * @returns {number} the exit code — 0 means the gate PASSED (bad),
 *   non-zero means the gate noticed (good).
 */
function runGate(tree) {
  const env = { ...process.env };
  // ★ Lesson 1358: the child must NOT inherit NODE_TEST_CONTEXT, or
  // it exits 0 whatever the child test does.
  delete env.NODE_TEST_CONTEXT;
  const gate = join(tree, "aethercode-tui", "scripts", "test", "r388n-checkpoint-menu.test.mjs");
  const r = spawnSync(process.execPath, ["--test", gate], {
    cwd: join(tree, "aethercode-tui"),
    env,
    encoding: "utf8",
    timeout: 180_000,
  });
  return r.status ?? -1;
}

/**
 * Assert that a mutation makes the gate FAIL.
 */
function assertGateGoesRed(name, mutate) {
  const tree = makeTree();
  try {
    mutate(tree);
    const status = runGate(tree);
    assert.notEqual(
      status,
      0,
      `${name}: the gate PASSED on a mutated tree — it cannot detect this defect`,
    );
  } finally {
    rmSync(tree, { recursive: true, force: true });
  }
}

const TUI_FILES = join("aethercode-tui", "src");
const JAVA_CORE = join(
  "aethercode",
  "aethercode-core",
  "src",
  "main",
  "java",
  "org",
  "aethercode",
  "core",
);
const JAVA_SDK = join(
  "aethercode",
  "aethercode-sdk",
  "src",
  "main",
  "java",
  "org",
  "aethercode",
  "sdk",
);
const JAVA_PROTO = join(
  "aethercode",
  "aethercode-protocol",
  "src",
  "main",
  "java",
  "org",
  "aethercode",
  "protocol",
);

/* ------------------------------------------------------------------ */

test("MUTATION: the line renderer keeping its OWN action list is caught", () => {
  assertGateGoesRed("line.ts grows a second CHECKPOINT_ACTIONS", (tree) => {
    editFile(
      tree,
      join(TUI_FILES, "line.ts"),
      /import \{ describeSentinel, intentForSentinel \} from "\.\/panelIntents\.js";/,
      'import { describeSentinel, intentForSentinel } from "./panelIntents.js";\n' +
        'const CHECKPOINT_ACTIONS = ["a", "b", "c"] as const;',
    );
  });
});

test("MUTATION: the dispatcher special-casing the combined action is caught", () => {
  assertGateGoesRed("tui.tsx special-cases restore_code_and_conversation", (tree) => {
    // ★ The anchor is INSIDE `applyCheckpointAction`. ★ There is
    // another `const info = CHECKPOINT_ACTION_INFO[action];` in the
    // JSX callback that renders the panel — and a mutation that hit
    // THAT one changed nothing the gate looks at. A needle that is
    // not unique lands on the wrong occurrence and pretends to have
    // tested something (Lesson 1319).
    editFile(
      tree,
      join(TUI_FILES, "tui.tsx"),
      /touchesConversation: boolean,\s*\): Promise<void> \{/,
      "touchesConversation: boolean,\n  ): Promise<void> {\n" +
        '    if (action === "restore_code_and_conversation") return;',
    );
  });
});

test("MUTATION: bare /rewind going back to demanding an index is caught", () => {
  assertGateGoesRed("/rewind no longer opens the menu", (tree) => {
    editFile(
      tree,
      join(TUI_FILES, "commands.ts"),
      /if \(rest\.length === 0\) return \{ local: "__CHECKPOINT_MENU__" \};/,
      'if (rest.length === 0) return { local: "usage: /rewind <history-index>" };',
    );
  });
});

test("MUTATION: the Esc-Esc opening moving BEFORE the window check is caught", () => {
  assertGateGoesRed("the FIRST Esc opens the menu", (tree) => {
    editFile(
      tree,
      join(TUI_FILES, "tui.tsx"),
      /const now = Date\.now\(\);\s*if \(now - escEscRef\.current <= ESC_ESC_WINDOW_MS\) \{/,
      "const now = Date.now();\n" +
        '      dispatch({ type: "openPanel", id: "checkpoints" });\n' +
        "      if (now - escEscRef.current <= ESC_ESC_WINDOW_MS) {",
    );
  });
});

test("MUTATION: the checkpoint ordinal going back to being derived directly is caught", () => {
  assertGateGoesRed("the transcript index is derived from the ordinal again", (tree) => {
    editFile(
      tree,
      join(JAVA_SDK, "AetherCodeEngine.java"),
      /java\.util\.List<Integer> turns\s*=\s*org\.aethercode\.core\.transcript\.CodeCheckpointStore\.userTurnIndices\(t\);/,
      "java.util.List<Integer> turns = java.util.List.of();",
    );
  });
});

test("MUTATION: the restore re-deriving its boundary instead of using the checkpoint's is caught", () => {
  assertGateGoesRed("restoreConversationTo ignores cp.transcriptIndex()", (tree) => {
    editFile(
      tree,
      join(JAVA_SDK, "AetherCodeEngine.java"),
      /int keep = Math\.max\(0, Math\.min\(cp\.transcriptIndex\(\), t\.size\(\)\)\);/,
      "int keep = Math.max(0, Math.min(cp.userMessageIndex(), t.size()));",
    );
  });
});

test("MUTATION: an HTTP route disappearing is caught", () => {
  assertGateGoesRed("checkpoints/restore is no longer routed on HTTP", (tree) => {
    editFile(
      tree,
      join(JAVA_PROTO, "http", "HttpJsonRpcServer.java"),
      /case "checkpoints\/restore" -> methods\.checkpointsRestore\(params\);/,
      'case "checkpoints/restore-legacy" -> methods.checkpointsRestore(params);',
    );
  });
});

test("MUTATION: the checkpoint producer being removed is caught", () => {
  assertGateGoesRed("the turn boundary stops taking checkpoints", (tree) => {
    editFile(
      tree,
      join(JAVA_CORE, "engine", "QueryEngine.java"),
      /onTurnBoundary\.accept\(userInput\);/,
      "// onTurnBoundary.accept(userInput);",
    );
  });
});

test("MUTATION: a second 'the menu is open' flag is caught", () => {
  assertGateGoesRed("state.ts grows a checkpointMenuOpen flag", (tree) => {
    editFile(
      tree,
      join(TUI_FILES, "state.ts"),
      /checkpoints: \[\],/,
      "checkpointMenuOpen: false,\n  checkpoints: [],",
    );
  });
});

test("MUTATION: commenting the turn-boundary call out is caught", () => {
  // ★ This is the case that found a REAL gate bug. ★ The gate read the
  // RAW source, so a commented-out call still satisfied "the hook must
  // fire" — the text was still in the file. The gate now reads the
  // comment-stripped source, and this mutation proves it.
  assertGateGoesRed("the turn boundary stops taking checkpoints", (tree) => {
    editFile(
      tree,
      join(JAVA_CORE, "engine", "QueryEngine.java"),
      /onTurnBoundary\.accept\(userInput\);/,
      "// onTurnBoundary.accept(userInput);",
    );
  });
});

test("MUTATION: commenting the checkpoint PRODUCER out is caught", () => {
  assertGateGoesRed("the engine stops taking checkpoints at the turn boundary", (tree) => {
    editFile(
      tree,
      join(JAVA_SDK, "AetherCodeEngine.java"),
      /saveCodeCheckpoint\(ordinal, summarizePrompt\(prompt\)\);/,
      "// saveCodeCheckpoint(ordinal, summarizePrompt(prompt));",
    );
  });
});
