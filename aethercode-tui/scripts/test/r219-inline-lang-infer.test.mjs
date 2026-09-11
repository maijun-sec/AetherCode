// R219: TUI inline-code language inference.
//
// R218 introduced `highlightInlineCode` with a "universal"
// tokenizer (no lang detection). R219 adds
// `inferInlineLang` — a heuristic that recognises strong-
// shape signals in the inline code text and dispatches
// to the per-lang tokenizer when confident. When no rule
// fires, the R218 universal fallback is preserved.
//
// The tests pin:
//
//   1. `inferInlineLang` is exported.
//   2. Each per-lang rule fires on a representative input.
//   3. Conservative fallback — inputs that DON'T match any
//      rule return `""` (the universal path is taken).
//   4. `highlightInlineCode` uses the inferred lang when
//      available, and the universal fallback otherwise.
//   5. No new dependencies (R216/R217/R218 contract
//      preserved).
//   6. tsc compiles clean.
//   7. Runtime tests for both inferred + universal paths.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { createRequire } from "node:module";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const hlPath = join(root, "src", "components", "markdown", "highlight.ts");
const mdPath = join(root, "src", "components", "Markdown.tsx");
const hl = readFileSync(hlPath, "utf-8");
const md = readFileSync(mdPath, "utf-8");
const pkg = JSON.parse(readFileSync(join(root, "package.json"), "utf-8"));

// ----- 1. inferInlineLang shape ---------------------------------------

test("R219: highlight.ts exports inferInlineLang", () => {
  assert.match(hl, /export function inferInlineLang/);
});

