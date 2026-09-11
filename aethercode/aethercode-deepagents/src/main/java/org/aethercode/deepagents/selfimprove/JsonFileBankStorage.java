package org.aethercode.deepagents.selfimprove;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;

/**
 * R241.3 (O-3): a {@link BankStorage} that persists each
 * {@link ReasoningUnit} to its own JSON file under a directory.
 *
 * <h2>Layout</h2>
 *
 * <pre>
 *   {dir}/{id}.json
 * </pre>
 *
 * <p>One file per unit, mirroring the layout
 * {@code aethercode-memory.ExperienceStore} uses for its
 * experience records. This is friendly to:
 *
 * <ul>
 *   <li>manual inspection — {@code cat *.json | jq} works;</li>
 *   <li>diffing across sessions — {@code git diff} on the
 *       directory shows only the changed unit files;</li>
 *   <li>concurrent access from multiple agent runs — the
 *       directory can be shared across processes if the
 *       caller serialises the writes through this storage.</li>
 * </ul>
 *
 * <h2>Atomic writes</h2>
 *
 * <p>Each save writes to a {@code .tmp} sibling then renames
 * over the target. On POSIX this is atomic; on Windows we fall
 * back to {@code StandardCopyOption.REPLACE_EXISTING} when
 * {@code AtomicMoveNotSupportedException} is thrown, which
 * still gives an all-or-nothing result from the perspective of
 * a reader (the file either exists with the new content or
 * with the old).
 *
 * <h2>Robust load</h2>
 *
 * <p>Malformed JSON in any individual file is logged and
 * skipped — the rest of the directory still loads. This
 * keeps the bank usable if a single file is half-written by
 * a crashing process.
 *
 * <h2>Concurrency</h2>
 *
 * <p>A {@code ReentrantReadWriteLock} lets {@link #loadAll()}
 * run concurrently with itself (e.g. multiple cold-starts on
 * a shared disk) while keeping writes serial.
 */
public final class JsonFileBankStorage implements BankStorage {

    private static final Logger LOG = LoggerFactory.getLogger(JsonFileBankStorage.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final String FILE_EXT = ".json";
    private static final String TMP_EXT = ".json.tmp";

    private final Path dir;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile boolean closed = false;

    public JsonFileBankStorage(Path dir) {
        if (dir == null) throw new IllegalArgumentException("dir must not be null");
        this.dir = dir;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("could not create reasoning-bank dir " + dir + ": " + e.getMessage(), e);
        }
    }

    /** The directory backing this storage. */
    public Path dir() { return dir; }

    @Override
    public List<ReasoningUnit> loadAll() {
        ensureOpen();
        lock.readLock().lock();
        try (Stream<Path> stream = Files.list(dir)) {
            List<ReasoningUnit> out = new ArrayList<>();
            for (Path p : (Iterable<Path>) stream::iterator) {
                String fn = p.getFileName().toString();
                if (!fn.endsWith(FILE_EXT) || fn.endsWith(TMP_EXT)) continue;
                try {
                    String json = Files.readString(p);
                    if (json.isBlank()) continue;
                    ReasoningUnit u = MAPPER.readValue(json, ReasoningUnit.class);
                    out.add(u);
                } catch (Exception e) {
                    LOG.warn("reasoning-bank load failed for {}: {}", p, e.getMessage());
                }
            }
            return out;
        } catch (IOException e) {
            LOG.warn("reasoning-bank dir list failed for {}: {}", dir, e.getMessage());
            return List.of();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void save(ReasoningUnit unit) {
        if (unit == null) return;
        ensureOpen();
        Path target = fileFor(unit.id());
        Path tmp = tmpFor(unit.id());
        lock.writeLock().lock();
        try {
            String json = MAPPER.writeValueAsString(unit);
            Files.writeString(tmp, json,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try {
                Files.move(tmp, target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException amns) {
                // Windows: ATOMIC_MOVE not supported across the same volume
                // in some configurations; fall back to non-atomic replace.
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new RuntimeException("reasoning-bank save failed for " + unit.id() + ": " + e.getMessage(), e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void remove(String id) {
        if (id == null) return;
        ensureOpen();
        lock.writeLock().lock();
        try {
            try { Files.deleteIfExists(fileFor(id)); }
            catch (IOException e) { LOG.warn("reasoning-bank delete failed: {}", e.getMessage()); }
            // Best-effort cleanup of a stale .tmp sibling
            try { Files.deleteIfExists(tmpFor(id)); }
            catch (IOException ignore) {}
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void flush() {
        // writes are already fsync-friendly because we do them through Files.writeString
        // and the rename is the durability point. Nothing buffered here.
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("JsonFileBankStorage is closed");
    }

    private Path fileFor(String id) {
        return dir.resolve(id + FILE_EXT);
    }

    private Path tmpFor(String id) {
        return dir.resolve(id + TMP_EXT);
    }
}
