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
 * The ONE registry of the Ink TUI's key bindings.
 *
 * ## Why this file exists
 *
 * Until R378 the TUI carried **three** hand-written copies of the same
 * list:
 *
 *   - `src/components/HelpOverlay.tsx` — the Ctrl-? overlay;
 *   - `src/components/Welcome.tsx` — the welcome screen's grid;
 *   - `src/ac-tui.ts` — the `--help` text.
 *
 * Three copies, three truths, and they had already contradicted each
 * other:
 *
 *   - the welcome screen said **"Ctrl-T — theme picker"**, but the
 *     handler binds Ctrl-T to re-open the welcome screen and the theme
 *     picker to **Ctrl-Shift-T**;
 *   - the help overlay said **"Ctrl-L — clear scrollback"**, but the
 *     handler's Ctrl-L is a placeholder that logs "(clear is a no-op in
 *     this build)" — and the branch that would have opened the log
 *     viewer sits BEHIND it, unreachable;
 *   - the overlay listed **13** chords; the app-level handler binds
 *     **35**. The missing ones included **Esc to stop a running turn** —
 *     the single most important binding in the product, and the one
 *     R363 exists to provide.
 *
 * The overlay also leaked eight development notes into user-facing text
 * ("R37: toggle side panel", "对应历史 round: …").
 *
 * **A second copy of a truth cannot be verified, and it drifts.** So
 * there is one copy now, and the R378 gate ties it to the handler.
 *
 * ## The contract
 *
 * - `keys` are the NORMALISED chords. The gate re-derives them from the
 *   app-level input handler in `tui.tsx` and asserts a two-way cover:
 *   every chord the handler binds is documented here, and every chord
 *   documented here is really bound.
 * - `text` is for conventions that are not chords (the user types `/`).
 *   Those are documented but not bound, and the gate does not pretend
 *   otherwise.
 * - `boundIn` names the file that binds a chord when it is NOT the
 *   app-level handler (the input box binds Enter).
 * - `method` names the RPC a chord calls, when it calls one. The gate
 *   cross-checks it against the refusal table, so the help can say
 *   which chords this daemon cannot serve.
 * - `short` is the terse form the welcome screen's dense grid uses.
 *
 * ## Lesson 1197 — a second copy of a truth drifts, and it drifts
 * ## silently
 *
 * Nothing failed. Every copy was a valid TypeScript array of valid
 * strings, and every gate that touched them was green. The only thing
 * that was wrong was that they disagreed with each other and with the
 * handler — and no gate was looking at that.
 *
 * The fix is not "be careful when editing the list". The fix is that
 * there is no list to edit twice.
 */

import { BACKEND_MISSING } from "./rpcCapabilities.js";

/** Which section of the help a binding belongs to. */
export type KeyGroup = "session" | "display" | "editing" | "subagent" | "permission";

export interface Keybinding {
  /**
   * Normalised chords this entry documents.
   *
   * <p>Normalised form: `ctrl+c`, `ctrl+shift+b`, `ctrl+1-9`, `esc`,
   * `tab`, `up`, `down`, `return`, or a bare letter.
   */
  keys?: string[];
  /**
   * A convention the user TYPES rather than presses (`/` opens the
   * slash dispatcher, `@` opens the file mention). Not a chord, so the
   * gate does not try to find it in a key handler.
   */
  text?: string;
  /** How the binding is shown to the user. */
  label: string;
  /** One line, for a person. No round numbers, no internal shorthand. */
  desc: string;
  /** The terse form for a dense grid. Falls back to `desc`. */
  short?: string;
  /** Which help section this belongs to. */
  group: KeyGroup;
  /** Set when the binding only applies in a narrower situation. */
  when?: string;
  /** The RPC this binding calls, when it calls one. */
  method?: string;
  /** The file that binds it, when it is not the app-level handler. */
  boundIn?: string;
  /** Show this on the welcome screen's dense grid. */
  welcome?: boolean;
}

