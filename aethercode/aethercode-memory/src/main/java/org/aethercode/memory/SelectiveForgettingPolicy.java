package org.aethercode.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.ToDoubleFunction;

/**
 * Paper 2608.28978 Selective Forgetting.
 * <p>
 * A 3-dimension forgetting policy that prunes memory records based on:
 * <ol>
 *   <li>Recency — older records decay</li>
 *   <li>Frequency — rarely-used records lose score</li>
 *   <li>Structural importance — graph centrality (if available)</li>
 * </ol>
 * <p>
 * The combined score is {@code recency * wR + frequency * wF + importance * wI}.
 * Records below the threshold are pruned.
 */
public final class SelectiveForgettingPolicy {

    /** A record with metadata needed for forgetting decisions. */
    public interface Forgettable {
        String id();
        long lastAccessedMs();
        int accessCount();
        double structuralImportance();
    }

    /** Default weights. */
    public record Weights(double recency, double frequency, double importance) {
        public Weights {
            double sum = recency + frequency + importance;
            if (Math.abs(sum - 1.0) > 0.001) {
                throw new IllegalArgumentException("weights must sum to 1.0, got " + sum);
            }
        }
    }

    private final Weights weights;
    private final double threshold;
    private final long nowMs;
    private final long decayHalfLifeMs;

    public SelectiveForgettingPolicy() {
        this(new Weights(0.4, 0.3, 0.3), 0.2, System.currentTimeMillis(), 30L * 24 * 3600 * 1000); // 30 days
    }

    public SelectiveForgettingPolicy(Weights weights, double threshold, long nowMs, long decayHalfLifeMs) {
        this.weights = Objects.requireNonNull(weights, "weights");
        this.threshold = threshold;
        this.nowMs = nowMs;
        this.decayHalfLifeMs = decayHalfLifeMs;
    }

    /** Score a record (higher = keep). */
    public double score(Forgettable record) {
        Objects.requireNonNull(record, "record");
        // Recency: 0..1, exp decay
        long age = Math.max(0, nowMs - record.lastAccessedMs());
        double recency = Math.exp(-(double) age / decayHalfLifeMs);
        // Frequency: normalized 0..1, log scale
        double frequency = 1.0 - 1.0 / (1.0 + Math.log(1.0 + record.accessCount()));
        // Importance: as-is, 0..1
        double importance = Math.max(0.0, Math.min(1.0, record.structuralImportance()));
        return weights.recency() * recency + weights.frequency() * frequency + weights.importance() * importance;
    }

    /** Return the IDs of records to keep (above threshold). */
    public <T extends Forgettable> List<T> prune(List<T> records) {
        Objects.requireNonNull(records, "records");
        List<T> kept = new ArrayList<>();
        for (T r : records) {
            if (score(r) >= threshold) {
                kept.add(r);
            }
        }
        return kept;
    }

    /** Return the top-K records by score. */
    public <T extends Forgettable> List<T> topK(List<T> records, int k) {
        Objects.requireNonNull(records, "records");
        List<T> sorted = new ArrayList<>(records);
        sorted.sort(Comparator.comparingDouble(this::score).reversed());
        if (sorted.size() > k) return sorted.subList(0, k);
        return sorted;
    }

    public Weights weights() { return weights; }
    public double threshold() { return threshold; }
}
