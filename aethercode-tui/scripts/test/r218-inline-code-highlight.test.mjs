// R218: TUI inline code highlight.
//
// R215 made inline code a filled pill (backgroundColor).
// R217 added syntax highlighting for 6 block langs (bash /
// json / js / python / java / yaml). The remaining gap:
// *inline* code (`` `xxx` ``) — model output is full of
// short code fragments like `git` / `if x > 0` /
// `npm install` / `function foo()` — and they're still
// single-colour. R218 applies the tokenizer to inline code
// too, with a "universal" tokenizer that doesn't need an
// explicit language (the inline code never carries one).
//
// The tests pin:
//
//   1. `highlightInlineCode` is exported + produces tokens.
//   2. The universal keyword union includes the most common
//      control-flow + declaration keywords across all the
//      supported langs.
//   3. Strings / numbers / operators / keywords are all
//      recognised.
//   4. The renderer in Markdown.tsx uses the new
//      `highlightInlineCode` and wraps the result in a
//      <Text backgroundColor={t.codeBg}> (NOT a <Box>).
//   5. No new dependencies (R216/R217 contract preserved).
//   6. tsc compiles clean.
//   7. Runtime tests for the common inline-code shapes.

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

// ----- 1. highlight.ts shape -------------------------------------------

test("R218: highlight.ts exports highlightInlineCode", () => {
  assert.match(hl, /export function highlightInlineCode/);
});