/**
 * Every binding the Ink TUI offers.
 *
 * <p>Order within a group is the order the user meets them.
 */
export const KEYBINDINGS: ReadonlyArray<Keybinding> = [
  // --- session --------------------------------------------------------
  {
    keys: ["ctrl+c"],
    label: "Ctrl+C",
    desc: "exit the TUI",
    group: "session",
  },
  {
    keys: ["esc"],
    label: "Esc",
    desc: "stop the running turn, close an overlay, or drop the queued prompts",
    short: "stop the run",
    group: "session",
    method: "cancel",
    welcome: true,
  },
  {
    keys: ["ctrl+z"],
    label: "Ctrl+Z",
    desc: "rewind to the previous turn",
    group: "session",
    method: "rewind",
  },
  {
    keys: ["ctrl+1-9"],
    label: "Ctrl+1…9",
    desc: "switch between the main agent and the most recent sub-agents",
    group: "session",
    method: "loadSession",
  },
  {
    keys: ["ctrl+shift+b"],
    label: "Ctrl+Shift+B",
    desc: "go back to the previous view",
    group: "session",
  },
  {
    keys: ["ctrl+?"],
    label: "Ctrl+?",
    desc: "show the help overlay",
    group: "session",
    welcome: true,
  },
  {
    keys: ["b"],
    label: "b",
    desc: "bookmark the last turn",
    group: "session",
  },

  // --- display --------------------------------------------------------
  {
    keys: ["ctrl+b"],
    label: "Ctrl+B",
    desc: "toggle the sidebar",
    short: "sidebar",
    group: "display",
    welcome: true,
  },
  {
    keys: ["ctrl+d"],
    label: "Ctrl+D",
    desc: "toggle the right column",
    short: "right panel",
    group: "display",
    welcome: true,
  },
  {
    keys: ["ctrl+f"],
    label: "Ctrl+F",
    desc: "search the scrollback",
    short: "search",
    group: "display",
    welcome: true,
  },
  {
    keys: ["ctrl+p"],
    label: "Ctrl+P",
    desc: "open the command palette",
    group: "display",
  },
  {
    keys: ["ctrl+o"],
    label: "Ctrl+O",
    desc: "collapse or expand every tool card",
    group: "display",
  },
  {
    keys: ["ctrl+i"],
    label: "Ctrl+I",
    desc: "show or hide the model's thinking",
    group: "display",
  },
  {
    keys: ["ctrl+l"],
    label: "Ctrl+L",
    desc: "toggle the log viewer",
    short: "log viewer",
    group: "display",
    welcome: true,
  },
  {
    keys: ["ctrl+s"],
    label: "Ctrl+S",
    desc: "open the sub-agent panel",
    short: "sub-agents",
    group: "display",
    welcome: true,
  },
  {
    keys: ["ctrl+shift+t"],
    label: "Ctrl+Shift+T",
    desc: "choose a theme",
    short: "theme",
    group: "display",
    welcome: true,
  },
  {
    keys: ["ctrl+t"],
    label: "Ctrl+T",
    desc: "re-open the welcome screen",
    group: "display",
  },

  // --- editing --------------------------------------------------------
  {
    keys: ["tab"],
    label: "Tab",
    desc: "complete a slash command",
    short: "complete",
    group: "editing",
    welcome: true,
  },
  {
    keys: ["up", "down"],
    label: "↑ / ↓",
    desc: "walk the input history",
    short: "history",
    group: "editing",
    welcome: true,
  },
  {
    keys: ["return"],
    label: "Enter",
    desc: "send the prompt",
    short: "send",
    group: "editing",
    boundIn: "src/components/InputBox.tsx",
    welcome: true,
  },
  {
    keys: ["ctrl+e"],
    label: "Ctrl+E",
    desc: "expand or collapse the last turn",
    group: "editing",
  },
  {
    keys: ["d"],
    label: "d",
    desc: "expand or collapse a tool turn's details",
    group: "editing",
    when: "no permission prompt is on screen",
  },
  {
    text: "/",
    label: "/",
    desc: "open the slash-command dispatcher",
    short: "commands",
    group: "editing",
    welcome: true,
  },
  {
    text: "@",
    label: "@",
    desc: "mention a file",
    short: "mention",
    group: "editing",
    welcome: true,
  },

  // --- sub-agent panel ------------------------------------------------
  {
    keys: ["return"],
    label: "Enter",
    desc: "insert the focused sub-agent's result",
    group: "subagent",
    when: "the sub-agent panel is open",
  },
  {
    keys: ["c"],
    label: "c",
    desc: "cancel the focused sub-agent",
    group: "subagent",
    when: "the sub-agent panel is open",
  },
  {
    keys: ["v"],
    label: "v",
    desc: "view the focused sub-agent",
    group: "subagent",
    when: "the sub-agent panel is open",
  },

  // --- permission prompt ----------------------------------------------
  {
    keys: ["a", "1"],
    label: "A / 1",
    desc: "allow the tool",
    group: "permission",
    when: "a permission prompt is on screen",
  },
  {
    keys: ["t"],
    label: "T",
    desc: "allow it for this task",
    group: "permission",
    when: "a permission prompt is on screen",
  },
  {
    keys: ["p"],
    label: "P",
    desc: "allow it for this project",
    group: "permission",
    when: "a permission prompt is on screen",
  },
  {
    keys: ["u"],
    label: "U",
    desc: "allow it for you",
    group: "permission",
    when: "a permission prompt is on screen",
  },
  {
    keys: ["y", "2"],
    label: "Y / 2",
    desc: "allow it for this session",
    group: "permission",
    when: "a permission prompt is on screen",
  },
  {
    keys: ["d", "3"],
    label: "D / 3",
    desc: "deny the tool",
    group: "permission",
    when: "a permission prompt is on screen",
  },
  {
    keys: ["n", "4"],
    label: "N / 4",
    desc: "always deny it",
    group: "permission",
    when: "a permission prompt is on screen",
  },
];

