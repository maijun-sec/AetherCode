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
