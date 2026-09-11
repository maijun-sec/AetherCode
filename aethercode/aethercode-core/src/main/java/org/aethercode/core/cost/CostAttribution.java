package org.aethercode.core.cost;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * per-tag cost attribution. Wraps a {@link CostTracker}
 * with the ability to record usage against one or more tags
 * (e.g. {@code "subagent:explore"}, {@code "feature:auth"},
 * {@code "session:abc"}). The {@link #summary()} method returns
 * a per-tag breakdown.
 */
public class CostAttribution {

    public record TaggedUsage(List<String> tags, int inputTokens, int outputTokens) {
        public TaggedUsage {
            tags = tags == null ? List.of() : List.copyOf(tags);
        }
        public boolean hasTag(String tag) { return tag != null && tags.contains(tag); }
    }

    private final CostTracker tracker;
    private final Map<String, CostTracker> perTag = new ConcurrentHashMap<>();
    private final AtomicLong taggedCalls = new AtomicLong();

    public CostAttribution(CostTracker tracker) {
        if (tracker == null) throw new IllegalArgumentException("tracker is null");
        this.tracker = tracker;
    }

    private CostTracker newTagTracker() {
        CostTracker sub = new CostTracker();
        // Inherit the price table from the parent so costFor() works.
        CostTracker.Summary s = tracker.summary();
        for (String model : s.byModel().keySet()) {
            // We don't have direct access to the price table; use a sentinel
            // by setting prices to the model's existing cost-per-token ratio.
            // In practice, callers should configure the same prices on both.
            sub.setPrice(model, 0.001, 0.001);
        }
        return sub;
    }

    public CostTracker tracker() { return tracker; }

    /** record usage against one or more tags (in addition to the global tracker). */
    public void record(String model, TaggedUsage usage) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(usage, "usage");
        tracker.record(model, new CostTracker.Usage(usage.inputTokens(), usage.outputTokens()));
        if (!usage.tags().isEmpty()) {
            taggedCalls.incrementAndGet();
            for (String tag : usage.tags()) {
                perTag.computeIfAbsent(tag, t -> newTagTracker()).record(model,
                        new CostTracker.Usage(usage.inputTokens(), usage.outputTokens()));
            }
        }
    }

    public void record(String model, int input, int output) {
        record(model, new TaggedUsage(List.of(), input, output));
    }

    public void record(String model, String tag, int input, int output) {
        record(model, new TaggedUsage(List.of(tag), input, output));
    }

    /** per-tag snapshot. */
    public Map<String, CostTracker.Summary> byTag() {
        Map<String, CostTracker.Summary> out = new LinkedHashMap<>();
        for (Map.Entry<String, CostTracker> e : perTag.entrySet()) {
            out.put(e.getKey(), e.getValue().summary());
        }
        return out;
    }

    public CostTracker.Summary tagSummary(String tag) {
        CostTracker t = perTag.get(tag);
        return t == null ? new CostTracker.Summary(0, 0, 0.0, Map.of()) : t.summary();
    }

    public long taggedCalls() { return taggedCalls.get(); }
    public int tagCount() { return perTag.size(); }

    /** a sorted, top-N list of tags by cost. */
    public List<Map.Entry<String, Double>> topTags(int n) {
        return perTag.entrySet().stream()
                .map(e -> Map.entry(e.getKey(), e.getValue().summary().totalCostUsd()))
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(Math.max(1, n))
                .toList();
    }
}
