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

// R362 鈥?/help is derived, so it cannot drift from the autocomplete catalog.
//
// Before R362, `SLASH_HELP` was a hand-maintained 88-line string literal that
// had drifted from `SLASH_COMMANDS` (76). `/help` advertised commands the
// Tab-completion could not reach, and its descriptions carried development
// notes ("T-080:", "瀵瑰簲鍘嗗彶 round:", "R700:") that shipped straight to the
// user. The two lists disagreed and nothing caught it.
//
// R362 makes `SLASH_HELP` a pure function of:
//   * SLASH_COMMANDS_DETAILED  (the autocomplete catalog 鈥?single source of truth)
//   * SLASH_COMMAND_GROUPS      (group -> ordered names, display order only)
//
// R380 removed the second list. The group is now a field ON the catalog
// entry, and SLASH_COMMANDS (the flat array the Tab completion and the
// command palette read) is DERIVED from the catalog too — so there is
// one list, and the three surfaces (Tab completion, the InputBox
// dropdown, the command palette) cannot disagree.
//
// The tests below were RE-AIMED at the new shape, not deleted: the
// properties they protected (the help is derived, every command is
// findable, the aliases are honest) are unchanged.
//
// These tests pin that derivation, because the value of the change is entirely
// in "it cannot drift again", not in today's particular /help text.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdirSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { codeOf } from "./strip-comments.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const tmp = join(root, "tmp-r362-help");

const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
if (existsSync(tmp)) {
  if (process.platform === "win32") spawnSync("cmd", ["/c", "rmdir", "/s", "/q", tmp]);
  else spawnSync("rm", ["-rf", tmp]);
}
mkdirSync(tmp, { recursive: true });

const r = spawnSync(
  `"${tscBin}"`,
  [
    "--outDir",
    join(tmp, "out"),
    "--target",
    "ES2022",
    "--module",
    "ES2022",
    "--moduleResolution",
    "bundler",
    "--jsx",
    "react",
    "--esModuleInterop",
    "true",
    "--skipLibCheck",
    "true",
    "--rootDir",
    join(root, "src"),
    `"${join(root, "src", "commands.ts")}"`,
  ],
  { encoding: "utf-8", shell: true },
);
if (r.status !== 0) {
  console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
  process.exit(1);
}

const mod = await import(pathToFileURL(join(tmp, "out", "commands.js")).href);
const { SLASH_HELP, SLASH_COMMANDS, SLASH_COMMANDS_DETAILED } = mod;
const src = readFileSync(join(root, "src", "commands.ts"), "utf-8");
/** The catalog entries, as the compiled module sees them.
 *
 *  R380: the group table is gone, so "which commands are grouped" is
 *  now "every entry that is not an alias" — the group travels with the
 *  entry, and an alias has no row of its own.
 */
function rowEntries() {
  return SLASH_COMMANDS_DETAILED.filter((c) => !c.aliasOf);
}

