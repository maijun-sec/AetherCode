package org.aethercode.deepagents.roles;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * prior round.2 (O-9): tests for {@link SharedBlackboard}.
 */
class SharedBlackboardTest {

    @Test
    void putThenGetReturnsEntry() {
        SharedBlackboard bb = new SharedBlackboard("test");
        SharedBlackboard.Entry e = bb.put("plan", "1. read file\n2. edit", "planner");
        assertEquals("plan", e.key());
        assertEquals("1. read file\n2. edit", e.value());
        assertEquals("planner", e.role());
        assertEquals(1L, e.seq());
        assertEquals(e, bb.get("plan").orElseThrow());
    }

    @Test
    void putOverwritesPriorValueButPreservesSeq() {
        SharedBlackboard bb = new SharedBlackboard("test");
        bb.put("plan", "v1", "planner");
        SharedBlackboard.Entry second = bb.put("plan", "v2", "coder");
        assertEquals("v2", second.value());
        assertEquals(2L, second.seq());
        assertEquals("v2", bb.get("plan").orElseThrow().value());
    }

    @Test
    void putFromRoleOverloadUsesRoleName() {
        SharedBlackboard bb = new SharedBlackboard();
        Role r = Role.builder("coder", "...").build();
        SharedBlackboard.Entry e = bb.put("diff", "patched", r);
        assertEquals("coder", e.role());
    }

    @Test
    void seqCounterMonotonicallyIncreases() {
        SharedBlackboard bb = new SharedBlackboard();
        for (int i = 0; i < 10; i++) {
            bb.put("k" + i, i, "writer");
        }
        List<SharedBlackboard.Entry> all = bb.snapshot().values().stream()
                .sorted((a, b) -> Long.compare(a.seq(), b.seq()))
                .toList();
        for (int i = 0; i < 10; i++) {
            assertEquals(i + 1, all.get(i).seq());
        }
    }

    @Test
    void sinceReturnsEntriesAboveGivenSeq() {
        SharedBlackboard bb = new SharedBlackboard();
        bb.put("a", 1, "planner");
        bb.put("b", 2, "researcher");
        bb.put("c", 3, "coder");
        List<SharedBlackboard.Entry> since = bb.since(1L);
        assertEquals(2, since.size());
        assertEquals(2L, since.get(0).seq());
        assertEquals(3L, since.get(1).seq());
    }

    @Test
    void requireThrowsForMissing() {
        SharedBlackboard bb = new SharedBlackboard();
        assertThrows(IllegalStateException.class, () -> bb.require("ghost"));
    }

    @Test
    void containsIsExact() {
        SharedBlackboard bb = new SharedBlackboard();
        bb.put("a", 1, "x");
        assertTrue(bb.contains("a"));
        assertFalse(bb.contains("b"));
    }

    @Test
    void sizeAndClear() {
        SharedBlackboard bb = new SharedBlackboard();
        bb.put("a", 1, "x");
        bb.put("b", 2, "x");
        assertEquals(2, bb.size());
        bb.clear();
        assertEquals(0, bb.size());
    }

    @Test
    void listenerSeesWrites() {
        SharedBlackboard bb = new SharedBlackboard();
        AtomicInteger writes = new AtomicInteger(0);
        java.util.concurrent.atomic.AtomicReference<SharedBlackboard.Entry> last =
                new java.util.concurrent.atomic.AtomicReference<>();
        bb.addListener(new SharedBlackboard.SharedBlackboardListener() {
            @Override public void onWrite(SharedBlackboard.Entry written, SharedBlackboard.Entry previous) {
                writes.incrementAndGet();
                last.set(written);
            }
        });
        bb.put("a", 1, "x");
        bb.put("b", 2, "y");
        assertEquals(2, writes.get());
        assertNotNull(last.get());
        assertEquals("b", last.get().key());
    }

    @Test
    void listenerReceivesPreviousOnOverwrite() {
        SharedBlackboard bb = new SharedBlackboard();
        java.util.concurrent.atomic.AtomicReference<SharedBlackboard.Entry> lastPrev =
                new java.util.concurrent.atomic.AtomicReference<>();
        bb.addListener(new SharedBlackboard.SharedBlackboardListener() {
            @Override public void onWrite(SharedBlackboard.Entry written, SharedBlackboard.Entry previous) {
                lastPrev.set(previous);
            }
        });
        SharedBlackboard.Entry first = bb.put("a", 1, "x");
        bb.put("a", 2, "y");
        assertSame(first, lastPrev.get());
    }

    @Test
    void withLockRunsTheAction() {
        SharedBlackboard bb = new SharedBlackboard();
        AtomicInteger ran = new AtomicInteger(0);
        bb.withLock(ran::incrementAndGet);
        assertEquals(1, ran.get());
    }

    @Test
    void entryToMapIsStable() {
        SharedBlackboard.Entry e = new SharedBlackboard.Entry("k", "v", "r", 7L, 1234L);
        Map<String, Object> m = e.toMap();
        assertEquals("k", m.get("key"));
        assertEquals("v", m.get("value"));
        assertEquals("r", m.get("role"));
        assertEquals(7L, m.get("seq"));
        assertEquals(1234L, m.get("atMs"));
    }
}
