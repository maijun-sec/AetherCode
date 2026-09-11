// R215: TUI markdown 漂亮化 — source-pin tests.
//
// The user said: "tui 现在渲染 markdown 吗？我们希望 tui 显示非常漂亮，方便查看"
// R215 polishes the existing state-machine renderer without
// pulling in a new dependency. These tests pin the visual
// decisions so a future cleanup doesn't accidentally roll
// them back.
//
// The "prettiness" surface area is:
//
//   1. Heading levels (H1-H6) have level-specific colour +
//      decoration tokens.
//   2. Inline code has a filled-pill background.
//   3. Code blocks have a "lang" tag + brand-tinted border.
//   4. Blockquote has a heavy `┃` left bar in cyan.
//   5. Horizontal rule is brand-coloured + heavy box-drawing.
//   6. Tables have brand-coloured header + heavy `━` separator
//      + bold `┃` column dividers.
//   7. Lists have colour-coded markers (brand bullet / accent
//      number).
//
// We assert each of these in source so a regression in the
// renderer is caught even if the runtime output isn't
// inspected.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const mdPath = join(root, "src", "components", "Markdown.tsx");
const themePath = join(root, "src", "theme.ts");
const md = readFileSync(mdPath, "utf-8");
const theme = readFileSync(themePath, "utf-8");

// ----- 1. theme.ts has the R215 tokens ---------------------------------

test("R215: theme.ts exposes heading tokens (heading1..heading6)", () => {
  for (let n = 1; n <= 6; n++) {
    assert.match(
      theme,
      new RegExp(`heading${n}\\s*:`),
      `theme.ts should export heading${n} token`,
    );
  }
});

test("R215: theme.ts exposes code background + border tokens", () => {
  assert.match(theme, /codeBg\s*:/);
  assert.match(theme, /codeBlockBg\s*:/);
  assert.match(theme, /codeBorder\s*:/);
});

test("R215: theme.ts exposes rule + table + quote tokens", () => {
  assert.match(theme, /rule\s*:/);
  assert.match(theme, /tableHeader\s*:/);
  assert.match(theme, /tableSep\s*:/);
  assert.match(theme, /quoteBar\s*:/);
  assert.match(theme, /quoteText\s*:/);
});

// ----- 2. heading colour / decoration ---------------------------------

test("R215: Markdown.tsx has a headingColor() helper that returns per-level colour", () => {
  assert.match(md, /function headingColor/);
  // The helper must dispatch on level and return a token.
  assert.match(md, /case 1:\s*return t\.heading1/);
  assert.match(md, /case 2:\s*return t\.heading2/);
  assert.match(md, /case 3:\s*return t\.heading3/);
});

