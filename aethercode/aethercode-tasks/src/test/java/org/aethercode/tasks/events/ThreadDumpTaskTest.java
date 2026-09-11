package org.aethercode.tasks.events;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-P1-T07: scheduled background thread dump for a session.
 * Each dump is a separate file under
 * {@code <cwd>/.aethercode/sessions/<id>/threads-<ts>.txt}.
 */
class ThreadDumpTaskTest {

    private Path tmp;

    @BeforeEach
    void setUp() throws IOException {
        tmp = Files.createTempDirectory("aethercode-threaddump-");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tmp != null) {
            try (var walk = Files.walk(tmp)) {
                walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
            }
        }
    }

    @Test
    void dumpNow_createsFileWithExpectedName() throws IOException {
        try (ThreadDumpTask t = new ThreadDumpTask("c-1", tmp, 60L)) {
            Path file = t.dumpNow(Instant.parse("2026-08-29T08:42:00Z"));
            assertNotNull(file);
            assertTrue(Files.exists(file));
            assertEquals("threads-20260829T084200Z.txt", file.getFileName().toString());
            assertEquals(tmp.resolve(".aethercode").resolve("sessions").resolve("c-1"),
                    file.getParent());
        }
    }

    @Test
    void dumpNow_fileContainsHeaderAndCurrentThread() throws IOException {
        try (ThreadDumpTask t = new ThreadDumpTask("c-2", tmp, 60L)) {
            Path file = t.dumpNow(Instant.parse("2026-08-29T08:42:00Z"));
            String content = Files.readString(file, StandardCharsets.UTF_8);
            // Header lines so a post-mortem grep on "# childId:" works.
            assertTrue(content.contains("# childId: c-2"));
            assertTrue(content.contains("# timestamp: 2026-08-29T08:42:00Z"));
            // The Thread that ran dumpNow() must appear in the dump.
            assertTrue(content.contains("Thread: \"" + Thread.currentThread().getName() + "\""));
            assertTrue(content.contains("    at "));
        }
    }

    @Test
    void scheduledTask_writesSecondFileWithinDeadline() throws Exception {
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread th = new Thread(r, "tdump-test");
            th.setDaemon(true);
            return th;
        });
        try (ThreadDumpTask t = new ThreadDumpTask("c-3", tmp, 1L, exec)) {
            t.start();
            // Wait up to 5s for at least 2 dumps (one immediate, one scheduled).
            long deadline = System.currentTimeMillis() + 5_000L;
            while (t.dumpsTaken() < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50L);
            }
            assertTrue(t.dumpsTaken() >= 2,
                    "expected >= 2 dumps, got " + t.dumpsTaken());
            // Files written should be uniquely named.
            try (Stream<Path> listing = Files.list(t.sessionsDir())) {
                long count = listing.filter(p -> p.getFileName().toString()
                        .startsWith("threads-")).count();
                assertTrue(count >= 2, "expected >= 2 dump files, got " + count);
            }
        } finally {
            exec.shutdownNow();
        }
    }
}
