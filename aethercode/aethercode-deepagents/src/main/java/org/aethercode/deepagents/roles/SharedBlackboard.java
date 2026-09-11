package org.aethercode.deepagents.roles;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * prior round.2 (O-9): a small key-value store that multiple roles
 * share. After a planner decomposes the goal, the coder reads
 * the plan from the blackboard; after the coder finishes, the
 * reviewer reads the diff summary. The blackboard is the
 * "shared state" mirror of the LangGraph / CrewAI / AutoGen
 * inter-agent memory pattern (Paper 6 §IV, Paper 8 §3.2).
 *
 * <h2>Shape</h2>
 *
 * <p>{@code Map<String, Entry>} under a thread-safe wrapper.
 * Each entry has a {@link Role#name() role tag} so a consumer
 * can tell who wrote what, and a monotonically increasing
 * sequence number so the caller can ask for "everything
 * written since #N" (used by the reviewer when it only wants
 * the coder's recent output).
 *
 * <h2>Why not just use the agent state?</h2>
 *
 * <p>The deep-agent {@code AgentState} is per-run and is
 * serialised into the model's context window on every
 * invocation. The blackboard is meant for
 * <em>cross-role</em> communication; it lives outside the
 * model context, so the planner's verbose plan does not
 * inflate the coder's prompt unless the coder explicitly
 * reads it.
 *
 * <h2>Thread safety</h2>
 *
 * <p>{@link #put} is racy-but-safe: two writers may end up
 * with the same sequence number, but every {@code put} is
 * preserved. Callers that need strict ordering should chain
 * their writes through the {@link #withLock(Runnable)} helper.
 */
public final class SharedBlackboard {

    private static final Logger LOG = LoggerFactory.getLogger(SharedBlackboard.class);

    /**
     * One entry on the blackboard. {@code seq} is monotonic
     * per-blackboard; {@code role} identifies the writer.
     */
    public record Entry(String key, Object value, String role, long seq, long atMs) {
        public Entry {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(role, "role");
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key);
            m.put("value", value);
            m.put("role", role);
            m.put("seq", seq);
            m.put("atMs", atMs);
            return m;
        }
    }

    private final String name;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicLong seqCounter = new AtomicLong(0L);
    private final List<SharedBlackboardListener> listeners = new CopyOnWriteArrayList<>();

    public SharedBlackboard() { this("default"); }

    public SharedBlackboard(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    public String name() { return name; }

    /** Put a value on the blackboard. Overwrites any prior
     *  value for {@code key}. The {@code role} argument is
     *  the writer's identifier (usually {@link Role#name()}). */
    public Entry put(String key, Object value, String role) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(role, "role");
        long seq = seqCounter.incrementAndGet();
        Entry e = new Entry(key, value, role, seq, System.currentTimeMillis());
        Entry prev = entries.put(key, e);
        fireWrite(e, prev);
        return e;
    }

    /** Convenience overload that infers the role name from
     *  the {@link Role} record. */
    public Entry put(String key, Object value, Role role) {
        return put(key, value, role.name());
    }

    public Optional<Entry> get(String key) {
        return Optional.ofNullable(entries.get(key));
    }

    public Object require(String key) {
        Entry e = entries.get(key);
        if (e == null) {
            throw new IllegalStateException("missing blackboard key: " + key);
        }
        return e.value();
    }

    public boolean contains(String key) {
        return entries.containsKey(key);
    }

    public int size() {
        return entries.size();
    }

    public Map<String, Entry> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    /** All entries with {@code seq > sinceSeq}, in seq order.
     *  Used by the reviewer / executor to ask "what changed
     *  since I last looked?". */
    public List<Entry> since(long sinceSeq) {
        return entries.values().stream()
                .filter(e -> e.seq() > sinceSeq)
                .sorted((a, b) -> Long.compare(a.seq(), b.seq()))
                .toList();
    }

    /** Run {@code action} with the blackboard locked; the
     *  lock is in-process and advisory (we do not actually
     *  block other threads from {@link #put} — we just
     *  guarantee a happens-before edge for the caller). */
    public void withLock(Runnable action) {
        synchronized (this) {
            action.run();
        }
    }

    /** Clear all entries. Test seam; not used by the runtime. */
    public void clear() {
        long lastSeq = seqCounter.get();
        entries.clear();
        seqCounter.set(0L);
        LOG.debug("blackboard '{}' cleared at seq={}", name, lastSeq);
    }

    // -----------------------------------------------------------------
    //  Listeners
    // -----------------------------------------------------------------

    public interface SharedBlackboardListener {
        default void onWrite(Entry written, Entry previous) {}
    }

    public void addListener(SharedBlackboardListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public boolean removeListener(SharedBlackboardListener listener) {
        return listeners.remove(listener);
    }

    private void fireWrite(Entry e, Entry prev) {
        for (SharedBlackboardListener l : listeners) {
            try { l.onWrite(e, prev); }
            catch (RuntimeException re) { /* listener bugs must not corrupt */ }
        }
    }
}
