package org.aethercode.compact;

import org.aethercode.core.compact.Compactor;
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * chain-aware compactor for 10-hour long task execution.
 * The legacy compactor ({@link AutoCompact}) summarises the
 * entire transcript into a single message. As a 10-hour run
 * progresses, that one summary grows unbounded, eventually
 * itself exceeding the context window — at which point a new
 * compaction would discard the OLD summary and start over
 * (lossy). This class instead maintains a CHAIN of summaries
 * (one per compaction pass) plus a sliding window of recent
 * verbatim messages.
 *
 * <p>On each {@link #compact} call, the compactor:
 * <ol>
 *   <li>Takes the head of the transcript (everything except the
 *       last {@link #keepRecent} messages, which are preserved
 *       verbatim for short-term context).</li>
 *   <li>Asks the inner summariser (typically {@link AutoCompact})
 *       to summarise that head into a single block.</li>
 *   <li>Appends the new summary to the chain.</li>
 *   <li>Returns: {@code [summary1, summary2, ..., summaryN,
 *       recentK1, ..., recentKM]} — the engine installs this as
 *       the new transcript.</li>
 * </ol>
 *
 * <p>After many compaction passes, the transcript is bounded by:
 * <pre>
 *   chain.length * summarySize + keepRecent * avgMessageSize
 * </pre>
 *
 * <p>which is roughly constant per compaction cycle, NOT a
 * function of total runtime.
 *
 * <p>State: the chain is held in this object (instance state).
 * Callers should reuse the same compactor across queries so the
 * chain persists. Per-session or per-task, construct a new
 * compactor.
 */
public final class SlidingWindowCompactor implements Compactor {

    private static final Logger LOG = LoggerFactory.getLogger(SlidingWindowCompactor.class);

    /** how many recent messages to keep verbatim. Default
     *  10 — small enough that the verbatim tail doesn't blow the
     *  context, large enough that the model sees its last few
     *  tool calls and answers without needing a recap. */
    public static final int DEFAULT_KEEP_RECENT = 10;

    private final Compactor summariser;
    private final int contextWindow;
    private final int bufferTokens;
    private final int keepRecent;
    /** chain of summary messages, oldest first. */
    private final List<Message> chain = new ArrayList<>();
    /** total chars summarised so far (cumulative). For
     *  diagnostics and the TUI's "compacted 12K → 1.4K" line. */
    private long totalSummarisedChars = 0L;
    private int compactionCount = 0;

    /** build a compactor that delegates summarisation to
     *  {@code inner}, keeps the last {@link #DEFAULT_KEEP_RECENT}
     *  messages verbatim, and triggers when the transcript
     *  exceeds {@code contextWindow - bufferTokens} chars. */
    public SlidingWindowCompactor(Compactor summariser, int contextWindow, int bufferTokens) {
        this(summariser, contextWindow, bufferTokens, DEFAULT_KEEP_RECENT);
    }

    public SlidingWindowCompactor(Compactor summariser, int contextWindow, int bufferTokens, int keepRecent) {
        if (summariser == null) throw new IllegalArgumentException("summariser");
        this.summariser = summariser;
        this.contextWindow = contextWindow;
        this.bufferTokens = bufferTokens;
        this.keepRecent = Math.max(0, keepRecent);
    }

    @Override
    public boolean shouldCompact(List<Message> messages) {
        if (messages == null) return false;
        if (messages.size() <= keepRecent) return false;
        // Estimate the transcript size and compare against the
        // context window budget. Chars/4 is the same heuristic
        // AutoCompact uses.
        long chars = estimateChars(messages);
        long tokens = chars / 4;
        return tokens > (contextWindow - bufferTokens);
    }

    @Override
    public List<Message> compact(List<Message> messages) {
        if (messages == null || messages.isEmpty()) return null;
        if (messages.size() <= keepRecent) return null;
        if (!shouldCompact(messages)) return null;
        // Slice: head = everything except the last keepRecent;
        // tail = the last keepRecent messages, preserved verbatim.
        int splitAt = messages.size() - keepRecent;
        List<Message> head = messages.subList(0, splitAt);
        List<Message> tail = messages.subList(splitAt, messages.size());
        long headChars = estimateChars(head);
        // Ask the inner summariser to summarise the head. The
        // summariser returns a List<Message> we can prepend to
        // the chain.
        List<Message> newSummary;
        try {
            newSummary = summariser.compact(new ArrayList<>(head));
        } catch (Exception e) {
            LOG.warn("summariser threw: {}", e.getMessage());
            return null;
        }
        if (newSummary == null || newSummary.isEmpty()) {
            LOG.debug("summariser returned no summary, skipping");
            return null;
        }
        chain.addAll(newSummary);
        compactionCount++;
        totalSummarisedChars += headChars;
        // Build the new transcript: chain + tail.
        List<Message> out = new ArrayList<>(chain.size() + tail.size());
        out.addAll(chain);
        out.addAll(tail);
        LOG.info("对应历史 round compacted: {} messages ({} chars) → {} summary blocks + {} recent",
                messages.size(), headChars, chain.size(), tail.size());
        return out;
    }

    /** reset the chain (e.g. for a new session). */
    public void reset() {
        chain.clear();
        totalSummarisedChars = 0L;
        compactionCount = 0;
    }

    public int chainSize() { return chain.size(); }
    public int compactionCount() { return compactionCount; }
    public long totalSummarisedChars() { return totalSummarisedChars; }
    public int keepRecent() { return keepRecent; }

    /** expose the chain for diagnostics. The returned list
     *  is a copy. */
    public List<Message> chainSnapshot() {
        return new ArrayList<>(chain);
    }

    static long estimateChars(List<Message> messages) {
        long total = 0;
        for (Message m : messages) {
            for (ContentBlock b : m.content()) {
                if (b instanceof ContentBlock.TextBlock t) total += t.text().length();
                else if (b instanceof ContentBlock.ToolResultBlock r) {
                    if (r.content() instanceof String s) total += s.length();
                }
            }
        }
        return total;
    }
}
