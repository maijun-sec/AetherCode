package org.aethercode.code.hooks;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Per-invocation context passed alongside a hook event.
 *
 * <p>Java-native port of the Python
 * {@code deepagents_code.hooks.models.domain.HookContext} record. The
 * context is the smallest set of facts that handlers may need to make a
 * decision without re-resolving session state.</p>
 *
 * @param threadId     active conversation thread id
 * @param cwd          working directory inherited from the session
 * @param promptId     per-turn prompt id, when one is known
 * @param approvalMode client approval policy
 * @param effort       optional model effort level
 * @param agent        optional subagent identity
 */
public record HookContext(
        String threadId,
        Path cwd,
        UUID promptId,
        ApprovalMode approvalMode,
        String effort,
        AgentIdentity agent) {

    public HookContext {
        if (threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("threadId must be non-blank");
        }
        if (cwd == null) {
            throw new IllegalArgumentException("cwd must not be null");
        }
        if (approvalMode == null) {
            approvalMode = ApprovalMode.MANUAL;
        }
    }
}
