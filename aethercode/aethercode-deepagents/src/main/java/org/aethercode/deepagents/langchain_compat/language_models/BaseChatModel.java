package org.aethercode.deepagents.langchain_compat.language_models;

import org.aethercode.deepagents.langchain_compat.messages.LangChainMessage;
import org.aethercode.deepagents.langchain_compat.runnables.Runnable;
import org.aethercode.deepagents.langchain_compat.runnables.RunnableConfig;
import org.aethercode.deepagents.langchain_compat.runnables.RunnableUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * LangChain-compatible BaseChatModel abstract class.
 *
 * <p>Java-native port of
 * {@code langchain_core.language_models.BaseChatModel}. The
 * subclass implements the low-level {@code _generate} hook; the
 * base class provides the {@code invoke} /
 * {@code ainvoke} entry points, message-list handling, and
 * convenience helpers for binding to a {@link RunnableConfig}.</p>
 *
 * <p>The Java port is a typed {@link Runnable} of
 * {@code List<LangChainMessage> &rarr; Object} (the model's
 * response object). Subclasses can return either a single
 * message or a richer response; the {@link #parseResult}
 * helper unwraps the common case.</p>
 */
public abstract class BaseChatModel
        implements Runnable<List<LangChainMessage>, Object> {

    /** Profile metadata, used by the runtime to identify the model. */
    public Map<String, Object> profile() { return Map.of(); }

    /** Provider name (e.g. {@code "openai"}, {@code "anthropic"}). */
    public String getLsProvider() { return ""; }

    /** Stable model identifier. */
    public String getLsModelName() { return ""; }

    @Override
    public Object invoke(List<LangChainMessage> messages, RunnableConfig config) {
        RunnableConfig cfg = RunnableUtils.ensureConfig(config);
        return _generate(messages, cfg);
    }

    @Override
    public CompletableFuture<Object> ainvoke(List<LangChainMessage> messages, RunnableConfig config) {
        RunnableConfig cfg = RunnableUtils.ensureConfig(config);
        return _agenerate(messages, cfg);
    }

    /**
     * Subclass hook: produce a model response for the given
     * messages and config. Subclasses should override this.
     */
    protected abstract Object _generate(List<LangChainMessage> messages, RunnableConfig config);

    /** Async variant; default delegates to {@link #_generate}. */
    protected CompletableFuture<Object> _agenerate(List<LangChainMessage> messages, RunnableConfig config) {
        return CompletableFuture.completedFuture(_generate(messages, config));
    }

    /**
     * Default helper to extract the AI message from a model
     * response. Subclasses can override to handle
     * provider-specific response shapes.
     */
    @SuppressWarnings("unchecked")
    protected LangChainMessage parseResult(Object result) {
        if (result instanceof LangChainMessage m) return m;
        if (result instanceof List<?> list && !list.isEmpty()
                && list.get(0) instanceof LangChainMessage m) {
            return m;
        }
        return null;
    }

    /** Build a {@link Runnable} that prepends a system message. */
    public Runnable<List<LangChainMessage>, Object> withSystemMessage(String system) {
        return (messages, cfg) -> {
            java.util.List<LangChainMessage> prefixed = new java.util.ArrayList<>();
            LangChainMessage sys = new org.aethercode.deepagents.langchain_compat.messages.LangChainMessage() {
                @Override public String role() { return "system"; }
                @Override public List<?> content() { return List.of(system); }
                @Override public String id() { return null; }
            };
            prefixed.add(sys);
            prefixed.addAll(messages);
            return invoke(prefixed, cfg);
        };
    }
}
