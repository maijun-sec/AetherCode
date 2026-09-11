package org.aethercode.deepagents.middleware;

import org.aethercode.core.middleware.ContextSize;
import org.aethercode.core.middleware.SummarizationCutoff;

import org.aethercode.deepagents.langchain_compat.langgraph.Command;
import org.aethercode.core.runtime.AgentState;
import org.aethercode.core.runtime.ContentBlock;
import org.aethercode.core.runtime.Message;
import org.aethercode.deepagents.tools.Tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.aethercode.core.runtime.Message.AIMessage;
import org.aethercode.core.runtime.Message.ToolMessage;
import org.aethercode.core.runtime.Message.SystemMessage;

/**
 * Middleware that exposes a {@code compact_conversation} tool for
 * manual compaction.
 *
 * <p>Java-native port of
 * {@code deepagents.middleware.summarization.SummarizationToolMiddleware}.
 * Composes with a {@link SummarizationMiddleware} instance, reusing
 * its summarization engine (backend, summarizer, messages-to-keep)
 * to let the agent (or a human-in-the-loop approval flow) trigger
 * compaction on demand.</p>
 *
 * <p>For a simpler setup, use
 * {@link CreateSummarizationToolMiddleware#create(Object, org.aethercode.core.fs.backend.BackendProtocol, String)}
 * which builds a default {@code SummarizationMiddleware} and wraps it.</p>
 *
 * <p>The {@code compact_conversation} tool is exposed via
 * {@link #providedToolNames()} as a no-arg, returns-acknowledgement
 * tool. The actual compaction is performed by invoking
 * {@link SummarizationMiddleware#compact(AgentState, List)}; in the
 * Java port the runtime is expected to look up the
 * {@code SummarizationToolMiddleware} from the chain when the
 * tool is called and forward the compact call. A direct call
 * helper is also available via {@link #runCompact(AgentState)} for
 * consumers that wire the tool themselves.</p>
 */
public class SummarizationToolMiddleware implements Middleware {

    /** Name of the tool this middleware exposes. */
    public static final String TOOL_NAME = "compact_conversation";

    /** Default description of the {@code compact_conversation} tool. */
    public static final String TOOL_DESCRIPTION =
            "Compact the conversation by summarizing older messages "
                    + "into a concise summary. Use this proactively when the "
                    + "conversation is getting long to free up context window "
                    + "space. Use it when moving on to a completely new, "
                    + "unrelated task, or after finishing synthesis or "
                    + "extraction when the previous working context is no "
                    + "longer needed. This tool takes no arguments.";

    private final SummarizationMiddleware summarization;
    private final String systemPrompt;

    public SummarizationToolMiddleware(SummarizationMiddleware summarization,
                                        String systemPrompt) {
        this.summarization = Objects.requireNonNull(summarization, "summarization");
        if (systemPrompt != null && !(systemPrompt instanceof String)) {
            throw new IllegalArgumentException(
                    "system_prompt must be String or null");
        }
        this.systemPrompt = systemPrompt;
    }

    public SummarizationToolMiddleware(SummarizationMiddleware summarization) {
        this(summarization, null);
    }

    public SummarizationMiddleware summarization() { return summarization; }
    public String systemPrompt() { return systemPrompt; }

    @Override
    public String name() { return "SummarizationToolMiddleware"; }

    /** Tool names this middleware exposes. Used by the runtime to
     *  register the tools with the model adapter. */
    public Set<String> providedToolNames() {
        return Set.of(TOOL_NAME);
    }

    /** The tool callable for the {@code compact_conversation} tool. */
    public Tool compactConversationTool() {
        return Tool.of(TOOL_NAME, TOOL_DESCRIPTION,
                (args, ctx) -> compactConversationToolResult());
    }

    private String compactConversationToolResult() {
        return "Compaction acknowledged. The runtime will perform a "
                + "compaction pass on the next beforeModel hook "
                + "(see SummarizationMiddleware.compact).";
    }

    /**
     * Run a compaction pass against the given state. Returns a new
     * state with the messages list replaced by a SystemMessage
     * summary plus the last {@code messagesToKeep} messages.
     *
     * <p>Mirrors the runtime contract of the Python
     * {@code compact_conversation} tool: takes no arguments, operates
     * on the current state, and returns the compacted state.</p>
     */
    public AgentState runCompact(AgentState state) {
        List<Message> messages = state.messages();
        if (messages.isEmpty()) return state;
        return summarization.compact(state, messages);
    }

