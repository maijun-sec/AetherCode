package org.aethercode.core.stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R743 (2026-10-20) — {@link StreamEvent.RunEnd} owns the canonical
 * classification of the engine's stopReasons.
 *
 * <h2>Root cause (Lesson 1054 / R735b — exact match across the wire)</h2>
 * Three layers independently classified stopReasons with their own
 * prefix heuristics, and all three were wrong in different ways:
 *
 * <ul>
 *   <li>{@code AetherCodeMethods:3775} and
 *       {@code EngineContinuationDispatcher:152} used
 *       {@code stopReason.startsWith("loop")} to decide whether to
 *       {@code incLoopStop()}. That matches
 *       {@code "loop_detected: empty_tool_input (…)"} but NOT
 *       {@code "same_error_hard_stop"} (R181/R739) — the most common
 *       unattended death spiral in the field. So the loop-stop counter
 *       silently under-counted exactly the case it exists to alert on,
 *       and {@code getMetrics} reported a clean session while the
 *       engine was hard-stopping runs.</li>
 *   <li>{@code TodoContinuationHook:318} used
 *       {@code sr.startsWith("loop_")} (note the underscore) to detect
 *       the loop family. {@code "loop_detected: …"} starts with
 *       {@code "loop_d"}, not {@code "loop_"}, so it MISSED that branch
 *       entirely and fell through to step 8 — scheduling an
 *       auto-continuation 2s after the engine had deterministically
 *       decided to stop. {@code "same_error_hard_stop"} missed it too.
 *       The result: the engine hard-stops, the hook re-dispatches the
 *       same transcript, the model re-emits the identical broken call,
 *       and the R739 hard-stop is silently undone into an unattended
 *       token-burning loop.</li>
 *   <li>The desktop store exact-matched
 *       {@code stopReason === 'awaiting_user_decision'} in four
 *       places, right next to the prefix-matching {@code failedKind}
 *       R735b introduced — a latent trap one decoration away from
 *       silently auto-firing a follow-up query into a paused engine.</li>
 * </ul>
 *
 * <p>R743 collapses all of that onto one predicate. Lesson 1052
 * applies: a classification you cannot see exercised is a
 * classification you cannot prove shipped — so these tests enumerate
 * every stopReason the engine can actually emit, taken from the
 * source lines noted per-case.
 */
class StreamEventStopReasonR743Test {

    // ---- bareKindOf ----

    @Test
    @DisplayName("R743: bareKindOf strips the decorative suffix off a stopReason")
    void bareKindStripsDecoration() {
        // QueryEngine:2363
        assertEquals("loop_detected",
                StreamEvent.bareKindOf(
                        "loop_detected: empty_tool_input (4 consecutive empty tool calls)"));
        // SpringAiChatClient:325
        assertEquals("error",
                StreamEvent.bareKindOf(
                        "error: WebClientResponseException$InternalServerError: 500 "
                                + "Internal Server Error from POST https://api.minimaxi.com/..."));
        // bare reasons pass through untouched
        assertEquals("end_turn", StreamEvent.bareKindOf("end_turn"));
        assertEquals("same_error_hard_stop", StreamEvent.bareKindOf("same_error_hard_stop"));
        assertEquals("cancelled", StreamEvent.bareKindOf("cancelled"));
        assertEquals("", StreamEvent.bareKindOf(null));
        assertEquals("", StreamEvent.bareKindOf("   "));
    }

    @Test
    @DisplayName("R743: RunEnd.bareKindOf instance method agrees with the static")
    void instanceMethodDelegatesToStatic() {
        var re = new StreamEvent.RunEnd(
                "loop_detected: empty_tool_input (4 consecutive empty tool calls)", List.of());
        assertEquals("loop_detected", re.bareKind());
        assertEquals(StreamEvent.bareKindOf(re.stopReason()), re.bareKind(),
                "R743: the instance method must not re-implement the parse — one "
                        + "definition, or they drift");
    }

    // ---- isDeterministicHardStopOf: the three kinds ----

    @Test
    @DisplayName("R743: the engine's three deterministic hard-stops are recognised")
    void recognisesAllThreeDeterministicHardStops() {
        // QueryEngine:2363 — R266h empty-tool-input death spiral
        assertTrue(StreamEvent.isDeterministicHardStopOf(
                        "loop_detected: empty_tool_input (4 consecutive empty tool calls)"),
                "R743: the engine's empty_tool_input hard-stop must be recognised. Note "
                        + "it starts with 'loop_d', NOT 'loop_' — that is why "
                        + "TodoContinuationHook's old startsWith(\"loop_\") missed it");

        // QueryEngine:2568 — R181 / R739 same-error death spiral
        assertTrue(StreamEvent.isDeterministicHardStopOf("same_error_hard_stop"),
                "R743: same_error_hard_stop must be recognised. Note it does NOT start "
                        + "with 'loop' — that is why the metrics sinks' old "
                        + "startsWith(\"loop\") under-counted it");

        // QueryEngine:1983 — R721 user cancel
        assertTrue(StreamEvent.isDeterministicHardStopOf("cancelled"),
                "R743: a user-initiated cancel must never be treated as a "
                        + "continuation-worthy stop");
    }

