package org.aethercode.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-session skip-confirmation counter. When a session has
 * {@code remaining > 0}, the {@link MatrixPermissionPolicy} short-circuits
 * the matrix's ASK action to ALLOW without prompting the user. Each
 * permission check decrements the counter; when it hits 0, normal
 * confirmation flow resumes.
 *
 * <p>Setter entry points:
 * <ul>
 *   <li>{@link #set(String, int)} — direct RPC for the
 *       {@code setSkipConfirmation} JSON-RPC method.</li>
 *   <li>{@link #consumeDefaultFromConfig} — applied at engine boot from
 *       {@code .aethercode/config.json::skipConfirmationRounds}.</li>
 * </ul>
 *
 * <p>R102: when constructed with a {@code persistenceDir}, every
 * {@link #set} call also writes
 * {@code <persistenceDir>/<sessionId>/skip-confirmation.json} so the
 * counter survives engine restarts. The on-disk format is:
 * <pre>
 *   { "remaining": 3, "updatedAt": 1734567890123 }
 * </pre>
 * {@link #loadFromDisk(String)} reads it back. Persistence is best-effort:
 * a failed write logs at warn and the in-memory counter still updates.
 *
 * <p>Thread-safe. Sessions are auto-created on first use and removed on
 * {@link #clear(String)} (called when a session is deleted).
 */
public final class SkipConfirmationRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(SkipConfirmationRegistry.class);

    /** file name used for persistence. Public so tests
     *  can clean up the file by name. */
    public static final String PERSIST_FILE_NAME = "skip-confirmation.json";

    private final ConcurrentHashMap<String, AtomicInteger> perSession = new ConcurrentHashMap<>();
    // optional persistence directory. When non-null, every
    // set() call writes a JSON file at
    // <persistenceDir>/<sessionId>/skip-confirmation.json.
    // The on-disk format is intentionally simple — one file per
    // session, no central index, so a session can be removed
    // without coordinating with the registry.
    private final Path persistenceDir;

    public SkipConfirmationRegistry() {
        this(null);
    }

    public SkipConfirmationRegistry(Path persistenceDir) {
        this.persistenceDir = persistenceDir;
    }

    /** Set the counter for a session. {@code rounds < 0} clears the counter. */
    public void set(String sessionId, int rounds) {
        if (sessionId == null) return;
        if (rounds <= 0) {
            perSession.remove(sessionId);
            persist(sessionId, 0);
            return;
        }
        perSession.put(sessionId, new AtomicInteger(rounds));
        persist(sessionId, rounds);
    }

    /**
     * Atomically consume one round if available. Returns {@code true} if
     * a skip was applied (the caller should treat ASK as ALLOW without
     * prompting the user).
     */
    public boolean consumeOne(String sessionId) {
        if (sessionId == null) return false;
        AtomicInteger c = perSession.get(sessionId);
        if (c == null) return false;
        // CAS-decrement; if we go negative, restore and return false.
        while (true) {
            int cur = c.get();
            if (cur <= 0) return false;
            if (c.compareAndSet(cur, cur - 1)) {
                int after = Math.max(0, cur - 1);
                persist(sessionId, after);
                return true;
            }
        }
    }

    public int remaining(String sessionId) {
        if (sessionId == null) return 0;
        AtomicInteger c = perSession.get(sessionId);
        return c == null ? 0 : Math.max(0, c.get());
    }

    public void clear(String sessionId) {
        if (sessionId == null) return;
        perSession.remove(sessionId);
        persist(sessionId, 0);
    }

    /** Apply the project's default skip count (from config.json) to a session. */
    public void applyDefault(String sessionId, AetherCodeConfig cfg) {
        if (sessionId == null || cfg == null) return;
        if (cfg.skipConfirmation) {
            // Project-level "skipConfirmation": true means infinite
            // (Integer.MAX_VALUE rounds). The session keeps skipping
            // until explicitly cleared or the engine exits.
            perSession.put(sessionId, new AtomicInteger(Integer.MAX_VALUE));
            persist(sessionId, Integer.MAX_VALUE);
            return;
        }
        if (cfg.skipConfirmationRounds > 0) {
            perSession.put(sessionId, new AtomicInteger(cfg.skipConfirmationRounds));
            persist(sessionId, cfg.skipConfirmationRounds);
        }
    }

    /**
     * load the persisted counter for a session. Returns 0
     * if the file is missing, malformed, or persistence is
     * disabled. Sets the in-memory counter as a side-effect.
     */
    public int loadFromDisk(String sessionId) {
        if (sessionId == null || persistenceDir == null) return 0;
        Path file = persistenceDir.resolve(sessionId).resolve(PERSIST_FILE_NAME);
        if (!Files.exists(file)) return 0;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = new ObjectMapper().readValue(Files.readString(file), Map.class);
            Object r = map.get("remaining");
            int remaining = 0;
            if (r instanceof Number) remaining = ((Number) r).intValue();
            else if (r instanceof String) {
                try { remaining = Integer.parseInt((String) r); }
                catch (NumberFormatException ignore) { return 0; }
            }
            if (remaining > 0) {
                perSession.put(sessionId, new AtomicInteger(remaining));
                LOG.info("R102: loaded skip-confirmation for session {} = {} rounds",
                        sessionId, remaining);
            } else {
                perSession.remove(sessionId);
            }
            return Math.max(0, remaining);
        } catch (IOException | RuntimeException e) {
            LOG.warn("R102: failed to load skip-confirmation for {}: {}",
                    sessionId, e.toString());
            return 0;
        }
    }

    /**
     * best-effort persist. A failed write logs at warn and
     * returns; the in-memory state still updates. The format is
     * one line of JSON — small enough that we don't bother with
     * atomic-write (rename) semantics; if the daemon crashes
     * mid-write the next {@link #loadFromDisk} either reads
     * the previous value or fails over to the default.
     */
    private void persist(String sessionId, int remaining) {
        if (persistenceDir == null || sessionId == null) return;
        Path dir = persistenceDir.resolve(sessionId);
        Path file = dir.resolve(PERSIST_FILE_NAME);
        try {
            Files.createDirectories(dir);
            String json = new ObjectMapper().writeValueAsString(Map.of(
                    "remaining", remaining,
                    "updatedAt", System.currentTimeMillis()
            ));
            Files.writeString(file, json);
        } catch (IOException | RuntimeException e) {
            LOG.warn("R102: failed to persist skip-confirmation for {}: {}",
                    sessionId, e.toString());
        }
    }

    /** accessor for the persistence directory. */
    public Path persistenceDir() { return persistenceDir; }
}
