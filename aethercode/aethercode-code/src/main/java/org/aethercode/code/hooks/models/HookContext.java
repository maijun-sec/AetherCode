package org.aethercode.code.hooks.models;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Per-invocation context passed alongside a hook event.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.hooks.models.domain.HookContext} record.</p>
 */
public record HookContext(
        String threadId,
        Path cwd,
        UUID promptId,
        String approvalMode,
        String effort,
        AgentIdentity agent,
        String transcriptRevision) {

    public HookContext {
        if (threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("threadId must be non-blank");
        }
        if (cwd == null) {
            throw new IllegalArgumentException("cwd must not be null");
        }
    }

    /** Convenience: a context with all optional fields unset. */
    public static HookContext of(String threadId, Path cwd) {
        return new HookContext(threadId, cwd, null, "manual", null, null, null);
    }
}