/** The help sections, in the order the overlay renders them. */
export const KEY_GROUPS: ReadonlyArray<{ id: KeyGroup; title: string }> = [
  { id: "session", title: "session" },
  { id: "display", title: "display" },
  { id: "editing", title: "editing" },
  { id: "subagent", title: "sub-agent panel" },
  { id: "permission", title: "permission prompt" },
];

/** Every chord the registry documents, de-duplicated. */
export function registryChords(bindings: ReadonlyArray<Keybinding> = KEYBINDINGS): string[] {
  const out = new Set<string>();
  for (const b of bindings) for (const k of b.keys ?? []) out.add(k);
  return [...out].sort();
}

/** The bindings of one group, in registry order. */
export function bindingsIn(
  group: KeyGroup,
  bindings: ReadonlyArray<Keybinding> = KEYBINDINGS,
): Keybinding[] {
  return bindings.filter((b) => b.group === group);
}

/** The welcome screen's dense grid. */
export function welcomeBindings(bindings: ReadonlyArray<Keybinding> = KEYBINDINGS): Keybinding[] {
  return bindings.filter((b) => b.welcome);
}

/**
 * Can THIS daemon serve the binding?
 *
 * <p>Derived from the refusal table, never hand-written: a binding
 * whose RPC is on the table is one the user cannot use here, and the
 * help should say so rather than promise it.
 *
 * <p>R378: this is R377's rule applied to a key. The help must not
 * advertise a capability the daemon cannot serve, and the answer comes
 * from the SAME table the dispatcher consults, so the two cannot
 * disagree.
 *
 * @param b the binding
 * @returns true when the daemon cannot serve it
 */
export function bindingUnavailable(b: Keybinding): boolean {
  return !!b.method && BACKEND_MISSING.has(b.method);
}

/** The note shown next to a binding this daemon cannot serve. */
export function unavailableNote(b: Keybinding): string {
  return b.method ? `${b.method} is not available on this daemon` : "not available on this daemon";
}
