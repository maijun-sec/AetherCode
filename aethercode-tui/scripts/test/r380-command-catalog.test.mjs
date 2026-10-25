// R380 — the command surface has ONE list.
//
// ## What this round is about
//
// Before R380 there were THREE lists of the same command set:
//
//   1. SLASH_COMMANDS          — a flat array of 77 strings
//   2. SLASH_COMMANDS_DETAILED — the catalog (name, description, args)
//   3. SLASH_COMMAND_GROUPS    — group -> ordered names, for /help
//
// and a FOURTH list, the router's own `case` arms, which held ten
// commands the catalog had never heard of.
//
// They had already drifted, in ways a user could feel:
//
//   - `/demo` was in the catalog but NOT in the flat array, so it was
//     neither Tab-completable nor in the command palette;
//   - `budget` appeared TWICE in the flat array;
//   - `/effort` — R377's headline feature — was routable but absent
//     from `/help` and from the completion list;
//   - the permission shortcut (`/no-confirm` and friends) called
//     itself "discoverable in one word" in a comment while being
//     visible nowhere.
//
// The comments describing the structure were wrong too: "source of
// truth for autocomplete is SLASH_COMMANDS_DETAILED" (it was
// SLASH_COMMANDS) and "mirrors the entries in SLASH_COMMANDS" (it did
// not).
//
// ## The shape now
//
// The catalog is the only list. Each entry carries its `group` and, for
// an alias, the command it stands for (`aliasOf`). The flat array and
// the /help grouping are DERIVED. The router's surface is a SUBSET of
// the catalog, declared there.
//
// ## The checks that matter
//
// The two that would have caught the drift:
//
//   - every `case` in the router is declared in the catalog (a command
//     the user can type is a command the catalog knows);
//   - every catalog entry is routable (a command /help advertises is a
//     command that does something).
//
// Both directions. A one-way check would leave half the drift standing.
//
// Lesson 1197 — a second copy of a truth drifts, and it drifts
// silently. The cure is not "be careful when editing the list"; the
// cure is that there is no second copy.

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { codeOf } from "./strip-comments.mjs";
import { loadTsModule } from "./load-ts-module.mjs";

const root = join(import.meta.dirname, "..", "..");
const src = readFileSync(join(root, "src", "commands.ts"), "utf-8");
const code = codeOf(src, "commands.ts");

const mod = await loadTsModule(root, "src/commands.ts");
const { SLASH_COMMANDS, SLASH_COMMANDS_DETAILED, SLASH_HELP } = mod;

/** The router's `case` arms — the commands the dispatcher can reach. */
function routedCommands() {
  const start = src.indexOf("export function routeSlash");
  assert.ok(start > 0, "routeSlash not found — re-anchor this gate");
  const end = src.indexOf("\nexport function handleSlash", start);
  const body = src.slice(start, end > 0 ? end : src.length);
  return [...body.matchAll(/case\s+"([^"]+)":/g)].map((m) => m[1]);
}

const routed = routedCommands();
const catalogNames = SLASH_COMMANDS_DETAILED.map((c) => c.name);
const rows = SLASH_COMMANDS_DETAILED.filter((c) => !c.aliasOf);
const aliases = SLASH_COMMANDS_DETAILED.filter((c) => c.aliasOf);

// =========================================================================
// 1. the one list
// =========================================================================

