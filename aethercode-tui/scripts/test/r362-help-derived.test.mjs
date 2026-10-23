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
// These tests pin that derivation, because the value of the change is entirely
// in "it cannot drift again", not in today's particular /help text.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdirSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const tmp = join(root, "tmp-r362-help");

const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
if (existsSync(tmp)) {
  if (process.platform === "win32") spawnSync("cmd", ["/c", "rmdir", "/s", "/q", tmp]);
  else spawnSync("rm", ["-rf", tmp]);
}
mkdirSync(tmp, { recursive: true });

const r = spawnSync(`"${tscBin}"`, [
  "--outDir", join(tmp, "out"), "--target", "ES2022", "--module", "ES2022",
  "--moduleResolution", "bundler", "--jsx", "react",
  "--esModuleInterop", "true", "--skipLibCheck", "true",
  "--rootDir", join(root, "src"),
  `"${join(root, "src", "commands.ts")}"`,
], { encoding: "utf-8", shell: true });
if (r.status !== 0) {
  console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
  process.exit(1);
}

const mod = await import(pathToFileURL(join(tmp, "out", "commands.js")).href);
const { SLASH_HELP, SLASH_COMMANDS, SLASH_COMMANDS_DETAILED } = mod;
const src = readFileSync(join(root, "src", "commands.ts"), "utf-8");
/** Pull ONLY the command names out of the group table.
 *
 *  The table shape is `["<group name>", ["cmd", "cmd", ...]],` so a naive
 *  /"([a-z0-9-]+)"/g also captures the group names ("session", "context",
 *  ...) and the test then reports those as commands missing from the
 *  catalog. Capture the inner array of each tuple instead.
 */
function extractGroupedCommands(groupsSrc) {
  const out = [];
  for (const m of groupsSrc.matchAll(/"[^"]*"\s*,\s*\[([^\]]*)\]/g)) {
    for (const n of m[1].matchAll(/"([^"]+)"/g)) out.push(n[1]);
  }
  return out;
}

test("SLASH_HELP is derived, not a hand-written literal", () => {
  // The regression guard for the whole change. If someone re-inlines a
  // hand-written array, this fails and points at the drift that caused it.
  assert.ok(src.includes("function renderSlashHelp"),
    "commands.ts must render /help from the catalog via renderSlashHelp()");
  assert.ok(src.includes("const SLASH_COMMAND_GROUPS"),
    "commands.ts must declare the grouping table");
  assert.doesNotMatch(src.slice(src.indexOf("export const SLASH_HELP")),
    /SLASH_HELP\s*=\s*\[/,
    "SLASH_HELP must be assigned from the renderer, not a literal array");
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
  assert.ok(det < help,
    "renderSlashHelp must be DEFINED and CALLED after SLASH_COMMANDS_DETAILED " +
    "(TDZ crash otherwise; tsc will not warn you)");
});

test("every grouped command exists in the catalog", () => {
  // A group row naming a command that is not in the catalog means the two
  // lists drifted again. renderSlashHelp silently skips those today; this
  // test makes the skip impossible to miss.
  const groupsSrc = src.slice(
    src.indexOf("const SLASH_COMMAND_GROUPS"),
    src.indexOf("];", src.indexOf("const SLASH_COMMAND_GROUPS")),
  );
  const grouped = extractGroupedCommands(groupsSrc);
  const catalogNames = new Set(SLASH_COMMANDS_DETAILED.map((c) => c.name));
  const orphans = grouped.filter((n) => !catalogNames.has(n));
  assert.deepEqual(orphans, [],
    `group table names commands missing from SLASH_COMMANDS_DETAILED: ${orphans.join(", ")}`);
});

test("every grouped command renders a non-empty row in /help", () => {
  const groupsSrc = src.slice(
    src.indexOf("const SLASH_COMMAND_GROUPS"),
    src.indexOf("];", src.indexOf("const SLASH_COMMAND_GROUPS")),
  );
  const grouped = extractGroupedCommands(groupsSrc);
  for (const name of grouped) {
    assert.ok(SLASH_HELP.includes(`/${name}`),
      `/help does not render /${name} even though it is in the group table`);
  }
});

test("/help carries no development notes", () => {
  // This is the user-facing copy. Round ids and T-numbers are engineering
  // records and belong in comments or in a registry `gapNote`, never here.
  for (const pat of [/R\d{3}/, /\bT-\d/, /鍘嗗彶/, /round:/i, /瀵瑰簲鍘嗗彶/]) {
    assert.doesNotMatch(SLASH_HELP, pat,
      `/help leaks a development note matching ${pat}`);
  }
});

test("catalog descriptions carry no development notes", () => {
  const dirty = SLASH_COMMANDS_DETAILED.filter((c) =>
    /\bR\d{3}\b|\bT-\d+|瀵瑰簲鍘嗗彶|round:/i.test(c.description));
  assert.deepEqual(dirty.map((c) => c.name), [],
    "autocomplete descriptions leak development notes into the Tab dropdown");
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
  for (const a of ["/quit", "/q", "/grants"]) {
    assert.ok(SLASH_HELP.includes(a), `/help does not mention the ${a} alias`);
  }
});

test("regression: the R362 rewrite kept every command that used to be listed", () => {
  // The old hand-written SLASH_HELP advertised these. Dropping any of them
  // is a user-visible loss even if they now live in a different group.
  for (const c of [
    "/help", "/clear", "/exit", "/tools", "/model", "/mode", "/sessions",
    "/stats", "/history", "/memory", "/memory-edit", "/memory-compact",
    "/agent-pick", "/effort-pick", "/cwd-pick", "/mcp", "/mcp-login",
    "/mcp-reconnect", "/threads", "/update", "/update-deps",
    "/notifications", "/continue", "/pause", "/stop", "/todos", "/tokens",
    "/consents", "/grants", "/workflow", "/skip", "/tool-actions",
    "/bank-stats", "/bank-recall", "/memory-audit", "/sdd",
  ]) {
    assert.ok(SLASH_HELP.includes(c), `/help lost the ${c} entry in the R362 rewrite`);
  }
});

test("SLASH_COMMANDS (the flat array) is unchanged in size", () => {
  // Sanity that the R362 edit did not disturb the legacy completion array
  // other tests depend on. 76 as of the R362 baseline, 77 as of R371
  // (which added `/context` — the command the context-meter placeholder
  // had been advertising without one since R362).
  assert.equal(SLASH_COMMANDS.length, 77,
    "SLASH_COMMANDS length changed — update this baseline deliberately, not by accident");
  assert.ok(SLASH_COMMANDS.includes("context"),
    "R371 added /context; the completion array must know about it or it " +
    "is unreachable by tab-completion");
});