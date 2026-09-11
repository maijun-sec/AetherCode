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
