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
 * R387: what `/clear` actually does — and the ONE place that decides it.
 *
 * ## What was wrong
 *
 * `/clear` was the only command in the catalog whose description promised
 * one thing and whose two renderers did two different OTHER things:
 *
 *   - the catalog said **"clear the scrollback"**;
 *   - the LINE renderer really did clear the screen (`\x1b[2J\x1b[H` plus a
 *     re-printed splash);
 *   - the INK renderer — the primary one — printed
 *     `"(clear is a no-op in this build; restart for a fresh scrollback)"`
 *     and did nothing at all.
 *
 * The Ink half was at least honest about being a no-op, and R378 explicitly
 * deferred it as "another honest gap". But honest-and-useless is still a
 * broken command: the user is told the capability is missing, and the
 * capability is NOT missing — the daemon has everything it needs.
 *
 * ## Why clearing the VIEW alone would have been a lie
 *
 * The TUI's `state.turns` is not the conversation. It is a MIRROR of the
 * daemon's transcript: R372's session picker rebuilds it by calling
 * `getTranscript` and replaying the daemon's messages. So a `/clear` that
 * emptied `state.turns` and left the daemon alone would have produced a
 * conversation the user can no longer see but the model still remembers —
 * and the next `/sessions` switch (or a restart) would bring the whole
 * history BACK. A clear that comes back is worse than no clear.
 *
 * So the clear has to happen where the conversation actually lives: on the
 * daemon.
 *
 * ## Why `createSession` and not a new "clear transcript" RPC
 *
 * The daemon already HAS the primitive, registered on BOTH transports
 * (`AetherCodeMethods.registerAll` for stdio, the HTTP switch for HTTP+WS):
 *
 *   `createSession {cwd}` → mints a new session id, writes an EMPTY
 *   transcript, switches the engine onto it, and (when a cwd is given)
 *   binds the new session to the same project and registers it in the
 *   session store.
 *
 * Measured against the running daemon (R387 probe, 2026-10-27):
 *
 *     createSession {cwd:"D:\\…\\AetherCode"}
 *       → {ok:true, sessionId:"2026-10-27T02-03-16…Z_df7a98cc",
 *          cwd:"D:\\…\\AetherCode", messageCount:0, active:"…"}
 *     getState   → sessionId = the new one, transcriptSize = 0
 *     getTranscript → {ok:true, sessionId: the new one, messages:[]}
 *
 * That is exactly "clear the context", and it is what Claude Code's
 * `/clear` does (a new conversation, the old one still recoverable from the
 * resume list). It is also NON-DESTRUCTIVE: the previous conversation stays
 * in the session store, so `/sessions` can bring it back. A method that
 * truncated the transcript in place would have destroyed it.
 *
 * The alternative — a new `clearTranscript` RPC — would have meant touching
 * the daemon, and would have been WORSE for the user: same session id, but
 * the conversation GONE with no way to recover it.
 *
 * ## One implementation, two renderers
 *
 * R385's lesson applies twice over:
 *
 *   - a command that works in line mode and shows a FALSE FAILURE in Ink
 *     mode is a defect (R385 was about `/mode`);
 *   - the two renderers must say the SAME sentence, or the user gets two
 *     different answers for one command.
 *
 * So the method name, the params, the session-id extraction and the outcome
 * sentence all live HERE, and both renderers call `runClear`. There is no
 * second copy of the wording to drift.
 *
 * ## The clear is ATOMIC
 *
 * Either the daemon confirms a new session — and then the view is replaced
 * with the new (empty) conversation — or nothing is cleared and the reason
 * is shown. A renderer that wiped the screen and THEN failed to reach the
 * daemon would leave the user staring at an empty screen with a model that
 * still remembers everything: a clear that did not clear.
 */

import type { RpcValue } from "./jsonrpc.js";

/**
 * The daemon method that starts a fresh conversation.
 *
 * <p>Registered on BOTH transports:
 * `AetherCodeMethods.registerAll` (stdio) and the `HttpJsonRpcServer`
 * switch (HTTP + WebSocket).
 */
export const CLEAR_METHOD = "createSession";

/**
 * The params the clear sends.
 *
 * <p>The cwd is carried so the new session stays bound to the SAME project:
 * without it the daemon would mint a session with no cwd at all, and the
 * cwd-scoped `/sessions` picker (R370) would file it under "unlinked"
 * instead of showing it next to the project the user is working in.
 */
export function clearParams(cwd: string): RpcValue {
  const trimmed = (cwd ?? "").trim();
  return trimmed.length > 0 ? { cwd: trimmed } : {};
}

/**
 * The new session id inside a `createSession` result, or null.
 *
 * <p>Null means "the daemon did not give us one" — it is NOT a licence to
 * invent one. A result that claims `ok` without an id is a malformed
 * answer, and treating it as a success would put the TUI on a session id
 * that does not exist.
 */
export function newSessionIdOf(result: unknown): string | null {
  if (!result || typeof result !== "object") return null;
  const r = result as Record<string, unknown>;
  if (r.ok !== true) return null;
  const id = r.sessionId;
  if (typeof id !== "string") return null;
  const trimmed = id.trim();
  return trimmed.length > 0 ? trimmed : null;
}

/**
 * The sentence the user reads after a `/clear`.
 *
 * <p>Every branch says what HAPPENED, not what was attempted. A success
 * names the new session (the session id CHANGED — the user should know
 * that); a failure names the reason and never pretends the screen was
 * cleared.
 */
export function clearOutcome(
  ok: boolean,
  ctx: { sessionId?: string | null; error?: string | null } = {},
): string {
  if (!ok) {
    const why = ctx.error && ctx.error.length > 0 ? ctx.error : "the daemon refused";
    return `clear failed: ${why}`;
  }
  const id = ctx.sessionId ?? "";
  return id.length > 0 ? `cleared — new session ${id}` : "cleared — started a fresh conversation";
}

/** What a clear attempt produced. */
export interface ClearResult {
  /** True only when the daemon confirmed a new session. */
  ok: boolean;
  /** The new session id, or null when the daemon did not give one. */
  sessionId: string | null;
  /** The sentence for the user. */
  message: string;
}

/**
 * Run the clear, once, for whichever renderer is asking.
 *
 * <p>The request function is injected because the two renderers reach the
 * daemon differently (Ink owns a `JsonRpcClient`; line mode owns its own
 * probe helper) — but the DECISION of what a clear means must not be
 * duplicated, so it lives here.
 */
export async function runClear(
  request: (method: string, params: RpcValue) => Promise<unknown>,
  ctx: { cwd: string },
): Promise<ClearResult> {
  let raw: unknown;
  try {
    raw = await request(CLEAR_METHOD, clearParams(ctx.cwd));
  } catch (e: unknown) {
    const why = e instanceof Error ? e.message : String(e);
    return { ok: false, sessionId: null, message: clearOutcome(false, { error: why }) };
  }
  const sessionId = newSessionIdOf(raw);
  if (sessionId === null) {
    // The daemon answered, but not with a session we can adopt. Say so
    // rather than clearing the view onto a session that may not exist.
    const reason =
      raw && typeof raw === "object" && typeof (raw as Record<string, unknown>).reason === "string"
        ? ((raw as Record<string, unknown>).reason as string)
        : "the daemon did not return a session id";
    return { ok: false, sessionId: null, message: clearOutcome(false, { error: reason }) };
  }
  return { ok: true, sessionId, message: clearOutcome(true, { sessionId }) };
}
