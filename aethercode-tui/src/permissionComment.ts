/**
 * R388l — the permission comment box.
 *
 * <p>Claude Code lets a user attach a free-text comment to a permission
 * answer: `Tab` on the selected option opens a comment field, and the
 * comment reaches the model. A boolean gate becomes a communication channel.
 *
 * <p>This module is the PURE part of that feature — no React, no Ink, no
 * terminal. That split is deliberate (R388k Lesson 1323): a gate script
 * cannot import a `.tsx` component (the bundle pulls in `react`/`ink`), so
 * the logic has to live in a plain `.ts` module to be testable at all.
 *
 * <p>The contract:
 *
 * <ul>
 *   <li>Typing into the comment box appends to the comment; the decision
 *       keys are SUSPENDED while the box is open (otherwise "d" would
 *       deny instead of typing the letter d).</li>
 *   <li>{@code Ctrl+<decision key>} arms the decision the comment will be
 *       sent with. The armed decision is visible in the box header, so the
 *       user can see what they are about to send.</li>
 *   <li>The default armed decision is DENY. A user who opens a comment box
 *       is almost always saying "not exactly this" — and a silent default
 *       that ALLOWS would be the dangerous direction to be wrong in.</li>
 *   <li>{@code Enter} sends the armed decision plus the comment.
 *       {@code Esc} closes the box WITHOUT replying (the ask stays
 *       pending) — cancelling must not be indistinguishable from answering.</li>
 * </ul>
 */

// R388n: the permission-mode vocabulary. The ONE owner of the daemon's
// `PermissionMode` enum names is `src/permissionModes.ts` (R385) — this
// module does not restate a mode name, it imports the catalog.
import { MODE, type PermissionModeName } from "./permissionModes.js";

/** The permission decisions a client can answer with. */
export type PermissionDecision = "allow" | "deny" | "always_allow" | "always_deny";

/**
 * The ONE registry of decision keys.
 *
 * <p>Each entry carries the key, the decision it produces, the label the
 * comment box shows, the scope a remembered decision installs, AND — since
 * R388n — the permission MODE the decision switches the session to (if any).
 * They used to be TWO parallel lists (`DECISION_KEYS` and `DECISION_SCOPE`),
 * and they had already drifted: the scope list carried a `y` the key list
 * did not. Two lists of the same keys is Lesson 1207's shape — one "list"
 * that is really several lists, and every one of them drifts.
 *
 * <p>The KEY MAP is the same one the TUI's decision keys use, so "the key
 * you press to answer" and "the key you press to arm the answer" are the
 * same key — one vocabulary, not two (R380's one-list rule).
 *
 * <p>`A` installs nothing (allow once). `T` / `Y` are session-scope rules.
 * `P` / `U` persist to the project / user permission files. `N` is a
 * session-scope deny rule. `M` is the R388n "yes, and switch to auto mode"
 * answer: it approves the call AND raises the session's permission mode.
 *
 * <p>★ WHY `mode` LIVES HERE ★
 *
 * <p>The mode a decision switches to is a PROPERTY OF THE DECISION — it is
 * not a separate list of "modes a decision can install". Putting it in a
 * second registry would reproduce Lesson 1207 exactly: the key list would
 * say `m` exists, the mode list would say which mode, and the two would
 * drift (a key that arms a decision but installs no mode, or installs a mode
 * the daemon has no enum constant for).
 *
 * <p>The MODE NAME is the daemon's enum constant, and it is written here
 * and nowhere else — `src/permissionModes.ts` owns the vocabulary and this
 * module imports it, so the daemon's `PermissionMode` enum remains the only
 * authority (R385's rule).
 */
