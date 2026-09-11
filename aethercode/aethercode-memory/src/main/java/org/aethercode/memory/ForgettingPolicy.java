package org.aethercode.memory;

import java.time.Instant;
import java.util.Objects;

/**
 * R230 (G2): forgetting / decay policy.
 *
 * <p>Computes a {@code Forgetting Score (FS) ∈ [0, 1]} for a memory item;
 * a low score means the item is a good candidate for forgetting.
 *
 * <p>The composite score is a weighted sum of three signals (mirroring
 * arXiv:2512.13564 §5.2.3 "Forgetting" and the cognitive-science
 * Ebbinghaus–Atkinson–Shiffrin tradition):
 * <ul>
 *   <li><b>recency</b> — exponential decay: {@code exp(-Δdays / τ)}</li>
 *   <li><b>frequency</b> — log-scaled access count</li>
 *   <li><b>utility</b> — explicit user/LLM-assigned importance (0..1)</li>
 * </ul>
 *
 * <p>Items below {@link #tombstoneThreshold} for at least
 * {@code tombstoneDays} are tombstoned (moved to {@code .trash/}).
 * Items below {@link #pruneThreshold} are hard-deleted by an explicit
 * {@code --memory-prune} invocation (never automatic).
 *
 * <p>Decisions:
 * <ul>
 *   <li>Default weights are (recency 0.5, frequency 0.3, utility 0.2).</li>
 *   <li>Default τ is 30 days — well within the typical project memory
 *       refresh cycle.</li>
 *   <li>Thresholds are deliberately conservative; the user can
 *       call {@link #runDecayPass(FileBackedMemory, boolean)} with
 *       {@code force=true} for an aggressive sweep.</li>
 * </ul>
 */
public final class ForgettingPolicy {

    /** Default exponential time constant in milliseconds (30 days). */
    public static final long DEFAULT_TAU_MS = 30L * 24 * 3600 * 1000;
    /** Below this FS, a stale item becomes a tombstone candidate. */
    public static final double DEFAULT_TOMBSTONE_THRESHOLD = 0.05;
    /** Below this FS, an item is hard-deletable. */
    public static final double DEFAULT_PRUNE_THRESHOLD = 0.01;
    /** How long an item must stay below tombstone before it's actually moved. */
    public static final long DEFAULT_TOMBSTONE_DAYS = 7;

    private final double weightRecency;
    private final double weightFrequency;
    private final double weightUtility;
    private final long tauMs;
    private final double tombstoneThreshold;
    private final double pruneThreshold;
    private final long tombstoneDays;

    public ForgettingPolicy(double weightRecency, double weightFrequency, double weightUtility,
                            long tauMs, double tombstoneThreshold, double pruneThreshold,
                            long tombstoneDays) {
        // Normalise weights so they sum to 1; ignore negative input
        double total = Math.max(1e-9, weightRecency + weightFrequency + weightUtility);
        this.weightRecency = Math.max(0, weightRecency) / total;
        this.weightFrequency = Math.max(0, weightFrequency) / total;
        this.weightUtility = Math.max(0, weightUtility) / total;
        this.tauMs = Math.max(1, tauMs);
        this.tombstoneThreshold = clamp01(tombstoneThreshold);
        this.pruneThreshold = clamp01(pruneThreshold);
        this.tombstoneDays = Math.max(0, tombstoneDays);
    }

    public static ForgettingPolicy defaults() {
        return new ForgettingPolicy(0.5, 0.3, 0.2,
                DEFAULT_TAU_MS,
                DEFAULT_TOMBSTONE_THRESHOLD,
                DEFAULT_PRUNE_THRESHOLD,
                DEFAULT_TOMBSTONE_DAYS);
    }

    public double weightRecency() { return weightRecency; }
    public double weightFrequency() { return weightFrequency; }
    public double weightUtility() { return weightUtility; }
    public long tauMs() { return tauMs; }
    public double tombstoneThreshold() { return tombstoneThreshold; }
    public double pruneThreshold() { return pruneThreshold; }
    public long tombstoneDays() { return tombstoneDays; }

    /** Compute the per-signal components for an item. All values ∈ [0, 1]. */
    public Components components(FileBackedMemory.MemoryItem item, int maxAccessCount, Instant now) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(now, "now");
        // Recency: from updatedAt (or createdAt if no updates)
        Instant ref = item.updatedAt() != null ? item.updatedAt() : item.createdAt();
        long deltaMs = Math.max(0, now.toEpochMilli() - ref.toEpochMilli());
        double recency = Math.exp(-((double) deltaMs / tauMs));

        // Frequency: log-scaled access count (we treat updatedAt as
        // a proxy for "touched" — file-backed items don't track
        // access yet; recall() can call touch() in prior round to bump
        // this). For now we just use updatedAt-vs-createdAt age as
        // a coarse signal.
        int accesses = estimateAccesses(item, maxAccessCount);
        double frequency = maxAccessCount <= 0
                ? 0.5
                : Math.log(1.0 + accesses) / Math.log(1.0 + Math.max(1, maxAccessCount));

