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
