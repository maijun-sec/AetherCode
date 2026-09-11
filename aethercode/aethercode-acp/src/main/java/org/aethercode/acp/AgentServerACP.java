package org.aethercode.acp;

import org.aethercode.acp.schema.Capabilities;
import org.aethercode.acp.schema.ContentBlock;
import org.aethercode.acp.schema.ContentBlocks;
import org.aethercode.acp.schema.McpServers.McpServer;
import org.aethercode.acp.schema.PermissionOption;
import org.aethercode.acp.schema.PlanEntry;
import org.aethercode.acp.schema.Responses;
import org.aethercode.acp.schema.SessionConfig;
import org.aethercode.acp.schema.SessionConfig.BareSessionConfigOption;
import org.aethercode.acp.schema.SessionConfig.SessionConfigOption;
import org.aethercode.acp.schema.SessionConfig.SessionConfigOptionRoot;
import org.aethercode.acp.schema.SessionConfig.SessionModeState;
import org.aethercode.acp.schema.SessionUpdate;
import org.aethercode.acp.schema.ToolCallTypes.ToolCallContent;
import org.aethercode.acp.schema.ToolCallTypes.ToolCallContentDiff;
import org.aethercode.acp.schema.ToolCallTypes.ToolCallContentText;
import org.aethercode.acp.schema.ToolKind;
import org.aethercode.langchain_compat.langgraph.Checkpointer;
import org.aethercode.langchain_compat.langgraph.Command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * ACP agent server that bridges a Deep Agent with the Agent
 * Client Protocol.
 *
 * <p>Java-native port of the Python {@code AgentServerACP}
 * class. The server holds either a {@link StreamingStateGraph}
 * (or a factory that builds one from a
 * {@link AgentSessionContext}) and adapts it to the ACP
 * session lifecycle ({@code initialize}, {@code newSession},
 * {@code loadSession}, {@code prompt}, {@code cancel},
 * mode and config-option changes).</p>
 *
 * <p>The streaming, state-history, and state-update methods
 * the prompt loop calls are part of
 * {@link StreamingStateGraph}. They are wired against the
 * {@link StreamingStateGraph.Unsupported} placeholder when
 * a plain {@link org.aethercode.langchain_compat.langgraph.CompiledStateGraph}
 * is supplied; the demo wires the real runtime when the
 * langgraph4j graph is in place.</p>
 */
public class AgentServerACP extends AcpAgent {

    /** Mode metadata key persisted in the checkpointer. */
    public static final String ACP_MODE_METADATA_KEY = "acp_mode";
    /** Model metadata key persisted in the checkpointer. */
    public static final String ACP_MODEL_METADATA_KEY = "acp_model";
    /** Session-marker metadata key persisted in the checkpointer. */
    public static final String ACP_SESSION_METADATA_KEY = "acp_session";

    /** "no value" sentinel used for non-execute command permissions. */
    private static final String NULL_ALLOWED_ARG = null;

    private final AgentFactoryOrGraph agentInput;
    private final Optional<SessionModeState> modes;
    private final Optional<List<ModelSpec>> models;
    private final boolean loadSessions;

    String cwd = "";
    StreamingStateGraph agent;
    String agentSessionId;
    final Map<String, String> sessionModes = new ConcurrentHashMap<>();
    final Map<String, SessionModeState> sessionModeStates = new ConcurrentHashMap<>();
    final Map<String, String> sessionModels = new ConcurrentHashMap<>();
    private volatile boolean cancelled = false;
    final Map<String, List<Map<String, Object>>> sessionPlans = new ConcurrentHashMap<>();
    final Map<String, String> sessionCwds = new ConcurrentHashMap<>();
    final Map<String, List<McpServer>> sessionMcpServers = new ConcurrentHashMap<>();
    /** Allowed command types per session, keyed by (toolName, cmdType). */
    final Map<String, Set<AllowedKey>> allowedCommandTypes = new ConcurrentHashMap<>();

    /**
     * One model spec in the {@code models} list. Mirrors the
     * {@code {"value": ..., "name": ..., "description": ...}}
     * dict the Python port accepts.
     */
    public record ModelSpec(String value, String name, String description) {
        public ModelSpec {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(name, "name");
            description = description == null ? "" : description;
        }
    }

    /** Compound (graph, factory) input. Mirrors the Python
     *  {@code CompiledStateGraph | Callable} union. */
    public sealed interface AgentFactoryOrGraph
            permits Graph, Factory {
    }
    public record Graph(StreamingStateGraph graph) implements AgentFactoryOrGraph {
        public Graph {
            Objects.requireNonNull(graph, "graph");
        }
    }
    public record Factory(Function<AgentSessionContext, StreamingStateGraph> factory)
            implements AgentFactoryOrGraph {
        public Factory {
            Objects.requireNonNull(factory, "factory");
        }
    }

    public AgentServerACP(AgentFactoryOrGraph agent,
                          SessionModeState modes,
                          List<Map<String, String>> models,
                          boolean loadSessions) {
        Objects.requireNonNull(agent, "agent");
        this.agentInput = agent;
        if (agent instanceof Graph) {
            if (modes != null) {
                throw new IllegalArgumentException("modes can only be provided when agent is a factory");
            }
            if (models != null) {
                throw new IllegalArgumentException("models can only be provided when agent is a factory");
            }
            this.modes = Optional.empty();
            this.models = Optional.empty();
            this.agent = ((Graph) agent).graph();
        } else {
            this.modes = Optional.ofNullable(modes);
            this.models = Optional.ofNullable(models).map(AgentServerACP::toModelSpecs);
        }
        this.loadSessions = loadSessions;
    }

    public AgentServerACP(StreamingStateGraph graph) {
        this(new Graph(graph), null, null, false);
    }

    public AgentServerACP(Function<AgentSessionContext, StreamingStateGraph> factory) {
        this(new Factory(factory), null, null, false);
    }

    public AgentServerACP(StreamingStateGraph graph, SessionModeState modes) {
        this(new Graph(graph), modes, null, false);
    }

    public AgentServerACP(Function<AgentSessionContext, StreamingStateGraph> factory,
                           SessionModeState modes) {
        this(new Factory(factory), modes, null, false);
    }

    public AgentServerACP(Function<AgentSessionContext, StreamingStateGraph> factory,
                           SessionModeState modes,
                           List<Map<String, String>> models) {
        this(new Factory(factory), modes, models, false);
    }

