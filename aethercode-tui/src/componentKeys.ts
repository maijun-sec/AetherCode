/**
 * R379 — the key tables of the PANELS.
 *
 * ## Why this file exists
 *
 * R378 made the app-level help GENERATED: one registry (`src/keybindings.ts`)
 * feeds the help overlay, the welcome screen and `--help`, and the gate
 * cross-checks it against the handler that actually binds the keys.
 *
 * The panels were left out of that round, and they have the same shape of
 * problem. Every panel owns a `useInput` handler AND renders a hand-written
 * key hint:
 *
 *     <Text dimColor> ↑/↓ (j/k): navigate Enter: select Esc: close</Text>
 *
 * That string is a SECOND COPY of what the handler binds, and the two
 * drift. Measured on 2026-10-25 (R379):
 *
 *   - `GrantsManager` binds `j` / `k` but its hint says only "↑/↓
 *     navigate" — the vim keys are invisible to the user;
 *   - `MemoryPanel` binds Tab, Shift+Tab and ←/→ to switch tabs, and
 *     its hint does not mention them AT ALL;
 *   - `CwdSwitcher`'s hint promises "Enter: switch", but its `useInput`
 *     binds only Escape — the Enter belongs to the text field.
 *
 * The fix is the same as R378's: there must not be a second copy. The
 * key table lives HERE, the panel renders the hint from it, and the gate
 * checks the table against the handler in BOTH directions.
 *
 * ## Scope
 *
 * The panels the user can actually open — the ones behind the advertised
 * panel intents (`src/panelIntents.ts`). Ten of the twelve render a key
 * hint; `ContextMeter` and `TodoBoard` do not, so they have no entry
 * (a registry entry for a panel that shows no keys would be a lie).
 *
 * ## The vocabulary
 *
 * A "token" is one key primitive, in the SAME words the handler's
 * `key.*` properties use. `KEY_PROP_TOKEN` maps the React/Ink property
 * names onto the short tokens the tables are written in, and the gate
 * imports THAT map — so the vocabulary has one definition, not two.
 *
 * A key that a panel's TEXT FIELD handles (Enter in the cwd switcher)
 * is declared `via: "input"`: the panel's own `useInput` does not bind
 * it, and the gate does not pretend otherwise.
 */

/** How a key is bound. */
export type KeyBinding = "handler" | "input";

/** One key a panel offers. */
export interface ComponentKey {
  /** A stable id — the label may be rewritten, the id may not. */
  id: string;
  /** The user-visible fragment, e.g. "↑/↓ (j/k): navigate". */
  label: string;
  /** The key primitives this entry binds. */
  tokens: string[];
  /**
   * "handler" — the panel's own `useInput` binds it.
   * "input"   — the panel's text field binds it (its `onSubmit`).
   */
  via: KeyBinding;
}

/** One panel's key table. */
export interface ComponentKeyTable {
  /** The component's path relative to `src/`, e.g. "components/EffortPicker.tsx". */
  component: string;
  /** What joins two labels. Panels disagree on this; the table records it. */
  separator: string;
  /** The keys, in the order they are shown. */
  keys: ComponentKey[];
  /**
   * A dynamic tail that is NOT a key binding (e.g. "3 presets
   * available"). Optional — most panels have none.
   */
  suffix?: (ctx: Record<string, unknown>) => string;
  /**
   * A note shown when there is nothing to act on, which may itself
   * name keys ("no grant focused — use ↑/↓ to pick one"). Optional.
   */
  emptyNote?: string;
}

/**
 * The Ink `key.*` properties, mapped onto the short token names the
 * tables are written in. ONE definition: the gate imports it, so the
 * vocabulary cannot drift between the product and the check.
 */
export const KEY_PROP_TOKEN: Readonly<Record<string, string>> = {
  escape: "esc",
  return: "enter",
  upArrow: "up",
  downArrow: "down",
  leftArrow: "left",
  rightArrow: "right",
  tab: "tab",
  shift: "shift",
  ctrl: "ctrl",
  backspace: "backspace",
  delete: "delete",
};

/**
 * The chord that reconnects an MCP server.
 *
 * <p>It lives HERE because the McpViewer's hint renders it, and a
 * second copy of a chord is a second copy of a truth.
 */
export const RECONNECT_KEY_LABEL = "Ctrl+R";

/**
 * The panels' key tables.
 *
 * <p>Ordered by component path so the file reads like a directory.
 */
