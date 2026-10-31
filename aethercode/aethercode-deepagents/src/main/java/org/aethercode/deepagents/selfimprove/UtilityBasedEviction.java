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

package org.aethercode.deepagents.selfimprove;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * R243.1 (O-3): the "drop the weakest units" eviction
 * policy. Keeps the bank under {@code maxUnits} by
 * discarding the entries with the lowest
 * <em>effective</em> utility at {@code now}, with
 * deterministic tie-breaks (oldest {@code createdAt}
 * first, then id) so two equally-weak units are not
 * always dropped in the same order.
 *
 * <h2>When to use</h2>
 *
 * <p>This is the right policy for a long-running agent
 * where utility is a meaningful signal: a unit that has not
 * been recalled and has been decayed to ~0 is safe to
 * drop, because it would not have been recalled anyway.
 * Combine with
 * {@link UtilityDecay#exponential(java.time.Duration)} so
 * the ranking respects the same time horizon.
 *
 * <h2>Eviction count</h2>
 *
 * <p>Eviction is exact: when the bank overflows by
 * {@code n} units, exactly {@code n} are dropped (modulo
 * the "keep at least one unit" safety check). If a
 * caller wants to amortise over larger gaps, the right
 * move is to add a periodic {@code evictIfNeeded()} call
 * from a scheduler, not to over-evict in a single round.
 */
public final class UtilityBasedEviction implements BankGrowthPolicy {

    private final int maxUnits;
    private final UtilityDecay decay;

    public UtilityBasedEviction(int maxUnits, UtilityDecay decay) {
        if (maxUnits <= 0) {
            throw new IllegalArgumentException("maxUnits must be > 0, got " + maxUnits);
        }
        if (decay == null) throw new IllegalArgumentException("decay must not be null");
        this.maxUnits = maxUnits;
        this.decay = decay;
    }

    public int maxUnits() { return maxUnits; }
    public UtilityDecay decay() { return decay; }

    @Override
    public List<ReasoningUnit> selectEvictions(int currentSize, List<ReasoningUnit> snapshot, Instant now) {
        int overflow = currentSize - maxUnits;
        if (overflow <= 0) return List.of();
        int target = overflow;
        if (target >= snapshot.size()) {
            // pathological: keep at least one unit around
            target = snapshot.size() - 1;
        }
        if (target <= 0) return List.of();
        Instant t = now == null ? Instant.now() : now;
        List<ReasoningUnit> sorted = new ArrayList<>(snapshot);
        sorted.sort(Comparator
                .comparingDouble((ReasoningUnit u) -> decay.effectiveUtility(u, t))
                .thenComparing(Comparator.comparing(ReasoningUnit::createdAt))
                .thenComparing(Comparator.comparing(ReasoningUnit::id)));
        return new ArrayList<>(sorted.subList(0, Math.min(target, sorted.size())));
    }
}