    public AgentServerACP(Function<AgentSessionContext, StreamingStateGraph> factory,
                           SessionModeState modes,
                           List<Map<String, String>> models,
                           boolean loadSessions) {
        this(new Factory(factory), modes, models, loadSessions);
    }

    public AgentServerACP(StreamingStateGraph graph,
                           boolean loadSessions) {
        this(new Graph(graph), null, null, loadSessions);
    }

    private static List<ModelSpec> toModelSpecs(List<Map<String, String>> raw) {
        List<ModelSpec> out = new ArrayList<>(raw.size());
        for (Map<String, String> m : raw) {
            out.add(new ModelSpec(
                    Objects.requireNonNull(m.get("value"), "model.value"),
                    Objects.requireNonNull(m.get("name"), "model.name"),
                    m.getOrDefault("description", "")));
        }
        return List.copyOf(out);
    }

    // -----------------------------------------------------------------
    // ACP lifecycle
    // -----------------------------------------------------------------

    @Override
    public void onConnect(Client connection) {
        super.onConnect(connection);
    }

    @Override
    public CompletableFuture<Responses.InitializeResponse> initialize(
            int protocolVersion,
            Capabilities.ClientCapabilities clientCapabilities,
            Capabilities.Implementation clientInfo,
            Map<String, Object> kwargs) {
        return CompletableFuture.completedFuture(
                new Responses.InitializeResponse(
                        protocolVersion,
                        new Capabilities.AgentCapabilities(
                                loadSessions,
                                new Capabilities.PromptCapabilities(
                                        Optional.of(true), Optional.empty(), Optional.empty()))));
    }

    @Override
    public CompletableFuture<Responses.NewSessionResponse> newSession(
            String cwd,
            List<String> additionalDirectories,
            List<McpServer> mcpServers,
            Map<String, Object> kwargs) {
        List<McpServer> mcp = mcpServers == null ? List.of() : mcpServers;
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        sessionCwds.put(sessionId, cwd == null ? "" : cwd);
        sessionMcpServers.put(sessionId, mcp);
        initializeSessionOptions(sessionId);

        if (loadSessions) {
            try {
                persistSession(sessionId).join();
            } catch (RuntimeException re) {
                throw re;
            }
        }
        List<SessionConfigOption> options = buildConfigOptions(sessionId);
        return CompletableFuture.completedFuture(
                new Responses.NewSessionResponse(
                        sessionId,
                        modes,
                        options.isEmpty() ? Optional.empty() : Optional.of(options)));
    }

    @Override
    public CompletableFuture<Responses.LoadSessionResponse> loadSession(
            String cwd,
            String sessionId,
            List<String> additionalDirectories,
            List<McpServer> mcpServers,
            Map<String, Object> kwargs) {
        if (!loadSessions) {
            throw RequestError.methodNotFound("session/load");
        }
        sessionCwds.put(sessionId, cwd == null ? "" : cwd);
        StreamingStateGraph cp = checkpointedAgent(sessionId);
        StateSnapshot snapshot = cp.agetState(sessionConfig(sessionId)).join();
        Map<String, Object> metadata = snapshot.values() == null
                ? Map.of()
                : snapshot.values();
        Object marker = metadata.get(ACP_SESSION_METADATA_KEY);
        if (!Boolean.TRUE.equals(marker)) {
            forgetSession(sessionId);
            throw RequestError.resourceNotFound(sessionId);
        }
        Object savedCwd = metadata.get("cwd");
        if (savedCwd != null && !savedCwd.equals(cwd)) {
            forgetSession(sessionId);
            throw RequestError.invalidParams(Map.of(
                    "cwd", "must match the working directory used to create the session"));
        }
        sessionMcpServers.put(sessionId, mcpServers == null ? List.of() : mcpServers);
        initializeSessionOptions(sessionId);
        if (restoreSessionOptions(sessionId, metadata)) {
            resetAgent(sessionId);
        }
        replaySession(sessionId, cp).join();
        List<SessionConfigOption> options = buildConfigOptions(sessionId);
        return CompletableFuture.completedFuture(
                new Responses.LoadSessionResponse(
                        Optional.ofNullable(sessionModeStates.get(sessionId)),
                        options.isEmpty() ? Optional.empty() : Optional.of(options)));
    }

    @Override
    public CompletableFuture<Responses.SetSessionModeResponse> setSessionMode(
            String modeId, String sessionId, Map<String, Object> kwargs) {
        if (modes.isPresent() && sessionModeStates.containsKey(sessionId)) {
            SessionModeState state = sessionModeStates.get(sessionId);
            sessionModes.put(sessionId, modeId);
            sessionModeStates.put(sessionId, new SessionModeState(
                    state.availableModes(), modeId));
            resetAgent(sessionId);
            if (loadSessions) {
                persistSession(sessionId).join();
            }
        }
        return CompletableFuture.completedFuture(new Responses.SetSessionModeResponse());
    }

    @Override
    public CompletableFuture<Responses.SetSessionConfigOptionResponse> setConfigOption(
            String configId, String sessionId, Object value, Map<String, Object> kwargs) {
        if (!(value instanceof String str)) {
            throw new RequestError(-32602,
                    "Config option '" + configId + "' expects a string value, got "
                            + (value == null ? "null" : value.getClass().getSimpleName()));
        }
        if ("mode".equals(configId)) {
            if (modes.isPresent() && sessionModeStates.containsKey(sessionId)) {
                boolean valid = modes.get().availableModes().stream()
                        .anyMatch(m -> m.id().equals(str));
                if (!valid) {
                    throw new RequestError(-32602, "Invalid mode: " + str);
                }
                SessionModeState state = sessionModeStates.get(sessionId);
                sessionModes.put(sessionId, str);
                sessionModeStates.put(sessionId, new SessionModeState(
                        state.availableModes(), str));
                resetAgent(sessionId);
                if (loadSessions) {
                    persistSession(sessionId).join();
                }
            }
        } else if ("model".equals(configId)) {
            if (models.isPresent()) {
                boolean valid = models.get().stream()
                        .anyMatch(m -> m.value().equals(str));
                if (!valid) {
                    throw new RequestError(-32602, "Invalid model: " + str);
                }
                sessionModels.put(sessionId, str);
                resetAgent(sessionId);
                if (loadSessions) {
                    persistSession(sessionId).join();
                }
            }
        } else {
            throw new RequestError(-32602, "Unknown config option: " + configId);
        }
        List<SessionConfigOption> options = buildConfigOptions(sessionId);
        return CompletableFuture.completedFuture(
                new Responses.SetSessionConfigOptionResponse(options));
    }

