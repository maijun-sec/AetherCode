package org.aethercode.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R280 unit tests for {@link ProjectMemoryStore}. Validates the
 * plain-text {@code PROJECT_MEMORY.md} contract:
 * <ul>
 *   <li>project-info block: write + read round-trip</li>
 *   <li>session-change block: append + count + parse + format</li>
 *   <li>{@code readExcludingSession}: filters the requested session's
 *       lines, leaves the project-info block intact</li>
 *   <li>compression: when count crosses threshold, the LLM client is
 *       called and a summary replaces the oldest block</li>
 * </ul>
 */
class ProjectMemoryStoreR280Test {

    @Test
    void bootstrapSkeleton_whenFileMissingHasBothBlocks(@TempDir Path tmp) throws Exception {
        ProjectMemoryStore s = ProjectMemoryStore.forCwd(
                tmp.toString(), "test", new ProjectMemoryCompressor(ProjectMemoryCompressor.NOOP), 20, 5);
        // file doesn't exist yet
        assertFalse(Files.exists(s.file()));
        // appendSessionChange creates the file
        s.appendSessionChange("session-1", "did thing 1");
        assertTrue(Files.exists(s.file()));
        String txt = Files.readString(s.file());
        assertTrue(txt.contains(ProjectMemoryStore.PROJECT_INFO_START));
        assertTrue(txt.contains(ProjectMemoryStore.PROJECT_INFO_END));
        assertTrue(txt.contains(ProjectMemoryStore.SESSION_CHANGES_START));
        assertTrue(txt.contains(ProjectMemoryStore.SESSION_CHANGES_END));
    }

    @Test
    void writeProjectInfo_thenReadProjectInfo_roundTrip(@TempDir Path tmp) throws Exception  {
        ProjectMemoryStore s = ProjectMemoryStore.forCwd(
                tmp.toString(), "test", new ProjectMemoryCompressor(ProjectMemoryCompressor.NOOP), 20, 5);
        s.appendSessionChange("session-1", "did thing 1");
        s.writeProjectInfo("# Project name: abc_1\n\nThis project is a Java Maven playground.\n- It supports int, short, long arrays.\n- It has 5+ sort algos.");
        String info = s.readProjectInfo();
        assertTrue(info.contains("Project name: abc_1"));
        assertTrue(info.contains("Java Maven"));
        // session-change block is preserved
        assertEquals(1, s.countChanges());
    }

    @Test
    void writeProjectInfo_replacesExisting(@TempDir Path tmp) throws Exception  {
        ProjectMemoryStore s = ProjectMemoryStore.forCwd(
                tmp.toString(), "test", new ProjectMemoryCompressor(ProjectMemoryCompressor.NOOP), 20, 5);
        s.writeProjectInfo("first version");
        s.writeProjectInfo("second version");
        String info = s.readProjectInfo();
        assertTrue(info.contains("second version"));
        assertFalse(info.contains("first version"));
    }

    @Test
    void appendSessionChange_writesExpectedFormat(@TempDir Path tmp) throws Exception {
        ProjectMemoryStore s = ProjectMemoryStore.forCwd(
                tmp.toString(), "test", new ProjectMemoryCompressor(ProjectMemoryCompressor.NOOP), 20, 5);
        s.appendSessionChange("sess-abc", "refactored daemon");
        // The on-disk format is "[<sessionId> <iso8601>] <description>"
        String body = Files.readString(s.file());
        // First session-change line begins with [sess-abc <ts>]
        String[] lines = body.split("\n");
        boolean found = false;
        for (String l : lines) {
            if (l.startsWith("[sess-abc ") && l.contains("] refactored daemon")) {
                found = true; break;
            }
        }
        assertTrue(found, "expected line '[sess-abc <ts>] refactored daemon' in:\n" + body);
        assertEquals(1, s.countChanges());
        // listChanges returns the parsed entry
        List<ProjectMemoryStore.ChangeEntry> entries = s.listChanges();
        assertEquals(1, entries.size());
        assertEquals("sess-abc", entries.get(0).sessionId());
        assertEquals("refactored daemon", entries.get(0).description());
    }

    @Test
    void appendSessionChange_blankDescriptionSkipped(@TempDir Path tmp) {
        ProjectMemoryStore s = ProjectMemoryStore.forCwd(
                tmp.toString(), "test", new ProjectMemoryCompressor(ProjectMemoryCompressor.NOOP), 20, 5);
        s.appendSessionChange("s1", "");
        s.appendSessionChange("s1", "   ");
        s.appendSessionChange("s1", null);
        assertEquals(0, s.countChanges());
    }

