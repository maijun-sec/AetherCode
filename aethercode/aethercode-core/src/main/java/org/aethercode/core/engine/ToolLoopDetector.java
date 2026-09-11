package org.aethercode.core.engine;

import org.aethercode.core.message.ContentBlock;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * a small sliding-window detector that catches tool-call
 * loops before the per-query turn cap does. The agent calls
 * {@link #recordBatch(List)} after each model turn's tool batch
 * is collected. When the same tool-call fingerprint appears
 * {@code threshold} times in the last {@code window} batches, the
 * detector returns the offending fingerprint (or {@code null} if
 * no loop).
 *
 * <p>Why both this AND a turn cap? The cap ({@code maxTurnsPerQuery})
 * protects against runaway costs but is coarse — a 50-turn real
 * workflow should not be cut short, but a 12-turn tight loop should
 * be stopped at turn 6. The loop detector gives us the second
 * signal: the model is repeating the same call(s) with no progress.
 *
 * <p>What counts as the same fingerprint? The tool name plus a
 * canonical rendering of the input map (keys sorted, values
 * stringified). Two calls with the same name but different inputs
 * are different fingerprints.
 *
 * <p>The window tracks BATCH-level fingerprints, not call-level.
 * A batch of 3 same-tool calls increments the count by 1 (the batch
 * is a single attempt). Use this with a window of 6-8 and threshold
 * of 3-4 to catch "I'm going to keep trying the same thing" loops
 * within a few turns while still allowing real iteration (e.g.
 * "compile, fix, compile, fix" until success).
 */
public final class ToolLoopDetector {

    private final int window;
    private final int threshold;
    /** Per-batch fingerprints in arrival order. Capped at {@code window}. */
    private final Deque<String> history = new ArrayDeque<>();
    /** Counts of each fingerprint currently in the window. */
    private final Map<String, Integer> counts = new HashMap<>();

    public ToolLoopDetector(int window, int threshold) {
        if (window < 1) {
            throw new IllegalArgumentException("window must be >= 1, got " + window);
        }
        if (threshold < 1) {
            throw new IllegalArgumentException("threshold must be >= 1, got " + threshold);
        }
        if (threshold > window) {
            // Misconfiguration: a fingerprint can never reach threshold.
            // Clamp to window — the user clearly wants the smallest possible
            // match. We don't throw because the most common case is someone
            // tweaking the defaults without realising the relationship.
            threshold = window;
        }
        this.window = window;
        this.threshold = threshold;
    }

    public int window() { return window; }
    public int threshold() { return threshold; }
    public int currentWindowSize() { return history.size(); }

    /**
     * Build the canonical fingerprint for a tool call. {@code name} plus
     * the input map with keys sorted and values stringified. Exposed
     * static for tests + reuse.
     */
    public static String fingerprint(ContentBlock.ToolUseBlock b) {
        if (b == null) return "<null>";
        StringBuilder sb = new StringBuilder(b.name());
        sb.append('|');
        Map<String, Object> input = b.input();
        if (input != null && !input.isEmpty()) {
            input.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> sb.append(e.getKey()).append('=')
                            .append(String.valueOf(e.getValue())).append(';'));
        }
        return sb.toString();
    }

    /**
     * Record a tool batch (one model turn's tool calls). Returns the
     * fingerprint that triggered the loop, or {@code null} if this
     * batch is healthy.
     *
     * <p>If a batch contains multiple different tool calls, the most
     * frequent fingerprint in the batch is what gets recorded. Ties
     * go to whichever appears first.
     */
    public String recordBatch(List<ContentBlock.ToolUseBlock> batch) {
        if (batch == null || batch.isEmpty()) return null;
        Map<String, Integer> batchCounts = new HashMap<>();
        for (ContentBlock.ToolUseBlock b : batch) {
            batchCounts.merge(fingerprint(b), 1, Integer::sum);
        }
        // Most frequent in this batch; ties broken by insertion order.
        String hot = null;
        int best = 0;
        for (Map.Entry<String, Integer> e : batchCounts.entrySet()) {
            if (e.getValue() > best) {
                best = e.getValue();
                hot = e.getKey();
            }
        }
        if (hot == null) return null;
        evictIfFull();
        history.addLast(hot);
        counts.merge(hot, 1, Integer::sum);
        if (counts.get(hot) >= threshold) return hot;
        return null;
    }

    private void evictIfFull() {
        while (history.size() >= window) {
            String evicted = history.pollFirst();
            if (evicted == null) break;
            counts.merge(evicted, -1, Integer::sum);
            if (counts.getOrDefault(evicted, 0) <= 0) counts.remove(evicted);
        }
    }

    /** Reset the detector (call at the start of each user query). */
    public void reset() {
        history.clear();
        counts.clear();
    }

    /** Snapshot of the current history for diagnostics / tests. */
    public List<String> recentFingerprints() {
        return List.copyOf(history);
    }
}