test("R215: H1 + H2 get decorative underlines via headingUnderline()", () => {
  assert.match(md, /function headingUnderline/);
  // H1 should be the heaviest `═` character; H2 a `─` line.
  assert.match(md, /level === 1\)\s*return\s*["']═["']/);
  assert.match(md, /level === 2\)\s*return\s*["']─["']/);
});

test("R215: H1 heading gets a `▌` brand bar prefix", () => {
  // The pre-R215 renderer rendered all headings as `prefix + body`
  // with no leading bar. R215 adds a small `▌` brand bar so the
  // user can pick the H1 out at a glance.
  assert.match(md, /level === 1\s*\?\s*<Text[^>]*>▌/);
});

// ----- 3. inline code pill --------------------------------------------

test("R215: inline code uses a filled backgroundColor (pill)", () => {
  // The renderInline() branch for ``code`` (group 10) must
  // emit a <Text> with both color and backgroundColor. We
  // search the whole file for the signature rather than
  // slicing the function (TSX <Text> attributes have
  // irregular whitespace that defeats fragile regex).
  //
  // R218 evolution: the inline code renderer is no longer
  // a single `<Text color backgroundColor>{` ${m[10]} `}</Text>`
  // — R218 tokenises the body and wraps the result in an
  // outer <Text> with the pill bg. The test must therefore
  // look for the new shape (outer <Text backgroundColor={t.codeBg}>)
  // rather than the obsolete template-string form.
  assert.match(md, /m\[10\]/);
  assert.match(md, /backgroundColor=\{t\.codeBg\}/);
  // R218: the pill is built from an outer <Text> wrapper +
  // a series of inner <Text color={...} backgroundColor={t.codeBg}>
  // tokens. We assert the outer <Text backgroundColor={t.codeBg}>
  // exists (i.e. the pill bg is still applied) and the
  // `m[10]` reference still drives the content.
  assert.match(md, /<Text[^>]*backgroundColor=\{t\.codeBg\}/);
});

// ----- 4. code block with lang tag ------------------------------------

test("R215: code block emits a `lang` filled pill when a language is set", () => {
  // The code branch must wrap the language in a backgroundColor
  // pill using t.brand. We look for the whole pattern as a
  // substring — the JSX in between is whitespace-sensitive.
  assert.match(md, /backgroundColor=\{t\.brand\}/);
  // The lang pill text should reference `lang` in its body.
  assert.match(md, /color="black"[\s\S]{0,80}?backgroundColor=\{t\.brand\}/);
});

test("R215: code block uses the dim purple border token (not t.dim)", () => {
  // Pre-R215 used t.dim which made code blocks blend with
  // chat borders. R215 uses t.codeBorder so they stand out.
  // We assert the literal token usage in the file.
  assert.match(md, /borderColor=\{t\.codeBorder\}/);
});

// ----- 5. blockquote heavy bar ---------------------------------------

test("R215: blockquote uses a heavy `┃` left bar in quote colour", () => {
  // The quote branch must reference the heavy bar character
  // and the brand colour tokens.
  assert.match(md, /t\.quoteBar/);
  assert.match(md, /t\.quoteText/);
  // The pre-R215 renderer used `│` (thin). The R215 renderer
  // uses `┃` (heavy). The file must contain `┃` as a literal.
  assert.ok(md.includes("┃"), "Markdown.tsx must contain heavy ┃ bar");
});

// ----- 6. horizontal rule — brand + heavy ----------------------------

test("R215: horizontal rule is brand-coloured + heavy `━`", () => {
  assert.match(md, /t\.rule/);
  // Heavy box-drawing character. The R215 rule uses `━.repeat(40)`.
  assert.match(md, /["']━["']\.repeat\(40\)/);
});

// ----- 7. table — brand header + heavy `━` separator -----------------

test("R215: table header row is brand-coloured + bold", () => {
  // Inside renderTable, the cells branch must use t.tableHeader
  // for the header row and bold for emphasis.
  assert.match(md, /isHeader\s*\?\s*t\.tableHeader/);
  assert.match(md, /bold=\{isHeader\}/);
});

test("R215: table uses heavy `━` separator + bold `┃` column dividers", () => {
  // Column dividers: bold `┃` in t.tableSep.
  assert.match(md, /t\.tableSep/);
  // The pre-R215 separator was `─` (thin). The R215 separator
  // is `━` (heavy).
  assert.match(md, /"━"\.repeat\(/);
  // The pre-R215 column divider was `│` (thin) with `│ `.
  // The R215 column divider is `┃` (heavy) with ` ┃ `.
  assert.ok(md.includes("┃"), "Markdown.tsx must contain heavy ┃ bar");
});

// ----- 8. list — colour-coded markers --------------------------------

test("R215: bullet list uses brand-coloured `▸` marker", () => {
  // The bullet marker is `▸` (heavy triangle) in t.brand.
  assert.match(md, /color=\{t\.brand\}[\s\S]{0,80}?>\{["']\s*▸\s*["']/);
});

test("R215: ordered list uses accent-coloured `(1)` style number", () => {
  // The number marker uses t.accent + padStart(2, " ").
  assert.match(md, /color=\{t\.accent\}/);
  // Padded number — padStart(2, " ").
  assert.match(md, /padStart\(2, ["'] ["']\)/);
});

// ----- 9. parser still parses (smoke) ---------------------------------

test("R215: parser still recognises every block kind from R31+R36", () => {
  // The R36 block kinds (table/rule/quote) must remain. If
  // R215 inadvertently drops one, this fires.
  for (const kind of ["table", "rule", "quote", "code", "heading", "list", "para"]) {
    assert.match(md, new RegExp(`kind:\\s*["']${kind}["']`));
  }
});

// ----- 10. no new dependencies ----------------------------------------

test("R215: package.json does NOT add a new markdown library (react-markdown / marked / remark)", () => {
  const pkg = JSON.parse(readFileSync(join(root, "package.json"), "utf-8"));
  const deps = { ...(pkg.dependencies ?? {}), ...(pkg.devDependencies ?? {}) };
  for (const forbidden of ["react-markdown", "remark-gfm", "marked", "markdown-it", "micromark"]) {
    assert.equal(
      deps[forbidden],
      undefined,
      `R215 must keep the lightweight parser — ${forbidden} was added`,
    );
  }
});

// ----- 11. tsc smoke test ---------------------------------------------

test("R215: TypeScript compile of Markdown.tsx + theme.ts is clean", () => {
  const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
  const tmp = join(root, "tmp-r215-tsc");
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
    '"' + join(root, "src", "theme.ts") + '"',
  ], { encoding: "utf-8", shell: true });
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
  }
  assert.equal(r.status, 0, "tsc compile failed for R215 Markdown + theme");
});
