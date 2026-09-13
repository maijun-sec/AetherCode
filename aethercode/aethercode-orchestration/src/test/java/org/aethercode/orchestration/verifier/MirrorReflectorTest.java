package org.aethercode.orchestration.verifier;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MirrorReflectorTest {

    @Test
    void emptyOutputTriggersEmptyFeedback() {
        var r = new MirrorReflector().firstPass("");
        assertTrue(r.needsRevision());
        assertEquals(MirrorReflector.Feedback.Kind.EMPTY, r.feedback().get(0).kind());
    }

    @Test
    void cleanOutputHasNoFeedback() {
        var r = new MirrorReflector().firstPass("The answer is 42.");
        assertFalse(r.needsRevision(), "clean output should not need revision; got: " + r.feedback());
    }

    @Test
    void numericMismatchFlagged() {
        var r = new MirrorReflector().firstPass("2+2 = 4. And also 2+2 = 5.");
        assertTrue(r.needsRevision());
        assertTrue(r.feedback().stream()
            .anyMatch(f -> f.kind() == MirrorReflector.Feedback.Kind.NUMERIC_MISMATCH));
    }

    @Test
    void bracketImbalanceMappedToUnparseable() {
        var r = new MirrorReflector().firstPass("Hello { world");
        assertTrue(r.needsRevision());
        assertTrue(r.feedback().stream()
            .anyMatch(f -> f.kind() == MirrorReflector.Feedback.Kind.UNPARSEABLE));
    }

    @Test
    void secondPassMergesExternalFeedback() {
        var first = new MirrorReflector().firstPass("clean output");
        var external = List.of(new MirrorReflector.Feedback(
            MirrorReflector.Feedback.Kind.PEER_GAP, "missing tool call"));
        var merged = new MirrorReflector().reflect(external, "clean output");
        assertFalse(first.needsRevision());
        assertTrue(merged.needsRevision());
        assertEquals(1, merged.feedback().size());
    }
}
