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

package org.aethercode.deepagents.langchain_compat.langgraph;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LangGraph-compatible {@code BaseCache} interface.
 *
 * <p>Java-native port of
 * {@code langgraph.cache.base.BaseCache}. The cache is a
 * simple key/value store used by chat models to memoize
 * identical requests. The {@link InMemoryCache} provides an
 * in-process implementation.</p>
 */
public interface BaseCache {

    /** Look up a cached value. */
    Optional<Map<String, Object>> get(String key);

    /** Store a value. */
    void set(String key, Map<String, Object> value);

    /** Invalidate all entries. */
    void clear();

    /** In-process implementation backed by a {@code ConcurrentMap}. */
    class InMemoryCache implements BaseCache {
        private final java.util.concurrent.ConcurrentMap<String, Map<String, Object>> map
                = new ConcurrentHashMap<>();

        @Override
        public Optional<Map<String, Object>> get(String key) {
            return Optional.ofNullable(map.get(key));
        }

        @Override
        public void set(String key, Map<String, Object> value) {
            map.put(key, value);
        }

        @Override
        public void clear() {
            map.clear();
        }
    }
}
