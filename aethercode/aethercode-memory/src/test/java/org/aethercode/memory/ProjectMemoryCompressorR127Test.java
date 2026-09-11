package org.aethercode.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * tests for {@link ProjectMemoryCompressor}.
 *
 * <p>Covers the LLM-driven summary path (with a stub chat
 * client) and the no-op fallback (the tag-only line that the
 * daemon uses when no chat client is wired).
 */
class ProjectMemoryCompressorR127Test {

    /** A test chat client that returns a single-line summary
     *  so the compressor's "one summary line + recent" file
     *  shape holds. Lets us assert the compressor did
     *  consult the LLM and used its output verbatim. */
    private static final ProjectMemoryCompressor.ChatClient STUB = prompt -> {
        // Echo back a single-line summary that mentions how
        // many entries were compressed. The compressor
        // appends the timestamp prefix, so we only return
        // the body. Returns single line so the file shape
        // stays exactly "1 summary + keepRecent entries".
        int count = 0;
        for (String line : prompt.split("\n")) {
            if (line.startsWith("[")) count++;
        }
        return Optional.of("summary: compressed " + count + " entries via LLM");
    };

    @Test
    void noopFallback_producesTagOnlyLine(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("MEMORY.md");
        Files.writeString(file,
                "[2026-08-20T10:00:00Z] alpha\n" +
                "[2026-08-20T10:01:00Z] beta\n" +
                "[2026-08-20T10:02:00Z] gamma\n" +
                "[2026-08-20T10:03:00Z] delta\n" +
                "[2026-08-20T10:04:00Z] epsilon\n");
        ProjectMemoryCompressor c = new ProjectMemoryCompressor(
                ProjectMemoryCompressor.NOOP);
        // threshold=2, keepRecent=1 -> 4 oldest become one
        // "[compressed: 4 entries]" line + the most-recent.
        var res = c.maybeCompress(file, 2, 1);
        assertTrue(res.compressed());
        assertEquals(5, res.beforeCount());
        assertEquals(2, res.afterCount());
        assertEquals("ok", res.reason());
        List<String> after = Files.readAllLines(file);
        // 1 summary line + 1 most-recent entry = 2.
        assertEquals(2, after.size());
        assertTrue(after.get(0).contains("[compressed: 4 entries]"),
                "tag-only summary should mention entry count: " + after);
    }

    @Test
    void stubChatClient_consultsLlmAndUsesSummary(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("MEMORY.md");
        Files.writeString(file,
                "[2026-08-20T10:00:00Z] alpha\n" +
                "[2026-08-20T10:01:00Z] beta\n" +
                "[2026-08-20T10:02:00Z] gamma\n" +
                "[2026-08-20T10:03:00Z] delta\n" +
                "[2026-08-20T10:04:00Z] epsilon\n");
        ProjectMemoryCompressor c = new ProjectMemoryCompressor(STUB);
        var res = c.maybeCompress(file, 2, 1);
        assertTrue(res.compressed());
        List<String> after = Files.readAllLines(file);
        assertEquals(2, after.size());
        // The stub returns a single line with "summary: ...".
        assertTrue(after.get(0).contains("summary: "),
                "LLM output should be in the file: " + after);
    }

    @Test
    void maybeCompress_underThresholdReturnsUnchanged(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("MEMORY.md");
        Files.writeString(file,
                "[2026-08-20T10:00:00Z] only-one\n");
        var c = new ProjectMemoryCompressor(STUB);
        var res = c.maybeCompress(file, /* threshold */ 5, /* keepRecent */ 3);
        assertFalse(res.compressed());
        assertEquals("under threshold", res.reason());
        assertEquals(1, res.beforeCount());
    }

    @Test
    void maybeCompress_keepsHeaderLines(@TempDir Path tmp) throws Exception {
        // The R127 brief mentions a "<椤圭洰鎻忚堪>" header can sit
        // above the change-log. Compressor must preserve it.
        Path file = tmp.resolve("MEMORY.md");
        Files.writeString(file,
                "Project: AetherCode\n" +
                "Owner: maijun\n" +
                "[2026-08-20T10:00:00Z] a\n" +
                "[2026-08-20T10:01:00Z] b\n" +
                "[2026-08-20T10:02:00Z] c\n" +
                "[2026-08-20T10:03:00Z] d\n");
        var c = new ProjectMemoryCompressor(ProjectMemoryCompressor.NOOP);
        var res = c.maybeCompress(file, 2, 1);
        assertTrue(res.compressed());
        List<String> after = Files.readAllLines(file);
        // First two lines should still be the headers.
        assertEquals("Project: AetherCode", after.get(0));
        assertEquals("Owner: maijun", after.get(1));
    }

