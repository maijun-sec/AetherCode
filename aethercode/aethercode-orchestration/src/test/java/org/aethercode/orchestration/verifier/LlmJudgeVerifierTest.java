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

package org.aethercode.orchestration.verifier;

import org.aethercode.orchestration.verifier.LlmJudgeVerifier.JudgeFn;
import org.aethercode.orchestration.verifier.LlmJudgeVerifier.JudgeReply;
import org.aethercode.orchestration.verifier.LlmJudgeVerifier.Verdict;
import org.aethercode.orchestration.verifier.Verifier.Severity;
import org.aethercode.orchestration.verifier.Verifier.VerificationResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link LlmJudgeVerifier}.
 */
class LlmJudgeVerifierTest {

    private static JudgeFn stub(Verdict v) {
        return (input, rubric) -> new JudgeReply(v, "stub says " + v, v == Verdict.PASS ? 0.9 : null);
    }

    private static JudgeFn throwing() {
        return (input, rubric) -> { throw new RuntimeException("judge offline"); };
    }

    private static JudgeFn returningNull() {
        return (input, rubric) -> null;
    }

    /* ----------------------- constructor ----------------------- */

    @Test
    void constructorRejectsBlankName() {
        assertThrows(IllegalArgumentException.class,
                () -> new LlmJudgeVerifier("", "rubric", stub(Verdict.PASS)));
    }

    @Test
    void constructorRejectsBlankRubric() {
        assertThrows(IllegalArgumentException.class,
                () -> new LlmJudgeVerifier("v", "", stub(Verdict.PASS)));
        assertThrows(IllegalArgumentException.class,
                () -> new LlmJudgeVerifier("v", null, stub(Verdict.PASS)));
    }

    @Test
    void constructorRejectsNullJudge() {
        assertThrows(IllegalArgumentException.class,
                () -> new LlmJudgeVerifier("v", "rubric", null));
    }

    /* ----------------------- pass / fail / inconclusive ----------------------- */

    @Test
    void passVerdictBecomesPass() {
        LlmJudgeVerifier v = new LlmJudgeVerifier("v", "Is it good?", stub(Verdict.PASS));
        VerificationResult r = v.verify("the answer");
        assertTrue(r.passed());
        assertEquals(Severity.INFO, r.severity());
        assertEquals("stub says PASS", r.reason());
    }

    @Test
    void failVerdictBecomesBlockByDefault() {
        LlmJudgeVerifier v = new LlmJudgeVerifier("v", "Is it good?", stub(Verdict.FAIL));
        VerificationResult r = v.verify("the answer");
        assertFalse(r.passed());
        assertEquals(Severity.BLOCK, r.severity());
        assertEquals("stub says FAIL", r.reason());
    }

    @Test
    void inconclusiveVerdictBecomesWarnByDefault() {
        LlmJudgeVerifier v = new LlmJudgeVerifier("v", "Is it good?", stub(Verdict.INCONCLUSIVE));
        VerificationResult r = v.verify("the answer");
        assertFalse(r.passed());
        assertEquals(Severity.WARN, r.severity());
    }

    @Test
    void customSeveritiesAreHonored() {
        LlmJudgeVerifier v = new LlmJudgeVerifier("v", "r", stub(Verdict.FAIL),
                Severity.WARN, Severity.BLOCK);
        VerificationResult r = v.verify("x");
        // FAIL with custom fail-severity WARN
        assertEquals(Severity.WARN, r.severity());
    }

    @Test
    void inconclusiveCustomSeverity() {
        LlmJudgeVerifier v = new LlmJudgeVerifier("v", "r", stub(Verdict.INCONCLUSIVE),
                Severity.BLOCK, Severity.BLOCK);
        VerificationResult r = v.verify("x");
        assertEquals(Severity.BLOCK, r.severity());
    }

    /* ----------------------- error containment ----------------------- */

    @Test
    void judgeThrowingIsContained() {
        LlmJudgeVerifier v = new LlmJudgeVerifier("v", "r", throwing());
        VerificationResult r = v.verify("x");
        assertFalse(r.passed());
        assertEquals(Severity.BLOCK, r.severity());
        assertTrue(r.reason().contains("judge offline"), r.reason());
        assertEquals("java.lang.RuntimeException", r.metadata().get("exception"));
    }

    @Test
    void judgeReturningNullIsTreatedAsInconclusive() {
        LlmJudgeVerifier v = new LlmJudgeVerifier("v", "r", returningNull());
        VerificationResult r = v.verify("x");
        assertFalse(r.passed());
        assertEquals(Severity.WARN, r.severity());
        assertTrue(r.reason().contains("null"), r.reason());
    }

    @Test
    void nullInputFails() {
        LlmJudgeVerifier v = new LlmJudgeVerifier("v", "r", stub(Verdict.PASS));
        VerificationResult r = v.verify(null);
        assertFalse(r.passed());
        assertEquals(Severity.BLOCK, r.severity());
    }

    /* ----------------------- metadata ----------------------- */

    @Test
    void passMetadataIncludesScore() {
        LlmJudgeVerifier v = new LlmJudgeVerifier("v", "r", stub(Verdict.PASS));
        VerificationResult r = v.verify("x");
        assertNotNull(r.metadata().get("judge_score"));
        assertEquals(0.9, r.metadata().get("judge_score"));
    }

    @Test
    void failMetadataExposesVerdict() {
        LlmJudgeVerifier v = new LlmJudgeVerifier("v", "r", stub(Verdict.FAIL));
        VerificationResult r = v.verify("x");
        assertEquals("FAIL", r.metadata().get("judge_verdict"));
    }

    /* ----------------------- accessors ----------------------- */

    @Test
    void nameDescriptionAndAccessors() {
        LlmJudgeVerifier v = new LlmJudgeVerifier("judge1", "is this safe?", stub(Verdict.PASS));
        assertEquals("judge1", v.name());
        assertTrue(v.description().contains("is this safe?"), v.description());
        assertEquals("is this safe?", v.rubric());
        assertNotNull(v.judge());
    }

    /* ----------------------- judge record ----------------------- */

    @Test
    void judgeReplyRejectsNullVerdict() {
        assertThrows(IllegalArgumentException.class,
                () -> new JudgeReply(null, "r", 0.5));
    }

    @Test
    void judgeReplyDefaultsReasonToEmpty() {
        JudgeReply r = new JudgeReply(Verdict.PASS, null, null);
        assertEquals("", r.reason());
    }
}
