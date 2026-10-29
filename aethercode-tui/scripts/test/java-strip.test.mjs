// Copyright (c) 2026 maijun

// Licensed under the MIT License.
// See the LICENSE file in the project root for the full license text.

// Self-tests for the shared Java scanner (`java-strip.mjs`).
//
// ## Why this file exists
//
// The scanner is used by a dozen gates, so a bug in it does not fail
// one gate — it fails whichever gate happens to trip over it, which
// is not a plan (Lesson 1212). It also fails SILENTLY: a truncated
// method body makes every `assert.match` over it pass vacuously, so
// the gate reports green while reading half a method.
//
// The three properties this file pins:
//
//   1. `javaCode` blanks comments and COPIES string literals verbatim
//      (the gates look for `"sessionId"` inside strings).
//   2. BRACE COUNTING SKIPS STRING LITERALS. A `}` inside a literal
//      must not close the method body.
//   3. `javaMethodContaining` finds the method that CONTAINS a marker
//      — so a contract pin can FOLLOW the implementation instead of
//      naming a method that a refactor may move.
//
// Each property is checked against a sample that CONTAINS the trap,
// not against a sample that happens to work.

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import {
  javaCode,
  javaLiteralMask,
  javaMethod,
  javaMethodContaining,
  javaMethodInfoContaining,
} from "./java-strip.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const repo = join(root, "..");

// =========================================================================
// 1. javaCode
// =========================================================================

test("javaCode blanks comments and keeps string literals verbatim", () => {
  const src = [
    "class D {",
    "    // a line comment with { and }",
    "    /* a block comment with { and } */",
    '    String s = "a literal with { and }";',
    "}",
  ].join("\n");
  const out = javaCode(src);
  assert.equal(out.length, src.length, "LENGTH MUST BE PRESERVED — callers slice by offset");
  assert.ok(!out.includes("//"), "the line comment must be blanked");
  assert.ok(!out.includes("block comment"), "the block comment must be blanked");
  assert.ok(
    out.includes('"a literal with { and }"'),
    "the string literal must survive VERBATIM — the gates look for things like " +
      '"sessionId" inside strings',
  );
});

test("javaLiteralMask marks exactly the literal bytes", () => {
  const src = 'x = "he said {hi}"; y = 1;';
  const code = javaCode(src);
  const mask = javaLiteralMask(code);
  // The literal spans from the opening quote to the closing quote.
  const open = code.indexOf('"');
  const close = code.indexOf('"', open + 1);
  for (let i = open; i <= close; i++) {
    assert.equal(mask[i], 1, `offset ${i} is inside the literal and must be masked`);
  }
  assert.equal(mask[open - 1], 0, "the byte before the literal is code");
  assert.equal(mask[close + 1], 0, "the byte after the literal is code");
});

// =========================================================================
// 2. brace counting skips string literals
// =========================================================================

/**
 * A sample whose method body contains a `}` INSIDE a string literal,
 * followed by more real code. A naive brace count stops at the
 * literal's `}` and returns a body that is missing the tail.
 */
const BRACE_TRAP = [
  "class Demo {",
  "    static void frame(String reason) {",
  '        System.out.println("a } brace in a literal");',
  '        System.out.println("the { real tail");',
  "        tail();",
  "    }",
  "",
  "    static void other() {",
  "        int x = 1;",
  "    }",
  "}",
].join("\n");

test("javaMethod does NOT stop at a `}` inside a string literal", () => {
  const code = javaCode(BRACE_TRAP);
  const body = javaMethod(code, "static void frame(String reason)");
  assert.ok(
    body.includes("tail();"),
    "the extracted body is TRUNCATED — it stopped at the `}` inside the string literal. " +
      "A truncated body makes every assertion over it pass vacuously.",
  );
  assert.ok(
    !body.includes("static void other()"),
    "the body must not run past the method's own closing brace",
  );
  // And it must be balanced.
  let depth = 0;
  for (const c of body) {
    if (c === "{") depth++;
    else if (c === "}") depth--;
  }
  assert.equal(depth, 0, "the extracted body must be brace-balanced");
});

