package org.aethercode.partner.quickjs;

import org.aethercode.middleware.Middleware;
import org.aethercode.partner.quickjs.format.Format;
import org.aethercode.partner.quickjs.js.JsExecutors;
import org.aethercode.partner.quickjs.js.JsExecutor;
import org.aethercode.partner.quickjs.prompt.ReplPrompt;
import org.aethercode.partner.quickjs.ptc.PtcSupport;
import org.aethercode.partner.quickjs.repl.EvalOutcome;
import org.aethercode.partner.quickjs.repl.Registry;
import org.aethercode.partner.quickjs.repl.Repl;
import org.aethercode.partner.quickjs.snapshot.SnapshotCodec;
import org.aethercode.partner.quickjs.subagent.SubagentBridge;
import org.aethercode.core.runtime.AgentState;
import org.aethercode.core.runtime.ContentBlock;
import org.aethercode.core.runtime.Message;
import org.aethercode.tools.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * Middleware exposing a JavaScript REPL to the agent.
 *
 * <p>1:1 port of the Python
 * <code>langchain_quickjs.middleware.CodeInterpreterMiddleware</code>.
 * Each LangGraph thread gets its own QuickJS slot (worker + runtime
 * + context), so globals from one conversation cannot leak into
 * another.</p>
 *
 * <p>Until a real {@link JsExecutor} binding is registered, the
 * REPL is wired but every {@code eval} invocation throws
 * {@link UnsupportedOperationException}. The non-execution paths
 * (prompt rendering, snapshot encoding, PTC filtering, subagent
 * event shaping) work without a binding so the rest of the partner
 * package can be unit tested.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * CodeInterpreterMiddleware mw = CodeInterpreterMiddleware.builder()
 *         .memoryLimit(64 * 1024 * 1024)
 *         .timeout(5.0)
 *         .maxPtcCalls(256)
 *         .build();
 * // Attach `mw` to your agent's middleware chain.
 * }</pre>
 */
public final class CodeInterpreterMiddleware implements Middleware {

    private static final Logger LOGGER = LoggerFactory.getLogger(CodeInterpreterMiddleware.class);

    /** Default memory limit: 64 MiB. */
    public static final int DEFAULT_MEMORY_LIMIT = 64 * 1024 * 1024;

    /** Default per-call timeout: 5 seconds. */
    public static final double DEFAULT_TIMEOUT = 5.0;

    /** Default PTC call budget per eval. */
    public static final Integer DEFAULT_MAX_PTC_CALLS = 256;

    /** Default result-character truncation. */
    public static final int DEFAULT_MAX_RESULT_CHARS = 4_000;

    /** Default tool name. */
    public static final String DEFAULT_TOOL_NAME = "eval";

    /** State key for the persisted REPL snapshot bytes. */
    public static final String STATE_SNAPSHOT_PAYLOAD = "_quickjs_snapshot_payload";

    /** State key for the persisted REPL snapshot HMAC. */
    public static final String STATE_SNAPSHOT_HMAC = "_quickjs_snapshot_hmac";

    // -----------------------------------------------------------------
    //  Construction state
    // -----------------------------------------------------------------

    private final int memoryLimit;
    private final double timeout;
    private final int maxPtcCalls;
    private final String toolName;
    private final int maxResultChars;
    private final boolean captureConsole;
    private final boolean subagentsEnabled;
    private final List<PtcSupport.PtcEntry> ptc;
    private final PersistenceMode mode;
    private final int maxSnapshotBytes;
    private final byte[] snapshotSigningKey;

    private final Registry registry;
    private final int memoryLimitMb;
    private final SnapshotCodec snapshotCodec;

    // Memoized per-instance state.
    private final Map<Boolean, String> basePromptCache = new ConcurrentHashMap<>();
    private volatile PtcPromptCache ptcPromptCache;
    private final Map<String, List<ReplPrompt.ToolLike>> ptcToolsByThread = new ConcurrentHashMap<>();
    private final String fallbackThreadId = "session_" + UUID.randomUUID().toString().substring(0, 8);

    private final List<Tool> tools;

