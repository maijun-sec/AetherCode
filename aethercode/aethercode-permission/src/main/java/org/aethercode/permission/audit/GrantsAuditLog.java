package org.aethercode.permission.audit;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.aethercode.core.config.SecureFilePermissions;
import org.aethercode.permission.grants.Grant;
import org.aethercode.permission.grants.GrantDecision;
import org.aethercode.permission.grants.GrantPaths;
import org.aethercode.permission.grants.GrantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * T-260..T-263 / design.md §3.5: append-only audit log of
 * grant lifecycle events.
 *
 * <p>Writes one JSONL record per line to
 * {@code <UserHome>/.aethercode/grants.log.jsonl}. The file
 * is append-only — every consent-related event ends up here
 * for the audit review pane and post-incident analysis.
 *
 * <p>Three event kinds:
 * <ul>
 *   <li>{@link EventKind#CREATED} — a {@link Grant} was
 *       added to a {@code grants.json} (T-261). The record
 *       carries the full grant payload so an analyst can
 *       see exactly which scope + category + decision +
 *       reason the user picked.</li>
 *   <li>{@link EventKind#REVOKED} — a grant was removed
 *       via {@code permission/revoke} or
 *       {@code permission/clear} (T-262). The record
 *       carries the grant id + the original scope /
 *       category so the audit trail is still readable
 *       after the grant is gone.</li>
 *   <li>{@link EventKind#PROMPTED} — a consent prompt
 *       was answered, with the outcome (allow / deny) +
 *       the option the user picked (T-263). The record
 *       also carries the categorisation's matched
 *       rules so an analyst can see "the user denied
 *       rm -rf even though the categoriser flagged it
 *       as high risk" without needing the full
 *       category list.</li>
 * </ul>
 *
 * <p>Wire format (one JSON object per line):
 * <pre>
 * {
 *   "ts":         1700000000000,                // epoch ms
 *   "user":       "alice",                      // who (or "system" if null)
 *   "kind":       "created" | "revoked" | "prompted",
 *   "scope":      "session" | "project" | "user" | null,
 *   "scopeId":    "sess-1" | null,
 *   "category":   "shell.command" | null,
 *   "decision":   "allow" | "deny" | null,
 *   "reason":     "user-picked" | null,
 *   "grantId":    "abc..." | null,
 *   "matchedRules": ["shell.destructive"] | [],
 *   "callSummary": "rm -rf /" | null
 * }
 * </pre>
 *
 * <p>Append semantics: the writer opens with
 * {@code CREATE | APPEND} (so the file is created on first
 * use and existing content is preserved) and writes one
 * record per call. Writes are guarded by a {@link ReentrantLock}
 * so concurrent writers don't interleave half-records.
 *
 * <p>Rotation: by default the log grows unbounded. Tests
 * can pass a custom path; production keeps the same path
 * forever (the file is small — every record is &lt; 1 KiB
 * — and a typical user generates a few grants a week, so
 * the log is dwarfed by the user-code git history).
 *
 * <p>Failure policy: a failed write logs at warn and is
 * swallowed. The audit log is best-effort; the runtime
 * must not break because the log write failed.
 */
public final class GrantsAuditLog {

    private static final Logger LOG = LoggerFactory.getLogger(GrantsAuditLog.class);

    /** Wire name of the log file (constant so callers don't drift). */
    public static final String FILE_NAME = "grants.log.jsonl";

    /** The three event kinds. Wire names are lower-case. */
    public enum EventKind {
        CREATED("created"),
        REVOKED("revoked"),
        PROMPTED("prompted");

        private final String wire;
        EventKind(String wire) { this.wire = wire; }

        /** Lower-case wire name (matches the design's JSONL
         *  shape). */
        @com.fasterxml.jackson.annotation.JsonValue
        public String wire() { return wire; }

        @com.fasterxml.jackson.annotation.JsonCreator
        public static EventKind fromWire(String s) {
            if (s == null) throw new IllegalArgumentException("kind is null");
            for (EventKind v : values()) {
                if (v.wire.equals(s)) return v;
            }
            throw new IllegalArgumentException("unknown audit kind: " + s);
        }
    }

    /**
     * One line of the JSONL log. Public so the audit
     * "review" pane and tests can re-hydrate a snapshot
     * for display.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"ts", "user", "kind", "scope", "scopeId",
            "category", "decision", "reason", "grantId",
            "matchedRules", "callSummary"})
    public record Entry(
            @JsonProperty("ts") long ts,
            @JsonProperty("user") String user,
            @JsonProperty("kind") EventKind kind,
            @JsonProperty("scope") GrantScope scope,
            @JsonProperty("scopeId") String scopeId,
            @JsonProperty("category") String category,
            @JsonProperty("decision") GrantDecision decision,
            @JsonProperty("reason") String reason,
            @JsonProperty("grantId") String grantId,
            @JsonProperty("matchedRules") List<String> matchedRules,
            @JsonProperty("callSummary") String callSummary
    ) {

        @JsonCreator
        public Entry {
            // Normalise nulls — an absent field reads as
            // null in Jackson, but the JSONL "review"
            // pane expects an empty list rather than null
            // for matchedRules. We do it in the compact
            // ctor so callers can rely on the record
            // being self-consistent.
            if (matchedRules == null) {
                matchedRules = List.of();
            } else {
                matchedRules = List.copyOf(matchedRules);
            }
            if (user == null) {
                user = "system";
            }
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    private final Path file;
    private final ReentrantLock writeLock = new ReentrantLock();

    /** Construct a log rooted at {@code file}. The file is
     *  created on the first {@code append} call; missing
     *  parents are made. A null file is permitted — every
     *  method becomes a no-op (used by the sandbox case
     *  where the user has no home directory). */
    public GrantsAuditLog(Path file) {
        this.file = file;
    }

    /** Convenience constructor that points at
     *  {@code <userHome>/.aethercode/grants.log.jsonl}. The
     *  {@code userHome} is allowed to be null only in
     *  sandbox scenarios where the user has no home — the
     *  resulting log is a no-op (calls return null and the
     *  file is null). */
    public static GrantsAuditLog forUserHome(java.nio.file.Path userHome) {
        return new GrantsAuditLog(GrantPaths.userAuditLogFile(userHome));
    }

    /** The file we write to. Null when the user home was
     *  null at construction (sandbox). */
    public Path file() {
        return file;
    }

    // ------------------------------------------------------------------
    //  T-261 — log every grant created
    // ------------------------------------------------------------------

    /**
     * Record that {@code grant} was just appended to a
     * grants file. The {@code actor} is the user who made
     * the decision (or "system" for CLI-driven grants).
     */
    public Entry logCreated(Grant grant, String actor) {
        Objects.requireNonNull(grant, "grant");
        Entry e = new Entry(
                System.currentTimeMillis(),
                actor,
                EventKind.CREATED,
                grant.scope(),
                grant.scopeId(),
                grant.category(),
                grant.decision(),
                grant.reason(),
                grant.id(),
                List.of(),
                null
        );
        writeEntry(e);
        return e;
    }

    // ------------------------------------------------------------------
    //  T-262 — log every grant revoked
    // ------------------------------------------------------------------

    /**
     * Record that {@code grant} was just revoked. The
     * grant is captured by value (the audit record carries
     * its id / scope / category / decision) so the entry
     * remains meaningful after the grant is gone from
     * {@code grants.json}.
     */
    public Entry logRevoked(Grant grant, String actor) {
        Objects.requireNonNull(grant, "grant");
        Entry e = new Entry(
                System.currentTimeMillis(),
                actor,
                EventKind.REVOKED,
                grant.scope(),
                grant.scopeId(),
                grant.category(),
                grant.decision(),
                "revoked by " + (actor == null ? "system" : actor),
                grant.id(),
                List.of(),
                null
        );
        writeEntry(e);
        return e;
    }

    /**
     * Bulk-revoke variant for {@code permission/clear}.
     * Writes one REVOKED entry per grant so the audit trail
     * is granular enough to undo a clear (re-grant the
     * individual ids). Returns the list of entries in
     * the same order as {@code revoked}.
     */
    public List<Entry> logRevokedBulk(List<Grant> revoked, String actor) {
        Objects.requireNonNull(revoked, "revoked");
        List<Entry> out = new ArrayList<>(revoked.size());
        for (Grant g : revoked) {
            out.add(logRevoked(g, actor));
        }
        return out;
    }

    // ------------------------------------------------------------------
    //  T-263 — log every consent prompt (allow/deny outcome)
    // ------------------------------------------------------------------

    /**
     * Record a consent prompt outcome. The {@code scope} +
     * {@code category} + {@code decision} are the option
     * the user picked; {@code matchedRules} is the
     * categoriser's rule trace so the audit shows
     * <em>why</em> the prompt fired in the first place.
     */
    public Entry logPrompted(
            GrantScope scope,
            String scopeId,
            String category,
            GrantDecision decision,
            String reason,
            List<String> matchedRules,
            String callSummary,
            String actor
    ) {
        Entry e = new Entry(
                System.currentTimeMillis(),
                actor,
                EventKind.PROMPTED,
                scope,
                scopeId,
                category,
                decision,
                reason,
                null,
                matchedRules == null ? List.of() : matchedRules,
                callSummary
        );
        writeEntry(e);
        return e;
    }

    // ------------------------------------------------------------------
    //  Read
    // ------------------------------------------------------------------

    /**
     * Read every entry currently on disk. Returns an
     * empty list if the file does not exist. A corrupt
     * line is skipped (the rest of the file is still
     * useful) — the file is append-only, so a single bad
     * line shouldn't poison the whole log.
     *
     * <p>Used by the audit "review" pane + tests.
     */
    public List<Entry> readAll() {
        if (file == null || !Files.exists(file)) return List.of();
        List<Entry> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                try {
                    out.add(MAPPER.readValue(line, Entry.class));
                } catch (Exception jpe) {
                    // Skip the bad line — see class javadoc.
                    LOG.warn("R-grants-audit: skipping corrupt line: {}", jpe.toString());
                }
            }
        } catch (IOException ioe) {
            LOG.warn("R-grants-audit: read failed: {} ({})", file, ioe.toString());
        }
        return out;
    }

    /** Total entries on disk. Convenience for tests. */
    public long count() {
        if (file == null) return 0L;
        return readAll().size();
    }

    /** Most recent entry, or null if the log is empty. */
    public Entry last() {
        if (file == null) return null;
        List<Entry> all = readAll();
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    /** Snapshot stats — useful for the audit pane header. */
    public Map<String, Long> stats() {
        Map<String, Long> out = new LinkedHashMap<>();
        long created = 0, revoked = 0, prompted = 0;
        if (file == null) {
            out.put("total", 0L);
            out.put("created", 0L);
            out.put("revoked", 0L);
            out.put("prompted", 0L);
            return out;
        }
        for (Entry e : readAll()) {
            switch (e.kind()) {
                case CREATED  -> created++;
                case REVOKED  -> revoked++;
                case PROMPTED -> prompted++;
            }
        }
        out.put("total",    created + revoked + prompted);
        out.put("created",  created);
        out.put("revoked",  revoked);
        out.put("prompted", prompted);
        return out;
    }

    // ------------------------------------------------------------------
    //  internals
    // ------------------------------------------------------------------

    private void writeEntry(Entry e) {
        if (file == null) return;  // sandbox
        String json;
        try {
            json = MAPPER.writeValueAsString(e);
        } catch (Exception jpe) {
            LOG.warn("R-grants-audit: serialize failed: {}", jpe.toString());
            return;
        }
        writeLock.lock();
        try {
            Path parent = file.getParent();
            if (parent != null) {
                try {
                    Files.createDirectories(parent);
                } catch (IOException ioe) {
                    LOG.warn("R-grants-audit: mkdir failed: {} ({})", parent, ioe.toString());
                    return;
                }
            }
            byte[] bytes = (json + "\n").getBytes(StandardCharsets.UTF_8);
            try {
                Files.write(file, bytes,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
                // T-507 / design.md §7: 0600 on the
                // audit log (it carries user-typed
                // reasons). Best-effort; helper never
                // throws. On Windows the call is a
                // no-op.
                SecureFilePermissions.applyOwnerReadWriteOnly(file);
            } catch (IOException ioe) {
                LOG.warn("R-grants-audit: write failed: {} ({})", file, ioe.toString());
            }
        } finally {
            writeLock.unlock();
        }
    }

    /** Build a "now" timestamp string for log message. */
    static String now() {
        return Instant.now().toString();
    }
}
