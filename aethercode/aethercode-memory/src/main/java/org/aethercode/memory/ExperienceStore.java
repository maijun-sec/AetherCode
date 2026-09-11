package org.aethercode.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.aethercode.core.config.SecureFilePermissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;

/**
 * R230 (G1): experience store. One JSON file per record on disk,
 * in-memory list cached behind a read-write lock.
 *
 * <p>Why not a single JSONL file? The existing {@link FileBackedMemory}
 * uses a single JSON file; experience records are larger (typically
 * 0.5–4 KB) and lower cardinality, so per-file storage is more
 * friendly to diff/inspect and the existing one-file-per-memory-file
 * pattern in the entrypoint layout.
 *
 * <p>Layout:
 * <pre>
 *   &lt;scopeRoot&gt;/experience/&lt;id&gt;.json
 * </pre>
 * One file per record. Each file is a self-contained JSON object
 * matching {@link ExperienceRecord}'s wire shape.
 *
 * <p>Sorting on recall: {@code utility desc, uses desc, createdAt desc}.
 */
public final class ExperienceStore {

    private static final Logger LOG = LoggerFactory.getLogger(ExperienceStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** Subdirectory under a scope root that holds experience files. */
    public static final String SUBDIR = "experience";

    private final Path root;
    private final Map<String, ExperienceRecord> cache = new LinkedHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private boolean dirty = false;

    public ExperienceStore(Path scopeRoot) {
        this.root = scopeRoot.resolve(SUBDIR);
        try { Files.createDirectories(this.root); } catch (IOException ignore) {}
        load();
    }

    public Path root() { return root; }
    public int size() {
        lock.readLock().lock();
        try { return cache.size(); }
        finally { lock.readLock().unlock(); }
    }

    public ExperienceRecord put(ExperienceRecord rec) {
        if (rec == null) throw new IllegalArgumentException("rec must not be null");
        lock.writeLock().lock();
        try {
            cache.put(rec.id(), rec);
            persist(rec);
            dirty = true;
            return rec;
        } finally { lock.writeLock().unlock(); }
    }

    /** Convenience builder. */
    public ExperienceRecord put(ExperienceKind kind, String title, String body,
                                String sourceSessionId, String sourceQuery,
                                String sourceOutcome, List<String> tags, List<String> links) {
        return put(new ExperienceRecord(
                UUID.randomUUID().toString(), kind, title, body, Instant.now(),
                sourceSessionId, sourceQuery, sourceOutcome, 0.5, 0,
                tags == null ? List.of() : tags,
                links == null ? List.of() : links
        ));
    }

    public Optional<ExperienceRecord> get(String id) {
        if (id == null) return Optional.empty();
        lock.readLock().lock();
        try { return Optional.ofNullable(cache.get(id)); }
        finally { lock.readLock().unlock(); }
    }

    public boolean remove(String id) {
        if (id == null) return false;
        lock.writeLock().lock();
        try {
            ExperienceRecord removed = cache.remove(id);
            if (removed == null) return false;
            try { Files.deleteIfExists(fileFor(id)); }
            catch (IOException e) { LOG.warn("experience delete failed: {}", e.getMessage()); }
            return true;
        } finally { lock.writeLock().unlock(); }
    }

    public List<ExperienceRecord> all() {
        lock.readLock().lock();
        try { return List.copyOf(cache.values()); }
        finally { lock.readLock().unlock(); }
    }

    /** Top-k experiences by utility desc → uses desc → createdAt desc. */
    public List<ExperienceRecord> topK(int k, List<String> tagFilter) {
        if (k <= 0) k = 3;
        lock.readLock().lock();
        try {
            Stream<ExperienceRecord> s = cache.values().stream();
            if (tagFilter != null && !tagFilter.isEmpty()) {
                s = s.filter(r -> r.tags().stream().anyMatch(tagFilter::contains));
            }
            return s
                    .sorted(Comparator
                            .comparingDouble(ExperienceRecord::utility).reversed()
                            .thenComparing(Comparator.comparingLong(ExperienceRecord::uses).reversed())
                            .thenComparing(Comparator.comparing(ExperienceRecord::createdAt).reversed()))
                    .limit(k)
                    .toList();
        } finally { lock.readLock().unlock(); }
    }

    /** Bump uses and lift utility. Returns the new record. */
    public ExperienceRecord recordUse(String id) {
        if (id == null) return null;
        lock.writeLock().lock();
        try {
            ExperienceRecord cur = cache.get(id);
            if (cur == null) return null;
            ExperienceRecord upd = cur.withUse();
            cache.put(id, upd);
            persist(upd);
            return upd;
        } finally { lock.writeLock().unlock(); }
    }

    public synchronized void flush() {
        // No-op: each put() persists its own file. We keep the signature
        // for symmetry with {@link FileBackedMemory#flush()}.
    }

    /** Recompute the in-memory cache by scanning the on-disk directory. */
    public void reload() {
        lock.writeLock().lock();
        try {
            cache.clear();
            load();
        } finally { lock.writeLock().unlock(); }
    }

    private void load() {
        if (!Files.isDirectory(root)) return;
        try (Stream<Path> stream = Files.list(root)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                String fn = p.getFileName().toString();
                if (!fn.endsWith(".json")) continue;
                try {
                    String json = Files.readString(p);
                    ExperienceRecord rec = MAPPER.readValue(json, ExperienceRecord.class);
                    cache.put(rec.id(), rec);
                } catch (Exception e) {
                    LOG.warn("experience load failed for {}: {}", p, e.getMessage());
                }
            }
        } catch (IOException e) {
            LOG.warn("experience dir list failed: {}", e.getMessage());
        }
    }

    private Path fileFor(String id) {
        return root.resolve(id + ".json");
    }

    private void persist(ExperienceRecord rec) {
        try {
            Path f = fileFor(rec.id());
            String json = MAPPER.writeValueAsString(rec);
            Files.writeString(f, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            SecureFilePermissions.applyOwnerReadWriteOnly(f);
        } catch (IOException e) {
            throw new RuntimeException("experience persist failed: " + e.getMessage(), e);
        }
    }

    /** Total record count by kind. */
    public Map<String, Long> statsByKind() {
        lock.readLock().lock();
        try {
            Map<String, Long> out = new LinkedHashMap<>();
            for (ExperienceRecord r : cache.values()) {
                out.merge(r.kind().wire(), 1L, Long::sum);
            }
            return out;
        } finally { lock.readLock().unlock(); }
    }
}
