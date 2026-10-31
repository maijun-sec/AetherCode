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

package org.aethercode.orchestration.selfcorrect;

import org.aethercode.orchestration.verifier.Verifier.Severity;
import org.aethercode.orchestration.verifier.Verifier.VerificationResult;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link HumanCorrectionStrategy}.
 */
class HumanCorrectionStrategyTest {

    private static VerificationResult dummyFail() {
        return VerificationResult.fail(Severity.BLOCK, "test", java.util.Map.of());
    }

    /* ----------------------- constructor ----------------------- */

    @Test
    void constructorRejectsBlankName() {
        assertThrows(IllegalArgumentException.class,
                () -> new HumanCorrectionStrategy<String>("", (p, f, a) -> null, 5));
    }

    @Test
    void constructorRejectsZeroTimeout() {
        assertThrows(IllegalArgumentException.class,
                () -> new HumanCorrectionStrategy<String>("h", (p, f, a) -> null, 0));
    }

    /* ----------------------- auto-give-up when not wired ----------------------- */

    @Test
    void autoGiveUpWhenNotWired() {
        HumanCorrectionStrategy<String> s = new HumanCorrectionStrategy<>("h", null, 5);
        assertNull(s.next("prev", dummyFail(), 1));
        assertFalse(s.isWired());
    }

    /* ----------------------- wired callback ----------------------- */

    @Test
    void wiredCallbackReturnsResult() {
        HumanCorrectionStrategy<String> s = new HumanCorrectionStrategy<>(
                "h", (prev, fail, attempt) -> CompletableFuture.completedFuture("human-fixed"), 5);
        assertEquals("human-fixed", s.next("prev", dummyFail(), 1));
        assertTrue(s.isWired());
    }

    @Test
    void wiredCallbackReturningNullTreatedAsGiveUp() {
        HumanCorrectionStrategy<String> s = new HumanCorrectionStrategy<>(
                "h", (prev, fail, attempt) -> null, 5);
        assertNull(s.next("prev", dummyFail(), 1));
    }

    @Test
    void wiredCallbackThrowingIsContained() {
        HumanCorrectionStrategy<String> s = new HumanCorrectionStrategy<>(
                "h", (prev, fail, attempt) -> { throw new RuntimeException("UI crashed"); }, 5);
        assertNull(s.next("prev", dummyFail(), 1));
    }

    @Test
    void wiredCallbackReturningFailingFutureIsContained() {
        CompletableFuture<String> failing = new CompletableFuture<>();
        failing.completeExceptionally(new RuntimeException("network"));
        HumanCorrectionStrategy<String> s = new HumanCorrectionStrategy<>(
                "h", (prev, fail, attempt) -> failing, 5);
        assertNull(s.next("prev", dummyFail(), 1));
    }

    @Test
    void timeoutGivesUp() {
        // Never-complete future → timeout
        HumanCorrectionStrategy<String> s = new HumanCorrectionStrategy<>(
                "h", (prev, fail, attempt) -> new CompletableFuture<>(), 1);
        long start = System.currentTimeMillis();
        assertNull(s.next("prev", dummyFail(), 1));
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed >= 1000 && elapsed < 3000, "timeout must be ~1s, got " + elapsed + "ms");
    }

    /* ----------------------- fallback ----------------------- */

    @Test
    void fallbackIsInvokedWhenAskReturnsNull() {
        HumanCorrectionStrategy<String> s = new HumanCorrectionStrategy<String>(
                "h", (p, f, a) -> null, 5, RetryStrategy.transform(x -> x + "?"));
        assertEquals("prev?", s.next("prev", dummyFail(), 1));
    }

    @Test
    void fallbackIsNotInvokedWhenAskSucceeds() {
        HumanCorrectionStrategy<String> s = new HumanCorrectionStrategy<String>(
                "h", (p, f, a) -> CompletableFuture.completedFuture("got-it"),
                5, RetryStrategy.transform(x -> x + "?"));
        assertEquals("got-it", s.next("prev", dummyFail(), 1));
    }

    /* ----------------------- accessors ----------------------- */

    @Test
    void nameAndAccessors() {
        // Unwired (no AskHuman callback) → description should mention auto-give-up
        HumanCorrectionStrategy<String> unwired = new HumanCorrectionStrategy<String>(
                "gate1", null, 7);
        assertEquals("gate1", unwired.name());
        assertEquals(7, unwired.timeoutS());
        assertNotNull(unwired.description());
        assertTrue(unwired.description().contains("auto-give-up"),
                "description should mention auto-give-up when not wired: " + unwired.description());

        // Wired (callback present) → description does NOT include auto-give-up
        HumanCorrectionStrategy<String> wired = new HumanCorrectionStrategy<>(
                "gate2", (p, f, a) -> null, 7);
        assertFalse(wired.description().contains("auto-give-up"),
                "description should NOT mention auto-give-up when wired: " + wired.description());
    }
}
