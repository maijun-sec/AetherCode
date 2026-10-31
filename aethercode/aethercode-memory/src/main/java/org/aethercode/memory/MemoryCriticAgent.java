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
