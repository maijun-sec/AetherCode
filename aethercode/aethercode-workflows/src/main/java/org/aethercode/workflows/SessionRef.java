package org.aethercode.workflows;

import java.util.Objects;

/**
 * Lightweight reference to a session spawned by a workflow run.
 * Mirrors the {@code session/spawn} JSON-RPC return shape: an id
 * plus the cwd / model / workflow name that produced it.
 *
 * <p>Defined in this module because the spec doesn't pick a home
 * for it (aethercode-protocol doesn't currently have a session
 * model; the task-supervisor's {@code task/spawn} returns
 * {@code childId} only). The CLI's {@code ac workflow run}
 * prints the id and stores the rest so a follow-up
 * {@code ac session show <id>} can find the right row.
 */
public record SessionRef(
        String id,
        String cwd,
        String model,
        String workflowName,
        java.util.Map<String, Object> config
) {

    public SessionRef {
        Objects.requireNonNull(id, "id");
        if (cwd == null) cwd = "";
        if (model == null) model = "";
        if (workflowName == null) workflowName = "";
        if (config == null) config = java.util.Map.of();
    }

    /** Compact "id (workflow=name)" form for log lines. */
    public String shortLabel() {
        if (workflowName.isEmpty()) return id;
        return id + " (workflow=" + workflowName + ")";
    }
}
