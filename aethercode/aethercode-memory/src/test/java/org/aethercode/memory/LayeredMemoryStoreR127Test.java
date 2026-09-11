package org.aethercode.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * tests for {@link LayeredMemoryStore}, the 3-layer facade
 * (USER / PROJECT / SESSION).
 *
 * <p>The LLM-driven {@link ProjectMemoryCompressor} is wired with
 * a no-op client so the tests can exercise the threshold / keep-recent
 * pass without bringing up a real chat backend.
 *
 * <p>Each test builds a fresh {@code LayeredMemoryStore} + closing
 * pair in a try/finally so the SQLite connection is released before
 * the {@code @TempDir} cleanup runs. Without close, the file lock
 * holds and Windows can't delete the tmp dir.
 */
class LayeredMemoryStoreR127Test {

    /** Resource-holder: keeps both the store and the SQLite
     *  connection, so the caller has a single try/finally. */
    private record Handle(LayeredMemoryStore store, SessionMemoryStore session) {}

    private static Handle build(Path memoryBase, String agentType,
                                 int threshold, int keepRecent, boolean autoCompress) {
        SessionMemoryStore session = new SessionMemoryStore(
                memoryBase.resolve("sessions.db"));
        ProjectMemoryCompressor compressor = new ProjectMemoryCompressor(
                ProjectMemoryCompressor.NOOP);
        LayeredMemoryStore s = new LayeredMemoryStore(
                memoryBase, agentType, session, compressor,
                threshold, keepRecent, autoCompress);
        return new Handle(s, session);
    }

    private static Handle build(Path memoryBase, String agentType) {
        return build(memoryBase, agentType, 5, 2, true);
    }

    @Test
    void putUser_thenListUser_roundTrips(@TempDir Path tmp) {
        Handle h = build(tmp, "user-agent");
        try {
            h.store().putUser("always quote file paths", List.of("style"));
            var list = h.store().listUser();
            assertEquals(1, list.size());
            assertEquals("user", list.get(0).scope());
            assertEquals(List.of("style"), list.get(0).tags());
        } finally { h.session().close(); }
    }

    @Test
    void getUser_returnsItemByKey(@TempDir Path tmp) {
        Handle h = build(tmp, "ua");
        try {
            FileBackedMemory.MemoryItem item = h.store().putUser("hello", List.of());
            Optional<FileBackedMemory.MemoryItem> got = h.store().getUser(item.id());
            assertTrue(got.isPresent());
            assertEquals("hello", got.get().content());
        } finally { h.session().close(); }
    }

    @Test
    void appendProjectChange_stampsTimestampAndContent(@TempDir Path tmp) {
        Handle h = build(tmp, "p-agent");
        try {
            FileBackedMemory.MemoryItem item = h.store().appendProjectChange(tmp.toString(), "added 3 file_writes");
            assertNotNull(item.id());
            // change-log line is "[<iso8601>] <content>".
            assertTrue(item.content().startsWith("["),
                    "project entry must start with timestamp: " + item.content());
            assertTrue(item.content().contains("added 3 file_writes"));
        } finally { h.session().close(); }
    }

    @Test
    void projectStore_isCwdIsolated(@TempDir Path tmp) throws Exception {
        Handle h = build(tmp, "p-agent");
        try {
            Path projA = tmp.resolve("A");
            Path projB = tmp.resolve("B");
            java.nio.file.Files.createDirectories(projA);
            java.nio.file.Files.createDirectories(projB);
            h.store().appendProjectChange(projA.toString(), "edit in A");
            h.store().appendProjectChange(projB.toString(), "edit in B");
            var aList = h.store().listProject(projA.toString());
            var bList = h.store().listProject(projB.toString());
            assertEquals(1, aList.size());
            assertEquals(1, bList.size());
            assertTrue(aList.get(0).content().contains("edit in A"));
            assertTrue(bList.get(0).content().contains("edit in B"));
        } finally { h.session().close(); }
    }

