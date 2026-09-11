// T-442: InputBox with fuzzy autocomplete.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// ----- 1. Pure helper tests (T-442) --------------------------------

// Mirror the Autocomplete.tsx helpers.
function lineStart(text, cursorIndex) {
  if (cursorIndex <= 0) return 0;
  const head = text.lastIndexOf("\n", cursorIndex - 1);
  return head < 0 ? 0 : head + 1;
}

function fuzzyMatch(text, prefix) {
  if (!prefix) return true;
  let ti = 0;
  for (let pi = 0; pi < prefix.length; pi++) {
    const c = prefix[pi];
    while (ti < text.length && text[ti] !== c) ti++;
    if (ti >= text.length) return false;
    ti++;
  }
  return true;
}

test("T-442: lineStart returns 0 for an empty input", () => {
  assert.equal(lineStart("", 0), 0);
  assert.equal(lineStart("hello", 0), 0);
});

test("T-442: lineStart handles mid-line cursor", () => {
  assert.equal(lineStart("hello", 3), 0);
  assert.equal(lineStart("hello", 5), 0);
});

test("T-442: lineStart handles multi-line input", () => {
  assert.equal(lineStart("a\nb", 3), 2);
  assert.equal(lineStart("a\nb\nc", 5), 4);
});

test("T-442: fuzzyMatch returns true for empty prefix", () => {
  assert.equal(fuzzyMatch("anything", ""), true);
});

test("T-442: fuzzyMatch is a strict subsequence", () => {
  assert.equal(fuzzyMatch("src/utils.ts", "su"), true);
  assert.equal(fuzzyMatch("src/utils.ts", "us"), true);
  assert.equal(fuzzyMatch("src/utils.ts", "xyz"), false);
  // The first char of the prefix must precede the second.
  assert.equal(fuzzyMatch("us", "su"), false);
  assert.equal(fuzzyMatch("us", "us"), true);
});

// ----- 2. Source-code assertions -----------------------------------

const ac = readFileSync(join(root, "src", "components", "Autocomplete.tsx"), "utf-8");
const ib = readFileSync(join(root, "src", "components", "InputBox.tsx"), "utf-8");
const cmds = readFileSync(join(root, "src", "commands.ts"), "utf-8");

test("T-442: Autocomplete.tsx exports MultiCompletionManager", () => {
  assert.match(ac, /export class MultiCompletionManager/);
  assert.match(ac, /add\(/);
  assert.match(ac, /onTextChanged\(/);
  assert.match(ac, /activeController\(/);
  assert.match(ac, /reset\(/);
});

test("T-442: Autocomplete.tsx exports SlashCommandController", () => {
  assert.match(ac, /export class SlashCommandController/);
  assert.match(ac, /canHandle\(/);
  // The `/` trigger.
  assert.match(ac, /text\[start\]\s*!==?\s*"\/"/);
});

test("T-442: Autocomplete.tsx exports FuzzyFileController", () => {
  assert.match(ac, /export class FuzzyFileController/);
  // The `@` trigger.
  assert.match(ac, /text\[start\]\s*===\s*"@"/);
});

test("T-442: Autocomplete.tsx exports fuzzyMatch helper", () => {
  assert.match(ac, /export function fuzzyMatch\(/);
});

test("T-442: Autocomplete.tsx exports CompletionDropdown component", () => {
  assert.match(ac, /export const CompletionDropdown/);
});

test("T-442: commands.ts exports SLASH_COMMANDS_DETAILED", () => {
  assert.match(cmds, /export const SLASH_COMMANDS_DETAILED/);
  assert.match(cmds, /description:\s*"/);
  // At least 30 entries (the full catalog).
  const matches = cmds.match(/\{\s*name:\s*"/g) || [];
  assert.ok(matches.length >= 30, `expected >= 30 entries, got ${matches.length}`);
});

test("T-442: InputBox.tsx imports the autocomplete helpers", () => {
  assert.match(ib, /import\s*\{[^}]*MultiCompletionManager[^}]*\}\s+from\s+"\.\/Autocomplete\.js"/);
  assert.match(ib, /import\s*\{[^}]*SlashCommandController[^}]*\}\s+from\s+"\.\/Autocomplete\.js"/);
  assert.match(ib, /import\s*\{[^}]*FuzzyFileController[^}]*\}\s+from\s+"\.\/Autocomplete\.js"/);
  assert.match(ib, /import\s*\{[^}]*CompletionDropdown[^}]*\}\s+from\s+"\.\/Autocomplete\.js"/);
});

test("T-442: InputBox.tsx mounts the dropdown", () => {
  assert.match(ib, /<CompletionDropdown\s+suggestions=\{suggestions\}/);
});

test("T-442: InputBox.tsx exposes a fileListSupplier prop", () => {
  assert.match(ib, /fileListSupplier\?:\s*\(\)\s*=>\s*string\[\]/);
});

// ----- 3. tsc compile ----------------------------------------------

test("T-442: TypeScript compile of Autocomplete.tsx is clean", () => {
  const tmp = join(root, "tmp-t442-tsc");
  if (existsSync(tmp)) {
    spawnSync(process.platform === "win32" ? "cmd" : "rm",
      process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", tmp] : ["-rf", tmp]);
  }
  const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
  const tscArgs = [
    "--outDir", tmp, "--target", "ES2022", "--module", "ES2022",
    "--moduleResolution", "bundler", "--jsx", "react",
    "--esModuleInterop", "true", "--skipLibCheck", "true",
    "--strict", "true",
    "--rootDir", join(root, "src"),
  ];
  const r = spawnSync(`"${tscBin}"`, [
    ...tscArgs,
    '"' + join(root, "src", "components", "Autocomplete.tsx") + '"',
    '"' + join(root, "src", "components", "InputBox.tsx") + '"',
  ], { encoding: "utf-8", shell: true });
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
  }
  assert.equal(r.status, 0, "tsc compile failed for T-442 Autocomplete/InputBox");
});
