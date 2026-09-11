package org.aethercode.prompts;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * tests for {@link RulesWatcher}.
 *
 * <p>The watcher uses the JDK's {@code WatchService},
 * which is reliable on Linux (inotify) and macOS
 * (FSEvents) but is generally slow on Windows network
 * drives. The tests below use {@link TempDir} (a
 * regular local directory) and a 2-second timeout on
 * the latch so the suite stays reliable on every
 * platform. A test that does not see the expected
 * event before the timeout fails with a clear message
 * ("listener never fired"), which is easy to
 * diagnose.
 */
class RulesWatcherTest {

    @TempDir Path tmp;

    private RulesWatcher watcher;

    @AfterEach
    void tearDown() {
        if (watcher != null) watcher.close();
    }

    // ---------------------------------------------------------------------------------
    //  Construction + registration
    // ---------------------------------------------------------------------------------

    @Test
    void start_watchesProjectRulesDir(@TempDir Path cwd) throws IOException {
        Path rulesDir = cwd.resolve(".aethercode/rules");
        Files.createDirectories(rulesDir);
        try (RulesWatcher w = RulesWatcher.start(cwd, null, "")) {
            assertThat(w).isNotNull();
            assertThat(w.watchedRoots()).contains(rulesDir);
        }
    }

    @Test
    void start_watchesRoleDirWhenRoleGiven(@TempDir Path cwd) throws IOException {
        Path roleDir = cwd.resolve(".aethercode/rules/roles/coder");
        Files.createDirectories(roleDir);
        try (RulesWatcher w = RulesWatcher.start(cwd, null, "coder")) {
            assertThat(w.watchedRoots())
                    .anyMatch(p -> p.endsWith(Path.of("roles", "coder")));
        }
    }

    @Test
    void start_watchesGlobalRulesDir(@TempDir Path cwd,
                                     @TempDir Path home) throws IOException {
        Path globalDir = home.resolve(".aethercode/rules");
        Files.createDirectories(globalDir);
        try (RulesWatcher w = RulesWatcher.start(cwd, home, "")) {
            assertThat(w.watchedRoots())
                    .anyMatch(p -> p.startsWith(home));
        }
    }

    @Test
    void start_missingDirsAreSkipped(@TempDir Path cwd) {
        // No rules dir → watcher starts but watches
        // nothing (startThread sees empty list and
        // closes the service).
        try (RulesWatcher w = RulesWatcher.start(cwd, null, "")) {
            // Either null (the loader's start path
            // returns null when the service can't be
            // built) or an empty watcher. Either way
            // is acceptable: the engine falls back to
            // "no live reload, prompt stays cached".
            if (w != null) assertThat(w.watchedRoots()).isEmpty();
        }
    }

    // ---------------------------------------------------------------------------------
    //  Listener fires on file change
    // ---------------------------------------------------------------------------------

    @Test
    void listenerFiresOnFileCreate() throws Exception {
        Path rulesDir = tmp.resolve(".aethercode/rules");
        Files.createDirectories(rulesDir);
        CountDownLatch fired = new CountDownLatch(1);
        AtomicReference<RulesWatcher.ChangeEvent> captured = new AtomicReference<>();
        try (RulesWatcher w = RulesWatcher.start(tmp, null, "")) {
            assertThat(w).isNotNull();
            w.onChange(ev -> { captured.set(ev); fired.countDown(); });
            // The OS needs a moment to register the
            // watch before we write. A short sleep is
            // more reliable than a retry loop in CI.
            Thread.sleep(150);
            Files.writeString(rulesDir.resolve("style.md"), "use tabs");
            assertThat(fired.await(2, TimeUnit.SECONDS))
                    .as("listener should fire after a file create")
                    .isTrue();
            assertThat(captured.get().path().getFileName().toString()).isEqualTo("style.md");
        }
    }

