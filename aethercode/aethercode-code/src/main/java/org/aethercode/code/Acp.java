package org.aethercode.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Dcode-specific ACP approval-mode adapter.
 *
 * <p>Java-native port of the Python {@code deepagents_code.acp} module.
 * The ACP server delivers user prompts and approval-mode state through
 * the {@code deepagents-acp} graph; this class wraps the
 * classifier/approval-mode surface the server builds.</p>
 */
public final class Acp {
    private Acp() {}

    private static final Logger LOG = LoggerFactory.getLogger(Acp.class);

    /** Approve a tool call (used by the ACP auto-mode). */
    public static CompletionStage<Map<String, Object>> approve(String threadId, String mode) {
        // The Java port returns a placeholder shape; the full implementation
        // is part of the deepagents-acp port.
        return CompletableFuture.completedFuture(Map.of(
                "thread_id", threadId == null ? "" : threadId,
                "approval_mode", mode == null ? ApprovalMode.Mode.AUTO.value() : mode,
                "turn_id", UUID.randomUUID().toString()));
    }

    /** Reject a tool call. */
    public static CompletionStage<Map<String, Object>> reject(String threadId, String reason) {
        return CompletableFuture.completedFuture(Map.of(
                "thread_id", threadId == null ? "" : threadId,
                "approval_mode", ApprovalMode.Mode.MANUAL.value(),
                "reason", reason == null ? "" : reason));
    }
}
