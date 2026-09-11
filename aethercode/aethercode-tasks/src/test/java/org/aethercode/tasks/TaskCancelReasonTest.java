package org.aethercode.tasks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * tests for {@link TaskCancelReason} label rendering.
 */
class TaskCancelReasonTest {

    @Test
    void label_isHumanReadable() {
        assertEquals("by user", TaskCancelReason.USER_REQUEST.label());
        assertEquals("deadline", TaskCancelReason.DEADLINE_EXCEEDED.label());
        assertEquals("parent killed", TaskCancelReason.PARENT_KILLED.label());
        assertEquals("plan aborted", TaskCancelReason.PLAN_ABORTED.label());
        assertEquals("other", TaskCancelReason.OTHER.label());
    }

    @Test
    void allEnumValuesHaveLabel() {
        for (TaskCancelReason r : TaskCancelReason.values()) {
            assertNotNull(r.label());
            assertFalse(r.label().isBlank(), "label for " + r + " must not be blank");
        }
    }
}
