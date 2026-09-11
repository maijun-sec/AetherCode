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
