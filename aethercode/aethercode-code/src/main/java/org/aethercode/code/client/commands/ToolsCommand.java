package org.aethercode.code.client.commands;

import java.util.List;

/**
 * Tools command handlers.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.client.commands.tools} module. The Java
 * port exposes a small command API for listing and inspecting
 * tools through a {@link ToolsBackend}.</p>
 */
public final class ToolsCommand {
    private ToolsCommand() {}

    /**
     * Result of a tools command.
     */
    public record Result(String text, int exitCode) {
        public static Result ok(String text) { return new Result(text, 0); }
        public static Result error(String text) { return new Result(text, 1); }
    }

    /** Tool descriptor. */
    public record ToolDescriptor(String name, String description, boolean enabled) {
    }

    /** Tools backend. */
    public interface ToolsBackend {
        List<ToolDescriptor> listTools();
        boolean toggle(String name, boolean enabled);
    }

    /** Sub-command id. */
    public enum SubCommand { LIST, ENABLE, DISABLE }

    /**
     * Dispatch a tools command.
     */
    public static Result dispatch(ToolsBackend backend, SubCommand subCommand, String name) {
        if (backend == null) return Result.error("Tools backend is not configured.");
        return switch (subCommand) {
            case LIST -> {
                List<ToolDescriptor> tools = backend.listTools();
                StringBuilder sb = new StringBuilder();
                for (ToolDescriptor t : tools) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(t.enabled() ? "✓ " : "  ")
                            .append(t.name()).append(" — ").append(t.description());
                }
                yield Result.ok(sb.length() == 0 ? "No tools." : sb.toString());
            }
            case ENABLE -> toggle(backend, name, true);
            case DISABLE -> toggle(backend, name, false);
        };
    }

    private static Result toggle(ToolsBackend backend, String name, boolean enabled) {
        if (name == null) return Result.error("usage: tools enable|disable <name>");
        return backend.toggle(name, enabled)
                ? Result.ok((enabled ? "Enabled " : "Disabled ") + name)
                : Result.error("Failed to update tool " + name);
    }
}
