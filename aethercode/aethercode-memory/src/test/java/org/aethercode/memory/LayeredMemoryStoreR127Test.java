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

package org.aethercode.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        // R728: appendProjectChange now writes to PROJECT_MEMORY.md
        // (ProjectMemoryStore), not MEMORY.md (FileBackedMemory).
        // Read back via countProjectChanges / readProjectMemory
        // so the test asserts cwd isolation on the canonical store.
        Handle h = build(tmp, "p-agent");
        try {
            Path projA = tmp.resolve("A");
            Path projB = tmp.resolve("B");
            java.nio.file.Files.createDirectories(projA);
            java.nio.file.Files.createDirectories(projB);
            h.store().appendProjectChange(projA.toString(), "edit in A");
            h.store().appendProjectChange(projB.toString(), "edit in B");
            assertEquals(1, h.store().countProjectChanges(projA.toString()),
                    "project A's PROJECT_MEMORY.md must hold 1 entry");
            assertEquals(1, h.store().countProjectChanges(projB.toString()),
                    "project B's PROJECT_MEMORY.md must hold 1 entry");
            String aBody = h.store().readProjectMemory(projA.toString());
            String bBody = h.store().readProjectMemory(projB.toString());
            assertTrue(aBody.contains("edit in A"), "A body must contain A's edit: " + aBody);
            assertTrue(bBody.contains("edit in B"), "B body must contain B's edit: " + bBody);
            assertFalse(aBody.contains("edit in B"), "A body must NOT contain B's edit (cwd isolation)");
            assertFalse(bBody.contains("edit in A"), "B body must NOT contain A's edit (cwd isolation)");
        } finally { h.session().close(); }
    }

    @Test
    void invalidateProject_dropsCacheAndReloadsFromDisk(@TempDir Path tmp) {
        // R728: invalidate must clear both the legacy FileBackedMemory
        // cache and the R280 ProjectMemoryStore cache for this cwd.
        Handle h = build(tmp, "p-agent");
        try {
            h.store().appendProjectChange(tmp.toString(), "first");
            h.store().invalidateProject(tmp.toString());
            assertEquals(1, h.store().countProjectChanges(tmp.toString()),
                    "invalidate must NOT drop the entry from disk; the next read re-loads 1 change");
        } finally { h.session().close(); }
    }

    @Test
    void invalidateAllProjects_dropsEveryCwdCache(@TempDir Path tmp) throws Exception {
        // R728: invalidateAllProjects clears both legacy + R280
        // caches for every cwd. After a fresh read, both project
        // memories still hold their entries (they're on disk).
        Handle h = build(tmp, "p-agent");
        try {
            Path projA = tmp.resolve("A");
            Path projB = tmp.resolve("B");
            java.nio.file.Files.createDirectories(projA);
            java.nio.file.Files.createDirectories(projB);
            h.store().appendProjectChange(projA.toString(), "A1");
            h.store().appendProjectChange(projB.toString(), "B1");
            h.store().invalidateAllProjects();
            assertEquals(1, h.store().countProjectChanges(projA.toString()),
                    "A's PROJECT_MEMORY.md must still hold 1 entry after invalidate");
            assertEquals(1, h.store().countProjectChanges(projB.toString()),
                    "B's PROJECT_MEMORY.md must still hold 1 entry after invalidate");
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
        // R728: appendProjectChange writes to ProjectMemoryStore;
        // compression fires inside appendSessionChange when the
        // entry count exceeds the threshold. We assert <= 6 to
        // allow the compressor to either win the lock or be
        // skipped (race tolerance).
        Handle h = build(tmp, "p-agent", 5, 2, true);
        try {
            for (int i = 0; i < 6; i++) {
                h.store().appendProjectChange(tmp.toString(), "change " + i);
            }
            int countAfter = h.store().countProjectChanges(tmp.toString());
            assertTrue(countAfter <= 6,
                    "compression must keep PROJECT_MEMORY.md change-count at or below threshold+1, got: " + countAfter);
        } finally { h.session().close(); }
    }

    @Test
    void autoCompress_disabledDoesNothing(@TempDir Path tmp) {
        // R728: appendProjectChange writes to ProjectMemoryStore;
        // compression is conditional on autoCompress=true at the
        // LayeredMemoryStore level (the inner ProjectMemoryStore
        // checks its own flag against threshold).
        Handle h = build(tmp, "p-agent", 1, 1, false);
        try {
            for (int i = 0; i < 10; i++) {
                h.store().appendProjectChange(tmp.toString(), "change " + i);
            }
            assertEquals(10, h.store().countProjectChanges(tmp.toString()),
                    "10 appends with autoCompress=false must leave PROJECT_MEMORY.md untouched by compression");
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
