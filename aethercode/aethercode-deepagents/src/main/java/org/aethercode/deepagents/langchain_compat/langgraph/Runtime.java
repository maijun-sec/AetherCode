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

package org.aethercode.deepagents.langchain_compat.langgraph;

import java.util.Map;
import java.util.Objects;

/**
 * LangGraph-compatible {@code Runtime}.
 *
 * <p>Java-native port of
 * {@code langgraph.runtime.Runtime}. Carries the per-run
 * context: the configurable map (containing
 * {@code thread_id}, {@code user_id}, etc.) and the store
 * handle.</p>
 */
public record Runtime(
        Map<String, Object> configurable,
        Object store) {

    public Runtime {
        configurable = configurable == null ? Map.of() : Map.copyOf(configurable);
    }

    public static Runtime empty() {
        return new Runtime(Map.of(), null);
    }

    public static Runtime of(Map<String, Object> configurable) {
        return new Runtime(configurable, null);
    }

    @SuppressWarnings("unchecked")
    public <T> T configurableAs(String key, Class<T> type) {
        Object v = configurable.get(key);
        return type.isInstance(v) ? (T) v : null;
    }

    public String threadId() {
        Object v = configurable.get("thread_id");
        return v == null ? null : v.toString();
    }
}
