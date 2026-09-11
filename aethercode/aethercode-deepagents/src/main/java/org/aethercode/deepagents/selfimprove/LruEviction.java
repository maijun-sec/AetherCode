package org.aethercode.deepagents.selfimprove;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * R243.1 (O-3): the "drop the least-recently-touched units"
 * eviction policy. Uses an explicit
 * {@code lastTouchedAt} map the bank maintains alongside
 * its in-memory index (the map is in-memory only — the
 * schema stays compatible with the on-disk JSON, which is
 * why {@code lastTouchedAt} is not a field of
 * {@link ReasoningUnit}).
 *
 * <h2>When to use</h2>
 *
 * <p>This is the right policy for an interactive agent
 * where utility scores are not as meaningful as "what has
 * the user actually been doing lately". A unit that has
 * not been touched in 30 days is unlikely to be useful
 * tomorrow, regardless of its score.
 *
 * <h2>Tie-breaks</h2>
 *
 * <p>Units that have never been touched fall back to
 * {@link ReasoningUnit#createdAt()} as the timestamp; this
 * keeps freshly-loaded units (where the in-memory LRU
 * index is empty) ranked alongside the long-resident
 * ones.
 *
 * <h2>Eviction count</h2>
 *
 * <p>Eviction is exact: when the bank overflows by
 * {@code n} units, exactly {@code n} are dropped. The
 * lastTouchedAt index is in-memory only, so over-eviction
 * would silently destroy persisted data; exact counts keep
 * the policy debuggable.
 */
public final class LruEviction implements BankGrowthPolicy {

    private final int maxUnits;

    public LruEviction(int maxUnits) {
        if (maxUnits <= 0) {
            throw new IllegalArgumentException("maxUnits must be > 0, got " + maxUnits);
        }
        this.maxUnits = maxUnits;
    }

    public int maxUnits() { return maxUnits; }

    @Override
    public List<ReasoningUnit> selectEvictions(int currentSize, List<ReasoningUnit> snapshot, Instant now) {
        // Defer to the (currentSize, snapshot, lastTouched)
        // overload. The interface contract says {@code now}
        // is for policies that need a clock; we use
        // {@code createdAt} as the LRU fallback instead, so
        // we ignore {@code now} here and pass a null map so
        // every unit falls back to its createdAt.
        int overflow = currentSize - maxUnits;
        if (overflow <= 0) return List.of();
        int target = overflow;
        if (target >= snapshot.size()) {
            target = snapshot.size() - 1;
        }
        if (target <= 0) return List.of();
        List<ReasoningUnit> sorted = new ArrayList<>(snapshot);
        sorted.sort(Comparator
                .comparing((ReasoningUnit u) -> u.createdAt())
                .thenComparing(Comparator.comparing(ReasoningUnit::id)));
        return new ArrayList<>(sorted.subList(0, Math.min(target, sorted.size())));
    }

    /**
     * Hook the bank uses to feed the policy the live
     * "last touched" map. {@code lastTouched} is a snapshot
     * of the bank's in-memory index; if a unit has no
     * entry, its {@code createdAt} is used instead.
     */
    public List<ReasoningUnit> selectEvictions(int currentSize, List<ReasoningUnit> snapshot,
                                                Map<String, Instant> lastTouched) {
        int overflow = currentSize - maxUnits;
        if (overflow <= 0) return List.of();
        int target = overflow;
        if (target >= snapshot.size()) {
            target = snapshot.size() - 1;
        }
        if (target <= 0) return List.of();
        List<ReasoningUnit> sorted = new ArrayList<>(snapshot);
        sorted.sort(Comparator
                .comparing((ReasoningUnit u) -> touchTime(u, lastTouched))
                .thenComparing(Comparator.comparing(ReasoningUnit::id)));
        return new ArrayList<>(sorted.subList(0, Math.min(target, sorted.size())));
    }

    private static Instant touchTime(ReasoningUnit u, Map<String, Instant> lastTouched) {
        if (lastTouched != null) {
            Instant t = lastTouched.get(u.id());
            if (t != null) return t;
        }
        return u.createdAt();
    }
}
