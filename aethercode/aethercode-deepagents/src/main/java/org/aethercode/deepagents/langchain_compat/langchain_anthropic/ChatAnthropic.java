package org.aethercode.deepagents.langchain_compat.langchain_anthropic;

import org.aethercode.deepagents.langchain_compat.language_models.BaseChatModel;
import org.aethercode.deepagents.langchain_compat.messages.LangChainMessage;
import org.aethercode.deepagents.langchain_compat.runnables.RunnableConfig;
import org.aethercode.core.runtime.llm.profiles.provider.NvidiaProviderProfile;

import java.util.List;
import java.util.Map;

/**
 * LangChain-compatible ChatAnthropic.
 *
 * <p>Java-native port of
 * {@code langchain_anthropic.ChatAnthropic}. The Java port is a
 * stub: it captures the constructor kwargs (model, temperature,
 * max_tokens, base_url, default_headers) and exposes them via
 * the standard {@link BaseChatModel} surface. The real HTTP
 * call to Anthropic's API is left to consumers (or to a
 * future R-round that adds an HTTP transport).</p>
 */
public class ChatAnthropic extends BaseChatModel {

    private final String modelName;
    private final Double temperature;
    private final Integer maxTokens;
    private final String baseUrl;
    private final Map<String, String> defaultHeaders;

    public ChatAnthropic(String modelName) {
        this(modelName, null, null, null, null);
    }

    public ChatAnthropic(String modelName,
                          Double temperature,
                          Integer maxTokens,
                          String baseUrl,
                          Map<String, String> defaultHeaders) {
        this.modelName = modelName;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.baseUrl = baseUrl;
        this.defaultHeaders = defaultHeaders == null ? Map.of() : Map.copyOf(defaultHeaders);
    }

    public String modelName() { return modelName; }
    public Double temperature() { return temperature; }
    public Integer maxTokens() { return maxTokens; }
    public String baseUrl() { return baseUrl; }
    public Map<String, String> defaultHeaders() { return defaultHeaders; }

    @Override
    public Map<String, Object> profile() {
        Map<String, Object> p = new java.util.LinkedHashMap<>();
        if (maxTokens != null) p.put("max_input_tokens", maxTokens);
        return p;
    }

    @Override
    public String getLsProvider() { return "anthropic"; }

    @Override
    public String getLsModelName() { return modelName; }

    @Override
    protected Object _generate(List<LangChainMessage> messages, RunnableConfig config) {
        // Stub: return a synthetic AI message. Real implementations
        // would marshal messages + kwargs into an Anthropic HTTP
        // request and parse the response.
        return messages.isEmpty() ? null : messages.get(messages.size() - 1);
    }

    /**
     * Convenience factory: ChatAnthropic with Deep Agents
     * NVIDIA attribution headers wired in.
     */
    public static ChatAnthropic withDeepAgentsAttribution(String modelName) {
        return new ChatAnthropic(
                modelName, null, null, null,
                Map.of(NvidiaProviderProfile.NVIDIA_BILLING_ORIGIN_HEADER,
                        NvidiaProviderProfile.NVIDIA_APP_ORIGIN));
    }
}
