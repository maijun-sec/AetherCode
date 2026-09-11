package org.aethercode.core.middleware;

import org.aethercode.core.runtime.Message;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Pure-logic helpers for the summarisation token counter and the
 * tools-aware probe that decides whether a counter accepts a
 * {@code tools} argument.
 *
 * <p>Java-native port of
 * {@code _DeepAgentsSummarizationMiddleware._token_counter_accepts_tools}
 * and {@code _count_tokens} in
 * {@code deepagents.middleware.summarization}. Because Java's
 * function types do not have named keyword parameters, the
 * {@code acceptsTools} probe is implemented as an
 * {@code instanceof} check on the
 * {@link ToolsAwareTokenCounter} marker interface rather than
 * signature introspection &mdash; the runtime probe fallback in
 * {@link #countTokens} covers callers that supply a counter that
 * is neither {@link ContextSize.TokenCounter} nor
 * {@link ToolsAwareTokenCounter}.</p>
 */
public final class SummarizationTokenCounter {

    private static final Logger log = Logger.getLogger(SummarizationTokenCounter.class.getName());

    private SummarizationTokenCounter() {}

    // -----------------------------------------------------------------
    //  Marker interface
    // -----------------------------------------------------------------

    /**
     * Counter that accepts a {@code tools} argument in addition to
     * messages. Mirrors the Python
     * {@code _token_counter_accepts_tools(...)} "true" branch: a
     * counter that has a {@code tools} parameter contributes the
     * tool schemas to its count.
     */
    @FunctionalInterface
    public interface ToolsAwareTokenCounter {
        int count(List<? extends Message> messages, List<?> tools);
    }

    // -----------------------------------------------------------------
    //  Signature / instance probe
    // -----------------------------------------------------------------

    /**
     * Decide whether {@code counter} accepts a {@code tools}
     * argument. Returns {@code true} when the counter implements
     * {@link ToolsAwareTokenCounter} or {@link java.util.function.BiFunction}
     * (a 2-arg function), {@code false} when it is a plain
     * {@link ContextSize.TokenCounter} or a 1-arg
     * {@link java.util.function.Function}, and {@code null} when
     * the counter is none of these &mdash; in which case the caller
     * must fall back to a runtime probe (handled by
     * {@link #countTokens}).
     */
    public static Boolean acceptsTools(Object counter) {
        if (counter == null) return Boolean.FALSE;
        if (counter instanceof ToolsAwareTokenCounter) return Boolean.TRUE;
        if (counter instanceof java.util.function.BiFunction) return Boolean.TRUE;
        if (counter instanceof ContextSize.TokenCounter) return Boolean.FALSE;
        if (counter instanceof java.util.function.Function) return Boolean.FALSE;
        // Unknown counter type: signal that a runtime probe is
        // needed. Mirrors Python's `None` return for opaque
        // callables.
        return null;
    }

    // -----------------------------------------------------------------
    //  countTokens with probe fallback
    // -----------------------------------------------------------------

    /**
     * Count tokens for {@code messages} plus optional
     * {@code systemMessage} and {@code tools}, dispatching through
     * the {@code counter} the caller provided.
     *
     * <p>Dispatch rules (matching the Python port's
     * {@code _count_tokens}):</p>
     * <ol>
     *   <li>If {@code counter} is a {@link ToolsAwareTokenCounter},
     *       it is called with {@code (messages, tools)}.</li>
     *   <li>Else if {@code counter} is a {@link ContextSize.TokenCounter},
     *       it is called with {@code (messages)} only.</li>
     *   <li>Else (unknown type) the helper probes by calling
     *       {@code counter.apply((Object) messages, tools)}; if a
     *       {@link java.lang.reflect.InvocationTargetException} or
     *       {@link IllegalArgumentException} surfaces, the helper
     *       falls back to {@code counter.apply(messages)} (single
     *       argument). A genuine {@link RuntimeException} thrown by
     *       the counter body is NOT swallowed &mdash; it propagates
     *       so a broken counter is not hidden behind a silently
     *       wrong count.</li>
     * </ol>
     *
     * <p>The system message, when non-null, is prepended to the
     * messages list before counting (matches Python's
     * {@code counted_messages = [system_message, *messages]}).</p>
     */
    public static int countTokens(Object counter,
                                  List<? extends Message> messages,
                                  Message systemMessage,
                                  List<?> tools) {
        List<? extends Message> countedMessages = messages;
        if (systemMessage != null) {
            java.util.ArrayList<Message> combined = new java.util.ArrayList<>(
                    messages == null ? 1 : messages.size() + 1);
            combined.add(systemMessage);
            if (messages != null) combined.addAll(messages);
            countedMessages = combined;
        }
        if (counter instanceof ToolsAwareTokenCounter tac) {
            return tac.count(countedMessages, tools);
        }
        if (counter instanceof ContextSize.TokenCounter tc) {
            return tc.apply(countedMessages);
        }
        // Unknown type: probe with two args, fall back to one.
        if (counter == null) {
            throw new IllegalStateException("token counter is null");
        }
        if (counter instanceof java.util.function.BiFunction<?, ?, ?> bf) {
            try {
                @SuppressWarnings("unchecked")
                java.util.function.BiFunction<List<? extends Message>, List<?>, Integer> typed =
                        (java.util.function.BiFunction<List<? extends Message>, List<?>, Integer>) bf;
                return typed.apply(countedMessages, tools);
            } catch (ClassCastException e) {
                // Signature-shape probe: the runtime type
                // did not match the BiFunction<List, List, ?>
                // shape, so try the single-arg form. This
                // is the SOLE path that swallows a probe
                // ClassCastException (a body-level
                // RuntimeException such as TypeError is
                // NOT caught here — those propagate so a
                // broken counter is not hidden behind a
                // silently wrong count).
                log.log(Level.FINE,
                        "Two-arg token counter probe failed: {0}; falling back to one-arg",
                        e.getMessage());
            }
        }
        if (counter instanceof java.util.function.Function<?, ?> f) {
            @SuppressWarnings("unchecked")
            java.util.function.Function<List<? extends Message>, Integer> typed =
                    (java.util.function.Function<List<? extends Message>, Integer>) f;
            return typed.apply(countedMessages);
        }
        throw new IllegalStateException(
                "Unsupported token counter type: " + counter.getClass().getName());
    }
}
