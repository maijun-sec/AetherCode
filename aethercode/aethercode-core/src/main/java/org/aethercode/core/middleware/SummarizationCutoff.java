package org.aethercode.core.middleware;

import org.aethercode.core.runtime.Message;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import org.aethercode.core.runtime.Message.AIMessage;

/**
 * Pure-logic helpers for the summarisation decision: should
 * summarisation fire, where should the cutoff land, and how should
 * the message list be split into "to summarize" + "to preserve"
 * halves.
 *
 * <p>Java-native port of
 * {@code _DeepAgentsSummarizationMiddleware._should_summarize},
 * {@code _determine_cutoff_index}, and
 * {@code _partition_messages} in
 * {@code deepagents.middleware.summarization}. The Java port
 * implements these directly because it does not have a
 * {@code LCSummarizationMiddleware} helper to delegate to; the
 * semantics mirror LangChain's behaviour one-for-one.</p>
 */
public final class SummarizationCutoff {

    private SummarizationCutoff() {}

    // -----------------------------------------------------------------
    //  Should-summarize trigger evaluation
    // -----------------------------------------------------------------

    /**
     * Decide whether summarisation should fire for the current
     * transcript.
     *
     * <p>Trigger evaluation:</p>
     * <ul>
     *   <li>{@code ("messages", N)} &mdash; true when
     *       {@code messageCount >= N}.</li>
     *   <li>{@code ("tokens", N)} &mdash; true when
     *       {@code totalTokens >= N}.</li>
     *   <li>{@code ("fraction", F)} &mdash; true when
     *       {@code totalTokens >= F * maxInputTokens}.
     *       When {@code maxInputTokens} is {@code null} this
     *       returns {@code false}.</li>
     * </ul>
     */
    public static boolean shouldSummarize(ContextSize trigger,
                                          int messageCount,
                                          int totalTokens,
                                          Integer maxInputTokens) {
        if (trigger == null) return false;
        return switch (trigger.kind()) {
            case MESSAGES -> messageCount >= trigger.value();
            case TOKENS -> totalTokens >= trigger.value();
            case FRACTION -> {
                if (maxInputTokens == null) yield false;
                int threshold = (int) (maxInputTokens
                        * (trigger.value() / 1_000_000.0));
                if (threshold <= 0) threshold = 1;
                yield totalTokens >= threshold;
            }
        };
    }

    // -----------------------------------------------------------------
    //  Cutoff index
    // -----------------------------------------------------------------

    /**
     * Choose a cutoff index respecting the retention policy.
     *
     * <p>For a {@code ("messages", N)} keep, the cutoff is
     * {@code max(0, len(messages) - N)}: preserve the most recent
     * {@code N} messages, summarise everything before. For a
     * {@code ("tokens", N)} or {@code ("fraction", F)} keep, the
     * cutoff is found by walking back from the end and accumulating
     * the per-message token count via {@code partialTokenCounter}
     * until the budget is filled; messages at and after the cutoff
     * are preserved. When {@code maxInputTokens} is {@code null} for
     * a fraction keep, the method falls back to a 20-message
     * keep.</p>
     *
     * <p>The cutoff may be {@code 0} or even negative when the
     * keep window covers the entire transcript &mdash; callers must
     * treat {@code cutoff <= 0} as "no summarisation possible".</p>
     */
    public static int determineCutoffIndex(ContextSize keep,
                                           List<Message> messages,
                                           Integer maxInputTokens,
                                           ToIntFunction<Message> partialTokenCounter) {
        if (messages == null) messages = List.of();
        int messageCount = messages.size();
        int keepValue = keep.value();
        return switch (keep.kind()) {
            case MESSAGES -> {
                if (messageCount <= keepValue) yield 0;
                yield messageCount - keepValue;
            }
            case TOKENS, FRACTION -> {
                int targetTokenCount;
                if (keep.kind() == ContextSize.Kind.FRACTION) {
                    if (maxInputTokens == null) {
                        // Fallback to a 20-message keep, matching
                        // Python's LCSummarizationMiddleware default
                        // when no model profile is available.
                        int fallback = 20;
                        if (messageCount <= fallback) yield 0;
                        yield messageCount - fallback;
                    }
                    targetTokenCount = (int) (maxInputTokens
                            * (keepValue / 1_000_000.0));
                } else {
                    targetTokenCount = keepValue;
                }
                if (targetTokenCount <= 0) targetTokenCount = 1;

                int tokensKept = 0;
                for (int i = messageCount - 1; i >= 0; i--) {
                    int msgTokens = partialTokenCounter.applyAsInt(messages.get(i));
                    if (tokensKept + msgTokens > targetTokenCount) {
                        yield i + 1;
                    }
                    tokensKept += msgTokens;
                }
                yield 0;
            }
        };
    }

    // -----------------------------------------------------------------
    //  Partition messages
    // -----------------------------------------------------------------

    /**
     * Split {@code messages} into the head (to summarise) and the
     * tail (to preserve), sliced at {@code cutoffIndex}.
     *
     * <p>Returns a {@link Partition} whose {@code toSummarize} is
     * {@code messages[0:cutoffIndex]} and whose {@code preserved} is
     * {@code messages[cutoffIndex:len(messages)}. The slices are
     * defensive copies of the input list.</p>
     */
    public static Partition partitionMessages(List<Message> messages, int cutoffIndex) {
        if (messages == null) messages = List.of();
        int size = messages.size();
        int clampedCutoff = Math.max(0, Math.min(cutoffIndex, size));
        List<Message> toSummarize = List.copyOf(messages.subList(0, clampedCutoff));
        List<Message> preserved = List.copyOf(messages.subList(clampedCutoff, size));
        return new Partition(toSummarize, preserved);
    }

    /**
     * Pair returned by {@link #partitionMessages(List, int)}.
     */
    public record Partition(List<Message> toSummarize, List<Message> preserved) {
        public Partition {
            toSummarize = toSummarize == null ? List.of() : List.copyOf(toSummarize);
            preserved = preserved == null ? List.of() : List.copyOf(preserved);
        }
    }

    // -----------------------------------------------------------------
    //  Reported tokens: usage_metadata-based triggering
    // -----------------------------------------------------------------

    /**
     * Return the largest {@code total_tokens} reported in any
     * {@link AIMessage#usageMetadata()} of the input list.
     * Returns {@code 0} when no AIMessage carries a
     * {@code total_tokens} entry. Mirrors Python's
     * {@code _should_summarize_based_on_reported_tokens} for the
     * use case where the model reports token usage that exceeds
     * the configured token trigger.
     */
    public static int maxReportedTotalTokens(List<Message> messages) {
        if (messages == null) return 0;
        int max = 0;
        for (Message m : messages) {
            if (!(m instanceof AIMessage ai)) continue;
            Map<String, Object> usage = ai.usageMetadata();
            if (usage == null) continue;
            Object total = usage.get("total_tokens");
            if (total instanceof Number n) {
                int t = n.intValue();
                if (t > max) max = t;
            }
        }
        return max;
    }
}
