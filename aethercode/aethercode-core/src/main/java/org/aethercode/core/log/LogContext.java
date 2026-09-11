package org.aethercode.core.log;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * per-thread logging context. Wraps {@link ThreadLocal} so
 * the {@link JsonlLogger} (prior round) can attach {@code session_id},
 * {@code tool_name}, etc. to every log line emitted on this thread.
 *
 * <p>Use {@link #with(String, Object)} to temporarily set a value:
 * <pre>{@code
 *   try (var ignored = LogContext.with("tool", "Bash")) {
 *       logger.log("running command");
 *   }
 * }</pre>
 */
public final class LogContext {

    private static final ThreadLocal<Map<String, Object>> CTX = new ThreadLocal<>();
    private static final AtomicLong DEPTH = new AtomicLong();

    private LogContext() {}

    /** push a key/value pair and return a handle that pops it on close. */
    public static Scope with(String key, Object value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        Map<String, Object> map = CTX.get();
        if (map == null) { map = new LinkedHashMap<>(); CTX.set(map); }
        Object prev = map.put(key, value);
        DEPTH.incrementAndGet();
        return new Scope(key, prev);
    }

    /** snapshot of the current thread's context (read-only). */
    public static Map<String, Object> snapshot() {
        Map<String, Object> map = CTX.get();
        if (map == null) return Map.of();
        return Map.copyOf(map);
    }

    /** clear the current thread's context. */
    public static void clear() {
        CTX.remove();
    }

    /** total number of active {@code with()} scopes (across all threads). */
    public static long activeScopeCount() { return DEPTH.get(); }

    /** handle returned by {@link #with}. */
    public static class Scope implements AutoCloseable {
        private final String key;
        private final Object prev;
        private boolean closed = false;
        Scope(String key, Object prev) { this.key = key; this.prev = prev; }
        @Override public void close() {
            if (closed) return;
            closed = true;
            Map<String, Object> map = CTX.get();
            if (map != null) {
                if (prev == null) map.remove(key);
                else map.put(key, prev);
            }
            DEPTH.decrementAndGet();
        }
    }
}
