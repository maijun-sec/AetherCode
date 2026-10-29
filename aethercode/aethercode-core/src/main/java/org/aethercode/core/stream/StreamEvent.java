/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.core.stream;

import org.aethercode.core.message.ContentBlock;

import java.util.List;
import java.util.Map;

/**
 * One event yielded by the {@link org.aethercode.core.engine.QueryEngine}. Modelled after the
 * TS original's {@code StreamEvent} union. The TUI / SDK consumes these and updates the screen.
 */
public sealed interface StreamEvent {

    /** Stream began. The first event of every run. */
    record RunStart(String runId, String model) implements StreamEvent {}

    /** A text delta from the model. */
    record TextDelta(String text) implements StreamEvent {}

    /** A tool_use block fully received from the model. */
    record ToolUseStart(String id, String name, Map<String, Object> input) implements StreamEvent {}

    /** Tool result is in (after permission + execution). */
    record ToolResult(String id, Object content, boolean isError) implements StreamEvent {}

    /**
     * a chunk of streaming output for an in-flight tool call.
     * The {@code id} matches the {@code ToolUseStart.id} / {@code
     * ToolResult.id} of the same call, and the renderer is expected
     * to append {@code text} to the matching tool event's
     * {@code output} field so the user sees the command's output
     * live (e.g. {@code mvn --version}'s banner) instead of waiting
     * for the whole tool to complete.
     *
     * <p>legacy, {@code BashTool} called {@code ctx.emit(...)} per
     * line and the engine forwarded those to a {@code messageSink}
     * consumer — but the SDK's {@code AetherCodeEngine} passed
     * {@code null} for the sink, so the streamed output was silently
     * dropped. The result: an {@code mvn --version} that takes
     * ~5s on Windows showed a blank card for the entire 5s, then
     * dumped the final output all at once. The user described
     * this as "the mvn command takes a bit long to run" — the actual runtime
     * was fine, the perception was that nothing was happening.
     *
     * <p>This event plugs the gap: the engine emits a
     * {@code ToolOutputDelta} on every progress line, the desktop
     * appends it to the tool event, and the auto-scroll watcher
     * (MessageList R193) follows the growing output to the bottom
     * of the viewport.
     */
    record ToolOutputDelta(String id, String text) implements StreamEvent {}

    /** A whole run completed. {@code stopReason} mirrors the API value (e.g. "end_turn", "tool_use"). */
    record RunEnd(String stopReason, java.util.List<ContentBlock> finalBlocks) implements StreamEvent {

        /**
         * R743: the BARE kind of this RunEnd's stopReason — the
         * canonical prefix before the first {@code ": "} / space, or
         * the whole string when it carries no decoration.
         *
         * <p>The engine decorates several stopReasons with a
         * diagnostic suffix — {@code "loop_detected: empty_tool_input
         * (4 consecutive empty tool calls)"}, {@code "error:
         * WebClientResponseException$…: 500 from POST https://…"}.
         * Consumers that need to CLASSIFY a stop (metrics
         * bucketing, continuation gating, the desktop's failed-pill)
         * must compare against this bare kind, never the decorated
         * string. This is the R735b lesson generalised: an exact
         * match against a wire string silently misclassifies every
         * decorated reason.
         */
        public String bareKind() {
            return bareKindOf(stopReason);
        }

        /**
         * R743: is this run a DETERMINISTIC terminal stop — one where
         * the engine has already decided, on its own, that continuing
         * is pointless or unwanted?
         *
         * <p>These are the reasons every downstream consumer must
         * treat as "do not auto-resume":
         * <ul>
         *   <li>{@code loop_detected…} — {@code QueryEngine:2363},
         *       empty-tool-input death spiral.</li>
         *   <li>{@code same_error_hard_stop} — {@code QueryEngine:2568},
         *       R181/R739 same-error death spiral. Note this one does
         *       NOT start with {@code "loop"}, which is why the old
         *       {@code startsWith("loop")} metrics predicate missed it.</li>
         *   <li>{@code cancelled} — {@code QueryEngine:1983}, R721
         *       user-initiated cancel. Never resurrect.</li>
         * </ul>
         *
         * <p>Deliberately NOT included:
         * <ul>
         *   <li>{@code stop} / {@code end_turn} — success.</li>
         *   <li>{@code tool_calls} — an intermediate hand-off.</li>
         *   <li>{@code awaiting_user_decision} — the engine is PAUSED
         *       waiting on the user; auto-resuming races their answer.</li>
         *   <li>{@code empty_input} — a no-op guard that never reaches
         *       a session-idle hook.</li>
         *   <li>{@code error…} — already excluded upstream by every
         *       caller's "contains error" check; kept out so this
         *       predicate stays about LOOPS specifically.</li>
         * </ul>
         */
        public boolean isDeterministicHardStop() {
            return isDeterministicHardStopOf(stopReason);
        }

