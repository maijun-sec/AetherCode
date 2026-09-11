package org.aethercode.memory;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class WorkingMemoryBufferTest {

    @Test
    void putAndGetRoundTrip() {
        WorkingMemoryBuffer buf = new WorkingMemoryBuffer("s1", "q1");
        WorkingMemoryBuffer.Entry e = buf.put(WorkingMemoryBuffer.Kind.PLAN_STEP, "investigate pom.xml");
        assertNotNull(e.id());
        Optional<WorkingMemoryBuffer.Entry> got = buf.get(e.id());
        assertTrue(got.isPresent());
        assertEquals("investigate pom.xml", got.get().content());
    }

    @Test
    void getTouchesEntry() {
        WorkingMemoryBuffer buf = new WorkingMemoryBuffer("s1", "q1");
        WorkingMemoryBuffer.Entry e1 = buf.put(WorkingMemoryBuffer.Kind.TEXT, "first");
        WorkingMemoryBuffer.Entry e2 = buf.put(WorkingMemoryBuffer.Kind.TEXT, "second");
        // Touch e1 — should move to end
        buf.get(e1.id());
        var list = buf.list();
        assertEquals(2, list.size());
        assertEquals("second", list.get(0).content());
        assertEquals("first", list.get(1).content());
    }

    @Test
    void listAndClear() {
        WorkingMemoryBuffer buf = new WorkingMemoryBuffer("s1", "q1");
        buf.put(WorkingMemoryBuffer.Kind.TODO, "fix bug");
        buf.put(WorkingMemoryBuffer.Kind.EVIDENCE, "stack trace in test");
        assertEquals(2, buf.size());
        assertEquals(2, buf.list().size());
        buf.clear();
        assertEquals(0, buf.size());
    }

    @Test
    void evictionHappensAtCapacity() {
        WorkingMemoryBuffer buf = new WorkingMemoryBuffer("s1", "q1", 3);
        for (int i = 0; i < 5; i++) {
            buf.put(WorkingMemoryBuffer.Kind.TEXT, "entry-" + i);
        }
        // Should keep at most 3 (newest)
        assertEquals(3, buf.size());
        var list = buf.list();
        assertEquals("entry-2", list.get(0).content());
        assertEquals("entry-3", list.get(1).content());
        assertEquals("entry-4", list.get(2).content());
    }

    @Test
    void renderProducesMarkdown() {
        WorkingMemoryBuffer buf = new WorkingMemoryBuffer("s1", "q1");
        buf.put(WorkingMemoryBuffer.Kind.PLAN_STEP, "step one");
        buf.put(WorkingMemoryBuffer.Kind.EVIDENCE, "proof: 42");
        String s = buf.render();
        assertTrue(s.contains("Working memory"));
        assertTrue(s.contains("[plan_step] step one"));
        assertTrue(s.contains("[evidence] proof: 42"));
    }

    @Test
    void snapshotAndRestore() {
        WorkingMemoryBuffer src = new WorkingMemoryBuffer("s1", "q1");
        src.put(WorkingMemoryBuffer.Kind.TEXT, "a");
        src.put(WorkingMemoryBuffer.Kind.TEXT, "b");
        var snap = src.snapshot();
        WorkingMemoryBuffer dst = new WorkingMemoryBuffer("s1", "q1");
        dst.restore(snap);
        assertEquals(2, dst.size());
        assertEquals("a", dst.list().get(0).content());
    }

    @Test
    void renderEmptyBufferIsEmptyString() {
        WorkingMemoryBuffer buf = new WorkingMemoryBuffer("s1", "q1");
        assertEquals("", buf.render());
    }

    @Test
    void putWithMetaStoresMeta() {
        WorkingMemoryBuffer buf = new WorkingMemoryBuffer("s1", "q1");
        var e = buf.put(WorkingMemoryBuffer.Kind.REFERENCE, "see memory id xyz",
                Map.of("memoryId", "abc-123", "scope", "user"));
        assertEquals("abc-123", e.meta().get("memoryId"));
        assertEquals("user", e.meta().get("scope"));
    }
}