test("SLASH_HELP is derived, not a hand-written literal", () => {
  // The regression guard for the whole change. If someone re-inlines a
  // hand-written array, this fails and points at the drift that caused it.
  assert.ok(
    src.includes("function renderSlashHelp"),
    "commands.ts must render /help from the catalog via renderSlashHelp()",
  );
  // R380: the group table is GONE. The renderer walks the catalog and
  // reads each entry's own `group`, so there is no second list that
  // could drift from the first.
  //
  // The check is on the CODE, not the raw file: the history of what the
  // table used to be lives in a comment, and a comment is not a second
  // list (Lesson: comments do not count as code).
  assert.doesNotMatch(
    codeOf(src, "commands.ts"),
    /SLASH_COMMAND_GROUPS/,
    "the group table must not come back — the group is a field on the entry",
  );
  assert.match(
    src,
    /for \(const c of SLASH_COMMANDS_DETAILED\)/,
    "the renderer must walk the catalog — that is the derivation",
  );
  assert.doesNotMatch(
    src.slice(src.indexOf("export const SLASH_HELP")),
    /SLASH_HELP\s*=\s*\[/,
    "SLASH_HELP must be assigned from the renderer, not a literal array",
  );
});

test("the renderer is declared AFTER the catalog it reads", () => {
  // `const SLASH_HELP = renderSlashHelp()` runs at module-eval time. If the
  // assignment precedes SLASH_COMMANDS_DETAILED, Node throws
  // `ReferenceError: Cannot access 'SLASH_COMMANDS_DETAILED' before
  // initialization` at runtime 鈥?and `tsc` does NOT catch it (types are
  // order-independent). Assert the order so a future move is caught here
  // rather than by a user staring at a blank screen.
  const det = src.indexOf("export const SLASH_COMMANDS_DETAILED");
  const help = src.indexOf("export const SLASH_HELP = renderSlashHelp()");
  assert.ok(det > 0 && help > 0, "both declarations must exist");
  assert.ok(
    det < help,
    "renderSlashHelp must be DEFINED and CALLED after SLASH_COMMANDS_DETAILED " +
      "(TDZ crash otherwise; tsc will not warn you)",
  );
});

test("every catalog entry carries a group, and no group is split", () => {
  // R380: the group is a field on the entry, so "a command with no
  // group" is now a TYPE error rather than a silent omission — but a
  // group that appears in two separate runs would render TWO sections
  // with the same header, and the user's scan would read as a mistake.
  // The catalog order is the display order, so a group must be one
  // contiguous run.
  const groups = rowEntries().map((c) => c.group);
  const seen = new Set();
  let previous = null;
  for (const g of groups) {
    if (g === previous) continue;
    assert.ok(
      !seen.has(g),
      `the group "${g}" appears in two separate runs of the catalog — /help would print ` +
        "the same header twice",
    );
    seen.add(g);
    previous = g;
  }
  assert.ok(seen.size >= 4, `only ${seen.size} groups in the catalog — re-anchor this test`);
});

test("every command renders a non-empty row in /help", () => {
  // The property this file has always protected: a command the user can
  // type must be FINDABLE in /help. R380: the group travels with the
  // entry, so this is now a total check over the catalog rather than
  // over a group table — a command cannot slip through ungrouped.
  for (const c of rowEntries()) {
    assert.ok(
      SLASH_HELP.includes(`/${c.name}`),
      `/help does not render /${c.name} even though it is in the catalog`,
    );
  }
});

test("/help carries no development notes", () => {
  // This is the user-facing copy. Round ids and T-numbers are engineering
  // records and belong in comments or in a registry `gapNote`, never here.
  for (const pat of [/R\d{3}/, /\bT-\d/, /鍘嗗彶/, /round:/i, /瀵瑰簲鍘嗗彶/]) {
    assert.doesNotMatch(SLASH_HELP, pat, `/help leaks a development note matching ${pat}`);
  }
});

test("catalog descriptions carry no development notes", () => {
  const dirty = SLASH_COMMANDS_DETAILED.filter((c) =>
    /\bR\d{3}\b|\bT-\d+|瀵瑰簲鍘嗗彶|round:/i.test(c.description),
  );
  assert.deepEqual(
    dirty.map((c) => c.name),
    [],
    "autocomplete descriptions leak development notes into the Tab dropdown",
  );
});

test("argument hints render in /help", () => {
  assert.match(SLASH_HELP, /\/skip <N\|off>/);
  assert.match(SLASH_HELP, /\/session <id>/);
  assert.match(SLASH_HELP, /\/bank-recall <kind> \[n\]/);
});

test("help groups are labelled and separated", () => {
  assert.match(SLASH_HELP, /^# session$/m);
  assert.match(SLASH_HELP, /^# context$/m);
  assert.match(SLASH_HELP, /^# config$/m);
  // descriptions are column-aligned within a group
  const rows = SLASH_HELP.split("\n").filter((l) => l.startsWith("/"));
  assert.ok(rows.length > 40, `expected a full help listing, got ${rows.length} rows`);
});

test("aliases are mentioned so /help stays honest", () => {
  // `/quit`, `/q`, `/?`, `/grants` are real commands the user can type but
  // are not separate catalog rows. Mentioning them in the primary's
  // description keeps /help complete without inflating the row count.
  //
  // R380: the mention is DERIVED from the alias's `aliasOf` field, so
  // the alias set is stated once (in the catalog) and the help text
  // follows. A hand-written "(alias: /?)" in a description would be a
  // second copy of the same fact.
  for (const a of ["/quit", "/q", "/grants"]) {
    assert.ok(SLASH_HELP.includes(a), `/help does not mention the ${a} alias`);
  }
  // And the source must NOT carry the mention as well — that would be
  // the second copy, and the two would drift (the renderer would
  // append a second mention to the hand-written one).
  assert.doesNotMatch(
    src,
    /description: "[^"]*\(alias(?:es)?: \//,
    "the alias mention must be derived from aliasOf, not hand-written in the description",
  );
  // Every alias in the catalog points at a command that exists, and
  // that command's row mentions it.
  for (const c of SLASH_COMMANDS_DETAILED) {
    if (!c.aliasOf) continue;
    assert.ok(
      SLASH_COMMANDS_DETAILED.some((p) => p.name === c.aliasOf),
      `the alias /${c.name} points at /${c.aliasOf}, which is not in the catalog`,
    );
    assert.ok(
      SLASH_HELP.includes(`/${c.aliasOf}`),
      `the alias /${c.name} points at /${c.aliasOf}, which has no row in /help`,
    );
  }
});

test("regression: the R362 rewrite kept every command that used to be listed", () => {
  // The old hand-written SLASH_HELP advertised these. Dropping any of them
  // is a user-visible loss even if they now live in a different group.
  for (const c of [
    "/help",
    "/clear",
    "/exit",
    "/tools",
    "/model",
    "/mode",
    "/sessions",
    "/stats",
    "/history",
    "/memory",
    "/memory-edit",
    "/memory-compact",
    "/agent-pick",
    "/effort-pick",
    "/cwd-pick",
    "/mcp",
    "/mcp-login",
    "/mcp-reconnect",
    "/threads",
    "/update",
    "/update-deps",
    "/notifications",
    "/continue",
    "/pause",
    "/stop",
    "/todos",
    "/tokens",
    "/consents",
    "/grants",
    "/workflow",
    "/skip",
    "/tool-actions",
    "/bank-stats",
    "/bank-recall",
    "/memory-audit",
    "/sdd",
  ]) {
    assert.ok(SLASH_HELP.includes(c), `/help lost the ${c} entry in the R362 rewrite`);
  }
});

test("SLASH_COMMANDS (the flat array) is unchanged in size", () => {
  // Sanity that the R362 edit did not disturb the legacy completion array
  // other tests depend on. 76 as of the R362 baseline, 77 as of R371
  // (which added `/context` — the command the context-meter placeholder
  // had been advertising without one since R362).
  //
  // R380: 87. The flat array is DERIVED from the catalog now, and the
  // catalog grew by ten — the router had ten case arms the catalog
  // never knew about, and "the router's surface must be declared" is
  // now a gate. Two of them are user-facing commands that /help never
  // mentioned (`/effort` — R377's feature — and the permission
  // shortcut); the other eight are aliases (`/resume`, `/kill`,
  // `/todo`, `/ctx`, `/token`, `/no-confirm`, `/noconfirm`,
  // `/yes-do-it`).
  //
  // The number moved on purpose. The "did not drift" property is now
  // structural, not a count: SLASH_COMMANDS is the catalog's
  // projection, so the two cannot disagree.
  //
  // R388: 88. The catalogue gained `/phase-budget` — the phase cap that
  // `/budget`'s description used to advertise while the only code that
  // could reach the daemon's `setPhaseBudget` sat behind a DUPLICATE
  // `case` label and was therefore dead code. Giving it its own name
  // made an already-registered daemon capability reachable, so the
  // count went up by one on purpose.
  assert.equal(
    SLASH_COMMANDS.length,
    88,
    "SLASH_COMMANDS length changed — update this baseline deliberately, not by accident",
  );
  assert.ok(
    SLASH_COMMANDS.includes("context"),
    "R371 added /context; the completion array must know about it or it " +
      "is unreachable by tab-completion",
  );
});