test("R219: inferInlineLang returns empty string on empty input", () => {
  // The function must be defensive — empty input should
  // not crash and should return `""` (universal path).
  // We look for the `if (!src) return ""` guard.
  const idx = hl.indexOf("function inferInlineLang");
  const tail = hl.slice(idx);
  assert.ok(
    /if\s*\(!src\)\s*return\s*["']["']/.test(tail),
    "inferInlineLang must have a `if (!src) return \"\"` guard",
  );
});

// ----- 2. per-lang rules fire on representative input ----------------

test("R219: Java rule — public/private/protected + type", () => {
  // The Java rule uses the literal access-modifier list
  // and the type-keyword list. We just assert the rule
  // pattern pieces are present in the source.
  const idx = hl.indexOf("function inferInlineLang");
  const tail = hl.slice(idx);
  assert.ok(tail.includes("public|private|protected"),
    "Java rule must include access modifier alternation");
  assert.ok(tail.includes("void|int|long|String"),
    "Java rule must include type keyword alternation");
});

test("R219: Java rule — `class Foo {` (brace, not colon)", () => {
  // The rule must explicitly key off the brace, since
  // `class Foo:` would be Python.
  const idx = hl.indexOf("function inferInlineLang");
  const tail = hl.slice(idx);
  assert.ok(
    tail.includes('class\\s+[A-Z]\\w*\\s*\\{'),
    "Java rule must include `class Foo {` (brace) signature",
  );
});

test("R219: Python rule — `def name(`", () => {
  const idx = hl.indexOf("function inferInlineLang");
  const tail = hl.slice(idx);
  assert.ok(tail.includes("def\\s+[A-Za-z_]\\w*\\s*\\("));
});

test("R219: Python rule — `class Foo:` (colon, not brace)", () => {
  const idx = hl.indexOf("function inferInlineLang");
  const tail = hl.slice(idx);
  assert.ok(tail.includes("class\\s+[A-Z]\\w*\\s*:"));
});

test("R219: Python rule — `@decorator`", () => {
  const idx = hl.indexOf("function inferInlineLang");
  const tail = hl.slice(idx);
  assert.ok(tail.includes("@\\w+"));
});

test("R219: Python rule — `self` keyword", () => {
  const idx = hl.indexOf("function inferInlineLang");
  const tail = hl.slice(idx);
  assert.ok(tail.includes("\\bself\\b"));
});

test("R219: JavaScript rule — `=>` arrow", () => {
  const idx = hl.indexOf("function inferInlineLang");
  const tail = hl.slice(idx);
  assert.ok(tail.includes("=>"));
});

test("R219: JavaScript rule — `function name(`", () => {
  const idx = hl.indexOf("function inferInlineLang");
  const tail = hl.slice(idx);
  assert.ok(tail.includes("function\\s+[A-Za-z_]\\w*\\s*\\("));
});

test("R219: JavaScript rule — `const|let|var name =`", () => {
  const idx = hl.indexOf("function inferInlineLang");
  const tail = hl.slice(idx);
  assert.ok(tail.includes("const|let|var"));
  assert.ok(tail.includes("\\b(const|let|var)\\s+[A-Za-z_]\\w*\\s*="));
});

test("R219: Bash rule — `$VAR` / `${VAR}`", () => {
  const idx = hl.indexOf("function inferInlineLang");
  const tail = hl.slice(idx);
  assert.ok(tail.includes("${") && tail.includes("}?"));
});

test("R219: Bash rule — `[ $foo ... ]` test syntax", () => {
  const idx = hl.indexOf("function inferInlineLang");
  const tail = hl.slice(idx);
  assert.ok(tail.includes("[\\s+\\$?\\w+"));
});

test("R219: Bash rule — leading `$` prompt", () => {
  const idx = hl.indexOf("function inferInlineLang");
  const tail = hl.slice(idx);
  assert.ok(tail.includes("^\\s*\\$\\s"));
});

test("R219: Bash rule — common commands", () => {
  // echo / cd / ls / grep / find / etc.
  const idx = hl.indexOf("function inferInlineLang");
  const tail = hl.slice(idx);
  assert.ok(tail.includes("echo|cd|ls|grep|find"));
});

test("R219: JSON rule — `{ \"key\":` or `{ 'key':`", () => {
  const idx = hl.indexOf("function inferInlineLang");
  const tail = hl.slice(idx);
  assert.ok(tail.includes("{.*[\"']\\w+[\"']\\s*:"));
});

test("R219: YAML rule — `key: value` at start", () => {
  const idx = hl.indexOf("function inferInlineLang");
  const tail = hl.slice(idx);
  assert.ok(tail.includes("^[A-Za-z_][\\w-]*\\s*:\\s*\\S+"));
});

// ----- 3. rule ordering — Java > Python > JS > Bash -----------------

test("R219: rule order — Java before Python", () => {
  // The first `return "java"` in `inferInlineLang` must
  // come BEFORE the first `return "python"`. The
  // ordering is important because Java and Python can
  // both match `class Foo` — Java needs the brace
  // (which Python forbids), but we still test the
  // ordering to be sure.
  const fnStart = hl.indexOf("function inferInlineLang");
  const fnEnd = hl.indexOf("\n}", fnStart);
  const body = hl.slice(fnStart, fnEnd);
  const firstJava = body.indexOf('return "java"');
  const firstPy = body.indexOf('return "python"');
  assert.ok(firstJava > 0, "must have java rule");
  assert.ok(firstPy > 0, "must have python rule");
  assert.ok(firstJava < firstPy, "Java rules must be checked before Python rules");
});

test("R219: rule order — Python before JavaScript", () => {
  const fnStart = hl.indexOf("function inferInlineLang");
  const fnEnd = hl.indexOf("\n}", fnStart);
  const body = hl.slice(fnStart, fnEnd);
  const firstPy = body.indexOf('return "python"');
  const firstJs = body.indexOf('return "javascript"');
  assert.ok(firstPy < firstJs, "Python rules must be checked before JavaScript rules");
});

test("R219: rule order — JavaScript before Bash", () => {
  const fnStart = hl.indexOf("function inferInlineLang");
  const fnEnd = hl.indexOf("\n}", fnStart);
  const body = hl.slice(fnStart, fnEnd);
  const firstJs = body.indexOf('return "javascript"');
  const firstBash = body.indexOf('return "bash"');
  assert.ok(firstJs < firstBash, "JavaScript rules must be checked before Bash rules");
});

// ----- 4. highlightInlineCode uses inferInlineLang -------------------

test("R219: highlightInlineCode calls inferInlineLang + dispatches to highlightCode", () => {
  // The function must call inferInlineLang first, and
  // route to highlightCode(src, lang) when a lang is
  // returned.
  const hlIdx = hl.indexOf("export function highlightInlineCode");
  assert.ok(hlIdx > 0, "could not locate highlightInlineCode");
  const open = hl.indexOf("{", hlIdx);
  let depth = 1;
  let i = open + 1;
  while (i < hl.length && depth > 0) {
    if (hl[i] === "{") depth++;
    else if (hl[i] === "}") depth--;
    i++;
  }
  const body = hl.slice(open, i);
  assert.ok(body.includes("inferInlineLang"), "highlightInlineCode must call inferInlineLang");
  assert.ok(body.includes("highlightCode("), "highlightInlineCode must call highlightCode when lang inferred");
});

test("R219: highlightInlineCode falls back to tokenize() when no lang inferred", () => {
  // The R218 universal fallback must still be present
  // — the function calls `tokenize(src, [...])` when
  // inferInlineLang returns "".
  const hlIdx = hl.indexOf("export function highlightInlineCode");
  const open = hl.indexOf("{", hlIdx);
  let depth = 1;
  let i = open + 1;
  while (i < hl.length && depth > 0) {
    if (hl[i] === "{") depth++;
    else if (hl[i] === "}") depth--;
    i++;
  }
  const body = hl.slice(open, i);
  assert.ok(body.includes("tokenize("), "highlightInlineCode must call tokenize() for universal fallback");
});

// ----- 5. no new dependencies ----------------------------------------

test("R219: package.json does NOT add a new highlighter / inference dependency", () => {
  const deps = { ...(pkg.dependencies ?? {}), ...(pkg.devDependencies ?? {}) };
  for (const forbidden of [
    "cli-highlight", "highlight.js", "shiki", "prismjs",
    "lowlight", "micromark", "starry-night", "pygments",
    "highlight", "codejar", "codeflask", "refractor",
  ]) {
    assert.equal(
      deps[forbidden],
      undefined,
      `R219 keeps the tokenizer dependency-free — ${forbidden} was added`,
    );
  }
});

// ----- 6. Markdown.tsx unchanged (R218 already wired up) ------------

test("R219: Markdown.tsx still imports highlightInlineCode (R218 wiring intact)", () => {
  // R219 doesn't change the renderer — it only changes
  // the function the renderer calls. The import line
  // must still be present.
  assert.match(md, /import\s*\{[^}]*highlightInlineCode[^}]*\}\s*from\s*["']\.\/markdown\/highlight\.js["']/);
});

// ----- 7. tsc smoke test --------------------------------------------

test("R219: TypeScript compile of highlight.ts + Markdown.tsx + theme.ts is clean", () => {
  const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
  const tmp = join(root, "tmp-r219-tsc");
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
    '"' + join(root, "src", "components", "Markdown.tsx") + '"',
    '"' + join(root, "src", "components", "markdown", "highlight.ts") + '"',
    '"' + join(root, "src", "theme.ts") + '"',
  ], { encoding: "utf-8", shell: true });
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
  }
  assert.equal(r.status, 0, "tsc compile failed for R219");
});

