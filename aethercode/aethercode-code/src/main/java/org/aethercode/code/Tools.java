package org.aethercode.code;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool registry helpers.
 *
 * <p>Java-native port of the Python {@code deepagents_code.tools} module.
 * The Java port exposes the small set of helpers used by the agent
 * graph factory when binding tools; the full tool definitions live in
 * the {@code deepagents-core} port.</p>
 */
public final class Tools {
    private Tools() {}

    /** A built-in tool name. */
    public record ToolDef(
            String name,
            String description,
            boolean readOnly) {
    }

    /** All built-in tools. */
    public static final List<ToolDef> BUILT_INS = List.of(
            new ToolDef("ls", "List directory entries", true),
            new ToolDef("read_file", "Read a file", true),
            new ToolDef("write_file", "Write a file", false),
            new ToolDef("edit_file", "Edit a file", false),
            new ToolDef("delete", "Delete a file", false),
            new ToolDef("glob", "Glob for paths", true),
            new ToolDef("grep", "Search file contents", true),
            new ToolDef("execute", "Execute a shell command", false),
            new ToolDef("web_search", "Search the web", true),
            new ToolDef("fetch_url", "Fetch a URL", true),
            new ToolDef("ask_user", "Ask the user a question", false),
            new ToolDef("task", "Run a subagent", false));

    /** The full set of tool names. */
    public static List<String> names() {
        return BUILT_INS.stream().map(ToolDef::name).toList();
    }
}
