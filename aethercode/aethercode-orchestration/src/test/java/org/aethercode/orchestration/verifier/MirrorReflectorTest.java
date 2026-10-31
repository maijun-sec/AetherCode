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
