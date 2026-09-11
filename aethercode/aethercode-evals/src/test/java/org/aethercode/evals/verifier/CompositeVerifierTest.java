package org.aethercode.evals.verifier;

import org.aethercode.evals.verifier.Verifier.Severity;
import org.aethercode.evals.verifier.Verifier.VerificationResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link CompositeVerifier}.
 */
class CompositeVerifierTest {

    private static Verifier<String> alwaysPass(String name) {
        return new Verifier<>() {
            @Override public String name() { return name; }
            @Override public VerificationResult verify(String s) {
                return VerificationResult.pass("always pass: " + s,
                        Map.of("verifier", name));
            }
        };
    }

    private static Verifier<String> alwaysFail(String name, Severity sev) {
        return new Verifier<>() {
            @Override public String name() { return name; }
            @Override public VerificationResult verify(String s) {
                return VerificationResult.fail(sev, "always fail: " + s,
                        Map.of("verifier", name));
            }
        };
    }

    private static Verifier<String> throwing(String name) {
        return new Verifier<>() {
            @Override public String name() { return name; }
            @Override public VerificationResult verify(String s) {
                throw new RuntimeException("boom from " + name);
            }
        };
    }

    /* ----------------------- constructor validation ----------------------- */

    @Test
    void constructorRejectsBlankName() {
        assertThrows(IllegalArgumentException.class,
                () -> new CompositeVerifier<>("", List.of(alwaysPass("a")), CompositeVerifier.Policy.ALL_MUST_PASS));
    }

    @Test
    void constructorRejectsEmptyVerifiers() {
        assertThrows(IllegalArgumentException.class,
                () -> new CompositeVerifier<>("c", List.of(), CompositeVerifier.Policy.ALL_MUST_PASS));
    }

    @Test
    void constructorRejectsNullPolicy() {
        assertThrows(IllegalArgumentException.class,
                () -> new CompositeVerifier<>("c", List.of(alwaysPass("a")), null));
    }