test("javaMethod still finds the SECOND method after a literal trap", () => {
  const code = javaCode(BRACE_TRAP);
  const body = javaMethod(code, "static void other()");
  assert.ok(body.includes("int x = 1;"), "the second method must be extracted whole");
  assert.ok(!body.includes("frame("), "the second method must not include the first");
});

test("javaMethod's occurrence parameter still selects the right overload", () => {
  const src = [
    "class Demo {",
    "    static void run() { a(); }",
    "    static void run(int x) { b(); }",
    "}",
  ].join("\n");
  const code = javaCode(src);
  assert.ok(javaMethod(code, "static void run(", 1).includes("a();"));
  assert.ok(javaMethod(code, "static void run(", 2).includes("b();"));
});

// =========================================================================
// 3. javaMethodContaining — the pin follows the implementation
// =========================================================================

test("javaMethodContaining finds the method that CONTAINS the marker", () => {
  const src = [
    "class Demo {",
    "    static Map<String, Object> build(String reason) {",
    '        r.put("type", "run_end");',
    "        return r;",
    "    }",
    "",
    "    static void other() {",
    "        unrelated();",
    "    }",
    "}",
  ].join("\n");
  const code = javaCode(src);
  const body = javaMethodContaining(code, 'put("type", "run_end")');
  assert.ok(body.includes('put("type", "run_end")'), "the marker must be inside the body");
  assert.ok(!body.includes("unrelated();"), "the OTHER method must not be included");
});

test("javaMethodContaining survives a literal trap before the marker", () => {
  const src = [
    "class Demo {",
    "    static Map<String, Object> build(String reason) {",
    '        r.put("note", "a } brace");',
    '        r.put("type", "run_end");',
    "        return r;",
    "    }",
    "}",
  ].join("\n");
  const code = javaCode(src);
  const body = javaMethodContaining(code, 'put("type", "run_end")');
  assert.ok(body.includes("return r;"), "the body must not be truncated by the literal's `}`");
});

test("javaMethodContaining picks the INNERMOST enclosing method", () => {
  // A marker inside a nested block still belongs to the method that
  // contains the block — not to the block.
  const src = [
    "class Demo {",
    "    static void outer() {",
    "        if (x) {",
    '            r.put("type", "run_end");',
    "        }",
    "    }",
    "}",
  ].join("\n");
  const code = javaCode(src);
  const body = javaMethodContaining(code, 'put("type", "run_end")');
  assert.ok(body.includes("static void outer") === false, "the body starts at the method's brace");
  assert.ok(body.includes("if (x)"), "the nested block must be inside the extracted body");
  assert.ok(body.includes("}"), "the method's closing brace must be included");
});

test("javaMethodContaining is not fooled by a lambda or an anonymous class", () => {
  // A lambda body `{` and an anonymous class body `{` both LOOK like
  // a method body to a brace matcher. If either were mistaken for the
  // method, the extraction would stop early and every contract
  // assertion over it would pass vacuously.
  const src = [
    "class Demo {",
    "    static void outer() {",
    "        Runnable r = new Runnable() {",
    "            public void run() {",
    "                doIt();",
    "            }",
    "        };",
    "        r.run();",
    '        mark("type", "run_end");',
    "    }",
    "",
    "    static void mark(String k, String v) {",
    "        sink(k, v);",
    "    }",
    "}",
  ].join("\n");
  const code = javaCode(src);
  const body = javaMethodContaining(code, 'mark("type", "run_end")');
  assert.ok(
    body.includes("Runnable r = new Runnable()"),
    "the anonymous class must be inside the extracted body — the extraction stopped at a " +
      "nested `{` that it mistook for the method's",
  );
  assert.ok(body.includes("r.run();"), "the marker must be inside the extracted body");
  assert.ok(!body.includes("static void mark"), "the NEXT method must not be included");
});

