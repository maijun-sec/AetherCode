package org.aethercode.code.hooks.models;

import java.time.Instant;
import java.util.UUID;

/**
 * Versioned hook invocation transport models.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.hooks.models.transport} module. The current
 * protocol version is {@code 1}.</p>
 */
public final class HookInvocationRequest {
    private HookInvocationRequest() {}

    /** Current protocol version. */
    public static final int PROTOCOL_VERSION = 1;

    /**
     * Request sent for a server-owned hook invocation.
     */
    public record Request(
            int protocolVersion,
            UUID invocationId,
            String snapshotId,
            String runId,
            HookInvocation invocation,
            Instant deadline) {
        public Request {
            if (protocolVersion != PROTOCOL_VERSION) {
                throw new IllegalArgumentException(
                        "unsupported protocol version: " + protocolVersion);
            }
        }
    }

    /**
     * Response returned for a server-owned hook invocation.
     */
    public record Response(
            int protocolVersion,
            UUID invocationId,
            String snapshotId,
            HookDecision decision) {
        public Response {
            if (protocolVersion != PROTOCOL_VERSION) {
                throw new IllegalArgumentException(
                        "unsupported protocol version: " + protocolVersion);
            }
        }
    }
}
