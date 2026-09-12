package org.aethercode.orchestration.selfcorrect;

import org.aethercode.orchestration.verifier.Verifier.VerificationResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for the cheap {@link RetryStrategy} helpers.
 */
class RetryStrategyTest {

    private static VerificationResult dummyFail() {
        return VerificationResult.fail(org.aethercode.orchestration.verifier.Verifier.Severity.BLOCK,
                "test", java.util.Map.of());
    }

    /* ----------------------- retrySame ----------------------- */

    @Test
    void retrySameReturnsPrevious() {
        CorrectionStrategy<String> s = RetryStrategy.retrySame();
        String prev = "hello";
        String next = s.next(prev, dummyFail(), 1);
        assertSame(prev, next);
        // description non-empty
        assertNotNull(s.description());
    }

    @Test
    void retrySameIgnoresAttemptNumber() {
        CorrectionStrategy<String> s = RetryStrategy.retrySame();
        for (int i = 1; i <= 10; i++) {
            assertEquals("v", s.next("v", dummyFail(), i));
        }
    }

    /* ----------------------- transform ----------------------- */

    @Test
    void transformAppliesOp() {
        CorrectionStrategy<String> s = RetryStrategy.transform(String::toUpperCase);
        assertEquals("HELLO", s.next("hello", dummyFail(), 1));
    }

    @Test
    void transformIsAppliedOnEachCall() {
        CorrectionStrategy<String> s = RetryStrategy.transform(x -> x + "!");
        assertEquals("a!", s.next("a", dummyFail(), 1));
        assertEquals("b!", s.next("b", dummyFail(), 2));
    }

    @Test
    void transformRejectsNullOp() {
        assertThrows(IllegalArgumentException.class,
                () -> RetryStrategy.transform(null));
    }

    /* ----------------------- giveUpImmediately ----------------------- */

    @Test
    void giveUpImmediatelyReturnsNull() {
        CorrectionStrategy<String> s = RetryStrategy.giveUpImmediately();
        assertNull(s.next("anything", dummyFail(), 1));
        assertNull(s.next("anything", dummyFail(), 99));
    }

    /* ----------------------- boundedRetry ----------------------- */

    @Test
    void boundedRetryAppliesOpUntilLimit() {
        CorrectionStrategy<String> s = RetryStrategy.boundedRetry(x -> x + "!", 3);
        assertEquals("a!", s.next("a", dummyFail(), 1));
        assertEquals("b!", s.next("b", dummyFail(), 2));
        assertEquals("c!", s.next("c", dummyFail(), 3));
        // attempt 4 > retryLimit=3 → null (give up)
        assertNull(s.next("d", dummyFail(), 4));
    }

    @Test
    void boundedRetryRejectsBadLimit() {
        assertThrows(IllegalArgumentException.class,
                () -> RetryStrategy.boundedRetry(x -> x, 0));
    }

    @Test
    void boundedRetryRejectsNullOp() {
        assertThrows(IllegalArgumentException.class,
                () -> RetryStrategy.boundedRetry(null, 3));
    }

    @Test
    void boundedRetryAtLimitBoundaryReturnsOp() {
        // attempt == limit → still applies (the null happens at attempt > limit)
        CorrectionStrategy<String> s = RetryStrategy.boundedRetry(x -> x + "x", 1);
        assertEquals("ax", s.next("a", dummyFail(), 1));
        assertNull(s.next("a", dummyFail(), 2));
    }
}
