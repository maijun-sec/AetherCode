package org.aethercode.deepagents.tools;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Helpers for inspecting and rewriting {@code create_deep_agent} tool inputs.
 *
 * <p>Java-native port of the Python {@code deepagents._tools} module.
 * The helpers cover the two operations the graph builder needs when wiring
 * tools into an agent: extracting a tool's name (from a {@link Tool} instance,
 * a plain {@link java.util.concurrent.Callable}, or a {@code Map<String,Object>}
 * spec), and applying description overrides without mutating caller-owned
 * tools.</p>
 *
 * <p>The Python port treats {@code BaseTool} as a special class; the Java
 * port uses a uniform {@link Tool} interface (see {@code org.aethercode.deepagents.tools.Tool})
 * so the same {@code toolName} and {@code applyToolDescriptionOverrides} logic
 * applies to all tool types.</p>
 */
public final class ToolHelper {
    private ToolHelper() {}

    /**
     * Extract the tool name from any supported tool type.
     *
     * @param tool A tool in any of the forms accepted by {@code create_deep_agent}.
     * @return The tool name, or {@code null} if it cannot be determined.
     */
    public static String toolName(Object tool) {
        if (tool == null) return null;
        if (tool instanceof Tool t) {
            String n = t.name();
            return n == null || n.isEmpty() ? null : n;
        }
        if (tool instanceof Map<?, ?> map) {
            Object name = map.get("name");
            return name instanceof String s && !s.isEmpty() ? s : null;
        }
        if (tool instanceof java.util.concurrent.Callable<?> c) {
            // Use the declared method name when the tool is a plain callable.
            // Lambdas don't expose a useful name; null in that case.
            return c.getClass().isSynthetic() ? null : c.getClass().getSimpleName();
        }
        return null;
    }

    /**
     * Apply description overrides without mutating caller-owned tools.
     *
     * <p>Only {@link Tool} instances and {@code Map<String,Object>} specs are
     * rewritten; plain {@link java.util.concurrent.Callable}s are returned
     * unchanged because safely replacing their descriptions would require
     * wrapping them in new tool objects.</p>
     *
     * @param tools User-supplied tools to copy and possibly rewrite.
     * @param overrides Description overrides keyed by tool name.
     * @param <T> The tool type (preserved across the rewrite).
     * @return A copied tool list with supported overrides applied, or {@code null}
     *         when {@code tools} is null.
     */
    @SuppressWarnings("unchecked")
    public static <T> List<T> applyToolDescriptionOverrides(List<T> tools, Map<String, String> overrides) {
        if (tools == null) return null;
        Objects.requireNonNull(overrides, "overrides");
        java.util.List<T> out = new java.util.ArrayList<>(tools.size());
        for (T tool : tools) {
            String name = toolName(tool);
            String override = (name == null) ? null : overrides.get(name);
            if (override == null) {
                out.add(tool);
                continue;
            }
            if (tool instanceof Tool t) {
                out.add((T) t.withDescription(override));
                continue;
            }
            if (tool instanceof Map<?, ?> map) {
                java.util.Map<String, Object> copy = new java.util.LinkedHashMap<>((Map<String, Object>) map);
                copy.put("description", override);
                out.add((T) copy);
                continue;
            }
            // Plain callables can't be safely rewritten.
            out.add(tool);
        }
        return out;
    }
}
