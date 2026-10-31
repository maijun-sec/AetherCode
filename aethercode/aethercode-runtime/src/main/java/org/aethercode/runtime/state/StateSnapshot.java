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

package org.aethercode.runtime.state;

import org.aethercode.runtime.message.Message;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Snapshot of the agent's working state.
 *
 * <p>Mirror of langgraph's <code>AgentState</code>. The state is a
 * homogeneous map of named fields; {@link #messages()} is the
 * conventional "messages" field, but middleware can attach any
 * other key (todos, files, skills, ...).</p>
 */
public record StateSnapshot(
        Map<String, Object> values
) {
    public StateSnapshot {
        values = values == null ? Map.of() : Map.copyOf(values);
    }

    public static StateSnapshot empty() {
        return new StateSnapshot(Map.of());
    }

    public static StateSnapshot ofMessages(List<Message> messages) {
        return new StateSnapshot(Map.of("messages", List.copyOf(messages)));
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(String key) {
        return Optional.ofNullable((T) values.get(key));
    }

    @SuppressWarnings("unchecked")
    public <T> T getOrDefault(String key, T fallback) {
        T v = (T) values.get(key);
        return v == null ? fallback : v;
    }

    public List<Message> messages() {
        @SuppressWarnings("unchecked")
        List<Message> v = (List<Message>) values.get("messages");
        return v == null ? List.of() : v;
    }

    public StateSnapshot withValue(String key, Object value) {
        Map<String, Object> merged = new java.util.HashMap<>(values);
        merged.put(key, value);
        return new StateSnapshot(merged);
    }

    public StateSnapshot withValues(Map<String, Object> more) {
        Map<String, Object> merged = new java.util.HashMap<>(values);
        merged.putAll(more);
        return new StateSnapshot(merged);
    }
}
