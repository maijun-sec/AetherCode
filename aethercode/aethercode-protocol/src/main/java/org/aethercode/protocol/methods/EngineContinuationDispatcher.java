package org.aethercode.protocol.methods;

import org.aethercode.core.app.AppState;
import org.aethercode.core.stream.StreamEvent;
import org.aethercode.hooks.builtin.ContinuationDispatcher;
import org.aethercode.hooks.builtin.TodoContinuationHook;
import org.aethercode.protocol.jsonrpc.JsonRpcMessage;
import org.aethercode.protocol.jsonrpc.JsonRpcNotification;
import org.aethercode.sdk.AetherCodeEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * production {@link ContinuationDispatcher} that wires
 * {@link TodoContinuationHook} into the JSON-RPC daemon. The
 * dispatcher is responsible for:
 *
 * <ul>
 *   <li>Re-entering the engine with a fresh {@code engine.query(...)}
 *       call when the hook's 2s countdown elapses, draining the
 *       resulting stream on a daemon thread and forwarding every
 *       event back to the TUI as a {@code stream_event} notification
 *       (so the user sees the continuation happen, not just the
 *       final result);</li>
 *   <li>Pushing a {@code todo_continuation_countdown} notification
 *       to the TUI right after the hook decides to schedule a
 *       continuation, so the TUI can show a "Auto-continuing in
 *       2s" toast with a "Stop" button;</li>
 *   <li>Tracking per-session "auto-continue stopped" flags, with
 *       a JSON-RPC {@code setContinuationStopped} method on the
 *       server side that the TUI's "Stop" button flips;</li>
 *   <li>Firing {@link AppState#fireSessionIdle} at the end of the
 *       continuation's stream, so the boulder hooks can decide
 *       whether to schedule ANOTHER continuation (the cycle
 *       continues until the todo list is fully complete or the
 *       failure cap is reached).</li>
 * </ul>
 *
 * <p>The dispatcher is intentionally engine-aware (it lives in
 * {@code aethercode-protocol} next to {@link AetherCodeMethods})
 * so it can mint its own run id and reuse the same event-formatting
 * path the regular {@code query} method uses.
 */
public class EngineContinuationDispatcher implements ContinuationDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(EngineContinuationDispatcher.class);

    /** Re-used by {@code AetherCodeMethods.query} — the continuation
     *  gets its own run id so the TUI can correlate the events
     *  with this dispatcher. */
    public static final String NOTIFY_TODO_CONTINUATION_COUNTDOWN =
            "todo_continuation_countdown";

    private final AetherCodeEngine engine;
    private final AetherCodeMethods methods;   // for event formatting + notifier access
    private final AtomicLong continuationRunCounter = new AtomicLong();
    private final java.util.Map<String, Boolean> stoppedBySession = new ConcurrentHashMap<>();

    public EngineContinuationDispatcher(AetherCodeEngine engine, AetherCodeMethods methods) {
        this.engine = engine;
        this.methods = methods;
    }

    // ------------------------------------------------------------------
    // ContinuationDispatcher
    // ------------------------------------------------------------------

    @Override
    public void dispatchContinuation(String sessionId, String runId, String prompt,
                                      List<Map<String, Object>> incompleteTodos) {
        // We are on the TodoContinuationHook's scheduler thread —
        // safe to issue a blocking query.
        // acquire the session-level run lock first.
        // The user-typed query() acquires the same lock, so
        // a user "Continue" that lands in the 2s window between
        // the previous run's end and this dispatch's start
        // wins: their query takes the lock and our
        // tryAcquire returns null, so we bail. Without
        // this gate the two queries can run concurrently
        // and the engine's transcript / tool-use state
        // gets corrupted.
        String newRunId = methods.tryAcquireSessionLockForContinuation(sessionId);
        if (newRunId == null) {
            LOG.info("对应历史 round continuation: session busy (user query in flight), skipping (parent run {})", runId);
            return;
        }
        LOG.info("R89 continuation: starting (parent run={}, new run={}, {} incomplete)",
                runId, newRunId, incompleteTodos.size());

        // Push a "continuation starting" stream_event so the TUI's
        // transcript shows the boulder marker. Without this the
        // TUI would see a fresh run with no context for why it
        // exists. The "side_note" event is the lowest-friction
        // way to inject a UI message without making the model
        // think the user typed it.
        try {
            notifySideNote(newRunId, "todo-continuation-start",
                    "auto-continuing with " + incompleteTodos.size()
                            + " remaining todo(s) (parent run " + runId + ")");
        } catch (Exception ignored) {}

        // Drain the new run on a fresh thread so the hook's
        // scheduler can move on. Each event is forwarded to the
        // client as a stream_event notification (the TUI's
        // existing handler paints text deltas, tool cards, etc.
        // for free).
        Thread t = new Thread(() -> {
            try {
                java.util.stream.Stream<StreamEvent> stream = engine.query(prompt);
                java.util.concurrent.ConcurrentHashMap<String, String> openToolSpans =
                        new java.util.concurrent.ConcurrentHashMap<>();
                String queryTraceId = engine.traces().startSpan("query.continuation", Map.of(
                        "runId", newRunId,
                        "parentRunId", runId,
                        "promptLen", prompt.length(),
                        "incompleteTodos", incompleteTodos.size()
                ));
                String lastStopReason = "end_turn";
                engine.metrics().incTurnStarted();
                java.util.Iterator<StreamEvent> it = stream.iterator();
                while (it.hasNext()) {
                    StreamEvent ev = it.next();
                    if (ev instanceof StreamEvent.ToolUseStart tu) {
                        engine.metrics().incToolCall();
                        String toolTraceId = engine.traces().startChildSpan(
                                queryTraceId,
                                "tool." + tu.name(),
                                Map.of("toolId", tu.id(), "runId", newRunId));
                        openToolSpans.put(tu.id(), toolTraceId);
                    } else if (ev instanceof StreamEvent.ToolResult tr) {
                        if (tr.isError()) engine.metrics().incToolError();
                        String toolTraceId = openToolSpans.remove(tr.id());
                        if (toolTraceId != null) {
                            engine.traces().endSpan(toolTraceId, tr.isError() ? "error" : "ok");
                        }
                    } else if (ev instanceof StreamEvent.RunEnd re) {
                        engine.metrics().incTurnCompleted();
                        if (re.stopReason() != null && re.stopReason().startsWith("loop")) {
                            engine.metrics().incLoopStop();
                        }
                        lastStopReason = re.stopReason() == null ? "end_turn" : re.stopReason();
                        engine.traces().endSpan(queryTraceId,
                                re.stopReason() != null && re.stopReason().startsWith("error") ? "error" : "ok");
                        for (String leftover : openToolSpans.values()) {
                            engine.traces().endSpan(leftover, "error");
                        }
                        openToolSpans.clear();
                    }
                    notifyStreamEvent(newRunId, ev);
                }
                // Fire SESSION_IDLE so any future boulder hooks see
                // this continuation as a normal idle session and
                // can decide to keep going. We also clear the
                // "stopped" flag here — the user explicitly asked
                // to stop the previous continuation, not all
                // future ones.
                engine.appState().fireSessionIdle(new AppState.SessionIdleEvent(
                        newRunId, lastStopReason, engine.appState().todoList()));
            } catch (Throwable th) {
                LOG.warn("R89 continuation {} failed: {}", newRunId, th.getMessage(), th);
                // Surface the error to the TUI as a log so the
                // user knows the auto-continue blew up.
                //
                // include level=error so the
                // renderer's `log` handler (which
                // previously treated a missing `level`
                // field as "info" and swallowed the
                // entry) actually surfaces the line in
                // the chat as a system message.
                try {
                    Map<String, Object> errWrap = new LinkedHashMap<>();
                    errWrap.put("runId", newRunId);
                    errWrap.put("level", "error");
                    errWrap.put("error", th.getMessage() != null
                            ? th.getMessage() : th.getClass().getName());
                    methods.notifyLog(errWrap);
                } catch (Exception ignored) {}
            } finally {
                // release the session-level run lock
                // we acquired at the top of dispatchContinuation.
                // Mirrors the run-finally in AetherCodeMethods.query().
                // Without this release, the next user query (or
                // the next auto-continue) would be rejected
                // with "session is busy with run cont-N" forever.
                methods.releaseSessionLock(sessionId, newRunId);
            }
        }, "aethercode-continuation-" + newRunId);
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void notifyCountdown(String sessionId, String runId, int incompleteCount,
                                int totalCount, long remainingMs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId",        sessionId);
        payload.put("runId",            runId);
        payload.put("incompleteCount",  incompleteCount);
        payload.put("totalCount",       totalCount);
        payload.put("remainingMs",      remainingMs);
        payload.put("kind",             "countdown-start");
        methods.notifyCustom("todo_continuation_countdown", payload);
    }

    @Override
    public boolean isContinuationStopped(String sessionId) {
        return stoppedBySession.getOrDefault(sessionId, false);
    }

    @Override
    public void setContinuationStopped(String sessionId, boolean stopped) {
        if (sessionId == null) return;
        if (stopped) {
            stoppedBySession.put(sessionId, true);
            // Also cancel any pending countdown so the
            // user-stop actually takes effect immediately
            // (without waiting for the 2s window).
            methods.cancelPendingContinuationCountdown(sessionId);
        } else {
            stoppedBySession.remove(sessionId);
        }
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private void notifyStreamEvent(String runId, StreamEvent ev) {
        try {
            Map<String, Object> evWrap = new LinkedHashMap<>();
            evWrap.put("runId", runId);
            evWrap.put("event", AetherCodeMethods.eventToMapPublic(ev));
            methods.notifyCustom(AetherCodeMethods.NOTIFY_STREAM_EVENT, evWrap);
        } catch (Exception notifyEx) {
            LOG.debug("notifyStreamEvent failed: {}", notifyEx.getMessage());
        }
    }

    private void notifySideNote(String runId, String kind, String message) {
        try {
            Map<String, Object> evWrap = new LinkedHashMap<>();
            evWrap.put("runId", runId);
            Map<String, Object> sideNote = new LinkedHashMap<>();
            sideNote.put("type", "side_note");
            sideNote.put("kind", kind);
            sideNote.put("message", message);
            evWrap.put("event", sideNote);
            methods.notifyCustom(AetherCodeMethods.NOTIFY_STREAM_EVENT, evWrap);
        } catch (Exception ignored) {}
    }
}
