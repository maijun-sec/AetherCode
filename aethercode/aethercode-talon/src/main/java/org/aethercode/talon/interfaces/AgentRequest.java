package org.aethercode.talon.interfaces;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Agent invocation request from a channel or scheduler.
 *
 * <p>Java-native port of {@code deepagents_talon.interfaces.AgentRequest}.</p>
 */
public record AgentRequest(
        String conversationId,
        String text,
        Map<String, Object> metadata,
        ApprovalHandler approvalHandler) {

    public AgentRequest {
        if (conversationId == null) {
            throw new IllegalArgumentException("conversationId must not be null");
        }
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public AgentRequest(String conversationId, String text) {
        this(conversationId, text, Map.of(), null);
    }

    public AgentRequest(String conversationId, String text, Map<String, Object> metadata) {
        this(conversationId, text, metadata, null);
    }

    /**
     * Functional surface for the approval handler.
     */
    @FunctionalInterface
    public interface ApprovalHandler extends Function<ToolApprovalRequest, CompletableFuture<ToolApprovalDecision>> {
    }
}