    @Test
    void readExcludingSession_filtersRequestedSession(@TempDir Path tmp) throws Exception {
        ProjectMemoryStore s = ProjectMemoryStore.forCwd(
                tmp.toString(), "test", new ProjectMemoryCompressor(ProjectMemoryCompressor.NOOP), 20, 5);
        s.writeProjectInfo("PERSISTENT-PROJECT-INFO");
        s.appendSessionChange("s1", "from session 1 �?keep");
        s.appendSessionChange("s2", "from session 2 �?drop");
        s.appendSessionChange("s2", "second from session 2 �?drop");
        s.appendSessionChange("s3", "from session 3 �?keep");

        // exclude s2
        String filtered = s.readExcludingSession("s2");
        assertTrue(filtered.contains("PERSISTENT-PROJECT-INFO"), "info block survives exclusion");
        assertTrue(filtered.contains("from session 1"));
        assertTrue(filtered.contains("from session 3"));
        assertFalse(filtered.contains("from session 2"), "s2 lines were filtered");
        // exclude null/blank -> no filter
        String all = s.readExcludingSession(null);
        assertTrue(all.contains("from session 2"));
    }

    @Test
    void compression_collapsesOldestBlockAndKeepsRecent(@TempDir Path tmp) throws Exception {
        // threshold 3, keep recent 2 -> at 4 entries, oldest (4-2)=2 should compress
        // use a fake chat client that returns a known summary
        java.util.concurrent.atomic.AtomicReference<String> capturedPrompt = new java.util.concurrent.atomic.AtomicReference<>();
        ProjectMemoryCompressor.ChatClient fakeChat = prompt -> {
            capturedPrompt.set(prompt);
            return Optional.of("[fake-summary-line] LLM-summarised 2 entries");
        };
        ProjectMemoryCompressor compressor = new ProjectMemoryCompressor(fakeChat);
        ProjectMemoryStore s = ProjectMemoryStore.forCwd(
                tmp.toString(), "test", compressor, 3, 2);

        // 4 entries: expect compression to fire on the 4th (count > 3 triggers)
        s.appendSessionChange("s1", "first");
        s.appendSessionChange("s1", "second");
        s.appendSessionChange("s1", "third");
        s.appendSessionChange("s1", "fourth");

        // Now the change-block should have:
        //   - 1 summary line "[fake-summary-line] ..."
        //   - 2 recent verbatim lines ("third", "fourth")
        // Total = 3 lines in the SESSION-CHANGES block
        String body = Files.readString(s.file());
        // capturedPrompt was non-null (chat client got called)
        assertNotNull(capturedPrompt.get());
        assertTrue(capturedPrompt.get().contains("first"));
        assertTrue(capturedPrompt.get().contains("second"));

        // Body must contain the summary + recent 2, NOT contain first/second verbatim
        assertTrue(body.contains("LLM-summarised 2 entries"),
                "summary line should appear, body was:\n" + body);
        // older lines "first" / "second" should be GONE (replaced by summary)
        assertFalse(body.contains("] first"), "oldest line was not replaced; body:\n" + body);
        assertFalse(body.contains("] second"), "second oldest line was not replaced; body:\n" + body);
        // recent lines should survive verbatim
        assertTrue(body.contains("] third"));
        assertTrue(body.contains("] fourth"));
        // count = summary(1) + recent(2) = 3 (matches keepRecent+1)
        assertEquals(3, s.countChanges());
    }

    @Test
    void compression_noopWhenUnderThreshold(@TempDir Path tmp) {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        ProjectMemoryCompressor.ChatClient counting = prompt -> {
            calls.incrementAndGet();
            return Optional.empty();
        };
        ProjectMemoryCompressor compressor = new ProjectMemoryCompressor(counting);
        ProjectMemoryStore s = ProjectMemoryStore.forCwd(
                tmp.toString(), "test", compressor, 20, 5);

        for (int i = 0; i < 10; i++) {
            s.appendSessionChange("s" + i, "entry " + i);
        }
        // 10 entries: 10 <= 20 (threshold) �?no compression
        assertEquals(0, calls.get());
        assertEquals(10, s.countChanges());
    }

    @Test
    void readProjectMemory_bootstrapShape(@TempDir Path tmp) throws Exception  {
        ProjectMemoryStore s = ProjectMemoryStore.forCwd(
                tmp.toString(), "test", new ProjectMemoryCompressor(ProjectMemoryCompressor.NOOP), 20, 5);
        // append one to materialise the file
        s.appendSessionChange("s1", "hello");
        String body = s.readAll();
        assertTrue(body.startsWith("<!--"));
        // Both blocks present
        assertTrue(body.contains(ProjectMemoryStore.PROJECT_INFO_START));
        assertTrue(body.contains(ProjectMemoryStore.SESSION_CHANGES_START));
        // The change line is present
        assertTrue(body.contains("[s1 "));
    }
}
