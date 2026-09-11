package org.aethercode.tasks.engine.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;

/**
 * Lightweight in-memory registry of running sessions and their
 * last-activity timestamps. The {@link guards.WallClockGuard} and
 * {@link guards.IdleGuard} read from this; the supervisor's main
 * loop calls {@link #recordActivity(String, long)} on every LLM
 * call (so {@code IdleGuard} can compare against it).
 *
 * <p>Thread-safety: backed by a {@link CopyOnWriteArrayList} of
 * active sessions; per-session activity times use a synchronized
 * map. The registry is intentionally small — the supervisor
 * typically handles 5-50 active sessions at once.
 */
public final class SessionRegistry {

    private final List<String> active = new CopyOnWriteArrayList<>();
    private final Map<String, Long> lastActivity = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Long> startedAt = new java.util.concurrent.ConcurrentHashMap<>();
    private final LongSupplier clock;

    public SessionRegistry() { this(System::currentTimeMillis); }
    public SessionRegistry(LongSupplier clock) { this.clock = Objects.requireNonNull(clock, "clock"); }

    public void register(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        if (!active.contains(sessionId)) active.add(sessionId);
        long now = clock.getAsLong();
        lastActivity.put(sessionId, now);
        startedAt.putIfAbsent(sessionId, now);
    }

    public void unregister(String sessionId) {
        active.remove(sessionId);
        lastActivity.remove(sessionId);
        startedAt.remove(sessionId);
    }

    /** Mark that an LLM call just happened for {@code sessionId}.
     *  Updates the activity timestamp; this is what {@code IdleGuard}
     *  compares against. */
    public void recordActivity(String sessionId) {
        if (sessionId == null) return;
        lastActivity.put(sessionId, clock.getAsLong());
    }

    public void recordActivity(String sessionId, long tsMs) {
        if (sessionId == null) return;
        lastActivity.put(sessionId, tsMs);
    }

    public Optional<Long> lastActivityMs(String sessionId) {
        Long v = lastActivity.get(sessionId);
        return Optional.ofNullable(v);
    }

    public Optional<Long> startedAtMs(String sessionId) {
        Long v = startedAt.get(sessionId);
        return Optional.ofNullable(v);
    }

    public List<String> activeSessions() { return List.copyOf(active); }

    public int activeCount() { return active.size(); }

    public void clear() {
        active.clear();
        lastActivity.clear();
        startedAt.clear();
    }

    /** Bulk snapshot (used in tests and JSON-RPC replies). */
    public Map<String, SessionSnapshot> snapshot() {
        Map<String, SessionSnapshot> m = new LinkedHashMap<>();
        for (String id : active) {
            m.put(id, new SessionSnapshot(id,
                    startedAt.getOrDefault(id, 0L),
                    lastActivity.getOrDefault(id, 0L)));
        }
        return m;
    }

    public record SessionSnapshot(String sessionId, long startedAtMs, long lastActivityMs) { }
}
