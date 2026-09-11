package org.aethercode.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * tests for the SQLite-backed {@link SessionMemoryStore}.
 *
 * <p>Covers the three groups: schema bootstrap, session_info CRUD,
 * and session_memory (key/value) CRUD. We use a fresh {@code @TempDir}
 * per test so SQLite files don't leak between runs.
 *
 * <p>Note: {@link SessionMemoryStore} is not {@link AutoCloseable}
 * in the public API (the connection stays open for the daemon's
 * lifetime), so we use try/finally for explicit close.
 */
class SessionMemoryStoreR127Test {

    @Test
    void constructor_createsDbFileAndParentDir(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("nested").resolve("dir").resolve("sessions.db");
        SessionMemoryStore s = new SessionMemoryStore(db);
        try {
            assertTrue(Files.exists(db), "db file should be created");
            assertTrue(Files.size(db) > 0, "db file should not be empty");
        } finally { s.close(); }
    }

    @Test
    void upsertSession_firstCallInsertsRow(@TempDir Path tmp) {
        Path db = tmp.resolve("sessions.db");
        SessionMemoryStore s = new SessionMemoryStore(db);
        try {
            SessionMemoryStore.SessionInfo info = s.upsertSession("sess-1", "/work/proj", "implement RAG");
            assertEquals("sess-1", info.sessionId());
            assertEquals("/work/proj", info.cwd());
            assertEquals("implement RAG", info.firstPrompt());
            assertTrue(info.createdAtMs() > 0);
            assertTrue(info.lastUsedAtMs() >= info.createdAtMs());
        } finally { s.close(); }
    }

    @Test
    void upsertSession_secondCallPreservesFirstPrompt(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("sessions.db");
        SessionMemoryStore s = new SessionMemoryStore(db);
        try {
            s.upsertSession("sess-1", "/work/proj", "implement RAG");
            Thread.sleep(20);
            SessionMemoryStore.SessionInfo info2 = s.upsertSession("sess-1", "/work/proj", null);
            assertEquals("implement RAG", info2.firstPrompt(),
                    "firstPrompt must be preserved across upserts");
            assertTrue(info2.lastUsedAtMs() > 0);
        } finally { s.close(); }
    }

    @Test
    void updateCwd_movesSessionToNewCwd(@TempDir Path tmp) {
        Path db = tmp.resolve("sessions.db");
        SessionMemoryStore s = new SessionMemoryStore(db);
        try {
            s.upsertSession("sess-1", "/old/cwd", "first prompt");
            s.updateCwd("sess-1", "/new/cwd");
            SessionMemoryStore.SessionInfo loaded = s.loadSession("sess-1").orElseThrow();
            assertEquals("/new/cwd", loaded.cwd());
            assertEquals("first prompt", loaded.firstPrompt());
        } finally { s.close(); }
    }

    @Test
    void listSessions_ordersByLastUsedDesc(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("sessions.db");
        SessionMemoryStore s = new SessionMemoryStore(db);
        try {
            s.upsertSession("a", "/a", null);
            Thread.sleep(20);
            s.upsertSession("b", "/b", null);
            Thread.sleep(20);
            s.upsertSession("c", "/c", null);
            Thread.sleep(20);
            s.upsertSession("a", "/a", null);
            List<SessionMemoryStore.SessionInfo> list = s.listSessions(10);
            assertEquals(3, list.size());
            assertEquals("a", list.get(0).sessionId(), "a was touched last");
            assertEquals("c", list.get(1).sessionId());
            assertEquals("b", list.get(2).sessionId());
        } finally { s.close(); }
    }

    @Test
    void deleteSession_removesSessionAndCascadesToMemory(@TempDir Path tmp) {
        Path db = tmp.resolve("sessions.db");
        SessionMemoryStore s = new SessionMemoryStore(db);
        try {
            s.upsertSession("sess-1", "/a", "p");
            s.putMemory("sess-1", "k1", "v1");
            s.putMemory("sess-1", "k2", "v2");
            assertTrue(s.deleteSession("sess-1"));
            assertTrue(s.loadSession("sess-1").isEmpty());
            assertTrue(s.listMemory("sess-1").isEmpty(),
                    "cascade delete should remove session_memory rows");
        } finally { s.close(); }
    }

    @Test
    void putMemory_thenGetMemory_roundTripsValue(@TempDir Path tmp) {
        Path db = tmp.resolve("sessions.db");
        SessionMemoryStore s = new SessionMemoryStore(db);
        try {
            s.putMemory("sess-A", "user_pref", "dark mode");
            Optional<SessionMemoryStore.MemoryEntry> got = s.getMemory("sess-A", "user_pref");
            assertTrue(got.isPresent());
            assertEquals("dark mode", got.get().value());
            assertTrue(got.get().createdAtMs() > 0);
            assertEquals(got.get().createdAtMs(), got.get().updatedAtMs());
        } finally { s.close(); }
    }

