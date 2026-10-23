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
 * Methods the TUI may name that the TUI's daemon does not register.
 *
 * @type {ReadonlyMap<string, string>}
 */
export const BACKEND_MISSING: ReadonlyMap<string, string> = new Map<string, string>([
  [
    "agent/setDefault",
    "no 'set the active agent' RPC; updateAgent writes agent DEFINITIONS, listAgents lists them",
  ],
  [
    "effort/set",
    "no effort concept on the daemon at all; no registered name contains 'effort'",
  ],
  ["rewind", "no rewind handler; transcript rollback is unimplemented daemon-side"],
  [
    "session/tokens",
    "no per-session token RPC; getState carries the aggregate counters (see /metrics)",
  ],
  [
    "task/kill",
    "the daemon has no task registry; task/* lives on SupervisorRpcServer, which is a separate CLI",
  ],
  // R369: these two used to say "same as task/kill", which is internal
  // shorthand and reads as nonsense to the user who is looking at it
  // ("task/pause is not available on this daemon — same as task/kill").
  // Every reason in this table is shown verbatim, so each one has to
  // stand alone.
  ["task/pause", "the daemon has no task registry, so there is no running task to pause"],
  ["task/resume", "the daemon has no task registry, so there is no paused task to resume"],
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