package org.aethercode.core.compact;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.aethercode.core.message.Message;

/**
 * a tiny, on-disk store of compact snapshots. Each snapshot
 * is a {@code (id, createdAt, label, originalMessageCount, summary,
 * messageList)} record persisted as a single JSON file. The
 * summarisation itself is left to the caller — this class only
 * handles persistence and listing.
 */
public class SnapshotStore {

    public record Snapshot(
            String id,
            String label,
            Instant createdAt,
            int originalMessageCount,
            int keptMessageCount,
            String summary,
            List<Message> messages
    ) {
        public Snapshot {
            if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
            if (label == null) label = "";
            if (createdAt == null) createdAt = Instant.now();
            if (summary == null) summary = "";
            if (messages == null) messages = List.of();
            messages = List.copyOf(messages);
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private final Path dir;
    private final Map<String, Snapshot> cache = new ConcurrentHashMap<>();
    private final AtomicLong totalBytes = new AtomicLong();
    private boolean loaded = false;

    public SnapshotStore(Path dir) {
        this.dir = dir;
    }

    public Path dir() { return dir; }

    public synchronized Snapshot save(String label, int originalCount, String summary, List<Message> messages) {
        ensureLoaded();
        Snapshot s = new Snapshot(null, label, null, originalCount,
                messages == null ? 0 : messages.size(),
                summary, messages);
        cache.put(s.id(), s);
        persist(s);
        return s;
    }

    public synchronized Optional<Snapshot> get(String id) {
        ensureLoaded();
        return Optional.ofNullable(cache.get(id));
    }

    public synchronized List<Snapshot> all() {
        ensureLoaded();
        return List.copyOf(cache.values());
    }

    public synchronized List<Snapshot> byLabel(String label) {
        ensureLoaded();
        return cache.values().stream()
                .filter(s -> s.label().equals(label))
                .toList();
    }

    public synchronized boolean delete(String id) {
        ensureLoaded();
        Snapshot removed = cache.remove(id);
        if (removed != null) {
            try { Files.deleteIfExists(pathFor(id)); }
            catch (IOException e) { return false; }
            return true;
        }
        return false;
    }

    public int size() { return cache.size(); }

    public long totalBytes() { return totalBytes.get(); }

    public synchronized void clear() {
        for (String id : cache.keySet()) {
            try { Files.deleteIfExists(pathFor(id)); }
            catch (IOException ignored) {}
        }
        cache.clear();
        totalBytes.set(0);
    }

    /** rebuild the cache by re-reading all snapshot files. */
    public synchronized void reload() {
        cache.clear();
        totalBytes.set(0);
        loaded = false;
        ensureLoaded();
    }

    private void ensureLoaded() {
        if (loaded) return;
        try {
            if (!Files.exists(dir)) Files.createDirectories(dir);
            try (var stream = Files.list(dir)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                      .forEach(p -> {
                          try {
                              Snapshot s = MAPPER.readValue(p.toFile(), Snapshot.class);
                              cache.put(s.id(), s);
                              totalBytes.addAndGet(Files.size(p));
                          } catch (Exception e) {
                              // skip corrupt
                          }
                      });
            }
        } catch (IOException e) {
            // best-effort
        }
        loaded = true;
    }

    private void persist(Snapshot s) {
        try {
            if (!Files.exists(dir)) Files.createDirectories(dir);
            Path f = pathFor(s.id());
            String json = MAPPER.writeValueAsString(s);
            Files.writeString(f, json);
            totalBytes.addAndGet(json.getBytes().length);
        } catch (IOException e) {
            // best-effort
        }
    }

    private Path pathFor(String id) {
        return dir.resolve(id + ".json");
    }
}
