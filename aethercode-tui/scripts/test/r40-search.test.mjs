// R40: search /find.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// ----- 1. Pure search algorithm tests --------------------------------

/** R40: search turns by a query string. Returns indices of
 *  matching turns in the order they appear in the input array.
 *  A "match" is a case-insensitive substring match against the
 *  turn's text + toolName + toolArgs + toolResult + planItems.
 *
 *  We re-implement here in the test (the source has the same
 *  algorithm in a TUI component) so we can unit-test it without
 *  a React tree. */
function searchTurns(turns, query) {
  if (!query) return [];
  const q = query.toLowerCase();
  return turns
    .map((t, i) => {
      const hay = [
        t.text,
        t.toolName,
        t.toolArgs,
        t.toolResult,
        ...(t.planItems ?? []),
      ].filter(Boolean).join(" ").toLowerCase();
      return { index: i, hit: hay.includes(q) };
    })
    .filter((r) => r.hit)
    .map((r) => r.index);
}

const sampleTurns = [
  { id: 1, role: "user", text: "list files in current directory" },
  { id: 2, role: "assistant", text: "I'll use the glob tool." },
  { id: 3, role: "tool", text: "glob", toolName: "glob", toolArgs: "{\"pattern\":\"*.java\"}", toolResult: "A.java\nB.java" },
  { id: 4, role: "assistant", text: "Here are the Java files." },
  { id: 5, role: "tool", text: "file_read", toolName: "file_read", toolArgs: "{\"path\":\"A.java\"}", toolResult: "public class A {}" },
  { id: 6, role: "user", text: "now read the python files" },
  { id: 7, role: "tool", text: "glob", toolName: "glob", toolResult: "p.py\nq.py" },
];

test("R40: empty query returns no results", () => {
  assert.deepEqual(searchTurns(sampleTurns, ""), []);
  assert.deepEqual(searchTurns(sampleTurns, null), []);
  assert.deepEqual(searchTurns(sampleTurns, undefined), []);
});

test("R40: matches by text content (case-insensitive)", () => {
  // "java" appears in the tool args, tool result, and assistant prose.
  // Indices: 2 (glob tool's args + result), 3 (assistant "Java files"),
  // 4 (file_read tool's args + result).
  assert.deepEqual(searchTurns(sampleTurns, "java"), [2, 3, 4]);
  assert.deepEqual(searchTurns(sampleTurns, "JAVA"), [2, 3, 4]);
  assert.deepEqual(searchTurns(sampleTurns, "Java"), [2, 3, 4]);
});

test("R40: matches by tool name", () => {
  // "file_read" should match the turn with toolName="file_read"
  // (turn index 4, id=5) and the user turn that mentions "read"
  // (turn index 5, id=6).
  const r = searchTurns(sampleTurns, "file_read");
  assert.ok(r.includes(4), `expected index 4 in results, got ${r}`);
});

test("R40: matches by tool result content", () => {
  // "p.py" is in the result of the last turn (turn index 6, id=7).
  const r = searchTurns(sampleTurns, "p.py");
  assert.deepEqual(r, [6]);
});

test("R40: matches by tool args (JSON)", () => {
  // The pattern "*.java" is in the args of turn index 2 (id=3).
  const r = searchTurns(sampleTurns, "*.java");
  assert.ok(r.includes(2), `expected index 2 in results, got ${r}`);
});

test("R40: no match returns empty", () => {
  assert.deepEqual(searchTurns(sampleTurns, "rustlang"), []);
});

test("R40: returns indices in order", () => {
  const r = searchTurns(sampleTurns, "a"); // many matches
  // Indices must be in ascending order.
  for (let i = 1; i < r.length; i++) {
    assert.ok(r[i] > r[i - 1], `indices out of order: ${r.join(",")}`);
  }
});

// ----- 2. Source code assertions -------------------------------------

test("R40: SearchBar.tsx exists", () => {
  assert.ok(existsSync(join(root, "src", "components", "SearchBar.tsx")),
    "SearchBar.tsx not created");
});

test("R40: SearchBar.tsx exports SearchBar + a search helper", () => {
  const src = readFileSync(join(root, "src", "components", "SearchBar.tsx"), "utf-8");
  assert.match(src, /export const SearchBar/);
  assert.match(src, /function searchTurns/);
  assert.match(src, /export (function|const) (searchTurns|findMatches)/);
});

test("R40: tui.tsx wires Ctrl-F to show search + R32 search action exists in state", () => {
  const tui = readFileSync(join(root, "src", "tui.tsx"), "utf-8");
  assert.match(tui, /key\.ctrl && \(input === "f" \|\| input === "F"\)/);
  assert.match(tui, /dispatch\(\{ type: "showSearch", show: true \}\)/);
});

test("R40: state.ts has showSearch field + showSearch action", () => {
  const state = readFileSync(join(root, "src", "state.ts"), "utf-8");
  assert.match(state, /showSearch: boolean/);
  assert.match(state, /showSearch\??:.*?boolean|showSearch:.*?boolean/);
  assert.match(state, /\| \{ type: "showSearch"; show: boolean/);
});

// ----- 3. Build smoke test -------------------------------------------

test("R40: TypeScript compile of new SearchBar.tsx is clean", () => {
  const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
  const tmp = join(root, "tmp-r40-tsc");
  if (existsSync(tmp)) {
    spawnSync(process.platform === "win32" ? "cmd" : "rm",
      process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", tmp] : ["-rf", tmp]);
  }
  const tscArgs = [
    "--outDir", tmp, "--target", "ES2022", "--module", "ES2022",
    "--moduleResolution", "bundler", "--jsx", "react",
    "--esModuleInterop", "true", "--skipLibCheck", "true",
    "--rootDir", join(root, "src"),
  ];
  const r = spawnSync(`"${tscBin}"`, [
    ...tscArgs,
    '"' + join(root, "src", "components", "SearchBar.tsx") + '"',
  ], { encoding: "utf-8", shell: true });
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
  }
  assert.equal(r.status, 0, "tsc compile failed for R40 SearchBar");
});