        /**
         * R750d: did this run end in a FAILURE, as opposed to a clean stop
         * or a user-initiated one?
         *
         * <p>This is what the desktop colours red and what the stream-json
         * {@code result} frame reports as {@code is_error}. Before R750d
         * nothing on the wire carried the answer: {@code eventToMap} for a
         * RunEnd emitted only {@code type} + {@code stopReason}, so the
         * only producer of {@code isError: true} was the protocol layer's
         * synthetic catch handler — and that handler is UNREACHABLE for any
         * throw raised inside the engine, because the engine's own
         * {@code tryAdvance} catch (R735a) already turned it into a
         * terminal RunEnd and returned normally.
         *
         * <p>So a run that died with {@code runtime_exception: …} reached
         * the desktop indistinguishable from a clean {@code end_turn} at the
         * field level. The renderer happened to recover because it also
         * prefix-matches the stopReason (R735b), but the stream-json
         * consumer — Multica — has no such fallback: it classifies success
         * from {@code is_error} alone, so an upstream 500 was reported as
         * a successful run.
         *
         * <p>Kept separate from {@link #isDeterministicHardStop()} on
         * purpose. That predicate answers "did the engine DECIDE to stop"
         * (loops, death spirals, user cancel) and deliberately excludes
         * errors. This one answers "was the outcome a failure", which
         * includes the deterministic stops that are also failures
         * ({@code same_error_hard_stop}, {@code loop_detected}) and
         * excludes {@code cancelled} (the user asked for it).
         */
        public boolean isErrorOutcome() {
            return isErrorOutcomeOf(stopReason);
        }
    }

    // ---- R743: shared stopReason classification --------------------
    //
    // Static twins of the RunEnd instance methods so callers that only
    // have the raw string (the desktop's `run_end` handler, the
    // session-idle hook, the metrics sink) can reuse ONE definition
    // instead of each re-inventing a prefix heuristic and drifting.
    // Three layers had three subtly different predicates before R743
    // and all three were wrong in different ways.

