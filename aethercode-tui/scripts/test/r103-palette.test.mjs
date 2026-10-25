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

test("R103: CommandPalette.tsx describeCommand covers skip + tool-actions", () => {
  const src = readFileSync(join(root, "src", "components", "CommandPalette.tsx"), "utf-8");
  // The describeCommand function must return a non-empty string
  // for the two new commands so the user sees the description
  // in the palette preview, not a blank line.
  assert.match(src, /case "skip":/);
  assert.match(src, /case "tool-actions":/);
  // Sanity: the descriptions contain "R99" and "R100" tags so
  // it's obvious which round the command comes from. Tweak
  // these tags freely; the test just guards against accidental
  // removal of the descriptions.
  assert.match(src, /R99/);
  assert.match(src, /R100/);
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
