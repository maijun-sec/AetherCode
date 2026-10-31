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

package org.aethercode.deepagents.langchain_compat.tools;

import java.util.List;
import java.util.Map;

/**
 * LangChain-compatible ToolRuntime.
 *
 * <p>Java-native port of
 * {@code langchain.tools.ToolRuntime}. Carries the runtime
 * context that a tool function receives: the current state, the
 * recent messages, the tool call id, and the run configuration.
 * The Java port uses this as a parameter object &mdash; tools
 * declared with the {@code @tool} decorator in Python become
 * methods that take a {@code ToolRuntime} argument; the Java
 * port supports that via the {@link #of} factory.</p>
 */
public record ToolRuntime(
        Object state,
        List<?> messages,
        String toolCallId,
        Map<String, Object> config) {

    public ToolRuntime {
        messages = messages == null ? List.of() : List.copyOf(messages);
        config = config == null ? Map.of() : Map.copyOf(config);
    }

    public static ToolRuntime of(Object state, List<?> messages, String toolCallId) {
        return new ToolRuntime(state, messages, toolCallId, null);
    }

    public static ToolRuntime empty() {
        return new ToolRuntime(null, List.of(), null, null);
    }

    @SuppressWarnings("unchecked")
    public <T> T stateAs(Class<T> type) {
        return type.isInstance(state) ? (T) state : null;
    }
}
