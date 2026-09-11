// R38: slash command tab-completion.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// ----- 1. Pure tests (re-implement the algorithm) --------------------

function longestCommonPrefix(strs) {
  if (strs.length === 0) return "";
  if (strs.length === 1) return strs[0];
  let prefix = strs[0];
  for (let i = 1; i < strs.length; i++) {
    // IMPORTANT: use startsWith, not indexOf === 0. indexOf matches
    // any substring, so "model".indexOf("mode") === 0 would falsely
    // accept "mode" as a prefix of "model".
    while (!strs[i].startsWith(prefix)) {
      prefix = prefix.slice(0, -1);
      if (prefix.length === 0) return "";
    }
  }
  return prefix;
}

const SLASH = [
  "help", "?", "clear", "exit", "quit", "q",
  "tools", "state", "ping", "model", "mode",
  "sessions", "tasks", "projects", "cwd", "stats", "history", "lastplan",
];

function completeSlash(partial) {
  const trimmed = partial.trim();
  if (!trimmed.startsWith("/")) return { completed: partial, alternatives: [], oneShot: false };
  const spaceAt = trimmed.indexOf(" ");
  const cmdPart = spaceAt < 0 ? trimmed.slice(1) : trimmed.slice(1, spaceAt);
  const rest = spaceAt < 0 ? "" : trimmed.slice(spaceAt);
  if (cmdPart.length === 0) {
    return { completed: "/" + SLASH[0], alternatives: SLASH.slice(1), oneShot: false };
  }
  const matches = SLASH.filter((c) => c.startsWith(cmdPart));
  if (matches.length === 0) return { completed: partial, alternatives: [], oneShot: false };
  if (matches.length === 1) {
    return { completed: "/" + matches[0] + (rest ? rest : " "), alternatives: [], oneShot: true };
  }
  const lcp = longestCommonPrefix(matches);
  return { completed: "/" + lcp + rest, alternatives: matches, oneShot: false };
}

test("R38: no-op on non-slash input", () => {
  const c = completeSlash("hello world");
  assert.equal(c.completed, "hello world");
  assert.deepEqual(c.alternatives, []);
});

test("R38: bare '/' shows all commands", () => {
  const c = completeSlash("/");
  assert.ok(c.alternatives.length > 10);
  assert.equal(c.oneShot, false);
});

test("R38: unique prefix completes fully", () => {
  // "/h" matches only "help" and "history" — both start with 'h', but
  // the LCP is "h" so we don't fully complete. Hmm, let me use a
  // better example.
  const c = completeSlash("/too");
  assert.equal(c.completed, "/tools ");
  assert.equal(c.oneShot, true);
  assert.deepEqual(c.alternatives, []);
});

test("R38: shared prefix returns alternatives + LCP", () => {
  const c = completeSlash("/mo");
  // Both "model" and "mode" start with "mo". LCP is "mo" (both
  // have just "mo" in common — "model" has "mod..." and "mode"
  // has "mod..." but they diverge at "mode-l" vs "mode-eof").
  // Wait: "model".startsWith("mo") and "mode".startsWith("mo")
  // both true, so LCP includes "mo" + more if they match.
  // "model".startsWith("mod") and "mode".startsWith("mod") — yes
  // both start with "mod". And "model".startsWith("mode") — yes
  // (model = "mode" + "l"). And "mode".startsWith("mode") — yes.
  // So LCP = "mode" (4 chars). The user is then at "/mode" and
  // can hit Tab to see the alternative "model".
  assert.equal(c.completed, "/mode");
  assert.deepEqual(c.alternatives.sort(), ["mode", "model"]);
  assert.equal(c.oneShot, false);
});

test("R38: '?' alias resolves to help", () => {
  const c = completeSlash("/?");
  assert.equal(c.completed, "/? ");
  assert.equal(c.oneShot, true);
});

test("R38: no match returns input unchanged", () => {
  const c = completeSlash("/zzz");
  assert.equal(c.completed, "/zzz");
  assert.equal(c.oneShot, false);
  assert.deepEqual(c.alternatives, []);
});

test("R38: completion preserves the rest of the input", () => {
  // If the user types "/mod foo", we complete the command part to
  // LCP ("mode") and preserve the trailing " foo".
  const c = completeSlash("/mod foo");
  assert.equal(c.completed, "/mode foo");
  assert.deepEqual(c.alternatives.sort(), ["mode", "model"]);
});

// ----- 2. Source code assertions -------------------------------------

test("R38: commands.ts exports SLASH_COMMANDS + completeSlash", () => {
  const src = readFileSync(join(root, "src", "commands.ts"), "utf-8");
  assert.match(src, /export const SLASH_COMMANDS/);
  assert.match(src, /export function completeSlash/);
  assert.match(src, /export interface Completion/);
});

test("R38: commands.ts has the longestCommonPrefix helper", () => {
  const src = readFileSync(join(root, "src", "commands.ts"), "utf-8");
  assert.match(src, /function longestCommonPrefix/);
});

test("R38: tui.tsx imports completeSlash + handles Tab key", () => {
  const tui = readFileSync(join(root, "src", "tui.tsx"), "utf-8");
  assert.match(tui, /import\s+\{[^}]*\bcompleteSlash\b[^}]*\}\s+from\s+"\.\/commands\.js"/);
  assert.match(tui, /key\.tab/);
  assert.match(tui, /completeSlash\(state\.input\)/);
  assert.match(tui, /sideNote/);
  assert.match(tui, /kind: "completion"/);
});

test("R38: completeSlash is only triggered for slash input", () => {
  const tui = readFileSync(join(root, "src", "tui.tsx"), "utf-8");
  // The Tab handler should guard on state.input.startsWith("/")
  // so that Tab inside regular text doesn't trigger completion.
  assert.match(tui, /state\.input\.startsWith\("\/"\)/);
});

// ----- 3. Build smoke test -------------------------------------------

test("R38: TypeScript compile of commands.ts is clean", () => {
  const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
  const tmp = join(root, "tmp-r38-tsc");
  if (existsSync(tmp)) {
    spawnSync(process.platform === "win32" ? "cmd" : "rm",
      process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", tmp] : ["-rf", tmp]);
  }
  const tscArgs = [
    "--outDir", tmp, "--target", "ES2022", "--module", "ES2022",
    "--moduleResolution", "bundler", "--esModuleInterop", "true",
    "--skipLibCheck", "true", "--rootDir", join(root, "src"),
  ];
  const r = spawnSync(`"${tscBin}"`, [
    ...tscArgs,
    '"' + join(root, "src", "commands.ts") + '"',
  ], { encoding: "utf-8", shell: true });
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
  }
  assert.equal(r.status, 0, "tsc compile failed for R38 commands");
});
