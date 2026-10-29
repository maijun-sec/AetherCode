// Copyright (c) 2026 maijun

// Licensed under the MIT License.
// See the LICENSE file in the project root for the full license text.

/**
 * The ONE permission-mode vocabulary and catalog.
 *
 * ## Why this file exists
 *
 * Before R385 the mode surface was described in SIX files, by
 * THIRTY-NINE separate string literals (measured on 2026-10-26 by
 * `scripts/test/r385-mode-surface.test.mjs`'s own scanner: `line.ts`
 * 16, `components/Header.tsx` 11, `tui.tsx` 7,
 * `components/Welcome.tsx` 3, `commands.ts` 1, `state.ts` 1), and
 * they had already disagreed:
 *
 *   - `line.ts` `pickMode` — the picker (cmd.exe line mode)
 *   - `line.ts` `modeStyle` — the status pill's badge + colour
 *   - `components/Header.tsx` `modeStyle` + `modeLabel` — TWO more
 *     hand-written switches, with FOUR names the daemon cannot produce
 *     (`AUTO`, `ASK`, `DENY`, `BYPASS` — the old `Action` enum)
 *   - `tui.tsx` — the toast blurb after `/mode <name>`
 *   - `commands.ts` — the literal `BYPASS_PERMISSIONS` behind
 *     `/no-confirm`
 *   - `state.ts` / `line.ts` / `tui.tsx` — the INITIAL mode, written
 *     three times, and two of the three disagreed: Ink fell back to
 *     `ACCEPT_TASK` while the daemon's own default is `DEFAULT`
 *     (`AppState.permissionMode = PermissionMode.DEFAULT`).
 *
 * The Ink header therefore showed "TASK" — "everything in this task
 * runs without asking" — while the daemon was in `DEFAULT`, where
 * every tool call asks. The user was told a lie about their own
 * safety net.
 *
 * The list was also INCOMPLETE: the daemon's
 * `org.aethercode.core.permission.PermissionMode` has SEVEN values, and
 * `ASK_BEFORE_TOOL` — whose own javadoc says it was named for the
 * TUI's status bar — was in none of the copies.
 *
 * ## The authority is the daemon's enum
 *
 * `setPermissionMode` resolves its argument with
 * `PermissionMode.valueOf(modeStr)`, so the daemon accepts EXACTLY the
 * enum names and nothing else. The vocabulary below is therefore a
 * *transcription* of that enum, and
 * `scripts/test/r385-mode-surface.test.mjs` reads `PermissionMode.java`
 * and compares the two, in both directions. A mode the daemon gains
 * and a mode the TUI invents both turn that gate red.
 *
 * The same gate checks that the vocabulary is written HERE and nowhere
 * else: no other source file may contain a string literal that is
 * exactly a mode name. Prose that mentions a mode is fine — a copy of
 * the list is not.
 *
 * ## Why the argument is normalised
 *
 * `valueOf` is case-sensitive and knows nothing about dashes, so
 * `/mode default` and `/mode ask-before-tool` both used to die with
 * "unknown permission mode" — even though the daemon's own javadoc
 * documents `/mode ask-before-tool` as the TUI spelling. The
 * normalisation below is one rule (upper-case, dashes to underscores),
 * not a synonym table: there is no invented vocabulary here, only the
 * two spellings a user can reasonably type.
 */

/**
 * The mode NAMES — the daemon's `PermissionMode` enum constants.
 *
 * They are written here and nowhere else. Every other file reaches a
 * mode through `MODE.<NAME>`, so "the vocabulary" has exactly one
 * owner and the gate can prove it.
 */
export const MODE = {
  DEFAULT: "DEFAULT",
  ASK_BEFORE_TOOL: "ASK_BEFORE_TOOL",
  ACCEPT_TASK: "ACCEPT_TASK",
  ACCEPT_EDITS: "ACCEPT_EDITS",
  BYPASS_PERMISSIONS: "BYPASS_PERMISSIONS",
  PLAN: "PLAN",
  AUTO_READ_ONLY: "AUTO_READ_ONLY",
} as const;