    @Test
    void putMemory_overwritesValueAndBumpsUpdatedAt(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("sessions.db");
        SessionMemoryStore s = new SessionMemoryStore(db);
        try {
            s.putMemory("sess-A", "k", "v1");
            long t1 = s.getMemory("sess-A", "k").orElseThrow().updatedAtMs();
            Thread.sleep(20);
            s.putMemory("sess-A", "k", "v2");
            SessionMemoryStore.MemoryEntry entry = s.getMemory("sess-A", "k").orElseThrow();
            assertEquals("v2", entry.value());
            assertTrue(entry.updatedAtMs() > t1,
                    "updatedAtMs should advance on overwrite");
        } finally { s.close(); }
    }

    @Test
    void listMemory_returnsSortedByKey(@TempDir Path tmp) {
        Path db = tmp.resolve("sessions.db");
        SessionMemoryStore s = new SessionMemoryStore(db);
        try {
            s.putMemory("sess-A", "zebra", "z");
            s.putMemory("sess-A", "alpha", "a");
            s.putMemory("sess-A", "mike", "m");
            List<SessionMemoryStore.MemoryEntry> list = s.listMemory("sess-A");
            assertEquals(3, list.size());
            assertEquals("alpha", list.get(0).key());
            assertEquals("mike", list.get(1).key());
            assertEquals("zebra", list.get(2).key());
        } finally { s.close(); }
    }

    @Test
    void listMemory_isolatesAcrossSessions(@TempDir Path tmp) {
        Path db = tmp.resolve("sessions.db");
        SessionMemoryStore s = new SessionMemoryStore(db);
        try {
            s.putMemory("sess-A", "shared", "alpha");
            s.putMemory("sess-B", "shared", "beta");
            assertEquals("alpha", s.getMemory("sess-A", "shared").orElseThrow().value());
            assertEquals("beta", s.getMemory("sess-B", "shared").orElseThrow().value());
            assertEquals(1, s.listMemory("sess-A").size());
            assertEquals(1, s.listMemory("sess-B").size());
        } finally { s.close(); }
    }

    @Test
    void deleteMemory_removesSingleKeyOnly(@TempDir Path tmp) {
        Path db = tmp.resolve("sessions.db");
        SessionMemoryStore s = new SessionMemoryStore(db);
        try {
            s.putMemory("sess-A", "k1", "v1");
            s.putMemory("sess-A", "k2", "v2");
            assertTrue(s.deleteMemory("sess-A", "k1"));
            assertFalse(s.getMemory("sess-A", "k1").isPresent());
            assertTrue(s.getMemory("sess-A", "k2").isPresent());
        } finally { s.close(); }
    }

    @Test
    void rowCount_agreesWithActualRows(@TempDir Path tmp) {
        Path db = tmp.resolve("sessions.db");
        SessionMemoryStore s = new SessionMemoryStore(db);
        try {
            s.upsertSession("s1", "/a", "p");
            s.upsertSession("s2", "/b", "q");
            s.putMemory("s1", "k", "v");
            assertEquals(2, s.rowCount("session_info"));
            assertEquals(1, s.rowCount("session_memory"));
        } finally { s.close(); }
    }

    @Test
    void dbFile_accessorReturnsConfiguredPath(@TempDir Path tmp) {
        Path db = tmp.resolve("sessions.db");
        SessionMemoryStore s = new SessionMemoryStore(db);
        try {
            assertEquals(db, s.dbFile());
        } finally { s.close(); }
    }

    @Test
    void schema_versionConstantIsStable() {
        // Bumped on structural change. R127 = 1.
        assertEquals(1, SessionMemoryStore.SCHEMA_VERSION);
    }

    @Test
    void reopensExistingDb_doesNotLoseData(@TempDir Path tmp) {
        Path db = tmp.resolve("sessions.db");
        SessionMemoryStore s1 = new SessionMemoryStore(db);
        s1.upsertSession("sess-X", "/work", "prompt 1");
        s1.putMemory("sess-X", "k", "v");
        s1.close();
        SessionMemoryStore s2 = new SessionMemoryStore(db);
        try {
            SessionMemoryStore.SessionInfo loaded = s2.loadSession("sess-X").orElseThrow();
            assertEquals("prompt 1", loaded.firstPrompt());
            assertEquals("v", s2.getMemory("sess-X", "k").orElseThrow().value());
        } finally { s2.close(); }
    }
}
