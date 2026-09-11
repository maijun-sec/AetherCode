// R168: tests for the multi-line input box.
//
// The user asked: "tui 需要参考 opencode, 不输于 opencode".
// R168 makes the prompt input multi-line (shift+Enter inserts
// a newline, plain Enter submits) and strips the box border
// to match the OpenCode visual language. The pre-R168
// TextInput-based input was strictly single-line — pasting a
// multi-line block of code was impossible.
//
// We test by source-code assertion: useInput hook is wired,
// borderStyle is gone, the input is rendered as multiple
// lines via flexDirection="column".

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// ----- 1. Source-code assertions: InputBox.tsx ---------------------

function inputBoxBody(src) {
  const start = src.indexOf("export const InputBox");
  if (start < 0) throw new Error("InputBox component not found");
  const fnStart = src.indexOf("=> {", start);
  if (fnStart < 0) throw new Error("arrow function start not found");
  const fnEnd = src.indexOf("\n};", fnStart);
  if (fnEnd < 0) throw new Error("function end not found");
  return src.slice(fnStart, fnEnd);
}

test("R168: InputBox no longer uses borderStyle (no box border)", () => {
  const src = readFileSync(join(root, "src", "components", "InputBox.tsx"), "utf-8");
  // R168 strips the box border around the input, matching
  // the R167 StatusBar treatment. The pre-R168 implementation
  // rendered the input inside a <Box borderStyle="round"
  // borderColor={...}> ... </Box>.
  const body = inputBoxBody(src);
  assert.doesNotMatch(body, /borderStyle=/,
    "InputBox body still uses borderStyle — R168 should remove it");
});

test("R168: InputBox no longer uses ink-text-input (replaced with useInput)", () => {
  const src = readFileSync(join(root, "src", "components", "InputBox.tsx"), "utf-8");
  // R168 replaces the single-line TextInput with the
  // lower-level useInput hook so we can distinguish
  // shift+Enter (newline) from Enter (submit). The
  // ink-text-input import is gone.
  assert.doesNotMatch(src, /import\s+TextInput\s+from\s+["']ink-text-input["']/);
});

test("R168: InputBox uses useInput hook", () => {
  const src = readFileSync(join(root, "src", "components", "InputBox.tsx"), "utf-8");
  // The useInput hook is what makes shift+Enter possible.
  assert.match(src, /import\s+\{\s*[^}]*\buseInput\b[^}]*\}\s+from\s+["']ink["']/);
  const body = inputBoxBody(src);
  assert.match(body, /useInput\(/);
});

test("R168: InputBox handles shift+Enter by inserting a newline", () => {
  const src = readFileSync(join(root, "src", "components", "InputBox.tsx"), "utf-8");
  const body = inputBoxBody(src);
  // The useInput callback checks key.shift + key.return
  // to insert a newline instead of submitting.
  assert.match(body, /key\.shift/);
  assert.match(body, /key\.return/);
  assert.match(body, /onChange\(state\.input \+ "\\n"\)/);
});

test("R168: InputBox handles plain Enter by submitting", () => {
  const src = readFileSync(join(root, "src", "components", "InputBox.tsx"), "utf-8");
  const body = inputBoxBody(src);
  // Plain Enter (no shift) calls onSubmit, but only if the
  // input is non-empty. Empty Enter is a no-op.
  assert.match(body, /onSubmit\(state\.input\)/);
  // The empty-input guard prevents accidental submits.
  assert.match(body, /state\.input\.length > 0/);
});

test("R168: InputBox handles backspace / delete", () => {
  const src = readFileSync(join(root, "src", "components", "InputBox.tsx"), "utf-8");
  const body = inputBoxBody(src);
  // Backspace / delete trim the last char.
  assert.match(body, /key\.backspace \|\| key\.delete/);
  assert.match(body, /state\.input\.slice\(0, -1\)/);
});

test("R168: InputBox renders multi-line input as flexDirection=\"column\"", () => {
  const src = readFileSync(join(root, "src", "components", "InputBox.tsx"), "utf-8");
  const body = inputBoxBody(src);
  // The pre-R168 input was a single TextInput on a single line.
  // R168 splits the input on \n and lays it out vertically.
  assert.match(body, /state\.input\.split\("\\n"\)/);
  // The text column wraps each line.
  assert.match(body, /lines\.map\(/);
  assert.match(body, /flexDirection="column"/);
});

test("R168: InputBox uses a left-edge ▌ accent + thin ─ rule (OpenCode style)", () => {
  const src = readFileSync(join(root, "src", "components", "InputBox.tsx"), "utf-8");
  const body = inputBoxBody(src);
  // The ▌ accent matches the R167 StatusBar's left-edge ribbon.
  assert.match(body, /▌/);
  // The horizontal rule above the input matches R167's StatusBar.
  assert.match(body, /["']─["']\.repeat\(/);
});

test("R168: InputBox shows a placeholder when the input is empty", () => {
  const src = readFileSync(join(root, "src", "components", "InputBox.tsx"), "utf-8");
  const body = inputBoxBody(src);
  // The pre-R168 TextInput had a built-in `placeholder` prop.
  // R168 inlines the placeholder as a `<Text dimColor>` when
  // the input is empty. The hint mentions shift+Enter so the
  // user knows the multi-line shortcut.
  assert.match(body, /type a prompt — Enter to send, shift\+Enter for newline/);
});

// ----- 2. Filesystem check ----------------------------------------

test("R168: InputBox.tsx file exists", () => {
  assert.ok(existsSync(join(root, "src", "components", "InputBox.tsx")));
});
