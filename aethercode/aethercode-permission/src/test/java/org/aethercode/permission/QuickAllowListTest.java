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
