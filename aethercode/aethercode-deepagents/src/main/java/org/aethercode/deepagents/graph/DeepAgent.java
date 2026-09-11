package org.aethercode.deepagents.graph;

import org.aethercode.core.fs.backend.BackendProtocol;
import org.aethercode.deepagents.middleware.Middleware;
import org.aethercode.deepagents.middleware.AsyncSubAgent;
import org.aethercode.deepagents.middleware.CompiledSubAgent;
import org.aethercode.deepagents.middleware.FilesystemMiddleware;
import org.aethercode.core.middleware.FilesystemPermission;
import org.aethercode.deepagents.middleware.PatchToolCallsMiddleware;
import org.aethercode.deepagents.middleware.PromptCachingMiddleware;
import org.aethercode.deepagents.middleware.PromptCachingProviderRegistry;
import org.aethercode.core.middleware.SkillSource;
import org.aethercode.deepagents.middleware.SkillsMiddleware;
import org.aethercode.deepagents.middleware.SubAgent;
import org.aethercode.deepagents.middleware.SubAgentMiddleware;
import org.aethercode.deepagents.middleware.SummarizationMiddleware;
import org.aethercode.deepagents.middleware.AsyncSubAgentMiddleware;
import org.aethercode.deepagents.middleware.ToolExclusionMiddleware;
import org.aethercode.deepagents.middleware.WrapModelCallResult;
import org.aethercode.core.runtime.AgentState;
import org.aethercode.core.runtime.ContentBlock;
import org.aethercode.core.runtime.Message;
import org.aethercode.deepagents.tools.Tool;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.aethercode.core.runtime.Message.AIMessage;
import org.aethercode.core.runtime.Message.HumanMessage;
import org.aethercode.core.runtime.Message.ToolMessage;
import org.aethercode.core.runtime.Message.SystemMessage;

/**
 * The fully configured deep agent.
 *
 * <p>Java-native port of the result of
 * {@code deepagents.graph.create_deep_agent}. Carries the
 * (model spec, backend, tool registry, middleware chain) the
 * runtime needs to invoke the agent. The
 * {@link #invoke(AgentState, String, Function)} entry point runs
 * the full agent loop: walk {@code beforeModel} hooks, call the
 * chat model, dispatch tool calls through the {@code wrapToolCall}
 * chain, repeat until the model returns no tool calls, then walk
 * {@code afterModel} hooks.</p>
 *
 * <p>The default {@link #invoke(AgentState, String)} (no chat model)
 * returns a stub result so existing tests that only check
 * assembly keep working. The real runtime is the 3-arg form.</p>
 */
