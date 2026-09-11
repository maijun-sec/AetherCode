package org.aethercode.deepagents.selfimprove;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Decorator around {@link BankClient} that adds an LRU + TTL cache in front of read-side methods
 * and passes write-side methods ({@link #touch}, {@link #recordOutcome}) through while
 * invalidating the entire cache.
 *
 * <p>A decorator is chosen over modifying BankClient directly so the latter stays stateless and
 * unaware of caching — caching is a deployment concern, and callers that don't need it (e.g. a
 * one-shot CLI) can grab the raw BankClient.</p>
 *
 * <p>The wire shape is unchanged — the cache sits below the {@code List<Map<String,Object>>}
 * boundary, so hits and misses produce the same JSON externally.</p>
 *
 * <p>Threading model: an accessOrder {@code LinkedHashMap} guarded by {@code synchronized}; reads
 * and writes share the same lock, but throughput is bounded by HTTP RTT, not by the lock.</p>
 */
public class CachingBankClient extends BankClient {

    private static final Logger LOG = LoggerFactory.getLogger(CachingBankClient.class);

    private final BankClient delegate;
    private final int maxSize;
    private final Duration ttl;
    private final LinkedHashMap<String, CacheEntry> cache;

    /**
     * @param delegate the underlying client. Must be non-null; it is the only one that talks to the wire.
     * @param maxSize  LRU eviction cap. {@code <= 0} means "unbounded, but TTL still applies".
     * @param ttl      per-entry TTL. {@code null} means "never expire"; entries are checked on read,
     *                 and expired entries are treated as misses and re-fetched.
     */
    public CachingBankClient(BankClient delegate, int maxSize, Duration ttl) {
        // Call the parent constructor only so baseUri remains valid if this is upcast to BankClient;
        // the parent's HttpClient / ObjectMapper are not used — go with the minimal compilable ctor.
        super(Objects.requireNonNull(delegate, "delegate").baseUri());
        this.delegate = delegate;
        this.maxSize = maxSize;
        this.ttl = ttl;
        // accessOrder=true: gets reorder the map, so the head is always the least-recently-used
        // entry (the eviction target).
        this.cache = new LinkedHashMap<>(16, 0.75f, true);
    }

    // Read-side (with cache)

    @Override
    public List<Map<String, Object>> recallFor(String kind, int n) {
        String key = cacheKey("recall", kind, n);
        List<Map<String, Object>> hit = getIfFresh(key);
        if (hit != null) {
            LOG.debug("bank cache hit {}", key);
            return copyList(hit);
        }
        List<Map<String, Object>> fresh = delegate.recallFor(kind, n);
        put(key, fresh);
        return copyList(fresh);
    }

    @Override
    public List<Map<String, Object>> recallAllKinds(int n) {
        String key = cacheKey("recall-all", "*", n);
        List<Map<String, Object>> hit = getIfFresh(key);
        if (hit != null) {
            LOG.debug("bank cache hit {}", key);
            return copyList(hit);
        }
        List<Map<String, Object>> fresh = delegate.recallAllKinds(n);
        put(key, fresh);
        return copyList(fresh);
    }

    @Override
    public Map<String, Object> stats() {
        String key = cacheKey("stats", "*", 0);
        Map<String, Object> hit = getIfFresh(key);
        if (hit != null) {
            LOG.debug("bank cache hit {}", key);
            return new LinkedHashMap<>(hit);
        }
        Map<String, Object> fresh = delegate.stats();
        put(key, fresh);
        return new LinkedHashMap<>(fresh);
    }

    // Write-side (pass-through + full invalidation)

    @Override
    public Map<String, Object> touch(String id) {
        Map<String, Object> r = delegate.touch(id);
        // We don't know which kind a unit belongs to, so conservatively drop the entire cache.
        // The bank is small (default 1000 entries), so a full clear is cheaper than tracking
        // per-kind dirty state.
        invalidateAll();
        return r;
    }

    @Override
    public Map<String, Object> recordOutcome(String id, boolean ok) {
        Map<String, Object> r = delegate.recordOutcome(id, ok);
        invalidateAll();
        return r;
    }

    // Cache management

    /**
     * Clears the entire cache. Called by write-side methods; tests also call it directly to
     * assert invalidation behavior.
     */
    public void invalidateAll() {
        synchronized (cache) {
            cache.clear();
        }
        LOG.debug("bank cache invalidated");
    }

    /** Current number of cache entries; exposed for tests. */
    public int cacheSize() {
        synchronized (cache) {
            return cache.size();
        }
    }

    // helpers

    private static String cacheKey(String op, String kind, int n) {
        return op + "|" + kind + "|" + n;
    }

    @SuppressWarnings("unchecked")
    private <T> T getIfFresh(String key) {
        synchronized (cache) {
            CacheEntry e = cache.get(key);
            if (e == null) return null;
            if (ttl != null && e.expiresAt != null
                    && Instant.now().isAfter(e.expiresAt)) {
                cache.remove(key);
                return null;
            }
            return (T) e.value;
        }
    }

    private void put(String key, Object value) {
        synchronized (cache) {
            // Evict expired entries before checking capacity, so a transient new entry cannot push
            // out a long-lived one that was just read.
            if (ttl != null) {
                Instant now = Instant.now();
                cache.entrySet().removeIf(en -> {
                    CacheEntry e = en.getValue();
                    return e.expiresAt != null && now.isAfter(e.expiresAt);
                });
            }
            Instant expiresAt = ttl == null ? null : Instant.now().plus(ttl);
            cache.put(key, new CacheEntry(value, expiresAt));
            if (maxSize > 0) {
                while (cache.size() > maxSize) {
                    // accessOrder=true: the head of the map is the least-recently-used entry, and
                    // the iterator's next() returns the head, so we can evict directly.
                    java.util.Iterator<String> it = cache.keySet().iterator();
                    if (!it.hasNext()) break;
                    String eldest = it.next();
                    cache.remove(eldest);
                }
            }
        }
    }

    /** Defensive copy to prevent callers from mutating cached objects. */
    private static List<Map<String, Object>> copyList(List<Map<String, Object>> in) {
        if (in == null) return List.of();
        List<Map<String, Object>> out = new ArrayList<>(in.size());
        for (Map<String, Object> m : in) {
            out.add(new LinkedHashMap<>(m));
        }
        return out;
    }

    /** A single cache entry holding a value and an optional expiration timestamp. */
    private static final class CacheEntry {
        final Object value;
        final Instant expiresAt;
        CacheEntry(Object value, Instant expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
        }
    }
}
