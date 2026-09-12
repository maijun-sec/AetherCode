package org.aethercode.orchestration.verifier;

import org.aethercode.orchestration.verifier.HeuristicVerifier.Check;
import org.aethercode.orchestration.verifier.HeuristicVerifier.MaxLengthCheck;
import org.aethercode.orchestration.verifier.HeuristicVerifier.MinLengthCheck;
import org.aethercode.orchestration.verifier.HeuristicVerifier.NonEmptyCheck;
import org.aethercode.orchestration.verifier.HeuristicVerifier.RepetitionCheck;
import org.aethercode.orchestration.verifier.Verifier.Severity;
import org.aethercode.orchestration.verifier.Verifier.VerificationResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link HeuristicVerifier} and the built-in {@link HeuristicChecker.Check}s.
 */
class HeuristicVerifierTest {

    /* ----------------------- MinLengthCheck ----------------------- */

    @Test
    void minLengthPassesAbove() {
        assertEquals(null, new MinLengthCheck(5).apply("hello world"));
    }

    @Test
    void minLengthPassesAtBoundary() {
        assertEquals(null, new MinLengthCheck(5).apply("hello"));
    }

    @Test
    void minLengthFailsBelow() {
        String r = new MinLengthCheck(5).apply("hi");
        assertEquals("input is 2 chars, expected >= 5", r);
    }

    @Test
    void minLengthRejectsNegative() {
        assertThrows(IllegalArgumentException.class, () -> new MinLengthCheck(-1));
    }

    /* ----------------------- MaxLengthCheck ----------------------- */

    @Test
    void maxLengthPassesAtBoundary() {
        assertEquals(null, new MaxLengthCheck(5).apply("hello"));
    }

    @Test
    void maxLengthFailsAbove() {
        String r = new MaxLengthCheck(5).apply("hello world");
        assertEquals("input is 11 chars, expected <= 5", r);
    }

    @Test
    void maxLengthRejectsNegative() {
        assertThrows(IllegalArgumentException.class, () -> new MaxLengthCheck(-1));
    }

    /* ----------------------- NonEmptyCheck ----------------------- */

    @Test
    void nonEmptyFailsOnEmpty() {
        String r = new NonEmptyCheck().apply("");
        assertEquals("input is empty or whitespace", r);
    }

    @Test
    void nonEmptyFailsOnWhitespace() {
        String r = new NonEmptyCheck().apply("   \n\t  ");
        assertEquals("input is empty or whitespace", r);
    }

    @Test
    void nonEmptyPassesOnContent() {
        assertEquals(null, new NonEmptyCheck().apply("x"));
    }

    /* ----------------------- RepetitionCheck ----------------------- */

    @Test
    void repetitionPassesOnNormalText() {
        assertEquals(null, new RepetitionCheck(50, 3).apply("the quick brown fox jumps over the lazy dog"));
    }

    @Test
    void repetitionFailsOnRepeatedNgram() {
        // Same 4-char chunk repeated 20 times, threshold 5 -> fail
        String repeated = "abcd".repeat(20);
        String r = new RepetitionCheck(4, 5).apply(repeated);
        assertNotNull(r);
        assertTrue(r.contains("repeated"), r);
    }

    @Test
    void repetitionPassesAtBoundary() {
        // 5 repetitions of a 4-char chunk, threshold 5 -> pass
        String input = "xxxx".repeat(5);
        assertEquals(null, new RepetitionCheck(4, 5).apply(input));
    }

    @Test
    void repetitionFailsJustAboveBoundary() {
        // 6 repetitions of a 4-char chunk, threshold 5 -> fail
        String input = "xxxx".repeat(6);
        assertNotNull(new RepetitionCheck(4, 5).apply(input));
    }

    @Test
    void repetitionRejectsTinyN() {
        assertThrows(IllegalArgumentException.class, () -> new RepetitionCheck(2, 5));
    }

    @Test
    void repetitionRejectsTooSmallMax() {
        assertThrows(IllegalArgumentException.class, () -> new RepetitionCheck(50, 1));
    }

    @Test
    void repetitionIgnoresShortInput() {
        // 3-char input < n=50 -> no check
        assertEquals(null, new RepetitionCheck(50, 3).apply("xxx"));
    }

    /* ----------------------- HeuristicVerifier wiring ----------------------- */

    @Test
    void verifyPassesAllChecks() {
        HeuristicVerifier v = new HeuristicVerifier("v", List.of(
                new NonEmptyCheck(),
                new MinLengthCheck(1),
                new MaxLengthCheck(1000)));
        assertTrue(v.verify("hello world").passed());
    }

    @Test
    void verifyFailsOnFirstFailingCheck() {
        HeuristicVerifier v = new HeuristicVerifier("v", List.of(
                new NonEmptyCheck(),
                new MinLengthCheck(100)));
        VerificationResult r = v.verify("hi");
        assertFalse(r.passed());
        assertTrue(r.reason().contains("min_length"), r.reason());
    }

    @Test
    void verifyRejectsNullInput() {
        HeuristicVerifier v = new HeuristicVerifier("v", List.of(new NonEmptyCheck()));
        VerificationResult r = v.verify(null);
        assertFalse(r.passed());
    }

    @Test
    void verifySurfacesFailingCheckInMetadata() {
        HeuristicVerifier v = new HeuristicVerifier("v", List.of(new MinLengthCheck(100)));
        VerificationResult r = v.verify("hi");
        assertEquals("min_length[100]", r.metadata().get("check"));
    }

    @Test
    void customSeverityIsHonored() {
        HeuristicVerifier v = new HeuristicVerifier("v",
                List.of(new MinLengthCheck(100)),
                Severity.WARN);
        VerificationResult r = v.verify("hi");
        assertEquals(Severity.WARN, r.severity());
    }

    @Test
    void nameAndAccessors() {
        HeuristicVerifier v = new HeuristicVerifier("heur", List.of(
                new NonEmptyCheck(), new MinLengthCheck(1)));
        assertEquals("heur", v.name());
        assertEquals(2, v.checks().size());
    }

    @Test
    void constructorRejectsBlankName() {
        assertThrows(IllegalArgumentException.class,
                () -> new HeuristicVerifier("", List.of(new NonEmptyCheck())));
    }

    @Test
    void constructorRejectsEmptyChecks() {
        assertThrows(IllegalArgumentException.class,
                () -> new HeuristicVerifier("v", List.of()));
    }

    /* ----------------------- helper ----------------------- */

}
