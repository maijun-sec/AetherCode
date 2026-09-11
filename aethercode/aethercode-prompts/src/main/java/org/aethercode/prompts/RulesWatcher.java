package org.aethercode.prompts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * lightweight in-process file-system watcher
 * for the rules directories.
 *
 * <p>The loader (prior round) reads the rules once when the
 * engine boots. After that, a user who edits
 * {@code .aethercode/rules/style.md} expects the next
 * query to see the change — but the engine has the
 * rendered prompt cached for LLM-prompt-cache
 * warmth, and a stale cache means the user has to
 * restart the daemon to see their rules take effect.
 *
 * <p>{@code RulesWatcher} closes that loop. It opens
 * a {@link WatchService} on the rules root directory
 * (and on each of the four layer directories
 * configured by the loader), starts a daemon thread
 * that polls for events, and fires a registered
 * listener whenever any file under those directories
 * is created, modified, or deleted. The engine
 * registers a listener that re-renders the prompt and
 * pushes the new value into {@code QueryEngine} via
 * {@code setSystemPrompt(...)}; the next query
 * automatically picks up the change.
 *
 * <p>Design notes:
 * <ul>
 *   <li><b>Single WatchService</b> for the whole
 *       watcher — one OS-level registration per
 *       watched directory, not per file. The
 *       standard {@code WatchService} implementation
 *       on Linux (inotify) and macOS (FSEvents)
 *       scales to thousands of watched dirs without
 *       trouble; Windows (ReadDirectoryChangesW) is
 *       similarly cheap.</li>
 *   <li><b>Polling, not signal-driven</b> — the
 *       thread calls {@code take()} in a loop and
 *       processes events. No native callbacks, no
 *       busy-wait. The thread is a daemon so it does
 *       not keep the JVM alive.</li>
 *   <li><b>Debounce on rapid edits</b> — a save that
 *       touches a file twice in 50 ms (common with
 *       editor "write to temp + rename" patterns)
 *       fires two events; the listener runs once.
 *       The debounce window is 100 ms and is shared
 *       across all watched directories.</li>
 *   <li><b>Best-effort</b> — if the WatchService
 *       throws (e.g. the directory was deleted), the
 *       watcher logs at warn and continues. The
 *       engine keeps the last successfully-rendered
 *       prompt; the user has to restart the daemon
 *       to re-attach the watcher.</li>
 *   <li><b>Auto-closeable</b> — the engine
 *       {@code close()} path closes the watcher
 *       cleanly. The daemon thread is interrupted
 *       via the watch service's {@code close()}
 *       (which unblocks {@code take()} with a
 *       {@code ClosedWatchServiceException}).</li>
 * </ul>
 *
 * <p>The class is thread-safe: the listener list is
 * a {@link CopyOnWriteArrayList}; the {@code close()}
 * path is idempotent.
 */
