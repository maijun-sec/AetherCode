package org.aethercode.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * MCP login service (stub).
 *
 * <p>Java-native port of the Python {@code deepagents_code.mcp_login_service}
 * module. The Java port exposes the public surface used by the
 * {@code dcode mcp login} command; the full implementation lands with
 * the MCP auth port.</p>
 */
public final class McpLoginService {
    private McpLoginService() {}

    private static final Logger LOG = LoggerFactory.getLogger(McpLoginService.class);

    /** Login to an MCP server. */
    public static CompletionStage<Boolean> login(String serverName) {
        LOG.info("MCP login (stub) for {}", serverName);
        return CompletableFuture.completedFuture(false);
    }
}
