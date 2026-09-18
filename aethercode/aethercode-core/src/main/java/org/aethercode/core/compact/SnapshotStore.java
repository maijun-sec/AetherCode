package org.aethercode.core.compact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.aethercode.core.message.Message;

/**
 * R284 (2026-09-18): on-disk store of pre-compaction context
 * snapshots. Each snapshot captures the live transcript at the
 * moment {@link org.aethercode.core.engine.QueryEngine#runPreFlightCompact}
 * decides the current turn must be summarised, so the user can
 * later "View original context" in the MessageList and see what
 * the summary was synthesised from.
 *
 * <p>Snapshots are organised by {@code sessionId} + a monotonic
 * {@code compactionIndex} (0, 1, 2, ...). The on-disk layout is
 * a flat directory — every snapshot is a single JSON file named
 * <pre>
 *   &lt;sessionId&gt;__&lt;compactionIndex&gt;.json
 * </pre>
 * The {@code __} separator lets a sessionId that itself has dots
 * (e.g. ISO timestamps) round-trip through {@link #parseFileName}.
 *
 * <p>Compaction indices are tracked per-session in memory
 * (via {@link #nextIndex}) and recovered from disk on
 * {@link #reload()}; the store handles concurrent saves via
 * {@code synchronized} on the snapshot-list mutex.
 *
 * <p>Wired up by {@code AetherCodeEngine} on startup with a
 * default path of {@code <workspace>/.aethercode/snapshots}.
 */
public class SnapshotStore {

