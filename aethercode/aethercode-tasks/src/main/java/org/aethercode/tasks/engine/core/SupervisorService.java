package org.aethercode.tasks.engine.core;

import org.aethercode.tasks.engine.summary.LlmCaller;
import org.aethercode.tasks.engine.summary.LlmBackedSummaryHook;
import org.aethercode.tasks.engine.summary.PostTurnSummaryHook;
import org.aethercode.tasks.engine.summary.SummaryExtractor;
import org.aethercode.tasks.engine.summary.SummaryFallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * T-1-24: minimal SupervisorService that exposes
 * {@link #onAssistantTurnEnd(String, String, List)} and wires
 * in the {@link PostTurnSummaryHook}. The full RPC surface
 * lives in the AetherCode supervisor; this class is the
 * deepagents-tasks analog focused on the post-turn flow.
 *
 * <p>Flow:
 * <ol>
 *   <li>Hook sees the last assistant message. If it has a
 *       {@code ## Summary} block, no-op.</li>
 *   <li>Otherwise, the {@link LlmBackedSummaryHook} queues a
 *       small LLM call. On success, the produced text is
 *       emitted as a {@code task/summary} event AND appended
 *       to the supervisor's per-session message stream
 *       (the stream lives in the {@link SessionStream}
 *       abstraction, which is in-memory for now).</li>
 *   <li>On failure, the {@link SummaryFallback} appends a
 *       human-readable fallback line and emits
 *       a {@code task/summary} event with the failure
 *       reason.</li>
 * </ol>
 *
 * <p>The method is non-blocking: it returns a
 * {@link CompletableFuture} that completes when the
 * post-turn work is done. The supervisor's main turn loop
 * proceeds without waiting.
 */
public final class SupervisorService {

    private static final Logger LOG = LoggerFactory.getLogger(SupervisorService.class);

    private final SupervisorStore store;
    private final SessionRegistry registry;
    private final SessionStream stream;
    private final TaskEventListener listener;
    private final PostTurnSummaryHook hook;
    private final Executor executor;

    public SupervisorService(SupervisorStore store,
                             SessionRegistry registry,
                             SessionStream stream,
                             TaskEventListener listener,
                             PostTurnSummaryHook hook,
                             Executor executor) {
        this.store = Objects.requireNonNull(store, "store");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.stream = Objects.requireNonNull(stream, "stream");
        this.listener = listener == null ? TaskEventListener.NOOP : listener;
        this.hook = hook == null ? PostTurnSummaryHook.NOOP : hook;
        this.executor = executor;
    }

    /**
     * Convenience: build a service that uses
     * {@link LlmBackedSummaryHook} with a {@link SummaryFallback}
     * wired in as the {@code exceptionally} callback.
     */
    public static SupervisorService withLlmBackedHook(
            SupervisorStore store, SessionRegistry registry, SessionStream stream,
            TaskEventListener listener, LlmCaller llm, Executor executor) {
        LlmBackedSummaryHook primary = new LlmBackedSummaryHook(llm, executor);
        PostTurnSummaryHook composed = new ComposedHook(primary, new SummaryFallback(executor, null));
        return new SupervisorService(store, registry, stream, listener, composed, executor);
    }

    public PostTurnSummaryHook hook() { return hook; }

    /**
     * Called by the supervisor's main loop after the assistant's
     * turn finishes. Returns a future that completes when the
     * post-turn work (summary injection or fallback) is done.
     */
    public CompletableFuture<OnTurnResult> onAssistantTurnEnd(
            String sessionId,
            String lastAssistantMessage,
            List<Map<String, Object>> recentMessages) {
        Objects.requireNonNull(sessionId, "sessionId");
        PostTurnSummaryHook.TurnContext ctx = new PostTurnSummaryHook.TurnContext(
                sessionId, lastAssistantMessage, recentMessages, Map.of());
        CompletableFuture<PostTurnSummaryHook.SummaryResult> fut = hook.onAssistantTurnEnd(ctx);

        return fut.handle((result, throwable) -> {
            if (throwable != null) {
                LOG.warn("post-turn hook threw for {}: {}", sessionId, throwable.getMessage());
                return applyFallback(sessionId, "hook exception: " + throwable.getMessage());
            }
            if (result == null) {
                return applyFallback(sessionId, "hook returned null");
            }
            return switch (result.kind()) {
                case ALREADY_PRESENT -> OnTurnResult.alreadyPresent(sessionId);
                case INJECTED -> applyInjected(sessionId, result.text());
                case FALLBACK -> applyFallback(sessionId, result.reason());
                case SKIPPED -> OnTurnResult.skipped(sessionId, result.reason());
            };
        });
    }

    private OnTurnResult applyInjected(String sessionId, String summaryText) {
        String footer = SummaryExtractor.renderInjectedFooter(summaryText);
        stream.appendAssistantText(sessionId, footer);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", sessionId);
        payload.put("injected", true);
        payload.put("text", summaryText);
        payload.put("source", "llm");
        listener.onEvent(TaskEvent.of(sessionId, "task/summary", payload));
        LOG.debug("post-turn summary INJECTED for {}", sessionId);
        return OnTurnResult.injected(sessionId, summaryText);
    }

    private OnTurnResult applyFallback(String sessionId, String reason) {
        String line = SummaryExtractor.renderFailureFooter(reason);
        stream.appendAssistantText(sessionId, "\n" + line);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", sessionId);
        payload.put("injected", false);
        payload.put("text", line.strip());
        payload.put("reason", reason);
        payload.put("source", "fallback");
        listener.onEvent(TaskEvent.of(sessionId, "task/summary", payload));
        LOG.info("post-turn summary FALLBACK for {}: {}", sessionId, reason);
        return OnTurnResult.fallback(sessionId, reason);
    }

    /** Composite hook: try primary; on failure, fall back. The
     *  fallback is invoked only when the primary returns
     *  {@link Kind#FALLBACK} or throws. The original reason is
     *  preserved by chaining the two hooks. */
    private static final class ComposedHook implements PostTurnSummaryHook {
        private final PostTurnSummaryHook primary;
        private final PostTurnSummaryHook fallback;

        ComposedHook(PostTurnSummaryHook primary, PostTurnSummaryHook fallback) {
            this.primary = primary;
            this.fallback = fallback;
        }

        @Override
        public CompletableFuture<SummaryResult> onAssistantTurnEnd(TurnContext ctx) {
            return primary.onAssistantTurnEnd(ctx)
                    .exceptionally(t -> SummaryResult.fallback(
                            t.getMessage() == null ? "primary hook threw" : t.getMessage()))
                    .thenCompose(result -> {
                        if (result.kind() == Kind.FALLBACK) {
                            // Preserve the original failure reason; the
                            // fallback hook only fills in the text.
                            return fallback.onAssistantTurnEnd(ctx)
                                    .thenApply(fb -> SummaryResult.fallback(
                                            result.reason() == null
                                                    ? (fb.reason() == null ? "fallback" : fb.reason())
                                                    : result.reason()));
                        }
                        return CompletableFuture.completedFuture(result);
                    });
        }
    }

    /** Result of {@link #onAssistantTurnEnd}. */
    public record OnTurnResult(
            String sessionId,
            boolean summaryAlreadyPresent,
            boolean summaryInjected,
            String summaryText,
            String fallbackReason) {

        public static OnTurnResult alreadyPresent(String sid) {
            return new OnTurnResult(sid, true, false, null, null);
        }
        public static OnTurnResult injected(String sid, String text) {
            return new OnTurnResult(sid, false, true, text, null);
        }
        public static OnTurnResult fallback(String sid, String reason) {
            return new OnTurnResult(sid, false, false, null, reason);
        }
        public static OnTurnResult skipped(String sid, String reason) {
            return new OnTurnResult(sid, false, false, null, reason);
        }
    }
}
