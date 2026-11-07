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

test("R388h: InputBox is a rounded PILL (the desktop's centre-panel input)", () => {
  const src = readFileSync(join(root, "src", "components", "InputBox.tsx"), "utf-8");
  const body = inputBoxBody(src);
  // R168 removed the box border ("OpenCode style"). R388h put a
  // border BACK — as a `round` pill — because the user asked for the
  // TUI to look like the DESKTOP's centre panel, and there the input
  // is the one framed element on the screen
  // (`.message-input-wrap`, a rounded textarea with a send button
  // anchored bottom-right). A pill is what makes the panel read as a
  // conversation: the transcript is flat, the input is the shape.
  //
  // The pill must be `round` — the OLD input box used `round` too,
  // so a plain `borderStyle=` grep cannot tell them apart; the
  // distinguishing fact is that it EXISTS again, and that it is
  // round rather than `single`.
  assert.match(body, /borderStyle="round"/);
  // …and the send affordance rides on its right edge.
  assert.match(body, /↵/);
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
  //
  // R388j: the text that is submitted is `submitText ?? state.input`.
  // `submitText` carries the CANONICAL command when the autocomplete
  // dropdown just completed one — the store's `state.input` is still
  // the pre-completion value inside the same handler. So the pin
  // follows the INVARIANT (plain Enter submits the current input)
  // rather than the spelling (Lesson 1233).
  assert.match(body, /onSubmit\((text|submitText|state\.input)\)/);
  // The empty-input guard prevents accidental submits.
  assert.match(body, /(text|state\.input)\.length > 0/);
});

test("R168: InputBox handles backspace / delete", () => {
  const src = readFileSync(join(root, "src", "components", "InputBox.tsx"), "utf-8");
  const body = inputBoxBody(src);
  // Backspace / delete trim the last char.
  assert.match(body, /key\.backspace \|\| key\.delete/);
  assert.match(body, /state\.input\.slice\(0, -1\)/);
});

test('R168: InputBox renders multi-line input as flexDirection="column"', () => {
  const src = readFileSync(join(root, "src", "components", "InputBox.tsx"), "utf-8");
  const body = inputBoxBody(src);
  // The pre-R168 input was a single TextInput on a single line.
  // R168 splits the input on \n and lays it out vertically.
  assert.match(body, /state\.input\.split\("\\n"\)/);
  // The text column wraps each line.
  assert.match(body, /lines\.map\(/);
  assert.match(body, /flexDirection="column"/);
});

test("R388h: InputBox uses a left-edge ▌ accent (OpenCode style)", () => {
  const src = readFileSync(join(root, "src", "components", "InputBox.tsx"), "utf-8");
  const body = inputBoxBody(src);
  // The ▌ accent matches the R167 StatusBar's left-edge ribbon.
  assert.match(body, /▌/);
  // R388h: the thin ─ rule that used to sit above the input is
  // GONE — the pill's own border is the separator now. A rule AND a
  // pill would draw two horizontal lines where the user expects one
  // shape. The status bar's own rule is the panel's bottom edge.
  assert.doesNotMatch(
    body,
    /["']─["']\.repeat\(/,
    "the ─ rule is redundant once the input is a bordered pill",
  );
});

test("R168: InputBox shows a placeholder when the input is empty", () => {
  const src = readFileSync(join(root, "src", "components", "InputBox.tsx"), "utf-8");
  const body = inputBoxBody(src);
  // The pre-R168 TextInput had a built-in `placeholder` prop.
  // R168 inlines the placeholder as a `<Text dimColor>` when
  // the input is empty. The hint mentions shift+Enter so the
  // user knows the multi-line shortcut.
  //
  // R381: the hint's TEXT moved into the key registry
  // (`src/componentKeys.ts`) — a hand-written hint is a second copy
  // of what the component binds, and R381 removed every one of them.
  // So the assertion is REDIRECTED to where the text now lives, and
  // the component's own half (it renders the registry's sentence) is
  // checked too. Re-anchoring an assertion to the new location is
  // the point; deleting it would throw away the property.
  assert.match(
    body,
    /proseFor\("components\/InputBox\.tsx",\s*"prompt"\)/,
    "the InputBox must render the registry's placeholder sentence",
  );
  const keys = readFileSync(join(root, "src", "componentKeys.ts"), "utf-8");
  assert.match(
    keys,
    /type a prompt — Enter to send, shift\+Enter for newline/,
    "the registry's placeholder sentence must still mention shift+Enter — the user has to " +
      "know the multi-line shortcut",
  );
});

// ----- 2. Filesystem check ----------------------------------------

test("R168: InputBox.tsx file exists", () => {
  assert.ok(existsSync(join(root, "src", "components", "InputBox.tsx")));
});
