package org.aethercode.core.middleware;

import org.aethercode.core.runtime.ContentBlock;
import org.aethercode.core.runtime.Message;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.aethercode.core.runtime.Message.AIMessage;

/**
 * Pure-logic helpers for the pre-summarization tool-argument
 * truncation that the summarization middleware applies when the
 * conversation is over budget but before it is summarised.
 *
 * <p>Java-native port of
 * {@code _DeepAgentsSummarizationMiddleware._truncate_args},
 * {@code _should_truncate_args}, {@code _determine_truncate_cutoff_index},
 * and {@code _truncate_tool_call} in
 * {@code deepagents.middleware.summarization}. The settings shape
 * mirrors Python's {@code TruncateArgsSettings} TypedDict.</p>
 */
public final class SummarizationTruncateArgs {

    /** Default {@code max_length} for an arg before it is truncated. */
    public static final int DEFAULT_MAX_LENGTH = 2000;
    /** Default suffix appended after the first 20 chars of a truncated
     *  argument. */
    public static final String DEFAULT_TRUNCATION_TEXT = "...(argument truncated)";
    /** Number of leading characters of an arg preserved verbatim. */
    public static final int TRUNCATION_HEAD_LENGTH = 20;
    /** Default tool names whose arguments are eligible for truncation. */
    public static final List<String> DEFAULT_TRUNCATABLE_TOOLS =
            List.of("write_file", "edit_file");

    private SummarizationTruncateArgs() {}

    // -----------------------------------------------------------------
    //  Settings shape
    // -----------------------------------------------------------------

    /**
     * Settings for truncating large tool-call arguments in older
     * messages before summarization fires. Mirrors Python's
     * {@code TruncateArgsSettings} TypedDict.
     */
    public record TruncateArgsSettings(
            ContextSize trigger,
            ContextSize keep,
            int maxLength,
            String truncationText) {

        public TruncateArgsSettings {
            trigger = trigger == null ? null : trigger;
            keep = keep == null ? new ContextSize(ContextSize.Kind.MESSAGES, 20) : keep;
            if (maxLength <= 0) {
                maxLength = DEFAULT_MAX_LENGTH;
            }
            truncationText = (truncationText == null || truncationText.isEmpty())
                    ? DEFAULT_TRUNCATION_TEXT
                    : truncationText;
        }

        /** Default settings: no trigger (truncation disabled),
         *  keep the most recent 20 messages, 2000-char cap,
         *  default truncation text. */
        public static TruncateArgsSettings defaults() {
            return new TruncateArgsSettings(null, null, 0, null);
        }
    }

    // -----------------------------------------------------------------
    //  Trigger evaluation
    // -----------------------------------------------------------------