    @Override
    public CompletableFuture<Void> cancel(String sessionId, Map<String, Object> kwargs) {
        this.cancelled = true;
        return CompletableFuture.completedFuture(null);
    }

    // -----------------------------------------------------------------
    // Session option helpers
    // -----------------------------------------------------------------

    /**
     * Build the list of session configuration options.
     * Combines mode and model selectors if available. Modes
     * are exposed as {@code category="mode"} selects, models
     * as {@code category="model"} selects.
     */
    public List<SessionConfigOption> buildConfigOptions(String sessionId) {
        List<SessionConfigOption> options = new ArrayList<>();
        if (modes.isPresent()) {
            SessionModeState m = modes.get();
            String currentMode = sessionModes.getOrDefault(sessionId, m.currentModeId());
            List<SessionConfig.SessionConfigSelectOption> modeOptions = m.availableModes().stream()
                    .map(mode -> new SessionConfig.SessionConfigSelectOption(
                            mode.id(), mode.name(), mode.description()))
                    .collect(Collectors.toList());
            SessionConfig.SessionConfigOptionSelect modeSelect = new SessionConfig.SessionConfigOptionSelect(
                    "mode", "Session Mode",
                    Optional.of("Controls how the agent requests permission"),
                    "mode", "select", currentMode, modeOptions);
            options.add(wrapConfigOption(modeSelect));
        }
        if (models.isPresent() && !models.get().isEmpty()) {
            List<ModelSpec> ms = models.get();
            String currentModel = sessionModels.getOrDefault(sessionId, ms.get(0).value());
            List<SessionConfig.SessionConfigSelectOption> modelOptions = new ArrayList<>();
            for (ModelSpec m : ms) {
                modelOptions.add(new SessionConfig.SessionConfigSelectOption(
                        m.value(), m.name(),
                        Optional.of(m.description().isEmpty() ? "" : m.description())));
            }
            SessionConfig.SessionConfigOptionSelect modelSelect = new SessionConfig.SessionConfigOptionSelect(
                    "model", "Model",
                    Optional.of("The LLM model to use for this session"),
                    "model", "select", currentModel, modelOptions);
            options.add(wrapConfigOption(modelSelect));
        }
        return options;
    }

    /** Wrap a select config option in the v0.8.x root wrapper
     *  (the Java port always wraps; v0.9+ clients ignore the
     *  root and read the select directly). Mirrors the
     *  Python port's compatibility shim. */
    private static SessionConfigOption wrapConfigOption(BareSessionConfigOption bare) {
        if (bare instanceof SessionConfig.SessionConfigOptionSelect s) {
            return new SessionConfigOptionRoot(s);
        }
        if (bare instanceof SessionConfig.SessionConfigOptionBoolean b) {
            return null; // booleans aren't wrapped; not used by the server today
        }
        throw new IllegalStateException("Unknown config option variant: " + bare);
    }

    void initializeSessionOptions(String sessionId) {
        if (modes.isPresent()) {
            sessionModes.put(sessionId, modes.get().currentModeId());
            sessionModeStates.put(sessionId, modes.get());
        }
        if (models.isPresent() && !models.get().isEmpty()) {
            sessionModels.put(sessionId, models.get().get(0).value());
        }
    }

    boolean restoreSessionOptions(String sessionId, Map<String, Object> metadata) {
        boolean changed = false;
        if (modes.isPresent()) {
            Object savedMode = metadata.get(ACP_MODE_METADATA_KEY);
            if (savedMode instanceof String s) {
                boolean valid = modes.get().availableModes().stream()
                        .anyMatch(m -> m.id().equals(s));
                if (valid) {
                    SessionModeState state = sessionModeStates.get(sessionId);
                    sessionModes.put(sessionId, s);
                    sessionModeStates.put(sessionId,
                            new SessionModeState(state.availableModes(), s));
                    changed = !s.equals(modes.get().currentModeId());
                }
            }
        }
        if (models.isPresent() && !models.get().isEmpty()) {
            Object savedModel = metadata.get(ACP_MODEL_METADATA_KEY);
            if (savedModel instanceof String s) {
                boolean valid = models.get().stream().anyMatch(m -> m.value().equals(s));
                if (valid) {
                    sessionModels.put(sessionId, s);
                    changed = changed || !s.equals(models.get().get(0).value());
                }
            }
        }
        return changed;
    }

