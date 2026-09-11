package org.aethercode.core.log;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogContextTest {

    @AfterEach
    void clear() { LogContext.clear(); }

    @Test
    void snapshot_emptyByDefault() {
        assertTrue(LogContext.snapshot().isEmpty());
    }

    @Test
    void with_setsValue() {
        try (var s = LogContext.with("session", "abc")) {
            assertEquals("abc", LogContext.snapshot().get("session"));
        }
    }

    @Test
    void with_replacesExisting() {
        LogContext.with("k", "v1");
        try (var s = LogContext.with("k", "v2")) {
            assertEquals("v2", LogContext.snapshot().get("k"));
        }
        // Scope closed, previous value restored
        assertEquals("v1", LogContext.snapshot().get("k"));
    }

    @Test
    void with_closesRemovesValue() {
        try (var s = LogContext.with("k", "v")) {
            assertEquals("v", LogContext.snapshot().get("k"));
        }
        assertNull(LogContext.snapshot().get("k"));
    }

    @Test
    void with_multipleKeys() {
        try (var s1 = LogContext.with("a", "1");
             var s2 = LogContext.with("b", "2")) {
            assertEquals("1", LogContext.snapshot().get("a"));
            assertEquals("2", LogContext.snapshot().get("b"));
        }
        assertNull(LogContext.snapshot().get("a"));
        assertNull(LogContext.snapshot().get("b"));
    }

    @Test
    void with_nestedScopes() {
        try (var s1 = LogContext.with("k", "outer")) {
            assertEquals("outer", LogContext.snapshot().get("k"));
            try (var s2 = LogContext.with("k", "inner")) {
                assertEquals("inner", LogContext.snapshot().get("k"));
            }
            assertEquals("outer", LogContext.snapshot().get("k"));
        }
    }

    @Test
    void with_rejectsNullKey() {
        assertThrows(NullPointerException.class, () -> LogContext.with(null, "v"));
    }

    @Test
    void clear_removesAll() {
        LogContext.with("a", "1");
        LogContext.with("b", "2");
        LogContext.clear();
        assertTrue(LogContext.snapshot().isEmpty());
    }

    @Test
    void snapshot_isImmutable() {
        LogContext.with("a", "1");
        Map<String, Object> snap = LogContext.snapshot();
        assertThrows(UnsupportedOperationException.class, () -> snap.put("b", "2"));
    }

    @Test
    void scope_isIdempotent() {
        var s = LogContext.with("k", "v");
        s.close();
        s.close(); // second close is no-op
        assertNull(LogContext.snapshot().get("k"));
    }

    @Test
    void threadIsolation() throws Exception {
        LogContext.with("main", "v");
        AtomicReference<String> other = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            assertTrue(LogContext.snapshot().isEmpty());
            try (var s = LogContext.with("other", "x")) {
                other.set(LogContext.snapshot().get("other").toString());
            }
            latch.countDown();
        });
        t.start();
        latch.await();
        // Main thread's value still intact
        assertEquals("v", LogContext.snapshot().get("main"));
        assertEquals("x", other.get());
    }

    @Test
    void with_valueCanBeAnyObject() {
        try (var s = LogContext.with("count", 42)) {
            assertEquals(42, LogContext.snapshot().get("count"));
        }
        try (var s = LogContext.with("flag", true)) {
            assertEquals(true, LogContext.snapshot().get("flag"));
        }
    }

    @Test
    void activeScopeCount_tracksOpensAndCloses() {
        long before = LogContext.activeScopeCount();
        try (var s1 = LogContext.with("a", "1")) {
            assertEquals(before + 1, LogContext.activeScopeCount());
            try (var s2 = LogContext.with("b", "2")) {
                assertEquals(before + 2, LogContext.activeScopeCount());
            }
            assertEquals(before + 1, LogContext.activeScopeCount());
        }
        assertEquals(before, LogContext.activeScopeCount());
    }

    @Test
    void with_rejectsNullValue() {
        assertThrows(NullPointerException.class, () -> LogContext.with("k", null));
    }
}
