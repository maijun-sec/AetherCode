package org.aethercode.compact;

import org.aethercode.core.llm.ChatClient;
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;
import org.aethercode.core.stream.StreamEvent;
import org.aethercode.core.compact.Compactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * R136.5: 8-section compactor, combining Claude Code's
 * 7-section layout with OpenCode's 5-section layout to
 * preserve the most context for a million-token model.
 *
 * <p>Where the prior round 7-section
 * {@link StructuredCompactor} puts:
 * <ol>
 *   <li>Goal</li>
 *   <li>Progress</li>
 *   <li>Decisions</li>
 *   <li>Files Touched</li>
 *   <li>Open Questions</li>
 *   <li>Current State</li>
 *   <li>Next Steps</li>
 * </ol>
 *
 * <p>the R136.5 8-section version adds one extra section
 * inspired by OpenCode's 5-section model:
 *
 * <ol>
 *   <li><b>Goal</b> (Claude 1) — the user's original ask,
 *       one sentence.</li>
 *   <li><b>Progress</b> (Claude 2) — what got done, with
 *       the key results / findings.</li>
 *   <li><b>Active Constraints</b> (OpenCode 1, NEW) —
 *       environmental / API / permission / budget
 *       constraints the model must respect to keep
 *       working. Without this section the compacted
 *       context loses the "do not run rm -rf", "max
 *       file size 200KB", "API rate limit 60 rpm"
 *       kind of state.</li>
 *   <li><b>Decisions</b> (Claude 3) — non-obvious
 *       choices the model made and why.</li>
 *   <li><b>Files Touched</b> (Claude 4) — paths read /
 *       written / edited, with one-line description of
 *       the change per file.</li>
 *   <li><b>Open Questions</b> (Claude 5) — things still
 *       uncertain or blocked.</li>
 *   <li><b>Current State</b> (Claude 6) — where
 *       execution is right now (which file is being
 *       edited, which test is failing, etc.).</li>
 *   <li><b>Next Steps</b> (Claude 7) — what the model
 *       plans to do next, in order.</li>
 * </ol>
 *
 * <p>Why the 8th section matters: in the prior round 7-section
 * version, a constraint like "the daemon's loop
 * detector is set to complex mode, so the model has
 * 20 turns before the detector warns" gets lost
 * between sections — it's neither a Decision (the
 * model didn't choose it), a File Touched (no file
 * was changed), nor a Next Step. With a dedicated
 * Active Constraints section, the model can pick up
 * the next compacted run knowing exactly which
 * knobs are turned and which are off-limits.
 *
 * <p>Implementation mirrors {@link StructuredCompactor}
 * but uses a single LLM call with an 8-section prompt.
 * The output is a single assistant-role message with
 * {@code kind=structured-summary-8} (vs the prior round
 * {@code kind=structured-summary}) so the next
 * compaction pass can tell the two formats apart and
 * chain them appropriately.
 *
 * <p>Token budget (R136.4):
 * <ul>
 *   <li>Default context window: 1_000_000 (matches
 *       MiniMax M3, Gemini 2.5 Pro)</li>
 *   <li>Default buffer: 64_000 (room for the summary
 *       call's response to be near-full 512K
 *       output)</li>
 *   <li>Default max input tokens: 900_000 (compact
 *       from a near-full transcript in one pass)</li>
 * </ul>
 */
public final class StructuredCompactor8 implements Compactor {

    private static final Logger LOG = LoggerFactory.getLogger(StructuredCompactor8.class);

    /** R136.5: default context window 1M. */
    public static final int DEFAULT_CONTEXT_WINDOW = 1_000_000;
    /** R136.5: default buffer 64K. */
    public static final int DEFAULT_BUFFER = 64_000;
    /** R136.5: default max input 900K. */
    public static final int DEFAULT_MAX_INPUT_TOKENS = 900_000;
    /** Circuit breaker: 3 consecutive failures stops compacting. */
    public static final int MAX_CONSECUTIVE_FAILURES = 3;

