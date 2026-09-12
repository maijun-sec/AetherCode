package org.aethercode.memory;

import org.aethercode.memory.ProceduralMemory.Procedure;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.ToDoubleFunction;

/**
 * Paper 2606.06787 AdMem: Memory critic agent.
 * <p>
 * An independent agent that scores memory records and decides whether
 * to keep / merge / prune them. Production wires this to a real LLM
 * (via {@code ToDoubleFunction}); the default uses a heuristic scorer.
 */
public final class MemoryCriticAgent {

    /** Decision types. */
    public enum Decision { KEEP, MERGE, PRUNE }

    /** A verdict on one memory record. */
    public record Verdict(String id, Decision decision, double score, String reason) {
        public Verdict {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(decision, "decision");
            Objects.requireNonNull(reason, "reason");
        }
    }

    private final ToDoubleFunction<Object> scorer;
    private final double mergeThreshold;
    private final double pruneThreshold;

    public MemoryCriticAgent() {
        this(rec -> 0.5, 0.3, 0.1);
    }

    public MemoryCriticAgent(ToDoubleFunction<Object> scorer, double mergeThreshold, double pruneThreshold) {
        this.scorer = Objects.requireNonNull(scorer, "scorer");
        this.mergeThreshold = mergeThreshold;
        this.pruneThreshold = pruneThreshold;
    }

    /** Score a memory record (e.g. {@link Procedure}). */
    public Verdict evaluate(Object record, String id) {
        double score = scorer.applyAsDouble(record);
        Decision d;
        String reason;
        if (score < pruneThreshold) {
            d = Decision.PRUNE;
            reason = "score=" + score + " < prune threshold " + pruneThreshold;
        } else if (score < mergeThreshold) {
            d = Decision.MERGE;
            reason = "score=" + score + " < merge threshold " + mergeThreshold;
        } else {
            d = Decision.KEEP;
            reason = "score=" + score + " >= merge threshold";
        }
        return new Verdict(id, d, score, reason);
    }

    /** Batch evaluate. */
    public <T> List<Verdict> evaluateBatch(List<T> records, java.util.function.Function<T, String> idFn) {
        List<Verdict> out = new ArrayList<>();
        for (T r : records) {
            out.add(evaluate(r, idFn.apply(r)));
        }
        return out;
    }
}