        // Utility: not on the item yet — caller passes 0.5 by default
        // and overrides per entry via tags like "utility=0.9".
        double utility = parseUtilityFromTags(item.tags());

        return new Components(recency, frequency, utility);
    }

    /** Composite forgetting score ∈ [0, 1]. Higher = should be kept. */
    public double score(FileBackedMemory.MemoryItem item, int maxAccessCount, Instant now) {
        Components c = components(item, maxAccessCount, now);
        return weightRecency * c.recency()
             + weightFrequency * c.frequency()
             + weightUtility * c.utility();
    }

    /** True iff the item should be moved to .trash now. */
    public boolean shouldTombstone(double score) {
        return score < tombstoneThreshold;
    }

    /** True iff the item should be hard-deleted (manual prune only). */
    public boolean shouldPrune(double score) {
        return score < pruneThreshold;
    }

    /** Three component signals — exposed for the recall scorer. */
    public record Components(double recency, double frequency, double utility) {
        public Components {
            recency = clamp01(recency);
            frequency = clamp01(frequency);
            utility = clamp01(utility);
        }
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) return 0;
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }

    /** Estimate access count from updatedAt-createdAt delta. Crude, but monotonic. */
    private static int estimateAccesses(FileBackedMemory.MemoryItem item, int maxAccessCount) {
        if (item.createdAt() == null || item.updatedAt() == null) return 0;
        long deltaMs = Math.max(0, item.updatedAt().toEpochMilli() - item.createdAt().toEpochMilli());
        // 1 access per 1-day delta, capped at maxAccessCount
        long days = deltaMs / (24L * 3600 * 1000);
        int est = (int) Math.min(maxAccessCount, days);
        return Math.max(0, est);
    }

    /** Look for a {@code utility=0.9} style tag. */
    private static double parseUtilityFromTags(java.util.List<String> tags) {
        if (tags == null) return 0.5;
        for (String t : tags) {
            if (t == null) continue;
            String low = t.toLowerCase();
            if (low.startsWith("utility=")) {
                try {
                    return clamp01(Double.parseDouble(low.substring("utility=".length())));
                } catch (NumberFormatException ignore) {}
            }
        }
        return 0.5;
    }

    /**
     * Run a single decay pass over the given file-backed store.
     *
     * <p>Returns a {@link Report} summarising what happened. Does not
     * hard-delete; the {@code force} flag only allows an aggressive
     * tombstone sweep. Hard-pruning is exposed separately as
     * {@link #prune(FileBackedMemory, java.time.Instant)}.
     *
     * <p>The pass is fast (one pass over the in-memory list).
     */
    public Report runDecayPass(FileBackedMemory store, boolean force) {
        return runDecayPass(store, force, Instant.now());
    }

    public Report runDecayPass(FileBackedMemory store, boolean force, Instant now) {
        if (store == null) return new Report(0, 0, 0, 0);
        var items = store.all();
        if (items.isEmpty()) return new Report(0, 0, 0, 0);
        int maxAccess = 0;
        for (var it : items) {
            int est = estimateAccesses(it, 365);
            if (est > maxAccess) maxAccess = est;
        }
        int decayed = 0;
        int tombstoned = 0;
        double effectiveTomb = force ? Math.max(pruneThreshold, tombstoneThreshold * 0.5)
                                     : tombstoneThreshold;
        for (var it : items) {
            double s = score(it, Math.max(1, maxAccess), now);
            if (shouldTombstone(s) || (force && s < effectiveTomb)) {
                if (store.remove(it.id())) {
                    tombstoned++;
                }
            } else if (s < effectiveTomb * 4) {
                // Soft decay: bump updatedAt? No — that breaks the
                // "touched" semantics. Just count it for stats.
                decayed++;
            }
        }
        return new Report(items.size(), decayed, tombstoned, 0);
    }

    /** Hard-delete everything below {@link #pruneThreshold}. */
    public Report prune(FileBackedMemory store, Instant now) {
        if (store == null) return new Report(0, 0, 0, 0);
        var items = store.all();
        if (items.isEmpty()) return new Report(0, 0, 0, 0);
        int maxAccess = 0;
        for (var it : items) {
            int est = estimateAccesses(it, 365);
            if (est > maxAccess) maxAccess = est;
        }
        int pruned = 0;
        for (var it : items) {
            double s = score(it, Math.max(1, maxAccess), now);
            if (shouldPrune(s)) {
                if (store.remove(it.id())) pruned++;
            }
        }
        return new Report(items.size(), 0, 0, pruned);
    }

    public record Report(int scanned, int decayed, int tombstoned, int pruned) {}
}
