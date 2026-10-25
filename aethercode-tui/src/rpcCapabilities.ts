/**
 * What the daemon the TUI actually spawns CANNOT do.
 *
 * ## Why this lives in src/ and not in a test
 *
 * Until R369 these seven entries lived only in
 * `scripts/test/r366-rpc-names.test.mjs`, where they existed to let the
 * gate stay green while keeping the gap countable. That made the gate's
 * allowlist the ONLY place the reasons were written down — so the UI,
 * which also needed to know them, had nowhere to read them from. The
 * table had to be duplicated or the UI had to stay silent.
 *
 * Moving it here makes one table serve both:
 *   - `handleSlash` consults it before dispatching, so a command that
 *     targets a missing method answers honestly instead of firing a
 *     request guaranteed to return -32601;
 *   - the R366 gate imports it, so the gate and the UI cannot drift.
 *
 * ## R377 — a refusal can hide a working feature
 *
 * The table had four entries. One of them — `effort/set` — was a
 * **name mismatch**, not a missing capability: the daemon calls the
 * thing a VARIANT and serves it through `switchVariant`, registered on
 * both transports since R285. The TUI had been refusing the user for
 * a feature the desktop has been driving the whole time.
 *
 * The other three were real gaps, and their REASONS were still wrong in
 * their details: `session/tokens` pointed at `getState` for the
 * aggregate counters, and `getState` carries no token counters at all
 * (the aggregates live in `CostTracker`).
 *
 * So the reason text is now part of the contract, not decoration: every
 * reason quotes the daemon's OWN identifiers, and the R377 gate checks
 * that each quoted identifier really exists in the daemon's code.
 *
 * ## The distinction that matters
 *
 * These are NOT unimplemented features of the TUI. Each is a REAL
 * surface that lives somewhere the TUI daemon does not, or a concept
 * the daemon has no name for. Telling the user "not available on this
 * daemon" is therefore accurate; "coming soon" would not be.
 *
 * Entries are `method -> why`, and the reason is user-visible: it is
 * appended to the message the TUI prints. Write it for a person.
 */

/**
 * The daemon's method for the reasoning-effort capability.
 *
 * <p>The USER says "effort"; the daemon says "variant". `Variant` is
 * documented as a "per-model effort / quality preset", inspired by
 * Claude Code's low / medium / high / xhigh levels, and `switchVariant`
 * is registered on BOTH transports. The desktop has driven it from its
 * Quality dropdown since R285.
 *
 * <p>R377: the TUI used to call `effort/set` — a method that does not
 * exist — and REFUSED the user, while the real one sat right there,
 * registered. The refusal was honest about the METHOD and a lie about
 * the CAPABILITY. A refusal that hides a working feature is worse than
 * no refusal: the user is told the thing is impossible when it is one
 * word away.
 *
 * <p>Lesson 1193 — a refusal is a claim about ANOTHER system's
 * vocabulary, and such a claim cannot verify itself. The fix is not a
 * better sentence; it is to make the claim about the daemon's own
 * identifiers, and to look the capability up in the daemon before
 * declaring it missing. See `docs/R377-capability-naming.md`.
 */
export const EFFORT_METHOD = "switchVariant";

/**
 * Methods the TUI may name that the TUI's daemon does not register.
 *
 * <p>Every reason below quotes the daemon's OWN identifiers — a class,
 * a method, a registered RPC — and the R377 gate checks that each
 * quoted identifier really exists in the daemon's code. A reason that
 * cannot be checked is a rumour.
 *
 * @type {ReadonlyMap<string, string>}
 */
export const BACKEND_MISSING: ReadonlyMap<string, string> = new Map<string, string>([
  // R379: the table is EMPTY, and that is the point.
  //
  // R377 left three entries here — `agent/setDefault`, `rewind`,
  // `session/tokens` — and every one of them was a REAL gap in the
  // daemon (not a name mismatch like `effort/set` was). R379 CLOSED
  // all three:
  //
  //   - `agent/setDefault` → `AgentRegistry` owns a default agent
  //     and the daemon installs its body into the system prompt, so
  //     the marker is a BEHAVIOUR, not a label nobody reads;
  //   - `rewind` → `AppState.truncateTranscript` +
  //     `Transcript.truncateTo` roll the conversation back, in
  //     memory AND on disk;
  //   - `session/tokens` → `CostTracker` keeps a per-session
  //     bucket and `QueryEngine` books usage against the live
  //     session.
  //
  // The mechanism STAYS: `handleSlash` still consults this table
  // before dispatching, and the Ink / line renderers still consult it
  // before calling a method directly. So the next capability that
  // lands without a daemon behind it is honest for free. An empty
  // table is not a dead guard — it is a guard that currently has
  // nothing to refuse.
  // R375: `task/kill`, `task/pause` and `task/resume` used to be here,
  // and every one of those entries was WRONG — twice over.
  //
  // The reasons claimed "the daemon has no task registry, so there is
  // no running task to pause". The daemon DOES have a task registry
  // (`TaskRegistry`, behind `listTasks` / `updateTaskStatus`); what it
  // does not have is a SUPERVISOR — `TaskMethods` is an adapter that
  // proxies `task/*` to a separate supervisor process that production
  // code never constructs, and `task/pause` is not even in its list.
  //
  // Worse, the TUI was not asking for a task at all: it was asking for
  // those names to interrupt the RUN the user was looking at, and
  // passing a SESSION id where a task id was expected.
  //
  // The capability the user actually wanted — stop the run in flight,
  // pause the session's automatic continuation — IS on the daemon
  // (`cancel` and `setContinuationStopped`). R375 points the TUI at
  // those, so the TUI no longer NAMES a method it cannot serve, and
  // these entries have nothing left to describe.
]);

/**
 * The reason this daemon cannot serve {@code method}, or undefined if
 * it can.
 *
 * @param {string | undefined} method
 * @returns {string | undefined}
 */
export function unsupportedReason(method: string | undefined): string | undefined {
  if (!method) return undefined;
  return BACKEND_MISSING.get(method);
}

/**
 * The sentence a user should see instead of a -32601 they cannot act
 * on. Kept here so the two renderers word it identically.
 *
 * @param {string} method
 * @param {string} why
 * @returns {string}
 */
export function unsupportedMessage(method: string, why: string): string {
  return `${method} is not available on this daemon — ${why}.`;
}
