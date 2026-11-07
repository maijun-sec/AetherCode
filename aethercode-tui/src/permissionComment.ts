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

/** The permission decisions a client can answer with. */
export type PermissionDecision = "allow" | "deny" | "always_allow" | "always_deny";

/**
 * The ONE registry of decision keys.
 *
 * <p>Each entry carries the key, the decision it produces, the label the
 * comment box shows, AND the scope a remembered decision installs. They used
 * to be TWO parallel lists (`DECISION_KEYS` and `DECISION_SCOPE`), and they
 * had already drifted: the scope list carried a `y` the key list did not.
 * Two lists of the same keys is Lesson 1207's shape — one "list" that is
 * really several lists, and every one of them drifts.
 *
 * <p>The KEY MAP is the same one the TUI's decision keys use, so "the key
 * you press to answer" and "the key you press to arm the answer" are the
 * same key — one vocabulary, not two (R380's one-list rule).
 *
 * <p>`A` installs nothing (allow once). `T` / `Y` are session-scope rules.
 * `P` / `U` persist to the project / user permission files. `N` is a
 * session-scope deny rule.
 */
export const DECISION_KEYS: ReadonlyArray<{
  key: string;
  decision: PermissionDecision;
  label: string;
  scope?: RememberScope;
}> = [
  { key: "a", decision: "allow", label: "allow once" },
  { key: "t", decision: "always_allow", label: "allow for this task", scope: "session" },
  { key: "p", decision: "always_allow", label: "allow for this project", scope: "project" },
  { key: "u", decision: "always_allow", label: "allow for this user", scope: "user" },
  { key: "y", decision: "always_allow", label: "allow for this task", scope: "session" },
  { key: "d", decision: "deny", label: "deny once" },
  { key: "n", decision: "always_deny", label: "deny and remember", scope: "session" },
] as const;

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
}

/** A fresh comment box. */
export function newCommentBox(): CommentBoxState {
  return { text: "", armed: DEFAULT_ARMED_DECISION };
}

/**
 * Resolve a decision key (case-insensitive) to its decision + scope.
 *
 * <p>Returns `null` for a key that is not a decision key — the caller then
 * treats the key as ordinary text.
 */
export function resolveDecisionKey(
  key: string,
): { decision: PermissionDecision; scope?: RememberScope } | null {
  const k = key.toLowerCase();
  for (const entry of DECISION_KEYS) {
    if (entry.key === k) {
      return { decision: entry.decision, scope: entry.scope };
    }
  }
  return null;
}

/**
 * Arm the comment box with a decision.
 *
 * <p>`Ctrl+<key>` is the only way to arm: while the box is open the plain
 * letter keys are text, so a plain "d" must not deny.
 */
export function armDecision(state: CommentBoxState, decisionKey: string): CommentBoxState {
  const resolved = resolveDecisionKey(decisionKey);
  if (!resolved) return state;
  return {
    text: state.text,
    armed: resolved.decision,
    scope: resolved.scope,
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

/** The label of the armed decision, for the box header. */
export function armedLabel(state: CommentBoxState): string {
  const entry = DECISION_KEYS.find((e) => e.decision === state.armed);
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
 */
export function permissionResponseParams(
  requestId: string,
  decision: PermissionDecision,
  scope: RememberScope | undefined,
  comment: string | undefined,
  renderer: string,
  reason?: string,
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
  return params;
}
