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
