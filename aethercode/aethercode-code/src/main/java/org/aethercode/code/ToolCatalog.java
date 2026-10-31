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

package org.aethercode.code;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Tool catalog for the {@code /tools} TUI screen.
 *
 * <p>Java-native port of the Python {@code deepagents_code.tool_catalog}
 * module. The Java port exposes a small registry abstraction; the TUI
 * host populates it with the tools the agent has bound for the current
 * session.</p>
 */
public final class ToolCatalog {
    private ToolCatalog() {}

    /** One registered tool. */
    public record Tool(
            String name,
            String description,
            String category,
            boolean readOnly,
            boolean requiresTrust) {
    }

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    /** Register a tool. */
    public void register(Tool tool) {
        if (tool == null) return;
        tools.put(tool.name(), tool);
    }

    /** Look up a tool by name. */
    public Tool get(String name) {
        return tools.get(name);
    }

    /** All tools in registration order. */
    public Map<String, Tool> all() {
        return Map.copyOf(tools);
    }

    /** All tool names. */
    public Set<String> names() {
        return Set.copyOf(tools.keySet());
    }
}
