package org.aethercode.core.fs.backend;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe in-memory {@link Store} for tests and ephemeral runs.
 *
 * <p>Java-native port of langgraph's <code>InMemoryStore</code>. Items
 * are kept in a {@link ConcurrentHashMap} keyed by
 * {@code namespace + "\u0000" + key} so that two stores that share a
 * namespace do not collide. Batch ops are applied under a single lock
 * to keep {@code put} / {@code delete} ordering predictable.</p>
 */
public class InMemoryStore implements Store {

    private final Map<String, Item> items = new ConcurrentHashMap<>();
    private final ReentrantLock batchLock = new ReentrantLock();

    private static String compositeKey(List<String> namespace, String key) {
        return String.join("\u0000", namespace) + "\u0000" + key;
    }

    @Override
    public Optional<Item> get(List<String> namespace, String key) {
        if (namespace == null || key == null) return Optional.empty();
        return Optional.ofNullable(items.get(compositeKey(namespace, key)));
    }

    @Override
    public void put(List<String> namespace, String key, Map<String, Object> value) {
        if (namespace == null || key == null) {
            throw new IllegalArgumentException("namespace and key are required");
        }
        if (value == null) {
            items.remove(compositeKey(namespace, key));
            return;
        }
        items.put(compositeKey(namespace, key), new Item(List.copyOf(namespace), key, new HashMap<>(value)));
    }

    @Override
    public List<Item> search(List<String> namespace,
                             String query,
                             Map<String, Object> filter,
                             int limit,
                             int offset) {
        if (namespace == null) return List.of();
        String nsPrefix = String.join("\u0000", namespace) + "\u0000";
        List<Item> matched = new ArrayList<>();
        for (Item item : items.values()) {
            String ckey = compositeKey(item.namespace(), item.key());
            if (!ckey.startsWith(nsPrefix)) continue;
            if (query != null && !query.isEmpty()) {
                // No natural-language search in the in-memory backend; the
                // Python equivalent also leaves the query to a real store.
                // We treat the query as a literal substring of the value's
                // string form so the basic StoreBackend tests still pass.
                String joined = String.valueOf(item.value().get("content"));
                if (joined == null || !joined.contains(query)) continue;
            }
            if (filter != null && !filter.isEmpty()) {
                if (!matchesFilter(item.value(), filter)) continue;
            }
            matched.add(item);
        }
        matched.sort((a, b) -> a.key().compareTo(b.key()));
        int from = Math.max(0, offset);
        int to = limit > 0 ? Math.min(matched.size(), from + limit) : matched.size();
        if (from >= matched.size()) return List.of();
        return Collections.unmodifiableList(matched.subList(from, to));
    }

    private static boolean matchesFilter(Map<String, Object> value, Map<String, Object> filter) {
        for (Map.Entry<String, Object> e : filter.entrySet()) {
            if (!java.util.Objects.equals(value.get(e.getKey()), e.getValue())) return false;
        }
        return true;
    }

    @Override
    public void batch(List<PutOp> ops) {
        batchLock.lock();
        try {
            for (PutOp op : ops) {
                if (op.isDelete()) {
                    items.remove(compositeKey(op.namespace(), op.key()));
                } else {
                    items.put(compositeKey(op.namespace(), op.key()),
                            new Item(List.copyOf(op.namespace()), op.key(), new HashMap<>(op.value())));
                }
            }
        } finally {
            batchLock.unlock();
        }
    }

    /** Test helper: snapshot all items as an unmodifiable map keyed by composite key. */
    public Map<String, Item> snapshot() {
        return Collections.unmodifiableMap(new HashMap<>(items));
    }
}
