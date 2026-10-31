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

package org.aethercode.hooks.builtin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R743 (2026-10-20) — {@link TodoContinuationHook} must NOT
 * auto-continue a run the engine has already stopped
 * deterministically.
 *
 * <h2>Root cause</h2>
 * The hook's loop-family gate was
 * {@code if (sr.startsWith("loop_"))} — underscore, not
 * {@code "loop"}. Since R266h/R181/R739 the engine emits three
 * deterministic hard-stops that do not match it:
 *
 * <ul>
 *   <li>{@code "loop_detected: empty_tool_input (…)"} —
 *       {@code QueryEngine:2363}. Starts with {@code "loop_d"}.</li>
 *   <li>{@code "same_error_hard_stop"} — {@code QueryEngine:2568}.
 *       Does not start with {@code "loop"} at all.</li>
 *   <li>{@code "cancelled"} — {@code QueryEngine:1983} (R721).</li>
 * </ul>
 *
 * All three fell through the gate to step 8, which schedules a
 * continuation 2s later. The engine hard-stops, the hook re-dispatches
 * the same transcript, the model re-emits the identical broken call,
 * and the R739 hard-stop is silently undone into an unattended
 * token-burning loop. For {@code "cancelled"} it is worse: the hook
 * resurrects a run the user deliberately killed with Ctrl-C.
 *
 * <p>This is the R739 lesson applied one layer up — a deterministic
 * engine decision must be honoured by a deterministic hook path, not
 * routed through a name-prefix heuristic that predates the decision.
 *
 * <h2>Test shape</h2>
 * These are BEHAVIOURAL: the hook is driven exactly as production
 * drives it ({@code onSessionIdle} with a non-empty incomplete todo
 * list) and we assert nothing lands on the dispatcher's queue. A
 * source-pin would not catch a reordering of the guard relative to
 * step 8.
 */
class TodoContinuationHookHardStopR743Test {

    private TodoContinuationHookTest.TestDispatcher dispatcher;
    private TodoContinuationHook hook;

    @BeforeEach
    void setUp() {
        dispatcher = new TodoContinuationHookTest.TestDispatcher();
        hook = new TodoContinuationHook(dispatcher);
        hook.setCountdownSecondsForTest(0);
        hook.setCooldownMsForTest(20);
    }

    @AfterEach
    void tearDown() {
        hook.shutdown();
    }

    private static Map<String, Object> todo(String status, String content) {
        Map<String, Object> m = new HashMap<>();
        m.put("content", content);
        m.put("status", status);
        return m;
    }

    private void assertNoDispatch(String stopReason, String because) throws Exception {
        hook.onSessionIdle("s1", "run-1", stopReason,
                List.of(todo("in_progress", "fix the engine")));
        assertThat(dispatcher.dispatches.poll(400, TimeUnit.MILLISECONDS))
                .as("R743: %s — the hook must not re-dispatch after stopReason '%s'",
                        because, stopReason)
                .isNull();
    }

    // ---- the regression: R739 same-error death spiral ----

    @Test
    @DisplayName("R743: same_error_hard_stop must NOT be auto-continued")
    void sameErrorHardStopIsNotContinued() throws Exception {
        assertNoDispatch("same_error_hard_stop",
                "R739 made this a hard stop precisely because 'more thinking' cannot "
                        + "recover a deterministically-broken tool call; re-dispatching "
                        + "the same transcript re-runs the identical broken call");
    }

    // ---- the regression: empty-tool-input death spiral ----

    @Test
    @DisplayName("R743: loop_detected: empty_tool_input must NOT be auto-continued")
    void emptyToolInputHardStopIsNotContinued() throws Exception {
        // Note the string is DECORATED — this is what makes it a
        // prefix-match test rather than an equals test.
        assertNoDispatch("loop_detected: empty_tool_input (4 consecutive empty tool calls)",
                "this starts with 'loop_d', NOT 'loop_', so the hook's old "
                        + "startsWith(\"loop_\") gate missed it entirely and it fell "
                        + "through to the dispatch step");
    }

    // ---- the regression: user cancel ----

    @Test
    @DisplayName("R743: a user cancel must never be resurrected")
    void cancelledIsNotContinued() throws Exception {
        assertNoDispatch("cancelled",
                "R721's thread-interrupt cancel is a deliberate user action; "
                        + "auto-continuing 2s later overrides it");
    }

    // ---- over-correction guards: these must still continue ----

    @Test
    @DisplayName("R743: an ordinary mid-task yield STILL continues (no over-correction)")
    void ordinaryYieldStillContinues() throws Exception {
        hook.onSessionIdle("s1", "run-1", "end_turn",
                List.of(todo("in_progress", "fix the engine")));
        assertThat(dispatcher.dispatches.poll(2, TimeUnit.SECONDS))
                .as("R743: 'end_turn' with incomplete todos is the hook's whole reason "
                        + "to exist — it must still dispatch")
                .isNotNull();
    }

    @Test
    @DisplayName("R743: loop_research_mode recovery still continues (regression guard)")
    void researchModeRecoveryStillContinues() throws Exception {
        hook.onSessionIdle("s1", "run-1", "loop_research_mode",
                List.of(todo("in_progress", "fix the engine")));
        assertThat(dispatcher.dispatches.poll(2, TimeUnit.SECONDS))
                .as("R743: R141/R142 deliberately allow ONE research_mode recovery — "
                        + "the R743 guard must not swallow it")
                .isNotNull();
    }

    @Test
    @DisplayName("R743: loop_same_fingerprint still skips (regression guard)")
    void sameFingerprintStillSkips() throws Exception {
        // Pre-existing R141 behaviour: same_fingerprint is a
        // non-recoverable loop and must keep its safe-default skip.
        // R743 must not change this.
        assertNoDispatch("loop_same_fingerprint",
                "R141's safe-default skip for non-recoverable loop kinds must survive");
    }

    // ---- predicate delegation ----

    @Test
    @DisplayName("R743: the hook's predicate is the canonical StreamEvent one")
    void predicateDelegatesToStreamEvent() {
        // The hook must NOT keep its own copy of the string list —
        // that is how the layers drifted in the first place.
        for (String s : new String[]{
                "same_error_hard_stop",
                "loop_detected: empty_tool_input (4 consecutive empty tool calls)",
                "cancelled",
                "end_turn", "stop", "tool_calls", "awaiting_user_decision", "error: x"}) {
            assertThat(TodoContinuationHook.isDeterministicHardStop(s))
                    .as("R743: predicate disagreement on '%s' between the hook and "
                            + "StreamEvent.isDeterministicHardStopOf", s)
                    .isEqualTo(org.aethercode.core.stream.StreamEvent
                            .isDeterministicHardStopOf(s));
        }
    }
}