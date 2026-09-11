package org.aethercode.core.fs.backend;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Java-native equivalent of langgraph's <code>BaseStore</code>.
 *
 * <p>Implementations provide a key/value store scoped by a hierarchical
 * namespace tuple. The deepagents <code>StoreBackend</code> sits on top
 * of this interface; the default {@link InMemoryStore} is enough for
 * unit tests and ephemeral runs, while a graph runtime can plug a
 * persistent implementation.</p>
 *
 * <p>Each sync method has a default async wrapper that delegates via
 * {@link CompletableFuture#supplyAsync} (or {@code runAsync} for void
 * ops). Concrete stores may override the async variants for native
 * async I/O.</p>
 */
public interface Store {

    /** Get a single item by namespace and key. */
    Optional<Item> get(List<String> namespace, String key);

    /** Upsert an item. */
    void put(List<String> namespace, String key, Map<String, Object> value);

    /**
     * Search items in {@code namespace}.
     *
     * @param namespace hierarchical prefix
     * @param query     optional natural-language query (may be {@code null})
     * @param filter    optional key/value filter (may be {@code null})
     * @param limit     page size; {@code 0} or negative means "use default"
     * @param offset    page offset
     */
    List<Item> search(List<String> namespace,
                      String query,
                      Map<String, Object> filter,
                      int limit,
                      int offset);

    /** Apply a batch of put/delete ops atomically (best-effort). */
    void batch(List<PutOp> ops);

    // -----------------------------------------------------------------
    // Async — default delegates to sync via supplyAsync / runAsync
    // -----------------------------------------------------------------

    default CompletableFuture<Optional<Item>> aget(List<String> namespace, String key) {
        return CompletableFuture.supplyAsync(() -> get(namespace, key));
    }

    default CompletableFuture<Void> aput(List<String> namespace, String key, Map<String, Object> value) {
        return CompletableFuture.runAsync(() -> put(namespace, key, value));
    }

    default CompletableFuture<List<Item>> asearch(List<String> namespace,
                                                  String query,
                                                  Map<String, Object> filter,
                                                  int limit,
                                                  int offset) {
        return CompletableFuture.supplyAsync(() -> search(namespace, query, filter, limit, offset));
    }

    default CompletableFuture<Void> abatch(List<PutOp> ops) {
        return CompletableFuture.runAsync(() -> batch(ops));
    }
}