    private final ChatClient chatClient;
    private final int contextWindow;
    private final int bufferTokens;
    private final int maxInputTokens;
    private int consecutiveFailures = 0;

    public StructuredCompactor8(ChatClient chatClient) {
        this(chatClient, DEFAULT_CONTEXT_WINDOW, DEFAULT_BUFFER, DEFAULT_MAX_INPUT_TOKENS);
    }

    public StructuredCompactor8(ChatClient chatClient, int contextWindow,
                                int bufferTokens, int maxInputTokens) {
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
            Message summaryMsg = new Message(
                    "summary8-" + System.currentTimeMillis(),
                    Role.ASSISTANT,
                    List.of(new ContentBlock.TextBlock(summary)),
                    null,
                    java.util.Map.of("kind", "structured-summary-8"));
            return List.of(summaryMsg);
        } catch (Exception e) {
            consecutiveFailures++;
            LOG.warn("R136.5 8-section compact failed (consecutive={}): {}",
                    consecutiveFailures, e.getMessage());
            return null;
        }
    }

    String summarise(List<Message> messages) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a context-compaction assistant. Produce a Markdown summary ");
        prompt.append("of the conversation below in EXACTLY these 8 sections, in this order, ");
        prompt.append("with these headings (use the exact '## ' prefix shown):\n\n");
        prompt.append("## Goal\n");
        prompt.append("(one sentence — the original user request)\n\n");
        prompt.append("## Progress\n");
        prompt.append("(bullet list — what was done, with the key results / findings)\n\n");
        prompt.append("## Active Constraints\n");
        prompt.append("(bullet list — environmental, API, permission, budget, or detector ");
        prompt.append("constraints the model must respect to keep working. Include: ");
        prompt.append("active detector mode, auto-approve state, max output tokens, ");
        prompt.append("denied commands, rate limits, current todo state.)\n\n");
        prompt.append("## Decisions\n");
        prompt.append("(bullet list — non-obvious choices the model made and why)\n\n");
        prompt.append("## Files Touched\n");
        prompt.append("(bullet list of file paths — read, written, edited; ");
        prompt.append("one-line description of the change per file)\n\n");
        prompt.append("## Open Questions\n");
        prompt.append("(bullet list — things still uncertain or blocked)\n\n");
        prompt.append("## Current State\n");
        prompt.append("(1-2 sentences — where execution is right now)\n\n");
        prompt.append("## Next Steps\n");
        prompt.append("(bullet list — what the model plans to do next, in order)\n\n");
        prompt.append("Keep each section short but complete. Preserve: explicit user ");
        prompt.append("preferences, constraints, Active Constraints, and concrete file ");
        prompt.append("paths / function names / values that would be expensive to re-derive. ");
        prompt.append("Drop: verbose tool output, repeated error text, anything that can be ");
        prompt.append("re-read from the file system.\n\n");
        prompt.append("Conversation:\n");
        int budget = maxInputTokens * 4;
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
        String systemPrompt = "You are a context-compaction assistant. Produce only the 8-section Markdown summary, no preamble or explanation.";
        List<Message> msgs = new ArrayList<>();
        msgs.add(Message.userText(prompt.toString()));
        StringBuilder out = new StringBuilder();
        Stream<StreamEvent> stream = chatClient.stream(msgs, systemPrompt, List.of());
        for (java.util.Iterator<StreamEvent> it = stream.iterator(); it.hasNext(); ) {
            StreamEvent ev = it.next();
            if (ev instanceof StreamEvent.TextDelta td) {
                out.append(td.text());
            } else if (ev instanceof StreamEvent.RunEnd re) {
                if (re.finalBlocks() != null) {
                    for (ContentBlock b : re.finalBlocks()) {
                        if (b instanceof ContentBlock.TextBlock t && out.length() == 0) {
                            out.append(t.text());
                        }
                    }
                }
                break;
            }
        }
        if (out.length() == 0) {
            throw new IllegalStateException("8-section compactor chat client returned no text");
        }
        return out.toString();
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
        return sb.toString().replace('\n', ' ').replace('\r', ' ');
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
