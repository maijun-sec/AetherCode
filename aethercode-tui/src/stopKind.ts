/**
 * R383 — the TUI's ONE stop-reason taxonomy.
 *
 * ## Why this module exists
 *
 * The engine DECORATES its stop reasons: a failure arrives as
 * `"error: WebClientResponseException$…: 500 from POST https://…"`, a
 * death spiral as `"loop_detected: empty_tool_input (4 consecutive empty
 * tool calls)"`. The canonical answer to "what kind of ending was that?"
 * lives in the engine (`StreamEvent.bareKindOf` / `isErrorOutcomeOf`).
 *
 * Before R383 the TUI had **two** hand-rolled copies of that answer —
 * `state.ts` and `line.ts` — and both were wrong in the same way: they
 * matched `"error_"` / `"_error"` with an UNDERSCORE, while the engine
 * emits `"error: …"` with a COLON. So the most common failure of all (an
 * upstream 4xx/5xx) was classified `"ok"`, and the StatusBar showed a
 * green **"✓ ready"** with no error banner. The desktop had its own
 * copy too, and that one missed `"loop_detected"` — so a death spiral
 * showed up as **● Idle**.
 *
 * The two copies were pinned to EACH OTHER by a test
 * (`line-runend.test.mjs`: "state.ts and line.ts classifyStopReason are
 * equivalent"). That test could not catch the bug: it proves the copies
 * agree, not that they are right. Two copies that are wrong together
 * agree perfectly.
 *
 * ## The rule now
 *
 * The daemon already tells us the verdict — R750d puts `isError` on the
 * wire, derived from the engine's own taxonomy — and R383 adds
 * `stopKind` (the bare kind) beside it. So:
 *
 * 1. **the verdict comes from the daemon** (`isError`);
 * 2. the bare kind comes from the daemon (`stopKind`) and only refines
 *    the WORDING (loop / max turns / empty);
 * 3. the local rule below is a FALLBACK for a daemon that expresses no
 *    verdict — and its failure set is pinned against the Java source by
 *    `scripts/test/r383-stop-kind.test.mjs`.
 *
 * A renderer never decides on its own whether a run failed.
 */

/** The display kinds the TUI renders. `null` = no run has finished yet. */
export type StopKind = "ok" | "loop" | "max_turns" | "error" | "empty" | null;

/**
 * The engine's failure kinds — the exact set `StreamEvent.isErrorOutcomeOf`
 * reports as a failure.
 *
 * This list is a FALLBACK copy (for an old daemon). It is pinned against
 * the Java source by the R383 gate: the gate extracts the `case "…"`
 * labels out of `isErrorOutcomeOf` and asserts this list matches.
 */
export const ENGINE_ERROR_KINDS = [
  "runtime_exception",
  "error",
  "no_run_end",
  "consumer_timeout",
  "same_error_hard_stop",
  "loop_detected",
  "stale_force_end",
] as const;

/**
 * Kinds that get the "loop" wording.
 *
 * `same_error_hard_stop` is the R181/R739 same-error death spiral — a
 * failure to the daemon, but the user-facing wording is "loop", because
 * that is what happened: the engine kept calling the same thing.
 */
export const LOOP_KINDS = ["loop_detected", "same_error_hard_stop"] as const;

/**
 * Any kind starting with `loop_` also gets the "loop" wording.
 *
 * The engine's hard-stop kind is always `loop_detected` (the specific
 * tier — `empty_tool_input` / `same_error` / `long_output` — travels in
 * the decoration, not the kind), so this prefix rule is a safety net for
 * the tiered variants. It is a WORDING refinement, never the verdict.
 *
 * `main_loop` must NOT match — a reason that merely CONTAINS "loop" is
 * not a loop. (Pinned by the R32-G regression guard.)
 */
export const LOOP_PREFIX = "loop_";

/** Kinds that get the "max turns" wording (the per-query turn cap). */
export const MAX_TURN_KINDS = ["max_iterations", "max_turns", "max_tool_iterations"] as const;

/** Kinds that mean "there was no input to run on". */
export const EMPTY_KINDS = ["empty_input", "empty"] as const;

/**
 * The engine's bare kind: the prefix before the first `:`, ` ` or `(`.
 *
 * A port of `StreamEvent.bareKindOf` — the daemon sends the bare kind
 * (R383), so this is only needed to classify a stop reason from an older
 * daemon that sends neither `stopKind` nor `isError`.
 */
export function bareKind(reason: string | null | undefined): string {
  if (reason == null) return "";
  const s = reason.trim();
  let cut = s.length;
  for (let i = 0; i < s.length; i++) {
    const c = s.charAt(i);
    if (c === ":" || c === " " || c === "(") {
      cut = i;
      break;
    }
  }
  return s.substring(0, cut);
}

/** What the daemon told us about the finished run. */
export interface RunEndSignal {
  /** The decorated reason, for display ("error: <class>: <msg>"). */
  stopReason?: string | null;
  /** R383: the engine's canonical kind ("error", "loop_detected", …). */
  stopKind?: string | null;
  /** R750d: the engine's failure verdict. */
  isError?: boolean;
}

/**
 * Classify a finished run.
 *
 * Order of authority:
 * 1. the daemon's `stopKind` (R383) — the canonical kind;
 * 2. the daemon's `isError` (R750d) — the canonical VERDICT;
 * 3. the local fallback — only when the daemon expressed NO verdict,
 *    i.e. an older daemon (or a transport that drops the field). Its
 *    failure set is `ENGINE_ERROR_KINDS`, pinned against the Java
 *    source by the R383 gate.
 */
export function classifyRunEnd(sig: RunEndSignal): StopKind {
  const raw = sig.stopReason ?? null;
  const wireKind = (sig.stopKind ?? "").trim();
  const hasWireKind = wireKind.length > 0;
  // The daemon expressed a VERDICT iff it sent `isError` at all. The
  // field is absent on a clean run (the daemon only writes it when the
  // engine's taxonomy says "failure"), so "absent" and "false" are
  // different things — and only the ABSENT case is a gap the local
  // fallback is allowed to fill.
  const hasVerdict = sig.isError !== undefined;
  // No signal at all → no run has finished (the pre-run state). Kept
  // distinct from "ok", which means "a run finished, cleanly".
  if (raw == null && !hasWireKind && !hasVerdict) return null;
  const kind = hasWireKind ? wireKind : bareKind(raw);

  // The specific wordings win over the generic failure: a loop is a
  // failure, but the user needs to hear "loop", not "error".
  if (LOOP_KINDS.includes(kind as (typeof LOOP_KINDS)[number])) return "loop";
  if (kind.startsWith(LOOP_PREFIX)) return "loop";
  if (MAX_TURN_KINDS.includes(kind as (typeof MAX_TURN_KINDS)[number])) return "max_turns";
  if (EMPTY_KINDS.includes(kind as (typeof EMPTY_KINDS)[number])) return "empty";

  // The daemon's verdict. A renderer never decides this on its own.
  if (sig.isError === true) return "error";

  // The daemon expressed NO verdict. Fall back to the engine's failure
  // set, applied to the bare kind. (This is the copy the R383 gate pins
  // against StreamEvent.isErrorOutcomeOf.)
  //
  // The condition is "no verdict", NOT "no stopKind". A daemon that
  // sends the kind but not the bit — a proxy that drops fields, or a
  // version skew — would otherwise have its failures reported as
  // successes, which is exactly the bug this module exists to remove.
  if (!hasVerdict && ENGINE_ERROR_KINDS.includes(kind as (typeof ENGINE_ERROR_KINDS)[number])) {
    return "error";
  }
  return "ok";
}
