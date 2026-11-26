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

package org.aethercode.protocol.methods;

import org.aethercode.core.stream.StreamEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * R415 (2026-11-26) — the CONSISTENCY VERDICT must survive the wire.
 *
 * <h2>Why this is a wire contract</h2>
 * <p>The engine computes, from the tree, whether a turn changed every copy
 * of every declaration it touched, and puts the verdict on the wire as a
 * {@code side_note} of kind {@code consistency}.
 *
 * <p>★ THE KIND IS THE ROUTING KEY ★ the desktop's side_note handler
 * deliberately drops kinds it does not know (R208: engine control
 * signals must not become chat lines). So a client that receives a
 * side_note WITHOUT its kind cannot tell a verdict from a control signal
 * — and the verdict is dropped.
 *
 * <p>★ AND THE MESSAGE MUST BE THE VERDICT ★ not a summary of it: the
 * report names the file, the line, the value that is still there and the
 * value it should have. A client that shows "inconsistent" without the
 * details has given the user a worry and no work.
 */
class SideNoteWireR415Test {

    @Test
    @DisplayName("R415: a side_note carries its kind and its message")
    void theSideNoteCarriesKindAndMessage() {
        Map<String, Object> frame = AetherCodeMethods.eventToMapPublic(
                new StreamEvent.SideNote("consistency", "[CONSISTENCY CHECK FAILED] ..."));
        assertNotNull(frame, "eventToMapPublic returned null for a side_note");
        assertEquals("side_note", frame.get("type"));
        assertEquals("consistency", frame.get("kind"));
        assertEquals("[CONSISTENCY CHECK FAILED] ...", frame.get("message"));
    }

    @Test
    @DisplayName("R415: the verdict's text reaches the wire unshortened")
    void theVerdictIsNotShortened() {
        String verdict = """
                [CONSISTENCY CHECK FAILED] 1 copy of a changed declaration was NOT updated:
                  - MAX_RETRIES at D:/tmp/proj/src/LegacyConfig.java:7 has the value 3 (it should be 5)
                  Change them in the SAME pass, then say which files you changed.""";
        Map<String, Object> frame = AetherCodeMethods.eventToMapPublic(
                new StreamEvent.SideNote("consistency", verdict));
        assertEquals(verdict, frame.get("message"),
                "the report names the file and the line — a shortened copy is not actionable");
    }

    @Test
    @DisplayName("R415: a kind is never dropped to 'note'")
    void theKindIsNotDefaulted() {
        Map<String, Object> frame = AetherCodeMethods.eventToMapPublic(
                new StreamEvent.SideNote("consistency", "x"));
        assertNotNull(frame.get("kind"), "the routing key must be present");
        assertEquals("consistency", frame.get("kind"));
    }
}