    public CodeInterpreterMiddleware(Builder b) {
        this.memoryLimit = b.memoryLimit;
        this.timeout = b.timeout;
        this.maxPtcCalls = b.maxPtcCalls;
        this.toolName = b.toolName;
        this.maxResultChars = b.maxResultChars;
        this.captureConsole = b.captureConsole;
        this.subagentsEnabled = b.subagents;
        this.ptc = b.ptc == null ? List.of() : List.copyOf(b.ptc);
        this.mode = b.mode;
        this.maxSnapshotBytes = b.maxSnapshotBytes == null ? memoryLimit : b.maxSnapshotBytes;
        this.snapshotSigningKey = b.snapshotSigningKey == null
                ? null
                : SnapshotCodec.normalizeSigningKey(b.snapshotSigningKey);
        this.memoryLimitMb = memoryLimit / (1024 * 1024);
        this.snapshotCodec = new SnapshotCodec();
        JsExecutor executor = resolveExecutor();
        this.registry = new Registry(
                executor,
                memoryLimit,
                timeout,
                captureConsole,
                maxResultChars,
                maxPtcCalls,
                subagentsEnabled,
                Set.of());
        this.tools = List.of(buildTool());
    }

    private static JsExecutor resolveExecutor() {
        String override = System.getProperty("org.aethercode.partner.quickjs.jsExecutor");
        if (override == null || override.isBlank()) {
            return JsExecutors.unsupported();
        }
        try {
            Class<?> cls = Class.forName(override);
            return (JsExecutor) cls.getMethod("instance").invoke(null);
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("Failed to load JsExecutor override {}; falling back to unsupported()", override, e);
            return JsExecutors.unsupported();
        }
    }

    // -----------------------------------------------------------------
    //  Middleware contract
    // -----------------------------------------------------------------

