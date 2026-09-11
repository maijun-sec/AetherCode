package org.aethercode.core.proc;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * an auto-restart policy with exponential backoff and a
 * jitter. Given a {@code RestartPolicy}, callers ask
 * {@link #nextDelay()} after each failure to get the next
 * backoff window. After a successful run, call
 * {@link #reset()} to clear the failure counter.
 */
public class RestartPolicy {

    public record Config(
            int maxAttempts,
            Duration initialBackoff,
            Duration maxBackoff,
            double multiplier,
            double jitter,
            boolean resetOnSuccess
    ) {
        public Config {
            if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
            if (initialBackoff == null || initialBackoff.isZero() || initialBackoff.isNegative())
                throw new IllegalArgumentException("initialBackoff must be positive");
            if (maxBackoff == null || maxBackoff.isNegative() || maxBackoff.isZero())
                throw new IllegalArgumentException("maxBackoff must be positive");
            if (multiplier < 1.0) throw new IllegalArgumentException("multiplier must be >= 1.0");
            if (jitter < 0.0 || jitter > 1.0) throw new IllegalArgumentException("jitter must be 0..1");
        }
    }

    public static Config defaults() {
        return new Config(5, Duration.ofSeconds(1), Duration.ofMinutes(1), 2.0, 0.25, true);
    }

    public static Config never() {
        return new Config(1, Duration.ofSeconds(1), Duration.ofSeconds(1), 1.0, 0.0, true);
    }

    private final Config config;
    private final LongSupplier clock;
    private final AtomicInteger failures = new AtomicInteger(0);

    public RestartPolicy() { this(defaults(), System::currentTimeMillis); }

    public RestartPolicy(Config config) { this(config, System::currentTimeMillis); }

    public RestartPolicy(Config config, LongSupplier clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Config config() { return config; }
    public int failureCount() { return failures.get(); }

    /** record a failure and return the backoff before the next attempt. */
    public Duration recordFailure() {
        int n = failures.incrementAndGet();
        if (n > config.maxAttempts()) {
            return Duration.ZERO; // exceeded max attempts — caller should stop
        }
        return computeBackoff(n);
    }

    /** get the backoff for a hypothetical Nth failure. */
    public Duration backoffFor(int attempt) {
        return computeBackoff(attempt);
    }

    private Duration computeBackoff(int attempt) {
        if (attempt < 1) return Duration.ZERO;
        double base = config.initialBackoff().toMillis() * Math.pow(config.multiplier(), attempt - 1);
        double capped = Math.min(config.maxBackoff().toMillis(), base);
        if (config.jitter() > 0) {
            double low = capped * (1.0 - config.jitter());
            double high = capped * (1.0 + config.jitter());
            capped = low + Math.random() * (high - low);
        }
        return Duration.ofMillis(Math.max(0, (long) capped));
    }

    /** record a success and (optionally) reset the failure counter. */
    public void recordSuccess() {
        if (config.resetOnSuccess()) {
            failures.set(0);
        }
    }

    public void reset() { failures.set(0); }

    public boolean shouldRetry() {
        return failures.get() < config.maxAttempts();
    }

    public int remainingAttempts() {
        return Math.max(0, config.maxAttempts() - failures.get());
    }
}
