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

package org.aethercode.core.proc;

import java.util.Map;
import org.aethercode.core.proc.ProcessPriority.Level;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessPriorityTest {

    @Test
    void current_returnsNormal() {
        assertEquals(Level.NORMAL, ProcessPriority.current());
    }

    @Test
    void level_niceValues() {
        assertEquals(-10, Level.LOW.niceValue());
        assertEquals(0, Level.NORMAL.niceValue());
        assertTrue(Level.HIGH.niceValue() > Level.NORMAL.niceValue());
        assertTrue(Level.REALTIME.niceValue() > Level.HIGH.niceValue());
    }

    @Test
    void request_returnsBoolean() {
        // The stub always returns false; we just verify the contract.
        assertFalse(ProcessPriority.request(Level.HIGH));
        assertFalse(ProcessPriority.request(null));
    }

    @Test
    void request_nullIsRejected() {
        assertFalse(ProcessPriority.request(null));
    }

    @Test
    void describe_containsAllFields() {
        Map<String, Object> d = ProcessPriority.describe();
        assertNotNull(d.get("level"));
        assertNotNull(d.get("nice"));
        assertNotNull(d.get("canSet"));
    }

    @Test
    void describe_levelIsCurrent() {
        Map<String, Object> d = ProcessPriority.describe();
        assertEquals(ProcessPriority.current().name(), d.get("level"));
    }

    @Test
    void canSetPriority_returnsFalse() {
        // Without root, can't set
        assertFalse(ProcessPriority.canSetPriority());
    }

    @Test
    void clamp_preservesNonRealtime() {
        assertEquals(Level.LOW, ProcessPriority.clamp(Level.LOW));
        assertEquals(Level.NORMAL, ProcessPriority.clamp(Level.NORMAL));
        assertEquals(Level.HIGH, ProcessPriority.clamp(Level.HIGH));
    }

    @Test
    void clamp_demotesRealtimeWhenCannotSet() {
        assertEquals(Level.HIGH, ProcessPriority.clamp(Level.REALTIME));
    }

    @Test
    void clamp_nullBecomesNormal() {
        assertEquals(Level.NORMAL, ProcessPriority.clamp(null));
    }

    @Test
    void allLevels_haveUniqueNiceValues() {
        var seen = new java.util.HashSet<Integer>();
        for (Level l : Level.values()) {
            assertTrue(seen.add(l.niceValue()), "duplicate nice value: " + l);
        }
    }
}
