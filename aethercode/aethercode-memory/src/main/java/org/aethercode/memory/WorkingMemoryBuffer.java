package org.aethercode.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * R230 (G4 skeleton): explicit working-memory layer.
 *
 * <p>Mirrors arXiv:2512.13564 §4.3 — "the set of mechanisms for the
 * <i>active management and manipulation</i> of context within a
 * single episode". In R230 we ship only the in-memory buffer + the
 * API surface; wiring it into {@code QueryEngine} (so the model can
 * actually {@code put}/{@code get} mid-turn) is R231.
 *
 * <p>Lifecycle: bound to a single {@code (sessionId, queryId)} tuple.
 * The engine constructs a buffer at the start of a query, hands it
 * to the tool pool, and clears it on completion. R230 ships a
 * stand-alone buffer so unit tests can exercise the API; integration
 * is the next round.
 *
 * <p>Capacity: the buffer is in-memory only and bounded by
 * {@link #DEFAULT_MAX_ENTRIES}. When full, {@link #put} evicts the
 * least-recently-touched entry (LRU) and logs a warning. In R231
 * the engine will also enforce a token budget.
 */
public final class WorkingMemoryBuffer {

    private static final Logger LOG = LoggerFactory.getLogger(WorkingMemoryBuffer.class);

    /** Default cap. 50 small entries ≈ 2-5k tokens; well under any context window. */
    public static final int DEFAULT_MAX_ENTRIES = 50;

    /** Kind of an entry — used for the recall render and for the engine to pick formatting. */
    public enum Kind {
        TEXT,           // arbitrary short text
        KEY_VALUE,      // JSON-like {k:v, k:v}
        REFERENCE,      // pointer to a memory item (id, scope, key)
        PLAN_STEP,      // one bullet in the working plan
        EVIDENCE,       // a fact/snip that the model is "thinking with"
        TODO;           // an open todo

        public String wire() { return name().toLowerCase(); }
    }

    /** One entry in the buffer. */
    public record Entry(
            String id,
            Kind kind,
            String content,
            Map<String, String> meta,
            Instant createdAt,
            Instant lastTouchedAt
    ) {
        public Entry {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(content, "content");
            if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
            if (meta == null) meta = Map.of();
            else meta = Map.copyOf(meta);
            if (createdAt == null) createdAt = Instant.now();
            if (lastTouchedAt == null) lastTouchedAt = createdAt;
        }
    }

    /** Snapshot of the buffer for cross-thread hand-off. */
    public record Snapshot(List<Entry> entries, int capacity, int size) {
        public Snapshot {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

    private final String sessionId;
    private final String queryId;
    private final int maxEntries;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Instant createdAt = Instant.now();

    public WorkingMemoryBuffer(String sessionId, String queryId) {
        this(sessionId, queryId, DEFAULT_MAX_ENTRIES);
    }

    public WorkingMemoryBuffer(String sessionId, String queryId, int maxEntries) {
        this.sessionId = sessionId;
        this.queryId = queryId;
        this.maxEntries = Math.max(1, maxEntries);
    }

    public String sessionId() { return sessionId; }
    public String queryId() { return queryId; }
    public int maxEntries() { return maxEntries; }
    public Instant createdAt() { return createdAt; }

    public Entry put(Kind kind, String content) {
        return put(kind, content, null);
    }

    public Entry put(Kind kind, String content, Map<String, String> meta) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(content, "content");
        lock.lock();
        try {
            if (entries.size() >= maxEntries) {
                // Evict the first (oldest) — LinkedHashMap iteration is insertion order.
                String firstKey = entries.keySet().iterator().next();
                entries.remove(firstKey);
                LOG.warn("working memory full ({}), evicted oldest entry", maxEntries);
            }
            Entry e = new Entry(UUID.randomUUID().toString(), kind, content,
                    meta, Instant.now(), Instant.now());
            entries.put(e.id(), e);
            return e;
        } finally { lock.unlock(); }
    }

    public Optional<Entry> get(String id) {
        if (id == null) return Optional.empty();
        lock.lock();
        try {
            Entry e = entries.get(id);
            if (e == null) return Optional.empty();
            // Touch — move to end of LRU order
            entries.remove(id);
            Entry touched = new Entry(e.id(), e.kind(), e.content(), e.meta(),
                    e.createdAt(), Instant.now());
            entries.put(id, touched);
            return Optional.of(touched);
        } finally { lock.unlock(); }
    }

    public List<Entry> list() {
        lock.lock();
        try { return List.copyOf(entries.values()); }
        finally { lock.unlock(); }
    }

    public int size() {
        lock.lock();
        try { return entries.size(); }
        finally { lock.unlock(); }
    }

    public boolean remove(String id) {
        if (id == null) return false;
        lock.lock();
        try { return entries.remove(id) != null; }
        finally { lock.unlock(); }
    }

    public void clear() {
        lock.lock();
        try { entries.clear(); }
        finally { lock.unlock(); }
    }

    public Snapshot snapshot() {
        lock.lock();
        try { return new Snapshot(List.copyOf(entries.values()), maxEntries, entries.size()); }
        finally { lock.unlock(); }
    }

    /** Restore from a snapshot. Used when the engine hand-offs across tool calls. */
    public void restore(Snapshot snap) {
        if (snap == null) return;
        lock.lock();
        try {
            entries.clear();
            for (Entry e : snap.entries()) entries.put(e.id(), e);
        } finally { lock.unlock(); }
    }

    /** Render the buffer as a markdown section the model can read. */
    public String render() {
        lock.lock();
        try {
            if (entries.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            sb.append("## Working memory (active scratchpad)\n");
            int i = 1;
            for (Entry e : entries.values()) {
                sb.append(i++).append(". [").append(e.kind().wire()).append("] ");
                sb.append(e.content().strip()).append('\n');
            }
            return sb.toString();
        } finally { lock.unlock(); }
    }
}
