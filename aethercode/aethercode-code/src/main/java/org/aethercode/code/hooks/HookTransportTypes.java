package org.aethercode.code.hooks;

import org.aethercode.code.hooks.HookDomainEvents.Decision;

import java.time.Instant;
import java.util.UUID;

/**
 * Server↔client interrupt transport payloads.
 *
 * <p>Java-native port of the {@code deepagents_code.hooks.models.transport}
 * module. The carrier envelope is sent through the LangGraph interrupt
 * channel so the client runtime can fulfill the request.</p>
 */
public final class HookTransportTypes {

    private HookTransportTypes() {}

    /**
     * A versioned hook invocation request emitted by the server.
     */
    public record HookInvocationRequest(
            int protocolVersion,
            UUID invocationId,
            String snapshotId,
            String runId,
            HookInvocation invocation,
            Instant deadline) {

        public HookInvocationRequest {
            if (invocationId == null) {
                throw new IllegalArgumentException("invocationId must not be null");
            }
            if (snapshotId == null || snapshotId.isEmpty()) {
                throw new IllegalArgumentException("snapshotId must be non-blank");
            }
        }
    }

    /**
     * The client runtime's reply to a {@link HookInvocationRequest}.
     */
    public record HookInvocationResponse(
            int protocolVersion,
            UUID invocationId,
            String snapshotId,
            Decision decision) {
    }
}