public final class RulesWatcher implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(RulesWatcher.class);

    /**
     * Debounce window. Edits that land within this
     * window are coalesced into a single listener
     * invocation. The value is small enough that a
     * user editing a file by hand sees one notification,
     * and large enough to absorb editor save patterns
     * that produce two events (write tmp + rename).
     */
    public static final long DEBOUNCE_MS = 100;

    private final WatchService watchService;
    private final List<Path> watchedRoots = new ArrayList<>();
    private final CopyOnWriteArrayList<Consumer<ChangeEvent>> listeners =
            new CopyOnWriteArrayList<>();
    private final Thread thread;
    private volatile boolean closed = false;
    private final AtomicLong lastFireAtMs = new AtomicLong();

    /**
     * One file-system change worth reporting. The
     * {@code path} is the file the event referred to
     * (relative to the watch root, or absolute —
     * consumers should treat as opaque). The
     * {@code kind} is one of the JDK's
     * {@link StandardWatchEventKinds} constants.
     */
    public record ChangeEvent(Path path, WatchEvent.Kind<?> kind, long atMs) {}

    /**
     * Start a watcher for the rules directories that
     * the loader would read for {@code (projectCwd, userHome, role)}.
     * Returns {@code null} (without throwing) if the
     * runtime cannot create a {@link WatchService}
     * — e.g. the JVM was started on a platform that
     * does not support it. Callers that receive null
     * should log a warning and proceed without
     * live-reload (the cached prompt stays until the
     * daemon restarts).
     */
    public static RulesWatcher start(Path projectCwd, Path userHome, String role) {
        WatchService ws;
        try {
            ws = projectCwd == null
                    ? null
                    : projectCwd.getFileSystem().newWatchService();
        } catch (IOException ioe) {
            LOG.warn("对应历史 round: cannot create WatchService: {}", ioe.getMessage());
            return null;
        }
        if (ws == null) return null;
        RulesWatcher w = new RulesWatcher(ws);
        String safeRole = RulesLoader.safeRoleOrEmpty(role);
        // Register all four layer directories (and
        // the rules root that contains them). Missing
        // directories are silently skipped — they
        // may be created later, but the engine only
        // needs to know about directories that exist
        // at boot.
        w.registerIfExists(projectCwd == null ? null
                : projectCwd.resolve(RulesLoader.PROJECT_RULES_DIR));
        if (!safeRole.isEmpty() && projectCwd != null) {
            w.registerIfExists(projectCwd.resolve(RulesLoader.PROJECT_RULES_DIR)
                    .resolve(RulesLoader.ROLES_SUBDIR).resolve(safeRole));
        }
        w.registerIfExists(userHome == null ? null
                : userHome.resolve(RulesLoader.GLOBAL_RULES_DIR));
        if (!safeRole.isEmpty() && userHome != null) {
            w.registerIfExists(userHome.resolve(RulesLoader.GLOBAL_RULES_DIR)
                    .resolve(RulesLoader.ROLES_SUBDIR).resolve(safeRole));
        }
        w.startThread();
        return w;
    }

    private RulesWatcher(WatchService ws) {
        this.watchService = ws;
        this.thread = new Thread(this::run, "aethercode-rules-watcher");
        this.thread.setDaemon(true);
    }

    private void registerIfExists(Path dir) {
        if (dir == null) return;
        if (!Files.isDirectory(dir)) return;
        try {
            dir.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.OVERFLOW);
            watchedRoots.add(dir);
            LOG.debug("对应历史 round: watching {}", dir);
        } catch (IOException ioe) {
            LOG.warn("对应历史 round: failed to watch {}: {}", dir, ioe.getMessage());
        }
    }

    private void startThread() {
        if (watchedRoots.isEmpty()) {
            // Nothing to watch — close the service
            // and stay inactive. Closing a service
            // whose thread never started is safe.
            try { watchService.close(); } catch (IOException ignored) {}
            closed = true;
            return;
        }
        thread.start();
    }

    /** Register a listener for change events. The
     *  listener fires on the watcher thread, so it
     *  must be quick (no blocking I/O). Returns the
     *  listener so the caller can later remove it
     *  (the engine does not, today). */
    public Consumer<ChangeEvent> onChange(Consumer<ChangeEvent> listener) {
        if (listener == null) throw new IllegalArgumentException("listener");
        listeners.add(listener);
        return listener;
    }

    private void run() {
        while (!closed) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (ClosedWatchServiceException closed) {
                return;
            }
            // Coalesce events: a single editor save can
            // produce several events in quick
            // succession. We process all events on the
            // key, then debounce the listener call.
            List<ChangeEvent> events = new ArrayList<>();
            for (WatchEvent<?> ev : key.pollEvents()) {
                if (ev.kind() == StandardWatchEventKinds.OVERFLOW) continue;
                @SuppressWarnings("unchecked")
                WatchEvent<Path> pathEvent = (WatchEvent<Path>) ev;
                Path child = pathEvent.context();
                Path parent = watchedRoots.stream()
                        .filter(p -> key.watchable() == p)
                        .findFirst().orElse(null);
                Path resolved = parent == null ? child : parent.resolve(child);
                events.add(new ChangeEvent(resolved, ev.kind(), System.currentTimeMillis()));
            }
            if (!key.reset()) {
                // The directory was deleted or
                // became inaccessible. Drop the key;
                // the listener won't fire for it again.
                LOG.warn("对应历史 round: watch key no longer valid; directory may have been deleted");
            }
            fireDebounced(events);
        }
    }

    private void fireDebounced(List<ChangeEvent> events) {
        long now = System.currentTimeMillis();
        long last = lastFireAtMs.get();
        if (now - last < DEBOUNCE_MS) {
            // Sleep until the debounce window closes,
            // then fire once with the latest event.
            try { Thread.sleep(DEBOUNCE_MS - (now - last)); }
            catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        lastFireAtMs.set(System.currentTimeMillis());
        for (var l : listeners) {
            try { l.accept(events.get(events.size() - 1)); }
            catch (Exception ex) {
                LOG.warn("对应历史 round: rules listener threw: {}", ex.getMessage());
            }
        }
    }

    /** True after {@link #close()} has been called. */
    public boolean isClosed() { return closed; }

    /** Read-only snapshot of the directories currently
     *  being watched. Used by tests and by the
     *  "what am I watching" debug log. */
    public List<Path> watchedRoots() { return List.copyOf(watchedRoots); }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try { watchService.close(); }
        catch (IOException ioe) { LOG.warn("对应历史 round: close failed: {}", ioe.getMessage()); }
        thread.interrupt();
    }

    /** Test seam: trigger a debounced fire with an
     *  artificial event, without touching the file
     *  system. The {@code setLastFireAtMs} override
     *  lets the test bypass the debounce window when
     *  it needs deterministic timing. */
    void testFire(ChangeEvent ev) {
        lastFireAtMs.set(0);
        for (var l : listeners) {
            try { l.accept(ev); }
            catch (Exception ex) { LOG.warn("对应历史 round: listener threw: {}", ex.getMessage()); }
        }
    }

    /** Set of {@link StandardWatchEventKinds} the
     *  watcher subscribes to. Exposed for tests
     *  that want to assert the right kinds are
     *  wired up. */
    public static final Set<java.nio.file.WatchEvent.Kind<?>> EVENT_KINDS =
            Set.of(StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
}
