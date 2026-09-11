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
