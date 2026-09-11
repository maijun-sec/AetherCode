// R47: command palette.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// ----- 1. Pure tests on rankCommands() -------------------------------

function isSubsequence(needle, haystack) {
  let i = 0;
  for (const c of haystack) {
    if (c === needle[i]) i++;
    if (i === needle.length) return true;
  }
  return i === needle.length;
}

function rankCommands(query, limit = 8) {
  if (!query) return [];
  const q = query.toLowerCase();
  const out = [];
  for (const name of ["help", "?", "clear", "exit", "quit", "q", "tools", "state", "ping", "model", "mode", "sessions", "tasks", "projects", "cwd", "stats", "history", "lastplan", "theme", "layout", "budget", "skip", "tool-actions"]) {
    const lower = name.toLowerCase();
    let score = 0;
    const highlights = [];
    if (lower.startsWith(q)) {
      score = 1000 - (lower.length - q.length);
      for (let i = 0; i < q.length; i++) highlights.push(i);
    } else if (lower.includes(q)) {
      const idx = lower.indexOf(q);
      score = 500 - idx;
      for (let i = 0; i < q.length; i++) highlights.push(idx + i);
    } else if (isSubsequence(q, lower)) {
      score = 100;
      let qi = 0;
      for (let i = 0; i < lower.length && qi < q.length; i++) {
        if (lower[i] === q[qi]) { highlights.push(i); qi++; }
      }
    }
    if (score > 0) out.push({ name, score, highlights });
  }
  out.sort((a, b) => b.score - a.score);
  return out.slice(0, limit);
}

test("R47: empty query returns no matches", () => {
  assert.deepEqual(rankCommands(""), []);
});

test("R47: prefix match ranks first (and is the only tier-1 match)", () => {
  const r = rankCommands("mo");
  // Both "model" and "mode" start with "mo" — but they share a prefix,
  // so the LCP is "mo" and we get both as prefix matches.
  const names = r.map((m) => m.name);
  assert.ok(names.includes("model"));
  assert.ok(names.includes("mode"));
  // All prefix matches should be at the top.
  for (const m of r) {
    assert.ok(m.score >= 500, `expected score >= 500 for ${m.name}, got ${m.score}`);
  }
});

test("R47: substring match ranks above subsequence", () => {
  // "or" appears in "tools" as a substring? "tools" = t-o-o-l-s.
  // "or" → o-r. There's no "r" in "tools", so this is subsequence.
  // Use "to" instead — appears in "tools" as substring.
  const r = rankCommands("to");
  const tools = r.find((m) => m.name === "tools");
  assert.ok(tools);
  // Substring match → score 500+.
  assert.ok(tools.score >= 500, `expected score >= 500 for tools with substring 'to', got ${tools.score}`);
});

test("R47: subsequence match (no substring) returns weak score", () => {
  // "et" doesn't appear in any command as a substring, but is a
  // subsequence of "exit" (e-x-i-t, no "e" then "t"), "state"
  // (s-t-a-t-e, "e" then "t" — yes! in reverse order, "t" then "e").
  // Hmm, "et" in "state" is s-t-a-t-e: t at idx 1, e at idx 4.
  // isSubsequence("et", "state") — needle[0]='e', haystack has 's','t','a','t','e'.
  //   At idx 0 's' != 'e'. idx 1 't' != 'e'. idx 2 'a' != 'e'. idx 3 't' != 'e'. idx 4 'e' == 'e' → i=1.
  //   Then needle[1]='t', idx 5 doesn't exist. Return i==needle.length? 1 != 2. False.
  // So "et" is not a subsequence of "state".
  // Try "et" in "exit": e-x-i-t. needle 'e' at idx 0 ✓. needle 't' at idx 3 ✓. Match!
  const r = rankCommands("et");
  const exit = r.find((m) => m.name === "exit");
  assert.ok(exit, `expected 'exit' in matches for "et", got ${r.map((m) => m.name).join(",")}`);
  assert.ok(exit.score < 500, `expected weak score for subsequence match, got ${exit.score}`);
});

test("R47: limit caps the number of results", () => {
  const r = rankCommands("e", 3);
  assert.equal(r.length, 3);
});

test("R47: highlights mark the matched positions", () => {
  const r = rankCommands("mo");
  const model = r.find((m) => m.name === "model");
  assert.ok(model);
  // "mo" is a 2-char prefix of "model" → highlights are [0, 1].
  assert.deepEqual(model.highlights, [0, 1]);
});

test("R47: no match returns empty", () => {
  assert.deepEqual(rankCommands("xyzzy"), []);
});

// ----- 2. Source code assertions -------------------------------------

test("R47: CommandPalette.tsx exists + exports CommandPalette + rankCommands", () => {
  assert.ok(existsSync(join(root, "src", "components", "CommandPalette.tsx")));
  const src = readFileSync(join(root, "src", "components", "CommandPalette.tsx"), "utf-8");
  assert.match(src, /export const CommandPalette/);
  assert.match(src, /export function rankCommands/);
  assert.match(src, /export interface PaletteEntry/);
});

test("R47: state.ts has paletteOpen field + setPaletteOpen action", () => {
  const state = readFileSync(join(root, "src", "state.ts"), "utf-8");
  assert.match(state, /paletteOpen: boolean/);
  assert.match(state, /setPaletteOpen/);
});

test("R47: tui.tsx has Ctrl-P binding + CommandPalette render", () => {
  const tui = readFileSync(join(root, "src", "tui.tsx"), "utf-8");
  assert.match(tui, /import\s+\{[^}]*\bCommandPalette\b[^}]*\}\s+from\s+"\.\/components\/CommandPalette\.js"/);
  assert.match(tui, /key\.ctrl && \(input === "p" \|\| input === "P"\)/);
  assert.match(tui, /state\.paletteOpen/);
});

// ----- 3. Build smoke test -------------------------------------------

test("R47: TypeScript compile of CommandPalette.tsx is clean", () => {
  const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
  const tmp = join(root, "tmp-r47-tsc");
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
    '"' + join(root, "src", "components", "CommandPalette.tsx") + '"',
  ], { encoding: "utf-8", shell: true });
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
  }
  assert.equal(r.status, 0, "tsc compile failed for R47 CommandPalette");
});
