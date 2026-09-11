package org.aethercode.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.aethercode.core.config.SecureFilePermissions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * a simple, file-backed memory store. Persists {@link MemoryItem}
 * records to a single JSON file under the project's memory directory.
 *
 * <p>This sits below the topic-file convention of {@link MemoryEntrypoint} —
 * it's a low-level utility used by tools that want to keep small
 * structured records (e.g. bookmarks, recent commands, todo items)
 * without rolling their own JSON marshalling.
 */
public class FileBackedMemory {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path file;
    private final List<MemoryItem> items = new ArrayList<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private boolean dirty = false;

    public FileBackedMemory(Path file) {
        this.file = file;
        load();
    }

    public Path file() { return file; }

    public synchronized MemoryItem add(String content, String scope, List<String> tags) {
        return add(content, scope, tags, Sensitivity.INTERNAL);
    }

    /** R230 (G10): add with explicit sensitivity. */
    public synchronized MemoryItem add(String content, String scope, List<String> tags, Sensitivity sensitivity) {
        Objects.requireNonNull(content, "content");
        MemoryItem item = new MemoryItem(
                UUID.randomUUID().toString(),
                content,
                scope == null ? "user" : scope,
                tags == null ? List.of() : List.copyOf(tags),
                Instant.now(),
                Instant.now(),
                sensitivity == null ? Sensitivity.INTERNAL : sensitivity,
                0L,
                Instant.now());
        lock.writeLock().lock();
        try {
            items.add(item);
            dirty = true;
            persist();
        } finally { lock.writeLock().unlock(); }
        return item;
    }

