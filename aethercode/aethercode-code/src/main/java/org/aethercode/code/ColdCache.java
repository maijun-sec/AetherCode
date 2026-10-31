/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

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
