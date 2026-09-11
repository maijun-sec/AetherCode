package org.aethercode.talon.runtime;

import org.aethercode.core.fs.backend.BackendProtocol;
import org.aethercode.core.fs.backend.LocalShellBackend;
import org.aethercode.core.llm.ChatClient;
import org.aethercode.core.middleware.SkillSource;
import org.aethercode.deepagents.graph.CreateDeepAgent;
import org.aethercode.deepagents.graph.DeepAgent;
import org.aethercode.deepagents.middleware.Middleware;
import org.aethercode.deepagents.selfimprove.BankServer;
import org.aethercode.deepagents.selfimprove.DecayScheduler;
import org.aethercode.deepagents.selfimprove.ReasoningBank;
import org.aethercode.deepagents.selfimprove.TalonSelfReflectWiring;
import org.aethercode.engine.springai.SpringAiChatClient;
import org.aethercode.core.runtime.AgentState;
import org.aethercode.core.runtime.Message;
import org.aethercode.talon.TalonConfig;
import org.aethercode.talon.cron.CronJobStore;
import org.aethercode.talon.cron.CronOrigin;
import org.aethercode.talon.interfaces.AgentRequest;
import org.aethercode.talon.interfaces.AgentResult;
import org.aethercode.talon.interfaces.AgentRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Deep Agents-backed runtime for Talon.
 *
 * <p>Java-native port of {@code deepagents_talon.runtime.DeepAgentRuntime}.
 * The Java port builds a {@link DeepAgent} through
 * {@link CreateDeepAgent#createDeepAgent(Object, List, String, List, List, List, List, List, BackendProtocol, Map, Object, Class, Class, String)}
 * and invokes it for each {@link AgentRequest}. The full graph-runtime
 * wiring (state channels, interrupts, retries) is best-effort: methods
 * that require langgraph execution gracefully fall back to a stub
 * response so the host loop can keep running while the Java langgraph
 * adapter lands.</p>
 */
public class DeepAgentRuntime implements AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(DeepAgentRuntime.class);

    private final String model;
    private final List<?> tools;
    private final String systemPrompt;
    private final List<Object> subagents;
    private final Path assistantDir;
    private final CronJobStore cronStore;
    private final BackendProtocol backend;
    private final List<String> skills;
    private final List<?> middleware;
    private final Map<String, Object> interruptOn;
    private final List<String> memory;
    private final boolean includeWebTools;
    private final int recursionLimit;
    private final int maxRetries;
    private final int maxContinuations;
    private final Map<String, String> env;

    private final AtomicReference<DeepAgent> graph = new AtomicReference<>();
    /** R243.2B (O-3): the ReasoningBank the deep-agent graph
     *  writes reflections into. Exposed for CLI inspection
     *  and tests; the host should not mutate it directly. */
    private final AtomicReference<ReasoningBank> bankRef = new AtomicReference<>();
    /** R244.2 (O-10): the HTTP server that exposes the bank
     *  to non-JVM surfaces (TUI / desktop / IntelliJ). */
    private final AtomicReference<BankServer> bankServerRef = new AtomicReference<>();
    /** R245.3 (O-3): background decay scheduler. Opt-in
     *  via {@code DEEPAGENTS_TALON_DECAY_INTERVAL_MIN}
     *  (default 60 min; 0 = off). */
    private final AtomicReference<DecayScheduler> decaySchedulerRef = new AtomicReference<>();
    private final ThreadLocal<CronOrigin> cronOrigin = new ThreadLocal<>();
    private final Supplier<CronOrigin> originSupplier = () -> {
        CronOrigin origin = cronOrigin.get();
        if (origin == null) {
            throw new IllegalStateException(
                    "cron tools must be called from within a Talon conversation");
        }
        return origin;
    };

    public DeepAgentRuntime(String model,
                            List<?> tools,
                            String systemPrompt,
                            List<?> subagents,
                            Path assistantDir,
                            CronJobStore cronStore,
                            Object backend,
                            List<String> skills,
                            List<?> middleware,
                            Map<String, Object> interruptOn,
                            List<String> memory,
                            Object checkpointer,
                            boolean includeWebTools,
                            int recursionLimit,
                            int maxRetries,
                            int maxContinuations,
                            Map<String, String> env) {
        this.model = model;
        this.tools = tools == null ? List.of() : List.copyOf(tools);
        this.systemPrompt = systemPrompt;
        this.subagents = subagents == null ? null : new ArrayList<>(subagents);
        this.assistantDir = assistantDir;
        this.cronStore = cronStore;
        this.backend = resolveBackend(backend, env);
        this.skills = skills == null ? null : List.copyOf(skills);
        this.middleware = middleware == null ? List.of() : List.copyOf(middleware);
        this.interruptOn = interruptOn == null ? Map.of() : Map.copyOf(interruptOn);
        this.memory = memory == null ? null : List.copyOf(memory);
        this.includeWebTools = includeWebTools;
        this.recursionLimit = recursionLimit;
        this.maxRetries = maxRetries;
        this.maxContinuations = maxContinuations;
        this.env = env == null ? Map.of() : Map.copyOf(env);
    }

    @Override
    public CompletableFuture<Void> start() {
        return CompletableFuture.runAsync(() -> {
            try {
                // R243.2B (O-3): wire the ReasoningBank +
                // reflection / recall middlewares into the
                // runtime's deep-agent graph so every
                // conversation participates in the
                // strategy-library loop. The wiring is
                // fail-safe: a null ChatClient or an
                // unusable assistantDir degrades to
                // in-memory + no-op reflector instead of
                // failing the start.
                TalonSelfReflectWiring.Result wiring = TalonSelfReflectWiring.build(
                        assistantDir, resolveChatClient(), env);
                if (wiring.bank() != null) {
                    bankRef.set(wiring.bank());
                }
                if (wiring.enabled()) {
                    log.info("deep-agent self-reflect wiring active (bankDir={})",
                            wiring.bankDir());
                }
                // R244.2 (O-10): expose the bank to non-JVM
                // surfaces via a localhost HTTP server. Opt-in
                // via the env var; default is off so the
                // release artifact is unchanged unless the
                // host flips the switch.
                String expose = env.get("DEEPAGENTS_TALON_EXPOSE_BANK");
                if (expose != null && !expose.isBlank()
                        && !expose.equalsIgnoreCase("false")
                        && !expose.equalsIgnoreCase("0")
                        && !expose.equalsIgnoreCase("no")
                        && !expose.equalsIgnoreCase("off")
                        && wiring.bank() != null) {
                    int port = parsePortOrDefault(env.get("DEEPAGENTS_TALON_BANK_PORT"),
                            BankServer.DEFAULT_PORT);
                    try {
                        BankServer server = new BankServer(wiring.bank()).start(port);
                        bankServerRef.set(server);
                        log.info("deep-agent bank exposed on http://127.0.0.1:{} "
                                + "(DEEPAGENTS_TALON_EXPOSE_BANK=true)", server.port());
                    } catch (RuntimeException re) {
                        log.warn("deep-agent bank server failed to start on port {}: {}",
                                port, re.getMessage());
                    }
                }
                // R245.3: start the background decay scheduler.
                // Opt-in via DEEPAGENTS_TALON_DECAY_INTERVAL_MIN
                // (default 60 min; 0 = off). Failures are
                // logged at warn and do not abort startup —
                // the runtime still works without a scheduler.
                try {
                    DecayScheduler scheduler = TalonSelfReflectWiring.startDecayScheduler(
                            wiring, env);
                    if (scheduler != null) {
                        decaySchedulerRef.set(scheduler);
                        log.info("deep-agent decay scheduler started (interval={} min, "
                                + "DEEPAGENTS_TALON_DECAY_INTERVAL_MIN)",
                                scheduler.interval().toMinutes());
                    }
                } catch (RuntimeException re) {
                    log.warn("deep-agent decay scheduler failed to start: {}", re.getMessage());
                }
                DeepAgent agent = CreateDeepAgent.createDeepAgent(
                        resolveModel(),
                        List.of(),
                        resolveSystemPrompt(),
                        new ArrayList<>(wiring.middlewares()),
                        (List<?>) (subagents == null ? List.of() : subagents),
                        resolveSkillSources(),
                        resolveMemory(),
                        List.of(),
                        backend,
                        interruptOn,
                        null,
                        null,
                        null,
                        "deep_agent_talon");
                graph.set(agent);
            } catch (RuntimeException e) {
                log.error("Failed to construct Deep Agent graph: {}", e.toString());
                throw e;
            }
        });
    }

    @Override
    public CompletableFuture<Void> stop() {
        BankServer server = bankServerRef.getAndSet(null);
        if (server != null) server.stop();
        // R245.3: stop the decay scheduler alongside the
        // bank server. The scheduler's stop() is idempotent
        // so it's safe even when the scheduler was never
        // started (the ref is null).
        DecayScheduler scheduler = decaySchedulerRef.getAndSet(null);
        if (scheduler != null) scheduler.stop();
        graph.set(null);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<AgentResult> invoke(AgentRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            DeepAgent agent = graph.get();
            if (agent == null) {
                throw new IllegalStateException("DeepAgentRuntime must be started before invoke");
            }
            CronOrigin origin = cronOriginFromRequest(request);
            cronOrigin.set(origin);
            try {
                String text = invokeUntilText(request);
                return new AgentResult(text == null ? "" : text);
            } finally {
                cronOrigin.remove();
            }
        });
    }

    // -----------------------------------------------------------------------
    // Invocation loop
    // -----------------------------------------------------------------------

    private String invokeUntilText(AgentRequest request) {
        Object content = requestModelContent(request);
        Object state = invokeWithRetries(content, request.conversationId());
        String text = lastText(state);
        if (text != null && !text.isEmpty()) {
            return text;
        }
        for (int attempt = 0; attempt < maxContinuations; attempt++) {
            log.warn("Agent returned no text for conversation {}; sending continuation nudge {}/{}",
                    request.conversationId(), attempt + 1, maxContinuations);
            state = invokeWithRetries(RuntimeEnv.CONTINUATION_NUDGE,
                    request.conversationId());
            text = lastText(state);
            if (text != null && !text.isEmpty()) {
                return text;
            }
        }
        state = invokeWithRetries(RuntimeEnv.FORCE_SUMMARY_PROMPT,
                request.conversationId());
        return lastText(state);
    }

    private Object invokeWithRetries(Object content, String conversationId) {
        return invokePayloadWithRetries(Map.of("messages",
                List.of(Map.of("role", "user", "content", content))), conversationId);
    }

    private Object invokePayloadWithRetries(Object payload, String conversationId) {
        DeepAgent agent = graph.get();
        if (agent == null) {
            throw new IllegalStateException("DeepAgentRuntime must be started before invoke");
        }
        Function<List<Message>, Message.AIMessage> chatModel = messages -> {
            // The Java port does not yet wire a real chat model; the runtime
            // surfaces a deterministic echo so the host loop can drive
            // turns end-to-end while the langgraph adapter lands.
            String text = (payload == null) ? "" : String.valueOf(payload);
            return new Message.AIMessage("ai-" + java.util.UUID.randomUUID(),
                    List.of(org.aethercode.core.runtime.ContentBlock.text(text)));
        };
        Throwable last = null;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                DeepAgent.DeepAgentResult result = agent.invoke(AgentState.empty(), null, chatModel);
                return result;
            } catch (CompletionException e) {
                if (!isRetryable(e.getCause() != null ? e.getCause() : e) || attempt + 1 >= maxRetries) {
                    throw e;
                }
                last = e;
                long backoff = Math.min(1L << attempt, 10L);
                log.warn("Retryable agent error in conversation {}; retrying in {}s: {}",
                        conversationId, backoff, e.toString());
                try {
                    Thread.sleep(backoff * 1000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        if (last != null) {
            throw new CompletionException(last);
        }
        throw new IllegalStateException("agent invocation retry loop exited unexpectedly");
    }

    private String lastText(Object state) {
        if (state instanceof org.aethercode.deepagents.graph.DeepAgent agent) {
            return agent == null ? "" : "";
        }
        if (!(state instanceof Map<?, ?> raw)) {
            return "";
        }
        Object messages = raw.get("messages");
        if (!(messages instanceof List<?> list) || list.isEmpty()) {
            return "";
        }
        Object last = list.get(list.size() - 1);
        Object content;
        if (last instanceof Map<?, ?> m) {
            content = m.get("content");
            if (content == null) {
                content = "";
            }
        } else {
            content = "";
        }
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof List<?> blocks) {
            StringBuilder sb = new StringBuilder();
            for (Object block : blocks) {
                if (block instanceof Map<?, ?> bm) {
                    Object t = bm.get("text");
                    if (t instanceof String ts) {
                        if (sb.length() > 0) {
                            sb.append('\n');
                        }
                        sb.append(ts);
                    }
                } else if (block instanceof String s) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(s);
                }
            }
            return sb.toString().strip();
        }
        return "";
    }

    private boolean isRetryable(Throwable t) {
        if (t == null) {
            return false;
        }
        if (t instanceof java.net.ConnectException
                || t instanceof java.net.SocketTimeoutException
                || t instanceof java.io.IOException) {
            return true;
        }
        Integer status = statusCode(t);
        if (status != null) {
            if (RuntimeEnv.RETRYABLE_STATUS_CODES.contains(status)) {
                return true;
            }
            if (status == RuntimeEnv.BAD_REQUEST_STATUS_CODE) {
                return containsMarker(t.toString(), RuntimeEnv.RETRYABLE_BAD_REQUEST_MARKERS);
            }
        }
        return containsMarker(t.toString(), RuntimeEnv.RETRYABLE_MESSAGE_MARKERS);
    }

    private static boolean containsMarker(String text, String[] markers) {
        String lower = text.toLowerCase();
        for (String m : markers) {
            if (lower.contains(m)) {
                return true;
            }
        }
        return false;
    }

    private static Integer statusCode(Throwable t) {
        for (Object source : new Object[]{t, getField(t, "response")}) {
            if (source == null) {
                continue;
            }
            for (String attr : new String[]{"status_code", "status"}) {
                Object v = getField(source, attr);
                if (v instanceof Integer i) {
                    return i;
                }
            }
        }
        return null;
    }

    private static Object getField(Object source, String name) {
        try {
            var f = source.getClass().getField(name);
            return f.get(source);
        } catch (ReflectiveOperationException ignored) {
            try {
                var m = source.getClass().getMethod(name);
                return m.invoke(source);
            } catch (ReflectiveOperationException ignored2) {
                return null;
            }
        }
    }

    // -----------------------------------------------------------------------
    // Subagent / skill / memory resolution
    // -----------------------------------------------------------------------

    private String resolveSystemPrompt() {
        if (systemPrompt != null) {
            return systemPrompt;
        }
        if (assistantDir == null) {
            return null;
        }
        Path path = assistantDir.resolve("AGENTS.md");
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            return Files.readString(path);
        } catch (IOException e) {
            log.warn("Could not read Talon system prompt from {}", path, e);
            return null;
        }
    }

    private List<SkillSource> resolveSkillSources() {
        if (skills != null && !skills.isEmpty()) {
            List<SkillSource> out = new ArrayList<>();
            for (String s : skills) {
                out.add(SkillSource.of(s));
            }
            return out;
        }
        List<SkillSource> sources = new ArrayList<>();
        if (assistantDir != null) {
            Path skillsDir = assistantDir.resolve("skills");
            try {
                Files.createDirectories(skillsDir);
            } catch (IOException ignored) {
                // best-effort
            }
            sources.add(SkillSource.of(skillsDir.toString()));
        }
        String raw = env.get("DEEPAGENTS_TALON_SKILLS_DIRS");
        if (raw == null || raw.isBlank()) {
            raw = env.get("SKILLS_DIRS");
        }
        if (raw != null && !raw.isBlank()) {
            for (String part : raw.split(java.io.File.pathSeparator)) {
                if (part.isBlank()) {
                    continue;
                }
                sources.add(SkillSource.of(Path.of(part).toAbsolutePath().toString()));
            }
        }
        return sources.isEmpty() ? null : sources;
    }

    private List<String> resolveMemory() {
        if (memory != null) {
            return memory.isEmpty() ? null : List.copyOf(memory);
        }
        List<String> paths = new ArrayList<>();
        String raw = env.get("DEEPAGENTS_TALON_MEMORY_PATHS");
        if (raw == null || raw.isBlank()) {
            raw = env.get("AGENT_MEMORY_PATHS");
        }
        if (raw != null && !raw.isBlank()) {
            for (String part : raw.split(java.io.File.pathSeparator)) {
                if (part.isBlank()) {
                    continue;
                }
                Path p = Path.of(part).toAbsolutePath();
                try {
                    Files.createDirectories(p.getParent());
                    if (!Files.exists(p)) {
                        Files.createFile(p);
                    }
                    paths.add(p.toString());
                } catch (IOException e) {
                    log.warn("Could not prepare memory file {}", p, e);
                }
            }
        }
        if (paths.isEmpty() && assistantDir != null) {
            Path memoryFile = assistantDir.resolve("memory").resolve("AGENTS.md");
            try {
                Files.createDirectories(memoryFile.getParent());
                if (!Files.exists(memoryFile)) {
                    Files.createFile(memoryFile);
                }
                paths.add(memoryFile.toString());
            } catch (IOException e) {
                log.warn("Could not prepare memory file {}", memoryFile, e);
            }
        }
        return paths.isEmpty() ? null : paths;
    }

    private Object resolveModel() {
        if (model == null) {
            return "openai:gpt-4o-mini";
        }
        String baseUrl = env.get("OPENAI_BASE_URL");
        if (baseUrl != null && !baseUrl.isBlank() && isOpenAiModel(model)) {
            // deepagents-core applies OPENAI_BASE_URL inside
            // createDeepAgent for "openai:*" model specs.
            return model;
        }
        return model;
    }

    private static boolean isOpenAiModel(String model) {
        return model != null && model.startsWith("openai:");
    }

    /**
     * R243.2B (O-3): construct a {@link ChatClient} the
     * self-reflect wiring can use as the reflection LLM.
     * Returns {@code null} if the env does not configure a
     * usable API key — {@code TalonSelfReflectWiring} handles
     * the null by falling back to a {@code StubReflector}
     * (no-op), so the runtime still starts.
     *
     * <p>We always wrap with {@code SpringAiChatClient}
     * because the project standardises on it (default base
     * URL {@code https://api.minimaxi.com/v1}, default model
     * {@code MiniMax-M3}, OPENAI_BASE_URL override). The
     * model spec is whatever {@link #resolveModel()} returns
     * — usually a string like {@code "openai:gpt-4o-mini"} —
     * but for the reflection path the model is irrelevant:
     * the reflection prompt is small, and MiniMax-M3
     * quality is enough. We therefore pass the bare model
     * spec (without the {@code "openai:"} prefix) so the
     * default MiniMax-M3 picks up if the spec is empty.
     */
    private ChatClient resolveChatClient() {
        try {
            String modelSpec = resolveModel() == null ? null : resolveModel().toString();
            String model = modelSpec == null ? "MiniMax-M3"
                    : (modelSpec.startsWith("openai:")
                            ? modelSpec.substring("openai:".length())
                            : modelSpec);
            return new SpringAiChatClient(model, null);
        } catch (RuntimeException e) {
            log.warn("deep-agent self-reflect: failed to build SpringAiChatClient "
                    + "(no API key?): {}", e.getMessage());
            return null;
        }
    }

    private BackendProtocol resolveBackend(Object backend, Map<String, String> env) {
        if (backend instanceof BackendProtocol bp) {
            return bp;
        }
        String root = env.get(RuntimeEnv.WORKSPACE_ENV);
        return new LocalShellBackend((root == null || root.isBlank()) ? null : root,
                false, 120, 100_000, RuntimeEnv.backendChildEnv(env), false);
    }

    private CronOrigin cronOriginFromRequest(AgentRequest request) {
        Object channel = request.metadata().get("channel");
        Object messageId = request.metadata().get("message_id");
        Object origin = request.metadata().get("origin_conversation_id");
        String originConversationId = (origin instanceof String s && !s.isEmpty())
                ? s : request.conversationId();
        return new CronOrigin(
                originConversationId,
                channel instanceof String cs ? cs : null,
                messageId instanceof String ms ? ms : null);
    }

    private Object requestModelContent(AgentRequest request) {
        Object content = request.metadata().get("model_content");
        if (isModelContent(content)) {
            return content;
        }
        return request.text();
    }

    private static boolean isModelContent(Object value) {
        if (!(value instanceof List<?> list)) {
            return false;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?>)) {
                return false;
            }
        }
        return true;
    }

    // -----------------------------------------------------------------------
    // Public configuration surface
    // -----------------------------------------------------------------------

    public String model() {
        return model;
    }

    public List<?> tools() {
        return tools;
    }

    public Optional<DeepAgent> agent() {
        return Optional.ofNullable(graph.get());
    }

    /**
     * R243.2B (O-3): the {@link ReasoningBank} the deep-agent
     * graph writes reflections into. Available after
     * {@link #start()} has run; empty before then. Hosts
     * should treat the returned bank as read-only — direct
     * mutation bypasses the middlewares' deduplication and
     * growth policy.
     */
    public Optional<ReasoningBank> bank() {
        ReasoningBank b = bankRef.get();
        return Optional.ofNullable(b);
    }

    /**
     * R244.2 (O-10): the HTTP bank server, when one was
     * started (i.e. the host passed
     * {@code DEEPAGENTS_TALON_EXPOSE_BANK=true}). Empty
     * when the opt-in is off. The returned server can be
     * queried for its bound {@link BankServer#port()}.
     */
    public Optional<BankServer> bankServer() {
        return Optional.ofNullable(bankServerRef.get());
    }

    private static int parsePortOrDefault(String raw, int dflt) {
        if (raw == null || raw.isBlank()) return dflt;
        try {
            int p = Integer.parseInt(raw.trim());
            return (p > 0 && p < 65536) ? p : dflt;
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    public CronJobStore cronStore() {
        return cronStore;
    }

    public TalonConfig configSnapshot() {
        // Cheap snapshot accessor; the real TalonConfig is held by the host.
        return null;
    }
}
