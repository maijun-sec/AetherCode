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

/**
 * R379/R381 — the key tables of the PANELS.
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
 * ## R381: the scope is what the user can actually OPEN
 *
 * R379's own list of "reachable components" was wrong: it counted
 * `NotificationSettings` and `NotificationDetail`, which no user can
 * reach (the first is imported only for its toggle REGISTRY; the
 * second is imported by nobody at all).
 *
 * So the scope is MEASURED now — `scripts/test/reachability.mjs` walks
 * the import graph from the entry points and resolves every JSX tag to
 * the file it really comes from. A component nobody can open cannot
 * mislead anybody, so it does not belong in a table of what the user
 * sees.
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
 *
 * A PROSE hint (R381) is a sentence rather than a list of keys — the
 * input box's "type a prompt — Enter to send, shift+Enter for newline,
 * / for commands, …" is one sentence that names keys handled by three
 * different places. A prose hint names its keys, and each one says who
 * binds it: the component's own handler, or the app-level registry
 * (`src/keybindings.ts`) for the keys the application handles.
 */

/**
 * How a key is bound.
 *
 * <p>"handler" — the component's own `useInput` binds it.
 * <p>"input"   — the component's text field binds it (its `onSubmit`).
 * <p>"global"  — the APPLICATION binds it (the app-level registry,
 *                `src/keybindings.ts`). Used by the components that
 *                have no handler of their own: the command palette,
 *                the search bar, the sub-agent panel and the welcome
 *                screen all show chords that the app-level handler
 *                owns.
 */
export type KeyBinding = "handler" | "input" | "global";

/** Who binds a key named in a PROSE sentence. */
export type ProseBinding = "handler" | "global";

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
  /**
   * Show this entry only when the predicate holds.
   *
   * <p>Used where the panel's affordance depends on its state — the
   * log viewer only offers "[/] switch tab" when there IS a second
   * tab to switch to.
   */
  when?: (ctx: Record<string, unknown>) => boolean;
}

/** One key named in a prose sentence, and who binds it. */
export interface ProseToken {
  /** The key primitive, in the registry's vocabulary. */
  token: string;
  /**
   * "handler" — the component's own `useInput` binds it.
   * "global"  — the APPLICATION binds it (the app-level registry,
   *             `src/keybindings.ts`).
   */
  via: ProseBinding;
}

/**
 * A free-form sentence that names keys.
 *
 * <p>Used where the hint is a sentence rather than a list — the input
 * box's "type a prompt — Enter to send, shift+Enter for newline, / for
 * commands, @ for files, ↑/↓ for history, Tab to accept, Ctrl-? for
 * help" is one sentence about seven keys handled by three different
 * places.
 */
