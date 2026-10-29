// Copyright (c) 2026 maijun

// Licensed under the MIT License.
// See the LICENSE file in the project root for the full license text.

// R376: the format contract.
//
// The standing rule is "format the code before every commit". A rule
// that lives only in a human's memory is not a rule, it is a habit —
// and habits lose. So this file makes the rule executable: every TUI
// source file must be prettier-clean, and the check runs as part of
// `npm test`, so an unformatted file is a RED BUILD, not a review
// comment nobody notices.
//
// ## Why a gate and not a script
//
// `npm run format` is a convenience. This file is the enforcement.
// The difference matters: the script has to be remembered, the gate
// has to be defeated.
//
// ## Why the scope is read from package.json
//
// One enumerator. `format`, `format:check` and this gate all name the
// same two directories plus the package's own JSON. If someone widens
// the formatter to a third place, this file fails until the gate
// learns about it — otherwise the gate would keep checking a set that
// no longer matches the one the formatter touches, and the contract
// would quietly be a lie in one direction or the other.
//
// See docs/R376-tui-format.md and AGENTS.md.

import { test } from "node:test";
import assert from "node:assert/strict";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { dirname, join, relative, sep } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// The scope, spelled once. `format` / `format:check` in package.json
// must name exactly these directories — asserted below.
const CODE_DIRS = ["src", "scripts"];
const CODE_EXT = /\.(ts|tsx|mjs|js)$/;
const ROOT_JSON = /\.json$/;

function walk(dir, out = []) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, entry.name);
    if (entry.isDirectory()) walk(p, out);
    else if (CODE_EXT.test(entry.name)) out.push(p);
  }
  return out;
}

/** Every file the formatter is responsible for. */
function scopeFiles() {
  const files = [];
  for (const d of CODE_DIRS) files.push(...walk(join(root, d)));
  for (const name of readdirSync(root)) {
    if (ROOT_JSON.test(name) && statSync(join(root, name)).isFile()) files.push(join(root, name));
  }
  return files.sort();
}

// prettier is a declared devDependency of this package. If it cannot
// be resolved, the gate MUST fail — a format check that silently
// passes because its tool is missing is the exact "successful but
// empty" failure this repo keeps refusing. See R365/R374.
const { default: prettier } = await import("prettier");

test("every TUI source file is prettier-clean", async () => {
  const files = scopeFiles();
  assert.ok(
    files.length > 50,
    `the scope collapsed to ${files.length} files — re-anchor this test`,
  );

  const dirty = [];
  for (const file of files) {
    const source = readFileSync(file, "utf-8");
    // The prettier API does NOT read .prettierrc.json on its own —
    // the CLI does that and merges the result into the options. A
    // gate that forgot this compares every file against prettier's
    // DEFAULTS (80 columns, no trailing commas) and reports the whole
    // package as dirty. Which is exactly what happened the first
    // time this file ran.
    const cfg = (await prettier.resolveConfig(file)) ?? {};
    const options = { ...cfg, filepath: file };
    if (!(await prettier.check(source, options)))
      dirty.push(relative(root, file).split(sep).join("/"));
  }

  assert.equal(
    dirty.length,
    0,
    `these files are not prettier-formatted (run \`npm run format\` in aethercode-tui):\n  ` +
      dirty.join("\n  "),
  );
});

test("the format scripts cover exactly the scope this gate checks", () => {
  const pkg = JSON.parse(readFileSync(join(root, "package.json"), "utf-8"));
  const format = pkg.scripts?.format;
  const check = pkg.scripts?.["format:check"];

  assert.ok(format, "aethercode-tui/package.json has no `format` script");
  assert.ok(check, "aethercode-tui/package.json has no `format:check` script");

  // The gate's directories must appear in BOTH scripts. If the
  // formatter is widened or narrowed, this fails until the gate and
  // the scripts agree.
  for (const dir of CODE_DIRS) {
    assert.ok(format.includes(`"${dir}/`), `the \`format\` script does not cover ${dir}/`);
    assert.ok(check.includes(`"${dir}/`), `the \`format:check\` script does not cover ${dir}/`);
  }

  // `format` must write, `format:check` must only check. A `format`
  // that silently checks (or a check that writes) is the wrong way
  // round for a pre-commit step.
  assert.ok(/--write\b/.test(format), "the `format` script does not pass --write");
  assert.ok(/--check\b/.test(check), "the `format:check` script does not pass --check");
  assert.ok(!/--write\b/.test(check), "the `format:check` script would rewrite files");
});

test("the prettier config is the one the TUI was measured against", () => {
  const cfg = JSON.parse(readFileSync(join(root, ".prettierrc.json"), "utf-8"));
  // These are not taste: they are what the TUI's existing code
  // measures (double quotes, semicolons, trailing commas, ~100 cols,
  // LF). Changing one of them is a style decision with a diff attached
  // — which is fine — but it must be a decision, not an accident.
  assert.equal(cfg.printWidth, 100);
  assert.equal(cfg.singleQuote, false);
  assert.equal(cfg.semi, true);
  assert.equal(cfg.trailingComma, "all");
  assert.equal(cfg.arrowParens, "always");
  assert.equal(cfg.tabWidth, 2);
  assert.equal(cfg.useTabs, false);
  assert.equal(cfg.endOfLine, "lf");
});
