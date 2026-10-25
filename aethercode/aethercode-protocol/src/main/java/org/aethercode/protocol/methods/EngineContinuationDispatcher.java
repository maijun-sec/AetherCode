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
                            + " remaining todo(s) (parent run " + runId + ")",
                    sessionId);
        } catch (Exception ignored) {}

        // Drain the new run on a fresh thread so the hook's
        // scheduler can move on. Each event is forwarded to the
        // client as a stream_event notification (the TUI's
        // existing handler paints text deltas, tool cards, etc.
        // for free).
        Thread t = new Thread(() -> {
            // R745: the consumer must ALWAYS leave a terminal event
            // behind, and must never hold the session lock open
            // forever. `sawRunEnd` is read by the post-loop guard
            // below; `eventCount` feeds the R745 LOG marker so a
            // silent consumer is provable from the daemon log
            // (Lesson 1052 - no marker means no way to prove the
            // deploy landed).
            final boolean[] sawRunEnd = { false };
            final int[] eventCount = { 0 };
            final java.util.concurrent.ConcurrentHashMap<String, String> openToolSpans =
                    new java.util.concurrent.ConcurrentHashMap<>();
            String queryTraceId = null;
            String lastStopReason = "end_turn";
            // R745: try-with-resources. Pre-fix this was a bare
            // `Stream<StreamEvent> stream = engine.query(prompt);`
            // with ZERO closes in the whole file - every continuation
            // leaked the Spliterator-backed stream (and whatever the
            // chat client holds behind it) for the JVM's lifetime.
            try (java.util.stream.Stream<StreamEvent> stream = engine.query(prompt)) {
                queryTraceId = engine.traces().startSpan("query.continuation", Map.of(
                        "runId", newRunId,
                        "parentRunId", runId,
                        "promptLen", prompt.length(),
                        "incompleteTodos", incompleteTodos.size()
                ));
                engine.metrics().incTurnStarted();
                java.util.Iterator<StreamEvent> it = stream.iterator();
                // R727: tracks whether this continuation
                // emitted at least one tool_use_start. The
                // idle-turn detector only fires when
                // sawToolUse==false AND final assistant
                // message is empty/whitespace.
                boolean[] sawToolUse = { false };
                while (true) {
                    // R745: bounded wait for the next event. R744
                    // guarded the producer side
                    // (SpringAiChatClient.runCall's FIRST
                    // hasNext()); NOTHING guarded this consumer
                    // side. A Spliterator whose backing queue never
                    // receives another element parks this thread
                    // inside hasNext() indefinitely - the
                    // continuation thread never exits, the
                    // finally below never runs, and the session run
                    // lock is held forever, which is exactly the
                    // "session is busy with run cont-N" wedge the
                    // lock comment warns about. Probe-thread +
                    // join(deadline) is the same shape R744 used:
                    // hasNext() is itself a blocking read, so it
                    // cannot be polled from this thread.
                    if (!awaitNextEvent(it)) {
                        String stopReason = "consumer_timeout: no stream event for "
                                + consumerTimeoutMs() + "ms (run " + newRunId + ")";
                        LOG.warn("R745: continuation consumer stalled - no stream event "
                                + "within {}ms (runId={}, events={}). Emitting a terminal "
                                + "consumer_timeout RunEnd and abandoning the stream.",
                                consumerTimeoutMs(), newRunId, eventCount[0]);
                        if (queryTraceId != null) {
                            try { engine.traces().endSpan(queryTraceId, "error"); } catch (Exception ignored) {}
                        }
                        for (String leftover : openToolSpans.values()) {
                            try { engine.traces().endSpan(leftover, "error"); } catch (Exception ignored) {}
                        }
                        openToolSpans.clear();
                        lastStopReason = stopReason;
                        // R745: deliver the terminal event BEFORE
                        // break so the TUI's isStreaming flips back
                        // to false - a timeout that only logged would
                        // reproduce the R735 "dead engine looks like
                        // a healthy green Idle" symptom.
                        StreamEvent.RunEnd te = new StreamEvent.RunEnd(
                                stopReason, java.util.List.of());
                        sawRunEnd[0] = true;
                        notifyStreamEvent(newRunId, te, sessionId);
                        break;
                    }
                    StreamEvent ev = it.next();
                    eventCount[0]++;
                    if (ev instanceof StreamEvent.ToolUseStart tu) {
                        sawToolUse[0] = true;
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
                        // R745: remember that the engine DID deliver a
                        // terminal event, so the post-loop guard below
                        // doesn't synthesise a duplicate.
                        sawRunEnd[0] = true;
                        engine.metrics().incTurnCompleted();
                        // R743: was `stopReason.startsWith("loop")`,
                        // which missed `same_error_hard_stop`
                        // (R181/R739) — see the identical fix in
                        // AetherCodeMethods. Delegate to the canonical
                        // predicate so the child-session metrics sink
                        // and the main one can't drift.
                        if (re.isDeterministicHardStop()) {
                            engine.metrics().incLoopStop();
                        }
                        lastStopReason = re.stopReason() == null ? "end_turn" : re.stopReason();
                        // R743: same span-status fix as the main sink —
                        // the old `startsWith("error")` recorded
                        // runtime_exception / cancelled /
                        // same_error_hard_stop spans as "ok".
                        boolean spanOk = re.bareKind().equals("stop")
                                || re.bareKind().equals("end_turn")
                                || re.bareKind().equals("tool_calls")
                                || re.bareKind().equals("awaiting_user_decision")
                                || re.bareKind().equals("empty_input");
                        engine.traces().endSpan(queryTraceId, spanOk ? "ok" : "error");
                        for (String leftover : openToolSpans.values()) {
                            engine.traces().endSpan(leftover, "error");
                        }
                        openToolSpans.clear();
                        // R727: detect "idle turn" on continuations
                        // too. The RAG Day 2 build's hardest symptom
                        // was an auto-continuation that fired but the
                        // model emitted nothing — the user had to
                        // type another message to nudge it. Counter
                        // surfaces in getMetrics; LOG.warn keeps a
                        // trail in the daemon log for postmortem.
                        engine.metrics().recordIdleTurnIfApplicable(
                                newRunId, sawToolUse[0],
                                re.stopReason(),
                                re.finalBlocks());
                    }
                    notifyStreamEvent(newRunId, ev, sessionId);
                }
                // R745: R735's terminal-event contract, enforced on
                // the CONSUMER side. A consumer can exit its loop
                // without ever having seen a RunEnd - the
                // Spliterator exhausted early, or the stream ended
                // after a throw inside the producer that R735a's
                // outer catch already converted elsewhere. When that
                // happens the TUI's `isStreaming` never flips back
                // to false and the user reads a dead continuation as
                // a healthy green "Idle" (the exact R735 symptom).
                // So: if we drained to exhaustion without a terminal
                // event, mint one and say so in the stopReason.
                if (!sawRunEnd[0]) {
                    LOG.warn("R745: continuation consumer exited without RunEnd "
                            + "(runId={}, events={}) - emitting a synthetic terminal event",
                            newRunId, eventCount[0]);
                    if (queryTraceId != null) {
                        try { engine.traces().endSpan(queryTraceId, "error"); } catch (Exception ignored) {}
                    }
                    for (String leftover : openToolSpans.values()) {
                        try { engine.traces().endSpan(leftover, "error"); } catch (Exception ignored) {}
                    }
                    openToolSpans.clear();
                    lastStopReason = "no_run_end: consumer exited without terminal event";
                    notifyStreamEvent(newRunId, new StreamEvent.RunEnd(
                            lastStopReason, java.util.List.of()), sessionId);
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
        // R379 — the constant, not the literal. The name used to live in
        // TWO places in this file (the constant declaration and this
        // call), and the constant was dead code. Two copies of a truth
        // drift silently: deleting the emission left the literal behind
        // in the constant, so a gate that looked for the literal still
        // went green on a daemon that had stopped counting down.
        methods.notifyCustom(NOTIFY_TODO_CONTINUATION_COUNTDOWN, payload);
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

    /**
     * R745/R746: how long the continuation consumer waits for the NEXT
     * stream event before declaring the stream stalled.
     *
     * <p>Reuses {@code AETHERCODE_R744_FIRST_CHUNK_TIMEOUT_MS} (and its
     * 180s default) deliberately rather than minting a new knob: R744
     * already established that budget as "the longest a healthy
     * provider may take before we call the attempt broken", measured
     * at 40-90s cold-start TTFB. A continuation that stalls for LONGER
     * than a first chunk may legitimately take is, by the same
     * evidence, dead - and the only reason it can be dead is
     * un-drained on this side, since R744 guards the producer.
     *
     * <p>A malformed override falls through to the default for the
     * same reason R744 does it: a typo in an env var must never be
     * able to disable the guard and reintroduce the wedge.
     *
     * <p>R746: the env-var parsing now lives in the shared
     * {@link org.aethercode.core.stream.StreamConsumerGuard} so the
     * continuation dispatcher, the subagent orchestrator,
     * {@code AgentTool} and {@code SddOrchestrator} cannot drift on the
     * number. The method stays as a one-line delegate because the
     * R745 source pins name it.
     */
    static long consumerTimeoutMs() {
        return org.aethercode.core.stream.StreamConsumerGuard.timeoutMs();
    }

    /** R745: same default R744 uses for the producer-side first-chunk
     *  wait. See {@link #consumerTimeoutMs()}. R746: the single source
     *  of truth is {@link org.aethercode.core.stream.StreamConsumerGuard#TIMEOUT_MS_DEFAULT}. */
    static final long CONSUMER_TIMEOUT_MS_DEFAULT =
            org.aethercode.core.stream.StreamConsumerGuard.TIMEOUT_MS_DEFAULT;

    /**
     * R745: bounded {@code hasNext()}.
     *
     * <p>{@code hasNext()} on a Spliterator-backed stream is itself a
     * blocking read, so it cannot be polled from the calling thread -
     * a single call parks until the producer offers an element. We run
     * it on a short-lived daemon probe thread and {@code join()} it
     * with a deadline, which keeps the original blocking semantics (no
     * busy-wait, no truncated stream) while giving the caller a bound.
     *
     * <p>Returns {@code true} when the caller may call
     * {@code it.next()} - including the legal "stream finished
     * cleanly with zero remaining elements" case, which yields
     * {@code false} from {@code hasNext()} but is a completed, NOT
     * stalled stream. Returns {@code false} only when the deadline
     * expired with the probe still parked, i.e. the stream is wedged.
     *
     * <p>On timeout the probe is interrupted and abandoned; it stays a
     * daemon so a genuinely dead provider cannot keep the JVM alive.
     *
     * <p>R746: the probe itself now lives in the shared
     * {@link org.aethercode.core.stream.StreamConsumerGuard}. Only the
     * dispatcher's own call site and its two terminal-event branches
     * (the consumer_timeout break and the no_run_end guard) stay here,
     * because those also own span teardown and the TUI notification.
     */
    private static boolean awaitNextEvent(java.util.Iterator<?> it) {
        return org.aethercode.core.stream.StreamConsumerGuard
                .awaitNextEvent(it, consumerTimeoutMs());
    }

    private void notifyStreamEvent(String runId, StreamEvent ev, String sessionId) {
        try {
            Map<String, Object> evWrap = new LinkedHashMap<>();
            evWrap.put("runId", runId);
            // tag with sessionId so the desktop drops the
            // stream event if the user already moved on
            // (same fix as the main query loop in
            // AetherCodeMethods.query()). Null-safe for
            // legacy single-engine daemons — the desktop's
            // isOurSession helper treats empty as "all".
            if (sessionId != null) evWrap.put("sessionId", sessionId);
            evWrap.put("event", AetherCodeMethods.eventToMapPublic(ev));
            methods.notifyCustom(AetherCodeMethods.NOTIFY_STREAM_EVENT, evWrap);
        } catch (Exception notifyEx) {
            LOG.debug("notifyStreamEvent failed: {}", notifyEx.getMessage());
        }
    }

    private void notifySideNote(String runId, String kind, String message, String sessionId) {
        try {
            Map<String, Object> evWrap = new LinkedHashMap<>();
            evWrap.put("runId", runId);
            // same sessionId tag as notifyStreamEvent above.
            if (sessionId != null) evWrap.put("sessionId", sessionId);
            Map<String, Object> sideNote = new LinkedHashMap<>();
            sideNote.put("type", "side_note");
            sideNote.put("kind", kind);
            sideNote.put("message", message);
            evWrap.put("event", sideNote);
            methods.notifyCustom(AetherCodeMethods.NOTIFY_STREAM_EVENT, evWrap);
        } catch (Exception ignored) {}
    }
}
