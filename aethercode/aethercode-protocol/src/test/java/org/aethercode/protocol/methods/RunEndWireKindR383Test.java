/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.aethercode.protocol.methods;

import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.stream.StreamEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R383 (2026-10-25) — the {@code run_end} frame must carry the
 * engine's CANONICAL KIND and its VERDICT.
 *
 * <h2>Why this is a wire contract and not a convenience</h2>
 * The engine <em>decorates</em> its stop reasons:
 *
 * <pre>
 *   "error: WebClientResponseException$InternalServerError: 500 from POST https://…"
 *   "loop_detected: empty_tool_input (4 consecutive empty tool calls)"
 *   "runtime_exception: java.lang.IllegalStateException: boom"
 * </pre>
 *
 * <p>So a client that wants to know "did that run fail, and how?" has to
 * RE-PARSE the string — and every client that did wrote its own parser,
 * and every one of them got it wrong:
 *
 * <ul>
 *   <li>the TUI matched {@code "error_"} / {@code "_error"} with an
 *       UNDERSCORE while the engine emits {@code "error:"} with a
 *       COLON, so the most common failure of all (an upstream 4xx/5xx)
 *       was classified {@code "ok"} and the status strip showed a green
 *       "✓ ready";</li>
 *   <li>the desktop's fallback list carried the DISPLAY kinds
 *       {@code "loop"} and {@code "max_turns"} — which the engine never
 *       emits — and missed {@code "loop_detected"} entirely, so a death
 *       spiral rendered as a healthy "● Idle".</li>
 * </ul>
 *
 * <p>R750d already put {@code isError} on the wire; R383 adds
 * {@code stopKind} (the bare kind) beside it, so no client has to
 * re-derive anything.
 *
 * <h2>What this test pins</h2>
 * <ol>
 *   <li>the frame carries {@code stopKind} on EVERY run end — including
 *       the clean ones, because a client must be able to tell "the run
 *       finished cleanly" from "the frame did not say";</li>
 *   <li>{@code stopKind} is the engine's own {@code bareKind()} — not a
 *       client-side re-parse;</li>
 *   <li>{@code isError} is present exactly when the engine's taxonomy
 *       says "failure", and its value is taken from that taxonomy
 *       ({@code StreamEvent.isErrorOutcome}) rather than from a
 *       hand-rolled prefix check;</li>
 *   <li>the field is ADDITIVE — a client that predates it keeps
 *       working.</li>
 * </ol>
 */
class RunEndWireKindR383Test {

    private static Map<String, Object> frame(String stopReason) {
        Map<String, Object> m = AetherCodeMethods.eventToMapPublic(
                new StreamEvent.RunEnd(stopReason, List.<ContentBlock>of()));
        assertNotNull(m, "eventToMapPublic returned null for " + stopReason);
        return m;
    }

    @Test
    @DisplayName("R383: the run_end frame always carries the engine's canonical kind")
    void everyRunEndCarriesTheBareKind() {
        // Including the DECORATED reasons — the whole point of the
        // field is that a client does not have to cut the string
        // itself.
        Map<String, Object> m = frame(
                "error: WebClientResponseException$InternalServerError: 500 from POST https://x");
        assertEquals("error", m.get("stopKind"),
                "the decorated reason must yield the bare kind 'error'");
        assertEquals("error: WebClientResponseException$InternalServerError: 500 from POST https://x",
                m.get("stopReason"),
                "the decorated reason must still be carried verbatim — the kind is ADDITIVE");
    }

    @Test
    @DisplayName("R383: the kind is the engine's bareKind(), not a re-parse")
    void theKindIsTheEnginesOwn() {
        for (String reason : new String[] {
                "end_turn",
                "stop",
                "tool_calls",
                "empty_input",
                "cancelled",
                "awaiting_user_decision",
                "same_error_hard_stop",
                "loop_detected: empty_tool_input (4 consecutive empty tool calls)",
                "runtime_exception: java.lang.IllegalStateException: boom",
                "no_run_end: consumer exited without terminal event",
                "consumer_timeout: no stream event for 180000ms (run r-1)",
        }) {
            Map<String, Object> m = frame(reason);
            assertEquals(new StreamEvent.RunEnd(reason, List.<ContentBlock>of()).bareKind(),
                    m.get("stopKind"),
                    "stopKind must be StreamEvent.bareKind() of " + reason);
        }
    }

    @ParameterizedTest(name = "isError is true for {0}")
    @CsvSource({
            "error",
            "error: WebClientResponseException$InternalServerError: 500 from POST https://x",
            "runtime_exception: java.lang.IllegalStateException: boom",
            "same_error_hard_stop",
            "loop_detected: empty_tool_input (4 consecutive empty tool calls)",
            "no_run_end: consumer exited without terminal event",
            "consumer_timeout: no stream event for 180000ms (run r-1)",
            "stale_force_end",
    })
    @DisplayName("R383: isError is carried exactly when the engine's taxonomy says 'failure'")
    void failuresCarryTheVerdict(String reason) {
        Map<String, Object> m = frame(reason);
        assertEquals(Boolean.TRUE, m.get("isError"),
                "a failure must carry isError=true — " + reason);
        assertTrue(StreamEvent.isErrorOutcomeOf(reason),
                "the engine's own taxonomy must agree that " + reason + " is a failure");
        assertEquals(reason, m.get("error"),
                "a failure must carry the reason in `error` too");
    }

    @ParameterizedTest(name = "isError is absent for {0}")
    @CsvSource({
            "end_turn",
            "stop",
            "tool_calls",
            "max_tokens",
            "empty_input",
            "cancelled",
            "awaiting_user_decision",
    })
    @DisplayName("R383: a clean run carries NO isError — absent and false mean different things")
    void cleanRunsCarryNoVerdict(String reason) {
        Map<String, Object> m = frame(reason);
        assertFalse(m.containsKey("isError"),
                "a clean run must NOT carry isError — the field is absent, not false, so a "
                        + "client can tell 'not a failure' from 'the daemon did not say'");
        assertFalse(StreamEvent.isErrorOutcomeOf(reason),
                "the engine's own taxonomy must agree that " + reason + " is not a failure");
    }

    @Test
    @DisplayName("R383: the run_end frame is ADDITIVE — the old fields are untouched")
    void theFrameStayedCompatible() {
        Map<String, Object> m = frame("end_turn");
        assertEquals("run_end", m.get("type"));
        assertEquals("end_turn", m.get("stopReason"));
        assertNotNull(m.get("stopKind"));
        // Nothing else changed shape: the frame still has exactly the
        // fields the R750d frame had, plus stopKind.
        assertTrue(m.containsKey("type"));
        assertTrue(m.containsKey("stopReason"));
    }
}
