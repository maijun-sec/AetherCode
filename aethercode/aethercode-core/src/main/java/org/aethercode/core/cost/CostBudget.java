package org.aethercode.core.cost;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * cost-budget enforcer. Wraps a {@link CostTracker} with a daily USD
 * cap (and optional per-call cap). {@link #tryAcquire} estimates the cost of
 * a planned LLM call and either grants the request or rejects it. After the
 * real call completes, {@link #record} updates the spent total.
 *
 * <p>Daily reset is keyed on {@link LocalDate} from a {@link Clock} so tests
 * can inject a fixed clock and avoid time-based flakes.
 */
public class CostBudget {

    private static final Logger LOG = LoggerFactory.getLogger(CostBudget.class);

    public enum Decision { ALLOW, DENY }

    public record Estimate(double estUsd, double remainingUsdAfter, Decision decision) {
        public boolean allowed() { return decision == Decision.ALLOW; }
    }

    public interface Listener {
        /** Called once when the daily cap is first exceeded. Not re-fired until the next day. */
        void onExceeded(String model, double estUsd, double dailyCap);
    }

    private final CostTracker tracker;
    private final double dailyCapUsd;
    private final double perCallCapUsd;
    private final Clock clock;
    private final List<Listener> listeners = new ArrayList<>();
    private final AtomicReference<String> lastDate = new AtomicReference<>();
    private final AtomicReference<Double> spentToday = new AtomicReference<>(0.0);
    private final AtomicReference<Boolean> exceededFlag = new AtomicReference<>(false);

    public CostBudget(CostTracker tracker, double dailyCapUsd) {
        this(tracker, dailyCapUsd, Double.POSITIVE_INFINITY, Clock.systemDefaultZone());
    }

    public CostBudget(CostTracker tracker, double dailyCapUsd, double perCallCapUsd) {
        this(tracker, dailyCapUsd, perCallCapUsd, Clock.systemDefaultZone());
    }

    public CostBudget(CostTracker tracker, double dailyCapUsd, double perCallCapUsd, Clock clock) {
        if (tracker == null) throw new IllegalArgumentException("tracker is null");
        if (dailyCapUsd <= 0) throw new IllegalArgumentException("dailyCapUsd must be > 0");
        if (perCallCapUsd <= 0) throw new IllegalArgumentException("perCallCapUsd must be > 0");
        if (clock == null) throw new IllegalArgumentException("clock is null");
        this.tracker = tracker;
        this.dailyCapUsd = dailyCapUsd;
        this.perCallCapUsd = perCallCapUsd;
        this.clock = clock;
        this.lastDate.set(LocalDate.now(clock).toString());
    }

    public CostBudget addListener(Listener l) {
        if (l != null) listeners.add(l);
        return this;
    }

    public double dailyCapUsd()   { return dailyCapUsd; }
    public double perCallCapUsd() { return perCallCapUsd; }

    /** Cumulative USD spent today (after the last reset). */
    public double spentToday() {
        rolloverIfNewDay();
        return spentToday.get();
    }

    /** USD remaining for today. {@code Double.POSITIVE_INFINITY} if cap disabled. */
    public double remainingToday() {
        return Math.max(0.0, dailyCapUsd - spentToday());
    }

    /**
     * Estimate the cost of a planned call and decide whether to allow it.
     * Does <b>not</b> record; use {@link #record} after the real call.
     */
    public Estimate tryAcquire(String model, int estInputTokens, int estOutputTokens) {
        rolloverIfNewDay();
        double est = tracker.costFor(model, estInputTokens, estOutputTokens);
        Decision d;
        if (est > perCallCapUsd) d = Decision.DENY;
        else if (spentToday.get() + est > dailyCapUsd) d = Decision.DENY;
        else d = Decision.ALLOW;
        double remainingAfter = Math.max(0.0, dailyCapUsd - (spentToday.get() + (d == Decision.ALLOW ? est : 0.0)));
        return new Estimate(est, remainingAfter, d);
    }

    /**
     * Record the actual usage of a completed LLM call. Safe to call after
     * either an allowed or denied tryAcquire.
     */
    public synchronized void record(String model, int inputTokens, int outputTokens) {
        rolloverIfNewDay();
        tracker.record(model, new CostTracker.Usage(inputTokens, outputTokens));
        double cost = tracker.costFor(model, inputTokens, outputTokens);
        double now = spentToday.get() + cost;
        spentToday.set(now);
        if (!exceededFlag.get() && now > dailyCapUsd) {
            exceededFlag.set(true);
            for (Listener l : listeners) {
                try { l.onExceeded(model, now, dailyCapUsd); }
                catch (Exception e) { LOG.warn("budget listener failed: {}", e.getMessage()); }
            }
        }
    }

    /**
     * Convenience: tryAcquire + record in one shot, but only records if
     * allowed. Throws {@link BudgetExceededException} if denied.
     */
    public void consume(String model, int inputTokens, int outputTokens) {
        Estimate e = tryAcquire(model, inputTokens, outputTokens);
        if (!e.allowed()) {
            throw new BudgetExceededException(
                    "cost budget exceeded: est $" + String.format("%.4f", e.estUsd())
                            + ", remaining today $" + String.format("%.4f", remainingToday())
                            + " (cap $" + dailyCapUsd + ")");
        }
        record(model, inputTokens, outputTokens);
    }

    /** reset spent-today counter (e.g. on test setup). */
    public synchronized void resetDay() {
        spentToday.set(0.0);
        exceededFlag.set(false);
        lastDate.set(LocalDate.now(clock).toString());
    }

    private void rolloverIfNewDay() {
        String today = LocalDate.now(clock).toString();
        if (!today.equals(lastDate.get())) {
            synchronized (this) {
                if (!today.equals(lastDate.get())) {
                    spentToday.set(0.0);
                    exceededFlag.set(false);
                    lastDate.set(today);
                }
            }
        }
    }
}
