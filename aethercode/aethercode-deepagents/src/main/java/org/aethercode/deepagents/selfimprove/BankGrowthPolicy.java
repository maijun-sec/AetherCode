package org.aethercode.deepagents.selfimprove;

import java.util.List;

/**
 * R243.1 (O-3): the "stop the bank from growing without
 * bound" policy. Inspired by the explicit
 * "memory consolidation / bounded growth" concerns in the
 * AI Agent memory survey (arXiv:2512.13564 §5.2.4), and by
 * the same problem the ReasoningBank 2025 paper flags in
 * §4.2.2 — "without a cap, the bank grows with every
 * failure and eventually dominates the recall slot".
 *
 * <h2>Trigger</h2>
 *
 * <p>The {@link ReasoningBank} calls
 * {@link #selectEvictions(int, List, java.time.Instant)}
 * after every {@link ReasoningBank#add(ReasoningUnit)}, with
 * the current size and a snapshot of the bank. The policy
 * returns the {@code ReasoningUnit}s that should be removed
 * (or an empty list to keep the bank untouched).
 *
 * <h2>Why a policy, not just a {@code maxUnits} int</h2>
 *
 * <p>Different deployments want different rules:
 *
 * <ul>
 *   <li>A long-running offline pipeline wants
 *       {@link NoGrowthCap} (unlimited growth — the bank
 *       is its whole history).</li>
 *   <li>A chat assistant wants {@link UtilityBasedEviction}
 *       (drop the strategies that nobody has touched and
 *       that score lowest after decay).</li>
 *   <li>A low-latency edge device wants
 *       {@link LruEviction} (drop whatever has not been
 *       used in the longest time).</li>
 * </ul>
 *
 * <p>Splitting the rule out keeps the bank's call site a
 * single line and makes the policy unit-testable in
 * isolation.
 */
public interface BankGrowthPolicy {

    /**
     * Decide which units (if any) should be removed from the
     * bank. Called after every add with the post-add size
     * and a snapshot of the bank.
     *
     * @param currentSize the bank's size <em>after</em> the
     *        just-completed add.
     * @param snapshot    the bank's current units, in
     *        insertion order.
     * @param now         the time the add happened (for
     *        policies that look at age).
     * @return the units to remove. The list may be empty
     *         (no eviction) or contain any number of
     *         entries (the bank will call
     *         {@link BankStorage#remove} for each id).
     */
    List<ReasoningUnit> selectEvictions(int currentSize, List<ReasoningUnit> snapshot, java.time.Instant now);

    /** The no-op policy: the bank is allowed to grow without
     *  bound. Default. */
    BankGrowthPolicy NO_GROWTH_CAP = (currentSize, snapshot, now) -> List.of();
}