public record DeepAgent(
        String name,
        Object model,
        BackendProtocol backend,
        List<Tool> tools,
        List<Middleware> middleware,
        String systemPrompt,
        DeepAgentState initialState,
        org.aethercode.core.runtime.llm.ModelProfile modelProfile,
        String modelProvider) {

    /** Default cap on tool-dispatch iterations. */
    public static final int DEFAULT_MAX_ITERATIONS = 16;

    public DeepAgent {
        Objects.requireNonNull(name, "name");
        middleware = middleware == null ? List.of() : List.copyOf(middleware);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    /**
     * Backward-compat 7-arg constructor (no model profile / provider).
     * Equivalent to the canonical ctor with both omitted (both
     * default to {@code null}).
     */
    public DeepAgent(
            String name,
            Object model,
            BackendProtocol backend,
            List<Tool> tools,
            List<Middleware> middleware,
            String systemPrompt,
            DeepAgentState initialState) {
        this(name, model, backend, tools, middleware, systemPrompt, initialState, null, null);
    }

    public Set<String> middlewareNames() {
        Set<String> out = new java.util.LinkedHashSet<>();
        for (Middleware m : middleware) out.add(m.name());
        return out;
    }

    /**
     * Convenience: invoke with no chat model. Returns a stub
     * result so existing assembly-only tests keep working.
     */
    public DeepAgentResult invoke(AgentState state, String input) {
        if (state == null) state = AgentState.empty();
        AgentState current = state;
        List<Middleware> ordered = new ArrayList<>(middleware);
        ordered.sort(Comparator.comparingInt(Middleware::priority));
        for (Middleware m : ordered) {
            current = m.beforeModel(current, makeRuntime());
            if (current == null) current = state;
        }
        return new DeepAgentResult(current, "Graph runtime not yet compiled (R3).");
    }

    /**
     * Real agent loop. Runs the chat model, dispatches tool calls,
     * loops until the model returns no more tool calls.
     *
     * @param state initial agent state; null is treated as empty
     * @param input user input appended as a HumanMessage if non-null
     * @param chatModel function the runtime calls to get the next
     *                   {@link AIMessage}. In production this
     *                   is the resolved chat model; in tests it is
     *                   a {@link MockChatModel#asFunction()}.
     */
    public DeepAgentResult invoke(AgentState state,
                                  String input,
                                  Function<List<Message>, AIMessage> chatModel) {
        return invoke(state, input, chatModel, DEFAULT_MAX_ITERATIONS);
    }

    /**
     * Real agent loop with a configurable iteration cap.
     *
     * <p>Mirrors the Python port's loop:
     * <ol>
     *   <li>Append the user input as a HumanMessage (if any).</li>
     *   <li>Walk {@code beforeModel} hooks.</li>
     *   <li>Call the chat model (via the {@code wrapModelCall} chain).</li>
     *   <li>Append the AIMessage to state; walk {@code afterModel} hooks.</li>
     *   <li>If the model emitted tool-use blocks, dispatch each
     *       through the {@code wrapToolCall} chain, append each
     *       ToolMessage to state, and go to (3).</li>
     *   <li>Otherwise return the final state.</li>
     * </ol>
     */
    public DeepAgentResult invoke(AgentState state,
                                  String input,
                                  Function<List<Message>, AIMessage> chatModel,
                                  int maxIterations) {
        Objects.requireNonNull(chatModel, "chatModel");
        if (maxIterations < 1) {
            throw new IllegalArgumentException("maxIterations must be >= 1");
        }
        AgentState current = state == null ? AgentState.empty() : state;

        // 1. Append user input as a HumanMessage (if any).
        if (input != null && !input.isEmpty()) {
            Message human = new HumanMessage(
                    "h-" + UUID.randomUUID(),
                    List.of(ContentBlock.text(input)));
            current = current.withMessages(appendMessage(current.messages(), human));
        }

        // Snapshot the model-call so wrapModelCall hooks can wrap it.
        Middleware.Runtime runtime = makeRuntime(chatModel);

        // Order middleware by priority once. Used for every hook below.
        List<Middleware> ordered = new ArrayList<>(middleware);
        ordered.sort(Comparator.comparingInt(Middleware::priority));

        // 2-N. Tool-call loop. Each iteration:
        //   a. Run beforeModel on every middleware (in priority order).
        //   b. Call the chat model (through wrapModelCall chain).
        //   c. Append the AIMessage to state; run afterModel on every middleware.
        //   d. If the model emitted tool-use blocks, dispatch each through
        //      the wrapToolCall chain, append each ToolMessage to state, and
        //      continue to the next iteration. Otherwise return.
        String finalText = "";
        for (int iter = 0; iter < maxIterations; iter++) {
            // 2. beforeModel chain — runs every iteration, mirroring the
            //    Python langgraph runtime contract.
            for (Middleware m : ordered) {
                AgentState next = m.beforeModel(current, runtime);
                if (next != null) current = next;
            }

            // 3. Build the messages list the model sees.
            List<Message> messages = buildMessages(current);

            // 4. Call the model (through wrapModelCall, with
            //    optional state-update Command for middlewares that
            //    implement the modern wrapModelCallWithEvents hook).
            java.util.function.BiFunction<List<Message>, Middleware.Runtime, AIMessage> rawBiFn =
                    (ms, rt) -> chatModel.apply(ms);
            WrapModelCallResult result = wrapModelCallChain(ordered, rawBiFn, messages, current, runtime);
            if (result.hasCommand()) {
                current = applyCommand(current, result.command());
            }
            current = current.withMessages(appendMessage(current.messages(), result.aiMessage()));

            // Track the final text reply.
            String text = ContentBlock.flattenText(result.aiMessage().content());
            if (!text.isEmpty()) finalText = text;

            // 5. afterModel chain.
            for (Middleware m : ordered) {
                AgentState next = m.afterModel(current, result.aiMessage(), runtime);
                if (next != null) current = next;
            }

            // 6. Find tool-use blocks; dispatch each.
            List<ContentBlock.ToolUseBlock> toolUses = new ArrayList<>();
            for (ContentBlock b : result.aiMessage().content()) {
                if (b instanceof ContentBlock.ToolUseBlock t) toolUses.add(t);
            }
            if (toolUses.isEmpty()) {
                // No more tool calls → return.
                return new DeepAgentResult(current, finalText);
            }
            for (ContentBlock.ToolUseBlock tu : toolUses) {
                ToolMessage tm = dispatchToolCall(tu, current, runtime, ordered);
                current = current.withMessages(appendMessage(current.messages(), tm));
            }
        }
        // Hit the iteration cap. Return whatever state we have.
        return new DeepAgentResult(current, finalText);
    }

    /** Async variant of the real agent loop. */
    public CompletableFuture<DeepAgentResult> ainvoke(
            AgentState state,
            String input,
            Function<List<Message>, AIMessage> chatModel) {
        return CompletableFuture.supplyAsync(() -> invoke(state, input, chatModel));
    }

    /**
     * Async variant of the stub 2-arg {@link #invoke(AgentState, String)}.
     * Returns the same stub result wrapped in a completed future.
     */
    public CompletableFuture<DeepAgentResult> ainvoke(AgentState state, String input) {
        return CompletableFuture.completedFuture(invoke(state, input));
    }

    /**
     * Walk the {@code wrapModelCall} chain. The innermost
     * invocation calls the raw model. Each middleware's
     * {@code wrapModelCallWithEvents} may transform the result
     * and emit a state-update {@link org.aethercode.deepagents.langchain_compat.langgraph.Command}.
     * Returns a {@link WrapModelCallResult} so the runtime can
     * apply the command to the state after the model call.
     */
    private WrapModelCallResult wrapModelCallChain(
            List<Middleware> ordered,
            java.util.function.BiFunction<List<Message>, Middleware.Runtime, AIMessage> rawCall,
            List<Message> messages,
            AgentState state,
            Middleware.Runtime runtime) {
        // We invert the order: start with the raw call, then layer
        // each middleware's wrap around the previous. The Python
        // port uses a similar composition.
        Function<List<Message>, WrapModelCallResult> composed = msgs ->
                WrapModelCallResult.passthrough(rawCall.apply(msgs, runtime));
        for (int i = ordered.size() - 1; i >= 0; i--) {
            Middleware m = ordered.get(i);
            Function<List<Message>, WrapModelCallResult> prev = composed;
            composed = msgs -> m.wrapModelCallWithEvents(
                    (ms, rt) -> prev.apply(ms).aiMessage(),
                    msgs,
                    state,
                    runtime);
        }
        return composed.apply(messages);
    }

    /**
     * Apply a {@link org.aethercode.deepagents.langchain_compat.langgraph.Command}'s
     * update map to the agent state. The update keys are merged
     * into {@link AgentState#extensions()} and the
     * {@code messages} key (if present) replaces the current
     * message list. Mirrors the Python port's
     * {@code Command(update=...)} semantics.
     */
    private AgentState applyCommand(AgentState state,
                                    org.aethercode.deepagents.langchain_compat.langgraph.Command command) {
        if (command == null || !command.hasUpdate()) {
            return state;
        }
        java.util.Map<String, Object> update = command.update();
        AgentState out = state;
        if (update.containsKey("messages")) {
            Object msgs = update.get("messages");
            if (msgs instanceof java.util.List<?> list) {
                java.util.List<org.aethercode.core.runtime.Message> typed = new java.util.ArrayList<>();
                for (Object o : list) {
                    if (o instanceof org.aethercode.core.runtime.Message m) typed.add(m);
                }
                out = out.withMessages(typed);
            }
        }
        for (java.util.Map.Entry<String, Object> e : update.entrySet()) {
            if ("messages".equals(e.getKey())) continue;
            out = out.withExtension(e.getKey(), e.getValue());
        }
        return out;
    }

    /**
     * Dispatch a single tool call through the wrapToolCall chain.
     * Returns the resulting ToolMessage.
     */
    private ToolMessage dispatchToolCall(
            ContentBlock.ToolUseBlock tu,
            AgentState state,
            Middleware.Runtime runtime,
            List<Middleware> ordered) {
        Tool tool = findTool(tu.name());
        if (tool == null) {
            return makeToolMessage(tu.id(), tu.name(),
                    "Error: tool '" + tu.name() + "' not found",
                    "error");
        }
        // Composed wrapToolCall: each middleware gets a chance to
        // intercept. Innermost = direct tool.invoke.
        Function<Middleware, java.util.function.BiFunction<
                Map<String, Object>, AgentState, Object>> wrapFn =
                m -> (args, st) -> {
                    try {
                        return m.wrapToolCall(tool, args, st, runtime);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                };
        // Build a chain: middleware[0] wraps (middleware[1] wraps (... tool.invoke))
        java.util.function.BiFunction<Map<String, Object>, AgentState, Object> chain =
                (args, st) -> {
                    try {
                        return tool.invoke(args);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                };
        for (int i = ordered.size() - 1; i >= 0; i--) {
            Middleware m = ordered.get(i);
            java.util.function.BiFunction<Map<String, Object>, AgentState, Object> prev = chain;
            chain = (args, st) -> {
                try {
                    return m.wrapToolCall(tool, args, st, runtime);
                } catch (RuntimeException re) {
                    // Re-throw so the dispatch error path below sees the
                    // original message without an extra "Error: " prefix.
                    // Middleware signals rejection with an exception whose
                    // message is already a formatted "Error: ..." string.
                    throw re;
                } catch (Exception e) {
                    return "Error: " + e.getMessage();
                }
            };
        }
        Object result;
        try {
            result = chain.apply(tu.input(), state);
        } catch (Throwable t) {
            String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            // Don't double-prefix if the message already begins with "Error: "
            // (e.g. middleware that throws an exception with a pre-formatted
            // error string).
            result = msg.startsWith("Error: ") ? msg : "Error: " + msg;
        }
        String contentText = stringifyResult(result);
        // If the wrapToolCall chain produced an "Error: ..." payload,
        // or the tool returned a backend result with an .error()
        // (e.g. ReadResult / DeleteResult / WriteResult / LsResult /
        // GrepResult / GlobResult), surface it as a failed ToolMessage
        // (status="error") rather than the default "success". This
        // mirrors how the Python port flags tool errors back to the
        // model.
        boolean backendError = result instanceof org.aethercode.core.fs.backend.ReadResult r
                && r.error().isPresent()
                || result instanceof org.aethercode.core.fs.backend.WriteResult w
                && w.error().isPresent()
                || result instanceof org.aethercode.core.fs.backend.EditResult ed
                && ed.error().isPresent()
                || result instanceof org.aethercode.core.fs.backend.DeleteResult d
                && d.error().isPresent()
                || result instanceof org.aethercode.core.fs.backend.LsResult l
                && l.error().isPresent()
                || result instanceof org.aethercode.core.fs.backend.GrepResult g
                && g.error().isPresent()
                || result instanceof org.aethercode.core.fs.backend.GlobResult b
                && b.error().isPresent();
        String status = (contentText.startsWith("Error: ") || backendError) ? "error" : "success";
        ToolMessage tm = makeToolMessage(tu.id(), tu.name(), contentText, status);
        // wrapToolResult hooks
        for (Middleware m : ordered) {
            ToolMessage next = m.wrapToolResult(tm, currentState(state, tm), runtime);
            if (next != null) tm = next;
        }
        return tm;
    }

    /** Build a {@link ToolMessage} from a tool result. */
    private ToolMessage makeToolMessage(String toolUseId,
                                               String name,
                                               String content,
                                               String status) {
        return new ToolMessage(
                "tm-" + UUID.randomUUID(),
                toolUseId,
                List.of(ContentBlock.text(content)),
                java.util.Optional.of(name),
                java.util.Optional.of(status),
                java.util.Optional.empty(),
                Map.of(),
                Map.of());
    }

    /** Helper: build a snapshot state with the ToolMessage appended (for wrapToolResult). */
    private AgentState currentState(AgentState state, ToolMessage tm) {
        return state.withMessages(appendMessage(state.messages(), tm));
    }

    /** Convert a tool result object to a string the model can see. */
    private String stringifyResult(Object result) {
        if (result == null) return "";
        if (result instanceof String s) return s;
        // Error-bearing result types: surface only the error message so
        // the model's ToolMessage is the clean "Error: ..." string the
        // Python port produces.
        if (result instanceof org.aethercode.core.fs.backend.WriteResult w
                && w.error().isPresent()) {
            return w.error().get();
        }
        if (result instanceof org.aethercode.core.fs.backend.EditResult e
                && e.error().isPresent()) {
            return e.error().get();
        }
        if (result instanceof org.aethercode.core.fs.backend.DeleteResult d
                && d.error().isPresent()) {
            return d.error().get();
        }
        if (result instanceof org.aethercode.core.fs.backend.LsResult l
                && l.error().isPresent()) {
            return l.error().get();
        }
        if (result instanceof org.aethercode.core.fs.backend.ReadResult r
                && r.error().isPresent()) {
            return r.error().get();
        }
        if (result instanceof org.aethercode.core.fs.backend.GrepResult g
                && g.error().isPresent()) {
            return g.error().get();
        }
        if (result instanceof org.aethercode.core.fs.backend.GlobResult b
                && b.error().isPresent()) {
            return b.error().get();
        }
        return result.toString();
    }

    /** Find a tool by name in the registry. */
    private Tool findTool(String name) {
        for (Tool t : tools) {
            if (t.name().equals(name)) return t;
        }
        return null;
    }

    /**
     * Build the messages list the model sees. Includes the
     * system prompt (if any) + the state's messages.
     */
    private List<Message> buildMessages(AgentState state) {
        List<Message> out = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            out.add(new SystemMessage(
                    "sys", List.of(ContentBlock.text(systemPrompt))));
        }
        out.addAll(currentMessages(state));
        return out;
    }

    /** Extract the messages from a state, looking at extensions first. */
    private List<Message> currentMessages(AgentState state) {
        return state.messages() == null ? List.of() : state.messages();
    }

    /** Append a message to a list (returns a new list, never mutates). */
    private static List<Message> appendMessage(List<Message> current, Message toAdd) {
        List<Message> next = new ArrayList<>(current == null ? List.of() : current);
        next.add(toAdd);
        return next;
    }

    /** Build a Runtime tied to this invocation. */
    private Middleware.Runtime makeRuntime(Function<List<Message>, AIMessage> chatModel) {
        return new Middleware.Runtime() {
            @Override public List<Tool> tools() { return tools; }
            @Override public Function<List<Message>, AIMessage> chatModel() {
                return chatModel;
            }
            @Override public org.aethercode.core.runtime.llm.ModelProfile modelProfile() {
                return modelProfile;
            }
            @Override public String modelProvider() {
                return modelProvider;
            }
        };
    }

    /** Stub runtime used by the 2-arg {@link #invoke(AgentState, String)} variant. */
    private Middleware.Runtime makeRuntime() {
        return makeRuntime(msgs -> new AIMessage("ai1",
                List.of(ContentBlock.text("Graph runtime not yet compiled (R3)."))));
    }

    /** Result of a (real) {@link DeepAgent#invoke} call. */
    public record DeepAgentResult(AgentState state, String text) {
        public DeepAgentResult {
            state = state == null ? AgentState.empty() : state;
            text = text == null ? "" : text;
        }
    }

    // =================================================================
    //  Streaming
    // =================================================================

    /**
     * Run the real agent loop and return both the final result and the
     * ordered list of events that the runtime emitted along the way.
     *
     * <p>Events are emitted at well-defined points:</p>
     * <ol>
     *   <li>{@link DeepAgentEvent.BeforeModel} — before each chat-model call,
     *       carries the state the model is about to see.</li>
     *   <li>{@link DeepAgentEvent.AfterModel} — after each chat-model call,
     *       carries the model's {@code AIMessage}.</li>
     *   <li>{@link DeepAgentEvent.ToolDispatch} — after each tool call,
     *       carries the resulting {@code ToolMessage}.</li>
     *   <li>{@link DeepAgentEvent.Final} — exactly once at the end,
     *       carries the final state and the final assistant text reply.</li>
     * </ol>
     *
     * <p>This is a minimal, blocking streaming surface. It does not match
     * langgraph v3's {@code astream_events} projections (subagent handles,
     * tool-call streams, etc.); those are deferred to a later round when
     * the full graph runtime is in place. The shape here is enough to
     * drive a UI that wants to show "model is thinking" / "model said" /
     * "tool ran" / "final answer" without a streaming protocol on top.</p>
     */
    public StreamResult stream(AgentState state,
                               String input,
                               Function<List<Message>, AIMessage> chatModel) {
        return stream(state, input, chatModel, DEFAULT_MAX_ITERATIONS);
    }

    /**
     * Streaming variant with a configurable iteration cap.
     *
     * @see #stream(AgentState, String, Function)
     */
    public StreamResult stream(AgentState state,
                               String input,
                               Function<List<Message>, AIMessage> chatModel,
                               int maxIterations) {
        Objects.requireNonNull(chatModel, "chatModel");
        if (maxIterations < 1) {
            throw new IllegalArgumentException("maxIterations must be >= 1");
        }
        java.util.List<DeepAgentEvent> events = new java.util.ArrayList<>();
        AgentState current = state == null ? AgentState.empty() : state;

        // Append user input as a HumanMessage (if any).
        if (input != null && !input.isEmpty()) {
            Message human = new HumanMessage(
                    "h-" + UUID.randomUUID(),
                    List.of(ContentBlock.text(input)));
            current = current.withMessages(appendMessage(current.messages(), human));
        }

        Middleware.Runtime runtime = makeRuntime(chatModel);

        List<Middleware> ordered = new ArrayList<>(middleware);
        ordered.sort(Comparator.comparingInt(Middleware::priority));

        String finalText = "";
        for (int iter = 0; iter < maxIterations; iter++) {
            for (Middleware m : ordered) {
                AgentState next = m.beforeModel(current, runtime);
                if (next != null) current = next;
            }
            events.add(new DeepAgentEvent.BeforeModel(current));

            List<Message> messages = buildMessages(current);

            java.util.function.BiFunction<List<Message>, Middleware.Runtime, AIMessage> rawBiFn =
                    (ms, rt) -> chatModel.apply(ms);
            WrapModelCallResult result = wrapModelCallChain(ordered, rawBiFn, messages, current, runtime);
            if (result.hasCommand()) {
                current = applyCommand(current, result.command());
            }
            current = current.withMessages(appendMessage(current.messages(), result.aiMessage()));

            String text = ContentBlock.flattenText(result.aiMessage().content());
            if (!text.isEmpty()) finalText = text;

            events.add(new DeepAgentEvent.AfterModel(result.aiMessage(), current));

            for (Middleware m : ordered) {
                AgentState next = m.afterModel(current, result.aiMessage(), runtime);
                if (next != null) current = next;
            }

            List<ContentBlock.ToolUseBlock> toolUses = new ArrayList<>();
            for (ContentBlock b : result.aiMessage().content()) {
                if (b instanceof ContentBlock.ToolUseBlock t) toolUses.add(t);
            }
            if (toolUses.isEmpty()) {
                events.add(new DeepAgentEvent.Final(current, finalText));
                return new StreamResult(current, finalText, List.copyOf(events));
            }
            for (ContentBlock.ToolUseBlock tu : toolUses) {
                ToolMessage tm = dispatchToolCall(tu, current, runtime, ordered);
                current = current.withMessages(appendMessage(current.messages(), tm));
                events.add(new DeepAgentEvent.ToolDispatch(tm, current));
            }
        }
        events.add(new DeepAgentEvent.Final(current, finalText));
        return new StreamResult(current, finalText, List.copyOf(events));
    }

    /**
     * The result of a {@link #stream(AgentState, String, Function)} call.
     *
     * <p>Carries the final state + final text (same as
     * {@link DeepAgentResult}) plus the ordered list of events the
     * runtime emitted. The final {@link DeepAgentEvent.Final} event
     * duplicates {@code state} + {@code text} for callers that only
     * want the event stream.</p>
     */
    public record StreamResult(AgentState state,
                               String text,
                               List<DeepAgentEvent> events) {
        public StreamResult {
            state = state == null ? AgentState.empty() : state;
            text = text == null ? "" : text;
            events = events == null ? List.of() : List.copyOf(events);
        }
    }
}
