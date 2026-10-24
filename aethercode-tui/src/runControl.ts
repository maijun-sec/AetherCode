/**
 * R375: what "stop", "pause" and "continue" actually mean — and which
 * daemon method carries each one.
 *
 * ## What was wrong
 *
 * The TUI had THREE ideas of "stop the work", and the two the user
 * could see were fiction:
 *
 *   1. `cancel {runId}` — R363's Esc-interrupt. Real, registered, and
 *      the only one the daemon actually performs.
 *   2. `setContinuationStopped {stopped, sessionId}` — registered on
 *      the daemon, wired to the engine's continuation dispatcher, and
 *      **never called by the TUI at all**. The daemon even pushes a
 *      `todo_continuation_countdown` notification carrying a "Stop
 *      auto-continue" button; the TUI drops that notification on the
 *      floor.
 *   3. `task/resume` / `task/pause` / `task/kill` — what the status
 *      bar's Continue/Pause/Stop buttons and the `/pause` `/stop`
 *      `/continue` slash commands actually called. **None of these is
 *      registered on the daemon the TUI spawns.**
 *
 * ## Why #3 looked real but was not
 *
 * `TaskMethods` (aethercode-protocol) DOES register `task/kill`,
 * `task/resume` and nine siblings — but it is an adapter that PROXIES
 * them to a `SupervisorClient`, i.e. a **separate supervisor process
 * with its own TCP loopback socket**, and production code never
 * constructs it. `task/pause` is not even in its list (the
 * `TaskPauseParams` DTO exists; the method does not).
 *
 * So the buttons were wired to a service the TUI does not start, using
 * a method name that does not exist at all, passing a **session id**
 * where a task id was expected — and the rejection was swallowed:
 *
 *     void client.request("task/pause", { id: state.sessionId });
 *
 * No `.catch`, so a -32601 became an unhandled rejection and the
 * button silently did nothing. R369's honest-refusal table then told
 * the user "the daemon has no task registry, so there is no running
 * task to pause" — a **false** reason: the daemon has a task registry
 * (`TaskRegistry`), it just is not this concept, and the reason for
 * `task/kill` even described pausing.
 *
 * ## The mapping this round installs
 *
 * | action   | daemon method            | what it does                          |
 * |----------|--------------------------|---------------------------------------|
 * | stop     | `cancel {runId}`         | interrupts the run in flight (R721)   |
 * | pause    | `setContinuationStopped` | stops this session's auto-continue    |
 * | continue | `setContinuationStopped` | lets the auto-continue happen again   |
 *
 * "Pause" is the industry meaning of the word — VS Code's Copilot Chat
 * and Claude Code both have a session-level pause, where the session
 * keeps its state and the AUTOMATIC work stops. "Stop" is the
 * interrupt of the turn that is running right now, which is exactly
 * what Esc already did — so the button and the key now share ONE
 * implementation instead of two diverging ones.
 *
 * The daemon clears its flag on the next user-prompt submit, so the
 * TUI clears its mirror at the same moment. The two stay in sync
 * because the TUI knows about both places the flag can change.
 *
 * ## One implementation, three surfaces
 *
 * The status bar's buttons, the `/pause` `/stop` `/continue` commands
 * and the Esc key used to be three implementations of two ideas — and
 * the button one pointed at a method this daemon does not have. They
 * all come through here now: the Ink TUI and the line renderer each
 * have exactly one `stopActiveRun` and one `pauseAutoContinue`, and
 * the slash table only ever names a method.
 */

import type { RpcValue } from "./jsonrpc.js";

/** The three run-control actions the UI can offer. */
export type RunAction = "stop" | "pause" | "continue";

/** The daemon method that interrupts the run in flight. */
export const RUN_STOP_METHOD = "cancel";

/** The daemon method that flips the session's auto-continue flag. */
export const RUN_PAUSE_METHOD = "setContinuationStopped";

/**
 * The daemon method each action uses.
 *
 * <p>Pause and continue share one method because the daemon models
 * the flag as a boolean — the two differ only in the value, not in the
 * operation. Stop is a different operation entirely.
 */
