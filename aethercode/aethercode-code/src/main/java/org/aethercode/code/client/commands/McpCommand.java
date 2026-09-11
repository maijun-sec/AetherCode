package org.aethercode.code.client.commands;

import java.util.List;

/**
 * MCP command handlers.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.client.commands.mcp} module. Exposes
 * list / connect / disconnect / login operations over a small
 * {@link McpBackend} interface.</p>
 */
public final class McpCommand {
    private McpCommand() {}

    /**
     * Result of an MCP command.
     */
    public record Result(String text, int exitCode) {
        public static Result ok(String text) { return new Result(text, 0); }
        public static Result error(String text) { return new Result(text, 1); }
    }

    /** Underlying MCP backend. */
    public interface McpBackend {
        List<McpServer> listServers();
        boolean connect(String name);
        boolean disconnect(String name);
        void login(String name);
    }

    /** MCP server metadata. */
    public record McpServer(String name, String status, boolean needsLogin) {
    }

    /** Sub-command id. */
    public enum SubCommand { LIST, CONNECT, DISCONNECT, LOGIN }

    /**
     * Dispatch an MCP command.
     */
    public static Result dispatch(McpBackend backend, SubCommand subCommand, String name) {
        if (backend == null) return Result.error("MCP backend is not configured.");
        return switch (subCommand) {
            case LIST -> {
                List<McpServer> servers = backend.listServers();
                StringBuilder sb = new StringBuilder();
                for (McpServer s : servers) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(s.name()).append(" — ").append(s.status());
                }
                yield Result.ok(sb.length() == 0 ? "No MCP servers configured." : sb.toString());
            }
            case CONNECT -> {
                if (name == null) yield Result.error("usage: mcp connect <name>");
                yield backend.connect(name) ? Result.ok("Connected to " + name)
                        : Result.error("Failed to connect to " + name);
            }
            case DISCONNECT -> {
                if (name == null) yield Result.error("usage: mcp disconnect <name>");
                yield backend.disconnect(name) ? Result.ok("Disconnected from " + name)
                        : Result.error("Failed to disconnect " + name);
            }
            case LOGIN -> {
                if (name == null) yield Result.error("usage: mcp login <name>");
                backend.login(name);
                yield Result.ok("Started login for " + name);
            }
        };
    }
}