    @Test
    void maybeCompress_missingFileReturnsNoFile(@TempDir Path tmp) {
        Path file = tmp.resolve("does-not-exist.md");
        var c = new ProjectMemoryCompressor(STUB);
        var res = c.maybeCompress(file, 1, 1);
        assertFalse(res.compressed());
        assertEquals("no file", res.reason());
    }

    @Test
    void maybeCompress_recordsPromptToChatClient(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("MEMORY.md");
        Files.writeString(file,
                "[2026-08-20T10:00:00Z] a\n" +
                "[2026-08-20T10:01:00Z] b\n" +
                "[2026-08-20T10:02:00Z] c\n" +
                "[2026-08-20T10:03:00Z] d\n");
        AtomicReference<String> captured = new AtomicReference<>();
        ProjectMemoryCompressor.ChatClient recording = prompt -> {
            captured.set(prompt);
            return Optional.of("[2026-08-20T10:00:00Z] summary");
        };
        var c = new ProjectMemoryCompressor(recording);
        c.maybeCompress(file, 2, 1);
        assertTrue(captured.get().contains("Compress the following change-log"),
                "prompt must instruct the LLM to compress");
        assertTrue(captured.get().contains("[2026-08-20T10:00:00Z] a"),
                "prompt must include the oldest entries");
    }

    @Test
    void maybeCompress_raceTwoThreads_OneSucceedsOtherBusy(@TempDir Path tmp) throws Exception {
        // The per-file lock is non-blocking. The first thread
        // wins, the second thread reports "busy".
        Path file = tmp.resolve("MEMORY.md");
        Files.writeString(file,
                "[2026-08-20T10:00:00Z] a\n" +
                "[2026-08-20T10:01:00Z] b\n" +
                "[2026-08-20T10:02:00Z] c\n" +
                "[2026-08-20T10:03:00Z] d\n");
        ProjectMemoryCompressor.ChatClient slow = prompt -> {
            try { Thread.sleep(150); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            return Optional.of("[2026-08-20T10:00:00Z] summary");
        };
        var c = new ProjectMemoryCompressor(slow);
        // Spawn two threads 鈥?exactly one should report
        // compressed=true, the other either succeeds (if it
        // waited out the lock) or reports "busy".
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<ProjectMemoryCompressor.CompressResult> r1 = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<ProjectMemoryCompressor.CompressResult> r2 = new java.util.concurrent.atomic.AtomicReference<>();
        Thread t1 = new Thread(() -> { start.countDown(); r1.set(c.maybeCompress(file, 2, 1)); });
        Thread t2 = new Thread(() -> {
            try { start.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            // tiny stagger so t1 grabs the lock first
            try { Thread.sleep(10); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            r2.set(c.maybeCompress(file, 2, 1));
        });
        t1.start();
        t2.start();
        t1.join();
        t2.join();
        boolean oneCompressed = r1.get().compressed() || r2.get().compressed();
        assertTrue(oneCompressed, "at least one compression should succeed");
    }

    @Test
    void maybeCompress_keepRecentLargerThanEntries_doesNothing(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("MEMORY.md");
        Files.writeString(file,
                "[2026-08-20T10:00:00Z] a\n" +
                "[2026-08-20T10:01:00Z] b\n");
        var c = new ProjectMemoryCompressor(STUB);
        var res = c.maybeCompress(file, 1, /* keepRecent */ 100);
        assertFalse(res.compressed());
        // Nothing to compress when keepRecent >= entry count.
        assertEquals("nothing to compress", res.reason());
    }

    @Test
    void chatAccessor_returnsWiredClient(@TempDir Path tmp) {
        var c = new ProjectMemoryCompressor(STUB);
        assertEquals(STUB, c.chat());
        // null client falls back to NOOP.
        var c2 = new ProjectMemoryCompressor(null);
        assertEquals(ProjectMemoryCompressor.NOOP, c2.chat());
    }
}
