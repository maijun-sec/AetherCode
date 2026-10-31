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
