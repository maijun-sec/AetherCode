package org.aethercode.tasks.supervisor;

import java.util.Objects;

/**
 * prior round (T-303/§4.1.1 design.md): a row in the supervisor's
 * {@code subagent_state} table. One row per (child, subagent) pair
 * so a single child can host many subagents (e.g. one per
 * delegated task) without overwriting each other's state.
 *
 * <pre>
 *   CREATE TABLE subagent_state (
 *     child_id TEXT NOT NULL,
 *     subagent_id TEXT NOT NULL,
 *     state TEXT,           -- JSON blob
 *     updated_at INTEGER NOT NULL,
 *     PRIMARY KEY (child_id, subagent_id)
 *   );
 * </pre>
 *
 * <p>{@code state} is a free-form JSON blob the AsyncSubAgent
 * middleware owns; the supervisor only stores and retrieves it.
 */
public record SubagentStateRecord(
        String childId,
        String subagentId,
        String stateJson,
        long updatedAtMs
) {
    public SubagentStateRecord {
        Objects.requireNonNull(childId, "childId");
        Objects.requireNonNull(subagentId, "subagentId");
        if (updatedAtMs <= 0) {
            throw new IllegalArgumentException("updatedAtMs must be > 0, got " + updatedAtMs);
        }
    }
}
