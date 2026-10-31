/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

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