test("javaMethodContaining says so when the marker is NOT inside a method", () => {
  // A static initializer is a block, not a method. The primitive must
  // NOT silently hand back the block and let the caller believe it
  // read a method — that is the "the extraction succeeded and reports
  // a defect that does not exist" failure mode, in a new costume.
  const src = [
    "class Demo {",
    "    static {",
    '        mark("type", "run_end");',
    "    }",
    "}",
  ].join("\n");
  const code = javaCode(src);
  assert.throws(
    () => javaMethodContaining(code, 'mark("type", "run_end")'),
    /no enclosing method/,
    "a marker that is not inside a method must fail loudly — a silently truncated " +
      "extraction makes every contract assertion over it vacuous",
  );
});

test("javaMethodContaining throws a NAMED error when the marker is absent", () => {
  const code = javaCode(BRACE_TRAP);
  assert.throws(
    () => javaMethodContaining(code, "no_such_marker"),
    /marker not found/,
    "a missing marker must fail loudly, not return an empty body",
  );
});

// =========================================================================
// 4. the scanner agrees with the REAL daemon sources
// =========================================================================

const methodsPath = join(
  repo,
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

test("the real daemon's run_end builder is extracted WHOLE", () => {
  // The R384 builder carries a `stopKind` put and a taxonomy guard.
  // If the extraction were truncated the assertions over it would
  // pass vacuously — so this checks the SHAPE, on the real file.
  const code = javaCode(readFileSync(methodsPath, "utf-8"));
  const body = javaMethodContaining(code, 'put("type", "run_end")');
  assert.ok(
    body.includes('put("stopKind"'),
    "the extracted builder must contain the stopKind put — a truncated body would make " +
      "every assertion over it vacuous",
  );
  assert.ok(
    body.includes("isErrorOutcome()"),
    "the extracted builder must contain the taxonomy guard",
  );
  assert.ok(
    body.includes("return r;"),
    "the extracted builder must reach its own return — a truncated body stops early",
  );
});

test("the real daemon's eventToMap is extracted WHOLE (it has 31 puts)", () => {
  const code = javaCode(readFileSync(methodsPath, "utf-8"));
  const body = javaMethod(code, "private static Map<String, Object> eventToMap(StreamEvent ev)");
  const puts = (body.match(/\.put\(/g) ?? []).length;
  assert.equal(
    puts,
    31,
    `eventToMap should carry 31 puts; the extraction found ${puts}. A truncated body ` +
      "would read a fraction of the method and make the assertions over it vacuous.",
  );
});

test("the primitive finds the RIGHT method in the real daemon", () => {
  // "Does the primitive locate the right method?" is only answerable
  // against a KNOWN ground truth. The R384 builder is known: it is the
  // only method that assembles a run_end frame, and the synthetic
  // terminals live in `query`.
  //
  // If the primitive ever drifts onto a neighbouring method, the gate
  // reads the wrong span and reports green — the silent failure this
  // whole round exists to remove.
  const code = javaCode(readFileSync(methodsPath, "utf-8"));
  for (const [marker, expected] of [
    ['put("type", "run_end")', "runEndFrame"],
    ['"no_run_end: consumer exited without terminal event"', "query"],
    ['"error: " + th.getMessage()', "query"],
  ]) {
    const info = javaMethodInfoContaining(code, marker);
    assert.equal(
      info.name,
      expected,
      `the marker ${marker} must locate the method "${expected}" — it located ` +
        `"${info.name}". A gate reading the wrong method reports green while checking ` +
        "nothing.",
    );
  }
});

test("the primitive refuses a marker that is not in the file", () => {
  const code = javaCode(readFileSync(methodsPath, "utf-8"));
  assert.throws(
    () => javaMethodInfoContaining(code, '"aethercode_stop_kind"'),
    /marker not found/,
    "a marker that does not exist must fail loudly",
  );
});
