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

package org.aethercode.orchestration.perf;

import org.aethercode.orchestration.verifier.Verifier.VerificationResult;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * LRU cache of {@link VerificationResult} keyed by the action that was
 * verified. Used by {@link org.aethercode.orchestration.runtime.AgentRuntime}
 * (or any other orchestrator) to short-circuit repeated verifier calls
 * on the same action — a hot pattern in self-correction loops and
 * multi-agent ensembles, where the same candidate is re-verified after
 * a small edit or a vote.
 *
 * <p>Keying is by {@link Object#equals(Object)}, so callers should pass
 * immutable actions (records / Strings / boxed primitives). For
 * mutable actions, wrap the call in a {@link Function#apply(Object)}
 * that materializes a stable key first.</p>
 *
 * <p>The cache is bounded by an LRU eviction policy; once {@code maxSize}
 * entries are stored, the least-recently-used entry is evicted on the
 * next insert. This keeps memory bounded without forcing the caller to
 * pre-size the cache.</p>
 */
public final class ActionCache {

    private final int maxSize;
    private final Map<Object, VerificationResult> store;
    private long hits;
    private long misses;

    public ActionCache(int maxSize) {
        if (maxSize < 1) {
            throw new IllegalArgumentException("maxSize must be >= 1");
        }
        this.maxSize = maxSize;
        this.store = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Object, VerificationResult> e) {
                return size() > ActionCache.this.maxSize;
            }
        };
    }

    /** Look up a previously verified action. {@code null} if absent. */
    public VerificationResult get(Object action) {
        VerificationResult v = store.get(action);
        if (v != null) hits++; else misses++;
        return v;
    }

    /** Insert a verifier result. Evicts LRU if full. */
    public void put(Object action, VerificationResult result) {
        if (action == null) {
            throw new IllegalArgumentException("action must be non-null");
        }
        if (result == null) {
            throw new IllegalArgumentException("result must be non-null");
        }
        store.put(action, result);
    }

    /** Look up, computing via {@code loader} on miss and caching the result. */
    public VerificationResult getOrCompute(Object action, Function<Object, VerificationResult> loader) {
        Objects.requireNonNull(loader, "loader");
        VerificationResult cached = get(action);
        if (cached != null) return cached;
        VerificationResult fresh = loader.apply(action);
        if (fresh != null) put(action, fresh);
        return fresh;
    }

    public int size() { return store.size(); }
    public int maxSize() { return maxSize; }
    public long hits() { return hits; }
    public long misses() { return misses; }
    public double hitRate() {
        long total = hits + misses;
        return total == 0 ? 0.0 : (double) hits / (double) total;
    }

    public void clear() {
        store.clear();
        hits = 0;
        misses = 0;
    }
}