test("R218: highlightInlineCode uses tokenize() with 4 patterns (string/number/keyword/operator)", () => {
  // The function body should call `tokenize(src, [...])`
  // with a 4-element pattern list. We look for the 4
  // `kind:` markers inside the function body.
  const idx = hl.indexOf("export function highlightInlineCode");
  assert.ok(idx > 0, "could not locate highlightInlineCode");
  const open = hl.indexOf("{", idx);
  assert.ok(open > 0, "could not locate function body");
  // Walk to the matching close.
  let depth = 1;
  let i = open + 1;
  while (i < hl.length && depth > 0) {
    if (hl[i] === "{") depth++;
    else if (hl[i] === "}") depth--;
    i++;
  }
  const body = hl.slice(open, i);
  // The 4 pattern kinds.
  for (const kind of ["string", "number", "keyword", "operator"]) {
    const re = new RegExp(`kind:\\s*["']${kind}["']`);
    assert.match(body, re, `highlightInlineCode body must include ${kind} pattern`);
  }
  // And the function should NOT use `plain` — plain is the
  // fall-through, not an explicit kind.
  assert.ok(
    !body.match(/kind:\s*["']plain["']/),
    "highlightInlineCode must not have an explicit plain kind (tokenize() emits plain fall-through)",
  );
});

// ----- 2. universal keyword union --------------------------------------

test("R218: INLINE_KEYWORDS includes cross-lang control flow", () => {
  // We expect a small UNION of the most common
  // control-flow keywords. Not every keyword from every
  // lang (would over-classify identifiers).
  for (const kw of ["if", "else", "for", "while", "return", "try", "catch"]) {
    assert.ok(
      hl.includes(`"${kw}"`),
      `INLINE_KEYWORDS must include "${kw}"`,
    );
  }
});

test("R218: INLINE_KEYWORDS includes cross-lang declarations", () => {
  for (const kw of ["function", "class", "const", "let", "var", "def", "new"]) {
    assert.ok(
      hl.includes(`"${kw}"`),
      `INLINE_KEYWORDS must include "${kw}"`,
    );
  }
});

test("R218: INLINE_KEYWORDS is word-bounded", () => {
  // The keyword regex must use `\b` so `iffoo` doesn't
  // match `if`. We assert the regex is built with `\b`.
  const idx = hl.indexOf("INLINE_KEYWORDS");
  assert.ok(idx > 0, "could not locate INLINE_KEYWORDS");
  const tail = hl.slice(idx);
  // Find the keyword regex constructor call.
  assert.match(tail, /\\b\(\?:/);
});

test("R218: INLINE_OPERATORS includes common compound forms", () => {
  // Compound operators must be tried first (multi-char
  // before single-char) so `==` doesn't split into two
  // `=` tokens.
  const idx = hl.indexOf("INLINE_OPERATORS");
  assert.ok(tailOr(idx, "==="));
  assert.ok(tailOr(idx, "!=="));
  assert.ok(tailOr(idx, "=="));
  assert.ok(tailOr(idx, "!="));
  assert.ok(tailOr(idx, "&&"));
  assert.ok(tailOr(idx, "||"));
  assert.ok(tailOr(idx, "=>"));
  assert.ok(tailOr(idx, "->"));
});

function tailOr(idx, needle) {
  if (idx < 0) return false;
  return hl.slice(idx).includes(needle);
}

test("R218: operators sorted by length DESC (longest first)", () => {
  // The operator regex must be built with operators
  // sorted by length DESC so multi-char forms match
  // before their single-char prefixes. We assert the
  // sort comparator is `b.length - a.length`.
  const idx = hl.indexOf("buildInlineOperatorRe");
  assert.ok(idx > 0, "could not locate buildInlineOperatorRe");
  const tail = hl.slice(idx);
  assert.match(tail, /\.length\s*-\s*a\.length/);
});

// ----- 3. renderer integration ----------------------------------------

test("R218: Markdown.tsx imports highlightInlineCode", () => {
  assert.match(md, /import\s*\{[^}]*highlightInlineCode[^}]*\}\s*from\s*["']\.\/markdown\/highlight\.js["']/);
});

test("R218: inline code branch calls highlightInlineCode", () => {
  // The inline code branch must call highlightInlineCode
  // on `m[10]`. We look between the inline-code `m[10]`
  // branch and the next branch in renderInline().
  const inlineIdx = md.indexOf("m[10] != null");
  assert.ok(inlineIdx > 0, "could not locate inline code branch");
  // Slice forward and look for highlightInlineCode call.
  const slice = md.slice(inlineIdx, inlineIdx + 2500);
  assert.ok(slice.includes("highlightInlineCode("), "inline code branch must call highlightInlineCode(");
});

test("R218: inline code pill uses <Text backgroundColor={t.codeBg}> (not <Box>)", () => {
  // Ink's <Box> does NOT support backgroundColor — only
  // <Text> does. R218 wraps the pill in <Text> for that
  // reason.
  const slice = md.slice(
    md.indexOf("m[10] != null"),
    md.indexOf("m[10] != null") + 2500,
  );
  // Look for the wrapping element.
  assert.match(slice, /<Text[^>]*backgroundColor=\{t\.codeBg\}/);
  // And ensure no <Box> has backgroundColor (which would
  // be a runtime no-op + TS error in some Ink versions).
  assert.ok(
    !slice.match(/<Box[^>]*backgroundColor=\{t\.codeBg\}/),
    "R218 inline code must use <Text> not <Box> for the bg",
  );
});

test("R218: inline code pill wraps tokens in backgroundColor t.codeBg", () => {
  // Each inner <Text> (per token + the leading + trailing
  // space) must also carry backgroundColor={t.codeBg} so
  // the entire pill shares one continuous bg segment.
  const slice = md.slice(
    md.indexOf("m[10] != null"),
    md.indexOf("m[10] != null") + 2500,
  );
  // We expect at least 2 mentions of backgroundColor
  // (outer Text + one inner).
  const matches = slice.match(/backgroundColor=\{t\.codeBg\}/g) || [];
  assert.ok(
    matches.length >= 2,
    `inline code pill must wrap tokens with backgroundColor (found ${matches.length})`,
  );
});

// ----- 4. no new dependencies -----------------------------------------

test("R218: package.json does NOT add a new highlighter / tokenizer dependency", () => {
  const deps = { ...(pkg.dependencies ?? {}), ...(pkg.devDependencies ?? {}) };
  for (const forbidden of [
    "cli-highlight", "highlight.js", "shiki", "prismjs",
    "lowlight", "micromark", "starry-night", "pygments",
    "highlight", "codejar", "codeflask",
  ]) {
    assert.equal(
      deps[forbidden],
      undefined,
      `R218 keeps the tokenizer dependency-free — ${forbidden} was added`,
    );
  }
});

// ----- 5. tsc smoke test ----------------------------------------------

test("R218: TypeScript compile of highlight.ts + Markdown.tsx + theme.ts is clean", () => {
  const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
  const tmp = join(root, "tmp-r218-tsc");
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
  assert.equal(r.status, 0, "tsc compile failed for R218");
});

// ----- 6. runtime tokenizer tests ------------------------------------

test("R218 (runtime): `if x > 0` → keyword + identifier + operator + number", () => {
  const compiled = join(root, "tmp-r218-tsc", "components", "markdown", "highlight.js");
  if (!existsSync(compiled)) return;
  const require = createRequire(import.meta.url);
  const { highlightInlineCode } = require(compiled);
  const tokens = highlightInlineCode("if x > 0");
  const kinds = new Set(tokens.map((t) => t.kind));
  assert.ok(kinds.has("keyword"), "expected keyword (if) in tokens");
  assert.ok(kinds.has("operator"), "expected operator (>) in tokens");
  assert.ok(kinds.has("number"), "expected number (0) in tokens");
  // The identifier `x` falls through to plain (NOT a kind).
  assert.ok(
    tokens.some((t) => t.kind === "plain" && t.text.includes("x")),
    "expected plain identifier (x) in tokens",
  );
});

test("R218 (runtime): `npm install` → 2 plain identifiers", () => {
  const compiled = join(root, "tmp-r218-tsc", "components", "markdown", "highlight.js");
  if (!existsSync(compiled)) return;
  const require = createRequire(import.meta.url);
  const { highlightInlineCode } = require(compiled);
  const tokens = highlightInlineCode("npm install");
  // No keyword / number / operator in this fragment.
  // Both `npm` and `install` should fall through to plain.
  // (The `install` IS a bash builtin but it's not in the
  // INLINE_KEYWORDS union — by design.)
  for (const t of tokens) {
    assert.ok(["plain", "string"].includes(t.kind), `unexpected kind ${t.kind} for "npm install"`);
  }
});

test("R218 (runtime): `\"hello\"` → single string token", () => {
  const compiled = join(root, "tmp-r218-tsc", "components", "markdown", "highlight.js");
  if (!existsSync(compiled)) return;
  const require = createRequire(import.meta.url);
  const { highlightInlineCode } = require(compiled);
  const tokens = highlightInlineCode('"hello"');
  assert.equal(tokens.length, 1);
  assert.equal(tokens[0].kind, "string");
  assert.equal(tokens[0].text, '"hello"');
});

test("R218 (runtime): `42` → single number token", () => {
  const compiled = join(root, "tmp-r218-tsc", "components", "markdown", "highlight.js");
  if (!existsSync(compiled)) return;
  const require = createRequire(import.meta.url);
  const { highlightInlineCode } = require(compiled);
  const tokens = highlightInlineCode("42");
  assert.equal(tokens.length, 1);
  assert.equal(tokens[0].kind, "number");
  assert.equal(tokens[0].text, "42");
});

test("R218 (runtime): empty input → empty array", () => {
  const compiled = join(root, "tmp-r218-tsc", "components", "markdown", "highlight.js");
  if (!existsSync(compiled)) return;
  const require = createRequire(import.meta.url);
  const { highlightInlineCode } = require(compiled);
  assert.deepEqual(highlightInlineCode(""), []);
});

test("R218 (runtime): `x === 42` → operator + identifier + number", () => {
  const compiled = join(root, "tmp-r218-tsc", "components", "markdown", "highlight.js");
  if (!existsSync(compiled)) return;
  const require = createRequire(import.meta.url);
  const { highlightInlineCode } = require(compiled);
  // R219 evolution: the R218 test used
  // `function foo() { return 42 }` to assert the inline
  // code branches. R219's lang-inference dispatches
  // this to the JS tokenizer, which doesn't classify
  // `(` / `)` as operator (R218's universal fallback did).
  // We replace the input with `x === 42` — a snippet
  // that has unambiguous operator (`===`) + identifier +
  // number tokens under BOTH the R218 universal
  // tokenizer AND R219's JS path. This keeps the test
  // meaningful as a regression guard for the inline
  // code branch without coupling it to a specific
  // lang's operator set.
  const tokens = highlightInlineCode("x === 42");
  const kinds = new Set(tokens.map((t) => t.kind));
  assert.ok(kinds.has("operator"), "expected operator (===) in tokens");
  assert.ok(kinds.has("number"), "expected number (42) in tokens");
  // The identifier `x` falls through to plain.
  assert.ok(
    tokens.some((t) => t.kind === "plain" && t.text.includes("x")),
    "expected plain identifier (x) in tokens",
  );
});