    @Test
    void minThresholdRejectsOutOfRange() {
        assertThrows(IllegalArgumentException.class,
                () -> new CompositeVerifier<>("c", "d",
                        List.of(alwaysPass("a"), alwaysPass("b")),
                        CompositeVerifier.Policy.MIN_THRESHOLD, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new CompositeVerifier<>("c", "d",
                        List.of(alwaysPass("a"), alwaysPass("b")),
                        CompositeVerifier.Policy.MIN_THRESHOLD, 3));
    }

    /* ----------------------- ALL_MUST_PASS ----------------------- */

    @Test
    void allMustPassPassesWhenAllPass() {
        CompositeVerifier<String> c = CompositeVerifier.allMustPass("c",
                List.of(alwaysPass("a"), alwaysPass("b")));
        VerificationResult r = c.verify("hello");
        assertTrue(r.passed());
        assertEquals(2, r.metadata().get("passed"));
    }

    @Test
    void allMustPassFailsWhenAnyFails() {
        CompositeVerifier<String> c = CompositeVerifier.allMustPass("c",
                List.of(alwaysPass("a"), alwaysFail("b", Severity.BLOCK)));
        VerificationResult r = c.verify("x");
        assertFalse(r.passed());
        assertEquals(Severity.BLOCK, r.severity());
    }

    @Test
    void allMustPassSurfacesHighestSeverityFailure() {
        // Two failures: one WARN, one BLOCK. The composite should surface BLOCK.
        CompositeVerifier<String> c = CompositeVerifier.allMustPass("c",
                List.of(alwaysFail("w", Severity.WARN), alwaysFail("b", Severity.BLOCK)));
        VerificationResult r = c.verify("x");
        assertFalse(r.passed());
        assertEquals(Severity.BLOCK, r.severity());
    }

    @Test
    void allMustPassSurfacesFailingVerifierInMetadata() {
        CompositeVerifier<String> c = CompositeVerifier.allMustPass("c",
                List.of(alwaysPass("a"), alwaysFail("b", Severity.BLOCK)));
        VerificationResult r = c.verify("x");
        assertEquals("b", r.metadata().get("failed_verifier"));
    }

    /* ----------------------- ANY_MUST_PASS ----------------------- */

    @Test
    void anyMustPassPassesWhenOnePasses() {
        CompositeVerifier<String> c = CompositeVerifier.anyMustPass("c",
                List.of(alwaysFail("a", Severity.BLOCK), alwaysPass("b")));
        VerificationResult r = c.verify("x");
        assertTrue(r.passed());
    }

    @Test
    void anyMustPassFailsWhenAllFail() {
        CompositeVerifier<String> c = CompositeVerifier.anyMustPass("c",
                List.of(alwaysFail("a", Severity.BLOCK), alwaysFail("b", Severity.BLOCK)));
        VerificationResult r = c.verify("x");
        assertFalse(r.passed());
        assertEquals(Severity.BLOCK, r.severity());
    }

    /* ----------------------- MIN_THRESHOLD ----------------------- */

    @Test
    void minThresholdPassesAtThreshold() {
        CompositeVerifier<String> c = CompositeVerifier.minThreshold("c",
                List.of(alwaysPass("a"), alwaysPass("b"), alwaysFail("c", Severity.BLOCK)), 2);
        VerificationResult r = c.verify("x");
        assertTrue(r.passed());
    }

    @Test
    void minThresholdPassesAboveThreshold() {
        CompositeVerifier<String> c = CompositeVerifier.minThreshold("c",
                List.of(alwaysPass("a"), alwaysPass("b"), alwaysPass("c")), 2);
        VerificationResult r = c.verify("x");
        assertTrue(r.passed());
    }

    @Test
    void minThresholdFailsBelowThreshold() {
        CompositeVerifier<String> c = CompositeVerifier.minThreshold("c",
                List.of(alwaysPass("a"), alwaysFail("b", Severity.BLOCK), alwaysFail("c", Severity.BLOCK)), 2);
        VerificationResult r = c.verify("x");
        assertFalse(r.passed());
        assertEquals(Severity.BLOCK, r.severity());
    }

    /* ----------------------- error containment ----------------------- */

    @Test
    void verifierCrashIsContained() {
        // A verifier that throws must not crash the composite; the crash
        // is reported as a BLOCK failure with the exception type in metadata.
        CompositeVerifier<String> c = CompositeVerifier.allMustPass("c",
                List.of(alwaysPass("a"), throwing("crash"), alwaysPass("b")));
        VerificationResult r = c.verify("x");
        assertFalse(r.passed());
        assertEquals(Severity.BLOCK, r.severity());
        assertTrue(r.reason().contains("crash"), r.reason());
    }

    @Test
    void verifierCrashIsContainedInAnyMustPass() {
        CompositeVerifier<String> c = CompositeVerifier.anyMustPass("c",
                List.of(throwing("crash"), alwaysPass("a")));
        VerificationResult r = c.verify("x");
        assertTrue(r.passed());
    }

    @Test
    void allThreeVerifiersCrashInMinThreshold() {
        CompositeVerifier<String> c = CompositeVerifier.minThreshold("c",
                List.of(throwing("a"), throwing("b")), 1);
        VerificationResult r = c.verify("x");
        assertFalse(r.passed());
        assertEquals(0, r.metadata().get("passed"));
    }

    /* ----------------------- per-verifier attribution ----------------------- */

    @Test
    void compositeResultExposesPerVerifierOutcomes() {
        CompositeVerifier<String> c = CompositeVerifier.allMustPass("c",
                List.of(alwaysPass("a"), alwaysFail("b", Severity.BLOCK)));
        VerificationResult r = c.verify("x");
        Object results = r.metadata().get("verifier_results");
        assertNotNull(results);
        assertTrue(results instanceof List<?>);
        assertEquals(2, ((List<?>) results).size());
    }

    @Test
    void compositeInputIsPassedToEachSubVerifier() {
        AtomicInteger calls = new AtomicInteger();
        Verifier<String> counter = new Verifier<>() {
            @Override public String name() { return "counter"; }
            @Override public VerificationResult verify(String s) {
                calls.incrementAndGet();
                return VerificationResult.pass("counted: " + s,
                        Map.of("verifier", "counter"));
            }
        };
        CompositeVerifier<String> c = CompositeVerifier.allMustPass("c",
                List.of(counter, counter, counter));
        c.verify("hello");
        assertEquals(3, calls.get(), "all sub-verifiers must see the same input");
    }

    /* ----------------------- accessors ----------------------- */

    @Test
    void nameAndAccessors() {
        CompositeVerifier<String> c = CompositeVerifier.minThreshold("c",
                List.of(alwaysPass("a"), alwaysPass("b")),
                1);
        assertEquals("c", c.name());
        // description is empty when built via the static factory
        assertEquals("", c.description());
        assertEquals(CompositeVerifier.Policy.MIN_THRESHOLD, c.policy());
        assertEquals(1, c.minThreshold());
        assertEquals(2, c.verifiers().size());
    }
}