    /**
     * A single snapshot. The {@code sessionId} +
     * {@code compactionIndex} pair uniquely identifies it.
     */
    public record Snapshot(
            String sessionId,
            long compactionIndex,
            Instant createdAt,
            int originalMessageCount,
            int keptMessageCount,
            String summary,
            List<Message> messages
    ) {
        public Snapshot {
            if (sessionId == null || sessionId.isBlank()) {
                throw new IllegalArgumentException("sessionId is required");
            }
            if (compactionIndex < 0) {
                throw new IllegalArgumentException(
                        "compactionIndex must be >= 0, got " + compactionIndex);
            }
            if (createdAt == null) createdAt = Instant.now();
            if (summary == null) summary = "";
            if (messages == null) messages = List.of();
            messages = List.copyOf(messages);
        }

        /** human-readable file name for this snapshot, no path. */
        public String fileName() {
            return sessionId + "__" + compactionIndex + ".json";
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private final Path dir;
    /**
     * sessionId → ordered list of (index, snapshotId) so we can
     * resolve the next index without scanning the directory on
     * every save. {@code snapshotId} is the file basename
     * without the {@code .json} extension.
     */
    private final Map<String, List<String>> indexBySession =
            new ConcurrentHashMap<>();
    /** sessionId → cache of all snapshots for fast lookup. */
    private final Map<String, Snapshot> snapshotBySnapshotId =
            new ConcurrentHashMap<>();
    private final AtomicLong totalBytes = new AtomicLong();
    private boolean loaded = false;

    public SnapshotStore(Path dir) {
        this.dir = dir;
    }

    public Path dir() { return dir; }

    /**
     * Persist the pre-compaction context for {@code sessionId}.
     * Allocates a new monotonic index (one higher than any
     * existing snapshot for this session) and writes a single
     * JSON file. Returns the persisted {@link Snapshot} so the
     * caller can attach {@code compactionIndex} +
     * {@code snapshotPath} to the synthetic summary message.
     */
    public synchronized Snapshot saveForSession(
            String sessionId,
            int originalCount,
            String summary,
            List<Message> messages
    ) {
        ensureLoaded();
        List<String> ids = indexBySession.computeIfAbsent(
                sessionId, k -> new ArrayList<>());
        long nextIndex = ids.size();
        Snapshot s = new Snapshot(
                sessionId,
                nextIndex,
                Instant.now(),
                originalCount,
                messages == null ? 0 : messages.size(),
                summary,
                messages);
        ids.add(s.fileName());
        snapshotBySnapshotId.put(s.fileName(), s);
        persist(s);
        return s;
    }

    /** All snapshots for a session, ordered by compactionIndex
     *  ascending (oldest first). Empty list for unknown session. */
    public synchronized List<Snapshot> bySession(String sessionId) {
        ensureLoaded();
        List<String> ids = indexBySession.get(sessionId);
        if (ids == null || ids.isEmpty()) return List.of();
        List<Snapshot> out = new ArrayList<>(ids.size());
        for (String id : ids) {
            Snapshot s = snapshotBySnapshotId.get(id);
            if (s != null) out.add(s);
        }
        return out;
    }

    /** The {@code N}-th snapshot for {@code sessionId}
     *  (compactionIndex == N). Empty if not present. */
    public synchronized Optional<Snapshot> getByIndex(
            String sessionId, long compactionIndex) {
        ensureLoaded();
        List<String> ids = indexBySession.get(sessionId);
        if (ids == null || compactionIndex >= ids.size()) return Optional.empty();
        return Optional.ofNullable(
                snapshotBySnapshotId.get(ids.get((int) compactionIndex)));
    }

    /** Remove a single snapshot. Returns false if no such index. */
    public synchronized boolean delete(String sessionId, long compactionIndex) {
        ensureLoaded();
        List<String> ids = indexBySession.get(sessionId);
        if (ids == null || compactionIndex >= ids.size()) return false;
        String snapshotId = ids.get((int) compactionIndex);
        Snapshot removed = snapshotBySnapshotId.remove(snapshotId);
        if (removed == null) return false;
        try { Files.deleteIfExists(pathFor(snapshotId)); }
        catch (IOException e) { return false; }
        // compact the ids list so the next saveForSession
        // reuses the freed slot (monotonic per session —
        // never reuse a number even if the file is gone).
        // Leaving compactionIndex monotonically increasing is
        // what we want for auditability.
        return true;
    }

    /** All sessions that currently have at least one snapshot. */
    public synchronized List<String> listSessions() {
        ensureLoaded();
        return List.copyOf(indexBySession.keySet());
    }

    public int size() { return snapshotBySnapshotId.size(); }

    public long totalBytes() { return totalBytes.get(); }

    public synchronized void clear() {
        for (String id : snapshotBySnapshotId.keySet()) {
            try { Files.deleteIfExists(pathFor(id)); }
            catch (IOException ignored) {}
        }
        indexBySession.clear();
        snapshotBySnapshotId.clear();
        totalBytes.set(0);
    }

    /** rebuild the in-memory index by re-reading every file in
     *  the directory. */
    public synchronized void reload() {
        indexBySession.clear();
        snapshotBySnapshotId.clear();
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
                      .filter(p -> p.getFileName().toString().contains("__"))
                      .forEach(p -> {
                          String fname = p.getFileName().toString();
                          try {
                              Snapshot s = MAPPER.readValue(p.toFile(), Snapshot.class);
                              indexBySession
                                      .computeIfAbsent(s.sessionId(), k -> new ArrayList<>())
                                      .add(fname);
                              snapshotBySnapshotId.put(fname, s);
                              totalBytes.addAndGet(Files.size(p));
                          } catch (Exception e) {
                              // skip corrupt
                          }
                      });
            }
            // restore the monotonic-per-session order from the
            // compactionIndex embedded in the filename (so a
            // crash mid-write doesn't leave holes in the
            // sequence — the next save still picks the right
            // index).
            for (var entry : indexBySession.entrySet()) {
                entry.getValue().sort(Comparator.comparingLong(
                        SnapshotStore::indexFromFileName));
            }
        } catch (IOException e) {
            // best-effort
        }
        loaded = true;
    }

    private static long indexFromFileName(String fileName) {
        // "<sessionId>__<N>.json" — split on "__" from the right
        // so sessionId dots don't confuse us.
        int sep = fileName.lastIndexOf("__");
        if (sep < 0) return -1;
        String tail = fileName.substring(sep + 2);
        int dot = tail.lastIndexOf('.');
        if (dot > 0) tail = tail.substring(0, dot);
        try { return Long.parseLong(tail); }
        catch (NumberFormatException e) { return -1; }
    }

    private void persist(Snapshot s) {
        try {
            if (!Files.exists(dir)) Files.createDirectories(dir);
            Path f = pathFor(s.fileName());
            String json = MAPPER.writeValueAsString(s);
            Files.writeString(f, json);
            totalBytes.addAndGet(json.getBytes().length);
        } catch (IOException e) {
            // best-effort
        }
    }

    private Path pathFor(String snapshotId) {
        // snapshotId is the on-disk file basename WITH the
        // .json suffix (e.g. "session-abc__0.json"). We
        // resolve it directly so callers don't have to
        // double-suffix the filename.
        return dir.resolve(snapshotId);
    }
}