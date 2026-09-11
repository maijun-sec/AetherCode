package org.aethercode.bridge;

import java.util.Map;
import java.util.Set;

/**
 * shared key-value store for swarm teammates. prior round used an in-process
 * {@code Map}; prior round lifts that to an interface so the same {@link SwarmCoordinator}
 * code can run against an in-memory, file-backed, or sqlite-backed store.
 *
 * <p>Implementations must be safe for concurrent use — {@link SwarmCoordinator}
 * spawns work on the common ForkJoinPool and writes from many threads.
 */
public interface Blackboard {

    /** Store {@code value} under {@code key}. The value may be a String, Number, Boolean, Map, or List. */
    void write(String key, Object value);

    /** Read the value at {@code key}, or {@code null} when missing. */
    Object read(String key);

    /** Remove the entry at {@code key}. */
    void delete(String key);

    /** Snapshot of the current key set. */
    Set<String> keys();

    /** Read-only view of all entries. */
    Map<String, Object> snapshot();

    /** Persist any in-memory state to durable storage. No-op for the in-memory backend. */
    void flush();
}
