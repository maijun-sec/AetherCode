package org.aethercode.compact;

import org.aethercode.core.app.AppState;
import org.aethercode.core.compact.Compactor;
import org.aethercode.core.llm.ChatClient;
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;
import org.aethercode.core.stream.StreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Decides when to compact and performs the compaction. Modelled on the TS
 * {@code autoCompactIfNeeded()} in {@code services/compact/autoCompact.ts}.
 *
 * <p>Two policies are merged:
 * <ul>
 *   <li>Token budget — when the transcript grows past the window, summarise</li>
 *   <li>Circuit breaker — if compaction fails three times in a row, stop trying for the rest
 *       of the session (matches the TS MAX_CONSECUTIVE_AUTOCOMPACT_FAILURES=3 rule)</li>
 * </ul>
 *
 * <p>The compact is invoked <em>between</em> LLM turns by {@link org.aethercode.core.engine.QueryEngine}.
 * It never runs mid-turn because the transcript is the source of truth for the next LLM
 * call and we want the model to see the trimmed state on the next request.
 */
public class CompactGate implements Compactor {

    private static final Logger LOG = LoggerFactory.getLogger(CompactGate.class);

    public static final int DEFAULT_CONTEXT_WINDOW = 200_000;
    public static final int DEFAULT_BUFFER = 13_000;
    public static final int DEFAULT_MAX_INPUT = 80_000;
    public static final int MAX_CONSECUTIVE_FAILURES = 3;

    private final ChatClient chatClient;
    private final int contextWindow;
    private final int bufferTokens;
    private final int maxInputTokens;
    private int consecutiveFailures = 0;
    private boolean circuitOpen = false;

    public CompactGate(ChatClient chatClient) {
        this(chatClient, DEFAULT_CONTEXT_WINDOW, DEFAULT_BUFFER, DEFAULT_MAX_INPUT);
    }

    public CompactGate(ChatClient chatClient, int contextWindow, int bufferTokens, int maxInputTokens) {
        this.chatClient = chatClient;
        this.contextWindow = contextWindow;
        this.bufferTokens = bufferTokens;
        this.maxInputTokens = maxInputTokens;
    }

    public boolean isCircuitOpen() { return circuitOpen; }
    public int consecutiveFailures() { return consecutiveFailures; }

    /**
     * Implements {@link Compactor#compact(List)} — returns a new spliced list on success,
     * the original list on failure (so the engine keeps going without modifying state).
     */
    @Override
    public List<Message> compact(List<Message> messages) {
        Result r = compactRaw(messages);
        if (!r.wasCompacted()) return messages;
        return spliceSummary(messages, r.summary());
    }

    /** Internal entry point that exposes the failure counter (used by tests). */
    public Result compactRaw(List<Message> messages) {
        if (circuitOpen) return new Result(false, null, consecutiveFailures);
        try {
            String summary = summarise(messages);
            consecutiveFailures = 0;
            return new Result(true, summary, 0);
        } catch (Exception e) {
            consecutiveFailures++;
            LOG.warn("compact failed (consecutive={}): {}", consecutiveFailures, e.getMessage());
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                circuitOpen = true;
                LOG.warn("compact circuit breaker opened — no more attempts this session");
            }
            return new Result(false, null, consecutiveFailures);
        }
    }

    /**
     * @return true if compaction is needed (caller should call {@link #compact} afterwards)
     */
    public boolean shouldCompact(List<Message> messages) {
        if (circuitOpen) return false;
        long tokens = estimateTokens(messages);
        return tokens > (contextWindow - bufferTokens);
    }

    /**
     * Summarise the conversation. On success, replace the original messages list with a
     * single synthetic assistant message containing the summary; the caller is responsible
     * for re-installing that list on the AppState.
     *
     * <p>On failure, increment the failure counter and open the circuit if it hits the cap.
     */
    public Result compact_(List<Message> messages) {
        return compactRaw(messages);
    }

    /** Build a new message list with the summary spliced in front of the recent tail. */
    public List<Message> spliceSummary(List<Message> original, String summary) {
        if (summary == null || summary.isBlank()) return original;
        int keepTail = Math.min(4, original.size());
        int cutFrom = original.size() - keepTail;
        List<Message> out = new ArrayList<>();
        // Carry any leading system messages through.
        for (int i = 0; i < cutFrom; i++) {
            if (original.get(i).role() == Role.SYSTEM) {
                out.add(original.get(i));
            }
        }
        // Synthetic summary as a user message.
        out.add(new Message(
                null, Role.USER,
                List.of(new ContentBlock.TextBlock(
                        "[Conversation compacted — earlier turns replaced by the summary below]\n\n" + summary)),
                null,
                Map.of("summary", true)
        ));
        // Append the preserved tail.
        for (int i = cutFrom; i < original.size(); i++) {
            out.add(original.get(i));
        }
        return out;
    }

    private String summarise(List<Message> messages) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Summarise the conversation so far in plain Markdown. Preserve:\n");
        prompt.append("- explicit user preferences and constraints\n");
        prompt.append("- file paths and code locations that were discussed\n");
        prompt.append("- tool outputs and their key results\n");
        prompt.append("- open questions / TODOs\n\n");
        prompt.append("Conversation:\n");
        int budget = maxInputTokens * 4;
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
        String systemPrompt = "You are a compaction assistant. Produce a single Markdown summary.";
        List<Message> req = new ArrayList<>();
        req.add(Message.userText(prompt.toString()));
        StringBuilder out = new StringBuilder();
        Stream<StreamEvent> stream = chatClient.stream(req, systemPrompt, List.of());
        for (java.util.Iterator<StreamEvent> it = stream.iterator(); it.hasNext(); ) {
            StreamEvent ev = it.next();
            if (ev instanceof StreamEvent.TextDelta td) out.append(td.text());
            else if (ev instanceof StreamEvent.RunEnd) break;
        }
        return out.toString();
    }

    public static long estimateTokens(List<Message> messages) {
        long total = 0;
        for (Message m : messages) {
            for (ContentBlock b : m.content()) {
                if (b instanceof ContentBlock.TextBlock t) total += t.text().length();
                else if (b instanceof ContentBlock.ToolResultBlock r) {
                    if (r.content() instanceof String s) total += s.length();
                }
            }
        }
        return total / 4;
    }

    public record Result(boolean wasCompacted, String summary, int consecutiveFailures) {}
}
