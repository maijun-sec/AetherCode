package org.aethercode.compact;

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
import java.util.stream.Stream;

/**
 * structured 7-section compactor.
 *
 * <p>Synthesises patterns from Claude Code's 7-section
 * compaction and OpenCode's 5-section compaction into a
 * structure that preserves enough context for the model to
 * continue the task without re-reading the full transcript.
 *
 * <p>The seven sections:
 * <ol>
 *   <li><b>Goal</b> — the original user request, in one sentence.</li>
 *   <li><b>Progress</b> — what got done, in bullet points.</li>
 *   <li><b>Decisions</b> — non-obvious choices the model made and why.</li>
 *   <li><b>Files Touched</b> — paths read / written / edited.</li>
 *   <li><b>Open Questions</b> — things still uncertain.</li>
 *   <li><b>Current State</b> — where execution is right now.</li>
 *   <li><b>Next Steps</b> — what the model plans to do next.</li>
 * </ol>
 *
 * <p>Implementation: a single LLM call with a structured prompt
 * that asks for the response in a fixed markdown layout. We then
 * return the response as a single assistant-role message —
 * {@code SlidingWindowCompactor} (or a future summarisation
 * chain) prepends it to the head of the transcript.
 *
 * <p>Compared to the auto-compactor (which produces a single
 * free-form summary), the structured form has two advantages:
 * <ul>
 *   <li>Each section is unambiguous; the model knows exactly
 *       what to write where. No risk of dropping the "what
 *       files did we touch" line because it doesn't fit the
 *       free-form flow.</li>
 *   <li>Subsequent compaction passes (the chain) preserve the
 *       structure, so a 10-hour run's chain of summaries stays
 *       compact and parseable instead of a single paragraph
 *       that grew unbounded.</li>
 * </ul>
 */
public final class StructuredCompactor implements Compactor {

    private static final Logger LOG = LoggerFactory.getLogger(StructuredCompactor.class);

    /** R136.4: bumped default context window from 200K
     *  (Claude 3 baseline) to 1M, matching the MiniMax
     *  M3 family's published spec. When the engine knows
     *  the actual model ceiling (via {@code ChatClient
     *  .Options.contextWindow()}), it passes the value
     *  to the 4-arg constructor; this default is the
     *  "if the caller didn't say" fallback. */
    public static final int DEFAULT_CONTEXT_WINDOW = 1_000_000;
    /** R136.4: bumped buffer from 13K to 64K so the
     *  summary call has room to receive a 1M-context
     *  model's thinking trace. 64K is still well under
     *  the output cap (MiniMax M3 = 512K) so we never
     *  truncate the summary because the model wanted
     *  more room to think. */
    public static final int DEFAULT_BUFFER = 64_000;
    /** R136.4: bumped maxInput from 80K to 900K so
     *  we can compact from a near-full 1M-context
     *  transcript in one pass. The summary call
     *  itself only emits ~32K tokens, so the input
     *  budget is the bottleneck. */
    public static final int DEFAULT_MAX_INPUT_TOKENS = 900_000;
    /** Cap on consecutive failures before the circuit breaker opens. */
    public static final int MAX_CONSECUTIVE_FAILURES = 3;

    private final ChatClient chatClient;
    private final int contextWindow;
    private final int bufferTokens;
    private final int maxInputTokens;
    private int consecutiveFailures = 0;

    public StructuredCompactor(ChatClient chatClient) {
        this(chatClient, DEFAULT_CONTEXT_WINDOW, DEFAULT_BUFFER, DEFAULT_MAX_INPUT_TOKENS);
    }

    public StructuredCompactor(ChatClient chatClient, int contextWindow, int bufferTokens, int maxInputTokens) {
        if (chatClient == null) throw new IllegalArgumentException("chatClient");
        this.chatClient = chatClient;
        this.contextWindow = contextWindow;
        this.bufferTokens = bufferTokens;
        this.maxInputTokens = maxInputTokens;
    }

