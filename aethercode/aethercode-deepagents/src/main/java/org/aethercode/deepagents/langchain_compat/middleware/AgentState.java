package org.aethercode.deepagents.langchain_compat.middleware;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * LangChain-compatible agent state type.
 *
 * <p>Java-native port of
 * {@code langchain.agents.middleware.types.AgentState}. The
 * underlying state is shaped like LangChain's {@code MessagesState}
 * &mdash; a list of messages plus a free-form extensions dict.</p>
 *
 * <p>This class is the LangChain-compatible alternative to
 * {@code org.aethercode.core.runtime.AgentState}. The two are
 * interoperable: this class exposes a {@code toAgentState()}
 * conversion helper.</p>
 */
public final class AgentState {
    private final List<?> messages;
    private final Map<String, Object> extensions;

    public AgentState(List<?> messages, Map<String, Object> extensions) {
        this.messages = messages == null ? List.of() : List.copyOf(messages);
        this.extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }

    public AgentState() {
        this(List.of(), Map.of());
    }

    public List<?> messages() { return messages; }

    public Map<String, Object> extensions() { return extensions; }

    public AgentState withMessages(List<?> newMessages) {
        return new AgentState(newMessages, extensions);
    }

    public AgentState withExtensions(Map<String, Object> newExtensions) {
        return new AgentState(messages, newExtensions);
    }

    public AgentState withExtension(String key, Object value) {
        java.util.Map<String, Object> next = new java.util.LinkedHashMap<>(extensions);
        next.put(key, value);
        return withExtensions(next);
    }

    @SuppressWarnings("unchecked")
    public <T> List<T> messagesAs(Class<T> elementType) {
        Objects.requireNonNull(elementType, "elementType");
        java.util.List<T> out = new java.util.ArrayList<>(messages.size());
        for (Object o : messages) {
            if (elementType.isInstance(o)) {
                out.add((T) o);
            }
        }
        return out;
    }

    public static AgentState empty() {
        return new AgentState();
    }
}