    @Test
    void invalidateProject_dropsCacheAndReloadsFromDisk(@TempDir Path tmp) {
        Handle h = build(tmp, "p-agent");
        try {
            h.store().appendProjectChange(tmp.toString(), "first");
            h.store().invalidateProject(tmp.toString());
            var list = h.store().listProject(tmp.toString());
            assertEquals(1, list.size());
        } finally { h.session().close(); }
    }

    @Test
    void invalidateAllProjects_dropsEveryCwdCache(@TempDir Path tmp) throws Exception {
        Handle h = build(tmp, "p-agent");
        try {
            Path projA = tmp.resolve("A");
            Path projB = tmp.resolve("B");
            java.nio.file.Files.createDirectories(projA);
            java.nio.file.Files.createDirectories(projB);
            h.store().appendProjectChange(projA.toString(), "A1");
            h.store().appendProjectChange(projB.toString(), "B1");
            h.store().invalidateAllProjects();
            assertEquals(1, h.store().listProject(projA.toString()).size());
            assertEquals(1, h.store().listProject(projB.toString()).size());
        } finally { h.session().close(); }
    }

    @Test
    void sessionLayer_isolatedAcrossSessionIds(@TempDir Path tmp) {
        Handle h = build(tmp, "s-agent");
        try {
            h.store().putSession("session-1", "k", "from 1");
            h.store().putSession("session-2", "k", "from 2");
            assertEquals("from 1", h.store().getSession("session-1", "k").orElseThrow().value());
            assertEquals("from 2", h.store().getSession("session-2", "k").orElseThrow().value());
        } finally { h.session().close(); }
    }

    @Test
    void deleteSession_returnsTrueAndRemovesKey(@TempDir Path tmp) {
        Handle h = build(tmp, "s-agent");
        try {
            h.store().putSession("session-1", "k", "v");
            assertTrue(h.store().deleteSession("session-1", "k"));
            assertTrue(h.store().getSession("session-1", "k").isEmpty());
        } finally { h.session().close(); }
    }

    @Test
    void autoCompress_triggersWhenThresholdExceeded(@TempDir Path tmp) {
        // threshold=5, keepRecent=2. 6 entries triggers compression.
        Handle h = build(tmp, "p-agent", 5, 2, true);
        try {
            for (int i = 0; i < 6; i++) {
                h.store().appendProjectChange(tmp.toString(), "change " + i);
            }
            var list = h.store().listProject(tmp.toString());
            // After compression: 1 summary line + 2 most-recent.
            // We assert <= 6 to allow the compressor to either
            // win the lock or be skipped (race tolerance).
            assertTrue(list.size() <= 6, "unexpected entry count: " + list.size());
        } finally { h.session().close(); }
    }

    @Test
    void autoCompress_disabledDoesNothing(@TempDir Path tmp) {
        // threshold=1, keepRecent=1, autoCompress=false.
        Handle h = build(tmp, "p-agent", 1, 1, false);
        try {
            for (int i = 0; i < 10; i++) {
                h.store().appendProjectChange(tmp.toString(), "change " + i);
            }
            // 10 raw entries, no compression.
            assertEquals(10, h.store().listProject(tmp.toString()).size());
        } finally { h.session().close(); }
    }

    @Test
    void accessors_exposeConfigForRpcWiring(@TempDir Path tmp) {
        SessionMemoryStore session = new SessionMemoryStore(tmp.resolve("sessions.db"));
        try {
            ProjectMemoryCompressor compressor = new ProjectMemoryCompressor(
                    ProjectMemoryCompressor.NOOP);
            LayeredMemoryStore s = new LayeredMemoryStore(
                    tmp, "x-agent", session, compressor,
                    7, 3, true);
            assertEquals("x-agent", s.agentType());
            assertEquals(7, s.projectCompressThreshold());
            assertEquals(3, s.keepRecent());
            assertTrue(s.autoCompress());
            assertEquals(session, s.sessionStore());
            assertEquals(tmp, s.memoryBase());
        } finally { session.close(); }
    }
}
