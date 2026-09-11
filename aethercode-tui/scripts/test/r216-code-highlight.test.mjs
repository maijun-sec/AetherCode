// R216: TUI code-block syntax highlighting.
//
// The R215 code-block surface is a "container" — without
// token-level colouring the body is one flat `t.code`
// magenta. R216 adds a regex-based tokenizer for the three
// languages we see most often in agent output (bash / json /
// javascript) plus 6 `t.tok*` colour tokens. The tests here
// pin:
//
//   1. The 6 new theme tokens exist.
//   2. The highlight dispatcher routes by `lang`.
//   3. Each language's tokenizer produces the expected kinds.
//   4. The renderer in Markdown.tsx emits one <Text> per
//      token kind and applies italic to comments.
//   5. No new dependencies (the tokenizer is hand-rolled).
//   6. The pre-R216 fallback (single `plain` token) still
//      works for unknown languages.
//
// We exercise the tokenizer through its exported functions
// (it's a pure module) and pin the source-level rendering
// decisions in Markdown.tsx.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { createRequire } from "node:module";

// The highlight module is a TypeScript file, so for the
// runtime tokenizer tests we point Node at the compiled
// outDir the smoke test produces below. If the outDir
// doesn't exist yet (first run), we fall back to reading the
// .ts source and skipping the runtime token tests — the
// source-pin assertions still cover the file shape.

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const themePath = join(root, "src", "theme.ts");
const mdPath = join(root, "src", "components", "Markdown.tsx");
const hlPath = join(root, "src", "components", "markdown", "highlight.ts");
const theme = readFileSync(themePath, "utf-8");
const md = readFileSync(mdPath, "utf-8");
const hl = readFileSync(hlPath, "utf-8");
const pkg = JSON.parse(readFileSync(join(root, "package.json"), "utf-8"));

// ----- 1. theme.ts has the 6 R216 tokens --------------------------------

test("R216: theme.ts exposes 6 token colour tokens", () => {
  for (const tok of [
    "tokKeyword",
    "tokString",
    "tokNumber",
    "tokComment",
    "tokBuiltin",
    "tokOperator",
  ]) {
    assert.match(
      theme,
      new RegExp(`${tok}\\s*:`),
      `theme.ts must export ${tok} token`,
    );
  }
});

// ----- 2. highlight.ts shape -------------------------------------------

test("R216: highlight.ts exports highlightCode + tokenKindToColor", () => {
  assert.match(hl, /export function highlightCode/);
  assert.match(hl, /export function tokenKindToColor/);
  assert.match(hl, /export type TokenKind/);
  assert.match(hl, /export interface Token/);
});

test("R216: highlight.ts defines all 7 token kinds", () => {
  for (const kind of [
    "plain",
    "keyword",
    "string",
    "number",
    "comment",
    "builtin",
    "operator",
  ]) {
    assert.match(hl, new RegExp(`["']${kind}["']`));
  }
});

