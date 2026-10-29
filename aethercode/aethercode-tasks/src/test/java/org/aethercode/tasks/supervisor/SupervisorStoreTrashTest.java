/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
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
