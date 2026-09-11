package org.aethercode.deepagents.selfimprove;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * R241.3 (O-3): the default, process-local {@link BankStorage}
 * implementation. Keeps units in a {@code ConcurrentHashMap}
 * behind a read-write lock; no disk activity, no cross-process
 * sharing, no crash recovery.
 *
 * <p>This is the same effective behaviour as the
 * R241.2 {@code ReasoningBank} had — previously.3, the bank
 * itself held the units in {@code ConcurrentHashMap}s. R241.3
 * moves the storage into a dedicated object so the bank can
 * be parameterised on storage without changing its public API.
 *
 * <p>{@code new ReasoningBank()} still picks this storage, so
 * existing R241.2 callers and tests are unaffected.
 */
public final class InMemoryBankStorage implements BankStorage {

    private final Map<String, ReasoningUnit> map = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public InMemoryBankStorage() {}

    @Override
    public List<ReasoningUnit> loadAll() {
        lock.readLock().lock();
        try { return new ArrayList<>(map.values()); }
        finally { lock.readLock().unlock(); }
    }

    @Override
    public void save(ReasoningUnit unit) {
        if (unit == null) return;
        lock.writeLock().lock();
        try { map.put(unit.id(), unit); }
        finally { lock.writeLock().unlock(); }
    }

    @Override
    public void remove(String id) {
        if (id == null) return;
        lock.writeLock().lock();
        try { map.remove(id); }
        finally { lock.writeLock().unlock(); }
    }

    @Override
    public void flush() {
        // no buffered writes; intentional no-op
    }

    @Override
    public void close() {
        // no resources to release
    }

    /** Visible for tests: current size. */
    public int size() {
        lock.readLock().lock();
        try { return map.size(); }
        finally { lock.readLock().unlock(); }
    }
}
