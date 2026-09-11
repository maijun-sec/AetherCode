package org.aethercode.core.fs.backend;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * In-memory {@link FilesStateStore}.
 *
 * <p>For tests and for the simple "no graph runtime" case. All reads
 * see the writes already sent: the {@code send} method mutates the
 * same map the {@code read} method returns.</p>
 */
public class InMemoryFilesStateStore implements FilesStateStore {

    private final Map<String, FileData> files = new HashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public InMemoryFilesStateStore() {}

    public InMemoryFilesStateStore(Map<String, FileData> initial) {
        Objects.requireNonNull(initial, "initial");
        lock.writeLock().lock();
        try {
            files.putAll(initial);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Map<String, FileData> read(boolean fresh) {
        lock.readLock().lock();
        try {
            // Defensive copy so callers can't mutate the live map.
            return new HashMap<>(files);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void send(Map<String, FileData> update) {
        if (update == null || update.isEmpty()) return;
        lock.writeLock().lock();
        try {
            for (Map.Entry<String, FileData> e : update.entrySet()) {
                if (e.getValue() == null) {
                    files.remove(e.getKey());
                } else {
                    files.put(e.getKey(), e.getValue());
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Seed initial files (test convenience). */
    public InMemoryFilesStateStore putAll(Map<String, FileData> initial) {
        send(initial);
        return this;
    }
}
