package org.aethercode.deepagents.langchain_compat.tools;

import java.util.List;
import java.util.Map;

/**
 * Per-invocation tool context.
 *
 * <p>Java-native port of the
 * {@code ToolRuntime} / {@code ToolCallRequest} context
 * object that LangChain's tools receive. Carries the recent
 * messages and the tool call id; the runtime is expected to
 * inject state through a thread-local or constructor (since
 * the LangChain Python version takes a {@code ToolRuntime}
 * parameter).</p>
 */
public record ToolContext(
        String toolCallId,
        List<?> recentMessages,
        Map<String, Object> config) {

    public ToolContext {
        recentMessages = recentMessages == null ? List.of() : List.copyOf(recentMessages);
        config = config == null ? Map.of() : Map.copyOf(config);
    }

    public static ToolContext empty() {
        return new ToolContext(null, List.of(), Map.of());
    }
}