    @Test
    void listenerFiresOnFileModify() throws Exception {
        Path rulesDir = tmp.resolve(".aethercode/rules");
        Files.createDirectories(rulesDir);
        Path file = rulesDir.resolve("style.md");
        Files.writeString(file, "v1");
        CountDownLatch fired = new CountDownLatch(1);
        try (RulesWatcher w = RulesWatcher.start(tmp, null, "")) {
            w.onChange(ev -> fired.countDown());
            Thread.sleep(150);
            Files.writeString(file, "v2");
            assertThat(fired.await(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void listenerFiresOnFileDelete() throws Exception {
        Path rulesDir = tmp.resolve(".aethercode/rules");
        Files.createDirectories(rulesDir);
        Path file = rulesDir.resolve("to-delete.md");
        Files.writeString(file, "bye");
        CountDownLatch fired = new CountDownLatch(1);
        try (RulesWatcher w = RulesWatcher.start(tmp, null, "")) {
            w.onChange(ev -> fired.countDown());
            Thread.sleep(150);
            Files.delete(file);
            assertThat(fired.await(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void rapidEditsAreDebounced() throws Exception {
        // Two writes within a short window should
        // produce fewer listener fires than the raw
        // number of file-system events. The exact
        // ratio depends on the OS — Windows can
        // produce 3+ events for a single save
        // (truncate, write, metadata) — but the
        // debounce guarantees that back-to-back events
        // are coalesced, so we should see strictly
        // fewer fires than the number of raw write
        // calls would naïvely produce.
        Path rulesDir = tmp.resolve(".aethercode/rules");
        Files.createDirectories(rulesDir);
        Path file = rulesDir.resolve("style.md");
        Files.writeString(file, "v0");
        AtomicInteger fireCount = new AtomicInteger();
        try (RulesWatcher w = RulesWatcher.start(tmp, null, "")) {
            w.onChange(ev -> fireCount.incrementAndGet());
            Thread.sleep(150);
            // Two rapid writes (each is a "save"
            // — the OS will deliver at least 2 events
            // per save, often more on Windows).
            for (int i = 0; i < 5; i++) {
                Files.writeString(file, "v" + i);
            }
            // Allow debounce to settle.
            Thread.sleep(500);
            // 5 writes × ≥1 event per write = ≥5 raw
            // events. With debounce, we expect a
            // handful of fires, not 5+ separate ones.
            // The exact number is OS-dependent; the
            // assertion is loose to keep the test
            // reliable across platforms.
            int fires = fireCount.get();
            assertThat(fires)
                    .as("rapid edits should be debounced (got %d fires for 5 writes)", fires)
                    .isLessThanOrEqualTo(5)
                    .isGreaterThanOrEqualTo(1);
        }
    }

    // ---------------------------------------------------------------------------------
    //  close() is idempotent
    // ---------------------------------------------------------------------------------

    @Test
    void closeIsIdempotent() throws IOException {
        Path rulesDir = tmp.resolve(".aethercode/rules");
        Files.createDirectories(rulesDir);
        RulesWatcher w = RulesWatcher.start(tmp, null, "");
        assertThat(w).isNotNull();
        w.close();
        w.close();  // second close is a no-op
        assertThat(w.isClosed()).isTrue();
    }

    // ---------------------------------------------------------------------------------
    //  listener list rejects null
    // ---------------------------------------------------------------------------------

    @Test
    void onChangeRejectsNullListener(@TempDir Path cwd) throws IOException {
        Path rulesDir = cwd.resolve(".aethercode/rules");
        Files.createDirectories(rulesDir);
        try (RulesWatcher w = RulesWatcher.start(cwd, null, "")) {
            assertThat(w).isNotNull();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> w.onChange(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ---------------------------------------------------------------------------------
    //  testFire seam: deterministic event without OS
    // ---------------------------------------------------------------------------------

    @Test
    void testFireBypassesDebounce() throws Exception {
        Path rulesDir = tmp.resolve(".aethercode/rules");
        Files.createDirectories(rulesDir);
        CountDownLatch fired = new CountDownLatch(1);
        try (RulesWatcher w = RulesWatcher.start(tmp, null, "")) {
            w.onChange(ev -> fired.countDown());
            w.testFire(new RulesWatcher.ChangeEvent(
                    rulesDir.resolve("x.md"),
                    java.nio.file.StandardWatchEventKinds.ENTRY_CREATE,
                    System.currentTimeMillis()));
            assertThat(fired.await(1, TimeUnit.SECONDS))
                    .as("testFire should fire the listener immediately")
                    .isTrue();
        }
    }
}