export const COMPONENT_KEYS: readonly ComponentKeyTable[] = [
  {
    component: "components/AgentSelector.tsx",
    separator: " ",
    keys: [
      { id: "filter", label: "type to filter", tokens: [], via: "input" },
      { id: "nav", label: "↑/↓ (j/k): navigate", tokens: ["up", "down", "j", "k"], via: "handler" },
      { id: "select", label: "Enter: select", tokens: ["enter"], via: "handler" },
      {
        id: "set-default",
        label: "Ctrl+S: set default",
        tokens: ["ctrl", "s"],
        via: "handler",
      },
      { id: "close", label: "Esc: close", tokens: ["esc"], via: "handler" },
    ],
  },
  {
    component: "components/CwdSwitcher.tsx",
    separator: " ",
    keys: [
      // Enter belongs to the text field, not to the panel's useInput.
      { id: "switch", label: "Enter: switch", tokens: ["enter"], via: "input" },
      { id: "cancel", label: "Esc: cancel", tokens: ["esc"], via: "handler" },
    ],
  },
  {
    component: "components/EffortPicker.tsx",
    separator: " ",
    keys: [
      { id: "nav", label: "↑/↓ (j/k): navigate", tokens: ["up", "down", "j", "k"], via: "handler" },
      { id: "select", label: "Enter: select", tokens: ["enter"], via: "handler" },
      { id: "close", label: "Esc: close", tokens: ["esc"], via: "handler" },
    ],
  },
  {
    component: "components/McpViewer.tsx",
    separator: " ",
    keys: [
      { id: "nav", label: "↑/↓ (j/k): navigate", tokens: ["up", "down", "j", "k"], via: "handler" },
      { id: "sign-in", label: "Enter: sign in", tokens: ["enter"], via: "handler" },
      {
        id: "reconnect",
        label: `${RECONNECT_KEY_LABEL}: reconnect`,
        tokens: ["ctrl", "r"],
        via: "handler",
      },
      { id: "close", label: "Esc: close", tokens: ["esc"], via: "handler" },
    ],
  },
  {
    component: "components/MemoryPanel.tsx",
    separator: " · ",
    keys: [
      { id: "nav", label: "↑/↓ (j/k): select", tokens: ["up", "down", "j", "k"], via: "handler" },
      { id: "edit", label: "Enter/e: edit", tokens: ["enter", "e"], via: "handler" },
      { id: "compact", label: "c: compact", tokens: ["c"], via: "handler" },
      {
        id: "tab",
        label: "Tab/←/→: switch tab",
        tokens: ["tab", "shift", "left", "right"],
        via: "handler",
      },
      { id: "scope", label: "1/2/3: scope", tokens: ["1", "2", "3"], via: "handler" },
      { id: "close", label: "Esc: close", tokens: ["esc"], via: "handler" },
    ],
  },
  {
    component: "components/NotificationCenter.tsx",
    separator: " ",
    keys: [
      { id: "nav", label: "↑/↓ (j/k): navigate", tokens: ["up", "down", "j", "k"], via: "handler" },
      { id: "select", label: "Enter: select", tokens: ["enter"], via: "handler" },
      { id: "settings", label: "Tab: settings", tokens: ["tab"], via: "handler" },
      { id: "close", label: "Esc: close", tokens: ["esc"], via: "handler" },
    ],
  },
  {
    component: "components/ThreadSelector.tsx",
    separator: " ",
    keys: [
      { id: "filter", label: "type to filter", tokens: [], via: "input" },
      { id: "nav", label: "↑/↓ (j/k): navigate", tokens: ["up", "down", "j", "k"], via: "handler" },
      { id: "select", label: "Enter: select", tokens: ["enter"], via: "handler" },
      { id: "cancel", label: "Esc: cancel", tokens: ["esc"], via: "handler" },
    ],
  },
  {
    component: "components/UpdatePanel.tsx",
    separator: " ",
    keys: [
      { id: "changelog", label: "Enter: changelog", tokens: ["enter"], via: "handler" },
      { id: "close", label: "Esc: close", tokens: ["esc"], via: "handler" },
    ],
  },
  {
    component: "components/WorkflowPicker.tsx",
    separator: " ",
    keys: [
      { id: "nav", label: "↑/↓ (j/k): navigate", tokens: ["up", "down", "j", "k"], via: "handler" },
      { id: "run", label: "Enter: run", tokens: ["enter"], via: "handler" },
      { id: "cancel", label: "Esc: cancel", tokens: ["esc"], via: "handler" },
    ],
  },
  {
    component: "components/permissions/GrantsManager.tsx",
    separator: " · ",
    keys: [
      { id: "nav", label: "↑/↓ (j/k): navigate", tokens: ["up", "down", "j", "k"], via: "handler" },
      { id: "revoke", label: "r: revoke", tokens: ["r"], via: "handler" },
      { id: "scope", label: "1-4: scope filter", tokens: ["1", "2", "3", "4"], via: "handler" },
    ],
    suffix: (ctx) =>
      `${Number(ctx.presets ?? 0)} preset${Number(ctx.presets ?? 0) === 1 ? "" : "s"} available`,
    // The EMPTY-STATE note also names keys, so it is part of the same
    // table. A second copy of "use ↑/↓ to pick one" is a second copy
    // of the truth.
    emptyNote: "(no grant focused — use ↑/↓ to pick one)",
  },
];

/** The table for a component, or undefined. */
export function keyTable(component: string): ComponentKeyTable | undefined {
  return COMPONENT_KEYS.find((t) => t.component === component);
}

/**
 * The hint a panel renders.
 *
 * @param component the component's path relative to `src/`
 * @param ctx the dynamic bits (only the preset count today)
 */
export function hintFor(component: string, ctx: Record<string, unknown> = {}): string {
  const table = keyTable(component);
  if (!table) return "";
  const parts = table.keys.map((k) => k.label);
  if (table.suffix) {
    const tail = table.suffix(ctx);
    if (tail) parts.push(tail);
  }
  return parts.join(table.separator);
}

/**
 * The note a panel shows when there is nothing to act on, if it names
 * keys.
 *
 * @param component the component's path relative to `src/`
 */
export function emptyNoteFor(component: string): string {
  return keyTable(component)?.emptyNote ?? "";
}

/**
 * The key primitives a component's OWN `useInput` binds, per the table.
 *
 * <p>The gate compares this with what the handler actually branches on.
 */
export function handlerTokens(component: string): string[] {
  const table = keyTable(component);
  if (!table) return [];
  const out = new Set<string>();
  for (const k of table.keys) {
    if (k.via !== "handler") continue;
    for (const t of k.tokens) out.add(t);
  }
  return [...out].sort();
}