    /**
     * Modern compact tool path: returns a {@link Command} carrying
     * the new summarization event, the session id, and a
     * {@code ToolMessage} confirmation keyed to {@code toolCallId}.
     *
     * <p>Mirrors the Python port's
     * {@code SummarizationToolMiddleware._run_compact}: a tool call
     * returns a {@code Command} for the runtime to apply. Three
     * outcomes are possible:</p>
     * <ul>
     *   <li>Not eligible (below threshold / nothing to compact):
     *       a {@code Command} with a "Nothing to compact" tool
     *       message.</li>
     *   <li>Eligible: a {@code Command} with the new event, session
     *       id, and a success {@code ToolMessage}.</li>
     *   <li>Exception: a {@code Command} with an error
     *       {@code ToolMessage}; the conversation is left
     *       untouched.</li>
     * </ul>
     */
    public Command runCompactCommand(AgentState state, String toolCallId) {
        Objects.requireNonNull(state, "state");
        List<Message> messages = state.messages();
        if (messages.isEmpty()) {
            return nothingToCompactCommand(toolCallId);
        }
        // Manual-compaction eligibility gate (Python's
        // `_is_eligible_for_compaction`): the conversation must be
        // at or above about 50% of the configured auto-summarization
        // trigger before the tool is allowed to fire. Without this
        // gate, the user could compact the entire conversation even
        // when it is well within the token budget.
        if (!isEligibleForCompaction(messages)) {
            return nothingToCompactCommand(toolCallId);
        }
        // Use the shared compact pipeline so the tool path and the
        // model-call path stay in sync.
        SummarizationMiddleware.CompactPipelineResult result;
        try {
            result = summarization.runCompactPipeline(state, messages, noopRuntime());
        } catch (RuntimeException exc) {
            return compactErrorCommand(toolCallId, exc);
        }
        if (result.event() == null) {
            // Below threshold or no cutoff available -> "nothing to compact".
            return nothingToCompactCommand(toolCallId);
        }
        Map<String, Object> update = new java.util.LinkedHashMap<>();
        update.put("_summarization_event", result.event());
        update.put("_summarization_session_id", result.sessionId());
        update.put("messages", List.of(
                new ToolMessage(
                        "tm-" + UUID.randomUUID(), toolCallId,
                        List.of(ContentBlock.text(
                                "Conversation compacted. Summarized "
                                        + result.summarizedCount()
                                        + " messages into a concise summary.")),
                        java.util.Optional.empty(),
                        java.util.Optional.empty(),
                        java.util.Optional.empty(),
                        Map.of(),
                        Map.of())));
        return Command.update(update);
    }

    /**
     * Async twin of {@link #runCompactCommand}. Default delegates
     * to the sync implementation off the calling thread; a future
     * C5 round can wire {@code runCompactPipeline} through the
     * backend's async upload APIs.
     */
    public java.util.concurrent.CompletableFuture<Command> arunCompactCommand(
            AgentState state, String toolCallId) {
        return java.util.concurrent.CompletableFuture.supplyAsync(
                () -> runCompactCommand(state, toolCallId));
    }

    /** Build a "nothing to compact yet" Command for the compact tool. */
    public static Command nothingToCompactCommand(String toolCallId) {
        return Command.update(Map.of("messages", List.of(
                new ToolMessage(
                        "tm-" + UUID.randomUUID(), toolCallId,
                        List.of(ContentBlock.text(
                                "Nothing to compact yet \u2014 conversation is within the token budget.")),
                        java.util.Optional.empty(),
                        java.util.Optional.empty(),
                        java.util.Optional.empty(),
                        Map.of(),
                        Map.of()))));
    }

