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

package org.aethercode.mcp;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.aethercode.core.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * time-bounded cache for an MCP server's {@code listTools()} response.
 * Modelled on the TS {@code services/mcp/toolCache.ts}. A fresh MCP session
 * can have 10+ tools, and the JSON-RPC {@code tools/list} call is a full
 * round trip — caching the result for a few minutes eliminates the
 * re-fetch on every {@code engine.start()}.
 *
 * <p>The cache is per-supplier; one instance wraps one
 * {@code listTools()} callable. The cache is thread-safe (the loader
 * may run concurrently from multiple threads, but the resulting list is
 * published atomically).
 */
public final class McpToolCache {

    private static final Logger LOG = LoggerFactory.getLogger(McpToolCache.class);
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    private final long ttlMs;
    private final Supplier<List<Tool>> loader;
    private final AtomicReference<List<Tool>> cached = new AtomicReference<>(List.of());
    private final AtomicLong expiresAt = new AtomicLong(0);
    private final AtomicInteger hits = new AtomicInteger(0);
    private final AtomicInteger misses = new AtomicInteger(0);
    private final AtomicInteger errors = new AtomicInteger(0);

    public McpToolCache(Supplier<List<Tool>> loader) { this(DEFAULT_TTL, loader); }
    public McpToolCache(Duration ttl, Supplier<List<Tool>> loader) { this(ttl.toMillis(), loader); }
    public McpToolCache(long ttlMs, Supplier<List<Tool>> loader) {
        this.ttlMs = Math.max(0, ttlMs);
        this.loader = loader;
    }

    /** get the cached list, refreshing on miss or expiry. */
    public List<Tool> get() {
        long now = System.currentTimeMillis();
        long expiry = expiresAt.get();
        // cache is "warm" once expiresAt has been set in a previous refresh
        if (ttlMs > 0 && expiry > now && expiry > 0) {
            hits.incrementAndGet();
            return cached.get();
        }
        // refresh
        try {
            List<Tool> fresh = loader.get();
            if (fresh != null) {
                cached.set(List.copyOf(fresh));
                expiresAt.set(now + ttlMs);
                misses.incrementAndGet();
                return cached.get();
            }
        } catch (Exception e) {
            errors.incrementAndGet();
            LOG.warn("tool cache refresh failed: {}", e.getMessage());
        }
        return cached.get();
    }

    /** force the next call to refresh. */
    public void invalidate() {
        expiresAt.set(0);
    }

    public int hitCount() { return hits.get(); }
    public int missCount() { return misses.get(); }
    public int errorCount() { return errors.get(); }
    public long ttlMs() { return ttlMs; }
    public boolean isStale() { return System.currentTimeMillis() >= expiresAt.get(); }
    public int size() { return cached.get().size(); }
}