export interface ProseHint {
  /** A stable id — the text may be rewritten, the id may not. */
  id: string;
  /** The sentence itself. */
  text: string;
  /** The keys the sentence names. */
  tokens: ProseToken[];
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
   * Free-form sentences, by id. A panel renders one with `proseFor`.
   */
  prose?: ProseHint[];
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
  return: "return",
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
      { id: "select", label: "Enter: select", tokens: ["return"], via: "handler" },
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
    component: "components/CommandPalette.tsx",
    separator: " ",
    // R381: the palette has no handler of its own — the APPLICATION
    // binds Esc and Enter while the palette is open.
    keys: [
      { id: "close", label: "Esc: close", tokens: ["esc"], via: "global" },
      { id: "select", label: "Enter: select", tokens: ["return"], via: "global" },
    ],
  },
  {
    component: "components/CwdSwitcher.tsx",
    separator: " ",
    keys: [
      // Enter belongs to the text field, not to the panel's useInput.
      { id: "switch", label: "Enter: switch", tokens: ["return"], via: "input" },
      { id: "cancel", label: "Esc: cancel", tokens: ["esc"], via: "handler" },
    ],
  },
  {
    component: "components/EffortPicker.tsx",
    separator: " ",
    keys: [
      { id: "nav", label: "↑/↓ (j/k): navigate", tokens: ["up", "down", "j", "k"], via: "handler" },
      { id: "select", label: "Enter: select", tokens: ["return"], via: "handler" },
      { id: "close", label: "Esc: close", tokens: ["esc"], via: "handler" },
    ],
  },
  {
    component: "components/InputBox.tsx",
    separator: " ",
    // R381: the input box's hint is a SENTENCE, not a list. It names
    // keys handled by three different places, so each key says who
    // binds it.
    keys: [],
    prose: [
      {
        id: "prompt",
        text: "type a prompt — Enter to send, shift+Enter for newline, / for commands, @ for files, ↑/↓ for history, Tab to accept, Ctrl-? for help",
        tokens: [
          { token: "return", via: "handler" },
          { token: "shift", via: "handler" },
          { token: "up", via: "handler" },
          { token: "down", via: "handler" },
          { token: "tab", via: "handler" },
          // The application handles the help chord…
          { token: "ctrl+?", via: "global" },
          // …and the two TEXT triggers, which are not keys at all:
          // they are what the user TYPES.
          { token: "/", via: "global" },
          { token: "@", via: "global" },
        ],
      },
      {
        id: "decision",
        text: "waiting for permission decision — press A / T / P / U / D / N (or collapse the card with Esc)",
        tokens: [
          { token: "esc", via: "handler" },
          // The decision keys are answered by the APPLICATION's
          // handler, not by this component's.
          { token: "a", via: "global" },
          { token: "t", via: "global" },
          { token: "p", via: "global" },
          { token: "u", via: "global" },
          { token: "d", via: "global" },
          { token: "n", via: "global" },
        ],
      },
    ],
  },
  {
    component: "components/LogViewer.tsx",
    separator: " · ",
    keys: [
      {
        id: "tab",
        label: "[/] switch tab",
        tokens: ["[", "]"],
        via: "handler",
        // Only offer the switch when there IS a second tab.
        when: (ctx) => Boolean(ctx.hasGrants),
      },
      { id: "close", label: "Esc: close", tokens: ["esc"], via: "handler" },
    ],
  },
  {
    component: "components/McpViewer.tsx",
    separator: " ",
    keys: [
      { id: "nav", label: "↑/↓ (j/k): navigate", tokens: ["up", "down", "j", "k"], via: "handler" },
      { id: "sign-in", label: "Enter: sign in", tokens: ["return"], via: "handler" },
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
      { id: "edit", label: "Enter/e: edit", tokens: ["return", "e"], via: "handler" },
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
    // R385: the Ink half of `/mode`. Same six keys as every other
    // picker in the app — the panel is a list, and a list is navigated
    // and confirmed the same way everywhere.
    component: "components/ModePicker.tsx",
    separator: " ",
    keys: [
      { id: "nav", label: "↑/↓ (j/k): navigate", tokens: ["up", "down", "j", "k"], via: "handler" },
      { id: "select", label: "Enter: select", tokens: ["return"], via: "handler" },
      { id: "close", label: "Esc: close", tokens: ["esc"], via: "handler" },
    ],
  },
  {
    component: "components/NotificationCenter.tsx",
    separator: " ",
    keys: [
      { id: "nav", label: "↑/↓ (j/k): navigate", tokens: ["up", "down", "j", "k"], via: "handler" },
      { id: "select", label: "Enter: select", tokens: ["return"], via: "handler" },
      { id: "settings", label: "Tab: settings", tokens: ["tab"], via: "handler" },
      { id: "close", label: "Esc: close", tokens: ["esc"], via: "handler" },
    ],
  },
  {
    component: "components/SearchBar.tsx",
    separator: " ",
    // The search bar has no handler of its own — the APPLICATION binds
    // Esc while the bar is open.
    keys: [{ id: "close", label: "Esc: close", tokens: ["esc"], via: "global" }],
  },
  {
    component: "components/StaleWarning.tsx",
    separator: " · ",
    keys: [
      { id: "reattach", label: "Enter/y: re-attach", tokens: ["return", "y"], via: "handler" },
      { id: "cancel", label: "n/Esc: cancel", tokens: ["n", "esc"], via: "handler" },
      { id: "swap", label: "Tab/←/→: swap", tokens: ["tab", "left", "right"], via: "handler" },
    ],
  },
  {
    component: "components/StatusBar.tsx",
    separator: " ",
    // The stale-session banner's re-attach affordance.
    keys: [{ id: "reattach", label: "Enter: re-attach", tokens: ["return"], via: "handler" }],
  },
  {
    component: "components/SubagentPanel.tsx",
    separator: " · ",
    // The panel has no handler of its own — the APPLICATION binds these
    // chords while the panel is open.
    keys: [
      { id: "select", label: "↑/↓: select", tokens: ["up", "down"], via: "global" },
      { id: "insert", label: "Enter: insert", tokens: ["return"], via: "global" },
      { id: "cancel", label: "c: cancel", tokens: ["c"], via: "global" },
      { id: "close", label: "Esc: close", tokens: ["esc"], via: "global" },
    ],
  },
  {
    component: "components/ThreadSelector.tsx",
    separator: " ",
    keys: [
      { id: "filter", label: "type to filter", tokens: [], via: "input" },
      { id: "nav", label: "↑/↓ (j/k): navigate", tokens: ["up", "down", "j", "k"], via: "handler" },
      { id: "select", label: "Enter: select", tokens: ["return"], via: "handler" },
      { id: "cancel", label: "Esc: cancel", tokens: ["esc"], via: "handler" },
    ],
  },
  {
    component: "components/UpdatePanel.tsx",
    separator: " ",
    keys: [
      { id: "changelog", label: "Enter: changelog", tokens: ["return"], via: "handler" },
      { id: "close", label: "Esc: close", tokens: ["esc"], via: "handler" },
    ],
  },
  {
    component: "components/Welcome.tsx",
    separator: " ",
    // The welcome screen has no handler of its own — the APPLICATION
    // binds Enter.
    keys: [],
    prose: [
      {
        id: "prompt",
        text: "· type a prompt and press Enter",
        tokens: [{ token: "return", via: "global" }],
      },
    ],
  },
  {
    component: "components/WorkflowPicker.tsx",
    separator: " ",
    keys: [
      { id: "nav", label: "↑/↓ (j/k): navigate", tokens: ["up", "down", "j", "k"], via: "handler" },
      { id: "run", label: "Enter: run", tokens: ["return"], via: "handler" },
      { id: "cancel", label: "Esc: cancel", tokens: ["esc"], via: "handler" },
    ],
  },
  {
    component: "components/chat/SubagentSpawnCard.tsx",
    separator: " ",
    keys: [{ id: "dismiss", label: "x: dismiss", tokens: ["x"], via: "handler" }],
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
  {
    component: "components/permissions/PresetSelector.tsx",
    separator: " · ",
    keys: [
      {
        id: "nav",
        label: "←/→ (h/l): navigate",
        tokens: ["left", "right", "h", "l"],
        via: "handler",
      },
      { id: "apply", label: "Enter: apply", tokens: ["return"], via: "handler" },
      { id: "quick", label: "1/2/3: quick-select", tokens: ["1", "2", "3"], via: "handler" },
    ],
    prose: [
      // The narrow-terminal variant repeats one line under EVERY
      // preset, so it stays short.
      {
        id: "compact",
        text: "Enter to apply · 1/2/3 quick",
        tokens: [
          { token: "return", via: "handler" },
          { token: "1", via: "handler" },
          { token: "2", via: "handler" },
          { token: "3", via: "handler" },
        ],
      },
      {
        id: "card-hi",
        text: "[ Enter / 1-3 to apply ]",
        tokens: [
          { token: "return", via: "handler" },
          { token: "1", via: "handler" },
          { token: "2", via: "handler" },
          { token: "3", via: "handler" },
        ],
      },
      {
        id: "card-idle",
        text: "[ 1-3 to apply ]",
        tokens: [
          { token: "1", via: "handler" },
          { token: "2", via: "handler" },
          { token: "3", via: "handler" },
        ],
      },
      { id: "card-applied", text: "[ applied ]", tokens: [] },
    ],
  },
  {
    component: "components/session/SessionControl.tsx",
    separator: " · ",
    keys: [
      { id: "act", label: "c/p/s: act", tokens: ["c", "p", "s"], via: "handler" },
      { id: "focus", label: "Tab/←/→: focus", tokens: ["tab", "left", "right"], via: "handler" },
      { id: "confirm", label: "Enter/Space: confirm", tokens: ["return", "space"], via: "handler" },
    ],
  },
  {
    component: "components/ThemePicker.tsx",
    separator: " ",
    keys: [
      { id: "nav", label: "↑/↓ (j/k): navigate", tokens: ["up", "down", "j", "k"], via: "handler" },
      { id: "select", label: "Enter: select", tokens: ["return"], via: "handler" },
      { id: "close", label: "Esc: close", tokens: ["esc"], via: "handler" },
    ],
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
 * @param ctx the dynamic bits (the preset count, the log viewer's tabs)
 */
export function hintFor(component: string, ctx: Record<string, unknown> = {}): string {
  const table = keyTable(component);
  if (!table) return "";
  const parts = table.keys.filter((k) => (k.when ? k.when(ctx) : true)).map((k) => k.label);
  if (table.suffix) {
    const tail = table.suffix(ctx);
    if (tail) parts.push(tail);
  }
  return parts.join(table.separator);
}

/**
 * A prose sentence a panel renders.
 *
 * @param component the component's path relative to `src/`
 * @param id the sentence's id
 */
export function proseFor(component: string, id: string): string {
  return keyTable(component)?.prose?.find((p) => p.id === id)?.text ?? "";
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
 *
 * <p>R381: a PROSE sentence can name keys too, and its "handler" ones
 * count — the input box's sentence names Enter, shift+Enter, ↑/↓ and
 * Tab, all of which its own handler binds.
 */
export function handlerTokens(component: string): string[] {
  const table = keyTable(component);
  if (!table) return [];
  const out = new Set<string>();
  for (const k of table.keys) {
    if (k.via !== "handler") continue;
    for (const t of k.tokens) out.add(t);
  }
  for (const p of table.prose ?? []) {
    for (const t of p.tokens) {
      if (t.via !== "handler") continue;
      out.add(t.token);
    }
  }
  return [...out].sort();
}

/**
 * The keys a component's PROSE sentences name, split by who binds them.
 *
 * <p>The gate checks the "global" ones against the app-level registry
 * (`src/keybindings.ts`) — a sentence that names a chord the app does
 * not offer is a promise nobody keeps.
 */
export function proseTokens(component: string): { handler: string[]; global: string[] } {
  const table = keyTable(component);
  const handler: string[] = [];
  const global: string[] = [];
  for (const p of table?.prose ?? []) {
    for (const t of p.tokens) {
      (t.via === "global" ? global : handler).push(t.token);
    }
  }
  return { handler: [...new Set(handler)].sort(), global: [...new Set(global)].sort() };
}

/**
 * The chords a component shows that the APPLICATION binds.
 *
 * <p>From the key LIST as well as from the prose: the command palette
 * has no handler of its own, and every chord it shows belongs to the
 * app-level handler.
 *
 * <p>The gate checks these against `src/keybindings.ts` — the one
 * app-level list — so a component cannot advertise a chord the
 * application does not handle.
 */
export function globalTokens(component: string): string[] {
  const table = keyTable(component);
  const out = new Set<string>();
  for (const k of table?.keys ?? []) {
    if (k.via !== "global") continue;
    for (const t of k.tokens) out.add(t);
  }
  for (const t of proseTokens(component).global) out.add(t);
  return [...out].sort();
}
