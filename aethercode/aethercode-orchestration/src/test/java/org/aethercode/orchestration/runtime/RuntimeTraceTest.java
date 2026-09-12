package org.aethercode.orchestration.runtime;

import org.aethercode.orchestration.runtime.RuntimeTrace.Entry;
import org.aethercode.orchestration.selfcorrect.RetryStrategy;
import org.aethercode.orchestration.selfcorrect.SelfCorrectionLoop;
import org.aethercode.orchestration.verifier.Verifier;
import org.aethercode.orchestration.verifier.Verifier.VerificationResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link RuntimeTrace}.
 */
class RuntimeTraceTest {

    private static VerificationResult pass() {
        return VerificationResult.pass("ok");
    }

    private static VerificationResult fail() {
        return VerificationResult.fail(
                Verifier.Severity.BLOCK, "no");
    }

    private static Verifier<String> failsN(int n) {
        return new Verifier<>() {
            int calls = 0;
            @Override public String name() { return "v"; }
            @Override public VerificationResult verify(String input) {
                calls++;
                return calls >= n ? pass() : fail();
            }
        };
    }

    /* ----------------------- add ----------------------- */

    @Test
    void addAppendsEntries() {
        RuntimeTrace t = new RuntimeTrace();
        t.add(new Entry("phase1", "x", pass()));
        t.add(new Entry("phase2", "y", fail()));
        assertEquals(2, t.size());
        assertEquals("phase1", t.entries().get(0).phase());
        assertEquals("phase2", t.entries().get(1).phase());
    }

    @Test
    void addRejectsBadArgs() {
        RuntimeTrace t = new RuntimeTrace();
        assertThrows(IllegalArgumentException.class,
                () -> t.add(new Entry(null, "x", pass())));
        assertThrows(IllegalArgumentException.class,
                () -> t.add(new Entry("", "x", pass())));
        assertThrows(IllegalArgumentException.class,
                () -> t.add(new Entry("p", null, pass())));
        assertThrows(IllegalArgumentException.class,
                () -> t.add(new Entry("p", "x", null)));
    }

    /* ----------------------- absorb ----------------------- */

    @Test
    void absorbFromSelfCorrectLoopAddsOneEntryPerAttempt() {
        Verifier<String> v = failsN(2);
        SelfCorrectionLoop<String> sc = new SelfCorrectionLoop<>(
                "sc", v, RetryStrategy.transform(s -> s + "-fixed"), 5);

        RuntimeTrace t = new RuntimeTrace();
        t.absorb(sc.run("y"));
        // y fails attempt 1, then y-fixed passes attempt 2.
        assertEquals(2, t.size());
        assertEquals("self-correct", t.entries().get(0).phase());
        assertEquals("y", t.entries().get(0).action());
        assertEquals("y-fixed", t.entries().get(1).action());
        assertTrue(t.entries().get(1).result().passed());
    }

    @Test
    void absorbFromNullLoopIsNoop() {
        RuntimeTrace t = new RuntimeTrace();
        t.absorb(null);
        assertEquals(0, t.size());
    }

    /* ----------------------- last / size / entries ----------------------- */

    @Test
    void lastReturnsLastEntry() {
        RuntimeTrace t = new RuntimeTrace();
        assertNull(t.last());
        Entry e = new Entry("p", "x", pass());
        t.add(e);
        assertSame(e, t.last());
    }

    @Test
    void entriesIsImmutable() {
        RuntimeTrace t = new RuntimeTrace();
        t.add(new Entry("p", "x", pass()));
        List<Entry> entries = t.entries();
        assertThrows(UnsupportedOperationException.class,
                () -> entries.add(new Entry("p", "y", pass())));
    }

    /* ----------------------- entry record ----------------------- */

    @Test
    void entryRecordHoldsAllThreeFields() {
        VerificationResult r = pass();
        Entry e = new Entry("verify", "x", r);
        assertEquals("verify", e.phase());
        assertEquals("x", e.action());
        assertSame(r, e.result());
    }
}
