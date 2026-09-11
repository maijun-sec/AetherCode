package org.aethercode.bridge;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * in-process {@link Blackboard} backed by a {@link ConcurrentHashMap}. This is the
 * default when the caller does not specify a path — same behaviour as the prior round
 * {@code SwarmCoordinator} had.
 */
public final class InMemoryBlackboard implements Blackboard {

    private final Map<String, Object> store = new ConcurrentHashMap<>();

    @Override public void write(String key, Object value) { store.put(key, value); }
    @Override public Object read(String key) { return store.get(key); }
    @Override public void delete(String key) { store.remove(key); }
    @Override public Set<String> keys() { return new HashSet<>(store.keySet()); }
    @Override public Map<String, Object> snapshot() { return Map.copyOf(store); }
    @Override public void flush() { /* no-op */ }
}