/** One of the seven mode names. */
export type PermissionModeName = (typeof MODE)[keyof typeof MODE];

/** How the status pill colours a mode. */
export type PermissionModeTone = "ask" | "allow" | "read" | "plan" | "danger" | "unknown";

/** One permission mode. */
export interface PermissionModeEntry {
  /**
   * The daemon's enum name — the exact string `setPermissionMode`
   * accepts. This is also what the TUI sends on the wire.
   */
  value: PermissionModeName;
  /** The long label the picker shows. */
  label: string;
  /** The short badge the status pill shows. */
  badge: string;
  /** One line: what this mode does to the user's safety net. */
  blurb: string;
  /** The pill's colour role. */
  tone: PermissionModeTone;
}

/**
 * Every permission mode the daemon supports, ordered from "trusts you
 * the least" to "trusts you the most".
 *
 * The ORDER is a UX choice (the daemon's enum order is not one), but
 * the SET is not: the gate compares it with `PermissionMode.java`.
 */
export const PERMISSION_MODES: readonly PermissionModeEntry[] = [
  {
    value: MODE.DEFAULT,
    label: "Ask before every tool call",
    badge: "ASK",
    blurb: "every tool call asks before running",
    tone: "ask",
  },
  {
    value: MODE.ASK_BEFORE_TOOL,
    label: "Ask before every tool call (explicit)",
    badge: "ASK",
    blurb: "same as DEFAULT, under its discoverable name",
    tone: "ask",
  },
  {
    value: MODE.ACCEPT_TASK,
    label: "Auto-allow everything within one task",
    badge: "TASK",
    blurb: "single task, no mid-task prompts (DEFAULT behaviour unless sub-task boundary)",
    tone: "allow",
  },
  {
    value: MODE.ACCEPT_EDITS,
    label: "Auto-allow file edits only",
    badge: "EDITS",
    blurb: "file edits auto-allowed; bash / network still ask",
    tone: "allow",
  },
  {
    value: MODE.AUTO_READ_ONLY,
    label: "Auto-allow read-only tools",
    badge: "READ",
    blurb: "read-only tools auto-allowed; writes still ask",
    tone: "read",
  },
  {
    value: MODE.PLAN,
    label: "Plan only — do not execute",
    badge: "PLAN",
    blurb: "plan mode — tools ask before running",
    tone: "plan",
  },
  {
    value: MODE.BYPASS_PERMISSIONS,
    label: "No prompts at all (danger)",
    badge: "BYPASS",
    blurb: "no prompts will be shown for any tool call",
    tone: "danger",
  },
];

/** The wire values, in catalog order. */
export const PERMISSION_MODE_VALUES: readonly PermissionModeName[] = PERMISSION_MODES.map(
  (m) => m.value,
);

/**
 * Turn what the user typed into the daemon's spelling.
 *
 * One rule: trim, upper-case, dashes to underscores. It maps
 * `default` → `DEFAULT` and `ask-before-tool` → `ASK_BEFORE_TOOL`,
 * and leaves an already-correct name alone.
 */
export function normalisePermissionMode(arg: string): string {
  return arg.trim().toUpperCase().replace(/-/g, "_");
}

/** The catalog entry for a mode name, or undefined. */
export function permissionModeEntry(value: string): PermissionModeEntry | undefined {
  const key = normalisePermissionMode(value);
  return PERMISSION_MODES.find((m) => m.value === key);
}

/** Is this a mode the daemon supports? */
export function isKnownPermissionMode(value: string): boolean {
  return permissionModeEntry(value) !== undefined;
}

/**
 * The one-line explanation of a mode.
 *
 * An UNKNOWN mode gets an honest sentence, not the DEFAULT one: the
 * daemon accepted it (the RPC succeeded), so it is in force, but this
 * build has nothing to say about it. Claiming it is DEFAULT would be a
 * lie about a mode the user just selected.
 */
export function permissionModeBlurb(value: string): string {
  const entry = permissionModeEntry(value);
  if (entry) return entry.blurb;
  return `mode ${value} is in force, but this build has no description for it`;
}
