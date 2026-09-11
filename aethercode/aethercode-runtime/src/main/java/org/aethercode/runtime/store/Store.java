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
