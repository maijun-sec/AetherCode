package org.aethercode.core.registry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * prior round: unified registry reload service.
 *
 * <p>Owns a single file {@link WatchService} thread that fires
 * {@link ReloadKind}-typed events when any of the watched
 * registry files change on disk. Subscribers (the engine's
 * tool pool, the renderer's UI, the daemon's /api/methods
 * endpoint) get notified via the in-process listener list AND
 * via a wire notification so a TUI / Desktop / multica
 * client that's connected to a remote daemon can react.
 *
 * <p>Why one service for both skills + MCP + agents? Three
 * reasons:
 * <ol>
 *   <li>The WatchService thread is expensive to create (a
 *       platform-specific inotify/FSEvents/ReadDirectoryChangesW
 *       handle). One thread watches all paths.</li>
 *   <li>The "something just changed, please re-scan" signal
 *       is generic; collapsing three poll loops into one
 *       drop-in callback is cheap.</li>
 *   <li>From the renderer's perspective, the user always
 *       says "I edited a config file, please reload" without
 *       caring which one. A unified {@code reloadRegistries}
 *       RPC mirrors that mental model.</li>
 * </ol>
 *
 * <p>Threading: the watcher thread is daemon + low priority.
 * A debounce of 250ms prevents a "save" operation (which
 * fires 2-3 events: write + rename + modify) from triggering
 * three reloads. The executor service is shutdown in
 * {@link #close()}.
 *
 * <p>Failure isolation: a thrown exception in one of the
 * reload handlers (skills.reload() / mcp.reload() / …) is
 * caught and logged so a corrupt MCP config can't take down
 * the whole service.
 */
public class RegistryReloadService implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(RegistryReloadService.class);

    /** Which registry changed. The wire notification payload
     *  carries this so the renderer can re-fetch just the
     *  affected list. */
    public enum ReloadKind {
        SKILLS, MCP, AGENTS, ALL
    }

    /** A single reload event. {@code kind} tells the
     *  subscriber which registry to re-read; {@code path}
     *  is the file that triggered the event (useful for
     *  "open in editor" affordances in the desktop UI). */
    public record ReloadEvent(ReloadKind kind, Path path, long atMs) {}

    /** Listener interface. Subscribers receive one callback
     *  per batched reload event (multiple filesystem events
     *  within the 250ms debounce window collapse into a
     *  single listener invocation). */
    @FunctionalInterface
    public interface Listener {
        void onReload(ReloadEvent event);
    }

    /** Functional handle to "reload this kind of registry".
     *  Each subscriber (skill registry, MCP loader, agent
     *  registry) wires its own implementation; the service
     *  just calls {@code Reloader#reload()} and broadcasts
     *  the resulting {@link ReloadEvent}. */
    @FunctionalInterface
    public interface Reloader {
        /** Run the reload. May throw — the service catches
         *  and logs so one broken reloader doesn't break the
         *  others. */
        void reload() throws Exception;
    }

    private final WatchService watcher;
    private final ScheduledExecutorService scheduler;
    private final Thread watcherThread;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong lastReloadAtMs = new AtomicLong(0);
    private volatile boolean closed = false;

    /** Per-kind debounced tasks. The 250ms timer restarts on
     *  every new filesystem event, so a "save" operation
     *  (which fires 2-3 events) collapses into one reload
     *  per affected kind. */
    private final java.util.EnumMap<ReloadKind, ScheduledFuture<?>> debounce =
            new java.util.EnumMap<>(ReloadKind.class);

    /** One Reloader per kind. The service invokes
     *  {@code reloaders[kind].reload()} on the worker thread. */
    private final java.util.EnumMap<ReloadKind, Reloader> reloaders =
            new java.util.EnumMap<>(ReloadKind.class);

    public RegistryReloadService() throws IOException {
        this.watcher = FileSystems.getDefault().newWatchService();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "registry-reload-scheduler");
            t.setDaemon(true);
            return t;
        });
        this.watcherThread = new Thread(this::watchLoop, "registry-reload-watcher");
        this.watcherThread.setDaemon(true);
        this.watcherThread.start();
    }

    /** Register a reloader. The service calls it on the
     *  scheduler thread (NOT the WatchService thread) so a
     *  slow reloader doesn't block the watcher. */
    public void registerReloader(ReloadKind kind, Reloader reloader) {
        reloaders.put(kind, reloader);
    }

    /** Subscribe to reload events. Useful for the renderer's
     *  store (re-fetch the affected list) or the daemon's
     *  notification fan-out. */
    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    /** Unregister a listener. */
    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** Watch a directory for changes. New files in the
     *  directory + modifications + deletions all fire a
     *  reload of {@code kind}. Nested directories are
     *  NOT watched (Register on each subdir if you need
     *  that). The watch is registered after the directory
     *  exists; if it doesn't exist yet, the call is a
     *  no-op (the user can call {@link #refresh()} later
     *  to pick up newly-created paths). */
    public void watch(Path dir, ReloadKind kind) {
        if (closed) return;
        if (dir == null || !Files.isDirectory(dir)) {
            LOG.debug("watch: skipping {} (not a directory)", dir);
            return;
        }
        try {
            WatchKey key = dir.register(watcher,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            watchedKeys.put(key, new WatchedPath(dir, kind));
            LOG.info("watching {} for {} changes", dir, kind);
        } catch (IOException e) {
            LOG.warn("failed to watch {}: {}", dir, e.getMessage());
        }
    }

    /** Watch a single file (not its directory). Used for
     *  config files like {@code mcp.json} that sit at a
     *  known path. Modifications + deletions fire. */
    public void watchFile(Path file, ReloadKind kind) {
        if (closed) return;
        if (file == null) return;
        Path parent = file.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            LOG.debug("watchFile: parent of {} is not a directory", file);
            return;
        }
        // We watch the parent dir and filter by file name in
        // the loop. This is the cross-platform approach —
        // there's no portable "watch one file" primitive in
        // java.nio.file.
        try {
            WatchKey key = parent.register(watcher,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            watchedKeys.put(key, new WatchedPath(file, kind));
            LOG.info("watching file {} for {} changes", file, kind);
        } catch (IOException e) {
            LOG.warn("failed to watch file {}: {}", file, e.getMessage());
        }
    }

    /** Force a reload of one kind. The renderer calls this
     *  when the user clicks the "↻" button next to a
     *  registry card. */
    public void refresh(ReloadKind kind) {
        if (closed) return;
        fireReload(kind, null);
    }

    /** Force a reload of every kind. Wired to the
     *  "reload everything" shortcut. */
    public void refreshAll() {
        refresh(ReloadKind.ALL);
    }

    /** When the service last ran a reload (any kind). Used
     *  by the renderer's "stale" badge. */
    public long lastReloadMs() { return lastReloadAtMs.get(); }

    // ---- internals -------------------------------------------------

    private final java.util.Map<WatchKey, WatchedPath> watchedKeys =
            new java.util.concurrent.ConcurrentHashMap<>();

    private record WatchedPath(Path path, ReloadKind kind) {}

    private void watchLoop() {
        while (!closed) {
            WatchKey key;
            try {
                key = watcher.take();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (ClosedWatchServiceException closed) {
                return;
            }
            WatchedPath wp = watchedKeys.get(key);
            if (wp == null) {
                key.cancel();
                continue;
            }
            // Drain queued events (collect affected paths).
            List<Path> affected = new java.util.ArrayList<>();
            for (WatchEvent<?> evt : key.pollEvents()) {
                WatchEvent.Kind<?> k = evt.kind();
                if (k == StandardWatchEventKinds.OVERFLOW) continue;
                @SuppressWarnings("unchecked")
                WatchEvent<Path> pathEvt = (WatchEvent<Path>) evt;
                Path name = pathEvt.context();
                if (wp.path.toFile().isFile()) {
                    // We registered a file-watcher (parent dir +
                    // name filter). Only fire for our file.
                    Path resolved = wp.path.getFileName();
                    if (!name.equals(resolved)) continue;
                    affected.add(wp.path);
                } else {
                    affected.add(wp.path.resolve(name));
                }
            }
            // Reset the key so we get further events.
            if (!key.reset()) {
                watchedKeys.remove(key);
            }
            // Fire a debounced reload.
            if (!affected.isEmpty()) {
                scheduleDebouncedReload(wp.kind, affected.get(0));
            }
        }
    }

    private void scheduleDebouncedReload(ReloadKind kind, Path trigger) {
        // Coalesce per-kind: if a debounce is already
        // scheduled, cancel and reschedule. The 250ms window
        // is short enough that a human's "save" feels instant
        // and long enough that 3 rapid write events (write
        // + chmod + close) collapse into one reload.
        ScheduledFuture<?> prev = debounce.get(kind);
        if (prev != null) prev.cancel(false);
        ReloadKind effective = kind == ReloadKind.ALL ? ReloadKind.ALL : kind;
        ScheduledFuture<?> next = scheduler.schedule(
                () -> {
                    try { fireReload(effective, trigger); }
                    catch (Exception e) { LOG.warn("reload handler failed: {}", e.getMessage()); }
                },
                250, TimeUnit.MILLISECONDS);
        debounce.put(kind, next);
    }

    private void fireReload(ReloadKind kind, Path trigger) {
        if (closed) return;
        long now = System.currentTimeMillis();
        lastReloadAtMs.set(now);
        if (kind == ReloadKind.ALL) {
            for (ReloadKind k : ReloadKind.values()) {
                if (k == ReloadKind.ALL) continue;
                invokeReloader(k, trigger);
            }
        } else {
            invokeReloader(kind, trigger);
        }
        ReloadEvent ev = new ReloadEvent(kind, trigger, now);
        for (Listener l : listeners) {
            try { l.onReload(ev); }
            catch (Exception e) { LOG.warn("reload listener failed: {}", e.getMessage()); }
        }
    }

    private void invokeReloader(ReloadKind kind, Path trigger) {
        Reloader r = reloaders.get(kind);
        if (r == null) return;
        try { r.reload(); }
        catch (Exception e) { LOG.warn("{} reloader failed: {}", kind, e.getMessage()); }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try { watcher.close(); } catch (IOException ignore) {}
        for (WatchKey k : watchedKeys.keySet()) k.cancel();
        watchedKeys.clear();
        scheduler.shutdownNow();
    }
}
