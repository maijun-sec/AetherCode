package org.aethercode.core.engine;

import org.aethercode.core.app.AppState;
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.permission.PermissionResult;
import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.ToolParamValidator;
import org.aethercode.core.tool.Tools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Streaming tool executor. Modelled on the TS {@code services/tools/StreamingToolExecutor.ts}.
 *
 * <p>prior round: the legacy {@code ToolOrchestrator} was deleted (it was prior round dead
 * code, never invoked from the query loop). This streaming executor is now
 * the only path the engine uses to dispatch tool calls, and it yields
 * events as soon as a tool transitions through its lifecycle. Supports
 * cancel propagation. In prior round the
 * orchestrator drained each batch synchronously before yielding; prior round makes the orchestrator a
 * thin wrapper around this streaming executor so the TUI / SDK can paint each step live.
 *
 * <p>State machine per call:
 * <pre>
 *   queued -> executing -> completed -> yielded
 *                       \-> failed   -> yielded
 *                       \-> cancelled (sibling fault) -> yielded
 * </pre>
 *
 * <p>Concurrent tools run in parallel via a virtual-thread-friendly {@code runAsync}. When
 * any tool in a concurrent batch fails with an error result, the executor calls
 * {@code ctx.abort()} on every running sibling, which propagates into the tool's
 * {@code CallContext#isAborted()} check.
 */
public class StreamingToolExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(StreamingToolExecutor.class);

    /**
     * the permission policy is now a mutable
     * volatile reference instead of a final field.
     * Previously, the executor captured the policy at
     * construction time and never saw later swaps —
     * {@code AetherCodeEngine.setPermissionMode}
     * updated {@code engine.policy} to a new
     * {@code ProjectPermissionPolicy} with the new
     * mode, but the executor's {@code this.policy}
     * still pointed at the old policy. The user
     * picked BYPASS_PERMISSIONS and the
     * status bar showed it, yet tool calls still
     * asked for permission because the streaming
     * path consulted the stale policy reference.
     * Volatile gives us per-update visibility
     * without locks; the executor reads
     * {@code this.policy} on every call, so a
     * concurrent setPolicy() is observed by the
     * very next tool call.
     */
    private volatile PermissionPolicy policy;
    private final List<HookBridge> preHooks;
    private final List<HookBridge> postHooks;
    private final int maxParallel;
    /** optional ChatClient so tools like {@code AgentTool} can
     *  spawn subagents from inside the engine path. Set by
     *  {@code AetherCodeEngine}; null in unit-test contexts. */
    private org.aethercode.core.llm.ChatClient chatClient;
    /** optional supplier for the current per-query
     *  {@code WorkingMemoryBuffer}. prior round created the lifecycle
     *  that owns the buffer, but no consumer wrote to it; this
     *  supplier plumbs the buffer into {@code CallContext.extras}
     *  on every tool call so the wm_* tools can find it without
     *  a hard reference to the memory module. May be null —
     *  in that case the buffer key is simply absent. */
    private java.util.function.Supplier<Optional<Object>> workingMemorySupplier;
    /** optional SubagentEngine so multi-step {@code AgentTool}
     *  invocations can re-enter the engine loop. Set by
     *  {@code AetherCodeEngine} (which implements the interface). */
    private org.aethercode.core.agent.Subagent.SubagentEngine subagentEngine;
    /** optional tool-call observer. Invoked on every
     *  completed tool execution (success or error) with
     *  the tool name and the post-call cost in USD. The
     *  AetherCodeEngine wires this to a
     *  {@code PhaseTracker.recordToolCall} so the per-phase
     *  budget (prior round) actually fires. Test contexts can
     *  leave this null. */
    @FunctionalInterface
    public interface ToolCallObserver {
        void onToolCall(String toolName, double costUsd);
    }
    private ToolCallObserver toolCallObserver;

    public StreamingToolExecutor(PermissionPolicy policy, int maxParallel) {
        this.policy = policy;
        this.maxParallel = Math.max(1, maxParallel);
        this.preHooks = new ArrayList<>();
        this.postHooks = new ArrayList<>();
    }

    /**
     * replace the live policy reference. Called
     * by {@code AetherCodeEngine.setPermissionMode}
     * and {@code swapPolicy} after the engine
     * produces a new {@code ProjectPermissionPolicy}
     * via {@code withMode(newMode)} (or
     * {@code MatrixPermissionPolicy.withMatrix(fresh)}).
     * The executor holds the same reference the
     * {@code AetherCodeEngine#check} path does
     * (line 326 below), so they MUST see the same
     * mode — otherwise the user picks BYPASS_PERMISSIONS in
     * the dropdown, the engine's appState reflects
     * it, but the executor still consults the
     * pre-swap policy and asks for every call.
     *
     * <p>{@code volatile} gives the executor a
     * happens-before relationship with the setter
     * thread: any call AFTER this method returns
     * observes the new policy.
     */
    public void setPolicy(PermissionPolicy newPolicy) {
        this.policy = newPolicy;
        if (newPolicy != null && currentSubTaskId != null) {
            // re-push the current sub-task id so
            // an ACCEPT_TASK swap (prior round) still has the
            // boundary state on the new policy.
            newPolicy.setCurrentSubTaskId(currentSubTaskId);
        }
    }

    /** the current policy reference. Volatile
     *  read by every tool call. Returns null only in
     *  a tiny window between construction and the
     *  first setPolicy() — tests that exercise the
     *  executor in isolation must install a policy
     *  before any execute() call. */
    public PermissionPolicy policy() { return policy; }

    /**
     * the live "current sub-task id" used by the ACCEPT_TASK
     * permission mode. Set by the QueryEngine right after a
     * SubTaskStart event fires. The next permission check that
     * comes in with a DIFFERENT sub-task id in its CallContext
     * will be promoted to an ask �?that's the "task boundary"
     * the user sees as a single decision prompt.
     *
     * <p>We also push it to the {@link ProjectPermissionPolicy}
     * (if that's what we're using) so the policy's resolveAcceptTask
     * can do the comparison. For other policy implementations we
     * just stash it in the CallContext extras; the policy can read
     * it from there if it wants.
     */
    private volatile String currentSubTaskId;

    public void setCurrentSubTaskId(String subTaskId) {
        this.currentSubTaskId = subTaskId;
        if (policy != null) policy.setCurrentSubTaskId(subTaskId);
    }

    public String currentSubTaskId() { return currentSubTaskId; }

    public StreamingToolExecutor withPreHook(HookBridge h) { preHooks.add(h); return this; }
    public StreamingToolExecutor withPostHook(HookBridge h) { postHooks.add(h); return this; }

    /** stash the chat client so tools like {@code AgentTool} can
     *  spawn subagents. Returns this for chaining. */
    public StreamingToolExecutor withChatClient(org.aethercode.core.llm.ChatClient c) {
        this.chatClient = c;
        return this;
    }

    /** stash the SubagentEngine so multi-step {@code AgentTool}
     *  can spawn recursive subagents that re-enter the engine loop. */
    public StreamingToolExecutor withSubagentEngine(org.aethercode.core.agent.Subagent.SubagentEngine e) {
        this.subagentEngine = e;
        return this;
    }

    /** install the working-memory supplier. The supplier
     *  returns the current per-query {@code WorkingMemoryBuffer}
     *  (boxed as {@code Object} to keep the executor free of a
     *  hard dep on the memory module) or {@link Optional#empty()}
     *  when no buffer is active. The buffer is then stashed into
     *  {@code CallContext.extras["working_memory"]} on every call. */
    public StreamingToolExecutor withWorkingMemorySupplier(
            java.util.function.Supplier<Optional<Object>> supplier) {
        this.workingMemorySupplier = supplier;
        return this;
    }
    /** install a tool-call observer. The observer is
     *  invoked exactly once per tool execution
     *  (success OR error) with the tool's name and the
     *  cost in USD (0.0 when the engine does not yet
     *  track cost). Pass null to remove. */
    public StreamingToolExecutor withToolCallObserver(ToolCallObserver o) {
        this.toolCallObserver = o;
        return this;
    }

    /** build the CallContext extras map. Always includes
     *  {@code call_id} and {@code app_state}; adds {@code chat_client}
     *  if one has been registered. prior round: also adds {@code subagent_engine}
     *  if one has been registered (used by AgentTool's multi-step path).
     * also adds {@code subTaskId} (the current sub-task id from
     *  ACCEPT_TASK mode), so the permission policy can decide whether
     *  the call belongs to the current task. */
    private Map<String, Object> buildExtras(String callId, AppState appState) {
        Map<String, Object> extras = new java.util.HashMap<>();
        extras.put("call_id", callId);
        extras.put("app_state", appState);
        if (chatClient != null) extras.put("chat_client", chatClient);
        if (subagentEngine != null) extras.put("subagent_engine", subagentEngine);
        if (currentSubTaskId != null) extras.put("subTaskId", currentSubTaskId);
        // working memory buffer (boxed as Object to avoid a
        // hard dep on the memory module). Stash it under the key the
        // wm_* tools look up.
        if (workingMemorySupplier != null) {
            try {
                java.util.Optional<Object> buf = workingMemorySupplier.get();
                if (buf != null && buf.isPresent()) {
                    extras.put("working_memory", buf.get());
                }
            } catch (Exception supplierEx) {
                LOG.warn("working memory supplier failed: {}", supplierEx.getMessage());
            }
        }
        return extras;
    }

    /**
     * Convenience bridge so the executor can call into the {@code aethercode-hooks} module
     * without depending on it. prior round wires the real registry; here we keep a tiny indirection
     * so the dependency graph stays clean and tests can plug fakes in.
     */
    public interface HookBridge {
        enum Phase { PRE, POST }

        /**
         * Verdict for a hook. {@link #CONTINUE} = let the tool
         * call proceed (with the result the executor already has,
         * or a replacement the hook provides). {@link #BLOCK} =
         * refuse the result (treat as failure).
         */
        enum Verdict { CONTINUE, BLOCK }

        /**
         * Hook outcome: verdict + optional replacement
         * result. The {@code newResult} field is non-null only
         * when the hook wants to mutate the tool's output body
         * (e.g. append a recovery hint to a failed edit).
         */
        record Outcome(Verdict verdict, Tool.ToolResult newResult) {
            public static Outcome continue_() { return new Outcome(Verdict.CONTINUE, null); }
            public static Outcome block() { return new Outcome(Verdict.BLOCK, null); }
            public static Outcome replace(Tool.ToolResult r) {
                return r == null ? continue_() : new Outcome(Verdict.CONTINUE, r);
            }
        }

        /**
         * Legacy boolean entry point. Existing bridge
         * implementations (lambdas, simple {@code return true}
         * stubs) keep working �?the default {@link #runWithOutcome}
         * translates {@code true} �?{@code CONTINUE} and
         * {@code false} �?{@code BLOCK}.
         */
        boolean run(Phase phase, Tool tool, String id, Map<String, Object> input,
                    Tool.ToolResult result, AppState appState);

        /**
         * Outcome-based entry point. New bridges that want to
         * mutate the tool result (replace the body, append a
         * hint, etc.) override this and return
         * {@link Outcome#replace}. The default delegates to
         * {@link #run} for backward compat.
         */
        default Outcome runWithOutcome(Phase phase, Tool tool, String id, Map<String, Object> input,
                                       Tool.ToolResult result, AppState appState) {
            return run(phase, tool, id, input, result, appState)
                    ? Outcome.continue_()
                    : Outcome.block();
        }
    }

    public Stream<Event> run(List<ContentBlock.ToolUseBlock> calls, AppState appState) {
        if (calls == null || calls.isEmpty()) return Stream.empty();
        List<Batch> batches = partition(calls, appState);
        return StreamSupport.stream(
                new java.util.Spliterators.AbstractSpliterator<>(
                        Long.MAX_VALUE, java.util.Spliterator.ORDERED | java.util.Spliterator.NONNULL) {
                    int batchIndex = 0;
                    java.util.Iterator<Event> pendingResults = null;
                    @Override
                    public boolean tryAdvance(java.util.function.Consumer<? super Event> action) {
                        while (true) {
                            if (pendingResults != null && pendingResults.hasNext()) {
                                Event ev = pendingResults.next();
                                if (ev instanceof Event.BatchEnd) {
                                    pendingResults = null;
                                    continue;
                                }
                                action.accept(ev);
                                return true;
                            }
                            pendingResults = null;
                            if (batchIndex >= batches.size()) return false;
                            Batch b = batches.get(batchIndex++);
                            pendingResults = runBatchBackpressured(b, appState);
                        }
                    }
                }, false);
    }

    /** R3 �?yield each tool event as soon as it happens, instead of waiting for the whole batch. */
    private java.util.Iterator<Event> runBatchBackpressured(Batch batch, AppState appState) {
        java.util.concurrent.BlockingQueue<Event> queue = new java.util.concurrent.LinkedBlockingQueue<>();
        java.util.concurrent.atomic.AtomicBoolean abortFlag = new java.util.concurrent.atomic.AtomicBoolean(false);
        if (batch.concurrent) {
            java.util.List<CompletableFuture<Void>> futures = new java.util.ArrayList<>();
            for (ContentBlock.ToolUseBlock call : batch.calls) {
                futures.add(CompletableFuture.runAsync(() -> {
                    runOneBackpressured(call, appState, queue, abortFlag);
                }));
            }
            CompletableFuture.runAsync(() -> {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                queue.offer(new Event.BatchEnd());
            });
        } else {
            CompletableFuture.runAsync(() -> {
                for (ContentBlock.ToolUseBlock call : batch.calls) {
                    runOneBackpressured(call, appState, queue, abortFlag);
                }
                queue.offer(new Event.BatchEnd());
            });
        }
        return new java.util.Iterator<>() {
            boolean done = false;
            @Override public boolean hasNext() {
                if (done) return false;
                Event ev;
                try { ev = queue.take(); } catch (InterruptedException e) { return false; }
                if (ev instanceof Event.BatchEnd) { done = true; return false; }
                // re-queue: we'll dequeue it on next() �?but easier: stash it
                stash = ev;
                return true;
            }
            Event stash;
            @Override public Event next() {
                Event e = stash;
                stash = null;
                return e;
            }
        };
    }

    private void runOneBackpressured(ContentBlock.ToolUseBlock call, AppState appState,
                                      java.util.concurrent.BlockingQueue<Event> queue,
                                      java.util.concurrent.atomic.AtomicBoolean batchAbort) {
        Tool tool = Tools.byName(appState.effectiveToolPool(), call.name());
        if (tool == null) {
            queue.offer(new Event.Completed(call.id(), "unknown tool: " + call.name(), true));
            return;
        }
        Tool.CallContext ctx = new Tool.CallContext(
                appState.sessionId(),
                msg -> queue.offer(new Event.Progress(call.id(), msg)),
                buildExtras(call.id(), appState)
        );
        if (ctx.isAborted() || batchAbort.get()) {
            queue.offer(new Event.Completed(call.id(), "aborted before execution", true));
            return;
        }
        queue.offer(new Event.Started(call.id(), call.name(), call.input()));

        for (HookBridge h : preHooks) {
            try {
                boolean ok = h.run(HookBridge.Phase.PRE, tool, call.id(), call.input(), null, appState);
                if (!ok) {
                    queue.offer(new Event.Completed(call.id(), "blocked by pre-hook", true));
                    batchAbort.set(true);
                    return;
                }
            } catch (Exception e) {
                LOG.warn("pre-hook threw: {}", e.toString());
            }
        }
        // the engine's PermissionPolicy is now the source of truth
        // for "should this tool call proceed?". Before this change the
        // executor called `tool.checkPermissions` (which defaulted to
        // Allow for every tool that didn't override it), so the
        // ACCEPT_TASK / BYPASS_PERMISSIONS / etc. modes that the policy
        // implements were effectively dead code in the streaming path.
        // We now consult the policy FIRST and only fall through to the
        // tool's own check if the policy didn't take a position (a no-op
        // PermissionPolicy never returned �?see PermissionPolicy.allowAll
        // for the test/fallback shape). This makes the prior round mode
        // semantics real and lets the user actually pick
        // BYPASS_PERMISSIONS via the TUI's /mode command.
        //
        // We do NOT support `Ask` here �?the streaming executor can't
        // pause a batch to wait for the user. The engine-level policy is
        // responsible for auto-resolving Ask to Allow/Deny when the
        // mode permits (ACCEPT_TASK does this via resolveAcceptTask).
        PermissionResult pr = null;
        if (policy != null) {
            try {
                pr = policy.check(tool, call.input(), ctx).get();
            } catch (Exception e) {
                LOG.warn("policy.check threw for {}: {}", call.name(), e.toString());
                pr = null;
            }
        }
        if (pr == null) {
            // Policy was absent or threw �?fall through to the tool's
            // own check (Tools.java default is Allow for safe-by-default
            // tools; tools that override checkPermissions can still
            // deny).
            try {
                pr = tool.checkPermissions(call.input(), ctx).get();
            } catch (Exception e) {
                queue.offer(new Event.Completed(call.id(), "permission check failed: " + e.getMessage(), true));
                batchAbort.set(true);
                return;
            }
        }
        if (pr instanceof PermissionResult.Deny d) {
            queue.offer(new Event.Completed(call.id(), d.message(), true));
            return;
        }
        if (pr instanceof PermissionResult.Ask) {
            queue.offer(new Event.Completed(call.id(),
                    "tool requires user input (ask not supported in streaming executor)", true));
            return;
        }
        Map<String, Object> finalInput = ((PermissionResult.Allow) pr).updatedInput();
        // R266h: early-reject when the model emitted a tool_use
        // with missing required parameters. Prior round the
        // input was forwarded to tool.call() and the tool itself
        // raised "X is required" — but the LLM, on receiving
        // that error, frequently re-emits the same empty call
        // (the v0.2.66 transcript shows the model thinking
        // "I will write the parameter this time" then emitting
        // `{}` again). The fix has three parts:
        //   1. Validate input here, BEFORE invoking the tool —
        //      no shell / file_write side effect, no wasted
        //      timeout.
        //   2. Return a precise error message that names the
        //      missing field AND shows the exact JSON shape the
        //      model must emit (the model thinks in JSON, so
        //      giving it the shape inline is more useful than
        //      the prose-only "command is required" hint).
        //   3. Count the validation failure as a "bad batch" for
        //      ProgressLoopDetector's emptyInputStreak so a
        //      model stuck in a "fix the tool call format"
        //      loop is hard-stopped after 2 consecutive empty
        //      inputs (the existing default of 3 is too
        //      forgiving — by the third retry the user has
        //      already watched 3 hopeless tool cards in the
        //      TUI). The 2-streak threshold is implemented in
        //      ProgressLoopDetector; this early-reject hands
        //      it the right signal by NOT incrementing a
        //      "successful" counter for a missing-params call.
        ToolParamValidator.ValidationResult vr =
                ToolParamValidator.validate(tool, finalInput);
        if (!vr.valid()) {
            String errMsg = buildMissingParamError(tool, finalInput, vr);
            LOG.warn("R266h: missing-params tool_use rejected pre-invoke: tool={}, id={}, errors={}",
                    call.name(), call.id(), vr.errors());
            queue.offer(new Event.Completed(call.id(), errMsg, true));
            // Mark the batch as aborted so sibling tools in
            // the same parallel batch are cancelled. A model
            // that emits N empty calls in a row is not
            // expecting the other N-1 to succeed; bailing out
            // now is cheaper than waiting for each sibling to
            // raise its own validation error.
            batchAbort.set(true);
            return;
        }
        Tool.ToolResult result;
        try {
            if (ctx.isAborted() || batchAbort.get()) {
                queue.offer(new Event.Completed(call.id(), "aborted", true));
                return;
            }
            result = tool.call(finalInput, ctx).get();
        } catch (Exception e) {
            result = Tool.ToolResult.error(e.getMessage() == null ? e.toString() : e.getMessage());
        }
        for (HookBridge h : postHooks) {
            try {
                HookBridge.Outcome o = h.runWithOutcome(HookBridge.Phase.POST, tool, call.id(), finalInput, result, appState);
                if (o == null) continue;
                if (o.verdict() == HookBridge.Verdict.BLOCK) {
                    result = Tool.ToolResult.error("rejected by post-hook");
                } else if (o.newResult() != null) {
                    // hook replaced the result. Use the
                    // replacement; the hook is responsible for
                    // building the new ToolResult (it can copy
                    // attachments if it wants them preserved).
                    result = o.newResult();
                }
            } catch (Exception e) {
                LOG.warn("post-hook threw: {}", e.toString());
            }
        }
        if (result.isError()) batchAbort.set(true);
        // notify the per-phase tracker (and any
        // other observer the engine installed) so the
        // budget enforcement actually fires. The cost
        // is currently 0.0 — the engine's cost tracker
        // does not yet break cost down by tool, so a
        // finer attribution is a R96+ follow-up.
        if (toolCallObserver != null) {
            try {
                toolCallObserver.onToolCall(tool.name(), 0.0);
            } catch (Throwable t) {
                LOG.debug("toolCallObserver threw for {}: {}", tool.name(), t.toString());
            }
        }
        queue.offer(new Event.Completed(call.id(), result.output(), result.isError()));
    }

    /** Same partitioning rules as the legacy ToolOrchestrator#partition
     *  (prior round). The legacy class was deleted in prior round; the partitioning
     *  rule itself (concurrent safe tools in one batch, otherwise serial)
     *  is preserved here. */
    List<Batch> partition(List<ContentBlock.ToolUseBlock> blocks, AppState appState) {
        List<Batch> out = new ArrayList<>();
        Batch current = null;
        for (ContentBlock.ToolUseBlock t : blocks) {
            Tool tool = Tools.byName(appState.effectiveToolPool(), t.name());
            boolean safe = tool != null && tool.isConcurrencySafe(t.input());
            if (current == null || current.concurrent != safe) {
                current = new Batch(safe);
                out.add(current);
            }
            current.calls.add(t);
        }
        return out;
    }

    private void runBatch(Batch batch, AppState appState,
                          java.util.function.Consumer<? super Event> sink) {
        // this synchronous path is kept for unit tests that don't need back-pressure.
        // Production code uses runBatchBackpressured via run() above.
        if (!batch.concurrent) {
            for (ContentBlock.ToolUseBlock call : batch.calls) {
                runOneLegacy(call, appState, sink, new java.util.concurrent.atomic.AtomicBoolean(false));
            }
            return;
        }
        java.util.concurrent.atomic.AtomicBoolean abortFlag = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.List<CompletableFuture<Void>> futures = new java.util.ArrayList<>();
        for (ContentBlock.ToolUseBlock call : batch.calls) {
            futures.add(CompletableFuture.runAsync(
                    () -> runOneLegacy(call, appState, sink, abortFlag)));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    private void runOneLegacy(ContentBlock.ToolUseBlock call, AppState appState,
                              java.util.function.Consumer<? super Event> sink,
                              java.util.concurrent.atomic.AtomicBoolean batchAbort) {
        Tool tool = Tools.byName(appState.effectiveToolPool(), call.name());
        if (tool == null) {
            sink.accept(new Event.Completed(call.id(), "unknown tool: " + call.name(), true));
            return;
        }
        Tool.CallContext ctx = new Tool.CallContext(
                appState.sessionId(),
                msg -> sink.accept(new Event.Progress(call.id(), msg)),
                Map.of("call_id", call.id())
        );
        if (ctx.isAborted() || batchAbort.get()) {
            sink.accept(new Event.Completed(call.id(), "aborted before execution", true));
            return;
        }
        sink.accept(new Event.Started(call.id(), call.name(), call.input()));
        for (HookBridge h : preHooks) {
            try {
                boolean ok = h.run(HookBridge.Phase.PRE, tool, call.id(), call.input(), null, appState);
                if (!ok) {
                    sink.accept(new Event.Completed(call.id(), "blocked by pre-hook", true));
                    batchAbort.set(true);
                    return;
                }
            } catch (Exception e) { LOG.warn("pre-hook threw: {}", e.toString()); }
        }
        // same policy-first behaviour as runOneBackpressured.
        // See the comment above the parallel block in runOneBackpressured
        // for the rationale (the previous version skipped the engine's
        // policy, so ACCEPT_TASK / BYPASS_PERMISSIONS modes were dead
        // code in the streaming path).
        org.aethercode.core.permission.PermissionResult pr = null;
        if (policy != null) {
            try { pr = policy.check(tool, call.input(), ctx).get(); }
            catch (Exception e) {
                LOG.warn("policy.check threw for {}: {}", call.name(), e.toString());
                pr = null;
            }
        }
        if (pr == null) {
            try { pr = tool.checkPermissions(call.input(), ctx).get(); }
            catch (Exception e) {
                sink.accept(new Event.Completed(call.id(), "permission check failed: " + e.getMessage(), true));
                batchAbort.set(true);
                return;
            }
        }
        if (pr instanceof org.aethercode.core.permission.PermissionResult.Deny d) {
            sink.accept(new Event.Completed(call.id(), d.message(), true));
            return;
        }
        if (pr instanceof org.aethercode.core.permission.PermissionResult.Ask) {
            sink.accept(new Event.Completed(call.id(),
                    "tool requires user input (ask not supported in streaming executor)", true));
            return;
        }
        Map<String, Object> finalInput = ((org.aethercode.core.permission.PermissionResult.Allow) pr).updatedInput();
        // R266h: see the early-reject comment in the parallel
        // branch above. The sink-based path is the newer
        // observation model; both must reject missing-params
        // calls pre-invoke so the model sees the same precise
        // error message and the loop detector counts the
        // failure uniformly.
        ToolParamValidator.ValidationResult vr2 =
                ToolParamValidator.validate(tool, finalInput);
        if (!vr2.valid()) {
            String errMsg = buildMissingParamError(tool, finalInput, vr2);
            LOG.warn("R266h: missing-params tool_use rejected pre-invoke (sink): tool={}, id={}, errors={}",
                    call.name(), call.id(), vr2.errors());
            sink.accept(new Event.Completed(call.id(), errMsg, true));
            batchAbort.set(true);
            return;
        }
        Tool.ToolResult result;
        try {
            if (ctx.isAborted() || batchAbort.get()) {
                sink.accept(new Event.Completed(call.id(), "aborted", true));
                return;
            }
            result = tool.call(finalInput, ctx).get();
        } catch (Exception e) {
            result = Tool.ToolResult.error(e.getMessage() == null ? e.toString() : e.getMessage());
        }
        for (HookBridge h : postHooks) {
            try {
                HookBridge.Outcome o = h.runWithOutcome(HookBridge.Phase.POST, tool, call.id(), finalInput, result, appState);
                if (o == null) continue;
                if (o.verdict() == HookBridge.Verdict.BLOCK) {
                    result = Tool.ToolResult.error("rejected by post-hook");
                } else if (o.newResult() != null) {
                    // hook replaced the result.
                    result = o.newResult();
                }
            } catch (Exception e) { LOG.warn("post-hook threw: {}", e.toString()); }
        }
        if (result.isError()) batchAbort.set(true);
        // see the queue.offer branch above for
        // context. The two branches handle the legacy
        // queue-based sink and the newer sink-based
        // observer respectively. Both must notify the
        // tracker so the budget enforces regardless of
        // which observation model the engine is using.
        if (toolCallObserver != null) {
            try {
                toolCallObserver.onToolCall(tool.name(), 0.0);
            } catch (Throwable t) {
                LOG.debug("toolCallObserver threw for {}: {}", tool.name(), t.toString());
            }
        }
        sink.accept(new Event.Completed(call.id(), result.output(), result.isError()));
    }

    /** Events yielded as tools run. The TUI turns these into redraws. */
    public sealed interface Event {
        String id();

        record Started(String id, String name, Map<String, Object> input) implements Event {}
        record Progress(String id, Message msg) implements Event {}
        record Completed(String id, Object output, boolean isError) implements Event {}
        /** Internal: marks end of a batch. The orchestrator consumes it as a no-op. */
        record BatchEnd() implements Event { public String id() { return "_batch_end_"; } }
    }

    static final class Batch {
        final boolean concurrent;
        final List<ContentBlock.ToolUseBlock> calls = new ArrayList<>();
        Batch(boolean concurrent) { this.concurrent = concurrent; }
    }

    /** R266h: build a precise error message when the model
     *  emitted a tool_use with missing required parameters.
     *
     *  Format goals (in order):
     *  1. State which tool + which fields are missing — the
     *     "X is required" prose the model has been seeing
     *     since v0.2.19, but more specific (per-field, not
     *     per-tool).
     *  2. Include the parameter types from the schema so the
     *     model knows whether to send a string / number /
     *     boolean.
     *  3. Show a complete, copy-pastable JSON example with
     *     every required key present and placeholders for
     *     optional ones. The v0.2.66 transcript shows the
     *     model "thinks" the right JSON but emits `{}` —
     *     a worked shape inline is the strongest signal
     *     that "this is what your tool_use.input should
     *     look like".
     *
     *  The example is intentionally short (one example, no
     *  per-field ramble) — long error messages tend to be
     *  truncated by the model or pasted back as user
     *  text. The model's system prompt already explains the
     *  JSON shape in detail; this is a last-mile hint. */
    private static String buildMissingParamError(
            Tool tool,
            Map<String, Object> input,
            ToolParamValidator.ValidationResult vr) {
        Map<String, Object> schema = tool.inputSchema();
        StringBuilder sb = new StringBuilder();
        sb.append("tool '").append(tool.name())
          .append("' was called with missing required parameters.\n");
        // list missing fields with their declared types
        sb.append("missing fields:\n");
        @SuppressWarnings("unchecked")
        Map<String, Object> props = schema == null
                ? null
                : (Map<String, Object>) schema.get("properties");
        Object reqSpec = schema == null ? null : schema.get("required");
        java.util.List<?> required = reqSpec instanceof java.util.List<?> r ? r : java.util.List.of();
        // Use the validator's errors as the primary source —
        // they already include the [tool-name] prefix. Strip
        // the prefix here so we can re-emit a cleaner line
        // per missing field.
        for (String err : vr.errors()) {
            String cleaned = err;
            if (cleaned.startsWith("[" + tool.name() + "] ")) {
                cleaned = cleaned.substring(("[" + tool.name() + "] ").length());
            }
            // Map the dotted path back to the schema so we
            // can print the type next to the field name.
            String fieldName = cleaned;
            int colon = cleaned.indexOf(':');
            if (colon > 0) fieldName = cleaned.substring(0, colon).trim();
            String type = "?";
            String desc = "";
            if (props != null && props.get(fieldName) instanceof Map<?, ?> p) {
                Object t = p.get("type");
                if (t != null) type = t.toString();
                Object d = p.get("description");
                if (d != null) desc = " — " + d;
            }
            sb.append("  - ").append(fieldName)
              .append(" (").append(type).append(")").append(desc)
              .append("\n");
        }
        // worked example. Build a tiny {"key": "..."} object
        // for each required field. We don't try to fill in
        // the value — the model knows what command / file
        // path / pattern it wants to use; we just confirm
        // the shape.
        sb.append("expected tool_use shape (fill the placeholders):\n");
        sb.append("```\n");
        sb.append("{\"type\":\"tool_use\",\"id\":\"call_<unique>\",")
          .append("\"name\":\"").append(tool.name()).append("\",");
        sb.append("\"input\":{");
        boolean first = true;
        for (Object r : required) {
            String name = r.toString();
            String placeholder = placeholderFor(props, name);
            if (!first) sb.append(",");
            sb.append("\"").append(name).append("\":").append(placeholder);
            first = false;
        }
        sb.append("}}\n");
        sb.append("```\n");
        sb.append("fix: re-emit the call with the required field(s) filled in. ")
          .append("if you cannot determine the right value, STOP retrying and ask the user.");
        return sb.toString();
    }

    /** R266h: return a JSON-shaped placeholder for a required
     *  field. Strings get `"<value>"`, numbers / booleans get
     *  bare values, arrays / objects get `[]` / `{}`. The
     *  intent is to show the model the right SHAPE — the
     *  value is its problem. */
    private static String placeholderFor(Map<String, Object> props, String name) {
        if (props == null) return "\"<value>\"";
        Object p = props.get(name);
        if (!(p instanceof Map<?, ?> pm)) return "\"<value>\"";
        Object t = pm.get("type");
        if (t == null) return "\"<value>\"";
        return switch (t.toString()) {
            case "string"  -> "\"<value>\"";
            case "integer",
                 "number"  -> "0";
            case "boolean" -> "true";
            case "array"   -> "[]";
            case "object"  -> "{}";
            default        -> "\"<value>\"";
        };
    }
}
