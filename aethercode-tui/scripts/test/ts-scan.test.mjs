// Copyright (c) 2026 maijun

// Licensed under the MIT License.
// See the LICENSE file in the project root for the full license text.

// R381 — the SCANNER's own test.
//
// ## Why this file exists
//
// `scripts/test/ts-scan.mjs` had no test of its own until R381. It is
// the scanner every source-pin gate leans on: it decides what is CODE
// and what is PROSE, and a gate that reads the wrong thing is not
// failing — it is passing on whitespace.
//
// The bug it had: a `'` with no partner on its own line (an apostrophe
// in JSX text — "the daemon's estimate") was treated as the start of a
// string literal, and the scanner read on until it found the NEXT
// apostrophe. In `src/tui.tsx` that was 47 lines, and the swallowed
// region contained the render of the whole grants panel.
//
// Lesson 1212 — a scanner with no test is a scanner whose bugs are
// found by whichever gate happens to trip over them, which is not a
// plan.
//
// Lesson 1213 — the fix must be proved by a case that would FAIL on
// the old scanner, or the test is decorative.

import { test } from "node:test";
import assert from "node:assert/strict";
import { scan, blankNonCode, matchBrace, matchBracket } from "./ts-scan.mjs";
import { stripComments } from "./strip-comments.mjs";

test("an apostrophe in JSX text does not swallow the lines after it", () => {
  const src = [
    "const a = 1;",
    "const view = (",
    "  <Text dimColor>",
    "    token count is the daemon's estimate (transcript characters ÷ 4), not a tokenizer",
    "    count",
    "  </Text>",
    ");",
    'case "grants": {',
    "  return <GrantsManager grants={grants} />;",
    "}",
  ].join("\n");

  const code = blankNonCode(src);

  // The apostrophe is prose, so everything after it must still be
  // visible. On the OLD scanner this region was blanked out entirely.
  //
  // NOTE: the assertion is about CODE STRUCTURE, not about string
  // contents — `blankNonCode` blanks string LITERALS on purpose, so
  // `case "grants"` loses its `"grants"`. What must survive is the
  // code around it.
  assert.ok(
    code.includes("case"),
    "the scanner swallowed the code after the apostrophe — a gate reading this " +
      "would be reading whitespace",
  );
  assert.ok(code.includes("<GrantsManager"), "the scanner swallowed the JSX after the apostrophe");
  assert.ok(code.includes("return"), "the scanner swallowed the statement after the apostrophe");
});

test("the apostrophe itself is NOT treated as a literal", () => {
  const src = 'const note = "the daemon\'s estimate";';
  const strings = scan(src).strings;
  assert.equal(
    strings.length,
    1,
    `expected one literal, got ${strings.length}: ${JSON.stringify(strings.map((s) => s.value))}`,
  );
  assert.equal(strings[0].quote, '"', "the double-quoted literal is the real one");
  assert.equal(strings[0].value, "the daemon's estimate");
});

test("a real string literal is still blanked", () => {
  const src = 'const s = "a { b } c";\nconst t = 2;';
  const code = blankNonCode(src);
  assert.ok(!code.includes("a { b } c"), "the literal must be blanked");
  assert.ok(code.includes("const t = 2;"), "the code after it must survive");
});

test("a template literal MAY span lines", () => {
  const src = "const s = `line one\nline two`;\nconst t = 3;";
  const code = blankNonCode(src);
  assert.ok(!code.includes("line one"), "the template must be blanked");
  assert.ok(code.includes("const t = 3;"), "the code after it must survive");
});

test("an apostrophe inside a COMMENT is not a string either", () => {
  const src = ["// it's a comment", "const t = 4;"].join("\n");
  const code = blankNonCode(src);
  assert.ok(!code.includes("it's a comment"), "the comment must be blanked");
  assert.ok(code.includes("const t = 4;"), "the code after it must survive");
});

test("an apostrophe in a JSX TEXT does not hide the hint text", () => {
  // The hint lives INSIDE the Text element, and the gates look for it
  // there. Blanking the prose would hide the very thing they hunt.
  const src = ["const view = (", "  <Text dimColor> it's here — Esc: close </Text>", ");"].join(
    "\n",
  );
  const code = blankNonCode(src);
  assert.ok(
    code.includes("it's here — Esc: close"),
    "the prose inside a Text element must stay visible",
  );
});

test("matchBrace still finds the end of a handler", () => {
  const src = [
    "useInput((input, key) => {",
    "  if (key.escape) {",
    "    onClose();",
    "  }",
    "});",
  ].join("\n");
  const at = src.indexOf("{");
  const end = matchBrace(src, at);
  assert.ok(end > 0, "matchBrace must find the handler's end");
  assert.ok(
    src.slice(at, end).includes("onClose"),
    "the matched region must contain the handler body",
  );
});

test("matchBracket still finds the end of a table entry", () => {
  const src = 'const t = ["a", "b"];\nconst u = 2;';
  const at = src.indexOf("[");
  const end = matchBracket(src, at);
  assert.ok(end > 0, "matchBracket must find the entry's end");
  assert.ok(src.slice(at, end).includes('"b"'), "the matched region must contain the entry");
});

test("the scanner agrees with strip-comments.mjs about a quote", () => {
  // The two scanners used to disagree: this one read past the newline,
  // that one stopped at it. A disagreement between two scanners is two
  // truths, and the gates that use them then read different code.
  const src = [
    "const note = 1;",
    "const view = <Text>the daemon's estimate, not a tokenizer</Text>;",
    'case "grants": {',
    "  return <GrantsManager />;",
    "}",
  ].join("\n");
  const mine = blankNonCode(src);
  const theirs = stripComments(src);
  // `blankNonCode` blanks string literals; `stripComments` keeps them.
  // So the two are compared on CODE, which must agree.
  for (const needle of ["case", "<GrantsManager />"]) {
    assert.ok(mine.includes(needle), `this scanner lost ${needle}`);
    assert.ok(theirs.includes(needle), `strip-comments.mjs lost ${needle}`);
  }
  // And the prose between them must survive in both.
  for (const [label, text] of [
    ["blankNonCode", mine],
    ["stripComments", theirs],
  ]) {
    assert.ok(
      text.includes("estimate, not a tokenizer"),
      `${label} lost the prose after the apostrophe`,
    );
  }
});
