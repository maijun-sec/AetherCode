package org.aethercode.core.stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R750d — the ONE definition of "did this run fail".
 *
 * <p>Before this round the same question was answered in three places with
 * three different rules:
 *
 * <ol>
 *   <li>{@code AetherCodeMethods.eventToMap} — no {@code isError} at
 *       all, so every engine-terminated failure looked clean on the wire;</li>
 *   <li>{@code ClaudeStreamJsonEmitter} — {@code !OK_KINDS.contains(kind)},
 *       an ALLOWLIST, which reported {@code cancelled} as
 *       {@code is_error: true};</li>
 *   <li>the desktop's {@code run_end} handler — a hardcoded prefix list,
 *       which happened to be right for the reasons it named and absent for
 *       the rest.</li>
 * </ol>
 *
 * <p>R743 already established that when several layers hand-roll a prefix
 * check over the same decorated string they drift, and at least one is
 * wrong. That is exactly what happened here. These tests pin the single
 * shared predicate so the next reason added cannot be silently
 * misclassified by one layer while the others agree.
 *
 * <p>The decorated-vs-bare distinction is load-bearing: the engine emits
 * {@code "runtime_exception: RuntimeException: upstream 503"}, and an exact
 * match against that would classify it as NOT an error — reintroducing the
 * original bug at a new layer.
 */
class StreamEventErrorOutcomeR750Test {

    // ---- failures: must be reported as errors ------------------------

    @ParameterizedTest(name = "{0} is a failure")
    @ValueSource(strings = {
            // R735a outer catch: "runtime_exception: <class>: <msg>".
            "runtime_exception",
            "runtime_exception: RuntimeException: upstream 503: gateway timeout",
            // SpringAiChatClient's terminal error shape (R697 retry exhaustion).
            "error",
            "error: WebClientResponseException: 500 Internal Server Error",
            // R745/R746 consumer-side terminal events.
            "no_run_end: engine stream ended without RunEnd",
            "consumer_timeout: no stream event within 180000ms (5 event(s) received)",
            // R181 / R739 — a death spiral IS a failure.
            "same_error_hard_stop",
            "loop_detected: empty_tool_input (3 consecutive empty tool calls)",
            // R750b surfaced a wedged first-chunk read as a timeout.
            "stale_force_end",
    })
    void failures_areReportedAsErrors(String stopReason) {
        assertTrue(StreamEvent.isErrorOutcomeOf(stopReason),
                "must classify as a failure: " + stopReason);
    }

    // ---- non-failures: must NOT be reported as errors ----------------

    @ParameterizedTest(name = "{0} is not a failure")
    @ValueSource(strings = {
            "end_turn",
            "stop",
            "tool_use",
            "tool_calls",
            // R721: the USER asked for this. Claude Code reports a cancelled
            // run as a normal exit; the emitter's old allowlist disagreed and
            // marked it is_error=true, which is the concrete bug R750d removed.
            "cancelled",
            // The engine is pausing for input — the system working as intended.
            "awaiting_user_decision",
            // R181's softer empty-input terminal is a pause, not a crash.
            "empty_input",
    })
    void nonFailures_areNotReportedAsErrors(String stopReason) {
        assertFalse(StreamEvent.isErrorOutcomeOf(stopReason),
                "must NOT classify as a failure: " + stopReason);
    }

    @Test
    void nullAndBlank_areNotFailures() {
        // A defensive case: eventToMap can see a RunEnd built with a null
        // reason (the record allows it), and `null` must not NPE on the wire.
        assertFalse(StreamEvent.isErrorOutcomeOf(null));
        assertFalse(StreamEvent.isErrorOutcomeOf(""));
        assertFalse(StreamEvent.isErrorOutcomeOf("   "));
    }

    // ---- the two predicates must stay DISTINCT ------------------------
    //
    // `isDeterministicHardStop` answers "did the engine DECIDE to stop";
    // `isErrorOutcome` answers "was the outcome a failure". Collapsing them
    // would either start counting errors as loop-stops or stop counting
    // death spirals as failures. R743's note that `error…` is deliberately
    // excluded from the hard-stop predicate is why this distinction needs a
    // test rather than a comment.

    @Test
    void errorOutcome_andDeterministicHardStop_areDifferentQuestions() {
        // runtime_exception: a failure, but the engine did not decide to stop
        // — the provider did. Must not inflate the loop-stop counter.
        String runtimeFailure = "runtime_exception: RuntimeException: upstream 503";
        assertTrue(StreamEvent.isErrorOutcomeOf(runtimeFailure));
        assertFalse(StreamEvent.isDeterministicHardStopOf(runtimeFailure));

        // same_error_hard_stop: both. The engine decided, and it is a failure.
        String hardStop = "same_error_hard_stop";
        assertTrue(StreamEvent.isErrorOutcomeOf(hardStop));
        assertTrue(StreamEvent.isDeterministicHardStopOf(hardStop));

        // cancelled: a decision, but not a failure.
        assertFalse(StreamEvent.isErrorOutcomeOf("cancelled"));
        assertTrue(StreamEvent.isDeterministicHardStopOf("cancelled"));
    }

    // ---- the record method delegates to the static twin ---------------

    @Test
    void runEndRecordMethod_agreesWithTheStaticPredicate() {
        StreamEvent.RunEnd re = new StreamEvent.RunEnd(
                "runtime_exception: RuntimeException: upstream 503", java.util.List.of());
        assertTrue(re.isErrorOutcome());
        assertFalse(re.isDeterministicHardStop());
        assertEquals("runtime_exception", re.bareKind());

        StreamEvent.RunEnd ok = new StreamEvent.RunEnd("end_turn", java.util.List.of());
        assertFalse(ok.isErrorOutcome());
    }

    private static void assertEquals(Object expected, Object actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }

    // ---- a bare kind that is neither known-nor-error is NOT a failure --
    //
    // The predicate is a denylist of failure kinds. An unrecognised reason
    // reads as "not a failure", which is the fail-safe direction for the
    // desktop (no red pill on an unfamiliar-but-benign stop) — but it means a
    // NEW failure kind must be added here deliberately. This test documents
    // that so the next reader knows the set is not exhaustive by accident.

    @Test
    void anUnrecognisedReason_isNotSilentlyTreatedAsAFailure() {
        assertFalse(StreamEvent.isErrorOutcomeOf("some_future_reason"));
        assertFalse(StreamEvent.isErrorOutcomeOf("prompt_too_long"));
    }
}