export const DECISION_KEYS: ReadonlyArray<{
  key: string;
  decision: PermissionDecision;
  label: string;
  scope?: RememberScope;
  /**
   * R388n: the permission mode this decision SWITCHES the session to.
   *
   * <p>`undefined` = the decision changes no mode. A decision with a mode
   * means "approve this call, and from now on behave like this mode".
   */
  mode?: PermissionModeName;
  /**
   * R388n: the POSITIONAL alias — the digit the user can press
   * instead of the letter.
   *
   * <p>★ This is DATA, not a derived position. ★
   *
   * <p>Deriving it from the array's order looked natural: "the Nth
   * entry is N". But the registry carries EIGHT entries while there are
   * only FIVE answers the user can pick numerically — `t` and `y` are
   * the SAME answer (allow for this task), and `t` has no number at
   * all. A derived numbering therefore produced chords `6`, `7` and
   * `8` that the help does not document and the user cannot discover.
   *
   * <p>That is Lesson 1207's shape one level down: the list of
   * NUMBERS was a second list, derived from the first, and it drifted
   * the moment the first list gained an entry. So the number is now a
   * field on the entry, and the gate can check that the numbers are
   * exactly 1..N with no gaps and no duplicates.
   */
  number?: number;
}> = [
  { key: "a", decision: "allow", label: "allow once", number: 1 },
  {
    key: "t",
    decision: "always_allow",
    label: "allow for this task",
    scope: "session",
  },
  {
    key: "p",
    decision: "always_allow",
    label: "allow for this project",
    scope: "project",
  },
  {
    key: "u",
    decision: "always_allow",
    label: "allow for this user",
    scope: "user",
  },
  { key: "y", decision: "always_allow", label: "allow for this task", scope: "session", number: 2 },
  { key: "d", decision: "deny", label: "deny once", number: 3 },
  { key: "n", decision: "always_deny", label: "deny and remember", scope: "session", number: 4 },
  {
    key: "m",
    decision: "allow",
    label: "allow and switch to auto mode",
    mode: MODE.ACCEPT_TASK,
    number: 5,
  },
] as const;

/**
 * The decision keys that carry a POSITIONAL alias, in numeric order.
 *
 * <p>These are the answers a user can pick by pressing a digit — the
 * projection the number keys resolve through. The NUMBER lives on the
 * entry, so this list is a VIEW, not a second copy.
 */
export const NUMBERED_DECISION_KEYS = DECISION_KEYS.filter(
  (e) => typeof e.number === "number",
).sort((a, b) => (a.number as number) - (b.number as number));

/** The decision a comment box sends when the user did not arm one. */
export const DEFAULT_ARMED_DECISION: PermissionDecision = "deny";

/** The scope a remembered decision installs, per decision key. */
export type RememberScope = "session" | "project" | "user";

/**
 * The state of the comment box.
 *
 * <p>`armed` is the decision the comment will be sent with. It defaults to
 * {@link DEFAULT_ARMED_DECISION}.
 */
export interface CommentBoxState {
  /** The comment text so far. */
  text: string;
  /** The decision the comment will be sent with. */
  armed: PermissionDecision;
  /** The scope the armed decision installs, if any. */
  scope?: RememberScope;
  /**
   * R388n: the permission MODE the armed decision switches the session to.
   *
   * <p>`undefined` = the armed decision changes no mode.
   */
  mode?: PermissionModeName;
}

/** A fresh comment box. */
export function newCommentBox(): CommentBoxState {
  return { text: "", armed: DEFAULT_ARMED_DECISION };
}

/**
 * Resolve a decision key (case-insensitive) to its decision + scope + mode.
 *
 * <p>Returns `null` for a key that is not a decision key — the caller then
 * treats the key as ordinary text.
 *
 * <p>R388n: the resolved MODE travels with the decision, so the caller
 * cannot pick one key and apply another key's mode.
 */
export function resolveDecisionKey(
  key: string,
): { decision: PermissionDecision; scope?: RememberScope; mode?: PermissionModeName } | null {
  const k = key.toLowerCase();
  for (const entry of DECISION_KEYS) {
    if (entry.key === k) {
      return { decision: entry.decision, scope: entry.scope, mode: entry.mode };
    }
  }
  return null;
}

/**
 * Arm the comment box with a decision.
 *
 * <p>`Ctrl+<key>` is the only way to arm: while the box is open the plain
 * letter keys are text, so a plain "d" must not deny.
 *
 * <p>R388n: arming a decision that carries a MODE also records the mode,
 * so the comment box's "sends as:" header can show what the answer will
 * do — including the mode it will switch to.
 */
