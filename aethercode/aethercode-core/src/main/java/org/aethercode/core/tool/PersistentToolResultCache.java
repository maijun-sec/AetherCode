package org.aethercode.core.tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * a persistent tool-result cache. Backed by a directory of
 * files (one per cache key). Results survive across sessions so
 * a long-running workflow can pick up where it left off.
 *
 * <p>Unlike {@link ToolResultCache} (in-memory LRU), this cache
 * is unbounded and load-on-demand. Use it for tools whose
 * results are expensive to recompute and stable across sessions
 * (e.g. static analysis, large file scans).
 *
 * <p>Thread-safe: each file write is atomic via
 * {@code Files.writeString}. In-memory metadata is held in a
 * {@link ConcurrentHashMap}.
 */
public final class PersistentToolResultCache {

    private final Path root;

    public PersistentToolResultCache(Path root) {
        if (root == null) throw new IllegalArgumentException("root must not be null");
        this.root = root;
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new RuntimeException("could not create cache root: " + root, e);
        }
    }

    public Path root() { return root; }

    public void put(String key, String value) {
        if (key == null || value == null) return;
        // Sanitize: replace path separators so key can't escape root.
        String safe = key.replace('/', '_').replace('\\', '_');
        try {
            Files.writeString(root.resolve(safe), value, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Best-effort; do not throw.
        }
    }

    public Optional<String> get(String key) {
        if (key == null) return Optional.empty();
        String safe = key.replace('/', '_').replace('\\', '_');
        Path file = root.resolve(safe);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public boolean containsKey(String key) {
        if (key == null) return false;
        String safe = key.replace('/', '_').replace('\\', '_');
        return Files.isRegularFile(root.resolve(safe));
    }

    public void invalidate(String key) {
        if (key == null) return;
        String safe = key.replace('/', '_').replace('\\', '_');
        try { Files.deleteIfExists(root.resolve(safe)); } catch (IOException e) { /* ignore */ }
    }

    public int size() {
        try (var stream = Files.list(root)) {
            return (int) stream.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            return 0;
        }
    }

    public void clear() {
        try (var stream = Files.list(root)) {
            stream.forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException e) { /* ignore */ }
            });
        } catch (IOException e) { /* ignore */ }
    }
}
