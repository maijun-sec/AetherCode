package org.aethercode.permission;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * tests for {@link QuickAllowList}.
 */
class QuickAllowListTest {

    @Test
    void addAndCheck() {
        QuickAllowList q = new QuickAllowList();
        assertTrue(q.add("bash"));
        assertTrue(q.isAllowed("bash"));
        assertFalse(q.isAllowed("edit"));
    }

    @Test
    void addRespectsMaxSize() {
        QuickAllowList q = new QuickAllowList(2);
        assertTrue(q.add("a"));
        assertTrue(q.add("b"));
        assertFalse(q.add("c"), "should reject when full and not present");
        assertTrue(q.add("a"), "should allow re-adding existing entry");
        assertEquals(2, q.size());
    }

    @Test
    void addRejectsBlankAndNull() {
        QuickAllowList q = new QuickAllowList();
        assertFalse(q.add(null));
        assertFalse(q.add(""));
        assertFalse(q.add("   "));
        assertEquals(0, q.size());
    }

    @Test
    void removeReturnsTrueWhenPresent() {
        QuickAllowList q = new QuickAllowList();
        q.add("x");
        assertTrue(q.remove("x"));
        assertFalse(q.remove("x"));
        assertFalse(q.isAllowed("x"));
    }

    @Test
    void clearEmptiesList() {
        QuickAllowList q = new QuickAllowList();
        q.add("a");
        q.add("b");
        q.clear();
        assertEquals(0, q.size());
    }

    @Test
    void snapshotIsIndependentCopy() {
        QuickAllowList q = new QuickAllowList();
        q.add("a");
        var snap = q.snapshot();
        q.add("b");
        assertEquals(1, snap.size(), "snapshot should not be affected by later adds");
    }
}