export function armDecision(state: CommentBoxState, decisionKey: string): CommentBoxState {
  const resolved = resolveDecisionKey(decisionKey);
  if (!resolved) return state;
  return {
    text: state.text,
    armed: resolved.decision,
    scope: resolved.scope,
    mode: resolved.mode,
  };
}

/** Append a character to the comment. */
export function appendComment(state: CommentBoxState, ch: string): CommentBoxState {
  return { ...state, text: state.text + ch };
}

/** Delete the last character of the comment. */
export function deleteLastChar(state: CommentBoxState): CommentBoxState {
  if (state.text.length === 0) return state;
  return { ...state, text: state.text.slice(0, -1) };
}

/**
 * The label of the armed decision, for the box header.
 *
 * <p>★ R388n: the lookup must match the SCOPE and the MODE too, not just
 * the decision. ★
 *
 * <p>Two decision keys share the `allow` decision — `a` ("allow once") and
 * `m` ("allow and switch to auto mode"). A lookup by decision alone finds
 * the FIRST match, so arming `m` would show "allow once": the header would
 * describe a DIFFERENT answer from the one about to be sent. That is the
 * worst kind of wrong — the user reads the header, believes no mode will
 * change, and presses Enter.
 *
 * <p>So the entry is found by the WHOLE identity: decision + scope + mode.
 * When no entry matches (a decision the catalog does not carry), the raw
 * decision name is the honest answer.
 */
export function armedLabel(state: CommentBoxState): string {
  const entry = DECISION_KEYS.find(
    (e) => e.decision === state.armed && e.scope === state.scope && e.mode === state.mode,
  );
  return entry ? entry.label : state.armed;
}

/**
 * The wire params for the `permissionResponse` RPC.
 *
 * <p>Two fields, on purpose:
 *
 * <ul>
 *   <li>`reason` — the mechanical audit trail ("TUI picked deny"). It was
 *       there before R388l and keeps its meaning.</li>
 *   <li>`comment` — the USER'S OWN WORDS. Kept separate so the daemon can
 *       present the human sentence to the model without dressing
 *       boilerplate up as the user's voice.</li>
 * </ul>
 *
 * <p>An empty comment is OMITTED, not sent as `""` — a client that has no
 * comment must produce byte-identical params to the pre-R388l client, so a
 * daemon on the old wire sees nothing new.
 *
 * <p>★ R388n — the `mode` field. ★
 *
 * <p>The "yes, and switch to auto mode" answer does TWO things: it approves
 * the call, and it raises the session's permission mode. The permission
 * decision and the mode change are separate concerns, so they travel as
 * separate fields — the same reasoning as `reason` vs `comment`.
 *
 * <p>The `mode` field is OMITTED unless the decision actually carries one.
 * A reply without a mode must produce byte-identical params to the
 * pre-R388n reply, so an old daemon sees nothing new — and an old daemon
 * that DID see a `mode` key would not know what to do with it.
 */
export function permissionResponseParams(
  requestId: string,
  decision: PermissionDecision,
  scope: RememberScope | undefined,
  comment: string | undefined,
  renderer: string,
  reason?: string,
  mode?: PermissionModeName,
): { [k: string]: string } {
  const params: { [k: string]: string } = {
    requestId,
    decision,
    // R388m: an explicit reason OVERRIDES the default
    // "`<renderer>` picked `<decision>`". The default is an audit
    // trail; the explicit reason is the EXPLANATION the model reads
    // when the call was denied for a reason the user did not choose
    // (nobody at the keyboard, a non-interactive run, a timeout).
    // Without it the model sees "line mode picked deny" and has no
    // idea whether retrying could ever work.
    reason: reason && reason.trim() ? reason.trim() : `${renderer} picked ${decision}`,
  };
  const c = comment?.trim();
  if (c) params.comment = c;
  // R388n: the mode travels only when the decision carries one.
  if (mode) params.mode = mode;
  return params;
}
