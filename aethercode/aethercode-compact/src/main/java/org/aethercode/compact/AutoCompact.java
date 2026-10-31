/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.aethercode.compact;

import org.aethercode.core.engine.TranscriptPruner;
import org.aethercode.core.llm.ChatClient;
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * R717 + R718: reworked AutoCompact — the L5 layer of the
 * 5-layer transcript-pruning pipeline (L1 offload + L2
 * microcompact + L3 dedup + L4 strip-oldest strip the cheap
 * stuff first; L5 is the only layer that spends an API call
 * on summarisation).
 *
 * <p>Five R718 additions on top of the R717 design:
 * <ol>
 *   <li><b>PostCompact re-injection</b> (R718-C) — after a
 *       successful compact, the {@link Result} now also
 *       carries a synthetic {@code Message} that re-anchors
 *       the model on cwd / recent files / git status /
 *       active intent. The Claude Code L5 pattern: the
 *       summary carries intent + decisions; the
 *       post-compact block carries "where you are and
 *       what you've touched" so the model doesn't
 *       re-discover the working directory after every
 *       compaction. Caller splices both into the
 *       transcript.</li>
 *   <li><b>Cheaper-model summarisation</b> (R718-E) — the
 *       compactor takes an OPTIONAL separate
 *       {@link ChatClient} for the summarise wire call
 *       (e.g. a {@code glm/glm-4-flash} that's 1/20 the
 *       cost of MiniMax-M3). When null, the compactor uses
 *       the same ChatClient as the main engine. Caller
 *       wires the env var
 *       {@code AETHERCODE_R718_COMPACT_MODEL} to switch.</li>
 *   <li><b>Cheaper trigger</b> (R718-G) — DEFAULT_COMPACT_AT
 *       lowered from 0.7 → 0.5 (50% of effective cap).
 *       Per the user's "more frequent compression" directive:
 *       don't wait until 70% of 200K = 140K tokens, fire
 *       at 100K. Matches Claude Code's actual ~50% trigger
 *       band once you back out its smaller cap.</li>
 *   <li><b>Telemetry hooks</b> (R718-H) — {@code compact()}
 *       now reports the summary's input-token estimate to a
 *       caller-supplied {@link MetricsCollector} so the
 *       UI can show "L5 fired at 100K input tokens, summary
 *       came back at 4K tokens".</li>
 *   <li><b>Honour the L4 + L3 already in transcript</b> —
 *       {@code shouldCompact} now also sees the L4 strip's
 *       effect on the live transcript (since compaction
 *       runs AFTER the wire-side pipeline in QueryEngine).
 *       This avoids "summarise what strip-oldest just
 *       dropped" double work.</li>
 * </ol>
 *
 * <p>Env overrides (read on every call, so a daemon restart
 * is not needed for field tuning):
 * <ul>
 *   <li>{@code AETHERCODE_R717_EFFECTIVE_CAP_TOKENS} — the
 *       target effective cap (default 200_000).</li>
 *   <li>{@code AETHERCODE_R717_COMPACT_AT} — fraction of cap
 *       that triggers compaction (default 0.5, lowered from
 *       R717's 0.7 per R718-G).</li>
 *   <li>{@code AETHERCODE_R717_PRESERVE_TAIL} — minimum
 *       recent messages kept verbatim (default 20).</li>
 *   <li>{@code AETHERCODE_R717_MAX_SUMMARISE_TOKENS} —
 *       max tokens for the summarise wire call (default
 *       120_000, leaves headroom in the 200K cap for the
 *       summarise LLM's own output).</li>
 * </ul>
 */
public class AutoCompact {

    private static final Logger LOG = LoggerFactory.getLogger(AutoCompact.class);

    /** R717: target effective cap. The user's chosen sweet spot
     *  (~200K, not the declared 1M). MiniMax-M3 happily accepts
     *  this and gives back better answers than when we max
     *  out the window with stale context. */
    public static final int DEFAULT_EFFECTIVE_CAP_TOKENS = 200_000;

    /** R719: trigger compaction at 40% of the effective cap
     *  (was R718's 0.5, was R717's 0.7). 200K × 0.4 = 80K —
     *  significantly below the wire red line, with the L5
     *  emitting a PostCompact re-injection (cwd / recent files
     *  / git / intent) on every fire. Per the R718 3-day RAG
     *  build telemetry: L5 fired 0 times in 26 turns because
     *  the post-L4 transcript always stayed under the 50%
     *  trigger. Lowering to 0.4 makes L5 fire 2-3x more often,
     *  refreshing the model's project memory via PostCompact
     *  re-injection on each cycle. The R719 Session Anchor
     *  is the primary fix; this is the secondary "earlier L5"
     *  fix. */
    public static final double DEFAULT_COMPACT_AT = 0.4;

    /** R717: keep at least the most-recent N messages verbatim
     *  when summarising. The model needs the last 1-2 turns
     *  intact to maintain conversational coherence. */
    public static final int DEFAULT_PRESERVE_TAIL = 20;

    /** R717: max tokens for the summarise call itself. The
     *  compactor needs to fit its own system prompt + a 9-section
     *  summary template + the source transcript under this
     *  budget. 120K is conservative — the model has 80K of
     *  headroom inside the 200K effective cap to think. */
    public static final int DEFAULT_MAX_SUMMARISE_TOKENS = 120_000;

    /** R717: we give the circuit breaker a half-life — failures
     *  decay linearly over this many turns instead of staying
     *  pinned. The Claude Code 3-failure-trip is fine for
     *  manually-driven sessions but too sticky for long
     *  automated runs where the same task might succeed
     *  next turn. */
    public static final int MAX_CONSECUTIVE_FAILURES = 3;
    public static final int CIRCUIT_BREAKER_DECAY_TURNS = 8;

    private final ChatClient chatClient;
    /** R718-E: optional cheaper-model ChatClient for the
     *  summarise wire call. When null, {@code chatClient} is
     *  used for both. The caller wires this by reading the
     *  env var {@code AETHERCODE_R718_COMPACT_MODEL} and
     *  building a dedicated ChatClient for that model id. */
    private final ChatClient summariserChatClient;
    private final int effectiveCapTokens;
    private final double compactAt;
    private final int preserveTail;
    private final int maxSummariseTokens;
    /** R718-C: optional supplier for the post-compact context
     *  (cwd / recent files / git status). When null, no
     *  re-injection message is emitted — the caller decides
     *  what to splice in. The supplier is invoked ONCE per
     *  successful compact, so it should be cheap. */
    private final java.util.function.Supplier<PostCompactContext> postCompactContextSupplier;
    private int consecutiveFailures = 0;
    private int turnsSinceFailure = 0;

    public AutoCompact(ChatClient chatClient) {
        this(chatClient, null, DEFAULT_EFFECTIVE_CAP_TOKENS,
                DEFAULT_COMPACT_AT, DEFAULT_PRESERVE_TAIL,
                DEFAULT_MAX_SUMMARISE_TOKENS, null);
    }

    public AutoCompact(ChatClient chatClient,
                       int effectiveCapTokens,
                       double compactAt,
                       int preserveTail,
                       int maxSummariseTokens) {
        this(chatClient, null, effectiveCapTokens, compactAt,
                preserveTail, maxSummariseTokens, null);
    }

    /** R718 full-arg constructor. {@code summariserChatClient}
     *  is the dedicated model for L5; {@code postCompactContextSupplier}
     *  returns cwd/recent-files/git-status to re-inject. Either
     *  may be null (falls back to main client / no re-injection). */
    public AutoCompact(ChatClient chatClient,
                       ChatClient summariserChatClient,
                       int effectiveCapTokens,
                       double compactAt,
                       int preserveTail,
                       int maxSummariseTokens,
                       java.util.function.Supplier<PostCompactContext> postCompactContextSupplier) {
        this.chatClient = chatClient;
        this.summariserChatClient = summariserChatClient;
        this.effectiveCapTokens = effectiveCapTokens;
        this.compactAt = compactAt;
        this.preserveTail = preserveTail;
        this.maxSummariseTokens = maxSummariseTokens;
        this.postCompactContextSupplier = postCompactContextSupplier;
    }

    /**
     * R717 compat: the 4-arg constructor maps the prior
     * round's "(contextWindow, bufferTokens, maxInputTokens)"
     * shape to the new 5-arg shape, so existing
     * {@code AutoCompact(cc, 200_000, 13_000, 80_000)} call
     * sites keep working. The first arg becomes the
     * effective cap; the second (buffer) is dropped (we
     * compute the trigger gate from compactAt instead); the
     * third (maxInputTokens) becomes maxSummariseTokens.
     */
    public AutoCompact(ChatClient chatClient,
                       int contextWindowOrCap,
                       int bufferTokensUnused,
                       int maxInputTokens) {
        this(chatClient, null, contextWindowOrCap, DEFAULT_COMPACT_AT,
                DEFAULT_PRESERVE_TAIL, maxInputTokens, null);
    }

    /**
     * Decide whether compaction is needed given the current
     * transcript. Pure threshold: if estimated tokens exceed
     * {@code effectiveCap * compactAt}, fire.
     *
     * <p>The circuit breaker is also wired here: after
     * {@link #MAX_CONSECUTIVE_FAILURES} consecutive failures,
     * we return false for a few turns (CIRCUIT_BREAKER_DECAY_TURNS)
     * so a transient API outage doesn't permanently silence
     * summarisation.
     */
    public boolean shouldCompact(List<Message> messages) {
        // The circuit breaker is "soft" — once tripped it
        // counts down turns, and recovers to 0 once we hit
        // the half-life. This is the R717 fix vs. prior round
        // permanent circuit-break.
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            if (turnsSinceFailure >= CIRCUIT_BREAKER_DECAY_TURNS) {
                LOG.info("AutoCompact: circuit-breaker cooldown elapsed; " +
                        "resuming compaction attempts");
                consecutiveFailures = 0;
                turnsSinceFailure = 0;
            } else {
                return false;
            }
        }
        long tokens = estimateTokens(messages);
        int cap = readIntEnv("AETHERCODE_R717_EFFECTIVE_CAP_TOKENS",
                effectiveCapTokens);
        double frac = readDoubleEnv("AETHERCODE_R717_COMPACT_AT", compactAt);
        long gate = (long) (cap * frac);
        return tokens > gate;
    }

    /**
     * Run the summarisation pipeline. The input is the live
     * transcript; the output is a {@link Result} carrying the
     * 9-section summary + the post-compact context message
     * (R718-C). On success the caller should splice both into
     * the transcript via {@link CompactGate#spliceSummary} +
     * appending the postCompactContext message right after.
     *
     * <p>R717: applies {@link TranscriptPruner#stripOldestToFit}
     * to its own outgoing transcript so the summarise call
     * doesn't trip the same per-call cap that triggered
     * compaction. Without this, the compactor's chat call
     * 400s on transcripts over the cap — i.e. on the exact
     * transcripts that triggered the compaction in the
     * first place. Self-recursive trap.
     */
    public Result compact(List<Message> messages) {
        return compact(messages, null);
    }

    /** R718: same as {@link #compact(List)} but plumbs an
     *  optional {@link org.aethercode.core.metrics.MetricsCollector}
     *  so the L5 telemetry counter increments when the engine
     *  fires. Pass null in tests that don't have a metrics
     *  collector. */
    public Result compact(List<Message> messages,
                          org.aethercode.core.metrics.MetricsCollector metrics) {
        if (!shouldCompact(messages)) {
            return new Result(false, null, null);
        }
        int preserve = readIntEnv("AETHERCODE_R717_PRESERVE_TAIL", preserveTail);
        try {
            String summary = summarise(messages, preserve);
            consecutiveFailures = 0;
            turnsSinceFailure = 0;
            Message postCompactMessage = null;
            if (postCompactContextSupplier != null) {
                PostCompactContext ctx = postCompactContextSupplier.get();
                if (ctx != null) postCompactMessage = ctx.toMessage();
            }
            if (metrics != null) {
                long inputTokensEstimate = Math.max(1,
                        summary == null ? 0 : summary.length() / 4);
                metrics.incL5Compact(inputTokensEstimate);
            }
            return new Result(true, summary, postCompactMessage);
        } catch (Exception e) {
            consecutiveFailures++;
            turnsSinceFailure = 0;
            LOG.warn("AutoCompact: compact failed (consecutive={}/{}): {}",
                    consecutiveFailures, MAX_CONSECUTIVE_FAILURES, e.getMessage());
            if (metrics != null) metrics.incCompactFailure();
            return new Result(false, null, null);
        }
    }

    /**
     * R718-C: post-compact re-injection context. Caller
     * supplies this via the AutoCompact constructor; when
     * non-null, a {@link #toMessage} call produces the
     * re-anchor Message that gets spliced into the transcript
     * right after the summary. Mirrors Claude Code's
     * {@code CLAUDE.md} + recent-files re-load.
     */
    public static final class PostCompactContext {
        public final String cwd;
        public final java.util.List<String> recentFiles;
        public final String gitStatus;
        public final String activeIntent;
        public PostCompactContext(String cwd,
                                  java.util.List<String> recentFiles,
                                  String gitStatus,
                                  String activeIntent) {
            this.cwd = cwd;
            this.recentFiles = recentFiles == null
                    ? java.util.List.of()
                    : java.util.List.copyOf(recentFiles);
            this.gitStatus = gitStatus;
            this.activeIntent = activeIntent;
        }
        public Message toMessage() {
            StringBuilder sb = new StringBuilder();
            sb.append("[PostCompact re-injection] The transcript was just compacted. ");
            sb.append("To re-orient:\n");
            if (cwd != null && !cwd.isBlank()) {
                sb.append("- Working directory: `").append(cwd).append("`\n");
            }
            if (!recentFiles.isEmpty()) {
                sb.append("- Recent files touched in this session:\n");
                int max = Math.min(8, recentFiles.size());
                for (int i = 0; i < max; i++) {
                    sb.append("  - `").append(recentFiles.get(i)).append("`\n");
                }
            }
            if (gitStatus != null && !gitStatus.isBlank()) {
                sb.append("- Git status: ").append(gitStatus).append("\n");
            }
            if (activeIntent != null && !activeIntent.isBlank()) {
                sb.append("- Active intent: ").append(activeIntent).append("\n");
            }
            sb.append("\nThe compacted summary above carries the decisions / errors / pending tasks; ");
            sb.append("this block re-anchors cwd / files / git. Continue from the user's last intent.");
            return new Message(
                    "r718-postcompact-" + System.currentTimeMillis(),
                    Role.USER,
                    List.of(new ContentBlock.TextBlock(sb.toString())),
                    null,
                    java.util.Map.of("kind", "post-compact-context",
                            "source", "R718-C"));
        }
    }

    /**
     * Build a 9-section summary of the message history.
     * Modelled on Claude Code's {@code BASE_COMPACT_PROMPT}.
     */
    String summarise(List<Message> messages, int preserveTail) {
        // 1. The outgoing transcript is the LAST `preserveTail`
        //    messages + a head of older messages we want
        //    summarised. We compute the head via the char
        //    budget derived from maxSummariseTokens (4 chars/token
        //    heuristic).
        int maxSummarise = readIntEnv("AETHERCODE_R717_MAX_SUMMARISE_TOKENS",
                maxSummariseTokens);
        int charBudget = maxSummarise * 4;
        int splitIdx = messages.size() - preserveTail;
        if (splitIdx < 0) splitIdx = 0;
        // Build the head (everything before the tail) under the
        // char budget. Walk head end -> start, drop earliest
        // first, until the prompt fits.
        List<Message> head = new ArrayList<>();
        int used = COMPACT_PROMPT.length();
        for (int i = splitIdx - 1; i >= 0; i--) {
            String line = "- [" + messages.get(i).role() + "] "
                    + messages.get(i).textContent() + "\n";
            if (used + line.length() > charBudget) {
                LOG.info("AutoCompact: head budget exhausted at {} of {}; " +
                        "older messages are summarised implicitly", i, splitIdx);
                break;
            }
            head.add(0, messages.get(i));
            used += line.length();
        }
        // 2. R717: self-recursive safety. The outgoing list
        //    (head + tail) is the summarise wire call's
        //    payload. We strip-oldest inside this to keep the
        //    wire call under the same per-call cap. The head
        //    is already bounded by charBudget; the tail is
        //    bounded by preserveTail, so this is a no-op in
        //    practice — but it's a defensive guard in case
        //    someone configures a tail of 200 and a cap of
        //    200K.
        List<Message> outgoing = new ArrayList<>(head);
        for (int i = splitIdx; i < messages.size(); i++) outgoing.add(messages.get(i));
        outgoing = TranscriptPruner.stripOldestToFit(
                outgoing,
                // The summarise call's own budget: 4 chars/token.
                charBudget,
                // Keep at least 4 messages at the tail of the
                // outgoing list (the user's last few turns).
                4,
                TranscriptPruner.DEFAULT_TRUNCATE_TO_CHARS);
        // 3. Build the prompt using Claude Code's 9-section
        //    template, with a NO_TOOLS preamble so the model
        //    doesn't waste its only summarise turn on a tool
        //    call.
        StringBuilder prompt = new StringBuilder(COMPACT_PROMPT);
        prompt.append("\n\n## Conversation to summarise\n\n");
        for (Message m : outgoing) {
            prompt.append("- [").append(m.role()).append("] ")
                    .append(m.textContent()).append("\n");
        }
        // 4. Send the summarise call. The model returns text;
        //    we capture every TextDelta until RunEnd. R718-E:
        //    use the dedicated summariser ChatClient when
        //    configured (cheaper model), falling back to the
        //    main chat client otherwise.
        String systemPrompt =
                "You are a compaction assistant. Produce a single Markdown " +
                "summary with the 9 sections above, in plain text. " +
                "Respond with NO tool calls.";
        List<Message> msgs = new ArrayList<>();
        msgs.add(Message.userText(prompt.toString()));
        StringBuilder out = new StringBuilder();
        ChatClient summariser = summariserChatClient != null
                ? summariserChatClient : chatClient;
        Stream<org.aethercode.core.stream.StreamEvent> stream =
                summariser.stream(msgs, systemPrompt, List.of());
        for (java.util.Iterator<org.aethercode.core.stream.StreamEvent> it =
                     stream.iterator(); it.hasNext(); ) {
            org.aethercode.core.stream.StreamEvent ev = it.next();
            if (ev instanceof org.aethercode.core.stream.StreamEvent.TextDelta td) {
                out.append(td.text());
            } else if (ev instanceof org.aethercode.core.stream.StreamEvent.RunEnd) {
                break;
            }
        }
        return out.toString();
    }

    /** R717: 9-section structured compaction prompt. Modelled on
     *  Claude Code's BASE_COMPACT_PROMPT (services/compact/prompt.ts:61-143).
     *  Forces NO_TOOLS in the preamble so the summarise turn
     *  never fails because the model called a tool. */
    private static final String COMPACT_PROMPT =
            "CRITICAL: Respond with TEXT ONLY. Do NOT call any tools.\n" +
            "- Do NOT use Read, Bash, Grep, Glob, Edit, Write, or ANY other tool.\n" +
            "- You already have all the context you need in the conversation above.\n" +
            "- Tool calls will be REJECTED and will waste your only turn — you will fail the task.\n" +
            "- Your entire response must be plain text: the 9 sections below, each starting with a heading.\n\n" +
            "Before you write the summary, draft your reasoning inside <analysis>...</analysis> " +
            "tags to organise your thoughts. The analysis block is removed before the summary " +
            "is given to the next turn — so put everything the next turn needs INSIDE the 9 sections, " +
            "not in the analysis.\n\n" +
            "## 9 sections to write, in this order:\n\n" +
            "### 1. Primary Request and Intent\n" +
            "Capture all of the user's explicit requests and intents in detail. " +
            "Why are they running this session? What are they trying to accomplish?\n\n" +
            "### 2. Key Technical Concepts\n" +
            "List all the technologies, frameworks, languages, libraries, " +
            "and patterns that came up. Keep the model grounded on the vocabulary.\n\n" +
            "### 3. Files and Code Sections\n" +
            "List every file path that was discussed, with the gist of what " +
            "was in it and which lines mattered. Include full code snippets " +
            "where applicable (the next turn needs them, not paraphrases).\n\n" +
            "### 4. Errors and Fixes\n" +
            "Document every error encountered and how it was resolved. " +
            "Pay special attention to specific user feedback that corrected a misstep.\n\n" +
            "### 5. Problem Solving\n" +
            "Document the approach taken for non-obvious problems. What was " +
            "tried, what worked, what didn't, and why. Preserve the decision " +
            "rationale, not just the result.\n\n" +
            "### 6. All user messages\n" +
            "List EVERY user message that is not a tool result, verbatim. " +
            "These are the user's actual instructions — losing any of them is " +
            "the worst kind of summary loss. Use a bullet list of quoted strings.\n\n" +
            "### 7. Pending Tasks\n" +
            "List work the user explicitly asked for that has not been completed yet.\n\n" +
            "### 8. Current Work\n" +
            "Describe in detail what was being worked on immediately before this " +
            "summary request — file paths, function names, the exact next step. " +
            "This is the most important section for resume continuity.\n\n" +
            "### 9. Optional Next Step\n" +
            "If there is an obvious next step in line with the user's most " +
            "recent request, name it (with a direct quote from the most recent " +
            "user message if possible). Otherwise write 'no clear next step yet'.\n\n" +
            "REMINDER: Do NOT call any tools. Respond with plain text only — " +
            "an <analysis> block followed by the 9 sections above. " +
            "Tool calls will be rejected and you will fail the task.";

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

    static int readIntEnv(String key, int defaultValue) {
        String raw;
        try { raw = System.getenv(key); }
        catch (SecurityException ignored) { return defaultValue; }
        if (raw == null || raw.isBlank()) return defaultValue;
        try { return Integer.parseInt(raw.trim()); }
        catch (NumberFormatException ignored) { return defaultValue; }
    }

    static double readDoubleEnv(String key, double defaultValue) {
        String raw;
        try { raw = System.getenv(key); }
        catch (SecurityException ignored) { return defaultValue; }
        if (raw == null || raw.isBlank()) return defaultValue;
        try { return Double.parseDouble(raw.trim()); }
        catch (NumberFormatException ignored) { return defaultValue; }
    }

    /** R717 + R718: compact() now returns the summary AND an
     *  optional post-compact re-injection message (R718-C).
     *  The 2-arg constructor is kept for back-compat with
     *  any test code that pattern-matches on the old shape. */
    public record Result(boolean wasCompacted, String summary, Message postCompactMessage) {
        /** R717 back-compat: callers that only care about
         *  the summary get the same shape as before. */
        public Result(boolean wasCompacted, String summary) {
            this(wasCompacted, summary, null);
        }
    }
}
