package org.aethercode.deepagents.middleware;

import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * A tool entry the subagent can use.
 *
 * <p>Java-native port of the union
 * {@code BaseTool | Callable | dict[str, Any]} from
 * {@code deepagents.middleware.subagents.SubAgent.tools}. The Java
 * port uses a sealed record-like carrier that wraps either a named
 * {@link org.aethercode.deepagents.tools.Tool Tool} reference, a plain
 * {@link java.util.function.BiFunction BiFunction} callable, or a
 * name + args-schema map.</p>
 */
public sealed interface SubAgentTool
        permits SubAgentTool.ToolRef, SubAgentTool.CallableRef, SubAgentTool.MapSpec {

    String name();

    static SubAgentTool of(org.aethercode.deepagents.tools.Tool tool) {
        return new ToolRef(Objects.requireNonNull(tool, "tool").name(), tool);
    }
    static SubAgentTool of(String name, BiFunction<java.util.Map<String, Object>,
            org.aethercode.deepagents.tools.Tool.ToolContext, Object> callable) {
        return new CallableRef(Objects.requireNonNull(name, "name"),
                Objects.requireNonNull(callable, "callable"));
    }
    static SubAgentTool ofMap(Map<String, Object> spec) {
        Object n = spec.get("name");
        if (!(n instanceof String s)) {
            throw new IllegalArgumentException("tool map spec must include a 'name' string");
        }
        return new MapSpec(s, spec);
    }

    record ToolRef(String name, org.aethercode.deepagents.tools.Tool tool) implements SubAgentTool {}
    record CallableRef(String name, BiFunction<Map<String, Object>,
            org.aethercode.deepagents.tools.Tool.ToolContext, Object> callable) implements SubAgentTool {}
    record MapSpec(String name, Map<String, Object> spec) implements SubAgentTool {
        public MapSpec {
            spec = spec == null ? Map.of() : Map.copyOf(spec);
        }
    }
}
