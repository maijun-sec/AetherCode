package org.aethercode.mcp.stdio;

/**
 * Marker interface for any MCP transport. prior round has stdio and SSE; future rounds can add
 * Streamable HTTP, WebSocket, etc. without touching the rest of the framework.
 */
public interface McpProtocol {
    // intentionally empty — exists so callers can pin a "transport" type if they want.
}
