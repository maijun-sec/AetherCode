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
