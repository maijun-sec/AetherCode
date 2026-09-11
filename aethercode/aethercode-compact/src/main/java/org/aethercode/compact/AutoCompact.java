package org.aethercode.compact;

import org.aethercode.core.llm.ChatClient;
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Auto-compaction. Modelled after the TS {@code services/compact/autoCompact.ts}.
 *
 * <p>When the running transcript gets too large, this class asks the model to summarise
 * everything before a configurable cutoff. The summary replaces the truncated prefix; the
 * tail (recent turns) is preserved verbatim.
 */
public class AutoCompact {

    private static final Logger LOG = LoggerFactory.getLogger(AutoCompact.class);

    /** R136.4: bumped default context window from 200K
     *  (Claude 3 baseline) to 1M, matching the MiniMax
     *  M3 family's published spec. */
    public static final int DEFAULT_CONTEXT_WINDOW = 1_000_000;
    /** R136.4: bumped buffer from 13K to 64K so the
     *  summary call has room to receive a 1M-context
     *  model's thinking trace. */
    public static final int DEFAULT_BUFFER = 64_000;
    /** R136.4: bumped hard ceiling from 80K to 900K
     *  so we can compact from a near-full 1M-context
     *  transcript in one pass. */
    public static final int DEFAULT_MAX_INPUT_TOKENS = 900_000;
    /** Cap on consecutive failures before the circuit breaker opens. */
    public static final int MAX_CONSECUTIVE_FAILURES = 3;

    private final ChatClient chatClient;
    private final int contextWindow;
    private final int bufferTokens;
    private final int maxInputTokens;
    private int consecutiveFailures = 0;

    public AutoCompact(ChatClient chatClient) {
        this(chatClient, DEFAULT_CONTEXT_WINDOW, DEFAULT_BUFFER, DEFAULT_MAX_INPUT_TOKENS);
    }

    public AutoCompact(ChatClient chatClient, int contextWindow, int bufferTokens, int maxInputTokens) {
        this.chatClient = chatClient;
        this.contextWindow = contextWindow;
        this.bufferTokens = bufferTokens;
        this.maxInputTokens = maxInputTokens;
    }

    /**
     * Decide whether compaction is needed given the current transcript. Naive prior round implementation:
     * 4 chars per token heuristic. Good enough to gate; not a substitute for real token counts.
     */
    public boolean shouldCompact(List<Message> messages) {
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) return false;
        long tokens = estimateTokens(messages);
        return tokens > (contextWindow - bufferTokens);
    }

    public Result compact(List<Message> messages) {
        if (shouldCompact(messages)) {
            try {
                String summary = summarise(messages);
                consecutiveFailures = 0;
                return new Result(true, summary);
            } catch (Exception e) {
                consecutiveFailures++;
                LOG.warn("compact failed (consecutive={}): {}", consecutiveFailures, e.getMessage());
                return new Result(false, null);
            }
        }
        return new Result(false, null);
    }

    /** Build a summary of the message history. R1: use a lightweight chat call. */
    String summarise(List<Message> messages) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Summarise the conversation so far in plain Markdown. Preserve:\n");
        prompt.append("- explicit user preferences and constraints\n");
        prompt.append("- file paths and code locations that were discussed\n");
        prompt.append("- tool outputs and their key results\n");
        prompt.append("- open questions / TODOs\n\n");
        prompt.append("Conversation:\n");
        int budget = maxInputTokens * 4; // char budget
        int used = prompt.length();
        for (Message m : messages) {
            String line = "- [" + m.role() + "] " + m.textContent() + "\n";
            if (used + line.length() > budget) {
                prompt.append("… (truncated)\n");
                break;
            }
            prompt.append(line);
            used += line.length();
        }
        // We invoke the chat client with a single user message; the LLM returns text.
        String systemPrompt = "You are a compaction assistant. Produce a single Markdown summary.";
        List<Message> msgs = new ArrayList<>();
        msgs.add(Message.userText(prompt.toString()));
        StringBuilder out = new StringBuilder();
        Stream<org.aethercode.core.stream.StreamEvent> stream = chatClient.stream(msgs, systemPrompt, List.of());
        for (java.util.Iterator<org.aethercode.core.stream.StreamEvent> it = stream.iterator(); it.hasNext(); ) {
            org.aethercode.core.stream.StreamEvent ev = it.next();
            if (ev instanceof org.aethercode.core.stream.StreamEvent.TextDelta td) {
                out.append(td.text());
            } else if (ev instanceof org.aethercode.core.stream.StreamEvent.RunEnd) {
                break;
            }
        }
        return out.toString();
    }

    static long estimateTokens(List<Message> messages) {
        long total = 0;
        for (Message m : messages) {
            for (ContentBlock b : m.content()) {
                if (b instanceof ContentBlock.TextBlock t) total += t.text().length();
                else if (b instanceof ContentBlock.ToolResultBlock r) {
                    if (r.content() instanceof String s) total += s.length();
                }
            }
        }
        return total / 4; // chars -> tokens heuristic
    }

    public record Result(boolean wasCompacted, String summary) {}
}
