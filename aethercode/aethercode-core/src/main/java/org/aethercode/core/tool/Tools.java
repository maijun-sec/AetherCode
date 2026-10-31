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

package org.aethercode.core.tool;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.aethercode.core.permission.PermissionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Public factory helpers for building {@link Tool} instances.
 *
 * <p>Wraps a {@link ToolDef} into a {@link Tool} via {@link #build}.
 * The other helpers ({@link #objectSchema}, {@link #stringProp},
 * {@link #intProp}, {@link #boolProp}) are convenience constructors
 * for the common JSON-schema shape used in tool definitions.</p>
 */
public final class Tools {
    private static final Logger LOG = LoggerFactory.getLogger(Tools.class);

    private Tools() {}

    /**
     * Wrap a {@link ToolDef} as a {@link Tool}. The returned tool
     * delegates {@code name}/{@code description}/{@code inputSchema}
     * to the {@code ToolDef} and forwards {@code call} / {@code
     * checkPermissions} to the lambdas the def captured.
     */
    public static Tool build(ToolDef def) {
        return new BuiltTool(def);
    }

    /**
     * Find a tool by name in a collection; null-safe.
     */
    public static Tool byName(Iterable<Tool> tools, String name) {
        if (tools == null || name == null) return null;
        for (Tool t : tools) {
            if (t != null && name.equals(t.name())) return t;
        }
        return null;
    }

    /** Build a JSON-schema object with the given required properties. */
    public static Map<String, Object> objectSchema(LinkedHashMap<String, Map<String, Object>> properties,
                                                    String... required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (required != null && required.length > 0) {
            schema.put("required", java.util.Arrays.asList(required));
        }
        return schema;
    }

    /** Property descriptor for a string-typed argument. */
    public static Map<String, Object> stringProp(String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "string");
        if (description != null) p.put("description", description);
        return p;
    }

    /** Property descriptor for an int-typed argument. */
    public static Map<String, Object> intProp(String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "integer");
        if (description != null) p.put("description", description);
        return p;
    }

    /** Property descriptor for a boolean-typed argument. */
    public static Map<String, Object> boolProp(String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "boolean");
        if (description != null) p.put("description", description);
        return p;
    }

    /** Internal {@link Tool} backed by a {@link ToolDef}. */
    static final class BuiltTool implements Tool {
        private final ToolDef def;
        BuiltTool(ToolDef def) { this.def = def; }

        @Override public String name() { return def.name(); }
        @Override public String description() { return def.description(); }
        @Override public Map<String, Object> inputSchema() { return def.inputSchema(); }

        @Override
        public CompletableFuture<PermissionResult> checkPermissions(
                Map<String, Object> input, Tool.CallContext ctx) {
            if (def.checkPermissions() == null) {
                return CompletableFuture.completedFuture(new PermissionResult.Allow(input));
            }
            return def.checkPermissions().apply(input, ctx);
        }

        @Override
        public CompletableFuture<Tool.ToolResult> call(Map<String, Object> input, Tool.CallContext ctx) {
            return def.call().apply(input, ctx);
        }
    }

    // Imported for callers that reference the result type explicitly.
    private static final Class<?> PERMISSION_RESULT = PermissionResult.class;
    private static final Class<?> TOOL_RESULT = Tool.ToolResult.class;
}
