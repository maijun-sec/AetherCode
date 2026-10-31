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

package org.aethercode.tasks.supervisor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupervisorStoreTrashTest {

    @Test
    void setTrashedAtPersistsTimestamp() throws Exception {
        try (SupervisorStore store = SupervisorStore.inMemory()) {
            store.migrate();
            String id = store.createChild("/tmp", "x", null, null);
            long now = System.currentTimeMillis();
            store.setTrashedAt(id, now);
            ChildRecord r = store.getChild(id).orElseThrow();
            assertTrue(r.isTrashed(), "row should be trashed after setTrashedAt");
            assertEquals(now, r.trashedAtMs());

            // Restore clears the column.
            store.setTrashedAt(id, null);
            ChildRecord r2 = store.getChild(id).orElseThrow();
            assertEquals(false, r2.isTrashed());
        }
    }
}