// ----- 8. runtime tests ---------------------------------------------

test("R219 (runtime): `def foo(x: int) -> str: return str(x)` → python tokens", () => {
  // The input is a Python signature — should be inferred
  // as Python and dispatched to highlightPython.
  // Python tokens: `def` / `return` (keyword) + `str`
  // (builtin) + `->` (operator) + `int` (plain).
  const compiled = join(root, "tmp-r219-tsc", "components", "markdown", "highlight.js");
  if (!existsSync(compiled)) return;
  const require = createRequire(import.meta.url);
  const { highlightInlineCode, inferInlineLang } = require(compiled);
  // The inference function should report "python".
  assert.equal(inferInlineLang("def foo(x: int) -> str: return str(x)"), "python");
  // The full pipeline must produce a `builtin` token
  // (`str`) which is unique to the Python tokenizer.
  const tokens = highlightInlineCode("def foo(x: int) -> str: return str(x)");
  const kinds = new Set(tokens.map((t) => t.kind));
  assert.ok(kinds.has("keyword"), "expected keyword (def/return) in python tokens");
  assert.ok(kinds.has("builtin"), "expected builtin (str) — only python tokenizer emits this");
  assert.ok(kinds.has("operator"), "expected operator (->) in python tokens");
});

test("R219 (runtime): `class Foo { void run() {} }` → java tokens", () => {
  // Java signature: `class Foo {` is the Java inference
  // signal (brace, not colon).
  const compiled = join(root, "tmp-r219-tsc", "components", "markdown", "highlight.js");
  if (!existsSync(compiled)) return;
  const require = createRequire(import.meta.url);
  const { highlightInlineCode, inferInlineLang } = require(compiled);
  assert.equal(inferInlineLang("class Foo { void run() {} }"), "java");
  const tokens = highlightInlineCode("class Foo { void run() {} }");
  const kinds = new Set(tokens.map((t) => t.kind));
  assert.ok(kinds.has("keyword"), "expected keyword (class/void) in java tokens");
});

