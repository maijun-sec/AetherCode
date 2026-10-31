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

package org.aethercode.deepagents.langchain_compat.runnables;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * LangChain-compatible RunnableConfig.
 *
 * <p>Java-native port of
 * {@code langchain_core.runnables.RunnableConfig}. Carries the
 * per-invocation configuration: callbacks, metadata, tags,
 * configurable (e.g. thread_id for checkpointers), and the
 * recursion limit.</p>
 */
public record RunnableConfig(
        Map<String, Object> configurable,
        Map<String, Object> metadata,
        Object[] tags,
        Object[] callbacks,
        Integer recursionLimit) {

    public RunnableConfig {
        configurable = configurable == null ? Map.of() : Map.copyOf(configurable);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        tags = tags == null ? new Object[0] : tags.clone();
        callbacks = callbacks == null ? new Object[0] : callbacks.clone();
    }

    public static RunnableConfig empty() {
        return new RunnableConfig(null, null, null, null, null);
    }

    public RunnableConfig withConfigurable(String key, Object value) {
        Map<String, Object> next = new LinkedHashMap<>(configurable);
        next.put(key, value);
        return new RunnableConfig(next, metadata, tags, callbacks, recursionLimit);
    }

    public RunnableConfig withMetadata(String key, Object value) {
        Map<String, Object> next = new LinkedHashMap<>(metadata);
        next.put(key, value);
        return new RunnableConfig(configurable, next, tags, callbacks, recursionLimit);
    }

    public RunnableConfig withRecursionLimit(int limit) {
        return new RunnableConfig(configurable, metadata, tags, callbacks, limit);
    }

    @SuppressWarnings("unchecked")
    public <T> T configurableAs(String key, Class<T> type) {
        Object v = configurable.get(key);
        return type.isInstance(v) ? (T) v : null;
    }

    public static RunnableConfig ofThreadId(String threadId) {
        return empty().withConfigurable("thread_id", threadId);
    }
}
