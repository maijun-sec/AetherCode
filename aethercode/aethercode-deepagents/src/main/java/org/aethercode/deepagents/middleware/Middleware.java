package org.aethercode.deepagents.middleware;

import org.aethercode.core.runtime.AgentState;
import org.aethercode.core.runtime.Message;
import org.aethercode.core.runtime.Message.ToolMessage;
import org.aethercode.deepagents.tools.Tool;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.aethercode.core.runtime.Message.AIMessage;

/**
 * The single, unified contract every middleware in the deepagents agent
 * loop must implement.
 *
 * <p>Java-native port of LangChain's {@code AgentMiddleware}. The interface
 * exposes one synchronous hook per phase of the agent loop. Each default
 * is a no-op so a minimal middleware can override only the hooks it
 * cares about. The runtime wires the active middleware chain via
 * {@link #name()} (used by {@code HarnessProfile.excluded_middleware}
 * filtering) and {@link #priority()} (lower priority runs first).</p>
 *
 * <p>All hooks receive the current {@link AgentState} plus an opaque
 * {@code Runtime} object that carries the tool registry, the chat model,
 * and any per-run configuration. The exact shape of {@code Runtime} is
 * a work in progress &mdash; the minimal contract today is the tool
 * registry and the chat model.</p>
 */
public interface Middleware {

    /** Stable identifier used by HarnessProfile filtering. */
    String name();

    /**
     * Priority for ordering the middleware chain. Lower runs first.
     * The default of 0 lets call sites override only when needed.
     */
    default int priority() { return 0; }

    /**
     * Hook called before each model invocation. Return the (possibly
     * modified) state; the runtime forwards the returned state to the
     * next middleware and finally to the chat model.
     */
    default AgentState beforeModel(AgentState state, Runtime runtime) {
        return state;
    }

    /**
     * Hook called after each model invocation. Receives the AI message
     * emitted by the chat model; may replace or augment it.
     */
    default AgentState afterModel(AgentState state, AIMessage aiMessage, Runtime runtime) {
        return state;
    }

    /**
     * Hook called to wrap a single tool call. The default delegates
     * straight to {@code tool.invoke(arguments)}; middleware that needs
     * retries, permission checks, or result rewriting overrides this.
     */
    default Object wrapToolCall(Tool tool,
                                 java.util.Map<String, Object> arguments,
                                 AgentState state,
                                 Runtime runtime) throws Exception {
        return tool.invoke(arguments);
    }

    /**
     * Hook called to wrap the model call itself. The default delegates
     * to the model; middleware that needs request rewriting, retries,
     * or model selection overrides this.
     */
    default AIMessage wrapModelCall(BiFunction<List<Message>, Runtime, AIMessage> modelCall,
                                             List<Message> messages,
                                             AgentState state,
                                             Runtime runtime) {
        return modelCall.apply(messages, runtime);
    }

    /**
     * Hook called to wrap the model call when the middleware may
     * emit a state-update {@link org.aethercode.deepagents.langchain_compat.langgraph.Command}
     * alongside the model response. Mirrors the Python port's
     * {@code wrap_model_call} hook that returns
     * {@code ModelResponse | ExtendedModelResponse} where the
     * extended variant carries a {@code Command} for state updates.
     *
     * <p>The default delegates to {@link #wrapModelCall} and wraps
     * the result in a {@code Command}-less {@link WrapModelCallResult}.
     * Middleware that needs to mutate state alongside the model
     * response (e.g. summarisation event recording) overrides
     * this. The {@code DeepAgent} runtime calls
     * {@code wrapModelCallWithEvents} when present and applies the
     * returned {@code Command} to the state after the model call.</p>
     */
    default WrapModelCallResult wrapModelCallWithEvents(
            BiFunction<List<Message>, Runtime, AIMessage> modelCall,
            List<Message> messages,
            AgentState state,
            Runtime runtime) {
        return WrapModelCallResult.passthrough(
                wrapModelCall(modelCall, messages, state, runtime));
    }

    /**
     * Async variant of {@link #wrapModelCallWithEvents}. Default
     * delegates to {@link #awrapModelCall} and wraps the result in
     * a {@code Command}-less {@link WrapModelCallResult}.
     */
    default CompletableFuture<WrapModelCallResult> awrapModelCallWithEvents(
            BiFunction<List<Message>, Runtime, CompletableFuture<AIMessage>> modelCall,
            List<Message> messages,
            AgentState state,
            Runtime runtime) {
        return awrapModelCall(modelCall, messages, state, runtime)
                .thenApply(WrapModelCallResult::passthrough);
    }

    /**
     * Async variant of {@link #wrapModelCall}. The default delegates
     * to {@code wrapModelCall} (off the calling thread), then wraps
     * the result in a completed future. Middleware with async
     * request rewriting (e.g. async eviction) overrides this.
     *
     * <p>Mirrors the Python port's {@code awrap_model_call} hook.</p>
     */
    default CompletableFuture<AIMessage> awrapModelCall(
            BiFunction<List<Message>, Runtime, CompletableFuture<AIMessage>> modelCall,
            List<Message> messages,
            AgentState state,
            Runtime runtime) {
        return CompletableFuture.completedFuture(
                wrapModelCall(
                        (ms, rt) -> modelCall.apply(ms, rt).join(),
                        messages, state, runtime));
    }

    /**
     * Hook called to wrap a tool result message. The default returns the
     * tool result unchanged; middleware that needs to rewrite the
     * result or attach context overrides this.
     */
    default ToolMessage wrapToolResult(ToolMessage toolResult,
                                        AgentState state,
                                        Runtime runtime) {
        return toolResult;
    }

    /**
     * Async variant of {@link #wrapToolCall}. Default delegates to
     * {@link #wrapToolCall}.
     */
    default CompletableFuture<Object> awrapToolCall(Tool tool,
                                                     java.util.Map<String, Object> arguments,
                                                     AgentState state,
                                                     Runtime runtime) {
        try {
            return CompletableFuture.completedFuture(wrapToolCall(tool, arguments, state, runtime));
        } catch (Exception e) {
            CompletableFuture<Object> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }
    }

    // -----------------------------------------------------------------
    //  Runtime context
    // -----------------------------------------------------------------

    /**
     * Opaque per-run context. Today the contract is "tool registry" and
     * "chat model"; future middleware will need more.
     */
    interface Runtime {
        /** Tool registry available to the middleware (read-only). */
        java.util.List<Tool> tools();

        /** A default chat-model invocation; middleware may use or ignore. */
        Function<List<Message>, AIMessage> chatModel();

        /**
         * Resolved {@link org.aethercode.core.runtime.llm.ModelProfile} for
         * the active chat model, or {@code null} when none was
         * registered. Default {@code null}. The FilesystemMiddleware
         * consults this to decide which multimodal content blocks
         * need scrubbing.
         */
        default org.aethercode.core.runtime.llm.ModelProfile modelProfile() {
            return null;
        }

        /**
         * Lower-cased provider tag for the active chat model
         * (e.g. {@code "openai"}, {@code "anthropic"},
         * {@code "google"}). Default {@code null}. The
         * FilesystemMiddleware consults this for the
         * non-PDF {@code file}-block provider gate.
         */
        default String modelProvider() {
            return null;
        }
    }
}
