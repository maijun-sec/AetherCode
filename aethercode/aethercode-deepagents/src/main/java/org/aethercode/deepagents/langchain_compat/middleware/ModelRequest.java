package org.aethercode.deepagents.langchain_compat.middleware;

import java.util.List;
import java.util.Map;

/**
 * LangChain-compatible model request type.
 *
 * <p>Java-native port of
 * {@code langchain.agents.middleware.types.ModelRequest}.
 * Carries the data needed to call a chat model: the current
 * messages, the available tool specs, and a {@code systemMessage}
 * fragment that middleware can amend.</p>
 *
 * <p>Use {@link #builder()} to construct an instance.</p>
 */
public final class ModelRequest {
    private final List<?> messages;
    private final List<Map<String, Object>> tools;
    private final String systemMessage;
    private final Map<String, Object> runtime;
    private final AgentState state;

    private ModelRequest(Builder b) {
        this.messages = b.messages == null ? List.of() : List.copyOf(b.messages);
        this.tools = b.tools == null ? List.of() : List.copyOf(b.tools);
        this.systemMessage = b.systemMessage;
        this.runtime = b.runtime == null ? Map.of() : Map.copyOf(b.runtime);
        this.state = b.state;
    }

    public List<?> messages() { return messages; }
    public List<Map<String, Object>> tools() { return tools; }
    public String systemMessage() { return systemMessage; }
    public Map<String, Object> runtime() { return runtime; }
    public AgentState state() { return state; }

    @SuppressWarnings("unchecked")
    public <T> List<T> messagesAs(Class<T> type) {
        java.util.List<T> out = new java.util.ArrayList<>();
        for (Object o : messages) if (type.isInstance(o)) out.add((T) o);
        return out;
    }

    public Builder toBuilder() {
        return new Builder()
                .messages(messages)
                .tools(tools)
                .systemMessage(systemMessage)
                .runtime(runtime)
                .state(state);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private List<?> messages;
        private List<Map<String, Object>> tools;
        private String systemMessage;
        private Map<String, Object> runtime;
        private AgentState state;

        public Builder messages(List<?> m) { this.messages = m; return this; }
        public Builder tools(List<Map<String, Object>> t) { this.tools = t; return this; }
        public Builder systemMessage(String s) { this.systemMessage = s; return this; }
        public Builder runtime(Map<String, Object> r) { this.runtime = r; return this; }
        public Builder state(AgentState s) { this.state = s; return this; }
        public ModelRequest build() { return new ModelRequest(this); }
    }
}
