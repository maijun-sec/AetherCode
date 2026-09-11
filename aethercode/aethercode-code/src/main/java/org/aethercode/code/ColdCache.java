package org.aethercode.code;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cold-cache state.
 *
 * <p>Java-native port of the Python {@code deepagents_code.cold_cache}
 * module. The Java port keeps a tiny in-memory cache of the most recent
 * model specs; the TUI consults it to decide whether to show the
 * "provider's prompt cache may be cold" warning.</p>
 */
public final class ColdCache {
    private ColdCache() {}

    /** A cache record. */
    public record CacheRecord(String modelSpec, long firstRequestAt, long lastRequestAt) {}

    private final Map<String, CacheRecord> records = new LinkedHashMap<>();
    private static final long COLD_THRESHOLD_MS = 5 * 60 * 1000L; // 5 minutes

    /** Record a request for a model spec. */
    public void recordRequest(String modelSpec) {
        if (modelSpec == null) return;
        long now = System.currentTimeMillis();
        CacheRecord existing = records.get(modelSpec);
        if (existing == null) {
            records.put(modelSpec, new CacheRecord(modelSpec, now, now));
        } else {
            records.put(modelSpec, new CacheRecord(modelSpec, existing.firstRequestAt(), now));
        }
    }

    /** Whether the cache is "cold" for a model spec. */
    public boolean isCold(String modelSpec) {
        CacheRecord r = records.get(modelSpec);
        if (r == null) return true;
        return System.currentTimeMillis() - r.lastRequestAt() > COLD_THRESHOLD_MS;
    }

    /** Forget all records. */
    public void clear() {
        records.clear();
    }
}
