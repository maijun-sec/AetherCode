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

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link LlmSelfCorrectionStrategy}.
 */
class LlmSelfCorrectionStrategyTest {

    private static VerificationResult dummyFail() {
        return VerificationResult.fail(Severity.BLOCK, "test", java.util.Map.of());
    }

    /* ----------------------- constructor ----------------------- */

    @Test
    void constructorRejectsBlankName() {
        assertThrows(IllegalArgumentException.class,
                () -> new LlmSelfCorrectionStrategy<>("", (p, f, a) -> p));
    }

    @Test
    void constructorRejectsNullRevise() {
        assertThrows(IllegalArgumentException.class,
                () -> new LlmSelfCorrectionStrategy<>("v", null));
    }

    /* ----------------------- next() ----------------------- */

    @Test
    void nextReturnsReviseResult() {
        LlmSelfCorrectionStrategy.ReviseFn<String> fn = (prev, fail, attempt) -> "v" + (attempt + 1);
        LlmSelfCorrectionStrategy<String> s = new LlmSelfCorrectionStrategy<>("v", fn);
        assertEquals("v2", s.next("v1", dummyFail(), 1));
    }

    @Test
    void nextForwardsAttemptNumber() {
        AtomicInteger seen = new AtomicInteger();
        LlmSelfCorrectionStrategy.ReviseFn<String> fn = (prev, fail, attempt) -> {
            seen.set(attempt);
            return "next";
        };
        LlmSelfCorrectionStrategy<String> s = new LlmSelfCorrectionStrategy<>("v", fn);
        s.next("prev", dummyFail(), 5);
        assertEquals(5, seen.get());
    }

    @Test
    void nextForwardsFailureResult() {
        VerificationResult[] holder = new VerificationResult[1];
        LlmSelfCorrectionStrategy.ReviseFn<String> fn = (prev, fail, attempt) -> {
            holder[0] = fail;
            return "next";
        };
        LlmSelfCorrectionStrategy<String> s = new LlmSelfCorrectionStrategy<>("v", fn);
        VerificationResult fail = dummyFail();
        s.next("prev", fail, 1);
        assertSame(fail, holder[0]);
    }

    @Test
    void nextReturnsNullWhenReviseReturnsNull() {
        LlmSelfCorrectionStrategy<String> s = new LlmSelfCorrectionStrategy<>(
                "v", (p, f, a) -> null);
        assertNull(s.next("prev", dummyFail(), 1));
    }

    /* ----------------------- fallback ----------------------- */

    @Test
    void fallbackIsInvokedWhenReviseReturnsNull() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        CorrectionStrategy<String> fb = (prev, fail, attempt) -> {
            fallbackCalls.incrementAndGet();
            return "fallback-" + attempt;
        };
        LlmSelfCorrectionStrategy<String> s = new LlmSelfCorrectionStrategy<>(
                "v", (p, f, a) -> null, fb);
        assertEquals("fallback-3", s.next("prev", dummyFail(), 3));
        assertEquals(1, fallbackCalls.get());
    }

    @Test
    void fallbackIsNotInvokedWhenReviseSucceeds() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        CorrectionStrategy<String> fb = (prev, fail, attempt) -> {
            fallbackCalls.incrementAndGet();
            return "fallback";
        };
        LlmSelfCorrectionStrategy<String> s = new LlmSelfCorrectionStrategy<>(
                "v", (p, f, a) -> "revised", fb);
        assertEquals("revised", s.next("prev", dummyFail(), 1));
        assertEquals(0, fallbackCalls.get());
    }

    @Test
    void fallbackReturningNullPropagatesNull() {
        CorrectionStrategy<String> fb = (p, f, a) -> null;
        LlmSelfCorrectionStrategy<String> s = new LlmSelfCorrectionStrategy<>(
                "v", (p, f, a) -> null, fb);
        assertNull(s.next("prev", dummyFail(), 1));
    }

    /* ----------------------- of() factory ----------------------- */

    @Test
    void ofFactoryBuildsStrategyFromBiFunction() {
        LlmSelfCorrectionStrategy<String> s = LlmSelfCorrectionStrategy.of(
                "v", (prev, fail) -> "fixed");
        assertEquals("fixed", s.next("prev", dummyFail(), 1));
    }

    /* ----------------------- accessors ----------------------- */

    @Test
    void nameAndDescription() {
        LlmSelfCorrectionStrategy<String> plain = new LlmSelfCorrectionStrategy<>(
                "myllm", (p, f, a) -> p);
        LlmSelfCorrectionStrategy<String> withFallback = new LlmSelfCorrectionStrategy<>(
                "myllm", (p, f, a) -> p, RetryStrategy.giveUpImmediately());
        assertEquals("myllm", plain.name());
        assertTrue(plain.description().contains("LLM"), plain.description());
        assertTrue(plain.description().contains("fallback") == false ||
                !plain.description().contains("with fallback"), plain.description());
        assertTrue(withFallback.description().contains("fallback"), withFallback.description());
    }
}
