package org.aethercode.core.log;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonlLoggerTest {

    @Test
    void createsFileAndDir(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("nested/log.jsonl");
        try (var l = new JsonlLogger(target)) {
            l.log(Map.of("event", "test"));
        }
        assertThat(Files.exists(target)).isTrue();
    }

    @Test
    void writesOneJsonPerLine(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("log.jsonl");
        try (var l = new JsonlLogger(target)) {
            l.log(Map.of("a", 1));
            l.log(Map.of("b", "two"));
            l.log(Map.of("c", true));
        }
        String body = Files.readString(target);
        assertThat(body.split("\\r?\\n")).hasSize(3);
        assertThat(body).contains("\"a\":1");
        assertThat(body).contains("\"b\":\"two\"");
        assertThat(body).contains("\"c\":true");
    }

    @Test
    void lineCountReflectsAppends(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("log.jsonl");
        long count;
        try (var l = new JsonlLogger(target)) {
            l.log(Map.of("a", 1));
            l.log(Map.of("a", 2));
            l.log(Map.of("a", 3));
            count = l.lineCount();
        }
        assertThat(count).isEqualTo(3);
    }

    @Test
    void appendsToExistingFile(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("log.jsonl");
        try (var l1 = new JsonlLogger(target)) { l1.log(Map.of("a", "first")); }
        try (var l2 = new JsonlLogger(target)) { l2.log(Map.of("a", "second")); }
        String body = Files.readString(target);
        assertThat(body.split("\\r?\\n")).hasSize(2);
        assertThat(body).contains("first").contains("second");
    }

    @Test
    void truncatesOnAppendFalse(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("log.jsonl");
        try (var l1 = new JsonlLogger(target, true)) { l1.log(Map.of("a", "kept")); }
        try (var l2 = new JsonlLogger(target, false)) { l2.log(Map.of("a", "only")); }
        String body = Files.readString(target);
        assertThat(body).contains("only");
        assertThat(body).doesNotContain("kept");
    }

    @Test
    void includesTimestamp(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("log.jsonl");
        try (var l = new JsonlLogger(target)) {
            l.log(Map.of("event", "x"));
        }
        String body = Files.readString(target);
        assertThat(body).contains("\"ts\":");
    }

    @Test
    void nullMapIsIgnored(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("log.jsonl");
        long count;
        try (var l = new JsonlLogger(target)) {
            l.log(null);
            count = l.lineCount();
        }
        assertThat(count).isZero();
    }

    @Test
    void concurrentWritesAreAtomic(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("log.jsonl");
        int n = 100;
        try (var l = new JsonlLogger(target)) {
            Thread[] threads = new Thread[4];
            for (int t = 0; t < threads.length; t++) {
                final int ti = t;
                threads[t] = new Thread(() -> {
                    try {
                        for (int i = 0; i < n; i++) {
                            l.log(Map.of("t", ti, "i", i));
                        }
                    } catch (IOException e) { throw new RuntimeException(e); }
                });
            }
            for (Thread t : threads) t.start();
            for (Thread t : threads) t.join();
        }
        String body = Files.readString(target);
        String[] lines = body.split("\\r?\\n");
        // trailing empty line is fine; we just need at least n*4 entries
        assertThat(lines.length).isGreaterThanOrEqualTo(n * 4);
        long nonEmpty = java.util.Arrays.stream(lines).filter(l2 -> !l2.isEmpty()).count();
        assertThat(nonEmpty).isEqualTo(n * 4);
        // every non-empty line is a valid JSON
        for (String line : lines) {
            if (line.isEmpty()) continue;
            assertThat(line).startsWith("{");
            assertThat(line.charAt(line.length() - 1)).isEqualTo('}');
        }
    }

    @Test
    void fileReturnsPath(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("log.jsonl");
        try (var l = new JsonlLogger(target)) {
            assertThat(l.file()).isEqualTo(target);
        }
    }
}