    @Override
    public String name() {
        return "CodeInterpreterMiddleware";
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public Message.AIMessage wrapModelCall(BiFunction<List<Message>, Middleware.Runtime, Message.AIMessage> modelCall,
                                           List<Message> messages,
                                           AgentState state,
                                           Middleware.Runtime runtime) {
        String prompt = prepareForCall(state, runtime);
        List<Message> augmented = extendSystem(messages, prompt);
        return modelCall.apply(augmented, runtime);
    }

    @Override
    public CompletableFuture<Message.AIMessage> awrapModelCall(
            BiFunction<List<Message>, Middleware.Runtime, CompletableFuture<Message.AIMessage>> modelCall,
            List<Message> messages,
            AgentState state,
            Middleware.Runtime runtime) {
        String prompt = prepareForCall(state, runtime);
        List<Message> augmented = extendSystem(messages, prompt);
        return modelCall.apply(augmented, runtime);
    }

    // -----------------------------------------------------------------
    //  Snapshot API (call from agent lifecycle)
    // -----------------------------------------------------------------

    /**
     * Restore a previously-persisted snapshot. Call from the
     * <em>before</em> agent hook. No-op when {@link #mode} is not
     * {@link PersistenceMode#THREAD} or no payload is present.
     */
    public AgentState beforeAgent(AgentState state) {
        if (mode != PersistenceMode.THREAD) return state;
        Object payload = readState(state, STATE_SNAPSHOT_PAYLOAD);
        if (!(payload instanceof byte[] bytes) || bytes.length == 0) return state;
        if (!snapshotAuthenticated(bytes, state)) {
            return withClearedSnapshot(state);
        }
        String threadId = resolveThreadId();
        Repl repl = registry.getIfExists(threadId);
        if (repl == null) {
            // The slot has not been created yet — create one and restore.
            try {
                Repl fresh = registry.get(threadId);
                fresh.restoreSnapshot(bytes);
            } catch (RuntimeException e) {
                LOGGER.warn("Failed to restore QuickJS snapshot for thread_id={}", threadId, e);
                return withClearedSnapshot(state);
            }
        } else {
            try {
                repl.restoreSnapshot(bytes);
            } catch (RuntimeException e) {
                LOGGER.warn("Failed to restore QuickJS snapshot for thread_id={}", threadId, e);
                return withClearedSnapshot(state);
            }
        }
        return state;
    }

    /**
     * Snapshot the current REPL state and evict the slot. Call from
     * the <em>after</em> agent hook.
     */
    public AgentState afterAgent(AgentState state) {
        String threadId = resolveThreadId();
        ptcToolsByThread.remove(threadId);
        if (mode != PersistenceMode.THREAD) {
            registry.evict(threadId);
            return state;
        }
        Repl repl = registry.getIfExists(threadId);
        if (repl == null) return state;
        byte[] prior = readSnapshotBytes(state, STATE_SNAPSHOT_PAYLOAD);
        Map<String, Object> update;
        try {
            byte[] payload = repl.createSnapshot();
            update = snapshotUpdate(payload, prior, threadId);
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to create QuickJS snapshot for thread_id={}", threadId, e);
            update = clearedSnapshot();
        } finally {
            registry.evict(threadId);
        }
        return state.withExtensions(mergeExtensions(state.extensions(), update));
    }

    private AgentState withClearedSnapshot(AgentState state) {
        Map<String, Object> ext = new LinkedHashMap<>(state.extensions());
        ext.put(STATE_SNAPSHOT_PAYLOAD, null);
        ext.put(STATE_SNAPSHOT_HMAC, null);
        return state.withExtensions(ext);
    }

    private Map<String, Object> clearedSnapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(STATE_SNAPSHOT_PAYLOAD, null);
        out.put(STATE_SNAPSHOT_HMAC, null);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object readState(AgentState state, String key) {
        return state.extensions().get(key);
    }

    private static byte[] readSnapshotBytes(AgentState state, String key) {
        Object v = readState(state, key);
        return v instanceof byte[] b ? b : new byte[0];
    }

    private static Map<String, Object> mergeExtensions(Map<String, Object> base, Map<String, Object> patch) {
        Map<String, Object> out = new LinkedHashMap<>(base);
        out.putAll(patch);
        return out;
    }

    // -----------------------------------------------------------------
    //  Tool build
    // -----------------------------------------------------------------

    private Tool buildTool() {
        String name = toolName;
        int maxChars = maxResultChars;
        String fallback = fallbackThreadId;
        ReplPrompt.Mode promptMode = ReplPrompt.Mode.fromWire(mode.wireName());
        String toolDescription = ReplPrompt.renderEvalToolDescription(promptMode);
        String codeDoc = ReplPrompt.renderEvalToolCodeDoc(promptMode);

        return Tool.of(name, toolDescription, (args, ctx) -> {
            Object code = args.get("code");
            if (!(code instanceof String codeStr)) {
                throw new IllegalArgumentException("`code` must be a string");
            }
            String threadId = resolveThreadId();
            Repl repl = replForEval(threadId);
            try {
                EvalOutcome outcome = repl.evalSync(codeStr, ctx);
                return new ToolMessageWithEvalOutcome(
                        UUID.randomUUID().toString(),
                        name,
                        Format.formatOutcome(outcome, maxChars),
                        outcome);
            } finally {
                if (mode == PersistenceMode.CALL) {
                    registry.resetRepl(threadId);
                }
            }
        });
    }

    // -----------------------------------------------------------------
    //  PTC plumbing
    // -----------------------------------------------------------------

    private Repl replForEval(String threadId) {
        Repl repl = registry.get(threadId);
        if (mode == PersistenceMode.CALL && !ptc.isEmpty()) {
            List<ReplPrompt.ToolLike> exposed = ptcToolsByThread.getOrDefault(threadId, List.of());
            if (!exposed.isEmpty()) {
                repl.installTools(exposed);
            }
        }
        return repl;
    }

    private String prepareForCall(AgentState state, Middleware.Runtime runtime) {
        List<ReplPrompt.ToolLike> requestTools = readRequestTools(runtime);
        String subagentSection = "";
        if (subagentsEnabled && findSubagentTaskTool(requestTools) != null) {
            subagentSection = ReplPrompt.renderSubagentSystemPrompt(toolName);
        }
        if (ptc.isEmpty()) {
            return basePrompt(false) + subagentSection;
        }
        List<ReplPrompt.ToolLike> exposed = PtcSupport.filterToolsForPtc(
                requestTools, ptc, toolName);
        String prompt = basePrompt(!exposed.isEmpty()) + subagentSection;
        String threadId = resolveThreadId();
        Repl repl = registry.get(threadId);
        repl.installTools(exposed);
        ptcToolsByThread.put(threadId, List.copyOf(exposed));
        Set<String> exposedNames = new HashSet<>();
        for (ReplPrompt.ToolLike t : exposed) exposedNames.add(t.name());
        PtcPromptCache cached = ptcPromptCache;
        if (cached == null || !cached.names().equals(exposedNames)) {
            String rendered = ReplPrompt.renderPtcPrompt(exposed, toolName);
            cached = new PtcPromptCache(exposedNames, rendered);
            ptcPromptCache = cached;
        }
        return prompt + cached.rendered();
    }

    private String basePrompt(boolean ptcAttached) {
        String cached = basePromptCache.get(ptcAttached);
        if (cached != null) return cached;
        synchronized (basePromptCache) {
            cached = basePromptCache.get(ptcAttached);
            if (cached != null) return cached;
            cached = ReplPrompt.renderReplSystemPrompt(
                    toolName,
                    timeout,
                    memoryLimitMb,
                    ReplPrompt.Mode.fromWire(mode.wireName()),
                    ptcAttached);
            basePromptCache.put(ptcAttached, cached);
            return cached;
        }
    }

    private List<ReplPrompt.ToolLike> readRequestTools(Middleware.Runtime runtime) {
        if (runtime == null) return List.of();
        try {
            Method m = runtime.getClass().getMethod("tools");
            Object v = m.invoke(runtime);
            if (v instanceof List<?> l) {
                List<ReplPrompt.ToolLike> out = new ArrayList<>();
                for (Object o : l) {
                    if (o instanceof ReplPrompt.ToolLike tl) out.add(tl);
                }
                return out;
            }
        } catch (ReflectiveOperationException ignored) {
            // fall through
        }
        return List.of();
    }

    private static SubagentBridge.TaskTool findSubagentTaskTool(List<ReplPrompt.ToolLike> tools) {
        for (ReplPrompt.ToolLike tool : tools) {
            if (SubagentBridge.taskToolName().equals(tool.name())) {
                // Bridge requires SubagentBridge.TaskTool; we just
                // return a marker indicating presence.
                return new MarkerTaskTool(tool);
            }
        }
        return null;
    }

    // -----------------------------------------------------------------
    //  Snapshot signing / update
    // -----------------------------------------------------------------

    private boolean snapshotAuthenticated(byte[] payload, AgentState state) {
        if (snapshotSigningKey == null) return true;
        String threadId = resolveThreadId();
        Object tag = state.extensions().get(STATE_SNAPSHOT_HMAC);
        byte[] tagBytes = tag instanceof byte[] b ? b : null;
        if (SnapshotCodec.verifySnapshot(snapshotSigningKey, payload, threadId, tagBytes)) {
            return true;
        }
        LOGGER.warn("Rejecting QuickJS snapshot for thread_id={}: HMAC verification failed "
                + "(missing or tampered signature). Snapshot will not be restored.", threadId);
        return false;
    }

    private Map<String, Object> snapshotUpdate(byte[] payload, byte[] prior, String threadId) {
        if (payload.length > maxSnapshotBytes) {
            LOGGER.warn("Dropping QuickJS snapshot for thread_id={} (size={} bytes exceeds max_snapshot_bytes={})",
                    threadId, payload.length, maxSnapshotBytes);
            return clearedSnapshot();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        // Sign the full payload *before* the patch chain folds it
        // into a delta. Restore recomputes the tag over the bytes
        // the chain replays back to, so the signature authenticates
        // the reconstructed snapshot.
        out.put(STATE_SNAPSHOT_PAYLOAD, snapshotCodec.encodeSnapshot(payload, prior).blob());
        if (snapshotSigningKey != null) {
            out.put(STATE_SNAPSHOT_HMAC, SnapshotCodec.signSnapshot(snapshotSigningKey, payload, threadId));
        }
        return out;
    }

    // -----------------------------------------------------------------
    //  Misc helpers
    // -----------------------------------------------------------------

    private String resolveThreadId() {
        // The Java runtime does not expose a langgraph-style
        // configurable; for a 1:1 port we use the per-instance
        // fallback id so wrapModelCall and the tool handler share
        // the same REPL slot.
        return fallbackThreadId;
    }

    private static List<Message> extendSystem(List<Message> messages, String prompt) {
        Message.SystemMessage baseSystem = null;
        for (Message m : messages) {
            if (m instanceof Message.SystemMessage sm) {
                baseSystem = sm;
                break;
            }
        }
        Message.SystemMessage newSystem;
        if (baseSystem == null) {
            newSystem = new Message.SystemMessage(
                    UUID.randomUUID().toString(),
                    List.of(ContentBlock.text(prompt)));
        } else {
            newSystem = appendToSystemMessage(baseSystem, prompt);
        }
        List<Message> out = new ArrayList<>(messages.size());
        boolean replaced = false;
        for (Message m : messages) {
            if (m instanceof Message.SystemMessage && !replaced) {
                out.add(newSystem);
                replaced = true;
            } else {
                out.add(m);
            }
        }
        if (!replaced) out.add(0, newSystem);
        return java.util.Collections.unmodifiableList(out);
    }

    /**
     * Append {@code text} to {@code systemMessage} and return a new
     * system message. Local copy of the
     * {@code deepagents.middleware._utils.append_to_system_message}
     * helper, tuned to the {@code org.aethercode.core.runtime.Message}
     * hierarchy.
     */
    private static Message.SystemMessage appendToSystemMessage(Message.SystemMessage systemMessage, String text) {
        java.util.List<ContentBlock> blocks = new java.util.ArrayList<>(
                systemMessage == null ? java.util.List.of() : systemMessage.content());
        if (!blocks.isEmpty()) {
            text = "\n\n" + text;
        }
        blocks.add(ContentBlock.text(text));
        return new Message.SystemMessage(java.util.UUID.randomUUID().toString(), blocks);
    }

    /**
     * Carrier that wraps a tool result so the formatting helper can
     * attach the {@link EvalOutcome} to the {@link Message.ToolMessage}
     * for downstream consumers.
     */
    public record ToolMessageWithEvalOutcome(
            String id,
            String toolName,
            String content,
            EvalOutcome outcome
    ) {
        public Message.ToolMessage toToolMessage(String toolCallId) {
            return new Message.ToolMessage(
                    id,
                    toolCallId,
                    List.of(ContentBlock.text(content)),
                    java.util.Optional.ofNullable(toolName),
                    java.util.Optional.empty(),
                    java.util.Optional.empty(),
                    Map.of(),
                    Map.of());
        }
    }

    private record PtcPromptCache(Set<String> names, String rendered) {}

    /** Marker task tool for {@link SubagentBridge#findSubagentTaskTool}. */
    private record MarkerTaskTool(ReplPrompt.ToolLike tool) implements SubagentBridge.TaskTool {
        @Override
        public String name() {
            return tool.name();
        }

        @Override
        public Set<String> inputFieldNames() {
            return SubagentBridge.SUBAGENT_TASK_TOOL_FIELDS;
        }

        @Override
        public CompletableFuture<Object> arun(Map<String, Object> input, Object runtime,
                                               Map<String, Object> config, String toolCallId) {
            // The Java port's CodeInterpreterMiddleware does not
            // own the dispatch path for subagent task invocations
            // — the runtime wires those through the actual
            // middleware. Return a stub.
            return CompletableFuture.completedFuture(Map.of());
        }
    }

    // -----------------------------------------------------------------
    //  Builder
    // -----------------------------------------------------------------

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int memoryLimit = DEFAULT_MEMORY_LIMIT;
        private double timeout = DEFAULT_TIMEOUT;
        private int maxPtcCalls = DEFAULT_MAX_PTC_CALLS;
        private String toolName = DEFAULT_TOOL_NAME;
        private int maxResultChars = DEFAULT_MAX_RESULT_CHARS;
        private boolean captureConsole = true;
        private boolean subagents = true;
        private List<PtcSupport.PtcEntry> ptc;
        private PersistenceMode mode;
        private Integer maxSnapshotBytes;
        private byte[] snapshotSigningKey;

        public Builder memoryLimit(int bytes) { this.memoryLimit = bytes; return this; }
        public Builder timeout(double seconds) { this.timeout = seconds; return this; }
        public Builder maxPtcCalls(int n) { this.maxPtcCalls = n; return this; }
        public Builder toolName(String name) { this.toolName = name; return this; }
        public Builder maxResultChars(int n) { this.maxResultChars = n; return this; }
        public Builder captureConsole(boolean b) { this.captureConsole = b; return this; }
        public Builder subagents(boolean b) { this.subagents = b; return this; }
        public Builder ptc(List<PtcSupport.PtcEntry> entries) { this.ptc = entries; return this; }
        public Builder mode(PersistenceMode m) { this.mode = m; return this; }
        public Builder maxSnapshotBytes(Integer n) { this.maxSnapshotBytes = n; return this; }
        public Builder snapshotSigningKey(byte[] key) { this.snapshotSigningKey = key; return this; }
        public Builder snapshotSigningKey(String key) { this.snapshotSigningKey = key == null ? null : key.getBytes(); return this; }

        public CodeInterpreterMiddleware build() {
            if (maxPtcCalls < 1) {
                throw new IllegalArgumentException("`maxPtc_calls` must be >= 1 or None");
            }
            if (maxSnapshotBytes != null && maxSnapshotBytes < 1) {
                throw new IllegalArgumentException("`max_snapshot_bytes` must be >= 1 or None");
            }
            return new CodeInterpreterMiddleware(this);
        }
    }
}
