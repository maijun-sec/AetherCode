package org.aethercode.deepagents.selfimprove;

import java.util.List;

/**
 * R241.3 (O-3): the persistence boundary for {@link ReasoningBank}.
 *
 * <p>A {@code BankStorage} is responsible for keeping a set of
 * {@link ReasoningUnit}s on some durable medium — memory, a JSON
 * file, a future SQLite table, etc. The bank itself owns the
 * in-memory index for fast recall; the storage is a write-through
 * companion and a source for cold-start reload.
 *
 * <h2>Contract</h2>
 *
 * <ul>
 *   <li>{@link #loadAll()} returns every unit currently on disk.
 *       Called once at bank construction time; the result feeds
 *       the bank's in-memory cache. Empty list if storage is
 *       empty or has not been initialised yet.</li>
 *   <li>{@link #save(ReasoningUnit)} is called on every
 *       {@link ReasoningBank#add} and {@link ReasoningBank#touch}.
 *       Implementations must be safe to call repeatedly with the
 *       same id (overwrite).</li>
 *   <li>{@link #remove(String)} drops the unit from the
 *       medium. No-op if the id is unknown.</li>
 *   <li>{@link #flush()} forces any buffered writes to the
 *       medium. The default {@link InMemoryBankStorage} has
 *       nothing to flush; a file-backed implementation can use
 *       this to commit its write-ahead log.</li>
 *   <li>{@link #close()} releases any held resources. The
 *       default in-memory implementation is a no-op.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 *
 * <p>Implementations must be safe for concurrent use from the
 * bank's writer thread and any background decay / GC thread.
 * {@link InMemoryBankStorage} uses a {@code ReentrantReadWriteLock};
 * {@link JsonFileBankStorage} does the same.
 *
 * <h2>Why a separate interface</h2>
 *
 * <p>The bank is the only writer; reads always go through the
 * bank. Splitting storage out keeps the bank small, lets us
 * swap in a vector store or SQLite without touching the
 * recall path, and matches the same Storage split that
 * {@code aethercode-memory.FileBackedMemory} uses for its
 * own backing stores.
 */
public interface BankStorage extends AutoCloseable {

    /**
     * Load every persisted unit. The returned list is the
     * cold-start seed for the bank's in-memory cache. Order is
     * unspecified; the bank will re-sort on recall.
     */
    List<ReasoningUnit> loadAll();

    /**
     * Persist (or overwrite) a unit. Must be safe to call
     * repeatedly with the same id.
     */
    void save(ReasoningUnit unit);

    /**
     * Remove a unit. No-op if the id is unknown.
     */
    void remove(String id);

    /**
     * Force any buffered writes to the medium. Idempotent.
     * Default no-op via {@link InMemoryBankStorage}.
     */
    void flush();

    /**
     * Release any resources held by the storage. Idempotent.
     */
    @Override
    void close();
}