    /** Build an error Command for the compact tool. */
    public static Command compactErrorCommand(String toolCallId, Throwable exc) {
        String type = exc == null ? "Error" : exc.getClass().getSimpleName();
        String msg = exc == null ? "unknown error" : exc.getMessage();
        return Command.update(Map.of("messages", List.of(
                new ToolMessage(
                        "tm-" + UUID.randomUUID(), toolCallId,
                        List.of(ContentBlock.text(
                                "Compaction failed: an error occurred while "
                                        + "generating the summary (" + type + ": "
                                        + msg + "). The conversation has not been "
                                        + "compacted \u2014 no messages were summarized "
                                        + "or removed.")),
                        java.util.Optional.empty(),
                        java.util.Optional.empty(),
                        java.util.Optional.empty(),
                        Map.of(),
                        Map.of()))));
    }

    /**
     * Manual-compaction eligibility gate. The conversation must be
     * at or above about 50% of the configured auto-summarization
     * trigger before the {@code compact_conversation} tool is allowed
     * to fire. Mirrors Python's
     * {@code SummarizationToolMiddleware._is_eligible_for_compaction}.
     *
     * <ul>
     *   <li>For {@code ("tokens", N)}, eligibility starts at
     *       {@code 0.5 * N} tokens.</li>
     *   <li>For {@code ("messages", N)}, eligibility starts at
     *       {@code 0.5 * N} messages.</li>
     *   <li>For {@code ("fraction", F)}, eligibility starts at
     *       {@code 0.5 * F * maxInputTokens}.</li>
     * </ul>
     *
     * <p>Uses reported token usage metadata when available; otherwise
     * falls back to the configured {@code tokenCounter} (or a
     * 1-token-per-message default if no counter is set). The
     * fraction kind requires a runtime with a model profile; without
     * one, the gate is conservative (returns false) and the tool
     * defers to the caller's profile inference.</p>
     */
    public boolean isEligibleForCompaction(List<Message> messages) {
        ContextSize trigger = summarization.trigger();
        if (trigger == null) return false;
        int reportedTotal = SummarizationCutoff.maxReportedTotalTokens(messages);
        int totalTokens = reportedTotal > 0
                ? reportedTotal
                : countTokens(messages);
        return switch (trigger.kind()) {
            case MESSAGES -> messages.size() >= Math.max(1, trigger.value() / 2);
            case TOKENS -> totalTokens >= Math.max(1, trigger.value() / 2);
            case FRACTION -> {
                // Without a runtime we don't know maxInputTokens;
                // fall back to half the fraction as a token target
                // using the message count as a rough proxy for now.
                int halfTrigger = Math.max(1, trigger.value() / 2);
                yield totalTokens >= halfTrigger;
            }
        };
    }

    /** Count tokens via the configured token counter, or
     *  fall back to a 1-token-per-message approximation. */
    private int countTokens(List<Message> messages) {
        Object counter = summarization.tokenCounter();
        if (counter instanceof ContextSize.TokenCounter tc) {
            return tc.apply(messages);
        }
        return messages.size();
    }

    /** Minimal no-op runtime for the compact tool's pipeline. */
    private static Middleware.Runtime noopRuntime() {
        return new Middleware.Runtime() {
            @Override public List<org.aethercode.deepagents.tools.Tool> tools() { return List.of(); }
            @Override public java.util.function.Function<List<Message>, AIMessage> chatModel() {
                return msgs -> new AIMessage("ai-1", List.of());
            }
        };
    }

    @Override
    public AgentState beforeModel(AgentState state, Runtime runtime) {
        // If a system-prompt fragment was provided, append it to the
        // system message so the model is nudged to call the tool.
        if (systemPrompt == null || systemPrompt.isEmpty()) {
            return state;
        }
        return appendNudge(state, systemPrompt);
    }

    private static AgentState appendNudge(AgentState state, String nudge) {
        List<Message> messages = state.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m instanceof SystemMessage sys) {
                String current = ContentBlock.flattenText(sys.content());
                String newText = current + "\n\n" + nudge;
                SystemMessage replaced = new SystemMessage(
                        sys.id(),
                        List.of(ContentBlock.text(newText)));
                ArrayList<Message> msgs = new ArrayList<>(messages);
                msgs.set(i, replaced);
                return state.withMessages(msgs);
            }
        }
        ArrayList<Message> msgs = new ArrayList<>();
        msgs.add(new SystemMessage(
                "summarization-tool-nudge-" + UUID.randomUUID(),
                List.of(ContentBlock.text(nudge))));
        msgs.addAll(messages);
        return state.withMessages(msgs);
    }
}
