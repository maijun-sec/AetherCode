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
 * R388m — the permission DEADLINE contract.
 *
 * <p>Two things every renderer needs to know about a pending permission
 * ask, and neither of them could be derived before:
 *
 * <ol>
 *   <li><b>WHEN does it expire?</b> The daemon is the authority. It
 *       publishes {@code expiresAtMs} on the {@code permission_request}
 *       notification, so the client's countdown runs on the SAME clock the
 *       daemon will enforce.</li>
 *   <li><b>WHY was it denied, in the model's terms?</b> When nobody can
 *       approve (a headless run, a piped stdin), the deny reason has to
 *       say so, and say <i>do not retry</i> — otherwise the model re-emits
 *       the same tool call and the run loops.</li>
 * </ol>
 *
 * <p>This module is the PURE part (no React, no Ink, no terminal), so a
 * gate script can import it directly — R388k Lesson 1323: logic that
 * lives only inside a `.tsx` component cannot be tested at all.
 *
 * <p>★ ONE vocabulary, three renderers. ★ The Ink TUI, the line TUI and
 * the headless {@code --print} driver all deny for the same reason, and
 * they say it in the same words. Three copies of a denial message is
 * Lesson 1207's shape: one "list" that is really several, and every one
 * of them drifts.
 */

/**
 * The default deadline the daemon enforces on a permission ask.
 *
 * <p>Matches `JsonRpcPermissionPrompter`'s default timeout (5 minutes).
 * The daemon's value is authoritative — this is only the client's
 * fallback for a daemon that does not publish {@code expiresAtMs}.
 */
export const DEFAULT_PERMISSION_TIMEOUT_MS = 300_000;

/**
 * The countdown window the client renders.
 *
 * <p>★ 120 seconds, like Claude Code. ★
 *
 * <p>The purpose of a countdown is NOT "the tool call times out at 2:00"
 * — it is that a person who walked away from the keyboard comes back and
 * sees "2:00 → 1:59 → …" instead of a stalled prompt, and that an
 * unattended session does not hang for five minutes.
 *
 * <p>★ IMPORTANT: the countdown is a CLIENT-side promise, not the
 * daemon's timeout. ★ The daemon's own deadline must be LONGER than this
 * window, or the daemon would deny underneath a client that is still
 * counting. The daemon's 5-minute timeout is longer than 2 minutes, so
 * the client always wins the race and the user gets a truthful message
 * ("nobody answered in 2 minutes") rather than a bare timeout.
 */
export const PERMISSION_COUNTDOWN_MS = 120_000;

/**
 * The deadline carried by a permission ask.
 *
 * <p>Both fields come from the daemon. `expiresAtMs` is absent when the
 * daemon does not publish one (an older daemon, or a synthetic ask from
 * the demo), and the client then falls back to {@link DEFAULT_PERMISSION_TIMEOUT_MS}.
 */
export interface PermissionDeadline {
  /** The daemon's deadline, in epoch milliseconds. */
  expiresAtMs: number;
  /** The full window, in milliseconds (so the client can show "2:00" at the start). */
  timeoutMs: number;
}

/**
 * Read the deadline off a `permission_request` payload.
 *
 * <p>Returns `null` when the daemon published no deadline — the caller
 * then has no countdown to show, and must NOT invent one (a guessed
 * deadline is how a client ends up denying a call the user was about to
 * approve).
 */
export function readDeadline(
  params: Record<string, unknown> | null | undefined,
): PermissionDeadline | null {
  if (!params) return null;
  const expires = params.expiresAtMs;
  if (typeof expires !== "number" || !Number.isFinite(expires) || expires <= 0) return null;
  const timeout =
    typeof params.timeoutMs === "number" && params.timeoutMs > 0
      ? params.timeoutMs
      : DEFAULT_PERMISSION_TIMEOUT_MS;
  return { expiresAtMs: expires, timeoutMs: timeout };
}

/**
 * The seconds left on the deadline, floored at 0.
 *
 * <p>Floored, not rounded: a card that says "0:00" for the last second
 * and then flips to "expired" is honest; a card that rounds UP shows
 * "0:01" while there is no time left, and the user presses a key that
 * arrives too late.
 */
export function secondsLeft(deadline: PermissionDeadline | null, nowMs: number): number {
  if (!deadline) return 0;
  const ms = deadline.expiresAtMs - nowMs;
  if (ms <= 0) return 0;
  return Math.floor(ms / 1000);
}

/**
 * Format the remaining time as `M:SS`.
 *
 * <p>The shape is the one a person reads fastest: "1:59" says "about two
 * minutes", "119" says "a number".
 */
export function formatCountdown(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds));
  const m = Math.floor(s / 60);
  const r = s % 60;
  return `${m}:${r.toString().padStart(2, "0")}`;
}

/**
 * The countdown line the permission card shows.
 *
 * <p>`null` when there is no deadline — the card then renders exactly as
 * it did before R388m (no countdown, no false promise of one).
 *
 * <p>★ The wording names the CONSEQUENCE. ★ "auto-denies in 1:12" tells
 * the user what will happen if they do nothing; a bare "1:12" leaves
 * them to guess whether it is a timeout, a clock, or a countdown.
 */
export function countdownLine(deadline: PermissionDeadline | null, nowMs: number): string | null {
  if (!deadline) return null;
  const left = secondsLeft(deadline, nowMs);
  if (left <= 0) return "no response — denying";
  return `auto-denies in ${formatCountdown(left)}`;
}

/**
 * The deny reason for a run in which NOBODY CAN APPROVE.
 *
 * <p>★ THIS IS THE CONTRACT. ★
 *
 * <p>Claude Code's rule: in a non-interactive run the prompt is never
 * shown, the call is denied, and the model is told explicitly that
 * nobody is there — so it does not retry the same call in a loop.
 *
 * <p>The wording is a CONTRACT, not a courtesy:
 *
 * <ul>
 *   <li>It must say <b>nobody can approve</b> — "no response" alone is
 *       ambiguous (is the client slow? is the daemon broken?).</li>
 *   <li>It must say <b>do not retry</b> — the model's default after a
 *       failure is to try again, and a retry against a permission wall
 *       is an infinite loop.</li>
 *   <li>It must offer a <b>way forward</b> — "tell the user" or "use a
 *       tool that does not need permission" — so the model has
 *       something other than retrying to do.</li>
 * </ul>
 *
 * <p>Every renderer that denies for this reason MUST use this function.
 * A renderer that writes its own sentence is a second copy, and a second
 * copy drifts — which is how the headless path ended up saying "stdin
 * not a TTY, defaulting to deny" (true, but silent about the retry).
 */
export function nobodyCanApproveReason(tool: string, surface: string): string {
  return (
    `permission denied: no one can approve the '${tool}' call in this session. ` +
    `The '${surface}' surface has no interactive user, so the ask was answered automatically with deny. ` +
    `DO NOT retry this tool call — the answer will be the same every time. ` +
    `Either (1) tell the user what you wanted to do so they can re-run it with permission, ` +
    `or (2) pick a different tool or approach that does not require permission.`
  );
}

/**
 * The short, human-facing version of the same fact.
 *
 * <p>For the terminal / stderr, not the model. One sentence, no jargon:
 * the user must be able to act on it ("re-run with --permission-mode=...").
 */
export function nobodyCanApproveNotice(tool: string, surface: string): string {
  return (
    `no one can approve '${tool}' in ${surface} — answering deny. ` +
    `Re-run with an interactive terminal, or set --permission-mode=ACCEPT_TASK / BYPASS_PERMISSIONS.`
  );
}
