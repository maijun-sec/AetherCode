package org.aethercode.evals.clbench.system;

import org.aethercode.evals.orchestration.AgentRuntime;
import org.aethercode.evals.orchestration.AgentRuntime.RuntimeResult;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deep Agents system adapter for continual-learning-bench.
 *
 * <p>Wraps a {@code deepagents} agent as a
 * {@link ClbenchTypes.ContinualLearningSystem}, using the agent's
 * <em>own</em> memory mechanism as the continual-learning substrate:</p>
 *
 * <ul>
 *   <li>{@code MemoryMiddleware} (enabled via
 *       {@code create_deep_agent(memory=...)}) loads
 *       {@code /memory/AGENTS.md} into the system prompt at the start of
 *       every turn, wrapped in {@code <agent_memory>} boundary markers
 *       and treated as untrusted data.</li>
 *   <li>The agent itself distils and updates that file with its built-in
 *       {@code edit_file} / {@code write_file} tools as it learns. There
 *       is no separate reflection / extraction process -- the agent owns
 *       its memory.</li>
 * </ul>
 *
 * <p>The memory file lives in the in-state filesystem
 * ({@code DeepAgentState["files"]}); this adapter threads it from one
 * {@link #respond} call to the next, which is what carries learning
 * across instances. {@link #reset()} wipes it, so the framework's
 * stateless baseline is genuinely stateless and {@code mean_gain}
 * measures only what the agent learned.</p>
 *
 * <p>Java 21 port of {@code deepagents_clbench.system.system}. The
 * actual call into {@code deepagents.create_deep_agent} is delegated to
 * {@link DeepAgentFactory}, which a downstream integration is expected
 * to back; the orchestration is the same shape the Python port uses.</p>
 */
public final class DeepAgentsSystem implements ClbenchTypes.ContinualLearningSystem {

    /** Path of the agent's durable notes, loaded into the prompt every turn. */
    public static final String AGENT_MEMORY_PATH = "/memory/AGENTS.md";

    /** Memory sources wired into the {@code MemoryMiddleware}. */
    public static final java.util.List<String> MEMORY_SOURCES = java.util.List.of(AGENT_MEMORY_PATH);

    /** Initial seed for the agent's memory (no learned content). */
    public static final String SEED_AGENTS_MD = "# Strategy notes\n\n(empty - update this as you learn)\n";

    /** System prompt injected into every deep agent this adapter builds. */
    public static final String SYSTEM_PROMPT = "You are being evaluated on a continual-learning benchmark: a sequence of"
            + " related instances in a shared environment. You are scored on how much you improve as you learn from earlier"
            + " instances.\n\n"
            + "Your durable strategy lives in " + AGENT_MEMORY_PATH + ", which is loaded into your context every turn. As"
            + " you discover what works in this environment, keep that file up to date with the edit_file/write_file tools:"
            + " record concise, generalizable lessons (tendencies to exploit, what worked, what to avoid) and prune anything"
            + " you find to be wrong. It is the only thing that carries into the next instance, so invest in it. Never store"
            + " secrets or credentials.\n\n"
            + "When you are given feedback on a previous action, use it to update your notes before you act again.";

    private final String name;
    private final String modelName;
    private final java.util.Map<Class<?>, Object> agents = new java.util.HashMap<>();
    private final java.util.List<ClbenchTypes.UsageEvent> usageEvents = new java.util.ArrayList<>();
    private java.util.Map<String, java.util.Map<String, String>> files;
    private String pendingFeedback;
    private int interactionCount;
    private AgentRuntime<Object> agentRuntime;

    static {
        ClbenchTypes.SystemRegistry.register("deepagents", DeepAgentsSystem.class);
    }

    public DeepAgentsSystem() {
        this("anthropic:claude-sonnet-4-6", "deepagents");
    }

    public DeepAgentsSystem(String model, String name) {
        this.modelName = model;
        this.name = name;
        seedMemory();
    }

    /* ----------------------------- interface ----------------------------- */

    @Override
    public boolean supportsBaseline() {
        return true;
    }

    @Override
    public boolean parallelSafe() {
        // in-memory state only; no fixed host paths or ports.
        return true;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public ClbenchTypes.Response respond(ClbenchTypes.Query query) {
        interactionCount++;
        // Surface feedback so the agent can update its own notes before acting.
        String feedback = null;
        if (query.feedback() != null && !query.feedback().content().strip().isEmpty()) {
            feedback = query.feedback().content().strip();
        } else if (pendingFeedback != null) {
            feedback = pendingFeedback;
        }
        pendingFeedback = null;
        String prompt = query.prompt() == null ? "(no content)" : query.prompt();
        if (feedback != null) {
            prompt = "Feedback on your previous action:\n" + feedback + "\n\n" + prompt;
        }
        Object agent = getAgent(query.responseSchema());
        Object result = DeepAgentFactory.invoke(agent, new DeepAgentFactory.InvokeRequest(
                prompt, java.util.Map.copyOf(files)));
        // Thread the (possibly agent-updated) memory filesystem forward.
        @SuppressWarnings("unchecked")
        java.util.Map<String, java.util.Map<String, String>> newFiles =
                (java.util.Map<String, java.util.Map<String, String>>) DeepAgentFactory.files(result, files);
        this.files = newFiles;
        recordUsageFromMessages(DeepAgentFactory.messages(result));
        Object action = DeepAgentFactory.structuredResponse(result);
        if (action == null) {
            throw new IllegalStateException(
                    "Deep agent did not return a structured response matching "
                            + query.responseSchema().getName());
        }
        // R-orch-2: when an AgentRuntime is wired in, route the raw action
        // through verify / self-correct / ensemble before returning. The
        // runtime's verifier decides whether the action is acceptable;
        // self-correct can retry with a fresh prompt; ensemble can fan
        // out to alternative generators. The trace is exposed via the
        // response metadata for audit.
        RuntimeResult<Object> runtimeResult = null;
        if (agentRuntime != null) {
            runtimeResult = agentRuntime.run(action);
            action = runtimeResult.action();
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("system", "deepagents");
        metadata.put("model", modelName);
        metadata.put("interaction", interactionCount);
        metadata.put("memory_files", memorySnapshot());
        if (runtimeResult != null) {
            metadata.put("runtime", runtimeResult.summary());
            metadata.put("runtime_outcome", runtimeResult.outcome());
            metadata.put("runtime_passed", runtimeResult.passed());
        }
        return new ClbenchTypes.Response(action, metadata);
    }

    /**
     * Wire an {@link AgentRuntime} into the response path. When set,
     * {@link #respond(ClbenchTypes.Query)} routes the raw agent output
     * through {@code V -> self-correct -> ensemble} before returning the
     * final action. Pass {@code null} to restore the bare-agent path.
     *
     * <p>The runtime is intentionally not part of the constructor: most
     * callers don't need it, and the default path stays byte-compatible
     * with the previous release (R-orch-2). Callers that want self-correction
     * or ensembling construct the runtime from R-radar-6/7/8 building
     * blocks and pass it here.</p>
     */
    public void setAgentRuntime(AgentRuntime<Object> runtime) {
        this.agentRuntime = runtime;
    }

    /** The currently wired runtime, or {@code null} when the bare-agent path is in use. */
    public AgentRuntime<Object> agentRuntime() {
        return agentRuntime;
    }

    @Override
    public void observe(ClbenchTypes.Observation observation, ClbenchTypes.Query nextQuery) {
        String content = observation == null ? "" : observation.content().strip();
        if (!content.isEmpty()) {
            pendingFeedback = content;
        }
    }

    @Override
    public void reset() {
        seedMemory();
        pendingFeedback = null;
        interactionCount = 0;
    }

    @Override
    public java.util.Map<String, Object> getRunArtifacts() {
        java.util.Map<String, Object> artifacts = new java.util.LinkedHashMap<>();
        artifacts.put("artifact_type", "deepagents");
        artifacts.put("model", modelName);
        artifacts.put("interaction_count", interactionCount);
        artifacts.put("memory_files", memorySnapshot());
        return artifacts;
    }

    @Override
    public void recordUsageEvent(ClbenchTypes.UsageEvent event) {
        usageEvents.add(event);
    }

    /** Read-only view of the recorded usage events. */
    public java.util.List<ClbenchTypes.UsageEvent> usageEvents() {
        return java.util.List.copyOf(usageEvents);
    }

    /* ----------------------------- internals ----------------------------- */

    private void seedMemory() {
        this.files = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> entry = new java.util.LinkedHashMap<>();
        entry.put("content", SEED_AGENTS_MD);
        entry.put("encoding", "utf-8");
        this.files.put(AGENT_MEMORY_PATH, entry);
    }

    private Object getAgent(Class<?> schema) {
        return agents.computeIfAbsent(schema, s -> DeepAgentFactory.create(
                new DeepAgentFactory.CreateRequest(
                        modelName,
                        SYSTEM_PROMPT,
                        MEMORY_SOURCES,
                        s)));
    }

    private java.util.Map<String, String> memorySnapshot() {
        java.util.Map<String, String> snapshot = new java.util.LinkedHashMap<>();
        for (String path : MEMORY_SOURCES) {
            snapshot.put(path, readFileData(files, path));
        }
        return snapshot;
    }

    private void recordUsageFromMessages(java.util.List<?> messages) {
        if (messages == null) {
            return;
        }
        int inputTokens = 0;
        int outputTokens = 0;
        boolean seen = false;
        for (Object msg : messages) {
            Object usage = usageMetadata(msg);
            if (usage == null) {
                continue;
            }
            seen = true;
            inputTokens += intOrZero(mapValue(usage, "input_tokens"));
            outputTokens += intOrZero(mapValue(usage, "output_tokens"));
        }
        if (!seen) {
            return;
        }
        recordUsageEvent(new ClbenchTypes.UsageEvent(
                "completion", modelName, inputTokens, outputTokens, inputTokens + outputTokens));
    }

    private static String readFileData(java.util.Map<String, java.util.Map<String, String>> files, String path) {
        java.util.Map<String, String> entry = files == null ? null : files.get(path);
        if (entry == null) {
            return "";
        }
        return entry.getOrDefault("content", "");
    }

    private static Object usageMetadata(Object message) {
        if (message instanceof java.util.Map<?, ?> m) {
            return m.get("usage_metadata");
        }
        return null;
    }

    private static Object mapValue(Object map, String key) {
        if (map instanceof java.util.Map<?, ?> m) {
            return m.get(key);
        }
        return null;
    }

    private static int intOrZero(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        return 0;
    }

    /* ----------------------------- integration seam ----------------------------- */

    /**
     * Integration seam for {@code deepagents.create_deep_agent}.
     *
     * <p>Mirrors the shape of the upstream call. The actual invocation
     * is delegated to a downstream integration; the Java port supplies
     * the orchestration so the compiled bytecode is self-contained.</p>
     */
    public static final class DeepAgentFactory {

        private DeepAgentFactory() {}

        public record CreateRequest(
                String modelName,
                String systemPrompt,
                java.util.List<String> memorySources,
                Class<?> responseSchema) {}

        public record InvokeRequest(String prompt, java.util.Map<String, java.util.Map<String, String>> files) {}

        /** Build a deep agent for a given schema. */
        public static Object create(CreateRequest request) {
            return new java.util.LinkedHashMap<>(
                    java.util.Map.of("__agent_kind__", "deepagents",
                            "__request__", request));
        }

        /** Invoke a deep agent with a single user prompt and the in-state filesystem. */
        public static Object invoke(Object agent, InvokeRequest request) {
            // The Java port returns a deterministic stand-in: the structured
            // response is a freshly constructed instance of the requested
            // schema class (or {@code null} when the schema has no public
            // constructor), and the memory filesystem is returned unchanged.
            java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("messages", java.util.List.of());
            result.put("files", request.files());
            Object schema = schemaOf(agent);
            result.put("structured_response", instantiateSchema(schema));
            return result;
        }

        /** Read the updated files map from a deep-agent result. */
        @SuppressWarnings("unchecked")
        public static Object files(Object result, Object fallback) {
            if (result instanceof java.util.Map<?, ?> m) {
                Object value = m.get("files");
                if (value != null) {
                    return value;
                }
            }
            return fallback;
        }

        /** Read the message log from a deep-agent result. */
        public static java.util.List<?> messages(Object result) {
            if (result instanceof java.util.Map<?, ?> m) {
                Object value = m.get("messages");
                if (value instanceof java.util.List<?> list) {
                    return list;
                }
            }
            return java.util.List.of();
        }

        /** Read the structured response from a deep-agent result. */
        public static Object structuredResponse(Object result) {
            if (result instanceof java.util.Map<?, ?> m) {
                return m.get("structured_response");
            }
            return null;
        }

        private static Object schemaOf(Object agent) {
            if (agent instanceof java.util.Map<?, ?> m) {
                Object request = m.get("__request__");
                if (request instanceof CreateRequest cr) {
                    return cr.responseSchema();
                }
            }
            return null;
        }

        private static Object instantiateSchema(Object schema) {
            if (!(schema instanceof Class<?> cls)) {
                return null;
            }
            try {
                return cls.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException ex) {
                return null;
            }
        }
    }
}
