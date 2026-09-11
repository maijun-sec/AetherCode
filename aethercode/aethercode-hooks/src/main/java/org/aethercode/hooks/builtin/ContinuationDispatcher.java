package org.aethercode.hooks.builtin;

import java.util.List;
import java.util.Map;

/**
 * seam between the pure-logic {@link TodoContinuationHook} and
 * the engine / protocol layer. The hook lives in {@code aethercode-hooks}
 * and intentionally has no dependency on the engine; the protocol
 * layer supplies an implementation that:
 *
 * <ul>
 *   <li>issues the actual re-prompt via {@code engine.query(...)}
 *       and forwards the resulting stream events back to the TUI
 *       over JSON-RPC, OR</li>
 *   <li>pushes a {@code todo_continuation_countdown} notification
 *       to the TUI so it can show a toast with a "Stop auto-continue"
 *       button, AND</li>
 *   <li>tracks which sessions have auto-continue stopped
 *       ({@link #isContinuationStopped}) so the hook can skip
 *       them on the next idle event.</li>
 * </ul>
 *
 * <p>All three operations are best-effort — exceptions are caught
 * by the hook and logged, never propagated. A misbehaving
 * dispatcher (e.g. a dropped network) must not stop the engine.
 */
public interface ContinuationDispatcher {

    /**
     * Re-issue the user's task on the same session, as a fresh
     * {@code engine.query(...)} call. The {@code prompt} is the
     * continuation prompt the hook built (boulder marker +
     * incomplete-todo summary). The dispatcher is responsible for
     * draining the resulting stream and forwarding events to the
     * client — the hook does not see the events.
     *
     * @param sessionId the session that just went idle
     * @param runId the run id of the just-finished query (used for
     *              log correlation; the new query gets its own runId)
     * @param prompt the continuation prompt (already includes the
     *               boulder marker + todo summary)
     * @param incompleteTodos the todos that are still pending /
     *                        in_progress, as a defensive copy the
     *                        hook took when the idle event fired
     */
    void dispatchContinuation(String sessionId, String runId, String prompt,
                              List<Map<String, Object>> incompleteTodos);

    /**
     * Push a {@code todo_continuation_countdown} notification to
     * the client so the TUI can show "Auto-continuing in Ns"
     * toast with a "Stop" button. The hook calls this exactly
     * once per scheduled continuation (i.e. when the cooldown
     * passed and the todo list has incomplete items), with a
     * small positive {@code remainingMs} value. The notification
     * is best-effort — when the dispatcher is null (tests,
     * headless mode) the hook skips this step.
     */
    void notifyCountdown(String sessionId, String runId, int incompleteCount,
                         int totalCount, long remainingMs);

    /**
     * Whether the user has explicitly asked to STOP auto-continue
     * on this session. The flag is cleared on the next user-prompt
     * submit, so a single stop only suppresses the immediate
     * continuation — the user can keep working with the model
     * after they manually type the next prompt.
     */
    boolean isContinuationStopped(String sessionId);

    /**
     * Set or clear the "stop auto-continue" flag for the session.
     * Wired to a JSON-RPC method so the TUI's "Stop" button can
     * flip it without round-tripping through the engine's
     * prompt loop.
     */
    void setContinuationStopped(String sessionId, boolean stopped);
}
