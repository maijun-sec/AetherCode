package org.aethercode.evals.verifier;

import org.aethercode.evals.verifier.HumanVerifier.AskHuman;
import org.aethercode.evals.verifier.HumanVerifier.Decision;
import org.aethercode.evals.verifier.Verifier.Severity;
import org.aethercode.evals.verifier.Verifier.VerificationResult;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link HumanVerifier}.
 */
class HumanVerifierTest {

    private static AskHuman decide(Decision d) {
        return summary -> CompletableFuture.completedFuture(d);
    }

    /* ----------------------- constructor ----------------------- */

    @Test
    void constructorRejectsBlankName() {
        assertThrows(IllegalArgumentException.class,
                () -> new HumanVerifier("", "summary"));
    }

    @Test
    void constructorRejectsZeroTimeout() {
        assertThrows(IllegalArgumentException.class,
                () -> new HumanVerifier("v", "s", decide(Decision.APPROVE), 0));
    }

    /* ----------------------- auto-deny when not wired ----------------------- */

    @Test
    void autoDenyWhenNotWired() {
        HumanVerifier v = new HumanVerifier("v", "summary");
        VerificationResult r = v.verify("approve this action");
        assertFalse(r.passed());
        assertEquals(Severity.BLOCK, r.severity());
        assertTrue(r.reason().contains("not wired"), r.reason());
        assertEquals(false, r.metadata().get("wired"));
    }

    /* ----------------------- decisions ----------------------- */

    @Test
    void approvePasses() {
        HumanVerifier v = new HumanVerifier("v", "summary", decide(Decision.APPROVE), 5);
        VerificationResult r = v.verify("approve this action");
        assertTrue(r.passed());
        assertEquals(Severity.INFO, r.severity());
    }

    @Test
    void rejectFailsAsBlock() {
        HumanVerifier v = new HumanVerifier("v", "summary", decide(Decision.REJECT), 5);
        VerificationResult r = v.verify("approve this action");
        assertFalse(r.passed());
        assertEquals(Severity.BLOCK, r.severity());
        assertTrue(r.reason().contains("rejected"), r.reason());
        assertEquals("REJECT", r.metadata().get("decision"));
    }

    @Test
    void abstainFailsAsWarn() {
        // Abstain != reject: surface the gap, don't block the action.
        HumanVerifier v = new HumanVerifier("v", "summary", decide(Decision.ABSTAIN), 5);
        VerificationResult r = v.verify("approve this action");
        assertFalse(r.passed());
        assertEquals(Severity.WARN, r.severity());
    }

    /* ----------------------- timeout / failure containment ----------------------- */

    @Test
    void timeoutReturnsWarn() {
        AskHuman slow = summary -> new CompletableFuture<>(); // never completes
        HumanVerifier v = new HumanVerifier("v", "summary", slow, 1);
        VerificationResult r = v.verify("approve this action");
        assertFalse(r.passed());
        assertEquals(Severity.WARN, r.severity());
        assertTrue(r.reason().contains("did not respond"), r.reason());
        assertEquals(true, r.metadata().get("timed_out"));
    }

    @Test
    void askHumanThrowingIsContained() {
        AskHuman broken = summary -> { throw new RuntimeException("UI crashed"); };
        HumanVerifier v = new HumanVerifier("v", "summary", broken, 5);
        VerificationResult r = v.verify("approve this action");
        assertFalse(r.passed());
        assertEquals(Severity.BLOCK, r.severity());
        assertTrue(r.reason().contains("UI crashed"), r.reason());
    }

    @Test
    void askHumanReturningNullFutureIsContained() {
        AskHuman nullFuture = summary -> null;
        HumanVerifier v = new HumanVerifier("v", "summary", nullFuture, 5);
        VerificationResult r = v.verify("approve this action");
        assertFalse(r.passed());
        assertEquals(Severity.BLOCK, r.severity());
    }

    @Test
    void askHumanFutureFailingIsContained() {
        CompletableFuture<Decision> failing = new CompletableFuture<>();
        failing.completeExceptionally(new RuntimeException("network"));
        AskHuman broken = summary -> failing;
        HumanVerifier v = new HumanVerifier("v", "summary", broken, 5);
        VerificationResult r = v.verify("approve this action");
        assertFalse(r.passed());
        assertEquals(Severity.BLOCK, r.severity());
    }

    /* ----------------------- summary builder ----------------------- */

    @Test
    void summaryBuilderIsApplied() {
        HumanVerifier v = new HumanVerifier("v", "Action: delete file", decide(Decision.APPROVE), 5);
        // No way to capture the summary from outside the verifier, but we
        // can at least assert the verifier runs and doesn't blow up.
        VerificationResult r = v.verify("payload");
        assertTrue(r.passed());
    }

    @Test
    void summaryBuilderDefaultsToPreview() {
        // Empty summaryBuilder + null input -> still works, no NPE.
        HumanVerifier v = new HumanVerifier("v", "", decide(Decision.APPROVE), 5);
        VerificationResult r = v.verify(null);
        // The input is null but the verifier doesn't care — it just
        // builds a summary around it.
        assertTrue(r.passed());
    }

    /* ----------------------- accessors ----------------------- */

    @Test
    void isWiredReflectsCallbackPresence() {
        HumanVerifier wired = new HumanVerifier("v", "s", decide(Decision.APPROVE), 5);
        HumanVerifier notWired = new HumanVerifier("v", "s");
        assertTrue(wired.isWired());
        assertFalse(notWired.isWired());
    }

    @Test
    void nameAndTimeoutAccessors() {
        HumanVerifier v = new HumanVerifier("gate1", "s", decide(Decision.APPROVE), 7);
        assertEquals("gate1", v.name());
        assertEquals(7, v.timeoutS());
        assertTrue(v.description().contains("wired"), v.description());
    }
}