    /** @see RunEnd#bareKind() */
    static String bareKindOf(String stopReason) {
        if (stopReason == null) return "";
        String s = stopReason.trim();
        int cut = s.length();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ':' || c == ' ' || c == '(') { cut = i; break; }
        }
        return s.substring(0, cut);
    }

    /** @see RunEnd#isDeterministicHardStop() */
    static boolean isDeterministicHardStopOf(String stopReason) {
        if (stopReason == null) return false;
        String kind = bareKindOf(stopReason);
        return "loop_detected".equals(kind)
                // R181 / R739 same-error death spiral.
                || "same_error_hard_stop".equals(kind)
                // R721 user-initiated cancel.
                || "cancelled".equals(kind);
    }

    /**
     * R750d: did the run END IN FAILURE?
     *
     * <p>One definition, shared by every layer that has to answer this —
     * the protocol's {@code eventToMap}, the stream-json {@code result}
     * frame, and (indirectly) the desktop's red styling. R743's lesson
     * applies verbatim: when three layers each hand-roll a prefix check over
     * the same decorated string, they drift, and at least one of them is
     * wrong.
     *
     * <p>The set is deliberately built from {@link #bareKindOf}, so
     * {@code "runtime_exception: RuntimeException: upstream 503"} and
     * {@code "runtime_exception"} classify identically. An exact match
     * against the decorated wire string would classify every real one as
     * "not an error" — which is precisely the bug this method exists to
     * remove.
     *
     * <p>Out of scope on purpose:
     * <ul>
     *   <li>{@code cancelled} — the user asked for it; not a failure.</li>
     *   <li>{@code awaiting_user_decision} — the engine is pausing for
     *       input, which is the system working.</li>
     *   <li>{@code end_turn} / {@code tool_use} / {@code stop} — clean.</li>
     * </ul>
     */
    static boolean isErrorOutcomeOf(String stopReason) {
        if (stopReason == null) return false;
        return switch (bareKindOf(stopReason)) {
            // R735a: the engine's own outer-catch translation of a throw.
            case "runtime_exception" -> true;
            // SpringAiChatClient's terminal error shape (R697 retry
            // exhaustion, provider 4xx/5xx): "error: <class>: <msg>".
            case "error" -> true;
            // R745/R746 consumer-side terminal events.
            case "no_run_end", "consumer_timeout" -> true;
            // R181 / R739 death spiral, R181 loop detection.
            case "same_error_hard_stop", "loop_detected" -> true;
            // R750b surfaced a stalled first-chunk read as a timeout.
            case "stale_force_end" -> true;
            // Backpressure is a provider-capacity signal the user retries;
            // reported separately by AetherCodeMethods, so not here.
            default -> false;
        };
    }

    /** Compaction or memory write occurred; the caller may want to redraw the status line. */
    record SideNote(String kind, String message) implements StreamEvent {}

    /**
     * per-todo adaptive control has reached the max-bumps limit
     * (default 10). The engine is pausing and wants the user to
     * decide whether to continue, abort, or change direction. The
     * renderer should surface a special prompt; the next user
     * message will be treated as a continuation decision. This is
     * <em>not</em> a terminal {@code RunEnd} — the query hasn't
     * finished, the engine is just waiting.
     *
     * <p>{@code options} is the structured menu of choices the
     * user can pick from (e.g. A/B/C/D for the default
     * "mark complete / revise plan / acknowledge / abort"
     * menu produced by {@code TodoRunController}). Each option
     * carries a short letter, label, and description so the UI
     * can render clickable cards without parsing the
     * {@code summary} text. Older engine builds don't emit
     * {@code options}; clients should fall back to parsing the
     * {@code summary} (or to a hard-coded default menu) when the
     * field is missing.
     */
    record AwaitUserDecision(
            String summary,
            int currentTodoSteps,
            int currentSoftThreshold,
            List<DecisionOption> options
    ) implements StreamEvent {}

    /**
     * one structured choice inside an {@code AwaitUserDecision}.
     * The renderer maps this to a clickable card; clicking
     * injects the matching continuation text into the input box
     * (or sends it directly if the engine accepts a
     * pre-canned response).
     *
     * <ul>
     *   <li>{@code letter} — short identifier such as {@code "A"}
     *       (single capital) the user types in their reply.
     *       Stable across engine versions so the LLM still
     *       recognises the user's choice.</li>
     *   <li>{@code label} — short human-readable verb the UI
     *       shows on the card button (e.g. "Mark complete").</li>
     *   <li>{@code description} — one-sentence rationale the UI
     *       shows as a tooltip / sub-text. Helps the user pick
     *       without opening the markdown summary.</li>
     * </ul>
     */
    record DecisionOption(String letter, String label, String description) {}

    /**
     * per-call token usage emitted by the chat client. The
     * engine forwards this to {@code CostTracker} and {@code
     * MetricsCollector} so the UI can show cumulative tokens. The
     * fields are per-call deltas (not cumulative).
     */
    record Usage(int inputTokens, int outputTokens) implements StreamEvent {}

    /**
     * a sub-task just transitioned to {@code in_progress}. The
     * renderer groups model calls + tool calls into a SubTaskCard
     * keyed by {@code subTaskId}; the next stream events are children
     * of this card until a {@code SubTaskEnd} with the same id.
     *
     * <p>Fields:
     * <ul>
     *   <li>{@code taskId} — index of the parent top-level todo in
     *       the current todo list (0-based). -1 if there's no parent.</li>
     *   <li>{@code subTaskId} — stable id of the sub-task. The
     *       model is expected to assign unique ids (e.g. via
     *       {@code sub_todo_write}).</li>
     *   <li>{@code content} — short description (the business
     *       concept unit the model committed to, e.g. "完成模块XX的测试" / "finish writing tests for module XX").</li>
     *   <li>{@code status} — initial status, usually {@code "in_progress"}.</li>
     * </ul>
     */
    record SubTaskStart(int taskId, String subTaskId, String content, String status) implements StreamEvent {}

    /**
     * a sub-task transitioned away from {@code in_progress}.
     * The matching {@code SubTaskStart} card should now display its
     * final status and optional summary, and any further stream
     * events belong to the next sub-task (or no sub-task at all).
     *
     * <p>Fields:
     * <ul>
     *   <li>{@code taskId} — same as the matching {@code SubTaskStart}.</li>
     *   <li>{@code subTaskId} — same as the matching {@code SubTaskStart}.</li>
     *   <li>{@code status} — final status: {@code completed}, {@code failed},
     *       or {@code skipped}.</li>
     *   <li>{@code summary} — optional one-line summary the model
     *       wrote when closing the sub-task. May be {@code null}.</li>
     * </ul>
     */
    record SubTaskEnd(int taskId, String subTaskId, String status, String summary) implements StreamEvent {}
}
