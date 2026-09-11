package org.aethercode.core.tool;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * an LRU + TTL cache for tool results. Avoids re-running
 * expensive tools (e.g. a long {@code bash} command) when the
 * same call is made repeatedly during a session. Two-tier
 * eviction:
 * <ul>
 *   <li><b>LRU</b>: when the cache exceeds {@code maxSize}, the
 *       least-recently-accessed entry is dropped first.</li>
 *   <li><b>TTL</b>: every entry expires after {@code ttlMs}
 *       milliseconds from the time it was stored. Expired entries
 *       are filtered on read; they are not eagerly removed.</li>
 * </ul>
 *
 * <p>Thread-safe: all operations are guarded by a single monitor
 * so the LRU invariant is preserved under concurrent access. The
 * cache is a pure data structure — no I/O, no logging.
 *
 * <p>Usage:
 * <pre>{@code
 * ToolResultCache cache = new ToolResultCache(100, 60_000L);
 * cache.put("bash:ls -la", "total 12\n...");
 * Optional<String> hit = cache.get("bash:ls -la");
 * }</pre>
 */
public final class ToolResultCache {

    private final int maxSize;
    private final long ttlMs;
    private final LinkedHashMap<String, Entry> map;

    public ToolResultCache(int maxSize, long ttlMs) {
        if (maxSize < 1) throw new IllegalArgumentException("maxSize must be >= 1");
        if (ttlMs < 0) throw new IllegalArgumentException("ttlMs must be >= 0");
        this.maxSize = maxSize;
        this.ttlMs = ttlMs;
        // accessOrder=true to make this an LRU.
        this.map = new LinkedHashMap<>(16, 0.75f, true);
    }

    public int maxSize() { return maxSize; }
    public long ttlMs() { return ttlMs; }

    public synchronized int size() { return map.size(); }

    public synchronized void put(String key, String value) {
        if (key == null || value == null) return;
        long now = System.currentTimeMillis();
        map.put(key, new Entry(value, now));
        // Evict LRU entries until we're under the cap.
        while (map.size() > maxSize) {
            var it = map.entrySet().iterator();
            if (!it.hasNext()) break;
            it.next();
            it.remove();
        }
    }

    public synchronized Optional<String> get(String key) {
        if (key == null) return Optional.empty();
        Entry e = map.get(key);
        if (e == null) return Optional.empty();
        if (isExpired(e)) {
            map.remove(key);
            return Optional.empty();
        }
        return Optional.of(e.value);
    }

    public synchronized boolean containsKey(String key) {
        if (key == null) return false;
        Entry e = map.get(key);
        if (e == null) return false;
        if (isExpired(e)) {
            map.remove(key);
            return false;
        }
        return true;
    }

    public synchronized void invalidate(String key) {
        if (key != null) map.remove(key);
    }

    public synchronized void clear() {
        map.clear();
    }

    /** drop all expired entries now. Returns the number
     *  of entries removed. Useful for periodic cleanup. */
    public synchronized int evictExpired() {
        long now = System.currentTimeMillis();
        int removed = 0;
        var it = map.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (isExpired(e.getValue(), now)) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    private boolean isExpired(Entry e) {
        return isExpired(e, System.currentTimeMillis());
    }

    private boolean isExpired(Entry e, long now) {
        return ttlMs > 0 && (now - e.storedAtMs) > ttlMs;
    }

    private record Entry(String value, long storedAtMs) {}
}