    /** R230 (G3): bump the access counter and last-accessed timestamp for an item. */
    public synchronized Optional<MemoryItem> touch(String id) {
        if (id == null) return Optional.empty();
        lock.writeLock().lock();
        try {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).id().equals(id)) {
                    MemoryItem cur = items.get(i);
                    MemoryItem upd = new MemoryItem(
                            cur.id(), cur.content(), cur.scope(), cur.tags(),
                            cur.createdAt(), Instant.now(),
                            cur.sensitivity(), cur.accessCount() + 1, Instant.now());
                    items.set(i, upd);
                    persist();
                    return Optional.of(upd);
                }
            }
            return Optional.empty();
        } finally { lock.writeLock().unlock(); }
    }

    /** R230 (G10): set the sensitivity label on an existing item. */
    public synchronized Optional<MemoryItem> setSensitivity(String id, Sensitivity sensitivity) {
        if (id == null || sensitivity == null) return Optional.empty();
        lock.writeLock().lock();
        try {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).id().equals(id)) {
                    MemoryItem cur = items.get(i);
                    MemoryItem upd = new MemoryItem(
                            cur.id(), cur.content(), cur.scope(), cur.tags(),
                            cur.createdAt(), Instant.now(),
                            sensitivity, cur.accessCount(), cur.lastAccessedAt());
                    items.set(i, upd);
                    persist();
                    return Optional.of(upd);
                }
            }
            return Optional.empty();
        } finally { lock.writeLock().unlock(); }
    }

    public synchronized boolean remove(String id) {
        lock.writeLock().lock();
        try {
            boolean removed = items.removeIf(i -> i.id().equals(id));
            if (removed) persist();
            return removed;
        } finally { lock.writeLock().unlock(); }
    }

    public Optional<MemoryItem> get(String id) {
        lock.readLock().lock();
        try {
            for (MemoryItem i : items) if (i.id().equals(id)) return Optional.of(i);
            return Optional.empty();
        } finally { lock.readLock().unlock(); }
    }

    public List<MemoryItem> all() {
        lock.readLock().lock();
        try { return List.copyOf(items); }
        finally { lock.readLock().unlock(); }
    }

    public List<MemoryItem> byScope(String scope) {
        if (scope == null) return all();
        lock.readLock().lock();
        try {
            return items.stream().filter(i -> i.scope().equals(scope)).collect(Collectors.toList());
        } finally { lock.readLock().unlock(); }
    }

    public List<MemoryItem> search(String query) {
        if (query == null || query.isBlank()) return all();
        String q = query.toLowerCase();
        lock.readLock().lock();
        try {
            return items.stream()
                    .filter(i -> i.content().toLowerCase().contains(q)
                            || i.tags().stream().anyMatch(t -> t.toLowerCase().contains(q)))
                    .collect(Collectors.toList());
        } finally { lock.readLock().unlock(); }
    }

    public synchronized boolean update(String id, String content, List<String> tags) {
        lock.writeLock().lock();
        try {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).id().equals(id)) {
                    MemoryItem cur = items.get(i);
                    MemoryItem upd = new MemoryItem(
                            cur.id(),
                            content == null ? cur.content() : content,
                            cur.scope(),
                            tags == null ? cur.tags() : List.copyOf(tags),
                            cur.createdAt(),
                            Instant.now(),
                            cur.sensitivity(),
                            cur.accessCount(),
                            Instant.now());
                    items.set(i, upd);
                    persist();
                    return true;
                }
            }
            return false;
        } finally { lock.writeLock().unlock(); }
    }

    public int size() {
        lock.readLock().lock();
        try { return items.size(); }
        finally { lock.readLock().unlock(); }
    }

    public synchronized void clear() {
        lock.writeLock().lock();
        try {
            items.clear();
            persist();
        } finally { lock.writeLock().unlock(); }
    }

    public synchronized void flush() {
        if (dirty) persist();
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            String json = Files.readString(file);
            if (json.isBlank()) return;
            List<MemoryItem> parsed = MAPPER.readValue(json, new TypeReference<>() {});
            items.addAll(parsed);
        } catch (IOException e) {
            // corrupt file: start fresh but keep the original on disk under .bak
            try {
                Files.move(file, file.resolveSibling(file.getFileName() + ".bak"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {}
            items.clear();
        }
    }

    private void persist() {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            String json = MAPPER.writeValueAsString(items);
            Files.writeString(file, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            // T-507 / design.md §7: 0600 on every persisted
            // memory file. The helper is a no-op on Windows
            // and best-effort on POSIX (logs a warn on
            // failure but never throws — the data is
            // already on disk by this point).
            SecureFilePermissions.applyOwnerReadWriteOnly(file);
            dirty = false;
        } catch (IOException e) {
            // bubble up — caller can decide
            throw new RuntimeException("failed to persist memory: " + e.getMessage(), e);
        }
    }

    public record MemoryItem(
            String id,
            String content,
            String scope,
            List<String> tags,
            Instant createdAt,
            Instant updatedAt,
            Sensitivity sensitivity,
            long accessCount,
            Instant lastAccessedAt
    ) {
        public MemoryItem {
            Objects.requireNonNull(content, "content");
            if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
            if (scope == null) scope = "user";
            if (tags == null) tags = List.of();
            else tags = List.copyOf(tags);
            if (createdAt == null) createdAt = Instant.now();
            if (updatedAt == null) updatedAt = createdAt;
            if (sensitivity == null) sensitivity = Sensitivity.INTERNAL;
            if (accessCount < 0) accessCount = 0;
            if (lastAccessedAt == null) lastAccessedAt = updatedAt == null ? createdAt : updatedAt;
        }

        // 6-arg compatibility constructor for callers that
        // pre-date the sensitivity / accessCount / lastAccessedAt
        // fields. Marks the entry INTERNAL and accessCount=0.
        public MemoryItem(String id, String content, String scope,
                          List<String> tags, Instant createdAt, Instant updatedAt) {
            this(id, content, scope, tags, createdAt, updatedAt,
                    Sensitivity.INTERNAL, 0L, updatedAt);
        }
    }

    /** returns a snapshot of statistics. */
    public Map<String, Object> stats() {
        lock.readLock().lock();
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("count", items.size());
            Map<String, Integer> byScope = new LinkedHashMap<>();
            for (MemoryItem i : items) {
                byScope.merge(i.scope(), 1, Integer::sum);
            }
            out.put("byScope", byScope);
            return Collections.unmodifiableMap(out);
        } finally { lock.readLock().unlock(); }
    }
}