    @Override
    public boolean shouldCompact(List<Message> messages) {
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) return false;
        if (messages == null || messages.isEmpty()) return false;
        long tokens = estimateTokens(messages);
        return tokens > (contextWindow - bufferTokens);
    }

    @Override
    public List<Message> compact(List<Message> messages) {
        if (!shouldCompact(messages)) return null;
        try {
            String summary = summarise(messages);
            consecutiveFailures = 0;
            // Return as a single assistant message with the
            // structured content. The downstream caller (engine
            // or SlidingWindowCompactor) installs it on the
            // transcript.
            Message summaryMsg = new Message(
                    "summary-" + System.currentTimeMillis(),
                    Role.ASSISTANT,
                    List.of(new ContentBlock.TextBlock(summary)),
                    null,
                    java.util.Map.of("kind", "structured-summary"));
            return List.of(summaryMsg);
        } catch (Exception e) {
            consecutiveFailures++;
            LOG.warn("structured compact failed (consecutive={}): {}", consecutiveFailures, e.getMessage());
            return null;
        }
    }

    /** Build the structured summary by calling the chat client
     *  with a prompt that asks for the 7-section markdown layout. */
    String summarise(List<Message> messages) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a context-compaction assistant. Produce a Markdown summary ");
        prompt.append("of the conversation below in EXACTLY these 7 sections, in this order, ");
        prompt.append("with these headings (use the exact '## ' prefix shown):\n\n");
        prompt.append("## Goal\n");
        prompt.append("(one sentence — the original user request)\n\n");
        prompt.append("## Progress\n");
        prompt.append("(bullet list — what was done, with the key results / findings)\n\n");
        prompt.append("## Decisions\n");
        prompt.append("(bullet list — non-obvious choices the model made and why)\n\n");
        prompt.append("## Files Touched\n");
        prompt.append("(bullet list of file paths — read, written, edited)\n\n");
        prompt.append("## Open Questions\n");
        prompt.append("(bullet list — things still uncertain or blocked)\n\n");
        prompt.append("## Current State\n");
        prompt.append("(1-2 sentences — where execution is right now)\n\n");
        prompt.append("## Next Steps\n");
        prompt.append("(bullet list — what the model plans to do next, in order)\n\n");
        prompt.append("Keep each section short. Preserve: explicit user preferences, ");
        prompt.append("constraints, and concrete file paths / function names / values that ");
        prompt.append("would be expensive to re-derive. Drop: verbose tool output, repeated ");
        prompt.append("error text, anything that can be re-read from the file system.\n\n");
        prompt.append("Conversation:\n");
        int budget = maxInputTokens * 4; // char budget
        int used = prompt.length();
        for (Message m : messages) {
            String line = "- [" + m.role() + "] " + compactText(m) + "\n";
            if (used + line.length() > budget) {
                prompt.append("… (truncated — older messages dropped to fit the budget)\n");
                break;
            }
            prompt.append(line);
            used += line.length();
        }
        String systemPrompt = "You are a context-compaction assistant. Produce only the 7-section Markdown summary, no preamble or explanation.";
        List<Message> msgs = new ArrayList<>();
        msgs.add(Message.userText(prompt.toString()));
        StringBuilder out = new StringBuilder();
        Stream<StreamEvent> stream = chatClient.stream(msgs, systemPrompt, List.of());
        for (java.util.Iterator<StreamEvent> it = stream.iterator(); it.hasNext(); ) {
            StreamEvent ev = it.next();
            if (ev instanceof StreamEvent.TextDelta td) {
                out.append(td.text());
            } else if (ev instanceof StreamEvent.RunEnd re) {
                // The chat client's own RunEnd carries the final
                // blocks. If the structured summary ended up as
                // a text block in the final message, prefer that
                // (it's already assembled and avoids any chunk
                // truncation). Otherwise we keep the streamed
                // accumulation.
                if (re.finalBlocks() != null) {
                    for (ContentBlock b : re.finalBlocks()) {
                        if (b instanceof ContentBlock.TextBlock t) {
                            // Only adopt the final block if the
                            // streamed accumulation is empty (the
                            // client didn't emit TextDelta).
                            if (out.length() == 0) {
                                out.append(t.text());
                            }
                        }
                    }
                }
                break;
            }
        }
        if (out.length() == 0) {
            throw new IllegalStateException("compactor chat client returned no text");
        }
        return out.toString();
    }

    /** Try to extract the model id from the chat client, falling
     *  back to "structured-summary" so messages have a stable id. */
    private static String modelTag(ChatClient client) {
        try { return client.modelId(); } catch (Exception e) { return "structured-summary"; }
    }

    private static String compactText(Message m) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : m.content()) {
            if (b instanceof ContentBlock.TextBlock t) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(t.text());
            } else if (b instanceof ContentBlock.ToolUseBlock u) {
                if (sb.length() > 0) sb.append(' ');
                sb.append('[').append(u.name()).append("(...)").append(']');
            } else if (b instanceof ContentBlock.ToolResultBlock r) {
                if (sb.length() > 0) sb.append(' ');
                String c = r.content() instanceof String s ? s : String.valueOf(r.content());
                if (c.length() > 200) c = c.substring(0, 200) + "…";
                sb.append("→").append(c);
            }
        }
        String s = sb.toString().replace('\n', ' ').replace('\r', ' ');
        return s;
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
        return total / 4;
    }

    public int consecutiveFailures() { return consecutiveFailures; }
}
