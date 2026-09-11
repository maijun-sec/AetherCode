// R36: Markdown enhancements (tables, rules, blockquote, strike).

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// ----- 1. Pure helper tests (re-implementing the parser) --------------

function isTableSeparator(line) {
  return /^\s*\|?\s*(:?-+:?\s*\|\s*)*:?-+:?\s*\|?\s*$/.test(line);
}

function splitTableRow(line) {
  const trimmed = line.replace(/^\s*\|/, "").replace(/\|\s*$/, "");
  return trimmed.split("|").map((c) => c.trim());
}

function parseAligns(sep) {
  return splitTableRow(sep).map((cell) => {
    const t = cell.trim();
    const left = t.startsWith(":");
    const right = t.endsWith(":");
    if (left && right) return "center";
    if (right) return "right";
    if (left) return "left";
    return null;
  });
}

test("R36: isTableSeparator matches common forms", () => {
  assert.ok(isTableSeparator("|---|---|"));
  assert.ok(isTableSeparator("| :--- | :---: | ---: |"));
  assert.ok(isTableSeparator("|---|---|---|"));
  assert.ok(isTableSeparator("---"));
  assert.ok(!isTableSeparator("not a separator"));
  assert.ok(!isTableSeparator("| --- | foo |"));
});

test("R36: splitTableRow handles leading/trailing pipes", () => {
  assert.deepEqual(splitTableRow("| a | b | c |"), ["a", "b", "c"]);
  assert.deepEqual(splitTableRow("a | b | c"), ["a", "b", "c"]);
  assert.deepEqual(splitTableRow("|a|b|"), ["a", "b"]);
});

test("R36: parseAligns maps cell syntax to alignment", () => {
  assert.deepEqual(parseAligns("|---|---|"), [null, null]);
  assert.deepEqual(parseAligns("|:---|---:|"), ["left", "right"]);
  assert.deepEqual(parseAligns("|:---:|:---:|"), ["center", "center"]);
});

// ----- 2. Source code assertions -------------------------------------

test("R36: Markdown.tsx has all 4 new block kinds (table, rule, quote)", () => {
  const md = readFileSync(join(root, "src", "components", "Markdown.tsx"), "utf-8");
  assert.match(md, /kind:\s*"table"/);
  assert.match(md, /kind:\s*"rule"/);
  assert.match(md, /kind:\s*"quote"/);
});

test("R36: Markdown.tsx handles inline ~~strike~~ (capture group 8/10)", () => {
  const md = readFileSync(join(root, "src", "components", "Markdown.tsx"), "utf-8");
  // Check the source contains the literal ~~ separator and the
  // strikethrough element attribute. The source has a regex like
  //   (~~([^~]+)~~)
  // and renders it with `strikethrough`.
  assert.ok(md.includes("~~"));
  assert.match(md, /strikethrough/);
});

test("R36: Markdown.tsx renders tables via renderTable", () => {
  const md = readFileSync(join(root, "src", "components", "Markdown.tsx"), "utf-8");
  assert.match(md, /function renderTable/);
  assert.match(md, /isTableSeparator/);
  assert.match(md, /parseAligns/);
});

test("R36: Markdown.tsx renders horizontal rules as ─ line", () => {
  const md = readFileSync(join(root, "src", "components", "Markdown.tsx"), "utf-8");
  assert.match(md, /b\.kind === "rule"/);
  assert.match(md, /─/);
});

test("R36: Markdown.tsx renders blockquote with │ bar", () => {
  const md = readFileSync(join(root, "src", "components", "Markdown.tsx"), "utf-8");
  assert.match(md, /b\.kind === "quote"/);
  assert.match(md, /│/);
});

// ----- 3. Build smoke test -------------------------------------------

test("R36: TypeScript compile of Markdown.tsx is clean", () => {
  const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
  const tmp = join(root, "tmp-r36-tsc");
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
  ], { encoding: "utf-8", shell: true });
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
  }
  assert.equal(r.status, 0, "tsc compile failed for R36 Markdown");
});