export function runMethod(action: RunAction): string {
  return action === "stop" ? RUN_STOP_METHOD : RUN_PAUSE_METHOD;
}

/**
 * The params each action sends.
 *
 * <p>`stop` needs the id of the run to interrupt. `pause`/`continue`
 * need the flag value and NOTHING else: the daemon defaults the
 * session to the engine's current one, which is exactly the session
 * the user is looking at when they type `/pause`.
 *
 * <p>Not passing a session id is also the safe choice. The line
 * renderer keeps its session id as the placeholder "—" until the
 * daemon's first `getState` lands, and a blank-check on the daemon
 * side would not catch that — it would happily set the flag for a
 * session named "—".
 */
export function runParams(action: RunAction, ctx: { runId?: string | null } = {}): RpcValue {
  if (action === "stop") {
    return { runId: ctx.runId ?? "" };
  }
  return { stopped: action === "pause" } as unknown as RpcValue;
}

/**
 * The one line the user reads after the action.
 *
 * <p>Every branch says what HAPPENED, not what was attempted. A
 * failure names the reason; a success names the new state. "stopped"
 * alone would be a claim the daemon has not confirmed yet, so the
 * success case says "stopping" — the run's own `run_end` is what
 * confirms it.
 */
export function runOutcome(
  action: RunAction,
  ok: boolean,
  ctx: { runId?: string | null; error?: string | null } = {},
): string {
  if (!ok) {
    const why = ctx.error && ctx.error.length > 0 ? ctx.error : "the daemon refused";
    if (action === "stop") return `stop failed: ${why}`;
    if (action === "pause") return `pause failed: ${why}`;
    return `resume failed: ${why}`;
  }
  if (action === "stop") {
    return ctx.runId ? `stopping run ${ctx.runId}…` : "stopping the run in flight…";
  }
  if (action === "pause") return "auto-continue paused for this session";
  return "auto-continue resumed for this session";
}

/**
 * The sentence for an action that is ALREADY in the requested state.
 *
 * <p>Pause and resume are idempotent, and saying so is better than
 * firing a request whose only effect is to set a flag to the value it
 * already has. `null` means "this action is always worth doing" —
 * stop is, because "nothing is running" is a useful answer.
 */
export function runAlreadyInState(action: RunAction): string | null {
  if (action === "pause") return "auto-continue is already paused for this session";
  if (action === "continue") return "auto-continue is not paused for this session";
  return null;
}

/**
 * The sentence for "there is nothing to stop".
 *
 * <p>NOT an error message. "stop failed: no run is in flight" reads
 * like something broke; nothing broke — there was simply no run. The
 * two renderers must say the same thing here, so the sentence lives in
 * this module rather than in each of them.
 */
export function runNothingInFlight(): string {
  return "no run is in flight (the daemon did not report a run id)";
}

/**
 * The session-control state the status bar's buttons read.
 *
 * <p>Same vocabulary as `SessionControl`'s `SessionState`, declared
 * here so this module stays free of React and Ink (and can be
 * compiled and run by a gate on its own).
 */
export type RunControlState = "running" | "paused" | "completed" | "failed" | "cancelled";

/**
 * Map the TUI's stringly-typed status to the session-control state.
 *
 * <p>R375: a session can also be PAUSED — that is the daemon's
 * auto-continue flag, mirrored locally. `SessionControl` disables
 * "continue" unless the session is paused, so before this mapping
 * existed the Continue button was permanently greyed out, and the
 * pause/continue pair was unreachable from the keyboard as well.
 *
 * <p>A paused session stays paused whatever the transport is doing:
 * the flag is about the session's automatic work, not the run.
 */
export function runControlState(
  status: string | null | undefined,
  paused: boolean,
): RunControlState {
  if (paused) return "paused";
  const lo = String(status || "").toLowerCase();
  if (lo === "paused") return "paused";
  if (lo === "completed" || lo === "ok") return "completed";
  if (lo === "failed" || lo === "error") return "failed";
  if (lo === "cancelled") return "cancelled";
  return "running";
}
