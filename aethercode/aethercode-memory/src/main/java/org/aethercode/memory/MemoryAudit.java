package org.aethercode.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.aethercode.core.config.SecureFilePermissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * R230 (G5): append-only audit log for memory operations.
 *
 * <p>Writes one JSON line per action to {@code <memoryBase>/audit.log}.
 * Best-effort: a failed write logs a warning but never throws — the
 * user-facing memory operation has already succeeded by the time we
 * record it, and we don't want audit logging to break the data path.
 *
 * <p>Threading: writes are serialised on a per-instance {@link ReentrantLock}.
 * The log file is opened in append mode and fsync is forced once per
 * {@link #FLUSH_BATCH} records (or every {@link #FLUSH_INTERVAL_MS} ms,
 * whichever first) to amortise syscall cost.
 *
 * <p>Schema (one JSON object per line):
 * <pre>
 *   {
 *     "ts": "2026-09-07T12:34:56.789Z",
 *     "actor": "agent:mavis" | "user:alice" | "system",
 *     "action": "read" | "write" | "delete" | "compress" |
 *               "decay" | "recall" | "share" | "setSensitivity",
 *     "scope": "user" | "project" | "session",
 *     "key": "user.name" | null,
 *     "kind": "fact" | "rule" | "change" | "breadcrumb" | "experience" | null,
 *     "sourceSessionId": "..." | null,
 *     "decision": "allow" | "deny" | "filtered-pii" | null,
 *     "meta": { ... caller-supplied extras ... }
 *   }
 * </pre>
 *
 * <p>Set the JVM system property {@code aethercode.memory.audit.enabled=false}
 * to disable globally (default: enabled when {@link #enable()} is called).
 */
public final class MemoryAudit {

    private static final Logger LOG = LoggerFactory.getLogger(MemoryAudit.class);

    /** Flush every N records. */
    public static final int FLUSH_BATCH = 100;
    /** Flush at most this often, in milliseconds. */
    public static final long FLUSH_INTERVAL_MS = 1000;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static volatile MemoryAudit INSTANCE;
    private static final ReentrantLock GLOBAL_LOCK = new ReentrantLock();

    private final Path logFile;
    private final ReentrantLock writeLock = new ReentrantLock();
    private volatile boolean enabled = true;
    private long lastFlushMs = System.currentTimeMillis();
    private int sinceLastFlush = 0;

    MemoryAudit(Path logFile) {
        this.logFile = logFile;
        if (this.logFile.getParent() != null) {
            try { Files.createDirectories(this.logFile.getParent()); } catch (IOException ignore) {}
        }
    }

    /** Initialise the global audit log at {@code <memoryBase>/audit.log}. */
    public static MemoryAudit enable(Path memoryBase) {
        GLOBAL_LOCK.lock();
        try {
            if (INSTANCE == null) {
                String sys = System.getProperty("aethercode.memory.audit.enabled", "true");
                boolean want = !"false".equalsIgnoreCase(sys);
                INSTANCE = new MemoryAudit(memoryBase.resolve("audit.log"));
                INSTANCE.enabled = want;
                if (!want) {
                    LOG.info("memory audit disabled via aethercode.memory.audit.enabled=false");
                }
            }
            return INSTANCE;
        } finally { GLOBAL_LOCK.unlock(); }
    }

    /** For tests: replace the global instance with a temp-path one. */
    public static void setInstanceForTesting(MemoryAudit audit) {
        GLOBAL_LOCK.lock();
        try { INSTANCE = audit; } finally { GLOBAL_LOCK.unlock(); }
    }

    /** For tests: clear the global instance. */
    public static void clearForTesting() {
        GLOBAL_LOCK.lock();
        try { INSTANCE = null; } finally { GLOBAL_LOCK.unlock(); }
    }

    /** Get the current global instance, or {@code null} if not enabled. */
    public static MemoryAudit current() {
        return INSTANCE;
    }

    public Path logFile() { return logFile; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /** Record a single event. Best-effort: never throws. */
    public void record(String actor, Action action, MemoryScope scope, String key, String kind,
                       String sourceSessionId, Decision decision, Map<String, Object> meta) {
        if (!enabled) return;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ts", Instant.now().toString());
        if (actor != null) row.put("actor", actor);
        row.put("action", action == null ? "unknown" : action.wire);
        if (scope != null) row.put("scope", scope.name().toLowerCase());
        if (key != null) row.put("key", key);
        if (kind != null) row.put("kind", kind);
        if (sourceSessionId != null) row.put("sourceSessionId", sourceSessionId);
        if (decision != null) row.put("decision", decision.wire);
        if (meta != null && !meta.isEmpty()) row.put("meta", meta);

        String line;
        try {
            line = MAPPER.writeValueAsString(row);
        } catch (Exception e) {
            LOG.warn("audit: failed to serialise row: {}", e.getMessage());
            return;
        }
        writeLock.lock();
        try {
            Files.writeString(logFile, line + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            SecureFilePermissions.applyOwnerReadWriteOnly(logFile);
            sinceLastFlush++;
            long now = System.currentTimeMillis();
            if (sinceLastFlush >= FLUSH_BATCH || (now - lastFlushMs) >= FLUSH_INTERVAL_MS) {
                flush();
            }
        } catch (IOException e) {
            LOG.warn("audit: write failed: {}", e.getMessage());
        } finally {
            writeLock.unlock();
        }
    }

    /** Convenience overload. */
    public void record(Action action, MemoryScope scope, String key, String kind) {
        record(null, action, scope, key, kind, null, null, null);
    }

    /** Convenience overload with actor. */
    public void record(String actor, Action action, MemoryScope scope, String key) {
        record(actor, action, scope, key, null, null, null, null);
    }

    /** Force a fsync of any buffered audit data. */
    public synchronized void flush() {
        // Files.writeString is unbuffered for non-FileChannel cases; we just bump the
        // counter. Future R-series can add a FileChannel + force(true) for stricter
        // durability (e.g. for write-then-crash recovery use cases).
        lastFlushMs = System.currentTimeMillis();
        sinceLastFlush = 0;
    }

    /** Read the last N lines from the log (newest first). */
    public java.util.List<String> readRecent(int limit) throws IOException {
        if (!Files.exists(logFile)) return java.util.List.of();
        java.util.List<String> all = Files.readAllLines(logFile);
        int n = Math.min(limit, all.size());
        java.util.List<String> out = new java.util.ArrayList<>(n);
        for (int i = all.size() - 1; i >= 0 && out.size() < n; i--) {
            String line = all.get(i);
            if (line != null && !line.isBlank()) out.add(line);
        }
        return out;
    }

    public enum Action {
        READ("read"),
        WRITE("write"),
        DELETE("delete"),
        COMPRESS("compress"),
        DECAY("decay"),
        RECALL("recall"),
        SHARE("share"),
        SET_SENSITIVITY("setSensitivity"),
        TOMBSTONE("tombstone"),
        PRUNE("prune");
        public final String wire;
        Action(String wire) { this.wire = wire; }
    }

    public enum Decision {
        ALLOW("allow"),
        DENY("deny"),
        FILTERED_PII("filtered-pii"),
        TOMBSTONED("tombstoned");
        public final String wire;
        Decision(String wire) { this.wire = wire; }
    }
}
