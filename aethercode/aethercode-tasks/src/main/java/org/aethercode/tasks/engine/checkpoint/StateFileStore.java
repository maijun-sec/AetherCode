package org.aethercode.tasks.engine.checkpoint;

import org.aethercode.tasks.engine.core.SupervisorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * T-1-02: off-process state file at
 * {@code <root>/<sessionId>/state.bin}. The supervisor's
 * {@link SupervisorStore} row only carries the SHA-256 hash +
 * size; the actual bytes live here.
 *
 * <p>Layout:
 * <pre>
 *   root/
 *     &lt;sessionId-a&gt;/
 *       state.bin
 *     &lt;sessionId-b&gt;/
 *       state.bin
 *     ...
 * </pre>
 *
 * <p>Auto-eviction: when the total size of all state files
 * exceeds {@code maxTotalBytes} (default 100 MB) the oldest
 * files (by last-modified) are pruned until the total is
 * under the cap. The supervisor's hash row is left in place —
 * the eviction is transparent to the {@code StateFileStore}
 * and the supervisor sees an {@code Optional.empty()} on
 * {@link #read(String, String)}.
 *
 * <p>Thread-safety: every public method synchronises on
 * {@code this}. The store is intended to be used from the
 * supervisor's single checkpoint thread + the on-startup
 * re-loader; concurrent writers from many threads is not a
 * target.
 */
public final class StateFileStore {

    private static final Logger LOG = LoggerFactory.getLogger(StateFileStore.class);

    /** Default per-session file name (overridable per call). */
    public static final String DEFAULT_FILE_NAME = "state.bin";

    /** Default total on-disk budget. 100 MB matches design.md §3.2.4. */
    public static final long DEFAULT_MAX_TOTAL_BYTES = 100L * 1024 * 1024;

    private final Path root;
    private final long maxTotalBytes;
    private final SupervisorStore store; // optional, may be null
    private final Object lock = new Object();

    public StateFileStore(Path root) {
        this(root, DEFAULT_MAX_TOTAL_BYTES, null);
    }

    public StateFileStore(Path root, long maxTotalBytes) {
        this(root, maxTotalBytes, null);
    }

    public StateFileStore(Path root, long maxTotalBytes, SupervisorStore store) {
        Objects.requireNonNull(root, "root");
        if (maxTotalBytes < 1024) {
            throw new IllegalArgumentException("maxTotalBytes must be >= 1024, got " + maxTotalBytes);
        }
        this.root = root;
        this.maxTotalBytes = maxTotalBytes;
        this.store = store;
    }

    public Path root() { return root; }
    public long maxTotalBytes() { return maxTotalBytes; }

    /**
     * Ensure the root directory exists. Idempotent.
     */
    public StateFileStore init() throws IOException {
        synchronized (lock) {
            Files.createDirectories(root);
            return this;
        }
    }

    /**
     * Write {@code rawState} to {@code sessionId/state.bin} and
     * record the hash + size in {@link SupervisorStore} (if one
     * was injected). Returns the SHA-256 hex of the raw bytes.
     *
     * <p>Internally delegates to {@link StateCheckpointCodec}
     * for the actual atomic write.
     */
    public String write(String sessionId, byte[] rawState) throws IOException {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(rawState, "rawState");
        synchronized (lock) {
            Path dir = sessionDir(sessionId);
            Files.createDirectories(dir);
            Path target = dir.resolve(DEFAULT_FILE_NAME);
            String hash = StateCheckpointCodec.writeAtomic(target, rawState);
            if (store != null) {
                try {
                    store.setStatePointer(sessionId, hash, rawState.length);
                } catch (RuntimeException e) {
                    LOG.warn("setStatePointer failed for {}: {}", sessionId, e.getMessage());
                }
            }
            evictIfNeeded();
            return hash;
        }
    }

    /**
     * Read the raw state for {@code sessionId} if the on-disk
     * file is present and its hash matches {@code expectedHash}
     * (when supplied). Returns {@link Optional#empty()} if the
     * file is missing, evicted, or hash-mismatched.
     */
    public Optional<byte[]> read(String sessionId, String expectedHash) {
        synchronized (lock) {
            Path target = sessionDir(sessionId).resolve(DEFAULT_FILE_NAME);
            if (!Files.exists(target)) return Optional.empty();
            if (expectedHash == null) {
                try { return Optional.of(StateCheckpointCodec.read(target)); }
                catch (IOException e) { return Optional.empty(); }
            }
            return StateCheckpointCodec.readIfHashMatches(target, expectedHash);
        }
    }

    /** Read without hash verification. Convenience for tests. */
    public Optional<byte[]> read(String sessionId) {
        return read(sessionId, null);
    }

    /** Delete the state file for {@code sessionId}. No-op if missing. */
    public boolean delete(String sessionId) {
        synchronized (lock) {
            Path target = sessionDir(sessionId).resolve(DEFAULT_FILE_NAME);
            try {
                boolean removed = Files.deleteIfExists(target);
                if (store != null) store.clearStatePointer(sessionId);
                return removed;
            } catch (IOException e) {
                return false;
            }
        }
    }

    /**
     * Force eviction to bring total size under
     * {@link #maxTotalBytes()}. Oldest (by mtime) are removed
     * first. Returns the number of files pruned.
     */
    public int evictIfNeeded() {
        synchronized (lock) {
            return pruneUnder(maxTotalBytes);
        }
    }

    /**
     * Force eviction down to {@code targetBytes}. Returns the
     * number of files pruned.
     */
    public int pruneUnder(long targetBytes) {
        if (targetBytes < 0) throw new IllegalArgumentException("targetBytes < 0");
        synchronized (lock) {
            List<Path> all = listStateFiles();
            long total = totalSize(all);
            if (total <= targetBytes) return 0;
            all.sort(Comparator.comparingLong(this::safeMtime));
            int pruned = 0;
            for (Path p : all) {
                if (total <= targetBytes) break;
                try {
                    long sz = Files.size(p);
                    Files.deleteIfExists(p);
                    total -= sz;
                    pruned++;
                    LOG.debug("pruned state file: {} ({} bytes)", p, sz);
                    // Also clear the corresponding hash row, if we have a store.
                    if (store != null) {
                        String sid = sessionIdFromPath(p);
                        if (sid != null) store.clearStatePointer(sid);
                    }
                } catch (IOException e) {
                    LOG.warn("prune failed for {}: {}", p, e.getMessage());
                }
            }
            return pruned;
        }
    }

    /** Total bytes across all on-disk state files. */
    public long totalBytes() {
        synchronized (lock) {
            return totalSize(listStateFiles());
        }
    }

    /** Number of state files on disk. */
    public int fileCount() {
        synchronized (lock) {
            return listStateFiles().size();
        }
    }

    /** Wipe all state files. For tests. */
    public void clear() throws IOException {
        synchronized (lock) {
            if (!Files.exists(root)) return;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
                for (Path p : ds) deleteRecursively(p);
            }
        }
    }

    // -- internals -------------------------------------------------------

    private Path sessionDir(String sessionId) {
        // Defensive: refuse ".." so a rogue sessionId cannot escape the root.
        if (sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            throw new IllegalArgumentException("invalid sessionId: " + sessionId);
        }
        return root.resolve(sessionId);
    }

    private List<Path> listStateFiles() {
        List<Path> out = new ArrayList<>();
        if (!Files.exists(root)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
            for (Path p : ds) {
                Path f = p.resolve(DEFAULT_FILE_NAME);
                if (Files.exists(f)) out.add(f);
            }
        } catch (IOException e) {
            LOG.warn("listStateFiles failed: {}", e.getMessage());
        }
        return out;
    }

    private static long totalSize(List<Path> files) {
        long total = 0;
        for (Path p : files) {
            try { total += Files.size(p); } catch (IOException ignored) { }
        }
        return total;
    }

    private long safeMtime(Path p) {
        try { return Files.getLastModifiedTime(p).toMillis(); }
        catch (IOException e) { return 0L; }
    }

    private String sessionIdFromPath(Path stateFile) {
        // stateFile = <root>/<sessionId>/state.bin
        Path parent = stateFile.getParent();
        if (parent == null) return null;
        return parent.getFileName().toString();
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (Files.isDirectory(p)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(p)) {
                for (Path c : ds) deleteRecursively(c);
            }
        }
        Files.deleteIfExists(p);
    }
}
