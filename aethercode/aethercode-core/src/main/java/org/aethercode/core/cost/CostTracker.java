package org.aethercode.core.cost;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * token + cost tracker. Modelled on the TS {@code src/cost-tracker.ts}.
 *
 * <p>Two pieces of state, both atomic so concurrent LLM streams can record safely:
 *
 * <ul>
 *   <li>per-model cumulative input / output token counts</li>
 *   <li>per-model cumulative USD cost, derived from a configurable price table</li>
 * </ul>
 *
 * <p>The default price table covers the Anthropic Claude 3 / 3.5 / 4 family; callers
 * can override via {@link #setPrice(String, double, double)}. Prices are in USD per
 * 1,000 tokens (input, output).
 */
public class CostTracker {

    public record Usage(int inputTokens, int outputTokens) {
        public Usage { if (inputTokens < 0) inputTokens = 0; if (outputTokens < 0) outputTokens = 0; }
        public int total() { return inputTokens + outputTokens; }
    }

    public record ModelUsage(long inputTokens, long outputTokens, double costUsd) {
        public long totalTokens() { return inputTokens + outputTokens; }
    }

    public record Summary(long totalInput, long totalOutput, double totalCostUsd, Map<String, ModelUsage> byModel) {}

    private final Map<String, double[]> priceTable = new LinkedHashMap<>(); // [inputPer1k, outputPer1k]
    private final Map<String, AtomicLong> input = new LinkedHashMap<>();
    private final Map<String, AtomicLong> output = new LinkedHashMap<>();
    private final AtomicLong totalInput = new AtomicLong();
    private final AtomicLong totalOutput = new AtomicLong();

    public CostTracker() { defaultPriceTable(); }

    /** list model ids known to this tracker (i.e.
     *  ones with a price entry). The desktop UI uses this to
     *  populate the model selector instead of hardcoding. */
    public java.util.List<String> knownModels() {
        return java.util.Collections.unmodifiableList(new java.util.ArrayList<>(priceTable.keySet()));
    }

    /** get the (input, output) price per 1k tokens for
     *  a model, or {@code null} if unknown. */
    public double[] priceFor(String model) {
        double[] p = priceTable.get(model);
        return p == null ? null : p.clone();
    }

    private void defaultPriceTable() {
        // USD per 1,000 tokens (input, output). Numbers are the public Claude prices as of mid-2026.
        setPrice("claude-sonnet-4-5", 0.003, 0.015);
        setPrice("claude-sonnet-4",   0.003, 0.015);
        setPrice("claude-opus-4",     0.015, 0.075);
        setPrice("claude-haiku-4-5",  0.001, 0.005);
        setPrice("claude-3-5-sonnet", 0.003, 0.015);
        setPrice("claude-3-5-haiku",  0.0008, 0.004);
        setPrice("claude-3-opus",     0.015, 0.075);
    }

    /** Override or add a price for a model id. Prices are USD per 1,000 tokens. */
    public CostTracker setPrice(String model, double inputPer1k, double outputPer1k) {
        priceTable.put(model, new double[]{inputPer1k, outputPer1k});
        input.computeIfAbsent(model, k -> new AtomicLong());
        output.computeIfAbsent(model, k -> new AtomicLong());
        return this;
    }

    /** Record one LLM call's usage. Thread-safe. */
    public synchronized void record(String model, Usage usage) {
        if (model == null || model.isBlank() || usage == null) return;
        input.computeIfAbsent(model, k -> new AtomicLong());
        output.computeIfAbsent(model, k -> new AtomicLong());
        input.get(model).addAndGet(usage.inputTokens());
        output.get(model).addAndGet(usage.outputTokens());
        totalInput.addAndGet(usage.inputTokens());
        totalOutput.addAndGet(usage.outputTokens());
    }

    /** Cumulative usage snapshot, broken down by model. */
    public Summary summary() {
        Map<String, ModelUsage> by = new LinkedHashMap<>();
        for (String m : input.keySet()) {
            long in = input.get(m).get();
            long out = output.get(m).get();
            by.put(m, new ModelUsage(in, out, costFor(m, in, out)));
        }
        return new Summary(totalInput.get(), totalOutput.get(),
                costForTotal(totalInput.get(), totalOutput.get()), by);
    }

    /** Per-message cost for a model. */
    public double costFor(String model, long inputTokens, long outputTokens) {
        double[] p = priceTable.get(model);
        if (p == null) return 0.0;
        return (inputTokens / 1000.0) * p[0] + (outputTokens / 1000.0) * p[1];
    }

    private double costForTotal(long in, long out) {
        // We can't easily sum per-model without re-walking; approximate by walking priceTable.
        double total = 0.0;
        for (String m : input.keySet()) {
            long mi = input.get(m).get();
            long mo = output.get(m).get();
            total += costFor(m, mi, mo);
        }
        return total;
    }

    /** Reset all counters — useful for "this session only" mode. */
    public synchronized void reset() {
        input.values().forEach(a -> a.set(0));
        output.values().forEach(a -> a.set(0));
        totalInput.set(0);
        totalOutput.set(0);
    }
}