    @Test
    @DisplayName("R743: successful / intermediate / paused stops are NOT hard-stops")
    void doesNotOverMatch() {
        assertFalse(StreamEvent.isDeterministicHardStopOf("stop"),
                "SpringAiChatClient:401 — the plain success stop");
        assertFalse(StreamEvent.isDeterministicHardStopOf("end_turn"),
                "the model yielded cleanly");
        assertFalse(StreamEvent.isDeterministicHardStopOf("tool_calls"),
                "SpringAiChatClient:431 — an intermediate hand-off, not terminal");
        assertFalse(StreamEvent.isDeterministicHardStopOf("awaiting_user_decision"),
                "QueryEngine:2628 — the engine is PAUSED waiting on the user; "
                        + "auto-resuming would race their answer");
        assertFalse(StreamEvent.isDeterministicHardStopOf("empty_input"),
                "QueryEngine:1819 — a no-op guard that never reaches a session-idle hook");
        assertFalse(StreamEvent.isDeterministicHardStopOf(null));
        assertFalse(StreamEvent.isDeterministicHardStopOf(""));
    }

    @Test
    @DisplayName("R743: 'error' stays out of the loop predicate (callers filter it separately)")
    void errorIsNotALoopKind() {
        // The `contains("error")` skip lives in TodoContinuationHook
        // and in the desktop's failedKind set. Keeping "error" out of
        // THIS predicate means incLoopStop() counts loops, not
        // failures — two different metrics.
        assertFalse(StreamEvent.isDeterministicHardStopOf(
                        "error: WebClientResponseException$InternalServerError: 500"),
                "R743: an upstream HTTP error is a transport failure, not a loop stop");
    }

    // ---- the regression this whole round exists for ----

    @Test
    @DisplayName("R743: every stopReason QueryEngine can emit is classified (exhaustive sweep)")
    void exhaustiveSweepOverEngineEmittedStopReasons() {
        // Each literal below is a stopReason string that appears in
        // a `stopReason = …` / `new RunEnd(…)` expression in the
        // engine's production sources. If a future round adds a new
        // hard-stop kind without updating the predicate, this sweep
        // is where it should show up (add a row).
        record Row(String reason, boolean hardStop, String origin) {}
        var rows = new java.util.ArrayList<>(List.of(
                new Row("end_turn", false, "QueryEngine:1951/2124 (default)"),
                new Row("stop", false, "SpringAiChatClient:401"),
                new Row("tool_calls", false, "SpringAiChatClient:431"),
                new Row("empty_input", false, "QueryEngine:1819"),
                new Row("cancelled", true, "QueryEngine:1983 (R721)"),
                new Row("loop_detected: empty_tool_input (3 consecutive empty tool calls)",
                        true, "QueryEngine:2363 (R266h)"),
                new Row("same_error_hard_stop", true, "QueryEngine:2568 (R181/R739)"),
                new Row("awaiting_user_decision", false, "QueryEngine:2628 (TodoRunController)"),
                new Row("runtime_exception: IllegalStateException: boom", false,
                        "QueryEngine:2659 (R735a) — handled by the error path, not the loop path"),
                new Row("error: Foo: bar", false, "SpringAiChatClient:135/325")));
        for (Row row : rows) {
            assertEquals(row.hardStop(),
                    StreamEvent.isDeterministicHardStopOf(row.reason()),
                    "R743: misclassified stopReason \"" + row.reason() + "\" from "
                            + row.origin());
        }
    }

    @Test
    @DisplayName("R743: a decorated hard-stop still classifies (no exact-match trap)")
    void decoratedHardStopsStillClassify() {
        // The whole point: these strings are ~120 chars and carry a
        // URL / diagnostic. An exact-match Set lookup returns false
        // for every one of them (the R735b failure mode).
        String decorated = "same_error_hard_stop: 12 turns, errorRate=0.89";
        assertTrue(StreamEvent.isDeterministicHardStopOf(decorated));
        assertEquals("same_error_hard_stop", StreamEvent.bareKindOf(decorated));

        String decoratedLoop = "loop_detected: empty_tool_input (30 consecutive empty tool calls)";
        assertTrue(StreamEvent.isDeterministicHardStopOf(decoratedLoop));
        assertEquals("loop_detected", StreamEvent.bareKindOf(decoratedLoop));
    }
}