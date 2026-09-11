package org.aethercode.code.hooks;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Native dcode tool vocabulary mapped to compatible wire names.
 *
 * <p>Java-native port of the Python
 * {@code deepagents_code.hooks.tools} module. The table mirrors the
 * upstream dictionary; the helper functions rewrite native tool-call
 * arguments into the Claude-compatible shape expected by hook
 * commands.</p>
 */
public final class Tools {

    private static final Pattern MCP_WIRE_RE = Pattern.compile("^mcp__.+__.+$");

    // Package-private so Projection (in the same package) can instantiate it
    // without exposing the constructor to other packages.
    Tools() {}

    private static Map<String, Object> select(Map<String, Object> args, String... fields) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String f : fields) {
            if (args.containsKey(f)) {
                out.put(f, args.get(f));
            }
        }
        return out;
    }

    private static Map<String, Object> bashInput(Map<String, Object> args) {
        Map<String, Object> result = select(args, "command");
        if (args.containsKey("timeout")) {
            Object timeout = args.get("timeout");
            if (timeout instanceof Integer i) {
                result.put("timeout", i * 1000);
            } else {
                result.put("timeout", timeout);
            }
        }
        return result;
    }

    private static Map<String, Object> writeInput(Map<String, Object> args) {
        return select(args, "file_path", "content");
    }

    private static Map<String, Object> editInput(Map<String, Object> args) {
        return select(args, "file_path", "old_string", "new_string", "replace_all");
    }

    private static Map<String, Object> readInput(Map<String, Object> args) {
        Map<String, Object> result = select(args, "file_path", "limit");
        if (args.containsKey("offset")) {
            Object offset = args.get("offset");
            if (offset instanceof Integer i) {
                result.put("offset", i + 1);
            } else {
                result.put("offset", offset);
            }
        }
        return result;
    }

    private static Map<String, Object> globInput(Map<String, Object> args) {
        return select(args, "pattern", "path");
    }

    private static Map<String, Object> grepInput(Map<String, Object> args) {
        Map<String, Object> result = select(args, "path", "glob", "output_mode");
        if (args.containsKey("pattern")) {
            Object pattern = args.get("pattern");
            if (pattern instanceof String s) {
                result.put("pattern", Pattern.quote(s));
            } else {
                result.put("pattern", pattern);
            }
        }
        if (args.containsKey("max_count")) {
            result.put("head_limit", args.get("max_count"));
        }
        return result;
    }

    private static Map<String, Object> lsInput(Map<String, Object> args) {
        return select(args, "path");
    }

    private record Adapter(String wireName,
                           Function<Map<String, Object>, Map<String, Object>> mapper) {}

    private static final Map<String, Adapter> NATIVE_TO_WIRE = Map.of(
            "execute", new Adapter("Bash", Tools::bashInput),
            "write_file", new Adapter("Write", Tools::writeInput),
            "edit_file", new Adapter("Edit", Tools::editInput),
            "read_file", new Adapter("Read", Tools::readInput),
            "glob", new Adapter("Glob", Tools::globInput),
            "grep", new Adapter("Grep", Tools::grepInput),
            "ls", new Adapter("LS", Tools::lsInput));

    /**
     * Format a compatible MCP wire tool name.
     *
     * @param server MCP server name
     * @param tool   bare MCP tool name
     * @return {@code mcp__{server}__{tool}}
     */
    public static String formatMcpWireName(String server, String tool) {
        return "mcp__" + server + "__" + tool;
    }

    /**
     * Map a native tool name to the compatible wire tool name.
     */
    public static String toWireToolName(String name, String mcpServer) {
        if (name == null) {
            return "";
        }
        if (MCP_WIRE_RE.matcher(name).matches()) {
            return name;
        }
        if (mcpServer != null && !mcpServer.isEmpty()) {
            String prefix = mcpServer + "_";
            String tool = name.startsWith(prefix) ? name.substring(prefix.length()) : name;
            return formatMcpWireName(mcpServer, tool);
        }
        Adapter adapter = NATIVE_TO_WIRE.get(name);
        return adapter != null ? adapter.wireName() : name;
    }

    /**
     * Map native tool arguments to the compatible wire input object.
     */
    public static Map<String, Object> toWireToolInput(String name, Map<String, Object> args) {
        if (args == null) {
            return Map.of();
        }
        Adapter adapter = NATIVE_TO_WIRE.get(name);
        return adapter != null ? adapter.mapper().apply(args) : new LinkedHashMap<>(args);
    }

    /**
     * Project a native tool call into compatible wire name and input.
     */
    public static WireCall toWireCall(ToolCallData call) {
        String name = toWireToolName(call.name(), call.mcpServer());
        if (call.mcpServer() != null || MCP_WIRE_RE.matcher(call.name()).matches()) {
            return new WireCall(name, new LinkedHashMap<>(call.args()));
        }
        return new WireCall(name, toWireToolInput(call.name(), call.args()));
    }

    /** Tuple of (wire name, wire input). */
    public record WireCall(String name, Map<String, Object> input) {}
}
