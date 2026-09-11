package org.aethercode.deepagents.langchain_compat.langgraph;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LangGraph-compatible {@code BaseStore} interface.
 *
 * <p>Java-native port of
 * {@code langgraph.store.base.BaseStore}. The interface
 * exposes the {@code get}, {@code put}, {@code delete}, and
 * {@code search} operations used by the runtime to read and
 * write long-term memory. The {@link InMemoryStore} provides
 * an in-process implementation.</p>
 */
public interface BaseStore {

    /** Get the item stored under {@code (namespace, key)}. */
    Optional<Item> get(List<String> namespace, String key);

    /** Put one or more items atomically; returns the assigned write ids. */
    List<String> put(List<String> namespace, java.util.Collection<PutOp> ops);

    /** Delete the items under the given keys. */
    void delete(List<String> namespace, List<String> key);

    /** Search for items under {@code namespace} whose key matches {@code prefix}. */
    List<Item> search(List<String> namespace, String prefix, int limit);

    /** A single stored item: key, value, namespace, and a write id. */
    record Item(String key, Map<String, Object> value, List<String> namespace, String writeId) {
        public Item {
            Objects.requireNonNull(key, "key");
            value = value == null ? Map.of() : Map.copyOf(value);
            namespace = namespace == null ? List.of() : List.copyOf(namespace);
        }
    }

    /** A put operation: key + value. */
    record PutOp(String key, Map<String, Object> value) {
        public PutOp {
            Objects.requireNonNull(key, "key");
            value = value == null ? Map.of() : Map.copyOf(value);
        }
    }

    /** In-process implementation backed by a {@code ConcurrentMap}. */
    class InMemoryStore implements BaseStore {
        private final java.util.concurrent.ConcurrentMap<List<String>, java.util.Map<String, Item>> store
                = new ConcurrentHashMap<>();

        @Override
        public Optional<Item> get(List<String> namespace, String key) {
            Objects.requireNonNull(namespace, "namespace");
            java.util.Map<String, Item> ns = store.get(namespace);
            if (ns == null) return Optional.empty();
            return Optional.ofNullable(ns.get(key));
        }

        @Override
        public List<String> put(List<String> namespace, java.util.Collection<PutOp> ops) {
            Objects.requireNonNull(namespace, "namespace");
            Objects.requireNonNull(ops, "ops");
            java.util.Map<String, Item> ns = store.computeIfAbsent(
                    List.copyOf(namespace), k -> new ConcurrentHashMap<>());
            java.util.List<String> writeIds = new java.util.ArrayList<>();
            for (PutOp op : ops) {
                String writeId = "store-" + System.nanoTime();
                ns.put(op.key(), new Item(op.key(), op.value(), namespace, writeId));
                writeIds.add(writeId);
            }
            return writeIds;
        }

        @Override
        public void delete(List<String> namespace, List<String> keys) {
            Objects.requireNonNull(namespace, "namespace");
            java.util.Map<String, Item> ns = store.get(namespace);
            if (ns == null) return;
            for (String key : keys) ns.remove(key);
        }

        @Override
        public List<Item> search(List<String> namespace, String prefix, int limit) {
            Objects.requireNonNull(namespace, "namespace");
            java.util.Map<String, Item> ns = store.get(namespace);
            if (ns == null) return List.of();
            java.util.List<Item> out = new java.util.ArrayList<>();
            for (Item item : ns.values()) {
                if (item.key().startsWith(prefix)) {
                    out.add(item);
                    if (out.size() >= limit) break;
                }
            }
            return out;
        }
    }
}
