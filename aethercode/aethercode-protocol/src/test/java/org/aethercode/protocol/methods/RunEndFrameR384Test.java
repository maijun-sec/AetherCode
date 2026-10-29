/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.protocol.methods;

import org.aethercode.core.stream.StreamEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R384 (2026-10-26) — there is exactly ONE {@code run_end} frame, and it
 * has ONE contract.
 *
 * <h2>What was wrong</h2>
 * A {@code run_end} frame's contract was written down in
 * {@code AetherCodeMethods.eventToMap}, but <b>three</b> code paths
 * assembled the frame:
 *
 * <ol>
 *   <li>{@code eventToMap} — the engine's own {@code RunEnd};</li>
 *   <li>the R745 synthetic terminal (the consumer ran out of events
 *       without a {@code RunEnd});</li>
 *   <li>the {@code catch (Throwable)} terminal.</li>
 * </ol>
 *
 * <p>The two synthetic ones built the frame by hand and <b>omitted
 * {@code stopKind}</b> — the field R383 had just put on the wire. That
 * was not cosmetic: R383 made "the verdict comes from the daemon" the
 * RULE, so a client that reads {@code isError} and expects a
 * {@code stopKind} beside it got {@code undefined} on exactly those two
 * paths. The desktop's wire-first path then collapsed the precise kind
 * ({@code no_run_end}) into the generic one ({@code error}) — a
 * diagnosis the user cannot act on, and one the prefix-match fallback
 * would have got right.
 *
 * <h2>What this test pins</h2>
 * <ol>
 *   <li>the frame ALWAYS carries {@code stopKind} — on clean runs too, so
 *       a client can tell "the run finished cleanly" from "the frame did
 *       not say";</li>
 *   <li>the kind is the ENGINE's bare kind, not a client-side re-parse;</li>
 *   <li>the verdict ({@code isError}) comes from the engine's taxonomy
 *       ({@code StreamEvent.isErrorOutcome()}) and is present EXACTLY
 *       when that taxonomy says "failure";</li>
 *   <li>{@code error} carries the caller's detail when given, and the
 *       stop reason otherwise;</li>
 *   <li>the two SYNTHETIC terminals — the paths that were broken — carry
 *       the same contract as the engine's own {@code RunEnd}.</li>
 * </ol>
 */
class RunEndFrameR384Test {

    private static final List<String> REAL_REASONS = List.of(
            "end_turn",
            "stop",
            "tool_calls",
            "max_tokens",
            "empty_input",
            "cancelled",
            "awaiting_user_decision",
            "error",
            "error: WebClientResponseException$InternalServerError: 500 from POST https://api.minimaxi.com/v1/chat/completions",
            "runtime_exception: java.lang.IllegalStateException: boom",
            "same_error_hard_stop",
            "loop_detected: empty_tool_input (4 consecutive empty tool calls)",
            "no_run_end: consumer exited without terminal event",
            "consumer_timeout: no stream event for 180000ms (run r-1)",
            "stale_force_end");

    @ParameterizedTest(name = "stopKind is carried for {0}")
    @ValueSource(strings = {
            "end_turn",
            "stop",
            "tool_calls",
            "max_tokens",
            "empty_input",
            "cancelled",
            "awaiting_user_decision",
            "error",
            "error: WebClientResponseException$InternalServerError: 500 from POST https://x",
            "runtime_exception: java.lang.IllegalStateException: boom",
            "same_error_hard_stop",
            "loop_detected: empty_tool_input (4 consecutive empty tool calls)",
            "no_run_end: consumer exited without terminal event",
            "consumer_timeout: no stream event for 180000ms (run r-1)",
            "stale_force_end",
    })
    @DisplayName("R384: EVERY run_end frame carries the engine's canonical kind")
    void everyFrameCarriesTheKind(String reason) {
        Map<String, Object> frame = AetherCodeMethods.runEndFrame(reason, null);
        assertEquals("run_end", frame.get("type"), "the frame type is wrong");
        assertEquals(reason, frame.get("stopReason"), "the raw reason must be carried verbatim");
        assertEquals(new StreamEvent.RunEnd(reason, List.of()).bareKind(), frame.get("stopKind"),
                "stopKind must be the engine's bareKind() of " + reason
                        + " — a client must never have to re-parse the decorated reason");
    }

    @Test
    @DisplayName("R384: the verdict comes from the engine's taxonomy, and only when it says failure")
    void theVerdictIsTheEngines() {
        for (String reason : REAL_REASONS) {
            Map<String, Object> frame = AetherCodeMethods.runEndFrame(reason, null);
            boolean failure = new StreamEvent.RunEnd(reason, List.of()).isErrorOutcome();
            if (failure) {
                assertEquals(Boolean.TRUE, frame.get("isError"),
                        "the engine calls " + reason + " a failure, so the frame must say so");
                assertNotNull(frame.get("error"), "a failure must carry an `error` field");
            } else {
                assertFalse(frame.containsKey("isError"),
                        "the engine does NOT call " + reason + " a failure, so the frame must "
                                + "carry NO verdict — absent and false mean different things");
                assertFalse(frame.containsKey("error"),
                        "a clean run must carry no `error` field: " + reason);
            }
        }
    }

    @Test
    @DisplayName("R384: the caller's detail is the `error`; without one the stop reason is")
    void theErrorField() {
        Map<String, Object> withDetail = AetherCodeMethods.runEndFrame(
                "no_run_end: consumer exited without terminal event",
                "stream exhausted without a RunEnd event (R745)");
        assertEquals("stream exhausted without a RunEnd event (R745)", withDetail.get("error"),
                "the caller's human-readable cause must reach the `error` field");

        Map<String, Object> withoutDetail = AetherCodeMethods.runEndFrame("error: boom", null);
        assertEquals("error: boom", withoutDetail.get("error"),
                "without a detail the `error` field is the stop reason");
    }

    @Test
    @DisplayName("R384: the SYNTHETIC terminals carry the same contract as the engine's own RunEnd")
    void theSyntheticTerminalsHonourTheContract() {
        // These two are the paths that were broken: they assembled the
        // frame by hand and omitted the kind.
        Map<String, Object> noRunEnd = AetherCodeMethods.runEndFrame(
                "no_run_end: consumer exited without terminal event",
                "stream exhausted without a RunEnd event (R745)");
        assertEquals("no_run_end", noRunEnd.get("stopKind"),
                "the R745 synthetic terminal must carry its kind — a client that trusts the wire "
                        + "would otherwise collapse 'no_run_end' into 'error'");
        assertEquals(Boolean.TRUE, noRunEnd.get("isError"),
                "the R745 synthetic terminal IS a failure (the engine's taxonomy says so)");

        Map<String, Object> caught = AetherCodeMethods.runEndFrame("error: boom", "boom");
        assertEquals("error", caught.get("stopKind"),
                "the catch(Throwable) terminal must carry its kind");
        assertEquals(Boolean.TRUE, caught.get("isError"),
                "the catch(Throwable) terminal IS a failure");
    }

    @Test
    @DisplayName("R384: the frame is ADDITIVE — a client that predates the kind still works")
    void theFrameIsAdditive() {
        Map<String, Object> frame = AetherCodeMethods.runEndFrame("end_turn", null);
        assertTrue(frame.containsKey("type"));
        assertTrue(frame.containsKey("stopReason"));
        assertTrue(frame.containsKey("stopKind"));
        // Nothing is REMOVED: the fields the R750d frame had are all
        // still there.
        assertEquals("end_turn", frame.get("stopReason"));
    }
}
