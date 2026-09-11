package org.aethercode.partner.quickjs.repl;

import org.aethercode.partner.quickjs.format.Format;
import org.aethercode.partner.quickjs.js.JsConcurrentEvalException;
import org.aethercode.partner.quickjs.js.JsContext;
import org.aethercode.partner.quickjs.js.JsDeadlockException;
import org.aethercode.partner.quickjs.js.JsError;
import org.aethercode.partner.quickjs.js.JsExecutor;
import org.aethercode.partner.quickjs.js.JsHandle;
import org.aethercode.partner.quickjs.js.JsHostCancellationException;
import org.aethercode.partner.quickjs.js.JsMarshalException;
import org.aethercode.partner.quickjs.js.JsMemoryLimitException;
import org.aethercode.partner.quickjs.js.JsRuntime;
import org.aethercode.partner.quickjs.js.JsSnapshot;
import org.aethercode.partner.quickjs.js.JsSourceTransform;
import org.aethercode.partner.quickjs.js.JsTimeoutException;
import org.aethercode.partner.quickjs.js.JsUndefined;
import org.aethercode.partner.quickjs.js.JsWorker;
import org.aethercode.partner.quickjs.prompt.ReplPrompt;
import org.aethercode.partner.quickjs.ptc.PtcSupport;
import org.aethercode.partner.quickjs.repl.Exceptions.PtcCallBudgetExceededException;
import org.aethercode.partner.quickjs.repl.Exceptions.TaskBridgeException;
import org.aethercode.partner.quickjs.subagent.SubagentBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * One QuickJS context + console buffer, per LangGraph thread.
 *
 * <p>1:1 port of the Python {@code _ThreadREPL} class in
 * <code>_repl.py</code>. All {@link JsContext} operations are
 * marshalled onto the {@link JsWorker}'s dedicated thread because
 * the QuickJS binding is not safe to use from arbitrary threads.
 * The public methods are safe to call from any thread / event loop.</p>
 *
 * <p>Until a real {@link JsExecutor} binding is registered, calling
 * {@link #evalSync(String)} or {@link #evalAsync(String)} will
 * throw {@link UnsupportedOperationException}.</p>
 */
public final class Repl {

    private static final Logger LOGGER = LoggerFactory.getLogger(Repl.class);

    /** Hard cap on concurrent subagent {@code task()} calls per REPL. */
    public static final int MAX_TASK_CALLS_PER_THREAD = 32;

    /** The JS global name backing the top-level {@code task()} host function. */
    public static final String TASK_FUNCTION_NAME = "task";

    private final JsWorker worker;
    private final JsRuntime runtime;
    private final double perCallTimeout;
    private final boolean captureConsole;
    private final int maxPtcCalls;
    private final boolean subagentsEnabled;

    private final ConsoleBuffer console;
    private JsContext ctx;

    // PTC state. `_registeredTools` tracks which camel-case names
    // have already had their host-function bridge installed on the
    // QuickJS context. Host functions cannot be un-registered, so we
    // never remove entries from here — changes to the exposed set
    // are reflected by rewriting `globalThis.tools` (see
    // installTools) to include only the currently-active subset.
    private final Map<String, ReplPrompt.ToolLike> registeredTools = new HashMap<>();
    private final Map<String, String> bridgeSymbols = new HashMap<>();
    private Set<String> activeToolNames = Collections.emptySet();
    // Tracks whether `globalThis.tools` has been assigned at least once.
    // Distinct from `activeToolNames` so the first call with an empty
    // tool set still installs `tools = {}` (otherwise `typeof tools.X`
    // throws ReferenceError instead of returning "undefined").
    private boolean toolsInstalled = false;
    // Mutable per-eval PTC state. Allocated at eval start and
    // cleared in finally so bridge calls cannot run outside the
    // current eval.
    private final AtomicReference<PtcState> ptcState = new AtomicReference<>();
    private Semaphore taskCalls;

    public Repl(JsWorker worker,
                JsRuntime runtime,
                double timeout,
                boolean captureConsole,
                int maxStdoutChars,
                Integer maxPtcCalls,
                boolean subagentsEnabled) {
        this.worker = worker;
        this.runtime = runtime;
        this.perCallTimeout = timeout;
        this.captureConsole = captureConsole;
        this.maxPtcCalls = maxPtcCalls == null ? -1 : maxPtcCalls;
        this.subagentsEnabled = subagentsEnabled;
        this.console = new ConsoleBuffer(maxStdoutChars);
        // Context creation + console install must happen on the worker
        // thread. Block caller here so the REPL is ready to use when
        // construction returns.
        worker.runSync(this::ainit);
    }

    // -----------------------------------------------------------------
    //  Init / close
    // -----------------------------------------------------------------

    private Void ainit() throws Exception {
        this.ctx = runtime.newContext(perCallTimeout);
        if (captureConsole) installConsole();
        if (subagentsEnabled) {
            taskCalls = new Semaphore(MAX_TASK_CALLS_PER_THREAD);
            registerTaskBridge();
        }
        return null;
    }

    /** Return the live QuickJS context or throw if this REPL is closed. */
    public JsContext requireCtx() {
        if (ctx == null) {
            throw new IllegalStateException("QuickJS context is closed");
        }
        return ctx;
    }

    private void installConsole() {
        JsContext c = requireCtx();
        ConsoleBuffer buf = console;
        c.register("__console_log", args -> {
            buf.append("log", args);
            return JsUndefined.INSTANCE;
        }, false);
        c.register("__console_warn", args -> {
            buf.append("warn", args);
            return JsUndefined.INSTANCE;
        }, false);
        c.register("__console_error", args -> {
            buf.append("error", args);
            return JsUndefined.INSTANCE;
        }, false);
        c.eval("globalThis.console = {"
                + " log: __console_log,"
                + " warn: __console_warn,"
                + " error: __console_error,"
                + "}; undefined");
    }

    // -----------------------------------------------------------------
    //  Tool installation (PTC)
    // -----------------------------------------------------------------

    /**
     * Expose {@code tools} as <code>globalThis.tools.<camelCase></code>
     * in the REPL. Idempotent per (camelName, tool identity). Safe to
     * call on every model-call turn: we diff against the current
     * active set and only (a) register new host-function bridges for
     * tools we have not seen before and (b) rewrite
     * <code>globalThis.tools</code> when the active-name set changes.
     * Hot path cost when nothing changes: one set-equality check.
     */
    public void installTools(List<? extends ReplPrompt.ToolLike> tools) {
        worker.runSync(() -> {
            ainstallTools(tools);
            return null;
        });
    }

    private Void ainstallTools(List<? extends ReplPrompt.ToolLike> tools) throws Exception {
        JsContext c = requireCtx();
        Map<String, ReplPrompt.ToolLike> nameToTool = new LinkedHashMap<>();
        for (ReplPrompt.ToolLike tool : tools) {
            String camel = PtcSupport.toCamelCase(tool.name());
            if (!PtcSupport.isValidJsIdentifier(camel)) {
                LOGGER.warn("Skipping PTC tool {}: {} is not a valid JS identifier",
                        tool.name(), camel);
                continue;
            }
            nameToTool.put(camel, tool);
        }
        Set<String> targetNames = nameToTool.keySet();
        if (targetNames.equals(activeToolNames) && toolsInstalled) {
            // Fast path: stable toolset, nothing to do. Keep the bridge's
            // dispatch target pointer current in case tool objects rotate
            // while keeping the same names.
            registeredTools.putAll(nameToTool);
            return null;
        }
        // Register host-function bridges for tools we have not seen before.
        for (Map.Entry<String, ReplPrompt.ToolLike> entry : nameToTool.entrySet()) {
            String camel = entry.getKey();
            if (!registeredTools.containsKey(camel)) {
                bridgeSymbols.put(camel, registerToolBridge(camel));
            }
            registeredTools.put(camel, entry.getValue());
        }
        // Rewrite globalThis.tools. Building the object inside a single
        // eval keeps assignments atomic from the model's point of view.
        Map<String, String> bridges = new LinkedHashMap<>();
        for (String camel : targetNames) {
            bridges.put(camel, bridgeSymbols.get(camel));
        }
        c.eval(renderToolsNamespaceAssignment(bridges));
        activeToolNames = targetNames;
        toolsInstalled = true;
        return null;
    }

    private static String renderToolsNamespaceAssignment(Map<String, String> bridges) {
        StringBuilder sb = new StringBuilder("globalThis.tools = {};");
        // Sort for deterministic output.
        List<String> names = new ArrayList<>(bridges.keySet());
        Collections.sort(names);
        for (String name : names) {
            String symbol = bridges.get(name);
            sb.append("globalThis.tools[")
                    .append(jsonString(name))
                    .append("] = globalThis[")
                    .append(jsonString(symbol))
                    .append("];");
        }
        sb.append("undefined");
        return sb.toString();
    }

    private static String jsonString(String s) {
        // Encode as a JSON string literal — the bridges payload is
        // well-known (camelCase identifiers / hash-tagged names), so a
        // simple escape is sufficient.
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
            }
        }
        return sb.append("\"").toString();
    }

    private String registerToolBridge(String camel) {
        JsContext c = requireCtx();
        Map<String, ReplPrompt.ToolLike> registered = registeredTools;
        String bridgeSymbol = bridgeSymbolName(camel);
        c.register(bridgeSymbol, args -> invokeToolBridge(camel, args), true);
        return bridgeSymbol;
    }

    /** Invoke a single tool from the PTC bridge. Returns the marshaled JS value. */
    private CompletableFuture<Object> invokeToolBridge(String camel, Object[] args) {
        ReplPrompt.ToolLike tool = registeredTools.get(camel);
        if (tool == null) {
            CompletableFuture<Object> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException("tool '" + camel + "' not registered"));
            return failed;
        }
        PtcState state = ptcState.get();
        if (state == null) {
            CompletableFuture<Object> failed = new CompletableFuture<>();
            failed.completeExceptionally(new JsConcurrentEvalException(
                    "PTC bridge called outside active eval"));
            return failed;
        }
        // Consume one budget slot.
        PtcState next = state.consumeCallBudget("tools." + camel, maxPtcCalls);
        ptcState.set(next);
        Map<String, Object> payload = normalizeToolInput(args.length == 0 ? null : args[0]);
        String callId = synthToolCallId(tool.name());
        Map<String, Object> enriched = injectToolArgsForPtc(tool, payload, state.outerRuntimeOrThrow(), callId);
        return ainvokeToolOnOuterLoop(tool, enriched, state.outerLoopOrNull()).thenApply(Format::coerceToolOutputForPtc);
    }

    private static String bridgeSymbolName(String toolName) {
        // Keep only identifier-safe characters and salt with a short
        // hash to avoid collisions when different source names
        // sanitize similarly.
        StringBuilder safe = new StringBuilder();
        for (int i = 0; i < toolName.length(); i++) {
            char c = toolName.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '$') {
                safe.append(c);
            } else {
                safe.append('_');
            }
        }
        if (safe.length() == 0 || Character.isDigit(safe.charAt(0))) {
            safe.insert(0, '_');
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(toolName.getBytes(StandardCharsets.UTF_8));
            String hex = HexFormat.of().formatHex(digest).substring(0, 8);
            return "__tools_" + safe + "_" + hex;
        } catch (Exception e) {
            // SHA-256 is required by the platform.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String synthToolCallId(String toolName) {
        return "ptc_" + toolName + "_" + Long.toHexString(System.nanoTime()).substring(0, 8);
    }

    // -----------------------------------------------------------------
    //  Tool input normalization
    // -----------------------------------------------------------------

    /**
     * Coerce whatever JS passed into {@code tools.X(...)} to a
     * {@code Map}. The bridge is configured to pass a single
     * object argument; the raw value is normalized and undefined keys
     * stripped (so the tool's schema defaults take over).
     */
    public static Map<String, Object> normalizeToolInput(Object raw) {
        if (raw == null || JsUndefined.isUndefined(raw)) return Map.of();
        if (raw instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                Object v = e.getValue();
                if (!JsUndefined.isUndefined(v)) {
                    out.put(String.valueOf(e.getKey()), stripUndefined(v));
                }
            }
            return out;
        }
        // Bare scalar / list — wrap under a conventional key so the
        // tool's schema validation produces an informative error.
        return Map.of("input", stripUndefined(raw));
    }

    private static Object stripUndefined(Object value) {
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                Object v = e.getValue();
                if (!JsUndefined.isUndefined(v)) {
                    out.put(String.valueOf(e.getKey()), stripUndefined(v));
                }
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(JsUndefined.isUndefined(item) ? null : stripUndefined(item));
            }
            return out;
        }
        return value;
    }

    /**
     * Mirror of {@code langgraph.prebuilt.tool_node._inject_tool_args}.
     * The Java port does not have the same Pydantic-driven annotation
     * machinery, so the implementation is a no-op for now &mdash; a
     * future binding that ties tools to {@code Middleware.Runtime}
     * can populate {@code InjectedState} / {@code InjectedStore} via
     * the {@code outerRuntime} here.
     */
    public static Map<String, Object> injectToolArgsForPtc(
            ReplPrompt.ToolLike tool,
            Map<String, Object> payload,
            Object outerRuntime,
            String toolCallId) {
        Map<String, Object> enriched = new LinkedHashMap<>(payload);
        // No-op enrichment in the Java port; left as a hook for
        // future injection logic. The tool_call_id is propagated
        // through the bridge and surfaced by the arun wrapper.
        return enriched;
    }

    private CompletableFuture<Object> ainvokeToolOnOuterLoop(ReplPrompt.ToolLike tool,
                                                             Map<String, Object> args,
                                                             Object outerLoop) {
        return CompletableFuture.supplyAsync(() -> invokeToolOnLoop(tool, args, outerLoop));
    }

    private Object invokeToolOnLoop(ReplPrompt.ToolLike tool, Map<String, Object> args, Object outerLoop) {
        try {
            return ainvokeToolSync(tool, args);
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Invoke {@code tool} via reflection over the {@code ToolLike}
     * interface. The Java port has no Python {@code BaseTool.arun};
     * the bridge instead dispatches through the {@link ReplPrompt.ToolLike}
     * contract when present, or through a reflective lookup of an
     * {@code invoke} / {@code arun} method on the underlying tool
     * object.
     */
    @SuppressWarnings("unchecked")
    private static Object ainvokeToolSync(ReplPrompt.ToolLike tool, Map<String, Object> args) throws Exception {
        // The ReplPrompt.ToolLike interface exposes only metadata;
        // actual invocation goes through the underlying tool object
        // when one is reachable. The Java port leaves the dispatch
        // shape pluggable; concrete tools provide an `invoke` or
        // `arun` method that takes a Map<String, Object>.
        Object underlying = underlyingToolObject(tool);
        if (underlying == null) {
            throw new UnsupportedOperationException(
                    "Tool '" + tool.name() + "' has no invokable underlying object; "
                            + "register a concrete tool bridge to enable PTC dispatch.");
        }
        for (String methodName : new String[]{"arun", "ainvoke", "invoke"}) {
            try {
                java.lang.reflect.Method m = underlying.getClass().getMethod(methodName, Map.class);
                Object r = m.invoke(underlying, args);
                if (r instanceof CompletableFuture<?> cf) return cf.get();
                return r;
            } catch (NoSuchMethodException ignored) {
                // try next
            }
        }
        throw new UnsupportedOperationException(
                "Tool '" + tool.name() + "' does not expose invoke(Map)/arun(Map)/ainvoke(Map).");
    }

    private static Object underlyingToolObject(ReplPrompt.ToolLike tool) {
        try {
            java.lang.reflect.Method m = tool.getClass().getMethod("tool");
            return m.invoke(tool);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    // -----------------------------------------------------------------
    //  Task bridge
    // -----------------------------------------------------------------

    private void registerTaskBridge() {
        JsContext c = requireCtx();
        Semaphore calls = taskCalls;
        c.register(TASK_FUNCTION_NAME, args -> {
            PtcState state = ptcState.get();
            if (state == null) {
                throw new JsConcurrentEvalException("task bridge called outside active eval");
            }
            Semaphore limit = taskCalls;
            if (limit == null) {
                throw new IllegalStateException("task call limiter not initialized");
            }
            Map<String, Object> payload = normalizeToolInput(args.length == 0 ? null : args[0]);
            return ainvokeTaskOnOuterLoop(payload, state)
                    .exceptionally(t -> {
                        if (t instanceof RuntimeException re) throw re;
                        throw new TaskBridgeException(t);
                    });
        }, true);
        c.eval("Object.freeze(globalThis.task);"
                + "Object.defineProperty(globalThis, 'task', {"
                + " value: globalThis.task,"
                + " writable: false,"
                + " configurable: false,"
                + "}); undefined");
    }

    private CompletableFuture<Object> ainvokeTaskOnOuterLoop(Map<String, Object> payload, PtcState state) {
        TaskFields fields = validateTaskPayload(payload);
        try {
            limitTaskCall(state);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return Exceptions.cancelledFuture();
        }
        Object runtime = state.outerRuntimeOrThrow();
        @SuppressWarnings("unchecked")
        List<? extends SubagentBridge.TaskTool> tools = readRuntimeTools(runtime);
        SubagentBridge.TaskTool taskTool = SubagentBridge.findSubagentTaskTool(tools);
        if (taskTool == null) {
            releaseTaskCall();
            throw new IllegalStateException("task tool not configured for this eval");
        }
        try {
            Object result = SubagentBridge.callSubagentTaskTool(
                    taskTool,
                    fields.description,
                    fields.subagentType,
                    fields.responseSchema,
                    runtime,
                    fields.label);
            return CompletableFuture.completedFuture(result);
        } finally {
            releaseTaskCall();
        }
    }

    private void limitTaskCall(PtcState state) throws InterruptedException {
        Semaphore s = taskCalls;
        if (s != null) s.acquire();
    }

    private void releaseTaskCall() {
        Semaphore s = taskCalls;
        if (s != null) s.release();
    }

    @SuppressWarnings("unchecked")
    private static List<? extends SubagentBridge.TaskTool> readRuntimeTools(Object runtime) {
        if (runtime == null) return List.of();
        try {
            java.lang.reflect.Method m = runtime.getClass().getMethod("tools");
            Object v = m.invoke(runtime);
            if (v instanceof List<?> l) {
                List<SubagentBridge.TaskTool> out = new ArrayList<>();
                for (Object o : l) {
                    if (o instanceof SubagentBridge.TaskTool tt) out.add(tt);
                }
                return out;
            }
        } catch (ReflectiveOperationException ignored) {
            // fall through
        }
        return List.of();
    }

    private record TaskFields(String description, String subagentType, String label, Map<String, Object> responseSchema) {}

    private static TaskFields validateTaskPayload(Map<String, Object> payload) {
        Object description = payload.get("description");
        if (!(description instanceof String s) || s.isEmpty()) {
            throw new IllegalArgumentException("task() requires non-empty string field `description`");
        }
        Object subagentType = payload.get("subagentType");
        if (!(subagentType instanceof String st) || st.isEmpty()) {
            throw new IllegalArgumentException("task() requires non-empty string field `subagentType`");
        }
        Object rawLabel = payload.get("label");
        if (rawLabel != null && !(rawLabel instanceof String)) {
            throw new IllegalArgumentException("task() field `label` must be a string when provided");
        }
        String label = rawLabel instanceof String ls ? ls.strip() : null;
        if (label != null && label.isEmpty()) label = null;
        Object rawSchema = payload.get("responseSchema");
        if (rawSchema != null && !(rawSchema instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("task() field `responseSchema` must be an object when provided");
        }
        Map<String, Object> schema = rawSchema == null ? null : (Map<String, Object>) rawSchema;
        return new TaskFields(s, st, label, schema);
    }

    // -----------------------------------------------------------------
    //  Eval entry points
    // -----------------------------------------------------------------

    /**
     * Evaluate {@code code} synchronously and return the outcome.
     * Both sync and async entry points funnel through
     * {@code ctx.eval_async} on the worker loop: sync {@code ctx.eval}
     * cannot dispatch async host functions (PTC bridges are async),
     * so routing sync callers through the async path is required
     * for PTC to work under sync invocation.
     */
    public EvalOutcome evalSync(String code) {
        return evalSync(code, null);
    }

    public EvalOutcome evalSync(String code, Object outerRuntime) {
        try {
            return worker.runSync(() -> aevalAsync(code, outerRuntime, null));
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public CompletableFuture<EvalOutcome> evalAsync(String code) {
        return evalAsync(code, null, null);
    }

    public CompletableFuture<EvalOutcome> evalAsync(String code, Object outerRuntime, Object outerLoop) {
        return worker.runAsync(() -> aevalAsync(code, outerRuntime, outerLoop));
    }

    // -----------------------------------------------------------------
    //  Snapshot
    // -----------------------------------------------------------------

    public byte[] createSnapshot() {
        try {
            return worker.runSync(this::doCreateSnapshot);
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public CompletableFuture<byte[]> acreateSnapshot() {
        return worker.runAsync(this::doCreateSnapshot);
    }

    private byte[] doCreateSnapshot() throws Exception {
        return requireCtx().createSnapshot().toBytes();
    }

    public void restoreSnapshot(byte[] payload) {
        restoreSnapshot(payload, true);
    }

    public void restoreSnapshot(byte[] payload, boolean injectGlobals) {
        try {
            worker.runSync(() -> {
                doRestoreSnapshot(payload, injectGlobals);
                return null;
            });
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public CompletableFuture<Void> arestoreSnapshot(byte[] payload) {
        return arestoreSnapshot(payload, true);
    }

    public CompletableFuture<Void> arestoreSnapshot(byte[] payload, boolean injectGlobals) {
        return worker.runAsync(() -> {
            doRestoreSnapshot(payload, injectGlobals);
            return null;
        });
    }

    private Void doRestoreSnapshot(byte[] payload, boolean injectGlobals) throws Exception {
        JsSnapshot snap = JsSnapshot.fromBytes(payload);
        runtime.restoreSnapshot(snap, requireCtx(), injectGlobals);
        return null;
    }

    // -----------------------------------------------------------------
    //  Eval async implementation
    // -----------------------------------------------------------------

    private EvalOutcome aevalAsync(String code, Object outerRuntime, Object outerLoop) throws Exception {
        JsContext c = requireCtx();
        EvalOutcome outcome = EvalOutcome.empty();
        PtcState prev = ptcState.get();
        int budget = maxPtcCalls;
        ptcState.set(new PtcState(budget < 0 ? null : budget, outerRuntime, outerLoop));
        try {
            // TODO(port): when a real JsExecutor is wired in, drive
            // any final-expression Promise (e.g. a bare async IIFE)
            // to its resolved value before marshaling. Without this
            // the Promise object itself fails to marshal and the
            // result surfaces as `[object]` rather than the awaited
            // value.
            JsHandle handle = c.evalHandleAsync(code, perCallTimeout).join();
            try {
                JsHandle resolved = handle.isPromise()
                        ? handle.awaitPromise(perCallTimeout).join()
                        : handle;
                try {
                    try {
                        Object value = resolved.toJava();
                        outcome = outcome.withResult(Format.stringify(value), null);
                    } catch (JsMarshalException me) {
                        // Fall back to a handle-formatting shape.
                        Integer arity = null;
                        try {
                            JsHandle lengthHandle = resolved.get("length");
                            try {
                                Object arityRaw = lengthHandle.toJava();
                                if (arityRaw instanceof Number n) arity = n.intValue();
                            } finally {
                                lengthHandle.dispose();
                            }
                        } catch (Exception ignored) {
                            // best-effort
                        }
                        outcome = outcome.withResult(
                                Format.formatHandle(resolved.typeOf(), arity), "handle");
                        Exceptions.clearExceptionReferences(me);
                    } finally {
                        if (resolved != handle) resolved.dispose();
                    }
                } finally {
                    handle.dispose();
                }
            } finally {
                // drain console after handle disposal
                ConsoleBuffer.DrainResult drained = console.drain();
                outcome = outcome.withStdout(drained.stdout(), drained.droppedChars());
            }
        } catch (PtcCallBudgetExceededException e) {
            outcome = outcome.withError("PTCCallBudgetExceeded", e.renderMessage(), null);
            Exceptions.clearExceptionReferences(e);
        } catch (JsTimeoutException e) {
            outcome = outcome.withError("Timeout", e.getMessage(), null);
            Exceptions.clearExceptionReferences(e);
        } catch (JsDeadlockException e) {
            outcome = outcome.withError("Deadlock", e.getMessage(), null);
            Exceptions.clearExceptionReferences(e);
        } catch (JsHostCancellationException e) {
            // JS declined to catch a cancellation — surface as
            // a cancellation rather than an error.
            throw new java.util.concurrent.CancellationException("host cancellation");
        } catch (JsConcurrentEvalException e) {
            outcome = outcome.withError("ConcurrentEval", e.getMessage(), null);
            Exceptions.clearExceptionReferences(e);
        } catch (JsMemoryLimitException e) {
            outcome = outcome.withError("OutOfMemory", e.getMessage(), null);
            Exceptions.clearExceptionReferences(e);
        } catch (TaskBridgeException e) {
            outcome = outcome.withError(e.errorType(), e.errorMessage(), null);
            Exceptions.clearExceptionReferences(e);
        } catch (JsError e) {
            outcome = outcome.withError(e.name(), e.getMessage(), e.stack());
            Exceptions.clearExceptionReferences(e);
        } finally {
            ptcState.set(prev);
        }
        return outcome;
    }

    // -----------------------------------------------------------------
    //  Close
    // -----------------------------------------------------------------

    public void close() {
        try {
            worker.runSync(this::doClose);
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public CompletableFuture<Void> aclose() {
        return worker.runAsync(() -> {
            doClose();
            return null;
        });
    }

    private Void doClose() throws Exception {
        if (ctx != null) {
            ctx.close();
            ctx = null;
        }
        return null;
    }
}
