package org.aethercode.core.fs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class FileWatcherTest {

    @Test
    void registerAddsKey(@TempDir Path tmp) throws Exception {
        try (var w = newWatch(tmp, new Recording())) {
            w.register(tmp);
            assertThat(w.registeredCount()).isEqualTo(1);
        }
    }

    @Test
    void createEventIsObserved(@TempDir Path tmp) throws Exception {
        Recording rec = new Recording();
        try (var w = newWatch(tmp, rec)) {
            w.register(tmp);
            w.start();
            Files.writeString(tmp.resolve("hello.txt"), "hi");
            assertThat(rec.await(2)).isTrue();
            assertThat(rec.first().path().getFileName().toString()).isEqualTo("hello.txt");
        }
    }

    @Test
    void modifyEventIsObserved(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("a.txt");
        Files.writeString(file, "init");
        Recording rec = new Recording();
        try (var w = newWatch(tmp, rec)) {
            w.register(tmp);
            w.start();
            Files.writeString(file, "changed");
            assertThat(rec.await(2)).isTrue();
            assertThat(rec.events()).anyMatch(e -> e.kind() == FileWatcher.Kind.MODIFIED);
        }
    }

    @Test
    void deleteEventIsObserved(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("gone.txt");
        Files.writeString(file, "bye");
        Recording rec = new Recording();
        try (var w = newWatch(tmp, rec)) {
            w.register(tmp);
            w.start();
            Files.delete(file);
            assertThat(rec.await(2)).isTrue();
            assertThat(rec.events()).anyMatch(e -> e.kind() == FileWatcher.Kind.DELETED);
        }
    }

    @Test
    void startStopIsIdempotent(@TempDir Path tmp) throws Exception {
        try (var w = newWatch(tmp, new Recording())) {
            w.register(tmp);
            w.start();
            w.start(); // no-op
            w.stop();
            w.stop(); // no-op
        }
    }

    @Test
    void multipleDirsCanBeRegistered(@TempDir Path tmp) throws Exception {
        Path a = tmp.resolve("a"); Files.createDirectories(a);
        Path b = tmp.resolve("b"); Files.createDirectories(b);
        Recording rec = new Recording();
        try (var w = newWatch(tmp, rec)) {
            w.register(a);
            w.register(b);
            assertThat(w.registeredCount()).isEqualTo(2);
            w.start();
            Files.writeString(a.resolve("x.txt"), "x");
            Files.writeString(b.resolve("y.txt"), "y");
            assertThat(rec.awaitSize(2, 2)).isTrue();
            assertThat(rec.events().size()).isGreaterThanOrEqualTo(2);
        }
    }

    @Test
    void debounceReducesBurst(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("b.txt");
        Files.writeString(file, "first");
        Recording rec = new Recording();
        try (var w = new FileWatcher(java.nio.file.FileSystems.getDefault().newWatchService(),
                                    rec, 500)) {
            w.register(tmp);
            w.start();
            // hammer the file
            for (int i = 0; i < 5; i++) {
                Files.writeString(file, "v" + i);
                Thread.sleep(20);
            }
            // wait for the watcher to settle
            Thread.sleep(800);
            long modifies = rec.events().stream()
                    .filter(e -> e.kind() == FileWatcher.Kind.MODIFIED)
                    .count();
            // with 500ms debounce + 100ms total writes, expect 1 or 2 — not 5
            assertThat(modifies).isLessThanOrEqualTo(2);
        }
    }

    @Test
    void drainReturnsEmptyWhenNoEvents(@TempDir Path tmp) throws Exception {
        try (var w = newWatch(tmp, new Recording())) {
            assertThat(w.drain()).isEmpty();
        }
    }

    // ---- helpers ----

    private static FileWatcher newWatch(@TempDir Path tmp, FileWatcher.Sink sink) throws IOException {
        return new FileWatcher(java.nio.file.FileSystems.getDefault().newWatchService(), sink);
    }

    private static final class Recording implements FileWatcher.Sink {
        private final List<FileWatcher.Event> events = new CopyOnWriteArrayList<>();
        private volatile CountDownLatch latch = new CountDownLatch(1);

        @Override
        public void onEvent(FileWatcher.Event event) {
            events.add(event);
            latch.countDown();
        }
        boolean await(int seconds) throws InterruptedException {
            return latch.await(seconds, TimeUnit.SECONDS);
        }
        boolean awaitSize(int n, int seconds) throws InterruptedException {
            long deadline = System.currentTimeMillis() + seconds * 1000L;
            while (System.currentTimeMillis() < deadline) {
                if (events.size() >= n) return true;
                Thread.sleep(50);
            }
            return events.size() >= n;
        }
        FileWatcher.Event first() { return events.isEmpty() ? null : events.get(0); }
        List<FileWatcher.Event> events() { return Collections.unmodifiableList(events); }
    }
}