    /**
     * Decide whether the truncation step should fire.
     *
     * <p>When {@code settings.trigger()} is {@code null}, truncation
     * is disabled and the method returns {@code false}. Otherwise
     * the trigger is evaluated against the supplied
     * {@code totalTokens} (or message count for the messages kind)
     * using {@code maxInputTokens} (which may be {@code null} for
     * fraction-based triggers without a model profile &mdash; in
     * which case the method returns {@code false} to fall back to
     * the no-truncation path).</p>
     */
    public static boolean shouldTruncateArgs(TruncateArgsSettings settings,
                                             int messageCount,
                                             int totalTokens,
                                             Integer maxInputTokens) {
        if (settings == null || settings.trigger() == null) {
            return false;
        }
        ContextSize trigger = settings.trigger();
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
    //  Cutoff index for the keep window
    // -----------------------------------------------------------------

    /**
     * Determine the cutoff index for argument truncation based on
     * the keep policy.
     *
     * <p>Messages at index {@code >= cutoff} are preserved without
     * truncation; messages at index {@code < cutoff} can have their
     * tool args truncated.</p>
     *
     * <p>For a {@code MESSAGES} keep, the cutoff is
     * {@code len(messages) - keepValue}. For a {@code TOKENS} or
     * {@code FRACTION} keep, the cutoff is found by walking back
     * from the end and accumulating the per-message token count
     * via {@code partialTokenCounter} until the budget is filled.
     * When {@code maxInputTokens} is {@code null} for a fraction
     * keep, the method falls back to a 20-message keep (Python's
     * fallback).</p>
     */
    public static int determineTruncateCutoffIndex(
            TruncateArgsSettings settings,
            List<Message> messages,
            Integer maxInputTokens,
            java.util.function.Function<Message, Integer> partialTokenCounter) {
        if (messages == null) messages = List.of();
        int messageCount = messages.size();
        ContextSize keep = settings.keep();
        int keepValue = keep.value();
        return switch (keep.kind()) {
            case MESSAGES -> {
                if (messageCount <= keepValue) yield messageCount;
                yield messageCount - keepValue;
            }
            case TOKENS, FRACTION -> {
                int targetTokenCount;
                if (keep.kind() == ContextSize.Kind.FRACTION) {
                    if (maxInputTokens == null) {
                        // Fallback: 20 messages (matches Python's
                        // `_determine_truncate_cutoff_index`).
                        int fallback = 20;
                        if (messageCount <= fallback) yield messageCount;
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
                    int msgTokens = partialTokenCounter.apply(messages.get(i));
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
    //  Single tool-call truncation
    // -----------------------------------------------------------------

    /**
     * Truncate large arguments on a single tool call. Returns a new
     * {@link ContentBlock.ToolUseBlock} whose {@code args} map has
     * any string value longer than {@code maxLength} replaced by
     * {@code value[:20] + truncationText}. If no arg was truncated,
     * the original block is returned unchanged.
     */
    public static ContentBlock.ToolUseBlock truncateToolCall(
            ContentBlock.ToolUseBlock toolCall,
            int maxLength,
            String truncationText) {
        if (toolCall == null) return null;
        Map<String, Object> args = toolCall.input();
        if (args == null || args.isEmpty()) {
            return toolCall;
        }
        Map<String, Object> truncated = new LinkedHashMap<>();
        boolean modified = false;
        for (Map.Entry<String, Object> e : args.entrySet()) {
            Object v = e.getValue();
            if (v instanceof String s && s.length() > maxLength) {
                truncated.put(e.getKey(),
                        s.substring(0, TRUNCATION_HEAD_LENGTH) + truncationText);
                modified = true;
            } else {
                truncated.put(e.getKey(), v);
            }
        }
        if (!modified) {
            return toolCall;
        }
        return new ContentBlock.ToolUseBlock(toolCall.id(), toolCall.name(), truncated);
    }

    // -----------------------------------------------------------------
    //  Truncate-args pipeline
    // -----------------------------------------------------------------

    /**
     * Truncate large tool-call arguments in old messages.
     *
     * <p>Returns a {@link Result} with the rewritten messages and a
     * {@code modified} flag. When {@code modified} is false, the
     * returned message list is the same instance as the input (the
     * caller can skip rebuilding the request).</p>
     *
     * <p>Only AIMessage blocks carrying a {@link ContentBlock.ToolUseBlock}
     * with a name in {@code truncatableTools} are eligible; other
     * tool calls (and tool calls outside the cutoff) pass through
     * unchanged.</p>
     */
    public static Result truncateArgs(TruncateArgsSettings settings,
                                      List<Message> messages,
                                      int cutoffIndex,
                                      List<String> truncatableTools) {
        if (messages == null || messages.isEmpty()) {
            return new Result(messages == null ? List.of() : messages, false);
        }
        if (cutoffIndex >= messages.size()) {
            return new Result(messages, false);
        }
        List<String> toolNames = (truncatableTools == null || truncatableTools.isEmpty())
                ? DEFAULT_TRUNCATABLE_TOOLS
                : truncatableTools;
        int maxLength = settings.maxLength();
        String truncationText = settings.truncationText();

        List<Message> out = new ArrayList<>(messages.size());
        boolean modified = false;
        for (int i = 0; i < messages.size(); i++) {
            Message m = messages.get(i);
            if (i >= cutoffIndex || !(m instanceof AIMessage aim)) {
                out.add(m);
                continue;
            }
            if (aim.content() == null || aim.content().isEmpty()) {
                out.add(m);
                continue;
            }
            List<ContentBlock> newBlocks = new ArrayList<>(aim.content().size());
            boolean msgModified = false;
            for (ContentBlock b : aim.content()) {
                if (b instanceof ContentBlock.ToolUseBlock tu
                        && toolNames.contains(tu.name())) {
                    ContentBlock.ToolUseBlock truncated = truncateToolCall(
                            tu, maxLength, truncationText);
                    if (truncated != tu) {
                        msgModified = true;
                    }
                    newBlocks.add(truncated);
                } else {
                    newBlocks.add(b);
                }
            }
            if (msgModified) {
                modified = true;
                out.add(new AIMessage(aim.id(), newBlocks, aim.toolCallId()));
            } else {
                out.add(m);
            }
        }
        return new Result(out, modified);
    }

    /**
     * Pair returned by {@link #truncateArgs}: the (possibly
     * modified) message list and a flag indicating whether any
     * message was rewritten.
     */
    public record Result(List<Message> messages, boolean modified) {
        public Result {
            messages = messages == null ? List.of() : List.copyOf(messages);
        }
    }
}
