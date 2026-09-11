package org.aethercode.acp.schema;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * ACP MCP server configuration records.
 *
 * <p>Java-native port of the {@code acp.schema.McpServer*} family.
 * ACP supports HTTP, SSE, and stdio MCP transports; the Java
 * port models them as a sealed {@link McpServer} hierarchy.</p>
 */
public final class McpServers {
    private McpServers() {}

    /**
     * Common MCP server metadata. Mirrors
     * {@code acp.schema.McpServer} base class.
     */
    public sealed interface McpServer
            permits HttpMcpServer, SseMcpServer, McpServerStdio {
        /** Stable identifier (used for tool-name prefixes). */
        String name();
    }

    /** HTTP transport. */
    public record HttpMcpServer(
            String name,
            String url,
            List<String> headers) implements McpServer {
        public HttpMcpServer {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(url, "url");
            headers = headers == null ? List.of() : List.copyOf(headers);
        }
    }

    /** Server-Sent Events transport. */
    public record SseMcpServer(
            String name,
            String url,
            List<String> headers) implements McpServer {
        public SseMcpServer {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(url, "url");
            headers = headers == null ? List.of() : List.copyOf(headers);
        }
    }

    /** Local subprocess transport. */
    public record McpServerStdio(
            String name,
            String command,
            List<String> args,
            Optional<List<String>> env) implements McpServer {
        public McpServerStdio {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(command, "command");
            args = args == null ? List.of() : List.copyOf(args);
            env = env == null ? Optional.empty() : env;
        }
    }
}