test("R219 (runtime): `const x = arr => arr.map(n => n * 2)` → javascript tokens", () => {
  // JS arrow function — the `=>` is the JS inference
  // signal.
  const compiled = join(root, "tmp-r219-tsc", "components", "markdown", "highlight.js");
  if (!existsSync(compiled)) return;
  const require = createRequire(import.meta.url);
  const { highlightInlineCode, inferInlineLang } = require(compiled);
  assert.equal(
    inferInlineLang("const x = arr => arr.map(n => n * 2)"),
    "javascript",
  );
  const tokens = highlightInlineCode("const x = 42");
  const kinds = new Set(tokens.map((t) => t.kind));
  assert.ok(kinds.has("keyword"), "expected keyword (const) in js tokens");
  assert.ok(kinds.has("number"), "expected number (42) in js tokens");
});

test("R219 (runtime): `if [ $foo -gt 0 ]; then` → bash tokens", () => {
  // Bash test syntax `[ $foo -gt 0 ]` — the `[$foo` is
  // the bash inference signal.
  const compiled = join(root, "tmp-r219-tsc", "components", "markdown", "highlight.js");
  if (!existsSync(compiled)) return;
  const require = createRequire(import.meta.url);
  const { highlightInlineCode, inferInlineLang } = require(compiled);
  assert.equal(inferInlineLang("if [ $foo -gt 0 ]; then"), "bash");
  const tokens = highlightInlineCode("if [ $foo -gt 0 ]; then");
  const kinds = new Set(tokens.map((t) => t.kind));
  assert.ok(kinds.has("keyword"), "expected keyword (if/then) in bash tokens");
  assert.ok(kinds.has("builtin"), "expected builtin ($foo) in bash tokens");
  assert.ok(kinds.has("operator"), "expected operator ([, -gt, ;, ]) in bash tokens");
});

test("R219 (runtime): `{\"name\": \"alice\"}` → json tokens", () => {
  const compiled = join(root, "tmp-r219-tsc", "components", "markdown", "highlight.js");
  if (!existsSync(compiled)) return;
  const require = createRequire(import.meta.url);
  const { inferInlineLang } = require(compiled);
  assert.equal(inferInlineLang('{"name": "alice"}'), "json");
});

test("R219 (runtime): `name: alice\\nage: 30` → yaml tokens", () => {
  // Note: this is multi-line, but our R219 inference
  // only looks at single-line shape. The first line
  // `name: alice` matches the YAML rule. The multi-line
  // test for the YAML tokenizer itself is in R217.
  const compiled = join(root, "tmp-r219-tsc", "components", "markdown", "highlight.js");
  if (!existsSync(compiled)) return;
  const require = createRequire(import.meta.url);
  const { inferInlineLang } = require(compiled);
  assert.equal(inferInlineLang("name: alice\nage: 30"), "yaml");
});

test("R219 (runtime): `x === 42` — operator + number (lang may or may not infer)", () => {
  const compiled = join(root, "tmp-r219-tsc", "components", "markdown", "highlight.js");
  if (!existsSync(compiled)) return;
  const require = createRequire(import.meta.url);
  const { highlightInlineCode } = require(compiled);
  // The pipeline must produce an operator + number
  // regardless of whether the lang is inferred (R218
  // universal also emits `===` as operator).
  const tokens = highlightInlineCode("x === 42");
  const kinds = new Set(tokens.map((t) => t.kind));
  assert.ok(kinds.has("operator"), "expected operator (===) in tokens");
  assert.ok(kinds.has("number"), "expected number (42) in tokens");
});

test("R219 (runtime): `hello world` — no rule fires, universal fallback", () => {
  // Plain text shouldn't match any inference rule.
  const compiled = join(root, "tmp-r219-tsc", "components", "markdown", "highlight.js");
  if (!existsSync(compiled)) return;
  const require = createRequire(import.meta.url);
  const { highlightInlineCode, inferInlineLang } = require(compiled);
  assert.equal(inferInlineLang("hello world"), "");
  // The pipeline should still return tokens (universal
  // fallback emits plain tokens).
  const tokens = highlightInlineCode("hello world");
  assert.ok(tokens.length > 0, "universal fallback should still produce tokens");
});

test("R219 (runtime): `git status` — bash command fallback", () => {
  // `git` isn't in the bash command list, but `status`
  // isn't either. So no rule fires — universal fallback.
  // This is a "negative" test that documents what the
  // R219 inference does NOT do (yet).
  const compiled = join(root, "tmp-r219-tsc", "components", "markdown", "highlight.js");
  if (!existsSync(compiled)) return;
  const require = createRequire(import.meta.url);
  const { inferInlineLang } = require(compiled);
  assert.equal(inferInlineLang("git status"), "");
});
