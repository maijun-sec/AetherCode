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
 * <p>Token budget (R136.4 + R718-G):
 * <ul>
 *   <li>Default context window: <b>200_000</b> (R718-G: lowered
 *       from 1_000_000 per user directive "最优的推理肯定不是
 *       100w 的上下文，而是 50w 左右的样子". 1M is a marketing
 *       cap; the real attention budget is ~1/5 of that. Even
 *       though the underlying provider declares 1M, pushing the
 *       transcript near the cap dilutes the model's attention
 *       and causes Lost-in-the-Middle noise.)</li>
 *   <li>Default buffer: <b>32_000</b> (R718-G: lowered from 64K
 *       so compaction fires earlier; the user's "压缩可以更
 *       频繁一点儿" directive).</li>
 *   <li>Default max input tokens: <b>160_000</b> (R718-G:
 *       lowered from 900K; the summarise wire call should
 *       itself fit inside the 200K cap, so the model's
 *       output gets ~40K of headroom).</li>
 * </ul>
 *
 * <p>R718 additions on top of the R136.5 design:
 * <ol>
 *   <li><b>PostCompact re-injection</b> — when a
 *       {@code postCompactContextSupplier} is wired, a
 *       second message carrying cwd / recent files /
 *       git status is spliced right after the summary so
 *       the next turn re-orients without re-discovering
 *       the working directory.</li>
 *   <li><b>Cheaper-model summarisation</b> — when a
 *       dedicated {@code summariserChatClient} is wired,
 *       the LLM summarise call goes through it instead
 *       of the main chat client. Engine resolves this
 *       from {@code AETHERCODE_R718_COMPACT_MODEL}.</li>
 *   <li><b>Metrics</b> — optional
 *       {@code MetricsCollector} wires L5 telemetry
 *       (compact count + last input tokens).</li>
 * </ol>
 */
public final class StructuredCompactor8 implements Compactor {

    private static final Logger LOG = LoggerFactory.getLogger(StructuredCompactor8.class);

    /** R718-G: effective cap = 200K (the user's chosen
     *  sweet spot, not the declared 1M marketing cap).
     *  Pushing the window to its declared max just
     *  dilutes attention. */
    public static final int DEFAULT_CONTEXT_WINDOW = 200_000;
    /** R719: buffer 80K (was R718's 32K). Smaller buffer
     *  means the trigger fires earlier — "more frequent
     *  compression". With buffer=80K the trigger fires at
     *  200K - 80K = 120K tokens, which roughly matches
     *  AutoCompact's compactAt=0.4 (200K * 0.4 = 80K tokens
     *  via the wire-call gate, which is the same effective
     *  frequency once you back out the two pipelines'
     *  different token estimators). Per the R718 3-day RAG
     *  build telemetry: L5 fired 0 times in 26 turns because
     *  the trigger band was 168K tokens and the post-L4
     *  transcript always stayed under it. With buffer=80K
     *  L5 fires at 120K tokens, 2-3x more often, emitting a
     *  fresh PostCompact re-injection (cwd / recent files /
     *  git / intent) on each cycle. The R719 Session Anchor
     *  is the primary fix; this is the secondary "earlier L5"
     *  fix. */
    public static final int DEFAULT_BUFFER = 80_000;
    /** R718-G: max input 160K (was 900K). The summarise
     *  wire call should itself fit inside the 200K cap. */
    public static final int DEFAULT_MAX_INPUT_TOKENS = 160_000;
    /** Circuit breaker: 3 consecutive failures stops compacting. */
    public static final int MAX_CONSECUTIVE_FAILURES = 3;

    private final ChatClient chatClient;
    /** R718-E: optional cheaper-model ChatClient for the
     *  summarise wire call. */
    private final ChatClient summariserChatClient;
    /** R718-C: optional cwd / files / git supplier for
     *  post-compact re-injection. */
    private final java.util.function.Supplier<AutoCompact.PostCompactContext> postCompactContextSupplier;
    /** R718-H: optional metrics collector for L5
     *  telemetry. */
    private org.aethercode.core.metrics.MetricsCollector metricsCollector;
    private final int contextWindow;
    private final int bufferTokens;
    private final int maxInputTokens;
    private int consecutiveFailures = 0;

    public StructuredCompactor8(ChatClient chatClient) {
        this(chatClient, DEFAULT_CONTEXT_WINDOW, DEFAULT_BUFFER, DEFAULT_MAX_INPUT_TOKENS);
    }

    public StructuredCompactor8(ChatClient chatClient, int contextWindow,
                                int bufferTokens, int maxInputTokens) {
        this(chatClient, null, contextWindow, bufferTokens, maxInputTokens, null);
    }

    /** R718 full-arg constructor. */
    public StructuredCompactor8(ChatClient chatClient,
                                ChatClient summariserChatClient,
                                int contextWindow,
                                int bufferTokens,
                                int maxInputTokens,
                                java.util.function.Supplier<AutoCompact.PostCompactContext> postCompactContextSupplier) {
        if (chatClient == null) throw new IllegalArgumentException("chatClient");
        this.chatClient = chatClient;
        this.summariserChatClient = summariserChatClient;
        this.contextWindow = contextWindow;
        this.bufferTokens = bufferTokens;
        this.maxInputTokens = maxInputTokens;
        this.postCompactContextSupplier = postCompactContextSupplier;
    }

    /** R718-H: optional metrics collector. The engine wires
     *  this from AetherCodeEngine; tests can leave null. */
    public void withMetricsCollector(org.aethercode.core.metrics.MetricsCollector mc) {
        this.metricsCollector = mc;
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
            // R718-C: post-compact re-injection message
            // (cwd / recent files / git status). When the
            // supplier is wired, append after the summary so
            // the model sees both blocks together next turn.
            Message postCompact = null;
            if (postCompactContextSupplier != null) {
                AutoCompact.PostCompactContext ctx =
                        postCompactContextSupplier.get();
                if (ctx != null) postCompact = ctx.toMessage();
            }
            // R718-H: bump the L5 metric with the
            // summary's input-token estimate (length/4).
            if (metricsCollector != null) {
                long inputTokensEstimate = Math.max(1, summary.length() / 4);
                metricsCollector.incL5Compact(inputTokensEstimate);
            }
            if (postCompact != null) return List.of(summaryMsg, postCompact);
            return List.of(summaryMsg);
        } catch (Exception e) {
            consecutiveFailures++;
            LOG.warn("R718 8-section compact failed (consecutive={}): {}",
                    consecutiveFailures, e.getMessage());
            if (metricsCollector != null) metricsCollector.incCompactFailure();
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
        // R718-E: use the dedicated summariser ChatClient
        // when configured (cheaper model), falling back to
        // the main chat client otherwise.
        ChatClient summariser = summariserChatClient != null
                ? summariserChatClient : chatClient;
        Stream<StreamEvent> stream = summariser.stream(msgs, systemPrompt, List.of());
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
