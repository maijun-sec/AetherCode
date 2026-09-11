package org.aethercode.tasks.supervisor;

import java.util.Objects;
import java.util.Optional;

/**
 * prior round (T-301/§4.1.1 design.md): a row in the supervisor's
 * {@code children} table. The record mirrors the SQL columns 1:1:
 *
 * <pre>
 *   CREATE TABLE children (
 *     id TEXT PRIMARY KEY,
 *     parent_session_id TEXT,
 *     cwd TEXT NOT NULL,
 *     status TEXT NOT NULL,
 *     prompt TEXT,
 *     created_at INTEGER NOT NULL,
 *     started_at INTEGER,
 *     ended_at INTEGER,
 *     config TEXT,        -- JSON
 *     state TEXT,         -- JSON
 *     limits_hit TEXT,    -- JSON
 *     error TEXT,
 *     title TEXT,                -- v2 (T-1-11)
 *     last_active_at INTEGER,    -- v2
 *     trashed_at INTEGER         -- v2
 *   );
 * </pre>
 *
 * <p>JSON-typed columns (config / state / limits_hit) are exposed as
 * raw {@link String}; callers that need typed access go through
 * {@link com.fasterxml.jackson.databind.ObjectMapper}.
 */
public record ChildRecord(
        String id,
        String parentSessionId,
        String cwd,
        ChildStatus status,
        String prompt,
        long createdAtMs,
        Long startedAtMs,
        Long endedAtMs,
        String configJson,
        String stateJson,
        String limitsHitJson,
        String error,
        String title,
        Long lastActiveAtMs,
        Long trashedAtMs
) {
    public ChildRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(cwd, "cwd");
        Objects.requireNonNull(status, "status");
        if (createdAtMs <= 0) {
            throw new IllegalArgumentException("createdAtMs must be > 0, got " + createdAtMs);
        }
    }

    public Optional<Long> startedAt() { return Optional.ofNullable(startedAtMs); }
    public Optional<Long> endedAt() { return Optional.ofNullable(endedAtMs); }
    public Optional<String> errorOpt() { return Optional.ofNullable(error); }
    public Optional<String> titleOpt() { return Optional.ofNullable(title); }
    public Optional<Long> lastActiveAt() { return Optional.ofNullable(lastActiveAtMs); }
    public Optional<Long> trashedAt() { return Optional.ofNullable(trashedAtMs); }

    /** True for a child the supervisor must resume on startup. */
    public boolean isResumable() {
        return status == ChildStatus.RUNNING || status == ChildStatus.PAUSED
                || status == ChildStatus.QUEUED;
    }

    /**
     * True when the row is in the trash (soft-deleted). The
     * {@code trashed_at} column is the timestamp the row was
     * moved to trash; the reaper task uses it to enforce the
     * 30-day retention.
     */
    public boolean isTrashed() {
        return trashedAtMs != null;
    }

    /** Compatibility constructor for v1 callers (no session fields). */
    public ChildRecord(String id, String parentSessionId, String cwd,
                       ChildStatus status, String prompt, long createdAtMs,
                       Long startedAtMs, Long endedAtMs,
                       String configJson, String stateJson,
                       String limitsHitJson, String error) {
        this(id, parentSessionId, cwd, status, prompt, createdAtMs,
                startedAtMs, endedAtMs, configJson, stateJson, limitsHitJson,
                error, null, null, null);
    }
}
