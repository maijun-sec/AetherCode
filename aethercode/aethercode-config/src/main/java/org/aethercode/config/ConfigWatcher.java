package org.aethercode.config;

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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * lightweight in-process file-system watcher for the
 * project config file ({@code <cwd>/.aethercode/config.json}).
 *
 * <p>Mirrors the design of {@code org.aethercode.prompts.RulesWatcher}
 * (prior round) but scoped to a single file. The watcher:
 * <ul>
 *   <li>opens a {@link WatchService} on the {@code .aethercode/}
 *       directory (the parent of the config file),</li>
 *   <li>runs a daemon thread that polls for events,</li>
 *   <li>fires a registered listener on every create / modify / delete
 *       of the config file,</li>
 *   <li>debounces rapid edits within a 100 ms window (so an editor's
 *       "write tmp + rename" pattern produces one event, not two),</li>
 *   <li>filters by file name so unrelated files under
 *       {@code .aethercode/} (sessions, rules) do not trigger a
 *       config reload,</li>
 *   <li>is best-effort — if the WatchService throws, the watcher
 *       logs at warn and stops. The cached config stays until the
 *       daemon restarts.</li>
 * </ul>
 *
 * <p>The class is thread-safe: the listener list is a
 * {@link CopyOnWriteArrayList}; the {@code close()} path is
 * idempotent.
 */
public final class ConfigWatcher implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigWatcher.class);

    /** Mirrors {@code RulesWatcher.DEBOUNCE_MS}. */
    public static final long DEBOUNCE_MS = 100;

    /** The config file name. Watcher fires only for events
     *  targeting this exact file. */
    public static final String CONFIG_FILE_NAME = "config.json";

    private final WatchService watchService;
    private final Path configFile;          // the file we care about (absolute or relative)
    private final Path watchedDir;           // the directory the WatchService is registered on
    private final CopyOnWriteArrayList<Consumer<ChangeEvent>> listeners =
            new CopyOnWriteArrayList<>();
    private final Thread thread;
    private volatile boolean closed = false;
    private final AtomicLong lastFireAtMs = new AtomicLong();

    /**
     * One config-file change worth reporting. The
     * {@code kind} is one of the JDK's
     * {@link StandardWatchEventKinds} constants.
     * {@code deleted} is true when the file was deleted
     * (the engine treats this as a config reset to defaults).
     */
    public record ChangeEvent(WatchEvent.Kind<?> kind, long atMs, boolean deleted) {}

    /**
     * Start a watcher for {@code <projectCwd>/.aethercode/config.json}.
     * Returns {@code null} (without throwing) if the runtime cannot
     * create a {@link WatchService}. The watcher is best-effort:
     * callers that receive {@code null} should log a warning and
     * proceed without live-reload.
     *
     * <p>If the {@code .aethercode/} directory does not exist the
     * watcher is also inactive (returns non-null but does not start
     * a thread). The user can create the file later and restart
     * the daemon; we do not auto-create the directory.
     */
    public static ConfigWatcher start(Path projectCwd) {
        if (projectCwd == null) return null;
        WatchService ws;
        try {
            ws = projectCwd.getFileSystem().newWatchService();
        } catch (IOException ioe) {
            LOG.warn("R101: cannot create WatchService: {}", ioe.getMessage());
            return null;
        }
        Path dir = projectCwd.resolve(".aethercode");
        ConfigWatcher w = new ConfigWatcher(ws, projectCwd.resolve(".aethercode").resolve(CONFIG_FILE_NAME), dir);
        w.registerIfExists();
        w.startThread();
        return w;
    }

    private ConfigWatcher(WatchService ws, Path configFile, Path watchedDir) {
        this.watchService = ws;
        this.configFile = configFile;
        this.watchedDir = watchedDir;
        this.thread = new Thread(this::run, "aethercode-config-watcher");
        this.thread.setDaemon(true);
    }

    private void registerIfExists() {
        if (watchedDir == null) return;
        if (!Files.isDirectory(watchedDir)) {
            LOG.debug("R101: .aethercode/ not present at {}, watcher inactive", watchedDir);
            try { watchService.close(); } catch (IOException ignored) {}
            closed = true;
            return;
        }
        try {
            watchedDir.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.OVERFLOW);
            LOG.debug("R101: watching {}", watchedDir);
        } catch (IOException ioe) {
            LOG.warn("R101: failed to watch {}: {}", watchedDir, ioe.getMessage());
        }
    }

    private void startThread() {
        if (closed) {
            // Nothing to watch — close the service and stay inactive.
            return;
        }
        thread.start();
    }

    /** Register a listener for change events. The
     *  listener fires on the watcher thread, so it
     *  must be quick (no blocking I/O). */
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
            } catch (ClosedWatchServiceException cwse) {
                return;
            }
            boolean dirty = false;
            WatchEvent.Kind<?> lastKind = null;
            for (WatchEvent<?> ev : key.pollEvents()) {
                WatchEvent.Kind<?> kind = ev.kind();
                if (kind == StandardWatchEventKinds.OVERFLOW) {
                    dirty = true;
                    lastKind = kind;
                    continue;
                }
                @SuppressWarnings("unchecked")
                WatchEvent<Path> pathEv = (WatchEvent<Path>) ev;
                Path name = pathEv.context();
                if (name == null) continue;
                Path resolved = watchedDir.resolve(name);
                if (!CONFIG_FILE_NAME.equals(name.toString())) {
                    // Ignore events for unrelated files in
                    // .aethercode/ (e.g. rules/, sessions/).
                    continue;
                }
                if (CONFIG_FILE_NAME.equals(name.toString())) {
                    dirty = true;
                    lastKind = kind;
                }
            }
            if (dirty && lastKind != null) {
                // Debounce: collapse a save-tmp + rename into
                // a single fire. The window matches
                // RulesWatcher.DEBOUNCE_MS.
                long now = System.currentTimeMillis();
                long last = lastFireAtMs.get();
                if (now - last < DEBOUNCE_MS) continue;
                if (!lastFireAtMs.compareAndSet(last, now)) continue;
                boolean deleted = lastKind == StandardWatchEventKinds.ENTRY_DELETE;
                ChangeEvent ce = new ChangeEvent(lastKind, now, deleted);
                for (Consumer<ChangeEvent> l : listeners) {
                    try {
                        l.accept(ce);
                    } catch (RuntimeException re) {
                        // A failing listener must never kill the
                        // watcher thread.
                        LOG.warn("R101: listener threw: {}", re.toString());
                    }
                }
            }
            if (!key.reset()) {
                // Watched dir no longer accessible — stop.
                return;
            }
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try { watchService.close(); } catch (IOException ignored) {}
        thread.interrupt();
    }
}
