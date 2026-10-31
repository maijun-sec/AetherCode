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

package org.aethercode.runtime.store;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pluggable key-value store for long-term agent memory.
 *
 * <p>Java-native equivalent of langgraph's <code>BaseStore</code>. The
 * default in-memory implementation is {@code InMemoryStore} (in the
 * <code>deepagents-storage</code> module); langsmith-backed and other
 * remote implementations live in the <code>partners</code> modules.</p>
 */
public interface Store {

    /** Retrieve one item; empty if not present. */
    Optional<StoreItem> get(String namespace, String key);

    /** Bulk get; the returned list has the same length and order as {@code keys}. */
    List<Optional<StoreItem>> getMany(String namespace, List<String> keys);

    /** List items whose key starts with {@code keyPrefix} under the namespace. */
    List<StoreItem> list(String namespace, Optional<String> keyPrefix);

    /** Apply a {@link SearchOp} and return matching items. */
    List<StoreItem> search(SearchOp op);

    /** Insert or overwrite one item. */
    void put(String namespace, String key, Map<String, Object> value);

    /** Insert or overwrite many items atomically. */
    void putMany(List<PutOp> ops);

    /** Delete one item. */
    void delete(String namespace, String key);

    /** Delete many items. */
    void deleteMany(List<PutOp> ops);

    /** Async variant: see {@link #get(String, String)}. */
    java.util.concurrent.CompletableFuture<Optional<StoreItem>> aget(String namespace, String key);

    /** Async variant: see {@link #put(String, String, Map)}. */
    java.util.concurrent.CompletableFuture<Void> aput(String namespace, String key, Map<String, Object> value);
}