test("the flat name list is DERIVED from the catalog, not maintained beside it", () => {
  // The failure this catches is the R376 one in a new place: a hand
  // maintained list of names that quietly disagrees with the catalog.
  assert.doesNotMatch(
    code,
    /export const SLASH_COMMANDS\s*:\s*string\[\]\s*=\s*\[/,
    "SLASH_COMMANDS must be derived from the catalog — a literal array is a second copy",
  );
  assert.match(
    code,
    /SLASH_COMMANDS_DETAILED\.map\(/,
    "SLASH_COMMANDS must be the catalog's projection",
  );
  assert.deepEqual(
    SLASH_COMMANDS,
    catalogNames,
    "the flat list and the catalog disagree — the Tab completion and the dropdown " +
      "would show different commands",
  );
  const seen = new Set();
  const dupes = SLASH_COMMANDS.filter((n) => (seen.has(n) ? true : (seen.add(n), false)));
  assert.deepEqual(
    dupes,
    [],
    `the flat list has duplicates: ${dupes.join(", ")} — the completion list showed ` +
      "`budget` twice",
  );
});

test("there is no second group table", () => {
  // The group is a field ON the entry now, so the table has no reason
  // to exist — and its absence is what makes "a command in the catalog
  // but missing from /help" impossible.
  assert.doesNotMatch(
    code,
    /SLASH_COMMAND_GROUPS/,
    "the group table must not come back — the group is a field on the entry",
  );
});

test("every entry carries a group, and no group is split in two", () => {
  for (const c of SLASH_COMMANDS_DETAILED) {
    assert.ok(
      typeof c.group === "string" && c.group.trim().length > 0,
      `the command /${c.name} has no group — it would not appear in /help`,
    );
  }
  const seen = new Set();
  let previous = null;
  for (const c of rows) {
    if (c.group === previous) continue;
    assert.ok(
      !seen.has(c.group),
      `the group "${c.group}" appears in two separate runs — /help would print the ` +
        "same header twice",
    );
    seen.add(c.group);
    previous = c.group;
  }
  assert.ok(seen.size >= 4, `only ${seen.size} groups — re-anchor this gate`);
});

// =========================================================================
// 2. the two directions — the checks that would have caught the drift
// =========================================================================

test("every command the ROUTER can reach is declared in the catalog", () => {
  // This is the direction that was missing. A `case` arm the catalog
  // does not know about is a command the user can type, complete in
  // one surface, and not find in another.
  const known = new Set(catalogNames);
  const undeclared = [...new Set(routed)].filter((n) => !known.has(n));
  assert.deepEqual(
    undeclared,
    [],
    `the router serves commands the catalog does not declare: ${undeclared.join(", ")} — ` +
      "they are not completable and not in /help",
  );
});

test("every command the CATALOG declares is routable", () => {
  // The other direction: a command /help advertises (and the dropdown
  // offers) that does nothing is a lie in a list of promises.
  const arms = new Set(routed);
  const unrouted = catalogNames.filter((n) => !arms.has(n));
  assert.deepEqual(
    unrouted,
    [],
    `the catalog advertises commands the router does not handle: ${unrouted.join(", ")}`,
  );
});

// =========================================================================
// 3. discoverability
// =========================================================================

test("every command the user can type is FINDABLE in /help", () => {
  // A row of its own, or — for an alias — a mention on its primary's
  // row. Either way the user can find it.
  for (const c of rows) {
    assert.ok(
      SLASH_HELP.includes(`/${c.name}`),
      `/help does not render /${c.name} even though it is in the catalog`,
    );
  }
  for (const c of aliases) {
    assert.ok(
      SLASH_HELP.includes(`/${c.aliasOf}`),
      `the alias /${c.name} points at /${c.aliasOf}, which has no row in /help`,
    );
    assert.ok(SLASH_HELP.includes(`/${c.name}`), `/help does not mention the alias /${c.name}`);
  }
});

test("the alias mention is DERIVED, not hand-written in a description", () => {
  // A hand-written "(alias: /?)" would be a second copy of the alias
  // set — and the renderer would append a second mention to it.
  //
  // NOTE the regex: `alias(?:es)?`, not `aliases?`. The latter matches
  // "aliase" + optional "s", NOT "alias" + optional "es" — so it
  // silently missed the singular form, which is the form three of the
  // four original aliases used. (This is the second time this exact
  // spelling mistake has cost a round.)
  assert.doesNotMatch(
    code,
    /description:\s*"[^"]*\(alias(?:es)?:\s*\//,
    "the alias mention must come from aliasOf, not from the description text",
  );
  // And every alias points at a command that exists.
  const known = new Set(catalogNames);
  for (const c of aliases) {
    assert.ok(
      known.has(c.aliasOf),
      `the alias /${c.name} points at /${c.aliasOf}, which is not in the catalog`,
    );
  }
});

test("an alias's description NAMES the command it stands for", () => {
  // The dropdown shows the description next to the name, so an entry
  // that says "alias for /mode" while claiming to alias /exit would
  // LIE to the user. This is also what makes "/clear" unable to
  // masquerade as an alias of something unrelated: its description is
  // "clear the scrollback", not "alias for /help".
  for (const c of aliases) {
    assert.equal(
      c.description,
      `alias for /${c.aliasOf}`,
      `the alias /${c.name} claims to stand for /${c.aliasOf} but its description says ` +
        `"${c.description}" — the dropdown would show a lie`,
    );
  }
});

test("the two features that were invisible are visible now", () => {
  // The specific drifts this round found. A general rule can regress
  // without anybody noticing; these two are named.
  assert.ok(
    catalogNames.includes("effort"),
    "/effort was routable but not in the catalog — R377's feature was invisible",
  );
  assert.ok(
    catalogNames.includes("no-confirm"),
    "the permission shortcut was routable but not in the catalog",
  );
  assert.ok(SLASH_HELP.includes("/effort"), "/help does not mention /effort");
});

test("an alias does not SHADOW the command it stands for", () => {
  // `/token` is an alias of `/tokens`. When both are offered, the
  // prefix "/tok" stops completing in one keystroke — the user has to
  // type one more letter for a distinction nobody cares about.
  //
  // The rule: when the primary an alias stands for ALSO matches the
  // prefix, the alias is redundant and is dropped. An alias whose
  // primary does not match stays ("/q" still offers "/q" and "/quit").
  const c = mod.completeSlash("/tok");
  assert.equal(
    c.completed,
    "/tokens ",
    `"/tok" must complete to "/tokens " in one keystroke, got ${JSON.stringify(c)}`,
  );
  assert.equal(c.oneShot, true, `"/tok" must be a one-shot completion: ${JSON.stringify(c)}`);
  // And the rule must not over-fire: "/q" still offers both spellings.
  const q = mod.completeSlash("/q");
  assert.deepEqual(
    [...q.alternatives].sort(),
    ["q", "quit"],
    '"/q" must still offer both spellings — the rule drops an alias only when its ' +
      "primary also matches",
  );
});

test("/help still carries no development notes", () => {
  for (const pat of [/R\d{3}/, /\bT-\d/, /历史/, /round:/i, /对应历史/]) {
    assert.doesNotMatch(SLASH_HELP, pat, `/help leaks a development note matching ${pat}`);
  }
});

// =========================================================================
// 4. not vacuous
// =========================================================================

test("the gate is not vacuous", () => {
  assert.ok(catalogNames.length >= 85, `only ${catalogNames.length} commands in the catalog`);
  assert.ok(routed.length >= 85, `only ${routed.length} router arms — re-anchor this gate`);
  assert.ok(rows.length >= 70, `only ${rows.length} commands with a /help row`);
  assert.ok(aliases.length >= 10, `only ${aliases.length} aliases — re-anchor this gate`);
  assert.ok(new Set(catalogNames).size === catalogNames.length, "the catalog has duplicate names");
});
