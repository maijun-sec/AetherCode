package org.aethercode.deepagents.selfimprove;

import java.time.Instant;
import java.util.List;

/**
 * R243.1 (O-3): the explicit "no cap" policy. Returns an
 * empty eviction list for every call. Kept as a separate
 * class (rather than just a static field) so that
 * {@code @FunctionalInterface}-style usage and the
 * {@link #selectEvictions} contract both compile cleanly
 * when this is referenced by name in a constructor.
 */
public final class NoGrowthCap implements BankGrowthPolicy {

    public static final NoGrowthCap INSTANCE = new NoGrowthCap();

    public NoGrowthCap() {}

    @Override
    public List<ReasoningUnit> selectEvictions(int currentSize, List<ReasoningUnit> snapshot, Instant now) {
        return List.of();
    }
}
