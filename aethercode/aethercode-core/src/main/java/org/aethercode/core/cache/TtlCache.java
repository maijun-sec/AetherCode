package org.aethercode.core.cache;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * a generic TTL cache. Each entry has a creation time and
 * an optional TTL. {@link #get} returns the value if it's still
 * fresh; otherwise it computes a new value via the loader (or
 * returns empty if no loader is configured).
 *
 * <p>Used by the subagent framework to memoize expensive lookups
 * (project info, file scans) within a session.
 */
public class TtlCache<K, V> {

    private record Entry<V>(V value, Instant expiresAt) {
        boolean isFresh(Instant now) { return expiresAt.isAfter(now); }
    }

    private final Map<K, Entry<V>> map = new LinkedHashMap<>();
    private final Duration defaultTtl;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong evictions = new AtomicLong();

    public TtlCache(Duration defaultTtl) {
        if (defaultTtl == null || defaultTtl.isZero() || defaultTtl.isNegative())
            throw new IllegalArgumentException("defaultTtl must be positive");
        this.defaultTtl = defaultTtl;
    }

    public Optional<V> get(K key) {
        return get(key, null);
    }

    public Optional<V> get(K key, Function<K, V> loader) {
        Instant now = Instant.now();
        Entry<V> e = map.get(key);
        if (e != null && e.isFresh(now)) {
            hits.incrementAndGet();
            return Optional.of(e.value());
        }
        misses.incrementAndGet();
        if (loader == null) return Optional.empty();
        V v = loader.apply(key);
        put(key, v, defaultTtl);
        return Optional.of(v);
    }

    public void put(K key, V value) {
        put(key, value, defaultTtl);
    }

    public synchronized void put(K key, V value, Duration ttl) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        map.put(key, new Entry<>(value, Instant.now().plus(ttl)));
    }

    public synchronized void invalidate(K key) {
        if (map.remove(key) != null) evictions.incrementAndGet();
    }

    public synchronized void clear() {
        evictions.addAndGet(map.size());
        map.clear();
    }

    public synchronized int prune() {
        Instant now = Instant.now();
        int n = 0;
        Iterator<Map.Entry<K, Entry<V>>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            if (!it.next().getValue().isFresh(now)) {
                it.remove();
                n++;
            }
        }
        evictions.addAndGet(n);
        return n;
    }

    public int size() { return map.size(); }
    public long hits() { return hits.get(); }
    public long misses() { return misses.get(); }
    public long evictions() { return evictions.get(); }
    public Duration defaultTtl() { return defaultTtl; }

    public synchronized boolean containsKey(K key) {
        Entry<V> e = map.get(key);
        if (e == null) return false;
        if (!e.isFresh(Instant.now())) {
            map.remove(key);
            evictions.incrementAndGet();
            return false;
        }
        return true;
    }

    public synchronized List<K> keys() { return new ArrayList<>(map.keySet()); }

    public synchronized void putAll(Map<K, V> entries) {
        for (Map.Entry<K, V> e : entries.entrySet()) put(e.getKey(), e.getValue());
    }
}
