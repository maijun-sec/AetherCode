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

package org.aethercode.deepagents.langchain_compat.messages;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * LangChain-compatible tool call structure.
 *
 * <p>Java-native port of
 * {@code langchain_core.messages.ToolCall}. A tool call has a
 * name, args (parsed JSON dict), an id, and a {@code type} field
 * that defaults to {@code "tool_call"}.</p>
 */
public final class ToolCall {
    private final String name;
    private final Map<String, Object> args;
    private final String id;
    private final String type;

    public ToolCall(String name, Map<String, Object> args, String id, String type) {
        this.name = Objects.requireNonNull(name, "name");
        this.args = args == null ? Map.of() : Map.copyOf(args);
        this.id = id;
        this.type = type == null ? "tool_call" : type;
    }

    public ToolCall(String name, Map<String, Object> args, String id) {
        this(name, args, id, "tool_call");
    }

    public ToolCall(String name, Map<String, Object> args) {
        this(name, args, null, "tool_call");
    }

    public String name() { return name; }
    public Map<String, Object> args() { return args; }
    public String id() { return id; }
    public String type() { return type; }

    /** Serialize to a {@code Map} matching the wire format. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("args", args);
        m.put("id", id);
        m.put("type", type);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static ToolCall fromMap(Map<String, Object> m) {
        Objects.requireNonNull(m, "m");
        return new ToolCall(
                (String) m.get("name"),
                m.get("args") instanceof Map<?, ?> am ? (Map<String, Object>) am : Map.of(),
                (String) m.get("id"),
                (String) m.get("type"));
    }

    @Override
    public String toString() {
        return "ToolCall{name=" + name + ", args=" + args + ", id=" + id + ", type=" + type + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ToolCall t)) return false;
        return java.util.Objects.equals(name, t.name)
                && java.util.Objects.equals(args, t.args)
                && java.util.Objects.equals(id, t.id)
                && java.util.Objects.equals(type, t.type);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(name, args, id, type);
    }
}