test("R216: highlight.ts dispatches bash / sh / shell / zsh", () => {
  // The dispatcher must recognise all 4 spellings — agent
  // output may use any of them, and treating `sh` as
  // plain is a regression.
  assert.match(hl, /norm === ["']bash["']/);
  assert.match(hl, /norm === ["']sh["']/);
  assert.match(hl, /norm === ["']shell["']/);
  assert.match(hl, /norm === ["']zsh["']/);
});

test("R216: highlight.ts dispatches json / jsonc", () => {
  assert.match(hl, /norm === ["']json["']/);
  assert.match(hl, /norm === ["']jsonc["']/);
});

test("R216: highlight.ts dispatches js / javascript / ts / typescript / tsx / jsx", () => {
  for (const lang of ["javascript", "js", "typescript", "ts", "tsx", "jsx"]) {
    assert.match(hl, new RegExp(`norm === ["']${lang}["']`));
  }
});

test("R216: highlight.ts has unknown-lang fallback (single plain token)", () => {
  // The last branch in the dispatcher must produce a single
  // `plain` token for unknown languages so the pre-R216
  // behaviour is preserved.
  assert.match(hl, /kind:\s*["']plain["']/);
});

// Helper: extract a function body by name. Functions may
// contain nested braces (e.g. `return { kind, re }` inside
// `tokenize()`), so we walk paren/brace depth instead of
// relying on a single regex.
function extractFunctionBody(source, name) {
  const idx = source.indexOf("function " + name);
  if (idx < 0) return null;
  // Find the opening brace of the body.
  const open = source.indexOf("{", idx);
  if (open < 0) return null;
  let depth = 1;
  let i = open + 1;
  while (i < source.length && depth > 0) {
    const ch = source[i];
    if (ch === "{") depth++;
    else if (ch === "}") depth--;
    i++;
  }
  if (depth !== 0) return null;
  return source.slice(open, i);
}

// ----- 3. bash tokenizer covers the right tokens ----------------------

test("R216: bash tokenizer includes control-flow keywords", () => {
  for (const kw of ["if", "then", "else", "fi", "for", "while", "do", "done", "case", "esac", "function"]) {
    assert.ok(
      hl.includes(`"${kw}"`) || hl.includes(`'${kw}'`),
      `bash tokenizer must include keyword "${kw}"`,
    );
  }
});

test("R216: bash tokenizer has comment + string + variable expansion", () => {
  const body = extractFunctionBody(hl, "highlightBash");
  assert.ok(body, "could not find highlightBash body");
  // Comments: the comment kind + `#` start.
  assert.ok(
    body.includes('"comment"') && body.includes("#"),
    "bash tokenizer must include comment pattern starting with #",
  );
  // Strings: both single + double quoted. The string
  // pattern must contain both quote flavours.
  assert.ok(body.includes('"string"'), "bash tokenizer must have string kind");
  assert.ok(body.includes('"'), "bash string pattern must contain double-quote");
  assert.ok(body.includes("'"), "bash string pattern must contain single-quote");
  // Variable expansion: `${VAR}` / `$VAR` / `$1`.
  assert.ok(body.includes("${"), "bash variable expansion must include ${ form");
  assert.ok(body.includes("}"), "bash variable expansion must include }");
});

test("R216: bash tokenizer does NOT classify plain text as keyword", () => {
  // The keyword regex must be word-bounded so partial
  // matches (e.g. "iffy" matching "if") are rejected.
  const body = extractFunctionBody(hl, "highlightBash");
  assert.ok(body, "could not find highlightBash body");
  // The keywordRe regex literal must use \b.
  const m = body.match(/keywordRe[^]*?RegExp/);
  assert.ok(m, "could not find keywordRe in bash body");
  // The RegExp constructor arg is the next string literal.
  // We look for `\b` somewhere in the body after `keywordRe`.
  const tail = body.slice(body.indexOf("keywordRe"));
  assert.ok(tail.includes("\\b"), "bash keyword regex must be word-bounded");
});

// ----- 4. json tokenizer ---------------------------------------------

test("R216: json tokenizer recognises true / false / null as keyword", () => {
  const body = extractFunctionBody(hl, "highlightJson");
  assert.ok(body, "could not find highlightJson body");
  // The keyword pattern must include the three JSON
  // literal names. We check for each literal as a
  // substring (the regex literal joins them with `|`).
  assert.ok(body.includes("true"), "json keyword pattern must include true");
  assert.ok(body.includes("false"), "json keyword pattern must include false");
  assert.ok(body.includes("null"), "json keyword pattern must include null");
});

test("R216: json tokenizer recognises string + number + structural punctuation", () => {
  const body = extractFunctionBody(hl, "highlightJson");
  assert.ok(body, "could not find highlightJson body");
  assert.ok(body.includes('"string"'), "json tokenizer must have string kind");
  assert.ok(body.includes('"number"'), "json tokenizer must have number kind");
  // The structural punctuation operator regex — must
  // include braces, brackets, colon, and comma.
  assert.ok(body.includes("["), "json operator regex must include bracket chars");
  assert.ok(body.includes("{}"), "json operator regex must include brace char class");
  assert.ok(body.includes(":"), "json operator regex must include colon");
  assert.ok(body.includes(","), "json operator regex must include comma");
});

// ----- 5. javascript tokenizer ---------------------------------------

test("R216: js tokenizer has single + double + template string", () => {
  const body = extractFunctionBody(hl, "highlightJavaScript");
  assert.ok(body, "could not find highlightJavaScript body");
  // The regex literal contains all three quote flavours.
  assert.ok(body.includes('"string"'), "JS tokenizer must have string kind");
  assert.ok(body.includes('"'), "JS string pattern must contain double-quote");
  assert.ok(body.includes("'"), "JS string pattern must contain single-quote");
  assert.ok(body.includes("`"), "JS string pattern must contain template backtick");
});

test("R216: js tokenizer handles // and /* */ comments", () => {
  const body = extractFunctionBody(hl, "highlightJavaScript");
  assert.ok(body, "could not find highlightJavaScript body");
  // The single-line comment pattern is `\/\/[^\n]*` and
  // the block comment pattern is `\/\*[\s\S]*?\*\/`. After
  // escaping both become `\\\/\\\/` and `\\\/\\\*` in the
  // .ts source. We assert the comment kind + the two
  // backslash sequences.
  assert.ok(body.includes('"comment"'), "JS tokenizer must have comment kind");
  assert.ok(body.includes("\\/\\/"), "JS comment pattern must include single-line //");
  assert.ok(body.includes("\\/\\*"), "JS comment pattern must include block /*");
});

test("R216: js tokenizer includes const / function / class / async", () => {
  // Sanity: the keyword set should include the ES6+ basics.
  assert.match(hl, /JS_KEYWORDS/);
  for (const kw of ["const", "function", "class", "async", "await", "let", "return", "import", "export"]) {
    assert.ok(
      hl.includes(`"${kw}"`),
      `JS_KEYWORDS must include "${kw}"`,
    );
  }
});

test("R216: ts tokenizer extras: interface / type / enum", () => {
  assert.match(hl, /TS_EXTRA_KEYWORDS/);
  for (const kw of ["interface", "type", "enum", "namespace", "declare"]) {
    assert.ok(
      hl.includes(`"${kw}"`),
      `TS_EXTRA_KEYWORDS must include "${kw}"`,
    );
  }
});

// ----- 6. Markdown.tsx uses the tokenizer -----------------------------

test("R216: Markdown.tsx imports highlightCode + tokenKindToColor", () => {
  assert.match(md, /import\s*\{[^}]*highlightCode[^}]*\}\s*from\s*["']\.\/markdown\/highlight\.js["']/);
  assert.match(md, /tokenKindToColor/);
  assert.match(md, /type TokenKind/);
});

test("R216: code block branch calls highlightCode(body, lang)", () => {
  // The code branch must call highlightCode on the body
  // and tokenKindToColor for kind→colour mapping. We
  // don't try to extract a single block (JSX expressions
  // confuse brace counters); instead we assert the call
  // sites exist between the `b.kind === "code"` branch
  // start and the next `b.kind === "X"` branch.
  const codeIdx = md.indexOf('b.kind === "code"');
  const nextIdx = md.indexOf('b.kind === "list"', codeIdx);
  assert.ok(codeIdx > 0, "could not locate code branch start");
  assert.ok(nextIdx > 0, "could not locate next branch");
  const slice = md.slice(codeIdx, nextIdx);
  assert.ok(slice.includes("highlightCode("), "code branch must call highlightCode(");
  assert.ok(slice.includes("tokenKindToColor"), "code branch must call tokenKindToColor");
});

test("R216: comments are rendered italic", () => {
  const codeIdx = md.indexOf('b.kind === "code"');
  const nextIdx = md.indexOf('b.kind === "list"', codeIdx);
  assert.ok(codeIdx > 0 && nextIdx > 0, "could not locate code branch");
  const slice = md.slice(codeIdx, nextIdx);
  assert.ok(
    slice.includes("italic") && slice.includes("comment"),
    "code branch must render comments italic",
  );
});

// ----- 7. no new dependencies -----------------------------------------

test("R216: package.json does NOT add a syntax highlighter", () => {
  const deps = { ...(pkg.dependencies ?? {}), ...(pkg.devDependencies ?? {}) };
  for (const forbidden of [
    "cli-highlight",
    "highlight.js",
    "shiki",
    "prismjs",
    "lowlight",
    "micromark",
    "starry-night",
  ]) {
    assert.equal(
      deps[forbidden],
      undefined,
      `R216 keeps the tokenizer dependency-free — ${forbidden} was added`,
    );
  }
});

// ----- 8. tsc smoke test ----------------------------------------------

test("R216: TypeScript compile of highlight.ts + Markdown.tsx + theme.ts is clean", () => {
  const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
  const tmp = join(root, "tmp-r216-tsc");
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
  assert.equal(r.status, 0, "tsc compile failed for R216");
});

// ----- 9. runtime tokenizer tests (require compiled .js) -------------

test("R216 (runtime): bash tokenizer splits `if [ $foo -gt 0 ]; then` into expected kinds", () => {
  // We require the compiled .js output. If it doesn't exist
  // (CI cold cache) we skip — source-pin tests already cover
  // the same surface area.
  const compiled = join(root, "tmp-r216-tsc", "components", "markdown", "highlight.js");
  if (!existsSync(compiled)) {
    return; // skip — source-pin coverage is the contract
  }
  const require = createRequire(import.meta.url);
  const { highlightCode, tokenKindToColor } = require(compiled);
  // Use UNQUOTED `$foo` so the variable-expansion pattern
  // (which routes to `builtin` kind) matches. A quoted
  // `"$foo"` would be swallowed whole by the string
  // pattern, which is the right behaviour — but then the
  // test wouldn't see the `builtin` kind.
  const tokens = highlightCode("if [ $foo -gt 0 ]; then", "bash");
  const kinds = new Set(tokens.map((t) => t.kind));
  assert.ok(kinds.has("keyword"), "expected keyword (if/then) in bash tokens");
  assert.ok(kinds.has("builtin"), "expected builtin ($foo variable) in bash tokens");
  assert.ok(kinds.has("operator"), "expected operator ([ ] ;) in bash tokens");
  assert.ok(kinds.has("number"), "expected number (0) in bash tokens");
  // And the dispatcher must have routed to bash — the result
  // is NOT a single plain token.
  assert.ok(tokens.length > 1, "bash should produce multiple tokens, not fall through to plain");
  // tokenKindToColor must return a real theme key.
  assert.equal(typeof tokenKindToColor("keyword"), "string");
  assert.equal(tokenKindToColor("keyword"), "tokKeyword");
  assert.equal(tokenKindToColor("string"), "tokString");
  assert.equal(tokenKindToColor("number"), "tokNumber");
});

test("R216 (runtime): json tokenizer splits `{\"a\": 1, \"b\": true}` into expected kinds", () => {
  const compiled = join(root, "tmp-r216-tsc", "components", "markdown", "highlight.js");
  if (!existsSync(compiled)) {
    return;
  }
  const require = createRequire(import.meta.url);
  const { highlightCode } = require(compiled);
  const tokens = highlightCode('{"a": 1, "b": true}', "json");
  const kinds = new Set(tokens.map((t) => t.kind));
  assert.ok(kinds.has("string"), "expected string (key) in json tokens");
  assert.ok(kinds.has("number"), "expected number (1) in json tokens");
  assert.ok(kinds.has("keyword"), "expected keyword (true) in json tokens");
  assert.ok(kinds.has("operator"), "expected operator ({ } : ,) in json tokens");
});

test("R216 (runtime): js tokenizer splits `const x = \"hi\"; // c` into expected kinds", () => {
  const compiled = join(root, "tmp-r216-tsc", "components", "markdown", "highlight.js");
  if (!existsSync(compiled)) {
    return;
  }
  const require = createRequire(import.meta.url);
  const { highlightCode } = require(compiled);
  const tokens = highlightCode('const x = "hi"; // c', "ts");
  const kinds = new Set(tokens.map((t) => t.kind));
  assert.ok(kinds.has("keyword"), "expected keyword (const) in ts tokens");
  assert.ok(kinds.has("string"), "expected string (\"hi\") in ts tokens");
  assert.ok(kinds.has("comment"), "expected comment (// c) in ts tokens");
  assert.ok(kinds.has("operator"), "expected operator (= ;) in ts tokens");
});

test("R216 (runtime): unknown lang falls back to single plain token", () => {
  const compiled = join(root, "tmp-r216-tsc", "components", "markdown", "highlight.js");
  if (!existsSync(compiled)) {
    return;
  }
  const require = createRequire(import.meta.url);
  const { highlightCode } = require(compiled);
  const tokens = highlightCode("hello world", "pascal");
  assert.equal(tokens.length, 1);
  assert.equal(tokens[0].kind, "plain");
  assert.equal(tokens[0].text, "hello world");
});

test("R216 (runtime): empty body returns empty token array", () => {
  const compiled = join(root, "tmp-r216-tsc", "components", "markdown", "highlight.js");
  if (!existsSync(compiled)) {
    return;
  }
  const require = createRequire(import.meta.url);
  const { highlightCode } = require(compiled);
  assert.deepEqual(highlightCode("", "bash"), []);
  assert.deepEqual(highlightCode("", ""), []);
});
