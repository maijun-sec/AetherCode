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
 * R388r — the file-changes card's ACTIONS.
 *
 * <p>★ WHY THIS FILE EXISTS ★
 *
 * <p>R388q shipped a card that ADVERTISED three actions —
 * `查看文件 f`, `[撤销 u]`, `[审查 r]` — and **none of them did
 * anything**. Two independent defects:
 *
 * <ol>
 *   <li>{@code Scrollback} passed **no callbacks**, so the card's
 *       {@code onUndo} / {@code onReview} were undefined;</li>
 *   <li>the card only handled keys when {@code focused} was true — and
 *       **nothing ever set it to true**. The handler was dead code.</li>
 * </ol>
 *
 * <p>★ THAT IS R388p's DEFECT CLASS ★ "A key that does nothing is
 * worse than a key that is absent: the user concludes the feature is
 * broken." The card was worse than absent — it looked like a working
 * affordance.
 *
 * <p>★ AND THE KEYS MUST NOT STEAL THE USER'S TYPING ★
 *
 * <p>Measured: Ink's {@code useInput} delivers a keystroke to EVERY
 * mounted hook — a letter typed into the prompt ALSO reaches the app's
 * command handler. So a bare-letter command fires **while the user is
 * typing**, and the app's existing `d` key has exactly that behaviour
 * today.
 *
 * <p>So the card's letters are **command-mode keys**: they fire only
 * when the prompt is EMPTY. That is the REPL convention, it is what
 * the user expects from a label that reads `[撤销 u]`, and it never
 * takes a keystroke the user meant as text.
 *
 * <p>★ WHY A PLAIN .ts FILE ★ A gate script must be able to import
 * it; a {@code .tsx} component would pull React and Ink into the gate.
 */

/**
 * The three actions the card offers.
 *
 * <p>★ ONE list, and it is the ONLY list. ★ The card renders it, the
 * app dispatches on it, and the gate reads it. A second copy of "what
 * the card can do" is Lesson 1207's shape — one "list" that is really
 * several lists, and every one of them drifts.
 */
export const FILE_CARD_ACTIONS = ["showFiles", "undo", "review"] as const;

/** One of the card's actions. */
export type FileCardAction = (typeof FILE_CARD_ACTIONS)[number];

/** What each action does, and how the card shows it. */
export interface FileCardActionInfo {
  /** The label the card shows. */
  label: string;
  /** The key that triggers it. */
  key: string;
  /** What it does, in one line. */
  desc: string;
}

/**
 * The action table.
 *
 * <p>★ The `key` IS the contract. ★ The card renders the key, the app
 * binds the key, and the gate checks that the card's rendered key is
 * the key the app actually binds — so a card cannot advertise a key
 * that nothing handles.
 */
export const FILE_CARD_ACTION_INFO: Readonly<Record<FileCardAction, FileCardActionInfo>> = {
  showFiles: { label: "查看文件", key: "f", desc: "list the files this turn changed" },
  undo: { label: "撤销", key: "u", desc: "open the rewind menu to undo the change" },
  review: { label: "审查", key: "r", desc: "show the per-file detail" },
};

/**
 * The action a keystroke triggers, or null.
 *
 * <p>★ THE PROMPT MUST BE EMPTY ★ This is the whole point of the
 * module: a letter typed into the prompt is TEXT, not a command. The
 * app's `d` key (expand a tool turn's details) has no such guard, and
 * typing "d" in the prompt expands a card — a pre-existing collision
 * this module does NOT copy.
 *
 * @param key the pressed key, lowercased
 * @param promptEmpty is the prompt empty?
 * @returns the action, or null
 */
export function fileCardActionFor(
  key: string | undefined,
  promptEmpty: boolean,
): FileCardAction | null {
  if (!promptEmpty) return null;
  if (!key) return null;
  const k = key.toLowerCase();
  for (const action of FILE_CARD_ACTIONS) {
    if (FILE_CARD_ACTION_INFO[action].key === k) return action;
  }
  return null;
}

/**
 * Is this action live right now?
 *
 * <p>★ THE CARD DIMS ITS ACTIONS WHEN THEY CANNOT FIRE ★ A card that
 * shows `[撤销 u]` and ignores the key is lying. When the prompt has
 * text, the keys are text — so the card says so, by dimming them.
 * The user learns WHY the key did nothing instead of concluding the
 * feature is broken.
 *
 * @param promptEmpty is the prompt empty?
 */
export function fileCardActionsLive(promptEmpty: boolean): boolean {
  return promptEmpty;
}

/**
 * What an action DOES, in terms the app can carry out.
 *
 * <p>★ WHY THE EFFECT IS A VALUE ★ The app's `useInput` handler cannot
 * be mounted in isolation, so a gate cannot drive it. But the app's
 * handler is three lines: look up the action, then carry out its
 * EFFECT. So the EFFECT is the part worth testing — and it is a
 * value, so the gate can assert on it directly.
 *
 * <p>★ WHY THIS MATTERS ★ A gate that greps the source for
 * `openPanel", id: "checkpoints"` proves that the STRING is in the
 * file — and the string is ALSO in the `Esc Esc` handler, so the grep
 * passes even when the card's undo action does something else
 * entirely. That is a check a LOOKUP can satisfy, and it is not a
 * check. Here the effect is a VALUE, so a mutation that changes the
 * effect changes the value, and the gate sees it.
 *
 * <p>★ AND IT KEEPS ONE IMPLEMENTATION ★ The app maps an effect to a
 * dispatch in ONE place. The card, the app and the gate all read the
 * same table, so they cannot drift.
 */
export type FileCardEffect =
  /** Toggle the group's per-file list open or closed. */
  | { kind: "toggleFiles"; turnId: number }
  /** Open the rewind menu, so the user picks the point to roll back to. */
  | { kind: "openRewind" }
  /** There is no card to act on. */
  | { kind: "none" };

/**
 * The EFFECT an action has, given the card it applies to.
 *
 * <p>★ 撤销 OPENS THE REWIND MENU, IT DOES NOT GUESS ★ The daemon's
 * restore is CHECKPOINT-granular — one restore puts back a batch of
 * files AND a stretch of conversation — while the card is
 * turn-granular. So the card does not guess which point to roll back
 * to: it opens the menu and the user picks the point. (The same path
 * `Esc Esc` uses.)
 *
 * @param action the action
 * @param turnId the group's first turn id, or undefined when there is
 *   no card on screen
 * @returns the effect
 */
export function fileCardEffect(action: FileCardAction, turnId: number | undefined): FileCardEffect {
  if (turnId === undefined) return { kind: "none" };
  if (action === "undo") return { kind: "openRewind" };
  // "showFiles" and "review" both reveal the per-file detail. They are
  // two names for one effect, and the table says so — a second copy of
  // this mapping would be a second truth.
  return { kind: "toggleFiles", turnId };
}

/**
 * The effect of a keystroke, or null when the keystroke is not a
 * card command.
 *
 * <p>This is the WHOLE of the card's keyboard contract, as one pure
 * function: press a key, get an effect. The app carries the effect
 * out; the gate asserts on it.
 *
 * @param key the pressed key, lowercased
 * @param promptEmpty is the prompt empty?
 * @param turnId the focused card's group id, or undefined
 * @returns the effect, or null when the key is not a card command
 */
export function fileCardEffectFor(
  key: string | undefined,
  promptEmpty: boolean,
  turnId: number | undefined,
): FileCardEffect | null {
  const action = fileCardActionFor(key, promptEmpty);
  if (!action) return null;
  return fileCardEffect(action, turnId);
}
