package org.aethercode.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R280 tests for the {@link LayeredMemoryStore} public API for
 * project memory: writeProjectInfo / readProjectMemory /
 * readProjectMemoryExcluding / appendSessionChange /
 * countProjectChanges. Backed by the underlying
 * {@link ProjectMemoryStore} for plain-text PROJECT_MEMORY.md.
 */
class LayeredMemoryStoreR280Test {

    @Test
    void writeAndReadProjectInfo_throughFacade(@TempDir Path tmp) throws Exception {
        LayeredMemoryStore s = build(tmp);
        try {
            String cwd = tmp.toString();
            s.writeProjectInfo(cwd, "# Project name: hello\n- Has 5 sort algos");
            String info = s.readProjectMemory(cwd);
            assertTrue(info.contains("Project name: hello"));
            assertTrue(info.contains("5 sort algos"));
            // 0 session changes, just info block
            assertEquals(0, s.countProjectChanges(cwd));
        } finally { close(s); }
    }

    @Test
    void appendSessionChange_throughFacade_writesExpectedFormat(@TempDir Path tmp) throws Exception {
        LayeredMemoryStore s = build(tmp);
        try {
            String cwd = tmp.toString();
            s.appendSessionChange(cwd, "session-x", "fixed R279 maven cache");
            Path memFile = ProjectMemoryStore.forCwd(cwd, "test",
                    new ProjectMemoryCompressor(ProjectMemoryCompressor.NOOP), 20, 5).file();
            assertTrue(Files.exists(memFile));
            String body = Files.readString(memFile);
            assertTrue(body.contains("session-x"));
            assertTrue(body.contains("fixed R279 maven cache"));
            assertEquals(1, s.countProjectChanges(cwd));
        } finally { close(s); }
    }

    @Test
    void readExcludingSession_filtersViaFacade(@TempDir Path tmp) {
        LayeredMemoryStore s = build(tmp);
        try {
            String cwd = tmp.toString();
            s.writeProjectInfo(cwd, "AGENT-CAPABILITIES: 5 sort algos");
            s.appendSessionChange(cwd, "sess-A", "did A work");
            s.appendSessionChange(cwd, "sess-B", "did B work — DO NOT INCLUDE");
            s.appendSessionChange(cwd, "sess-C", "did C work");

            String keptA = s.readProjectMemoryExcluding(cwd, "sess-B");
            assertTrue(keptA.contains("AGENT-CAPABILITIES"));
            assertTrue(keptA.contains("did A work"));
            assertTrue(keptA.contains("did C work"));
            assertFalse(keptA.contains("did B work — DO NOT INCLUDE"));

            // null exclude -> no filter
            String all = s.readProjectMemoryExcluding(cwd, null);
            assertTrue(all.contains("did B work — DO NOT INCLUDE"));
        } finally { close(s); }
    }

    @Test
    void appendSessionChange_blankDescriptionSkipped(@TempDir Path tmp) {
        LayeredMemoryStore s = build(tmp);
        try {
            String cwd = tmp.toString();
            s.appendSessionChange(cwd, "s1", null);
            s.appendSessionChange(cwd, "s1", "");
            s.appendSessionChange(cwd, "s1", "   ");
            assertEquals(0, s.countProjectChanges(cwd));
        } finally { close(s); }
    }

    @Test
    void countProjectChanges_handlesMissingCwd(@TempDir Path tmp) {
        LayeredMemoryStore s = build(tmp);
        try {
            assertEquals(0, s.countProjectChanges(null));
            assertEquals(0, s.readProjectMemory(null).length());
        } finally { close(s); }
    }

    @Test
    void invalidateProject_dropsCachedProjectMemoryStore(@TempDir Path tmp) {
        LayeredMemoryStore s = build(tmp);
        try {
            String cwd = tmp.toString();
            s.appendSessionChange(cwd, "s1", "first");
            assertEquals(1, s.countProjectChanges(cwd));
            s.invalidateProject(cwd);
            // After invalidate, the file is reloaded — still 1 entry on disk.
            assertEquals(1, s.countProjectChanges(cwd));
        } finally { close(s); }
    }

    // ---- helpers ----

    private LayeredMemoryStore build(Path memoryBase) {
        SessionMemoryStore session = new SessionMemoryStore(memoryBase.resolve("sessions.db"));
        ProjectMemoryCompressor compressor = new ProjectMemoryCompressor(ProjectMemoryCompressor.NOOP);
        return new LayeredMemoryStore(memoryBase, "test", session, compressor, 20, 5, true);
    }

    private void close(LayeredMemoryStore s) {
        try { s.sessionStore().close(); } catch (Exception ignore) {}
    }
}
