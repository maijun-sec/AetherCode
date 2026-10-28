// Copyright (c) 2026 maijun

// Licensed under the MIT License.
// See the LICENSE file in the project root for the full license text.

// R103: TUI command palette exposes the new R99 / R100 commands.
//
// R103 closes a discoverability gap: R99 added `/skip` and R100
// added `/tool-actions` to the slash-command dispatcher, but the
// Ctrl-P command palette (the user-facing "discovery" surface) was
// derived from SLASH_COMMANDS. As long as the new commands are
// in SLASH_COMMANDS, they automatically show up in the palette;
// R103's job is to make sure that is the case AND that the
// palette's `describeCommand` returns a meaningful description
// (so the user sees "R99: arm/clear skip-confirmation" not an
// empty line).
//
// This test is a pure source-code assertion — we don't need to
// spawn the TUI to verify the wiring, just confirm the source
// has the new entries.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { loadTsModule } from "./load-ts-module.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

test("R103: commands.ts has /skip in SLASH_COMMANDS", () => {
  const src = readFileSync(join(root, "src", "commands.ts"), "utf-8");
  // The list lives in the SLASH_COMMANDS array. We don't try to
  // parse the file; we just check that the literal "skip"
  // string appears alongside the SLASH_COMMANDS declaration.
  assert.match(src, /export const SLASH_COMMANDS/);
  assert.match(src, /"skip"/);
});

test("R103: commands.ts has /tool-actions in SLASH_COMMANDS", () => {
  const src = readFileSync(join(root, "src", "commands.ts"), "utf-8");
  assert.match(src, /export const SLASH_COMMANDS/);
  assert.match(src, /"tool-actions"/);
});

test("R103: commands.ts has a /skip handler that calls setSkipConfirmation RPC", () => {
  const src = readFileSync(join(root, "src", "commands.ts"), "utf-8");
  // The case "skip" branch must dispatch to the
  // setSkipConfirmation RPC with a `rounds` param.
  assert.match(src, /case "skip":/);
  assert.match(src, /setSkipConfirmation/);
  assert.match(src, /rounds:/);
});

test("R103: commands.ts has a /tool-actions handler that calls listToolActions RPC", () => {
  const src = readFileSync(join(root, "src", "commands.ts"), "utf-8");
  assert.match(src, /case "tool-actions":/);
  assert.match(src, /listToolActions/);
});

test("R103: /help output mentions /skip and /tool-actions", () => {
  // R362: SLASH_HELP is no longer a hand-written literal in commands.ts — it is
  // rendered at module-eval time from SLASH_COMMANDS_DETAILED (the same catalog
  // that feeds the Tab dropdown) plus the SLASH_COMMAND_GROUPS table. That is
  // what killed the drift where `/help` advertised 88 commands while
  // autocomplete knew 76.
  //
  // R380: the group table is GONE — the group is a field on the catalog
  // entry itself, so "in the catalog AND in a group row" collapsed to
  // "in the catalog". The property is unchanged (these two commands
  // must be in /help); only the number of places to look shrank.
  const src = readFileSync(join(root, "src", "commands.ts"), "utf-8");
  const catalog = src.slice(
    src.indexOf("export const SLASH_COMMANDS_DETAILED"),
    src.indexOf("];", src.indexOf("export const SLASH_COMMANDS_DETAILED")),
  );
  assert.match(catalog, /name: "skip"/, "skip missing from the autocomplete catalog");
  assert.match(catalog, /name: "tool-actions"/, "tool-actions missing from the catalog");
  // argument placeholder must survive into the rendered help
  assert.match(
    catalog,
    /name: "skip"[^}]*args: "<N\|off>"/,
    "skip must carry the <N|off> argument hint so /help shows it",
  );
});

test("R103: the command palette shows a non-empty description for skip + tool-actions", async () => {
  // R388: this assertion used to pin the SPELLING of the palette's own
  // hand-written `describeCommand` table (`case "skip":` / `case
  // "tool-actions":` in CommandPalette.tsx). That table is gone — it
  // covered only 23 of the catalogue's 88 commands, so 65 palette rows
  // rendered an EMPTY hint, and the ones it did cover could disagree
  // with the catalogue (`/budget` said "set cost budget" in the palette
  // and "set a phase's tool-call / cost cap" in the catalogue).
  //
  // The INTENT of this test was never the spelling — it was "the user
  // sees a description, not a blank line" (see the comment above). So
  // the assertion now asks the REAL question: does the palette's
  // description function return something for these two commands?
  //
  // A CONTRACT pin must FOLLOW the implementation (R384, Lesson 1233):
  // pinning the table's shape would have gone red on the very fix that
  // made 65 rows non-empty.
  const { describeCommand } = await loadTsModule(root, "src/commands.ts");
  for (const name of ["skip", "tool-actions"]) {
    assert.ok(
      describeCommand(name).trim().length > 0,
      `the palette would show an empty hint for /${name}`,
    );
  }
  // And the palette really takes them from there.
  const palette = readFileSync(join(root, "src", "components", "CommandPalette.tsx"), "utf-8");
  assert.match(palette, /describeCommand/);
  assert.match(palette, /from "\.\.\/commands\.js"/);
});

test("R103: state.ts has the skip-confirmation counter wired", () => {
  const src = readFileSync(join(root, "src", "state.ts"), "utf-8");
  // The R99 state field is `skipConfirmationRemaining: number`.
  // R103 doesn't add any new state; the test is here to guard
  // against a future cleanup removing the field.
  assert.match(src, /skipConfirmationRemaining: number/);
});

test("R103: tui.tsx handles the skip_confirmation notification", () => {
  const src = readFileSync(join(root, "src", "tui.tsx"), "utf-8");
  // The Ctrl-P palette opens via `state.paletteOpen`. The
  // palette itself fires `onSelect` with a slash command; the
  // existing slash dispatcher in commands.ts handles the new
  // commands. R103's contribution is making sure the notifier
  // case for `skip_confirmation` is present so the status bar
  // re-renders.
  assert.match(src, /case "skip_confirmation":/);
  assert.match(src, /setSkipConfirmationRemaining/);
});

test("R103: StatusBar.tsx shows the skip-confirmation badge", () => {
  const src = readFileSync(join(root, "src", "components", "StatusBar.tsx"), "utf-8");
  // The badge text "⏩ skip N" appears when the counter is > 0.
  assert.match(src, /⏩ skip/);
  assert.match(src, /skipConfirmationRemaining > 0/);
});
