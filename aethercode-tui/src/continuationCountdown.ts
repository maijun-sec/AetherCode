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
 * R379 — the daemon's auto-continuation countdown.
 *
 * ## What the daemon sends
 *
 * `EngineContinuationDispatcher.notifyCountdown` pushes a
 * `todo_continuation_countdown` notification:
 *
 *     { sessionId, runId, incompleteCount, totalCount, remainingMs,
 *       kind: "countdown-start" }
 *
 * `remainingMs` is the window the daemon gave itself before it
 * dispatches the continuation (`TodoContinuationHook` passes
 * `countdownSeconds * 1000`).
 *
 * ## What the TUI used to do with it
 *
 * Nothing. Neither renderer had a case for the notification, so the
 * daemon scheduled an automatic turn and the user saw NOTHING — no
 * warning, no clock, no hint that a turn was about to start on its
 * own. A turn that begins without the user asking is exactly the
 * kind of thing the user must be told about before it happens.
 *
 * ## The line
 *
 * Claude Code says: `Continuing automatically at 3:45pm · esc to cancel`.
 * The TUI says the same thing — and, unlike a decoration, the "esc to
 * cancel" half is wired to the RPC that actually stops the
 * continuation (`setContinuationStopped`, the same one `/pause` uses).
 *
 * ## One implementation, two renderers
 *
 * The line lives HERE so the Ink and the line renderer cannot word it
 * differently. A second copy of this string is a second copy of a
 * truth, and it drifts silently.
 */

/** The notification the daemon pushes when it schedules a continuation. */
export const COUNTDOWN_METHOD = "todo_continuation_countdown";

/** A pending auto-continuation, as the daemon described it. */
export interface ContinuationCountdown {
  /** The session the continuation belongs to. */
  sessionId: string;
  /** The run the continuation will produce. */
  runId: string;
  /** How many todo items are still open. */
  incompleteCount: number;
  /** How many todo items the list has in total. */
  totalCount: number;
  /** How long the daemon will wait, in milliseconds. */
  remainingMs: number;
  /** The absolute timestamp the continuation is expected to fire. */
  at: number;
}

/**
 * Parse the notification payload.
 *
 * <p>Returns null for a payload that does not carry a countdown — a
 * notification the daemon may extend later, or one from a newer
 * daemon with a different shape. The caller then does nothing, which
 * is the honest response to an event it does not understand.
 *
 * @param params the raw notification params
 * @param now the current epoch milliseconds (injected so the result is testable)
 */
export function parseCountdown(
  params: unknown,
  now: number = Date.now(),
): ContinuationCountdown | null {
  if (params === null || typeof params !== "object") return null;
  const p = params as Record<string, unknown>;
  const remainingMs =
    typeof p.remainingMs === "number" && p.remainingMs >= 0 ? p.remainingMs : null;
  if (remainingMs === null) return null;
  return {
    sessionId: typeof p.sessionId === "string" ? p.sessionId : "",
    runId: typeof p.runId === "string" ? p.runId : "",
    incompleteCount: typeof p.incompleteCount === "number" ? p.incompleteCount : 0,
    totalCount: typeof p.totalCount === "number" ? p.totalCount : 0,
    remainingMs,
    at: now + remainingMs,
  };
}

/**
 * The clock time of the continuation, the way Claude Code writes it:
 * `3:45pm`.
 *
 * <p>12-hour clock, no leading zero, lowercase meridiem. The TUI's
 * own timestamps use 24-hour `HH:MM`, but this string is borrowed
 * whole from Claude Code and the user recognises it at a glance.
 */
export function countdownClock(at: number): string {
  const d = new Date(at);
  let h = d.getHours() % 12;
  if (h === 0) h = 12;
  const mm = String(d.getMinutes()).padStart(2, "0");
  return `${h}:${mm}${d.getHours() < 12 ? "am" : "pm"}`;
}

/**
 * The line the user sees.
 *
 * <p>Exactly the shape Claude Code uses, so the two surfaces read the
 * same way: `Continuing automatically at 3:45pm · esc to cancel`.
 */
export function countdownLine(c: ContinuationCountdown): string {
  return `Continuing automatically at ${countdownClock(c.at)} · esc to cancel`;
}
