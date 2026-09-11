package org.aethercode.runtime.llm;

import org.aethercode.runtime.message.Message;
import org.aethercode.runtime.message.Usage;
import org.aethercode.runtime.tool.Tool;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A single LLM call request.
 *
 * <p>Mirrors the langchain <code>BaseChatModel.invoke</code> input:
 * a list of messages, the available tools, the response format (if
 * structured output is required), and optional model parameters.</p>
 *
 * <p>Built with a fluent builder to keep the call sites compact.</p>
 */
public record LLMRequest(
        List<Message> messages,
        List<Tool> tools,
        Optional<String> responseFormat,
        Optional<String> model,
        Map<String, Object> modelKwargs,
        Optional<Double> temperature,
        Optional<Integer> maxTokens,
        Optional<String> stopSequence
) {

    public LLMRequest {
        messages    = messages    == null ? List.of() : List.copyOf(messages);
        tools       = tools       == null ? List.of() : List.copyOf(tools);
        modelKwargs = modelKwargs == null ? Map.of()  : Map.copyOf(modelKwargs);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private List<Message> messages = List.of();
        private List<Tool> tools = List.of();
        private String responseFormat;
        private String model;
        private Map<String, Object> modelKwargs = Map.of();
        private Double temperature;
        private Integer maxTokens;
        private String stopSequence;

        public Builder messages(List<Message> v)        { this.messages = v; return this; }
        public Builder tools(List<Tool> v)              { this.tools = v; return this; }
        public Builder responseFormat(String v)         { this.responseFormat = v; return this; }
        public Builder model(String v)                  { this.model = v; return this; }
        public Builder modelKwargs(Map<String, Object> v) { this.modelKwargs = v; return this; }
        public Builder temperature(Double v)            { this.temperature = v; return this; }
        public Builder maxTokens(Integer v)             { this.maxTokens = v; return this; }
        public Builder stopSequence(String v)           { this.stopSequence = v; return this; }

        public LLMRequest build() {
            return new LLMRequest(
                    messages,
                    tools,
                    Optional.ofNullable(responseFormat),
                    Optional.ofNullable(model),
                    modelKwargs,
                    Optional.ofNullable(temperature),
                    Optional.ofNullable(maxTokens),
                    Optional.ofNullable(stopSequence));
        }
    }
}
