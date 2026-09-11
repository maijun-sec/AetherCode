package org.aethercode.permission.categorize;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * T-230..T-234 / design.md §3.2: a stripped-down view of a tool
 * call used as input to {@link RiskCategorizer}.
 *
 * <p>This is intentionally <em>not</em> the same type as
 * {@link org.aethercode.core.tool.Tool} — the categorizer doesn't
 * need the full execution context, the result type, or any of
 * the implementation details. It only needs the tool name + the
 * arguments the model passed. Decoupling the input also makes
 * the categorizer trivially testable (no need to mock a real
 * {@code Tool}).
 *
 * <p>Arguments are stored in an unmodifiable map so callers
 * cannot accidentally mutate the categorizer's view.
 */
public final class ToolCall {

    private final String tool;
    private final Map<String, Object> args;

    public ToolCall(String tool, Map<String, Object> args) {
        this.tool = Objects.requireNonNull(tool, "tool");
        if (tool.isBlank()) {
            throw new IllegalArgumentException("tool name must not be blank");
        }
        this.args = args == null
                ? Map.of()
                : Collections.unmodifiableMap(new HashMap<>(args));
    }

    public String tool() { return tool; }
    public Map<String, Object> args() { return args; }

    /** Convenience accessor for an arg value (the model often
     *  passes a string in {@code command} / {@code file_path} /
     *  {@code path}). Returns {@code null} when absent so the
     *  rule table can do a {@code equals}-style match. */
    public String argString(String key) {
        if (key == null) return null;
        Object v = args.get(key);
        return v == null ? null : v.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ToolCall other)) return false;
        return tool.equals(other.tool) && args.equals(other.args);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tool, args);
    }

    @Override
    public String toString() {
        return "ToolCall{tool=" + tool + ", args=" + args + "}";
    }
}