    Map<String, Object> sessionConfig(String sessionId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ACP_SESSION_METADATA_KEY, true);
        metadata.put("cwd", sessionCwds.getOrDefault(sessionId, ""));
        if (sessionModes.containsKey(sessionId)) {
            metadata.put(ACP_MODE_METADATA_KEY, sessionModes.get(sessionId));
        }
        if (sessionModels.containsKey(sessionId)) {
            metadata.put(ACP_MODEL_METADATA_KEY, sessionModels.get(sessionId));
        }
        return Map.of(
                "configurable", Map.of("thread_id", sessionId),
                "metadata", metadata);
    }

    StreamingStateGraph checkpointedAgent(String sessionId) {
        if (agent == null || !sessionId.equals(agentSessionId)) {
            resetAgent(sessionId);
        }
        Checkpointer cp = agent.checkpointer();
        if (cp == null) {
            throw new IllegalStateException(
                    "session/load requires an agent compiled with a checkpointer");
        }
        return agent;
    }

    CompletableFuture<Void> persistSession(String sessionId) {
        StreamingStateGraph cp = checkpointedAgent(sessionId);
        return cp.aupdateState(sessionConfig(sessionId), Map.of(), "__start__");
    }

    CompletableFuture<Void> replaySession(String sessionId, StreamingStateGraph cp) {
        return cp.agetStateHistory(sessionConfig(sessionId)).thenAccept(snapshots -> {
            // Walk newest-to-oldest so the first occurrence of each
            // message id wins. Then iterate the unique messages in
            // chronological order to drive the replay.
            List<StateSnapshot> reversed = new ArrayList<>(snapshots);
            java.util.Collections.reverse(reversed);
            Map<String, Object> messages = new LinkedHashMap<>();
            for (StateSnapshot s : reversed) {
                List<?> msgs = s.values() == null
                        ? List.of()
                        : (List<?>) s.values().getOrDefault("messages", List.of());
                for (Object m : msgs) {
                    Object id = invokeAccessor(m, "id");
                    if (id instanceof String sid && !messages.containsKey(sid)) {
                        messages.put(sid, m);
                    }
                }
            }
            Map<String, Map<String, Object>> activeToolCalls = new LinkedHashMap<>();
            for (Object m : messages.values()) {
                String type = (String) invokeAccessor(m, "type");
                if ("human".equals(type)) {
                    replayHumanMessage(sessionId, (String) invokeAccessor(m, "id"), m);
                } else if ("ai".equals(type)) {
                    replayAiMessage(sessionId, (String) invokeAccessor(m, "id"),
                            m, activeToolCalls);
                } else if ("tool".equals(type)) {
                    replayToolMessage(sessionId, m, activeToolCalls);
                }
            }
        });
    }

    void replayHumanMessage(String sessionId, String messageId, Object message) {
        for (ContentBlock block : replayContentBlocks(message)) {
            connection().sessionUpdate(sessionId,
                    new SessionUpdate.UserMessageChunk(messageId, block),
                    "DeepAgent");
        }
    }

    void replayAiMessage(String sessionId, String messageId, Object message,
                         Map<String, Map<String, Object>> activeToolCalls) {
        for (ContentBlock block : replayContentBlocks(message)) {
            connection().sessionUpdate(sessionId,
                    new SessionUpdate.AgentMessageChunk(messageId, block),
                    "DeepAgent");
        }
        List<?> toolCalls = (List<?>) invokeAccessor(message, "tool_calls");
        if (toolCalls == null) return;
        for (Object tc : toolCalls) {
            Object id = invokeAccessor(tc, "id");
            if (!(id instanceof String toolId)) continue;
            String name = (String) invokeAccessor(tc, "name");
            @SuppressWarnings("unchecked")
            Map<String, Object> args = (Map<String, Object>) invokeAccessor(tc, "args");
            activeToolCalls.put(toolId, Map.of("name", name, "args", args));
            connection().sessionUpdate(sessionId,
                    createToolCallStart(toolId, name, args), "DeepAgent");
            if ("write_todos".equals(name)) {
                Object todosObj = args == null ? null : args.get("todos");
                if (todosObj instanceof List<?> todos) {
                    handleTodoUpdate(sessionId, castToObjectMapList(todos), false).join();
                }
            }
        }
    }

    void replayToolMessage(String sessionId, Object message,
                           Map<String, Map<String, Object>> activeToolCalls) {
        Object toolCallId = invokeAccessor(message, "tool_call_id");
        if (!(toolCallId instanceof String tcid)) return;
        Map<String, Object> info = activeToolCalls.get(tcid);
        if (info == null || "edit_file".equals(info.get("name"))) return;
        Object blocks = invokeAccessor(message, "content_blocks");
        StringBuilder text = new StringBuilder();
        if (blocks instanceof List<?> list) {
            for (Object b : list) {
                if (b instanceof Map<?, ?> mb) {
                    Object type = mb.get("type");
                    Object bt = mb.get("text");
                    if ("text".equals(type) && bt != null) text.append(bt);
                }
            }
        }
        String formatted = text.toString();
        if ("execute".equals(info.get("name"))) {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = (Map<String, Object>) info.get("args");
            String command = args == null ? "" : String.valueOf(args.getOrDefault("command", ""));
            formatted = Utils.formatExecuteResult(command, formatted);
        }
        Object status = invokeAccessor(message, "status");
        String toolStatus = "error".equals(status) ? "failed" : "completed";
        connection().sessionUpdate(sessionId,
                AcpBlock.updateToolCall(tcid, toolStatus,
                        List.of(new ToolCallContentText(formatted))),
                "DeepAgent");
    }

    void forgetSession(String sessionId) {
        sessionCwds.remove(sessionId);
        if (sessionId.equals(agentSessionId)) {
            agent = null;
            agentSessionId = null;
        }
    }

    void resetAgent(String sessionId) {
        String newCwd = sessionCwds.get(sessionId);
        if (newCwd != null) this.cwd = newCwd;
        if (agentInput instanceof Graph g) {
            this.agent = g.graph();
        } else {
            Factory f = (Factory) agentInput;
            String mode = sessionModes.getOrDefault(sessionId,
                    modes.map(SessionModeState::currentModeId).orElse("auto"));
            Optional<String> model = models.isPresent()
                    ? Optional.ofNullable(sessionModels.get(sessionId))
                    : Optional.empty();
            this.agent = f.factory().apply(
                    new AgentSessionContext(cwd, mode, model));
        }
        this.agentSessionId = sessionId;
    }

    // -----------------------------------------------------------------
    // Streaming helpers
    // -----------------------------------------------------------------

    CompletableFuture<Void> logText(String sessionId, String text) {
        Client conn = connection();
        if (conn == null) return CompletableFuture.completedFuture(null);
        conn.sessionUpdate(sessionId,
                AcpBlock.updateAgentMessage(AcpBlock.textBlock(text)),
                "DeepAgent");
        return CompletableFuture.completedFuture(null);
    }

    boolean allTasksCompleted(List<Map<String, Object>> plan) {
        if (plan == null || plan.isEmpty()) return true;
        for (Map<String, Object> t : plan) {
            if (!"completed".equals(t.get("status"))) return false;
        }
        return true;
    }

    CompletableFuture<Void> clearPlan(String sessionId) {
        Client conn = connection();
        if (conn == null) return CompletableFuture.completedFuture(null);
        conn.sessionUpdate(sessionId,
                new SessionUpdate.AgentPlanUpdate(List.of()),
                "DeepAgent");
        sessionPlans.put(sessionId, List.of());
        return CompletableFuture.completedFuture(null);
    }

    CompletableFuture<Void> handleTodoUpdate(String sessionId,
                                             List<Map<String, Object>> todos,
                                             boolean logPlan) {
        Client conn = connection();
        if (conn == null) return CompletableFuture.completedFuture(null);
        List<PlanEntry> entries = new ArrayList<>();
        for (Map<String, Object> todo : todos) {
            String content = String.valueOf(todo.getOrDefault("content", ""));
            String status = String.valueOf(todo.getOrDefault("status", "pending"));
            if (!"pending".equals(status) && !"in_progress".equals(status)
                    && !"completed".equals(status)) {
                status = "pending";
            }
            entries.add(new PlanEntry(content, status, "medium"));
        }
        conn.sessionUpdate(sessionId,
                new SessionUpdate.AgentPlanUpdate(entries),
                "DeepAgent");
        if (logPlan) {
            StringBuilder planText = new StringBuilder("## Plan\n\n");
            for (int i = 0; i < todos.size(); i++) {
                planText.append(i + 1).append(". ")
                        .append(todos.get(i).getOrDefault("content", ""))
                        .append("\n");
            }
            return logText(sessionId, planText.toString());
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Build a tool-call start session update. Mirrors
     * {@code _create_tool_call_start}: maps the tool name to
     * the ACP {@link ToolKind}, builds a title, and for
     * {@code edit_file} produces a diff content item.
     */
    public SessionUpdate.ToolCallStart createToolCallStart(
            String toolId, String toolName, Map<String, Object> toolArgs) {
        String kind = switch (toolName) {
            case "read_file" -> ToolKind.READ;
            case "edit_file" -> ToolKind.EDIT;
            case "write_file" -> ToolKind.EDIT;
            case "ls", "glob", "grep" -> ToolKind.SEARCH;
            case "execute" -> ToolKind.EXECUTE;
            default -> ToolKind.OTHER;
        };
        if (toolArgs == null) toolArgs = Map.of();
        if ("read_file".equals(toolName)) {
            Object path = toolArgs.get("file_path");
            String title = path != null ? "Read `" + path + "`" : toolName;
            return AcpBlock.startToolCall(toolId, title, kind, "pending", toolArgs);
        }
        if ("edit_file".equals(toolName)) {
            String path = String.valueOf(toolArgs.getOrDefault("file_path", ""));
            String oldString = String.valueOf(toolArgs.getOrDefault("old_string", ""));
            String newString = String.valueOf(toolArgs.getOrDefault("new_string", ""));
            String title = !path.isEmpty() ? "Edit `" + path + "`" : toolName;
            if (!path.isEmpty() && !oldString.isEmpty() && !newString.isEmpty()) {
                ToolCallContent diff = new ToolCallContentDiff(path, newString, oldString);
                return AcpBlock.startEditToolCall(toolId, title, path, diff, List.of(diff));
            }
            return AcpBlock.startToolCall(toolId, title, kind, "pending", toolArgs);
        }
        if ("write_file".equals(toolName)) {
            Object path = toolArgs.get("file_path");
            String title = path != null ? "Write `" + path + "`" : toolName;
            return AcpBlock.startToolCall(toolId, title, kind, "pending", toolArgs);
        }
        if ("execute".equals(toolName)) {
            String command = String.valueOf(toolArgs.getOrDefault("command", ""));
            String title = !command.isEmpty() ? command : "Execute command";
            return AcpBlock.startToolCall(toolId, title, kind, "pending", toolArgs);
        }
        return AcpBlock.startToolCall(toolId, toolName, kind, "pending", toolArgs);
    }

    // -----------------------------------------------------------------
    // Prompt loop
    // -----------------------------------------------------------------

    @Override
    public CompletableFuture<Responses.PromptResponse> prompt(
            List<ContentBlock> prompt, String sessionId, String messageId,
            Map<String, Object> kwargs) {
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(sessionId, "sessionId");
        if (agent == null || (agentSessionId != null && !agentSessionId.equals(sessionId))) {
            resetAgent(sessionId);
        }
        if (agent == null) {
            throw new IllegalStateException("Agent initialization failed");
        }
        // Auto-attach a checkpointer if missing — mirrors the
        // Python port's `self._agent.checkpointer = MemorySaver()`
        // line in `prompt`.
        if (agent.checkpointer() == null) {
            throw new IllegalStateException(
                    "Agent has no checkpointer; provide a CompiledStateGraph with a checkpointer " +
                            "or wrap it in a StreamingStateGraph that supplies one.");
        }
        cancelled = false;

        // Convert ACP content blocks to LangChain multimodal content.
        List<Map<String, Object>> contentBlocks = new ArrayList<>();
        for (ContentBlock block : prompt) {
            contentBlocks.addAll(Utils.convertContentBlockToContentBlocks(block, cwd));
        }
        Map<String, Object> config = sessionConfig(sessionId);

        Map<String, Map<String, Object>> activeToolCalls = new LinkedHashMap<>();
        Map<Integer, Map<String, Object>> toolCallAccumulator = new LinkedHashMap<>();

        return runPromptLoop(sessionId, contentBlocks, config, activeToolCalls,
                toolCallAccumulator, List.of());
    }

    private CompletableFuture<Responses.PromptResponse> runPromptLoop(
            String sessionId,
            List<Map<String, Object>> contentBlocks,
            Map<String, Object> config,
            Map<String, Map<String, Object>> activeToolCalls,
            Map<Integer, Map<String, Object>> toolCallAccumulator,
            List<Map<String, Object>> userDecisions) {

        if (cancelled) {
            cancelled = false;
            return CompletableFuture.completedFuture(
                    new Responses.PromptResponse(Responses.PromptResponse.CANCELLED));
        }
        Object input;
        if (!userDecisions.isEmpty()) {
            input = new Command(Map.of("resume", Map.of("decisions", userDecisions)), null, null);
        } else {
            input = Map.of("messages", List.of(Map.of(
                    "role", "user", "content", contentBlocks)));
        }
        List<String> streamModes = List.of("messages", "updates");
        boolean subgraphs = true;
        List<StreamingStateGraph.StreamEvent> events = new ArrayList<>();
        Flow.Publisher<StreamingStateGraph.StreamEvent> publisher =
                agent.astream(input, config, streamModes, subgraphs);
        CollectingSubscriber sub = new CollectingSubscriber(events);
        publisher.subscribe(sub);
        // Block-style collection: the default placeholder
        // throws on subscribe, so we propagate the failure to
        // the caller with a clear message. Real graph runtimes
        // are reactive.
        try {
            sub.completable.join();
        } catch (RuntimeException re) {
            throw re;
        }
        // Dispatch tool-call chunks.
        List<StateSnapshot.Interrupt> pendingInterrupts = new ArrayList<>();
        for (StreamingStateGraph.StreamEvent ev : events) {
            if (cancelled) {
                cancelled = false;
                return CompletableFuture.completedFuture(
                        new Responses.PromptResponse(Responses.PromptResponse.CANCELLED));
            }
            if ("updates".equals(ev.streamMode())) {
                @SuppressWarnings("unchecked")
                Map<String, Object> updates = (Map<String, Object>) ev.data();
                if (updates != null && updates.containsKey("__interrupt__")) {
                    Object raw = updates.get("__interrupt__");
                    if (raw instanceof List<?> list) {
                        for (Object interruptObj : list) {
                            Object interruptValue = invokeAccessor(interruptObj, "value");
                            if (!(interruptValue instanceof Map<?, ?>)) {
                                throw new RequestError(-32600,
                                        "ACP limitation: this agent raised a free-form LangGraph " +
                                                "interrupt(), which ACP cannot display. " +
                                                "Use the HumanInTheLoopMiddleware-style action_requests " +
                                                "/review_configs interrupt shape instead.");
                            }
                        }
                        // In the Python port the snapshot is read
                        // *after* the iterator closes; here we
                        // stash the raw interrupt list and let
                        // the caller re-pull state once the
                        // iterator drains.
                        for (Object interruptObj : list) {
                            Object id = invokeAccessor(interruptObj, "id");
                            Object value = invokeAccessor(interruptObj, "value");
                            pendingInterrupts.add(new StateSnapshot.Interrupt(
                                    id == null ? null : id.toString(), value));
                        }
                        continue;
                    }
                }
                if (updates != null) {
                    Object nodeUpdate = updates.get("tools");
                    if (nodeUpdate instanceof Map<?, ?> nm) {
                        Object todos = nm.get("todos");
                        if (todos instanceof List<?> tl) {
                            handleTodoUpdate(sessionId, castToObjectMapList(tl), false).join();
                        }
                    }
                }
                continue;
            }
            // "messages" stream mode: pair = (messageChunk, metadata).
            Object data = ev.data();
            if (data instanceof List<?> pair && pair.size() >= 1) {
                Object messageChunk = pair.get(0);
                processToolCallChunks(sessionId, messageChunk, activeToolCalls,
                        toolCallAccumulator).join();
                if (messageChunk instanceof String s) {
                    if (ev.namespace() == null || ev.namespace().isEmpty()) {
                        logText(sessionId, s).join();
                    }
                } else {
                    Object type = invokeAccessor(messageChunk, "type");
                    if ("tool".equals(type)) {
                        Object toolCallId = invokeAccessor(messageChunk, "tool_call_id");
                        if (toolCallId instanceof String tcid
                                && activeToolCalls.containsKey(tcid)) {
                            Object toolInfo = activeToolCalls.get(tcid);
                            Object toolName = invokeAccessor(toolInfo, "name");
                            if (!"edit_file".equals(toolName)) {
                                Object content = invokeAccessor(messageChunk, "content");
                                String formatted;
                                if ("execute".equals(toolName)) {
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> targs =
                                            (Map<String, Object>) invokeAccessor(toolInfo, "args");
                                    String command = targs == null
                                            ? ""
                                            : String.valueOf(targs.getOrDefault("command", ""));
                                    formatted = Utils.formatExecuteResult(command,
                                            String.valueOf(content));
                                } else {
                                    formatted = String.valueOf(content);
                                }
                                Client conn = connection();
                                if (conn != null) {
                                    conn.sessionUpdate(sessionId,
                                            AcpBlock.updateToolCall(tcid, "completed",
                                                    List.of(new ToolCallContentText(formatted))),
                                            "DeepAgent");
                                }
                            }
                        }
                    } else {
                        Object content = invokeAccessor(messageChunk, "content");
                        if (content != null) {
                            String text;
                            if (content instanceof String cs) {
                                text = cs;
                            } else if (content instanceof List<?> cl) {
                                StringBuilder sb = new StringBuilder();
                                for (Object b : cl) {
                                    if (b instanceof Map<?, ?> mb
                                            && "text".equals(mb.get("type"))) {
                                        sb.append(mb.get("text"));
                                    } else if (b instanceof String bs) {
                                        sb.append(bs);
                                    }
                                }
                                text = sb.toString();
                            } else {
                                text = content.toString();
                            }
                            if (!text.isEmpty()
                                    && (ev.namespace() == null || ev.namespace().isEmpty())) {
                                logText(sessionId, text).join();
                            }
                        }
                    }
                }
            }
        }
        // Re-pull the post-stream snapshot, resolve interrupts,
        // decide, and loop.
        StateSnapshot snapshot = agent.agetState(config).join();
        if (!pendingInterrupts.isEmpty() || snapshot.interrupts().isPresent()) {
            List<Map<String, Object>> decisions = handleInterrupts(
                    snapshot, sessionId, pendingInterrupts).join();
            if (!decisions.isEmpty()) {
                return runPromptLoop(sessionId, contentBlocks, config,
                        activeToolCalls, toolCallAccumulator, decisions);
            }
        }
        return CompletableFuture.completedFuture(
                new Responses.PromptResponse(Responses.PromptResponse.END_TURN));
    }

    CompletableFuture<Void> processToolCallChunks(
            String sessionId,
            Object messageChunk,
            Map<String, Map<String, Object>> activeToolCalls,
            Map<Integer, Map<String, Object>> toolCallAccumulator) {
        if (messageChunk instanceof String) return CompletableFuture.completedFuture(null);
        Object rawChunks = invokeAccessor(messageChunk, "tool_call_chunks");
        if (!(rawChunks instanceof List<?> chunks) || chunks.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        for (Object c : chunks) {
            if (!(c instanceof Map<?, ?> cm)) continue;
            Object chunkId = cm.get("id");
            Object chunkName = cm.get("name");
            Object chunkArgs = cm.get("args");
            Object idxObj = cm.get("index");
            int index = idxObj instanceof Number n ? n.intValue() : 0;
            String cid = chunkId == null ? null : chunkId.toString();
            String cname = chunkName == null ? null : chunkName.toString();
            String cargs = chunkArgs == null ? "" : chunkArgs.toString();
            Map<String, Object> existing = toolCallAccumulator.get(index);
            boolean isNew = existing == null
                    || !cid.equals(existing.get("id"));
            if (cid != null && cname != null && isNew) {
                Map<String, Object> acc = new LinkedHashMap<>();
                acc.put("id", cid);
                acc.put("name", cname);
                acc.put("args_str", "");
                toolCallAccumulator.put(index, acc);
            }
            if (!cargs.isEmpty() && toolCallAccumulator.containsKey(index)) {
                Map<String, Object> acc = toolCallAccumulator.get(index);
                acc.put("args_str", acc.get("args_str") + cargs);
            }
        }
        for (Map.Entry<Integer, Map<String, Object>> e : new ArrayList<>(toolCallAccumulator.entrySet())) {
            Map<String, Object> acc = e.getValue();
            String toolId = (String) acc.get("id");
            String toolName = (String) acc.get("name");
            String argsStr = (String) acc.get("args_str");
            if (toolId != null
                    && !activeToolCalls.containsKey(toolId)
                    && argsStr != null
                    && !argsStr.isEmpty()) {
                Map<String, Object> toolArgs;
                try {
                    toolArgs = parseJsonObject(argsStr);
                } catch (Exception ex) {
                    continue;
                }
                activeToolCalls.put(toolId, Map.of(
                        "name", toolName == null ? "" : toolName,
                        "args", toolArgs));
                Client conn = connection();
                if (conn != null) {
                    conn.sessionUpdate(sessionId,
                            createToolCallStart(toolId, toolName, toolArgs),
                            "DeepAgent");
                }
                if ("write_todos".equals(toolName)) {
                    Object todosObj = toolArgs.get("todos");
                    if (todosObj instanceof List<?> todos) {
                        handleTodoUpdate(sessionId, castToObjectMapList(todos), false).join();
                    }
                }
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Handle a list of pending interrupts. Mirrors the Python
     * {@code _handle_interrupts} loop: auto-approve commands
     * the user has previously allowed, present everything else
     * to the client via {@link Client#requestPermission}.
     */
    CompletableFuture<List<Map<String, Object>>> handleInterrupts(
            StateSnapshot currentState,
            String sessionId,
            List<StateSnapshot.Interrupt> pendingInterrupts) {
        List<Map<String, Object>> userDecisions = new ArrayList<>();
        List<StateSnapshot.Interrupt> interrupts = pendingInterrupts;
        if (interrupts.isEmpty() && currentState.interrupts().isPresent()) {
            interrupts = currentState.interrupts().get();
        }
        if (interrupts.isEmpty()) {
            return CompletableFuture.completedFuture(userDecisions);
        }
        for (StateSnapshot.Interrupt interrupt : interrupts) {
            String toolCallId = interrupt.id();
            Object interruptValue = interrupt.value();
            List<?> actionRequests = List.of();
            if (interruptValue instanceof Map<?, ?> iv) {
                Object ar = iv.get("action_requests");
                if (ar instanceof List<?> list) actionRequests = list;
            }
            for (Object action : actionRequests) {
                if (!(action instanceof Map<?, ?> am)) continue;
                Object nameObj = am.get("name");
                Object argsObj = am.get("args");
                String toolName = nameObj == null ? "tool" : nameObj.toString();
                @SuppressWarnings("unchecked")
                Map<String, Object> toolArgs = argsObj instanceof Map<?, ?>
                        ? (Map<String, Object>) argsObj
                        : Map.of();

                if ("write_todos".equals(toolName) && sessionPlans.containsKey(sessionId)) {
                    List<Map<String, Object>> existingPlan = sessionPlans.get(sessionId);
                    boolean allCompleted = allTasksCompleted(existingPlan);
                    if (!allCompleted) {
                        Object todosObj = toolArgs.get("todos");
                        if (todosObj instanceof List<?> tl) {
                            sessionPlans.put(sessionId, castToObjectMapList(tl));
                        }
                        userDecisions.add(Map.of("type", "approve"));
                        continue;
                    }
                }
                if (allowedCommandTypes.containsKey(sessionId)) {
                    Set<AllowedKey> allowed = allowedCommandTypes.get(sessionId);
                    if ("execute".equals(toolName)) {
                        String command = String.valueOf(toolArgs.getOrDefault("command", ""));
                        if (!Utils.containsDangerousPatterns(command)) {
                            List<String> types = Utils.extractCommandTypes(command);
                            if (!types.isEmpty() && types.stream()
                                    .allMatch(t -> allowed.contains(new AllowedKey("execute", t)))) {
                                userDecisions.add(Map.of("type", "approve"));
                                continue;
                            }
                        }
                    } else if (allowed.contains(new AllowedKey(toolName, NULL_ALLOWED_ARG))) {
                        userDecisions.add(Map.of("type", "approve"));
                        continue;
                    }
                }
                String title = buildPermissionTitle(toolName, toolArgs);
                String desc = buildPermissionDescription(toolName, toolArgs);
                List<PermissionOption> options = List.of(
                        new PermissionOption("approve", "Approve", PermissionOption.Kind.ALLOW_ONCE.wire()),
                        new PermissionOption("reject", "Reject", PermissionOption.Kind.REJECT_ONCE.wire()),
                        new PermissionOption("approve_always",
                                "Always allow " + desc + " commands",
                                PermissionOption.Kind.ALLOW_ALWAYS.wire()));
                Client.ToolCallView view = new Client.ToolCallView(
                        toolCallId, title, toolArgs, List.of());
                Client.PermissionOutcome outcome = connection().requestPermission(
                        sessionId, view, options);
                if (outcome.outcome() == Client.PermissionOutcome.Kind.SELECTED) {
                    String decision = outcome.optionId();
                    if ("approve_always".equals(decision)) {
                        Set<AllowedKey> allowed = allowedCommandTypes.computeIfAbsent(
                                sessionId, k -> ConcurrentHashMap.newKeySet());
                        if ("execute".equals(toolName)) {
                            String command = String.valueOf(toolArgs.getOrDefault("command", ""));
                            for (String ct : Utils.extractCommandTypes(command)) {
                                allowed.add(new AllowedKey("execute", ct));
                            }
                        } else {
                            allowed.add(new AllowedKey(toolName, NULL_ALLOWED_ARG));
                        }
                        userDecisions.add(Map.of("type", "approve"));
                    } else if ("write_todos".equals(toolName) && "reject".equals(decision)) {
                        clearPlan(sessionId).join();
                        Map<String, Object> decision2 = new LinkedHashMap<>();
                        decision2.put("type", "reject");
                        decision2.put("feedback",
                                "The user rejected the plan. Please ask them for feedback " +
                                        "on how the plan can be improved, then create a new " +
                                        "and improved plan using this same write_todos tool.");
                        userDecisions.add(decision2);
                    } else if ("write_todos".equals(toolName) && "approve".equals(decision)) {
                        Object todosObj = toolArgs.get("todos");
                        if (todosObj instanceof List<?> tl) {
                            sessionPlans.put(sessionId, castToObjectMapList(tl));
                        }
                        userDecisions.add(Map.of("type", decision));
                    } else {
                        userDecisions.add(Map.of("type", decision));
                    }
                } else {
                    userDecisions.add(Map.of("type", "reject"));
                    if ("write_todos".equals(toolName)) {
                        clearPlan(sessionId).join();
                    }
                }
            }
        }
        return CompletableFuture.completedFuture(userDecisions);
    }

    private static String buildPermissionTitle(String toolName,
                                               Map<String, Object> toolArgs) {
        return switch (toolName) {
            case "write_todos" -> "Review Plan";
            case "edit_file" -> "Edit `"
                    + toolArgs.getOrDefault("file_path", "file") + "`";
            case "write_file" -> "Write `"
                    + toolArgs.getOrDefault("file_path", "file") + "`";
            case "execute" -> {
                String command = String.valueOf(toolArgs.getOrDefault("command", ""));
                String display = Utils.truncateExecuteCommandForDisplay(command);
                yield command.isEmpty() ? "Execute command" : "Execute: `" + display + "`";
            }
            default -> toolName;
        };
    }

    private static String buildPermissionDescription(String toolName,
                                                    Map<String, Object> toolArgs) {
        if ("execute".equals(toolName)) {
            String command = String.valueOf(toolArgs.getOrDefault("command", ""));
            List<String> types = Utils.extractCommandTypes(command);
            if (types.isEmpty()) return toolName;
            if (types.size() == 1) return "`" + types.get(0) + "`";
            List<String> unique = new ArrayList<>();
            for (String t : types) {
                if (!unique.contains(t)) unique.add(t);
            }
            return unique.stream().map(t -> "`" + t + "`")
                    .collect(Collectors.joining(", "));
        }
        return toolName;
    }

    // -----------------------------------------------------------------
    // Replay-content helpers
    // -----------------------------------------------------------------

    /**
     * Convert a LangChain message's content blocks into
     * replayable ACP content blocks. Mirrors the Python
     * {@code _replay_content_blocks} helper.
     */
    @SuppressWarnings("unchecked")
    static List<ContentBlock> replayContentBlocks(Object message) {
        List<ContentBlock> out = new ArrayList<>();
        Object blocks = invokeAccessor(message, "content_blocks");
        if (!(blocks instanceof List<?> list)) return out;
        for (Object b : list) {
            if (!(b instanceof Map<?, ?> block)) continue;
            Object type = block.get("type");
            Object data = block.get("base64");
            Object mime = block.get("mime_type");
            if ("text".equals(type)) {
                Object text = block.get("text");
                out.add(new ContentBlocks.TextContentBlock(
                        text == null ? "" : text.toString()));
            } else if (data instanceof String ds && mime instanceof String ms) {
                if ("image".equals(type)) {
                    Object url = block.get("url");
                    out.add(new ContentBlocks.ImageContentBlock(
                            ds, ms,
                            Optional.ofNullable(url == null ? null : url.toString()),
                            Optional.empty()));
                } else if ("audio".equals(type)) {
                    out.add(new ContentBlocks.AudioContentBlock(ds, ms, Optional.empty()));
                }
            }
        }
        return out;
    }

    // -----------------------------------------------------------------
    // Utility helpers
    // -----------------------------------------------------------------

    /** Reflective getter used to read fields off LangChain
     *  message objects without coupling to deepagents-core
     *  message types. Returns {@code null} for missing
     *  accessors / values. */
    private static Object invokeAccessor(Object target, String method) {
        if (target == null) return null;
        if (target instanceof Map<?, ?> m) return m.get(method);
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<Map<String, Object>> castToObjectMapList(List<?> raw) {
        List<Map<String, Object>> out = new ArrayList<>(raw.size());
        for (Object o : raw) {
            if (o instanceof Map<?, ?> m) {
                out.add((Map<String, Object>) m);
            } else if (o != null) {
                out.add(new LinkedHashMap<>());
            }
        }
        return out;
    }

    private static Map<String, Object> parseJsonObject(String json) {
        // Tiny dependency-free parser: we use Jackson if available,
        // otherwise the body falls through to an empty map. The
        // deepagents-core runtime already pulls Jackson, so this
        // path is the common one.
        try {
            com.fasterxml.jackson.databind.ObjectMapper m =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = m.readValue(json, Map.class);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** A (toolName, cmdType) pair used as the key in
     *  {@link #allowedCommandTypes}. {@code cmdType} is null
     *  for non-execute tools. Mirrors the Python
     *  {@code (tool_name, None)} / {@code (tool_name, cmd_type)}
     *  tuple keys. */
    record AllowedKey(String toolName, String cmdType) {
    }

    /**
     * A blocking {@link Flow.Subscriber} that drains a
     * {@link Flow.Publisher} into a list. Mirrors the
     * Python port's {@code async for stream_chunk in agent.astream(...)}
     * consumption pattern. The default
     * {@link StreamingStateGraph.Unsupported} throws on
     * subscribe; this subscriber surfaces the failure
     * through {@link #completable}.
     */
    private static final class CollectingSubscriber
            implements Flow.Subscriber<StreamingStateGraph.StreamEvent> {
        private final List<StreamingStateGraph.StreamEvent> sink;
        private final CompletableFuture<Void> completable = new CompletableFuture<>();
        private Flow.Subscription subscription;

        CollectingSubscriber(List<StreamingStateGraph.StreamEvent> sink) {
            this.sink = sink;
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            this.subscription = s;
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(StreamingStateGraph.StreamEvent item) {
            sink.add(item);
            if (subscription != null) subscription.request(1);
        }

        @Override
        public void onError(Throwable throwable) {
            completable.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            completable.complete(null);
        }
    }
}
