package org.aethercode.talon.interfaces;

import java.util.List;
import java.util.Map;

/**
 * Tool approval request surfaced to a channel operator.
 *
 * <p>Java-native port of {@code deepagents_talon.interfaces.ToolApprovalRequest}.</p>
 */
public record ToolApprovalRequest(
        String conversationId,
        String interruptId,
        List<Map<String, Object>> actionRequests) {

    public ToolApprovalRequest {
        if (conversationId == null) {
            throw new IllegalArgumentException("conversationId must not be null");
        }
        if (interruptId == null) {
            throw new IllegalArgumentException("interruptId must not be null");
        }
        actionRequests = actionRequests == null ? List.of() : List.copyOf(actionRequests);
    }
}
